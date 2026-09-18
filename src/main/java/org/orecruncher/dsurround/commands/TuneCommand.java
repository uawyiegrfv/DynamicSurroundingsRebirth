package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import net.minecraft.commands.CommandBuildContext;
import org.orecruncher.dsurround.commands.handlers.TuneCommandHandler;

/**
 * Live A/B tuning for the acoustic values that are otherwise only reachable by editing the config
 * and restarting.
 *
 * <p>This exists because "is the new behaviour better?" is unanswerable from memory: standing
 * behind a wall, switching the value, and hearing the difference in place is the only reliable
 * comparison. Nothing is persisted, so a value tried once does not silently become permanent.
 *
 * <p>NeoForge build: commands go through Architectury's {@code ClientCommandRegistrationEvent}
 * because the client command source stack differs from the vanilla one (see
 * {@link AbstractClientCommand}), so this one file is loader-specific while {@code AudioTuning} and
 * {@code TuneCommandHandler} are shared verbatim with the Forge build.
 */
class TuneCommand extends AbstractClientCommand {

    private static final String COMMAND = "dstune";
    private static final String RESET = "reset";
    private static final String KEY = "key";
    private static final String VALUE = "value";

    public void register(CommandDispatcher<ClientCommandRegistrationEvent.ClientCommandSourceStack> dispatcher,
                         CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommandRegistrationEvent.literal(COMMAND)
                .executes(ctx -> this.execute(ctx, TuneCommandHandler::describe))
                .then(ClientCommandRegistrationEvent.literal(RESET)
                        .executes(ctx -> this.execute(ctx, TuneCommandHandler::reset)))
                .then(ClientCommandRegistrationEvent.argument(KEY, StringArgumentType.word())
                        .then(ClientCommandRegistrationEvent.argument(VALUE, StringArgumentType.word())
                                .executes(this::set))));
    }

    private int set(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        return this.execute(ctx, () -> TuneCommandHandler.set(
                StringArgumentType.getString(ctx, KEY),
                StringArgumentType.getString(ctx, VALUE)));
    }
}
