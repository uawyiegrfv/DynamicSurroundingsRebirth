package org.orecruncher.dsurround.compat;

import dev.architectury.platform.Platform;

import java.lang.reflect.Method;

/**
 * The one place that answers "is that other mod here, and is it doing the thing".
 *
 * <p>Every compatibility decision in the weather work starts with a presence test, and before
 * this class those tests were scattered: the ids lived next to the code that used them, one id
 * per call site, and a mod that renamed itself or shipped two ids (Oculus declares
 * {@code provides=iris}, but FML's {@code isLoaded} does not resolve aliases) simply read as
 * absent. Two rules follow from that and both are enforced here:</p>
 *
 * <ul>
 * <li><b>Arrays, not single ids</b>, for anything that has a sibling (Iris/Oculus, Sodium and
 *     its two forks) - see {@link #IRIS}, {@link #SODIUM}.</li>
 * <li><b>State, not presence, for shaders.</b> A pack being installed says nothing about whether
 *     one is in use. {@link #shadersInUse()} asks Iris itself, through reflection, and is the
 *     only question worth asking - see the weather document, section 10.</li>
 * </ul>
 *
 * <p><b>Reflection, deliberately.</b> Iris and Oculus are not compile dependencies of any of the
 * three builds; naming their types would add a link-time dependency to a class we ship. Nothing
 * here references an Iris type, so the class loads fine without them and
 * {@link #shadersInUse()} answers false when the API is missing, which is the same answer as
 * "no shader pack". Every probe is wrapped: a mod that is present but unreadable must never be
 * able to throw out of a render path.</p>
 */
public final class ModIds {

    // ---- Weather, fog, clouds, rain ---------------------------------------

    /** Distant Horizons: owns the fog on 1.20.1 (see the hand-off table in the weather doc). */
    public static final String DISTANT_HORIZONS = "distanthorizons";
    /** Voxy: 26.1 only, and Fabric-only upstream - present here for the shared source. */
    public static final String VOXY = "voxy";
    /** Simple Clouds: cancels {@code renderClouds} at HEAD (priority 1001), so it wins the clouds. */
    public static final String SIMPLE_CLOUDS = "simpleclouds";
    /**
     * Better Clouds: renders its own cloud sheet from a mixin on {@code LevelRenderer.renderClouds}
     * (verified in its jar: a {@code renderClouds} handler in the REQUIRED client mixin set) and
     * offsets the cloud height through {@code DimensionSpecialEffects.OverworldEffects}. The mod is
     * not new to this project - 1.3.2's changelog already credits it for the rain/thunder fog flicker
     * fix - but it was not part of the weather batch's six-mod compatibility list, which is where the
     * cloud ownership question was missed.
     */
    public static final String BETTER_CLOUDS = "betterclouds";
    /** Every mod known to draw the clouds itself - see the cloud route in RouteFuse. */
    public static final String[] CLOUD_MODS = { SIMPLE_CLOUDS, BETTER_CLOUDS };
    /** Particle Rain: cancels {@code renderSnowAndRain} at HEAD, so it wins the rain curtain. */
    public static final String PARTICLE_RAIN = "particlerain";

    // ---- Sound (the B7 footstep fallback) ---------------------------------

    /** IMB11's Sounds: replaces the vanilla sound *event* table, which is why footsteps need it. */
    public static final String SOUNDS = "sounds";
    /** Sounds' former id, still shipped by older 1.20.1 builds. */
    public static final String SONANCE = "sonance";

    // ---- Render-pipeline mods ---------------------------------------------

    /** Iris (Fabric/NeoForge) and Oculus (the Forge port). Oculus is the one 1.20.1 sees. */
    public static final String[] IRIS = { "iris", "oculus" };
    /**
     * Sodium and its two forks - all three rewrite the chunk/render pipeline.
     *
     * <p>Order matters for what gets REPORTED: Embeddium ships a second mod entry with the id
     * {@code rubidium} (a compatibility alias, seen in its own {@code mods.toml}), so a player who
     * installed Embeddium would be told "rubidium" by {@link #firstPresent} if the alias came
     * first. The concrete ids lead; the alias stays last so it is still detected.</p>
     */
    public static final String[] SODIUM = { "sodium", "embeddium", "rubidium" };
    /** Caching mods that can serve a stale resource pack; warned about, never fought. */
    public static final String[] CACHE_MODS = { "badoptimizations" };

