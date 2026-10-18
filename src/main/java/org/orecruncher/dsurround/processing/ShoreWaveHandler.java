package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.processing.scanner.ShoreScan;
import org.orecruncher.dsurround.tags.BiomeTags;

/**
 * Shore surf (stage B of {@code SHORE-WAVE-SPEC.md}): a pixel band of foam that
 * rides up the beach and back down, drawn in world space on the water surface.
 *
 * <p>Two jobs, split by cost. The <em>scan</em> reads blocks and runs only when
 * the player has moved far enough to invalidate the window; the <em>build</em>
 * turns that frozen picture plus the current swell into quads and is pure
 * arithmetic, so it can run every tick. Both are timed with
 * {@code System.nanoTime} and reported - the spec forbids estimating cost.</p>
 *
 * <p>Rendering follows {@code PrecipitationRenderer} to the letter, because that
 * class already paid for every mistake available here: state restored one item
 * at a time in a {@code finally}, {@code uv2(block, sky)} in that order, the
 * Tesselator torn down after a throw (it is process-wide, and a builder left
 * building kills the next frame), and a render failure disabling the pass
 * instead of the frame.</p>
 *
 * <p><b>One deliberate divergence.</b> Precipitation draws at
 * {@code AFTER_WEATHER} and relies on the model view the frame already holds,
 * which is correct there because vanilla installs the camera rotation into that
 * stack a few statements earlier. The surf draws at
 * {@code AFTER_TRANSLUCENT_BLOCKS}, which is <em>before</em> that point in the
 * frame, so it installs the rotation itself - taken from the event's own pose
 * stack. See {@link #draw} for the derivation. what the inherited matrix actually turned out to be.</p>
 *
 * <p>Sound is deliberately not synchronised: {@code beach.ogg} carries five
 * swells in thirty seconds at irregular 0.75-10.55s spacing, so driving the
 * picture from it would make the sea look broken. The visual keeps its own
 * period and the existing ambience keeps playing.</p>
 */
public class ShoreWaveHandler extends AbstractClientHandler {

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[ShoreScan.TEX_COUNT];

    static {
        // Only the strip is drawn. The film, cap and corner sheets belonged to the
        // per-cell renderer and are gone from the jar as well, so their slots stay
        // empty - an unbound slot is never drawn, and the draw loop already skips it.
        TEXTURES[ShoreScan.TEX_STRIP] = texture("shore_foam_strip");
    }

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private static final BlockPos.MutableBlockPos BLOCK_POS = new BlockPos.MutableBlockPos(0, 0, 0);

    /**
     * Re-scan once the player has walked this far from the window centre.
     *
     * <p>Not 2. A full window is ~6.5k block reads and measured 0.2-2.1ms on the
     * render thread, and walking along a beach triggered it every other step -
     * seven rescans inside one second in the first capture. Four blocks still
     * leaves {@code RANGE - 4 = 12} blocks of coverage ahead, comfortably more
     * than the {@code RENDER_MARGIN + FADE_BLOCKS = 6} the draw has to give up.</p>
     */
    private static final int RECENTER_BLOCKS = 4;
    /** ...or after this long standing still (chunk edits, tide changes). */
    private static final int RESCAN_TICKS = 40;
    /**
     * Floor on the gap between full scans, however fast the player moves. The
     * re-centre rule alone has no rate limit: sprinting or riding doubled the
     * rescan rate with no visual gain, because the window slides a block at a
     * time either way.
     */
    private static final int MIN_SCAN_INTERVAL_TICKS = 8;
    /** Fraction of the cycle spent climbing; the rest is the retreat. */
    private static final float CLIMB_FRACTION = 0.7F;

    private final ITickCount tickCount;
    private final ShoreScan.Quads quads = new ShoreScan.Quads();

    private ShoreScan.Grid grid;
    private ResourceLocation dimensionId;
    private int scanX = Integer.MIN_VALUE;
    private int scanZ = Integer.MIN_VALUE;
    private long scanTick = Long.MIN_VALUE;

    private long lastScanNanos = 0L;
    private long lastBuildNanos = 0L;
    private int lastRunCells = 0;
    private int lastFoamCells = 0;
    /** Result of the geometric self-check for the quads currently in the buffer. */
    private ShoreScan.Audit lastAudit = new ShoreScan.Audit();
    private float lastProgress = 0F;

    private boolean filterPinned = false;
    /** Last {@code shoreFilmDebugColor} value the log was told about. */
    private boolean loggedFilmDebugColor = false;
    /** Last fog range the log was told about, and what the pass did with it. */
    private float loggedFogStart = Float.NaN;
    private float loggedFogEnd = Float.NaN;
    private boolean loggedFogNeutralised = false;
    /** The fog the frame handed this pass on the last draw - the raw pair, not what was drawn with. */
    private float frameFogStart = Float.NaN;
    private float frameFogEnd = Float.NaN;
    private boolean broken = false;
    private int failures = 0;
    private long framesSeen = 0L;

    /**
     * Last {@link #auditVisibility} tally actually printed, as its own fields.
     * Null until the first print; the audit only speaks when this changes.
     */
    private String lastAuditPrint;
    private long framesDrawn = 0L;
    private long lastRenderNanos = 0L;

    /**
     * Fingerprint of the GPU state this pass was handed, so it prints once per
     * change rather than once per frame.
     *
     * <p>This is the part no offline harness can produce. Every number here is
     * whatever the previous pass left in {@code RenderSystem} - the surf draws
     * with the particle shader, and that shader reads its tint from
     * {@code ColorModulator}, which nothing in this class has ever set or
     * restored. RenderSystem state is static and shared across passes, so the
     * surf inherits it. A shore drawn at night under a stale sky tint, or a band
     * drawn with fog already closed inside its own range, looks like a geometry
     * bug and is not one; both are invisible in the vertex data, because the
     * vertex still says the colour the emitter chose.</p>
     */
    private String lastStatePrint;

    public ShoreWaveHandler(final Configuration config, final IModLog logger, final ITickCount tickCount) {
        super("ShoreWave", config, logger);
        this.tickCount = tickCount;
        MinecraftForge.EVENT_BUS.addListener(this::doRender);
    }

    private static ResourceLocation texture(final String name) {
        // Deliberately NOT textures/block: everything under that folder is
        // stitched into minecraft:blocks, and the surf tiles are sampled by
        // themselves. DSR's other effects live in environment/ for the same
        // reason.
        return new ResourceLocation(Constants.MOD_ID, "textures/environment/" + name + ".png");
    }

    @Override
    public void onConnect() {
        clear();
    }

    @Override
    public void onDisconnect() {
        clear();
    }

    private void clear() {
        this.grid = null;
        this.dimensionId = null;
        this.scanX = Integer.MIN_VALUE;
        this.scanZ = Integer.MIN_VALUE;
        this.scanTick = Long.MIN_VALUE;
        this.quads.clear();
        this.broken = false;
        this.framesSeen = 0L;
        this.framesDrawn = 0L;
        this.lastAuditPrint = null;
    }

    // =====================================================================
    // Tick
    // =====================================================================

