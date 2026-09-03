package org.orecruncher.dsurround.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

/**
 * Draws a red pulsing frame over the vanilla hotbar selection when the held
 * item's durability is at or below the configured threshold.  Only the
 * selection frame region of widgets.png (uv 0,22 / 24x23) is redrawn, so the
 * item texture is never touched.  No sound - the audio side of durability
 * warnings is covered by other mods.
 */
public final class LowDurabilityHighlightOverlay {

    private static final ResourceLocation WIDGETS = ResourceLocation.withDefaultNamespace("textures/gui/widgets.png");

    private static final Configuration.CompassAndClockOptions CONFIG = ContainerManager.resolve(Configuration.CompassAndClockOptions.class);

    private LowDurabilityHighlightOverlay() {

    }

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (!CONFIG.enableLowDurabilityHighlight)
            return;

        final var player = Minecraft.getInstance().player;
        if (player == null)
            return;

        final ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || !stack.isDamageableItem())
            return;

        // Remaining durability fraction (not the used fraction) decides the
        // highlight: it shows only when durability has actually worn DOWN to
        // the threshold.
        final float remaining = (float) (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
        if (remaining * 100.0F > (float) CONFIG.lowDurabilityThreshold)
            return;

        // Vanilla hotbar geometry: the selection frame is 24x23 drawn 1px up/left
        // of the selected 20x20 slot.
        final int x = screenWidth / 2 - 91 - 1 + player.getInventory().selected * 20;
        final int y = screenHeight - 23;

        // Breathing white <-> soft red via the RGB shader color channels.
        final float t = (float) (Util.getMillis() % 2000L) / 2000.0F;
        final float pulse = 0.5F + 0.5F * Mth.sin(t * (float) (Math.PI * 2.0));

        RenderSystem.setShaderColor(1.0F, 1.0F - 0.5F * pulse, 1.0F - 0.5F * pulse, 1.0F);
        graphics.blit(WIDGETS, x, y, 0, 22, 24, 23);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}