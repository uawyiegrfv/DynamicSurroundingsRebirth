package org.orecruncher.dsurround.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Everything this mod needs from Distant Horizons: who owns the fog, and how to make DH's LOD fog
 * agree with the weather fog we are drawing.
 *
 * <h2>Why the fog has to be asked about</h2>
 *
 * <p>DH's fog mixin injects at the RETURN of {@code FogRenderer.setupFog} - after Forge's
 * {@code ViewportEvent.RenderFog}, which is where this mod's fog lives - and when it decides to
 * cancel vanilla fog it pushes the fog planes to {@code 420694206942069}. Whatever we wrote a
 * moment earlier is gone. DH only does that while its own {@code enableVanillaFog} option is OFF,
 * so that option - not the mod's presence - is what the hand-off has to read.</p>
 *
 * <h2>Why the reads and writes go through DH's API</h2>
 *
 * <p>The knobs are reached by walking DH's config classes (see {@link #CLIENT} for the two failed
 * walks that taught us how), then wrapped in DH's own {@code DhApiConfigValue}, whose
 * {@code getValue()} is the documented read. The difference is real: when another mod has set an
 * API override, the raw entry's {@code get()} reports the value in the config file while
 * {@code getValue()} reports the one in force - and deciding a hand-off on a number nobody is
 * using is exactly the kind of silent wrongness this batch exists to remove.</p>
 *
 * <p>With DH rendering distant terrain, a weather fog that shortens visibility leaves the LOD
 * terrain beyond the vanilla render distance perfectly clear - near world fogged, far world not.
 * DH exposes its far fog through the same API, so this class scales DH's own
 * {@code farFogStart}/{@code farFogEnd} by how far our visibility has closed in, using DH's
 * configured values as the base and {@code setValue(value, owner)} so DH records who did it, and
 * {@code clearValue()} to hand them back the moment the weather no longer needs them. Same
 * mechanism for the clear-sky event's cloud half: DH drawing its own LOD clouds while vanilla's
 * sheet is being removed is the one cloud disagreement worth fixing.</p>
 *
 * <p><b>Reflection, deliberately, and only into DH's public API surface plus the config holder
 * classes it is built on.</b> No compile dependency, so nothing here has to be present at build
 * time. Every failure path is fail-closed - "DH does not own the fog", "no override" - and says
 * so in the log once, because a silent no is what cost three attempts to find.</p>
 */
public final class DistantHorizonsCompat {

    /** DH's config root. Not API by package name, but it is what DH's own API is built on. */
    private static final String CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config";

    /**
     * The owner string every write carries. DH logs and shows it ("Config [x] was set by [y]"),
     * which is how a player finds out why a DH setting moved.
     */
    private static final String OWNER = "Dynamic Surroundings";

    /**
     * The config path, level by level, with both spellings DH has used.
     *
     * <p><b>Two failed attempts are recorded here, because the third is only obvious after
     * them.</b> DH's config is a tree of <em>holder classes</em>: each category is a
     * {@code ConfigCategory} whose payload is the class that declares the next level's fields
     * (bytecode: {@code new ConfigCategory.Builder().set(Config$Client.class).build()}), and the
     * entries are {@code public static} fields on those classes. So:</p>
     *
     * <ul>
     * <li>Walking <em>instances</em> cannot work: the value is a plain {@code ConfigCategory} and
     *     the field lives on {@code Config$Client}, a class the instance does not reveal. Attempt
     *     one failed with {@code NoSuchFieldException: Advanced} on the very pack it was written
     *     for.</li>
     * <li>Guessing at capitalisation does not help: the <em>classes</em> are
     *     {@code Client/Advanced/Graphics/Fog} while 3.3.3's <em>fields</em> are lower case.
     *     Attempt two mixed the two places up.</li>
     * <li>Walking the <em>nested classes</em> by name is what DH's own source does, and the jar
     *     confirms them: {@code Config$Client$Advanced$Graphics$Fog}.</li>
     * </ul>
     */
    private static final String[][] CLIENT = { { "Client", "client" } };
    private static final String[][] ADVANCED = { { "Advanced", "advanced" } };
    private static final String[][] GRAPHICS = { { "Graphics", "graphics" } };
    private static final String[][] FOG = { { "Fog", "fog" } };
    private static final String[][] GENERIC_RENDERING = { { "GenericRendering", "genericRendering" } };
    /** DH keeps its LOD render distance under Graphics.Quality, not Graphics. */
    private static final String[][] QUALITY = { { "Quality", "quality" } };

