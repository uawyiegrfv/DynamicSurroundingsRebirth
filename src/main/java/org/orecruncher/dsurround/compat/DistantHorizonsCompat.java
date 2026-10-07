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

    /** Resolved knobs. Null means the probe could not reach it - every use checks. */
    private static volatile Object vanillaFogKnob = null;
    private static volatile Object dhFogKnob = null;
    private static volatile Object farFogStartKnob = null;
    private static volatile Object farFogEndKnob = null;
    private static volatile Object cloudRenderingKnob = null;

    private static final Object PROBE_LOCK = new Object();
    private static volatile boolean probed = false;

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
    public static void syncFog(final float visibilityRatio) {
        if (!integrationEnabled()) {
            clearFogOverride();
            return;
        }
        probe();
        final Object start = farFogStartKnob;
        final Object end = farFogEndKnob;
        if (start == null || end == null)
            return;
        final float ratio = clamp(visibilityRatio, 0.05F, 1.0F);
        if (ratio < deepestRatio)
            deepestRatio = ratio;
        // Engage low, release high: one threshold chattered at the boundary (see SYNC_THRESHOLD).
        if (ratio >= (fogOverridden ? RELEASE_THRESHOLD : SYNC_THRESHOLD)) {
            clearFogOverride();
            return;
        }
        // We are going to mirror, so DH's own LOD fog has to exist. The first live session found it
        // switched off by another mod (Iris writes enableDhFog through this same API at world join),
        // which made the whole mirror invisible while looking perfectly healthy in the log.
        final Boolean dhFogOn = readBoolean(dhFogKnob);
        if (dhFogOn != null && !dhFogOn && !dhFogForcedOn) {
            if (write(dhFogKnob, Boolean.TRUE)) {
                dhFogForcedOn = true;
                log("DH's own LOD fog was switched off (another mod or the DH config) while the weather"
                        + " wants to close the view in; switching it on for the duration, and putting it"
                        + " back the way it was when the weather clears.");
            } else if (!reportedDhFogOff) {
                reportedDhFogOff = true;
                log("DH's own LOD fog is switched off and DSR was not allowed to switch it on, so the"
                        + " weather fog cannot show on LOD terrain. Nothing else is touched.");
                return;
            }
        }
        if (!fogOverridden) {
            // DH's configured values are the base, not our idea of a default: a player who tuned
            // their far fog keeps the shape of it, scaled.
            baseStart = readFloat(start, 0.4F);
            baseEnd = readFloat(end, 1.0F);
            fogOverridden = true;
            log("weather fog is closing visibility to " + Math.round(ratio * 100F)
                    + "% of the far plane - scaling Distant Horizons' LOD fog to match");
        }
        final float wantStart = clamp(baseStart * ratio, 0.0F, 1.0F);
        final float wantEnd = clamp(baseEnd * ratio, 0.05F, 1.0F);
        // Compare against what DH reports NOW, not against what we last wrote. Writing once and
        // trusting it was the bug behind "the integration looks healthy but nothing changes": the
        // effective value can be dropped or replaced by anyone (another mod writing the same knob,
        // or DH falling back to its file value), and a cache of our own last write cannot see that.
        if (close(readFloat(start, Float.NaN), wantStart) && close(readFloat(end, Float.NaN), wantEnd))
            return;
        if (write(start, wantStart) && write(end, wantEnd)) {
            writtenStart = wantStart;
            writtenEnd = wantEnd;
            verifyWrite(start, wantStart, "farFogStart");
            verifyWrite(end, wantEnd, "farFogEnd");
            if (Float.isNaN(firstWrittenStart)) {
                firstWrittenStart = wantStart;
                firstWrittenEnd = wantEnd;
                // The numbers matter as much as the fact: a mirror that only ever moves DH's fog by
                // ten percent is a mirror nobody can see, and this line is what tells the difference
                // between "it did not run" and "it ran and was too small to notice".
                // The effective values of the two knobs that decide whether any of this can be SEEN:
                // another mod can hold DH's own LOD fog off (Iris does exactly that at world join),
                // and a mirror into a fog layer that is not drawn is a no-op with a healthy log.
                log("mirroring weather fog into DH's LOD fog: ratio=" + round(ratio)
                        + " farFogStart " + round(baseStart) + "->" + round(wantStart)
                        + ", farFogEnd " + round(baseEnd) + "->" + round(wantEnd)
                        + " (DH reports enableDhFog=" + read(dhFogKnob) + ")");
            }
        }
    }

    /** Hands DH's far fog back. Safe to call every frame; only the transition does work. */
    public static void clearFogOverride() {
        if (!fogOverridden)
            return;
        fogOverridden = false;
        writtenStart = Float.NaN;
        writtenEnd = Float.NaN;
        release(farFogStartKnob, baseStart);
        release(farFogEndKnob, baseEnd);
        if (dhFogForcedOn) {
            // Back to what we found: DH's own LOD fog off again, under whoever's name DH records.
            dhFogForcedOn = false;
            write(dhFogKnob, Boolean.FALSE);
        }
        log("weather fog released - Distant Horizons has its own LOD fog values back (deepest visibility"
                + " ratio while engaged: " + round(deepestRatio) + ")");
        deepestRatio = 1F;
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
        clearFogOverride();
        setCloudsSuppressed(false);
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
    private static void verifyWrite(final Object knob, final float want, final String name) {
        final Float actual = readFloatObject(knob);
        if (actual == null || Math.abs(actual - want) < WRITE_EPSILON)
            return;
        if (reportedIneffective)
            return;
        reportedIneffective = true;
        log("DH reports " + name + "=" + round(actual.floatValue()) + " after we wrote " + round(want)
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
                + " | overridable: dhFog=" + canOverride(dhFogKnob)
                + " farStart=" + canOverride(farFogStartKnob)
                + " farEnd=" + canOverride(farFogEndKnob)
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
