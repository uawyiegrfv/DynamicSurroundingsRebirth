package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.entity.projectile.AbstractArrow;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 1.20.1: AbstractArrow lives in net.minecraft.world.entity.projectile (not .arrow),
// and MixinExtras is not on the Forge 1.20.1 compile classpath, so the 26.1
// @WrapOperation is replaced with an equivalent base @Redirect.
@Mixin(AbstractArrow.class)
public abstract class MixinEntityArrow {

    @Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;isCritArrow()Z"))
    private boolean dsurround_isCriticalCheck(AbstractArrow instance) {
        if (MixinHelpers.particleTweaksConfig.suppressProjectileParticleTrails)
            return false;
        return instance.isCritArrow();
    }
}
