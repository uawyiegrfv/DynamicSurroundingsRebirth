package org.orecruncher.dsurround.processing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;

import java.util.WeakHashMap;

/**
 * Plays a break/shatter sound when a thrown ender pearl, egg or snowball
 * impacts a block or an entity.  Rides on the NeoForge ProjectileImpactEvent,
 * which fires on the client for all projectiles (vanilla hooks it into
 * Projectile#tick on both logical sides).  A per-projectile guard keeps the
 * sound from repeating while the entity lingers before the server removes it.
 */
public class ProjectileBreakSoundHandler {

    private static final ResourceLocation PEARL_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/pearl");
    private static final ResourceLocation EGG_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/egg");
    private static final ResourceLocation SNOWBALL_BREAK = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "projectile/snowball");

    private final Configuration config;
    private final IAudioPlayer audioPlayer;

    // Weak keys: entries vanish with the entity, no leak.
    private final WeakHashMap<Projectile, Boolean> played = new WeakHashMap<>();

    public ProjectileBreakSoundHandler(Configuration config, IAudioPlayer audioPlayer) {
        this.config = config;
        this.audioPlayer = audioPlayer;
        NeoForge.EVENT_BUS.addListener(this::onProjectileImpact);
    }

    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (!this.config.entityEffects.enableProjectileBreakSounds)
            return;

        final Projectile projectile = event.getProjectile();
        if (!(projectile instanceof ThrownEnderpearl) && !(projectile instanceof ThrownEgg) && !(projectile instanceof Snowball))
            return;

        // Strictly client side.
        if (!projectile.level().isClientSide())
            return;

        if (event.getRayTraceResult().getType() == HitResult.Type.MISS)
            return;

        // The impact event can fire more than once while the projectile lingers
        // before the server removes it - only play once per projectile.
        if (this.played.put(projectile, Boolean.TRUE) != null)
            return;

        final ResourceLocation factoryId;
        if (projectile instanceof ThrownEnderpearl)
            factoryId = PEARL_BREAK;
        else if (projectile instanceof ThrownEgg)
            factoryId = EGG_BREAK;
        else
            factoryId = SNOWBALL_BREAK;

        // Resolve lazily: the sound library loads its data during resource loading.
        final ISoundFactory factory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactory(factoryId)
                .orElse(null);

        if (factory == null)
            return;

        final Vec3 pos = projectile.position();
        this.audioPlayer.play(factory.createAtLocation(pos));
    }
}