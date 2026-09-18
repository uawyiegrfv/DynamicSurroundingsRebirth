package org.orecruncher.dsurround.lib.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import org.orecruncher.dsurround.mixinutils.IClientWorld;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class WorldUtils {
    /** Field names the superflat flag may appear under: Mojang mapping, then the obfuscated runtime. */
    private static final String[] FLAT_FIELD_NAMES = {"isFlat", "f_104832_"};

    /**
     * Whether the world is a superflat one, for the {@code isSuperFlat} dimension variable.
     *
     * <p>This used to be an accessor mixin on {@code ClientLevel$ClientLevelData}. That mixin applies
     * lazily, the first time the class loads (i.e. on login), and it took the client down with
     * {@code NoClassDefFoundError} / {@code ClassNotFoundException} in a production instance - on a
     * class Oculus also mixes into ({@code sky.MixinClientLevelData_DisableVoidPlane}). It applies
     * cleanly in a development instance, so this is an interaction between mods rather than a defect in
     * the mixin, and the value it carries is decorative (a dimension variable for the JS condition
     * system). Reflection reads the same field with no mixin application at all, so there is nothing
     * left to conflict over.
     */
    public static boolean isSuperFlat(final Level world) {
        final LevelData info = world.getLevelData();
        if (info == null)
            return false;
        // Try both mapping names on the runtime class; the field is absent on other LevelData
        // implementations, and an unreadable field is reported as "not superflat" rather than throwing.
        for (final String name : FLAT_FIELD_NAMES) {
            try {
                final Field field = info.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.getBoolean(info);
            } catch (final NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
                // try the next candidate name
            }
        }
        return false;
    }

    public static BlockPos getTopSolidOrLiquidBlock(final Level world, final BlockPos pos) {
        return world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
    }

    public static int getPrecipitationHeight(final Level world, final BlockPos pos) {
        return world.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    public static List<BlockEntity> getLoadedBlockEntities(Level world, Predicate<BlockEntity> predicate) {
        var accessor = (IClientWorld) world;
        return accessor.dsurround_getLoadedChunks()
                .flatMap(chunk -> chunk.getBlockEntities().values().stream())
                .filter(predicate)
                .collect(Collectors.toList());
    }

    public static boolean isChunkLoaded(Level world, BlockPos pos) {
        return world.isLoaded(pos);
    }
}
