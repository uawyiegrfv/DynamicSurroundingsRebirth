package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * A2-8: Falling dust emitted from floating blocks (e.g. a dirt ledge with air below).
 * Falls with gravity, drifts slightly, fades out, and disappears when it settles.
 */
public class DustParticle extends TextureSheetParticle {
    private static final IRandomizer RANDOM = Randomizer.current();

    public DustParticle(ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, 0.0, 0.0, 0.0);
        this.setSprite(ParticleUtils.getSprite(new ResourceLocation("minecraft", "generic_5")));
        // Small, dust-like particle that falls.
        this.gravity = 1.0F;
        this.friction = 0.98F;
        this.quadSize *= 0.54F;
        this.lifetime = 50 + this.random.nextInt(30);
        this.setColor(0.62F, 0.5F, 0.32F);  // dirt brown
        this.setAlpha(0.9F);

        // Slight random horizontal drift.
        this.xd = RANDOM.nextGaussian() * 0.04F;
        this.yd = 0.0D;
        this.zd = RANDOM.nextGaussian() * 0.04F;
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

        // Gravity + drag, matching the original.
        this.yd -= 0.04D * this.gravity;
        this.move(this.xd, this.yd, this.zd);
        this.xd *= this.friction;
        this.yd *= this.friction;
        this.zd *= this.friction;

        // Fade out over the last 40% of the lifetime.
        float life = this.age / (float) this.lifetime;
        this.setAlpha(life > 0.6F ? 1.0F - (life - 0.6F) / 0.4F : 0.9F);

        // Gone once it settles on a solid block below.
        if (this.level.getBlockState(BlockPos.containing(this.x, this.y, this.z).below()).isSolid())
            this.remove();
    }
}
