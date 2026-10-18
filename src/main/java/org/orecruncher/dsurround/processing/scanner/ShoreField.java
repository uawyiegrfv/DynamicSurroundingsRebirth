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

    private static net.minecraft.client.renderer.texture.TextureAtlasSprite water = null;
    private static float smoothed = -1F;
    private static long lastNanos = 0L;

    private ShoreField() {
    }

    /** A body of water smaller than this is a puddle, not a coast. */
    private static final int MIN_SEA_CELLS = 100;

    static void build(final ShoreScan.Grid g, final Level level, final float p,
                      final ShoreScan.Quads out) {
        // Water is counted per CONNECTED BODY, not over the whole window: the window
        // almost always holds a hundred cells of ocean somewhere, so a one-block puddle
        // dug in the sand still passed the old check and grew a ring of foam.
        final boolean[] bigSea = bigWaterBodies(g);
        final List<float[]> edges = coastEdges(g, level, bigSea);
        if (edges.isEmpty())
            return;

        water = waterSprite();
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
                    // The sea must belong to a real body of water, the sand must be sand
                    // (not a roof or a path), and the shore must be a beach or ocean
                    // biome. Checked per edge so a puddle inside a beach produces none.
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

    /** Floods the sea into connected bodies; true where the body has enough cells. */
    private static boolean[] bigWaterBodies(final ShoreScan.Grid g) {
        final int size = g.size;
        final int n = size * size;
        final int[] id = new int[n];
        java.util.Arrays.fill(id, -1);
        final int[] bodyCells = new int[n];
        final int[] stack = new int[n];
        int bodies = 0;
        for (int start = 0; start < n; start++) {
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
        final boolean[] big = new boolean[n];
        for (int i = 0; i < n; i++)
            big[i] = id[i] >= 0 && bodyCells[id[i]] >= MIN_SEA_CELLS;
        return big;
    }

    /** Foam only forms over sand, gravel, stone, sandstone or grass. */
    private static boolean surfBlock(final Level level, final ShoreScan.Grid g, final int gx, final int gz) {
        if (level == null)
            return true;
        // BELOW the surface: at waterTop the beach cell is the sea itself, so asking
        // there answered "water" for every normal shore and the surf survived only on
        // the raised steps where sand reaches the water line.
        final net.minecraft.world.level.block.state.BlockState st =
                level.getBlockState(new net.minecraft.core.BlockPos(g.ox + gx, g.waterTop - 1, g.oz + gz));
        return st.is(net.minecraft.world.level.block.Blocks.SAND)
                || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || st.is(net.minecraft.world.level.block.Blocks.STONE)
                || st.is(net.minecraft.world.level.block.Blocks.SANDSTONE)
                || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
    }

    /** ...and only in a beach or ocean biome. */
    private static boolean surfBiome(final Level level, final ShoreScan.Grid g, final int gx, final int gz) {
        if (level == null)
            return true;
        final net.minecraft.world.level.biome.Biome b =
                level.getBiome(new net.minecraft.core.BlockPos(g.ox + gx, g.waterTop, g.oz + gz)).value();
        final var key = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getResourceKey(b);
        return key.map(k -> {
            final String path = k.location().getPath();
            return path.contains("beach") || path.contains("ocean") || path.contains("shore");
        }).orElse(false);
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
            final int q0light = ShoreScan.lightAt(level, g, g.ox + gx, g.oz + gz);
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
            // The film first: from the water line (vx, vz) to the foam's seaward edge
            // (ox, oz). It is the SAME base line as the foam, so the film can never
            // pass the foam and never leaves a strip of bare sand between them.
            if (water != null) {
                final ShoreScan.ShoreQuad fq = out.next();
                fq.tex = ShoreScan.TEX_FILM;
                fq.kind = 0;
                fq.rgb = waterColour(level, g, gx, gz);
                fq.y = g.waterTop + 1F + ShoreScan.FOAM_LIFT * 0.5F;
                fq.light = q0light;
                fq.alpha = alpha;
                // Rectangles straight off the coastline. The film used the corner
                // INTERSECTION points before, and at a turn those are displaced along the
                // run: the quad then stretched across the corner and crossed its
                // neighbours - the tangle of triangles at every corner.
                fq.wx[0] = g.ox + vx[i];
                fq.wz[0] = g.oz + vz[i];
                fq.wx[1] = g.ox + vx[i + 1];
                fq.wz[1] = g.oz + vz[i + 1];
                // Reaches a whole band INLAND, under the foam: the wave sheet has
                // transparent parts, so stopping at the crest left a strip of bare sand
                // between the film and the visible crest.
                final float reach = fseg[i] + ShoreScan.BAND_WIDTH * 0.35F;
                fq.wx[2] = g.ox + vx[i + 1] + nx[i] * reach;
                fq.wz[2] = g.oz + vz[i + 1] + nz[i] * reach;
                fq.wx[3] = g.ox + vx[i] + nx[i] * reach;
                fq.wz[3] = g.oz + vz[i] + nz[i] * reach;
                // Vanilla's mapping: one whole tile per block, then the shrink ratio
                // pulls the corners towards the tile centre so the sampler never reads
                // the neighbouring sprite in the atlas. Without the shrink the film came
                // out as flat grey wallpaper.
                final float u0s = water.getU(0F), v0s = water.getV(0F);
                final float u1s = water.getU(16F), v1s = water.getV(16F);
                final float cu = (u0s + u1s) * 0.5F, cv = (v0s + v1s) * 0.5F;
                final float shrink = water.uvShrinkRatio();
                for (int k = 0; k < 4; k++) {
                    final float px = fq.wx[k] - (float) Math.floor(fq.wx[k]);
                    final float pz = fq.wz[k] - (float) Math.floor(fq.wz[k]);
                    float uu = u0s + (u1s - u0s) * px;
                    float vv = v0s + (v1s - v0s) * pz;
                    fq.u[k] = net.minecraft.util.Mth.lerp(shrink, uu, cu);
                    fq.v[k] = net.minecraft.util.Mth.lerp(shrink, vv, cv);
                }
            }
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

    /** The biome's own water colour - what vanilla tints the sea with. */
    private static int waterColour(final Level level, final ShoreScan.Grid g, final int gx, final int gz) {
        if (level == null)
            return 0x3F76E4;
        return level.getBiome(new net.minecraft.core.BlockPos(g.ox + gx, g.waterTop, g.oz + gz))
                .value().getWaterColor() & 0xFFFFFF;
    }

    /** Vanilla's water sprite, for the film's colour and its animation. */
    private static net.minecraft.client.renderer.texture.TextureAtlasSprite waterSprite() {
        return net.minecraft.client.Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/water_still"));
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