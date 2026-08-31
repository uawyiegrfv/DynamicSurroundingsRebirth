package org.orecruncher.dsurround.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.orecruncher.dsurround.network.MapCenterPayload;

public final class MapCenterSyncService {

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++this.tickCounter < 20)
            return;
        this.tickCounter = 0;

        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncMaps(player);
        }
    }

    private void syncMaps(ServerPlayer player) {
        for (var stack : player.getInventory().items) {
            if (!stack.is(Items.FILLED_MAP))
                continue;
            var mapId = stack.get(DataComponents.MAP_ID);
            if (mapId == null)
                continue;
            var data = player.serverLevel().getMapData(mapId);
            if (data == null)
                continue;
            PacketDistributor.sendToPlayer(player, new MapCenterPayload(mapId.id(), data.centerX, data.centerZ));
        }
    }
}
