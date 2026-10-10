package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One plant model's attachment geometry, built once per model and cached (see
 * {@link FoliageProfileCache}). Everything here is derived from what the game
 * actually renders - {@code BakedModel.getQuads(state, null, random)} with the
 * render-seeded random - so grass, ferns, flowers and modded plants all land
 * without a single hand-tuned constant.
 *
 * <p>Per plane (a deduped unculled quad): the sprite's alpha mask is
 * rasterized, and the anchors are the TOP opaque pixel of each mask column
 * (where rain and dew collect) plus the mask endpoints (blade tips, where a
 * droplet lets go). Slide paths are pre-baked by a constrained descent over
 * the mask - prefer straight down, never leave opaque pixels - so a droplet
 * slides ALONG the blade, never across a gap. The pixel-to-model mapping is
 * the plane's affine (parallelogram assumption, verified against the fourth
 * corner; non-parallelogram planes are dropped).</p>
 *
 * <p>Two review fixes are baked in here:</p>
 * <ul>
 *   <li><b>Dedup key is winding-independent</b> (混元 review B): the four
 *       corners are sorted lexicographically before hashing. A cross model's
 *       north/south faces share geometry with reversed winding; a naive
 *       corner-order key would fail to dedupe them and double every anchor.
 *       Acceptance: a cross model must dedupe to exactly 2 planes.</li>
 *   <li><b>Alpha reads frame 0</b> of animated sprites so the profile is
 *       stable across ticks.</li>
 * </ul>
 */
public final class FoliageProfile {

    /** Shared empty answer - plants whose model has no unculled quads. */
    public static final FoliageProfile EMPTY = new FoliageProfile(List.of());

    /** One attachment point, in MODEL space (0..1 within the block). */
    /**
     * One landing spot. {@code room} is how wide a unit this spot can carry:
     * the largest {@code r <= 3} whose {@code (2r+1)^2} window is mostly leaf
     * (0 = the spot sits on an edge or a leaf tip, 2 = a 5x5 window is solid).
     *
     * <p>The snow layer reads it to keep a wide unit off a narrow blade - a
     * sheet dropped on a leaf tip hangs half of itself in the air. Measured on
     * a real grass sprite: without this, 25% of the snow pixels were overhang;
     * with it, back under 10%. The bead layer ignores it (a bead is 0.2 of a
     * block and only ever rides a column top, which is solid by definition).</p>
     */
    public record Anchor(int plane, float x, float y, float z, float weight, int room) {
    }

    /** One pre-baked descent, waypoints in MODEL space, {@code length} in blocks. */
    public record SlidePath(int plane, float[] pts, float length) {
    }

    /**
     * One deduped unculled quad plane: corners, unit normal, anchors, paths,
     * and {@code scatter} - the whole-plant landing spots for snow units.
     */
    public record Plane(float[] corners, float nx, float ny, float nz,
                        Anchor[] anchors, SlidePath[] paths, Anchor[] scatter) {
    }

    // Trace counters - read by the handlers' diagnostics, reset per build.
    public static int statQuads = 0;
    public static int statDeduped = 0;
    public static int statDegenerate = 0;
    public static int statNonParallelogram = 0;
    public static int statNoMask = 0;
    /** 散布锚点计数（诊断用，构建期累加）。 */
    public static int statScatter = 0;

    /**
     * Snapshot of the LAST build. The {@code stat*} counters above are cleared
     * at the top of every build and are only meaningful while one is running,
     * so "why did this plant produce no anchors" cannot be answered from them
     * after the fact. These are written when the build returns.
     */
    public static int lastQuads = 0;
    public static int lastDeduped = 0;
    public static int lastDegenerate = 0;
    public static int lastNonParallelogram = 0;
    public static int lastNoMask = 0;
    public static int lastPlanes = 0;
    public static int lastAnchors = 0;
    public static int lastPaths = 0;
    public static String lastModel = "<none>";

