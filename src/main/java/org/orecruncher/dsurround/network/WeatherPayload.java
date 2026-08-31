package org.orecruncher.dsurround.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.orecruncher.dsurround.Constants;

/**
 * Server -> client: the overworld's current rain state. The nether never receives
 * the vanilla weather packets (they are broadcast per-dimension), so the server
 * pushes the overworld's rain state to nether players so the nether dust can follow
 * /weather. Optional payload: a client without DS simply ignores it.
 */
public record WeatherPayload(boolean raining) implements CustomPacketPayload {

    public static final Type<WeatherPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "weather"));
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
