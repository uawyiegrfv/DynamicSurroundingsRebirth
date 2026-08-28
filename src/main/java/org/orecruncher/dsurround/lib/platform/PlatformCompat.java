package org.orecruncher.dsurround.lib.platform;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Thin Forge-native replacements for the Architectury Platform calls used by 26.1.
 * (Architectury-forge 9.1.13 has dev-environment mixin incompatibilities; see
 * docs/1.20.1-MIGRATION-LOG.md 1.3.)
 */
public final class PlatformCompat {

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Optional<? extends ModContainer> getModContainer(String modId) {
        return ModList.get().getModContainerById(modId);
    }

    public static Path getConfigFolder() {
        return FMLPaths.CONFIGDIR.get();
    }
}
