package org.orecruncher.dsurround.processing.weather;

import java.util.Random;

import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.compat.DistantHorizonsCompat;
import org.orecruncher.dsurround.config.biome.biometraits.BiomeTraits;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

/**
 * <p>Dry-weather sky events: the <b>overcast</b> (a day that turns and stays
 * grey) and the <b>clear sky</b> (the day the clouds go away entirely). Both
 * are slow, hours-scale changes to a sky that no precipitation intensity can
 * express, which is why they are their own scheduler feeding their own root
 * channels ({@link PrecipitationResponse#OVERCAST} / {@link #CLEAR}) instead
 * of riding any part of the rain chain.</p>
 *
 * <h2>The one architectural rule</h2>
 *
 * <p>{@code PrecipitationIntensity.update()} has an idle fast path: when
 * nothing is falling and every channel has settled it returns before
 * {@code tickAll}, and that return is what keeps a dry world cheap. An event
 * that lasts in-game hours cannot hold that path open - so this scheduler is
 * called <em>before</em> the idle branch and advances the two channels itself
 * through {@link PrecipitationResponse#advance}, and the channels are excluded
 * from both {@code tickAll} and {@code anyActive()}. The consequence is
 * deliberate and worth stating: while an event runs, idle ticks keep firing
 * (the {@code [PRECIP-IDLE]} line keeps appearing in the log - that is the
 * regression proof) and the only work added to them is this class, which is
 * O(1) arithmetic and a couple of field reads.</p>
 *
 * <p>That restriction shapes everything else: no biome queries, no profile
 * resolves, no level access in the per-tick path. The one biome lookup - the
 * profile resolve behind each roll - happens once per roll interval, and the
 * traits are handed in by the caller that already has them.</p>
 *
 * <h2>How events are drawn</h2>
 *
 * <p>A <b>lottery, not a schedule</b>. While the weather is quiet, one roll
 * happens every {@link #ROLL_INTERVAL_TICKS} (a game hour): the biome's
 * profile and season give two per-roll chances - overcast and clear - and a
 * single uniform decides "overcast", "clear" or "nothing". Most rolls come up
 * nothing, which is what makes the spacing weather-like: some game days see
 * two grey spells, some see none, and no clock is telling you when the next
 * one is due. The chances themselves are expectations read off the climate
 * each profile stands for - see the tables on
 * {@link PrecipitationProfile#overcastChanceFor}. While an event is up
 * (running or fading) <b>no rolls happen at all</b>, and the roll clock only
 * runs while the weather is quiet - rain is its own event and must not
 * consume the sky's rolls.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 *   GAP  - waiting. Quiet-weather roll every game hour; most rolls nothing.
 *   RUNNING - the event is up. Rain makes it <b>yield</b>: the channel target
 *          drops to 0 and the channel's own fall constant (5s for overcast)
 *          hands the sky to the rain without a pop, while the event's clock
 *          is suspended - a rainstorm does not eat the grey day. Past the
 *          suspension limit the event is ended rather than kept alive
 *          indefinitely. When the rain ends the target returns to 1 and the
 *          channel's rise constant (45s) restores it slowly.
 *   FADING - duration spent: a 30s linear fade before the event ends, so a
 *          natural end does not snap back to blue.
 * </pre>
 *
 * <p>The two events never run at once: one scheduler, one current event.
 * {@code /dsprecip event overcast|clear|stop} starts and ends events by hand -
 * a manual start bypasses the roll (and the quiet check: started into rain it
 * simply yields until the rain passes, exactly as a rolled event would).</p>
 */
public final class SkyEventScheduler {

    private SkyEventScheduler() {
    }

    // ---- schedule -----------------------------------------------------------
    //
    // A Minecraft game hour is 50 real seconds (1000 ticks). One roll per game
    // hour with per-roll chances in the few-to-fifteen percent range puts a
    // temperate biome at roughly two overcast starts and one clear start per
    // game day, with wide variance - the expectation, not the schedule.

    /** Quiet-weather ticks between rolls of the sky-event lottery. */
    private static final int ROLL_INTERVAL_TICKS = 1000;
    /** Safety ceiling on the combined per-roll chance, whatever the tables say. */
    private static final float ROLL_CHANCE_CAP = 0.35F;
    /** Ticks into a single rain after which a suspended event is ended for good. */
    private static final int SUSPEND_LIMIT_TICKS = 2 * 1000;
    /** Length of the natural-end fade, in ticks. */
    private static final int FADE_OUT_TICKS = 600;

