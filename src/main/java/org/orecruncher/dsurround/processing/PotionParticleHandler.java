package org.orecruncher.dsurround.processing;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.EffectParticleModificationEvent;
import org.orecruncher.dsurround.Configuration;

/**
 * Suppresses the player's potion/effect particles when configured, ported
 * from the original 1.12.2 "Suppress Potion Particles" option. In 26.1 the
 * vanilla particle set is stored in the synced DATA_EFFECT_PARTICLES data,
 * rebuilt from the active effects via the NeoForge
 * EffectParticleModificationEvent. The event fires for the server player
 * (whose list is then synced to clients) as well as for the local client
 * player, so we suppress for any Player to cover both ends.
 */
public class PotionParticleHandler {

    private final Configuration config;

    public PotionParticleHandler(Configuration config) {
        this.config = config;
        NeoForge.EVENT_BUS.addListener(this::onEffectParticles);
    }

    @SubscribeEvent
    public void onEffectParticles(EffectParticleModificationEvent event) {
        if (!this.config.entityEffects.suppressPotionParticles)
            return;

        if (event.getEntity() instanceof Player)
            event.setVisible(false);
    }
}