    /**
     * 散布锚点数（每平面）。雪不像水珠那样只挂列顶 —— 整株上中下都要有落点
     * （维护者：只贴列顶太单调）。24 x 2 平面 = 48 个落点；定标页实测每株
     * 16 个单元时白覆盖率 ~33%，落点必须比它多才够挑。
     */
    private static final int MAX_SCATTER_PER_PLANE = 24;

    /** 散布点的最小间距（贴图像素），防两个雪单元叠在一起（重叠是纯浪费）。 */
    private static final int SCATTER_MIN_DIST_PX = 2;

    private static final int MAX_ANCHORS_PER_PLANE = 128;
    private static final int MAX_PATH_POINTS = 48;
    /** How far a sliding path may lean sideways before it is cut off. */
    private static final int MAX_PATH_DRIFT = 3;
    /**
     * How far above the block's bottom edge a bead may sit, in BLOCKS.
     *
     * <p>A plant's sprite puts the base of its stem on the block's own bottom
     * edge, so a bead that rides the mask all the way down is standing in the
     * block the plant is growing out of - and vanilla reads a particle's light
     * from the block it is inside, which is zero in there, so the bead renders
     * BLACK for as long as it lasts. The old "stop a row short of the last"
     * guard was not enough: the row above the last is only 0.09 of a block up,
     * which is less than a bead's own radius, and a plant whose model is
     * shorter than a block (crops, flowerbeds, most modded plants) puts even
     * that row underground.</p>
     *
     * <p>0.1 is the radius of the largest bead the particle can make
     * ({@code VANILLA_DRIP_QUAD_SIZE}), so holding the bead's centre this high
     * keeps the WHOLE quad inside the plant's block - nothing dips into the
     * ground, and the light sample can only ever come from a plant, never from
     * the soil.</p>
     */
    private static final float GROUND_CLEARANCE = 0.10F;
    /**
     * How far above the block's own bottom edge a DESCENT PATH may run, in
     * blocks.
     *
     * <p>Round 44: 0.0 - it used to be {@link #GROUND_CLEARANCE}, 0.10. The
     * clearance is about where a bead RESTS: a perch down at the base of the
     * stem is a bead standing in the soil, and that is still refused
     * ({@link #GROUND_CLEARANCE} is untouched for anchors and for the scatter
     * points). A path is not a resting place - it is the blade going down, and
     * cutting it at 0.10 cost the last waypoint of every baked descent.
     * Measured over 17 real sprites (_slide_bottom_sim.py): the lowest point of
     * the mask is y=0.094 and the guard left every path ending at 0.156, one
     * plant pixel short of the bottom of the blade, on all 17. The reason for
     * the guard was a bead down there reading the soil's light and rendering
     * black; the particle lights itself from above now (see the light override
     * in FoliageDripParticle), so the last waypoint stays and the slide reaches
     * the bottom of the plant.</p>
     */
    private static final float PATH_FLOOR_CLEARANCE = 0.0F;
    private static final float ALPHA_THRESHOLD = 8.0F;

