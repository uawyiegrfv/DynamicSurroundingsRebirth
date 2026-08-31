package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.orecruncher.dsurround.gui.sound.IndividualSoundControlScreen;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.gui.ColorPalette;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.mixinutils.IMusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundOptionsScreen.class)
public abstract class MixinSoundOptionsScreen extends OptionsSubScreen {

    public MixinSoundOptionsScreen(Screen screen, Options options, Component component) {
        super(screen, options, component);
    }

    @Inject(method = "addOptions()V", at = @At("RETURN"))
    public void dsurround_addSoundConfigButton(CallbackInfo ci) {
        // This will add a button in the lower left corner of the sound options menu
        var toolTip = Tooltip.create(Component.translatable("dsurround.text.config.soundconfiguration.tooltip"));
        var style = Style.EMPTY.withColor(ColorPalette.GOLD);
        var buttonText = Component.translatable("dsurround.text.config.soundconfiguration").withStyle(style);
        var textWidth = GameUtils.getTextRenderer().width(buttonText) + 10;

        var buttonToAdd = Button.builder(buttonText, this::dsurround_onPress)
                .tooltip(toolTip)
                .bounds(5, this.height - 27, textWidth, 20).build();

        this.layout.addToFooter(buttonToAdd, settings -> settings.alignHorizontally(0.01F));

        // Add Footsteps / Biomes volume sliders to the main options list (appended
        // after the vanilla sliders, same position as the 26.1 build).
        var soundConfig = org.orecruncher.dsurround.lib.config.ConfigurationData.getConfig(Configuration.class).soundOptions;
        this.list.addSmall(java.util.List.of(
                new DsVolumeSlider("dsurround.options.footstepVolume", soundConfig.footstepVolume, v -> soundConfig.footstepVolume = v),
                new DsVolumeSlider("dsurround.options.playerEffectVolume", soundConfig.playerEffectVolume, v -> soundConfig.playerEffectVolume = v),
                new DsVolumeSlider("dsurround.options.biomeVolume", soundConfig.biomeVolume, v -> soundConfig.biomeVolume = v)));
    }

    /** A 0..2 (0%..200%) volume slider backed by a double configuration field. */
    @Unique
    private static final class DsVolumeSlider extends AbstractSliderButton {

        private final String key;
        private final java.util.function.Consumer<Double> setter;

        DsVolumeSlider(String key, double current, java.util.function.Consumer<Double> setter) {
            // 1.21.1 has no AbstractSliderButton.DEFAULT_HEIGHT; vanilla sliders are 20px tall.
            super(0, 0, 150, 20, Component.translatable(key), current / 2.0);
            this.key = key;
            this.setter = setter;
            this.updateMessage();
        }

        @Override
        protected void applyValue() {
            this.setter.accept(this.value * 2.0);
        }

        @Override
        protected void updateMessage() {
            var suffix = this.value <= 0.001
                    ? Component.literal(": ").append(Component.translatable("options.off"))
                    : Component.literal(": " + (int) (this.value * 200.0) + "%");
            this.setMessage(Component.translatable(this.key).copy().append(suffix));
        }
    }

    @Unique
    private void dsurround_onPress(Button button) {
        var enablePlayButtons = GameUtils.getMC().level == null || GameUtils.isSinglePlayer();

        // If play buttons are enabled, we need to prevent the MusicManager from
        // ticking.
        var musicManager = (IMusicManager)GameUtils.getMC().getMusicManager();
        if (enablePlayButtons) {
            musicManager.dsurround_setPaused(true);
        }

        var screen = new IndividualSoundControlScreen(
                this,
                enablePlayButtons,
                ignore -> {
                    // Stop any sounds left hanging for whatever reason, and restart the MusicManager
                    GameUtils.getSoundManager().stop();
                    if (enablePlayButtons)
                        musicManager.dsurround_setPaused(false);
                });

        this.minecraft.setScreen(screen);
    }
}