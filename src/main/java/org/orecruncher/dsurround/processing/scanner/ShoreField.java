package org.orecruncher.dsurround.processing.scanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.Level;

/**
 * The surf: one strip of one-block pieces along the water line.
 *
 * <p>Coast edges are stitched into polylines first (unit sea/beach edges joined at
 * the integer corner points they share, so the join is exact). Each polyline vertex
 * is moved inland once, and one quad is emitted per segment. Nothing here decides
 * "is this cell wet" and nothing places a piece by its own rule, which is what keeps
 * the line in one piece when the swell moves.</p>
 *
 * <p><b>Corners</b> follow the maintainer's construction: the crossing band runs
 * half a band width past the corner (so it reaches the MIDDLE of the band it crosses,
 * never its outer edge) and is a little wider than normal, so its flanks overshoot
 * and hide the straight cut. Both are the two tunables at the top of the class.</p>
 */
final class ShoreField {

    /** How far a band runs past a corner, in band widths. Negative = short of it. */
    public static float cornerGrow = -0.25F;
    /** Extra width on a band that ends at a corner, in band widths. */
    public static float cornerWiden = 0.2F;
    /** Largest inland move of the crest, in blocks. */
    private static final float MAX_OFFSET = 1F;
    /** Time constant for the swell level, seconds. */
    private static final float TAU = 0.15F;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private static float smoothed = -1F;
    private static long lastNanos = 0L;

    private ShoreField() {
    }

    /** A body of water smaller than this is a puddle, not a coast. */
    private static final int MIN_SEA_CELLS = 100;

    static void build(final ShoreScan.Grid g, final Level level, final float p,
                      final ShoreScan.Quads out) {
        // Only real water gets surf. A dug pool on the beach is CLS_SEA like the
        // ocean, so it used to grow a ring of foam; requiring a hundred cells of
        // water in the window keeps that out without asking about biomes.
        int sea = 0;
        for (int i = 0; i < g.cls.length; i++)
            if (g.cls[i] == ShoreScan.CLS_SEA)
                sea++;
        if (sea < MIN_SEA_CELLS)
            return;
        final List<float[]> edges = coastEdges(g);
        if (edges.isEmpty())
            return;

        final float target = Math.max(0F, Math.min(1F, p));
        final long now = System.nanoTime();
        if (smoothed < 0F) {
            smoothed = target;
        } else {
            final float dt = (now - lastNanos) / 1.0e9F;
            final float k = (dt <= 0F || dt > 1F) ? 1F : 1F - (float) Math.exp(-dt / TAU);
            smoothed += (target - smoothed) * k;
        }
        lastNanos = now;
        final float f = Math.min(MAX_OFFSET, Math.max(0F, smoothed) * MAX_OFFSET);
        g.fieldLevel = f;

        int chains = 0;
        int widest = 0;
        g.fieldSegs = 0;
        g.fieldSkipBounds = 0;
        g.fieldSkipAlpha = 0;
        g.fieldOpenChains = 0;
        g.fieldBorderEnds = 0;
        g.fieldFMin = Float.MAX_VALUE;
        g.fieldFMax = 0F;
        for (final List<float[]> chain : chains(edges)) {
            chains++;
            widest = Math.max(widest, emitChain(g, level, out, chain, f));
        }
        g.fieldChains = chains;
        g.fieldEdges = edges.size();
        g.fieldFieldMax = widest;
    }