    /**
     * The Iris API entry point, present in both Iris and Oculus.
     *
     * <p>Deliberately a string: see the class comment. {@code isShaderPackInUse()} is the method
     * that matters and it has been stable since the API existed.</p>
     */
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

    private ModIds() {
    }

    /**
     * Whether a mod id is loaded. Never throws: an early call, a missing loader or a shimmed
     * environment all answer "not loaded", which every caller already handles.
     */
    public static boolean present(String modId) {
        if (modId == null || modId.isEmpty())
            return false;
        try {
            return Platform.isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether any of the ids is loaded. Used for the ids that have siblings (Iris/Oculus...). */
    public static boolean isAny(String... modIds) {
        return firstPresent(modIds) != null;
    }

    /**
     * The first loaded id, or null. The name matters as much as the answer: a log line that says
     * {@code iris=present} when the pack actually runs Oculus sends the reader to the wrong
     * issue tracker, so the summary reports the id that was found.
     */
    public static String firstPresent(String... modIds) {
        if (modIds == null)
            return null;
        for (String id : modIds)
            if (present(id))
                return id;
        return null;
    }

    /** {@code present} / {@code absent}, for the one-line compatibility summary. */
    public static String flag(String modId) {
        return present(modId) ? "present" : "absent";
    }

    /** {@code present} / {@code absent} over a group of interchangeable ids. */
    public static String flagAny(String... modIds) {
        return isAny(modIds) ? "present" : "absent";
    }

    /**
     * Whether a shader pack is <em>in use</em> right now - not whether Iris is installed.
     *
     * <p>The distinction is the whole point of this method. A pack that is merely available
     * changes nothing, and the toggles can be flipped at any time, so the answer is read live
     * rather than cached: a cached "no" would outlive the user enabling a pack and quietly
     * mislabel every compatibility decision for the rest of the session.</p>
     *
     * <p>Returns false when the API is missing or unreadable - the same answer as "no pack",
     * and the conservative one: every shader-aware decision in this mod is a step <em>back</em>
     * from taking over a route, so answering false keeps the mod's own behaviour.</p>
     */
    /**
     * The resolved API accessors. The <em>lookups</em> are cached; the <em>answer</em> is not -
     * see {@link #shadersInUse()}.
     *
     * <p>Worth caching because the question is asked far more often than it used to be: the
     * plant-attached layers ask it every tick (three handlers) once
     * {@code disableFoliageLayersWithShaders} is on, and each uncached call was a
     * {@code Class.forName} plus two {@code getMethod} copies. Those are cheap individually and
     * pointless in aggregate, and "pointless in aggregate" is how a 60-times-a-second reflective
     * lookup ends up in a frame budget discussion.</p>
     */
    private static final Object PROBE_LOCK = new Object();
    private static volatile boolean probeDone = false;
    private static volatile Method irisGetInstance = null;
    private static volatile Method irisIsShaderPackInUse = null;

    public static boolean shadersInUse() {
        if (!probeDone)
            probeIris();
        final Method getInstance = irisGetInstance;
        final Method inUse = irisIsShaderPackInUse;
        if (getInstance == null || inUse == null)
            return false;
        try {
            final Object instance = getInstance.invoke(null);
            if (instance == null)
                return false;
            final Object result = inUse.invoke(instance);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves the Iris accessors once. A failure is permanent and silent here on purpose: the
     * callers all treat "cannot read" as "no pack", and retrying a missing class every tick would
     * be worse than the answer being wrong in the conservative direction.
     */
    private static void probeIris() {
        if (probeDone)
            return;
        synchronized (PROBE_LOCK) {
            if (probeDone)
                return;
            try {
                final Class<?> api = Class.forName(IRIS_API_CLASS);
                final Method getInstance = api.getMethod("getInstance");
                final Method inUse = api.getMethod("isShaderPackInUse");
                irisGetInstance = getInstance;
                irisIsShaderPackInUse = inUse;
            } catch (Throwable t) {
                irisGetInstance = null;
                irisIsShaderPackInUse = null;
            } finally {
                probeDone = true;
            }
        }
    }

    /** {@code present(shaders=on)} style fragment for the iris group. */
    public static String irisFlag() {
        String id = firstPresent(IRIS);
        if (id == null)
            return "absent";
        return id + "(shaders=" + (shadersInUse() ? "on" : "off") + ")";
    }
}
