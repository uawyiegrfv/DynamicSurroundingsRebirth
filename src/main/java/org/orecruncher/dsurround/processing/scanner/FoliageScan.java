package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.orecruncher.dsurround.processing.FoliageProfile;
import org.orecruncher.dsurround.processing.FoliageProfileCache;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;

/**
 * Where the foliage-drip layers can attach: a plain volume walk around the
 * player (the drip scanner's framework, a different predicate - plants are
 * not overhangs, so this is a sibling of {@link DripScanner}, not a branch of
 * it). The box is deliberately small and never leaves the loaded chunks the
 * client is guaranteed to hold: at the configured range of 16 it is 33x33x14,
 * about 15k block reads a pass, against the eave scanner's 29k. The 14 is
 * {@link #UP_RANGE} plus {@link #DOWN_RANGE} plus one: round 49 widened it from
 * seven (4 up / 2 down) to fourteen (7/6), and the extra reach goes to the upper
 * edge, because on a slope it is the plants above the player that the old band
 * left out - the horizontal fan cannot reach them, it is horizontal.
 *
 * <p>The predicate chain, in cost order:</p>
 * <ol>
 *   <li>air / fluid - the bulk of the box, one read each;</li>
 *   <li>candidate set - a class/tag test, still no model access (bushes,
 *       flowers, crops past the sprout stages, saplings; leaves excluded -
 *       the eave scanner's canopy branch already drips from leaf bottoms,
 *       and doubling it up would be noise);</li>
 *   <li>sky - RAIN asks the eave scanner's full triple (canSeeSky +
 *       heightmap + biome RAIN: "is rain landing here"); DEW asks only
 *       canSeeSky + heightmap ("is the sky open") - whether a biome CAN rain
 *       says nothing about whether dew forms. Leaves and glass both sit in
 *       the MOTION_BLOCKING heightmap, so covered plants pass neither.</li>
 *   <li>profile - the model alpha mask must have produced anchors; built
 *       once per model and cached.</li>
 * </ol>
 *
 * <p>Sky access is asked at SCAN time only, exactly once per spot; the
 * handlers' staleness passes never re-ask the heightmap (a freshly loaded
 * chunk's heightmap lags - asking it there is the appears-and-vanishes
 * flicker the eave layer already paid for).</p>
 */
public final class FoliageScan {

    public enum Mode {
        /** Rain landing on the plant: full {@code rainReachesTop} triple. */
        RAIN,
        /** Morning dew: sky open, no biome precipitation term. */
        DEW,
        /**
         * Snow settling on the plant. Same sky test as DEW - "can snow reach
         * this blade" is exactly "is the sky open above it". The snow PHASE
         * and the cold-temperature gates are the handler's, asked per spot
         * (icicle lesson: the column's own phase, not the player's).
         */
        SNOW
    }

    /** Default horizontal reach (the config's foliageScanRange drives the real one). */
    public static final int RANGE_XZ = 10;

    /**
     * Round 12: a box spends a third of itself on ground behind the player, which is the
     * part nobody can see. These stretch the sweep along where the player is looking and
     * pull it in behind, as two half-ellipses joined at the player (front
     * {@link #FAN_FORWARD}, back {@link #FAN_BACK}, sideways {@link #FAN_SIDE}, all in
     * units of {@code range}). At the default 16 that reaches <b>26 blocks ahead and 19
     * to either side</b> while walking 94% of the columns the box did - so the coat now
     * starts twenty-odd blocks out for slightly LESS work than before.
     */
    private static final float FAN_FORWARD = 1.65F;
    private static final float FAN_SIDE = 1.19F;
    private static final float FAN_BACK = 0.50F;
    /**
     * Vertical reach above the player. Seven, deliberately one more than the reach
     * below: the window is centred on the player's own feet rather than on the
     * terrain, so on a slope the plants the old 4/2 band missed are the ones
     * <em>above</em> the player, and round 49's extra reach goes to that edge.
     */
    public static final int UP_RANGE = 7;
    /**
     * Vertical reach below the player.
     *
     * <p>Six. Round 12 had pulled this down to two - the reach below only matters while the player
     * is <em>above</em> the plants, and everything it covered had to be within the four blocks
     * above as well. Round 49 puts it back to six, on the maintainer's report that "from a
     * hillside, looking up or looking down, the plants get almost no snow": the horizontal fan
     * reaches twenty-odd blocks out while this band is centred on the player's own feet, so on a
     * slope the far end of the sweep leaves the band long before it leaves the radius. 7/6 is the
     * enlarged window that answers it. The price is arithmetic on a measurement: the sweep is
     * 33x33x7 -> 33x33x14, twice the block reads of the window it replaces, and the A/B that was
     * actually timed ran at 6/6 (13 layers) with a median of 1280us and a p90 of 1924us per scan -
     * so the shipped 14 layers should sit about 8% above that, near 1.4ms. Plants that fall
     * outside are not torn down - the handlers' staleness pass keeps them until the plant itself
     * goes - so a narrower window fills more slowly, it does not flicker.</p>
     */
    public static final int DOWN_RANGE = 6;

