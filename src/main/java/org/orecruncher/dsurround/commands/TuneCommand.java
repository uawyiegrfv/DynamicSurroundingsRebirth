package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.orecruncher.dsurround.commands.handlers.TuneCommandHandler;
import org.orecruncher.dsurround.runtime.audio.AudioTuning;

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

    /**
     * The keys {@link AudioTuning#set(String, String)} handles, read from
     * {@link AudioTuning#keys()} so completion cannot drift from the switch below: add a case
     * without adding its key here and completion silently stops offering it.
     */
    private static final SuggestionProvider<CommandSourceStack> KEY_SUGGESTIONS = (ctx, builder) -> {
        for (var key : AudioTuning.keys())
            builder.suggest(key);
        return builder.buildFuture();
    };

    /**
     * Only the boolean keys have a fixed vocabulary. The numeric and comma-separated ones take
     * whatever you type, and suggesting values for them would read as a closed set the parser
     * does not actually enforce.
     */
    private static final SuggestionProvider<CommandSourceStack> VALUE_SUGGESTIONS = (ctx, builder) -> {
        String key = null;
        try {
            key = StringArgumentType.getString(ctx, KEY);
        } catch (final RuntimeException ignored) {
            // By the time a value is completed the key is already on the command line; if it
            // somehow is not, offering nothing beats letting completion throw.
        }
        if ("evaluateOnSoundThread".equals(key) || "realismWavelength".equals(key)
                || "probe".equals(key)) {
            builder.suggest("true");
            builder.suggest("false");
        }
        return builder.buildFuture();
    };

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND)
                .executes(ctx -> this.execute(ctx, TuneCommandHandler::describe))
                .then(net.minecraft.commands.Commands.literal(RESET)
                        .executes(ctx -> this.execute(ctx, TuneCommandHandler::reset)))
                .then(net.minecraft.commands.Commands.argument(KEY, StringArgumentType.word())
                        .suggests(KEY_SUGGESTIONS)
                        .then(net.minecraft.commands.Commands.argument(VALUE, StringArgumentType.word())
                                .suggests(VALUE_SUGGESTIONS)
                                .executes(this::set))));
    }

    private int set(CommandContext<CommandSourceStack> ctx) {
        return this.execute(ctx, () -> TuneCommandHandler.set(
                StringArgumentType.getString(ctx, KEY),
                StringArgumentType.getString(ctx, VALUE)));
    }
}
