package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.orecruncher.dsurround.commands.handlers.TuneCommandHandler;

/**
 * Live A/B tuning for the acoustic values that are otherwise only reachable by editing the config
 * and restarting.
 *
 * <p>This exists because "is the new behaviour better?" is unanswerable from memory: standing
 * behind a wall, switching the value, and hearing the difference in place is the only reliable
 * comparison. Nothing is persisted, so a value tried once does not silently become permanent.
 */
class TuneCommand extends AbstractClientCommand {

    private static final String COMMAND = "dstune";
    private static final String RESET = "reset";
    private static final String KEY = "key";
    private static final String VALUE = "value";

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND)
                .executes(ctx -> this.execute(ctx, TuneCommandHandler::describe))
                .then(net.minecraft.commands.Commands.literal(RESET)
                        .executes(ctx -> this.execute(ctx, TuneCommandHandler::reset)))
                .then(net.minecraft.commands.Commands.argument(KEY, StringArgumentType.word())
                        .then(net.minecraft.commands.Commands.argument(VALUE, StringArgumentType.word())
                                .executes(this::set))));
    }

    private int set(CommandContext<CommandSourceStack> ctx) {
        return this.execute(ctx, () -> TuneCommandHandler.set(
                StringArgumentType.getString(ctx, KEY),
                StringArgumentType.getString(ctx, VALUE)));
    }
}
