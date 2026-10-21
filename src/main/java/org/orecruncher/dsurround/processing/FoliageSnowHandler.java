package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.client.particle.FoliageSnowParticle;
import org.orecruncher.dsurround.client.particle.ModParticles;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.seasons.SeasonManager;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;
import org.orecruncher.dsurround.processing.scanner.DistanceLod;
import org.orecruncher.dsurround.processing.scanner.FoliageScan;
import org.orecruncher.dsurround.processing.scanner.ScannerCadence;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;

/**
 * Snow settled on foliage - the dew layer's bookkeeping wearing white.
 *
 * <p>Same skeleton as {@link FoliageDewHandler} (persistent anchors, scanned on
 * a chunk change plus a slow top-up, each plant keeping a handful of units),
 * four things differ, and they are the four the calibration page and the review
 * pinned down:</p>
 *
 * <ol>
 * <li><b>Clock.</b> Dew runs on {@code dayTime % 24000}; snow runs on a
 *     PRECIPITATION EVENT clock (the icicle's: {@code precipStartTick} +
 *     {@code peakTicks}), because snow accumulates over a storm and then
 *     lingers - it has nothing to do with the hour.</li>
 * <li><b>Units, not beads.</b> Up to {@link #MAX_UNITS_PER_PLANT} per plant,
 *     growing dot &rarr; clump &rarr; sheet as the event deepens (the sheet
 *     being a wide THIN sliver, which is what covers area without hiding the
 *     plant the way a big square blob does - see {@link FoliageSnowParticle}).</li>
 * <li><b>New plants start FULL.</b> This is the fix for "the snow in a freshly
 *     reached chunk does not match the phase". Every other layer can afford to
 *     grow a plant in slowly because its life is minutes; snow accumulates over
 *     a game hour, so a plant discovered now would spend that hour visibly
 *     bare next to its neighbours. A plant that is NOT yet on the books gets
 *     its full {@code N(progress)} at once; only a plant already being watched
 *     grows in by {@link #ACCUM_PER_SCAN} a scan.</li>
 * <li><b>Melting is staggered shrinking.</b> Each unit gets its own
 *     {@code meltAt} (a stable hash over the whole melt window), then
 *     {@code beginEvaporate} - which SHRINKS, because the snow unit is on the
 *     OPAQUE sheet where a vertex alpha is ignored ({@code disableBlend()}).
 *     Rain over the top collapses everybody's {@code meltAt} to now.</li>
 * </ol>
 */
public class FoliageSnowHandler extends AbstractClientHandler {

    // ---- cadence (the dew layer's, unchanged) -------------------------------
    /** Round 10: was 100 (five seconds of nothing when you crossed into a new chunk). */
    private static final int CHUNK_COOLDOWN_TICKS = 30;
    /** Round 10: was 36 (six blocks). Three and a half now - a walk has to trigger a rescan sooner than that. */
    private static final double RESCAN_MOVE_SQ = 12.25D;
    // The move-trigger floor is shared; see ScannerCadence.MOVE_RESCAN_MIN_TICKS. This layer
    // needed it most: at three and a half blocks the walk test fires every couple of ticks on an
    // elytra, and its scan is the expensive one. The new-chunk test and the slow top-up keep
    // their own cooldowns, untouched.
    /**
     * Round 10: "快靠近才出现粒子". This is the slow top-up's number - with
     * it at 600 a plant you walked up to took 30 seconds per scan and two
     * units per scan to fill in, so the coat visibly GREW while you watched.
     * 10 seconds now.
     */
    private static final int TOPUP_TICKS = 200;
    private static final int STALE_CHECK_TICKS = 2;
    private static final int KEEP_BLOCK_CHECK = 5;
    /**
     * How far a plant may drift before its snow is let go (round 7: "I have to
     * be right on top of a plant before it gets any snow, but it keeps the
     * snow until I am miles away"). The scan only looks foliageScanRange
     * blocks out, so a 48 block keep read as snow popping in at your feet and
     * hanging on forever behind you. 28 is a bit under twice the scan radius:
     * far enough that nothing vanishes in front of you, close enough that the
     * field behind you drains.
     */
    private static final long KEEP_DIST_SQ = 28L * 28L;
    /** Only enough to win the depth test against the leaf (dew: 0.04 read as floating). */
    private static final double NORMAL_OFFSET = 0.012D;

    /**
     * Round 12: every unit used to sit at exactly the same distance off the leaf, so two
     * that overlapped were exactly coplanar and the depth test had nothing to choose
     * between them - the coat flickered wherever it closed up. Lift each unit by a
     * slightly different amount instead. The whole spread is 4 x 0.006 = 0.024 blocks,
     * far under a pixel to look at but several times the depth buffer's step out to the
     * ranges we scan, so overlapping pieces get a definite order instead of a tie.
     */
    private static final int DEPTH_STEPS = 5;
    private static final double DEPTH_STAGGER = 0.006D;
    private static final int DIAG_INTERVAL_TICKS = 100;

