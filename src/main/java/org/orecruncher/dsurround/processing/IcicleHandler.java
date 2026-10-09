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
import net.minecraft.util.Mth;
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
import org.orecruncher.dsurround.processing.scanner.ScannerCadence;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Icicles under eaves while it snows: they grow out of the edges the drip
 * scanner already knows how to find, hang there for as long as the snow
 * lasts, occasionally let go on their own, and when the snow stops they
 * MELT - shrinking back toward the roof edge while the handler rolls each
 * one's drop dice every ten seconds and sprinkles random drips (a single
 * drop, a burst, or a stream) off the melting tips. A rolled icicle falls,
 * shatters on the ground with a crack sized to its tier, and a replacement
 * grows from a random rim of the last scan.
 *
 * <p>Persistence is bookkeeping, not state: an icicle lives as long as its
 * point survives and the player stays near. Walk away and it is discarded;
 * walk back and the scan finds the same edge again - and because shape,
 * placement and growth delay all derive from a STABLE hash of the position,
 * the same eave always grows the same icicles.</p>
 *
 * <p>The size curve runs on the GLOBAL precipitation event clock, calibrated
 * by the event's own base intensity (its rolled peak, fixed at event start):
 * at a base of 0.5 the curve's peak reaches the middle of five tiers after
 * three game hours (the maintainer's anchor), and the scale is inverse to
 * the intensity - twice the rain fills an eave in half the time.</p>
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
    /** How often the staleness/distance pass runs. TWO ticks: a broken roof
     * block must read as a fall almost immediately (the live test's "it
     * hesitates first" - at 10 the wait was half a second). */
    private static final int STALE_CHECK_TICKS = 2;
    /** Mean interval between self-drops, GLOBAL across all live icicles (~80s): one lets go now and then, not a shower. */
    private static final int SELF_DROP_MEAN_TICKS = 1600;
    /** The size curve's anchor: at a base intensity of 0.5, the peak reaches
     * the middle of five tiers after 3000 ticks (three game hours - the
     * maintainer's example). Other intensities scale the clock inversely. */
    private static final long EVENT_PEAK_TICKS_AT_HALF = 3000L;
    /** Five size tiers under the duration curve. */
    private static final int TIER_COUNT = 5;
    /** Three shape textures; the hash picks, so a rim keeps its character. */
    private static final int SHAPE_COUNT = 3;
    /** After the snow stops: the drop lottery cadence and odds (per icicle). */
    private static final int DROP_ROLL_INTERVAL = 200;
    private static final float DROP_ROLL_CHANCE = 0.03F;
    /** Melt drips spawn ABOVE the tip by this much: the drop's own half
     * height (its top, not its center, touches the tip) plus the drip
     * animation's arithmetic - the hang phase sinks 0.0012/tick over a 40
     * tick hang (0.048) and the shrinking tip rises ~0.019 in the same
     * window (a tier-4 knife), so 0.045 + 0.048 + 0.019 ~= 0.11 keeps the
     * drop against the tip for its whole hang. */
    private static final double MELT_DRIP_Y_OFFSET = 0.11D;
    /** Per-tick chance that ONE melting icicle drips (expected every ~12s).
     *  The maintainer's ruling: the melt should read slower than the growth,
     *  drips included - 240 keeps the expected number of drips over a full
     *  melt the same (3600/240 = 15, was 2400/160) while the melt itself is
     *  50% longer. */
    private static final int DRIP_ROLL_ONE_IN = 240;
    /** Melt drips are the drip layer's own small drops - DripHandler
     * .icicleMeltDrop plays the whole chain at melt size and volume. */
    /** Sound range for the crack - scaled further by the size tier. */
    private static final float MIN_VOLUME = 0.45F;
    private static final float MAX_VOLUME = 0.9F;
    private static final float MIN_PITCH = 0.85F;
    private static final float MAX_PITCH = 1.15F;
    /** After an icicle leaves a rim, the rim stays bare for this long
     * (~30s): instant re-growth at the same spot reads as the icicle being
     * lifted back up instead of as a new icicle. */
    private static final long RIM_COOLDOWN_TICKS = 600L;

    private final DripHandler dripHandler;
    private final IAudioPlayer audioPlayer;
    private final ISoundLibrary soundLibrary;
    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private final Map<Long, Attached> attached = new HashMap<>();
    private final List<PendingDrip> melts = new ArrayList<>();
    private final Map<Identifier, ISoundFactory> factories = new HashMap<>();
    /** The last scan's eave candidates - the pool replacements draw from. */
    private final ObjectArray<DripScanner.DripPoint> lastPool = new ObjectArray<>();
    /** Rims that just shed an icicle, until when they may not re-grow: an
     * icicle falling and a fresh one instantly growing at the same spot read
     * as one icicle being "lifted back up" (the live test's report). */
    private final Map<Long, Long> rimCooldown = new HashMap<>();

    private boolean wasSnowing = false;

    /**
     * Round 12: when the melt began, and how far it has got. The melt used to be a
     * one-shot too - everything attached at the moment the snow stopped was told to
     * start melting, and the handler then stopped looking entirely. Walk up to a house
     * ten minutes later and its eaves were bare, while the ones behind you were stubs.
     * The melt is world-wide, so keep sweeping until it is over and hand every icicle
     * found the melt elapsed so far.
     */
    private long meltStartTick = -1L;
    /** Melt elapsed handed to an icicle spawned this tick; 0 while it is snowing. */
    private int meltAgeForNew = 0;
    /** World-wide precipitation event clock: when the sky last OPENED. */
    private boolean wasRaining = false;
    private long precipStartTick = 0L;
    /** The event's base intensity (its rolled peak), read once per event. */
    private float eventBaseIntensity = 0.5F;
    private boolean baseCaptured = false;
    // NOT Long.MIN_VALUE: tick - MIN_VALUE overflows to negative, so
    // `tick - lastScan >= SCAN_TICKS` never fired and the layer sat silent
    // through the whole first live test. -1000 means "due immediately".
    private long lastScan = -1000L;
    /** Where the last scan was centred, for the walk test. Null until the first scan. */
    private BlockPos lastScanPos;
    /** Re-scan sooner than the period when the player walks this far (4 blocks, squared). */
    private static final double RESCAN_MOVE_SQ = 16.0D;
    private long lastStaleCheck = -1000L;
    private long lastEmptyLog = -1000L;
    private long lastDropRoll = -1000L;
    /** Consecutive tick failures - see the catch in {@link #onTick}. */
    /** Round 12: one status line every five seconds. The flip line below only speaks when the
     * gate moves, so a layer quietly producing nothing prints nothing at all - which is the
     * state a bug report always arrives in. */
    private static final int DIAG_INTERVAL_TICKS = 100;
    private long lastDiagTick = -1000L;

    private int failures = 0;
    /** BUG-3: a dimension switch leaves `client.player` perfectly alive, so
     * nothing else noticed the old world's bookkeeping was still here. The
     * vanilla particle engine empties its queues WITHOUT marking the cleared
     * particles removed, so `isDead()` could never report them either. */
    private Level lastWorld;

    public IcicleHandler(final Configuration config, final IModLog logger, final DripHandler dripHandler,
                         final IAudioPlayer audioPlayer, final ISoundLibrary soundLibrary,
                         final ITickCount tickCount) {
        super("Icicles", config, logger);
        this.dripHandler = dripHandler;
        this.audioPlayer = audioPlayer;
        this.soundLibrary = soundLibrary;
        this.tickCount = tickCount;
        // The icicle layer registers on the tick-end bus directly, exactly like
        // the drip layer: Handlers resolves this class WITHOUT adding it to its
        // own tick list (that is what its register() is for), so a handler that
        // only ever calls process() sits constructed but never called - the
        // live test's "no icicles at all", with the mod otherwise clean.
        ClientState.TICK_END.register(this::onTick);
        // BUG-3: the tick bus was the ONLY thing this layer had - Handlers
        // resolves this class WITHOUT adding it to its own handler list (see
        // Handlers.init), so disconnect0() never ran and nothing ever cleaned
        // up: live icicles and their rims survived into the next world, where
        // the stale coordinates blocked every regrowth at those spots.
        ClientState.ON_DISCONNECT.register(client -> this.clear());
        // 碎冰声改「直播」：冰锥真正碎裂的那一刻由 IcicleParticle.shatter()
        // 直接回调这里播放。不再预约落点时刻，重力/距离改多少次都不会对不上。
        IcicleParticle.setShatterListener(this::playShatter);
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
            // BUG-8: `attached` is not the whole layer - a release can leave
            // melting drips queued and rims on cooldown. With nothing attached
            // the old test skipped clear() AND stopped consuming the queues,
            // so re-enabling the option replayed last session's leftovers.
            if (!this.attached.isEmpty() || !this.melts.isEmpty() || !this.rimCooldown.isEmpty())
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

        if (this.lastWorld != world) {
            if (this.lastWorld != null)
                this.clear();
            this.lastWorld = world;
        }
        // BUG-4: nothing ever dropped an expired rim out of this map - every
        // eave that ever shed an icicle kept an entry forever, and its key (a
        // packed BlockPos) means the same across worlds.
        if (!this.rimCooldown.isEmpty())
            this.rimCooldown.entrySet().removeIf(e -> e.getValue() <= tick);

        // The size curve's clock: ONE world-wide precipitation event, from the
        // moment the sky opens. Rain and snow share it, and walking between
        // biomes cannot reset it. Its SPEED is calibrated by the event's base
        // intensity (the rolled peak, constant per event): read once per event.
        final boolean raining = world.isRaining();
        if (raining && !this.wasRaining) {
            this.precipStartTick = tick;
            this.baseCaptured = false;
        }
        this.wasRaining = raining;
        if (raining && !this.baseCaptured) {
            final float rolled = PrecipitationIntensity.peak();
            if (rolled > 0F) {
                this.eventBaseIntensity = rolled;
                this.baseCaptured = true;
            }
        }

        // Column-STABLE snow gate. PrecipitationIntensity.phase() answers for
        // the player's own block column - its heightmap check flips to NONE
        // the moment the player steps under an eave or a tree. What the
        // icicles need is "is it snowing in this biome": vanilla says it is
        // precipitating, the biome sheds snow, and the temperature is under
        // the snow threshold - no per-column terms.
        final boolean snowing = raining
                && PrecipitationIntensity.potentialPhase(pos) == Biome.Precipitation.SNOW
                && SeasonManager.HANDLER.isSnowTemperature(pos);
        if (snowing != this.wasSnowing)
            this.logger.info("[IcicleHandler] snow=%s (raining=%s potential=%s ambient=%.2f base=%.2f) attached=%d near (%d,%d,%d)",
                    snowing, raining, PrecipitationIntensity.potentialPhase(pos),
                    PrecipitationIntensity.ambient(), this.eventBaseIntensity, this.attached.size(),
                    pos.getX(), pos.getY(), pos.getZ());
        // Round 12: the same terms as the snow line, so "neither layer is producing" reads as
        // one cause in the log instead of two mysteries. attached=/MAX_ICICLES is the number to
        // watch: a roster pinned at the ceiling with a storm still going means the eaves stopped
        // being re-read, not that the snow stopped.
        if (tick - this.lastDiagTick >= DIAG_INTERVAL_TICKS) {
            this.lastDiagTick = tick;
            this.logger.info("[IcicleHandler] gate snowing=%s raining=%s potential=%s snowTemp=%s"
                            + " attached=%d/%d sinceScan=%d near (%d,%d,%d)",
                    snowing, raining, PrecipitationIntensity.potentialPhase(pos),
                    SeasonManager.HANDLER.isSnowTemperature(pos), this.attached.size(), MAX_ICICLES,
                    tick - this.lastScan, pos.getX(), pos.getY(), pos.getZ());
        }

        if (snowing) {
            // Resume edge: a biome-border flap's drop schedule is undone - the
            // eave only sheds when the snow STAYS over. (The event clock above
            // needs no resume care: world-wide rain cannot be flipped by a
            // border walk.)
            if (!this.wasSnowing) {
                // Snow is back: the melt is over, and its clock is not evidence any more.
                this.meltStartTick = -1L;
                for (final Attached a : this.attached.values()) {
                    a.dropAt = -1;
                    // BUG-5: the melt was never cancelled. Snow that came back
                    // after a gap found its old icicles still shrinking, and
                    // they melted away in the middle of the snowfall.
                    a.particle.stopMelting();
                }
            }
            // The period, or a walk that has taken the player somewhere the last scan did
            // not cover. Until the move trigger was added this layer only ever re-scanned on
            // its sixty-tick clock, so walking up to a new house could leave its eaves bare
            // for three seconds - the icicle layer "appears late" next to the drip layer,
            // which has always had the walk test. Rationed by the shared floor (the box is
            // the expensive one, 33x33x27) and never allowed to bypass it.
            // (the sweep itself now lives below - it has to run during the melt as well)
            // The occasional lone dropout while it keeps snowing.
            if (!this.attached.isEmpty() && this.random.nextInt(SELF_DROP_MEAN_TICKS) == 0) {
                final Attached pick = this.pickAny();
                if (pick != null)
                    this.scheduleDrop(pick, tick + this.random.nextInt(40));
            }
        } else if (this.wasSnowing) {
            // Snow's over: everything enters the MELT - slow shrink toward the
            // root - while the drop lottery below decides who falls first.
            // Recorded whether or not anything is attached: the eaves the player has not
            // reached yet are melting too, and they are the whole reason for the sweep
            // below. Without a start tick an icicle found later would begin its own fresh
            // three minute shrink and outlive every one around it.
            if (this.meltStartTick < 0L)
                this.meltStartTick = tick;
            for (final Attached a : this.attached.values())
                a.particle.startMelting();
        }

        // The sweep. It used to sit inside `if (snowing)` alone, so the melt never looked
        // anywhere new - the maintainer's "到新的地方不会刷新". Keep going while there is
        // ice left to show, and see spawnOne for the elapsed hand-off.
        final boolean melting = this.meltStartTick >= 0L
                && tick - this.meltStartTick < IcicleParticle.MELT_TICKS;
        this.meltAgeForNew = melting ? (int) (tick - this.meltStartTick) : 0;
        if (snowing || melting) {
            final boolean moved = this.lastScanPos == null
                    || this.lastScanPos.distToCenterSqr(pos.getX(), pos.getY(), pos.getZ()) >= RESCAN_MOVE_SQ;
            final boolean movedNow = moved
                    && tick - this.lastScan >= ScannerCadence.MOVE_RESCAN_MIN_TICKS;
            if (tick - this.lastScan >= SCAN_TICKS || movedNow) {
                this.lastScan = tick;
                this.lastScanPos = pos.immutable();
                this.scanAndAttach(world, pos, tick);
            }
        }

        // Fire scheduled drops (self-drops during the snow).
        //
        // The replacement is spawned AFTER the walk, not from inside it. Calling
        // reAttach here used to end in spawnOne's attached.put, i.e. a structural
        // modification of the map this iterator is walking - and a put after
        // it.remove() still bumps the modCount, so the next element threw
        // ConcurrentModificationException. The handler's guard caught it and logged
        // an ERROR, which is how it was found, but the tick's remaining melt and
        // staleness work was skipped every time it happened. One replacement per
        // release is kept exactly as before; only the moment changes, and by then
        // the fallen rim is already free - which is what the no-replacement-at-the-
        // fallen-rim rule wants anyway.
        int replacements = 0;
        for (final Iterator<Attached> it = this.attached.values().iterator(); it.hasNext(); ) {
            final Attached a = it.next();
            if (a.particle.isDead()) {
                it.remove();
            } else if (a.dropAt >= 0 && tick >= a.dropAt) {
                a.dropAt = -1;
                this.release(a, tick);
                it.remove();
                replacements++;
            }
        }
        for (int i = 0; i < replacements; i++)
            this.reAttach(world, tick, snowing);

        // The after-snow lottery: every ten seconds each melting icicle rolls
        // a ten percent chance to let go. Losers keep shrinking; an icicle
        // that never rolls a fall melts away entirely (the particle handles
        // that itself).
        if (!snowing && !this.attached.isEmpty() && tick - this.lastDropRoll >= DROP_ROLL_INTERVAL) {
            this.lastDropRoll = tick;
            for (final Iterator<Attached> it = this.attached.values().iterator(); it.hasNext(); ) {
                final Attached a = it.next();
                if (a.particle.isDead()) {
                    it.remove();
                    continue;
                }
                if (this.random.nextFloat() < DROP_ROLL_CHANCE) {
                    this.release(a, tick);
                    it.remove();
                }
            }
        }

        // Staleness and distance, on their own clock - TWO ticks: a broken
        // roof block must read as a fall almost immediately.
        if (tick - this.lastStaleCheck >= STALE_CHECK_TICKS) {
            this.lastStaleCheck = tick;
            for (final Iterator<Attached> it = this.attached.values().iterator(); it.hasNext(); ) {
                final Attached a = it.next();
                if (a.particle.isDead()) {
                    it.remove();
                    continue;
                }
                if (a.point.point().distSqr(pos) > KEEP_DIST_SQ) {
                    // Round 11: remember how far along it was. Walking out of
                    // range discards the particle, and with it the growth clock,
                    // so coming back used to replay all twenty seconds of growth
                    // from zero - "it started over" was exactly that. The tier is
                    // not what was lost (that comes off the event clock and is
                    // the same on return); the elapsed growth was.
                    this.rememberGrowth(a);
                    a.particle.discard();
                    it.remove();
                    continue;
                }
                // Its support is gone (block broken) - it falls NOW. No
                // replacement: the live test read the pool re-spawn on the
                // same eave as "the icicle was lifted back up". The next scan
                // refills the eave wherever it still qualifies.
                if (!DripScanner.isStillHeld(world, a.point)) {
                    this.release(a, tick);
                    it.remove();
                }
            }
        }

        // Melt drips: the melting icicles leak - a single drop, a burst, or a
        // short stream, from the icicle's own TIP (the particle position).
        this.rollDrips(tick);
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
        // A fresh world (or the snow being over) is a fresh start for growth too.
        this.grown.clear();
        this.melts.clear();
        this.lastPool.clear();
        this.wasSnowing = false;
        this.wasRaining = false;
        this.precipStartTick = 0L;
        this.baseCaptured = false;
        this.lastScan = -1000L;
        this.lastStaleCheck = -1000L;
        // BUG-4: without this the cooldown bookkeeping outlived the world it
        // belonged to - and every entry in it blocked a rim forever.
        this.rimCooldown.clear();
        // Every world-bound clock too: otherwise the new world's first melt
        // dice roll inherits the old world's cadence.
        this.lastDropRoll = -1000L;
        this.lastEmptyLog = -1000L;
    }

    // ---- growth -------------------------------------------------------------

    /**
     * Finds eave edges with the drip scanner and grows an icicle at each new
     * one. Shape, rim placement and growth delay come from one stable hash of
     * the point; the SIZE comes from the event curve.
     */
    private void scanAndAttach(final Level world, final BlockPos center, final long tick) {
        // Density rides the precipitation intensity, the drip budget's way.
        // Round 12: during the melt the roster has to wind DOWN, not hold at the storm's
        // number - the further into the melt, the fewer eaves still carry anything, and
        // by the end none do. Left unscaled, walking around during the melt would refill
        // every rim it found and the layer would never finish.
        final int fullWant = DENSITY_BASE + Math.round(DENSITY_SPAN * PrecipitationIntensity.ambient());
        final float meltLeft = this.meltAgeForNew > 0
                ? 1F - this.meltAgeForNew / (float) IcicleParticle.MELT_TICKS : 1F;
        final int want = Math.min(MAX_ICICLES, Math.round(fullWant * meltLeft));
        if (this.attached.size() >= want)
            return;
        int candidates = 0;
        int spawned = 0;
        int failed = 0;
        final int[] shapes = new int[SHAPE_COUNT];
        final ObjectArray<DripScanner.DripPoint> found = new ObjectArray<>(64);
        // scanAll rather than scan(..., true): same superset, but it is the one the drip
        // layer reads from this tick, so the box is walked once for both layers.
        for (final DripScanner.DripPoint point : DripScanner.scanAll(world, center)) {
            candidates++;
            found.add(point);
            if (this.attached.size() >= want)
                break;
            final Long key = point.point().asLong();
            if (this.attached.containsKey(key))
                continue;
            final Long coolingUntil = this.rimCooldown.get(key);
            if (coolingUntil != null && tick < coolingUntil)
                continue;
            final long hash = stableHash(point.point());
            final int growthDelay = (int) ((hash >>> 8) % 200);
            // Build from the same hash as the shape, so a rim keeps its bodies too.
            final int shapeForBuild = (int) (hash % SHAPE_COUNT);
            final int build = buildFor(shapeForBuild, hash);
            final double[] hang = icicleSpot(point, hash, maxOffsetFor(build));
            // The root sits just under the roof's measured underside - the
            // same formula the drips use, minus their hang-off gap: an icicle
            // grows FROM the surface, not 0.09 below it.
            final double rootY = point.roof().getY() + point.undersideY() - 0.02D;
            // How much room is under the root. A long icicle in a low doorway used to
            // grow straight through the floor; the tier is cut back to what fits.
            final float room = roomUnder(world, hang[0], rootY, hang[1]);
            final int shape = this.spawnOne(point, hang[0], rootY, hang[1], hash, tick, growthDelay,
                    build, room);
            if (shape >= 0) {
                spawned++;
                shapes[shape]++;
            } else {
                failed++;
            }
        }
        // The replacement pool: what this scan says can hold an icicle.
        if (candidates > 0) {
            this.lastPool.clear();
            this.lastPool.addAll(found);
        }
        // The two log lines that keep a silent first test readable.
        if (candidates == 0 && this.attached.isEmpty() && tick - this.lastEmptyLog >= 100) {
            this.lastEmptyLog = tick;
            final StringBuilder hist = new StringBuilder();
            for (int i = 0; i < DripScanner.STAT_COUNT; i++)
                hist.append(' ').append(DripScanner.STAT_NAMES[i]).append('=').append(DripScanner.STATS[i]);
            this.logger.info("[IcicleHandler] snowing but the scan found 0 eave candidates near (%d,%d,%d)%s",
                    center.getX(), center.getY(), center.getZ(), hist);
        } else if (spawned > 0) {
            this.logger.info("[IcicleHandler] growing %d new icicles (cone=%d knife=%d wedge=%d, %d attached, %d failed) near (%d,%d,%d)",
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
    /** @param maxOffset how far from the block centre this icicle may hang and still
     *         fit - see {@link IcicleParticle#WIDTH_MAX}. */
    private static double[] icicleSpot(final DripScanner.DripPoint point, final long hash,
                                       final double maxOffset) {
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
            // Round 12: the lean used to be a flat 0.42..0.47, which is fine for a needle
            // and hangs a stout icicle's crossing out past the block edge. Pull it in to
            // what this body actually has room for. The SPREAD along the edge below is not
            // touched - it is +-0.336, inside every build's limit - so a small icicle
            // still lands anywhere along the rim, which is the randomness worth keeping.
            final double lean = Math.min(maxOffset, 0.42D + slice(hash, 24, 5) * 0.01D);
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
     * going - his anchor, a base intensity of 0.5, peaks on the middle of
     * five tiers after three game hours. Heavier events fill an eave faster:
     * the clock scales inversely to the event's base intensity. A gaussian
     * around the drifting peak keeps neighboring tiers common and both
     * extremes rare.
     */
    private int rollTier(final long tick) {
        final float base = Math.max(0.15F, this.eventBaseIntensity);
        final float peakTicks = Mth.clamp(1500F / base, 600F, 12000F);
        final float peak = Math.min(TIER_COUNT - 1F,
                Math.max(0F, (tick - this.precipStartTick) / peakTicks * (TIER_COUNT - 1F)));
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
     * tier from the event curve, the growth delay from the caller
     * (hash-spread on first fill, zero on an immediate replacement).
     *
     * @return the shape index that was spawned, or -1 on failure
     */
    private int spawnOne(final DripScanner.DripPoint point, final double hangX, final double rootY,
                         final double hangZ, final long hash, final long tick, final int growthDelay,
                         final int build, final float room) {
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return -1;
        final int shape = (int) (hash % SHAPE_COUNT);
        // Round 12: cut the tier back to what actually fits above the ground. room <= 0
        // means "not measured" (a hand-summoned icicle) and leaves the roll alone.
        int tier = rollTier(tick);
        if (room > 0F) {
            final float gain = IcicleParticle.LENGTH_GAIN[build] * SHAPE_HEIGHT[shape];
            while (tier > 0 && IcicleParticle.TIER_HEIGHTS[tier] * gain > room)
                tier--;
        }
        final Particle particle = ((MixinParticleManager) pm)
                .dsurround_createParticle(typeFor(shape), hangX, rootY, hangZ,
                        tier, growthDelay, build);
        if (particle == null)
            return -1;
        pm.add(particle);
        // Round 11: pick the growth back up where this eave left it. The key is
        // the same position, so the hash-spread delay is the same too, and the
        // clock we stored is directly comparable - no replay, no double delay.
        final Integer prior = this.grown.remove(point.point().asLong());
        if (prior != null)
            ((IcicleParticle) particle).resumeGrowthAt(prior);
        // Round 12: this one is joining a melt that started before it existed. Without
        // this it grew to full length and then took three minutes of its own to go.
        if (this.meltAgeForNew > 0)
            ((IcicleParticle) particle).startMeltingAt(this.meltAgeForNew);
        this.attached.put(point.point().asLong(), new Attached(point, (IcicleParticle) particle, hangX, hangZ, tier));
        return shape;
    }

    /**
     * Round 11: growth elapsed per eave, kept while the icicle is out of range.
     * Keyed by the drip point's position; bounded, because it only ever needs to
     * hold the eaves just walked away from, and cleared whenever the layer is
     * emptied (a new world or the snow being over is a fresh start anyway).
     */
    private final java.util.Map<Long, Integer> grown = new java.util.HashMap<>();
    private static final int GROWN_CAP = 512;

    private void rememberGrowth(final Attached a) {
        if (this.grown.size() >= GROWN_CAP)
            this.grown.clear();
        this.grown.put(a.point.point().asLong(), a.particle.growthClock());
    }

    /** Shape to particle type - a rim keeps its character across regrowths. */
    /** How many body builds the hash may roll - must match IcicleParticle.WIDTH_MAX. */
    private static final int WIDTH_BUILDS = 3;

    /**
     * Height factor per shape, mirroring the three {@code IcicleParticle.Provider}s
     * (cone 0.7, knife 1.0, wedge 0.45). Duplicated on purpose: the handler needs the
     * length to cut a tier back before the particle exists, and the providers are
     * registered on the other side of the mod. **Change both together.**
     */
    private static final float[] SHAPE_HEIGHT = { 0.7F, 1.0F, 0.45F };

    /**
     * How far from the block centre an icicle of this build may hang. The crossed quads
     * lie on the block diagonal, so a quad of width w projects w/2*cos45 onto each axis;
     * what is left of the half block is the room the spot has to move in.
     */
    private static double maxOffsetFor(final int build) {
        final float wMax = IcicleParticle.WIDTH_MAX[Mth.clamp(build, 0, IcicleParticle.WIDTH_MAX.length - 1)];
        return 0.5D - 0.02D - (wMax / 2.0D) * 0.7071067811865476D;
    }

    /**
     * Clear air below the root, in blocks. Counted from the root down, because that is
     * the direction an icicle grows.
     */
    private static float roomUnder(final Level world, final double x, final double rootY, final double z) {
        final int bx = Mth.floor(x);
        final int bz = Mth.floor(z);
        int by = Mth.floor(rootY);
        float room = 0F;
        for (int i = 0; i < 4; i++) {
            final BlockPos p = new BlockPos(bx, by - i, bz);
            if (world.isOutsideBuildHeight(p.getY()))
                break;
            if (!world.getBlockState(p).isAir())
                break;
            room += 1F;
        }
        return room;
    }

    /**
     * Round 12: the body build, weighted per shape. Stout is the rare one and the cone
     * carries most of it - a fat knife reads as a slab, a fat wedge as a blob, while a
     * fat cone is simply a proper icicle. The maintainer asked for exactly this: fewer
     * stouts, and cone first. The roll is still the position hash, so a rim keeps its
     * bodies across regrowths.
     */
    private static int buildFor(final int shape, final long hash) {
        final int roll = (int) slice(hash, 32, 100);
        return switch (shape) {
            case 0 -> roll < 25 ? 2 : (roll < 60 ? 1 : 0);   // cone: stout 25%, mid 35%
            case 1 -> roll < 8 ? 2 : (roll < 48 ? 1 : 0);    // knife: stout 8%
            default -> roll < 12 ? 2 : (roll < 52 ? 1 : 0);  // wedge: stout 12%
        };
    }

    private static SimpleParticleType typeFor(final int shape) {
        return switch (shape) {
            case 0 -> ModParticles.ICICLE_CONE;
            case 1 -> ModParticles.ICICLE_KNIFE;
            default -> ModParticles.ICICLE_WEDGE;
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
     * Lets go: the particle switches to its ballistic fall. The shatter sound
     * needs no booking - the particle plays it itself from shatter().
     */
    private void release(final Attached a, final long tick) {
        a.particle.release();
        // Cooldown the rim: no re-growth here for a while.
        this.rimCooldown.put(a.point.point().asLong(), tick + RIM_COOLDOWN_TICKS);
        // BUG-7: drops already queued for this source kept spawning off an
        // icicle that was on its way down - the tip was falling, but the water
        // still hung there for another tick or twenty.
        this.melts.removeIf(m -> m.source() == a.particle);
        // The melt drops hanging on this rim fall with their ice - the drip
        // layer's own broken-block release, pointed at this rim.
        this.dripHandler.releaseHangingAt(a.point);
        // One INFO line per release: a break is player-initiated and rare,
        // and this pins the released particle's exact tip - the "lifted up"
        // report needs the numbers to chase.
        this.logger.info("[IcicleHandler] released tier=%d tip=(%.2f,%.2f,%.2f) root=%.2f fall=%d",
                a.tier, a.particle.tipX(), a.particle.tipY(), a.particle.tipZ(),
                a.point.roof().getY() + a.point.undersideY(), a.point.fall());
    }

    /**
     * The maintainer's rule: whenever an icicle goes somewhere, a fresh one
     * immediately starts growing from zero - the eave never empties while the
     * snow lasts. NOT at the fallen icicle's own rim: that is how a broken
     * roof block looped grow-and-drop forever in the live test (the rim stops
     * being valid the moment its block is gone, and forcing the replacement
     * right back onto it re-fails every staleness pass). The replacement
     * draws a RANDOM rim from the last scan's candidate pool instead,
     * skipping rims that are gone or already occupied. Only while it IS
     * snowing: after the stop, the melt handles the wind-down.
     */
    private void reAttach(final Level world, final long tick, final boolean snowing) {
        if (!snowing || this.lastPool.isEmpty())
            return;
        final List<DripScanner.DripPoint> usable = new ArrayList<>(this.lastPool.size());
        for (final DripScanner.DripPoint point : this.lastPool) {
            final Long key = point.point().asLong();
            if (this.attached.containsKey(key))
                continue;
            final Long coolingUntil = this.rimCooldown.get(key);
            if (coolingUntil != null && tick < coolingUntil)
                continue;
            if (!DripScanner.isStillHeld(world, point))
                continue;
            usable.add(point);
        }
        if (usable.isEmpty())
            return;
        final DripScanner.DripPoint pick = usable.get(this.random.nextInt(usable.size()));
        final long hash = stableHash(pick.point());
        final int build = buildFor((int) (hash % SHAPE_COUNT), hash);
        final double[] hang = icicleSpot(pick, hash, maxOffsetFor(build));
        this.spawnOne(pick, hang[0], pick.roof().getY() + pick.undersideY() - 0.02D, hang[1], hash, tick, 0,
                build, roomUnder(world, hang[0], pick.roof().getY() + pick.undersideY() - 0.02D, hang[1]));
    }

    /**
     * The ice crack, played from {@link IcicleParticle#shatter()} - the tick
     * the tip actually breaks, whatever the fall turns out to be. Nothing has
     * to agree with the particle's integration any more: the sound cannot lead
     * or trail the visual, the extra "+2 tick" cushion the scheduler needed is
     * gone, and changing the gravity below can never desync anything again.
     */
    private void playShatter(final double x, final double y, final double z, final int tier) {
        final ISoundFactory factory = this.factory(SHATTER);
        if (factory == null)
            return;
        final float volume = (0.40F + 0.12F * tier) + this.random.nextFloat() * 0.10F;
        this.audioPlayer.play(factory.createAtLocation(x, y, z, Math.min(MAX_VOLUME, volume)));
    }

    /**
     * The melt's water: melting icicles leak at random, and every style the
     * maintainer named rides the same pending list - a single drop, a burst
     * of three, or a stream (a drop every few ticks for a couple of
     * seconds). The pending entry holds the ICICLE, not coordinates: the
     * drop spawns at the tip's LIVE position, so a shrinking icicle never
     * leaves its drop floating in the air below where the tip used to be.
     * The drops themselves are the DRIP LAYER's - its spawnDrop + landing
     * plip chain plays them, whole.
     */
    private void rollDrips(final long tick) {
        for (final Attached a : this.attached.values()) {
            if (!a.particle.isMelting() || a.particle.isDead())
                continue;
            if (this.random.nextInt(DRIP_ROLL_ONE_IN) != 0)
                continue;
            final int style = this.random.nextInt(10);
            if (style < 5) {
                // single drop
                this.melts.add(new PendingDrip(a.particle, a.point, tick));
            } else if (style < 8) {
                // burst of three
                for (int i = 0; i < 3; i++)
                    this.melts.add(new PendingDrip(a.particle, a.point, tick + i * 3));
            } else {
                // stream: a drop every few ticks for ~2.5s
                for (int i = 0; i < 10; i++)
                    this.melts.add(new PendingDrip(a.particle, a.point, tick + i * 8));
            }
        }
    }

    /**
     * Spawns the queued melt drips through the drip layer's public entry: a
     * drop hangs at the icicle's LIVE tip (read at spawn time - the shrink
     * keeps moving it), falls the eave's travel, and lands with the layer's
     * own small plip.
     */
    private void spawnDueMelts(final Level world, final long tick) {
        final Iterator<PendingDrip> it = this.melts.iterator();
        while (it.hasNext()) {
            final PendingDrip melt = it.next();
            if (melt.spawnAt() > tick)
                continue;
            it.remove();
            final IcicleParticle src = melt.source();
            if (src.isDead())
                continue;
            this.dripHandler.icicleMeltDrop(world, melt.point(),
                    src.tipX(), src.tipY() + MELT_DRIP_Y_OFFSET, src.tipZ());
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
                        + this.melts.size() + " drips pending");
    }

    /** One live icicle. {@code dropAt} carries a scheduled end (-1 = none yet). */
    private static final class Attached {
        final DripScanner.DripPoint point;
        final IcicleParticle particle;
        final double hangX;
        final double hangZ;
        final int tier;
        long dropAt = -1;

        Attached(final DripScanner.DripPoint point, final IcicleParticle particle,
                 final double hangX, final double hangZ, final int tier) {
            this.point = point;
            this.particle = particle;
            this.hangX = hangX;
            this.hangZ = hangZ;
            this.tier = tier;
        }
    }

    /** A queued melt drop, hanging off its (still shrinking) source icicle. */
    private record PendingDrip(IcicleParticle source, DripScanner.DripPoint point, long spawnAt) {
    }
}
