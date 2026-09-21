package org.orecruncher.dsurround.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the exact damage a hit dealt, for the popoff number.
 *
 * <p>Vanilla's own {@code ClientboundDamageEventPacket} carries no amount - only the entity id, the
 * source type and the source entities (verified against the mapped 1.20.1 jar) - so a client-side mod
 * could only infer the number from the entity's health. That inference is unreliable by construction:
 * the health sync is a SEPARATE packet, so it may arrive before the damage event, in which case the
 * measured delta is zero and the hit is silently dropped. The number therefore came out wrong or
 * missing, reported as "weapons dealing 15+ damage show as only 9".
 *
 * <p>{@code LivingHurtEvent.getAmount()} on the server is the real, post-mitigation value, so this
 * carries it across instead of guessing.
 *
 * <p>Sent only to players who can already see the entity, mirroring what vanilla does for its own
 * damage packet, so the overhead is of the same order as the packet that already exists.
 */
public record DamageMessage(int entityId, float amount, double sourceX, double sourceY, double sourceZ) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeFloat(this.amount);
        buf.writeDouble(this.sourceX);
        buf.writeDouble(this.sourceY);
        buf.writeDouble(this.sourceZ);
    }

    public static DamageMessage decode(FriendlyByteBuf buf) {
        return new DamageMessage(buf.readVarInt(), buf.readFloat(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(DamageMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> org.orecruncher.dsurround.processing.CritWordHandler
                .onExactDamage(msg.entityId(), msg.amount(),
                        msg.sourceX(), msg.sourceY(), msg.sourceZ()));
        ctx.get().setPacketHandled(true);
    }
}
