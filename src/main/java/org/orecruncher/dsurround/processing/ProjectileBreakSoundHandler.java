package org.orecruncher.dsurround.processing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;

import java.util.WeakHashMap;

/**
 * Plays a break/shatter sound when a thrown ender pearl, egg or snowball is
 * removed after impacting a block or an entity.  Detection rides on the client
 * side entity removal notification (MixinEntityRemoved): the authoritative
 * server impact despawns the projectile almost immediately, so the removal
 * itself is the only reliable signal - the client-side ProjectileImpactEvent
 * loses that race nearly every time.  Works in multiplayer with the mod
 * installed client side only.
 */
public final class ProjectileBreakSoundHandler {

    private static final ResourceLocation PEARL_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/pearl");
    private static final ResourceLocation EGG_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/egg");
    private static final ResourceLocation SNOWBALL_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/snowball");

    private static final Configuration.EntityEffects CONFIG = ContainerManager.resolve(Configuration.EntityEffects.class);

    // Weak keys: entries vanish with the entity, no leak.
    private static final WeakHashMap<Projectile, Boolean> played = new WeakHashMap<>();

    private ProjectileBreakSoundHandler() {

    }

    /**
     * Called from MixinEntityRemoved for every entity removal.
     */
    public static void onEntityRemoved(final Entity entity, final Entity.RemovalReason reason) {
        // Discarded is the reason used for projectile despawn (and for the
        // client side remove packet).  Unloads and void kills are filtered out.
        if (reason != Entity.RemovalReason.DISCARDED)
            return;

        if (!CONFIG.enableProjectileBreakSounds)
            return;

        if (!(entity instanceof ThrownEnderpearl) && !(entity instanceof ThrownEgg) && !(entity instanceof Snowball))
            return;

        // Strictly client side.
        if (!entity.level().isClientSide())
            return;

        // Guard against a double play.
        if (played.put((Projectile) entity, Boolean.TRUE) != null)
            return;

        final ResourceLocation factoryId;
        if (entity instanceof ThrownEnderpearl)
            factoryId = PEARL_BREAK;
        else if (entity instanceof ThrownEgg)
            factoryId = EGG_BREAK;
        else
            factoryId = SNOWBALL_BREAK;

        // Resolve lazily: the sound library loads its data during resource loading.
        final ISoundFactory factory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactory(factoryId)
                .orElse(null);

        if (factory == null)
            return;

        final Vec3 pos = entity.position();
        ContainerManager.resolve(IAudioPlayer.class).play(factory.createAtLocation(pos));
    }
}