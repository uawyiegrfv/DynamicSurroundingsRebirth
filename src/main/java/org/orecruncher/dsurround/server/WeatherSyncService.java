package org.orecruncher.dsurround.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.orecruncher.dsurround.network.WeatherPayload;

public final class WeatherSyncService {

    private boolean lastRaining;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        boolean raining = server.overworld().isRaining();
        if (raining == this.lastRaining)
            return;
        this.lastRaining = raining;

        WeatherPayload payload = new WeatherPayload(raining);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.NETHER) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
