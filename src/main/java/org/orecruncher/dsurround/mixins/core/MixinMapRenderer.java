package org.orecruncher.dsurround.mixins.core;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.MapRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlays the horizontal distance on a held map as it is rendered in the
 * first-person view: explorer / treasure maps show distance to the target marker.
 * 1.20.1: MapRenderer.render(PoseStack, MultiBufferSource, int, MapItemSavedData, boolean, int)
 * with the text drawn via Font.drawInBatch directly into the map's buffer.
 */
@Mixin(MapRenderer.class)
public abstract class MixinMapRenderer {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/level/saveddata/maps/MapItemSavedData;ZI)V", at = @At("TAIL"))
    private void dsurround_renderDistance(PoseStack pose, MultiBufferSource buffer, int mapId, MapItemSavedData data, boolean isFoil, int packedLight, CallbackInfo ci) {
        if (!ConfigurationData.getConfig(Configuration.class).mapOptions.enableTreasureDistance)
            return;
        var player = Minecraft.getInstance().player;
        if (player == null)
            return;

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

        // 1.20.1: drawInBatch's built-in background (Integer.MIN_VALUE) renders a fixed-width
        // dim slab and dulls the glyphs. Draw a width-fitting background quad first, then the
        // bright text on top (no background parameter, full-brightness light).
        float bgX0 = -2.0F;
        float bgY0 = -2.0F;
        float bgX1 = textWidth + 2.0F;
        float bgY1 = font.lineHeight + 2.0F;
        var mat = pose.last().pose();
        var bgVc = buffer.getBuffer(net.minecraft.client.renderer.RenderType.textBackground());
        vertex(bgVc, mat, bgX0, bgY0, 0.0F, 0.0F, 0x80000000);
        vertex(bgVc, mat, bgX0, bgY1, 0.0F, 1.0F, 0x80000000);
        vertex(bgVc, mat, bgX1, bgY1, 1.0F, 1.0F, 0x80000000);
        vertex(bgVc, mat, bgX1, bgY0, 1.0F, 0.0F, 0x80000000);

        font.drawInBatch(text.getVisualOrderText(), 0.0F, 0.0F, -1, true, pose.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        pose.popPose();
    }

    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, float x, float y, float u, float v, int color) {
        vc.vertex(m, x, y, 0.0F).color(color).uv(u, v).uv2(0xF000F0).endVertex();
    }

    /**
     * Horizontal distance to the treasure target, from the world coordinates stored
     * in the map's decorations. Returns null when the held map has no treasure
     * target (e.g. a plain filled map) - nothing is drawn then.
     */
    private static Integer computeDistance(Player player, ItemStack stack) {
        var data = MapItem.getSavedData(stack, player.level());
        if (data == null || data.getDecorations() == null)
            return null;

        // Prefer the server-pushed centre (MapCenterCache); fall back to the integrated
        // server copy in single-player.
        int centerX = data.centerX;
        int centerZ = data.centerZ;
        var mapId = MapItem.getMapId(stack);
        if (mapId != null) {
            int[] cached = org.orecruncher.dsurround.network.MapCenterCache.get(mapId);
            if (cached != null) {
                centerX = cached[0];
                centerZ = cached[1];
            } else {
                var mc = Minecraft.getInstance();
                if (mc.getSingleplayerServer() != null) {
                    var serverData = mc.getSingleplayerServer().overworld().getMapData("map_" + mapId);
                    if (serverData != null) {
                        centerX = serverData.centerX;
                        centerZ = serverData.centerZ;
                    }
                }
            }
        }

        // decoration x/y = (world - center)/scale * 2 (addDecoration bytecode-verified).
        for (var entry : data.getDecorations()) {
            if (entry.renderOnFrame()) {
                int blockScale = 1 << data.scale;
                double worldX = centerX + (entry.getX() / 2.0) * blockScale;
                double worldZ = centerZ + (entry.getY() / 2.0) * blockScale;
                return (int) Math.round(Math.hypot(worldX - player.getX(), worldZ - player.getZ()));
            }
        }
        return null;
    }
}