    // ---- the snow layer's own numbers ---------------------------------------
    /** Units per plant at a fully developed storm. Calibrated: ~59% white coverage on grass. */
    /** Round 11: was 16 - twenty steps instead of sixteen, so the coat reads as more stages. */
    private static final int MAX_UNITS_PER_PLANT = 20;
    /**
     * The growth curve's exponent - see {@link #targetCount(float, float)}.
     * Measured in the offline twin over 9 real sprites x 14 seeds.
     */
    /**
     * Round 11: was 0.55. With an exponent below 1 the count climbs steeply at
     * the very start - at p=0.05 it was already at 0.19 of the cap, which is
     * why "the snow arrived almost immediately". 0.80 flattens that opening.
     */
    private static final float GROWTH_BETA = 0.80F;
    /** Scatter anchors at which a plant counts as "big" - see {@link #boostOf}. */
    private static final float BOOST_FULL_ANCHORS = 24F;
    /** How much of the boost is left at this depth: all of it at 0, none past it. */
    private static final float BOOST_FADE_AT = 0.35F;
    private static final float BOOST_MAX = 1.2F;
    /** Extra clump share a big plant gets while the snow is shallow - see attach(). */
    private static final float EARLY_CLUMP_GAIN = 0.45F;
    /** Depth at which the shallow-snow clump bonus has faded back to CLUMP_SHARE. */
    private static final float EARLY_CLUMP_FADE = 0.60F;
    /** How many a plant ALREADY being watched gains per scan (the "settling" read). */
    /** Round 10: was 2 - filling a coat four units short took two or three scans, which at the old top-up rate was a minute. */
    /**
     * Round 11: was 4. Four units in one scan is the visible "jump" - the coat
     * gained a fifth of itself in a single frame. Two at a time now; the faster
     * top-up rate (TOPUP_TICKS 200) is what keeps it from feeling slow.
     */
    private static final int ACCUM_PER_SCAN = 2;
    /**
     * How many plants may carry snow at once. Was the rain-dew layer's 48 -
     * but a storm coats a whole meadow, and the scan finds far more candidates
     * inside its radius than 48 (the rest then read as "many plants have no
     * snow at all"). 96 keeps the unit budget sane (96 x 16 = 1536 static
     * quads) while covering the field the scan actually returns - the scan
     * range went up in round 7 and it returns still more.
     */
    private static final int MAX_PLANTS = 224;
    /**
     * Round 9: the roster is a fixed budget and a meadow hands us hundreds of
     * plants for it, so what it spends the budget ON is the whole question.
     * These shrink the coat a plant wears by how far away it is - a plant at
     * the edge of the scan does not need sixteen units to read as snowy, and
     * the units it gives up let two more plants near the player have any at
     * all. Round 10 eased both (0.7/0.45 -> 0.85/0.70): at 0.45 a plant ten
     * blocks out was visibly thin, so walking towards it meant watching snow
     * appear. The point is to spend the budget on RANGE, not to make the far
     * edge look half-melted.
     */
    private static final int LOD_NEAR_SQ = 10 * 10;
    private static final int LOD_FAR_SQ = 16 * 16;
    private static final float LOD_MID_SCALE = 0.85F;
    private static final float LOD_FAR_SCALE = 0.70F;
    /** Slide speed for the rare unit that lets go (dew's, unhurried). */
    private static final float SLIDE_SPEED = 0.010F;
    /**
     * Round 8: "下滑动画... 直接竖直下落就行了，还有下滑速度尽量再慢一些".
     * The melting unit's exit - see {@link #meltOff}. Straight down at this
     * pace (blocks per tick): a third of what the dew bead slides at, which
     * offline is 0.07 blocks a second, about one sprite pixel. The old exit
     * covered the same sort of distance at 0.2 blocks a second and he still
     * called it quick, so this is deliberately slower than it looks like it
     * needs to be.
     */
    private static final float SINK_SPEED = 0.0035F;
    /**
     * The lottery slip: the same straight-down sink, slower still - 0.045 blocks a second
     * against the melt's 0.07, so a slip reads as settling rather than leaving. It travels
     * the same capped distance (0.06..0.36), which is between 1.3s and 8s of fall.
     */
    private static final float SLIP_SPEED = 0.0022F;
    /** Longer than the melt's fade: a slip should dissolve on the way down, not stop and blink. */
    private static final int SLIP_FADE_TICKS = 110;
    /**
     * How far a melting unit may sink before it fades, in blocks - a CEILING
     * only. Round 44 raised it from 0.36 to the height of one block.
     *
     * <p>0.36 was the round-7 "slide a good distance first" number, and it had
     * quietly become the thing that decides HOW LOW a unit gets to go. Measured
     * over the same 17 sprites (1384 scatter anchors): a plant's scatter points
     * sit at y=0.34..0.63, so the cap bit for 65% of them and the sink ended at
     * y=0.215 on average - three and a half plant pixels above the base of the
     * blade, and 0.29 (4.6px) on allium. A unit now goes as far down as its own
     * room allows, which is SINK_FLOOR_CLEARANCE above the bottom of its block,
     * i.e. the base of the plant: that is "让粒子滑到底". The pace is
     * untouched (SINK_SPEED / SLIP_SPEED), so the exit takes longer in
     * proportion - offline, the melt 5.1s -> 7.1s and the slip 8.2s -> 11.3s
     * at the median anchor.</p>
     */
    private static final float SINK_DROP_MAX = 1.00F;
    /** ...and the least it will do, for a unit anchored near the floor. */
    private static final float SINK_DROP_MIN = 0.06F;
    /**
     * How far off the block's floor the sink has to stop. Same number as
     * FoliageProfile's GROUND_CLEARANCE and for the same reason: a unit that
     * ends up inside the block below takes its light from in there and
     * renders BLACK. Kept as its own constant because the profile's is
     * private and this is the handler's own budget.
     */
    private static final float SINK_FLOOR_CLEARANCE = 0.10F;
    /** The melt window each unit is scattered across (2 minutes). Round
     *  7: the coat takes minutes to grow now, so it takes minutes to go. */
    private static final int MELT_STAGGER_TICKS = 2400;
    /**
     * How long one unit takes to shrink away once its turn comes (40s). Still
     * the melt window's tail - {@link #meltProgress} adds it to the stagger to
     * get the span the depth falls across. The unit itself no longer shrinks
     * (round 7); it slides and fades over {@link #MELT_FADE_TICKS} instead.
     */
    private static final int MELT_SHRINK_TICKS = 800;
    /** How long that fade lasts, in ticks - 3 seconds. */
    private static final int MELT_FADE_TICKS = 60;
    /** Rain on top of snow collapses the window to this (5 minutes). */
    private static final int RAIN_MELT_TICKS = 300;
    /** The let-go lottery: every 200t, mostly nothing. */
    private static final int LOTTERY_INTERVAL = 200;
    private static final float ROLL_SLIP = 0.02F;
    /** The icicle's event clock: how long a storm takes to reach full snow. */
    private static final float EVENT_PEAK_AT_BASE = 6000F;
    private static final float EVENT_PEAK_MIN = 4800F;
    private static final float EVENT_PEAK_MAX = 24000F;
    /**
     * Nothing settles on a blade for the first half minute of a storm (round
     * 7: "it starts snowing and the plants are white straight away, I never
     * see it grow"). Half a minute of bare plants while the ground gets wet,
     * then the climb - and EVENT_PEAK_AT_BASE was 1500, which over a hard
     * storm (peak base 1.0) filled a coat in 75 seconds, the "no growth at
     * all" read. 6000 makes it minutes: ~5 at full storm, ~10 at half, ~20 at
     * the weakest (and a forced layer does not wait, it is pinned to 1.0).
     */
    private static final int ACCUM_LAG_TICKS = 600;

    /** Diagnostics only: ignore the snow gates so the layer can be exercised any time. */
    public static volatile boolean DEBUG_FORCE = false;
    /** Units-per-plant ceiling override from /dsdebug foliage snow; 0 = the calibrated default. */
    public static volatile int DEBUG_UNITS = 0;
    public static volatile FoliageSnowHandler INSTANCE = null;
    public static int attached = 0;
    public static int spawnNull = 0;
    public static int spawnWrong = 0;
    /** Rejection tally for the last scan. */
    public static int rejWarm = 0;
    public static int rejPhase = 0;
    public static int rejNoAttach = 0;
    /** Turned away because the roster was full and nothing farther out was worth swapping. */
    public static int rejCap = 0;

    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private final Map<Long, PlantSnow> plants = new HashMap<>();

    private Level lastWorld;
    private long lastScanTick = -1000L;
    private long lastStaleCheck = -1000L;
    private int blockCheckPhase = 0;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    /**
     * Round 12: telling a plant the snow fell on from one the player just put there.
     *
     * <p>While it is melting the layer still looks around, so a plant can be new to us for two
     * quite different reasons: we walked into ground we had never swept (it snowed there too, so
     * it should be dressed), or the player placed it after the snow stopped (the snow never fell
     * on it). Both read as "never seen before". Hence two records - chunks this snowfall has
     * swept, and plants this snowfall has actually seen snow on. A plant sitting in a swept chunk
     * that is missing from the second set arrived after the snow did.</p>
     */
    private final java.util.Set<Long> scannedChunks = new java.util.HashSet<>();
    private final java.util.Set<Long> seenPlants = new java.util.HashSet<>();
    /** Bounded: a long walk in heavy snow could otherwise sit on thousands of entries. */
    private static final int SEEN_CAP = 8192;

