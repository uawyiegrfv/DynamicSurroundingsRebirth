package org.orecruncher.dsurround.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.orecruncher.dsurround.Constants;

public final class Network {

    private static final String VERSION = "1.0";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
    );
    private static int id = 0;

    private Network() {
    }

    public static void register() {
        CHANNEL.registerMessage(id++, WeatherMessage.class,
                WeatherMessage::encode, WeatherMessage::decode, WeatherMessage::handle);
        CHANNEL.registerMessage(id++, MapCenterMessage.class,
                MapCenterMessage::encode, MapCenterMessage::decode, MapCenterMessage::handle);
    }

    public static void sendWeatherToPlayer(ServerPlayer player, boolean raining) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new WeatherMessage(raining));
    }

    public static void sendMapCenterToPlayer(ServerPlayer player, int mapId, int centerX, int centerZ) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MapCenterMessage(mapId, centerX, centerZ));
    }

    /** True when the remote (client) has DS installed and completed the channel handshake. */
    public static boolean isPlayerPresent(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }
}
