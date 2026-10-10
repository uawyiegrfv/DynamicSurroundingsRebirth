package org.orecruncher.dsurround.client.particle;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.orecruncher.dsurround.Constants;

/**
 * The drip layer's own particle. Vanilla's {@code dripping_water} reads as a
 * wide, saturated blue blob; the maintainer asked for something paler and a
 * pixel narrower, and that means owning a texture instead of borrowing one.
 *
 * <p>The type goes in through the loader's {@code RegisterEvent} and the
 * providers through its {@code RegisterParticleProvidersEvent} - the two
 * windows the loader opens for exactly this, both wired up in the mod
 * constructor. See {@link #registerType} and {@link #registerProviders}.</p>
 */
public final class ModParticles {

    /**
     * The type, not the key. {@code DripHandler} hands this straight to
     * {@code world.addParticle}, which wants {@code ParticleOptions} - an
     * {@code Identifier} would compile in the registry call below and then
     * fail at every spawn site, because a resource location is not a particle
     * option. The key is built separately, once, at registration.
     */
    public static final SimpleParticleType WATER_DRIP = new SimpleParticleType(false);

    /** The three icicle SHAPES (size is decoupled - the handler rolls a size
     * tier from the snowfall-duration curve and the shape from the position
     * hash, so one texture reads at every size). */
    public static final SimpleParticleType ICICLE_CONE = new SimpleParticleType(false);
    public static final SimpleParticleType ICICLE_KNIFE = new SimpleParticleType(false);
    public static final SimpleParticleType ICICLE_WEDGE = new SimpleParticleType(false);
    /** The shard burst when an icicle shatters on the ground. */
    public static final SimpleParticleType ICICLE_LAND = new SimpleParticleType(false);
    /** A droplet attached to a plant surface - rain sliders/droppers and dew
     * beads share it; the spawning handler decides the fate via setters. */
    public static final SimpleParticleType FOLIAGE_DROP = new SimpleParticleType(false);

    /** The snow layer's unit resting on a plant. */
    public static final SimpleParticleType FOLIAGE_SNOW = new SimpleParticleType(false);
    /** The crumb burst when that unit lands - see {@link SnowBreakParticle}. */
    public static final SimpleParticleType SNOW_BREAK = new SimpleParticleType(false);

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
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "water_drip"),
                () -> WATER_DRIP);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_cone"),
                () -> ICICLE_CONE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_knife"),
                () -> ICICLE_KNIFE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_wedge"),
                () -> ICICLE_WEDGE);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_land"),
                () -> ICICLE_LAND);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "foliage_drop"),
                () -> FOLIAGE_DROP);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "foliage_snow"),
                () -> FOLIAGE_SNOW);
        event.register(BuiltInRegistries.PARTICLE_TYPE.key(),
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_break"),
                () -> SNOW_BREAK);
    }

    /**
     * 26.1: the icicle's own particle group. This version dispatches particles
     * by their render type, and a custom group is the only way to add a factory
     * for one - the icicle needs it because its quad is not square (see
     * {@link IcicleParticleGroup}).
     */
    public static void registerParticleGroups(
            final net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent event) {
        event.register(IcicleParticleGroup.type(), IcicleParticleGroup::new);
        // The foliage drop carries its whole life in one group - 26.1 assigns a
        // particle to its group at add time, so the FALL billboard stays here
        // too rather than moving to a second group.
        event.register(FoliageDripParticleGroup.type(), FoliageDripParticleGroup::new);
        event.register(FoliageSnowParticleGroup.type(), FoliageSnowParticleGroup::new);
    }

    /**
     * Bind the providers, from the loader's own {@code
     * RegisterParticleProvidersEvent}.
     *
     * <p>This used to go through Architectury's {@code
     * ParticleProviderRegistry}, whose {@code register(ParticleType, ...)}
     * overloads are <b>empty methods</b> in architectury-neoforge - javap shows
     * {@code 0: return} for both, and only the {@code RegistrySupplier} overload
     * does anything, which we cannot use because we do not hold suppliers. So
     * not one provider was ever bound and none of our particles was ever drawn,
     * with nothing at all in the log to say why.</p>
     *
     * <p>Moving the old call from {@code initializeClient()} to {@code
     * onComplete()} did not help either: moving a call to an empty method
     * changes nothing. The loader's own event is the supported route, and the
     * engine is handed to us already stitched.</p>
     */
    public static void registerProviders(final RegisterParticleProvidersEvent event) {
        // Only the drip particle takes a SpriteSet - its sprite is bound at
        // stitch time, so it needs the deferred route.
        event.registerSpriteSet(WATER_DRIP, sprites -> new WaterDripParticle.Provider(sprites));
        // The rest fetch their own sprite from the atlas by id, so they are
        // plain providers.
        event.registerSpecial(ICICLE_CONE, new IcicleParticle.Provider(12, 5, 0.7F,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_cone")));
        event.registerSpecial(ICICLE_KNIFE, new IcicleParticle.Provider(14, 3, 1.0F,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_knife")));
        event.registerSpecial(ICICLE_WEDGE, new IcicleParticle.Provider(10, 5, 0.45F,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_wedge")));
        event.registerSpecial(ICICLE_LAND, new IcicleLandParticle.Provider(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "icicle_land")));
        event.registerSpecial(FOLIAGE_DROP, new FoliageDripParticle.Provider());
        event.registerSpecial(FOLIAGE_SNOW, new FoliageSnowParticle.Provider());
        event.registerSpecial(SNOW_BREAK, new SnowBreakParticle.Provider(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_dot_a")));
    }
}
