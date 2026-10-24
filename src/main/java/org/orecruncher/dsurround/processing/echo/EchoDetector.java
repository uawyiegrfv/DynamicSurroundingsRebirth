package org.orecruncher.dsurround.processing.echo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.config.libraries.IBlockLibrary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the vertical faces around the listener that could return an echo.
 *
 * <p>Not a ray caster in the "shoot rays in every direction and hope" sense. For a flat wall the
 * specular reflection point is a closed-form geometric fact, and a fan of rays cannot be relied on
 * to land on it - that was measured and is why the previous attempt was silent (HANDOFF 8.91).
 * What this class does is find the WALLS: a plane and its normal are all the solver needs.</p>
 *
 * <p>16 azimuths, marched in one-block steps out to 80 blocks. A column is a wall cell when the
 * block at ear height is solid, the cell in front of it is open (an exposed face), and the terrain
 * above it rises at least {@link #MIN_WALL_HEIGHT} blocks over the ear - otherwise it is a slope,
 * and a slope scatters rather than reflects. Two perpendicular samples on each side must agree, so
 * a single pillar does not qualify.</p>
 */
public final class EchoDetector {

    private static final int AZIMUTHS = 16;
    private static final int MAX_DISTANCE = 80;
    private static final float MIN_DELAY_MS = 60F;
    /** Terrain must rise at least this far above the ear to count as a wall. */
    private static final int MIN_WALL_HEIGHT = 3;
    /** Samples either side of the hit that must also be solid: a face at least this wide. */
    private static final int WIDTH_SAMPLES = 2;
    private static final float MIN_SEPARATION_DEG = 30F;
    private static final int MAX_WALLS = 3;

    private final Level level;
    private final Vec3 ear;
    private final IBlockLibrary blocks;

    public EchoDetector(final Level level, final Vec3 ear, final IBlockLibrary blocks) {
        this.level = level;
        this.ear = ear;
        this.blocks = blocks;
    }

    /** Walls that could return an echo to this ear, strongest first, azimuth-deduplicated. */
    public List<EchoWall> detect() {
        final List<EchoWall> found = new ArrayList<>();
        final int earY = (int) Math.floor(this.ear.y);
        final double eyeX = this.ear.x;
        final double eyeZ = this.ear.z;

        for (int i = 0; i < AZIMUTHS; i++) {
            final double angle = Math.PI * 2.0D * i / AZIMUTHS;
            // MC yaw: 0 = +z (south), 90 = -x (west).
            final double dx = -Math.sin(angle);
            final double dz = Math.cos(angle);
            final EchoWall wall = march(dx, dz, earY, eyeX, eyeZ, i, angle);
            if (wall != null)
                found.add(wall);
        }

        // Strongest first, then drop walls within MIN_SEPARATION_DEG of a kept one: a canyon's two
        // walls are 180 deg apart, but a jagged face can report the same wall several times.
        found.sort(Comparator.comparingDouble(EchoWall::reflectivity).reversed());
        final List<EchoWall> kept = new ArrayList<>(MAX_WALLS);
        for (final EchoWall wall : found) {
            boolean tooClose = false;
            for (final EchoWall other : kept) {
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(wall.azimuthDeg() - other.azimuthDeg())) < MIN_SEPARATION_DEG) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                kept.add(wall);
                if (kept.size() == MAX_WALLS)
                    break;
            }
        }
        return kept;
    }

    private EchoWall march(final double dx, final double dz, final int earY,
                           final double eyeX, final double eyeZ, final int index, final double angle) {
        boolean wasOpen = true;
        for (int dist = 2; dist <= MAX_DISTANCE; dist++) {
            final int x = (int) Math.floor(eyeX + dx * dist);
            final int z = (int) Math.floor(eyeZ + dz * dist);
            final BlockPos cell = new BlockPos(x, earY, z);
            final BlockState state = this.level.getBlockState(cell);
            final boolean open = state.isAir() || !state.getFluidState().isEmpty();
            if (open) {
                wasOpen = true;
                continue;
            }
            // A solid cell at ear height, entered from an open one: an exposed vertical face.
            if (!wasOpen)
                continue;
            if (!isWallColumn(x, z, earY))
                continue;
            if (!hasWidth(x, z, dx, dz, earY))
                continue;
            final Vec3 normal = new Vec3(-dx, 0, -dz).normalize();
            // The face is the near side of the cell, half a block back along the ray.
            final Vec3 face = new Vec3(x + 0.5D - dx * 0.5D, earY, z + 0.5D - dz * 0.5D);
            final float reflectivity = this.blocks.getBlockInfoWeak(state).getSoundReflectivity();
            final float azimuth = mcYawFor(dx, dz);
            return new EchoWall(face, normal, WIDTH_SAMPLES + 0.5F, reflectivity, azimuth, dist);
        }
        return null;
    }

    private boolean isWallColumn(final int x, final int z, final int earY) {
        final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return surface >= earY + MIN_WALL_HEIGHT;
    }

    private boolean hasWidth(final int x, final int z, final double dx, final double dz, final int earY) {
        // Perpendicular direction in the horizontal plane.
        final double px = -dz;
        final double pz = dx;
        for (int s = -WIDTH_SAMPLES; s <= WIDTH_SAMPLES; s++) {
            if (s == 0)
                continue;
            final int sx = (int) Math.floor(x + px * s);
            final int sz = (int) Math.floor(z + pz * s);
            final BlockState state = this.level.getBlockState(new BlockPos(sx, earY, sz));
            if (state.isAir() || !state.getFluidState().isEmpty())
                return false;
        }
        return true;
    }

    private static float mcYawFor(final double dx, final double dz) {
        // Inverse of the yaw convention above: yaw = atan2(-dx, dz) in degrees.
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
