package org.orecruncher.dsurround.processing.weather;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.orecruncher.dsurround.compat.ModIds;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.events.HandlerPriority;
import org.orecruncher.dsurround.lib.logging.IModLog;

/**
 * Watches the routes this mod has taken over and says so, loudly, when one of them stops being
 * consulted.
 *
 * <p>Every route below is a {@code require = 0} injection or an event handler. That is the right
 * choice - a mapping change must not be fatal - but it has one consequence that has bitten this
 * project repeatedly: <b>a miss is completely silent</b>. The mixin does not apply, the game runs,
 * the feature is simply absent, and the only way anyone finds out is a bug report that reads like
 * a rendering problem rather than a missing hook. The same holds for the overworld effects object:
 * the registry keeps the last registration, so another mod registering {@code overworld} after us
 * takes the rain and the clouds away without a word.</p>
 *
 * <p>The fuse closes that gap. Each route has a counter, fed from the one place that knows the
 * route was consulted - the same place that already keeps {@code hookCalls} and friends. Once a
 * window of {@link #WINDOW_TICKS} ticks closes, a route that should have been consulted and was
 * not gets one WARN; when it comes back it gets one INFO. Bounded output on purpose: this runs
 * every tick of every session, and a warning that repeats is a warning nobody reads.</p>
 *
 * <p><b>Why the expectations differ per route.</b> A hook that fires only when the player is
 * outdoors in the rain cannot be checked the same way as one that fires once per frame. Two
 * kinds are encoded in {@link Expect}:</p>
 *
 * <ul>
 * <li>{@code ALWAYS} / {@code WHEN_CLOUDS} - the vanilla code path is unconditional (verified
 *     against the 1.20.1 bytecode: {@code LevelRenderer.renderLevel} calls
 *     {@code DimensionSpecialEffects.renderSnowAndRain} every frame with no rain test in front
 *     of it), so zero hits in a window means the hook is gone.</li>
 * <li>{@code SELF_CALIBRATED} - the path is legitimately idle most of the time (the thunder and
 *     sky-darken route does nothing on a clear day). Those are only reported once they have been
 *     seen working and then stopped, which is the failure that costs a debugging session.</li>
 * </ul>
 *
 * <p>This class deliberately owns no game state and changes no behaviour: if it is wrong, the
 * cost is a log line. That is what makes it safe to run before anything else in the weather
 * work.</p>
 */
public final class RouteFuse {

    /** Ticks per observation window. 200 = ten seconds: long enough that a slow path is not
     *  mistaken for a dead one, short enough that a broken route is reported in the same
     *  minute it breaks. */
    public static final int WINDOW_TICKS = 200;

    /** How a route is expected to behave, which decides what a zero window means. */
    private enum Expect {
        /** Consulted every frame the level renders. Zero in a window is a failure. */
        ALWAYS,
        /** As ALWAYS, but only while clouds are switched on. */
        WHEN_CLOUDS,
        /** Legitimately idle; only a route that worked and stopped is reported. */
        SELF_CALIBRATED
    }

    /**
     * The routes this mod hooks. The label is what appears in the log, so it names the visible
     * thing (what the player loses) rather than the class that carries the hook.
     */
    public enum Route {
        /** {@code ClientLevel.getSkyColor} - the sky's rain desaturation. */
        SKY_COLOR("sky color", Expect.ALWAYS),
        /** {@code ClientLevel.getCloudColor} - the cloud sheet's rain desaturation. */
        CLOUD_COLOR("cloud color", Expect.WHEN_CLOUDS),
        /** {@code FogRenderer.setupColor} - the fog's rain and thunder colour pass. */
        FOG_COLOR("fog color", Expect.ALWAYS),
        /** {@code ClientLevel.getSkyDarken} + the storm desaturation - the world's darkening. */
        SKY_DARKEN("thunder & sky darken", Expect.SELF_CALIBRATED),
        /** {@code LevelRenderer.renderSky}'s celestial alpha - the sun, moon and stars fading. */
        SUN_ALPHA("sun & celestial alpha", Expect.ALWAYS),
        /** {@code DimensionSpecialEffects.renderSnowAndRain} + {@code tickRain} - the rain curtain. */
        RAIN_RENDER("rain render", Expect.ALWAYS),
        /** {@code ViewportEvent.RenderFog} - the fog distance (1.20.1's event, not a mixin). */
        FOG_RANGE("fog range", Expect.ALWAYS);

