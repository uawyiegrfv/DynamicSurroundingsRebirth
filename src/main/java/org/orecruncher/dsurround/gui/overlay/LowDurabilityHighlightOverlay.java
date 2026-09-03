package org.orecruncher.dsurround.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

/**
 * Draws a red pulsing selection frame over the vanilla hotbar selection when
 * the held item's durability is at or below the configured threshold.  Only
 * the frame sprite is redrawn (tinted red via the shader color), so the item
 * texture itself is never touched.  No sound - the audio side of durability
 * warnings is covered by other mods.
 */
public final class LowDurabilityHighlightOverlay {

    private static final ResourceLocation WIDGETS = ResourceLocation.withDefaultNamespace("textures/gui/widgets.png");

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

        final float frac = (float) stack.getDamageValue() / (float) stack.getMaxDamage();
        if (frac * 100.0F < (float) CONFIG.lowDurabilityThreshold)
            return;

        // Vanilla hotbar geometry: the selection frame is 24x24 drawn 1px up/left
        // of the selected 20x20 slot.
        final int w = guiGraphics.guiWidth();
        final int h = guiGraphics.guiHeight();
        final int x = w / 2 - 91 - 1 + player.getInventory().selected * 20;
        final int y = h - 23;

        // Breathing pulse so it reads as a warning without hiding anything.
        final float t = (float) (Util.getMillis() % 1600L) / 1600.0F;
        final float pulse = 0.5F + 0.5F * Mth.sin(t * (float) (Math.PI * 2.0));
        final float alpha = 0.35F + 0.45F * pulse;

        RenderSystem.setShaderColor(1.0F, 0.2F, 0.2F, alpha);
        guiGraphics.blit(WIDGETS, x, y, 0, 0, 24, 24, 256, 256);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}