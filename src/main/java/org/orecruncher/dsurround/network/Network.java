package org.orecruncher.dsurround.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.orecruncher.dsurround.Constants;

public final class Network {

    private static final String VERSION = "1.0";
    // acceptMissingOr: a peer WITHOUT DS (no channel) is accepted, so players can join
    // servers regardless of which side carries the mod. Server-side features simply
    // don't fire for peers that never handshake the channel (see isPlayerPresent).
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION::equals),
            NetworkRegistry.acceptMissingOr(VERSION::equals)
    );
    private static int id = 0;

    private Network() {
    }

    public static void register() {
        CHANNEL.registerMessage(id++, WeatherMessage.class,
                WeatherMessage::encode, WeatherMessage::decode, WeatherMessage::handle);
        CHANNEL.registerMessage(id++, MapCenterMessage.class,
                MapCenterMessage::encode, MapCenterMessage::decode, MapCenterMessage::handle);
        CHANNEL.registerMessage(id++, BubbleMessage.class,
                BubbleMessage::encode, BubbleMessage::decode, BubbleMessage::handle);
        // The exact damage a hit dealt. Vanilla's damage packet carries no amount, so without this
        // the popoff number has to be inferred from the entity's health - a separate packet, and
        // therefore an unreliable source.
        CHANNEL.registerMessage(id++, DamageMessage.class,
                DamageMessage::encode, DamageMessage::decode, DamageMessage::handle);
    }

    /**
     * Sends the exact damage a hit dealt to players near the victim.
     *
     * <p>The recipient set mirrors vanilla's own damage packet - everyone tracking the entity - so a
     * busy server pays for one extra small packet per hit it was already spending one on. The range
     * is the entity-tracking range, rounded down; {@code ServerPlayer.canSee} does not exist on the
     * server, so distance is what is available.
     */
    public static void sendDamage(final net.minecraft.world.entity.LivingEntity victim, final float amount,
                                  final double sourceX, final double sourceY, final double sourceZ) {
        final var message = new DamageMessage(victim.getId(), amount, sourceX, sourceY, sourceZ);
        final double rangeSqr = ENTITY_TRACK_RANGE * ENTITY_TRACK_RANGE;
        for (final ServerPlayer player : victim.level().getEntitiesOfClass(ServerPlayer.class,
                victim.getBoundingBox().inflate(ENTITY_TRACK_RANGE))) {
            if (player == victim || player.distanceToSqr(victim) > rangeSqr)
                continue;
            if (isPlayerPresent(player))
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    /** Client-side entity tracking range, the same set vanilla sends its damage packet to. */
    private static final double ENTITY_TRACK_RANGE = 48.0D;

    public static void sendWeatherToPlayer(ServerPlayer player, boolean raining) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new WeatherMessage(raining));
    }

    public static void sendMapCenterToPlayer(ServerPlayer player, int mapId, int centerX, int centerZ) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MapCenterMessage(mapId, centerX, centerZ));
    }

    public static void sendBubbleToPlayer(ServerPlayer player, BubbleMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** True when the remote (client) has DS installed and completed the channel handshake. */
    public static boolean isPlayerPresent(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }
}