    /** Every unit edge where a sea cell touches a beach cell, beach on the right. */
    private static List<float[]> coastEdges(final ShoreScan.Grid g) {
        final int size = g.size;
        final List<float[]> out = new ArrayList<>(256);
        for (int gz = 0; gz < size; gz++) {
            for (int gx = 0; gx < size; gx++) {
                if (g.cls[gz * size + gx] != ShoreScan.CLS_SEA)
                    continue;
                for (int k = 0; k < 4; k++) {
                    final int ax = gx + DX[k], az = gz + DZ[k];
                    if (ax < 0 || az < 0 || ax >= size || az >= size)
                        continue;
                    if (g.cls[az * size + ax] != ShoreScan.CLS_BEACH)
                        continue;
                    final float cx = gx + 0.5F, cz = gz + 0.5F;
                    final float mx = cx + DX[k] * 0.5F, mz = cz + DZ[k] * 0.5F;
                    final float hx = -DZ[k] * 0.5F, hz = DX[k] * 0.5F;
                    out.add(new float[]{mx - hx, mz - hz, mx + hx, mz + hz});
                }
            }
        }
        return out;
    }

    /** Joins edges that share an endpoint exactly into chains. */
    private static List<List<float[]>> chains(final List<float[]> edges) {
        final Map<Long, List<Integer>> at = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            final float[] e = edges.get(i);
            at.computeIfAbsent(key(e[0], e[1]), k -> new ArrayList<>(2)).add(i << 1);
            at.computeIfAbsent(key(e[2], e[3]), k -> new ArrayList<>(2)).add((i << 1) | 1);
        }
        final boolean[] used = new boolean[edges.size()];
        final List<List<float[]>> out = new ArrayList<>();
        for (int seed = 0; seed < edges.size(); seed++) {
            if (used[seed])
                continue;
            final List<float[]> fwd = walk(edges, at, used, seed, 0);
            final List<float[]> back = walk(edges, at, used, seed, 1);
            final List<float[]> chain = new ArrayList<>();
            for (int i = back.size() - 1; i >= 1; i--)
                chain.add(back.get(i));
            chain.addAll(fwd);
            if (!chain.isEmpty())
                out.add(chain);
        }
        return out;
    }

    private static List<float[]> walk(final List<float[]> edges, final Map<Long, List<Integer>> at,
                                      final boolean[] used, final int seed, final int entryEnd) {
        final List<float[]> path = new ArrayList<>();
        int cur = seed;
        int end = entryEnd;
        while (true) {
            used[cur] = true;
            final float[] e = edges.get(cur);
            final float ax = (end == 0) ? e[0] : e[2];
            final float az = (end == 0) ? e[1] : e[3];
            final float bx = (end == 0) ? e[2] : e[0];
            final float bz = (end == 0) ? e[3] : e[1];
            path.add(new float[]{ax, az, bx, bz});
            final List<Integer> cand = at.get(key(bx, bz));
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
        return path;
    }

    private static long key(final float x, final float z) {
        return ((long) Math.round(x * 64F) << 32) ^ (Math.round(z * 64F) & 0xffffffffL);
    }

    /** Emits one chain; returns the widest beach it stood on, in blocks. */
    private static int emitChain(final ShoreScan.Grid g, final Level level, final ShoreScan.Quads out,
                                  final List<float[]> chain, final float f) {
        final int n = chain.size();
        final float[] vx = new float[n + 1];
        final float[] vz = new float[n + 1];
        vx[0] = chain.get(0)[0];
        vz[0] = chain.get(0)[1];
        for (int i = 0; i < n; i++) {
            vx[i + 1] = chain.get(i)[2];
            vz[i + 1] = chain.get(i)[3];
        }
        final boolean closed = Math.abs(vx[0] - vx[n]) < 1.0e-4F && Math.abs(vz[0] - vz[n]) < 1.0e-4F;
        if (!closed) {
            g.fieldOpenChains++;
            // An open end close to the window border means the scan cut the coast, not
            // that the coast ends there - the difference between a real dead end and a
            // segment that flickers with the player's position.
            if (nearBorder(g, vx[0], vz[0]))
                g.fieldBorderEnds++;
            if (nearBorder(g, vx[n], vz[n]))
                g.fieldBorderEnds++;
        }

        // Segment normals, pointing inland.
        final float[] nx = new float[n];
        final float[] nz = new float[n];
        for (int i = 0; i < n; i++) {
            final float dx = vx[i + 1] - vx[i];
            final float dz = vz[i + 1] - vz[i];
            final float len = (float) Math.hypot(dx, dz);
            nx[i] = len < 1.0e-4F ? 0F : dz / len;
            nz[i] = len < 1.0e-4F ? 0F : -dx / len;
        }

        // Distance each segment may travel, clamped by the beach under it and
        // smoothed along the chain so neighbouring distances differ by little.
        final float[] fseg = new float[n];
        int widest = 0;
        for (int i = 0; i < n; i++) {
            final float room = room(g, (vx[i] + vx[i + 1]) * 0.5F, (vz[i] + vz[i + 1]) * 0.5F, nx[i], nz[i]);
            widest = Math.max(widest, Math.round(room));
            fseg[i] = Math.max(0F, Math.min(f, room - 1F));
            g.fieldFMin = Math.min(g.fieldFMin, fseg[i]);
            g.fieldFMax = Math.max(g.fieldFMax, fseg[i]);
        }
        for (int pass = 0; pass < 2; pass++)
            for (int i = 1; i < n - 1; i++)
                fseg[i] = (fseg[i - 1] + 2F * fseg[i] + fseg[i + 1]) * 0.25F;

        // Vertex = intersection of the two offset lines: C + nA*fA + nB*fB.
        final float[] ox = new float[n + 1];
        final float[] oz = new float[n + 1];
        for (int v = 0; v <= n; v++) {
            final int a = (v == 0) ? (closed ? n - 1 : 0) : v - 1;
            final int b = (v == n) ? (closed ? 0 : n - 1) : v;
            final boolean same = Math.abs(nx[a] - nx[b]) < 1.0e-4F && Math.abs(nz[a] - nz[b]) < 1.0e-4F;
            if (same) {
                final float fa = (fseg[a] + fseg[b]) * 0.5F;
                ox[v] = vx[v] + nx[a] * fa;
                oz[v] = vz[v] + nz[a] * fa;
            } else {
                ox[v] = vx[v] + nx[a] * fseg[a] + nx[b] * fseg[b];
                oz[v] = vz[v] + nz[a] * fseg[a] + nz[b] * fseg[b];
            }
        }

        final float base = ShoreScan.BAND_WIDTH;
        float along = 0F;
        for (int i = 0; i < n; i++) {
            final boolean startTurn = i > 0 && Math.abs(nx[i - 1] * nx[i] + nz[i - 1] * nz[i]) < 0.1F;
            final boolean endTurn = i + 1 < n && Math.abs(nx[i] * nx[i + 1] + nz[i] * nz[i + 1]) < 0.1F;
            // cornerGrow is NEGATIVE, so "pull" is the amount to walk back along this
            // band from the intersection, towards its own interior: that is the
            // maintainer's "cover only half of the band you cross".
            // Only a LONG piece retracts at a turn. On a staircased coast every
            // segment is one block and both ends are turns, so retracting by a fixed
            // distance took a third out of every piece and the line came out dashed
            // in game while the long-segment test scene looked perfect. A one-block
            // piece stays full length and does the work of joining the staircase.
            final float pull = -cornerGrow * base;
            final boolean longPiece = Math.hypot(vx[i + 1] - vx[i], vz[i + 1] - vz[i]) > 1.5F;
            final float ps = (startTurn && longPiece) ? pull : 0F;
            final float pe = (endTurn && longPiece) ? pull : 0F;
            final float dxs = vx[i + 1] - vx[i];
            final float dzs = vz[i + 1] - vz[i];
            final float el = (float) Math.hypot(dxs, dzs);
            final float ux = el < 1.0e-4F ? 1F : dxs / el;
            final float uz = el < 1.0e-4F ? 0F : dzs / el;
            final float w = base * (1F + ((startTurn || endTurn) ? cornerWiden : 0F));
            // Inner edge along the SEGMENT normal: every piece is a rectangle. The
            // corner notch that leaves is covered by the crossing band instead (it runs
            // half a band width past the turn and is slightly wider), which is the
            // construction the maintainer drew: no slant, no gap, no extra square.
            final float inEx = nx[i];
            final float inEz = nz[i];
            final float inFx = nx[i];
            final float inFz = nz[i];
            final float s0x = ox[i] + ux * ps, s0z = oz[i] + uz * ps;
            final float s1x = ox[i + 1] - ux * pe, s1z = oz[i + 1] - uz * pe;
            final int gx = (int) Math.floor((s0x + s1x) * 0.5F);
            final int gz = (int) Math.floor((s0z + s1z) * 0.5F);
            final int alpha = (gx < 0 || gz < 0 || gx >= g.size || gz >= g.size) ? 0
                    : ShoreScan.edgeAlpha(g, gx, gz);
            final float segLen = (float) Math.hypot(s1x - s0x, s1z - s0z);
            if (alpha <= 0 || segLen < 1.0e-4F) {
                if (gx < 0 || gz < 0 || gx >= g.size || gz >= g.size)
                    g.fieldSkipBounds++;
                else
                    g.fieldSkipAlpha++;
                along += segLen;
                continue;
            }
            g.fieldSegs++;
            final ShoreScan.ShoreQuad q = out.next();
            q.tex = ShoreScan.TEX_STRIP;
            q.kind = 0;
            q.rgb = ShoreScan.FOAM_RGB;
            // Flat water level + lift. (A per-quad tallest-ground version was tried
            // for the pieces that vanish near steps; the maintainer says that is NOT the
            // cause - whole stretches go missing and the damage persists across world
            // reloads - so it was reverted.)
            q.y = g.waterTop + 1F + ShoreScan.FOAM_LIFT;
            q.light = ShoreScan.lightAt(level, g, g.ox + gx, g.oz + gz);
            q.alpha = alpha;
            q.wx[0] = g.ox + s0x;
            q.wz[0] = g.oz + s0z;
            q.wx[1] = g.ox + s1x;
            q.wz[1] = g.oz + s1z;
            q.wx[2] = g.ox + s1x + inFx * w;
            q.wz[2] = g.oz + s1z + inFz * w;
            q.wx[3] = g.ox + s0x + inEx * w;
            q.wz[3] = g.oz + s0z + inEz * w;
            q.u[0] = along / ShoreScan.STRIP_PERIOD;
            q.u[1] = (along + segLen) / ShoreScan.STRIP_PERIOD;
            q.u[2] = q.u[1];
            q.u[3] = q.u[0];
            q.v[0] = 0.7F;
            q.v[1] = 0.7F;
            q.v[2] = 0F;
            q.v[3] = 0F;
            g.stripQuads++;
            g.lineBlocks += segLen;
            g.lineTexels += segLen * ShoreScan.STRIP_TEX_WIDTH;
            along += segLen;
        }
        return widest;
    }

    /** True when a point sits within two blocks of the scan window's edge. */
    private static boolean nearBorder(final ShoreScan.Grid g, final float x, final float z) {
        final int m = 2;
        return x < m || z < m || x > g.size - 1 - m || z > g.size - 1 - m;
    }

    /** Beach depth under a coastal normal, in blocks, capped at 4. */
    private static float room(final ShoreScan.Grid g, final float cx, final float cz,
                              final float nx, final float nz) {
        float r = 0F;
        for (float d = 0.5F; d <= 4.0F; d += 0.5F) {
            final int gx = (int) Math.floor(cx + nx * d);
            final int gz = (int) Math.floor(cz + nz * d);
            if (gx < 0 || gz < 0 || gx >= g.size || gz >= g.size)
                break;
            if (g.cls[gz * g.size + gx] != ShoreScan.CLS_BEACH)
                break;
            r = d;
        }
        return r;
    }
}