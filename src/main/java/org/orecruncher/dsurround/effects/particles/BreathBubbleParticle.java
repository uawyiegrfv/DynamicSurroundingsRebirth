package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.resources.ResourceLocation;

/**
 * A small translucent breath bubble spawned at the entity's mouth while underwater.
 * 1.20.1 port: extends TextureSheetParticle (SingleQuadParticle is a low-level quad
 * class here) and sources its sprite from the engine's SpriteSet for the vanilla
 * bubble particle.
 */
public class BreathBubbleParticle extends TextureSheetParticle {

    public BreathBubbleParticle(ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, 0.0D, 0.0D, 0.0D);
        this.setSprite(ParticleUtils.getSprite(new ResourceLocation("minecraft", "bubble")));

        this.quadSize = 0.11F;
        this.setAlpha(0.35F);
        this.xd = 0.0D;
        this.yd = 0.05D;
        this.zd = 0.0D;
        this.lifetime = 40;
        this.hasPhysics = false;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            this.move(this.xd, this.yd, this.zd);
            this.yd *= 0.98F;
        }
    }
}
