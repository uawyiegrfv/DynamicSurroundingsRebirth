package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.eventing.handlers.BlockUpdateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.1: Level.onBlockStateChange was removed. Level.setBlock now calls
// this.setBlocksDirty(pos, oldState, newState) on actual block changes, and
// ClientLevel overrides setBlocksDirty, so that is the client-side hook.
@Mixin(ClientLevel.class)
public class MixinWorld {

    /**
     * Tap into block state change detection in the World instance.  Need to be careful to only get updates to
     * a world that is client side.  Server side is a don't care.
     */
    @Inject(method = "setBlocksDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    public void dsurround_onBlockChanged(BlockPos pos, BlockState oldBlock, BlockState newBlock, CallbackInfo ci) {
        BlockUpdateHandler.blockPositionUpdate(pos, oldBlock, newBlock);
    }
}
