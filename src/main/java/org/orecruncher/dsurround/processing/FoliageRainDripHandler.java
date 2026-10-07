package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.client.particle.FoliageDripParticle;
import org.orecruncher.dsurround.client.particle.ModParticles;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;
import org.orecruncher.dsurround.processing.scanner.DripScanner;
import org.orecruncher.dsurround.processing.scanner.FoliageScan;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;

/**
 * Rain landing on foliage: droplets form on the leaves' upper edges and, per
 * the maintainer's sixth-round ruling, SLIDE - a spawned drop's fate is
 * decided once, at spawn: most slide down the pre-baked path to the ground
 * and vanish silently, a few rest a moment and fall as a SINGLE drop (no
 * bursts, no threads - the eave layer's rhythms stay on the eaves). Falling
 * drops make no landing sound here; a wet meadow that plips at every blade
 * was ruled too loud.
 *
 * <p>The budget is the drip layer's shape - one global rate
 * {@code BASE*value + SPAN*value^2} shared by every spot, per-spot cooldown,
 * and the pool refilled by random draws - but the eave's three rhythms are
 * deliberately NOT ported, which removes the whole burst/thread state
 * machine. Per-plant concurrency is capped at SIX live drops (ruling: three
 * was too few).</p>
 *
 * <p>Scan cadence is 30t with a +10 tick phase offset (混元 review G): the
 * eave scan runs on its own 20t cadence, and two volume walks landing on the
 * same tick would stack into a single-frame spike. Not a hard guarantee -
 * both cadences drift - but the coincidence rate drops to 1 in 60 ticks, and
 * both walks measure in tenths of a millisecond.</p>
 */
public class FoliageRainDripHandler extends AbstractClientHandler {

    /** Scan cadence and phase offset vs the eave layer's 20t clock (review G). */
    private static final int SCAN_TICKS = 30;
    private static final int SCAN_PHASE = 10;
    /** Global drop budget: {@code BASE*value + SPAN*value^2} drops a second. */
    private static final double RATE_BASE = 5.0D;
    private static final double RATE_SPAN = 28.0D;
    private static final float TICK_SECONDS = 0.05F;
    /** Pool sizing, the drip budget's way. */
    private static final int MAX_SPOTS = 48;
    /** Per-plant live-drop cap (sixth-round ruling). */
    private static final int MAX_DROPS_PER_PLANT = 6;
    /** Per-spot cooldown between drops - a spot is one anchor on one blade. */
    private static final int COOLDOWN_CALM_TICKS = 16;
    private static final int COOLDOWN_STORM_TICKS = 4;
    /** Fate odds at spawn (sixth-round ruling: slide-first). */
    private static final float SLIDE_CHANCE = 0.80F;
    /**
     * Slider speed, blocks per second. Deliberately slow: the eave layer's
     * drop hangs for {@code HANG_TICKS = 40} before it even lets go, so a
     * blade-scale slide that finished in 8 ticks read as teleporting - the
     * "飞速" half of the complaint. At 0.25 a typical ~0.5 block blade path
     * takes ~40 ticks, the same two seconds the eave drop hangs.
     */
    private static final float SLIDE_SPEED = 0.0125F;
    /** A dropper's rest on the blade before letting go, in ticks. */
    private static final int REST_MIN_TICKS = 10;
    private static final int REST_MAX_TICKS = 20;
    /**
     * Drop size. Left at 0 so the particle takes vanilla's own variance
     * (0.5..1.0 of the vanilla drip quad) - the maintainer asked for the
     * eave layer's size roll, and a hand-picked range here only pins every
     * drop to one look again.
     */
    private static final float SIZE_MIN = 0.0F;
    /**
     * How far a drop hangs off its plane, toward the viewer side. Just enough
     * to win the depth test against the leaf it sits on - at 0.04 it was
     * nearly a pixel of pure air, and on a blade one or two pixels wide that
     * reads as the bead floating in front of the plant rather than on it.
     */
    private static final double NORMAL_OFFSET = 0.012D;
    /** Spot lifetime - the pool rotation the eave layer tuned. */
    private static final int SPOT_LIFETIME_MIN_TICKS = 600;
    private static final int SPOT_LIFETIME_SPAN_TICKS = 900;
    // ---- the after-rain residual channel ------------------------------------
    /**
     * Wet plants do not go dry the instant the rain stops. For this long after
     * the last rain the layer keeps making drops, but as PERSISTENT beads that
     * sit on the leaf and only rarely let go (see
     * {@link #RESIDUAL_DROP_CHANCE}), on a rate that is cut hard at the moment
     * the rain stops and then decays to nothing over the window.
     */
    /** The cut applied the moment the rain stops - "突然大幅减少". */
    private static final float RESIDUAL_FRACTION = 0.15F;
    /** How often a residual bead rolls for letting go - once a second. */
    private static final int RESIDUAL_ROLL_INTERVAL = 20;
    /** ...and how rarely it actually does. */
    private static final float RESIDUAL_DROP_CHANCE = 0.02F;
    /** Diagnostics cadence - one status line per 5s, not per tick. */
    private static final int DIAG_INTERVAL_TICKS = 100;