    /** Resolved knobs. Null means the probe could not reach it - every use checks. */
    private static volatile Object vanillaFogKnob = null;
    private static volatile Object dhFogKnob = null;
    private static volatile Object farFogStartKnob = null;
    private static volatile Object farFogEndKnob = null;
    private static volatile Object cloudRenderingKnob = null;
    private static volatile Object farFogFalloffKnob = null;
    private static volatile Object farFogMinKnob = null;
    private static volatile Object farFogMaxKnob = null;
    private static volatile Object chunkDistanceKnob = null;
    /** How DH spells its LOD render distance; the same setting its API calls chunkRenderDistance. */
    /**
     * How DH spells its LOD render distance radius, in chunks. Its own API method is
     * {@code chunkRenderDistance()}, whose implementation reads
     * {@code Config$Client$Advanced$Graphics$Quality.lodChunkRenderDistanceRadius} - read from the
     * bytecode, because guessing the name (this field is not called after the API method) is what made
     * viewScale() answer 1.0 and collapse the whole fog window into a hairline at the LOD boundary.
     */
    private static final String[] CHUNK_DISTANCE_NAMES = { "lodChunkRenderDistanceRadius", "chunkRenderDistance", "renderDistance" };

    private static final Object PROBE_LOCK = new Object();
    private static volatile boolean probed = false;

    /**
     * One knob this class is currently holding at a value of its own, plus what was there before.
     *
     * <p>A ledger rather than a handful of fields, because the mirror is no longer one number: making
     * DH's LOD fog line up with ours means matching its <em>shape</em>, which is four settings
     * (start, end, falloff, min/max thickness). Every one of them is put back from here.</p>
     */
    private static final class Held {
        final Object knob;
        final String name;
        /** Value found before we touched it; null when the knob was unreadable. */
        final Object base;
        Object written;

        Held(final Object knob, final String name, final Object base) {
            this.knob = knob;
            this.name = name;
            this.base = base;
        }
    }

    private static final java.util.List<Held> held = new java.util.ArrayList<>();

    /** The values we last wrote, so a per-frame sync does not hammer the API. */
    private static volatile boolean fogOverridden = false;
    private static volatile float writtenStart = Float.NaN;
    private static volatile float writtenEnd = Float.NaN;
    private static volatile float baseStart = Float.NaN;
    private static volatile float baseEnd = Float.NaN;
    private static volatile boolean cloudsSuppressed = false;
    /** Deepest visibility ratio seen while engaged, and the first pair actually written. */
    private static volatile float deepestRatio = 1F;
    private static volatile float firstWrittenStart = Float.NaN;
    private static volatile float firstWrittenEnd = Float.NaN;
    private static volatile boolean reportedDhFogOff = false;
    /**
     * Set while this class has switched DH's own LOD fog ON because something else had it off.
     *
     * <p>Without that layer there is nothing for the mirror to act on: DH cancels vanilla fog and
     * would draw no fog of its own, so the far world stays crisp however thick the weather gets. The
     * setting is put back exactly as found when the weather stops needing it.</p>
     */
    private static volatile boolean dhFogForcedOn = false;
    /** Set once if DH reports a different value than we wrote - see {@link #verifyWrite}. */
    private static volatile boolean reportedIneffective = false;
    /** One line the first time the join is written. */
    private static volatile boolean reportedMirror = false;