    private static long chunkKey(final BlockPos pos) {
        return (((long) (pos.getX() >> 4)) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
    }

    private BlockPos lastScanPos = null;
    private int failures = 0;
    private long lastDiagTick = -1000L;

    // ---- event clock (icicle's shape) --------------------------------------
    /** Tick the snow started; 0 = not snowing (and not melting back yet). */
    private long precipStartTick = 0L;
    /** Peak intensity seen this event - drives how fast it deepens. */
    private float base = 0F;
    private boolean wasSnowing = false;
    private boolean meltScheduled = false;
    /** When the melt window opened - the depth channel's fall runs from here. */
    private long meltStartTick = 0L;

    public FoliageSnowHandler(final Configuration config, final IModLog logger, final ITickCount tickCount) {
        super("FoliageSnow", config, logger);
        this.tickCount = tickCount;
        ClientState.TICK_END.register(this::onTick);
        ClientState.ON_DISCONNECT.register(client -> this.clear());
        INSTANCE = this;
    }

    // ---- diagnostics surface ------------------------------------------------

    public int plantCount() {
        return this.plants.size();
    }

    public int unitCount() {
        int n = 0;
        for (final PlantSnow p : this.plants.values())
            n += p.units.size();
        return n;
    }

    public Configuration config() {
        return this.config;
    }

    public void forceRescan() {
        this.lastScanTick = -1000L;
        this.lastScanPos = null;
        this.lastChunkX = Integer.MIN_VALUE;
        this.lastChunkZ = Integer.MIN_VALUE;
        this.lastDiagTick = -1000L;
    }

    private void onTick(final Minecraft client) {
        try {
            this.tick0();
        } catch (final Throwable t) {
            final int count = ++this.failures;
            if (count == 1 || count % 200 == 0)
                this.logger.error(t, "[FoliageSnow] threw (%d consecutive); the layer sat this tick out", count);
        }
    }

    private void tick0() {
        if (!this.config.weatherOptions.enableSnowCover) {
            if (!this.plants.isEmpty())
                this.clear();
            return;
        }
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;

        final Level world = player.level();
        if (world != this.lastWorld) {
            this.lastWorld = world;
            this.clear();
        }

        final long tick = this.tickCount.getTickCount();
        final BlockPos pos = player.blockPosition();

        // ---- the event clock ----
        // Snow is a per-COLUMN question (icicle lesson: the column's own phase,
        // never the player's), and so is "is it snowing here at all".
        // The shader switch (see FoliageScan.plantLayersAllowed) reads as "not snowing here", so
        // the layer winds down through its own melt path - scheduleMelt below - instead of having
        // its units cut off mid-air.
        final boolean plantLayersOff = !DEBUG_FORCE && !FoliageScan.plantLayersAllowed(this.config);
        if (plantLayersOff)
            FoliageScan.reportShaderSuppression(this.logger);
        final boolean snowingHere = !plantLayersOff
                && (DEBUG_FORCE
                    || (world.isRaining() && PrecipitationIntensity.potentialPhase(pos) == Biome.Precipitation.SNOW));
        if (snowingHere && !this.wasSnowing) {
            // A new snowfall: what the last one saw is no longer evidence.
            this.scannedChunks.clear();
            this.seenPlants.clear();
            this.precipStartTick = tick;
            this.meltScheduled = false;
            this.base = Math.max(0.05F, PrecipitationIntensity.peak());
            this.lastScanTick = -1000L;         // fresh snow: look around now
        }
        if (!snowingHere && this.wasSnowing)
            this.scheduleMelt(tick, world);
        this.wasSnowing = snowingHere;

        // ---- the depth channel ----
        // The handler owns the accumulation clock (its pace varies with how
        // hard the storm is: EVENT_PEAK / base); the channel is its readable
        // face - the ONE number everything else can ask "how deep is the snow
        // right now". /dsprecip lock foliageSnow x pins it, and the pin
        // outranks the clock inside easeTo, so a locked depth holds still.
        final var depthChannel = PrecipitationResponse.FOLIAGE_SNOW;
        depthChannel.advance(snowingHere ? this.accumProgress(tick) : this.meltProgress(tick),
                1.0F / 20.0F);

        // What a plant should hold right now. Forced mode wants the FULL coat
        // - that is the point of forcing, seeing the layer at all - and the
        // old shape forced progress to climb from zero on the 600..12000t
        // clock, which left every forced plant at two dots for ten minutes.
        // A lock beats the force fallback: pin 0.4 and force shows 0.4.
        final float progress;
        if (depthChannel.isLocked())
            progress = depthChannel.value();
        else if (snowingHere)
            progress = DEBUG_FORCE ? 1.0F : depthChannel.value();
        else
            progress = 1.0F;

        if (tick - this.lastDiagTick >= DIAG_INTERVAL_TICKS) {
            this.lastDiagTick = tick;
            // Round 12: this line used to sit behind traceEnabled, so a layer that had quietly
            // stopped producing printed nothing at all - which is exactly the state worth reading
            // about. It also used to answer only "how much", never "why not", so every term of
            // the gate goes in: the phase the column is shedding, whether the shader switch has
            // the plant layers down, how much of the plant budget is spoken for, and how stale
            // the last look around is. None of it changes behaviour.
            // No FoliageScan.statsLine() here: the scan histogram was only added to the
            // 1.20.1 scanner. Capping the plants prints next to the budget on purpose -
            // "plants=224/224" is the whole answer to "why do only the flowers have snow".
            this.logger.debug("[FoliageSnow] gate snowing=%s raining=%s potential=%s plantLayersOff=%s force=%s"
                            + " | progress=%.2f base=%.2f plants=%d/%d units=%d sinceScan=%d",
                    snowingHere, world.isRaining(), PrecipitationIntensity.potentialPhase(pos),
                    plantLayersOff, DEBUG_FORCE, progress, this.base, this.plants.size(), MAX_PLANTS,
                    this.unitCount(), tick - this.lastScanTick);
            // Round 48: the vertical-window instrument, and - the other half of the same
            // question - where the units we are STILL holding actually are. Both lines are
            // skipped entirely while the diag switch is off, so a normal run's log is
            // exactly what it was.
            if (FoliageScan.WINDOW_DIAG) {
                this.logger.debug("[FoliageSnow] window " + FoliageScan.windowLine());
                this.logger.debug("[FoliageSnow] pool " + this.poolLine(pos));
            }
        }

        // Rain over snow: the window collapses for everyone still standing.
        if (!snowingHere && world.isRaining() && !this.plants.isEmpty())
            this.rainCollapse(tick);

        // ---- scans: new chunk, a walk, or the slow top-up ----
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        final boolean newChunk = chunkX != this.lastChunkX || chunkZ != this.lastChunkZ;
        final boolean moved = this.lastScanPos == null
                || this.lastScanPos.distToCenterSqr(pos.getX(), pos.getY(), pos.getZ()) >= RESCAN_MOVE_SQ;
        // Moved only brings the next scan forward, and only past the floor. The first scan after a
        // fresh snowfall still happens at once: that path resets lastScanTick to the far past.
        final boolean movedNow = moved
                && tick - this.lastScanTick >= ScannerCadence.MOVE_RESCAN_MIN_TICKS;
        // Round 12: the scan used to stop dead the moment the snow did, so a player who walked
        // somewhere new during the melt found bare plants there while the ones back at the old
        // camp were still shedding their coat. The melt is world-wide - keep looking while there
        // is still snow to show. A plant found now is dressed at the CURRENT depth, because
        // targetCount reads the same progress the plants already on the roster were dressed at,
        // so it joins the melt at exactly the stage its neighbours are at.
        // Round 12, corrected: the depth is the CHANNEL value, not the local progress above -
        // that one is pinned to 1.0 whenever it is not snowing (it means something else), so
        // gating on it kept the scan running through the whole melt and re-dressed every
        // plant it found at full depth. The snow never left.
        if ((snowingHere || depthChannel.value() > 0F) && (newChunk && tick - this.lastScanTick >= CHUNK_COOLDOWN_TICKS
                || movedNow
                || tick - this.lastScanTick >= TOPUP_TICKS)) {
            this.lastChunkX = chunkX;
            this.lastChunkZ = chunkZ;
            this.lastScanTick = tick;
            this.lastScanPos = pos.immutable();
            if (this.scannedChunks.size() < SEEN_CAP)
                this.scannedChunks.add(chunkKey(pos));
            // Melting: hand the scan the real depth, so a plant found now wears what its
            // neighbours wear instead of a fresh full coat.
            // Round 48: the scan AND the attach behind it, clocked as one - the attach is
            // what scales with how many spots the window hands back, so windowing the sweep
            // without this number would only measure half of what the window costs.
            final boolean windowDiag = FoliageScan.WINDOW_DIAG;
            final long scanStart = windowDiag ? System.nanoTime() : 0L;
            this.scanAndAttach(world, pos, tick, snowingHere ? progress : depthChannel.value(), player,
                    !snowingHere);
            if (windowDiag) {
                final long d = System.nanoTime() - scanStart;
                this.scanNanos += d;
                this.scanPasses++;
                if (d > this.scanMaxNanos)
                    this.scanMaxNanos = d;
            }
        }

        // Round 48: the pool's own two per-tick passes, clocked while the diag switch is on
        // and untouched otherwise - one volatile read per pass is the entire cost of leaving
        // the instrument in the shipped build.
        final boolean windowDiag = FoliageScan.WINDOW_DIAG;
        if (tick - this.lastStaleCheck >= STALE_CHECK_TICKS) {
            this.lastStaleCheck = tick;
            final long staleStart = windowDiag ? System.nanoTime() : 0L;
            this.checkStale(world, pos);
            if (windowDiag) {
                final long d = System.nanoTime() - staleStart;
                this.staleNanos += d;
                this.stalePasses++;
                if (d > this.staleMaxNanos)
                    this.staleMaxNanos = d;
            }
        }
        final long lotteryStart = windowDiag ? System.nanoTime() : 0L;
        this.rollLottery(world, tick);
        if (windowDiag) {
            final long d = System.nanoTime() - lotteryStart;
            this.lotteryNanos += d;
            this.lotteryPasses++;
            if (d > this.lotteryMaxNanos)
                this.lotteryMaxNanos = d;
        }
    }

    /** Accumulation, 0..1 - the icicle's shape, on this layer's clock. */
    private float accumProgress(final long tick) {
        final float peak = net.minecraft.util.Mth.clamp(EVENT_PEAK_AT_BASE / Math.max(0.05F, this.base),
                EVENT_PEAK_MIN, EVENT_PEAK_MAX);
        final float since = tick - this.precipStartTick - ACCUM_LAG_TICKS;
        return net.minecraft.util.Mth.clamp(since / peak, 0F, 1F);
    }

    /** The melt side of the depth: 1 falling to 0 across the staggered window. */
    private float meltProgress(final long tick) {
        if (!this.meltScheduled)
            return 0F;
        final float span = MELT_STAGGER_TICKS + MELT_SHRINK_TICKS;
        return net.minecraft.util.Mth.clamp(1F - (tick - this.meltStartTick) / span, 0F, 1F);
    }

    /**
     * Units a plant should carry at this depth: the coat scales with the depth
     * AND with the config's foliage density - the same multiplier the rain-dew
     * layers take, so "0 = off" holds here too. The /dsdebug override replaces
     * the calibrated ceiling, not the shape.
     */
    private int targetCount(final float progress, final float boost) {
        final float density = (float) this.config.weatherOptions.foliageDensity;
        final int cap = DEBUG_UNITS > 0 ? DEBUG_UNITS : MAX_UNITS_PER_PLANT;
        if (progress <= 0F)
            return 0;
        final float early = net.minecraft.util.Mth.clamp(1F - progress / BOOST_FADE_AT, 0F, 1F);
        final float n = cap * (float) Math.pow(progress, GROWTH_BETA) * (1F + boost * early);
        return Math.max(0, Math.round(n * density));
    }

    /**
     * How big a plant is, as a 0..1.2 multiplier on its coat: the more blade
     * there is for snow to land on, the more of it lands. 24 scatter anchors
     * counts as "full" - a grass blade bakes ~48 and gets the lot, a poppy
     * bakes ~14 and gets nothing. That gap is the round 7 report in one
     * number: same depth, same two units, flower visible and grass invisible.
     */
    private static float boostOf(final FoliageProfile profile) {
        int total = 0;
        for (final FoliageProfile.Plane plane : profile.planes())
            total += plane.scatter().length;
        return net.minecraft.util.Mth.clamp(total / BOOST_FULL_ANCHORS - 1F, 0F, BOOST_MAX);
    }

    /**
     * The narrowest {@code Anchor.room} each shape TIER can sit on. A tier is a
     * pair of kinds (dot_a/b, clump_a/b, sheet_a/b) that differ only in texture.
     * A sheet is ~7 plant pixels wide and wants a solid 7x7 window under it
     * (room 3), a clump is ~3 px (room 2), a dot rides anywhere.
     */
    private static final int[] TIER_MIN_ROOM = { 0, 2, 3 };

    /**
     * Full-coat shape mix. Round 3 used 0.625 here: ten sheets on one
     * plant, all of them fighting over the same few wide anchors down at
     * the base of the sprite. Seven sprites x 16 seeds say 0.375 spreads
     * the same 16 units over all three height bands instead.
     */
    /**
     * Round 11 raised the slab share to 0.44 to close the coat sooner, and the middle size
     * duly vanished from the picture. Round 12 puts it back to 0.375 and doubles the clump
     * share: one sheet covers about twenty times what a clump does (53 plant pixels against
     * 2.6), so the slab share is what made the middle read as scarce - not the clump count,
     * which was already 42% of the units. Because a clump also grew (0.26 -> 0.32, see
     * FoliageSnowParticle.SIZES) the cover does not fall, it rises: grass holds 68.3%,
     * fern 82.5%, and the flowers gain 8 to 11 points where a slab never fit anyway.
     */
    private static final float SHEET_SHARE = 0.375F;
    private static final float CLUMP_SHARE = 0.25F;

    /**
     * Height up the quad above which an anchor counts as a TIP. A cross
     * model quad spans the whole block, so an anchor's own Y is how far up
     * the plant it sits: 0.5625 is sprite row 6.5 of 16, the upper 40%.
     * Tips score room 0 by definition - nothing under them is solid - so
     * the tier rule can only ever offer them a DOT, and a dot is 0.6 of a
     * plant pixel across. That is why grass read as "no snow up top"
     * (2.5% coverage there). Above this line the tier steps UP a notch so
     * a tip wears a cap instead (33% now).
     */
    private static final float TIP_LINE_Y = 0.5625F;

    // ---- melting ------------------------------------------------------------

    private void scheduleMelt(final long tick, final Level world) {
        if (this.meltScheduled)
            return;
        this.meltScheduled = true;
        this.meltStartTick = tick;
        for (final PlantSnow plant : this.plants.values())
            for (final SnowUnit unit : plant.units)
                // Stable per unit, so a meadow does not melt on one tick - and
                // the same hash every run, so it does not shimmer.
                unit.meltAt = tick + stableHash(unit.pos, unit.index) % MELT_STAGGER_TICKS;
    }

    private void rainCollapse(final long tick) {
        for (final PlantSnow plant : this.plants.values())
            for (final SnowUnit unit : plant.units)
                if (unit.meltAt > tick + RAIN_MELT_TICKS)
                    unit.meltAt = tick + RAIN_MELT_TICKS;
    }

    private static long stableHash(final BlockPos pos, final int index) {
        long h = pos.asLong() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h = h * 0xC2B2AE3D27D4EB4FL + index * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        return Math.abs(h);
    }


    /**
     * A unit's exit when the coat thins - round 7 gave it a slide instead of
     * a shrink ("消失动画有点难绷，直接缩小不太好，还不如下滑一大段距离以后直接
     * 慢慢隐去消失"), and round 8 took the corners out of that slide:
     * "下滑动画就尽量不要转弯了，直接竖直下落就行了". It used to ride the
     * plane's baked path, and that path follows the leaf's OUTLINE - measured
     * offline over the real sprites, 80 degrees of turn on average and up to
     * half a unit of sideways drift per unit of fall. Straight down now: the
     * unit settles through still air, there is nothing down there for it to
     * follow. Same physics and the same fade at the end, only the route.
     *
     * <p>How far it can go is capped by the block's floor - a unit that ends
     * up inside the block below takes its light from in there and renders
     * BLACK, the same trap the path baker clears with its ground clearance.
     * A unit anchored near the floor therefore sinks less; one that cannot
     * sink at all just fades where it stands.</p>
     */
    private void meltOff(final SnowUnit unit, final int fadeTicks) {
        final float room = unit.anchor().y() - SINK_FLOOR_CLEARANCE;
        final float drop = Math.max(SINK_DROP_MIN, Math.min(SINK_DROP_MAX, room));
        if (!unit.particle().beginSink(drop, SINK_SPEED, fadeTicks))
            unit.particle().beginFade(fadeTicks);
    }

    // ---- scanning and attaching --------------------------------------------

    private void scanAndAttach(final Level world, final BlockPos center, final long tick,
                               final float progress, final Player player, final boolean melting) {
        rejWarm = 0;
        rejPhase = 0;
        rejNoAttach = 0;
        rejCap = 0;
        // The target is per PLANT: a blade catches more snow than a flower
        // head does, so the depth alone cannot size the coat. baseWant is only
        // the "is there any snow at all yet" gate and the number the log prints.
        final int baseWant = this.targetCount(progress, 0F);
        if (baseWant <= 0)
            return;
        // ★ Round 9, and this is the whole "草又没雪了" bug. FoliageScan walks
        // the box column by column from -range, so the FIRST spots it hands
        // back are the far north-west corner - sixteen blocks away. The roster
        // (MAX_PLANTS) is a couple of hundred at most and a meadow hands us
        // hundreds of plants, so the roster filled up with that corner and
        // everything the player was actually standing in was turned away at
        // the capacity gate. Which plants got in depended on which way you
        // happened to be facing a field - that is the "稳定性有点弱", and it is
        // also why it looked like a grass problem: grass grows in sheets, so
        // the whole sheet went to whichever corner was scanned first, while a
        // flower growing on the near side of the same field got in fine.
        // Sort by distance and take the nearest first. ObjectArray has no
        // stream(), so collect it by hand.
        final List<FoliageScan.FoliageSpot> spots = new ArrayList<>(64);
        // Round 12: sweep towards what the player is looking at. player is null only in a
        // couple of tests; a zero facing means "plain box" and costs nothing.
        final double yaw = player == null ? 0D : player.getYRot() * (Math.PI / 180D);
        final float facingX = player == null ? 0F : (float) -Math.sin(yaw);
        final float facingZ = player == null ? 0F : (float) Math.cos(yaw);
        for (final FoliageScan.FoliageSpot spot : FoliageScan.scan(world, center, FoliageScan.Mode.SNOW,
                this.config.weatherOptions.foliageScanRange, facingX, facingZ))
            spots.add(spot);
        spots.sort(Comparator.comparingDouble(s -> s.pos().distSqr(center)));
        for (final FoliageScan.FoliageSpot spot : spots) {
            final float boost = boostOf(spot.profile());
            final double dist = spot.pos().distSqr(center);
            final Long key = spot.pos().asLong();
            // Round 12: melting, in a chunk we have already swept, and this snowfall
            // never saw it - it was placed after the snow stopped. A plant in a chunk we
            // have never swept is just new ground and is dressed like its neighbours.
            if (melting && this.scannedChunks.contains(chunkKey(spot.pos()))
                    && !this.seenPlants.contains(key))
                continue;
            if (this.seenPlants.size() < SEEN_CAP)
                this.seenPlants.add(key);
            // Mark the chunk the PLANT is in, not just the one the player is in. The sweep
            // is a box around the player, so half of what it returns lies in a neighbouring
            // chunk - and only the player's own chunk used to be marked as swept. The test
            // above asks about the plant's chunk, so a plant placed two blocks past a chunk
            // border was never protected: it was re-dressed during the melt as if it had
            // stood in the snow all along. Sugar cane made this visible because it is planted
            // at the water's edge, usually from the far side of a border.
            if (this.scannedChunks.size() < SEEN_CAP)
                this.scannedChunks.add(chunkKey(spot.pos()));
            final PlantSnow existing = this.plants.get(key);
            // A plant at the far edge wears a thinner coat, so the budget reaches further out
            // than it could otherwise. The tier is remembered per plant and only moves when the
            // distance clears a threshold by a margin (see DistanceLod): recomputing it fresh
            // every scan made the coat grow while the player walked up to it, which is the
            // "走近了雪才长出来" read the round-10 scales had already been raised to hide.
            final int lodTier = DistanceLod.nextTier(existing == null ? DistanceLod.TIER_NEAR : existing.lodTier, dist);
            final float lod = DistanceLod.scaleFor(lodTier);
            final int want = Math.max(1, Math.round(this.targetCount(progress, boost) * lod));
            if (existing != null) {
                // Already watched: grow in slowly, that is the "settling" read.
                // attach() takes the plant's TARGET here - the dew layer's
                // contract (its loop stops at beads total, not at beads more).
                // Handing it the increment instead made the loop condition
                // units.size() < count false on every scan after the first,
                // so a plant stopped at whatever it held when it enrolled.
                existing.lodTier = lodTier;
                if (existing.units.size() < want)
                    this.attach(world, player, spot, existing, want, tick, progress, boost, existing.units.size());
                // Depth fell below what the plant holds: the TOP snow goes
                // first (units are appended in schedule order, so the tail is
                // the newest), a quick shrink rather than the melt's slow one.
                while (existing.units.size() > want) {
                    final SnowUnit extra = existing.units.remove(existing.units.size() - 1);
                    // Same exit as the melt (round 7): slide, then fade.
                    this.meltOff(extra, 40);
                }
                continue;
            }
            // Frost only - and this is exactly the gate that keeps the snow
            // layer off the dew layer's plants (dew skips cold biomes too).
            // DEBUG_FORCE skips this gate too, the same way it skips the phase
            // gate: with it, the layer could only ever be seen in a biome that
            // was cold AND snowing, which is a rare enough combination to make
            // the whole thing untestable from the overworld's temperate half.
            if (!DEBUG_FORCE && !SeasonManager.HANDLER.isSnowTemperature(spot.pos())) {
                rejWarm++;
                continue;
            }
            // The column's own phase: a warm pocket inside a cold biome.
            if (!DEBUG_FORCE && PrecipitationIntensity.potentialPhase(spot.pos()) != Biome.Precipitation.SNOW) {
                rejPhase++;
                continue;
            }
            // ★ A plant we had not been watching gets its FULL count at once.
            // In the world's own terms that blade has been standing in this
            // storm as long as every other one - it is only new to US, and
            // growing it in from zero would read as a bare patch in the snow.
            // Capacity, the same ceiling the rain-dew layer uses. The map is not
            // culled by distance inside a scan, so without this a dense field
            // would keep enrolling plants until the map outgrew the scan budget.
            if (this.plants.size() >= MAX_PLANTS) {
                // Full. Only worth a swap if this one is clearly nearer than
                // the farthest plant already on the roster - a quarter nearer,
                // so walking about does not churn the same two plants in and
                // out on every scan. The one that goes is the farthest, and it
                // fades rather than falling: falling is what snow does when
                // the PLANT is gone, and this one is simply out of range.
                final PlantSnow far = this.farthest(center);
                if (far == null || dist * 4.0 >= far.pos.distSqr(center) * 3.0) {
                    rejCap++;
                    continue;
                }
                for (final SnowUnit u : far.units)
                    u.particle().beginFade(30);
                this.plants.remove(far.pos.asLong());
            }
            final PlantSnow plant = new PlantSnow(spot.pos(), spot.state().getBlock());
            plant.lodTier = lodTier;
            // (Assigned before attach(): attach reads the tier for nothing today,
            // but a plant created here is already at its tier - leaving it at the
            // default until the next scan would give every new plant one scan at
            // full coat, i.e. exactly the pop this memory exists to prevent.)
            if (!this.attach(world, player, spot, plant, want, tick, progress, boost, 0)) {
                rejNoAttach++;
                for (final SnowUnit u : plant.units)
                    u.particle().remove();
                continue;
            }
            plant.meltAtBase = tick;
            this.plants.put(key, plant);
        }
        this.logger.info("[FoliageSnow] scan@(%d,%d,%d) progress=%.2f want=%d: warm=%d phase=%d noAnchor=%d cap=%d -> plants=%d units=%d",
                center.getX(), center.getY(), center.getZ(), progress, baseWant,
                rejWarm, rejPhase, rejNoAttach, rejCap, this.plants.size(), this.unitCount());
    }

    /**
     * Tops the plant up to {@code count} units IN TOTAL (the dew layer's
     * contract), adding at most a couple per call to an already watched
     * plant; false if nothing landed.
     */
    private boolean attach(final Level world, final Player player, final FoliageScan.FoliageSpot spot,
                           final PlantSnow plant, final int count, final long tick,
                           final float progress, final float boost, final int startIndex) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return false;
        final Vec3 offset = spot.state().getOffset(world, spot.pos());
        // This version's Plane record keeps the quads in a List, not an array.
        final List<FoliageProfile.Plane> planes = spot.profile().planes();
        boolean any = false;
        int index = startIndex;
        final int have = plant.units.size();
        final int need = count - have;
        if (need <= 0)
            return false;
        // A plant already being watched gains a couple at a time - the
        // "settling" read. A NEW one gets its full count at once: in the
        // world's own terms it has been standing in this storm as long as
        // every other plant, and growing it from zero reads as a bare patch.
        // A plant already being watched gains a couple at a time - the
        // "settling" read. A brand new one used to get its whole coat in one
        // go: right in the world's terms (that blade has stood in this storm
        // as long as any other) but it read as "the snow was always there, it
        // never grows" - every plant you walked past was finished on its
        // first scan. Half of it now, the rest settles in.
        // Round 10: a brand new plant used to start at HALF its coat. Right in
        // the world's terms, but combined with the slow top-up it is what made
        // snow look like it was appearing as you approached - the plant was
        // already enrolled and thin, and it filled in while you watched.
        // Three quarters now, and ACCUM_PER_SCAN finishes the rest quickly.
        final int budget = have > 0 ? Math.min(ACCUM_PER_SCAN, need) : Math.max(1, (need * 3 + 3) / 4);

        // HOW A COAT IS LAID OUT (round 4). Everything below was measured in
        // the offline twin of this routine over 7 real sprites x 16 seeds.
        //
        // (1) The SHAPE follows the anchor, not the unit's index. kindFor()
        //     handed shapes out by index, so a slab and a speck could land
        //     side by side: 23% of neighbouring covered pixels were different
        //     tiers and the largest bare hole was 24 plant pixels. room()
        //     decides instead - room 3 carries a sheet, room 2 a clump, the
        //     rest dots.
        // (2) A small shape only takes a NARROW perch. Otherwise the first
        //     units eat every wide spot and the sheets have nowhere left.
        // (3) Biggest tier first, and EVERY unit goes to whichever face is
        //     thinnest at that moment. Round-robinning the faces only equalised
        //     the COUNT, never where they landed: allium measured 28.5% of
        //     coverage difference between its two faces, now 3.8%.
        // (4) Draw the anchor at random. Baking already holds every anchor
        //     >=2 plant pixels apart (SCATTER_MIN_DIST_PX), so "the farthest
        //     one" (round 3's best-candidate) did nothing except make every
        //     plant converge on the same handful - grass measured 0.44 Jaccard
        //     between two plants, now 0.13.
        int sheets = Math.round(count
                * net.minecraft.util.Mth.clamp((progress - 0.40F) / 0.60F, 0F, 1F) * SHEET_SHARE);
        // A dot is 0.6 of a plant pixel across. On a grass blade in shallow
        // snow that is invisible - and shallow snow is where a blade spends
        // most of a storm, which is the whole of the round 7 "grass has no
        // snow" report. A big plant therefore takes a bigger share of clumps
        // while the depth is low; by EARLY_CLUMP_FADE the share is back to the
        // calibrated CLUMP_SHARE and the full coat is what it always was.
        final float clumpShare = CLUMP_SHARE + EARLY_CLUMP_GAIN * boost
                * net.minecraft.util.Mth.clamp(1F - progress / EARLY_CLUMP_FADE, 0F, 1F);
        int clumps = Math.round(count * clumpShare);
        // Both faces must receive the same number of every shape, and neither
        // face can supply more of one than it has anchors for. Cap to twice
        // what the POORER face can offer and symmetry comes for free.
        int availRoom3 = 0;
        int availRoom2 = 0;
        boolean firstProbe = true;
        for (final FoliageProfile.Plane probe : planes) {
            final int r3 = this.freeAnchors(probe, plant, TIER_MIN_ROOM[2], false).size();
            final int r2 = this.freeAnchors(probe, plant, TIER_MIN_ROOM[1], false).size();
            availRoom3 = firstProbe ? r3 : Math.min(availRoom3, r3);
            availRoom2 = firstProbe ? r2 : Math.min(availRoom2, r2);
            firstProbe = false;
        }
        sheets = Math.min(sheets, availRoom3 * 2);
        clumps = Math.max(0, Math.min(clumps, availRoom2 * 2 - sheets));
        final int[] left = { Math.max(0, count - sheets - clumps), clumps, sheets };
        // What the plant already holds comes off the quota, so an increment
        // lands in whatever shape the COAT is still missing instead of
        // replaying the whole mix from scratch (that is how a plant that
        // fills in two at a time still ends up 6 sheets + 2 clumps + 8 dots).
        for (final SnowUnit unit : plant.units)
            left[unit.tier] = Math.max(0, left[unit.tier] - 1);
        int added = 0;
        int guard = 0;
        final int maxGuard = count * 4 + planes.size() + 8;
        while (added < budget && plant.units.size() < count && guard < maxGuard) {
            guard++;
            // The largest tier still holding slots goes first, so wide spots
            // are claimed by big shapes while they are still available.
            int tier = -1;
            for (int t = TIER_MIN_ROOM.length - 1; t >= 0; t--) {
                if (left[t] > 0) {
                    tier = t;
                    break;
                }
            }
            if (tier < 0)
                break;
            // (3) whichever face is thinnest right now gets the unit; ties are
            // broken at random so the two do not take turns in a fixed order.
            int fewest = Integer.MAX_VALUE;
            for (final FoliageProfile.Plane probe : planes)
                fewest = Math.min(fewest, this.countOn(plant, probe));
            int ties = 0;
            for (final FoliageProfile.Plane probe : planes)
                if (this.countOn(plant, probe) == fewest)
                    ties++;
            int pickTie = ties > 0 ? this.random.nextInt(ties) : 0;
            int first = 0;
            for (int i = 0; i < planes.size(); i++) {
                if (this.countOn(plant, planes.get(i)) == fewest) {
                    if (pickTie == 0) {
                        first = i;
                        break;
                    }
                    pickTie--;
                }
            }
            boolean placed = false;
            for (int o = 0; o < planes.size() && !placed; o++) {
                final FoliageProfile.Plane plane = planes.get((first + o) % planes.size());
                if (plane.scatter().length == 0)
                    continue;
                FoliageProfile.Anchor anchor = null;
                int useTier = -1;
                for (int t = tier; t >= 0; t--) {
                    // Step DOWN until there is a perch that can honestly carry
                    // this shape - a sheet on a leaf tip hangs most of itself in
                    // the air. The kind follows whichever tier actually took it.
                    final boolean narrowOnly = t == 0;
                    List<FoliageProfile.Anchor> free = this.freeAnchors(plane, plant, TIER_MIN_ROOM[t], narrowOnly);
                    // Once every narrow perch is gone a small shape may sit
                    // anywhere rather than go unused - the plant still fills up.
                    if (free.isEmpty() && narrowOnly)
                        free = this.freeAnchors(plane, plant, 0, false);
                    if (free.isEmpty())
                        continue;
                    // (4) a plain draw from the free list - see the note above.
                    anchor = free.get(this.random.nextInt(free.size()));
                    useTier = t;
                    if (useTier == 0 && anchor.y() > TIP_LINE_Y)
                        useTier = 1;      // a blade tip wears a cap, see TIP_LINE_Y
                    break;
                }
                if (anchor == null)
                    continue;

                final int kind = useTier * 2 + (index & 1);
                final double ax = spot.pos().getX() + offset.x + anchor.x();
                final double ay = spot.pos().getY() + offset.y + anchor.y();
                final double az = spot.pos().getZ() + offset.z + anchor.z();

                float nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
                final double tx = player.getX() - ax, ty = player.getY() - ay, tz = player.getZ() - az;
                if (nx * tx + ny * ty + nz * tz < 0) {
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                }
                float rx = 0F, ry = 1F, rz = 0F;
                if (Math.abs(nx * rx + ny * ry + nz * rz) > 0.99F) {
                    rx = 0F;
                    ry = 0F;
                    rz = 1F;      // horizontal plane: no natural up (review A)
                }
                float vx = rx - nx * (nx * rx + ny * ry + nz * rz);
                float vy = ry - ny * (nx * rx + ny * ry + nz * rz);
                float vz = rz - nz * (nx * rx + ny * ry + nz * rz);
                final float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (vl < 1.0e-6F)
                    continue;
                vx /= vl;
                vy /= vl;
                vz /= vl;
                final float ux = vy * nz - vz * ny;
                final float uy = vz * nx - vx * nz;
                final float uz = vx * ny - vy * nx;

                final Particle particle = ((MixinParticleManager) pm).dsurround_createParticle(
                        ModParticles.FOLIAGE_SNOW, ax, ay, az, 0, (double) kind, 0.0D);
                if (particle == null) {
                    final int n = ++spawnNull;
                    if (n == 1 || n % 100 == 0)
                        this.logger.warn("[FoliageSnow] createParticle returned null x%d - is FOLIAGE_SNOW registered?", n);
                    continue;
                }
                if (!(particle instanceof FoliageSnowParticle unit)) {
                    final int n = ++spawnWrong;
                    if (n == 1 || n % 100 == 0)
                        this.logger.warn("[FoliageSnow] createParticle gave %s, not a FoliageSnowParticle x%d",
                                particle.getClass().getName(), n);
                    continue;
                }
                pm.add(particle);
                attached++;
                unit.setPlane(nx, ny, nz, ux, uy, uz, vx, vy, vz);
                // Round 44: the unit carries its own anchor - see the check in
                // FoliageDripParticle.tick. checkStale only walks the units still
                // on this plant's list; a unit handed to an exit (the depth
                // trim's beginSink, a roster eviction's beginFade) is off it and
                // would keep animating a plant that is gone.
                unit.setAnchor(spot.pos(), spot.state().getBlock());
                final double lift = NORMAL_OFFSET + (index % DEPTH_STEPS) * DEPTH_STAGGER;
                unit.move(nx * lift, ny * lift, nz * lift);
                // No land sound: dry snow is too light to announce itself (the
                // same ruling the dew layer's plip was weighed against).
                plant.units.add(new SnowUnit(unit, plane, anchor, spot.pos(), spot.state(),
                        useTier, tick + this.random.nextInt(LOTTERY_INTERVAL), index));
                index++;
                any = true;
                placed = true;
                added++;
            }
            // Spend the slot even when nothing fitted: the tier falls through
            // to a smaller shape next pass instead of being dropped. Dropping
            // is what left allium with 7 units out of 16.
            left[tier]--;
        }
        return any;
    }

