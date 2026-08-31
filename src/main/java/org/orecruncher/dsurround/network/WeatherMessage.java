package org.orecruncher.dsurround.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public record WeatherMessage(boolean raining) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.raining);
    }

    public static WeatherMessage decode(FriendlyByteBuf buf) {
        return new WeatherMessage(buf.readBoolean());
    }

    public static void handle(WeatherMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> WeatherSyncState.setRaining(msg.raining()));
        ctx.get().setPacketHandled(true);
    }
}
