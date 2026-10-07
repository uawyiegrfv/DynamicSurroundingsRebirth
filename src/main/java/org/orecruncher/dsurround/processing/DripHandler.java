package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.client.particle.ModParticles;
import org.orecruncher.dsurround.client.particle.WaterDripParticle;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;
import net.minecraft.client.particle.Particle;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.processing.scanner.DripScanner;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Eave and canopy drips: while it rains, and for a while after, water that
 * gathered on a roof leaves at its edges, falls, and lands with a plip.
 * Client-side atmosphere only - it reads the world, plays a sound, spawns one
 * particle, and writes nothing.
 *
 * <p>The shape of the budget is the whole feature. The scan can find dozens
 * of candidate points under a village's worth of roofs, and if every one of
 * them fired on its own dice the storm would sound like hail on sheet metal -
 * the exact failure the material rain layer spent nine rounds digging out of.
 * So intensity enters exactly once: a single global rate
 * {@code MAX_RATE * value<sup>2</sup>} shared by all points. The square is
 * what makes the tail - the rain has stopped, the roof is still draining, and
 * what is left is a drop every few seconds, each one further apart. That tail
 * is the reason the feature exists, and it is also why the rate is a budget
 * rather than a per-point dice: the number of sounding drips must go down
 * smoothly to zero instead of switching points off.</p>
 *
 * <p>The sounds are the two 1.12.2 pools that have been sitting in
 * sounds.json unused since the port: {@code waterdrops} (water1-3) for a drop
 * landing on a solid, {@code waterdrips} (drop1-4) for a drop landing in
 * water - the same split {@code ParticleDripOverride} made. Pitch and level
 * are ranges, sampled per play, so four and three recordings stretch a long
 * way; the no-repeat rule is not even needed at these rates, only the
 * per-point cooldown.</p>
 *
 * <p>Points are pooled, not re-picked every scan: a rescan confirms the
 * existing ones first and only fills what fell out, so the drip does not
 * hop around the roof every half second - the material layer's sixth log
 * already paid for that lesson. The one predicate, {@link DripScanner#
 * buildPoint}, is also the fire-time check, so a roof the player digs away
 * goes silent on the next roll rather than on the next scan.</p>
 */
public class DripHandler {