    /**
     * Free anchors on this plane that can carry the shape {@code minRoom} asks for.
     *
     * <p>{@code narrowOnly} restricts the pick to anchors of EXACTLY that room -
     * the narrow perches - so the small shapes do not squat on the wide ones the
     * sheets need. Never {@code null}, possibly empty.</p>
     */
    private List<FoliageProfile.Anchor> freeAnchors(final FoliageProfile.Plane plane, final PlantSnow plant,
                                                    final int minRoom, final boolean narrowOnly) {
        final List<FoliageProfile.Anchor> out = new ArrayList<>();
        for (final FoliageProfile.Anchor anchor : plane.scatter()) {
            if (this.isTaken(plant, plane, anchor))
                continue;
            if (narrowOnly ? anchor.room() != minRoom : anchor.room() < minRoom)
                continue;
            out.add(anchor);
        }
        return out;
    }

    /** How many units this plant already rides on that plane. */
    private int countOn(final PlantSnow plant, final FoliageProfile.Plane plane) {
        int n = 0;
        for (final SnowUnit unit : plant.units)
            if (unit.plane() == plane)
                n++;
        return n;
    }

    /**
     * Whether this plant already holds a unit on that exact anchor. Identity,
     * not equals: the anchors come out of the plane's own baked array, so the
     * same object is the same landing spot. Two units on one anchor are a waste
     * - they overlap, and overlapping pixels buy no coverage.
     */
    private boolean isTaken(final PlantSnow plant, final FoliageProfile.Plane plane,
                            final FoliageProfile.Anchor anchor) {
        for (final SnowUnit unit : plant.units)
            if (unit.plane() == plane && unit.anchor() == anchor)
                return true;
        return false;
    }

