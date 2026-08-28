package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import org.orecruncher.dsurround.lib.Library;

import java.util.function.Supplier;

abstract class AbstractClientCommand {

    protected AbstractClientCommand() {

    }

    public abstract void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess);

    private static net.minecraft.world.entity.player.Player getClientPlayer() {
        return Minecraft.getInstance().player;
    }

    protected int execute(CommandContext<CommandSourceStack> ctx, Supplier<Component> commandHandler) {
        try {
            var result = commandHandler.get();
            getClientPlayer().sendSystemMessage(result);
            return 0;
        } catch(Exception ex) {
            Library.LOGGER.error(ex, "Unable to execute command %s", ctx.getCommand().toString());
            getClientPlayer().sendSystemMessage(Component.literal(ex.getMessage()));
            return 1;
        }
    }

    protected LiteralArgumentBuilder<CommandSourceStack> subCommand(String command, Supplier<Component> supplier) {
        return net.minecraft.commands.Commands.literal(command).executes(ctx -> this.execute(ctx, supplier));
    }
}