    /**
     * Diagnostics only: pretend the rain is falling at full intensity so the
     * layer can be exercised without waiting for weather. Set from
     * {@code /dsdebug foliage force}; never read by anything else.
     */
    public static volatile boolean DEBUG_FORCE = false;
    /** The live instance, for the diagnostics command. */
    public static volatile FoliageRainDripHandler INSTANCE = null;
    /** How many drops this layer has actually spawned since the last clear. */
    public static int spawned = 0;
    /** Spawn failures: null = no provider, wrong = the provider made something else. */
    public static int spawnNull = 0;
    public static int spawnWrong = 0;

    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private final List<Spot> pool = new ArrayList<>();
    /** Live-drop accounting for the per-plant cap: (plant key, expire tick). */
    private final List<long[]> live = new ArrayList<>();
    /** config.weatherOptions.foliageDensity, read per tick. */

    private Level lastWorld;
    private long lastScanTick = -1000L + 10L;   // phase offset (review G)
    private float accumulator = 0F;
    private int failures = 0;
    private BlockPos lastScanPos;
    private long lastDiagTick = -1000L;
    /** Beads made by the residual channel, each rolling once a second. */
    private final List<Residual> residualDrops = new ArrayList<>();
    /** True while this tick's drops are residual ones (persistent, no slide). */
    private boolean inResidual = false;

    public FoliageRainDripHandler(final Configuration config, final IModLog logger, final ITickCount tickCount) {
        super("FoliageRainDrips", config, logger);
        this.tickCount = tickCount;
        ClientState.TICK_END.register(this::onTick);
        ClientState.ON_DISCONNECT.register(client -> this.clear());
        INSTANCE = this;
    }

    // ---- diagnostics surface ---------------------------------------------

    public int poolSize() {
        return this.pool.size();
    }

    public int liveCount() {
        return this.live.size();
    }

    /** The config this instance was built with (the diagnostics command needs it). */
    public Configuration config() {
        return this.config;
    }

    /**
     * Drops the scan stamp so the next tick rescans instead of waiting out the
     * 30s cadence - for {@code /dsdebug foliage force}.
     */
    public void forceRescan() {
        this.lastScanTick = -1000L;
        this.lastScanPos = null;
        this.lastDiagTick = -1000L;
    }

    private void onTick(final Minecraft client) {
        try {
            this.tick0();
        } catch (final Throwable t) {
            final int count = ++this.failures;
            if (count == 1 || count % 200 == 0)
                this.logger.error(t, "[FoliageRain] threw (%d consecutive); the layer sat this tick out", count);
        }
    }

