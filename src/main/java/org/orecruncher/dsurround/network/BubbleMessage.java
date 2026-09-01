package org.orecruncher.dsurround.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -> client: a /bubble command message shown as a speech bubble above the
 * sender's head. The server only sends it to players within 30 blocks in the same
 * dimension (sender included); it never goes through chat. Clients without DS never
 * receive it (channel handshake).
 */
public record BubbleMessage(UUID sender, String text, int seconds) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.sender);
        buf.writeUtf(this.text, 256);
        buf.writeVarInt(this.seconds);
    }

    public static BubbleMessage decode(FriendlyByteBuf buf) {
        return new BubbleMessage(buf.readUUID(), buf.readUtf(256), buf.readVarInt());
    }

    public static void handle(BubbleMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> BubbleSyncState.enqueue(msg.sender(), msg.text(), msg.seconds()));
        ctx.get().setPacketHandled(true);
    }
}
