package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.client.particle.IcicleParticle;
import org.orecruncher.dsurround.client.particle.ModParticles;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.seasons.SeasonManager;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;
import org.orecruncher.dsurround.processing.scanner.DripScanner;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Icicles under eaves while it snows: they grow out of the edges the drip
 * scanner already knows how to find, hang there for as long as the snow
 * lasts, occasionally let go on their own, and when the snow stops they all
 * come down staggered - shattering on the ground with a crack and a scatter
 * of shards, then a spell of melt drips off the same edge.
 *
 * <p>Persistence is bookkeeping, not state: an icicle lives as long as its
 * point survives and the player stays near. Walk away and it is discarded;
 * walk back and the scan finds the same edge again - and because size,
 * placement and growth delay all derive from a STABLE hash of the position,
 * the same eave always grows the same icicles. The "same icicles every time"
 * requirement is the hash, not a save file.</p>
 *
 * <p>The scan is the drip scan, verbatim - an icicle hangs exactly where a
 * drop would. What is reused from the drip handler is the landing shape: the
 * falling icicle integrates a known ballistic (gravity 0.06, no drag - see
 * {@link IcicleParticle#tick()}), so the shatter sound is booked in advance
 * for the tick the tip reaches the ground, the same clock trick the drips
 * use, and the melt drips afterward are the drip handler's own particle on
 * its own hang/fall parameters.</p>
 */
public class IcicleHandler extends AbstractClientHandler {

    /** The shatter: DSR's own ice crack set (ambient/ice/crack1-3). */
    private static final Identifier SHATTER =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "ice");

    /** Rescan cadence while it snows. Slower than the drip scan: roofs do not move, and ice grows on a 20s clock. */
    private static final int SCAN_TICKS = 60;
    /** Hard ceiling on live icicles around the player. */
    private static final int MAX_ICICLES = 48;
    /** The live cap scales with precipitation intensity, the drip budget's way:
     * want = base + span * ambient, clamped to the ceiling. */
    private static final int DENSITY_BASE = 6;
    private static final int DENSITY_SPAN = 42;
    /** An icicle whose point is further than this (3D) is discarded outright - the walk-back regrows it identically. */
    private static final long KEEP_DIST_SQ = 48L * 48L;
    /** How often the staleness/distance pass runs. */
    private static final int STALE_CHECK_TICKS = 10;
    /** Mean interval between self-drops, GLOBAL across all live icicles (~80s): one lets go now and then, not a shower. */
    private static final int SELF_DROP_MEAN_TICKS = 1600;
    /** When the snow stops, everything comes down spread across this window (~60s), not at once. */
    private static final int SNOW_END_SPREAD_TICKS = 1200;
    /** Precipitation-event duration at which the size curve's peak reaches the
     * grand tier: 3000 ticks = three game hours - the maintainer's "3 小时后
     * 中形态" example lands the peak on the middle of five tiers right there.
     * The clock is the GLOBAL rain/snow event (one clock, world-wide, from the
     * moment the sky opens) - not per-biome, so walking between biomes never
     * resets it. */
    private static final long EVENT_PEAK_TICKS = 3000L;
    /** Five size tiers under the duration curve (was three, flat-rolled). */
    private static final int TIER_COUNT = 5;
    /** Three shape textures; the hash picks, so a rim keeps its character. */
    private static final int SHAPE_COUNT = 3;
    /** Extra ticks booked on the shatter clock so the sound never leads the visual. */
    private static final int SHATTER_DELAY_EXTRA = 2;
    /** Melt drips per fallen icicle, and when they start after the shatter. */
    private static final int MELT_DRIPS_PER_ICICLE = 2;
    private static final int MELT_DRIP_DELAY_MIN = 100;
    private static final int MELT_DRIP_DELAY_SPAN = 200;
    /** Sound range for the crack. */
    private static final float MIN_VOLUME = 0.45F;
    private static final float MAX_VOLUME = 0.9F;
    private static final float MIN_PITCH = 0.85F;
    private static final float MAX_PITCH = 1.15F;
    /** Guard on booked shatters, mirroring the drip handler's cap. */
    private static final int MAX_PENDING = 64;

    private final IAudioPlayer audioPlayer;
    private final ISoundLibrary soundLibrary;
    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private final Map<Long, Attached> attached = new HashMap<>();
    private final List<PendingShatter> pending = new ArrayList<>();
    private final List<PendingMeltDrip> melts = new ArrayList<>();
    private final Map<Identifier, ISoundFactory> factories = new HashMap<>();

    private boolean wasSnowing = false;
    /** World-wide precipitation event clock: when the sky last OPENED. The
     * size curve reads tick - precipStartTick; walking between biomes does
     * not touch it, only the sky closing and reopening does. */
    private boolean wasRaining = false;
    private long precipStartTick = 0L;
    /** The last scan's eave candidates - the pool replacements draw from. */
    private final ObjectArray<DripScanner.DripPoint> lastPool = new ObjectArray<>();
    // NOT Long.MIN_VALUE: tick - MIN_VALUE overflows to negative, so
    // `tick - lastScan >= SCAN_TICKS` never fired and the layer sat silent
    // through the whole first live test. -1000 means "due immediately".
    private long lastScan = -1000L;
    private long lastStaleCheck = -1000L;
    private long lastEmptyLog = -1000L;
    /** Consecutive tick failures - see the catch in {@link #onTick}. */
    private int failures = 0;

    public IcicleHandler(final Configuration config, final IModLog logger, final IAudioPlayer audioPlayer,
                         final ISoundLibrary soundLibrary, final ITickCount tickCount) {
        super("Icicles", config, logger);
        this.audioPlayer = audioPlayer;
        this.soundLibrary = soundLibrary;
        this.tickCount = tickCount;
        // The icicle layer registers on the tick-end bus directly, exactly like
        // the drip layer: Handlers resolves this class WITHOUT adding it to its
        // own tick list (that is what its register() is for), so a handler that
        // only ever calls process() sits constructed but never called - the
        // live test's "no icicles at all", with the mod otherwise clean.
        ClientState.TICK_END.register(this::onTick);
    }

    /**
     * Same isolation as the drip layer's onTick: an exception here would take
     * every later tick-end subscriber down with it, so log the first failure
     * in full, then one line every 200 ticks, and never let it escape.
     */
    private void onTick(final Minecraft client) {
        try {
            this.tick0();
        } catch (final Throwable t) {
            final int count = ++this.failures;
            if (count == 1 || count % 200 == 0)
                this.logger.error(t, "[IcicleHandler] threw (%d consecutive); the icicle layer sat this tick out",
                        count);
        }
    }

    private void tick0() {
        if (!this.config.weatherOptions.enableIcicles) {
            // Switched off mid-session: bring the live ones down cleanly.
            if (!this.attached.isEmpty())
                this.clear();
            return;
        }
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;
        this.process(player);
    }

    public void process(final Player player) {
        final long tick = this.tickCount.getTickCount();
        final Level world = player.level();
        final BlockPos pos = player.blockPosition();

        // The size curve's clock: ONE world-wide precipitation event, from the
        // moment the sky opens. Rain and snow share it, and walking between
        // biomes cannot reset it - the snow gate below only decides whether
        // ICICLES may grow here; how big they have earned the right to be is a
        // property of the weather event itself (maintainer round-6 feedback).
        final boolean raining = world.isRaining();
        if (raining && !this.wasRaining)
            this.precipStartTick = tick;
        this.wasRaining = raining;

        // Column-STABLE snow gate. PrecipitationIntensity.phase() answers for
        // the player's own block column - its heightmap check flips to NONE
        // the moment the player steps under an eave or a tree (the live test's
        // log flapped SNOW/NONE every few ticks while he walked a village),
        // which would both block growth and fire the snow-end stagger mid-
        // snowfall. What the icicles need is "is it snowing in this biome":
        // vanilla says it is precipitating, the biome sheds snow, and the
        // temperature is under the snow threshold - no per-column terms.
        final boolean snowing = world.isRaining()
                && PrecipitationIntensity.potentialPhase(pos) == Biome.Precipitation.SNOW
                && SeasonManager.HANDLER.isSnowTemperature(pos);
        // The INFO line that makes a silent first test readable (the drip
        // layer's own lesson): on every snow-state change, say what the gate
        // actually returned.
        if (snowing != this.wasSnowing)
            this.logger.info("[IcicleHandler] snow=%s (raining=%s potential=%s ambient=%.2f) attached=%d near (%d,%d,%d)",
                    snowing, world.isRaining(), PrecipitationIntensity.potentialPhase(pos),
                    PrecipitationIntensity.ambient(), this.attached.size(),
                    pos.getX(), pos.getY(), pos.getZ());

        if (snowing) {
            // Resume edge: a biome-border flap's drop schedule is undone - the
            // eave only sheds when the snow STAYS over. The curve needs no
            // resume care: its clock is the world-wide rain event, which
            // borders cannot flip.
            if (!this.wasSnowing) {
                for (final Attached a : this.attached.values())
                    a.dropAt = -1;
            }
            if (tick - this.lastScan >= SCAN_TICKS) {
                this.lastScan = tick;
                this.scanAndAttach(world, pos, tick);
            }
            // The occasional lone dropout while it keeps snowing.
            if (!this.attached.isEmpty() && this.random.nextInt(SELF_DROP_MEAN_TICKS) == 0) {
                final Attached pick = this.pickAny();
                if (pick != null)
                    this.scheduleDrop(pick, tick + this.random.nextInt(40));
            }
        } else if (this.wasSnowing && !this.attached.isEmpty()) {
            // Snow's over: everyone comes down, staggered, melting as they land.
            for (final Attached a : this.attached.values())
                this.scheduleDrop(a, tick + this.random.nextInt(SNOW_END_SPREAD_TICKS));
        }

        // Fire scheduled drops; drop bookkeeping for anything that died on its own.
        for (final Iterator<Attached> it = this.attached.values().iterator(); it.hasNext(); ) {
            final Attached a = it.next();
            if (a.particle.isDead()) {
                it.remove();
            } else if (a.dropAt >= 0 && tick >= a.dropAt) {
                a.dropAt = -1;
                this.release(a, tick);
                it.remove();
                this.reAttach(world, tick, snowing);
            }
        }

        // Staleness and distance, on their own slow clock.
        if (tick - this.lastStaleCheck >= STALE_CHECK_TICKS) {
            this.lastStaleCheck = tick;
            for (final Iterator<Attached> it = this.attached.values().iterator(); it.hasNext(); ) {
                final Attached a = it.next();
                if (a.particle.isDead()) {
                    it.remove();
                    continue;
                }
                if (a.point.point().distSqr(pos) > KEEP_DIST_SQ) {
                    a.particle.discard();
                    it.remove();
                    continue;
                }
                // Its support is gone (block broken) - it falls NOW.
                if (!DripScanner.isStillHeld(world, a.point)) {
                    this.release(a, tick);
                    it.remove();
                    this.reAttach(world, tick, snowing);
                }
            }
        }

        this.playDue(tick);
        this.spawnDueMelts(world, tick);
        this.wasSnowing = snowing;
    }

    public void onDisconnect() {
        this.clear();
    }

    private void clear() {
        for (final Attached a : this.attached.values())
            a.particle.discard();
        this.attached.clear();
        this.pending.clear();
        this.melts.clear();
        this.wasSnowing = false;
        this.wasRaining = false;
        this.precipStartTick = 0L;
        this.lastPool.clear();
        this.lastScan = -1000L;
        this.lastStaleCheck = -1000L;
    }

    // ---- growth -------------------------------------------------------------

    /**
     * Finds eave edges with the drip scanner and grows an icicle at each new
     * one. Everything about the icicle - size tier, where it hangs along the
     * edge, how long before it starts growing - comes from one stable hash of
     * the point, so a given edge always produces the same icicle.
     */
    private void scanAndAttach(final Level world, final BlockPos center, final long tick) {
        // Density rides the precipitation intensity, the drip budget's way.
        final int want = Math.min(MAX_ICICLES,
                DENSITY_BASE + Math.round(DENSITY_SPAN * PrecipitationIntensity.ambient()));
        if (this.attached.size() >= want)
            return;
        int candidates = 0;
        int spawned = 0;
        int failed = 0;
        final int[] shapes = new int[SHAPE_COUNT];
        final ObjectArray<DripScanner.DripPoint> found = new ObjectArray<>(64);
        for (final DripScanner.DripPoint point : DripScanner.scan(world, center, true)) {
            candidates++;
            found.add(point);
            if (this.attached.size() >= want)
                break;
            final Long key = point.point().asLong();
            if (this.attached.containsKey(key))
                continue;
            final long hash = stableHash(point.point());
            final int growthDelay = (int) ((hash >>> 8) % 200);
            final double[] hang = icicleSpot(point, hash);
            // The root sits just under the roof's measured underside - the
            // same formula the drips use, minus their hang-off gap: an icicle
            // grows FROM the surface, not 0.09 below it.
            final double rootY = point.roof().getY() + point.undersideY() - 0.02D;
            final int shape = this.spawnOne(point, hang[0], rootY, hang[1], hash, tick, growthDelay);
            if (shape >= 0) {
                spawned++;
                shapes[shape]++;
            } else {
                failed++;
            }
        }
        // The replacement pool: what this scan says can hold an icicle. The
        // pool is refilled wholesale - rims that stopped qualifying fall out
        // of it with the next scan, which is what keeps the broken-roof loop
        // dead even without the isStillHeld re-check in reAttach.
        if (candidates > 0) {
            this.lastPool.clear();
            this.lastPool.addAll(found);
        }
        // The other silent-first-test line: zero candidates means the scan
        // found no eave in range (stand nearer a roof); a spawn failure means
        // the provider never registered. Both were invisible before this.
        if (candidates == 0 && this.attached.isEmpty() && tick - this.lastEmptyLog >= 100) {
            this.lastEmptyLog = tick;
            // The scanner's own rejection histogram rides along - it names the
            // gate that ate the candidates (rainUnreachable high = sky test,
            // etc.) instead of leaving "zero" unexplained.
            final StringBuilder hist = new StringBuilder();
            for (int i = 0; i < DripScanner.STAT_COUNT; i++)
                hist.append(' ').append(DripScanner.STAT_NAMES[i]).append('=').append(DripScanner.STATS[i]);
            this.logger.info("[IcicleHandler] snowing but the scan found 0 eave candidates near (%d,%d,%d)%s",
                    center.getX(), center.getY(), center.getZ(), hist);
        } else if (spawned > 0) {
            this.logger.info("[IcicleHandler] growing %d new icicles (cone=%d knife=%d fang=%d, %d attached, %d failed) near (%d,%d,%d)",
                    spawned, shapes[0], shapes[1], shapes[2], this.attached.size(), failed,
                    center.getX(), center.getY(), center.getZ());
        } else if (failed > 0 && tick - this.lastEmptyLog >= 100) {
            this.lastEmptyLog = tick;
            this.logger.info("[IcicleHandler] %d icicle spawns failed (particle provider missing?) near (%d,%d,%d)",
                    failed, center.getX(), center.getY(), center.getZ());
        }
    }

    /**
     * Where along the edge the icicle hangs - the drip handler's rim logic,
     * with the stable hash in place of its random: the lean off the face and
     * the spread along it are fixed per position, which is what makes the same
     * eave read as the same row of icicles on every visit.
     */
    private static double[] icicleSpot(final DripScanner.DripPoint point, final long hash) {
        double hx = point.point().getX() + 0.5D;
        double hz = point.point().getZ() + 0.5D;
        final int mask = point.faces();
        if (mask == DripScanner.FACE_CENTER) {
            // A post sprouts from the middle of its underside.
        } else if (mask == 0) {
            hx += (slice(hash, 16, 9) - 4) * 0.08D;
            hz += (slice(hash, 20, 9) - 4) * 0.08D;
        } else {
            final int count = Integer.bitCount(mask);
            final int pick = (int) slice(hash, 16, count >= 2 ? count + 1 : 1);
            final boolean diagonal = count >= 2 && pick == count;
            int faceX = 0;
            int faceZ = 0;
            if (diagonal) {
                // Bit i = the i-th face of Direction.Plane.HORIZONTAL, counted
                // while iterating - the same convention the drip rim logic uses.
                int index = 0;
                for (final Direction d : Direction.Plane.HORIZONTAL) {
                    if ((mask & (1 << index)) != 0) {
                        faceX += d.getStepX();
                        faceZ += d.getStepZ();
                    }
                    index++;
                }
                if (faceX == 0 && faceZ == 0) {
                    // Opposite faces cancel - fall back to the first set face.
                    index = 0;
                    for (final Direction d : Direction.Plane.HORIZONTAL) {
                        if ((mask & (1 << index)) != 0) {
                            faceX = d.getStepX();
                            faceZ = d.getStepZ();
                            break;
                        }
                        index++;
                    }
                }
            } else {
                int index = 0;
                int seen = 0;
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
            final double lean = 0.42D + slice(hash, 24, 5) * 0.01D;
            hx += faceX * lean;
            hz += faceZ * lean;
            // Spread along the edge - the full block, like the drips.
            if (faceX == 0)
                hx += (slice(hash, 4, 9) - 4) * 0.084D;
            if (faceZ == 0)
                hz += (slice(hash, 28, 9) - 4) * 0.084D;
        }
        return new double[] { hx, hz };
    }

    /**
     * Size tier from the precipitation-EVENT duration curve (the maintainer's
     * model): the likeliest size drifts upward as the weather event keeps
     * going - his example, three game hours in, peaks on the middle of five
     * tiers. A gaussian around the drifting peak keeps neighboring tiers
     * common and both extremes rare. The clock restarts only when the sky
     * closes and opens again.
     */
    private int rollTier(final long tick) {
        final float peak = Math.min(TIER_COUNT - 1F,
                Math.max(0F, (tick - this.precipStartTick) / (float) EVENT_PEAK_TICKS * (TIER_COUNT - 1F)));
        final double sigma = 1.1D;
        final double[] weights = new double[TIER_COUNT];
        double total = 0.0D;
        for (int i = 0; i < TIER_COUNT; i++) {
            final double d = i - peak;
            total += weights[i] = Math.exp(-(d * d) / (2.0D * sigma * sigma));
        }
        double roll = this.random.nextFloat() * total;
        for (int i = 0; i < TIER_COUNT; i++) {
            roll -= weights[i];
            if (roll <= 0.0D)
                return i;
        }
        return TIER_COUNT - 1;
    }

    /**
     * Spawns one icicle at a rim and files it. The shape comes from the
     * position hash (a rim keeps its character across regrowths), the size
     * tier from the precipitation-event curve, the growth delay from the
     * caller (hash-spread on first fill, zero on an immediate replacement).
     *
     * @return the shape index that was spawned, or -1 on failure
     */
    private int spawnOne(final DripScanner.DripPoint point, final double hangX, final double rootY,
                         final double hangZ, final long hash, final long tick, final int growthDelay) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return -1;
        final int shape = (int) (hash % SHAPE_COUNT);
        final int tier = rollTier(tick);
        final Particle particle = ((MixinParticleManager) pm)
                .dsurround_createParticle(typeFor(shape), hangX, rootY, hangZ,
                        tier, growthDelay, 0.0D);
        if (particle == null)
            return -1;
        pm.add(particle);
        this.attached.put(point.point().asLong(), new Attached(point, (IcicleParticle) particle, hangX, hangZ));
        return shape;
    }

    /**
     * The maintainer's rule: whenever an icicle goes somewhere, a fresh one
     * immediately starts growing from zero - the eave never empties while the
     * snow lasts. NOT at the fallen icicle's own rim: that is how a broken
     * roof block looped grow-and-drop forever in the live test (the rim stops
     * being valid the moment its block is gone, and forcing the replacement
     * right back onto it re-fails every staleness pass). The replacement
     * draws a RANDOM rim from the last scan's candidate pool instead,
     * skipping rims that are gone or already occupied - the population as a
     * whole stays where the scan says it can be. Only while it IS snowing:
     * after the stop, replacements would grow into nothing.
     */
    private void reAttach(final Level world, final long tick, final boolean snowing) {
        if (!snowing || this.lastPool.isEmpty())
            return;
        final List<DripScanner.DripPoint> usable = new ArrayList<>(this.lastPool.size());
        for (final DripScanner.DripPoint point : this.lastPool) {
            if (this.attached.containsKey(point.point().asLong()))
                continue;
            if (!DripScanner.isStillHeld(world, point))
                continue;
            usable.add(point);
        }
        if (usable.isEmpty())
            return;
        final DripScanner.DripPoint pick = usable.get(this.random.nextInt(usable.size()));
        final long hash = stableHash(pick.point());
        final double[] hang = icicleSpot(pick, hash);
        this.spawnOne(pick, hang[0], pick.roof().getY() + pick.undersideY() - 0.02D, hang[1], hash, tick, 0);
    }

    private static SimpleParticleType typeFor(final int shape) {
        // Shape to particle type - a switch keeps the shape count here and the
        // textures in sync by eye.
        return switch (shape) {
            case 0 -> ModParticles.ICICLE_CONE;
            case 1 -> ModParticles.ICICLE_KNIFE;
            default -> ModParticles.ICICLE_FANG;
        };
    }

    /** A positive-ish slice of the hash: bits {@code shift}.., mod {@code mod}. */
    private static long slice(final long hash, final int shift, final int mod) {
        return ((hash >>> shift) & 0xFFFFL) % Math.max(1, mod);
    }

    /** Position-stable hash: the same block always rolls the same icicle. */
    private static long stableHash(final BlockPos pos) {
        long h = pos.asLong();
        h ^= h >>> 17;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }

    // ---- dropping and landing ----------------------------------------------

    private Attached pickAny() {
        int n = this.random.nextInt(this.attached.size());
        for (final Attached a : this.attached.values()) {
            if (n-- == 0)
                return a;
        }
        return null;
    }

    private void scheduleDrop(final Attached a, final long at) {
        if (a.dropAt < 0)
            a.dropAt = at;
    }

    /**
     * Lets go: the particle switches to its ballistic fall, the shatter sound
     * is booked for the tick its tip reaches the ground (same analytic clock -
     * see {@link #fallTicks}), and the melt drips are scheduled off the same
     * edge a while after: the ice is gone, the snow on the rim melts and
     * drips.
     */
    private void release(final Attached a, final long tick) {
        a.particle.release();
        if (this.pending.size() < MAX_PENDING) {
            this.pending.add(new PendingShatter(a.point.landing(),
                    tick + fallTicks(a.point.fall()) + SHATTER_DELAY_EXTRA,
                    MIN_VOLUME + this.random.nextFloat() * (MAX_VOLUME - MIN_VOLUME)));
        }
        for (int i = 0; i < MELT_DRIPS_PER_ICICLE; i++) {
            this.melts.add(new PendingMeltDrip(a.hangX, a.rootY - 0.05D, a.hangZ,
                    a.point.fall(),
                    tick + fallTicks(a.point.fall()) + MELT_DRIP_DELAY_MIN
                            + this.random.nextInt(MELT_DRIP_DELAY_SPAN)));
        }
    }

    /**
     * Ticks for the icicle's tip to fall {@code fall} blocks on the particle's
     * exact integration (acceleration 0.06/tick, no drag): distance after n
     * ticks is 0.03*n*(n+1). Solved in closed form; the particle checks
     * {@code onGround} anyway, this only places the sound.
     */
    private static int fallTicks(final int fall) {
        final double d = Math.max(1, fall);
        return (int) Math.ceil((Math.sqrt(0.0009D + 0.12D * d) - 0.03D) / 0.06D);
    }

    private void playDue(final long tick) {
        final Iterator<PendingShatter> it = this.pending.iterator();
        while (it.hasNext()) {
            final PendingShatter shatter = it.next();
            if (shatter.dueTick() > tick)
                continue;
            it.remove();
            final ISoundFactory factory = this.factory(SHATTER);
            if (factory == null)
                continue;
            final BlockPos landing = shatter.landing();
            this.audioPlayer.play(factory.createAtLocation(
                    landing.getX() + 0.5D, landing.getY() + 1.0D, landing.getZ() + 0.5D,
                    shatter.volume()));
        }
    }

    /**
     * Melt drips: the drip handler's own particle, hanging off the edge the
     * icicle fell from and falling the same travel. No sound of its own - the
     * drip layer owns landing sounds, and during a snow wind-down these are
     * few enough to stay background.
     */
    private void spawnDueMelts(final Level world, final long tick) {
        final Iterator<PendingMeltDrip> it = this.melts.iterator();
        while (it.hasNext()) {
            final PendingMeltDrip melt = it.next();
            if (melt.spawnAt() > tick)
                continue;
            it.remove();
            final var pm = GameUtils.getParticleManager();
            if (pm == null)
                continue;
            final Particle particle = ((MixinParticleManager) pm).dsurround_createParticle(
                    ModParticles.WATER_DRIP, melt.x(), melt.y(), melt.z(),
                    20 + this.random.nextInt(60),
                    melt.fall() * 2 + 2, 0.0D);
            if (particle == null)
                continue;
            pm.add(particle);
        }
    }

    /**
     * Resolved against the library on first use - the library only knows
     * sounds.json's events after a resource load, so asking at construction
     * would race it. Same lazy cache the drip handler keeps.
     */
    private ISoundFactory factory(final Identifier pool) {
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

    @Override
    protected void gatherDiagnostics(final CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems,
                "Icicles: " + this.attached.size() + " attached, "
                        + this.pending.size() + " shatters pending, "
                        + this.melts.size() + " melts pending");
    }

    /** One live icicle. {@code dropAt} carries a scheduled end (-1 = none yet). */
    private static final class Attached {
        final DripScanner.DripPoint point;
        final IcicleParticle particle;
        final double hangX;
        final double hangZ;
        final double rootY;
        long dropAt = -1;

        Attached(final DripScanner.DripPoint point, final IcicleParticle particle,
                 final double hangX, final double hangZ) {
            this.point = point;
            this.particle = particle;
            this.hangX = hangX;
            this.hangZ = hangZ;
            this.rootY = point.roof().getY() + point.undersideY() - 0.02D;
        }
    }

    private record PendingShatter(BlockPos landing, long dueTick, float volume) {
    }

    private record PendingMeltDrip(double x, double y, double z, int fall, long spawnAt) {
    }
}