    /** The let-go lottery: a unit now and then slips off the blade. */
    private void rollLottery(final Level world, final long tick) {
        for (final PlantSnow plant : this.plants.values()) {
            for (final Iterator<SnowUnit> it = plant.units.iterator(); it.hasNext(); ) {
                final SnowUnit unit = it.next();
                if (unit.particle().isRemoved()) {
                    it.remove();
                    continue;
                }
                if (unit.meltAt > 0L && tick >= unit.meltAt) {
                    this.meltOff(unit, MELT_FADE_TICKS);
                    unit.meltAt = -1L;
                    continue;
                }
                if (unit.meltAt < 0L || !unit.particle().isStatic() || tick < unit.nextRoll())
                    continue;
                unit.nextRoll = tick + LOTTERY_INTERVAL;
                if (this.random.nextFloat() >= ROLL_SLIP || unit.plane().paths().length == 0)
                    continue;
                final FoliageProfile.SlidePath path = FoliageProfile.nearestPath(
                        unit.plane().paths(), unit.anchor().x(), unit.anchor().y(), unit.anchor().z());
                if (path == null)
                    continue;
                final Vec3 offset = unit.state().getOffset(world, unit.pos());
                final float[] pts = new float[path.pts().length];
                for (int i = 0; i < path.pts().length; i += 3) {
                    pts[i] = (float) (unit.pos().getX() + offset.x + path.pts()[i]);
                    pts[i + 1] = (float) (unit.pos().getY() + offset.y + path.pts()[i + 1]);
                    pts[i + 2] = (float) (unit.pos().getZ() + offset.z + path.pts()[i + 2]);
                }
                // Round 12: "积雪滑落动画还是会斜着滑" - the melt exit was straightened
                // in round 8, this one never was, so it still rode the leaf's baked path.
                // Straight down, and slower still than the melt sink: a slip is a bit of
                // snow losing its grip, not the coat taking its leave. The drop is capped
                // the same way (never into the block below), so the fade always finishes
                // above the floor rather than inside it.
                final float room = unit.anchor().y() - SINK_FLOOR_CLEARANCE;
                final float drop = Math.max(SINK_DROP_MIN, Math.min(SINK_DROP_MAX, room));
                unit.particle().beginSink(drop, SLIP_SPEED, SLIP_FADE_TICKS);
            }
        }
    }

