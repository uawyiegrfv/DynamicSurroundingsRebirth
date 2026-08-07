package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleTypes;

/**
 * Soft, coloured dust motes kicked up when sand or gravel lands. Uses the vanilla
 * campfire-smoke sprite (translucent, smoothly graded - reads as haze rather than hard
 * grains) with an explicit outward velocity (billowing sideways, barely rising) and a
 * per-block dust colour (sand yellow / gravel grey).
 *
 * Unlike {@link DustParticle} (which uses the small ASH sprite and shrinks), this particle
 * keeps a constant, generous size for its whole life and fades out only at the end, so the
 * kicked-up cloud stays clearly visible instead of vanishing as tiny grains.
 */
public class DustCloudParticle extends SingleQuadParticle {

    private static final SpriteSet spriteProvider = ParticleUtils.getSpriteProvider(ParticleTypes.CAMPFIRE_COSY_SMOKE);

    public DustCloudParticle(ClientLevel level, double x, double y, double z,
                             double xd, double yd, double zd, float r, float g, float b, float scale) {
        super(level, x, y, z, 0.0, 0.0, 0.0, spriteProvider.first());
        this.gravity = 0.0F;
        this.friction = 0.94F;
        // Fixed, generous size - no birth-shrink, no grow-over-time.
        this.quadSize = 0.5F * scale * (0.8F + this.random.nextFloat() * 0.4F);
        this.lifetime = 30 + this.random.nextInt(20);
        this.setColor(r, g, b);
        this.setAlpha(0.65F);

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
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