    /**
     * The pre-baked descent whose START lies closest to this anchor - a bead
     * slides down the blade it is actually sitting on.
     *
     * <p>The paths are baked from a spread of column tops and the bead is
     * given a WEIGHTED RANDOM anchor, so the two are unrelated: picking one
     * at random (the old code) meant the bead's first move was a leap to
     * wherever that path began - sometimes half a sprite sideways, and when
     * the bead's own anchor sat lower, upward. Nearest-start keeps the opening
     * move short; {@code beginSlide} still discards whatever is left above the
     * bead, so a climb can never survive.</p>
     */
    public static SlidePath nearestPath(final SlidePath[] paths, final float ax, final float ay, final float az) {
        SlidePath best = null;
        float bestD = Float.MAX_VALUE;
        for (final SlidePath p : paths) {
            if (p == null || p.pts().length < 6)
                continue;
            final float dx = p.pts()[0] - ax;
            final float dy = p.pts()[1] - ay;
            final float dz = p.pts()[2] - az;
            final float d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private final List<Plane> planes;

    private FoliageProfile(List<Plane> planes) {
        this.planes = planes;
    }

    public List<Plane> planes() {
        return this.planes;
    }

    public boolean isEmpty() {
        return this.planes.isEmpty();
    }

    /**
     * Builds the profile for one model. {@code seed} is the state's render
     * seed, so weighted-variant models resolve exactly as the renderer would.
     */
    public static FoliageProfile build(final BakedModel model, final BlockState state, final long seed) {
        final FoliageProfile result = build0(model, state, seed);
        lastQuads = statQuads;
        lastDeduped = statDeduped;
        lastDegenerate = statDegenerate;
        lastNonParallelogram = statNonParallelogram;
        lastNoMask = statNoMask;
        lastPlanes = result.planes.size();
        int anchors = 0;
        int paths = 0;
        for (final Plane p : result.planes) {
            anchors += p.anchors().length;
            paths += p.paths().length;
        }
        lastAnchors = anchors;
        lastPaths = paths;
        lastModel = String.valueOf(model);
        return result;
    }

    private static FoliageProfile build0(final BakedModel model, final BlockState state, final long seed) {
        statQuads = 0;
        statDeduped = 0;
        statDegenerate = 0;
        statNonParallelogram = 0;
        statNoMask = 0;

        final RandomSource random = RandomSource.create(seed);
        final List<BakedQuad> quads = model.getQuads(state, null, random);
        if (quads.isEmpty())
            return EMPTY;

        // Decode, then dedupe with the WINDING-INDEPENDENT key (review B).
        final List<float[]> cornerList = new ArrayList<>(quads.size());
        final List<float[]> uvList = new ArrayList<>(quads.size());
        final List<TextureAtlasSprite> spriteList = new ArrayList<>(quads.size());
        final List<String> seen = new ArrayList<>(quads.size());
        for (final BakedQuad quad : quads) {
            statQuads++;
            final int[] v = quad.getVertices();
            if (v.length < 32)
                continue;   // 4 vertices x 8 ints - anything else is not a block quad
            final float[] c = new float[12];
            final float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                c[i * 3] = Float.intBitsToFloat(v[i * 8]);
                c[i * 3 + 1] = Float.intBitsToFloat(v[i * 8 + 1]);
                c[i * 3 + 2] = Float.intBitsToFloat(v[i * 8 + 2]);
                uv[i * 2] = Float.intBitsToFloat(v[i * 8 + 4]);
                uv[i * 2 + 1] = Float.intBitsToFloat(v[i * 8 + 5]);
            }
            final String key = signature(c);
            boolean dup = false;
            for (final String s : seen) {
                if (s.equals(key)) {
                    dup = true;
                    break;
                }
            }
            if (dup)
                continue;
            seen.add(key);
            statDeduped++;
            cornerList.add(c);
            uvList.add(uv);
            spriteList.add(quad.getSprite());
        }

        final List<Plane> planes = new ArrayList<>(cornerList.size());
        for (int i = 0; i < cornerList.size(); i++) {
            final Plane plane = buildPlane(cornerList.get(i), uvList.get(i), spriteList.get(i), planes.size());
            if (plane != null)
                planes.add(plane);
        }
        return planes.isEmpty() ? EMPTY : new FoliageProfile(planes);
    }

    /**
     * Winding-independent geometry key (混元 review B): sort the four corners
     * lexicographically, then concatenate. A face and its reversed winding
     * produce the same sorted tuple.
     */
    private static String signature(final float[] c) {
        final float[][] pts = new float[4][3];
        for (int i = 0; i < 4; i++) {
            pts[i][0] = Math.round(c[i * 3] * 1000F) / 1000F;
            pts[i][1] = Math.round(c[i * 3 + 1] * 1000F) / 1000F;
            pts[i][2] = Math.round(c[i * 3 + 2] * 1000F) / 1000F;
        }
        Arrays.sort(pts, (a, b) -> {
            for (int k = 0; k < 3; k++) {
                final int d = Float.compare(a[k], b[k]);
                if (d != 0)
                    return d;
            }
            return 0;
        });
        final StringBuilder sb = new StringBuilder(96);
        for (final float[] p : pts)
            sb.append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(';');
        return sb.toString();
    }

    /**
     * Mask, anchors and paths for one plane. Returns null for planes this
     * feature cannot use (degenerate UV mapping, non-parallelogram, empty
     * mask) - each rejection bumps its trace counter.
     */
    private static Plane buildPlane(final float[] c, final float[] uv, final TextureAtlasSprite sprite, final int planeIndex) {
        final int mw = sprite.contents().width();
        final int mh = sprite.contents().height();

        // Sprite-space UV per corner (0..1 across the sprite).
        final float su0 = (uv[0] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
        final float sv0 = (uv[1] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        final float su1 = (uv[2] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
        final float sv1 = (uv[3] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        final float su2 = (uv[4] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
        final float sv2 = (uv[5] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());

        // Affine basis: model pos = P0 + d1*a + d2*b for mask coords (a,b).
        final float e1x = su1 - su0, e1y = sv1 - sv0;
        final float e2x = su2 - su0, e2y = sv2 - sv0;
        final float det = e1x * e2y - e2x * e1y;
        if (Math.abs(det) < 1.0e-9F) {
            statDegenerate++;
            return null;
        }
        final float d1x = c[3] - c[0], d1y = c[4] - c[1], d1z = c[5] - c[2];
        // VERTEX 2, not vertex 3 - and this is the bug that made the whole
        // feature produce nothing. Vanilla bakes a quad's corners in the order
        // (top-right, bottom-right, bottom-left, top-left) - see FaceInfo -
        // so v0->v1 is an EDGE and v0->v2 is the DIAGONAL. e2 above is the
        // v0->v2 UV delta, so the geometric vector it pairs with has to be
        // v0->v2 as well. Pairing the v0->v3 edge with the v0->v2 UV diagonal
        // maps every mask pixel onto the wrong axis: the parallelogram check
        // below then fails by a full quad width, EVERY plane is dropped, and
        // the profile comes back empty - no anchors, no drops, no dew, and
        // nothing in the log to say why.
        final float d2x = c[6] - c[0], d2y = c[7] - c[1], d2z = c[8] - c[2];

        // Parallelogram check against the fourth corner.
        {
            final float s3x = (uv[6] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
            final float s3y = (uv[7] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
            final float rx = s3x - su0, ry = s3y - sv0;
            final float a = (rx * e2y - ry * e2x) / det;
            final float b = (ry * e1x - rx * e1y) / det;
            final float px = c[0] + d1x * a + d2x * b;
            final float py = c[1] + d1y * a + d2y * b;
            final float pz = c[2] + d1z * a + d2z * b;
            final float ex = px - c[9], ey = py - c[10], ez = pz - c[11];
            if (ex * ex + ey * ey + ez * ez > 1.0e-4F) {
                statNonParallelogram++;
                return null;
            }
        }

        // Model position of a mask pixel center (the affine, wrapped).
        final float[] mapped = new float[3];
        final Affine mapper = new Affine(c, d1x, d1y, d1z, d2x, d2y, d2z, su0, sv0, e1x, e1y, e2x, e2y, det, mw, mh);

        // Alpha mask (frame 0 - stable across animated sprites).
        final boolean[] opaque = new boolean[mw * mh];
        int opaqueCount = 0;
        for (int py = 0; py < mh; py++) {
            for (int px = 0; px < mw; px++) {
                if (((sprite.getPixelRGBA(0, px, py) >> 24) & 0xFF) >= ALPHA_THRESHOLD) {
                    opaque[py * mw + px] = true;
                    opaqueCount++;
                }
            }
        }
        if (opaqueCount == 0) {
            statNoMask++;
            return null;
        }

        // Column tops only - the top of each mask column is where water
        // actually collects on a blade.
        //
        // Two things were wrong here and both showed up as "the bead is not
        // on the plant": the old build added a SECOND anchor family -
        // "endpoints", pixels with <= 1 opaque neighbour - and those are the
        // whiskers at the corners of the sprite, where there is no texture
        // around them at all (maintainer: "贴在草的左右上角没有纹理的地方").
        // And a column top with nothing UNDER it is that same whisker, so a
        // bead centred there hangs half off the blade and reads as floating.
        // Both are gone: a perch has to be a pixel with blade below it.
        final List<int[]> topPixels = new ArrayList<>(mw);
        final List<float[]> topWeights = new ArrayList<>(mw);
        for (int px = 0; px < mw; px++) {
            for (int py = 0; py < mh; py++) {
                if (!opaque[py * mw + px])
                    continue;
                if (py + 1 >= mh || !opaque[(py + 1) * mw + px])
                    continue;   // nothing under it - not a perch
                topPixels.add(new int[] { px, py });
                topWeights.add(new float[] {
                        (float) Math.pow(1.0F - py / (float) Math.max(1, mh - 1), 1.5) + 0.1F });
                break;
            }
        }

        final List<Anchor> anchors = new ArrayList<>(topPixels.size());
        for (int i = 0; i < topPixels.size(); i++) {
            final int[] p = topPixels.get(i);
            mapper.map(p[0], p[1], mapped);
            // A perch down at the base of the stem is a bead standing in the
            // soil - see GROUND_CLEARANCE. It is not an attachment point.
            if (mapped[1] < GROUND_CLEARANCE)
                continue;
            // room 0: a bead rides a column top, which is solid by definition,
            // and it is far too small to overhang anything.
            anchors.add(new Anchor(planeIndex, mapped[0], mapped[1], mapped[2],
                    topWeights.get(i)[0], 0));
        }
        anchors.sort((x, y) -> Float.compare(y.weight(), x.weight()));
        final List<Anchor> capped = anchors.size() > MAX_ANCHORS_PER_PLANE
                ? anchors.subList(0, MAX_ANCHORS_PER_PLANE)
                : anchors;

        // Slide paths: constrained descent (prefer straight down, never leave
        // the mask), decimated + one Chaikin pass to kill the pixel staircase,
        // then mapped to model space. Starts spread across the column tops.
        final int pathCount = Math.max(2, Math.min(6, opaqueCount / 24));
        final List<SlidePath> paths = new ArrayList<>(pathCount);
        if (!topPixels.isEmpty()) {
            for (int k = 0; k < pathCount; k++) {
                final int[] start = topPixels.get((k * topPixels.size()) / pathCount);
                List<int[]> pixels = descend(opaque, mw, mh, start[0]);
                if (pixels == null)
                    continue;
                pixels = smooth(pixels);
                // Round 44: the path runs to the bottom of the plant - see
                // PATH_FLOOR_CLEARANCE. It used to stop at GROUND_CLEARANCE
                // (0.10) because the bottom rows of the mask are the base of
                // the stem, which sits in the block below, and a bead there
                // read the soil's light. The particle lights itself from above
                // now, so the lowest waypoint stays and the bead rides the
                // blade all the way down before the fade takes over.
                int usable = 0;
                for (int i = 0; i < pixels.size(); i++) {
                    mapper.map(pixels.get(i)[0], pixels.get(i)[1], mapped);
                    if (mapped[1] < PATH_FLOOR_CLEARANCE)
                        break;
                    usable = i + 1;
                }
                if (usable < 3)
                    continue;
                final float[] pts = new float[usable * 3];
                float length = 0F;
                float lx = 0F, ly = 0F, lz = 0F;
                for (int i = 0; i < usable; i++) {
                    mapper.map(pixels.get(i)[0], pixels.get(i)[1], mapped);
                    pts[i * 3] = mapped[0];
                    pts[i * 3 + 1] = mapped[1];
                    pts[i * 3 + 2] = mapped[2];
                    if (i > 0) {
                        final float dx = mapped[0] - lx, dy = mapped[1] - ly, dz = mapped[2] - lz;
                        length += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    }
                    lx = mapped[0];
                    ly = mapped[1];
                    lz = mapped[2];
                }
                paths.add(new SlidePath(planeIndex, pts, length));
            }
        }

        // Unit normal: cross of the two quad edges from corner 0.
        float nx = d1y * d2z - d1z * d2y;
        float ny = d1z * d2x - d1x * d2z;
        float nz = d1x * d2y - d1y * d2x;
        final float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0e-7F) {
            statDegenerate++;
            return null;
        }
        nx /= nl;
        ny /= nl;
        nz /= nl;

        // ---- 散布锚点：整株零散雪单元的落点 ----
        // 与列顶锚点的差别在"依托规则"：列顶要求下方有不透明像素（水珠挂叶尖
        // 的规则），雪还可以落在叶面内部 —— 8 邻域 >= 3 说明周围有纹理，不是
        // 贴图角上的"须须"（悬空就出自那里）。两者都要过 GROUND_CLEARANCE：
        // 茎基部其实在下面那个方块里，挂上去会读到泥土的光（入地变黑那个坑）。
        final List<int[]> scatterPix = new ArrayList<>(Math.min(128, opaqueCount));
        final List<Float> scatterWt = new ArrayList<>(Math.min(128, opaqueCount));
        for (int spy = 0; spy < mh; spy++) {
            for (int spx = 0; spx < mw; spx++) {
                if (!opaque[spy * mw + spx])
                    continue;
                mapper.map(spx, spy, mapped);
                if (mapped[1] < GROUND_CLEARANCE)
                    continue;
                final boolean below = spy + 1 < mh && opaque[(spy + 1) * mw + spx];
                if (!below && neighbours8(opaque, mw, mh, spx, spy) < 3)
                    continue;
                final boolean edge = !(spx > 0 && opaque[spy * mw + spx - 1])
                        || !(spx + 1 < mw && opaque[spy * mw + spx + 1])
                        || !(spy > 0 && opaque[(spy - 1) * mw + spx])
                        || !(spy + 1 < mh && opaque[(spy + 1) * mw + spx]);
                scatterPix.add(new int[] { spx, spy });
                // 上重下轻（雪积在朝上的面）+ 边缘加成（轮廓像素更容易挂雪）。
                // 权重与定标页的 Python 实现一致，改这里要同步改那边。
                scatterWt.add(0.7F * (1.0F - spy / (float) Math.max(1, mh - 1))
                        + 0.3F * (edge ? 1.0F : 0.0F));
            }
        }
        final List<Anchor> scatter = new ArrayList<>(MAX_SCATTER_PER_PLANE);
        final int nCand = scatterPix.size();
        final boolean[] sUsed = new boolean[nCand];
        final List<Integer> sChosen = new ArrayList<>(MAX_SCATTER_PER_PLANE);
        // 确定性 LCG（不用 Random）：构建期一次性、每个模型可复现。
        // 逐帧随机会让单元在玩家走动时 shimmer —— 这是踩过的坑。
        long sSeed = 0x5BF03635L ^ (long) planeIndex * 0x9E3779B9L ^ (long) opaqueCount * 0x85EBCA6BL;
        for (int attempt = 0; attempt < 500 && scatter.size() < MAX_SCATTER_PER_PLANE; attempt++) {
            float sTotal = 0F;
            for (int i = 0; i < nCand; i++)
                if (!sUsed[i])
                    sTotal += scatterWt.get(i);
            if (sTotal <= 0F)
                break;
            sSeed = sSeed * 6364136223846793005L + 1442695040888963407L;
            float roll = ((sSeed >>> 33) / (float) (1L << 31)) * sTotal;
            int pick = -1;
            for (int i = 0; i < nCand; i++) {
                if (sUsed[i])
                    continue;
                roll -= scatterWt.get(i);
                if (roll <= 0F) {
                    pick = i;
                    break;
                }
            }
            if (pick < 0)
                continue;
            final int[] q = scatterPix.get(pick);
            boolean far = true;
            for (final Integer ci : sChosen) {
                final int[] prev = scatterPix.get(ci);
                if (Math.max(Math.abs(prev[0] - q[0]), Math.abs(prev[1] - q[1])) < SCATTER_MIN_DIST_PX) {
                    far = false;
                    break;
                }
            }
            if (!far)
                continue;
            sUsed[pick] = true;
            sChosen.add(pick);
            mapper.map(q[0], q[1], mapped);
            scatter.add(new Anchor(planeIndex, mapped[0], mapped[1], mapped[2],
                    scatterWt.get(pick), roomAt(opaque, mw, mh, q[0], q[1])));
        }
        statScatter += scatter.size();

        return new Plane(c, nx, ny, nz, capped.toArray(new Anchor[0]), paths.toArray(new SlidePath[0]), scatter.toArray(new Anchor[0]));
    }

    /** How much solid leaf surrounds a mask pixel - see {@link Anchor#room()}. */
    private static final float ROOM_SOLIDITY = 0.6F;
    private static final int ROOM_MAX = 3;

    /**
     * The largest {@code r} (capped at {@link #ROOM_MAX}) whose square window
     * around this pixel is at least {@link #ROOM_SOLIDITY} leaf. Out-of-bounds
     * cells count as empty - a window hanging off the sprite's edge is exactly
     * the overhang the snow layer is trying to avoid.
     */
    private static int roomAt(final boolean[] opaque, final int mw, final int mh,
                              final int px, final int py) {
        int room = 0;
        for (int r = 1; r <= ROOM_MAX; r++) {
            int total = 0;
            int hit = 0;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    total++;
                    final int nx = px + dx;
                    final int ny = py + dy;
                    if (nx >= 0 && nx < mw && ny >= 0 && ny < mh && opaque[ny * mw + nx])
                        hit++;
                }
            }
            if ((float) hit / (float) total < ROOM_SOLIDITY)
                break;
            room = r;
        }
        return room;
    }

    /** Opaque pixels in the 8-neighbourhood of a mask pixel (bounds-safe). */
    private static int neighbours8(final boolean[] opaque, final int mw, final int mh,
                                   final int px, final int py) {
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0)
                    continue;
                final int nx = px + dx;
                final int ny = py + dy;
                if (nx < 0 || nx >= mw || ny < 0 || ny >= mh)
                    continue;
                if (opaque[ny * mw + nx])
                    n++;
            }
        }
        return n;
    }

    /** The pixel-center affine: mask pixel -> model space. */
    private static final class Affine {
        private final float px0, py0, pz0;
        private final float d1x, d1y, d1z, d2x, d2y, d2z;
        private final float su0, sv0, e1x, e1y, e2x, e2y, det, invMw, invMh;

        Affine(float[] c, float d1x, float d1y, float d1z, float d2x, float d2y, float d2z,
               float su0, float sv0, float e1x, float e1y, float e2x, float e2y, float det, int mw, int mh) {
            this.px0 = c[0];
            this.py0 = c[1];
            this.pz0 = c[2];
            this.d1x = d1x;
            this.d1y = d1y;
            this.d1z = d1z;
            this.d2x = d2x;
            this.d2y = d2y;
            this.d2z = d2z;
            this.su0 = su0;
            this.sv0 = sv0;
            this.e1x = e1x;
            this.e1y = e1y;
            this.e2x = e2x;
            this.e2y = e2y;
            this.det = det;
            this.invMw = 1.0F / mw;
            this.invMh = 1.0F / mh;
        }

        /** Writes the model-space position of the pixel center into {@code out}. */
        void map(final int px, final int py, final float[] out) {
            final float sx = (px + 0.5F) * this.invMw - this.su0;
            final float sy = (py + 0.5F) * this.invMh - this.sv0;
            final float a = (sx * this.e2y - sy * this.e2x) / this.det;
            final float b = (sy * this.e1x - sx * this.e1y) / this.det;
            out[0] = this.px0 + this.d1x * a + this.d2x * b;
            out[1] = this.py0 + this.d1y * a + this.d2y * b;
            out[2] = this.pz0 + this.d1z * a + this.d2z * b;
        }
    }

    /**
     * The path a sliding drop takes down the blade: from a column top, step
     * DOWNWARD only, straight down when the blade allows and diagonally when
     * it slants, and stop when the blade runs out.
     *
     * <p>The previous version also allowed a pure LATERAL step ({@code dy == 0})
     * and scored candidates mostly by {@code ny}, which produced the
     * grasshopper the maintainer saw: once a drop reached the wide bottom of
     * a grass sprite - where nothing is below it any more - it walked
     * sideways along the row, and because the tie-break always preferred the
     * same direction it crossed the whole texture and then slid down the far
     * side, at 45 degrees and at constant speed.</p>
     *
     * <p>A drop runs DOWN a blade; it never crawls along a row of pixels. So:
     * downward neighbours only, straight down preferred, and the sideways
     * drift is capped - a slanted blade may lean, but it cannot walk the drop
     * off the side of the leaf. When nothing is below, the path simply ends
     * and the drop lets go, which is what a real drop does at a leaf tip.</p>
     */
    private static List<int[]> descend(final boolean[] opaque, final int mw, final int mh, final int startPx) {
        int x = startPx;
        int y = -1;
        for (int py = 0; py < mh; py++) {
            if (opaque[py * mw + x]) {
                y = py;
                break;
            }
        }
        if (y < 0)
            return null;
        final List<int[]> path = new ArrayList<>();
        path.add(new int[] { x, y });
        int drift = 0;
        int lastDx = 0;
        while (path.size() < MAX_PATH_POINTS) {
            int bestDx = 0;
            boolean found;
            if (y + 1 < mh && opaque[(y + 1) * mw + x]) {
                // Straight down wins outright - no lean at all.
                bestDx = 0;
                found = true;
            } else {
                // A diagonal step - and once leaning one way, KEEP leaning
                // that way. Re-picking the side every step by "smallest drift"
                // walks the path left, right, left: the zig-zag the maintainer
                // called a bug in the grass. A blade is slanted, so the drop
                // rides that slant all the way down instead of oscillating.
                found = false;
                final int first = lastDx != 0 ? lastDx : -1;
                for (int k = 0; k < 2 && !found; k++) {
                    final int dx = k == 0 ? first : -first;
                    final int nx = x + dx;
                    if (nx < 0 || nx >= mw || y + 1 >= mh)
                        continue;
                    if (!opaque[(y + 1) * mw + nx])
                        continue;
                    bestDx = dx;
                    found = true;
                }
            }
            if (!found)
                break;   // ran off the bottom of the blade - the drop lets go
            if (Math.abs(drift + bestDx) > MAX_PATH_DRIFT)
                break;
            // Stop a row short of the bottom. The last row of the sprite is the
            // base of the plant, which sits IN the ground block - and a bead
            // that slides into that block takes its light from inside it, so
            // it renders BLACK for the moment before it dries up.
            if (y + 1 >= mh - 1)
                break;
            x += bestDx;
            y++;
            drift += bestDx;
            lastDx = bestDx;
            path.add(new int[] { x, y });
        }
        return path.size() >= 3 ? path : null;
    }

    /**
     * Kill the pixel staircase: keep every other point (and the last), then
     * one Chaikin corner-cutting pass. Cheap, and the difference reads at
     * droplet scale.
     */
    private static List<int[]> smooth(final List<int[]> pixels) {
        final List<int[]> decimated = new ArrayList<>((pixels.size() + 1) / 2 + 1);
        for (int i = 0; i < pixels.size(); i += 2)
            decimated.add(pixels.get(i));
        if (decimated.get(decimated.size() - 1) != pixels.get(pixels.size() - 1))
            decimated.add(pixels.get(pixels.size() - 1));
        if (decimated.size() < 3)
            return decimated;
        final List<int[]> out = new ArrayList<>(decimated.size() * 2);
        out.add(decimated.get(0));
        for (int i = 0; i < decimated.size() - 1; i++) {
            final int[] p = decimated.get(i), q = decimated.get(i + 1);
            out.add(new int[] { (p[0] * 3 + q[0]) / 4, (p[1] * 3 + q[1]) / 4 });
            out.add(new int[] { (p[0] + q[0] * 3) / 4, (p[1] + q[1] * 3) / 4 });
        }
        out.add(decimated.get(decimated.size() - 1));
        return out;
    }
}