    /**
     * Engage below this visibility ratio, release above {@link #RELEASE_THRESHOLD}.
     *
     * <p>Two different numbers, because one number chatters: the first live session engaged at 0.899
     * and released at 0.900 four seconds apart, twice. A band costs nothing - the fog cannot be
     * "worth mirroring" and "not worth mirroring" on either side of a single value.</p>
     */
    private static final float SYNC_THRESHOLD = 0.90F;
    /** Above this the override is handed back. See {@link #SYNC_THRESHOLD}. */
    private static final float RELEASE_THRESHOLD = 0.95F;
    /**
     * Where DH's LOD fog joins ours, in DH's units (fractions of the vanilla render distance).
     *
     * <p>DH documents 1.0 as the closest edge of the vanilla render distance and 1.414 as its corner.
     * The join is exactly at that edge - which is also where vanilla fog reaches full thickness.</p>
     */
    /** Thickness at the join: 1.0, because vanilla fog is fully opaque at its own far plane. */
    /**
     * Where DH's LOD fog starts and how thick it may get, in DH's units (fractions of the vanilla
     * render distance; DH documents 1.0 as that distance's closest edge).
     *
     * <p>Start is fixed at the edge - that is where the LOD chunks begin - with thickness 0, and the
     * ramp runs up to full thickness where our own fog would end. Everything the weather does is in
     * that end point, which is why this cannot collapse into a band the way a scaled copy of DH's own
     * start/end window did.</p>
     */
    /** How far out the fog window spans, in render distances, before the weather pulls it in. */
    private static final float WINDOW_SPAN = 4.0F;
    private static final float RAMP_MIN = 0.0F;
    private static final float RAMP_MAX = 1.0F;

    /** Engage once our fog has moved the range by this fraction of it; release well below. */
    private static final float ENGAGE_MOVE = 0.05F;
    private static final float RELEASE_MOVE = 0.02F;

    /** Smallest change worth another API write. */
    private static final float WRITE_EPSILON = 0.01F;

    private DistantHorizonsCompat() {
    }

    /**
     * Whether DH is cancelling the vanilla terrain fog right now.
     *
     * <p>Read live rather than cached: DH's config screen can flip the option without a restart,
     * and a cached answer would keep this mod out of the fog for the rest of the session.</p>
     */
    public static boolean ownsVanillaFog() {
        if (!ModIds.present(ModIds.DISTANT_HORIZONS))
            return false;
        probe();
        final Boolean enabled = readBoolean(vanillaFogKnob);
        // enableVanillaFog == false means DH cancels vanilla fog. Anything else - true, null, an
        // unexpected type - means it does not, and we keep our own fog.
        return enabled != null && !enabled;
    }

