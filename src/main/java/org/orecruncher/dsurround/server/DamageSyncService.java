package org.orecruncher.dsurround.server;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.orecruncher.dsurround.network.Network;

/**
 * Server side: forwards the exact damage of each hit to the clients that can see the victim.
 *
 * <p>This exists because vanilla's {@code ClientboundDamageEventPacket} carries no damage amount, so
 * the popoff number could only be inferred from the victim's health on the client. That inference is
 * unreliable by construction - the health sync is a separate packet with no ordering guarantee
 * against the damage event, so a measured delta can come out as zero and the hit is then dropped
 * silently. The reported symptom was "weapons dealing 15+ damage show as only 9".
 *
 * <p>{@code LivingHurtEvent.getAmount()} is the real, post-mitigation value, so this sends that
 * instead of letting the client guess.
 *
 * <p>The client keeps its health-delta path as a fallback: it is all that is available when the
 * server does not have the mod, or when a player has not completed the channel handshake.
 */
public final class DamageSyncService {

    @SubscribeEvent
    public void onLivingHurt(final LivingHurtEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim == null || victim.level().isClientSide())
            return;

        final float amount = event.getAmount();
        if (amount <= 0F)
            return;

        final var source = event.getSource().getEntity();
        final double sx = source != null ? source.getX() : victim.getX();
        final double sy = source != null ? source.getY() : victim.getY();
        final double sz = source != null ? source.getZ() : victim.getZ();

        Network.sendDamage(victim, amount, sx, sy, sz);
    }
}
