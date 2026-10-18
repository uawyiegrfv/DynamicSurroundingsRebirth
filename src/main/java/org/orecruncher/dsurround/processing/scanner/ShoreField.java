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
    public static float cornerGrow = 0F;
    /** Extra width on a band that ends at a corner, in band widths. */
    public static float cornerWiden = 0F;
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
        // Only water that is PART OF A BIG BODY gets surf. Counting sea cells in the
        // window was not enough: the window almost always holds a hundred cells of
        // ocean somewhere, so a one-block puddle on the beach still grew a ring of
        // foam. This floods the sea into connected bodies and marks the big ones.
        final boolean[] bigSea = bigWaterBodies(g);
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
        final float f = Math.max(0F, Math.min(MAX_OFFSET, smoothed * MAX_OFFSET));
        g.fieldLevel = f;

        // Distance to the sea at every cell centre, in blocks, water line at 0.
        final int size = g.size;
        final float[] d = new float[size * size];
        java.util.Arrays.fill(d, 99F);
        for (int cz = 0; cz < size; cz++) {
            for (int cx = 0; cx < size; cx++) {
                if (g.cls[cz * size + cx] != ShoreScan.CLS_SEA)
                    continue;
                for (int vz = 0; vz < size; vz++) {
                    for (int vx = 0; vx < size; vx++) {
                        final float dx = vx - cx, dz = vz - cz;
                        // -1: the sea is a square, so the beach cell that touches it must
                        // read 0, not 0.5. With -0.5 the first row of sand was still 0.5
                        // away and the surf only appeared once the swell passed half a
                        // block - which is why nothing showed at low p.
                        final float dist = Math.max(0F, (float) Math.sqrt(dx * dx + dz * dz) - 1F);
                        if (dist < d[vz * size + vx])
                            d[vz * size + vx] = dist;
                    }
                }
            }
        }

        // Which cells the water has reached: one cell thick, sitting on the level
        // line d = f. The set comes from the FIELD, so a narrow neck closes by itself
        // - once f passes the neck's own distance both shores are inside the band and
        // the line reads as one, with no merging code anywhere.
        final boolean[] wet = new boolean[size * size];
        final byte[] orient = new byte[size * size];   // 1 = runs along z, 2 = along x
        for (int gz = 0; gz < size; gz++) {
            for (int gx = 0; gx < size; gx++) {
                final int gi = gz * size + gx;
                if (g.cls[gi] != ShoreScan.CLS_BEACH)
                    continue;
                if (d[gi] > f || d[gi] <= f - ShoreScan.BAND_WIDTH)
                    continue;
                if (!surfBiome(level, g, gx, gz))
                    continue;
                if (!surfBlock(level, g, gx, gz))
                    continue;
                float best = d[gi];
                int inl = 1;
                if (gx + 1 < size && d[gi + 1] > best) { best = d[gi + 1]; inl = 1; }
                if (gx > 0 && d[gi - 1] > best) { best = d[gi - 1]; inl = -1; }
                if (gz + 1 < size && d[gi + size] > best) { best = d[gi + size]; inl = 2; }
                if (gz > 0 && d[gi - size] > best) { best = d[gi - size]; inl = -2; }
                wet[gi] = true;
                orient[gi] = (byte) (Math.abs(inl) == 1 ? 1 : 2);
            }
        }

        // Bridge the diagonal steps. On a 45-degree shore the wet cells line up
        // corner-to-corner, and two axis-aligned rectangles that only share a corner
        // leave a gap - which is exactly the row of dashes the maintainer sees on a
        // staircased coast. Filling ONE of the two cells between each such pair turns
        // the diagonal into a staircase of quads that share edges, and it stays
        // axis-aligned: the filler is an ordinary cell, not a special corner piece.
        final boolean[] wet0 = wet.clone();
        final byte[] orient0 = orient.clone();
        for (int gz = 0; gz + 1 < size; gz++) {
            for (int gx = 0; gx + 1 < size; gx++) {
                final int a = gz * size + gx;
                final int b = (gz + 1) * size + gx + 1;
                final int c = gz * size + gx + 1;
                final int e = (gz + 1) * size + gx;
                if (wet0[a] && wet0[b] && !wet0[c] && !wet0[e]) {
                    wet[c] = true;
                    orient[c] = orient0[b];
                }
                final int a2 = gz * size + gx + 1;
                final int b2 = (gz + 1) * size + gx;
                final int c2 = gz * size + gx;
                final int e2 = (gz + 1) * size + gx + 1;
                if (wet0[a2] && wet0[b2] && !wet0[c2] && !wet0[e2]) {
                    wet[e2] = true;
                    orient[e2] = orient0[a2];
                }
            }
        }

        // Consecutive collinear cells become ONE quad. Drawing them one per cell cut the
        // texture into quarters: the strip art repeats every four blocks, so a per-cell
        // u made every block show the same slice and the line read as a row of dashes.
        for (int gz = 0; gz < size; gz++) {
            for (int gx = 0; gx < size; gx++) {
                final int gi = gz * size + gx;
                if (!wet[gi] || orient[gi] != 2)
                    continue;
                if (gx > 0 && wet[gi - 1] && orient[gi - 1] == 2)
                    continue;
                int len = 0;
                while (gx + len < size && wet[gi + len] && orient[gi + len] == 2)
                    len++;
                emitRun(g, level, out, gx, gz, len, 1, 0);
            }
        }
        for (int gx = 0; gx < size; gx++) {
            for (int gz = 0; gz < size; gz++) {
                final int gi = gz * size + gx;
                if (!wet[gi] || orient[gi] != 1)
                    continue;
                if (gz > 0 && wet[gi - size] && orient[gi - size] == 1)
                    continue;
                int len = 0;
                while (gz + len < size && wet[gi + len * size] && orient[gi + len * size] == 1)
                    len++;
                emitRun(g, level, out, gx, gz, len, 0, 1);
            }
        }
        // The film: everything the water has reached, drawn as one flat sheet under the
        // foam. It comes from the SAME field and the same f, so the foam line sits
        // exactly on the film's inner edge - which is the "real connection" the shape
        // needs, not a second geometry that has to be kept in step.
        for (int gz = 0; gz < size; gz++) {
            for (int gx = 0; gx < size; gx++) {
                final int gi = gz * size + gx;
                if (g.cls[gi] != ShoreScan.CLS_BEACH || !wet[gi])
                    continue;
                final int alpha = ShoreScan.edgeAlpha(g, gx, gz);
                if (alpha <= 0)
                    continue;
                final ShoreScan.ShoreQuad q = out.next();
                q.tex = ShoreScan.TEX_FILM;
                q.kind = 0;
                q.rgb = ShoreScan.FOAM_RGB;
                q.y = g.waterTop + 1F + ShoreScan.FOAM_LIFT * 0.5F;
                q.light = ShoreScan.lightAt(level, g, g.ox + gx, g.oz + gz);
                q.alpha = Math.min(alpha, 110);
                q.wx[0] = g.ox + gx;
                q.wz[0] = g.oz + gz;
                q.wx[1] = g.ox + gx + 1;
                q.wz[1] = g.oz + gz;
                q.wx[2] = g.ox + gx + 1;
                q.wz[2] = g.oz + gz + 1;
                q.wx[3] = g.ox + gx;
                q.wz[3] = g.oz + gz + 1;
                final float uu = (gx + gz) / (float) ShoreScan.STRIP_PERIOD;
                q.u[0] = uu;
                q.u[1] = uu + 1F / ShoreScan.STRIP_PERIOD;
                q.u[2] = q.u[1];
                q.u[3] = uu;
                q.v[0] = 0F;
                q.v[1] = 0F;
                q.v[2] = 1F;
                q.v[3] = 1F;
                g.stripQuads++;
            }
        }
        g.fieldChains = 0;
        g.fieldFieldMax = 0;
    }

    /** One quad for a run of {@code len} wet cells, u spread across its whole length. */
    private static void emitRun(final ShoreScan.Grid g, final Level level, final ShoreScan.Quads out,
                                final int gx, final int gz, final int len,
                                final int dx, final int dz) {
        final int cx = gx + (dx * len) / 2;
        final int cz = gz + (dz * len) / 2;
        final int alpha = ShoreScan.edgeAlpha(g, cx, cz);
        if (alpha <= 0 || len <= 0)
            return;
        final ShoreScan.ShoreQuad q = out.next();
        q.tex = ShoreScan.TEX_STRIP;
        q.kind = 0;
        q.rgb = ShoreScan.FOAM_RGB;
        q.y = g.waterTop + 1F + ShoreScan.FOAM_LIFT;
        q.light = ShoreScan.lightAt(level, g, g.ox + cx, g.oz + cz);
        q.alpha = alpha;
        q.wx[0] = g.ox + gx;
        q.wz[0] = g.oz + gz;
        q.wx[1] = g.ox + gx + dx * len;
        q.wz[1] = g.oz + gz + dz * len;
        q.wx[2] = g.ox + gx + dx * len + dz;
        q.wz[2] = g.oz + gz + dz * len + dx;
        q.wx[3] = g.ox + gx + dz;
        q.wz[3] = g.oz + gz + dx;
        float u0 = (dx == 0 ? gz : gx) / ShoreScan.STRIP_PERIOD;
        float u1 = u0 + len / ShoreScan.STRIP_PERIOD;
        if (len < ShoreScan.STRIP_PERIOD) {
            // A run shorter than the texture's four-block period can only show a slice
            // of the artwork, and a random slice reads as a gap. Centre the motif on
            // the piece instead, so every short piece carries a whole crest.
            final float span = len / (float) ShoreScan.STRIP_PERIOD;
            final float centre = (float) Math.floor(u0) + 0.5F;
            u0 = centre - span * 0.5F;
            u1 = u0 + span;
        }
        q.u[0] = u0;
        q.u[1] = u1;
        q.u[2] = u1;
        q.u[3] = u0;
        q.v[0] = 0.7F;
        q.v[1] = 0.7F;
        q.v[2] = 0F;
        q.v[3] = 0F;
        g.stripQuads++;
    }

    /** Floods the sea into connected bodies; true where the body has enough cells. */
    private static boolean[] bigWaterBodies(final ShoreScan.Grid g) {
        final int size = g.size;
        final int[] sizeOfBody = new int[size * size];
        final int[] id = new int[size * size];
        java.util.Arrays.fill(id, -1);
        final int[] stack = new int[size * size];
        int bodies = 0;
        final int[] bodyCells = new int[size * size];
        for (int start = 0; start < size * size; start++) {
            if (g.cls[start] != ShoreScan.CLS_SEA || id[start] >= 0)
                continue;
            int sp = 0;
            int cells = 0;
            stack[sp++] = start;
            id[start] = bodies;
            while (sp > 0) {
                final int cur = stack[--sp];
                cells++;
                final int cx = cur % size, cz = cur / size;
                for (int k = 0; k < 4; k++) {
                    final int nx2 = cx + (k == 0 ? 1 : k == 1 ? -1 : 0);
                    final int nz2 = cz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (nx2 < 0 || nz2 < 0 || nx2 >= size || nz2 >= size)
                        continue;
                    final int ni = nz2 * size + nx2;
                    if (g.cls[ni] != ShoreScan.CLS_SEA || id[ni] >= 0)
                        continue;
                    id[ni] = bodies;
                    stack[sp++] = ni;
                }
            }
            bodyCells[bodies++] = cells;
        }
        final boolean[] big = new boolean[size * size];
        for (int i = 0; i < size * size; i++)
            big[i] = id[i] >= 0 && bodyCells[id[i]] >= MIN_SEA_CELLS;
        return big;
    }

    /** Foam only forms over sand, stone or grass - not over a dirt path or a roof. */
    private static boolean surfBlock(final Level level, final ShoreScan.Grid g, final int gx, final int gz) {
        if (level == null)
            return true;   // headless previews have no world to ask
        final net.minecraft.world.level.block.state.BlockState st =
                level.getBlockState(new net.minecraft.core.BlockPos(g.ox + gx, g.waterTop, g.oz + gz));
        return st.is(net.minecraft.world.level.block.Blocks.SAND)
                || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || st.is(net.minecraft.world.level.block.Blocks.STONE)
                || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || st.is(net.minecraft.world.level.block.Blocks.SANDSTONE);
    }

    /** ...and only in a beach or ocean biome. */
    private static boolean surfBiome(final Level level, final ShoreScan.Grid g, final int gx, final int gz) {
        if (level == null)
            return true;
        final net.minecraft.world.level.biome.Biome b =
                level.getBiome(new net.minecraft.core.BlockPos(g.ox + gx, g.waterTop, g.oz + gz)).value();
        final var key = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getResourceKey(b);
        return key.map(k -> k.location().getPath().contains("beach")
                || k.location().getPath().contains("ocean")
                || k.location().getPath().contains("shore")).orElse(false);
    }

    /** Every unit edge where a sea cell touches a beach cell, beach on the right. */
    private static List<float[]> coastEdges(final ShoreScan.Grid g, final Level level, final boolean[] bigSea) {
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
                    // The sea it belongs to must be a real body of water, the beach must
                    // be sand/stone/grass, and the shore must be a beach or ocean biome -
                    // a pond dug into a plains hill is none of those.
                    if (!bigSea[gz * size + gx])
                        continue;
                    if (!surfBlock(level, g, ax, az) || !surfBiome(level, g, gx, gz))
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
            // Half the sand width, minus half a band: the swell from one shore stops just
            // short of the middle, so the swell from the other shore meets it there and
            // the two read as ONE line. On a wide beach this is far beyond the one-block
            // limit and changes nothing; it only bites where the bar is narrower than the
            // swell, which is exactly the neck the maintainer drew.
            fseg[i] = Math.max(0F, Math.min(f, room * 0.5F - ShoreScan.BAND_WIDTH * 0.5F));
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
            final float pull = -cornerGrow * base;
            final float ps = startTurn ? pull : 0F;
            final float pe = endTurn ? pull : 0F;
            final float dxs = vx[i + 1] - vx[i];
            final float dzs = vz[i + 1] - vz[i];
            final float el = (float) Math.hypot(dxs, dzs);
            final float ux = el < 1.0e-4F ? 1F : dxs / el;
            final float uz = el < 1.0e-4F ? 0F : dzs / el;
            final float w = base * (1F + ((startTurn || endTurn) ? cornerWiden : 0F));
            final float inEx = startTurn ? nx[i - 1] + nx[i] : nx[i];
            final float inEz = startTurn ? nz[i - 1] + nz[i] : nz[i];
            final float inFx = endTurn ? nx[i] + nx[i + 1] : nx[i];
            final float inFz = endTurn ? nz[i] + nz[i + 1] : nz[i];
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
        // Inland only. Measuring the seaward side as well returns zero everywhere (the
        // very next cell in that direction is water), which froze the swell at f = 0 -
        // the surf stopped moving completely. The width of a sand bar is already what
        // this returns: it walks inland until the sand ends, and on a bar that end is the
        // water on the far side.
        return depth(g, cx, cz, nx, nz);
    }

    /** How far sand continues from a point along a direction, in blocks. */
    private static float depth(final ShoreScan.Grid g, final float cx, final float cz,
                               final float nx, final float nz) {
        float r = 0F;
        for (float d = 0.5F; d <= 8.0F; d += 0.5F) {
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