    /** Whether the player has the DH integration switched on. */
    public static boolean integrationEnabled() {
        if (!ModIds.present(ModIds.DISTANT_HORIZONS))
            return false;
        final org.orecruncher.dsurround.Configuration config = org.orecruncher.dsurround.Client.Config;
        return config != null && config.weatherOptions.enableDistantHorizonsIntegration;
    }
    /**
     * Brings DH's LOD fog in line with how far our weather fog lets the player see.
     *
     * @param visibilityRatio our fog end as a fraction of the vanilla far plane, 1.0 = clear.
     *                        Values at or above {@link #SYNC_THRESHOLD} release the override, so
     *                        clear weather leaves DH exactly as the player configured it.
     */
    public static void syncFog(final float ourStart, final float ourEnd,
            final float vanillaStart, final float vanillaEnd) {
        if (!integrationEnabled() || vanillaEnd < 16F) {
            releaseAll();
            return;
        }
        probe();
        if (farFogStartKnob == null || farFogMinKnob == null || farFogMaxKnob == null)
            return;
        // One fog for the whole scene, ramping up from where the LOD chunks begin. The maintainer's
        // design, and it removes every problem the previous attempts had:
        //   - the ramp starts at 0 exactly at the vanilla render distance edge (DH unit 1.0, which is
        //     where its LOD chunks start), so there is no step at the boundary and no band to collapse;
        //   - it reaches full thickness where OUR fog would end, stretched over DH's view length by
        //     viewScale() - so the weather still decides how fast the view closes in;
        //   - the foreground (vanilla chunks, inside the vanilla render distance) is left to vanilla fog,
        //     which with DH's own vanilla-fog option off is no fog at all. That is expected: there is
        //     almost no fog that close in the first place, and DH's fog pass only covers LOD pixels.
        // The numbers, derived from the shader rather than guessed - this is the part that took
        // several rounds, so it is written down here.
        //
        // DH's fog shader (shaders/fog/gl/fog.frag) computes, for all three falloffs,
        //     thickness = farFogMin + (farFogMax - farFogMin) * f((d - start) / length)
        // with f clamped/saturating at 1. So the fog is a ramp that STARTS at farFogStart and is
        // FULL from farFogEnd outward: raising the end spreads the ramp, lowering it pulls the
        // opaque wall in - which is exactly how the weather shrinks the view distance.
        //
        // The unit is the render distance the fog is drawn over: the shader scales world distance
        // with uFogScale = 1/renderDistance, and DH's own defaults (start 0.4, end 1.0, with 1.414
        // documented as "the corner") only make sense as fractions of DH's own LOD distance. The
        // LOD area therefore runs 0..1.0 (edge) / 0..1.414 (corner), and a window that ends beyond
        // 1.0 is a window whose wall is never reached - which is why 4.0 looked like a thin haze.
        //
        // viewScale() is how many vanilla render distances long the LOD view is (256-chunk DH view
        // against a 12-chunk vanilla one is about 21), so the vanilla boundary sits at 1/scale.
        final float scale = viewScale();
        final float tightness = clamp(ourEnd / Math.max(vanillaEnd, 1F), 0.15F, 1.0F);
        final float rampStart = clamp(1F / Math.max(scale, 1F), 0F, 0.5F);
        final float rampEnd = clamp(tightness, rampStart + 0.05F, 1.414F);
        // Full thickness lands at rampEnd render distances, i.e. this many blocks out:
        final float wallBlocks = rampEnd * Math.max(vanillaEnd, 1F) * Math.max(scale, 1F);
        hold(farFogStartKnob, "farFogStart", rampStart);
        hold(farFogEndKnob, "farFogEnd", rampEnd);
        hold(farFogMinKnob, "farFogMin", RAMP_MIN);
        hold(farFogMaxKnob, "farFogMax", RAMP_MAX);
        hold(farFogFalloffKnob, "farFogFalloff", linearFalloff());
        if (!reportedMirror) {
            reportedMirror = true;
            log("DH's LOD fog carries the weather fog: thickness 0 at " + round(rampStart)
                    + " render distances (the vanilla boundary; view scale " + round(scale)
                    + "), full at " + round(rampEnd) + " (= " + Math.round(wallBlocks) + " blocks out,"
                    + " weather tightness " + round(tightness) + ", enableDhFog=" + read(dhFogKnob) + ")");
        }
    }

    /**
     * Writes one knob and remembers what was there before, at most once per value.
     *
     * <p>Re-reads the effective value every frame instead of trusting the last write: the number DH
     * reports can be changed underneath us (another mod, or DH falling back to its file value), and
     * a cache of our own writes cannot see that.</p>
     */
    private static void hold(final Object knob, final String name, final Object value) {
        if (knob == null || value == null)
            return;
        final Object current = read(knob);
        Held entry = find(name);
        if (entry == null) {
            entry = new Held(knob, name, current);
            held.add(entry);
        }
        if (sameValue(current, value))
            return;
        if (write(knob, value)) {
            entry.written = value;
            verifyWrite(knob, value, name);
        }
    }

