package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.world.level.Level;

/**
 * The surf, drawn as two contours of the distance to the sea.
 *
 * <p>Distance is exact Euclidean, sampled at grid VERTICES: a vertex's value is
 * the distance from that corner to the nearest sea cell centre. Sampling at the
 * corners is what makes marching squares work - a cell centre field is a plateau
 * of equal values, so a level line at any height between two plateaus crosses no
 * cell at all and nothing is drawn. The first version of this class did exactly
 * that and produced a band only at the smallest swell.</p>
 *
 * <p>The band lives between the contour at {@code f} and the one at
 * {@code f + width}, so both edges are contours and neighbouring cells share the
 * interpolated point on their common edge exactly: the ribbon cannot break, no
 * matter how short the coast segments are.</p>
 */
final class ShoreContour {

    /** Largest inland travel of the crest, in blocks. */
    private static final float MAX_OFFSET = 1F;
    /** A body of water smaller than this is a puddle, not a coast. */
    private static final int MIN_SEA_CELLS = 100;

    private ShoreContour() {
    }

    static void build(final ShoreScan.Grid g, final Level level, final float p,
                      final ShoreScan.Quads out) {
        final int size = g.size;
        final int vn = size + 1;
        int sea = 0;
        for (int i = 0; i < g.cls.length; i++)
            if (g.cls[i] == ShoreScan.CLS_SEA)
                sea++;
        if (sea < MIN_SEA_CELLS)
            return;

        // Exact distance at every grid VERTEX, in blocks.
        final float[] vd = new float[vn * vn];
        java.util.Arrays.fill(vd, Float.MAX_VALUE);
        for (int cz = 0; cz < size; cz++) {
            for (int cx = 0; cx < size; cx++) {
                if (g.cls[cz * size + cx] != ShoreScan.CLS_SEA)
                    continue;
                final float sx = cx + 0.5F, sz = cz + 0.5F;
                for (int vz = 0; vz < vn; vz++) {
                    final float dz = vz - sz;
                    final int row = vz * vn;
                    for (int vx = 0; vx < vn; vx++) {
                        final float dx = vx - sx;
                        // -0.5: the sea is a REGION, not a point at the cell centre.
                        // Without it every vertex is at least half a block from the
                        // nearest centre, so the water line sits at 0.7 and a contour
                        // near zero crosses nothing.
                        final float d = Math.max(0F, (float) Math.sqrt(dx * dx + dz * dz) - 0.5F);
                        if (d < vd[row + vx])
                            vd[row + vx] = d;
                    }
                }
            }
        }

        // Never below a third of a block: the field is sampled at cell corners, so a
        // level line very close to zero falls between the samples and finds no cell to
        // cross - the band simply disappears at the low end of the swell.
        final float f = 0.3F + Math.max(0F, Math.min(1F, p)) * (MAX_OFFSET - 0.3F);
        final java.util.List<float[]> segs = new java.util.ArrayList<>();
        final float[] px = new float[4];
        final float[] pz = new float[4];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final float d00 = vd[z * vn + x];
                final float d10 = vd[z * vn + x + 1];
                final float d01 = vd[(z + 1) * vn + x];
                final float d11 = vd[(z + 1) * vn + x + 1];
                if (crossing(d00, d10, d11, d01, x, z, f, px, pz) != 2)
                    continue;
                segs.add(new float[]{px[0], pz[0], px[1], pz[1]});
            }
        }
        if (segs.isEmpty())
            return;

        // Chain the contour: neighbouring cells interpolate the SAME point on their
        // shared edge, so the segments join exactly and the ribbon cannot break.
        final java.util.Map<Long, java.util.List<Integer>> at = new java.util.HashMap<>();
        for (int i = 0; i < segs.size(); i++) {
            final float[] s = segs.get(i);
            at.computeIfAbsent(key(s[0], s[1]), k -> new java.util.ArrayList<>(2)).add(i << 1);
            at.computeIfAbsent(key(s[2], s[3]), k -> new java.util.ArrayList<>(2)).add((i << 1) | 1);
        }
        final boolean[] used = new boolean[segs.size()];
        final float half = ShoreScan.BAND_WIDTH * 0.5F;
        for (int seed = 0; seed < segs.size(); seed++) {
            if (used[seed])
                continue;
            final java.util.List<float[]> fwd = new java.util.ArrayList<>();
            walk(segs, at, used, seed, 0, fwd);
            final java.util.List<float[]> back = new java.util.ArrayList<>();
            walk(segs, at, used, seed, 1, back);
            final java.util.List<float[]> pts = new java.util.ArrayList<>();
            for (int i = back.size() - 1; i >= 0; i--)
                pts.add(back.get(i));
            pts.addAll(fwd);
            if (pts.size() >= 2)
                stroke(g, level, out, pts, vd, vn, half);
        }
    }

    private static void walk(final java.util.List<float[]> segs, final java.util.Map<Long, java.util.List<Integer>> at,
                             final boolean[] used, final int seed, final int entry,
                             final java.util.List<float[]> path) {
        int cur = seed;
        int end = entry;
        while (true) {
            used[cur] = true;
            final float[] s = segs.get(cur);
            final float ax = (end == 0) ? s[0] : s[2];
            final float az = (end == 0) ? s[1] : s[3];
            final float bx = (end == 0) ? s[2] : s[0];
            final float bz = (end == 0) ? s[3] : s[1];
            path.add(new float[]{ax, az, bx, bz});
            final java.util.List<Integer> cand = at.get(key(bx, bz));
            if (cand == null)
                break;
            int next = -1;
            int nextEnd = 0;
            for (final int code : cand) {
                final int sid = code >> 1;
                if (sid == cur || used[sid])
                    continue;
                next = sid;
                nextEnd = code & 1;
                break;
            }
            if (next < 0)
                break;
            cur = next;
            end = nextEnd;
        }
    }

    private static long key(final float x, final float z) {
        return ((long) Math.round(x * 1000F) << 32) ^ (Math.round(z * 1000F) & 0xffffffffL);
    }

    /** One quad per contour segment, half a band width to either side, mitred. */
    private static void stroke(final ShoreScan.Grid g, final Level level, final ShoreScan.Quads out,
                               final java.util.List<float[]> segs, final float[] vd, final int vn,
                               final float half) {
        final int n = segs.size();
        final float[] vx = new float[n + 1];
        final float[] vz = new float[n + 1];
        vx[0] = segs.get(0)[0];
        vz[0] = segs.get(0)[1];
        for (int i = 0; i < n; i++) {
            vx[i + 1] = segs.get(i)[2];
            vz[i + 1] = segs.get(i)[3];
        }
        final float[] sx = new float[n];
        final float[] sz = new float[n];
        for (int i = 0; i < n; i++) {
            final float dx = vx[i + 1] - vx[i];
            final float dz = vz[i + 1] - vz[i];
            final float len = (float) Math.hypot(dx, dz);
            float nx = len < 1.0e-4F ? 0F : dz / len;
            float nz = len < 1.0e-4F ? 0F : -dx / len;
            // Inland side = the side where the field is larger.
            final float mxp = (vx[i] + vx[i + 1]) * 0.5F, mzp = (vz[i] + vz[i + 1]) * 0.5F;
            if (sample(vd, vn, mxp + nx * 0.3F, mzp + nz * 0.3F) < sample(vd, vn, mxp - nx * 0.3F, mzp - nz * 0.3F)) {
                nx = -nx;
                nz = -nz;
            }
            sx[i] = nx;
            sz[i] = nz;
        }
        final float[] mx = new float[n + 1];
        final float[] mz = new float[n + 1];
        for (int v = 0; v <= n; v++) {
            final int a = (v == 0) ? 0 : v - 1;
            final int b = (v == n) ? n - 1 : v;
            float nx = sx[a] + sx[b];
            float nz = sz[a] + sz[b];
            final float len = (float) Math.hypot(nx, nz);
            if (len < 1.0e-4F) {
                mx[v] = sx[a];
                mz[v] = sz[a];
            } else {
                final float dot = sx[a] * sx[b] + sz[a] * sz[b];
                final float scale = (dot < 0.4F) ? 1F : (1F / (float) Math.sqrt((1F + dot) * 0.5F));
                mx[v] = nx / len * scale;
                mz[v] = nz / len * scale;
            }
        }
        float along = 0F;
        for (int i = 0; i < n; i++) {
            final int cx = (int) Math.floor((vx[i] + vx[i + 1]) * 0.5F);
            final int cz = (int) Math.floor((vz[i] + vz[i + 1]) * 0.5F);
            final int alpha = ShoreScan.edgeAlpha(g, cx, cz);
            final float len = (float) Math.hypot(vx[i + 1] - vx[i], vz[i + 1] - vz[i]);
            if (alpha <= 0 || len < 1.0e-4F) {
                along += len;
                continue;
            }
            final ShoreScan.ShoreQuad q = out.next();
            q.tex = ShoreScan.TEX_STRIP;
            q.kind = 0;
            q.rgb = ShoreScan.FOAM_RGB;
            q.y = g.waterTop + 1F + ShoreScan.FOAM_LIFT;
            q.light = ShoreScan.lightAt(level, g, g.ox + cx, g.oz + cz);
            q.alpha = alpha;
            q.wx[0] = g.ox + vx[i] - mx[i] * half;
            q.wz[0] = g.oz + vz[i] - mz[i] * half;
            q.wx[1] = g.ox + vx[i + 1] - mx[i + 1] * half;
            q.wz[1] = g.oz + vz[i + 1] - mz[i + 1] * half;
            q.wx[2] = g.ox + vx[i + 1] + mx[i + 1] * half;
            q.wz[2] = g.oz + vz[i + 1] + mz[i + 1] * half;
            q.wx[3] = g.ox + vx[i] + mx[i] * half;
            q.wz[3] = g.oz + vz[i] + mz[i] * half;
            q.u[0] = along / ShoreScan.STRIP_PERIOD;
            q.u[1] = (along + len) / ShoreScan.STRIP_PERIOD;
            q.u[2] = q.u[1];
            q.u[3] = q.u[0];
            q.v[0] = 0.7F;
            q.v[1] = 0.7F;
            q.v[2] = 0F;
            q.v[3] = 0F;
            g.stripQuads++;
            along += len;
        }
    }

    private static float sample(final float[] vd, final int vn, final float x, final float z) {
        final int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        final float fx = x - x0, fz = z - z0;
        final float a = vd[z0 * vn + x0] * (1F - fx) + vd[z0 * vn + x0 + 1] * fx;
        final float b = vd[(z0 + 1) * vn + x0] * (1F - fx) + vd[(z0 + 1) * vn + x0 + 1] * fx;
        return a * (1F - fz) + b * fz;
    }

    /** Marching squares: the two points where the level crosses this cell. */
    private static int crossing(final float d00, final float d10, final float d11, final float d01,
                                final int x, final int z, final float level,
                                final float[] px, final float[] pz) {
        int n = 0;
        if ((d00 < level) != (d10 < level)) {
            px[n] = x + (level - d00) / (d10 - d00);
            pz[n] = z;
            n++;
        }
        if ((d10 < level) != (d11 < level)) {
            px[n] = x + 1;
            pz[n] = z + (level - d10) / (d11 - d10);
            n++;
        }
        if ((d01 < level) != (d11 < level)) {
            px[n] = x + (level - d01) / (d11 - d01);
            pz[n] = z + 1;
            n++;
        }
        if ((d00 < level) != (d01 < level)) {
            px[n] = x;
            pz[n] = z + (level - d00) / (d01 - d00);
            n++;
        }
        if (n == 4) {
            px[1] = px[2];
            pz[1] = pz[2];
            n = 2;
        }
        return n;
    }
}