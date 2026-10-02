package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The eave drip drop: forms at the underside edge, hangs and stretches as it
 * fills, then lets go and falls for exactly the ticks the landing sound was
 * booked against - so the fall the player watches is the fall the sound
 * times.
 *
 * <p>The texture is ours (pale, one pixel wide) because vanilla's is a
 * saturated two-pixel blob and a texture cannot be tinted thinner. The fall
 * and hang lengths ride in through the spawn call's speed arguments - a
 * particle options class for a single spawn site is more ceremony than
 * help.</p>
 *
 * <p>26.1 rebuilt this corner of the particle API: {@code TextureSheetParticle}
 * is gone and {@code SingleQuadParticle} takes its place, the sprite arrives
 * through the constructor instead of a {@code setSprite} call, and the render
 * group is a {@code Layer} record rather than a {@code ParticleRenderType}.
 * The motion is written out by hand rather than inherited, because it is
 * scripted - the handler already knows where the drop lands, so inheriting a
 * physics step would only mean inheriting whatever 26.1 decided gravity and
 * friction now do.</p>
 */
public class WaterDripParticle extends SingleQuadParticle {

    /** Must agree with {@code DripHandler.HANG_TICKS} - the sound is booked on the same clock. */
    public static final int HANG_TICKS = 40;

    /**
     * The drop's on-screen size while it hangs, and what it stretches to before
     * it lets go.
     *
     * <p>Not 0.01. Vanilla's {@code DripParticle} only calls {@code
     * setSize(0.01F, 0.01F)} and never touches {@code quadSize}, so the quad
     * stays at the base class default of 0.1 - setting 0.01 here made the drop
     * ten times too small, which is what "特别特别小" was. The texture is one
     * pixel wide against vanilla's two, so at the same quad size the drop is
     * already half as wide as vanilla's; these two only need to be near it.</p>
     */
    private static final float DRIP_START_SIZE = 0.06F;
    private static final float DRIP_FULL_SIZE = 0.11F;

    private final int fallTicks;

    WaterDripParticle(final ClientLevel level, final double x, final double y, final double z,
                      final double hangTicks, final double fallTicks, final SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.fallTicks = Math.max(1, (int) Math.round(fallTicks));
        this.lifetime = (int) Math.round(hangTicks) + this.fallTicks;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.quadSize = DRIP_START_SIZE;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        // Translucent: the point of owning the texture was a paler, thinner
        // drop, and an opaque layer would ignore its alpha.
        return Layer.TRANSLUCENT;
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
        if (this.age <= HANG_TICKS) {
            // filling: the droplet stretches as it takes on water and sags a
            // little toward the ground
            final float fill = (float) this.age / HANG_TICKS;
            // Hangs still and stretches - it has not let go yet. Moving it
            // downward here is what made the drop appear to "slowly fall, then
            // speed up"; the fall belongs to gravity, after release.
            this.quadSize = DRIP_START_SIZE + (DRIP_FULL_SIZE - DRIP_START_SIZE) * fill;
            return;
        }
        // the drop lets go: scripted pace, about two ticks to the block
        this.yd -= 0.06D;
        this.setPos(this.x + this.xd, this.y + this.yd, this.z + this.zd);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double hangTicks, final double fallTicks, final double unused,
                                       final net.minecraft.util.RandomSource random) {
            return new WaterDripParticle(level, x, y, z, hangTicks, fallTicks, this.sprites);
        }
    }
}
