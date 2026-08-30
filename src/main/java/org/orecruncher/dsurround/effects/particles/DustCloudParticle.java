package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.resources.ResourceLocation;

/**
 * Soft, coloured dust motes kicked up when sand or gravel lands. Uses the vanilla
 * campfire-smoke sprite with an explicit outward velocity and a per-block dust colour.
 */
public class DustCloudParticle extends TextureSheetParticle {

    
    public DustCloudParticle(ClientLevel level, double x, double y, double z,
                             double xd, double yd, double zd, float r, float g, float b, float scale) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.setSprite(ParticleUtils.getSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "big_smoke_0")));
        this.gravity = 0.0F;
        this.friction = 0.94F;
        // Fixed, generous size - no birth-shrink, no grow-over-time.
        this.quadSize = 0.9F * scale * (0.8F + this.random.nextFloat() * 0.4F);
        this.lifetime = 30 + this.random.nextInt(20);
        this.setColor(r, g, b);
        this.setAlpha(0.65F);

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
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
            return;
        }

        // Drift outward (no gravity collapse); fade out over the last 40% of life.
        this.move(this.xd, this.yd, this.zd);
        this.xd *= this.friction;
        this.zd *= this.friction;
        float life = this.age / (float) this.lifetime;
        this.setAlpha(life > 0.6F ? 0.65F * (1.0F - (life - 0.6F) / 0.4F) : 0.65F);
    }
}
