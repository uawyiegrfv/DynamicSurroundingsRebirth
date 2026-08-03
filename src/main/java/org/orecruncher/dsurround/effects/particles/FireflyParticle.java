package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Firefly using the vanilla firefly texture and flicker implementation (glow fades in/out
 * over the lifetime, gentle random drifting). DS still controls the spawn conditions via
 * AreaBlockEffects (near flowers at night, 3.5% chance); the vanilla "remove when inside a
 * block" check is dropped because DS spawns them at the flower's own position.
 */
public class FireflyParticle extends SingleQuadParticle {
    private static final IRandomizer RANDOM = Randomizer.current();
    private static final float PARTICLE_FADE_OUT_LIGHT_TIME = 0.3F;
    private static final float PARTICLE_FADE_IN_LIGHT_TIME = 0.1F;
    private static final float PARTICLE_FADE_OUT_ALPHA_TIME = 0.5F;
    private static final float PARTICLE_FADE_IN_ALPHA_TIME = 0.3F;
    private static final SpriteSet spriteProvider = ParticleUtils.getSpriteProvider(ParticleTypes.FIREFLY);

    public FireflyParticle(ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, 0.0, 0.0, 0.0, spriteProvider.first());
        this.speedUpWhenYMotionIsBlocked = true;
        this.friction = 0.96F;
        this.quadSize *= 0.75F;

        // Flicker in from nothing like the vanilla firefly
        this.lifetime = 120 + this.random.nextInt(80);
        this.setAlpha(0.0F);

        // Gentle initial drift around the flower
        this.xd = RANDOM.nextGaussian() * 0.03F;
        this.yd = RANDOM.nextGaussian() * 0.015F;
        this.zd = RANDOM.nextGaussian() * 0.03F;
        this.gravity = 0F;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    @Override
    public int getLightCoords(float a) {
        // Vanilla firefly flicker: the glow pulses in and out over the lifetime
        return (int) (255.0F * getFadeAmount(this.getLifetimeProgress(this.age + a), PARTICLE_FADE_IN_LIGHT_TIME, PARTICLE_FADE_OUT_LIGHT_TIME));
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            this.setAlpha(getFadeAmount(this.getLifetimeProgress(this.age), PARTICLE_FADE_IN_ALPHA_TIME, PARTICLE_FADE_OUT_ALPHA_TIME));
            // Random re-roll of drift direction, matching the vanilla firefly behaviour
            if (this.random.nextFloat() > 0.95F || this.age == 1) {
                this.setParticleSpeed(
                        -0.05F + 0.1F * this.random.nextFloat(),
                        -0.05F + 0.1F * this.random.nextFloat(),
                        -0.05F + 0.1F * this.random.nextFloat()
                );
            }
            this.move(this.xd, this.yd, this.zd);
        }
    }

    private float getLifetimeProgress(float currentAge) {
        return Mth.clamp(currentAge / this.lifetime, 0.0F, 1.0F);
    }

    private static float getFadeAmount(float lifetimeProgress, float fadeInTime, float fadeOutTime) {
        if (lifetimeProgress >= 1.0F - fadeInTime) {
            return (1.0F - lifetimeProgress) / fadeInTime;
        } else {
            return lifetimeProgress <= fadeOutTime ? lifetimeProgress / fadeOutTime : 1.0F;
        }
    }
}
