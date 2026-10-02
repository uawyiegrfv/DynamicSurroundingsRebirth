package org.orecruncher.dsurround.client.particle;

import dev.architectury.registry.client.particle.ParticleProviderRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.Constants;

/**
 * The drip layer's own particle. Vanilla's {@code dripping_water} reads as a
 * wide, saturated blue blob; the maintainer asked for something paler and a
 * pixel narrower, and that means owning a texture instead of borrowing one.
 *
 * <p>The type registers straight into the built-in registry during client
 * init, and the provider goes through Architectury's {@code
 * ParticleProviderRegistry}, which defers sprite binding until the particle
 * atlas has been stitched - one call, no loader-specific event wiring.</p>
 */
public final class ModParticles {

    public static final SimpleParticleType WATER_DRIP = new SimpleParticleType(false);

    private ModParticles() {
    }

    /**
     * Register the type from the loader's {@code RegisterEvent} - the only
     * place a mod is allowed to add to a built-in registry.
     *
     * <p>1.20.1 already proved the alternative does not work: a plain
     * {@code Registry.register} from client init throws "Can not register to a
     * locked registry" and takes the whole mod down during CONSTRUCT. Going
     * through the event means the entry goes in while the registry is still
     * open and the id is reserved before anything can look it up.</p>
     */
    public static void registerType(final net.neoforged.neoforge.registries.RegisterEvent event) {
        if (!event.getRegistryKey().equals(BuiltInRegistries.PARTICLE_TYPE.key()))
            return;
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "water_drip"),
                () -> WATER_DRIP);
    }

    /**
     * Binds the provider. Separate from the type because this one goes through
     * Architectury, which defers sprite binding until the particle atlas has
     * been stitched - one call, no loader-specific event wiring.
     */
    public static void registerProvider() {
        ParticleProviderRegistry.register(WATER_DRIP, sprites -> new WaterDripParticle.Provider(sprites));
    }
}
