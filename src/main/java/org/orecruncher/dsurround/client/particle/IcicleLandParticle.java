package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * The shard that scatters when an icicle shatters on the ground. A plain
 * short-lived puff: it billboards like any vanilla particle (a shard tumbling
 * on the ground has no facing worth preserving, unlike the hanging icicle),
 * pops outward on the velocity the shatter hands it, drops, settles, and is
 * gone inside half a second. The texture is the snowball-shaped scatter of
 * blue and pale-blue specks - icicle_land.png.
 */
public class IcicleLandParticle extends SingleQuadParticle {

    IcicleLandParticle(final ClientLevel level, final double x, final double y, final double z,
                       final double vx, final double vy, final double vz, final TextureAtlasSprite sprite) {
        super(level, x, y, z, vx, vy, vz, sprite);
        this.lifetime = 7 + this.random.nextInt(6);
        // Bigger spawns come from bigger icicles (the burst passes them higher
        // velocities), so the chunk reads large: size tracks the spawn speed.
        final double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        this.quadSize = 0.10F + 0.10F * Math.min(1.0F, (float) (speed * 3.0D));
        this.friction = 1.0F;
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
        this.yd = this.yd * 0.96D - 0.02D;
        this.move(this.xd, this.yd, this.zd);
        if (this.onGround) {
            // Settle where it landed instead of skating on.
            this.xd *= 0.6D;
            this.zd *= 0.6D;
        }
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.OPAQUE;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final Identifier spriteId;

        public Provider(final Identifier spriteId) {
            this.spriteId = spriteId;
        }

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double vx, final double vy, final double vz,
                                       final net.minecraft.util.RandomSource random) {
            return new IcicleLandParticle(level, x, y, z, vx, vy, vz, ParticleUtils.getSprite(this.spriteId));
        }
    }
}
