package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Small translucent puffs emitted from a living entity's mouth in cold biomes.
 * 1.20.1 port: rewritten for the TextureSheetParticle model.
 */
public class FrostBreathParticle extends TextureSheetParticle {

    private final SpriteSet spriteProvider;

    public FrostBreathParticle(LivingEntity entity) {
        super((ClientLevel) entity.level(), 0, 0, 0, 0.0, 0.0, 0.0);

        // Reuse the vanilla cloud sheet + setSpriteFromAge so the puff grows and
        // dissipates through the cloud's frames (matches 26.1). Fall back to a static
        // puff if the cloud sprite set is not registered yet.
        final SpriteSet cloud = ParticleUtils.getSpriteProvider(ParticleTypes.CLOUD);
        this.spriteProvider = cloud;
        this.setSprite(cloud != null ? cloud.get(0, 32)
                : ParticleUtils.getSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "generic_4")));

        final IRandomizer rand = Randomizer.current();

        final Vec3 origin = ParticleUtils.getBreathOrigin(entity);
        final Vec3 trajectory = ParticleUtils.getLookTrajectory(entity);

        this.setPos(origin.x, origin.y, origin.z);
        this.xo = origin.x;
        this.yo = origin.y;
        this.zo = origin.z;

        this.xd = trajectory.x * 0.01D;
        this.yd = trajectory.y * 0.01D;
        this.zd = trajectory.z * 0.01D;

        this.setAlpha(0.2F);
        float f1 = 1.0F - (float) (rand.nextDouble() * (double) 0.3F);
        this.setColor(f1, f1, f1);
        // 26.1 parity (1.875F): the 1.8x global 1.20.1 particle compensation made
        // the breath puffs look oversized - user verified the 26.1 size is correct.
        this.quadSize *= 1.875F * (entity.isBaby() ? 0.125F : 0.25F);
        int i = (int) (8.0D / (rand.nextDouble() * 0.8D + 0.3D));
        this.lifetime = (int) Math.max((float) i * 2.5F, 1.0F);
        this.hasPhysics = false;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public float getQuadSize(float tickDelta) {
        return this.quadSize * Mth.clamp(((float)this.age + tickDelta) / (float)this.lifetime * 32.0F, 0.0F, 1.0F);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            if (this.spriteProvider != null)
                this.setSpriteFromAge(this.spriteProvider);
            this.move(this.xd, this.yd, this.zd);
            this.xd *= 0.96F;
            this.yd *= 0.96F;
            this.zd *= 0.96F;

            if (this.onGround) {
                this.xd *= 0.7F;
                this.zd *= 0.7F;
            }
        }
    }
}