        /** Name used in the log. Kept short and stable: it is grepped. */
        public final String label;
        private final Expect expect;

        Route(String label, Expect expect) {
            this.label = label;
            this.expect = expect;
        }
    }

    /** Capabilities (not routes): what the mod believes it currently owns. */
    public enum Capability {
        FOG("fog"),
        CLOUD("cloud"),
        RAIN("rain"),
        SKY("sky");

        public final String label;

        Capability(String label) {
            this.label = label;
        }
    }

    private static final Route[] ROUTES = Route.values();
    private static final int ROUTE_COUNT = ROUTES.length;
    private static final Capability[] CAPABILITIES = Capability.values();

    /** Total consultations since the world was entered. Written from render and tick threads. */
    private static final long[] HITS = new long[ROUTE_COUNT];
    /** {@link #HITS} at the end of the previous window - the baseline a window is measured from. */
    private static final long[] WINDOW_BASE = new long[ROUTE_COUNT];
    /** Any tick in this window where the route should have been consulted. */
    private static final boolean[] EXPECTED = new boolean[ROUTE_COUNT];
    /** Whether the route has ever been consulted in this world (the self-calibrated baseline). */
    private static final boolean[] SEEN_ALIVE = new boolean[ROUTE_COUNT];
    /** Whether a failure has already been reported, so the WARN is emitted once. */
    private static final boolean[] REPORTED = new boolean[ROUTE_COUNT];
    /** Whether the route is currently believed lost - reported by the diagnostics overlay. */
    private static final boolean[] LOST = new boolean[ROUTE_COUNT];
    /** Capability loss, set either by a route failure or by the effects re-check. */
    private static final boolean[] CAPABILITY_LOST = new boolean[CAPABILITIES.length];

    /**
     * Ticks of visible rain during which the rain route was consulted zero times.
     *
     * <p>This is the behavioural detector for "another mod cancelled
     * {@code LevelRenderer.renderSnowAndRain} before vanilla could ask us" - which is exactly what
     * Particle Rain and Simple Clouds do at HEAD of that method. It needs no knowledge of either
     * mod: if it is raining and vanilla never asks who should draw the rain, someone above us owns
     * it, whoever that is. Presence is then only used to NAME the suspect in the log.</p>
     */
    private static int rainSilentTicks = 0;
    /** Rain route hits as of the previous tick, for the comparison above. */
    private static long lastRainHits = 0L;
    /** True once the rain route has been proven to belong to someone else. */
    private static volatile boolean rainRouteForeign = false;

    /**
     * How long the rain route may stay silent, in ticks of visible rain, before the hand-off.
     * Two seconds: long enough that a single stalled frame cannot trigger it, short enough that
     * the player does not spend a storm watching two rains on top of each other.
     */
    private static final int RAIN_FOREIGN_TICKS = 40;

    private static volatile boolean inWorld = false;
    private static int ticksInWorld = 0;
    private static volatile boolean bootstrapped = false;

    private RouteFuse() {
    }

    /** Registers the tick listener. Safe to call more than once. */
    public static void bootstrap() {
        if (bootstrapped)
            return;
        bootstrapped = true;
        ClientState.TICK_END.register(RouteFuse::onTickEnd, HandlerPriority.LOW);
        ClientState.ON_DISCONNECT.register(mc -> reset());
    }