    /**
     * The maintainer's listening classification, wired in as it was sorted.
     * Ground drips come in two flavors - big/full for heavy rain, small/short
     * for a drizzle - and each deliberately mixes vanilla dripstone
     * recordings with the 1.12.2 droplets he kept. Drips landing in water use
     * vanilla's own cauldron set. The old waterdrops pool (water1-3) is
     * retired: it would not play for him.
     */
    private static final ResourceLocation GROUND_BIG =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "weather/drip/ground_big");
    private static final ResourceLocation GROUND_SMALL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "weather/drip/ground_small");
    private static final ResourceLocation LANDING_WATER =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block.pointed_dripstone.drip_water_into_cauldron");
    /** Ground drips read as the heavier set once the channel passes this. */
    private static final float BIG_FLAVOR_THRESHOLD = 0.5F;
    /** The icicle layer's melt drops are the small ones (its slot pins the
     * size exactly). */
    public static final double MELT_DRIP_SIZE = 0.45D;

    // Scan cadence. The roofs do not move; twenty ticks answers a dug-away
    // eave within a second, and fire-time validation answers it immediately.
    private static final int SCAN_TICKS = 20;
    /** Re-scan sooner than that when the player walks this far (4 blocks). */
    private static final double RESCAN_MOVE_SQ = 16.0D;
    /**
     * Floor on how often the walk test may bring the next scan forward.
     *
     * <p>Walking is what the move test is for, and at walking speed the twenty-tick cadence is
     * already the binding constraint. Nothing bounded it from above, though: at elytra speed (about
     * 1.5 blocks a tick) the four-block test is satisfied every three ticks, so the scan ran six
     * times as often as intended and the cost of a 33x33x27 box scaled with the player's speed.
     * Ten ticks keeps the responsiveness that matters (a new eave answered within half a second)
     * and puts a ceiling on the rate.</p>
     *
     * <p>It rations only the <em>extra</em> scans. The twenty-tick period and the still-valid
     * check are untouched, so a stationary player sees no change at all.</p>
     */
    private static final int MOVE_RESCAN_MIN_TICKS = 10;
    /** A point further than this (3D) is neither picked nor kept - beyond it the LINEAR attenuation is already silent. */
    /**
     * Must not be smaller than the scan box, or points the scan found are
     * thrown away by this check - which is how one log line could report
     * candidates=1 and "no drip points" at the same time. The box is now 16
     * horizontally and 14 up, so its far corner (16*16 + 14*14 = 452) does sit
     * outside this 400 and is discarded; that costs a little wasted scanning
     * and nothing else, and raising it would put drips beyond the range the
     * attenuation can render.
     */
    private static final long RELEASE_DIST_SQ = 400L;
    /**
     * Minimum gap between two drops, in blocks squared, measured at the hang
     * point. 0.16 = 0.4 blocks: close enough for one block edge to carry two
     * threads, far enough that they never overlap.
     */
    private static final double MIN_HANG_SPREAD_SQ = 0.16D;
    /** Hard ceiling on sounding points; the value curve usually asks for fewer. */
    private static final int MAX_POINTS = 56;
    /**
     * How long one spot owns its stretch of eave before handing it to a
     * random replacement: 30 seconds plus 0-45 more. The maintainer's own
     * design - a spot lives a while, then vanishes, and the replacement
     * lands somewhere else - and the reason a long roof reads as wet all
     * along after a minute even though only a fraction of it drips at any
     * instant.
     */
    private static final int SPOT_LIFETIME_MIN_TICKS = 600;
    private static final int SPOT_LIFETIME_SPAN_TICKS = 900;
    /**
     * Global drips per second at a full channel, shared by every point:
     * {@code BASE * value + SPAN * value^2}. Both terms scale with the value
     * on purpose - the base term is what keeps a drizzle audible, and the
     * square term is what makes a storm busy and the after-rain tail fade
     * into single plips instead of stopping dead. Measured offline
     * (tools/_drip_sim.py): storm ~33/s, medium 13, drizzle 2.9, mist 1.0.
     */
    private static final double DRIP_RATE_BASE = 4.0D;
    private static final double DRIP_RATE_SPAN = 30.0D;
    private static final float TICK_SECONDS = 0.05F;
    /**
     * The same point never sounds twice within this many ticks (0.4s)
     * outside a burst. At the old 0.8s a single point topped out at 1.25
     * drips a second no matter what the budget said - which is exactly why
     * intensity 1.0 sounded the same as half.
     */
    /**
     * Per-point pace, and the reason this is a function of intensity and not a
     * constant: the shared budget is divided among every live point, so a
     * constant cooldown left intensity with only one dial - "how full" - and a
     * downpour then read as more drips rather than as more urgent ones. A
     * drizzle's point waits most of a second between drops; a storm's lets go
     * five times a second.
     */
    private static final int COOLDOWN_CALM_TICKS = 16;
    private static final int COOLDOWN_STORM_TICKS = 4;

    private static int cooldownTicks(final float value) {
        return Math.round(COOLDOWN_CALM_TICKS
                + (COOLDOWN_STORM_TICKS - COOLDOWN_CALM_TICKS) * value);
    }
    /** A drip falls ~2.1 ticks per block (particle gravity 0.06, drag 0.98 - read off the jar); the +2 is a margin. */
    private static final int FALL_TICKS_PER_BLOCK = 2;
    private static final int LANDING_DELAY_EXTRA = 2;
    /**
     * The hang before the drop lets go. {@code DRIPPING_WATER} is vanilla's
     * leaf-drip hang particle: it forms under the surface, bobs and stretches
     * for 40 ticks, and only then spawns the falling drop - the same
     * form-hold-fall read as a dripstone stalactite, which is exactly what
     * the first live test asked for.
     */
    private static final int HANG_TICKS = 40;
    /** Chance that a fired point starts a burst - the sheet lets go again, quickly. */
    /**
     * A point that has "let go" properly becomes a thread of water: it drips
     * from exactly one spot, over and over, for a stretch of seconds, then
     * falls back to the occasional drop. That is what a wet eave actually does
     * - the film gathers at the same place every time - and it is what a
     * downpour is made of.
     */
    /**
     * Chance a fresh point is a thread. Deliberately NOT "most of them" in a
     * downpour: threads ignore the shared budget, so their total rate is
     * (how many) x (how fast each), and at 0.85 of a 40-point pool that is
     * 136 drips a second against a budget of 34 - which blew the in-flight cap
     * and chopped every thread into bursts. A third of the pool at 4 ticks is
     * about 60 a second: plainly threads, and still room for the budget-driven
     * single and bursty points to be heard.
     */
    private static final float STEADY_CHANCE_CALM = 0.10F;
    /** ... and in a downpour. */
    private static final float STEADY_CHANCE_STORM = 0.30F;
    /** Ticks between drops of a thread: slow at a drizzle, quick in a storm. */
    private static final int STEADY_INTERVAL_CALM = 16;
    private static final int STEADY_INTERVAL_STORM = 4;
    /** How long a thread runs, in seconds. */
    private static final float STEADY_SECONDS_CALM = 8F;
    private static final float STEADY_SECONDS_STORM = 30F;

    private static float steadyChance(final float value) {
        return STEADY_CHANCE_CALM + (STEADY_CHANCE_STORM - STEADY_CHANCE_CALM) * value;
    }

    private static int steadyInterval(final float value) {
        return Math.round(STEADY_INTERVAL_CALM
                + (STEADY_INTERVAL_STORM - STEADY_INTERVAL_CALM) * value);
    }

    private static long steadyTicks(final float value) {
        return Math.round((STEADY_SECONDS_CALM
                + (STEADY_SECONDS_STORM - STEADY_SECONDS_CALM) * value) * 20F);
    }

    /**
     * Three kinds of point, always mixed. A real eave is not uniform: one spot
     * lets go a single drop now and then, another gives up a sheet in a quick
     * run, and one has a thread running for half a minute. All three are on the
     * same roof at once.
     */
    private static final int KIND_SINGLE = 0;
    private static final int KIND_BURSTY = 1;
    private static final int KIND_STEADY = 2;

    /** Share of points that are quick multi-drop runs: a drizzle is mostly these. */
    private static final float BURSTY_CHANCE_CALM = 0.40F;
    /** ... a downpour is mostly threads instead. */
    private static final float BURSTY_CHANCE_STORM = 0.12F;

    private static float burstyChance(final float value) {
        return BURSTY_CHANCE_CALM + (BURSTY_CHANCE_STORM - BURSTY_CHANCE_CALM) * value;
    }

    /**
     * How likely a fired point is to run on. Per kind, not a single global
     * number: a single-drop point should almost never double, a bursty one
     * almost always should, and a thread should mostly NOT - it is already
     * committed to a steady drip, and letting it burst too covered its own
     * rhythm with someone else's. (It was 0.35; that is what made every
     * thread sound like a burst with a pause.)
     */
    private static float burstChanceFor(final Spot spot) {
        return switch (spot.kind) {
            case KIND_BURSTY -> 0.85F;
            case KIND_STEADY -> 0.15F;
            default -> 0.12F;
        };
    }

    /**
     * What the drip strength is multiplied by the moment the rain stops.
     *
     * <p>A roof only drips as long as something is feeding it. The cloud passes
     * and the supply is gone - what is left is the water already hanging on the
     * rim, which is a fraction of what a live downpour sustains. Without this
     * the channel's own fall/release was the only thing bringing the drips down,
     * and it starts from full strength: the eave stayed as busy as it had been
     * while it was pouring, and only then thinned out. That read as "the rain
     * stopped but nobody told the roof".</p>
     *
     * <p>The factor is applied on the falling edge of {@code
     * PrecipitationIntensity.ambient()} - shaped intensity, so it is zero the
     * moment the rain does, unlike a channel with a tail - and stays applied:
     * the channel then carries that quarter down to nothing, which is the
     * "then slowly to zero" half.</p>
     */
    private static final float AFTER_STOP_FACTOR = 0.40F;

    /** Chance a fired point is to run on.
    /** Chance each burst follow-up extends the burst by one more drop. */
    private static final float BURST_EXTEND_CHANCE = 0.6F;
    /**
     * How many drops may be in the air at once. Must exceed what a downpour
     * actually has airborne: each drop stays pending for HANG_TICKS plus its
     * fall - about 2.5 to 3 seconds - so at 30-odd drips a second the steady
     * count is around 100. At 96 the pool was permanently full and every
     * thread's drops were being discarded, which is what turned a steady
     * thread into "a few drops, a pause, a few more".
     */
    private static final int MAX_PENDING = 256;
    private static final float MIN_PITCH = 0.85F;
    private static final float MAX_PITCH = 1.15F;
    /**
     * Raised twice: 0.35/0.55 was inaudible over the rain, and 0.55/0.80 was
     * still being lost. A drip is a short, quiet sound competing with a
     * downpour, so it has to be near the top of the range to be heard at all.
     */
    private static final float MIN_VOLUME = 0.70F;
    private static final float MAX_VOLUME = 0.95F;

    private final Configuration config;
    private final IModLog logger;
    private final IAudioPlayer audioPlayer;
    private final ISoundLibrary soundLibrary;
    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private final Map<ResourceLocation, ISoundFactory> factories = new HashMap<>();
    private final List<Spot> pool = new ArrayList<>();
    private final List<PendingLanding> pending = new ArrayList<>();
    /** Drops we are still holding, so a point that stops being one can let go. */
    private final List<HangingDrop> hanging = new ArrayList<>();

    private Level lastWorld;
    private long lastScanTick = -1000L;
    private long gateLogTick = -1000L;
    private long scanLogTick = -1000L;
    /** Scan cost, so "how much is this layer" is measured and not guessed. */
    private long lastScanNanos = 0L;
    private long scanNanos = 0L;
    private long scanCount = 0L;
    /**
     * This tick's copy of the trace switch. Every diagnostic cost in this
     * layer - the nanoTime pair around the scan, the running totals, the
     * rejection histogram, the sample coordinates and the two INFO lines -
     * hangs off this one flag, so a player with debug logging off pays for
     * none of it. Same shape as the rain-material layer's rainMatTrace.
     */
    private boolean dripTrace = false;
    /** 1 while it is raining; {@link #AFTER_STOP_FACTOR} from the moment it stops. */
    private float afterStop = 1F;
    private float lastAmbient = -1F;
    private int failures = 0;
    private BlockPos lastScanPos;
    private float accumulator = 0F;

    public DripHandler(Configuration config, IModLog logger, IAudioPlayer audioPlayer,
                       ISoundLibrary soundLibrary, ITickCount tickCount) {
        this.config = config;
        this.logger = logger;
        this.audioPlayer = audioPlayer;
        this.soundLibrary = soundLibrary;
        this.tickCount = tickCount;
        ClientState.TICK_END.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        // The drip layer registers on the tick-end bus directly (not through
        // the effectHandlers loop), so an exception here would take every
        // later subscriber down with it AND silently freeze this layer's
        // pool at whatever it was - the fifth live test's "one spot, forever"
        // smell. Log the first failure in full, then one line every 200
        // ticks, and never let it escape.
        try {
            this.tick0(client);
        } catch (final Throwable t) {
            final int failures = ++this.failures;
            if (failures == 1 || failures % 200 == 0)
                this.logger.error(t, "[DripHandler] threw (%d consecutive); the drip layer sat this tick out",
                        failures);
        }
    }

    private void tick0(Minecraft client) {
        if (!this.config.weatherOptions.enableWaterDrips)
            return;
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;
        this.dripTrace = this.trace();
        DripScanner.TRACE = this.dripTrace;

        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;

        final Level world = player.level();
        if (world != this.lastWorld) {
            // Points and landings belong to the world they were scanned in.
            this.lastWorld = world;
            this.pool.clear();
            this.pending.clear();
            this.accumulator = 0F;
            this.lastScanTick = -1000L;
            // Neither should a new world inherit the last one's rain-stop cut.
            this.resetAfterStop();
        }

        final long tick = this.tickCount.getTickCount();
        // It is SNOWING here, not raining: the eaves are frozen and the
        // icicle layer owns what hangs on them - liquid drops on icicles read
        // as a defect (maintainer report). Zero the value; the pool
        // bookkeeping keeps running so nothing stateful is skipped.
        final boolean frozen = world.isRaining()
                && PrecipitationIntensity.potentialPhase(player.blockPosition()) == Biome.Precipitation.SNOW;
        final float value = frozen ? 0.0F : this.valueFor(world);

        if (value <= 0F) {
            // Clear weather: nothing retained, nothing asked - the scan does
            // not even run, which is where most of this layer's cost was
            // always going to be.
            if (!this.pool.isEmpty() || !this.pending.isEmpty() || this.accumulator != 0F) {
                this.pool.clear();
                this.pending.clear();
                this.accumulator = 0F;
            }
            // A silent gate and a broken layer are indistinguishable from the
            // chair. The one state worth shouting about is rain the player
            // can see falling while this layer still reads zero - say so, at
            // most once every ten seconds, so the first live test diagnoses
            // itself instead of coming back as "no drips".
            final float ramp = world.getRainLevel(1F);
            if (this.dripTrace && ramp > 0F && tick - this.gateLogTick >= 200L) {
                this.gateLogTick = tick;
                this.logger.info("[DripHandler] gate closed while raining: value=%.2f rainLevel=%.2f grading=%s",
                        value, ramp, PrecipitationIntensity.grading());
            }
            return;
        }

        final BlockPos playerPos = player.blockPosition();
        final boolean moved = this.lastScanPos == null
                || this.lastScanPos.distToCenterSqr(playerPos.getX(), playerPos.getY(), playerPos.getZ()) >= RESCAN_MOVE_SQ;
        // Moved only brings the next scan forward, and only past the floor - see
        // MOVE_RESCAN_MIN_TICKS. The first scan of a session still happens at once, because
        // lastScanPos is null and lastScanTick starts far in the past.
        final boolean movedNow = moved && tick - this.lastScanTick >= MOVE_RESCAN_MIN_TICKS;
        if (tick - this.lastScanTick >= SCAN_TICKS || movedNow)
            this.rescan(world, playerPos, tick, value);

        // The Poisson-ish roll: one shared budget, jittered per tick so the
        // spacing never settles into a metronome.
        this.accumulator += (DRIP_RATE_BASE * value + DRIP_RATE_SPAN * value * value)
                * TICK_SECONDS * (0.5F + this.random.nextFloat());
        if (this.accumulator > 2F)
            this.accumulator = 2F;
        while (this.accumulator >= 1F) {
            this.accumulator -= 1F;
            this.fireOne(world, player, tick, value);
        }

        // Bursts. A sheet of water that lets go tends to let go several times
        // in a row - the "one point, several quick drops" the first live test
        // missed. A burst bypasses the global budget (it IS rain coming off
        // one point) and dies out on its own: each follow-up extends only by
        // half chance.
        for (final Spot spot : this.pool) {
            // A spot whose block is gone stops producing on the very next tick.
            // These two paths bypass the budget draw, and the budget draw was
            // the only place that asked - so a broken eave kept dripping for
            // up to a scan interval (20 ticks) and a thread re-fired every few
            // ticks throughout. Cost is a handful of block reads per spot per
            // tick, a few percent of what one scan already costs.
            if (!DripScanner.isStillValid(world, spot.point))
                continue;
            // Threads: one place, dripping on a clock of its own, for a stretch
            // of seconds. They bypass the shared budget because they ARE one
            // spot letting go - the budget decides how much of the eave is
            // going at once, not how fast a single thread runs.
            if (tick < spot.steadyUntil && tick >= spot.nextSteadyTick) {
                spot.nextSteadyTick = tick + spot.steadyInterval;
                spot.lastFire = tick;
                this.fireSpot(world, player, spot, tick, value);
            }
            if (spot.burstLeft > 0 && tick >= spot.nextBurstTick) {
                spot.burstLeft--;
                this.fireSpot(world, player, spot, tick, value);
                if (this.random.nextFloat() < BURST_EXTEND_CHANCE)
                    // Also intensity-paced: a storm's sheet lets go in a
                    // stutter, a drizzle's in slow single drops.
                    spot.nextBurstTick = tick + (int) (10 - 7 * value)
                            + this.random.nextInt(10 - (int) (5 * value));
                else
                    spot.burstLeft = 0;
            }
        }

        this.releaseDrops(world);
        this.playDue(tick);

        // A spot whose time is up hands its stretch of eave back; the refill
        // lands the replacement somewhere else at random (see rescan). This
        // rotation is what makes a long roof read as wet all along after a
        // minute even though only a fraction of it drips at any instant.
        boolean expired = false;
        for (final Iterator<Spot> it = this.pool.iterator(); it.hasNext(); ) {
            if (tick >= it.next().expireTick) {
                it.remove();
                expired = true;
            }
        }
        if (expired)
            this.rescan(world, playerPos, tick, value);
    }

    /**
     * The drip strength for this tick: the channel's value, cut down the moment
     * the rain stops. {@code ambient()} is the falling edge to watch because it
     * is shaped intensity and has no tail of its own - a channel would take
     * seconds to admit the rain had gone.
     */
    private float valueFor(final Level world) {
        final float ambient = PrecipitationIntensity.ambient();
        if (this.lastAmbient >= 0F && this.lastAmbient > 0F && ambient <= 0F)
            this.afterStop = AFTER_STOP_FACTOR;
        else if (ambient > 0F)
            this.afterStop = 1F;
        this.lastAmbient = ambient;
        final float raw = this.drivingValue(world);
        return raw <= 0F ? 0F : raw * this.afterStop;
    }

    /**
     * Reset with the weather: a new world, or rain starting again after a dry
     * spell, must not inherit the previous stop's cut.
     */
    private void resetAfterStop() {
        this.afterStop = 1F;
        this.lastAmbient = -1F;
    }

    /**
     * What drives the budget. With grading on it is the {@code DRIP} channel -
     * an intensity locked with {@code /dsprecip lock} is honored immediately,
     * because a lock is a hypothesis and waiting out the rise before the
     * first plip is exactly the trap the wet-footstep accent fell into. With
     * grading off the layer is vanilla's rain level, like every consumer that
     * has no curves to ride.
     */
    /** The trace switch, mirrored onto the scanner: logging on AND the drip bit in the mask. */
    private boolean trace() {
        return this.config.logging.enableDebugLogging
                && (this.config.logging.traceMask & Configuration.Flags.WATER_DRIPS) != 0;
    }

    private float drivingValue(Level world) {
        if (!PrecipitationIntensity.grading())
            return Math.max(world.getRainLevel(1F), PrecipitationIntensity.forced());
        return Math.max(PrecipitationResponse.DRIP.value(), PrecipitationIntensity.forced());
    }

    /**
     * Keeps the spots that are still good, then fills whatever is vacant
     * with a <b>random</b> draw of the scan.
     *
     * <p>The fill is random on purpose - the maintainer asked for it
     * explicitly: a spot lives a while, then goes away, and the replacement
     * lands somewhere else. Scored fills kept answering "where is the best
     * spot" with the same corners, and a long eave then only ever dripped at
     * the few blocks nearest the player; a random draw walks the pool around
     * the whole roof over a minute, which is what makes it read as wet all
     * along.</p>
     */
    private void rescan(Level world, BlockPos playerPos, long tick, float value) {
        this.lastScanTick = tick;
        this.lastScanPos = playerPos.immutable();
        final boolean wasDripping = !this.pool.isEmpty();

        final List<Spot> kept = new ArrayList<>(this.pool.size());
        for (final Spot spot : this.pool) {
            if (tick >= spot.expireTick)
                continue;
            if (spot.point.point().distSqr(playerPos) > RELEASE_DIST_SQ)
                continue;
            if (!DripScanner.isStillValid(world, spot.point))
                continue;
            kept.add(spot);
        }

        final int want = Math.min(MAX_POINTS, 10 + Math.round(30F * value));
        int candidateCount = 0;
        if (kept.size() < want) {
            // Timed only while tracing: with the switch off this layer does not
            // call nanoTime at all, the way the rain-material layer does it.
            final long scanStart = this.dripTrace ? System.nanoTime() : 0L;
            final var candidates = DripScanner.scan(world, playerPos);
            if (this.dripTrace) {
                this.lastScanNanos = System.nanoTime() - scanStart;
                this.scanCount++;
                this.scanNanos += this.lastScanNanos;
            }
            candidateCount = candidates.size();
            if (this.trace()) {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < DripScanner.STAT_COUNT; i++)
                    sb.append(DripScanner.STAT_NAMES[i]).append('=').append(DripScanner.STATS[i]).append(' ');
                this.logger.debug(Configuration.Flags.WATER_DRIPS,
                        "[DripHandler] scan @(%d,%d,%d) candidates=%d kept=%d want=%d %s samples=%s",
                        playerPos.getX(), playerPos.getY(), playerPos.getZ(),
                        candidates.size(), kept.size(), want, sb, DripScanner.SAMPLES);
            }
            // Fisher-Yates with the shared random: the replacements land
            // wherever the scan says is possible, not where a score says is
            // best.
            final List<DripScanner.DripPoint> shuffled = new ArrayList<>(candidates.size());
            for (final var candidate : candidates)
                shuffled.add(candidate);
            for (int i = shuffled.size() - 1; i > 0; i--) {
                final int j = this.random.nextInt(i + 1);
                final var tmp = shuffled.get(i);
                shuffled.set(i, shuffled.get(j));
                shuffled.set(j, tmp);
            }
            for (final var candidate : shuffled) {
                if (kept.size() >= want)
                    break;
                if (candidate.point().distSqr(playerPos) > RELEASE_DIST_SQ)
                    continue;
                // No skipping. Taking every eligible block does give a row of
                // eave blocks one point each, and skipping was the obvious way
                // to break that up - but the scan is already liable to miss
                // blocks, and dropping candidates on purpose would only make
                // the places that should be dripping quieter. Unevenness comes
                // from the second attempt below instead: some blocks carry two
                // threads, some one, and their hang positions vary along the
                // rim.
                // Two tries at the same block: the first places a drop, the
                // second may place another one elsewhere on the same block
                // (a roof edge can carry two threads). Only half the time, so
                // "two here" and "one here" mix instead of pairing up neatly.
                final boolean corner = (candidate.faces() & DripScanner.FACE_CORNER) != 0;
                for (int attempt = 0; attempt < 2 && kept.size() < want; attempt++) {
                    // 转角汇水，更容易挂住：给它的第二次机会大得多。
                    final float skipSecond = corner ? 0.10F : 0.5F;
                    if (attempt > 0 && this.random.nextFloat() < skipSecond)
                        break;
                    final double[] hang = hangSpot(candidate);
                    if (!isFree(kept, hang[0], hang[1]))
                        continue;
                    final Spot spot = new Spot(candidate, tick + SPOT_LIFETIME_MIN_TICKS
                            + this.random.nextInt(SPOT_LIFETIME_SPAN_TICKS), hang[0], hang[1]);
                    // Which kind this point is. Threads are drawn first; whatever
                    // is left splits between quick runs and the occasional single
                    // drop, so all three are always present on the same roof.
                    final float roll = this.random.nextFloat();
                    // 转角汇水：更可能挂出一道水线，而且那道线跑得更快 ——
                    // 两片屋面的水都往这个角上来。
                    float pSteady = steadyChance(value);
                    if (corner)
                        pSteady = Math.min(0.95F, pSteady * 1.6F + 0.10F);
                    final float pBursty = burstyChance(value);
                    if (roll < pSteady) {
                        spot.kind = KIND_STEADY;
                        spot.steadyInterval = corner
                                ? Math.max(2, (int) (steadyInterval(value) * 0.6F))
                                : steadyInterval(value);
                        spot.steadyUntil = tick + steadyTicks(value);
                        spot.nextSteadyTick = tick + spot.steadyInterval;
                    } else if (roll < pSteady + pBursty) {
                        spot.kind = KIND_BURSTY;
                    }
                    kept.add(spot);
                }
            }
        }

        this.pool.clear();
        this.pool.addAll(kept);
        // The two INFO lines that make a silent first test readable: the
        // moment drips start, and the standing-still-and-nothing case, rate
        // limited to once every five seconds. Everything finer stays debug.
        if (this.dripTrace && !wasDripping && !this.pool.isEmpty()) {
            // Threads counted, so "I never see a steady one" is answerable
            // from the log: threads=0 means they were never assigned, threads>0
            // means they are running and the problem is elsewhere.
            int threads = 0;
            for (final Spot spot : this.pool)
                if (spot.kind == KIND_STEADY)
                    threads++;
            this.logger.info("[DripHandler] dripping: %d points (%d threads) near (%d,%d,%d) value=%.2f afterStop=%.2f scan=%.2fms/avg %.2fms x%d",
                    this.pool.size(), threads, playerPos.getX(), playerPos.getY(), playerPos.getZ(), value,
                    this.afterStop, this.lastScanNanos / 1.0e6D,
                    this.scanCount == 0L ? 0D : (this.scanNanos / (double) this.scanCount) / 1.0e6D,
                    this.scanCount);
        }
        if (this.dripTrace && this.pool.isEmpty() && tick - this.scanLogTick >= 100L) {
            this.scanLogTick = tick;
            // The rejection histogram rides along on the INFO line. The counts
            // are accumulated unconditionally by DripScanner.note() - only the
            // sample coordinates are gated behind the trace flag - so this
            // costs nothing and is available without turning debug logging on.
            // rainUnreachable high = the sky/heightmap test is too strict;
            // noOpenFace high = the seam test is eating the rim;
            // noLanding high = the drop has nowhere to land.
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < DripScanner.STAT_COUNT; i++)
                sb.append(' ').append(DripScanner.STAT_NAMES[i]).append('=').append(DripScanner.STATS[i]);
            this.logger.info("[DripHandler] no drip points near (%d,%d,%d) value=%.2f (candidates=%d)%s",
                    playerPos.getX(), playerPos.getY(), playerPos.getZ(), value, candidateCount, sb);
        }
        this.logger.debug(Configuration.Flags.WATER_DRIPS,
                "[DripHandler] scan value=%.2f pool=%d", value, this.pool.size());
    }

    /**
     * Spread rule, measured between where the drops actually hang rather than
     * between block positions. Judging by block gave one drop per block and no
     * more - so a block-wide eave could never carry two threads, and density
     * had nowhere to go. Judging by the hang coordinates lets one block hold
     * two drops on different parts of its rim while still keeping any two
     * drops from landing on top of each other.
     */
    private boolean isFree(List<Spot> chosen, double hangX, double hangZ) {
        for (final Spot spot : chosen) {
            final double dx = spot.hangX - hangX;
            final double dz = spot.hangZ - hangZ;
            if (dx * dx + dz * dz < MIN_HANG_SPREAD_SQ)
                return false;
        }
        return true;
    }

    /**
     * One drip out of the budget: pick a cooled-down, still-valid point -
     * corners first, then near - and fire it. If nothing qualifies the token
     * is dropped rather than banked; a roof that went quiet should not get a
     * burst the moment a point cools down. A fired point may also start a
     * burst, the quick follow-ups of one sheet letting go.
     */
    /**
     * One drip out of the budget: pick a cooled-down, still-valid point by
     * <b>uniform random</b> draw (reservoir sampling) and fire it. The pick
     * was nearest-first once, and the budget cap then did something ugly:
     * the nearest handful of points saturated the budget and the far end of
     * a long eave never fired again - the fourth live test's bell curve.
     * Distance already speaks through attenuation; the pick must not speak
     * for it too. If nothing qualifies the token is dropped rather than
     * banked. A fired point may also start a burst, the quick follow-ups of
     * one sheet letting go.
     */
    private void fireOne(Level world, Player player, long tick, float value) {
        Spot best = null;
        int eligible = 0;
        int inCooldown = 0;
        int outOfRange = 0;
        int invalid = 0;
        for (final Spot spot : this.pool) {
            if (tick - spot.lastFire < cooldownTicks(value)) {
                inCooldown++;
                continue;
            }
            final var point = spot.point;
            final double distSq = point.point().distToCenterSqr(player.getX(), player.getY(), player.getZ());
            if (distSq > RELEASE_DIST_SQ) {
                outOfRange++;
                continue;
            }
            if (!DripScanner.isStillValid(world, point)) {
                invalid++;
                continue;
            }
            eligible++;
            if (this.random.nextInt(eligible) == 0)
                best = spot;
        }
        if (best == null) {
            if (this.trace())
                this.logger.debug(Configuration.Flags.WATER_DRIPS,
                        "[DripHandler] no fire: pool=%d eligible=%d cooldown=%d outOfRange=%d invalid=%d",
                        this.pool.size(), eligible, inCooldown, outOfRange, invalid);
            return;
        }
        if (this.trace())
            this.logger.debug(Configuration.Flags.WATER_DRIPS,
                    "[DripHandler] fire at (%d,%d,%d) eligible=%d/%d value=%.2f",
                    best.point.point().getX(), best.point.point().getY(), best.point.point().getZ(),
                    eligible, this.pool.size(), value);
        best.lastFire = tick;
        if (this.random.nextFloat() < burstChanceFor(best)) {
            best.burstLeft = 2 + this.random.nextInt(4);
            best.nextBurstTick = tick + (int) (8 - 5 * value)
                    + this.random.nextInt(8 - (int) (3 * value));
        }
        this.fireSpot(world, player, best, tick, value);
    }

    /**
     * Where a point's drops hang, decided once per point. One place, one rim:
     * a real eave gathers its film at the same spot every time, and a burst
     * that re-rolled this per drop read as a cluster rather than as repetition.
     * Spreading a long eave out is the job of how many points there are - not
     * of jittering each drop away from its own rim.
     */
    private double[] hangSpot(DripScanner.DripPoint point) {
        double hx = point.point().getX() + 0.5D;
        double hz = point.point().getZ() + 0.5D;
        final int mask = point.faces();
        if (mask == DripScanner.FACE_CENTER) {
            // A post drips from the middle of its underside, exactly - the
            // spray below is for a leaf canopy, which has no single place.
        } else if (mask == 0) {
            hx += (this.random.nextDouble() - 0.5D) * 0.7D;
            hz += (this.random.nextDouble() - 0.5D) * 0.7D;
        } else {
            final int count = Integer.bitCount(mask);
            final int pick = this.random.nextInt(count >= 2 ? count + 1 : 1);
            final boolean diagonal = count >= 2 && pick == count;
            int faceX = 0;
            int faceZ = 0;
            if (diagonal) {
                int index = 0;
                for (final Direction d : Direction.Plane.HORIZONTAL) {
                    if ((mask & (1 << index)) != 0) {
                        faceX += d.getStepX();
                        faceZ += d.getStepZ();
                    }
                    index++;
                }
                // Opposite faces cancel. EAST + WEST sums to (0,0), and a drop
                // with no direction hangs in the middle of the block, on
                // neither rim. Fall back to one of the actual faces.
                if (faceX == 0 && faceZ == 0) {
                    final int fallback = this.random.nextInt(Math.max(1, count));
                    index = 0;
                    int seen = 0;
                    for (final Direction d : Direction.Plane.HORIZONTAL) {
                        if ((mask & (1 << index)) != 0) {
                            if (seen == fallback) {
                                faceX = d.getStepX();
                                faceZ = d.getStepZ();
                                break;
                            }
                            seen++;
                        }
                        index++;
                    }
                }
            } else {
                int seen = 0;
                int index = 0;
                for (final Direction d : Direction.Plane.HORIZONTAL) {
                    if ((mask & (1 << index)) != 0) {
                        if (seen == pick) {
                            faceX = d.getStepX();
                            faceZ = d.getStepZ();
                            break;
                        }
                        seen++;
                    }
                    index++;
                }
            }
            final double lean = 0.42D + this.random.nextDouble() * 0.06D;
            hx += faceX * lean;
            hz += faceZ * lean;
            // And along that edge - across most of it, not just the middle.
            // A 1-block edge is a full block wide; wandering only +/-0.15 used
            // the middle third and left both ends bare. The old +/-0.38 looked
            // like orbiting, but that was because every drop re-rolled it; now
            // the point is fixed, so a wide spread reads as scatter along the
            // eave, not as motion. Chosen once per point, so the drops of one
            // point still coincide exactly.
            if (faceX == 0)
                hx += (this.random.nextDouble() - 0.5D) * 0.84D;
            if (faceZ == 0)
                hz += (this.random.nextDouble() - 0.5D) * 0.84D;
        }
        return new double[] { hx, hz };
    }

    /** The drip itself: hang the drop at the exposed rim, book the landing for when the visible drop hits. */
    private void fireSpot(Level world, Player player, Spot spot, long tick, float value) {
        final var point = spot.point;
        if (pending.size() >= MAX_PENDING)
            return;

        // Fixed per point, not per drop - see Spot.hangX/hangZ and hangSpot().
        // Re-rolling the rim offset for every drop is what made a burst look
        // like a small cluster of drops rather than one spot dripping over and
        // over; spreading a long eave out is the job of how many points there
        // are, not of jittering each drop away from its own rim.
        final double hx = spot.hangX;
        final double hz = spot.hangZ;
        // The underside the scanner measured: the block bottom for a cube or
        // a bottom slab, half a block up for a top slab or an inverted stair.
        // The point-side "just under the block" guess is what floated top
        // slabs in mid-air.
        // 挂低一点，但只低一点点：0.15 会让水滴明显离开下表面，有的看着是悬空的。
        // 0.09 约合 4 厘米，还贴着边但不嵌在方块里。
        final double hy = point.roof().getY() + point.undersideY() - 0.09D;
        // Our own drop particle: pale and a pixel wide (vanilla's
        // dripping_water reads as a wide saturated blob). The hang length and
        // the fall travel ride in through the spawn speeds - a particle
        // options class for a single spawn site is more ceremony than help -
        // and the particle removes itself when the drop lands, which is the
        // moment the sound is booked for.
        // Vanilla's dripping_water: it forms the droplet, hangs and stretches,
        // and after ~40 ticks spawns its own falling drop - the same
        // form-hold-fall read as a dripstone stalactite. Our own particle got
        // the size, colour and animation all wrong across two attempts, and
        // none of those can be judged without looking at the game. Swap this
        // one line back to ModParticles.WATER_DRIP to use ours again.
        // Our own particle, wearing vanilla's texture: water_drip.png is a copy of
        // vanilla's drip_hang.png. Borrowing vanilla's sprite directly does not
        // work, so the drop is ours - which is also what lets a hanging drop be
        // released the moment its block goes away (see releaseDrops).
        this.spawnDrop(world, point, hx, hy, hz);

        // The pool is picked when the drop falls, not when it lands: a drizzle
        // sheds the small set, a storm the big one. Quieter as the roof
        // drains - the tail is sparse AND soft.
        final ResourceLocation pool = point.water()
                ? LANDING_WATER
                : (value >= BIG_FLAVOR_THRESHOLD ? GROUND_BIG : GROUND_SMALL);
        this.pending.add(new PendingLanding(point, pool,
                tick + HANG_TICKS + point.fall() * FALL_TICKS_PER_BLOCK + LANDING_DELAY_EXTRA,
                0.55F + 0.45F * value));
        this.logger.debug(Configuration.Flags.WATER_DRIPS,
                "[DripHandler] drip at (%d,%d,%d) fall=%d water=%s",
                point.point().getX(), point.point().getY(), point.point().getZ(), point.fall(), point.water());
    }

    /** The landing sound, when the falling drop would have hit. */
    /**
     * Spawn one drop and keep hold of it. The handle is the whole point: a drop
     * still hanging when its block goes has to be told to let go, and vanilla's
     * {@code addParticle} does not give one back.
     */
    private void spawnDrop(final Level world, final DripScanner.DripPoint point,
                           final double x, final double y, final double z) {
        // 0 = 用原版自己的大小波动（random*0.5+0.5，即 0.5~1.0 倍）。
        // 之前这里传 1.0，粒子那边"sizeScale > 0 就固定大小"的分支于是把所有
        // 屋檐滴都钉成了同一个尺寸 —— 加冰锥时为了给融化滴定尺寸引进的参数，
        // 顺手把主力路径也一起钉死了。定尺寸只该给融化滴（MELT_DRIP_SIZE）用。
        this.spawnDrop(world, point, x, y, z, 0.0D);
    }

    /**
     * One melt drip for the icicle layer: a drop forms at the position given
     * (the melting icicle's live tip), hangs briefly, falls the eave's travel
     * and lands with the small plip - the drip layer's own complete chain
     * (particle, splash, landing sound), at melt size and a quiet volume.
     * The icicle layer owns WHEN a drop happens; this owns HOW it plays.
     */
    /**
     * Releases every hanging drop parked on this rim, right now - the icicle
     * layer calls it when the icicle on that rim drops, so a melt drop does
     * not hang in the air where its ice used to be. The same release the
     * broken-block pass uses; the drops complete their own fall and plip.
     */
    public void releaseHangingAt(final DripScanner.DripPoint point) {
        for (final HangingDrop held : this.hanging) {
            if (held.point().point().equals(point.point()) && held.drop().isHanging())
                held.drop().release();
        }
    }

    public void icicleMeltDrop(final Level world, final DripScanner.DripPoint point,
                               final double x, final double rootY, final double z) {
        this.spawnDrop(world, point, x, rootY, z, MELT_DRIP_SIZE);
        if (this.pending.size() < MAX_PENDING)
            this.pending.add(new PendingLanding(point, GROUND_SMALL,
                    this.tickCount.getTickCount() + HANG_TICKS
                            + point.fall() * FALL_TICKS_PER_BLOCK + LANDING_DELAY_EXTRA,
                    0.3F));
    }

    /** The drip's own size scale: 1.0 is the eave drop; the icicle layer's
     * melt drops ask for the small one (the third spawn slot pins the size
     * exactly, no vanilla variance). */
    private void spawnDrop(final Level world, final DripScanner.DripPoint point,
                           final double x, final double y, final double z, final double sizeScale) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return;
        // makeParticle, not createParticle: the public one queues as well as
        // creating, and a particle that is queued and then added again ticks
        // and renders twice.
        final Particle particle = ((MixinParticleManager) pm).dsurround_createParticle(
                ModParticles.WATER_DRIP, x, y, z, HANG_TICKS,
                point.fall() * FALL_TICKS_PER_BLOCK + LANDING_DELAY_EXTRA, sizeScale);
        if (particle == null)
            return;
        pm.add(particle);
        if (particle instanceof WaterDripParticle drop)
            this.hanging.add(new HangingDrop(point, drop));
    }


    /**
     * Let go of every drop whose point has stopped being a drip point - its
     * block was broken, so it is hanging off nothing. The same pass drops the
     * bookkeeping for anything that already landed or already let go, which
     * keeps the list no longer than the roof is wet.
     */
    private void releaseDrops(final Level world) {
        if (this.hanging.isEmpty())
            return;
        for (int i = this.hanging.size() - 1; i >= 0; i--) {
            final HangingDrop held = this.hanging.get(i);
            if (held.drop().isDead() || !held.drop().isHanging()) {
                this.hanging.remove(i);
                continue;
            }
            // 只问还挂得住吗，不问天还看得到吗 —— 见 DripScanner.isStillHeld。
            if (!DripScanner.isStillHeld(world, held.point())) {
                held.drop().release();
                this.hanging.remove(i);
            }
        }
    }

    /** A drop we are still holding on to, and the point it hangs from. */
    private record HangingDrop(DripScanner.DripPoint point, WaterDripParticle drop) {
    }

    private void playDue(long tick) {
        final Iterator<PendingLanding> it = this.pending.iterator();
        while (it.hasNext()) {
            final PendingLanding landing = it.next();
            if (landing.dueTick > tick)
                continue;
            it.remove();
            final var factory = this.factory(landing.pool());
            if (factory == null)
                continue;
            final BlockPos landingPos = landing.point.landing();
            final double y = landingPos.getY() + (landing.point.water() ? 0.9D : 1.0D);
            this.audioPlayer.play(factory.createAtLocation(
                    landingPos.getX() + 0.5D, y, landingPos.getZ() + 0.5D, landing.volumeScale));
        }
    }

    /**
     * The two 1.12.2 pools, built once with their jitter ranges. Resolved
     * against the library on first use - the library only knows sounds.json's
     * events after a resource load, so asking at construction would race it.
     */
    private ISoundFactory factory(ResourceLocation pool) {
        // containsKey, not get() == null: a pool that failed to resolve is
        // remembered as null, and get() cannot tell that apart from "never
        // asked" - so every landing re-ran the lookup and re-printed the
        // warning below, for every drop, forever.
        if (this.factories.containsKey(pool))
            return this.factories.get(pool);
        if (!this.soundLibrary.isSoundRegistered(pool)) {
            this.logger.warn("[DripHandler] sound pool %s is not registered; drips will be silent", pool);
            this.factories.put(pool, null);
            return null;
        }
        final ISoundFactory cached = SoundFactoryBuilder.create(pool)
                .category(SoundSource.AMBIENT)
                .volume(MIN_VOLUME, MAX_VOLUME)
                .pitch(MIN_PITCH, MAX_PITCH)
                .build();
        this.factories.put(pool, cached);
        return cached;
    }

    /** A pooled point, carrying its own cooldown, burst state and lifespan. */
    private static final class Spot {
        final DripScanner.DripPoint point;
        final long expireTick;
        /**
         * Where this point's drops form, decided once. Every drop of this point
         * hangs at exactly these coordinates - recomputing the offset per drop
         * is what made a burst look like a small cluster instead of one place
         * dripping repeatedly.
         */
        final double hangX;
        final double hangZ;
        long lastFire = -1000L;
        int burstLeft = 0;
        long nextBurstTick = -1000L;
        /** One of KIND_SINGLE / KIND_BURSTY / KIND_STEADY. */
        int kind = KIND_SINGLE;
        /** While the tick is below this the point is a thread; after it, occasional drops. */
        long steadyUntil = -1000L;
        int steadyInterval = 0;
        long nextSteadyTick = -1000L;

        Spot(DripScanner.DripPoint point, long expireTick, double hangX, double hangZ) {
            this.point = point;
            this.expireTick = expireTick;
            this.hangX = hangX;
            this.hangZ = hangZ;
        }
    }

    /** A drip already falling: the pool was chosen at fire time, the sound is due when the visible drop lands. */
    private record PendingLanding(DripScanner.DripPoint point, ResourceLocation pool, long dueTick, float volumeScale) {
    }
}