    // ---- round 48: the vertical window, instrumented ------------------------
    /**
     * The vertical-window switch. <b>Off in every normal run</b>, and while it is
     * off this class walks exactly the window it always does: no probe pass, no
     * stage clock read, and {@link #windowLine()} returns an empty string that
     * appends nothing to a handler's line. (Round 49 made the three shape counters
     * - columns, voxels, candidates - unconditional, because the slow-scan line
     * needs them in a normal run; see {@link #SLOW_SCAN_NANOS}.)
     *
     * <p>The maintainer's report is "from a hillside, looking up or looking down,
     * the plants get almost no snow". That is a question about the vertical
     * window and nothing else: {@link #UP_RANGE}/{@link #DOWN_RANGE} are a fixed
     * band around the player's own feet, while the horizontal fan reaches
     * twenty-odd blocks out - so on any slope the far end of the sweep leaves the
     * band long before it leaves the radius, and the yaw fan cannot help because
     * it is horizontal. Two things answer it, and neither is an argument:</p>
     * <ol>
     *   <li>counts and {@code System.nanoTime} timings per scan - columns,
     *       voxels, candidates, hits, and the two stages worth timing (the sky
     *       test and the profile lookup), so an A/B is a measurement;</li>
     *   <li>a MISS PROBE: the {@link #PROBE_BANDS} layers just outside the
     *       active window, walked with the same predicate chain and thrown away,
     *       so "what is the window leaving on the table" is a number.</li>
     * </ol>
     *
     * <p>Set from {@code -Ddsr.foliage.windowDiag=true} at class load, or by hand
     * from a debug command - it is volatile and read once per scan.</p>
     */
    public static volatile boolean WINDOW_DIAG = readFlag("dsr.foliage.windowDiag");

    /**
     * The A/B vertical window, as {@code up/down} from
     * {@code -Ddsr.foliage.window=6/6}. <b>-1 is "the shipped window"</b> - 7/6 as
     * of round 49 - and is what every normal run has; 0 is a legal reach (a flat
     * window), which is why -1 and not 0 is the sentinel. Read once per scan, so a
     * debug command can move it live, and moving it drops the rolling cost window
     * with it.
     */
    public static volatile int ALT_UP_RANGE = -1;
    public static volatile int ALT_DOWN_RANGE = -1;

    /** The last scan's shape and cost. Written only while {@link #WINDOW_DIAG} is on. */
    public static int lastColumns = 0;
    public static int lastVoxels = 0;
    public static int lastCandidates = 0;
    public static long lastSkyNanos = 0L;
    public static long lastProfileNanos = 0L;
    /** The window the last scan walked; recorded unconditionally, it is configuration, not a measurement. */
    public static int lastUp = UP_RANGE;
    public static int lastDown = DOWN_RANGE;
    public static int lastCenterX = 0;
    public static int lastCenterY = 0;
    public static int lastCenterZ = 0;
    public static boolean lastFan = false;
    public static float lastFx = 0F;
    public static float lastFz = 0F;

    /**
     * How many layers outside the active window the miss probe walks, per side.
     * Six, because that was the widest window the A/B switch was expected to take
     * (6/6) and the probe has to keep having something to say once a decision has
     * moved the edge out past it.
     */
    public static final int PROBE_BANDS = 6;
    /** Candidates one, two, ... layers past each edge, summed over every column of the sweep. */
    public static final int[] PROBE_UP = new int[PROBE_BANDS];
    public static final int[] PROBE_DOWN = new int[PROBE_BANDS];
    /** ...and how many of those were fully usable spots (sky open and the profile has anchors). */
    public static int probeUpOk = 0;
    public static int probeDownOk = 0;

