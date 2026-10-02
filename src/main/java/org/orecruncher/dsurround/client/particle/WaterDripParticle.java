package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

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
 * help - and physics are scripted rather than simulated, since the handler
 * already knows the landing spot from the scan.</p>
 *
 * <p>1.20.1 is the odd one out in two ways. It has no Architectury, and
 * Forge's event only exposes the plain {@code register(type, provider)}
 * overload here, so the provider cannot be handed a {@code SpriteSet}; the
 * sprite is pulled from the particle atlas on demand through the same
 * {@code ParticleUtils} helper the rest of the mod's particles use. And the
 * sprite is set after construction rather than passed to the super
 * constructor.</p>
 */
public class WaterDripParticle extends TextureSheetParticle {

    /** Must agree with {@code DripHandler.HANG_TICKS} - the sound is booked on the same clock. */
    public static final int HANG_TICKS = 40;

    static final ResourceLocation SPRITE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "water_drip");

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
    private int age = 0;

    WaterDripParticle(final ClientLevel level, final double x, final double y, final double z,
                      final double hangTicks, final double fallTicks, final TextureAtlasSprite sprite) {
        super(level, x, y, z);
        this.setSprite(sprite);
        this.fallTicks = Math.max(1, (int) Math.round(fallTicks));
        this.lifetime = (int) Math.round(hangTicks) + this.fallTicks;
        this.gravity = 0.0F;      // the fall is scripted, not simulated
        this.setSize(0.005F, 0.02F);
        this.quadSize = DRIP_START_SIZE;
    }

    @Override
    public ParticleRenderType getRenderType() {
        // Translucent, not opaque: the whole point of owning the texture was a
        // paler, thinner drop, and an opaque quad throws that away by ignoring
        // the alpha in it.
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;
        if (this.age >= this.lifetime) {
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
        this.move(this.xd, this.yd, this.zd);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double hangTicks, final double fallTicks, final double unused) {
            return new WaterDripParticle(level, x, y, z, hangTicks, fallTicks,
                    ParticleUtils.getSprite(SPRITE));
        }
    }
}
