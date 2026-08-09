package org.orecruncher.dsurround.mixins.core;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlays the horizontal distance on a held map as it is rendered in the
 * first-person view:
 * - plain (filled) maps: distance from the player to the map centre
 * - explorer / treasure maps: distance to the treasure target marker
 *
 * Rendered inside the map's own PoseStack via OrderedSubmitNodeCollector, so the
 * text stays glued to the map surface as the camera and arm move.
 *
 * Rendering approach based on TreasureDistance by ldtteam (CC BY):
 *   mixin into MapRenderer.render + submitText in the map pose stack
 *   https://github.com/ldtteam/TreasureDistance
 */
@Mixin(MapRenderer.class)
public abstract class MixinMapRenderer {

    @Inject(method = "render", at = @At("TAIL"))
    private void dsurround_renderDistance(MapRenderState state, PoseStack pose,
                                          SubmitNodeCollector collector, boolean isFoil,
                                          int packedLight, CallbackInfo ci) {
        if (!ConfigurationData.getConfig(Configuration.class).mapOptions.enableTreasureDistance)
            return;
        var player = Minecraft.getInstance().player;
        if (player == null)
            return;

        // Prefer the main hand, fall back to the offhand.
        var stack = player.getMainHandItem();
        if (!stack.is(Items.FILLED_MAP))
            stack = player.getOffhandItem();
        if (!stack.is(Items.FILLED_MAP))
            return;

        var distance = computeDistance(player, stack);
        if (distance == null)
            return;

        var font = Minecraft.getInstance().font;
        var text = Component.translatable("dsurround.hud.map_distance", distance);
        float textWidth = font.width(text);
        float scale = Mth.clamp(25.0F / textWidth, 0.0F, 6.0F / 9.0F);

        pose.pushPose();
        // Top-right corner of the map, tight against the edges.
        pose.translate(128.0F - textWidth * scale - 2.0F, 2.0F, -0.025F);
        pose.scale(scale, scale, -1.2F);
        pose.translate(0.0F, 0.0F, 0.1F);

        collector.order(1)
                .submitText(pose, 0.0F, 0.0F, text.getVisualOrderText(), false,
                        Font.DisplayMode.NORMAL, packedLight, -1, Integer.MIN_VALUE, 0);
        pose.popPose();
    }

    /**
     * Horizontal distance to the treasure target, from the world coordinates stored
     * in the map's decorations. Returns null when the held map has no treasure
     * target (e.g. a plain filled map) - nothing is drawn then.
     */
    private static Integer computeDistance(Player player, ItemStack stack) {
        var decorations = stack.get(DataComponents.MAP_DECORATIONS);
        if (decorations == null)
            return null;
        for (var entry : decorations.decorations().values()) {
            if (entry.type().value().showOnItemFrame()) {
                return (int) Math.round(Math.hypot(entry.x() - player.getX(), entry.z() - player.getZ()));
            }
        }
        return null;
    }
}
