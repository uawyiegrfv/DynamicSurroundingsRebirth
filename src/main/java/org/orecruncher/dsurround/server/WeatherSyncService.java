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

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // Sync every 20 ticks (1s): covers both weather changes and a player freshly
        // entering the nether (whose client cache would otherwise stay stale).
        if (++this.tickCounter < 20)
            return;
        this.tickCounter = 0;

        MinecraftServer server = event.getServer();
        WeatherPayload payload = new WeatherPayload(server.overworld().isRaining());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.NETHER) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
