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
 * stack. See {@link #draw} for the derivation; {@link #logInheritedMatrix}
 * reports what the inherited matrix actually turned out to be.</p>
 *
 * <p>Sound is deliberately not synchronised: {@code beach.ogg} carries five
 * swells in thirty seconds at irregular 0.75-10.55s spacing, so driving the
 * picture from it would make the sea look broken. The visual keeps its own
 * period and the existing ambience keeps playing.</p>
 */
public class ShoreWaveHandler extends AbstractClientHandler {

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[ShoreScan.TEX_COUNT];

    static {
        TEXTURES[ShoreScan.TEX_FILM] = texture("shore_foam_film");
        TEXTURES[ShoreScan.TEX_STRIP] = texture("shore_foam_strip");
        TEXTURES[ShoreScan.TEX_CAP] = texture("shore_foam_cap");
        for (int w = 0; w < 4; w++) {
            TEXTURES[ShoreScan.TEX_CONVEX + w] = texture("shore_foam_corner_convex_w" + w);
            TEXTURES[ShoreScan.TEX_CONCAVE + w] = texture("shore_foam_corner_concave_w" + w);
        }
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
    private long framesDrawn = 0L;
    private long lastRenderNanos = 0L;

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
        logFilmDebugColor();
        final long mark = System.nanoTime();
        ShoreScan.build(this.grid, this.lastProgress, level, this.quads,
                this.config.shoreWaveOptions.shoreFilmDebugColor);
        // Replaces the surf with opaque magenta blocks - see ShoreScan.buildDebug.
        if (this.config.shoreWaveOptions.debugSolidBlocks)
            ShoreScan.buildDebug(this.grid, this.quads);
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

    // =====================================================================
    // Diagnostics logging
    // =====================================================================
    //
    // Every number below is read off the structures the pass actually uses -
    // the grids it scanned and the quads it will draw. Nothing is recomputed
    // from a second copy of the rules: a diagnostic that disagrees with the
    // renderer is worse than no diagnostic at all.

    /**
     * What the frame left behind, before this pass replaces it - said once and
     * then never again, because it is a property of the frame, not of the surf.
     *
     * <p>The transform bug this chase started with is invisible in every other
     * number the pass logs: the quad counts were always fine, the geometry was
     * always on the shore, the alpha was always full. Only the matrix knows, and
     * sixteen floats cannot be read at a glance - so it is printed as
     * <em>effects</em>: the translation against {@code -cam}, and then two
     * points and a basis pushed through it.</p>
     *
     * <p>{@code eye+5*look} through the inherited matrix is the decisive one. It
     * reads {@code (0,0,-5)} if and only if the frame has already installed the
     * camera rotation into the model view - which is the whole question of
     * whether world coordinates need {@code T(-cam)} alone or
     * {@code R * T(-cam)}. The three world axes and the camera's own three
     * vectors are printed beside it so that the rotation itself, not just its
     * effect on one line, can be read back out of the log when the two
     * disagree.</p>
     */
    private void logInheritedMatrix(final Camera camera, final Vec3 cam) {
        if (!logging() || this.loggedMatrix)
            return;
        final Matrix4f inherited = RenderSystem.getModelViewMatrix();
        final Vector3f look = camera.getLookVector();
        this.logger.info("[ShoreWave] inherited model view translate (%.2f,%.2f,%.2f) | cam (%.1f,%.1f,%.1f)"
                        + " | -cam (%.1f,%.1f,%.1f)",
                inherited.m30(), inherited.m31(), inherited.m32(),
                cam.x, cam.y, cam.z, -cam.x, -cam.y, -cam.z);
        // The decisive probe. (0,0,-5) here means the frame already carries R and
        // world coordinates only need T(-cam); anything else means the rotation
        // has to be supplied by this pass - which is what draw() now does, so the
        // line is recorded rather than acted upon.
        this.logger.info("[ShoreWave] inherited model view: eye -> %s | eye+5*look -> %s"
                        + " [reads (0,0,-5) only if the frame already has R here] | basis %s",
                position(inherited, cam.x, cam.y, cam.z),
                position(inherited, cam.x + look.x() * 5D, cam.y + look.y() * 5D, cam.z + look.z() * 5D),
                basis(inherited, camera));
    }

    /**
     * Verifies the matrix this pass just installed: two points with known
     * answers, and then the basis.
     *
     * <p>The eye must map to the origin and five blocks of look direction must
     * map to {@code -Z}. Both are properties of the transform, not of the
     * terrain, so they hold whether or not there is any surf in the world, and
     * one line settles "is the camera transform right" without another round
     * trial in game. The basis is the same statement six more times: world
     * {@code x^/y^/z^} must land on the camera's own axes and the camera's
     * {@code look/up/left} on {@code (0,0,-1)/(0,1,0)/(-1,0,0)} exactly.</p>
     *
     * <p>{@code sourceDelta} checks where the rotation came from, not what it
     * is: it compares the pose stack handed to the event against the same
     * rotation rebuilt from the camera, {@code Ry(180) * conj(rotation())}. It
     * prints 0.000000 when they agree. They are the same matrix - the 180 is the
     * difference between "five blocks in front" and "five blocks behind" - and
     * a large number here would mean the pose stack is not the level view at
     * this stage, which is the single assumption {@link #draw} rests on and
     * therefore worth a number rather than a promise.</p>
     */
    private void logMatrixOnce(final Camera camera, final Vec3 cam, final Matrix4f source) {
        if (!logging() || this.loggedMatrix)
            return;
        this.loggedMatrix = true;
        final Matrix4f m = RenderSystem.getModelViewMatrix();
        final Vector3f look = camera.getLookVector();

        this.logger.info("[ShoreWave] matrix check: eye -> %s [want ~(0,0,0)]"
                        + " | eye+5*look -> %s [want ~(0,0,-5)]",
                position(m, cam.x, cam.y, cam.z),
                position(m, cam.x + look.x() * 5D, cam.y + look.y() * 5D, cam.z + look.z() * 5D));
        this.logger.info("[ShoreWave] matrix check: basis %s"
                        + " [want x^(-1,0,0) y^(0,1,0) z^(0,0,-1); look(0,0,-1) up(0,1,0) left(-1,0,0)]"
                        + " | pose-stack R vs Ry(180)*conj(camera.rotation()) max delta %.6f",
                basis(m, camera), rotationDelta(source, camera));
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
        // A window with no beach next to no water is why most "nothing draws"
        // reports start: say which of the two is missing.
        if (g.beachCells == 0 || g.seaCells == 0)
            this.logger.info("[ShoreWave] no shoreline in window: beach cells %d, sea cells %d "
                            + "- waterTop %d must be the topmost water block index (getSeaLevel()-1)",
                    g.beachCells, g.seaCells, g.waterTop);
        this.sawTerrain = true;
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

    /**
     * What actually reached the buffer this frame, counted per texture group,
     * plus one sample in camera space. That last part is the whole point: if
     * the sample lands hundreds of blocks from the camera, the geometry is
     * right and the transform is wrong, and no amount of staring at the shader
     * will tell you which.
     */
    private void logRender(final Vec3 cam) {
        if (!logging())
            return;
        this.framesDrawn++;
        if (this.framesDrawn > 1 && this.framesDrawn % 200 != 0)
            return;
        final int[] perGroup = new int[ShoreScan.TEX_COUNT];
        int alphaZero = 0;
        int zeroArea = 0;
        for (int i = 0; i < this.quads.count; i++) {
            final ShoreScan.ShoreQuad q = this.quads.items[i];
            perGroup[q.tex]++;
            if (q.alpha <= 0)
                alphaZero++;
            // A quad whose four corners collapsed onto each other draws nothing
            // and does not fail - it just is not there.
            if (q.wx[0] == q.wx[1] && q.wx[0] == q.wx[2] && q.wx[0] == q.wx[3]
                    && q.wz[0] == q.wz[1] && q.wz[0] == q.wz[2] && q.wz[0] == q.wz[3])
                zeroArea++;
        }
        final StringBuilder groups = new StringBuilder();
        for (int t = 0; t < perGroup.length; t++)
            if (perGroup[t] > 0)
                groups.append(' ').append(TEX_NAMES[t]).append('=').append(perGroup[t]);
        final StringBuilder detail = new StringBuilder(200);
        detail.append(String.format(
                "[ShoreWave] frame %d cam (%.1f,%.1f,%.1f) | %d quads -> %d (%.2fms) |%s",
                this.framesDrawn, cam.x, cam.y, cam.z,
                this.quads.count, this.quads.count * 4, this.lastRenderNanos / 1_000_000D, groups));
        if (alphaZero > 0)
            detail.append(" | alpha=0 skipped in build: ").append(alphaZero);
        if (zeroArea > 0)
            detail.append(" | ZERO-AREA: ").append(zeroArea);
        // "Invisible" and "too faint" have different fixes, and both look like
        // nothing at all from the player's chair. One line settles it.
        int faint = 0, mid = 0, solid = 0;
        for (int i = 0; i < this.quads.count; i++) {
            final int a = this.quads.items[i].alpha;
            if (a < 32) faint++;
            else if (a < 128) mid++;
            else solid++;
        }
        detail.append(String.format(" | alpha <32:%d 32-127:%d >=128:%d", faint, mid, solid));
        // "It is drawn but too far to see" and "it is drawn in the wrong place"
        // look identical on screen. Distance to the NEAREST quad separates
        // them: if the closest foam is 20 blocks away while the player stands
        // on the beach, the fault is upstream of rendering entirely. The single
        // quad0 sample could not show this - it is the outermost grid row by
        // construction, so it is always the furthest one.
        float nearest = Float.MAX_VALUE;
        int within8 = 0;
        final float[] dists = new float[this.quads.count];
        for (int i = 0; i < this.quads.count; i++) {
            final ShoreScan.ShoreQuad q = this.quads.items[i];
            final float d = (float) Math.hypot(q.wx[0] - cam.x, q.wz[0] - cam.z);
            dists[i] = d;
            if (d < nearest) nearest = d;
            if (d <= 8F) within8++;
        }
        if (this.quads.count > 0) {
            java.util.Arrays.sort(dists);
            detail.append(String.format(" | dist nearest %.1f median %.1f furthest %.1f (<=8: %d)",
                    nearest, dists[dists.length / 2], dists[dists.length - 1], within8));
        }
        detail.append(String.format(" | fog given %s/%s%s",
                fogPlane(this.frameFogStart), fogPlane(this.frameFogEnd),
                this.loggedFogNeutralised ? " (neutralised by this pass)" : ""));
        if (this.quads.count > 0) {
            final ShoreScan.ShoreQuad q = this.quads.items[0];
            detail.append(String.format(" | sample cam-space (%.2f,%.2f,%.2f) y %.3f",
                    q.wx[0] - (float) cam.x, q.y - (float) cam.y, q.wz[0] - (float) cam.z, q.y));
            // A fully lit foam drawn at light 0 is a black line on water, which
            // reads as "nothing is drawn". Cheap to rule out once.
            detail.append(String.format(" | light raw 0x%08X block %d sky %d rgb 0x%06X",
                    q.light, q.light & 0xFFFF, q.light >> 16 & 0xFFFF, q.rgb));
        }
        this.logger.info(detail.toString());
    }

    /** Names for the log, same order as the texture slots. */
    private static final String[] TEX_NAMES = buildTexNames();

    private static String[] buildTexNames() {
        final String[] n = new String[ShoreScan.TEX_COUNT];
        n[ShoreScan.TEX_FILM] = "film";
        n[ShoreScan.TEX_STRIP] = "strip";
        n[ShoreScan.TEX_CAP] = "cap";
        for (int w = 0; w < 4; w++) {
            n[ShoreScan.TEX_CONVEX + w] = "convex" + w;
            n[ShoreScan.TEX_CONCAVE + w] = "concave" + w;
        }
        return n;
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
        logFog(fogStart, fogEnd, fogNeutralised);
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
            logInheritedMatrix(event.getCamera(), cam);
            this.lastRenderNanos = System.nanoTime();
            draw(event, event.getCamera(), cam);
            this.lastRenderNanos = System.nanoTime() - this.lastRenderNanos;
            logRender(cam);
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
     * Says what fog the pass found, once per change - and, when it neutralised
     * it, why that is the difference between a blue film and a pale sheet.
     *
     * <p>Logged rather than assumed: the value is a property of the frame and of
     * whatever ran before this pass, and nothing else in the mod writes it down.
     * A player report of "the surf is white again" is then one line away from
     * being a number.</p>
     */
    private void logFog(final float start, final float end, final boolean neutralised) {
        if (!logging())
            return;
        if (Float.compare(start, this.loggedFogStart) == 0
                && Float.compare(end, this.loggedFogEnd) == 0
                && neutralised == this.loggedFogNeutralised)
            return;
        this.loggedFogStart = start;
        this.loggedFogEnd = end;
        this.loggedFogNeutralised = neutralised;
        if (neutralised)
            this.logger.info("[ShoreWave] fog start %s end %s -> %s (bound %d)%s",
                    fogPlane(start), fogPlane(end),
                    neutralised ? "NEUTRALISED for this pass" : "kept",
                    ShoreScan.MAX_QUAD_DISTANCE,
                    neutralised ? ": a range this short replaces every vertex colour with FogColor,"
                            + " so the film and the foam would both paint the fog colour" : "");
    }

    /**
     * One line when the film's debug colour is switched, so the switch cannot be
     * silently ignored - the one thing a troubleshooting toggle must never do.
     *
     * <p>Not gated on {@code debugLogging} when it turns ON: the player flipped a
     * switch whose whole purpose is to be looked at, and the confirmation is the
     * only proof the build they launched actually contains it. Turning it off
     * again is ordinary state and stays behind the gate.</p>
     */
    private void logFilmDebugColor() {
        final boolean on = this.config.shoreWaveOptions.shoreFilmDebugColor;
        if (on == this.loggedFilmDebugColor)
            return;
        this.loggedFilmDebugColor = on;
        if (on)
            this.logger.info("[ShoreWave] film debug color ON: rgb=FF00FF - the water film keeps its"
                    + " geometry, height and alpha and paints solid magenta; back to normal the moment"
                    + " the option is turned off");
        else if (logging())
            this.logger.info("[ShoreWave] film debug color OFF: the film is back to the biome water colour");
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
     * {@code AFTER_WEATHER}. {@link #logMatrixOnce} re-derives the identity
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
     * {@link #logInheritedMatrix} still reports it, for the record.</p>
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
            mv.setIdentity();
            mv.mulPoseMatrix(view);
            mv.translate(-cam.x, -cam.y, -cam.z);
            RenderSystem.applyModelViewMatrix();
            logMatrixOnce(camera, cam, view);
            drawQuads(buffer, tesselator);
        } finally {
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private void drawQuads(final BufferBuilder buffer, final Tesselator tesselator) {
        // One begin/draw per texture slot. RenderSystem.setShaderTexture binds
        // immediately, but the draw happens at Tesselator.end() - so a single
        // begin() over all ten slots painted every quad with whichever texture
        // was bound last, and the strip tiles never reached the screen at all.
        for (int tex = 0; tex < ShoreScan.TEX_COUNT; tex++) {
            boolean building = false;
            for (int i = 0; i < this.quads.count; i++) {
                final ShoreScan.ShoreQuad q = this.quads.items[i];
                if (q.tex != tex)
                    continue;
                if (!building) {
                    RenderSystem.setShaderTexture(0, TEXTURES[tex]);
                    buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    building = true;
                }
                emit(buffer, q);
            }
            if (building)
                tesselator.end();
        }
    }

    /**
     * World coordinates, straight through. The camera is applied by the model
     * view that {@link #draw} installs, and nowhere else - doing it here as well
     * is what anchored the surf to the camera instead of the shore.
     */
    private static void emit(final BufferBuilder buffer, final ShoreScan.ShoreQuad q) {
        // getLightColor packs sky << 20 | block << 4, so the high half is sky.
        // uv2() wants (block, sky) - handing them over the other way round puts
        // the outdoor-constant sky term on the block axis and the foam never
        // darkens at night.
        final int block = q.light & 0xFFFF;
        final int sky = q.light >> 16 & 0xFFFF;
        final int r = q.rgb >> 16 & 0xFF;
        final int g = q.rgb >> 8 & 0xFF;
        final int b = q.rgb & 0xFF;
        for (int i = 0; i < 4; i++) {
            buffer.vertex(q.wx[i], q.y, q.wz[i])
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
                    "Shore foam uv density=%.1f texel/block | film alpha p50=%d | foam alpha p50=%d"
                            + " | order=%s | film uv density=%.1f texel/block (%d quads)",
                    foamDensity, medianAlpha(ShoreScan.TEX_FILM, true), medianAlpha(ShoreScan.TEX_FILM, false),
                    ShoreScan.DRAW_ORDER, filmDensity, this.quads.count));
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
                "Shore fog: given %s/%s -> %s (pass bound %d, film debug %s)",
                fogPlane(this.frameFogStart), fogPlane(this.frameFogEnd),
                this.loggedFogNeutralised ? "neutralised" : "kept",
                ShoreScan.MAX_QUAD_DISTANCE,
                this.config.shoreWaveOptions.shoreFilmDebugColor ? "ON rgb=FF00FF" : "off"));
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
     * Median vertex alpha of one side of the pass, 0 when that side is empty.
     *
     * @param film true selects the film ({@link ShoreScan#TEX_FILM}), false the
     *             whole foam line - strip, caps and corner tiles.
     */
    private int medianAlpha(final int filmSlot, final boolean film) {
        final int n = this.quads.count;
        if (n == 0)
            return 0;
        final int[] sorted = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            final ShoreScan.ShoreQuad q = this.quads.items[i];
            if ((q.tex == filmSlot) == film)
                sorted[k++] = q.alpha;
        }
        if (k == 0)
            return 0;
        java.util.Arrays.sort(sorted, 0, k);
        return sorted[k / 2];
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
