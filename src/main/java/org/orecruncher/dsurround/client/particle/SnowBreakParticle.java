package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * A crumb of snow thrown off where a snow unit lands - the snow layer's
 * landing burst (round 7: "如果能像露水一样掉落就好了... 最好可以再加一个落地
 * 破碎粒子及动画").
 *
 * <p>Cloned from {@link IcicleLandParticle}, which does the same job for a
 * shattering icicle, then cut right down. The maintainer asked for vanilla's
 * snowball break but "原版的那个粒子太大，弹射范围太远太高了": a snowball
 * bursts eight item sprites over the better part of a block and throws them
 * high enough to arc. A crumb off a snow clump is a speck that drops more or
 * less where it landed.</p>
 *
 * <ul>
 * <li><b>Size.</b> A third of the icicle shard's - 0.045..0.075 against its
 *     0.10..0.20. It still tracks the speed the burst passed in, so a heavy
 *     landing throws visibly bigger crumbs than a light one.</li>
 * <li><b>Throw.</b> The burst hands out a couple of hundredths of a block per
 *     tick sideways and barely any lift; the tick below bleeds it off faster
 *     than the shard does, so the crumb is a few centimetres from where it
 *     started when it settles.</li>
 * <li><b>Life.</b> 5..9 ticks - a quarter of a second, gone before the eye
 *     can follow one.</li>
 * </ul>
 */
public class SnowBreakParticle extends TextureSheetParticle {

    SnowBreakParticle(final ClientLevel level, final double x, final double y, final double z,
                      final double vx, final double vy, final double vz, final TextureAtlasSprite sprite) {
        super(level, x, y, z, vx, vy, vz);
        this.setSprite(sprite);
        this.lifetime = 5 + this.random.nextInt(5);
        final double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        this.quadSize = 0.045F + 0.030F * Math.min(1.0F, (float) (speed * 3.0D));
    }

    /**
     * Hand written with fixed numbers, like the icicle shard's - this file
     * exists in three versions and the crumb has to fall the same in all of
     * them. Low gravity and heavy drag: a crumb flutters down, it does not arc.
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
        this.yd = this.yd * 0.88D - 0.012D;
        this.move(this.xd, this.yd, this.zd);
        this.xd *= 0.80D;
        this.zd *= 0.80D;
        if (this.onGround) {
            // Settle where it landed instead of skating on.
            this.xd *= 0.4D;
            this.zd *= 0.4D;
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
            return new SnowBreakParticle(level, x, y, z, vx, vy, vz, ParticleUtils.getSprite(this.spriteId));
        }
    }
}
