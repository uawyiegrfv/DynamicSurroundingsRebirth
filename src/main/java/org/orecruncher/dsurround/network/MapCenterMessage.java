package org.orecruncher.dsurround.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public record MapCenterMessage(int mapId, int centerX, int centerZ) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(this.mapId);
        buf.writeInt(this.centerX);
        buf.writeInt(this.centerZ);
    }

    public static MapCenterMessage decode(FriendlyByteBuf buf) {
        return new MapCenterMessage(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(MapCenterMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> MapCenterCache.put(msg.mapId(), msg.centerX(), msg.centerZ()));
        ctx.get().setPacketHandled(true);
    }
}
