package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.orecruncher.dsurround.processing.CritWordHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.1: LivingDamageEvent is fired server-side only, so a client mod never sees
 * it (except the local player). The client learns about entity damage via this packet,
 * so hook it and forward to CritWordHandler.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void dsurround_onDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        int entityId = packet.entityId();
        Entity entity = mc.level.getEntity(entityId);
        if (entity instanceof LivingEntity living && living.isAlive()) {
            var srcPos = packet.sourcePosition().orElse(null);
            CritWordHandler.onClientDamage(living, srcPos);
        }
    }
}
