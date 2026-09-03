package org.orecruncher.dsurround.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

/**
 * Draws a red pulsing frame over the vanilla hotbar selection when the held
 * item's durability is at or below the configured threshold.  Only the
 * selection frame region of widgets.png is redrawn, so the item texture is
 * never touched.  No sound - the audio side of durability warnings is covered
 * by other mods.
 *
 * Note: 1.21.1 bakes the color per vertex when a GUI draw is queued, so the
 * global RenderSystem shader color never reaches batched GUI draws.  The tint
 * is therefore passed through the color-accepting blit overload instead.
 */
public final class LowDurabilityHighlightOverlay {

    private static final ResourceLocation HOTBAR_SELECTION_SPRITE = ResourceLocation.withDefaultNamespace("hud/hotbar_selection");

    private static final Configuration.EntityEffects CONFIG = ContainerManager.resolve(Configuration.EntityEffects.class);

    private LowDurabilityHighlightOverlay() {

    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!CONFIG.enableLowDurabilityHighlight)
            return;

        final var player = Minecraft.getInstance().player;
        if (player == null)
            return;

        final ItemStack stack = player.getInventory().getSelected();
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
        final int w = guiGraphics.guiWidth();
        final int h = guiGraphics.guiHeight();
        final int x = w / 2 - 91 - 1 + player.getInventory().selected * 20;
        final int y = h - 23;

        // Breathing white <-> soft red via the RGB channels.  In 1.21.1 the
        // sprite shader multiplies the texture by the shader color RGB but the
        // alpha channel of the shader color does not participate in the blend
        // (the sprite is drawn opaque), so an alpha pulse is a no-op - the
        // pulse has to ride on the color channels instead.
        final float t = (float) (Util.getMillis() % 2000L) / 2000.0F;
        final float pulse = 0.5F + 0.5F * Mth.sin(t * (float) (Math.PI * 2.0));

        RenderSystem.setShaderColor(1.0F, 1.0F - 0.5F * pulse, 1.0F - 0.5F * pulse, 1.0F);
        guiGraphics.blitSprite(HOTBAR_SELECTION_SPRITE, x, y, 24, 23);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}