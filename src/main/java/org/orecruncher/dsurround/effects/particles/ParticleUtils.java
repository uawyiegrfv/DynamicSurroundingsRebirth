package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.mixins.core.MixinParticleManager;

public final class ParticleUtils {

    private static final IRandomizer RANDOM = Randomizer.current();

    /**
     * Look up the SpriteSet registered for the given particle type. In 1.20.1 the sprite
     * table lives directly on ParticleEngine (there is no ParticleResources), so it is
     * reached through the MixinParticleManager accessor.
     */
    public static SpriteSet getSpriteProvider(ParticleType<?> particleType) {
        var id = BuiltInRegistries.PARTICLE_TYPE.getKey(particleType);
        return ((MixinParticleManager) GameUtils.getParticleManager()).dsurround_getSpriteSets().get(id);
    }

    /**
     * Fetch a single sprite from the particle texture atlas. The ripple/footprint strips
     * are registered into the atlas via assets/dsurround/atlases/particles.json.
     */
    public static TextureAtlasSprite getSprite(ResourceLocation id) {
        var atlas = (TextureAtlas) GameUtils.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
        return atlas.getSprite(id);
    }

    public static Vec3 getBreathOrigin(final LivingEntity entity) {
        final Vec3 eyePosition = eyePosition(entity).subtract(0D, entity.isBaby() ? 0.1D : 0.2D, 0D);
        final Vec3 look = entity.getViewVector(1F); // Don't use the other look vector method!
        return eyePosition.add(look.scale(entity.isBaby() ? 0.25D : 0.5D));
    }

    public static Vec3 getLookTrajectory(final LivingEntity entity) {
        return entity.getLookAngle()
                .zRot(RANDOM.nextFloat() * 2F)   // yaw
                .yRot(RANDOM.nextFloat() * 2F)   // pitch
                .normalize();
    }

    /*
     * Use some corrective lenses because the MC routine just doesn't lower the
     * height enough for our rendering purpose.
     */
    private static Vec3 eyePosition(final Entity e) {
        var y = e.getEyePosition();
        if (e.isCrouching()) {
            y = y.subtract(0, 0.25D, 0);
        }
        return y;
    }
}