    /** Ambient intensity below which the weather counts as quiet for rolling. */
    private static final float SPAWN_QUIET = 0.001F;

    // ---- rendering constants ------------------------------------------------
    //
    // The overcast grey: how the overcast sky is built from the sky colour
    // vanilla just computed. The rain branch of getSkyColor interpolates towards
    // luma * 0.6 - dark by construction - so "grey sky" cannot be expressed by
    // feeding the overcast into that route; the change has to run on the final
    // RGB. The first cut lifted the sky almost to white, which reads as a void,
    // not as weather (the maintainer's screenshot): a real overcast zenith is a
    // PALE GREY, slightly cool, clearly darker than the cloud bases against it.
    // So the target sits a little above the colour's own luminance with a
    // quarter of its hue kept, capped well short of white - and the cap is what
    // keeps the sunset case (bright orange, high luma) from saturating.

    /** How much of the sky colour's hue survives the grey-out. */
    public static final float WHITE_CHROMA_KEEP = 0.12F;
    /** Where above its own luminance the overcast grey sits. */
    public static final float WHITE_BRIGHT = 1.15F;
    /** Ceiling of the grey-out - pale grey, never white. */
    public static final float WHITE_CAP = 0.85F;

    /** Per-channel target of the overcast grey, given the colour's luminance. */
    public static float whiteTarget(float channel, float luma) {
        final float chroma = luma * WHITE_BRIGHT + (channel - luma) * WHITE_CHROMA_KEEP;
        return Math.min(WHITE_CAP, chroma);
    }

    /**
     * Whether any sky-event channel is currently moving cloud colour. The cloud
     * colour is BAKED into vanilla's cloud vertex buffer and the buffer only
     * rebuilds when the colour has moved more than 2.0E-4 since the last bake -
     * a seconds-scale fade moves ~0.0002 per tick and never clears that bar, so
     * both the overcast grey and the clear-sky melt reach the screen only when
     * the player crosses a cloud cell and vanilla happens to re-bake. Callers
     * force the rebuild while this is true - see MixinLevelRendererWeather.
     */
    public static boolean cloudColourAnimating() {
        return PrecipitationResponse.OVERCAST.value() > 0F
                || PrecipitationResponse.CLEAR.value() > 0F;
    }

    /**
     * Cloud-draw suppression threshold. Below it the cloud colour is melted
     * into the sky colour so the sheet fades into the background; past it the
     * pass is declined outright, which on 1.20.1/1.21.1 is the only way to
     * remove the sheet (their cloud colour has no alpha). By 0.9 the melt has
     * already taken the sheet to within a few percent of the sky, so the
     * decline is invisible.
     */
    private static final float CLOUD_CANCEL_THRESHOLD = 0.9F;

    /**
     * Whether the cloud rendering pass should be declined this frame. Called
     * from the {@code DimensionSpecialEffects} override on 1.20.1/1.21.1 -
     * returning "already handled" there is vanilla's own way to skip the pass.
     */
    public static boolean suppressCloudRendering() {
        if (!enabled())
            return false;
        return PrecipitationResponse.CLEAR.value() >= CLOUD_CANCEL_THRESHOLD;
    }

    /** Overcast white-lift strength, 0..1. 0 keeps vanilla's colour untouched. */
    public static float whiteShiftStrength() {
        return PrecipitationResponse.OVERCAST.value();
    }

    /** Clear-sky melt strength, 0..1 - how far clouds lean into the sky colour. */
    public static float cloudMeltStrength() {
        return PrecipitationResponse.CLEAR.value();
    }

    private static boolean enabled() {
        var config = Client.Config;
        return config != null && config.weatherOptions.enableSkyEvents;
    }

    /** Whether the overcast event may run at all. */
    public static boolean overcastAllowed() {
        var config = Client.Config;
        return config != null && config.weatherOptions.enableSkyEvents
                && config.weatherOptions.enableOvercastSkyEvent;
    }

    /** Whether the clear-sky event may run at all. */
    public static boolean clearAllowed() {
        var config = Client.Config;
        return config != null && config.weatherOptions.enableSkyEvents
                && config.weatherOptions.enableClearSkyEvent;
    }

