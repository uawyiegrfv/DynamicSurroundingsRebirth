package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

/**
 * 1.20.1: ParticleEngine keeps its sprite table ({@code spriteSets}) on itself; the
 * {@code ParticleResources} indirection introduced in 1.21.x does not exist here. Expose
 * the sprite table and the particle factory so the mod can spawn custom particles.
 */
@Mixin(ParticleEngine.class)
public interface MixinParticleManager {

    @Accessor("spriteSets")
    Map<ResourceLocation, SpriteSet> dsurround_getSpriteSets();

    /**
     * Creates a particle WITHOUT queueing it, so the caller can adjust its state before adding it.
     *
     * <p>This invokes {@code makeParticle}, not {@code createParticle}. The public
     * {@code ParticleEngine.createParticle} is {@code makeParticle(...)} followed by {@code add(...)} -
     * it queues the particle itself - so invoking it here meant that every caller of
     * {@code AbstractBlockEffect.createParticle} queued the same instance a SECOND time when it then
     * called {@code addParticle}.
     *
     * <p>The consequences were not cosmetic: the duplicate entry in the add queue made the instance
     * tick TWICE per tick and render TWICE per frame, so its age advanced at double rate - halving its
     * lifetime - and its translucent alpha was composited twice. Waterfall and steam had compensated
     * with a {@code *2} on the lifetime; the flame jet and the bubble column had not, so their
     * particles simply died at half the intended age.
     */
    @Invoker("makeParticle")
    <T extends ParticleOptions> Particle dsurround_createParticle(T parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ);
}
