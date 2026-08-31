package org.orecruncher.dsurround.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.orecruncher.dsurround.network.WeatherPayload;

/**
 * Server side: pushes the overworld's rain state to nether players whenever it
 * changes, so the nether dust can follow /weather even though the nether never
 * receives the vanilla per-dimension weather broadcast.
 */
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