    /**
     * The scans since the window last changed. A mean taken over a session that
     * has held two different windows is a number about nothing, which is why
     * {@link #applyWindowProperty} drops these.
     */
    public static int winScans = 0;
    public static long winSumNanos = 0L;
    public static long winMaxNanos = 0L;

    static {
        applyWindowProperty(System.getProperty("dsr.foliage.window"));
    }

    /**
     * {@code up/down} -> {@link #ALT_UP_RANGE}/{@link #ALT_DOWN_RANGE}. Anything
     * unreadable is ignored: a test knob must never be able to change what the
     * layer does by being misspelled.
     */
    public static void applyWindowProperty(final String spec) {
        if (spec == null)
            return;
        final int slash = spec.indexOf('/');
        if (slash <= 0)
            return;
        try {
            final int up = Integer.parseInt(spec.substring(0, slash).trim());
            final int down = Integer.parseInt(spec.substring(slash + 1).trim());
            // A knob that could ask for a whole-column walk would answer a
            // different question than the one being asked; 64 layers is already
            // nine times the shipped window.
            if (up < 0 || down < 0 || up + down > 64)
                return;
            ALT_UP_RANGE = up;
            ALT_DOWN_RANGE = down;
            resetWindowStats();
        } catch (final NumberFormatException ignored) {
            // Leave the shipped window alone - the safe way round.
        }
    }

    private static boolean readFlag(final String name) {
        try {
            final String v = System.getProperty(name);
            return v != null && (v.isEmpty() || Boolean.parseBoolean(v));
        } catch (final Throwable t) {
            return false;
        }
    }

    /** The window the next scan walks: the shipped one unless the A/B switch moved it. */
    public static int activeUp() {
        return ALT_UP_RANGE >= 0 ? ALT_UP_RANGE : UP_RANGE;
    }

    /** See {@link #activeUp()}. */
    public static int activeDown() {
        return ALT_DOWN_RANGE >= 0 ? ALT_DOWN_RANGE : DOWN_RANGE;
    }

    /** Drops the rolling A/B cost window. */
    public static void resetWindowStats() {
        winScans = 0;
        winSumNanos = 0L;
        winMaxNanos = 0L;
    }

    /** Trace switch - driven by the handlers from the logging config. */
    public static volatile boolean TRACE = false;

    /** Logged once per session when the shader switch suppresses the plant layers. */
    private static volatile boolean shaderSuppressionReported = false;

    /**
     * Whether the plant-attached layers may run right now.
     *
     * <p>A shader pack that makes plants sway moves the plant in its vertex shader - the geometry
     * the player sees is displaced, while a particle anchored to that plant stays where the scan
     * found it. There is no way for us to follow: the displacement lives in the pack's GLSL, the
     * pack does not publish its parameters, and our particles are not drawn through the plant's
     * program. So the only honest lever is to stop drawing them, and this is that lever - with the
     * eave layers deliberately left alone, because blocks do not sway.</p>
     *
     * <p>Off by default: plenty of packs do not sway plants at all, and silently removing three
     * features for every shader user would be a worse default than one config line. The startup
     * summary names the switch whenever a pack is in use.</p>
     */
    public static boolean plantLayersAllowed(final org.orecruncher.dsurround.Configuration config) {
        if (config == null || !config.weatherOptions.disableFoliageLayersWithShaders)
            return true;
        return !org.orecruncher.dsurround.compat.ModIds.shadersInUse();
    }

    /** One line, once, when the switch above actually takes the layers down. */
    public static void reportShaderSuppression(final org.orecruncher.dsurround.lib.logging.IModLog logger) {
        if (shaderSuppressionReported)
            return;
        shaderSuppressionReported = true;
        logger.info("Plant-attached layers (leaf drips / dew / snow) are switched off because a shader"
                + " pack is in use and some packs sway the plants out from under a particle. Eave drips"
                + " and icicles are unaffected. Set weatherOptions.disableFoliageLayersWithShaders=false"
                + " to keep all three layers anyway.");
    }

