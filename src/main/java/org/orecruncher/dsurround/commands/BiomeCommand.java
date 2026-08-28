package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.commands.handlers.BiomeCommandHandler;

import static net.minecraft.commands.arguments.ResourceArgument.ERROR_INVALID_RESOURCE_TYPE;

class BiomeCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsbiome";
    private static final String BIOME_PARAMETER = "biome";
    private static final String SCRIPT_PARAMETER = "script";

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(net.minecraft.commands.Commands.literal(COMMAND)
                .then(net.minecraft.commands.Commands.argument(BIOME_PARAMETER, ResourceArgument.resource(registryAccess, Registries.BIOME))
                .then(net.minecraft.commands.Commands.argument(SCRIPT_PARAMETER, MessageArgument.message()).executes(this::execute))));
    }

    @SuppressWarnings("unchecked")
    public int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Holder.Reference<Biome> reference = ctx.getArgument(BIOME_PARAMETER, Holder.Reference.class);
        ResourceKey<Biome> registryKey = reference.key();
        if (!registryKey.isFor(Registries.BIOME)) {
            throw ERROR_INVALID_RESOURCE_TYPE.create(registryKey.location(), registryKey.registry(), Registries.BIOME.registry());
        }

        var script = ctx.getArgument(SCRIPT_PARAMETER, MessageArgument.Message.class);
        return this.execute(ctx, () -> BiomeCommandHandler.execute(registryKey.location(), script.getText()));
    }
}