    private void tick0() {
        if (!this.config.weatherOptions.enableFoliageRainDrips) {
            if (!this.pool.isEmpty() || !this.live.isEmpty())
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
        FoliageScan.TRACE = this.dripTrace();

        // Frozen gate, the drip layer's rule: snow means the liquid layer is off.
        final boolean frozen = world.isRaining()
                && PrecipitationIntensity.potentialPhase(pos) == Biome.Precipitation.SNOW;
        final float value = frozen ? 0.0F : this.valueFor(world);

        // Live-drop accounting decay.
        this.live.removeIf(e -> e[1] <= tick);

        // Diagnostics: one line per 5s. "Nothing is dripping" is answered by
        // this line alone - value says whether the gate is open, the histogram
        // says whether the plants ever reached the pool at all.
        if (tick - this.lastDiagTick >= DIAG_INTERVAL_TICKS) {
            this.lastDiagTick = tick;
            this.logger.info("[FoliageRain] enabled=%s value=%.3f pool=%d live=%d spawned=%d force=%s | %s",
                    this.config.weatherOptions.enableFoliageRainDrips, value, this.pool.size(),
                    this.live.size(), spawned, DEBUG_FORCE, FoliageScan.statsLine());
        }

        if (value <= 0F) {
            if (!this.pool.isEmpty() || this.accumulator != 0F || !this.residualDrops.isEmpty()) {
                if (!this.residualDrops.isEmpty())
                    this.releaseResiduals(RESIDUAL_FADE_TICKS);
                this.pool.clear();
                this.accumulator = 0F;
            }
            return;
        }

        final boolean moved = this.lastScanPos == null
                || this.lastScanPos.distToCenterSqr(pos.getX(), pos.getY(), pos.getZ()) >= 16.0D;
        if (tick - this.lastScanTick >= SCAN_TICKS || moved) {
            this.lastScanTick = tick;
            this.lastScanPos = pos.immutable();
            this.rescan(world, pos, tick, value, player);
        }

        // Shared budget, jittered per tick so spacing never settles.
        final float density = (float) this.config.weatherOptions.foliageDensity;
        this.accumulator += (RATE_BASE * value + RATE_SPAN * value * value) * TICK_SECONDS
                * (0.5F + this.random.nextFloat()) * density;
        if (this.accumulator > 2F)
            this.accumulator = 2F;
        // A starved pool must not silently eat the budget: if no spot is
        // eligible this tick the drop is not "spent", it rolls over. The
        // accumulator is still capped, so a long drought cannot build into a
        // burst - it only hands back what the cooldowns withheld.
        while (this.accumulator >= 1F) {
            if (!this.fireOne(world, player, tick, value))
                break;
            this.accumulator -= 1F;
        }

        // The after-rain lottery, whenever there are persistent beads left.
        if (!this.residualDrops.isEmpty())
            this.rollResidual(world, tick);

        // Expire spots.
        boolean expired = false;
        for (final Iterator<Spot> it = this.pool.iterator(); it.hasNext(); ) {
            if (tick >= it.next().expireTick) {
                it.remove();
                expired = true;
            }
        }
        if (expired)
            this.rescan(world, pos, tick, value, player);
    }

    /**
     * The gate value for this tick, and it no longer drops to zero the moment
     * the rain does: wet plants stay wet. While it rains this is the ambient
     * intensity; once it stops the value is cut to {@link #RESIDUAL_FRACTION}
     * of what the rain had ("突然大幅减少") and then decays linearly to zero
     * across 120s by the PrecipitationResponse.FOLIAGE_DRIP channel, after which the layer goes quiet.
     *
     * <p>Sets {@link #inResidual} so the spawner knows to make persistent
     * beads instead of sliders.</p>
     */
    private float valueFor(final Level world) {
        if (DEBUG_FORCE) {
            this.inResidual = false;
            return 1.0F;   // diagnostics: skip the weather gate entirely
        }
        // The project already owns an after-rain curve for the eave drips
        // (PrecipitationResponse.DRIP); this layer reads its own channel off
        // the same mechanism rather than running arithmetic of its own. The
        // channel carries the decay AND the sweep that guarantees it reaches
        // zero, which a hand-rolled linear ramp has to re-solve every time.
        final float channel = PrecipitationResponse.FOLIAGE_DRIP.value();
        final boolean raining = PrecipitationIntensity.ambient() > 0F || world.isRaining();
        // "突然大幅减少": the moment the rain is over the same curve is read at
        // a fraction of itself. The curve is the same water either way.
        this.inResidual = !raining && channel > 0F;
        return this.inResidual ? channel * RESIDUAL_FRACTION : channel;
    }

    /**
     * The residual lottery: once a second each persistent bead either stays
     * where it is or lets go (2%). The falling drop is the same size as the
     * resting one - see FoliageDripParticle's billboard.
     */
    private void rollResidual(final Level world, final long tick) {
        for (final Iterator<Residual> it = this.residualDrops.iterator(); it.hasNext(); ) {
            final Residual r = it.next();
            if (r.drop.isRemoved()) {
                it.remove();
                continue;
            }
            // The blade is gone (broken, harvested, swapped): the water it was
            // holding falls. Same rule the dew layer already had - water on a
            // broken blade falls, it does not hang in the air where the plant
            // used to be.
            if (world.getBlockState(r.pos).getBlock() != r.block) {
                r.drop.beginFall();
                it.remove();
                continue;
            }
            if (!r.drop.isStatic())
                continue;   // already on its way down
            if (tick < r.nextRoll)
                continue;
            r.nextRoll = tick + RESIDUAL_ROLL_INTERVAL;
            if (this.random.nextFloat() < RESIDUAL_DROP_CHANCE)
                r.drop.beginFall();
        }
    }

    private void rescan(final Level world, final BlockPos center, final long tick, final float value, final Player player) {
        // Keep what is still valid.
        final List<Spot> kept = new ArrayList<>(this.pool.size());
        for (final Spot spot : this.pool) {
            if (tick >= spot.expireTick)
                continue;
            if (spot.pos.distToCenterSqr(player.getX(), player.getY(), player.getZ()) > 400D)
                continue;
            if (!world.getBlockState(spot.pos).getBlock().equals(spot.state.getBlock()))
                continue;
            kept.add(spot);
        }

        final int want = Math.min(MAX_SPOTS, 10 + Math.round(30F * value));
        int candidatePlants = 0;
        if (kept.size() < want) {
            final ObjectArray<FoliageScan.FoliageSpot> found =
                    FoliageScan.scan(world, center, FoliageScan.Mode.RAIN,
                            this.config.weatherOptions.foliageScanRange);
            candidatePlants = found.size();
            // The walk hands the candidates back in BOX ORDER - the (-x,-z)
            // corner first - so taking the first `want` of them pinned every
            // drop to that one corner: a fixed patch of dripping grass that
            // walked along with the player. Start at a random candidate and
            // wrap around instead, and the pool is drawn from the whole box.
            final int first = candidatePlants > 0 ? this.random.nextInt(candidatePlants) : 0;
            for (int k = 0; k < candidatePlants; k++) {
                if (kept.size() >= want)
                    break;
                final FoliageScan.FoliageSpot plant = found.get((first + k) % candidatePlants);
                // One or two spots per plant: the second only half the time,
                // the eave layer's spread rule, so plants read unevenly.
                int existing = 0;
                for (final Spot s : kept)
                    if (s.pos.equals(plant.pos()))
                        existing++;
                final int attempts = existing == 0 ? 2 : 1;
                for (int attempt = 0; attempt < attempts && kept.size() < want; attempt++) {
                    if (attempt > 0 && this.random.nextFloat() < 0.5F)
                        break;
                    final FoliageProfile.Plane plane = plant.profile().planes()
                            .get(this.random.nextInt(plant.profile().planes().size()));
                    if (plane.anchors().length == 0)
                        continue;
                    final int anchor = this.weightedAnchor(plane);
                    final Spot spot = new Spot(plant.pos(), plant.state(), plant.profile(),
                            plane, anchor, tick + SPOT_LIFETIME_MIN_TICKS
                                    + this.random.nextInt(SPOT_LIFETIME_SPAN_TICKS));
                    kept.add(spot);
                }
            }
            if (FoliageScan.TRACE && candidatePlants == 0)
                this.logger.info("[FoliageRain] scan @(%d,%d,%d) found 0 candidates (review F: sparse ground fills no pool)",
                        center.getX(), center.getY(), center.getZ());
        }

        this.pool.clear();
        this.pool.addAll(kept);
        if (this.dripTrace() && !kept.isEmpty())
            this.logger.debug(Configuration.Flags.FOLIAGE_DRIPS,
                    "[FoliageRain] pool=%d plants~%d value=%.2f", this.pool.size(), candidatePlants, value);
    }

    private boolean dripTrace() {
        return this.config.logging.enableDebugLogging
                && (this.config.logging.traceMask & Configuration.Flags.FOLIAGE_DRIPS) != 0;
    }

    private int weightedAnchor(final FoliageProfile.Plane plane) {
        float total = 0F;
        for (final FoliageProfile.Anchor a : plane.anchors())
            total += a.weight();
        float roll = this.random.nextFloat() * total;
        for (int i = 0; i < plane.anchors().length; i++) {
            roll -= plane.anchors()[i].weight();
            if (roll <= 0F)
                return i;
        }
        return plane.anchors().length - 1;
    }

    private int cooldownTicks(final float value) {
        return Math.round(COOLDOWN_CALM_TICKS
                + (COOLDOWN_STORM_TICKS - COOLDOWN_CALM_TICKS) * value);
    }

    private boolean fireOne(final Level world, final Player player, final long tick, final float value) {
        Spot best = null;
        int eligible = 0;
        for (final Spot spot : this.pool) {
            if (tick - spot.lastFire < cooldownTicks(value))
                continue;
            if (plantLiveCount(spot.pos) >= MAX_DROPS_PER_PLANT)
                continue;
            if (!world.getBlockState(spot.pos).getBlock().equals(spot.state.getBlock()))
                continue;
            eligible++;
            if (this.random.nextInt(eligible) == 0)
                best = spot;
        }
        if (best == null)
            return false;
        best.lastFire = tick;
        this.spawn(world, player, best, tick);
        return true;
    }

    private int plantLiveCount(final BlockPos pos) {
        final long key = pos.asLong();
        int n = 0;
        for (final long[] e : this.live)
            if (e[0] == key)
                n++;
        return n;
    }

    /** Spawns one drop with its fate fixed (sixth-round ruling). */
    private void spawn(final Level world, final Player player, final Spot spot, final long tick) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return;
        final Vec3 offset = spot.state.getOffset(world, spot.pos);
        final FoliageProfile.Anchor anchor = spot.plane.anchors()[spot.anchor];
        final double ax = spot.pos.getX() + offset.x + anchor.x();
        final double ay = spot.pos.getY() + offset.y + anchor.y();
        final double az = spot.pos.getZ() + offset.z + anchor.z();

        // Plane basis with the horizontal-plane fallback (混元 review A), the
        // normal flipped toward the viewer so the bead sits on the near side.
        float nx = spot.plane.nx(), ny = spot.plane.ny(), nz = spot.plane.nz();
        final double toPlayerX = player.getX() - ax, toPlayerY = player.getY() - ay, toPlayerZ = player.getZ() - az;
        if (nx * toPlayerX + ny * toPlayerY + nz * toPlayerZ < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float rx = 0F, ry = 1F, rz = 0F;
        final float d = nx * rx + ny * ry + nz * rz;
        if (Math.abs(d) > 0.99F) {
            // Horizontal plane (lily pad top): world up projects to zero - use north.
            rx = 0F;
            ry = 0F;
            rz = 1F;
        }
        float vx = rx - nx * (nx * rx + ny * ry + nz * rz);
        float vy = ry - ny * (nx * rx + ny * ry + nz * rz);
        float vz = rz - nz * (nx * rx + ny * ry + nz * rz);
        float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vl < 1.0e-6F)
            return;
        vx /= vl;
        vy /= vl;
        vz /= vl;
        float ux = vy * nz - vz * ny;
        float uy = vz * nx - vx * nz;
        float uz = vx * ny - vy * nx;

        // The residual channel makes PERSISTENT beads: they sit on the leaf and
        // roll once a second to let go. No slide, no self-timed fall - that is
        // the whole difference from a drop made while it is still raining.
        final boolean residual = this.inResidual;
        final boolean slider = !residual
                && this.random.nextFloat() < SLIDE_CHANCE && spot.plane.paths().length > 0;
        // 0 = let the particle take vanilla's own size variance (0.5..1.0),
        // exactly the roll the eave layer's drops get. Pinning a size here
        // is what WaterDripParticle calls out as wrong for the main path.
        final double sizeScale = SIZE_MIN;
        final int rest = residual ? 0
                : (slider ? 0 : REST_MIN_TICKS + this.random.nextInt(REST_MAX_TICKS - REST_MIN_TICKS + 1));

        final Particle particle = ((MixinParticleManager) pm).dsurround_createParticle(
                ModParticles.FOLIAGE_DROP, ax, ay, az, rest, sizeScale, 0.0D);
        if (particle == null) {
            // The one hard failure: no provider is registered for FOLIAGE_DROP.
            final int n = ++spawnNull;
            if (n == 1 || n % 100 == 0)
                this.logger.warn("[FoliageRain] createParticle returned null x%d - is FOLIAGE_DROP registered?", n);
            return;
        }
        pm.add(particle);
        spawned++;
        if (!(particle instanceof FoliageDripParticle drop)) {
            final int n = ++spawnWrong;
            if (n == 1 || n % 100 == 0)
                this.logger.warn("[FoliageRain] createParticle gave %s, not a FoliageDripParticle x%d",
                        particle.getClass().getName(), n);
            return;
        }

        // Nudge onto the near side of the plane - and never above the anchor.
        drop.setPlane(nx, ny, nz, ux, uy, uz, vx, vy, vz);
        drop.setDew(false);      // rain-foliage: pale blue, not the dew white
        drop.move(nx * NORMAL_OFFSET, ny * NORMAL_OFFSET, nz * NORMAL_OFFSET);

        final long expire;
        if (residual) {
            // Persistent: it stays until the once-a-second roll says let go.
            this.residualDrops.add(new Residual(drop, spot.pos, spot.state.getBlock(),
                    tick + RESIDUAL_ROLL_INTERVAL));
            // A ceiling anyway - a bead that never wins must not sit there
            // forever after the layer has gone quiet.
            expire = tick + 2400L;   // 120s ceiling, matches the channel
            this.live.add(new long[] { spot.pos.asLong(), expire });
            return;
        }
        if (slider) {
            // The bead sits on one anchor; the paths were baked from other
            // column tops. Take the one that STARTS nearest this bead - the
            // old random pick made the opening move a leap across the sprite.
            final FoliageProfile.SlidePath path = FoliageProfile.nearestPath(
                    spot.plane.paths(), anchor.x(), anchor.y(), anchor.z());
            // Waypoints to world space (same block offset as the anchor).
            final float[] pts = path == null ? null : new float[path.pts().length];
            if (pts != null) {
                for (int i = 0; i < path.pts().length; i += 3) {
                    pts[i] = (float) (spot.pos.getX() + offset.x + path.pts()[i]);
                    pts[i + 1] = (float) (spot.pos.getY() + offset.y + path.pts()[i + 1]);
                    pts[i + 2] = (float) (spot.pos.getZ() + offset.z + path.pts()[i + 2]);
                }
            }
            // The path end at model y > 0.25 is mid-air (double-plant upper
            // halves): let go instead of evaporating on the spot.
            final float endY = path == null ? 0F : path.pts()[path.pts().length - 2];
            if (pts != null && drop.beginSlide(pts, SLIDE_SPEED, endY > 0.25F)) {
                // Nominal slide time, generously - the bead's own pace decides,
                // and this is only the accounting that frees the plant's slot.
                expire = tick + Math.round(path.length() / SLIDE_SPEED * 1.6F) + 60L;
            } else {
                // Nothing below the bead to slide down: it lets go instead.
                drop.beginFall();
                expire = tick + 120L;
            }
        } else {
            expire = tick + rest + 120L;
        }
        this.live.add(new long[] { spot.pos.asLong(), expire });
    }

