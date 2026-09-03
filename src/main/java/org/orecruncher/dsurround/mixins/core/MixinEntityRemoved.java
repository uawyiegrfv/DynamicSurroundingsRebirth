package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.entity.Entity;
import org.orecruncher.dsurround.processing.ProjectileBreakSoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps entity removal for thrown projectile break sounds.  The authoritative
 * server impact almost always despawns the projectile before the client's own
 * hit detection runs, so the client-side ProjectileImpactEvent rarely fires -
 * but the removal packet always arrives.  The handler filters down to ender
 * pearls, eggs and snowballs removed with DISCARDED on the client side.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRemoved {

    @Inject(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("TAIL"))
    private void dsurround_onRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        ProjectileBreakSoundHandler.onEntityRemoved((Entity) (Object) this, reason);
    }
}