package org.orecruncher.dsurround.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.orecruncher.dsurround.Constants;

/**
 * Server -> client: a /bubble command message shown as a speech bubble above the
 * sender's head. The server only sends it to players within 30 blocks in the same
 * dimension (sender included); it never goes through chat. Optional payload: a
 * client without DS simply ignores it. The UUID rides as a string so the codec
 * needs no dedicated UUID support.
 */
public record BubblePayload(UUID sender, String text, int seconds) implements CustomPacketPayload {

    public static final Type<BubblePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "bubble"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BubblePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, payload -> payload.sender().toString(),
            ByteBufCodecs.STRING_UTF8, BubblePayload::text,
            ByteBufCodecs.VAR_INT, BubblePayload::seconds,
            (uuid, text, seconds) -> new BubblePayload(UUID.fromString(uuid), text, seconds));

    @Override
    public Type<BubblePayload> type() {
        return TYPE;
    }

    public static void handle(final BubblePayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> BubbleSyncState.enqueue(payload.sender(), payload.text(), payload.seconds()));
    }
}
