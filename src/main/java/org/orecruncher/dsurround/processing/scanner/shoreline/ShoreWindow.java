package org.orecruncher.dsurround.processing.scanner.shoreline;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures a square window of shore classification around the player and can
 * serialize it for offline replay.
 *
 * <p>Classification deliberately copies the removed shore scan, because that
 * part was measured to work: the beach is the column whose ground is exactly at
 * the water plane, land is ground above it, sea is ground below it with water at
 * the plane. No material tags - a stone shore is still a shore.</p>
 */
public final class ShoreWindow implements ShoreFront.World {

    public static final int UNKNOWN = ShoreFront.UNKNOWN;
    private static final int WINDOW_LO = -3;
    private static final int WINDOW_HI = 2;

    private final int originX, originZ, size, seaLevel;
    private final byte[] kind;

    private ShoreWindow(int originX, int originZ, int size, int seaLevel, byte[] kind) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.seaLevel = seaLevel;
        this.kind = kind;
    }

    public static ShoreWindow capture(final Level level, final BlockPos center, final int size) {
        final int half = size / 2;
        final int originX = center.getX() - half;
        final int originZ = center.getZ() - half;
        final int seaLevel = level.getSeaLevel();
        final int waterTop = seaLevel - 1;
        final byte[] kind = new byte[size * size];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int wx = originX + x, wz = originZ + z;
                if (!level.hasChunkAt(new BlockPos(wx, waterTop, wz))) {
                    kind[z * size + x] = (byte) ShoreFront.UNKNOWN;
                    continue;
                }
                pos.set(wx, waterTop, wz);
                final FluidState fs = level.getFluidState(pos);
                final boolean isSea = !fs.isEmpty() && fs.is(FluidTags.WATER);
                int ground = Integer.MIN_VALUE;
                for (int y = waterTop + WINDOW_HI; y >= waterTop + WINDOW_LO; y--) {
                    pos.set(wx, y, wz);
                    final BlockState s = level.getBlockState(pos);
                    if (s.isAir())
                        continue;
                    final FluidState own = s.getFluidState();
                    if (!own.isEmpty() && own.is(FluidTags.WATER))
                        continue;
                    ground = y;
                    break;
                }
                final int k;
                if (ground == waterTop) k = ShoreFront.BEACH;
                else if (ground > waterTop) k = ShoreFront.LAND;
                else k = isSea ? ShoreFront.SEA : ShoreFront.UNKNOWN;
                kind[z * size + x] = (byte) k;
            }
        }
        return new ShoreWindow(originX, originZ, size, seaLevel, kind);
    }

    @Override public int width() { return this.size; }
    @Override public int height() { return this.size; }

    @Override
    public int kind(final int x, final int z) {
        if (x < 0 || z < 0 || x >= this.size || z >= this.size)
            return ShoreFront.UNKNOWN;
        return this.kind[z * this.size + x];
    }

    public int originX() { return this.originX; }
    public int originZ() { return this.originZ; }
    public int seaLevel() { return this.seaLevel; }

    /** One line per row, S/B/L/U; the first line is "originX,originZ,size,seaLevel". */
    public List<String> csv() {
        final List<String> lines = new ArrayList<>(this.size + 1);
        lines.add(this.originX + "," + this.originZ + "," + this.size + "," + this.seaLevel);
        for (int z = 0; z < this.size; z++) {
            final StringBuilder sb = new StringBuilder(this.size);
            for (int x = 0; x < this.size; x++)
                sb.append(switch (this.kind(x, z)) {
                    case ShoreFront.SEA -> 'S';
                    case ShoreFront.BEACH -> 'B';
                    case ShoreFront.LAND -> 'L';
                    default -> 'U';
                });
            lines.add(sb.toString());
        }
        return lines;
    }

    public String stats() {
        int sea = 0, beach = 0, land = 0, unknown = 0;
        for (final byte k : this.kind) {
            switch (k) {
                case ShoreFront.SEA -> sea++;
                case ShoreFront.BEACH -> beach++;
                case ShoreFront.LAND -> land++;
                default -> unknown++;
            }
        }
        return "sea=" + sea + " beach=" + beach + " land=" + land + " unknown=" + unknown;
    }
}
