package org.orecruncher.dsurround.processing.scanner.shoreline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 淹没前沿 + 偏移交点。
 *
 * 淹没决定：哪些边是前沿（已淹/未淹沙滩的公共边）、什么时候消失（s 超过深度）。
 * 偏移交点决定：线具体落在哪（向未淹格内偏移 f；直角用两条偏移线的交点）。
 */
public final class ShoreFront {

    public static final int SEA = 0, BEACH = 1, LAND = 2, UNKNOWN = 3;
    public static final int INF = Integer.MAX_VALUE / 2;
    private static final long KEY = 100_000L;

    public interface World {
        int width();
        int height();
        int kind(int x, int z);
    }

    public record Seg(double x0, double z0, double x1, double z1) {
        public boolean axisAligned() {
            return Math.abs(x1 - x0) < 1e-9 || Math.abs(z1 - z0) < 1e-9;
        }
    }

    public record Edge(double x0, double z0, double x1, double z1, double nx, double nz, boolean hide) {
    }

    public record Result(List<Seg> segs, int[][] depth, int flooded, int frontier,
                         double s, double f, List<Edge> edges) {
    }

    private ShoreFront() {
    }

    public static Result build(final World w, final double p, final double runup) {
        final int W = w.width(), H = w.height();
        final int[][] d = new int[H][W];
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++)
                d[z][x] = INF;

        final ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++)
                if (w.kind(x, z) == SEA) {
                    d[z][x] = 0;
                    q.add(new int[]{x, z});
                }
        final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            final int[] c = q.poll();
            for (int i = 0; i < 4; i++) {
                final int nx = c[0] + DX[i], nz = c[1] + DZ[i];
                if (nx < 0 || nz < 0 || nx >= W || nz >= H)
                    continue;
                if (w.kind(nx, nz) != BEACH || d[nz][nx] != INF)
                    continue;
                d[nz][nx] = d[c[1]][c[0]] + 1;
                q.add(new int[]{nx, nz});
            }
        }

        final double s = p * runup;
        final int k = (int) Math.floor(s);
        final double f = s - k;
        final boolean[][] flooded = new boolean[H][W];
        int fc = 0, fr = 0;
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                int kd = w.kind(x, z);
                if (kd == SEA) { flooded[z][x] = true; fc++; }
                else if (kd == BEACH && d[z][x] <= k) { flooded[z][x] = true; fc++; }
            }

        final List<Edge> edges = new ArrayList<>();
        for (int z = 0; z < H; z++) {
            for (int x = 0; x < W; x++) {
                if (w.kind(x, z) != BEACH || flooded[z][x])
                    continue;
                // 未淹的沙滩格；四邻中已淹的 -> 前沿边
                final boolean e = flooded(w, flooded, x + 1, z);
                final boolean wst = flooded(w, flooded, x - 1, z);
                final boolean n = flooded(w, flooded, x, z + 1);
                final boolean so = flooded(w, flooded, x, z - 1);
                if (!e && !wst && !n && !so)
                    continue;
                fr++;
                final boolean squeeze = (e && wst) || (n && so);
                final boolean hide = squeeze && f > 0.5;
                if (e) edges.add(new Edge(x + 1, z, x + 1, z + 1, -1, 0, hide));
                if (wst) edges.add(new Edge(x, z, x, z + 1, 1, 0, hide));
                if (n) edges.add(new Edge(x, z + 1, x + 1, z + 1, 0, -1, hide));
                if (so) edges.add(new Edge(x, z, x + 1, z, 0, 1, hide));
            }
        }

        final Map<Long, List<Edge>> at = new HashMap<>();
        for (final Edge e : edges) {
            if (e.hide())
                continue;
            at.computeIfAbsent(vkey(e.x0(), e.z0()), key -> new ArrayList<>()).add(e);
            at.computeIfAbsent(vkey(e.x1(), e.z1()), key -> new ArrayList<>()).add(e);
        }

        final List<Seg> segs = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Edge e : edges) {
            if (e.hide())
                continue;
            final double[] a = corner(at, e, e.x0(), e.z0(), f);
            final double[] b = corner(at, e, e.x1(), e.z1(), f);
            add(segs, seen, a[0], a[1], b[0], b[1]);
        }
        return new Result(segs, d, fc, fr, s, f, edges);
    }

    private static double[] corner(final Map<Long, List<Edge>> at, final Edge e,
                                   final double vx, final double vz, final double f) {
        final List<Edge> list = at.get(vkey(vx, vz));
        if (list == null || list.size() != 2)
            return new double[]{vx + e.nx() * f, vz + e.nz() * f};
        final Edge o = list.get(0) == e ? list.get(1) : list.get(0);
        if (Math.abs(e.nx() * o.nx() + e.nz() * o.nz()) < 1e-9)
            return new double[]{vx + (e.nx() + o.nx()) * f, vz + (e.nz() + o.nz()) * f};
        return new double[]{vx + e.nx() * f, vz + e.nz() * f};
    }

    private static boolean flooded(final World w, final boolean[][] fl, final int x, final int z) {
        if (x < 0 || z < 0 || x >= w.width() || z >= w.height())
            return false;
        return fl[z][x];
    }

    private static void add(final List<Seg> segs, final Set<String> seen,
                            final double x0, final double z0, final double x1, final double z1) {
        if (Math.abs(x1 - x0) < 1e-6 && Math.abs(z1 - z0) < 1e-6)
            return;
        final String key = String.format("%.4f,%.4f,%.4f,%.4f", x0, z0, x1, z1);
        final String rev = String.format("%.4f,%.4f,%.4f,%.4f", x1, z1, x0, z0);
        if (seen.contains(key) || seen.contains(rev))
            return;
        seen.add(key);
        segs.add(new Seg(x0, z0, x1, z1));
    }

    private static long vkey(final double x, final double z) {
        return Math.round(x) * KEY + Math.round(z);
    }
}
