package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.orecruncher.dsurround.commands.handlers.FoliageCommandHandler;

/**
 * {@code /dsdebug foliage} - the plant-drip diagnostics.
 *
 * <p>The foliage layers are the first in this mod whose positions come from a
 * model's own texture rather than from a block rule, so "nothing is on screen"
 * has far more places to hide: the candidate predicate, the sky test, the
 * alpha-mask profile, the gate, and only then the particle. A command that
 * prints each stage is cheaper than guessing at any of them.</p>
 *
 * <p>{@code force} is the one that makes the feature testable at all: dew
 * only exists inside a window of the day clock, and waiting for in-game
 * 05:24 is not a workflow.</p>
 */
class FoliageCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsdebug";
    private static final String FOLIAGE = "foliage";
    private static final String SCAN = "scan";
    private static final String PROFILE = "profile";
    private static final String SPAWN = "spawn";
    private static final String FORCE = "force";
    private static final String SNOW = "snow";
    private static final String UNITS = "units";

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND)
                .then(net.minecraft.commands.Commands.literal(FOLIAGE)
                        .executes(ctx -> this.execute(ctx, FoliageCommandHandler::overview))
                        .then(subCommand(SCAN, FoliageCommandHandler::scan))
                        .then(subCommand(PROFILE, FoliageCommandHandler::profile))
                        .then(subCommand(SPAWN, FoliageCommandHandler::spawn))
                        .then(subCommand(FORCE, FoliageCommandHandler::force))
                        .then(net.minecraft.commands.Commands.literal(SNOW)
                                .executes(ctx -> this.execute(ctx, FoliageCommandHandler::snowStatus))
                                .then(net.minecraft.commands.Commands.argument(UNITS,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> this.execute(ctx, () -> FoliageCommandHandler.snowSet(
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, UNITS))))))
                )
        );
    }
}
