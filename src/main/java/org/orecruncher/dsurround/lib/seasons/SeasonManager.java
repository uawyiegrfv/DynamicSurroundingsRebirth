package org.orecruncher.dsurround.lib.seasons;

import net.minecraftforge.fml.ModList;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.seasons.compat.SereneSeasons;
import org.orecruncher.dsurround.lib.seasons.compat.VanillaSeasons;

public class SeasonManager {

    public static final ISeasonalInformation HANDLER;

    static {
        // 1.20.1: Architectury Platform.isModLoaded -> Forge ModList
        if (ModList.get().isLoaded(Constants.SERENE_SEASONS)) {
            HANDLER = ContainerManager.resolve(SereneSeasons.class);
        } else {
            HANDLER = ContainerManager.resolve(VanillaSeasons.class);
        }

        Library.LOGGER.info("Season provider: %s", HANDLER.getProviderName());
    }
}