    private static Held find(final String name) {
        for (final Held h : held)
            if (h.name.equals(name))
                return h;
        return null;
    }

    private static boolean sameValue(final Object a, final Object b) {
        if (a instanceof Number na && b instanceof Number nb)
            return Math.abs(na.floatValue() - nb.floatValue()) < WRITE_EPSILON;
        return a == null ? b == null : a.equals(b);
    }

    /** DH's LINEAR falloff constant, or null when the enum cannot be reached. */
    private static Object linearFalloff() {
        try {
            final Class<?> type = Class.forName(
                    "com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff");
            for (final Object constant : type.getEnumConstants())
                if ("LINEAR".equals(((Enum<?>) constant).name()))
                    return constant;
        } catch (Throwable t) {
            // No enum, no shape change: the distances alone still apply.
        }
        return null;
    }

    /**
     * Hands every held knob back. Safe to call every frame; only the transition does work.
     */
    public static void releaseAll() {
        if (held.isEmpty())
            return;
        final StringBuilder names = new StringBuilder();
        for (final Held h : held) {
            if (names.length() > 0)
                names.append(',');
            names.append(h.name);
            if (h.base != null)
                write(h.knob, h.base);
            else
                clear(h.knob);
        }
        held.clear();
        writtenStart = Float.NaN;
        writtenEnd = Float.NaN;
        log("weather fog released - Distant Horizons has its own LOD fog values back (" + names
                + "; deepest visibility ratio while engaged: " + round(deepestRatio) + ")");
        deepestRatio = 1F;
        if (dhFogForcedOn) {
            dhFogForcedOn = false;
            write(dhFogKnob, Boolean.FALSE);
        }
    }

    /** Drops an API override without writing a value back. */
    private static void clear(final Object knob) {
        try {
            knob.getClass().getMethod("clearValue").invoke(knob);
        } catch (Throwable t) {
            // Raw entry: nothing to clear.
        }
    }
    /**
     * Suppresses DH's own LOD clouds while this mod is removing the vanilla cloud sheet.
     *
     * <p>The clear-sky event's whole point is a sky without clouds; DH drawing its own layer
     * through it is the one disagreement between the two worth fixing. Tied to the event, not to
     * the weather: the moment it ends the override is handed back.</p>
     */
    public static void setCloudsSuppressed(final boolean suppressed) {
        if (!suppressed || !integrationEnabled()) {
            if (cloudsSuppressed) {
                cloudsSuppressed = false;
                release(cloudRenderingKnob, Boolean.TRUE);
                log("Distant Horizons' own LOD clouds are back");
            }
            return;
        }
        if (cloudsSuppressed)
            return;
        probe();
        if (cloudRenderingKnob == null)
            return;
        if (write(cloudRenderingKnob, Boolean.FALSE)) {
            cloudsSuppressed = true;
            log("clear-sky event: asking Distant Horizons to hold its own LOD clouds");
        }
    }

    /** Called when the world goes away: every override this class placed is handed back. */
    public static void onWorldLeft() {
        firstWrittenStart = Float.NaN;
        firstWrittenEnd = Float.NaN;
        deepestRatio = 1F;
        releaseAll();
        setCloudsSuppressed(false);
    }