    /**
     * The plant on the roster that is farthest from {@code center} - the one
     * that gets swapped out when the budget is full and something nearer
     * wants in. O(roster), and only walked when the roster is actually full.
     */
    private PlantSnow farthest(final BlockPos center) {
        PlantSnow far = null;
        double best = -1.0;
        for (final PlantSnow plant : this.plants.values()) {
            final double d = plant.pos.distSqr(center);
            if (d > best) {
                best = d;
                far = plant;
            }
        }
        return far;
    }

    private void checkStale(final Level world, final BlockPos playerPos) {
        this.blockCheckPhase = (this.blockCheckPhase + 1) % KEEP_BLOCK_CHECK;
        final boolean checkBlocks = this.blockCheckPhase == 0;
        for (final Iterator<Map.Entry<Long, PlantSnow>> it = this.plants.entrySet().iterator(); it.hasNext(); ) {
            final PlantSnow plant = it.next().getValue();
            for (final Iterator<SnowUnit> uIt = plant.units.iterator(); uIt.hasNext(); ) {
                final SnowUnit unit = uIt.next();
                if (unit.particle().isRemoved()) {
                    uIt.remove();
                    continue;
                }
                if (unit.pos().distToCenterSqr(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > KEEP_DIST_SQ) {
                    unit.particle().remove();
                    uIt.remove();
                    continue;
                }
                // The plant is gone. Round 7: "将植物破坏以后雪延迟一下直接消失，
                // 如果能像露水一样掉落就好了，直接整个自由落体就行了" - so it
                // falls instead of blinking out. Round 8: the stagger I put
                // in front of that fall was mine, not his, and it read to him
                // as precisely the delay he had been complaining about
                // ("破坏后雪块掉落有很大延迟，每个依次掉"). Offline it spread a
                // plant's worth of units over 1.9 seconds - the first let go
                // at 1.2s and the last at 3.1s. Nothing is holding the snow
                // up the moment the block is gone, so let go on the tick we
                // notice and let the whole coat come off together.
                if (checkBlocks && world.getBlockState(unit.pos()).getBlock() != plant.block()) {
                    unit.particle().beginFall();
                    uIt.remove();
                }
            }
            if (plant.units.isEmpty())
                it.remove();
        }
    }

    // ---- round 48: the retention pool, measured -----------------------------
    /**
     * Retaining a unit does not cost nothing, and the maintainer's question -
     * "the kept range feels larger than the scanned range, could the exit just be
     * delayed into a standing pool" - is a question about that cost. These three
     * triples are the whole bill the HANDLER pays, in {@code System.nanoTime}:
     * the staleness pass (every {@link #STALE_CHECK_TICKS} ticks), the let-go
     * lottery (every tick) and the scan-and-attach it shares with the scan. The
     * first two walk every plant and every unit, so their cost is linear in the
     * pool and two runs at two pool sizes give the slope directly.
     *
     * <p>Clocked only while {@link FoliageScan#WINDOW_DIAG} is on; while it is off
     * this is one volatile read per pass and no clock at all. What is NOT in here
     * is the particle's own {@code tick()} and the render pass - those are the
     * engine's loops, and the pool's share of them is the difference between two
     * runs at two pool sizes, not something a handler can honestly clock.</p>
     */
    private long staleNanos = 0L;
    private long staleMaxNanos = 0L;
    private int stalePasses = 0;
    private long lotteryNanos = 0L;
    private long lotteryMaxNanos = 0L;
    private int lotteryPasses = 0;
    private long scanNanos = 0L;
    private long scanMaxNanos = 0L;
    private int scanPasses = 0;

    /**
     * Where the units we are holding sit relative to the window the scanner last
     * walked, plus what those three passes cost - the numbers an argument about
     * retention radii is decided on. Diagnostics only, and only ever called while
     * the diag switch is on: it walks the whole roster, which is nothing on a
     * {@link #DIAG_INTERVAL_TICKS} line and would be wrong per tick.
     *
     * <p>{@code offScanWindow} is the maintainer's observation made countable -
     * units that are still being drawn (and still being ticked) that no sweep is
     * maintaining. {@code overKeep28} is the same statement against the exit rule
     * itself; it is expected to stay at 0 and a non-zero there means a unit is
     * past the radius the staleness pass enforces.</p>
     */
    private String poolLine(final BlockPos playerPos) {
        final int[] bands = new int[5];
        int inWindow = 0;
        int units = 0;
        for (final PlantSnow plant : this.plants.values()) {
            units += plant.units.size();
            if (FoliageScan.inLastWindow(plant.pos.getX(), plant.pos.getY(), plant.pos.getZ()))
                inWindow++;
            final double d = Math.sqrt(plant.pos.distSqr(playerPos));
            if (d < 8D)
                bands[0]++;
            else if (d < 16D)
                bands[1]++;
            else if (d < 24D)
                bands[2]++;
            else if (d < 28D)
                bands[3]++;
            else
                bands[4]++;
        }
        final int plants = this.plants.size();
        return String.format(
                "plants=%d units=%d inScanWindow=%d offScanWindow=%d"
                        + " | dist<8=%d 8-16=%d 16-24=%d 24-28=%d overKeep28=%d"
                        + " | stale(%d) avg=%dus max=%dus | lottery(%d) avg=%dus max=%dus"
                        + " | scanAttach(%d) avg=%dus max=%dus",
                plants, units, inWindow, plants - inWindow,
                bands[0], bands[1], bands[2], bands[3], bands[4],
                this.stalePasses, this.staleNanos / 1000L / Math.max(1, this.stalePasses),
                this.staleMaxNanos / 1000L,
                this.lotteryPasses, this.lotteryNanos / 1000L / Math.max(1, this.lotteryPasses),
                this.lotteryMaxNanos / 1000L,
                this.scanPasses, this.scanNanos / 1000L / Math.max(1, this.scanPasses),
                this.scanMaxNanos / 1000L);
    }

    public void clear() {
        for (final PlantSnow plant : this.plants.values())
            for (final SnowUnit unit : plant.units)
                unit.particle().remove();
        this.plants.clear();
        this.lastScanTick = -1000L;
        this.lastScanPos = null;
        this.lastChunkX = Integer.MIN_VALUE;
        this.lastChunkZ = Integer.MIN_VALUE;
        this.wasSnowing = false;
        this.meltScheduled = false;
        this.precipStartTick = 0L;
    }

    @Override
    protected void gatherDiagnostics(final CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems,
                "Snow cover: " + this.plants.size() + " plants, " + this.unitCount() + " units");
    }

