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
    public record Anchor(int plane, float x, float y, float z, float weight) {
    }

    /** One pre-baked descent, waypoints in MODEL space, {@code length} in blocks. */
    public record SlidePath(int plane, float[] pts, float length) {
    }

    /** One deduped unculled quad plane: corners, unit normal, anchors, paths. */
    public record Plane(float[] corners, float nx, float ny, float nz,
                        Anchor[] anchors, SlidePath[] paths) {
    }

    // Trace counters - read by the handlers' diagnostics, reset per build.
    public static int statQuads = 0;
    public static int statDeduped = 0;
    public static int statDegenerate = 0;
    public static int statNonParallelogram = 0;
    public static int statNoMask = 0;

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

    private static final int MAX_ANCHORS_PER_PLANE = 128;
    private static final int MAX_PATH_POINTS = 48;
    private static final float ALPHA_THRESHOLD = 8.0F;

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

        // Pixel-domain attachment points. Column tops (rule A - where water
        // collects) and endpoints (rule B - blade tips). Pixels are kept
        // alongside: the slide paths need a mask-space start.
        final List<int[]> topPixels = new ArrayList<>(mw);
        final List<float[]> topWeights = new ArrayList<>(mw);
        for (int px = 0; px < mw; px++) {
            for (int py = 0; py < mh; py++) {
                if (!opaque[py * mw + px])
                    continue;
                topPixels.add(new int[] { px, py });
                topWeights.add(new float[] {
                        (float) Math.pow(1.0F - py / (float) Math.max(1, mh - 1), 1.5) + 0.1F });
                break;
            }
        }
        final List<int[]> tipPixels = new ArrayList<>();
        for (int py = 0; py < mh; py++) {
            for (int px = 0; px < mw; px++) {
                if (!opaque[py * mw + px])
                    continue;
                int nb = 0;
                for (int dy = -1; dy <= 1 && nb <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0)
                            continue;
                        final int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= mw || ny >= mh)
                            continue;
                        if (opaque[ny * mw + nx])
                            nb++;
                    }
                }
                if (nb <= 1)
                    tipPixels.add(new int[] { px, py });
            }
        }

        final List<Anchor> anchors = new ArrayList<>(topPixels.size() + tipPixels.size());
        for (int i = 0; i < topPixels.size(); i++) {
            final int[] p = topPixels.get(i);
            mapper.map(p[0], p[1], mapped);
            anchors.add(new Anchor(planeIndex, mapped[0], mapped[1], mapped[2], topWeights.get(i)[0]));
        }
        for (final int[] p : tipPixels) {
            mapper.map(p[0], p[1], mapped);
            anchors.add(new Anchor(planeIndex, mapped[0], mapped[1], mapped[2], 0.5F));
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
                final float[] pts = new float[pixels.size() * 3];
                float length = 0F;
                float lx = 0F, ly = 0F, lz = 0F;
                for (int i = 0; i < pixels.size(); i++) {
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

        return new Plane(c, nx, ny, nz, capped.toArray(new Anchor[0]), paths.toArray(new SlidePath[0]));
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
     * Constrained descent: from the column top, repeatedly step to the
     * 8-neighbor opaque pixel with the LARGEST y (ties: smallest |dx|), never
     * leaving the mask - the path hugs the blade and can never cross a gap.
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
        while (path.size() < MAX_PATH_POINTS) {
            int bestDx = 0, bestDy = 0;
            int bestScore = -1;
            for (int dy = 1; dy >= 0; dy--) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dy == 0 && dx == 0)
                        continue;
                    final int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= mw || ny >= mh)
                        continue;
                    if (!opaque[ny * mw + nx])
                        continue;
                    // Prefer larger ny; ties prefer straighter steps. A pure
                    // lateral step only wins when nothing below is opaque.
                    final int score = ny * 4 - Math.abs(dx) + (dy == 1 ? 1 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
                if (bestDy == 1)
                    break;   // a downward step beats any lateral one
            }
            if (bestScore < 0 || (bestDx == 0 && bestDy == 0))
                break;
            x += bestDx;
            y += bestDy;
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