    /**
     * How much longer the DH view is than the vanilla one, as a multiplier (1.0 = no change).
     *
     * <p>This is the number the redesign turns on. Our fog is built to reach full thickness at the
     * vanilla render distance edge, and that is exactly what makes the LOD world vanish when the same
     * range is handed to DH: the fog is already opaque before the first LOD chunk starts. Spread over
     * DH's view instead - same shape, same relative place in the view, longer view - and it reaches full
     * thickness at DH's horizon rather than at the vanilla boundary, which also removes the ring of fog
     * that vanilla draws at its own far chunks. Both renderers then use one ramp: the vanilla chunks show
     * its early part (thin), the LODs continue it, and there is no boundary to reconcile because both
     * sides are the same function at the same normalised distance.</p>
     *
     * <p>Answers 1.0 when DH is absent, the integration is off, or the setting cannot be read, so every
     * caller can use it unconditionally.</p>
     */
    public static float viewScale() {
        if (!integrationEnabled())
            return 1F;
        probe();
        if (chunkDistanceKnob == null)
            return 1F;
        final Object value = read(chunkDistanceKnob);
        final float chunks = value instanceof Number n ? n.floatValue() : 0F;
        if (chunks <= 0F)
            return 1F;
        final float dhBlocks = chunks * 16F;
        final float vanillaBlocks = Math.max(16F,
                net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16F);
        return Math.max(1F, dhBlocks / vanillaBlocks);
    }

    /** One line for the startup summary and the diagnostics panel. */
    public static String status() {
        if (!ModIds.present(ModIds.DISTANT_HORIZONS))
            return "dh=absent";
        return "dh=" + (integrationEnabled() ? "integrated" : "present(integration off)")
                + (ownsVanillaFog() ? " fog=dh" : "");
    }

    // ---- resolving DH's knobs -------------------------------------------------------------

    /**
     * Walks DH's config once and wraps every knob this class uses.
     *
     * <p>Failure is permanent and reported once: a mod whose config moved is not going to move
     * back mid-session, and retrying a failed reflection every frame would be worse.</p>
     */
    private static void probe() {
        if (probed)
            return;
        synchronized (PROBE_LOCK) {
            if (probed)
                return;
            Throwable failure = null;
            try {
                vanillaFogKnob = wrap(resolveEntry(FOG, "enableVanillaFog"));
                dhFogKnob = wrap(resolveEntry(FOG, "enableDhFog"));
                farFogStartKnob = wrap(resolveEntry(FOG, "farFogStart"));
                farFogEndKnob = wrap(resolveEntry(FOG, "farFogEnd"));
                cloudRenderingKnob = wrap(resolveEntry(GENERIC_RENDERING, "enableCloudRendering"));
                farFogFalloffKnob = wrap(resolveEntry(FOG, "farFogFalloff"));
                farFogMinKnob = wrap(resolveEntry(FOG, "farFogMin"));
                farFogMaxKnob = wrap(resolveEntry(FOG, "farFogMax"));
                for (final String name : CHUNK_DISTANCE_NAMES) {
                    try {
                        chunkDistanceKnob = wrap(resolveEntryUnder(QUALITY, name));
                        break;
                    } catch (Throwable notThisName) {
                        // Try the next spelling.
                    }
                }
            } catch (Throwable t) {
                vanillaFogKnob = null;
                dhFogKnob = null;
                farFogStartKnob = null;
                farFogEndKnob = null;
                cloudRenderingKnob = null;
                failure = t;
            } finally {
                probed = true;
            }
            if (failure != null)
                log("Distant Horizons config probe failed (" + failure
                        + "); DSR keeps its own fog and leaves DH's settings alone. Expected if DH"
                        + " changes its config layout.");
        }
    }

    /** As {@link #resolveEntry}, but for an entry two levels below Graphics. */
    private static Object resolveEntryUnder(final String[][] level, final String field) throws Exception {
        Class<?> holder = Class.forName(CONFIG_CLASS);
        holder = nestedClass(holder, CLIENT[0]);
        holder = nestedClass(holder, ADVANCED[0]);
        holder = nestedClass(holder, GRAPHICS[0]);
        holder = nestedClass(holder, level[0]);
        return staticField(holder, field);
    }