    @Override
    public void process(final Player player) {
        if (!this.config.shoreWaveOptions.enableShoreWaves) {
            if (this.grid != null)
                clear();
            return;
        }

        final Level level = player.level();
        // A sky is required (same test the aurora uses): Forge dispatches the
        // stage event in every dimension, and a ceilinged dimension has no surf.
        if (level.dimensionType().hasCeiling() || !level.dimensionType().hasSkyLight()) {
            if (this.grid != null)
                clear();
            return;
        }

        final long tick = this.tickCount.getTickCount();
        final int px = player.blockPosition().getX();
        final int pz = player.blockPosition().getZ();
        final ResourceLocation dim = level.dimension().location();

        final boolean recentred = Math.abs(px - this.scanX) >= RECENTER_BLOCKS
                || Math.abs(pz - this.scanZ) >= RECENTER_BLOCKS;
        final boolean stale = this.grid == null
                || !dim.equals(this.dimensionId)
                || (recentred && tick - this.scanTick >= MIN_SCAN_INTERVAL_TICKS)
                || tick - this.scanTick >= RESCAN_TICKS;

        if (stale) {
            // D7: only real shores. The biome is read per run, at the shoreline
            // cell - never at the player, who may be standing well inland.
            this.grid = ShoreScan.scan(level, px, pz);
            this.dimensionId = dim;
            this.scanX = px;
            this.scanZ = pz;
            this.scanTick = tick;
            this.lastScanNanos = this.grid.scanNanos;
            this.lastRunCells = this.grid.runCells;
            final int runsTotal = this.grid.runs.size();
            this.grid.runs.removeIf(run -> !isShoreBiome(level, run));
            this.grid.runsDroppedByBiome = runsTotal - this.grid.runs.size();
            logScan(this.grid, level, px, pz);
        }

        this.lastProgress = swellProgress(tick, this.config.shoreWaveOptions.wavePeriodTicks);
        // Read every build so the two emitters can be swapped without a rebuild.
        ShoreScan.fieldSurf = this.config.shoreWaveOptions.shoreFieldSurf;
        final long mark = System.nanoTime();
        try {
            ShoreScan.build(this.grid, this.lastProgress, level, this.quads);
        } catch (final Throwable t) {
            // A build that throws must cost ONE frame, not the feature: the buffer is
            // left empty (so nothing stale is ever drawn), the failure is counted, and
            // the next tick tries again. Before this, an exception here left whatever
            // the buffer happened to hold and the picture stopped matching the log.
            this.quads.clear();
            if (this.buildFailures++ < 5)
                this.logger.warn("[ShoreWave] build failed, frame skipped: {}", t.toString());
        }
        // Geometric self-check, on the quads themselves and not on the rules: any
        // quad that spans more than ShoreScan.MAX_QUAD_SPAN blocks, or that sits
        // further than MAX_QUAD_DISTANCE from the player, is dropped here with a
        // warning instead of being drawn. It runs inside the build timing so the
        // audit's own cost is in the reported number rather than hidden.
        this.lastAudit = ShoreScan.auditQuads(this.grid, this.quads, px, pz);
        this.lastBuildNanos = System.nanoTime() - mark;
        this.lastFoamCells = this.grid.foamCells;
        this.failures = 0;
        logBuild(tick, this.grid, player, this.lastAudit);
    }
    /** The first quad whose baseline runs along X (or along Z), or null if there is none. */
    private static ShoreScan.ShoreQuad firstOriented(final ShoreScan.Quads quads, final boolean alongX) {
        for (int i = 0; i < quads.count; i++) {
            final ShoreScan.ShoreQuad q = quads.items[i];
            if ((q.wz[0] == q.wz[1]) == alongX)
                return q;
        }
        return null;
    }

    /** One quad's geometry as a single line - corners, height, alpha, arc-length u. */
    private static String describe(final ShoreScan.ShoreQuad q) {
        if (q == null)
            return "none";
        return String.format("tex %d y %.3f a %d corners (%.2f,%.2f)(%.2f,%.2f)(%.2f,%.2f)(%.2f,%.2f) uSpan %.3f v %.1f",
                q.tex, q.y, q.alpha,
                q.wx[0], q.wz[0], q.wx[1], q.wz[1], q.wx[2], q.wz[2], q.wx[3], q.wz[3],
                Math.abs(q.u[1] - q.u[0]), q.v[0]);
    }

