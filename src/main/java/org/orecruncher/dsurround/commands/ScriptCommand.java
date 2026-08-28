package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import org.orecruncher.dsurround.commands.handlers.ScriptCommandHandler;

class ScriptCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsscript";
    private static final String SCRIPT_PARAMETER = "script";

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND)
                .then(net.minecraft.commands.Commands.argument(SCRIPT_PARAMETER, MessageArgument.message()).executes(this::execute)));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        var script = ctx.getArgument(SCRIPT_PARAMETER, MessageArgument.Message.class);
        return this.execute(ctx, () -> ScriptCommandHandler.execute(script.getText()));
    }
}
