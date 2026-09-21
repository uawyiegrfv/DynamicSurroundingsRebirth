package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.gui.screens.OptionsSubScreen;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists the DS volume sliders added to the vanilla sound options screen.
 *
 * <p>Those sliders write straight into the live config object, and nothing ever called save() - the
 * only save paths in the mod are the load-time rewrite and the DS config screen. So moving
 * "Footsteps", "Player Effects" or "Biomes" there worked for the session and was silently reverted at
 * the next launch, while the identical values persisted correctly when changed through the DS
 * screen.
 *
 * <p>This targets OptionsSubScreen rather than SoundOptionsScreen on purpose. removed() and onClose()
 * are DECLARED here, not in the sound screen - SoundOptionsScreen declares only addOptions(). An
 * injection into an inherited method from a subclass mixin risks a mixin apply failure at runtime,
 * which would silently leave the save unwired.
 *
 * <p>Both entry points are hooked and both call the same idempotent save, so a double save is
 * harmless: onClose() covers the ESC path, removed() covers the screen being replaced or the world
 * unloading.
 */
@Mixin(OptionsSubScreen.class)
public abstract class MixinOptionsSubScreen {

    @Inject(method = "onClose()V", at = @At("RETURN"))
    public void dsurround_saveOnClose(CallbackInfo ci) {
        saveConfig();
    }

    @Inject(method = "removed()V", at = @At("RETURN"))
    public void dsurround_saveOnRemoved(CallbackInfo ci) {
        saveConfig();
    }

    private static void saveConfig() {
        final var config = ConfigurationData.getConfig(Configuration.class);
        if (config != null)
            config.save();
    }
}