    /** One plant carrying snow. {@code block} is the staleness sentinel. */
    private static final class PlantSnow {
        final BlockPos pos;
        final net.minecraft.world.level.block.Block block;
        final List<SnowUnit> units = new ArrayList<>(4);
        /** When the units were placed - the melt window is shot from here. */
        long meltAtBase;
        /**
         * Which distance tier this plant was last judged to be in. Held here, not
         * recomputed per scan, so the coat only changes when the distance really
         * changed - see {@link DistanceLod}. A plant's tier is the only per-item
         * state LOD needs, and the plant is already the thing being remembered.
         */
        int lodTier = DistanceLod.TIER_NEAR;

        PlantSnow(final BlockPos pos, final net.minecraft.world.level.block.Block block) {
            this.pos = pos;
            this.block = block;
        }

        net.minecraft.world.level.block.Block block() {
            return this.block;
        }
    }

    /** One live unit: its particle, its plane (for the slip path), its clocks. */
    private static final class SnowUnit {
        final FoliageSnowParticle particle;
        final FoliageProfile.Plane plane;
        final FoliageProfile.Anchor anchor;
        final BlockPos pos;
        final BlockState state;
        /** Which shape tier this unit actually landed as (see TIER_MIN_ROOM). */
        final int tier;
        long nextRoll;
        final int index;
        /** -1 = already melting; 0 = still snowing; &gt;0 = the tick it starts. */
        long meltAt;

        SnowUnit(final FoliageSnowParticle particle, final FoliageProfile.Plane plane,
                 final FoliageProfile.Anchor anchor, final BlockPos pos, final BlockState state,
                 final int tier, final long nextRoll, final int index) {
            this.particle = particle;
            this.plane = plane;
            this.anchor = anchor;
            this.pos = pos;
            this.state = state;
            this.tier = tier;
            this.nextRoll = nextRoll;
            this.index = index;
            this.meltAt = 0L;
        }

        FoliageProfile.Anchor anchor() {
            return this.anchor;
        }

        FoliageSnowParticle particle() {
            return this.particle;
        }

        FoliageProfile.Plane plane() {
            return this.plane;
        }

        BlockPos pos() {
            return this.pos;
        }

        BlockState state() {
            return this.state;
        }

        long nextRoll() {
            return this.nextRoll;
        }
    }
}