    /**
     * Records that a route was consulted. Called from the hook itself, before any ownership
     * test, because the question is whether the injection is on the code path at all.
     *
     * <p>One array increment: this runs on every frame for every route, so it must not
     * allocate, log, or take a lock. It does not.</p>
     */
    public static void feed(Route route) {
        HITS[route.ordinal()]++;
    }

    /** Total consultations of a route in this world. Diagnostics only. */
    public static long hits(Route route) {
        return HITS[route.ordinal()];
    }

    /**
     * Whether the rain render route has been proven to belong to another mod. Read by
     * {@code PrecipitationRenderer.active()} to stand down before drawing a second rain.
     */
    public static boolean rainRouteForeign() {
        return rainRouteForeign;
    }

    /** Whether the route is currently believed dead. Diagnostics only. */
    public static boolean lost(Route route) {
        return LOST[route.ordinal()];
    }

    /** Whether a capability is currently believed lost. Read by the compatibility summary. */
    public static boolean capabilityLost(Capability capability) {
        return CAPABILITY_LOST[capability.ordinal()];
    }

    /**
     * Declares a capability lost. Used by the overworld effects re-check, which is the only way
     * to notice that another mod took the {@code overworld} registration from us - the registry
     * keeps the last registration and says nothing.
     */
    public static void noteCapabilityLost(Capability capability, String why) {
        final int index = capability.ordinal();
        if (CAPABILITY_LOST[index])
            return;
        CAPABILITY_LOST[index] = true;
        warn("capability '" + capability.label + "' lost: " + why);
    }

    /** One line for the diagnostics overlay and the compatibility summary. */
    public static String summary() {
        StringBuilder sb = new StringBuilder("routes: ");
        for (Route route : ROUTES) {
            sb.append(route.label).append('=').append(LOST[route.ordinal()] ? "lost" : "ok").append(' ');
        }
        sb.append("| capabilities: ");
        for (Capability capability : CAPABILITIES)
            sb.append(capability.label).append('=')
                    .append(CAPABILITY_LOST[capability.ordinal()] ? "lost" : "ok").append(' ');
        return sb.toString().trim();
    }

    // ---- internals --------------------------------------------------------

    private static void onTickEnd(Minecraft mc) {
        try {
            final ClientLevel level = mc.level;
            if (level == null || mc.player == null) {
                if (inWorld)
                    reset();
                return;
            }
            if (!inWorld) {
                inWorld = true;
                ticksInWorld = 0;
                resetCounters();
            }
            ticksInWorld++;
            detectForeignRainRoute(level);
            markExpectations(mc);
            if (ticksInWorld % WINDOW_TICKS == 0)
                checkWindow();
        } catch (Throwable t) {
            // A watchdog must never be the reason a frame dies. It also must not go quiet
            // about its own failure - that is the whole point of this class.
            warn("watchdog tick failed: " + t);
        }
    }

    /**
     * Which routes vanilla should have consulted during this tick.
     *
     * <p>The rain render route is unconditional - verified in the 1.20.1 bytecode, where
     * {@code LevelRenderer.renderLevel} calls {@code effects().renderSnowAndRain} on both
     * branches of the weather block and the rain-level test only happens <em>inside</em>
     * vanilla's own drawing, after the effects object has already declined. So "a level is
     * loaded" is the correct expectation, and a zero window means another mod replaced the
     * effects object or the call never ran.</p>
     */
    private static void markExpectations(Minecraft mc) {
        for (Route route : ROUTES) {
            final boolean expected = switch (route.expect) {
                // A rain route proven to belong to another mod is not a failure when it stays
                // silent - it is the hand-off working. The detectForeignRainRoute() report is the
                // one that matters; this only stops the window check from repeating it.
                case ALWAYS -> route != Route.RAIN_RENDER || !rainRouteForeign;
                case WHEN_CLOUDS -> mc.options.getCloudsType() != CloudStatus.OFF;
                case SELF_CALIBRATED -> false;
            };
            if (expected)
                EXPECTED[route.ordinal()] = true;
        }
        // SELF_CALIBRATED routes are deliberately not marked here. They are judged against their
        // own history in checkWindow instead, so that a route which is legitimately idle on a
        // clear day is not reported as broken.
    }

