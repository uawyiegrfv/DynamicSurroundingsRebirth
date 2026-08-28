package org.orecruncher.dsurround.gui.keyboard;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.gui.overlay.DiagnosticsOverlay;
import org.orecruncher.dsurround.gui.sound.IndividualSoundControlScreen;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.config.IConfigScreenFactoryProvider;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.platform.PlatformCompat;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.sound.IAudioPlayer;

import java.util.ArrayList;
import java.util.List;

public class KeyBindings {

    public static final KeyMapping modConfigurationMenu;
    public static final KeyMapping individualSoundConfigBinding;
    public static final KeyMapping diagnosticHud;
    public static final KeyMapping quickSoundVolume;

    // 1.20.1: category is a translation-key string (KeyMapping.Category is 1.21+)
    // Use the existing 26.1-era lang key (key.category.dsurround.keybind).
    private static final String CATEGORY = "key.category.dsurround.keybind";
    private static final List<KeyMapping> KEY_MAPPINGS = new ArrayList<>();

    static {
        var modMenuKey = PlatformCompat.isModLoaded(Constants.MODMENU) ? InputConstants.UNKNOWN.getValue() : InputConstants.KEY_EQUALS;
        modConfigurationMenu = registerKeyBinding("dsurround.text.keybind.modConfigurationMenu", modMenuKey);
        individualSoundConfigBinding = registerKeyBinding("dsurround.text.keybind.individualSoundConfig", InputConstants.UNKNOWN.getValue());
        diagnosticHud = registerKeyBinding("dsurround.text.keybind.diagnosticHud", InputConstants.UNKNOWN.getValue());
        // Hold together with Ctrl to open the quick sound volume overlay.
        quickSoundVolume = registerKeyBinding("dsurround.text.keybind.quickSoundVolume", InputConstants.KEY_GRAVE);
    }

    private static KeyMapping registerKeyBinding(String translationKey, int code) {
        var mapping = new KeyMapping(translationKey, code, CATEGORY);
        KEY_MAPPINGS.add(mapping);
        return mapping;
    }

    public static void register(RegisterKeyMappingsEvent event) {
        KEY_MAPPINGS.forEach(event::register);
    }

    public static void register() {
        ClientState.TICK_END.register(KeyBindings::handleMenuKeyPress);
    }

    private static void handleMenuKeyPress(Minecraft client) {
        if (GameUtils.getCurrentScreen().isPresent() || GameUtils.getPlayer().isEmpty())
            return;

        if (modConfigurationMenu.consumeClick()) {
            var provider = ContainerManager.resolve(IConfigScreenFactoryProvider.class);
            var factory = provider.getModConfigScreenFactory(Configuration.class);
            if (factory.isPresent()) {
                GameUtils.setScreen(factory.get().create(null));
            } else {
                Library.LOGGER.info("Configuration GUI libraries not present");
            }
        }

        if (diagnosticHud.consumeClick())
            ContainerManager.resolve(DiagnosticsOverlay.class).toggleCollection();

        if (individualSoundConfigBinding.consumeClick()) {
            final boolean singlePlayer = GameUtils.isSinglePlayer();
            GameUtils.setScreen(new IndividualSoundControlScreen(null, singlePlayer));
            if (singlePlayer)
                ContainerManager.resolve(IAudioPlayer.class).stopAll();
        }
    }
}
