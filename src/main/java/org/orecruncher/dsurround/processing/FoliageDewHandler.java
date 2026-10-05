package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.client.particle.FoliageDripParticle;
import org.orecruncher.dsurround.client.particle.ModParticles;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.config.libraries.IBiomeLibrary;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.seasons.SeasonManager;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;
import org.orecruncher.dsurround.processing.scanner.FoliageScan;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Morning dew on foliage - the icicle layer's bookkeeping wearing liquid:
 * persistent anchors scanned when the player ENTERS A NEW CHUNK (plus a slow
 * 30s top-up and a move trigger, so standing still in a growing world still
 * fills in), each plant keeping 1-3 beads for the whole dew window, and a
 * quiet per-bead lottery every 200 ticks: mostly nothing, sometimes a slide,
 * rarely a drop.
 *
 * <p>The density runs on {@link #dewCurve(long)} - pure function of
 * {@code dayTime % 24000} (混元 review C: NOT the cumulative getDayTime, or
 * the curve shifts a day late): ramping from 03:36, PEAKING just before
 * sunrise (05:24 - the maintainer's anchor), holding through dawn, gone by
 * mid-morning. Density is two factors multiplied: how many beads per plant
 * (1-3, cap 3 by ruling) times how many plants carry dew at all (biome trait
 * coefficient - WET/LUSH swamps get everything, deserts and frost get
 * nothing).</p>
 *
 * <p>The lottery is phase-shifted per bead ({@code stableHash(pos, index) %
 * 200}, 混元 review D): one scan batch would otherwise age in lockstep and
 * a whole meadow would slide on the same tick. Landing plips are the RAIN
 * drip layer's own ground_small pool at a lower volume, throttled globally
 * to one per 40 ticks (混元 review E) - a dewy meadow must rustle, not
 * patter.</p>
 */
public class FoliageDewHandler extends AbstractClientHandler {

    // ---- dew curve (pure; see dewCurve) -------------------------------------
    /** Beads start forming in the small hours. */
    private static final long CURVE_START = 21600L;
    /** The peak lands just BEFORE sunrise (05:24 game clock). */
    private static final long CURVE_PEAK = 23400L;
    /** Dawn hold ends here; the evaporation ramp runs to CURVE_END. */
    private static final long CURVE_DAWN = 300L;
    /** Gone by ~09:15 game clock. */
    private static final long CURVE_END = 3300L;
    private static final long DAY_LENGTH = 24000L;

    // ---- cadence -------------------------------------------------------------
    /** Rescan when the player enters a new chunk (the maintainer's ruling). */
    private static final int CHUNK_COOLDOWN_TICKS = 100;
    /** ...or walks this far (blocks, squared). */
    private static final double RESCAN_MOVE_SQ = 36.0D;
    /** Slow top-up so standing still still fills the world in. */
    private static final int TOPUP_TICKS = 600;
    /** The bead lottery: every 200t (10s), per ruling. */
    private static final int LOTTERY_INTERVAL = 200;
    private static final float ROLL_STAY = 0.87F;
    private static final float ROLL_SLIDE = 0.10F;
    /** Drops per plant cap (fifth-round ruling). */
    private static final int MAX_DROPS_PER_PLANT = 3;
    /**
     * Bead size: 0, so the particle rolls vanilla's own variance (0.5..1.0 of
     * the vanilla drip quad) - the same roll the eave layer uses. Dew is
     * already separated from rain by how many beads there are and by the
     * window, not by every bead being cut to one small size.
     */
    private static final float DEW_SIZE_SCALE = 0.0F;
    /** Dew slides are unhurried. */
    private static final float SLIDE_SPEED = 0.5F;
    /** The window's tail: beads shrink out over this. */
    private static final int EVAPORATE_TICKS = 600;
    /** Rain kills dew faster. */
    private static final int EVAPORATE_RAIN_TICKS = 300;
    /** Live anchors past this distance are dropped (icicle rule). */
    private static final long KEEP_DIST_SQ = 48L * 48L;
    /** Staleness cadence - a broken plant must read immediately. */
    private static final int STALE_CHECK_TICKS = 2;
    /** Landing plip throttle (review E) and volume. */
    private static final long SOUND_MIN_INTERVAL = 40L;
    private static final float MIN_VOLUME = 0.15F;
    private static final float MAX_VOLUME = 0.25F;
    private static final float MIN_PITCH = 0.9F;
    private static final float MAX_PITCH = 1.1F;
    private static final ResourceLocation GROUND_SMALL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "weather/drip/ground_small");
    private static final ResourceLocation LANDING_WATER =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block.pointed_dripstone.drip_water_into_cauldron");
    /** Drop hang off the plane, toward the viewer. */
    /**
     * Drop hang off the plane, toward the viewer. Only enough to win the depth
     * test against the leaf - at 0.04 it was nearly a pixel of air and the
     * beads read as floating in front of the plant instead of sitting on it.
     */
    private static final double NORMAL_OFFSET = 0.012D;

    private final ITickCount tickCount;
    private final IAudioPlayer audioPlayer;
    private final ISoundLibrary soundLibrary;
    private final IBiomeLibrary biomeLibrary;
    private final IRandomizer random = Randomizer.current();

    private final Map<Long, PlantDew> plants = new HashMap<>();
    private final Map<ResourceLocation, ISoundFactory> factories = new HashMap<>();

    private Level lastWorld;
    private long lastScanTick = -1000L;
    private long lastStaleCheck = -1000L;
    private long lastSoundTick = -1000L;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private BlockPos lastScanPos = null;
    /** True while the previous tick had a live curve - the window edges. */
    private boolean wasDewy = false;
    private boolean evaporateStarted = false;
    private int failures = 0;

    public FoliageDewHandler(final Configuration config, final IModLog logger, final ITickCount tickCount,
                             final IAudioPlayer audioPlayer, final ISoundLibrary soundLibrary,
                             final IBiomeLibrary biomeLibrary) {
        super("FoliageDew", config, logger);
        this.tickCount = tickCount;
        this.audioPlayer = audioPlayer;
        this.soundLibrary = soundLibrary;
        this.biomeLibrary = biomeLibrary;
        ClientState.TICK_END.register(this::onTick);
        ClientState.ON_DISCONNECT.register(client -> this.clear());
        // Landing plips come back as a live callback from the particle (the
        // icicle layer's lesson: announce the moment, never book it ahead).
        FoliageDripParticle.setLandListener(this::onLand);
    }

    private void onTick(final Minecraft client) {
        try {
            this.tick0();
        } catch (final Throwable t) {
            final int count = ++this.failures;
            if (count == 1 || count % 200 == 0)
                this.logger.error(t, "[FoliageDew] threw (%d consecutive); the layer sat this tick out", count);
        }
    }

    private void tick0() {
        if (!this.config.weatherOptions.enableMorningDew) {
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
        final float curve = dewCurve(world.getDayTime());
        final boolean raining = world.isRaining();
        // Rain owns the leaves while it falls; dew only exists in clear air.
        final boolean dewy = curve > 0F && !raining;

        if (dewy != this.wasDewy)
            this.logger.info("[FoliageDew] dew=%s curve=%.2f raining=%s plants=%d near (%d,%d,%d)",
                    dewy, curve, raining, this.plants.size(), pos.getX(), pos.getY(), pos.getZ());

        // Window edges: scan immediately on open; shrink everything on close
        // or when rain moves in over a dewy morning.
        if (dewy && !this.wasDewy) {
            this.evaporateStarted = false;
            this.lastScanTick = -1000L;
        }
        if (!dewy && this.wasDewy && !this.evaporateStarted && !this.plants.isEmpty()) {
            this.evaporateStarted = true;
            final int evapTicks = raining ? EVAPORATE_RAIN_TICKS : EVAPORATE_TICKS;
            for (final PlantDew plant : this.plants.values())
                for (final DewDrop drop : plant.drops)
                    drop.particle().beginEvaporate(evapTicks);
        }
        this.wasDewy = dewy;

        // Scans: new chunk (with cooldown), a walk, the window opening, or the
        // slow top-up - never the per-tick churn of the eave layer.
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        final boolean newChunk = chunkX != this.lastChunkX || chunkZ != this.lastChunkZ;
        final boolean moved = this.lastScanPos == null
                || this.lastScanPos.distToCenterSqr(pos.getX(), pos.getY(), pos.getZ()) >= RESCAN_MOVE_SQ;
        if (dewy && (newChunk && tick - this.lastScanTick >= CHUNK_COOLDOWN_TICKS
                || moved
                || tick - this.lastScanTick >= TOPUP_TICKS)) {
            this.lastChunkX = chunkX;
            this.lastChunkZ = chunkZ;
            this.lastScanTick = tick;
            this.lastScanPos = pos.immutable();
            this.scanAndAttach(world, pos, tick, curve, player);
        }

        // The per-bead lottery, phase-shifted per bead (review D).
        if (dewy)
            this.rollLottery(world, tick, curve);

        // Staleness and distance on the icicle layer's fast clock.
        if (tick - this.lastStaleCheck >= STALE_CHECK_TICKS) {
            this.lastStaleCheck = tick;
            this.checkStale(world, pos);
        }
    }

    /**
     * The dew curve, a PURE function of the day clock (review C): callers
     * pass {@code world.getDayTime()}; the modulo is in here so nobody can
     * feed it the raw cumulative value and shift every morning by a day.
     *
     * @return 0..1 - 0 before 03:36, ramping to 1.0 at 05:24 (just before
     *         sunrise), holding through dawn, fading to 0 by ~09:15.
     */
    public static float dewCurve(long dayTime) {
        final long t = Math.floorMod(dayTime, DAY_LENGTH);
        if (t >= CURVE_START)
            return Math.min(1.0F, (t - CURVE_START) / (float) (CURVE_PEAK - CURVE_START));
        if (t < CURVE_DAWN)
            return 1.0F;
        if (t < CURVE_END)
            return Math.max(0.0F, 1.0F - (t - CURVE_DAWN) / (float) (CURVE_END - CURVE_DAWN));
        return 0.0F;
    }

    /**
     * Biome trait coefficient (maintainer ruling: the DSR preset table).
     * First match wins, ordered wet to dry; unknown/other biomes get zero -
     * conservative, and the dry/frost rows are the deliberate zeros.
     */
    private float coefficientOf(final Level world, final BlockPos pos) {
        final Biome biome = world.getBiome(pos).value();
        final BiomeInfo info = this.biomeLibrary.getBiomeInfo(biome);
        final var traits = info.getTraits();
        if (traits.contains("WET") || traits.contains("LUSH") || traits.contains("SWAMP") || traits.contains("JUNGLE"))
            return 1.25F;
        if (traits.contains("FLORAL") || traits.contains("FOREST") || traits.contains("TEMPERATE") || traits.contains("DENSE"))
            return 1.0F;
        if (traits.contains("PLAINS") || traits.contains("TAIGA") || traits.contains("CONIFEROUS")
                || traits.contains("HILLS") || traits.contains("MOUNTAIN"))
            return 0.7F;
        if (traits.contains("SAVANNA") || traits.contains("WINDSWEPT") || traits.contains("SPARSE")
                || traits.contains("MUSHROOM") || traits.contains("MAGICAL"))
            return 0.4F;
        return 0.0F;
    }

    private void scanAndAttach(final Level world, final BlockPos center, final long tick,
                               final float curve, final Player player) {
        for (final FoliageScan.FoliageSpot spot : FoliageScan.scan(world, center, FoliageScan.Mode.DEW,
                this.config.weatherOptions.foliageScanRange)) {
            final Long key = spot.pos().asLong();
            if (this.plants.containsKey(key))
                continue;
            // Frost has no dew either (and one day, its own frost feature).
            if (SeasonManager.HANDLER.isSnowTemperature(spot.pos()))
                continue;
            final float coeff = this.coefficientOf(world, spot.pos());
            final float density = (float) this.config.weatherOptions.foliageDensity;
            if (coeff <= 0F)
                continue;
            final float keep = Math.min(1.0F, 0.15F + 0.85F * curve * coeff) * density;
            if (this.random.nextFloat() >= keep)
                continue;
            final int beads = Math.max(1, Math.min(MAX_DROPS_PER_PLANT,
                    1 + Math.round(2F * curve * coeff)));
            final PlantDew plant = new PlantDew(spot.pos(), spot.state().getBlock());
            if (!this.attach(world, player, spot, plant, beads, tick)) {
                for (final DewDrop d : plant.drops)
                    d.particle().remove();
                continue;
            }
            this.plants.put(key, plant);
        }
    }

    /** Spawns up to {@code beads} drops on distinct anchors; false if none landed. */
    private boolean attach(final Level world, final Player player, final FoliageScan.FoliageSpot spot,
                           final PlantDew plant, final int beads, final long tick) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return false;
        final Vec3 offset = spot.state().getOffset(world, spot.pos());
        boolean any = false;
        int index = 0;
        for (final FoliageProfile.Plane plane : spot.profile().planes()) {
            if (plant.drops.size() >= beads)
                break;
            if (plane.anchors().length == 0)
                continue;
            final int anchorIdx = this.weightedAnchor(plane);
            final FoliageProfile.Anchor anchor = plane.anchors()[anchorIdx];
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
                rz = 1F;   // horizontal plane: no natural up (review A)
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
                    ModParticles.FOLIAGE_DROP, ax, ay, az, 0, DEW_SIZE_SCALE, 0.0D);
            if (!(particle instanceof FoliageDripParticle drop))
                continue;
            pm.add(particle);
            drop.setPlane(nx, ny, nz, ux, uy, uz, vx, vy, vz);
            drop.setSoundOnLand();
            drop.move(nx * NORMAL_OFFSET, ny * NORMAL_OFFSET, nz * NORMAL_OFFSET);
            // Review D: the first lottery is phase-shifted per bead, so one
            // scan batch never ages in lockstep.
            final long nextRoll = tick + this.random.nextInt(LOTTERY_INTERVAL);
            plant.drops.add(new DewDrop(drop, plane, spot.pos(), spot.state(), nextRoll, index++));
            any = true;
        }
        return any;
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

    /** The 10s lottery: mostly nothing, sometimes a slide, rarely a drop. */
    private void rollLottery(final Level world, final long tick, final float curve) {
        for (final PlantDew plant : this.plants.values()) {
            for (final Iterator<DewDrop> it = plant.drops.iterator(); it.hasNext(); ) {
                final DewDrop drop = it.next();
                if (drop.particle().isRemoved()) {
                    it.remove();
                    continue;
                }
                if (!drop.particle().isStatic() || tick < drop.nextRoll())
                    continue;
                drop.advanceRoll(tick);
                final float roll = this.random.nextFloat();
                if (roll < 0.03F) {
                    // The rare let-go: a single drop (ruling: always single),
                    // plip booked by the particle's land callback.
                    drop.particle().beginFall();
                } else if (roll < 0.03F + ROLL_SLIDE && drop.plane().paths().length > 0) {
                    final FoliageProfile.SlidePath path = drop.plane().paths()[
                            this.random.nextInt(drop.plane().paths().length)];
                    final Vec3 offset = drop.state().getOffset(world, drop.pos());
                    final float[] pts = new float[path.pts().length];
                    for (int i = 0; i < path.pts().length; i += 3) {
                        pts[i] = (float) (drop.pos().getX() + offset.x + path.pts()[i]);
                        pts[i + 1] = (float) (drop.pos().getY() + offset.y + path.pts()[i + 1]);
                        pts[i + 2] = (float) (drop.pos().getZ() + offset.z + path.pts()[i + 2]);
                    }
                    final int ticks = Math.max(5, Math.round(path.length() / SLIDE_SPEED * 20F));
                    final float endY = path.pts()[path.pts().length - 2];
                    drop.particle().beginSlide(pts, ticks, endY > 0.25F);
                }
                // else: stay - the common answer.
            }
        }
    }

    private void checkStale(final Level world, final BlockPos playerPos) {
        for (final Iterator<Map.Entry<Long, PlantDew>> it = this.plants.entrySet().iterator(); it.hasNext(); ) {
            final PlantDew plant = it.next().getValue();
            for (final Iterator<DewDrop> dIt = plant.drops.iterator(); dIt.hasNext(); ) {
                final DewDrop drop = dIt.next();
                if (drop.particle().isRemoved()) {
                    dIt.remove();
                    continue;
                }
                // Distance first: beyond KEEP_DIST the beads stop being ours.
                if (drop.pos().distToCenterSqr(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > KEEP_DIST_SQ) {
                    drop.particle().remove();
                    dIt.remove();
                    continue;
                }
                // The plant is gone (or swapped): the beads let go and fall -
                // water on a broken blade falls, it does not evaporate.
                if (world.getBlockState(drop.pos()).getBlock() != plant.block()) {
                    drop.particle().beginFall();
                    dIt.remove();
                }
            }
            if (plant.drops.isEmpty())
                it.remove();
        }
    }

    /** The dew plip - ground_small at hushed volume, globally throttled (review E). */
    private void onLand(final double x, final double y, final double z) {
        final long tick = this.tickCount.getTickCount();
        if (tick - this.lastSoundTick < SOUND_MIN_INTERVAL)
            return;
        this.lastSoundTick = tick;
        final Level world = this.lastWorld;
        if (world == null)
            return;
        final var below = BlockPos.containing(x, y - 0.4D, z);
        final ResourceLocation pool = world.getFluidState(below).isEmpty() ? GROUND_SMALL : LANDING_WATER;
        final ISoundFactory factory = this.factory(pool);
        if (factory == null)
            return;
        this.audioPlayer.play(factory.createAtLocation(x, y, z,
                MIN_VOLUME + this.random.nextFloat() * (MAX_VOLUME - MIN_VOLUME)));
    }

    /** Lazy sound-factory cache - the library resolves after a resource load. */
    private ISoundFactory factory(final ResourceLocation pool) {
        if (this.factories.containsKey(pool))
            return this.factories.get(pool);
        if (!this.soundLibrary.isSoundRegistered(pool)) {
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

    private void clear() {
        for (final PlantDew plant : this.plants.values())
            for (final DewDrop drop : plant.drops)
                drop.particle().remove();
        this.plants.clear();
        this.lastScanTick = -1000L;
        this.lastScanPos = null;
        this.lastChunkX = Integer.MIN_VALUE;
        this.lastChunkZ = Integer.MIN_VALUE;
        this.wasDewy = false;
        this.evaporateStarted = false;
    }

    @Override
    protected void gatherDiagnostics(final CollectDiagnosticsEvent event) {
        int drops = 0;
        for (final PlantDew plant : this.plants.values())
            drops += plant.drops.size();
        event.add(CollectDiagnosticsEvent.Section.Systems,
                "Dew: " + this.plants.size() + " plants, " + drops + " beads, cache "
                        + FoliageProfileCache.size() + " models");
    }

    /** One plant carrying beads. {@code block} is the staleness sentinel. */
    private static final class PlantDew {
        final BlockPos pos;
        final net.minecraft.world.level.block.Block block;
        final List<DewDrop> drops = new ArrayList<>(3);

        PlantDew(final BlockPos pos, final net.minecraft.world.level.block.Block block) {
            this.pos = pos;
            this.block = block;
        }

        net.minecraft.world.level.block.Block block() {
            return this.block;
        }
    }

    /** One live bead: its particle, its plane (for slide paths), its lottery clock. */
    private static final class DewDrop {
        final FoliageDripParticle particle;
        final FoliageProfile.Plane plane;
        final BlockPos pos;
        final net.minecraft.world.level.block.state.BlockState state;
        long nextRoll;
        final int index;

        DewDrop(final FoliageDripParticle particle, final FoliageProfile.Plane plane,
                final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state,
                final long nextRoll, final int index) {
            this.particle = particle;
            this.plane = plane;
            this.pos = pos;
            this.state = state;
            this.nextRoll = nextRoll;
            this.index = index;
        }

        FoliageDripParticle particle() {
            return this.particle;
        }

        FoliageProfile.Plane plane() {
            return this.plane;
        }

        BlockPos pos() {
            return this.pos;
        }

        net.minecraft.world.level.block.state.BlockState state() {
            return this.state;
        }

        long nextRoll() {
            return this.nextRoll;
        }

        void advanceRoll(final long tick) {
            this.nextRoll = tick + LOTTERY_INTERVAL;
        }
    }
}
