package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.eventing.handlers.BlockUpdateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Mixing into ClientLevel means this hook can only ever run client side, so no
// isClientSide() guard is needed (contrast the 1.21.1 sibling, which mixes into
// Level.onBlockStateChange and does need one).
//
// NOTE: an earlier revision of this comment claimed "26.1: Level.onBlockStateChange
// was removed". That is a 26.1-specific fact and is NOT true on 1.20.1 - the comment
// was copied from the 26.1 sibling. Do not restate it here.
//
// NOT VERIFIED: whether ClientLevel.setBlocksDirty is in fact driven on every block
// change on this version (no mapped MC 1.20.1 sources were available when this was
// written). Do not retarget this injection without checking the MC sources first.
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
