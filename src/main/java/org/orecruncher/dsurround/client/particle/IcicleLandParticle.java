package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;
import net.minecraft.resources.ResourceLocation;

/**
 * The shard that scatters when an icicle shatters on the ground. A plain
 * short-lived puff: it billboards like any vanilla particle (a shard tumbling
 * on the ground has no facing worth preserving, unlike the hanging icicle),
 * pops outward on the velocity the shatter hands it, drops, settles, and is
 * gone inside half a second. The texture is the snowball-shaped scatter of
 * blue and pale-blue specks - icicle_land.png.
 */
public class IcicleLandParticle extends TextureSheetParticle {

    IcicleLandParticle(final ClientLevel level, final double x, final double y, final double z,
                       final double vx, final double vy, final double vz, final TextureAtlasSprite sprite) {
        super(level, x, y, z, vx, vy, vz);
        this.setSprite(sprite);
        this.lifetime = 7 + this.random.nextInt(7);
        this.quadSize = 0.07F + this.random.nextFloat() * 0.05F;
    }

    /**
     * Written out by hand with fixed numbers rather than leaning on the base
     * tick's gravity/friction conventions - this file exists in three versions
     * and the shard has to fall the same in all of them. Gentle drop: a shard
     * is light and flutters more than it plummets.
     */
    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        this.yd = this.yd * 0.98D - 0.01D;
        this.move(this.xd, this.yd, this.zd);
        if (this.onGround) {
            // Settle where it landed instead of skating on.
            this.xd *= 0.6D;
            this.zd *= 0.6D;
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    public static class Provider implements net.minecraft.client.particle.ParticleProvider<SimpleParticleType> {

        private final ResourceLocation spriteId;

        public Provider(final ResourceLocation spriteId) {
            this.spriteId = spriteId;
        }

        @Override
        public net.minecraft.client.particle.Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                                                     final double x, final double y, final double z,
                                                                     final double vx, final double vy, final double vz) {
            return new IcicleLandParticle(level, x, y, z, vx, vy, vz, ParticleUtils.getSprite(this.spriteId));
        }
    }
}
