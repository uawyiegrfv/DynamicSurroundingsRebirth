package org.orecruncher.dsurround.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.orecruncher.dsurround.network.Network;

public final class MapCenterSyncService {

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++this.tickCounter < 20)
            return;
        this.tickCounter = 0;

        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncMaps(player);
        }
    }

    private void syncMaps(ServerPlayer player) {
        if (!Network.isPlayerPresent(player))
            return;
        for (var stack : player.getInventory().items) {
            if (!stack.is(Items.FILLED_MAP))
                continue;
            Integer mapId = MapItem.getMapId(stack);
            if (mapId == null)
                continue;
            var data = player.serverLevel().getMapData("map_" + mapId);
            if (data == null)
                continue;
            Network.sendMapCenterToPlayer(player, mapId, data.centerX, data.centerZ);
        }
    }
}