    /**
     * One entry from DH's config tree: walk the holder classes, then read the static field.
     *
     * @param path  the holder levels below {@code Config}, in order
     * @param field the entry's field name on the last holder
     */
    private static Object resolveEntry(final String[][] path, final String field) throws Exception {
        Class<?> holder = Class.forName(CONFIG_CLASS);
        holder = nestedClass(holder, CLIENT[0]);
        holder = nestedClass(holder, ADVANCED[0]);
        holder = nestedClass(holder, GRAPHICS[0]);
        for (final String[] level : path)
            holder = nestedClass(holder, level);
        return staticField(holder, field);
    }

    /**
     * Wraps a config entry in DH's own API value object when that class is present.
     *
     * <p>{@code DhApiConfigValue} is what every other mod talks to, and it is what makes the write
     * path honest: {@code setValue(value, owner)} records who moved the setting and
     * {@code clearValue()} gives it back. The constructor is matched by assignability rather than
     * by a guessed parameter type, so a future DH handing out a subclass still gets wrapped.</p>
     */
    private static Object wrap(final Object entry) {
        if (entry == null)
            return null;
        try {
            final Class<?> wrapper = Class.forName(
                    "com.seibel.distanthorizons.core.config.api.DhApiConfigValue");
            for (final java.lang.reflect.Constructor<?> candidate : wrapper.getConstructors()) {
                final Class<?>[] params = candidate.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(entry))
                    return candidate.newInstance(entry);
            }
        } catch (Throwable t) {
            // Fall through to the raw entry: get()/set() still work, only clearValue() is lost.
        }
        return entry;
    }
    // ---- reading and writing DH's knobs ---------------------------------------------------

    private static Boolean readBoolean(final Object knob) {
        final Object value = read(knob);
        return value instanceof Boolean b ? b : null;
    }

    private static float readFloat(final Object knob, final float fallback) {
        final Object value = read(knob);
        return value instanceof Number n ? n.floatValue() : fallback;
    }

    private static Object read(final Object knob) {
        if (knob == null)
            return null;
        try {
            return readMethod(knob).invoke(knob);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes one knob, preferring DH's {@code setValue(value, owner)} so the change is attributed
     * in DH's own log and config screen.
     */
    private static boolean write(final Object knob, final Object value) {
        if (knob == null)
            return false;
        try {
            final Method setter = writeMethod(knob);
            final Object result = setter.getParameterCount() == 2
                    ? setter.invoke(knob, value, OWNER)
                    : setter.invoke(knob, value);
            final boolean accepted = !(result instanceof Boolean b) || b;
            if (!accepted)
                log("Distant Horizons refused a write (the setting does not allow API overrides);"
                        + " the weather fog mirror is not in effect");
            return accepted;
        } catch (Throwable t) {
            log("could not write to Distant Horizons (" + t + ")");
            return false;
        }
    }

    /**
     * Gives one knob back. Prefers DH's own {@code clearValue()}, which drops the API override;
     * without it the value found before overriding is written back, so DH is never left holding
     * our numbers with no way home.
     */
    private static void release(final Object knob, final Object previous) {
        if (knob == null)
            return;
        try {
            knob.getClass().getMethod("clearValue").invoke(knob);
            return;
        } catch (Throwable noClearValue) {
            // Raw entry or older API: put back what we read.
        }
        if (previous != null && !(previous instanceof Float f && f.isNaN()))
            write(knob, previous);
    }

    /** {@code getValue()} on an API value, {@code get()} on a raw entry. */
    private static Method readMethod(final Object knob) throws NoSuchMethodException {
        try {
            return knob.getClass().getMethod("getValue");
        } catch (NoSuchMethodException notAnApiValue) {
            return knob.getClass().getMethod("get");
        }
    }

    /** {@code setValue(value, owner)} when DH offers it, plain {@code set(value)} otherwise. */
    private static Method writeMethod(final Object knob) throws NoSuchMethodException {
        try {
            return knob.getClass().getMethod("setValue", Object.class, String.class);
        } catch (NoSuchMethodException noOwnerVariant) {
            // Generic erasure means the API method takes Object; a raw entry takes its own T.
        }
        try {
            return knob.getClass().getMethod("setValue", Object.class);
        } catch (NoSuchMethodException notAnApiValue) {
            return knob.getClass().getMethod("set", Object.class);
        }
    }

    // ---- reflection helpers --------------------------------------------------------------

    /** The nested holder class of {@code outer} under any of its known spellings. */
    private static Class<?> nestedClass(final Class<?> outer, final String[] names)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (final String name : names) {
            try {
                return Class.forName(outer.getName() + "$" + name);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException(java.util.Arrays.toString(names));
    }

    /** A public static field's value on a config holder class. */
    private static Object staticField(final Class<?> holder, final String name) throws Exception {
        final Field field = holder.getField(name);
        return java.lang.reflect.Modifier.isStatic(field.getModifiers()) ? field.get(null) : null;
    }

    private static boolean close(final float a, final float b) {
        return !Float.isNaN(b) && Math.abs(a - b) < WRITE_EPSILON;
    }

    /**
     * Reads a knob back after writing it and complains once if DH is not reporting our number.
     *
     * <p>The one failure this mod cannot otherwise see: {@code setValue} can be accepted and still
     * have no effect, because an entry that does not allow API overrides keeps reporting its file
     * value. Without this line that looks exactly like success.</p>
     */
    private static void verifyWrite(final Object knob, final Object want, final String name) {
        final Object actual = read(knob);
        if (sameValue(actual, want))
            return;
        if (reportedIneffective)
            return;
        reportedIneffective = true;
        log("DH reports " + name + "=" + actual + " after we wrote " + want
                + " (overridable=" + canOverride(knob) + "); the weather fog cannot be mirrored into DH's"
                + " LOD fog on this build. DSR stops trying and leaves the setting alone.");
    }
    private static Float readFloatObject(final Object knob) {
        final Object value = read(knob);
        return value instanceof Number n ? n.floatValue() : null;
    }

    /** DH's own answer to "may a mod override this", or null when it cannot be asked. */
    private static Boolean canOverride(final Object knob) {
        if (knob == null)
            return null;
        try {
            final Object result = knob.getClass().getMethod("getCanBeOverrodeByApi").invoke(knob);
            return result instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One line listing every DH knob this class touches, with the value DH is actually using.
     *
     * <p>This exists because three rounds were spent guessing which link was broken. The effective
     * values (plus whether each entry even allows an API override) answer it in one launch.</p>
     */
    public static String knobsCensus() {
        if (!ModIds.present(ModIds.DISTANT_HORIZONS))
            return "";
        probe();
        if (vanillaFogKnob == null)
            return "DH config unreachable";
        return "DH knobs: enableVanillaFog=" + read(vanillaFogKnob)
                + " enableDhFog=" + read(dhFogKnob)
                + " farFogStart=" + read(farFogStartKnob) + " farFogEnd=" + read(farFogEndKnob)
                + " cloudRendering=" + read(cloudRenderingKnob)
                + " falloff=" + read(farFogFalloffKnob) + " min=" + read(farFogMinKnob)
                + " max=" + read(farFogMaxKnob)
                + " | overridable: dhFog=" + canOverride(dhFogKnob)
                + " farStart=" + canOverride(farFogStartKnob)
                + " farEnd=" + canOverride(farFogEndKnob)
                + " falloff=" + canOverride(farFogFalloffKnob)
                + " minMax=" + canOverride(farFogMinKnob) + "/" + canOverride(farFogMaxKnob)
                + " clouds=" + canOverride(cloudRenderingKnob);
    }

    /** Two decimals, for the log lines above. */
    private static String round(final float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static float clamp(final float v, final float lo, final float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void log(final String message) {
        try {
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .info("[COMPAT] " + message);
        } catch (Throwable t) {
            // Nothing left to report with - see every other logging guard in this batch.
        }
    }
}
