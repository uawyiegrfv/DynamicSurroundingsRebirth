package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

/**
 * One plant model's attachment geometry - the 26.1 shape of {@code FoliageProfile}
 * (see the 1.20.1 file for the full rationale; only the model plumbing differs).
 *
 * <p>26.1 model plumbing: the renderer's model is a {@link BlockStateModel}
 * (the {@code BlockRenderDispatcher/BakedModel} pair no longer exists), the
 * quad list comes from {@code collectParts(random, parts)} - the level/pos-free
 * deprecated overload, because a cache-time build has neither - and each
 * {@link BakedQuad} is a record: {@code position(i)} for the model-space
 * corners, {@code packedUV(i)} for ATLAS-space UVs packed as two float bits
 * (decoded here inline; {@code UVPair.unpackU/V} is exactly this), and the
 * sprite off {@code materialInfo()}. Variant rotations are still baked into
 * the corner positions, so the sorted-corner dedupe key and the whole mask /
 * anchor / path pipeline carry over unchanged.</p>
 *
 * <p>One accepted imprecision (documented): {@code collectParts} selects
 * weighted random variants from the seed we pass, so a plant whose variants
 * are folded into ONE model with per-position random rotation (lily pad's
 * y=90/180/270 on some packs) may anchor on a sibling rotation. Cross plants
 * - the entire target set but lily pads - are exact.</p>
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

    private static final int MAX_ANCHORS_PER_PLANE = 128;
    private static final int MAX_PATH_POINTS = 48;
    /** How far a sliding path may lean sideways before it is cut off. */
    private static final int MAX_PATH_DRIFT = 3;
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
     * Builds the profile for one model. A FIXED seed: the build runs once per
     * model in the cache, so weighted variants resolve once and stay.
     */
    public static FoliageProfile build(final BlockStateModel model, final BlockState state) {
        statQuads = 0;
        statDeduped = 0;
        statDegenerate = 0;
        statNonParallelogram = 0;
        statNoMask = 0;

        // The level/pos-free overload - a cache-time build has neither. The
        // renderer uses the level/pos form for model data; plants have none.
        @SuppressWarnings("deprecation")
        final List<BlockStateModelPart> parts = new ArrayList<>();
        final RandomSource random = RandomSource.create(0L);
        model.collectParts(random, parts);
        if (parts.isEmpty())
            return EMPTY;

        // Decode, then dedupe with the WINDING-INDEPENDENT key (review B).
        final List<float[]> cornerList = new ArrayList<>(parts.size());
        final List<float[]> uvList = new ArrayList<>(parts.size());
        final List<TextureAtlasSprite> spriteList = new ArrayList<>(parts.size());
        final List<String> seen = new ArrayList<>(parts.size());
        for (final BlockStateModelPart part : parts) {
            for (final BakedQuad quad : part.getQuads(null)) {
                statQuads++;
                final float[] c = new float[12];
                final float[] uv = new float[8];
                for (int i = 0; i < 4; i++) {
                    final Vector3fc p = quad.position(i);
                    c[i * 3] = p.x();
                    c[i * 3 + 1] = p.y();
                    c[i * 3 + 2] = p.z();
                    uv[i * 2] = unpackU(quad.packedUV(i));
                    uv[i * 2 + 1] = unpackV(quad.packedUV(i));
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
                spriteList.add(quad.materialInfo().sprite());
            }
        }

        final List<Plane> planes = new ArrayList<>(cornerList.size());
        for (int i = 0; i < cornerList.size(); i++) {
            final Plane plane = buildPlaneFull(cornerList.get(i), uvList.get(i), spriteList.get(i), planes.size());
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

    /** Atlas UV out of a packed long - high 32 bits = u float bits, low = v. */
    private static float unpackU(final long packedUV) {
        return Float.intBitsToFloat((int) (packedUV >> 32));
    }

    private static float unpackV(final long packedUV) {
        return Float.intBitsToFloat((int) packedUV);
    }

    /**
     * The plane build: mask, anchors and paths, from corners + atlas UVs.
     * Returns null for planes this feature cannot use - each rejection bumps
     * its trace counter.
     */
    private static Plane buildPlaneFull(final float[] c, final float[] uvAtlas, final TextureAtlasSprite sprite,
                                        final int planeIndex) {
        final int mw = sprite.contents().width();
        final int mh = sprite.contents().height();

        final float[] su = new float[4];
        final float[] sv = new float[4];
        for (int i = 0; i < 4; i++) {
            su[i] = (uvAtlas[i * 2] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
            sv[i] = (uvAtlas[i * 2 + 1] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        }

        final float e1x = su[1] - su[0], e1y = sv[1] - sv[0];
        final float e2x = su[2] - su[0], e2y = sv[2] - sv[0];
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
        // below fails by a full quad width, EVERY plane is dropped, and the
        // profile comes back empty - no anchors, no drops, no dew, and
        // nothing in the log to say why.
        final float d2x = c[6] - c[0], d2y = c[7] - c[1], d2z = c[8] - c[2];

        // Parallelogram check against the fourth corner.
        {
            final float rx = su[3] - su[0], ry = sv[3] - sv[0];
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

        final Affine mapper = new Affine(c, d1x, d1y, d1z, d2x, d2y, d2z,
                su[0], sv[0], e1x, e1y, e2x, e2y, det, mw, mh);

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

        final float[] mapped = new float[3];
        final List<Anchor> anchors = new ArrayList<>(topPixels.size());
        for (int i = 0; i < topPixels.size(); i++) {
            final int[] p = topPixels.get(i);
            mapper.map(p[0], p[1], mapped);
            anchors.add(new Anchor(planeIndex, mapped[0], mapped[1], mapped[2], topWeights.get(i)[0]));
        }
        anchors.sort((x, y) -> Float.compare(y.weight(), x.weight()));
        final List<Anchor> capped = anchors.size() > MAX_ANCHORS_PER_PLANE
                ? anchors.subList(0, MAX_ANCHORS_PER_PLANE)
                : anchors;

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
            x += bestDx;
            y++;
            drift += bestDx;
            lastDx = bestDx;
            path.add(new int[] { x, y });
        }
        return path.size() >= 3 ? path : null;
    }

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
