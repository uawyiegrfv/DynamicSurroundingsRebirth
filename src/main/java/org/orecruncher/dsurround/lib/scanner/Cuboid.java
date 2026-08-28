package org.orecruncher.dsurround.lib.scanner;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public final class Cuboid {

    public static BoundingBox of(BlockPos[] points) {
        return BoundingBox.fromCorners(points[0], points[1]);
    }

    public static BoundingBox of(BlockPos pos1, BlockPos pos2) {
        return BoundingBox.fromCorners(pos1, pos2);
    }

    public static boolean intersects(BoundingBox box1, BoundingBox box2) {
        var meMin = new BlockPos(box1.minX(), box1.minY(), box1.minZ());
        var meMax = new BlockPos(box1.maxX(), box1.maxY(), box1.maxZ());
        var oMin = new BlockPos(box2.minX(), box2.minY(), box2.minZ());
        var oMax = new BlockPos(box2.maxX(), box2.maxY(), box2.maxZ());
        return meMin.getX() <= oMax.getX()
                && meMax.getX() >= oMin.getX()
                && meMin.getY() <= oMax.getY()
                && meMax.getY() >= oMin.getY()
                && meMin.getZ() <= oMax.getZ()
                && meMax.getZ() >= oMin.getZ();
    }

    @Nullable
    public static BoundingBox intersection(BoundingBox box1, BoundingBox box2) {
        if (intersects(box1, box2)) {
            var meMin = new BlockPos(box1.minX(), box1.minY(), box1.minZ());
            var meMax = new BlockPos(box1.maxX(), box1.maxY(), box1.maxZ());
            var oMin = new BlockPos(box2.minX(), box2.minY(), box2.minZ());
            var oMax = new BlockPos(box2.maxX(), box2.maxY(), box2.maxZ());
            int minX = Math.max(meMin.getX(), oMin.getX());
            int minY = Math.max(meMin.getY(), oMin.getY());
            int minZ = Math.max(meMin.getZ(), oMin.getZ());
            int maxX = Math.min(meMax.getX(), oMax.getX());
            int maxY = Math.min(meMax.getY(), oMax.getY());
            int maxZ = Math.min(meMax.getZ(), oMax.getZ());
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return null;
    }
}