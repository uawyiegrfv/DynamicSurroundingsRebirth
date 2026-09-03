package org.orecruncher.dsurround.gui.overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

/**
 * Draws a red pulsing frame over the vanilla hotbar selection when the held
 * item's durability is at or below the configured threshold.  Only the
 * selection frame sprite (hud/hotbar_selection) is redrawn with a pulsing
 * tint, so the item texture is never touched.  No sound - the audio side of
 * durability warnings is covered by other mods.
 */
public final class LowDurabilityHighlightOverlay {

    private static final Identifier HOTBAR_SELECTION_SPRITE = Identifier.withDefaultNamespace("hud/hotbar_selection");

    private static final Configuration.CompassAndClockOptions CONFIG = ContainerManager.resolve(Configuration.CompassAndClockOptions.class);

    private LowDurabilityHighlightOverlay() {

    }

    public static void render(final GuiGraphicsExtractor graphics, final DeltaTracker tracker) {
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
        final int w = graphics.guiWidth();
        final int h = graphics.guiHeight();
        final int x = w / 2 - 91 - 1 + player.getInventory().getSelectedSlot() * 20;
        final int y = h - 23;

        // Breathing white <-> soft red via the tint color channel (per draw).
        final float t = (float) (Util.getMillis() % 2000L) / 2000.0F;
        final float pulse = 0.5F + 0.5F * Mth.sin(t * (float) (Math.PI * 2.0));
        final int color = ((int) (pulse * 255.0F) << 24) | 0x00FF8080;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR_SELECTION_SPRITE, x, y, 24, 23, color);
    }
}