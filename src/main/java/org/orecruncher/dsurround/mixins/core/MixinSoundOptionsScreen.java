package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;

import net.minecraft.client.gui.screens.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.gui.sound.IndividualSoundControlScreen;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.gui.ColorPalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * 1.20.1: adds the DS sound configuration button (lower-left) and the
 * Footsteps / Biomes volume sliders to the vanilla sound options screen.
 * The 1.20.1 screen has no Layout system (1.20.2+), so widgets are placed
 * with absolute coordinates.
 */
@Mixin(SoundOptionsScreen.class)
public abstract class MixinSoundOptionsScreen {

    @Shadow
    private OptionsList list;

    @Inject(method = "init()V", at = @At("RETURN"))
    public void dsurround_addSoundConfigButton(CallbackInfo ci) {
        var self = (net.minecraft.client.gui.screens.Screen) (Object) this;

        // Button in the lower-left corner of the sound options screen.
        var toolTip = Tooltip.create(Component.translatable("dsurround.text.config.soundconfiguration.tooltip"));
        var style = Style.EMPTY.withColor(TextColor.fromRgb(ColorPalette.GOLD.getValue()));
        var buttonText = Component.translatable("dsurround.text.config.soundconfiguration").withStyle(style);
        var textWidth = GameUtils.getTextRenderer().width(buttonText) + 10;

        // 26.1: play buttons are enabled outside a world (main menu) too.
        var buttonToAdd = Button.builder(buttonText, b -> {
                    var enablePlayButtons = Minecraft.getInstance().level == null || GameUtils.isSinglePlayer();
                    Minecraft.getInstance().setScreen(new IndividualSoundControlScreen(self, enablePlayButtons));
                })
                .tooltip(toolTip)
                .bounds(5, self.height - 27, textWidth, 20)
                .build();
        dsurround_addWidget(self, buttonToAdd);

        // Footsteps / Biomes volume sliders appended to the vanilla options list
        // (1.20.1 pattern: OptionInstance + UnitDouble 0..1, stored value = 0..1.5 for
        // footsteps, 0..2 for biomes). The footstep slider caps at 150%: the engine
        // clamps a single voice's gain at 1.0, so beyond ~150% every footstep voice
        // pins at the ceiling and the landing>step loudness hierarchy collapses.
        var soundConfig = ConfigurationData.getConfig(Configuration.class).soundOptions;
        var footSlider = new OptionInstance<>("dsurround.options.footstepVolume",
                OptionInstance.noTooltip(),
                (caption, value) -> caption.copy().append(": " + Math.round(value * 150.0D) + "%"),
                OptionInstance.UnitDouble.INSTANCE,
                Math.min(1.0D, soundConfig.footstepVolume / 1.5D),
                v -> soundConfig.footstepVolume = v * 1.5D);
        var biomeSlider = new OptionInstance<>("dsurround.options.biomeVolume",
                OptionInstance.noTooltip(),
                (caption, value) -> caption.copy().append(": " + Math.round(value * 200.0D) + "%"),
                OptionInstance.UnitDouble.INSTANCE,
                soundConfig.biomeVolume / 2.0D,
                v -> soundConfig.biomeVolume = v * 2.0D);
        this.list.addSmall(footSlider, biomeSlider);
    }

    /**
     * 1.20.1: Screen.addRenderableWidget is protected and its generic (multi-bound) signature
     * cannot be @Shadow/@Invoker resolved. Invoke reflectively, matching BOTH the mojmap name
     * (dev) and the SRG name (production obfuscated).
     */
    @Unique
    private static void dsurround_addWidget(net.minecraft.client.gui.screens.Screen screen, net.minecraft.client.gui.components.AbstractWidget widget) {
        try {
            for (var m : net.minecraft.client.gui.screens.Screen.class.getDeclaredMethods()) {
                var n = m.getName();
                if (n.equals("addRenderableWidget") || n.equals("m_142416_")) {
                    m.setAccessible(true);
                    m.invoke(screen, widget);
                    return;
                }
            }
        } catch (Throwable ex) {
            org.orecruncher.dsurround.lib.Library.LOGGER.error(ex, "Failed to add widget to sound options screen");
        }
    }
}
