package org.orecruncher.dsurround.mixins.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.processing.EnchantTableSoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps the vanilla client side enchanting table book animation ticker so the
 * book opening and page turn gestures can trigger sounds.  All the animation
 * state (open/flipT/...) lives in public fields on the block entity.
 */
@Mixin(EnchantmentTableBlockEntity.class)
public abstract class MixinEnchantingTableBook {

    @Inject(method = "bookAnimationTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/EnchantmentTableBlockEntity;)V", at = @At("TAIL"))
    private static void dsurround_onBookAnimation(Level level, BlockPos pos, BlockState state, EnchantmentTableBlockEntity blockEntity, CallbackInfo ci) {
        EnchantTableSoundHandler.bookAnimationTick(level, pos, blockEntity);
    }
}