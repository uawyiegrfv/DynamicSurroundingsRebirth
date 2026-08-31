package org.orecruncher.dsurround.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.orecruncher.dsurround.Constants;

public record WeatherPayload(boolean raining) implements CustomPacketPayload {

    public static final Type<WeatherPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weather"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, WeatherPayload::raining,
            WeatherPayload::new
    );

    @Override
    public Type<WeatherPayload> type() {
        return TYPE;
    }

    public static void handle(final WeatherPayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> WeatherSyncState.setRaining(payload.raining()));
    }
}