    /** Empties the layer (public: the diagnostics command turns the forced gate back off with it). */
    public void clear() {
        // Particles FIRST, lists second - and that order is the whole fix. A persistent
        // bead is only ever let go by rollResidual, which walks residualDrops; clearing
        // the list without removing the particles dropped the only handle to them, so the
        // beads stayed in the world forever and every rain after that added another coat.
        // Losing the reference was worse than not clearing at all. No fade here: this runs
        // on a world change or a disconnect, where there is nothing to watch.
        this.releaseResiduals(0);
        this.pool.clear();
        this.live.clear();
        this.accumulator = 0F;
        this.lastScanTick = -1000L + 10L;
        this.lastScanPos = null;
        this.inResidual = false;
    }

    /**
     * How long a persistent bead takes to shrink away when the layer is emptied for good.
     * Quick on purpose: this is the rain slider's own ending, and a bead whose water has
     * already run out should not be seen hanging around waiting to be noticed.
     */
    private static final int RESIDUAL_FADE_TICKS = 20;

    /**
     * Lets go of every persistent bead, then forgets them.
     *
     * <p>Called from both places that empty this layer, and from nowhere else - the point is
     * that no path may drop the list while the particles are still alive. Each removal is
     * guarded on its own: one particle refusing to go must not strand the rest, and a failure
     * here must never be silent (it is exactly the state that leaked before).</p>
     *
     * @param fadeTicks 0 to remove them now, otherwise the shrink-out duration
     */
    private void releaseResiduals(final int fadeTicks) {
        for (final Residual r : this.residualDrops) {
            try {
                if (fadeTicks <= 0)
                    r.drop.remove();
                else
                    r.drop.beginEvaporate(fadeTicks);
            } catch (Throwable t) {
                this.logger.warn("[FoliageRain] could not release a residual bead: %s", t);
            }
        }
        this.residualDrops.clear();
    }

    /** One persistent after-rain bead, and the tick it next rolls to let go. */
    private static final class Residual {
        final FoliageDripParticle drop;
        final BlockPos pos;
        final Block block;
        long nextRoll;

        Residual(final FoliageDripParticle drop, final BlockPos pos, final Block block, final long nextRoll) {
            this.drop = drop;
            this.pos = pos;
            this.block = block;
            this.nextRoll = nextRoll;
        }
    }

    /**
     * One pooled anchor: a plant block, one of its profile's planes, and the
     * anchor index within it. {@code state} is kept for the offset recompute
     * and the staleness check at fire time.
     */
    private static final class Spot {
        final BlockPos pos;
        final net.minecraft.world.level.block.state.BlockState state;
        final FoliageProfile profile;
        final FoliageProfile.Plane plane;
        final int anchor;
        final long expireTick;
        long lastFire = -1000L;

        Spot(final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state,
             final FoliageProfile profile, final FoliageProfile.Plane plane, final int anchor,
             final long expireTick) {
            this.pos = pos;
            this.state = state;
            this.profile = profile;
            this.plane = plane;
            this.anchor = anchor;
            this.expireTick = expireTick;
        }
    }
}