    /**
     * Notices that the rain route is not ours, from behaviour rather than from a mod list.
     *
     * <p>The rain pass is asked for once per frame through the dimension effects object, so the
     * hit count moves every tick there is a level to draw - unless something cancelled the call
     * before it reached us. Vanilla's own rain level is the witness: while rain is visibly falling
     * and our counter has not moved, the pass is being drawn by somebody else (or, in the worst
     * case, not at all). Two seconds of that and the hand-off is made.</p>
     */
    private static void detectForeignRainRoute(final ClientLevel level) {
        if (level.getRainLevel(1F) <= 0.05F) {
            // Nothing is falling: silence here says nothing at all.
            rainSilentTicks = 0;
            lastRainHits = HITS[Route.RAIN_RENDER.ordinal()];
            return;
        }
        final long hits = HITS[Route.RAIN_RENDER.ordinal()];
        if (hits != lastRainHits) {
            lastRainHits = hits;
            rainSilentTicks = 0;
            if (rainRouteForeign) {
                rainRouteForeign = false;
                info("the rain render route is ours again (vanilla is asking us once more)");
            }
            return;
        }
        if (rainRouteForeign)
            return;
        if (++rainSilentTicks < RAIN_FOREIGN_TICKS)
            return;
        rainRouteForeign = true;
        warn("it is raining and the rain render route has not been consulted for "
                + RAIN_FOREIGN_TICKS + " ticks: another mod cancels LevelRenderer.renderSnowAndRain"
                + " before vanilla can ask us" + rainSuspects()
                + ". Our graded rain curtain is standing down so that only one rain is drawn -"
                + " set 'gradedRainOverParticleRain' to force ours back on.");
    }

    /**
     * Names the mods known to cancel that call, so the WARN above does not read as a mystery.
     * Only a hint: the detection itself never used this list.
     */
    private static String rainSuspects() {
        final StringBuilder sb = new StringBuilder();
        if (ModIds.present(ModIds.PARTICLE_RAIN))
            sb.append(sb.length() == 0 ? " (" : ", ").append(ModIds.PARTICLE_RAIN);
        if (ModIds.present(ModIds.SIMPLE_CLOUDS))
            sb.append(sb.length() == 0 ? " (" : ", ").append(ModIds.SIMPLE_CLOUDS);
        if (sb.length() == 0)
            return "";
        return sb.append(')').toString();
    }

    /**
     * Whether a mod that cancels the vanilla rain pass at HEAD is installed - the two known ones,
     * named here only so the window check can tell "explained" silence from "broken" silence.
     */
    private static boolean rainDrawingModPresent() {
        return ModIds.present(ModIds.PARTICLE_RAIN) || ModIds.present(ModIds.SIMPLE_CLOUDS);
    }