    /** The logger, for the manual start/stop paths that have none to hand in. */
    private static IModLog logger() {
        try {
            return org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- state --------------------------------------------------------------

    private enum Phase { GAP, RUNNING, FADING }

    private static final Random RANDOM = new Random();

    private static Phase phase = Phase.GAP;
    /** Which event is up. Only meaningful in RUNNING/FADING; null in GAP. */
    private static PrecipitationResponse channel = null;
    /** Ticks of duration left in the running event. Only runs when it is dry. */
    private static int durationRemaining = 0;
    /** Ticks left in the natural-end fade. */
    private static int fadeRemaining = 0;
    /** Ticks the current event has been suspended by rain, consecutively. */
    private static int suspendedTicks = 0;
    /** Ticks until the next roll of the lottery. Only runs while the weather is quiet. */
    private static int rollRemaining = ROLL_INTERVAL_TICKS;

    // ---- per-tick -----------------------------------------------------------

    private static final float DT = 1F / 20F;

    /** Diagnostics: calls served, so the cost line has a denominator. */
    private static volatile long advances = 0L;
    /** Diagnostics: nanoseconds spent, accumulated only while tracing. */
    private static volatile long advanceNanos = 0L;

    /**
     * One client tick. Called from {@code PrecipitationIntensity.update()}
     * before its idle fast path - see the class comment for why the position
     * is load-bearing. {@code raining} is the ramp-derived answer update() has
     * already computed; {@code traits} may be null (an unresolvable biome
     * falls back to TEMPERATE chances at roll time); {@code overworld} is the
     * dimension test, handed in by the caller that already holds the level,
     * because this class is not allowed to reach for one in its tick path.
     */
    public static void tick(IModLog logger, boolean raining, BiomeTraits traits, boolean overworld) {
        final boolean trace;
        final long start;
        var config = Client.Config;
        if (config != null && config.logging.enableDebugLogging
                && (config.logging.traceMask & Configuration.Flags.SKY_EVENTS) != 0) {
            trace = true;
            start = System.nanoTime();
        } else {
            trace = false;
            start = 0L;
        }
        try {
            advance(logger, raining, traits, config, overworld);
            // The clear-sky event is the one moment our cloud handling and DH's disagree: we are
            // removing vanilla's sheet while DH draws its own LOD layer. Ask it to hold that layer
            // for the duration - the call is a no-op unless the state actually changes.
            DistantHorizonsCompat.setCloudsSuppressed(cloudMeltStrength() > 0F);
        } finally {
            if (trace) {
                advanceNanos += System.nanoTime() - start;
                advances++;
            }
        }
    }

    private static void advance(IModLog logger, boolean raining, BiomeTraits traits,
                                Configuration config, boolean overworld) {
        if (config == null || !config.weatherOptions.enableSkyEvents) {
            // Switched off (or starting up): walk both channels home and keep the
            // state machine parked. advance() rather than a pin, so an event that
            // is being turned off mid-flight fades out instead of freezing.
            PrecipitationResponse.OVERCAST.advance(0F, DT);
            PrecipitationResponse.CLEAR.advance(0F, DT);
            phase = Phase.GAP;
            channel = null;
            rollRemaining = ROLL_INTERVAL_TICKS;
            return;
        }
        if (PrecipitationIntensity.forced() >= 0F) {
            // A diagnostics lock holds the whole chain still, this scheduler with
            // it - no new rolls, no fades. /dsprecip lock is there to answer
            // questions one variable at a time. The dimension gate below sits AFTER this
            // one on purpose: a lock is the maintainer asking to see a channel at a value,
            // in whatever dimension they are standing in.
            return;
        }
        if (!overworld) {
            // Neither event is an overworld-only idea in the code, but both are applied by
            // paths that run in every dimension - the sky and the fog colour of a grey day
            // are written in the Nether exactly as they are in the overworld - so an overcast
            // event that was up when the player stepped through a portal turned the Nether's
            // fog grey. That was the report.
            //
            // Walk both channels home and park the machine, the same way the disabled
            // branch above does. Parked rather than suspended: a grey day is weather in a
            // place, not a property of the player, so it does not travel. No roll happens
            // here either - the sky lottery belongs to the overworld's clock.
            PrecipitationResponse.OVERCAST.advance(0F, DT);
            PrecipitationResponse.CLEAR.advance(0F, DT);
            if (phase != Phase.GAP)
                logTrace(logger, "sky event parked: the player left the overworld");
            phase = Phase.GAP;
            channel = null;
            durationRemaining = 0;
            fadeRemaining = 0;
            suspendedTicks = 0;
            rollRemaining = ROLL_INTERVAL_TICKS;
            return;
        }

        float overcastTarget = 0F;
        float clearTarget = 0F;

        switch (phase) {
            case RUNNING -> {
                if (raining) {
                    // Yield: hand the sky to the rain (the channel's fall constant
                    // does the easing) and stop the clock. The event resumes where
                    // it left off unless the rain outlasts the suspension limit -
                    // a grey day that a two-hour storm ate entirely is over.
                    suspendedTicks++;
                    if (suspendedTicks >= SUSPEND_LIMIT_TICKS) {
                        endEvent(logger, "suspended too long by rain");
                    } else {
                        if (channel == PrecipitationResponse.OVERCAST)
                            overcastTarget = 0F;
                        else
                            clearTarget = 0F;
                    }
                } else {
                    suspendedTicks = 0;
                    durationRemaining--;
                    if (durationRemaining <= 0) {
                        phase = Phase.FADING;
                        fadeRemaining = FADE_OUT_TICKS;
                        logTrace(logger, "%s spent, fading out", channel.name());
                    } else {
                        if (channel == PrecipitationResponse.OVERCAST)
                            overcastTarget = 1F;
                        else
                            clearTarget = 1F;
                    }
                }
            }
            case FADING -> {
                // A rain arriving mid-fade just races it: rain's own darkening
                // takes the sky through max() while the fade walks to zero.
                fadeRemaining--;
                final float ramp = Math.max(0F, fadeRemaining / (float) FADE_OUT_TICKS);
                if (channel == PrecipitationResponse.OVERCAST)
                    overcastTarget = ramp;
                else
                    clearTarget = ramp;
                if (fadeRemaining <= 0)
                    endEvent(logger, "duration elapsed");
            }
            case GAP -> {
                // The lottery only runs while the weather is quiet - no event may
                // start into a rain, and anyActive() is false exactly when the
                // after-rain tail (fog, drips, wetness) has settled. The sky-event
                // channels themselves are excluded from anyActive(), which is what
                // lets an event chain exist at all. While an event is up the clock
                // does not run at all: an event owns its hours, the lottery waits.
                if (!raining && PrecipitationIntensity.ambient() < SPAWN_QUIET
                        && !PrecipitationResponse.anyActive()) {
                    rollRemaining--;
                    if (rollRemaining <= 0) {
                        rollRemaining = ROLL_INTERVAL_TICKS;
                        tryRoll(logger, traits);
                    }
                }
            }
        }

        // A channel whose event is switched off walks home instead of holding:
        // turning an event off mid-flight fades it out rather than freezing it.
        PrecipitationResponse.OVERCAST.advance(overcastAllowed() ? overcastTarget : 0F, DT);
        PrecipitationResponse.CLEAR.advance(clearAllowed() ? clearTarget : 0F, DT);
    }

    /**
     * One draw of the lottery. The profile and season give two per-roll
     * chances; a single uniform picks overcast, clear, or - most often -
     * nothing. Either way the clock re-arms for the next game hour.
     */
    private static void tryRoll(IModLog logger, BiomeTraits traits) {
        final var profile = PrecipitationProfile.resolve(traits);
        final var season = PrecipitationIntensity.seasonFor(profile);
        final float overcast = overcastAllowed() ? sanitiseChance(profile.overcastChanceFor(season)) : 0F;
        // BOTH chances are clamped: the clear one used to be capped while the overcast
        // one went through raw, so a large base chance times a 1.6 seasonal factor could
        // pass the cap and, above 1.0, turn the lottery into overcast on every roll.
        final float clear = clearAllowed() ? sanitiseChance(profile.clearChanceFor(season)) : 0F;
        final float overcastChance = Math.min(overcast, ROLL_CHANCE_CAP);
        final float clearChance = Math.min(clear, Math.max(0F, ROLL_CHANCE_CAP - overcastChance));
        final float roll = RANDOM.nextFloat();
        if (roll < overcastChance)
            beginEvent(logger, PrecipitationResponse.OVERCAST, profile, season, "rolled");
        else if (roll < overcastChance + clearChance)
            beginEvent(logger, PrecipitationResponse.CLEAR, profile, season, "rolled");
        else
            logTrace(logger, "roll came up nothing (overcast %.3f clear %.3f profile=%s season=%s)",
                    overcastChance, clearChance, profile.name(),
                    season == null ? "vanilla" : season.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static float sanitiseChance(final float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0F)
            return 0F;
        return Math.min(1F, v);
    }

    private static void beginEvent(IModLog logger, PrecipitationResponse which,
                                   PrecipitationProfile profile, PrecipitationSeason season,
                                   String why) {
        if ((which == PrecipitationResponse.OVERCAST && !overcastAllowed())
                || (which == PrecipitationResponse.CLEAR && !clearAllowed())) {
            logTrace(logger, "%s not started - disabled in config", which.name());
            return;
        }
        channel = which;
        durationRemaining = rollDuration();
        suspendedTicks = 0;
        phase = Phase.RUNNING;
        if (logger != null)
            logger.info("[SKY-EVENT] %s begins - %s, profile=%s season=%s duration=%.0fs",
                    which.name(), why, profile.name(),
                    season == null ? "vanilla" : season.name().toLowerCase(java.util.Locale.ROOT),
                    durationRemaining / 20F);
    }

    private static void endEvent(IModLog logger, String why) {
        if (logger != null && channel != null)
            logger.info("[SKY-EVENT] %s ends - %s", channel.name(), why);
        phase = Phase.GAP;
        channel = null;
        durationRemaining = 0;
        fadeRemaining = 0;
        suspendedTicks = 0;
        rollRemaining = ROLL_INTERVAL_TICKS;
    }

    // ---- manual control (the /dsprecip event command) ------------------------

    /**
     * Force-start an event by name ("overcast" / "clear"). Bypasses the roll
     * and the quiet check - started into rain the event simply yields until
     * the rain passes, which is the same thing a rolled event would do. Any
     * event already up is ended first: one sky, one event.
     *
     * @return false when the name is not an event or the feature is off
     */
    public static boolean startEvent(String name) {
        if (!enabled())
            return false;
        final PrecipitationResponse which;
        if ("overcast".equalsIgnoreCase(name))
            which = PrecipitationResponse.OVERCAST;
        else if ("clear".equalsIgnoreCase(name))
            which = PrecipitationResponse.CLEAR;
        else
            return false;
        final IModLog logger = logger();
        if (phase != Phase.GAP)
            endEvent(logger, "replaced by a manual start");
        channel = which;
        durationRemaining = rollDuration();
        suspendedTicks = 0;
        phase = Phase.RUNNING;
        rollRemaining = ROLL_INTERVAL_TICKS;
        if (logger != null)
            logger.info("[SKY-EVENT] %s begins - manual start, duration=%.0fs",
                    which.name(), durationRemaining / 20F);
        return true;
    }

    /**
     * End the event that is up, if any. The channel's own fall constant does
     * the easing, so a manual stop lands as smoothly as a natural one.
     *
     * @return true when an event was actually ended
     */
    public static boolean stopEvent() {
        if (phase == Phase.GAP || channel == null)
            return false;
        endEvent(logger(), "manual stop");
        return true;
    }

    private static int rollDuration() {
        // 1..5 game hours, uniform.
        return (int) (1000 * (1F + RANDOM.nextFloat() * 4F));
    }

    private static void logTrace(IModLog logger, String format, Object... args) {
        if (logger == null)
            return;
        logger.debug(Configuration.Flags.SKY_EVENTS, "[SKY-EVENT] " + format, args);
    }

    // ---- diagnostics --------------------------------------------------------

    /** One-line state for the command and the F3 line. */
    public static String diagnostics() {
        final String base = switch (phase) {
            case RUNNING -> channel == null ? "running"
                    : "%s running, %.0fs left%s".formatted(channel.name(),
                            durationRemaining / 20F,
                            suspendedTicks > 0 ? " (yielding to rain)" : "");
            case FADING -> channel == null ? "fading"
                    : "%s fading, %.0fs".formatted(channel.name(), fadeRemaining / 20F);
            case GAP -> "waiting, next roll in %.0fs".formatted(Math.max(0, rollRemaining) / 20F);
        };
        final long calls = advances;
        if (calls == 0L)
            return base;
        // Only accumulated while tracing, so an average without the flag is
        // stale by construction - say so rather than print a number that lies.
        return base + String.format(" (avg=%.0fns over %d traced calls)",
                advanceNanos / (double) calls, calls);
    }

    /** Per-session state reset - the events do not survive a world change. */
    public static void reset() {
        phase = Phase.GAP;
        channel = null;
        durationRemaining = 0;
        fadeRemaining = 0;
        suspendedTicks = 0;
        rollRemaining = ROLL_INTERVAL_TICKS;
    }
}
