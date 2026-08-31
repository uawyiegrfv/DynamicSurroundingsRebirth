package org.orecruncher.dsurround.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.orecruncher.dsurround.Constants;

/**
 * Server -> client: the world-space centre of a filled map. The vanilla map sync
 * does not include the centre, so the treasure-distance overlay pulls it from the
 * server to compute real coordinates on a dedicated server.
 */
public record MapCenterPayload(int mapId, int centerX, int centerZ) implements CustomPacketPayload {

    public static final Type<MapCenterPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "map_center"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapCenterPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, MapCenterPayload::mapId,
            ByteBufCodecs.INT, MapCenterPayload::centerX,
            ByteBufCodecs.INT, MapCenterPayload::centerZ,
            MapCenterPayload::new
    );

    @Override
    public Type<MapCenterPayload> type() {
        return TYPE;
    }

    public static void handle(final MapCenterPayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> MapCenterCache.put(payload.mapId(), payload.centerX(), payload.centerZ()));
    }
}
