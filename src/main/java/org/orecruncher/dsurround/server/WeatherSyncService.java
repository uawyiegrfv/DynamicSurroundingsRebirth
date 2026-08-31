package org.orecruncher.dsurround.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.orecruncher.dsurround.network.Network;

public final class WeatherSyncService {

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++this.tickCounter < 20)
            return;
        this.tickCounter = 0;

        MinecraftServer server = event.getServer();
        boolean raining = server.overworld().isRaining();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.NETHER && Network.isPlayerPresent(player)) {
                Network.sendWeatherToPlayer(player, raining);
            }
        }
    }
}
