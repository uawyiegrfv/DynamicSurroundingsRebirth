package org.orecruncher.dsurround.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.orecruncher.dsurround.network.BubblePayload;
import org.slf4j.Logger;

/**
 * Server side: /bubble <text> shows the text as a speech bubble above the sender's
 * head on every DS client within 30 blocks (sender included), without the message
 * ever appearing in chat. Requires DS on the server; vanilla clients never receive
 * the optional payload and simply see nothing.
 */
public final class BubbleCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final double BROADCAST_RADIUS = 30.0D;
    public static final int BUBBLE_SECONDS = 30;
    private static final int MAX_TEXT_LENGTH = 256;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bubble")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> this.execute(ctx.getSource(), StringArgumentType.getString(ctx, "text")))));
    }

    private int execute(CommandSourceStack source, String rawText) throws CommandSyntaxException {
        final ServerPlayer sender = source.getPlayerOrException();
        String text = rawText.trim();
        if (text.isEmpty())
            return 0;
        if (text.length() > MAX_TEXT_LENGTH)
            text = text.substring(0, MAX_TEXT_LENGTH);

        final var payload = new BubblePayload(sender.getUUID(), text, BUBBLE_SECONDS);
        int recipients = 0;
        for (final ServerPlayer recipient : sender.server.getPlayerList().getPlayers()) {
            if (recipient.level().dimension() != sender.level().dimension())
                continue;
            if (recipient.distanceToSqr(sender) > BROADCAST_RADIUS * BROADCAST_RADIUS)
                continue;
            PacketDistributor.sendToPlayer(recipient, payload);
            recipients++;
        }
        LOGGER.info("[BUBBLE-DBG] server: {} -> \"{}\" ({}s), recipients={}",
                sender.getName().getString(), text, BUBBLE_SECONDS, recipients);
        return 1;
    }
}