    /** {@code m * (x,y,z,1)}, a position - the translation takes part. */
    private static String position(final Matrix4f m, final double x, final double y, final double z) {
        final Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1F);
        m.transform(v);
        return format(v);
    }

    /** {@code m * (x,y,z,0)}, a direction - so translation cannot leak in. */
    private static String direction(final Matrix4f m, final float x, final float y, final float z) {
        final Vector4f v = new Vector4f(x, y, z, 0F);
        m.transform(v);
        return format(v);
    }

    private static String format(final Vector4f v) {
        return String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    /**
     * The three world axes and the camera's own three vectors through one
     * matrix. Printed together because that is what makes a wrong rotation
     * readable: the point probes say <em>that</em> it is wrong, this says
     * <em>how</em>, and it is enough to reconstruct the matrix from the log.
     */
    private static String basis(final Matrix4f m, final Camera camera) {
        final Vector3f look = camera.getLookVector();
        final Vector3f up = camera.getUpVector();
        final Vector3f left = camera.getLeftVector();
        return String.format("x^%s y^%s z^%s | look%s up%s left%s",
                direction(m, 1F, 0F, 0F), direction(m, 0F, 1F, 0F), direction(m, 0F, 0F, 1F),
                direction(m, look.x(), look.y(), look.z()),
                direction(m, up.x(), up.y(), up.z()),
                direction(m, left.x(), left.y(), left.z()));
    }

    /**
     * Max component difference between the event's pose stack and the same
     * rotation rebuilt from the camera: {@code Ry(180) * conj(rotation())}.
     *
     * <p>The copy of the quaternion is not optional. JOML's {@code conjugate()}
     * mutates in place and {@code Camera.rotation()} hands out the camera's own
     * field, so {@code camera.rotation().conjugate()} leaves the camera mirrored
     * for the rest of the frame - every later consumer of its basis sees a world
     * reflected through the origin. It is the obvious spelling of this fix and
     * it is a landmine.</p>
     */
    private static float rotationDelta(final Matrix4f source, final Camera camera) {
        final Matrix4f expected = new Matrix4f()
                .rotateY((float) Math.PI)
                .rotate(new Quaternionf(camera.rotation()).conjugate());
        float worst = 0F;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++)
                worst = Math.max(worst, Math.abs(source.get(row, col) - expected.get(row, col)));
        return worst;
    }

    /**
     * How long the straight pieces of coast are. Every one-cell run gets a cap
     * at each end, so a shoreline ground down into ones and twos is mostly cap
     * quads and reads as scattered specks rather than a line - the same
     * complaint as "the corners do not join", from entirely the other end.
     */
    private void logRunLengths(final ShoreScan.Grid g) {
        if (!logging() || g.runs.isEmpty())
            return;
        int min = Integer.MAX_VALUE;
        int max = 0;
        int singles = 0;
        int total = 0;
        for (int i = 0; i < g.runs.size(); i++) {
            final int len = g.runs.get(i).len;
            if (len < min) min = len;
            if (len > max) max = len;
            if (len <= 1) singles++;
            total += len;
        }
        this.logger.info("[ShoreWave] run lengths: min %d max %d avg %.2f | single-cell runs %d/%d (%d%% of cells)",
                min, max, total / (double) g.runs.size(), singles, g.runs.size(),
                singles * 100 / Math.max(1, total));
    }

    /** The first tick after a change, then every LOG_INTERVAL ticks. */
    private static final int LOG_INTERVAL = 80;

    private boolean sawTerrain = false;
    private boolean loggedMatrix = false;
    private long logAtTick = 0L;

    private boolean logging() {
        return this.config.shoreWaveOptions.debugLogging;
    }

    private void logScan(final ShoreScan.Grid g, final Level level, final int px, final int pz) {
        if (!logging() || this.grid == null)
            return;
        this.logger.info("[ShoreWave] scan @ (%d,%d) dim %s window %d..%d x %d..%d waterTop %d | "
                        + "terrain: sea %d beach %d high %d unknown %d | runs %d cells %d (%.1fms)",
                px, pz, level.dimension().location(),
                g.ox, g.ox + g.size - 1, g.oz, g.oz + g.size - 1, g.waterTop,
                g.seaCells, g.beachCells, g.highCells, g.unknownCells,
                g.runs.size(), g.runCells, g.scanNanos / 1_000_000D);
        logRunLengths(g);
        // NOTE: the coast-edge / connector counters are set during ShoreScan.build, which runs AFTER
        // this method - logging them here reads zeros by construction (that mistake cost one round).
        // They are printed by logBuild instead.
        // A window with no beach next to no water is why most "nothing draws"
        // reports start: say which of the two is missing.
        if (g.beachCells == 0 || g.seaCells == 0)
            this.logger.info("[ShoreWave] no shoreline in window: beach cells %d, sea cells %d "
                            + "- waterTop %d must be the topmost water block index (getSeaLevel()-1)",
                    g.beachCells, g.seaCells, g.waterTop);
        this.sawTerrain = true;
    }

    /** "0:12 1:3 2:0 ..." - only the buckets that actually fired. */
    private static String histogram(final int[] h) {
        final StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < h.length; i++) {
            if (h[i] == 0)
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(i).append(':').append(h[i]);
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private void logBuild(final long tick, final ShoreScan.Grid g, final Player player,
                          final ShoreScan.Audit audit) {
        if (!logging())
            return;
        final boolean due = tick >= this.logAtTick;
        // Always report the first frame that produces something, and the frame
        // where it stops: those two transitions are the useful ones to catch.
        final boolean changed = (g.runCells == 0) == this.sawTerrain;
        if (!due && !changed)
            return;
        this.logAtTick = tick + LOG_INTERVAL;
        this.sawTerrain = g.runCells != 0;

        final StringBuilder detail = new StringBuilder(160);
        detail.append(String.format(
                "[ShoreWave] tick %d p %.2f | player (%d,%d,%d) | quads %d (film %d strip %d cap %d corner %d)"
                        + " | foam cells %d filled %d runs %d | build %.3fms",
                tick, this.lastProgress,
                player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
                this.quads.count, g.filmQuads, g.stripQuads, g.capQuads, g.cornerQuads,
                g.foamCells, g.filledCells, g.runCells, this.lastBuildNanos / 1_000_000D));
        // Where the film goes. filled > 0 with film 0 means the front marked
        // cells and the foam drew them, but the film emitted nothing - and
        // until these four numbers existed that state was indistinguishable
        // from "no water reached the shore". candidates 0 means the merge
        // never offered a rectangle; rects all dropped means emitFilmRect's
        // alpha test is the wall; a non-zero min alpha with drops to match
        // means the wall is somewhere else entirely.
        // The shape of the lines the joint search runs on. "alone" counts an end
        // with nothing else on its perpendicular line, but says nothing about
        // WHY - a band that simply stops in the middle of a headland is correct
        // as drawn, while one whose line ran out of window is a truncation. The
        // two need opposite fixes, so they are counted apart before any rule is
        // touched: an interior-heavy tally says the pairing is doing its job and
        // the gaps are the shore's shape, and an edge-heavy one says RANGE is
        // the thing to raise.
        // Which way the bands were drawn. Cross-direction bands at zero meant
        // those stretches of coast had no geometry of their own and existed
        // only where a joint bridged them, so a number here is the direct
        // measure of how much coast used to be able to vanish.
        // mainAlongX is package-private in Grid, and the two edge counts are
        // already printed by the coast-edges line above, so this one stays on
        // what it is for: how many bands were drawn either side of the split.
        // Distance-field mode. level is the run-up the contour was drawn at, cap
        // the deepest beach the window offered, so a level pinned at the cap
        // means the wave is as far up as this coast allows. Short chains are the
        // only thing the level set drops, and they are counted rather than
        // silently skipped.
        this.logger.info("[ShoreWave]   field: %.2f of the beach crossed | chains %d | dropped as too short %d"
                        + " | widest beach %d blocks",
                g.fieldLevel, g.fieldChains, g.fieldShortChains, g.fieldFieldMax);
        // Which part of the coast did NOT become geometry this frame, and why. A
        // segment that vanishes as the player walks is one of these two counts, or an
        // open chain end sitting on the window border.
        this.logger.info("[ShoreWave]   field geom: edges %d | segments %d | skipped bounds %d alpha %d"
                        + " | open chains %d (ends at border %d) | f %.2f..%.2f",
                g.fieldEdges, g.fieldSegs, g.fieldSkipBounds, g.fieldSkipAlpha,
                g.fieldOpenChains, g.fieldBorderEnds,
                g.fieldFMin == Float.MAX_VALUE ? 0F : g.fieldFMin, g.fieldFMax);
        // Burial census. This is the one measurement that separates "the band was
        // never made" from "the band was made and then the beach ate it": a
        // negative gap means the quad is in the buffer, inside the frustum, at
        // full alpha, and still absent from the picture because the sand in front
        // of it won the depth test. quadsBuried should be zero.
        this.logger.info("[ShoreWave]   burial: %d of %d band quads at or below ground"
                        + " | worst gap %s at (%d,%d)",
                g.quadsBuried, g.quads,
                g.minGap == Float.MAX_VALUE ? "n/a" : String.format("%+.3f", g.minGap),
                g.minGapAtX, g.minGapAtZ);

        this.logger.info("[ShoreWave] coast edges: alongX %d alongZ %d | grid id %d shuffle %d shoreEdgeCount %d",
                g.edgeX, g.edgeZ, g.gridId, g.shuffle, g.shoreEdgeCount);

        // Joints, verbatim. The screen shows them in the wrong place "always the same
        // way", which no offline harness reproduces, so the first few are written down as
        // they are emitted: the cross edge that triggered them, the two band corners they
        // were given, the two ends that came out, and what cell each end landed on. Read
        // this against the screenshot and the offset is measurable rather than guessed.

        // The other half of the question: not the joints that were drawn but the ends
        // that were passed over. The joints are paired by mutual nearestness on each
        // line, so an end with no joint is one of three things - nothing else on its
        // line, something else but further than the reach, or a partner in reach that
        // prefers somebody else. Those three have three different fixes, so they are
        // counted apart, with the world cell of a few of them so a screenshot can be
        // matched to a coordinate.

        // "A vertical with no horizontal beside it" cannot be produced by the
        // pairing - a joint only exists between two band ends. So if it is on screen,
        // either a band was skipped this frame (no beach at this phase) or the joint's
        // end sits on a cell no band covered. Both are counted; together they say
        // whether this is a drawing problem at all.
        for (int i = 0; i < Math.min(ShoreScan.Grid.JOINT_FAIL_SAMPLE, g.endMissCount); i++) {
            final int o = i * 2;
            final String why = switch (g.endMissWhy[i]) {
                case 1 -> "out of reach";
                case 2 -> "not mutual";
                default -> "alone";
            };
        }

        // The flicker census. The maintainer reports the verticals "very unstable, at
        // random", and every counter above is a yes/no that cannot say WHY: a joint that
        // missed the band by one cell and one that missed by six both read "no band at A".
        // This is the number that separates them - how far the nearest band corner really
        // was, at each end, with no tolerance limit. Clustered at 1-2 the fix is the
        // tolerance; clustered at 6+ the band is simply not there and widening the search
        // would draw joints across open water.
        for (int i = 0; i < Math.min(ShoreScan.Grid.JOINT_FAIL_SAMPLE, g.jointFailed); i++) {
            final int o = i * 6;
            final String why = switch (g.jointFailWhy[i]) {
                case 0 -> "no band at A";
                case 1 -> "no band at B (same line only)";
                case 2 -> "no band at B (nothing there)";
                default -> "?";
            };
        }
        for (int i = 0; i < g.jointSampleCount; i++) {
            final int o8 = i * 8;
            final int o4 = i * 4;
        }

        // World vs camera-space sample: the fastest way to tell "nothing is
        // produced" from "it is drawn somewhere else".
        final Vec3 eye = player.position();
        if (this.quads.count > 0) {
            final ShoreScan.ShoreQuad q = this.quads.items[0];
            detail.append(String.format(" | quad0 tex %d world (%.2f,%.2f) y %.3f alpha %d",
                    q.tex, q.wx[0], q.wz[0], q.y, q.alpha));
            detail.append(String.format(" | rel camera (%.2f,%.2f,%.2f) dist %.1f",
                    q.wx[0] - (float) eye.x, q.y - (float) eye.y, q.wz[0] - (float) eye.z,
                    Math.hypot(q.wx[0] - eye.x, q.wz[0] - eye.z)));
        } else {
            detail.append(" | NO QUADS (runs ").append(g.runCells)
                    .append(", biome-dropped ").append(g.runsDroppedByBiome)
                    .append(", foam cells ").append(g.foamCells).append(')');
        }
        // The two coast orientations go through different code paths (bands vs
        // connectors), and one of them is invisible on screen while both are counted.
        // Print the first quad of each orientation with its full geometry, so the two
        // can be compared as numbers rather than by eye.
        detail.append(" | horizontal ").append(describe(firstOriented(this.quads, true)))
                .append(" | vertical ").append(describe(firstOriented(this.quads, false)));

        // The geometric range, in one line: what the pass emitted, the constants
        // that are actually compiled in (so a stale jar is visible), the box every
        // quad fell in, and the nearest/furthest quad from the player. This is the
        // line to ask for when a report says "one big plate": a plate is either a
        // span that is out of bounds (it will show up in maxSpan and in the drop
        // counts) or a span that is in bounds and still too big for the picture -
        // two different bugs that look identical from the player's chair.
        detail.append(String.format(
                " | geom: RANGE %d RUNUP_MAX %d D_SCAN_MAX %d maxSpan %d maxDist %d"
                        + " | across %d dropped (span %d dist %d) | world x %d..%d z %d..%d"
                        + " | dist nearest %.1f furthest %.1f",
                ShoreScan.RANGE, ShoreScan.RUNUP_MAX, ShoreScan.D_SCAN_MAX,
                ShoreScan.MAX_QUAD_SPAN, ShoreScan.MAX_QUAD_DISTANCE,
                audit.droppedSpan + audit.droppedDistance, audit.droppedSpan, audit.droppedDistance,
                audit.minX, audit.maxX, audit.minZ, audit.maxZ, audit.nearest, audit.furthest));

        if (audit.droppedAnything()) {
            // Not a warning about the shore: a warning about this class. Nothing
            // correct can exceed either bound, so a drop means an emitter upstream
            // is producing geometry no rule here can justify, and the player is
            // owed a log line rather than a wall of quads.
            this.logger.warn("[ShoreWave] GEOMETRY DROPPED: %d quad(s) over %d blocks of span,"
                            + " %d quad(s) further than %d blocks from the player - the picture is"
                            + " missing them; fix the emitter, do not raise the bound",
                    audit.droppedSpan, ShoreScan.MAX_QUAD_SPAN,
                    audit.droppedDistance, ShoreScan.MAX_QUAD_DISTANCE);
        }
        this.logger.info(detail.toString());
    }
    private boolean isShoreBiome(final Level level, final ShoreScan.Run run) {
        final Biome biome = level.getBiome(BLOCK_POS.set(run.startX, level.getSeaLevel(), run.startZ)).value();
        return TAG_LIBRARY.is(BiomeTags.IS_BEACH, biome)
                || TAG_LIBRARY.is(BiomeTags.IS_STONY_SHORES, biome)
                || TAG_LIBRARY.is(BiomeTags.IS_OCEAN, biome);
    }

    /**
     * One wave: climb to full run-up, then fall back. Sawtooth in shape but
     * continuous, so the line never jumps.
     */
    private static float swellProgress(final long tick, final int period) {
        final int p = Math.max(20, period);
        final float f = Math.floorMod(tick, p) / (float) p;
        return f < CLIMB_FRACTION
                ? f / CLIMB_FRACTION
                : Math.max(0F, 1F - (f - CLIMB_FRACTION) / (1F - CLIMB_FRACTION));
    }

    // =====================================================================
    // Render
    // =====================================================================

    public void doRender(final RenderLevelStageEvent event) {
        // Water is drawn by the translucent block pass, so this is the first
        // point in the frame where the surface we are laying onto exists.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        this.framesSeen++;
        if (logging()) {
            // Say once why nothing happened, not every frame: the three ways to
            // draw nothing have three different fixes.
            if (this.broken && this.framesSeen == 1)
                this.logger.warn("[ShoreWave] pass is BROKEN from an earlier render throw - no surf will draw until reconnect");
            else if (this.quads.count == 0 && (this.framesSeen == 1 || this.framesSeen % 400 == 0))
                this.logger.info("[ShoreWave] stage fired but no quads (quads %d, broken %b)",
                        this.quads.count, this.broken);
        }
        if (this.broken || this.quads.count == 0)
            return;

        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null)
            return;

        // The fog the frame hands over is part of the paint, not part of the
        // surf: particle.fsh ends with linear_fog(), and a range that is closed at
        // or inside this pass's own draw bound replaces EVERY fragment's RGB with
        // FogColor while leaving the alpha alone. That failure is invisible in the
        // vertex data - the log keeps saying rgb 0x3F76E4 - and on screen it is a
        // single pale sheet at exactly the right alpha, which is why it was twice
        // read as "the vertex colour is not applied". Read, decided and logged
        // here; restored in the finally below.
        final float fogStart = RenderSystem.getShaderFogStart();
        final float fogEnd = RenderSystem.getShaderFogEnd();
        final boolean fogNeutralised = fogClosed(fogStart, fogEnd);
        this.frameFogStart = fogStart;
        this.frameFogEnd = fogEnd;
        if (fogNeutralised) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(Minecraft.useShaderTransparency());
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getParticleShader);
            // setShader() only calls apply() when the shader INSTANCE changes, and
            // this one is shared with the two precipitation passes - so after a
            // neutralisation the uniforms have to be pushed again on purpose or
            // the draw would still use the fog it was given.
            if (fogNeutralised) {
                final ShaderInstance particle = GameRenderer.getParticleShader();
                if (particle != null)
                    particle.apply();
            }
            pinPixelFilter();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            final Vec3 cam = event.getCamera().getPosition();
            // Read BEFORE the draw, while this pass's own state is installed:
            // after the finally block these values are the frame's again and
            // would describe the wrong moment.
            if (logging())
                auditGpuState();
            this.lastRenderNanos = System.nanoTime();
            draw(event, event.getCamera(), cam);
            this.lastRenderNanos = System.nanoTime() - this.lastRenderNanos;
        } catch (final Throwable t) {
            // A render failure must cost the pass, not the frame.
            this.broken = true;
            if (++this.failures <= 3)
                this.logger.error(t, "[ShoreWave] render threw - the surf pass is disabled for this world");
            try {
                Tesselator.getInstance().end();
            } catch (final Throwable cleanupFailure) {
                this.logger.warn("[ShoreWave] tesselator cleanup failed (%s)", cleanupFailure);
            }
        } finally {
            restoreRenderState(mc);
            if (fogNeutralised) {
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
            }
        }
    }

    /**
     * True when the frame's fog range would replace the colour of geometry this
     * pass is allowed to draw.
     *
     * <p>Every quad the pass emits is within {@link ShoreScan#MAX_QUAD_DISTANCE}
     * blocks of the player by construction, so a range whose far plane is inside
     * that bound - or one that is not a range at all ({@code start >= end}) -
     * cannot be describing this pass's geometry; it is a range computed for
     * something else and left behind. Normal terrain fog starts at
     * {@code 0.75 * renderDistance * 16}, i.e. 96 blocks at the shortest render
     * distance the game allows and 192 at the default 16 chunks, so a well-formed
     * frame never reaches this branch and the pass is then left exactly as it
     * found the frame.</p>
     */
    private static boolean fogClosed(final float start, final float end) {
        return !(end > ShoreScan.MAX_QUAD_DISTANCE) || !(start < end);
    }

    /**
     * A fog plane for the log: {@code "%.1f"}, or {@code "none"} for the
     * {@code Float.MAX_VALUE} a render type writes when it disables fog.
     *
     * <p>Not cosmetics. The disabled case is one of the two states the log exists
     * to tell apart, and {@code 340282346638528859811704183484516925440.0} in the
     * middle of a line is exactly the kind of output a reader skips.</p>
     */
    private static String fogPlane(final float value) {
        return value >= Float.MAX_VALUE * 0.5F ? "none" : String.format("%.1f", value);
    }

    /**
     * Installs {@code R * T(-cam)} on the model view and draws world
     * coordinates through it.
     *
     * <p><b>Where R comes from.</b> {@code event.getPoseStack()} - the
     * level-render pose stack, the same object vanilla renders the world with.
     * {@code GameRenderer.renderLevel} applies exactly three rotations to the
     * stack it then hands to {@code LevelRenderer.renderLevel}, and Forge hands
     * that same stack to every {@code RenderLevelStageEvent}:</p>
     *
     * <pre>
     *   poseStack.mulPose(Axis.ZP.rotationDegrees(roll));            // Forge camera angles
     *   poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
     *   poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180F));
     * </pre>
     *
     * <p>Nothing else, and no translation. So at this stage it is precisely the
     * world&rarr;view rotation. Three independent things in this tree already
     * depend on that and are correct in production: {@code CritWordHandler} and
     * {@code SpeechBubbleHandler} both build {@code projection * poseStack} and
     * project world-minus-camera positions through it, and
     * {@code PrecipitationRenderer} draws camera-relative world coordinates
     * relying on the model view vanilla installs from the same stack at
     * {@code AFTER_WEATHER}. the matrix check re-derives the identity
     * numerically every session rather than trusting this paragraph.</p>
     *
     * <p><b>Why not {@code camera.rotation()}.</b> That quaternion is the other
     * way round, and this is the bug the pass shipped with.
     * {@code Camera.setRotation} builds it as {@code rotationYXZ(-yRot, xRot, 0)}
     * and then rotates the camera's own basis <em>by</em> it -
     * {@code forwards = (0,0,1).rotate(rotation)} - so it maps view axes to
     * world. Multiplying a model view by it rotates the world by the inverse of
     * the intended amount: a pure rotation, no length change, which is exactly
     * what the recorded probe showed (5.000 blocks long, wrong direction).</p>
     *
     * <p><b>Its inverse is not the fix either, in either spelling.</b> Both were
     * checked offline against the real JOML 1.10.5 that ships with 1.20.1, on
     * the camera pose recovered from that probe (yRot 150.81&deg;, xRot
     * 12.80&deg;):</p>
     *
     * <pre>
     *   conj(rotation())              ->  eye+5*look = (0.000,-0.000, 5.000)   WRONG
     *   Ry(180)*conj(rotation())      ->  eye+5*look = (0.000,-0.000,-5.000)   RIGHT
     *   Rx(xRot)*Ry(yRot+180)         ->  eye+5*look = (0.000,-0.000,-5.000)   RIGHT, same matrix
     * </pre>
     *
     * <p>The two right-hand forms agree to 0.000000 in every component; the
     * first differs by 1.746. The 180 is the difference between "the point five
     * blocks in front of the player" and "the point five blocks behind them",
     * which is the whole difference between surf on the shore and surf behind
     * the camera.</p>
     *
     * <p><b>Do not write {@code camera.rotation().conjugate()}.</b> JOML's
     * {@code conjugate()} mutates in place and {@code Camera.rotation()} hands
     * out the camera's own field, so that expression leaves the camera mirrored
     * for the rest of the frame - every later consumer of its basis, vanilla's
     * included, sees a world reflected through the origin. Copy first; the
     * cross-check in {@link #rotationDelta} does.</p>
     *
     * <p>The previous form of this method composed {@code rotation() * T(-cam)}
     * itself and its comment claimed the inherited model view carries no R at
     * {@code AFTER_TRANSLUCENT_BLOCKS}. That claim was never measured - the code
     * had already cleared the matrix by the time it looked - and it no longer
     * has to be: taking R from the pose stack is correct whether or not the
     * frame happens to hold it, so the pass no longer depends on the answer.
     *</p>
     */
    private void draw(final RenderLevelStageEvent event, final Camera camera, final Vec3 cam) {
        final Tesselator tesselator = Tesselator.getInstance();
        final BufferBuilder buffer = tesselator.getBuilder();

        // A copy, so the rotation this pass logs and the rotation it applies are
        // the same object even if the level stack is pushed again mid-draw - and
        // so the model view stack below can never alias the level stack.
        final Matrix4f view = new Matrix4f(event.getPoseStack().last().pose());

        final PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        try {
            // Rotation only: the camera TRANSLATION lives in the vertices (see
            // emit), not here. fog_distance's shape != 0 branch transforms the
            // vertex with two axes zeroed, so any translation in the model view
            // lands in vertexDistance - with T(-cam) installed and world-space
            // vertices it reported ~|cam| (2200+ blocks, on a beach where the surf
            // is 17 away), which drove linear_fog past fogEnd and replaced every
            // fragment's RGB with FogColor while leaving the alpha alone: sky
            // tint, sun-facing gradient, no texture, opacity intact.
            mv.setIdentity();
            mv.mulPoseMatrix(view);
            RenderSystem.applyModelViewMatrix();
            auditVisibility(view, cam);
            if (this.config.shoreWaveOptions.shoreDebugSolid)
                drawSolid(buffer, tesselator, cam);
            else
                drawQuads(buffer, tesselator, cam);
        } finally {
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Projects every quad the way the GPU will and says how many could actually
     * reach the screen.
     *
     * <p>The maintainer reports the surf appearing and disappearing at random,
     * strongly tied to player position, and the scan cannot produce that: quads
     * are non-degenerate, above ground, and every joint end sits on foam. So the
     * question moves to the pass - a quad that is emitted and never drawn looks
     * exactly like a quad that was never emitted. Rather than paint the world
     * magenta and ask a human to look, this asks the matrix: the same
     * {@code projection * view} the vertices go through, counting the four ways a
     * quad can be thrown away after it exists - behind the camera, off screen,
     * outside the depth range, or collapsed to no pixels.</p>
     */
    private void auditVisibility(final Matrix4f view, final Vec3 cam) {
        if (!logging())
            return;
        final Matrix4f pv = new Matrix4f(RenderSystem.getProjectionMatrix());
        pv.mul(view);
        final double cx = cam.x;
        final double cy = cam.y;
        final double cz = cam.z;
        int behind = 0;
        int offscreen = 0;
        int outsideDepth = 0;
        int collapsed = 0;
        int drawable = 0;
        int quads = 0;
        // Opacity buckets, over the quads that reach the screen. A quad can be
        // perfectly on-screen and still invisible, and "invisible" and "too
        // faint" cannot be told apart by eye - so the geometry verdict above is
        // not enough on its own, and this is the half that was missing when a
        // frame said 57 of 90 drawable while the picture showed a third of that.
        int faintLo = 0;
        int faintMid = 0;
        int faintHi = 0;
        int faintByKind = 0;
        // The buckets above only say "nothing is faint". That is a fact about
        // the SET, and on its own it cannot tell a healthy pass from a pass
        // whose alpha happens to be uniform - which is what the first run of
        // this audit reported, and it was indistinguishable from "fine" until
        // the per-kind extremes were printed. A max is enough for that: one
        // number per slot says where the paint actually sits.
        int bandMin = Integer.MAX_VALUE;
        int bandMax = 0;
        int joints = 0;
        int jointMin = Integer.MAX_VALUE;
        for (int i = 0; i < this.quads.count; i++) {
            final ShoreScan.ShoreQuad q = this.quads.items[i];
            quads++;
            // Every quad is the strip now; the film slot is gone, so there is no
            // second population to tell apart.
            bandMin = Math.min(bandMin, q.alpha);
            bandMax = Math.max(bandMax, q.alpha);
            if (q.kind == 1) {
                joints++;
                jointMin = Math.min(jointMin, q.alpha);
            }
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            boolean anyBehind = false;
            boolean anyInside = false;
            boolean anyInDepth = false;
            for (int k = 0; k < 4; k++) {
                // JOML multiplies on the MATRIX: pv.transform(v) is P*view*v.
                final Vector4f v = new Vector4f((float) (q.wx[k] - cx), (float) (q.y - cy), (float) (q.wz[k] - cz), 1F);
                pv.transform(v);
                if (v.w <= 0F) {
                    anyBehind = true;
                    break;
                }
                final double nx = v.x / v.w;
                final double ny = v.y / v.w;
                final double nz = v.z / v.w;
                minX = Math.min(minX, nx);
                maxX = Math.max(maxX, nx);
                minY = Math.min(minY, ny);
                maxY = Math.max(maxY, ny);
                minZ = Math.min(minZ, nz);
                maxZ = Math.max(maxZ, nz);
                if (Math.abs(nx) <= 1D && Math.abs(ny) <= 1D)
                    anyInside = true;
                if (nz >= -1D && nz <= 1D)
                    anyInDepth = true;
            }
            if (anyBehind)
                behind++;
            else if (!anyInside)
                offscreen++;
            else if (!anyInDepth)
                outsideDepth++;
            else if ((maxX - minX) * (maxY - minY) < 1.0E-8D)
                collapsed++;
            else {
                drawable++;
                // The texture multiplies the vertex alpha on top of it, so the
                // bands (shore_foam_strip has four fully transparent rows) and
                // the film are not judged by this number alone - it is the
                // ceiling, and the film reaching it is what makes it suspicious.
                if (q.alpha < 32)
                    faintLo++;
                else if (q.alpha < 128)
                    faintMid++;
                else
                    faintHi++;
                // Joints are the ones the maintainer reports vanishing; a
                // population of on-screen joints at rock-bottom alpha is the
                // signature of that, and it is invisible in the totals.
                if (q.kind == 1 && q.alpha < 32)
                    faintByKind++;
            }
        }
        // Printed on change, not per frame. Measured on a real session: 24,494
        // audit lines in about ninety seconds of standing on a beach, two per
        // frame, which buries the rest of the log and costs string formatting on
        // the render thread every frame. A count that only moves when the camera
        // moves is worth exactly as much as the frame it last changed in.
        final String fingerprint = quads + "/" + drawable + "/" + behind + "/" + offscreen
                + "/" + outsideDepth + "/" + collapsed + "/" + faintLo + "/" + faintMid
                + "/" + faintHi + "/" + faintByKind
                + "/" + bandMin + "/" + bandMax + "/" + joints + "/" + jointMin;
        if (!fingerprint.equals(this.lastAuditPrint) || this.framesSeen % 600 == 1L) {
            this.lastAuditPrint = fingerprint;
            this.logger.info("[ShoreWave] on-screen audit: %d quads | drawable %d | behind camera %d"
                            + " | off screen %d | outside depth %d | collapsed %d",
                    quads, drawable, behind, offscreen, outsideDepth, collapsed);
            if (drawable > 0) {
                this.logger.info("[ShoreWave]   on-screen alpha: <32 %d | 32-127 %d | >=128 %d"
                                + " | joints under 32: %d",
                        faintLo, faintMid, faintHi, faintByKind);
                this.logger.info("[ShoreWave]   alpha range: %d..%d | joints %d of %d, min %d",
                        bandMin == Integer.MAX_VALUE ? 0 : bandMin, bandMax,
                        joints, quads, jointMin == Integer.MAX_VALUE ? 0 : jointMin);
            }
            if (drawable < quads)
                this.logger.info("[ShoreWave]   %d of %d quads cannot reach the screen this frame",
                        quads - drawable, quads);
        }
    }

    /**
     * What the GPU is actually being handed this frame, plus how many quads went
     * out under each texture.
     *
     * <p>Everything printed so far describes what this class <em>intended</em>:
     * where it put the vertices, what alpha it wrote, whether the corners land
     * inside the frustum. All of that is computed on this side of the API and
     * none of it can see the draw call. Three failures live on the other side
     * of it, and each of them reproduces the reported symptom exactly while
     * leaving every number above looking correct:</p>
     *
     * <ul>
     *   <li><b>ColorModulator.</b> The particle shader multiplies the vertex
     *       colour by {@code ColorModulator}, which is RenderSystem state. This
     *       class never sets it and never restores it, so the surf is tinted by
     *       whichever pass ran last - vanilla's sky pass included. Reported once
     *       already as "pale blue, no texture, turns orange when facing the
     *       sun", and read twice as "the vertex colour is not applied".</li>
     *   <li><b>Fog.</b> {@code particle.fsh} ends in {@code linear_fog()}: at or
     *       past {@code fogEnd} it replaces the fragment RGB with FogColor and
     *       leaves alpha alone - a flat pale sheet at exactly the right opacity.</li>
     *   <li><b>Per-slot counts.</b> One {@code begin()} across all slots paints
     *       every quad with whichever texture was bound last. That already
     *       happened once here; the count per slot is what makes it visible
     *       again, because a slot that emits nothing is indistinguishable from
     *       a slot that emits and is then covered.</li>
     * </ul>
     *
     * <p>Prints on change, not per frame: at 60fps an unthrottled version of
     * this wrote ~24k lines in ninety seconds and buried the rest of the log.</p>
     */
    private void auditGpuState() {
        int bandCount = 0;
        int jointCount = 0;
        for (int i = 0; i < this.quads.count; i++) {
            final ShoreScan.ShoreQuad q = this.quads.items[i];
            if (q.kind == 1)
                jointCount++;
            else
                bandCount++;
        }
        // Read through GL, not through RenderSystem: this class knows what it
        // ASKED for, and asking is not the question. RenderSystem keeps no
        // getters for the enable flags in 1.20.1 (verified with javap - only
        // enable*/disable*/depthMask, no isOn), so the only way to learn what
        // the driver is actually holding is to ask it.
        final boolean blendOn = glIsEnabled(GL_BLEND);
        final boolean depthOn = glIsEnabled(GL_DEPTH_TEST);
        final boolean cullOn = glIsEnabled(GL_CULL_FACE);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final float[] tint = RenderSystem.getShaderColor();
        final float fogStart = RenderSystem.getShaderFogStart();
        final float fogEnd = RenderSystem.getShaderFogEnd();
        final float fogShape = RenderSystem.getShaderFogShape().ordinal();
        final boolean fogNeutralised = fogClosed(fogStart, fogEnd);
        final String fingerprint = String.format(
                "%d|%d|%.3f,%.3f,%.3f,%.3f|%s|%s|%.0f|%b%b%b%b",
                bandCount, jointCount,
                tint[0], tint[1], tint[2], tint[3],
                fogPlane(fogStart), fogPlane(fogEnd), fogShape,
                blendOn, depthOn, cullOn, depthMask);
        if (fingerprint.equals(this.lastStatePrint) && this.framesSeen % 600 != 1L)
            return;
        this.lastStatePrint = fingerprint;
        this.logger.info("[ShoreWave] gpu state: tint %.3f %.3f %.3f %.3f"
                        + " | fog %s..%s shape %d%s | blend %b depthTest %b depthMask %b cull %b",
                tint[0], tint[1], tint[2], tint[3],
                fogPlane(fogStart), fogPlane(fogEnd), (int) fogShape,
                fogNeutralised ? " [NEUTRALISED]" : "",
                blendOn, depthOn, depthMask, cullOn);
        this.logger.info("[ShoreWave]   emitting %d quads (bands %d, joints %d)",
                this.quads.count, bandCount, jointCount);
    }

    /**
     * GL capability enum values, written out rather than imported.
     *
     * <p>The obvious spelling - {@code com.mojang.blaze3d.platform.GL11} - does
     * not exist in 1.20.1: that package only has {@code GLX} here (checked in
     * the 1.20.1 client jar), and the wrapper class arrives in later versions.
     * The four values below are fixed by the OpenGL spec and are the same
     * numbers on every version, so naming them here keeps one diagnostic from
     * needing three different imports.</p>
     */
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_DEPTH_WRITEMASK = 0x0B72;

    /**
     * GL query, isolated. A diagnostic that throws inside the render path kills
     * the feature it is measuring - the symptom then reads as "the surf has a
     * bug", which is the one thing a probe must never cause.
     */
    private static boolean glIsEnabled(final int cap) {
        try {
            return RenderSystem.isOnRenderThread() && org.lwjgl.opengl.GL11.glIsEnabled(cap);
        } catch (final Throwable t) {
            return false;
        }
    }

    private static boolean glGetBoolean(final int cap) {
        try {
            return RenderSystem.isOnRenderThread() && org.lwjgl.opengl.GL11.glGetBoolean(cap);
        } catch (final Throwable t) {
            return false;
        }
    }

    
    /** How many build failures have been reported this session. */
    private int buildFailures = 0;
    /** How many draw-side failures have been reported this session. */
    private int drawFailures = 0;

    /**
     * Diagnostic draw: every quad as flat opaque magenta, no texture, no depth
     * test. It answers one question and nothing else - are the vertices of the
     * missing stretches in the buffer at all? Textured and solid modes disagreeing
     * means the geometry is fine and the fault is in the texture/sprite path;
     * agreeing means the vertices themselves never made it.
     */
    private void drawSolid(final BufferBuilder buffer, final Tesselator tesselator, final Vec3 cam) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();
        try {
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < this.quads.count; i++) {
                final ShoreScan.ShoreQuad q = this.quads.items[i];
                for (int k = 0; k < 4; k++)
                    buffer.vertex(q.wx[k] - cam.x, q.y - cam.y, q.wz[k] - cam.z)
                            .color(1F, 0F, 1F, 1F)
                            .endVertex();
            }
            tesselator.end();
        } catch (final Throwable t) {
            this.logger.warn("[ShoreWave] solid debug draw failed: {}", t.toString());
            try {
                tesselator.end();
            } catch (final Throwable ignored) {
                // nothing else to do
            }
        } finally {
            RenderSystem.enableDepthTest();
        }
    }
    /** True once a draw threw with the builder open; cleared when a forced end works. */
    private boolean drawStuck = false;

    private void drawQuads(final BufferBuilder buffer, final Tesselator tesselator, final Vec3 cam) {
        // One begin/draw per texture slot. RenderSystem.setShaderTexture binds
        // immediately, but the draw happens at Tesselator.end() - so a single
        // begin() over all ten slots painted every quad with whichever texture
        // was bound last, and the strip tiles never reached the screen at all.
        for (int tex = 0; tex < ShoreScan.TEX_COUNT; tex++) {
            boolean building = false;
            try {
                for (int i = 0; i < this.quads.count; i++) {
                    final ShoreScan.ShoreQuad q = this.quads.items[i];
                    if (q.tex != tex)
                        continue;
                    if (!building) {
                        // The film's slot samples the vanilla water sprite instead of
                        // one of our own PNGs - the colour still comes from the vertex,
                        // the same way vanilla paints the sea. See the section above
                        // waterSprite() for the three things that costs.
                        RenderSystem.setShaderTexture(0, TEXTURES[tex]);
                        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                        building = true;
                    }
                    emit(buffer, q, cam);
                }
            } catch (final Throwable t) {
                // Tesselator is process-wide and has no reset. If begin() itself throws
                // (or anything throws before "building" is set) the builder is left
                // mid-batch and every later pass writes into a buffer that is never
                // drawn: whole stretches of surf stop appearing, new stretches stop as
                // the buffer grows, and it follows you into other worlds because the
                // builder lives in the process. Only a game restart clears it.
                this.drawStuck = true;
                if (this.drawFailures++ < 5)
                    this.logger.warn("[ShoreWave] draw aborted for texture {}: {}", tex, t.toString());
                // Close the batch by force - that is the only way back to a usable
                // builder without restarting the game.
                try {
                    tesselator.end();
                } catch (final Throwable t2) {
                    if (this.drawFailures < 8)
                        this.logger.warn("[ShoreWave] forced end failed too: {}", t2.toString());
                }
            } finally {
                if (building) {
                    try {
                        tesselator.end();
                    } catch (final Throwable t) {
                        if (this.drawFailures++ < 8)
                            this.logger.warn("[ShoreWave] tesselator.end failed: {}", t.toString());
                    }
                }
            }
        }
    }

    /**
     * World coordinates, straight through. The camera is applied by the model
     * view that {@link #draw} installs, and nowhere else - doing it here as well
     * is what anchored the surf to the camera instead of the shore.
     */
    /**
     * Camera-relative vertices: the camera rotation comes from the model view,
     * the camera position is subtracted here. World coordinates with T(-cam) in
     * the matrix put the surf in the same place on screen - but fog_distance's
     * shape != 0 branch would then read the camera's distance from the world
     * origin as the vertex distance and repaint every fragment with FogColor.
     * See draw().
     */
    private void emit(final BufferBuilder buffer, final ShoreScan.ShoreQuad q, final Vec3 cam) {
        // getLightColor packs sky << 20 | block << 4, so the high half is sky.
        // uv2() wants (block, sky) - handing them over the other way round puts
        // the outdoor-constant sky term on the block axis and the foam never
        // darkens at night.
        final int block = q.light & 0xFFFF;
        final int sky = q.light >> 16 & 0xFFFF;
        final int r = q.rgb >> 16 & 0xFF;
        final int g = q.rgb >> 8 & 0xFF;
        final int b = q.rgb & 0xFF;
        final float cx = (float) cam.x;
        final float cy = (float) cam.y;
        final float cz = (float) cam.z;
        // Fold the tile-local coordinate back into 0..1 and then into the
        // sprite's window in the atlas. The fold has to happen BEFORE the remap:
        // taking a 0..3 coordinate straight into the sprite's window would walk
        // out of the sprite and into whatever is stitched beside it.
        for (int i = 0; i < 4; i++) {
            // One texture, one rule: the strip's u runs along the band in blocks
            // (period 4), v is the cross-section, and both stay inside 0..1 by
            // construction. The film's extra wrap() died with the film slot.
            buffer.vertex(q.wx[i] - cx, q.y - cy, q.wz[i] - cz)
                    .uv(q.u[i], q.v[i])
                    .color(r, g, b, q.alpha)
                    .uv2(block, sky)
                    .endVertex();
        }
    }

    /**
     * Gives the frame back the state the pass borrowed. Each piece is restored on
     * its own: a failure to drop the light layer must not also leave culling off,
     * because each has its own symptom and they get read separately.
     */
    private void restoreRenderState(final Minecraft mc) {
        try {
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        } catch (final Throwable t) {
            this.logger.warn("[ShoreWave] light layer restore failed: %s", t);
        }
        try {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } catch (final Throwable t) {
            this.logger.warn("[ShoreWave] render state restore failed: %s", t);
        }
    }

    // =====================================================================
    // Diagnostics
    // =====================================================================

    @Override
    protected void gatherDiagnostics(final CollectDiagnosticsEvent event) {
        if (!this.config.shoreWaveOptions.enableShoreWaves)
            return;
        // The one line that answers "is the surf pale because the texture is
        // being minified, because the film and the foam have collapsed into one
        // another, or because the film is drawn with the wrong paint" without
        // another round in game. Every value is read off the quads the pass will
        // actually draw and the grid it actually built - none is recomputed from
        // a second copy of the rules.
        //
        //   foam uv density  texels of texture per block of foam line. The design
        //                    gauge is 16 (one texel = 1/16 block). Anything much
        //                    below that is minification, which is what feeds the
        //                    sampler a deep mip and turns the band into a smear.
        //   film alpha p50   median film vertex alpha, 0..255. The film's own
        //                    opacity is FILM_ALPHA x this.
        //   foam alpha p50   median foam vertex alpha. White foam at alpha 100
        //                    over a film that is itself pale is one flat field;
        //                    this is the number that says so.
        //   order            draw order and the two lifts, verbatim.
        if (this.grid != null) {
            final double foamDensity = this.grid.lineBlocks <= 0F
                    ? 0D : this.grid.lineTexels / this.grid.lineBlocks;
            final double filmDensity = this.grid.filmBlocks <= 0F
                    ? 0D : this.grid.filmTexels / this.grid.filmBlocks;
            event.add(CollectDiagnosticsEvent.Section.Systems, String.format(
                    "Shore foam uv density=%.1f texel/block | order=%s | %d quads",
                    foamDensity, ShoreScan.DRAW_ORDER, this.quads.count));
        }
        // The geometric check's own tally, beside the uv density line: the bounds
        // that are compiled in, the widest span actually emitted, the box the
        // quads fell in, and how many were dropped for breaking a bound. A drop
        // count that is not zero means the picture is missing geometry on purpose.
        final ShoreScan.Audit audit = this.lastAudit;
        event.add(CollectDiagnosticsEvent.Section.Systems, String.format(
                "Shore geom: bounds span<=%d dist<=%d (RANGE %d RUNUP_MAX %d D_SCAN_MAX %d)"
                        + " | maxSpan %d maxDist %d | dropped span %d dist %d"
                        + " | world x %d..%d z %d..%d | nearest %.1f furthest %.1f",
                ShoreScan.MAX_QUAD_SPAN, ShoreScan.MAX_QUAD_DISTANCE,
                ShoreScan.RANGE, ShoreScan.RUNUP_MAX, ShoreScan.D_SCAN_MAX,
                audit.maxSpan, audit.furthest,
                audit.droppedSpan, audit.droppedDistance,
                audit.minX, audit.maxX, audit.minZ, audit.maxZ,
                audit.nearest, audit.furthest));
        // The fog the draw inherited, next to the geometry: when the film reads as
        // a pale sheet, this is the number that says whether the pass handed the
        // shader a colour at all.
        event.add(CollectDiagnosticsEvent.Section.Systems, String.format(
                "Shore fog: given %s/%s -> %s (pass bound %d)",
                fogPlane(this.frameFogStart), fogPlane(this.frameFogEnd),
                this.loggedFogNeutralised ? "neutralised" : "kept",
                ShoreScan.MAX_QUAD_DISTANCE,
                "off"));
        event.add(CollectDiagnosticsEvent.Section.Systems, String.format(
                "Shore: q %d (f%d s%d c%d x%d) foam %d runs %d p %.2f %.1f/%.1fms%s",
                this.quads.count,
                this.grid == null ? 0 : this.grid.filmQuads,
                this.grid == null ? 0 : this.grid.stripQuads,
                this.grid == null ? 0 : this.grid.capQuads,
                this.grid == null ? 0 : this.grid.cornerQuads,
                this.lastFoamCells, this.lastRunCells, this.lastProgress,
                this.lastScanNanos / 1_000_000D, this.lastBuildNanos / 1_000_000D,
                this.broken ? " BROKEN" : ""));
    }

    

    /**
     * Pins the eleven surf textures to NEAREST/NEAREST with no mip chain, once.
     *
     * <p>This is what {@code SimpleTexture} already gives them -
     * {@code load} calls {@code TextureUtil.prepareImage(id, 0, w, h)}, which
     * sets {@code GL_TEXTURE_MAX_LEVEL} to 0, and {@code AbstractTexture}
     * defaults {@code blur} and {@code mipmap} to false - so today it changes
     * nothing. It is here because that is the whole reason the band reads as
     * pixel art rather than as a smear, it is invisible in every other number
     * the pass logs, and one line in a loader is all it would take to lose it.
     * Written down, it is a policy; assumed, it is a coincidence.</p>
     */
    // =====================================================================
    // What the film samples
    // =====================================================================
    //
    // This used to be vanilla's water sprite, taken out of the BLOCK atlas, and
    // the cost of that was threefold: the sampler had to be bound to the atlas
    // object instead of to a file of ours, the quad's u/v had to be remapped into
    // the sprite's window (or the sampler read whichever neighbour happened to
    // sit at that position in the sheet), and the frame index had to be stepped
    // at emit time because sprite frames are advanced by the atlas ticker while
    // the quads are rebuilt on a scan cadence.
    //
    // None of that is true any more: the one remaining slot binds its own sheet,
    // our own 16x16 tile, like every other slot in this pass - there is no atlas
    // remap left in emit() and no frame index to step. The tile is pure white and
    // that is still deliberate: the film's colour comes from the biome water
    // colour at the vertex, which is exactly how vanilla paints the sea - a pale
    // texture multiplied by a tint.
    //
    // The trap this leaves behind, and the reason it is written down: a uniformly
    // opaque tile looks correct through ANY uv, so nothing about the picture tells
    // you the sampling is wrong. emit() kept folding v into a 1/32 window (the
    // frame layout of the 16x512 sheet it no longer sampled), which squeezed the
    // whole film into half a texel of height - wrong mip level, and the opacity
    // and grain that came with it lost at distance, with no visible symptom at
    // arm's length. A stale assumption about where a texture comes from is
    // invisible until something measures it; the audit's alpha buckets are what
    // finally did.

    /**
     * The atlas window of the water sprite, or null when the film keeps its own
     * tile. Ordered u0, v0, du, dv.
     */
    private void pinPixelFilter() {
        if (this.filterPinned)
            return;
        this.filterPinned = true;
        final net.minecraft.client.renderer.texture.TextureManager manager =
                Minecraft.getInstance().getTextureManager();
        for (final ResourceLocation location : TEXTURES) {
            try {
                manager.getTexture(location).setFilter(false, false);
            } catch (final Throwable t) {
                this.logger.warn("[ShoreWave] could not pin the filter on %s: %s", location, t);
            }
        }
    }
}
