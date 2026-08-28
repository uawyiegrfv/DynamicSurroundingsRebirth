package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Firefly glow using the 26.1 flicker implementation (glow fades in/out over the lifetime,
 * gentle random drifting). The firefly texture was back-ported from 26.1
 * (assets/dsurround/textures/particle/firefly.png), since 1.20.1 has no vanilla firefly.
 */
public class FireflyParticle extends TextureSheetParticle {
    private static final IRandomizer RANDOM = Randomizer.current();
    private static final float PARTICLE_FADE_OUT_LIGHT_TIME = 0.3F;
    private static final float PARTICLE_FADE_IN_LIGHT_TIME = 0.1F;
    private static final float PARTICLE_FADE_OUT_ALPHA_TIME = 0.5F;
    private static final float PARTICLE_FADE_IN_ALPHA_TIME = 0.3F;

    public FireflyParticle(ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, 0.0, 0.0, 0.0);
        this.setSprite(ParticleUtils.getSprite(new ResourceLocation("dsurround", "firefly")));
        this.speedUpWhenYMotionIsBlocked = true;
        this.friction = 0.96F;
        this.quadSize *= 1.35F;


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
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getLightColor(float partialTick) {
        // Firefly flicker: the glow pulses in and out over the lifetime. 26.1 returns a raw
        // 0..255 brightness; 1.20.1 packs light as sky<<20 | block<<4, so map the fade amount
        // onto a full block-light level so the particle glows even at night.
        float fade = getFadeAmount(this.getLifetimeProgress(this.age + partialTick), PARTICLE_FADE_IN_LIGHT_TIME, PARTICLE_FADE_OUT_LIGHT_TIME);
        int level = (int) (15.0F * fade);
        return LightTexture.pack(level, level);
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
