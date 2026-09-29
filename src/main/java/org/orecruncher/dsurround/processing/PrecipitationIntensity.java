package org.orecruncher.dsurround.processing;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.seasons.SeasonManager;
import org.orecruncher.dsurround.processing.weather.PrecipitationProfile;
import org.orecruncher.dsurround.processing.weather.PrecipitationRenderer;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;
import org.orecruncher.dsurround.processing.weather.PrecipitationSeason;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>The single source of truth for "how hard is it precipitating right now".</p>
 *
 * <h2>Two different numbers live here</h2>
 *
 * <p>{@link #of(Level)} is the <em>vanilla rain level</em> - a read-only mirror of
 * what the server sent. {@link #intensity()} is <em>our graded value</em>: the
 * per-rain-event roll, shaped by the biome's profile and the season. They are not
 * interchangeable, and mixing them up is a real trap:</p>
 *
 * <ul>
 *   <li>The <b>desert sandstorm</b> must use {@link #of(Level)}. Vanilla reports
 *       {@code precipitationAt == NONE} in a desert ({@code has_precipitation} is
 *       false there, and rain is still rendered globally), so a phase-gated value
 *       would be 0 and the sandstorm - which is driven by "it is raining over a
 *       dusty place" - would never appear. The sandstorm is about dust, not about
 *       precipitation landing on that biome.</li>
 *   <li><b>Precipitation rendering and audio</b> (N4/N7) use {@link #intensity()},
 *       which is gated: NONE means nothing falls, so intensity is 0.</li>
 * </ul>
 *
 * <h2>Why every read goes through here</h2>
 *
 * <ul>
 *   <li><b>No feedback loop.</b> {@code WeatherStormHandler.dustTexture()} used to
 *       call {@code level.getRainLevel(1F)} inline. The moment anything writes a
 *       graded value back into the client {@code rainLevel}, that read becomes a
 *       loop. Routing through one choke point keeps the write side off the table.</li>
 *   <li><b>No write side.</b> The vanilla client {@code rainLevel} is a mirror of
 *       the server: {@code ServerLevel} ramps it by 0.01/tick toward the weather
 *       boolean and broadcasts {@code RAIN_LEVEL_CHANGE} on every tick it changes.
 *       A client write survives while the value is saturated but is overwritten
 *       every tick during the ~5s ramp at each weather start/stop - steady state
 *       looks fine, transitions silently break.</li>
 *   <li><b>One place to add grading.</b> Biome and season shaping already land
 *       here; altitude and anything else joins them.</li>
 * </ul>
 *
 * <h2>Division of labour with Serene Seasons</h2>
 *
 * <p>Serene Seasons already rewrites precipitation: it shifts biome temperature per
 * season (why plains get snow in winter) and gates tropical biomes on their own
 * wet/dry season. <b>We copy none of that</b> - we ask
 * {@code ISeasonalInformation.getActivePrecipitation(pos)}, which answers "is it
 * raining", "does this biome precipitate here now" (Serene Seasons overrides this
 * with {@code SeasonHooks.getPrecipitationAtSeasonal}, internally applying
 * {@code hasPrecipitationSeasonal}: biomes in its {@code tropical_biomes} tag are
 * switched by {@code TropicalSeason}, everything else falls back to vanilla), "is
 * there a block overhead", and finally rain vs snow. All we add is magnitude.</p>
 */
public final class PrecipitationIntensity {

    private static final Random RANDOM = new Random();

    /**
     * Minimum intensity for background thunder, before a profile's thunder bias is
     * applied. Taken from 1.12.2 {@code ModOptions.rain.stormThunderThreshold},
     * default 0.75F.
     */
    private static final float THUNDER_THRESHOLD = 0.75F;

    /** How far a profile's thunderBias can pull that threshold down, 0..1. */
    private static final float THUNDER_BIAS_RANGE = 0.6F;

    /**
     * Thunder level above which the world counts as storming. Low on purpose: the
     * level ramps, and waiting for it to get high would mean a thunderstorm was
     * not recognised as one until it was well underway.
     */
    private static final float THUNDER_ACTIVE_LEVEL = 0.02F;

    /** Bounds on how far the climate you are standing in may re-express the roll. */
    private static final float LOCATION_SCALE_MIN = 0.5F;
    private static final float LOCATION_SCALE_MAX = 2.5F;

    /**
     * Bounds on roll two, the per-event gustiness multiplier. Below 1 an event is
     * calmer than its profile suggests; above 1 it is more fitful. Two events with
     * the same base intensity can therefore still differ in character, which is what
     * stops the variation from reading as a single number being moved around.
     */
    private static final float GUSTINESS_MIN = 0.5F;
    private static final float GUSTINESS_MAX = 1.5F;

    /**
     * Periods of the two sines that make the sustain wobble, in seconds, and their
     * weights. Deliberately in the tens-of-seconds range: intensity is quantised
     * into eight texture levels on the way out, so a wobble that resolves faster
     * than this does not read as "the rain is easing off" - it reads as the texture
     * stepping, which is exactly the flicker that makes graded rain look broken.
     */
    private static final float GUST_PERIOD_A = 71F;
    private static final float GUST_PERIOD_B = 173F;
    private static final double GUST_WEIGHT_A = 0.65D;
    private static final double GUST_WEIGHT_B = 0.35D;

    /**
     * Where the intensity starts, as a fraction of the rolled base.
     *
     * <p>The attack used to climb from literally nothing, so on a profile with a
     * long attack the opening stretch produced rain that was present but far below
     * anything visible - the player saw sky darkening and fog closing in over an
     * empty sky. Starting at a tenth of the base means there is rain on screen
     * from the first tick and the attack spends its whole length going from
     * drizzle to the event's character, rather than spending its first third
     * crossing the invisible part of the range.</p>
     */
    private static final float ATTACK_FLOOR = 0.1F;

    /**
     * How quickly the location modulation follows a change of biome, in seconds.
     *
     * <p>Deliberately short. Smoothing here is a fallback, not the mechanism:
     * the real smoothing is spatial - {@link #sampleLocationMode} averages five
     * points spread along the direction of travel, so the value is already a
     * function of where you are rather than of how long you have been there.
     * Adding a long time constant on top of that does not make it smoother, it
     * makes it <em>late</em>: the intensity lags behind the countryside, so
     * standing at a border the weather is still catching up to a place you
     * have already left, and walking fast it trails further still.
     *
     * <p>What is left here only absorbs the residual jitter when the sample set
     * changes, which is a frame-scale problem, not a seconds-scale one.</p>
     */
    private static final float LOCATION_FOLLOW_SECONDS = 1F;

    private static final float LOCATION_ALPHA =
            (float) (1D - Math.exp(-1D / (LOCATION_FOLLOW_SECONDS * 20D)));

    /** How far ahead to look, in seconds of travel, and the bounds put on it. */
    private static final float LOOKAHEAD_SECONDS = 4F;
    private static final float LOOKAHEAD_MIN_BLOCKS = 8F;
    private static final float LOOKAHEAD_MAX_BLOCKS = 48F;

    /**
     * Where the five samples sit, as {forward, sideways, weight} multiples of the
     * look-ahead distance. The near ones dominate so standing still is stable, and
     * the two flanking ones are what give a boundary crossing a value in between
     * rather than a switch - a border that runs diagonally past the player is
     * caught by them even when the player is walking parallel to it.
     */
    private static final float[][] LOCATION_SAMPLES = {
            { 0F, 0F, 1.0F },
            { 0.5F, 0F, 0.7F },
            { 1.0F, 0F, 0.5F },
            { 0.7F, 0.6F, 0.35F },
            { 0.7F, -0.6F, 0.35F },
    };

    /**
     * Bounds roll two draws the wobble's shape from.
     *
     * <p>The floor on the period is what keeps a storm from flickering: intensity
     * is quantised into texture levels on the way out, so a wobble that resolves
     * faster than roughly this reads as the texture stepping rather than as the
     * rain changing. The ceiling is what keeps it from being a straight line - past
     * a few minutes the player has stopped watching by the time it turns around.</p>
     *
     * <p>The ratio bounds are deliberately not integers. Two periods at 1:2 or 1:3
     * produce a sum that visibly repeats, and the player gets the sense of a loop
     * rather than of weather.</p>
     */
    // Halved at the top end on purpose. At 40-200 a five minute storm holds
    // between two and eight swells, which is not weather arriving in fits - it
    // is one slow breath, and it reads as steady rain that happens to drift.
    // This range puts four to eleven swells inside the same storm, which is the
    // difference between "the rain is easing and coming back" and "the rain is
    // constant". Simulated with tools/_simulate_precip_curves.py.
    private static final float GUST_PERIOD_MIN = 28F;

    // ---- discrete squalls --------------------------------------------------
    //
    // Continuous wobble, however short its period, is still a breath: it swells
    // and subsides on a schedule and reads as steady rain that happens to
    // drift. Weather arrives in fits. So on top of the swells, an event also
    // throws discrete squalls - each with its own arrival, duration and size.
    //
    // The hard rule is that a squall must never stop the rain. The event is
    // still running, so it is still raining; a squall may take it down to a
    // drizzle but not to nothing. Downward room is capped at
    // SQUALL_MIN_FACTOR of the base for exactly that reason.

    /** Shortest and longest a single squall lasts, in seconds. */
    private static final float SQUALL_MIN_SECONDS = 14F;
    private static final float SQUALL_MAX_SECONDS = 34F;
    /** Gap between squalls, in seconds. Short enough that a storm has several. */
    private static final float SQUALL_GAP_MIN_SECONDS = 22F;
    private static final float SQUALL_GAP_MAX_SECONDS = 70F;
    /** A falling squall may not take the rain below this fraction of the base. */
    private static final float SQUALL_MIN_FACTOR = 0.45F;
    /** A squall swings the rain by this multiple of the profile's gustiness. */
    private static final float SQUALL_AMPLITUDE_SCALE = 1.6F;
    /** Ceiling on that swing as a fraction of the base, however squally. */
    private static final float SQUALL_MAX_AMPLITUDE = 0.55F;
    /** Squalls more often bring more rain than less. */
    private static final float SQUALL_HEAVY_CHANCE = 0.62F;

    /**
     * Below this, vanilla's thunder level may not add darkness.
     *
     * <p>Ours is driven by intensity alone, so folding vanilla's in at any
     * level would give one intensity two appearances - darker whenever the
     * world happened to be thundering. Above it, ours is already carrying a
     * storm, and there vanilla's full darkness is worth reaching.</p>
     */
    private static final float STORM_THUNDER_MIN = 0.25F;
    /**
     * Storm level at which vanilla's thunder is allowed in at full strength.
     * Between {@link #STORM_THUNDER_MIN} and this the contribution is faded in
     * rather than switched - see {@code stormWithVanilla}.
     */
    private static final float STORM_THUNDER_FULL = 0.70F;
    private static final float GUST_PERIOD_MAX = 120F;

    private static final float GUST_RATIO_MIN = 2.2F;
    private static final float GUST_RATIO_MAX = 3.5F;
    private static final float GUST_WEIGHT_B_MIN = 0.2F;
    private static final float GUST_WEIGHT_B_MAX = 0.5F;

    /**
     * Time constant of the output low-pass, in seconds. Everything the envelope
     * produces goes through it.
     *
     * <p>This is an anti-aliasing filter and nothing else. It used to be 3s, which
     * quietly made it the thing that decided how fast the rain was allowed to
     * change - and that is a job it cannot do, because it cannot tell a gust from
     * a discontinuity. A 3s constant against a 71s wobble is a fixed attenuation,
     * so it simply scaled the wobble down, and it would have swallowed any shorter
     * period just as readily: making roll two capable of producing a fast, fitful
     * storm would have achieved nothing, because this filter would have removed
     * exactly the thing that made it fitful.</p>
     *
     * <p>How fast the rain appears to change is now the envelope's own business -
     * the period floor on roll two, the attack length, and each derived channel's
     * time constant. This filter only guarantees that no single frame-to-frame
     * step (the attack handing over to the sustain, a jump in the vanilla rain
     * level, a level change from the texture quantiser) reaches the renderer as a
     * visible jump.</p>
     */
    private static final float SMOOTH_SECONDS = 0.4F;

    private static final double TWO_PI = Math.PI * 2D;

    /** Per-tick lerp factor of the output low-pass; see {@link #SMOOTH_SECONDS}. */
    private static final float SMOOTH_ALPHA =
            (float) (1D - Math.exp(-1D / (SMOOTH_SECONDS * 20D)));

    private static volatile PrecipitationProfile currentProfile = PrecipitationProfile.TEMPERATE;
    private static volatile Biome.Precipitation currentPhase = Biome.Precipitation.NONE;
    /** Peak intensity rolled for the rain event currently in progress. */
    private static volatile float rolled = 0F;
    /** Graded intensity in [0, 1] - see {@link #update}. */
    private static volatile float graded = 0F;
    /** Vanilla thunder level, 0..1 (already multiplied by rain level by vanilla). */
    private static volatile float thunderLevel = 0F;
    private static volatile boolean thunderPossible = false;
    private static volatile boolean eventActive = false;
    /** The season column used for the roll in progress - null when no season data. */
    private static volatile PrecipitationSeason currentSeason = null;
    /**
     * Forced intensity override, diagnostics only. Negative means "not locked";
     * a locked value pins both {@link #rolled} and {@link #graded} so one level
     * can be held steady and compared against another.
     */
    private static volatile float forced = -1F;

    /** Game time the attack started at; only meaningful during an event. */
    private static volatile long attackStartTick = 0L;
    /** Per-event phase offset so no two storms gust alike. */
    private static volatile double gustPhase = 0D;

    /**
     * Roll two, part of the shape: the main gust period of this event, in seconds.
     *
     * <p>Rolled per event rather than fixed. A constant period means every storm a
     * profile produces breathes at exactly the same rate, and a viewer who has
     * watched one has watched them all - the wobble varies in size but never in
     * character.</p>
     */
    private static volatile float gustPeriodA = GUST_PERIOD_A;

    /**
     * Roll two, part of the shape: the secondary period, in seconds. Kept at a
     * non-integer ratio to the main one so the sum does not visibly repeat inside
     * one storm.
     */
    private static volatile float gustPeriodB = GUST_PERIOD_B;

    /** Roll two, part of the shape: weight of the secondary harmonic. The main
     * harmonic gets {@code 1 - this}. More weight reads as a more unsettled event. */
    private static volatile float gustWeightB = (float) GUST_WEIGHT_B;

    /**
     * Roll two, part of the shape: how squally the wobble is, 0..1.
     *
     * <p>0 leaves the sum as sines - a slow swell, the steady soak. 1 pushes it
     * towards a square wave, which is what a convective shower actually does: it
     * arrives, it stops, it arrives again. Soft-clipped rather than hard, so the
     * value stays continuous - a hard square wave would step, and the whole point
     * of the wobble is that it never steps.</p>
     */
    private static volatile float gustSharpness = 0F;

    /** Tick the running squall began, or {@link Long#MIN_VALUE} when none is. */
    private static volatile long squallStartTick = Long.MIN_VALUE;
    /** Length of the running squall in ticks, and its size, signed. */
    private static volatile float squallDurationTicks = 0F;
    private static volatile float squallGain = 0F;
    /** Tick the next squall is due, or {@link Long#MIN_VALUE} if unscheduled. */
    private static volatile long nextSquallTick = Long.MIN_VALUE;
    /** Last shaped value - held through the release so the vanilla ramp can fade it. */
    private static volatile float lastEnvelope = 0F;
    /** Roll two itself: how fitful this particular event is, roughly 0.5..1.5. */
    private static volatile float gustiness = 1F;
    /** Roll two resolved into an amplitude: profile response curve x gustiness. */
    private static volatile float gustAmplitude = 0F;
    /**
     * The profile's mode at the position and season the event was rolled at. The
     * rolled base is relative to this, so it is what lets the same base be
     * re-expressed when the player walks somewhere with a different climate.
     */
    private static volatile float rollMode = 0F;
    /** The profile's mode where the player is now, low-passed - see LOCATION_FOLLOW_SECONDS. */
    private static volatile float locationMode = 0F;
    /**
     * Whether the world is thundering. Read from the thunder <em>level</em>, never
     * from {@code Level.isThundering()} - on the client that returns a literal
     * false (ClientLevelData has no thundering field at all; the method is
     * {@code iconst_0; ireturn}). This is the same trap as {@code isRaining()},
     * third time it has come up: a boolean that looks like state but is not,
     * while the float beside it is the thing actually kept in sync.
     */
    private static volatile boolean stormy = false;
    /** Attack length of the event in progress, in ticks. */
    private static volatile float attackTicks =
            PrecipitationProfile.DEFAULT_ATTACK_SECONDS * 20F;
    /** Low-passed intensity - the value actually handed to the renderer. */
    private static volatile float smoothed = 0F;
    /**
     * The same shaped intensity without the phase gate. Sky, fog and sound read
     * this one: they describe the weather around the player, which does not stop
     * because the player stepped under a roof. See {@code PrecipitationResponse}.
     */
    private static volatile float ambient = 0F;

    /**
     * Whether the event in progress was carried over from a previous session
     * rather than rolled in this one - see {@link #restoreEvent}.
     */
    private static volatile boolean eventRestored = false;
    /** Set when a restored event needs to appear at its level without ramping up. */
    private static volatile boolean warmStart = false;

    /**
     * Client level last seen by {@link #update}. Ownership has to be answerable
     * from the render thread, which is not the same moment as the tick - see
     * {@link #takeoverReady()}.
     */
    private static volatile Level clientLevel = null;

    /**
     * Previous tick's vanilla rain level. Only {@link #update} advances it - see
     * {@link #weatherRising}, which needs to know which way it moved.
     */
    private static volatile float lastRamp = 0F;

    /**
     * The dead-band the ramp is compared against - and, the same number, the level
     * below which the ramp counts as dry. One number on purpose: the vanilla ramp
     * steps by 0.01 per tick and arrives at zero through the float32 residue of a
     * hundred such steps, so its last move is worth about 1e-7 (it is the residue
     * that gets clamped, not a real step). That is far below any dead-band that
     * can still separate a real step from a repeated reading, so "did the ramp
     * move" can never be the test for "is it dry". See {@link #weatherRising} -
     * this was the whole bug: a ramp that had already gone dry was judged flat,
     * flat was answered with the latched event flag, and the event became
     * immortal, so its peak was never re-rolled.
     */
    private static final float RAMP_EPSILON = 1e-4F;

    /**
     * The rain level at which vanilla itself calls a world rainy:
     * {@code Level.isRaining()} is {@code getRainLevel(1F) > 0.2F}. It separates
     * "the player arrived and it was already raining" - the one case a persisted
     * event may be resumed for - from "a storm is starting right now". A storm
     * that starts in front of the player begins at a ramp of about zero and
     * climbs, so a ramp already at a fifth or more means this player did not see
     * it begin.
     */
    private static final float RESUME_RAMP_MIN = 0.2F;

    /** What the last {@link #weatherRising} call decided. Diagnostics only. */
    private static volatile boolean lastRising = false;

    /** How far the attack has progressed, 0..1. Drives the sun - see {@code SUN}. */
    private static volatile float attackProgress = 0F;

    private PrecipitationIntensity() {
    }

    // ---- dust storm -------------------------------------------------------
    //
    // A desert cloudburst delivers almost no water but is still a violent event,
    // and what the player sees of it is dust, not rain. So the event carries two
    // magnitudes: the rain roll (ARID, essentially zero) and this one. They share
    // the attack, the gusting and the release, because they are one weather event
    // - only the rolled base differs. See PrecipitationProfile.DUST_STORM.

    /** The dust profile for the player's biome, or null where there is no dust. */
    private static PrecipitationProfile dustProfile = null;
    /** This event's rolled dust magnitude, 0..1. 0 when the biome has no dust. */
    private static float dustBase = 0F;
    /** Low-passed dust value, mirroring {@code smoothed}. */
    private static float dustSmoothed = 0F;
    /** Dust storm intensity after the vanilla ramp, 0..1. Read by the sandstorm. */
    private static volatile float dustIntensity = 0F;

    /**
     * The vanilla rain level, 0..1. Read-only mirror of the server value - see the
     * class comment for why the sandstorm must use this and not {@link #intensity()}.
     *
     * @param level the client level
     * @return vanilla rain level in [0, 1]
     */
    public static float of(Level level) {
        // TODO(N14): dimensions where Level.canHaveWeather() is false (nether, end)
        // keep rainLevel at 0. DSR still shows dust there via WeatherSyncState.
        return level.getRainLevel(1F);
    }

    /**
     * Our graded precipitation intensity, 0..1. Zero when nothing is falling at the
     * player's position (dry season, no-precipitation biome, or not raining).
     *
     * @return graded intensity in [0, 1]
     */
    public static float intensity() {
        return graded;
    }

    /**
     * Whether we should be suppressing vanilla precipitation right now.
     *
     * <p>This is deliberately <em>not</em> {@code intensity() &gt; 0}, and it is
     * deliberately <em>not</em> a flag the tick latched. Both of those were
     * tried and both raced with the frame:</p>
     *
     * <ul>
     *   <li>Gating on intensity let vanilla keep drawing for the first ticks of
     *       every event - the rain was visibly already falling before we took it
     *       over, and then it restarted from nothing.</li>
     *   <li>Gating on a tick-latched {@code eventActive} meant the render thread
     *       could ask this question <em>before</em> the tick that sets it had
     *       run. That window is only one tick, but it is exactly the window in
     *       which the vanilla rain level starts climbing from zero, so the fog
     *       - which falls back to the vanilla rain level whenever we say we do
     *       not own the frame - tightened on vanilla's schedule and then dropped
     *       back to ours a moment later. That is the "sudden tighten, then the
     *       curve takes over" step: not two threads writing the same field, but
     *       two <em>answers</em> to "who owns this frame" disagreeing by one
     *       tick.</li>
     *   <li>Using the phase-gated {@code graded} for the release dropped
     *       ownership whenever the player stepped under a roof, because nothing
     *       is landing on them there. The weather around them had not stopped.</li>
     * </ul>
     *
     * <p>So the answer is read live from the client level, and the release is
     * driven by the ambient chain rather than by the phase gate. The asymmetry
     * on the way out is the point: an event starting owns the frame immediately,
     * but an event that has just ended keeps owning it until <em>every</em>
     * consumer of the curve has actually finished with it - see
     * {@code PrecipitationResponse.anyActive()}. Releasing the instant the
     * intensity touches zero is what made rain fog dissipate in a step: the fog
     * channel lags the intensity by design, so at that moment it was still
     * holding a value and the handover threw it away.</p>
     *
     * @return true when vanilla precipitation must stand down
     */
    public static boolean takeoverReady() {
        // A diagnostics lock always owns the frame, including lock 0, which is how
        // "no rain at all" is demonstrated.
        if (forced >= 0F)
            return true;
        return rainingNow()
                || eventActive
                || ambient > 0F
                || PrecipitationResponse.anyActive();
    }

    /**
     * How many times the sky brightness route has been consulted. Stays 0 when the
     * injection did not land - a renamed {@code renderSky}, or a rewritten render
     * pipeline - which is the only way to tell "the sky is following our curve"
     * apart from "we think it is". Incremented at the top of
     * {@link #sunAlphaRainLevel}, before the ownership check, so it counts every
     * consultation including the ones that hand vanilla's value straight back.
     */
    private static volatile int skyHooks = 0;
    /** As {@link #skyHooks}, for the sky *colour* mixin. */
    private static volatile int skyColorHooks = 0;
    /** As {@link #skyHooks}, for the cloud *colour* mixin. */
    private static volatile int cloudColorHooks = 0;
    /**
     * Each sample point's last known profile mode. See sampleLocationMode: holding
     * these is what keeps the average's denominator fixed.
     */
    private static final float[] lastSampleModes = new float[LOCATION_SAMPLES.length];
    private static volatile boolean samplesPrimed = false;

    /**
     * The last value actually handed to the sky colour route. Reported by the
     * diagnostics because the channel value alone lies: the route falls back to
     * vanilla's rain level whenever ownership is not ours, so a perfectly smooth
     * SKY can still be reaching the renderer interleaved with a very different
     * number. This is the value to watch when the sky misbehaves.
     */
    private static volatile float lastSkyColorOut = -1F;

    // ---- Frame-level jump diagnostics -----------------------------------------
    //
    // The channels are all low-passed, so a frame-to-frame jump in the value handed
    // to vanilla means the route changed (ownership flipped, a fallback fired, or
    // vanilla itself stepped), never that the curve did. The detector below compares
    // consecutive values at the exact point they are returned to the renderer and
    // dumps the full context - camera biome, player biome, the five location samples
    // and their modes - so a visible pop can be traced to its cause without a
    // debugger.

    /** Logger captured from {@link #update}; null until the first tick. */
    private static volatile IModLog diagLogger = null;
    /** Last value returned to the sky colour route, and whether we owned the frame. */
    private static float lastReportedSkyOut = -1F;
    private static boolean lastReportedSkyOwner = false;
    /** Cooldown so a flicker storm produces one detailed line, not sixty. */
    private static int skyJumpCooldown = 0;
    /** Frame counter for the periodic "everything is fine" line. */
    private static int skyFrameLogCounter = 0;
    /** As {@link #lastReportedSkyOut}, for the fog colour route. */
    private static float lastReportedFogOut = -1F;
    private static int fogJumpCooldown = 0;
    /** Biome id under each of the five location samples, refreshed per tick. */
    private static final Object[] lastSampleBiomes = new Object[LOCATION_SAMPLES.length];

    /** As {@link #skyHooks}, for the fog *colour* mixin. */
    private static volatile int fogColorHooks = 0;
    /** As {@link #skyHooks}, for the thunder (second desaturation) mixins. */
    private static volatile int stormHooks = 0;

    /** Called by the sky colour mixin. */
    public static void noteSkyColorHook() {
        skyColorHooks++;
    }

    /**
     * Log entry point for the 26.1 colour route, which has no mixin of its own
     * to hang a logger on - {@code SkyStateInjector} is a plain render-state
     * listener. Null-op when the handler has not handed us a logger yet.
     */
    public static void diagLog(String format, Object... args) {
        final IModLog log = diagLogger;
        if (log != null)
            log.info(format, args);
    }

    /** Called by the cloud colour mixin. */
    public static void noteCloudColorHook() {
        cloudColorHooks++;
    }

    /** Called by the fog colour mixin. */
    public static void noteFogColorHook() {
        fogColorHooks++;
    }

    /**
     * How the climate the player is standing in re-expresses the rolled base.
     *
     * <p>The roll itself is fixed for the whole event - that is what makes it an
     * event rather than a per-biome lookup - but it was drawn against the mode
     * where it started, so the same roll is worth more in a jungle and less in
     * dry country. This is that factor.</p>
     */
    /**
     * Size of the running squall at this instant, folded into the intensity.
     *
     * <p>The envelope is {@code sin(pi * p)}: it leaves zero at zero slope,
     * peaks once, and returns to zero at zero slope. A squall therefore cannot
     * pop into or out of existence, which is the one thing it must never do.</p>
     *
     * <p>Direction matters asymmetrically, and not in the way the continuous
     * wobble does. Upward, a squall may use nearly all the remaining room -
     * that is what makes a storm suddenly hammer. Downward it may only use the
     * room above {@code base * SQUALL_MIN_FACTOR}, because going further would
     * stop the rain, and the rain cannot stop while the event is running. A
     * squall can reduce the rain to a drizzle. It cannot end it.</p>
     */
    private static float applySquall(float current, float base, long gameTime) {
        if (squallStartTick == Long.MIN_VALUE || squallDurationTicks <= 0F)
            return current;
        final float p = (float) (gameTime - squallStartTick) / squallDurationTicks;
        if (p < 0F || p > 1F)
            return current;
        final float shape = (float) Math.sin(Math.PI * p);
        // Relative to what the rain already is, and only then bounded by the
        // room in each direction. Scaling by the room first was the bug: at
        // base 0.45 there is 0.55 of room, so a squall reached 0.9 - the rain
        // doubled, which reads as a second weather event rather than as a
        // squall inside this one.
        final float amplitude = base
                * Math.min(gustAmplitude * SQUALL_AMPLITUDE_SCALE, SQUALL_MAX_AMPLITUDE);
        // Rooms are bounded against the value the squall actually lands on (the
        // wobble-adjusted target), not against base: bounding both against base
        // double-counted the room the wobble had already spent, and a negative
        // squall aligned with a wobble trough could clamp the rain to zero
        // mid-event - breaking the SQUALL_MIN_FACTOR floor.
        final float up = Math.min(amplitude, 1F - current);
        final float down = Math.min(amplitude, Math.max(0F, current - base * SQUALL_MIN_FACTOR));
        final float delta = squallGain >= 0F ? squallGain * up : squallGain * down;
        return current + delta * shape;
    }

    /**
     * Starts and ends squalls. Called once per tick from {@link #update}, which
     * is where the level - and therefore the game time - is available.
     */
    private static void advanceSqualls(Level level) {
        final long now = level.getGameTime();
        if (squallStartTick == Long.MIN_VALUE) {
            if (nextSquallTick == Long.MIN_VALUE) {
                nextSquallTick = now + (long) (nextSquallGap() * 20F);
                return;
            }
            if (now < nextSquallTick)
                return;
            squallStartTick = now;
            squallDurationTicks = (SQUALL_MIN_SECONDS
                    + RANDOM.nextFloat() * (SQUALL_MAX_SECONDS - SQUALL_MIN_SECONDS)) * 20F;
            squallGain = RANDOM.nextFloat() < SQUALL_HEAVY_CHANCE
                    ? RANDOM.nextFloat()
                    : -RANDOM.nextFloat();
            return;
        }
        if (now - squallStartTick >= squallDurationTicks) {
            squallStartTick = Long.MIN_VALUE;
            nextSquallTick = now + (long) (nextSquallGap() * 20F);
        }
    }

    private static float nextSquallGap() {
        return SQUALL_GAP_MIN_SECONDS
                + RANDOM.nextFloat() * (SQUALL_GAP_MAX_SECONDS - SQUALL_GAP_MIN_SECONDS);
    }

    /** Forget any squall in progress; used when an event starts or is restored. */
    private static void resetSqualls() {
        squallStartTick = Long.MIN_VALUE;
        nextSquallTick = Long.MIN_VALUE;
        squallGain = 0F;
    }

    private static float locationScale() {
        if (rollMode <= 0.01F)
            return 1F;
        // Bounded, and this bound is the fix for a sky that flickered. The raw
        // ratio is a division by the mode where the roll happened, and that mode
        // can be tiny - an arid profile sits near 0.02 - so a storm rolled in dry
        // country and then walked into a rainforest produced a ratio above 40.
        // The base clamps to 1, so the storm simply went to full intensity the
        // moment the samples crossed, and hovered there: no gradient, just on and
        // off, and visibly darker. Worse, near a border the samples cross back
        // and forth, so it turned on and off repeatedly.
        //
        // Clamping is also the honest model. The roll says how heavy THIS event
        // is; where you are standing scales that, and it does not scale it by
        // forty.
        return Mth.clamp(locationMode / rollMode, LOCATION_SCALE_MIN, LOCATION_SCALE_MAX);
    }

    // skyRainLevel() - the max(SKY, SUN) patch - had no consumer: renderSky feeds
    // on sunAlphaRainLevel() (SUN alone) and the sky colour on skyColorRainLevel()
    // (SKY alone). Removed along with PrecipitationResponse.skyRainLevel().
    /**
     * The rain level the sun, moon and stars fade by.
     *
     * <p>{@code renderSky} reads the rain level once, computes {@code 1 - x}, and
     * pushes it through {@code setShaderColor} immediately before drawing
     * SUN_LOCATION - so that number drives the celestial bodies and nothing else.
     * The sky quad was already drawn earlier, from {@code getSkyColor}. The two
     * are separate routes with separate meanings, and only this one should follow
     * {@code SUN}: the sun goes away when the cloud arrives and then stays away,
     * which is what {@code SUN} models.</p>
     */
    public static float sunAlphaRainLevel(Level level, float partialTick) {
        // Counted before the ownership check, exactly like the colour routes: the
        // question is whether this version's sky route runs at all, not whether we
        // happened to own the frame.
        skyHooks++;
        if (!ownsAmbient())
            return level.getRainLevel(partialTick);
        return PrecipitationResponse.SUN.value();
    }

    /**
     * The rain level the <em>cloud colour</em> should be computed from - CLOUD
     * alone, for the same reason the sky colour takes SKY alone.
     */
    public static float cloudColorRainLevel(Level level, float partialTick) {
        cloudColorHooks++;
        if (!ownsAmbient())
            return level.getRainLevel(partialTick);
        return PrecipitationResponse.CLOUD.value();
    }

    /**
     * The rain level the sky <em>colour</em> should be computed from.
     *
     * <p>Deliberately {@code SKY} alone and not {@code max(SKY, SUN)}. Those two
     * mean different things: {@code SUN} is the sun going away, which by design
     * reaches full at the end of the attack and then holds - a storm does not
     * re-expose the sun every time the intensity dips. {@code SKY} is how dark
     * the air is, and that has to keep tracking the intensity for the whole
     * event. Folding them together with a max pinned the sky colour at 1.0 the
     * moment the attack finished, so the sky stopped reflecting the weather
     * entirely and just sat at full dark.</p>
     *
     * <p>{@code renderSky} still takes the combined value, because the number it
     * reads drives the sun, moon and star alpha, and those really should follow
     * whichever is worse. The colour is a separate route with a separate
     * meaning, which is why it gets its own interface.</p>
     */
    public static float skyColorRainLevel(Level level, float partialTick) {
        skyColorHooks++;
        final float out = ownsAmbient()
                ? PrecipitationResponse.SKY.value()
                : level.getRainLevel(partialTick);
        lastSkyColorOut = out;
        noteSkyColorFrame(level, partialTick, out);
        return out;
    }

    /**
     * The rain level the world light should be dimmed by.
     *
     * <p>Its own channel rather than the sky's: vanilla feeds one number to
     * both, which is convenient and unhelpful - it means the light can never be
     * inspected or tuned without moving the colour too. Separate channel, same
     * shaping, so nothing changes until someone wants it to.</p>
     */
    public static float skyLightRainLevel(Level level, float partialTick) {
        if (!ownsAmbient())
            return level.getRainLevel(partialTick);
        return PrecipitationResponse.SKY_LIGHT.value();
    }

    // ---- rain sound and ground splash (N7) ---------------------------------
    //
    // Vanilla's rain sound is not a loop. tickRain counts frames and fires a
    // two second one-shot about 6.9 times a second, so roughly fourteen of them
    // overlap at any moment. That gives us two independent knobs that vanilla
    // welds into one: how often a voice starts, and how loud it is. Light rain
    // is a handful of voices, heavy rain is the full fourteen - which is the
    // part vanilla cannot express, because its only input is rainLevel and that
    // number is near 1 far too early.

    /**
     * Expected number of overlapping vanilla rain voices at full rate.
     *
     * <p>6.9 plays per second (the {@code random.nextInt(3)} gate over one
     * decrement per tick gives an expected 2.89 ticks between fires) times a
     * ~2.0 second clip. Used only to work out how much of the stack we removed,
     * so its error bars are wide and that is fine.</p>
     */
    private static final float VANILLA_RAIN_OVERLAP = 13.8F;

    /** Play-rate floor. At 0.15 the stack is about two voices - sparse but present. */
    private static final float RAIN_AUDIO_DENSITY_MIN = 0.15F;

    /** Diagnostics: how many times vanilla offered to play a rain one-shot. */
    private static int rainAudioOpportunities = 0;

    /** Diagnostics: how many of those we actually let through. */
    private static int rainAudioPlayed = 0;

    /**
     * The rain level the ground splash and the rain sound density follow.
     *
     * <p>{@code SPLASH} and not {@code intensity()}: it is derived from
     * {@code GEOMETRY} precisely so that what you hear and what you see land on
     * the player together. Reading the raw intensity here would give the sound
     * its own clock, which is the one artefact a graded storm cannot survive.</p>
     */
    public static float splashRainLevel(Level level, float partialTick) {
        if (!ownsAmbient())
            return level.getRainLevel(partialTick);
        return PrecipitationResponse.SPLASH.value();
    }

    /**
     * Fraction of vanilla's ~6.9 plays per second that should actually be let
     * through, 0..1.
     *
     * <p>Light rain is sparse but still continuous - it never goes to zero, or
     * the rain would switch off between drops. It also never reaches vanilla's
     * full rate at low intensity: at full rate the stack is fourteen voices, and
     * fourteen voices at drizzle volume is a downpour that forgot to get loud.</p>
     */
    public static float rainAudioDensity() {
        if (!ownsAmbient())
            return 1F;
        final float splash = PrecipitationResponse.SPLASH.value();
        return RAIN_AUDIO_DENSITY_MIN + (1F - RAIN_AUDIO_DENSITY_MIN) * splash;
    }

    /**
     * The {@code AUDIO} channel value at which the rain should sound exactly as
     * loud as vanilla's rain: {@code 0.625 ^ 0.85 = 0.6704}, i.e. 1.12.2's
     * {@code heavy} tier.
     *
     * <p>Vanilla's rain sound is calibrated for "a rainstorm" and plays at that
     * level whenever it rains at all - it has no grading. Our curve used to reach
     * vanilla parity only at {@code intensity == 1.0}, so everything below
     * torrential came out quieter than the vanilla rain the player is used to
     * (measured: -4.5 dB at heavy). Anchoring parity at the {@code heavy} tier
     * instead says what it should say: heavy and above are all "a real
     * rainstorm", and only the tiers below it are quieter.</p>
     */
    private static final float RAIN_AUDIO_PARITY = 0.6704F;

    /**
     * How far above vanilla the very top of the curve goes, 0 = no headroom.
     * A torrential downpour should read as <em>more</em> than vanilla's single
     * rainstorm level, not merely equal to it: +35% is about +2.6 dB.
     */
    private static final float RAIN_AUDIO_HEADROOM = 0.35F;

    /**
     * Multiplier on vanilla's hard-coded 0.1 / 0.2 rain volumes.
     *
     * <p>Three terms. The first is the level curve (1.12.2's
     * {@code 0.05 + 0.95 * x}; the floor keeps a drizzle audible instead of fading
     * to nothing) evaluated on {@code x} normalized so parity lands at
     * {@link #RAIN_AUDIO_PARITY} rather than at 1.0. The second is the headroom
     * above that point. The third pays back the loudness lost by thinning the
     * stack, so the knobs move independently: at full density the compensation is
     * exactly 1 and the volume is vanilla's own number, and it rises only as the
     * stack thins out.</p>
     *
     * <p>Normalizing the <em>channel</em> value rather than the raw intensity is
     * deliberate. {@code AUDIO} is low-passed (rise 1s / fall 2.5s / release 4s)
     * on purpose - see {@link #rainAudioCalmBlend} - so that the sound does not
     * follow the per-frame gust noise in the geometry channels. Reading the raw
     * intensity here would reintroduce that breathing into the loudness.</p>
     */
    public static float rainAudioVolumeScale() {
        if (!ownsAmbient())
            return 1F;
        final float level = PrecipitationResponse.AUDIO.value();
        final float overlap = Math.max(VANILLA_RAIN_OVERLAP * rainAudioDensity(), 0.5F);
        final float compensation = (float) Math.sqrt(VANILLA_RAIN_OVERLAP / overlap);
        final float normalised = Math.min(1F, level / RAIN_AUDIO_PARITY);
        final float headroom = 1F + RAIN_AUDIO_HEADROOM
                * Math.max(0F, (level - RAIN_AUDIO_PARITY) / (1F - RAIN_AUDIO_PARITY));
        return (0.05F + 0.95F * normalised) * headroom * compensation;
    }

    /**
     * Our own rain one-shot - the calm layer. Ported from 1.12.2 (rain_calm1-4;
     * that project is MIT and the clip carries no third-party credit, unlike
     * every sound OreCruncher took from elsewhere, which is why it is safe to
     * bring over). It is thin and distant where the vanilla clip is thick and
     * close, which is the entire reason to have it: light rain is a different
     * sound, and turning the vanilla clip down does not turn it into one.
     */
    private static final net.minecraft.sounds.SoundEvent RAIN_CALM_SOUND =
            net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("dsurround", "rain"));

    public static net.minecraft.sounds.SoundEvent rainCalmSound() {
        return RAIN_CALM_SOUND;
    }

    /**
     * How much louder the calm clip has to be played to sit next to the vanilla
     * one. Measured: rain_calm RMS -46 dB against vanilla rain1-8 at -27 dB, so
     * equal loudness would be +19 dB. Not that much, for two reasons - at these
     * base volumes +19 dB pushes a one-shot past 1.0, and a drizzle is supposed
     * to be quieter than a downpour anyway. +13 dB closes most of the gap and
     * leaves the rest to the level curve.
     */
    private static final float RAIN_CALM_GAIN = 4.47F;

    public static float rainCalmGain() {
        return RAIN_CALM_GAIN;
    }

    /**
     * Fraction of the rain one-shots that should be the heavy (vanilla) clip
     * rather than the calm one, 0..1.
     *
     * <p>A per-one-shot probability, not a filter and not two clips playing at
     * once. The stack is made of individual drops, so choosing which clip each
     * drop is <em>is</em> the crossfade: a drizzle is mostly calm drops with the
     * occasional heavy one, a downpour is the other way round, and the middle
     * is a genuine mix. It costs nothing - the same number of voices, no
     * filtering - which is the only reason this is the way the blend is done.</p>
     *
     * <p>Driven by {@code AUDIO} and not {@code SPLASH} so that the texture and
     * the loudness agree: the density knob is what landing rain looks like,
     * this one is what the weather sounds like.</p>
     */
    public static float rainAudioCalmBlend() {
        if (!ownsAmbient())
            return 1F;
        return Mth.clamp(PrecipitationResponse.AUDIO.value(), 0F, 1F);
    }

    /**
     * Dither state for {@link #nextRainAudioHeavy()}. Not part of any curve -
     * it is the fractional carry that keeps the clip mix honest.
     */
    private static float rainTexturePhase = 0F;

    /**
     * Whether the next rain one-shot should be the heavy (vanilla) clip rather
     * than the calm one.
     *
     * <p>An independent coin flip gets the ratio right on average and wrong in
     * exactly the short runs a listener actually hears: at a fifth heavy, three
     * heavy drops in a row turns up every fifteen seconds or so, and when the
     * stack is thin - two or three voices in a drizzle - that is a drizzle that
     * briefly becomes a downpour. So the mix is dithered instead of drawn: the
     * blend is accumulated and one heavy drop is emitted each time the running
     * total crosses one. Heavy drops then arrive at the rate that was asked for
     * instead of in clumps - roughly spaced, never three in a row - which is
     * the difference between an intensity change sounding like the weather
     * filling in and sounding like a switch.</p>
     */
    public static boolean nextRainAudioHeavy() {
        if (!ownsAmbient())
            return true;
        // Jittered, and the jitter averages to exactly 1. A bare accumulator
        // with a steady blend emits on precisely every Nth one-shot, and at
        // vanilla's offer rate that is a heavy drop several times a second on a
        // fixed beat - a rhythm, not a texture, and one that only shows up
        // because the blend sits still between intensity changes. Scaling the
        // step by a mean-1 random leaves the long-run mix equal to the blend
        // (Wald's identity: mean step / 1 = emissions per one-shot) while the
        // spacing stops being regular.
        rainTexturePhase += rainAudioCalmBlend() * 2F * ThreadLocalRandom.current().nextFloat();
        if (rainTexturePhase >= 1F) {
            rainTexturePhase -= 1F;
            return true;
        }
        return false;
    }

    /** Counts a vanilla offer to play a rain one-shot, and records the answer. */
    public static void noteRainAudio(boolean played) {
        rainAudioOpportunities++;
        if (played)
            rainAudioPlayed++;
    }

    /** Diagnostics: {@code played/offered} rain one-shots since the last reset. */
    public static String rainAudioDiagnostics() {
        return rainAudioPlayed + "/" + rainAudioOpportunities;
    }

    // ---- channel locks -----------------------------------------------------
    //
    // Pinning a channel is the only way to answer "at one intensity, why do
    // these two disagree". Hold them one at a time and the disagreement stops
    // being a matter of opinion.

    /** Locks a channel by name. Returns false if the name is not one. */
    public static boolean lockChannel(String name, float value) {
        return forChannel(name, c -> c.lock(value));
    }

    /** Releases one channel, or every channel when name is null. */
    public static boolean unlockChannel(String name) {
        if (name == null) {
            for (var c : PrecipitationResponse.values())
                c.unlock();
            return true;
        }
        return forChannel(name, c -> c.unlock());
    }

    public static String lockedChannels() {
        final StringBuilder sb = new StringBuilder();
        for (var c : PrecipitationResponse.values()) {
            if (c.isLocked()) {
                if (sb.length() > 0)
                    sb.append(",");
                sb.append(c.name()).append("=").append(String.format("%.2f", c.value()));
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static boolean forChannel(String name, java.util.function.Consumer<PrecipitationResponse> action) {
        final PrecipitationResponse c = PrecipitationResponse.find(name);
        if (c == null)
            return false;
        action.accept(c);
        return true;
    }

    /**
     * The rain level the fog <em>colour</em> should be computed from.
     *
     * <p>Note this is the colour only. Fog distance already reads
     * {@link PrecipitationResponse#FOG_DIST}; this is the other half of the same
     * air, derived from it with no delay so the two cannot disagree. Vanilla
     * darkens the fog by the rain level twice over - once through the sky colour
     * it starts from, once directly - so both routes have to be ours or the
     * result is our value fighting vanilla's.</p>
     */
    public static float fogColorRainLevel(Level level, float partialTick) {
        fogColorHooks++;
        final float out = ownsAmbient()
                ? PrecipitationResponse.FOG_COLOR.value()
                : level.getRainLevel(partialTick);
        noteFogColorFrame(level, partialTick, out);
        return out;
    }

    /**
     * The thunder level the sky should be desaturated with.
     *
     * <p>Vanilla applies a second desaturation pass for thunder on top of the one
     * for rain, and it is that second pass - luminance cut to 0.2 rather than 0.6
     * - which makes a thunderstorm look like night. Handing it our own storminess
     * is what lets a genuinely heavy storm reach that darkness.</p>
     *
     * <p>Vanilla thunder is not replaced, only floored: where the world really is
     * thundering, its level still wins. Otherwise a locked or dry-weather state
     * would <em>remove</em> a thunderstorm's darkness, which is not ours to
     * remove.</p>
     */
    /**
     * Storm darkening, with vanilla's own kept for the heavy end.
     *
     * <p>See {@code STORM_THUNDER_MIN}. When we are not owning the frame this
     * returns vanilla's level untouched, so nothing changes for anyone not
     * using the graded system.</p>
     */
    /**
     * How much of the storm darkening survives when the precipitation is snow.
     * See the damping in {@link #stormWithVanilla}.
     */
    private static final float SNOW_STORM_FACTOR = 0.35F;

    private static float stormWithVanilla(Level level, float partialTick) {
        float ours = PrecipitationResponse.STORM.value();
        // Snow is stratiform - it has no convective tower - so a heavy snowfall
        // must not earn thunderstorm-grade extra darkening just because STORM is
        // ATMOSPHERE^4 and crosses the blend band. Damped, not zeroed: thundersnow
        // exists. Maintainer ruling 2026-09-27.
        if (currentPhase == Biome.Precipitation.SNOW)
            ours *= SNOW_STORM_FACTOR;
        final float vanilla = level.getThunderLevel(partialTick);
        if (vanilla <= ours)
            return ours;
        // Faded in over a band, not switched on at a threshold.
        //
        // The old form was `if (ours < MIN) return ours; return max(ours,
        // vanilla);`. That is a genuine step: the frame the storm channel
        // crosses MIN the answer jumps from `ours` to `max(ours, vanilla)`,
        // which with vanilla at full thunder is a whole unit in one frame -
        // on screen, a flash. Worse, the storm channel keeps moving, so an
        // intensity sitting near MIN stepped it on and off repeatedly.
        //
        // Lowering MIN would only move where it flashes. The defect is the
        // discontinuity, not its position. Smoothstepping the extra darkness
        // makes the result continuous in `ours` and in `vanilla`, so nothing
        // can pop however fast either of them moves - and because the fade
        // starts well below MIN, mid-strength storms get some of the extra
        // darkness instead of none of it.
        final float t = Mth.clamp((ours - STORM_THUNDER_MIN)
                / (STORM_THUNDER_FULL - STORM_THUNDER_MIN), 0F, 1F);
        return ours + (vanilla - ours) * t * t * (3F - 2F * t);
    }

    public static float stormRainLevel(Level level, float partialTick) {
        stormHooks++;
        if (!ownsAmbient())
            return level.getThunderLevel(partialTick);
        // Ours alone. Taking the larger of this and vanilla thunder level gave
        // one intensity two appearances: the same roll read darker whenever the
        // world happened to be thundering, which is not what thundering means.
        // Thundering means lightning and the sound of it; how dark the sky gets
        // is the intensity job, and STORM already does it from intensity.
        return stormWithVanilla(level, partialTick);
    }

    /** Diagnostics only: how many times the sky mixin has been consulted. */
    public static int skyHooks() {
        return skyHooks;
    }

    /** Diagnostics only: how many times the sky colour mixin has been consulted. */
    public static int skyColorHooks() {
        return skyColorHooks;
    }

    /** Diagnostics only: how many times the cloud colour mixin has been consulted. */
    public static int cloudColorHooks() {
        return cloudColorHooks;
    }

    /** Diagnostics only: how many times the fog colour mixin has been consulted. */
    public static int fogColorHooks() {
        return fogColorHooks;
    }

    /** Diagnostics only: how many times the thunder mixins have been consulted. */
    public static int stormHooks() {
        return stormHooks;
    }

    /**
     * Whether it is raining, read live from the client level's rain level.
     *
     * <p>Not {@link Level#isRaining()}. On the client that method is
     * <em>derived</em>, not stored - {@code return this.getRainLevel(1F) > 0.2D}
     * - and the server ramps the level by 0.01 a tick, so it does not become
     * true until twenty ticks into the storm. See {@link #weatherRising}.</p>
     *
     * <p>"The level is above zero" is the right question here anyway: it is true
     * from the first tick of the ramp and stays true for the whole of the
     * release, which is exactly the span the takeover has to cover. It is a
     * field read, cheap enough to call from the render path.</p>
     */
    /** Public so consumers that must not lag the storm - the rain-on-material
     *  layer among them - can ask this instead of gating on a level. */
    public static boolean rainingNow() {
        final Level level = clientLevel;
        if (level != null) {
            try {
                return level.getRainLevel(1F) > 0F;
            } catch (Throwable t) {
                // A dead level must never be able to break a render frame.
            }
        }
        return eventActive;
    }

    /**
     * Whether the weather is coming in rather than going out, from the direction
     * the vanilla rain level is travelling.
     *
     * <p><b>Why this exists.</b> {@code Level.isRaining()} looks like the obvious
     * question and it is the wrong one. On the client it is derived from the
     * rain level - {@code return this.getRainLevel(1F) > 0.2D} - and the server
     * ramps that level by 0.01 per tick. So for the first twenty ticks of every
     * storm, a full second, {@code isRaining()} answers <em>false</em> while the
     * rain is already visibly falling. Everything keyed off it - the roll, the
     * takeover, and therefore the fog, the rain geometry and the sky - started a
     * second late, and that second is precisely the window in which vanilla was
     * still drawing and its fog was still ramping. That is the takeover delay,
     * and it is not a race: it is one number being derived from another that
     * has not arrived yet.</p>
     *
     * <p>The direction the ramp is travelling is available on the very first
     * tick: it is 0.01 and rising where it was 0 the tick before. Saturated
     * counts as rising so a storm joined in progress is still an event. It also
     * fixes the other end - {@code isRaining()} stayed true for four seconds
     * into the fade-out, so the envelope kept running its attack and its gust
     * wobble while the rain was supposed to be releasing.</p>
     *
     * <p>Only {@link #update} may call this: it consumes the previous sample.</p>
     */
    /**
     * The climate the player is standing in, sampled with look-ahead.
     *
     * <p>Resolving the profile at the player's own block is not enough. A profile
     * is a discrete classification, so the value steps the instant that block
     * changes - and it changes <em>after</em> the player has already crossed,
     * which reads as the rain reacting late. Sampling a fan of points ahead of
     * the player's direction of travel means the change begins before the border
     * and is partway through by the time it is crossed, and a boundary running
     * obliquely produces a value between the two sides rather than a flip.</p>
     *
     * <p>Only positions in loaded chunks are sampled. Calling {@code getBiome} on
     * an unloaded position forces a load, and that is exactly how a tick ends up
     * blocked on chunk generation.</p>
     */
    private static float sampleLocationMode(Level level, BlockPos pos,
                                            PrecipitationSeason season, float fallback) {
        double fx = 0D;
        double fz = 0D;
        double speed = 0D;

        var player = org.orecruncher.dsurround.lib.GameUtils.getPlayer().orElse(null);
        if (player != null) {
            var motion = player.getDeltaMovement();
            speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (speed > 1e-3D) {
                fx = motion.x / speed;
                fz = motion.z / speed;
            } else {
                // Standing still: fall back to where they are looking, so turning
                // on the spot still leans the sample towards the way they face.
                var look = player.getLookAngle();
                final double l = Math.sqrt(look.x * look.x + look.z * look.z);
                if (l > 1e-3D) {
                    fx = look.x / l;
                    fz = look.z / l;
                }
            }
        }

        final float distance = Mth.clamp((float) speed * LOOKAHEAD_SECONDS,
                LOOKAHEAD_MIN_BLOCKS, LOOKAHEAD_MAX_BLOCKS);

        if (!samplesPrimed) {
            for (int i = 0; i < lastSampleModes.length; i++)
                lastSampleModes[i] = fallback;
            samplesPrimed = true;
        }

        // Every sample keeps its last known value. Averaging only the points that
        // happen to be usable this tick was the flicker: near a border the usable
        // set keeps changing size, and an average over a changing denominator jumps
        // even though the countryside did not. Holding the last value keeps the
        // denominator fixed at five, so the only thing that moves the result is an
        // actual change in what was sampled.
        double weighted = 0D;
        double total = 0D;
        for (int i = 0; i < LOCATION_SAMPLES.length; i++) {
            final float[] sample = LOCATION_SAMPLES[i];
            final double ox = (fx * sample[0] - fz * sample[1]) * distance;
            final double oz = (fz * sample[0] + fx * sample[1]) * distance;
            final BlockPos at = new BlockPos(
                    pos.getX() + (int) Math.round(ox),
                    pos.getY(),
                    pos.getZ() + (int) Math.round(oz));
            if (level.isLoaded(at)) {
                final float mode = modeAt(level, at, season, i);
                if (mode >= 0F)
                    lastSampleModes[i] = mode;
            }
            weighted += lastSampleModes[i] * sample[2];
            total += sample[2];
        }
        return total > 0D ? (float) (weighted / total) : fallback;
    }

    /** The profile mode at one position, or -1 when it cannot be determined. */
    private static float modeAt(Level level, BlockPos pos, PrecipitationSeason season, int index) {
        try {
            var holder = level.getBiome(pos);
            // The key itself, not a rendered string: written per sample per tick,
            // read only by the diagnostics line (rendered on demand).
            lastSampleBiomes[index] = holder.unwrapKey().orElse(null);
            var biome = holder.value();
            var info = ((org.orecruncher.dsurround.mixinutils.IBiomeExtended) (Object) biome)
                    .dsurround_getInfo();
            if (info == null)
                return -1F;
            return PrecipitationProfile.resolve(info.getTraits()).modeFor(season);
        } catch (Throwable t) {
            // A sample is a refinement. Never let a bad one stop the tick.
            return -1F;
        }
    }

    // ---- Frame-level jump diagnostics -----------------------------------------

    /**
     * Called from the sky colour route after the value has been chosen. Compares
     * it with the previous frame's value and, on a step far larger than any
     * low-passed channel can produce, logs the full context. Also emits a periodic
     * line so the route's behaviour can be watched while nothing is wrong.
     */
    private static void noteSkyColorFrame(Level level, float partialTick, float out) {
        final IModLog log = diagLogger;
        if (log == null)
            return;
        final boolean owner = ownsAmbient();
        // 0.08 is far above what a 0.4s low-pass can move in one frame (~0.04 at
        // 60fps), so crossing it means the route switched, not that the curve moved.
        if (lastReportedSkyOut >= 0F && Math.abs(out - lastReportedSkyOut) > 0.08F
                && skyJumpCooldown <= 0) {
            skyJumpCooldown = 10;
            log.info("[SKY-JUMP] out %.3f -> %.3f owner %s -> %s SKY=%.3f vanillaRamp=%.3f camBiome=%s playerBiome=%s samples=%s",
                    lastReportedSkyOut, out, lastReportedSkyOwner, owner,
                    PrecipitationResponse.SKY.value(),
                    level == null ? -1F : level.getRainLevel(partialTick),
                    cameraBiomeId(level), playerBiomeId(level),
                    sampleBiomeSummary());
        }
        if (skyJumpCooldown > 0)
            skyJumpCooldown--;
        // Roughly once a second: the same line without the jump, so a flicker that
        // predates the detector still has a baseline to compare against.
        if (++skyFrameLogCounter >= 120) {
            skyFrameLogCounter = 0;
            log.info("[SKY-FRAME] out=%.3f SKY=%.3f owner=%s ramp=%.3f graded=%.3f ambient=%.3f loc=%.3f roll=%.3f camBiome=%s playerBiome=%s samples=%s",
                    out, PrecipitationResponse.SKY.value(), owner,
                    level == null ? -1F : level.getRainLevel(partialTick),
                    graded, ambient, locationMode, rollMode,
                    cameraBiomeId(level), playerBiomeId(level),
                    sampleBiomeSummary());
        }
        lastReportedSkyOut = out;
        lastReportedSkyOwner = owner;
    }

    /** As {@link #noteSkyColorFrame}, for the fog colour route. */
    private static void noteFogColorFrame(Level level, float partialTick, float out) {
        final IModLog log = diagLogger;
        if (log == null)
            return;
        if (lastReportedFogOut >= 0F && Math.abs(out - lastReportedFogOut) > 0.08F
                && fogJumpCooldown <= 0) {
            fogJumpCooldown = 10;
            log.info("[FOG-JUMP] out %.3f -> %.3f owner=%s FOG_COLOR=%.3f vanillaRamp=%.3f camBiome=%s playerBiome=%s",
                    lastReportedFogOut, out, ownsAmbient(),
                    PrecipitationResponse.FOG_COLOR.value(),
                    level == null ? -1F : level.getRainLevel(partialTick),
                    cameraBiomeId(level), playerBiomeId(level));
        }
        if (fogJumpCooldown > 0)
            fogJumpCooldown--;
        lastReportedFogOut = out;
    }

    private static String biomeIdAt(Level level, BlockPos pos) {
        try {
            return level.getBiome(pos).unwrapKey()
                    .map(k -> k.identifier().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String cameraBiomeId(Level level) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.gameRenderer == null)
                return "none";
            return biomeIdAt(level, BlockPos.containing(mc.gameRenderer.getMainCamera().position()));
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String playerBiomeId(Level level) {
        try {
            var player = org.orecruncher.dsurround.lib.GameUtils.getPlayer().orElse(null);
            if (player == null)
                return "none";
            return biomeIdAt(level, player.blockPosition());
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String sampleBiomeSummary() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LOCATION_SAMPLES.length; i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(i).append('=')
                    .append(lastSampleBiomes[i] == null ? "?" : lastSampleBiomes[i])
                    .append('(').append(String.format("%.2f", lastSampleModes[i])).append(')');
        }
        return sb.toString();
    }

    private static boolean weatherRising(float ramp) {
        // A ramp that has not moved must not be read as "the rain stopped".
        // The server steps rainLevel once per server tick and the client does not
        // run in lockstep with it, so a client tick can observe the same value
        // twice in a row. Treating that as "falling" ended the event and the next
        // tick started a fresh one - so a single storm re-rolled its base over and
        // over and the peak never held still. Only an actual decrease is falling;
        // anything else keeps whatever we already decided.
        // A rain level at or below the dead-band is dry, and that is an answer,
        // not a fallback. It has to come before the direction test: the vanilla
        // ramp reaches zero in a step worth about 1e-7 (the float32 residue of a
        // hundred 0.01 steps being clamped away), which the dead-band cannot see,
        // so the last move of every storm used to be classified "flat" and flat
        // fell through to the latched flag below - keeping an ended event alive
        // forever and pinning its peak. Rain level zero means no precipitation;
        // no amount of "it did not move" can outweigh that.
        final boolean rising;
        if (ramp <= RAMP_EPSILON)
            rising = false;
        else if (ramp >= 1F || ramp > lastRamp + RAMP_EPSILON)
            rising = true;
        else if (ramp < lastRamp - RAMP_EPSILON)
            rising = false;
        else
            rising = eventActive;
        lastRamp = ramp;
        lastRising = rising;
        return rising;
    }

    /**
     * Whether graded precipitation is actually switched on. A diagnostics lock
     * counts as on: the whole point of {@code /dsprecip lock} is to answer "does
     * the chain work at all", and that answer must not depend on a toggle.
     */
    public static boolean grading() {
        if (forced >= 0F)
            return true;
        var config = org.orecruncher.dsurround.Client.Config;
        return config != null && config.weatherOptions.enableGradedPrecipitation;
    }

    /**
     * Whether sky, fog and audio follow our curve instead of the vanilla rain
     * level. Splitting this out from {@link #takeoverReady()} is what keeps
     * "feature switched off" meaning "vanilla behaviour" for the ambient
     * consumers too, rather than them quietly following a curve the player
     * turned off.
     */
    public static boolean ownsAmbient() {
        return grading() && takeoverReady();
    }

    /**
     * Intensity without the phase gate, 0..1. Sky darkness, fog and rain sound
     * read this: they are properties of the weather, not of what is landing on
     * the player's head.
     *
     * @return shaped intensity in [0, 1]
     */
    /**
     * The gust amplitude the current event rolled (profile response curve x
     * gustiness). Encodes "convective showers gust, drizzle is steady" - the
     * rain wind layer paces its leaf gusts with it.
     */
    public static float gustAmplitude() {
        return gustAmplitude;
    }

    public static float ambient() {
        return ambient;
    }

    /**
     * The peak rolled for the rain event in progress - the ceiling
     * {@link #intensity()} is clamped to. 1.12.2 exposes the pair as
     * {@code getIntensityLevel()} / {@code getMaxIntensityLevel()} and divides them
     * to get {@code alphaRatio}, which is how the renderer scales opacity.
     *
     * @return the rolled peak in [0, 1]; 0 when no event is in progress
     */
    public static float peak() {
        return rolled;
    }

    /**
     * What is actually falling at a position, season-aware. Delegates to
     * {@code ISeasonalInformation.getActivePrecipitation}.
     *
     * @param pos the position to test
     * @return NONE, RAIN or SNOW
     */
    /**
     * What <em>would</em> fall at a position, ignoring whether it is raining
     * right now - {@code ISeasonalInformation.getPrecipitationAt}.
     *
     * <p>{@link #phase} answers NONE whenever the world is not currently
     * raining, which is correct for rendering and useless for a diagnostics
     * lock: a lock is by definition used when it is not raining. This is the
     * same question without the raining test, so a lock in a snowy biome can
     * draw snow and a lock in the desert can draw dust instead of always
     * drawing rain.</p>
     *
     * @param pos the position to test
     * @return NONE, RAIN or SNOW
     */
    public static Biome.Precipitation potentialPhase(BlockPos pos) {
        return SeasonManager.HANDLER.getPrecipitationAt(pos);
    }

    public static Biome.Precipitation phase(BlockPos pos) {
        return SeasonManager.HANDLER.getActivePrecipitation(pos);
    }

    /** The profile resolved for the player's current biome; never null. */
    /**
     * Ticks between full state lines in the log. The F3 line cannot hold this
     * much - see {@link #compactText()} - and the parts it had to drop are the
     * ones that answer "is the takeover live at all".
     */
    private static final int STATE_LOG_INTERVAL = 100;
    /** Ticks between idle counter lines - see the comment in {@link #update}. */
    private static final int IDLE_LOG_INTERVAL = 600;
    /** Intensity below which, with no event running, a tick goes idle. */
    private static final float IDLE_EPSILON = 0.001F;
    private static int stateLogCounter = 0;
    private static int idleLogCounter = 0;

    /**
     * Dust storm intensity, 0..1. Zero where the biome has no dust to blow.
     *
     * <p>This is <em>not</em> a second weather event. It is the same event the
     * rain roll describes, read through the dust profile: a desert cloudburst
     * puts almost no water on the ground and still arrives as a wall of dust.
     * It therefore shares the attack, the gusting and the release with
     * {@link #intensity()} - see the comment on {@code PrecipitationProfile.DUST_STORM}.</p>
     *
     * <p>Read by the sandstorm veil and by the dust horizon tint. Everything
     * else - sound, particles, geometry - is deliberately still to come.</p>
     *
     * @return dust storm intensity in [0, 1]
     */
    public static float dustIntensity() {
        return dustIntensity;
    }

    /** The dust profile in play, or null where this biome gets no dust storm. */
    public static PrecipitationProfile dustProfile() {
        return dustProfile;
    }

    public static PrecipitationProfile profile() {
        return currentProfile;
    }

    /** The season column used for the roll in progress; null when there is none. */
    public static PrecipitationSeason season() {
        return currentSeason;
    }

    /** Locked intensity, or -1 when nothing is locked. Diagnostics only. */
    public static float forced() {
        return forced;
    }

    /**
     * Hold a single intensity level steady so two levels can be compared by eye.
     * The renderer is expected to bypass its per-column phase gate while locked,
     * otherwise nothing draws unless the world is actually raining.
     *
     * @param value intensity to hold, 0..1
     */
    public static void force(float value) {
        forced = Mth.clamp(value, 0F, 1F);
        // A lock is a deliberate jump, so it must land on its level immediately
        // rather than waiting for the hysteresis band to be crossed.
        PrecipitationRenderer.resetLevelHysteresis();
    }

    /** Release a {@link #force} lock. */
    public static void release() {
        forced = -1F;
        PrecipitationRenderer.resetLevelHysteresis();
    }

    /**
     * A short line for the F3 panel, with the live intensity first.
     *
     * <p>{@link #diagnosticText()} is for working out <em>why</em> something is
     * wrong and is deliberately exhaustive - which also makes it unusable for
     * watching a storm happen, because the number you are looking for is buried
     * two thirds of the way along a line that does not fit on screen. This one
     * leads with the intensity at the player's own position, which is the number
     * to hold against the rain you can see.</p>
     *
     * <p>{@code now} and {@code target} are separated because they are not the
     * same thing and the gap between them is informative: {@code target} is the
     * envelope's answer and {@code now} is what survived the phase gate and the
     * output filter, so a storm you are standing inside reads as two equal
     * numbers while one you are watching from under a roof does not.</p>
     */
    /**
     * The exhaustive line, split for the overlay.
     *
     * <p>{@link #diagnosticText()} is one string joined with " | ", which is
     * far too wide for a single row of the diagnostics panel. The overlay walks
     * this instead and emits one row per segment.</p>
     */
    public static java.util.List<String> diagnosticLines() {
        return java.util.Arrays.asList(diagnosticText().split(" \\| "));
    }

    public static String compactText() {
        // Deliberately narrow. The exhaustive line runs off the side of the
        // screen, which made the ownership flag - the single most useful reading
        // when the sky misbehaves - the one thing that could never be seen. The
        // hook counters moved back to the long line; they were only needed to
        // prove the injections ran, and they have.
        return "Precip now=%.2f target=%.2f peak=%.2f loc=%.2f %s | sky=%.2f sun=%.2f fog=%.2f"
                .formatted(
                        graded,
                        smoothed,
                        rolled,
                        locationScale(),
                        PrecipitationRenderer.levelName(forced >= 0F ? forced : graded),
                        // SKY alone: this is what the sky COLOUR is driven with
                        // now that colour and sun alpha have been separated. The
                        // sun's own value is reported separately below, because
                        // folding the two together is exactly what made this
                        // number sit at 1.0 and stop saying anything.
                        // What the sky colour route actually received last, not
                        // the channel: this is the one that can disagree, because
                        // it falls back to vanilla whenever ownership lapses.
                        lastSkyColorOut < 0F ? PrecipitationResponse.SKY.value() : lastSkyColorOut,
                        // The sun, kept separate now that it no longer drives the
                        // colour. It reaches full when the attack does and then
                        // holds, so seeing it sit at 1.00 is correct and not a
                        // stuck value - the sky should keep moving underneath it.
                        PrecipitationResponse.SUN.value(),
                        PrecipitationResponse.FOG_DIST.value());
    }

    /** One-line state dump for the command and the diagnostics panel. */
    public static String diagnosticText() {
        float f = forced;
        return "Precipitation: profile=%s season=%s mode=%.2f rolled=%.2f intensity=%.3f ambient=%.3f level=%s gust=%.3f%s"
                .formatted(
                        profile().name(),
                        currentSeason == null ? "none" : currentSeason.name(),
                        profile().modeFor(currentSeason),
                        rolled,
                        graded,
                        ambient,
                        f >= 0F ? PrecipitationRenderer.levelName(f) : PrecipitationRenderer.levelName(graded),
                        gustAmplitude,
                        eventRestored ? " [RESTORED]" : "",
                        f >= 0F ? " [LOCKED %.2f]".formatted(f) : "")
                // The tail is what actually answers "is the takeover live": hook=N is
                // vanilla asking us about the rain pass, tick=N is vanilla asking about
                // the splash/sound pass, render=N is our stage firing, config is the toggle.
                + " | hook=%d tick=%d render=%d config=%s broken=%s".formatted(
                        PrecipitationRenderer.hookCalls(),
                        PrecipitationRenderer.tickHooks(),
                        PrecipitationRenderer.renderCalls(),
                        org.orecruncher.dsurround.Client.Config == null ? "null"
                                : String.valueOf(org.orecruncher.dsurround.Client.Config.weatherOptions.enableGradedPrecipitation),
                        PrecipitationRenderer.isBroken())
                // The per-channel response values: what the sky, fog and audio are
                // actually being driven with. They differ from intensity on purpose.
                + " | sky=%.2f fog=%.2f audio=%.2f".formatted(
                        PrecipitationResponse.SKY.value(),
                        PrecipitationResponse.FOG_DIST.value(),
                        PrecipitationResponse.AUDIO.value())
                // Who is driving the ambient consumers, and what is still holding
                // the frame. "owner=ours" with a non-"none" holder is normal at the
                // tail of a storm; "owner=vanilla" while it is raining is not.
                + " | owner=%s holding=%s".formatted(
                        ownsAmbient() ? "ours" : "vanilla",
                        PrecipitationResponse.slowestChannel())
                // The vanilla ramp is what the takeover is keyed off now, so it is
                // worth being able to see it: rising=on the first tick of a storm
                // the graded intensity is already rolling.
                // dust=now/base - n/a outside dusty biomes. base is the roll,
                // now is what the envelope and the release ramp have done to it.
                + " | dust=%s".formatted(
                        dustProfile == null ? "n/a"
                                : "%.2f/%.2f".formatted(dustIntensity, dustBase))
                + " | ramp=%.3f rising=%s active=%s".formatted(
                        clientLevel == null ? -1F : clientLevel.getRainLevel(1F),
                        lastRising,
                        eventActive)
                // skyHook: how many times the sky brightness route was consulted.
                // Counted inside sunAlphaRainLevel(), so it climbs whenever the
                // route runs at all - which is exactly the question "did this
                // version's sky injection land". A zero that stays zero means the
                // sky is still vanilla's. (A cloud brightness counter used to sit
                // next to it, but renderClouds has no getRainLevel call in any of
                // our versions, so it could only ever read 0 - it was a diagnostic
                // that always cried wolf, and has been removed.)
                // Colour hooks: sky / cloud / fog. Separate routes from the
                // brightness one. All three must climb while it is raining; a zero
                // that stays zero means that version's colour is still vanilla.
                + " | skyHook=%d color=%d/%d/%d storm=%d".formatted(
                        skyHooks(),
                        skyColorHooks(), cloudColorHooks(), fogColorHooks(), stormHooks())
                // rainAudio = played/offered on the rain sound pass: offered is
                // vanilla asking (about seven times a second while it rains),
                // played is what the density gate let through. The ratio IS the
                // density, so it should be well under 1 in a drizzle and reach
                // 1 only at full intensity. Both counters are cumulative, so
                // read the ratio and not the numbers.
                // dens/vol are the two knobs themselves, and both read 1.00 on
                // any frame vanilla owns - which is the point: seeing 1.00
                // while it rains means the redirect did not land.
                + " | rainAudio=%s dens=%.2f vol=%.2f heavy=%.2f".formatted(
                        rainAudioDiagnostics(),
                        rainAudioDensity(),
                        rainAudioVolumeScale(),
                        rainAudioCalmBlend());
    }

    /**
     * Per-tick update: resolve the profile and phase, roll a new peak when a rain
     * event starts, and recompute the graded intensity. Call this from a tick, not
     * from the render path - {@code getActivePrecipitation} does a biome lookup and
     * a heightmap query.
     *
     * @param logger logger for the change/event lines
     * @param level  the client level
     * @param info   the player's biome info - may be null
     * @param pos    the player's position
     */
    public static void update(IModLog logger, Level level, BiomeInfo info, BlockPos pos) {
        // Hand the level to the render-side ownership check. This is the whole
        // fix for the one-tick race: whoever asks "do we own this frame" gets an
        // answer read from the level itself, never from state this tick has not
        // written yet.
        clientLevel = level;
        diagLogger = logger;

        // The vanilla ramp has to be read FIRST and judged by its direction of
        // travel. Level.isRaining() is derived from it (rainLevel > 0.2) and so
        // lags it by a full second at the start of every storm and by four
        // seconds at the end - see weatherRising().
        final float ramp = level.getRainLevel(1F);
        final boolean raining = weatherRising(ramp);

        // Idle fast path. When nothing is falling, nothing has been rolled and
        // every channel has settled, skip the five-sample biome query, the
        // envelope and the squall clock - the ramp read above is the only thing
        // that watches for weather starting. Ticks outnumber rain by orders of
        // magnitude; this is the difference the profiler sees.
        //
        // "Every channel has settled" has to be a TEST, not an assumption. This
        // path returns before PrecipitationResponse.tickAll(), and tickAll is
        // the only thing that ever advances a channel - so any channel still
        // above zero when the path is taken stays there. Frozen, not decaying:
        // the sky keeps its rain tint and the fog keeps its rain thickness for
        // good, because nothing is left running that could take them away.
        // The two facts that make it reachable are both upstream of this test:
        // the ramp reaches zero in a single tick and ambient is multiplied by
        // it, so "dry" arrives instantly, while the slowest chain (atmosphere
        // -> sky -> cloud, several seconds of release) is still sweeping down.
        // Hence anyActive(), asked last so it only costs anything on a tick
        // that is already idle on every cheaper count.
        if (forced < 0F && !raining && !eventActive
                && ambient < IDLE_EPSILON && dustIntensity < IDLE_EPSILON
                && !PrecipitationResponse.anyActive()) {
            if (++idleLogCounter >= IDLE_LOG_INTERVAL) {
                idleLogCounter = 0;
                logger.info("[PRECIP-IDLE] hook=%d tick=%d render=%d broken=%s color=%d/%d/%d storm=%d config=%s",
                        PrecipitationRenderer.hookCalls(),
                        PrecipitationRenderer.tickHooks(),
                        PrecipitationRenderer.renderCalls(),
                        PrecipitationRenderer.isBroken(),
                        skyColorHooks(), cloudColorHooks(), fogColorHooks(), stormHooks(),
                        org.orecruncher.dsurround.Client.Config == null ? "null"
                                : String.valueOf(org.orecruncher.dsurround.Client.Config.weatherOptions.enableGradedPrecipitation));
            }
            return;
        }

        var profile = info == null
                ? PrecipitationProfile.TEMPERATE
                : PrecipitationProfile.resolve(info.getTraits());
        var phase = phase(pos);

        // Where the player is - and, more importantly, where they are about to be.
        // See sampleLocationMode: sampling only the current position would only
        // start reacting once the border was already crossed, which is the
        // difference between weather that changes with the countryside and a
        // switch thrown at the boundary.
        final float modeNow = sampleLocationMode(level, pos, currentSeason,
                profile.modeFor(currentSeason));
        // A negative return means "too few usable samples" - hold what we have.
        if (modeNow >= 0F) {
            if (locationMode <= 0F)
                locationMode = modeNow;
            else
                locationMode += (modeNow - locationMode) * LOCATION_ALPHA;
        }

        // An event that has just ended is forgotten, so a world rejoined after a dry
        // spell rolls a new one instead of inheriting the attack clock of a storm
        // that finished sessions ago.
        // Forget the event only once the vanilla ramp has drained - the
        // first downward step is while the rain is still on screen (the release),
        // and clearing then deleted the persistence file mid-release and dipped
        // the dust. Drained means below the dead-band, not equal to zero: the ramp
        // can stop a hair above zero and stay there, and an "equals zero" test
        // for dry is what let an ended event live forever.
        if (!raining && eventActive && lastRamp <= RAMP_EPSILON)
            clearEvent(level);

        if (raining && !eventActive) {
            var season = seasonFor(profile);
            currentSeason = season;

            // Joining a world that is already mid-storm must not restart the curve.
            // The event identity is persisted so the attack clock keeps running across
            // sessions instead of the player returning to find the storm building from
            // drizzle again - see {@link #restoreEvent}.
            //
            // Resume is only for that case, and the ramp is what says which case
            // this is. A storm the player watches begin starts at a ramp of about
            // zero and climbs, so it is a new event whatever file happens to be
            // lying around - a leftover file (a crash, a storm that ended while
            // the world was closed) used to be replayed verbatim, which is how
            // one world could draw the same peak for the rest of its life.
            eventRestored = ramp >= RESUME_RAMP_MIN && restoreEvent(level, profile);

            if (eventRestored) {
                logger.info("Precipitation event -> RESTORED base=%.2f profile=%s season=%s age=%.0fs",
                        rolled, profile.name(), season == null ? "none" : season.name(),
                        (level.getGameTime() - attackStartTick) / 20D);
            } else {
                // Roll one: where this event sits on the profile's curve. This is the
                // event's ceiling and it does not move for the rest of the event.
                // Thunderstorms are the top of the convective scale, so when the
                // world is already storming as the event starts the roll is skewed
                // heavy. See PrecipitationProfile.sampleBase.
                stormy = level.getThunderLevel(1F) > THUNDER_ACTIVE_LEVEL;
                rolled = profile.sampleBase(season, RANDOM, stormy);
                // Roll one-and-a-half: the same event's dust magnitude. Drawn
                // from the dust profile, not from the rain profile - see the
                // comment on DUST_STORM for why one event carries both.
                dustProfile = PrecipitationProfile.resolveDust(
                        info == null ? null : info.getTraits());
                dustBase = dustProfile == null
                        ? 0F
                        : dustProfile.sampleBase(seasonFor(dustProfile), RANDOM, stormy);

                // Roll two: the shape of this event's intensity curve. This used to
                // draw a single scalar - how much it wobbles - while the shape of the
                // wobble was the same two sines for every storm ever. So two storms
                // from one profile differed only in volume and starting point, never
                // in character, which is exactly what makes synthetic variation
                // recognisable. It now draws the curve: how fast it breathes, how
                // complex, and whether it swells or arrives in squalls.
                gustiness = GUSTINESS_MIN + RANDOM.nextFloat() * (GUSTINESS_MAX - GUSTINESS_MIN);
                gustAmplitude = profile.gustAmplitudeAt(rolled) * gustiness;
                gustPeriodA = GUST_PERIOD_MIN + RANDOM.nextFloat() * (GUST_PERIOD_MAX - GUST_PERIOD_MIN);
                gustPeriodB = gustPeriodA
                        * (GUST_RATIO_MIN + RANDOM.nextFloat() * (GUST_RATIO_MAX - GUST_RATIO_MIN));
                gustWeightB = GUST_WEIGHT_B_MIN + RANDOM.nextFloat() * (GUST_WEIGHT_B_MAX - GUST_WEIGHT_B_MIN);
                gustSharpness = RANDOM.nextFloat();
                attackTicks = Math.max(0F, profile.attackSeconds) * 20F;

                attackStartTick = level.getGameTime();
                // Squalls belong to the event, so they start over with it.
                resetSqualls();
                rollMode = profile.modeFor(season);
                locationMode = rollMode;
                gustPhase = RANDOM.nextDouble() * TWO_PI;
                lastEnvelope = 0F;
                smoothed = 0F;
                persistEvent(level);
                logger.info("Precipitation event -> base=%.2f profile=%s season=%s attack=%.0fs gust=%.3f (%.2f x %s) wobble=%.0fs+%.0fs w=%.2f squall=%.2f",
                        rolled, profile.name(), season == null ? "none" : season.name(),
                        profile.attackSeconds, gustAmplitude, gustiness, profile.gustResponse.name(),
                        gustPeriodA, gustPeriodB, gustWeightB, gustSharpness);
            }
        }
        eventActive = raining || (eventActive && lastRamp > RAMP_EPSILON);

        if (profile != currentProfile || phase != currentPhase) {
            currentProfile = profile;
            currentPhase = phase;
            logger.info("Precipitation -> profile=%s (summerMode=%.2f spread=%.2f thunder=%.2f) phase=%s",
                    profile.name(), profile.mode(), profile.spread, profile.thunderBias, phase.name());
        }

        // The vanilla ramp is no longer the envelope - it is the RELEASE. Our own attack
        // climbs to the rolled peak over ATTACK_SECONDS so a storm visibly builds instead
        // of hitting its ceiling in ~3s and sitting flat for the rest of the event, and a
        // gusting wobble keeps the plateau alive. Multiplying by the vanilla ramp on the
        // way out means our rain fades together with the vanilla sky, fog and rain sound
        // instead of outliving them.
        if (forced >= 0F) {
            // Diagnostics lock: pin the whole chain so one level can be held steady,
            // smoothing included - a locked level must not take 3s to arrive.
            rolled = forced;
            graded = forced;
            smoothed = forced;
            ambient = forced;
            // A lock has to stand in for a storm that has already built, not for
            // one that has just started. attackProgress rides the attack clock,
            // which a lock never advances, so without this the sun stayed out at
            // lock 1.0 while it correctly disappeared in real rain - the lock
            // then proved nothing about the one thing it was meant to prove.
            attackProgress = 1F;
            // A lock stands in for a whole storm, dust included, so that
            // /dsprecip lock can hold a sandstorm at one grade.
            dustIntensity = dustBase > 0F ? forced : 0F;
        } else {
            float shaped = envelope(level.getGameTime(), raining);
            // Everything reaches the renderer through this low-pass. It is what keeps
            // the wobble from showing up as the texture stepping between levels, and it
            // costs nothing: one lerp per tick.
            smoothed += (shaped - smoothed) * SMOOTH_ALPHA;
            // The upper bound is 1, not the rolled peak, and this is the same
            // mistake that was corrected inside envelope() - in two places, because
            // it was made in two places. Capping at the peak means everything the
            // envelope legitimately produces above it is thrown away: walking into
            // wetter country scales the base up past the roll and every upward gust
            // of a storm already near its peak is flattened. The peak is where the
            // roll landed, not a ceiling on what the weather can then do.
            float scaled = Mth.clamp(smoothed * ramp, 0F, 1F);
            ambient = scaled;
            // The dust storm rides the SAME envelope. It is one event: whatever
            // the attack, the gusting and the terrain scaling did to the rain,
            // it did to the dust too. Only the magnitude it was applied to is
            // different, so the envelope is normalised back out to a shape in
            // roughly [0, 1.5] and re-applied to the dust base. Rolling the
            // dust on its own clock instead would give one storm two arrivals.
            if (dustBase > 0F) {
                final float eventShape = rolled > 1e-4F ? shaped / rolled : 1F;
                dustSmoothed += (Mth.clamp(dustBase * eventShape, 0F, 1F) - dustSmoothed)
                        * SMOOTH_ALPHA;
                dustIntensity = Mth.clamp(dustSmoothed * ramp, 0F, 1F);
            } else {
                dustIntensity = 0F;
            }
            // The player standing under something must NOT stop the rain.
            //
            // Vanilla does not do this: it draws rain everywhere regardless of
            // what is over the player's head, and only stops spawning ground
            // splash particles when the player is covered. 1.12.2 did not do it
            // either - its gate is per column (each column is clipped at its own
            // precipitation height), never global. Zeroing this value made
            // walking under a tree switch the rain off world-wide, and standing
            // in a doorway showed a completely dry world outside.
            //
            // "Is anything landing here" is a per-column question and the
            // renderer already asks it per column, at that column's surface.
            // What is left for a gated value to mean is splashes - rain that
            // landed - which is N7's job.
            graded = scaled;
        }

        // The dust magnitude follows the biome the player is standing in, not
        // the event. The rain roll is made once per event because an event is a
        // weather system, but whether that system shows up as dust is a
        // question about where the player is right now - so walking into dusty
        // country mid-storm has to roll one rather than wait for the next
        // event, which is what happened before and why an entire storm could
        // pass with no sandstorm in it.
        if (raining) {
            var wantedDust = PrecipitationProfile.resolveDust(
                    info == null ? null : info.getTraits());
            if (wantedDust != dustProfile) {
                dustProfile = wantedDust;
                dustBase = wantedDust == null
                        ? 0F
                        : wantedDust.sampleBase(seasonFor(wantedDust), RANDOM,
                                level.getThunderLevel(1F) > THUNDER_ACTIVE_LEVEL);
            }
        }

        // How far into the attack we are, smoothstepped. The sun rides this
        // rather than the intensity: cloud cover arrives while the storm builds,
        // so the sun should be gone by the end of the attack and then stay gone
        // instead of coming back every time the intensity gusts down.
        final float attackRaw = (raining && attackTicks > 0F)
                ? Mth.clamp((level.getGameTime() - attackStartTick) / attackTicks, 0F, 1F)
                : (raining ? 1F : 0F);
        // A lock owns the sun too. Without this the assignment in force() was
        // overwritten on the very next tick by a clock that reads zero whenever
        // the world is not actually raining - so a lock could hold the
        // intensity and still leave the sun out.
        attackProgress = forced >= 0F ? 1F : attackRaw;

        // Each consumer gets its own curve and its own follow speed from here.
        // Squalls advance on the tick, where the game time lives.
        advanceSqualls(level);

        PrecipitationResponse.tickAll(graded, ambient, attackProgress, dustIntensity, 1F / 20F);

        // Everything the F3 line can no longer hold goes to the log instead.
        // There are two cadences on purpose: the full state only while there is
        // weather to report, and a counters-only line always, because the thing
        // that most needs watching - hook=0, meaning the takeover never landed
        // (the 26.1 architecture blocker) - is only visible when nothing is
        // happening. Waiting for rain to discover it wastes the whole session.
        final IModLog log = diagLogger;
        if (log != null) {
            if (eventActive || forced >= 0F) {
                if (++stateLogCounter >= STATE_LOG_INTERVAL) {
                    stateLogCounter = 0;
                    idleLogCounter = 0;
                    log.info("[PRECIP] %s", diagnosticText());
                }
            } else if (++idleLogCounter >= IDLE_LOG_INTERVAL) {
                idleLogCounter = 0;
                log.info("[PRECIP-IDLE] hook=%d tick=%d render=%d broken=%s color=%d/%d/%d storm=%d config=%s",
                        PrecipitationRenderer.hookCalls(),
                        PrecipitationRenderer.tickHooks(),
                        PrecipitationRenderer.renderCalls(),
                        PrecipitationRenderer.isBroken(),
                        skyColorHooks(), cloudColorHooks(), fogColorHooks(), stormHooks(),
                        org.orecruncher.dsurround.Client.Config == null ? "null"
                                : String.valueOf(org.orecruncher.dsurround.Client.Config.weatherOptions.enableGradedPrecipitation));
            }
        }

    }

    /**
     * Vanilla thunder level, 0..1. Vanilla already multiplies thunder by rain level,
     * so this is 0 whenever it is not raining - it is a "how stormy", not a
     * "will there be lightning".
     */
    public static float thunderLevel() {
        return thunderLevel;
    }

    /**
     * Whether background thunder should be firing right now: vanilla says it is
     * thundering AND our intensity clears the (profile-adjusted) threshold. This is
     * 1.12.2's {@code backgroundThunderPossible()} shape.
     *
     * <p>N6 (thunder scheduling) uses two more 1.12.2 formulas, both keyed off
     * intensity - recorded here so they do not have to be re-derived:</p>
     * <pre>
     *   // SimulationTracker.nextThunderEvent(intensity)
     *   scale = 2.0F - intensity;
     *   ticksToNext = random.nextInt((int)(450 * scale)) + 300;   // 15-60s, denser when heavy
     *
     *   // SimulationTracker.doFlash(intensity) - is this strike visible?
     *   flash = random.nextInt(150) &lt;= (int)(intensity * 100F);   // 0% at 0, ~67% at 1
     * </pre>
     */
    /**
     * Whether the world is thundering, for anything that needs to render or play
     * a storm. This is the gate the maintainer asked for: our own thunder and
     * lightning fire if and only if vanilla is storming.
     */
    public static boolean isStormy() {
        return stormy;
    }

    public static boolean thunderPossible() {
        return thunderPossible;
    }

    /**
     * Intensity a profile must reach before thunder fires. Thundery profiles (high
     * {@code thunderBias}) get thunder at lower intensity; arid ones need a real
     * downpour. At bias 0 this is 1.12.2's default threshold unchanged.
     */
    public static float thunderThreshold(PrecipitationProfile profile) {
        return THUNDER_THRESHOLD * (1F - profile.thunderBias * THUNDER_BIAS_RANGE);
    }

    /**
     * Which seasonal column of the profile table applies.
     *
     * <p>Tropical columns are used only for the two tropical profiles, because
     * whether a <em>given</em> biome follows the tropical cycle is Serene Seasons'
     * call, not ours - and that answer already reached us through the phase gate.
     * Everything else uses the four temperate seasons.</p>
     */
    /**
     * Shapes how the rolled peak is approached: a slow attack so a storm visibly
     * builds calm -> light -> ... -> heavy, then a gentle gusting wobble around the
     * peak once it is reached.
     *
     * <p>1.12.2 leaned on the vanilla 5s ramp for this, which meant a moderate
     * shower reached its ceiling in about 3s and then sat perfectly flat for the
     * remaining minutes of the event - intensity was a constant, not a shape.</p>
     */
    private static float envelope(long gameTime, boolean raining) {
        if (!raining)
            return lastEnvelope;

        // Part one - the attack. Climbs from nothing to the rolled base over the
        // profile's attack length.
        //
        // Linear, not smoothstepped. Smoothstep spends the first fifth of the
        // climb producing a tenth of the intensity - a dead zone at exactly the
        // point where the player is looking for the rain to start - and then
        // accelerates, so the storm read as "nothing, nothing, then suddenly
        // building fast". A straight line over the full attack length still
        // takes just as long, it simply does not waste the beginning. The value
        // is continuous either way and the output low-pass takes care of the
        // slope change where the attack hands over to the sustain.
        final float eased = attackTicks > 0F
                ? Mth.clamp((gameTime - attackStartTick) / attackTicks, 0F, 1F)
                : 1F;

        // Part two - the sustain. Two slow sines around the base, sized by roll two.
        // The periods are coprime-ish and both well over a minute, so the sum does not
        // repeat inside any storm you are likely to watch, and neither is fast enough
        // to read as flicker.
        final double seconds = gameTime / 20D;
        final double wa = 1D - gustWeightB;
        double gust = Math.sin(seconds * TWO_PI / gustPeriodA + gustPhase) * wa
                + Math.sin(seconds * TWO_PI / gustPeriodB + gustPhase * 1.7D) * gustWeightB;

        // Soft-clip towards a square wave. tanh keeps it continuous (a hard sign()
        // would step, and stepping is the one thing the wobble must never do) while
        // squashing the smooth middle of the sine and leaving the turns steep, which
        // is the difference between a steady soak and weather that arrives in fits.
        // Dividing by tanh(k) restores the +/-1 range so gustAmplitude keeps its
        // meaning whatever the sharpness.
        if (gustSharpness > 0F) {
            final double k = 1D + gustSharpness * 4D;
            gust = Math.tanh(gust * k) / Math.tanh(k);
        }

        // The wobble is applied in the space that is actually available, not
        // symmetrically around the base.
        //
        // The previous form was clamp(base * (1 + amp * gust), 0, base): the upper
        // bound was the base itself, so every upward half-cycle was flattened
        // against it and the intensity could only ever sit at or below the rolled
        // peak. A storm therefore had no gusts at all on the way up - it had a
        // ceiling it was pinned to - and the one direction that survived (downward)
        // was the direction a viewer least reads as "it is easing off and coming
        // back".
        //
        // Clamping symmetrically to [0, 1] instead does not fix it either: with
        // base 0.92 and amplitude 0.30 the peak is 1.22, and clamping that to 1
        // turns the top of every cycle into a flat run of constant intensity -
        // which is worse than no wobble, because it reads as the rain sticking.
        //
        // So the amplitude is capped by the room in each direction. Upward room
        // shrinks as the storm gets heavier; downward room shrinks as it gets
        // lighter. Nothing is ever clipped, and the asymmetry is physically right:
        // a downpour can only let up, a drizzle can only pick up.
        // The attack no longer starts at zero - see ATTACK_FLOOR.
        final float attackFactor = ATTACK_FLOOR + (1F - ATTACK_FLOOR) * eased;
        // Re-express the rolled base against the climate the player is standing in
        // now. The roll itself is fixed for the event (that is what makes it a
        // weather event rather than a per-biome lookup), but it was drawn against
        // the mode where it started, so walking into wetter country scales it up
        // and walking into drier country scales it down - smoothly, because
        // locationMode is low-passed.
        final float scale = locationScale();
        final float base = Mth.clamp(rolled * attackFactor * scale, 0F, 1F);
        final float swing = base * gustAmplitude;
        final float up = Math.min(swing, 1F - base);
        final float down = Math.min(swing, base);
        final float wobble = (float) gust;
        float target = Mth.clamp(base + (wobble >= 0F ? wobble * up : wobble * down), 0F, 1F);
        // Discrete squalls ride on top of the swells - see applySquall.
        target = Mth.clamp(applySquall(target, base, gameTime), 0F, 1F);
        // A restored event joins mid-storm, so it must appear at its current level
        // immediately rather than low-passing up from zero over the next few seconds.
        if (warmStart) {
            warmStart = false;
            smoothed = target;
        }
        lastEnvelope = target;
        return target;
    }

    /**
     * Where the in-progress event is remembered. One small file next to the game
     * directory, keyed by world seed and dimension, so a different world (or a
     * different dimension in the same world) never inherits a storm.
     */
    private static java.nio.file.Path stateFile() {
        try {
            return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("dsurround-precip-event.json");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Identifies the world the persisted event belongs to. Single player uses the
     * save name, multiplayer the server address; either way the dimension is part
     * of the key, so a storm in the overworld is never inherited by the nether.
     */
    private static String worldKey(Level level) {
        String who = "unknown";
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server != null)
                who = "sp:" + server.getWorldData().getLevelName();
            else if (mc.getCurrentServer() != null)
                who = "mp:" + mc.getCurrentServer().ip;
        } catch (Throwable t) {
            // fall through with the default
        }
        // String.valueOf, not the key's location()/identifier(): the accessor is
        // named differently on each version and toString() is unique either way.
        return who + "|" + String.valueOf(level.dimension());
    }

    /** Remember the event just rolled so a later session can pick it up. */
    private static void persistEvent(Level level) {
        var file = stateFile();
        if (file == null)
            return;
        try {
            var obj = new com.google.gson.JsonObject();
            obj.addProperty("world", worldKey(level));
            obj.addProperty("startTick", attackStartTick);
            obj.addProperty("base", rolled);
            obj.addProperty("gustiness", gustiness);
            obj.addProperty("gustPhase", gustPhase);
            obj.addProperty("gustPeriodA", gustPeriodA);
            obj.addProperty("gustPeriodB", gustPeriodB);
            obj.addProperty("gustWeightB", gustWeightB);
            obj.addProperty("gustSharpness", gustSharpness);
            obj.addProperty("attackSeconds", attackTicks / 20F);
            obj.addProperty("dustBase", dustBase);
            java.nio.file.Files.writeString(file, obj.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // Persistence is a nicety. It must never be able to break a tick.
        }
    }

    /**
     * Forget the event. Called when the rain stops, so that a world which has
     * been reloaded after a dry spell rolls a genuinely new event instead of
     * inheriting the attack clock of a storm that ended sessions ago.
     */
    private static void clearEvent(Level level) {
        resetSqualls();
        dustIntensity = 0F;
        dustSmoothed = 0F;
        var file = stateFile();
        if (file == null)
            return;
        try {
            // Only delete the persistence file if it belongs to the world we are
            // in. A ramp at zero because the player stepped into the Nether must
            // not erase the overworld storm they will walk back into.
            if (java.nio.file.Files.isRegularFile(file)) {
                var obj = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(file,
                                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (!obj.has("world")
                        || obj.get("world").getAsString().equals(worldKey(level)))
                    java.nio.file.Files.deleteIfExists(file);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    /**
     * Clears per-session state when a world is left: the event, the channels,
     * the dust roll and the diagnostics lock are all per-world, and statics
     * that survive a disconnect are exactly how weather from one world leaks
     * into the next. The persistence file is deliberately kept - it belongs to
     * the world being left, and restoreEvent validates the world key on return.
     */
    public static void onWorldLeft() {
        eventActive = false;
        ambient = 0F;
        smoothed = 0F;
        graded = 0F;
        attackProgress = 0F;
        rolled = 0F;
        dustBase = 0F;
        dustSmoothed = 0F;
        dustIntensity = 0F;
        warmStart = false;
        lastRamp = 0F;
        lastRising = false;
        eventRestored = false;
        rainTexturePhase = 0F;
        forced = -1F;
        resetSqualls();
        PrecipitationResponse.resetAll();
    }

    /**
     * Pick up an event that was already running when the player joined.
     *
     * <p>Without this, joining a world mid-storm rolls a brand new event with
     * {@code attackStartTick} set to now, so the storm the player was standing in
     * restarts from drizzle and climbs all over again. The attack clock is
     * restored instead, which means the intensity resumes exactly where it should
     * be for how long the storm has been going.</p>
     *
     * @return true when an event was restored and the rolls must be skipped
     */
    private static boolean restoreEvent(Level level, PrecipitationProfile profile) {
        var file = stateFile();
        if (file == null || !java.nio.file.Files.isRegularFile(file))
            return false;
        try {
            var text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            var obj = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (!obj.get("world").getAsString().equals(worldKey(level)))
                return false;

            final long start = obj.get("startTick").getAsLong();
            final long now = level.getGameTime();
            // A start in the future means the file is from another world that happens
            // to share this seed, or the clock went backwards.
            if (start > now)
                return false;

            rolled = Mth.clamp(obj.get("base").getAsFloat(), 0F, 1F);
            // Read defensively: a file written before the dust roll existed has
            // no such key, and falling back to "no dust" is far better than
            // failing the restore and restarting the storm from drizzle.
            dustBase = obj.has("dustBase")
                    ? Mth.clamp(obj.get("dustBase").getAsFloat(), 0F, 1F) : 0F;
            gustiness = obj.get("gustiness").getAsFloat();
            gustPhase = obj.get("gustPhase").getAsDouble();
            // The shape fields are read defensively: a file written before roll two
            // drew a shape has no such keys, and failing to restore the wobble would
            // be far less damaging than failing the whole restore and restarting the
            // storm from drizzle.
            gustPeriodA = obj.has("gustPeriodA") ? obj.get("gustPeriodA").getAsFloat() : GUST_PERIOD_A;
            gustPeriodB = obj.has("gustPeriodB") ? obj.get("gustPeriodB").getAsFloat() : GUST_PERIOD_B;
            gustWeightB = obj.has("gustWeightB") ? obj.get("gustWeightB").getAsFloat() : (float) GUST_WEIGHT_B;
            gustSharpness = obj.has("gustSharpness") ? obj.get("gustSharpness").getAsFloat() : 0F;
            attackTicks = Math.max(0F, obj.get("attackSeconds").getAsFloat()) * 20F;
            attackStartTick = start;
            gustAmplitude = profile.gustAmplitudeAt(rolled) * gustiness;
            rollMode = profile.modeFor(currentSeason);
            locationMode = rollMode;
            lastEnvelope = 0F;
            warmStart = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static PrecipitationSeason seasonFor(PrecipitationProfile profile) {
        var handler = SeasonManager.HANDLER;
        if (handler.usesTropicalSeasons()
                && (profile == PrecipitationProfile.ARID
                    || profile == PrecipitationProfile.TROPICAL
                    || profile == PrecipitationProfile.DUST_STORM))
            return handler.isTropicalWet()
                    ? PrecipitationSeason.TROPICAL_WET
                    : PrecipitationSeason.TROPICAL_DRY;

        if (handler.isSpring())
            return PrecipitationSeason.SPRING;
        if (handler.isSummer())
            return PrecipitationSeason.SUMMER;
        if (handler.isAutumn())
            return PrecipitationSeason.AUTUMN;
        if (handler.isWinter())
            return PrecipitationSeason.WINTER;
        // No calendar at all. Returning null here used to fall back to the
        // SUMMER column, which pinned a vanilla world to one sixth of the
        // table. See PrecipitationSeason.VANILLA.
        return PrecipitationSeason.VANILLA;
    }

}
