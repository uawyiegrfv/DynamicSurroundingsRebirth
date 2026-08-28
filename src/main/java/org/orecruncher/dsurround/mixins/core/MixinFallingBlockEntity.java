package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects a FallingBlockEntity landing on the client. When a falling block settles, the
 * server turns it back into a block (setBlock) and discards the entity; the removal is
 * synced to the client, which calls {@code onClientRemoval()} on the entity. That call is
 * the reliable, client-only landing moment - the entity's position is the landing spot.
 *
 * Injected on {@link Entity#onClientRemoval()} (FallingBlockEntity does not override it),
 * filtered to falling blocks only.
 */
@Mixin(Entity.class)
public abstract class MixinFallingBlockEntity {

    @Inject(method = "onClientRemoval()V", at = @At("HEAD"))
    private void dsurround_onLand(CallbackInfo ci) {
        var self = (Entity) (Object) this;
        if (!(self instanceof FallingBlockEntity falling))
            return;

        var level = self.level();
        if (!(level instanceof Level) || !level.isClientSide())
            return;

        ClientEventHooks.FALLING_BLOCK_LAND_EVENT.raise().onLand(falling, level, self.blockPosition());
    }
}
