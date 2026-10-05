package org.orecruncher.dsurround.client.particle;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.GameUtils;

/**
 * The drip layer's own particle. Vanilla's {@code dripping_water} reads as a
 * wide, saturated blue blob; the maintainer asked for something paler and a
 * pixel narrower, and that means owning a texture instead of borrowing one.
 *
 * <p>1.20.1 has no Architectury, so the type registers directly into the
 * built-in registry here and the provider is wired by {@code DSurround}
 * through Forge's {@code RegisterParticleProvidersEvent}.</p>
 */
public final class ModParticles {

    public static final SimpleParticleType WATER_DRIP = new SimpleParticleType(false);

    /** The three icicle SHAPES (size is decoupled - the handler rolls a size
     * tier from the snowfall-duration curve and the shape from the position
     * hash, so one texture reads at every size). */
    public static final SimpleParticleType ICICLE_CONE = new SimpleParticleType(false);
    public static final SimpleParticleType ICICLE_KNIFE = new SimpleParticleType(false);
    public static final SimpleParticleType ICICLE_WEDGE = new SimpleParticleType(false);
    /** The shard burst when an icicle shatters on the ground. */
    public static final SimpleParticleType ICICLE_LAND = new SimpleParticleType(false);

    private ModParticles() {
    }

    /**
     * Register the type from Forge's {@code RegisterEvent} - the only place a
     * 1.20.1 mod is allowed to add to a built-in registry.
     *
     * <p>{@code Registry.register} throws "Can not register to a locked
     * registry" here and takes the whole mod down at CONSTRUCT, which is what
     * the first attempt did. Forge serialises registration through this event
     * instead, so the entry goes in while the registry is still open and the
     * id is reserved before anything can ask for it.</p>
     */
    public static void registerType(final net.minecraftforge.registries.RegisterEvent event) {
        if (!event.getRegistryKey().equals(BuiltInRegistries.PARTICLE_TYPE.key()))
            return;
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "water_drip"),
                () -> WATER_DRIP);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_cone"),
                () -> ICICLE_CONE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_knife"),
                () -> ICICLE_KNIFE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_wedge"),
                () -> ICICLE_WEDGE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_land"),
                () -> ICICLE_LAND);
    }

    /**
     * Binds the provider. Deliberately not done from Forge's {@code
     * RegisterParticleProvidersEvent}: this Forge build does not expose a
     * {@code register} method on it at all (it fails to resolve, not to
     * overload), so the engine is reached directly instead - once the client
     * has actually started, which is also when the particle atlas is stitched
     * and the sprite {@code Provider} fetches exists.
     */
    public static void registerProvider() {
        final var engine = GameUtils.getParticleManager();
        if (engine != null) {
            engine.register(WATER_DRIP, new WaterDripParticle.Provider());
            engine.register(ICICLE_CONE, new IcicleParticle.Provider(12, 5, 0.7F,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_cone")));
            engine.register(ICICLE_KNIFE, new IcicleParticle.Provider(14, 3, 1.0F,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_knife")));
            engine.register(ICICLE_WEDGE, new IcicleParticle.Provider(10, 5, 0.45F,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_wedge")));
            engine.register(ICICLE_LAND, new IcicleLandParticle.Provider(
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "icicle_land")));
        }
    }
}
