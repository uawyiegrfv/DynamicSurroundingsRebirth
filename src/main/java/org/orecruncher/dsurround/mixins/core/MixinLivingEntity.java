package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.effects.entity.EntityEffectInfo;
import org.orecruncher.dsurround.mixinutils.ILivingEntityExtended;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity implements ILivingEntityExtended {

    @Unique
    private EntityEffectInfo dsurround_effectInfo;

    @Shadow
    protected boolean jumping;

    @Override
    public EntityEffectInfo dsurround_getEffectInfo() {
        return this.dsurround_effectInfo;
    }

    @Override
    public void dsurround_setEffectInfo(@Nullable EntityEffectInfo info) {
        this.dsurround_effectInfo = info;
    }

    @Override
    public boolean dsurround_isJumping() {
        return this.jumping;
    }
}