    public static final int STAT_AIR = 0;
    public static final int STAT_NOT_CANDIDATE = 1;
    public static final int STAT_SKY = 2;
    public static final int STAT_NO_PROFILE = 3;
    public static final int STAT_OK = 4;
    public static final int STAT_COUNT = 5;
    public static final int[] STATS = new int[STAT_COUNT];
    public static final String[] STAT_NAMES = {
            "air", "notCandidate", "skyBlocked", "noProfile", "ok"
    };

    /**
     * The histogram of the LAST scan, whatever TRACE was at the time. Kept as a
     * snapshot so a diagnostics read never races a scan's own reset. The live
     * {@link #STATS} are cleared at the top of every scan, so "why did this
     * plant produce no profile" cannot be answered from them afterwards.
     */
    public static final int[] LAST = new int[STAT_COUNT];
    /** What the last scan found (candidate plants), and how long it took. */
    public static int lastFound = 0;
    public static long lastNanos = 0;
    public static int lastRange = 0;

    /**
     * The rejection histogram as one line. This is the whole answer to "is the
     * detection step or the render step broken": if {@code ok} is 0 the plants
     * never reached the handlers, and the biggest of the other buckets says why.
     */
    public static String statsLine() {
        final StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < STAT_COUNT; i++)
            sb.append(STAT_NAMES[i]).append('=').append(LAST[i]).append(' ');
        return sb.append("(found=").append(lastFound)
                .append(" range=").append(lastRange)
                .append(" ").append(lastNanos / 1000L).append("us)").toString();
    }

    /**
     * The vertical-window instrument as one token, or <b>""</b> while
     * {@link #WINDOW_DIAG} is off. An empty string appends nothing to a handler's
     * line, which is how a normal run's log stays exactly what it was.
     *
     * <p>{@code missUp[i]} counts the candidate plants {@code i+1} layers above
     * the top of the window that would have been spots, and {@code missDown} the
     * same below the bottom - so "how much is the window leaving on the table,
     * and how far up does it go on having something to show" is read straight off
     * the line.</p>
     */
    public static String windowLine() {
        if (!WINDOW_DIAG)
            return "";
        final StringBuilder sb = new StringBuilder(256);
        sb.append("window=+").append(lastUp).append("/-").append(lastDown)
                .append(" cols=").append(lastColumns)
                .append(" voxels=").append(lastVoxels)
                .append(" cand=").append(lastCandidates)
                .append(" hits=").append(lastFound)
                .append(" last=").append(lastNanos / 1000L).append("us")
                .append(" sky=").append(lastSkyNanos / 1000L).append("us")
                .append(" prof=").append(lastProfileNanos / 1000L).append("us");
        if (winScans > 0)
            sb.append(" n=").append(winScans)
                    .append(" avg=").append(winSumNanos / 1000L / winScans).append("us")
                    .append(" max=").append(winMaxNanos / 1000L).append("us");
        return sb.append(" missUp=").append(bands(PROBE_UP))
                .append(" missDown=").append(bands(PROBE_DOWN))
                .append(" missUpOk=").append(probeUpOk)
                .append(" missDownOk=").append(probeDownOk).toString();
    }

    /** {@code [a,b,c]}, band 0 being the layer immediately outside the window. */
    private static String bands(final int[] counts) {
        final StringBuilder sb = new StringBuilder(PROBE_BANDS * 4 + 2);
        sb.append('[');
        for (int i = 0; i < PROBE_BANDS; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(counts[i]);
        }
        return sb.append(']').toString();
    }

    // ---- round 49: the slow-scan line, always on ----------------------------
    /** Where the slow-scan line goes: the root logger, there is no handler here to borrow one from. */
    private static final IModLog LOGGER = Library.LOGGER;

    /**
     * A single scan slower than this gets a line in the log, <b>whatever the switches
     * say</b>.
     *
     * <p>Why it is not behind {@link #WINDOW_DIAG}: the maintainer asked for the window
     * instrument, ran with it, and then asked for the same measurement again on a later
     * build - and a diagnostic that is off unless someone remembers a JVM property is a
     * diagnostic nobody has. There is something to catch: at the 6/6 window a scan's
     * median is 1280us and its p90 1924us, but the historical maximum is <b>40439us</b>
     * - forty milliseconds - and it lands in the first scans after a world load (n=3),
     * where it reads as a hitch with no explanation. One line when it happens is worth
     * more than a switch nobody predicted.</p>
     *
     * <p>A normal scan pays one long comparison and three int counters that
     * {@link #STATS} was already paying for per block.</p>
     */
    private static final long SLOW_SCAN_NANOS = 10_000_000L;

    /** At most one line a second... */
    private static final long SLOW_LINE_GAP_NANOS = 1_000_000_000L;
    /** ...and at most this many a minute; the rest are counted and summarised. */
    private static final int SLOW_LINES_PER_MINUTE = 5;
    private static final long SLOW_WINDOW_NANOS = 60_000_000_000L;

    private static long slowWindowStart = System.nanoTime();
    /** Now minus a gap, so the first slow scan prints whatever nanoTime's origin is. */
    private static long slowLastLine = slowWindowStart - SLOW_LINE_GAP_NANOS;
    private static int slowLines = 0;
    private static int slowSuppressed = 0;
    private static long slowWorstNanos = 0L;

    /**
     * The line itself, rate limited so one bad minute cannot become a bad log: at most
     * one line a second and five a minute, and everything the limits drop is counted
     * into a single summary line, written by the first slow scan after the minute rolls
     * over. That count is what separates "a hitch at world load" from "this layer is too
     * expensive", which is the question the line exists to answer.
     *
     * <p>{@code x}/{@code y}/{@code z} is the scan centre, which is the player's own
     * block in all three handlers.</p>
     */
    private static void reportSlowScan(final long nanos, final int cols, final int voxels, final int cand,
                                       final int hits, final int x, final int y, final int z,
                                       final int up, final int down, final Mode mode) {
        final long now = System.nanoTime();
        if (now - slowWindowStart >= SLOW_WINDOW_NANOS) {
            if (slowSuppressed > 0)
                LOGGER.warn("[FoliageScan] slow scan summary: " + slowLines + " printed, "
                        + slowSuppressed + " suppressed over the last minute (worst "
                        + (slowWorstNanos / 1000L) + "us)");
            slowWindowStart = now;
            slowLines = 0;
            slowSuppressed = 0;
            slowWorstNanos = 0L;
        }
        if (nanos > slowWorstNanos)
            slowWorstNanos = nanos;
        if (slowLines >= SLOW_LINES_PER_MINUTE || now - slowLastLine < SLOW_LINE_GAP_NANOS) {
            slowSuppressed++;
            return;
        }
        slowLines++;
        slowLastLine = now;
        LOGGER.warn("[FoliageScan] slow scan " + (nanos / 1000L) + "us mode=" + mode
                + " cols=" + cols + " voxels=" + voxels + " cand=" + cand + " hits=" + hits
                + " player=" + x + "/" + y + "/" + z + " window=+" + up + "/-" + down);
    }

    /**
     * Whether a block was inside the window the LAST scan walked. This is the
     * "retention pool" question in one call - how many of the units a handler is
     * still holding are outside the sweep that maintains them - and it is
     * rebuilt from the recorded centre and fan rather than stored per spot, so it
     * costs nothing while nobody asks.
     */
    public static boolean inLastWindow(final int x, final int y, final int z) {
        final int dy = y - lastCenterY;
        if (dy > lastUp || dy < -lastDown)
            return false;
        final int dx = x - lastCenterX;
        final int dz = z - lastCenterZ;
        if (!lastFan)
            return Math.abs(dx) <= lastRange && Math.abs(dz) <= lastRange;
        final float forward = dx * lastFx + dz * lastFz;
        final float side = dx * -lastFz + dz * lastFx;
        final float a = (forward >= 0F ? FAN_FORWARD : FAN_BACK) * lastRange;
        final float b = FAN_SIDE * lastRange;
        final float u = forward / a;
        final float v = side / b;
        return u * u + v * v <= 1F;
    }

    /**
     * The layers just outside the active window, on one column, walked with the
     * same predicate chain and thrown away. Reached only while {@link #WINDOW_DIAG}
     * is on, and it touches neither {@link #STATS}, {@link #LAST} nor the result
     * list: a measurement must not change the thing it measures. It does cost
     * real block reads - twelve extra columns' worth per column of the sweep -
     * which is the price of the answer and the reason it lives behind a switch.
     */
    private static void probe(final Level world, final Mode mode, final int x, final int z,
                              final int cy, final int up, final int down) {
        for (int i = 0; i < PROBE_BANDS; i++) {
            final int above = probeOne(world, mode, x, cy + up + 1 + i, z);
            if (above > 0)
                PROBE_UP[i]++;
            if (above == 2)
                probeUpOk++;
            final int below = probeOne(world, mode, x, cy - down - 1 - i, z);
            if (below > 0)
                PROBE_DOWN[i]++;
            if (below == 2)
                probeDownOk++;
        }
    }

    /** 0 = nothing there, 1 = a plant the sky or the profile turned away, 2 = it would have been a spot. */
    private static int probeOne(final Level world, final Mode mode, final int x, final int y, final int z) {
        if (world.isOutsideBuildHeight(y))
            return 0;
        final BlockPos pos = new BlockPos(x, y, z);
        final BlockState state = world.getBlockState(pos);
        if (state.isAir() || !isCandidate(state))
            return 0;
        if (!skyOpen(world, pos, mode))
            return 1;
        return FoliageProfileCache.get(world, pos, state).isEmpty() ? 1 : 2;
    }

    /** One attachable plant: position, its state, and its ready profile. */
    public record FoliageSpot(BlockPos pos, BlockState state, FoliageProfile profile) {
    }

    private FoliageScan() {
    }

    /**
     * Round 43: unconditional. The histogram used to need TRACE, so a live
     * test printed air=0 notCandidate=0 ... ok=0 - which reads as "the scanner
     * is broken" when it only means "it is muted". Counting a handful of ints
     * per block is nothing next to the walk itself (the scan prints at ~300us),
     * and DripScanner has always counted unconditionally: the line is meant to
     * be readable without turning debug on.
     */
    private static void note(final int stat) {
        STATS[stat]++;
    }

    /**
     * Round 45: what the scanner ACCEPTED, by block. The histogram says how many
     * blocks were turned away, but "ok=1 out of seven thousand" cannot be read
     * from it - it needs to know which blocks got as far as the profile. Only
     * candidates reach this, so the map stays a handful of keys.
     */
    public static final java.util.LinkedHashMap<String, int[]> KINDS = new java.util.LinkedHashMap<>();
    private static int kindsOverflow = 0;

    private static void noteKind(final BlockState state, final boolean profileOk) {
        if (KINDS.size() >= 48 && !KINDS.containsKey(key(state))) {
            kindsOverflow++;
            return;
        }
        final int[] v = KINDS.computeIfAbsent(key(state), k -> new int[2]);
        v[profileOk ? 0 : 1]++;
    }

    private static String key(final BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** {@link #KINDS} as one token: {@code id:okN/npN}. */
    public static String kindsLine() {
        if (KINDS.isEmpty())
            return "kinds=none(overflow=" + kindsOverflow + ")";
        final StringBuilder sb = new StringBuilder(160);
        sb.append("kinds[");
        for (final java.util.Map.Entry<String, int[]> e : KINDS.entrySet())
            sb.append(e.getKey()).append(":ok").append(e.getValue()[0])
                    .append("/np").append(e.getValue()[1]).append(' ');
        return sb.append("]overflow=").append(kindsOverflow).toString();
    }

    /**
     * Every attachable plant in the box around {@code center}, now. Rejection
     * reasons are counted (when {@link #TRACE} is on) so a live test can
     * answer "why is nothing dripping" from one log line.
     */
    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode) {
        return scan(world, center, mode, RANGE_XZ);
    }

    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode,
                                                final int range) {
        return scan(world, center, mode, range, 0F, 0F);
    }

    /**
     * The same sweep, narrowed to what the player can actually see. {@code fx}/{@code fz}
     * is the horizontal facing (a unit vector); pass 0/0 for the plain box, which is what
     * the dew and rain layers still ask for. Ground that swings into view as the player
     * turns is swept then, and anything already dressed is kept out to its layer's own
     * keep distance regardless of shape.
     */
    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode,
                                                final int range, final float fx, final float fz) {
        // Always filled, not just while tracing: "why does 26.1 have
        // almost no snow" is answered by this histogram and nothing else,
        // and it costs five increments per visited block.
        final long start = System.nanoTime();
        java.util.Arrays.fill(STATS, 0);
        KINDS.clear();
        kindsOverflow = 0;
        final ObjectArray<FoliageSpot> result = new ObjectArray<>(64);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int rx = Math.max(2, range);
        final boolean fan = fx != 0F || fz != 0F;
        // The sweep is wider than rx now, so the walk has to reach past it - a box-sized
        // loop clipped the fan back to exactly the old 16 and quietly did nothing.
        final int w = fan ? (int) Math.ceil(Math.max(FAN_SIDE, Math.max(FAN_FORWARD, FAN_BACK)) * rx) : rx;
        // Round 48: the vertical window is the band it always was unless the A/B switch
        // moved it. Round 49: the three shape counters are unconditional, because the
        // slow-scan line reports them in a normal run; the stage timings, the probe pass
        // and the rolling cost window still wait for the diag switch. Hoisting the centre
        // out of the two loops is what the y bound now reads.
        final int up = activeUp();
        final int down = activeDown();
        final int cx = center.getX();
        final int cy = center.getY();
        final int cz = center.getZ();
        final boolean diag = WINDOW_DIAG;
        int columns = 0;
        int voxels = 0;
        int candidates = 0;
        long skyNanos = 0L;
        long profileNanos = 0L;
        if (diag) {
            java.util.Arrays.fill(PROBE_UP, 0);
            java.util.Arrays.fill(PROBE_DOWN, 0);
            probeUpOk = 0;
            probeDownOk = 0;
        }

        for (int dx = -w; dx <= w; dx++) {
            for (int dz = -w; dz <= w; dz++) {
                if (fan) {
                    final float forward = dx * fx + dz * fz;
                    final float side = dx * -fz + dz * fx;
                    final float a = (forward >= 0F ? FAN_FORWARD : FAN_BACK) * rx;
                    final float b = FAN_SIDE * rx;
                    final float u = forward / a;
                    final float v = side / b;
                    if (u * u + v * v > 1F)
                        continue;
                }
                final int x = cx + dx;
                final int z = cz + dz;
                columns++;
                for (int y = cy + up; y >= cy - down; y--) {
                    voxels++;
                    if (world.isOutsideBuildHeight(y))
                        continue;
                    cursor.set(x, y, z);
                    final BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        note(STAT_AIR);
                        continue;
                    }
                    if (!isCandidate(state)) {
                        note(STAT_NOT_CANDIDATE);
                        continue;
                    }
                    candidates++;
                    final BlockPos pos = new BlockPos(x, y, z);
                    // Round 48: the two stages that actually cost something, timed apart.
                    // Both clocks are read only while the diag switch is on, so a normal
                    // scan pays one boolean test per candidate and nothing else.
                    final long skyStart = diag ? System.nanoTime() : 0L;
                    final boolean sky = skyOpen(world, pos, mode);
                    if (diag)
                        skyNanos += System.nanoTime() - skyStart;
                    if (!sky) {
                        note(STAT_SKY);
                        continue;
                    }
                    final long profileStart = diag ? System.nanoTime() : 0L;
                    final FoliageProfile profile = FoliageProfileCache.get(world, pos, state);
                    if (diag)
                        profileNanos += System.nanoTime() - profileStart;
                    if (profile.isEmpty()) {
                        note(STAT_NO_PROFILE);
                        noteKind(state, false);
                        continue;
                    }
                    note(STAT_OK);
                    noteKind(state, true);
                    result.add(new FoliageSpot(pos, state, profile));
                }
                if (diag)
                    probe(world, mode, x, z, cy, up, down);
            }
        }
        System.arraycopy(STATS, 0, LAST, 0, STAT_COUNT);
        lastFound = result.size();
        lastNanos = System.nanoTime() - start;
        lastRange = rx;
        lastUp = up;
        lastDown = down;
        lastCenterX = cx;
        lastCenterY = cy;
        lastCenterZ = cz;
        lastFan = fan;
        lastFx = fx;
        lastFz = fz;
        // Round 49: the shape of the last scan is known in every run, so it is always
        // recorded; the stage timings and the rolling cost window still need the switch.
        lastColumns = columns;
        lastVoxels = voxels;
        lastCandidates = candidates;
        if (diag) {
            lastSkyNanos = skyNanos;
            lastProfileNanos = profileNanos;
            winScans++;
            winSumNanos += lastNanos;
            if (lastNanos > winMaxNanos)
                winMaxNanos = lastNanos;
        }
        if (lastNanos > SLOW_SCAN_NANOS)
            reportSlowScan(lastNanos, columns, voxels, candidates, result.size(), cx, cy, cz, up, down, mode);
        return result;
    }

    /**
     * The one answer to "is this block a plant that can carry droplets".
     * Bushes, flowers, crops and saplings - every stage, including the first
     * (round 43). A sprout is 2.7% opaque and its anchors all sit in the bottom
     * half (survey §15), so it gets few units and only of the smallest tier;
     * that is the profile's job, not this gate's.
     */
    private static boolean isCandidate(final BlockState state) {
        if (state.is(BlockTags.LEAVES))
            return false;
        if (!state.getFluidState().isEmpty())
            return false;
        // Round 43: the FIRST crop stage is a candidate again. It used to be
        // turned away here - a wheat sprout is 2.7% opaque and every anchor sits
        // in the bottom half, so a unit read as a white dot on the ground rather
        // than snow on a plant. The maintainer overruled that ("第一阶段的作物
        // 可以积雪，大小还行，可以承受最小的雪块"): a farm is mostly stage-one
        // rows, and what keeps it honest is the profile, not this gate - a sprout
        // offers few anchors and no room, so it takes the smallest tier and only
        // a couple of them. Nothing else about the placement changes.
        // Round 12: sugar cane. It is a cross model exactly like grass, but
        // SugarCaneBlock extends Block rather than BushBlock and it is in none of the
        // three tags below, so it never once made the list - the maintainer asked why.
        // (Cactus is the same shape of omission and stays out on purpose: snow sitting
        // on a cactus is not a picture anyone asked for.)
        if (state.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE))
            return true;
        // Round 43: 26.1 RETIRED BushBlock - it has no subclasses left. Every
        // plant now extends VegetationBlock directly: CropBlock, SaplingBlock,
        // FlowerBlock, MushroomBlock, NetherWartBlock, and - the ones the
        // maintainer reported missing - TallGrassBlock (Blocks.SHORT_GRASS) and
        // DoublePlantBlock (Blocks.TALL_GRASS). So `instanceof BushBlock` matched
        // NOTHING on this version: grass and tall grass were turned away while
        // flowers kept working only because BlockTags.FLOWERS caught them, which
        // is exactly "只有花有雪，普通草和高草一点都没有".
        // VegetationBlock is the 26.1 equivalent of the old BushBlock set. It adds
        // the newer plants (leaf litter, dry grass, firefly bush, azalea) and
        // mushrooms/lily pads, all of which take snow sensibly; the nether ones
        // never reach here because canSeeSky is false down there.
        if (state.getBlock() instanceof VegetationBlock)
            return true;
        return state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS);
    }

    /** Sky test per mode - asked once per spot, at scan time, never again. */
    private static boolean skyOpen(final Level world, final BlockPos pos, final Mode mode) {
        if (mode == Mode.RAIN)
            return DripScanner.rainReachesTop(world, pos.above());
        // DEW and SNOW share the answer: "can precipitation reach this blade"
        // is "is the sky open above it". Whether it is RAIN or SNOW up there
        // is a PHASE question, and that is the handler's (per spot).
        return dewSkyOpen(world, pos);
    }

    /**
     * "Is the sky directly open above this plant" - canSeeSky plus the
     * MOTION_BLOCKING heightmap. The heightmap is what knows about glass
     * (which skylight shines through) and leaves; the biome precipitation
     * term of {@code rainReachesTop} is deliberately absent - dew does not
     * care whether this biome calls its weather RAIN.
     */
    public static boolean dewSkyOpen(final Level world, final BlockPos pos) {
        if (!world.canSeeSky(pos.above()))
            return false;
        return world.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                <= pos.getY() + 1;
    }
}
