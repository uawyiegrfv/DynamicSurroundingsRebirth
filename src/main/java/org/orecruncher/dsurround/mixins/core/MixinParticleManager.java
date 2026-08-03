package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ParticleEngine.class)
public interface MixinParticleManager {

    // 26.1: ParticleEngine.spriteSets (Map<Identifier, SpriteSet>) was moved to
    // ParticleResources; expose the resource manager so ParticleUtils can reach it.

    @Accessor("resourceManager")
    ParticleResources dsurround_getResourceManager();

    @Invoker("createParticle")
    <T extends ParticleOptions> Particle dsurround_createParticle(T parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ);
}
