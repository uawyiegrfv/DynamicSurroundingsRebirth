package org.orecruncher.dsurround.lib.config.compat;

import org.orecruncher.dsurround.lib.platform.PlatformCompat;
import net.minecraft.network.chat.Style;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.config.ConfigOptions;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.config.IConfigScreenFactoryProvider;
import org.orecruncher.dsurround.lib.config.IScreenFactory;
import org.orecruncher.dsurround.lib.gui.ColorPalette;

import java.util.Optional;

public class ClothAPIFactoryProvider implements IConfigScreenFactoryProvider {

    @Override
    public Optional<IScreenFactory<?>> getModConfigScreenFactory(Class<? extends ConfigurationData> configClass) {

        IScreenFactory<?> result = null;

        if (PlatformCompat.isModLoaded(Constants.CLOTH_CONFIG_FABRIC) || PlatformCompat.isModLoaded(Constants.CLOTH_CONFIG_NEOFORGE) ) {
            var configData = ConfigurationData.getConfig(configClass);

            ConfigOptions options = new ConfigOptions()
                    .setTranslationRoot("dsurround.config")
                    .setTitleStyle(Style.EMPTY.withColor(ColorPalette.PUMPKIN_ORANGE.getValue()))
                    .setPropertyGroupStyle(Style.EMPTY.withColor(ColorPalette.GOLDENROD.getValue()))
                    .setPropertyStyle(Style.EMPTY.withColor(ColorPalette.WHEAT.getValue()))
                    .setTooltipStyle(Style.EMPTY.withColor(ColorPalette.SEASHELL.getValue()))
                    .wrapToolTip(true);

            result = new ClothAPIFactory(options, configData)::apply;
        }

        return Optional.ofNullable(result);
    }
}
