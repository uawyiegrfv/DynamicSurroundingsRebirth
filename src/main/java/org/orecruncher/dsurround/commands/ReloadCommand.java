package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.orecruncher.dsurround.commands.handlers.ReloadCommandHandler;

class ReloadCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsreload";

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND).executes(this::execute));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        return this.execute(ctx, ReloadCommandHandler::execute);
    }
}