    private static void checkWindow() {
        for (Route route : ROUTES) {
            final int index = route.ordinal();
            final long hits = HITS[index];
            final boolean fired = hits > WINDOW_BASE[index];
            final boolean shouldHaveFired = route.expect == Expect.SELF_CALIBRATED
                    ? SEEN_ALIVE[index]
                    : EXPECTED[index];
            if (fired) {
                SEEN_ALIVE[index] = true;
                if (REPORTED[index]) {
                    REPORTED[index] = false;
                    LOST[index] = false;
                    info("route '" + route.label + "' is alive again (" + hits
                            + " consultations since the world was entered)");
                }
            } else if (shouldHaveFired && !REPORTED[index]) {
                REPORTED[index] = true;
                LOST[index] = true;
                loseCapabilityFor(route);
                if (route == Route.RAIN_RENDER && rainDrawingModPresent()) {
                    // The first live session produced this case and the message was wrong: a dry
                    // window in which Particle Rain had cancelled the pass was reported as "this
                    // version's code path does not carry our hook", i.e. as a broken injection. A
                    // mod that cancels LevelRenderer.renderSnowAndRain at HEAD is a complete
                    // explanation for silence, and the WARN that matters is the behavioural one
                    // (detectForeignRainRoute), which names it. So this case is stated for what
                    // it is: expected, not broken.
                    info("the rain render route stayed silent for a " + WINDOW_TICKS
                            + " tick window; " + ModIds.firstPresent(ModIds.PARTICLE_RAIN, ModIds.SIMPLE_CLOUDS)
                            + " owns that pass (it cancels it before vanilla can ask us), so this is"
                            + " the hand-off working, not a missed hook.");
                } else {
                    warn("route '" + route.label + "' was not consulted for " + WINDOW_TICKS
                            + " ticks while it was expected to be - this version's code path does not "
                            + "carry our hook, so that feature is silently off rather than broken."
                            + pipelineHint());
                }
            }
            WINDOW_BASE[index] = hits;
            EXPECTED[index] = false;
        }
    }

    /**
     * Which capability a dead route costs the player. Only the routes whose loss is visible as
     * a missing feature are mapped; the rest are reported as routes alone.
     */
    private static void loseCapabilityFor(Route route) {
        switch (route) {
            case RAIN_RENDER -> CAPABILITY_LOST[Capability.RAIN.ordinal()] = true;
            case CLOUD_COLOR -> CAPABILITY_LOST[Capability.CLOUD.ordinal()] = true;
            case FOG_COLOR, FOG_RANGE -> CAPABILITY_LOST[Capability.FOG.ordinal()] = true;
            default -> {
                // Sky colour and darkening: the sky route degrades to vanilla on its own.
            }
        }
    }

    /**
     * What is installed here, appended to a route failure so the reader does not have to ask.
     * The three answers that matter are: a replaced render pipeline (Sodium and friends), a
     * shader pack (which owns the sky and the fog outright), and nothing at all - in which case
     * the reader is looking at a mapping or code-path change.
     */
    private static String pipelineHint() {
        StringBuilder sb = new StringBuilder();
        if (ModIds.isAny(ModIds.SODIUM))
            sb.append(" [sodium-family mod present: ")
                    .append(ModIds.firstPresent(ModIds.SODIUM)).append(']');
        if (ModIds.shadersInUse())
            sb.append(" [shader pack in use: the sky and fog routes belong to it]");
        if (sb.length() == 0)
            sb.append(" [no render-pipeline mod detected - suspect a mapping or code-path change]");
        return sb.toString();
    }

    private static void reset() {
        inWorld = false;
        ticksInWorld = 0;
        rainSilentTicks = 0;
        lastRainHits = 0L;
        rainRouteForeign = false;
        resetCounters();
    }

    private static void resetCounters() {
        for (int i = 0; i < ROUTE_COUNT; i++) {
            HITS[i] = 0L;
            WINDOW_BASE[i] = 0L;
            EXPECTED[i] = false;
            SEEN_ALIVE[i] = false;
            REPORTED[i] = false;
            LOST[i] = false;
        }
        for (int i = 0; i < CAPABILITIES.length; i++)
            CAPABILITY_LOST[i] = false;
    }

    /**
     * Diagnostics only, and never fatal. The logger is resolved lazily because the fuse can be
     * reached before the container is populated, and the whole call is wrapped because a
     * watchdog that throws while reporting a problem is worse than the problem.
     */
    private static void log(String level, String message) {
        try {
            IModLog logger = ContainerManager.resolve(IModLog.class);
            if ("WARN".equals(level))
                logger.warn("[COMPAT] " + message);
            else
                logger.info("[COMPAT] " + message);
        } catch (Throwable t) {
            // Nothing left to do: there is no way to report a reporting failure.
        }
    }

    private static void warn(String message) {
        log("WARN", message);
    }

    private static void info(String message) {
        log("INFO", message);
    }
}
