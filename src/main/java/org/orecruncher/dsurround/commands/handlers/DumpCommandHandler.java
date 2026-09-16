package org.orecruncher.dsurround.commands.handlers;

import net.minecraft.network.chat.Component;
import org.orecruncher.dsurround.config.libraries.*;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.platform.IMinecraftDirectories;
import org.orecruncher.dsurround.processing.FootstepGenerator;

import java.io.PrintStream;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class DumpCommandHandler {

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    private static final IMinecraftDirectories directories = ContainerManager.resolve(IMinecraftDirectories.class);
    private static final IBiomeLibrary biomeLibrary = ContainerManager.resolve(IBiomeLibrary.class);
    private static final ISoundLibrary soundLibrary = ContainerManager.resolve(ISoundLibrary.class);
    private static final IDimensionLibrary dimensionLibrary = ContainerManager.resolve(IDimensionLibrary.class);
    private static final IBlockLibrary blockLibrary = ContainerManager.resolve(IBlockLibrary.class);
    private static final IItemLibrary itemLibrary = ContainerManager.resolve(IItemLibrary.class);
    private static final ITagLibrary tagLibrary = ContainerManager.resolve(ITagLibrary.class);

    public static Component dumpBiomes() {
        return handle("biomes", biomeLibrary::dump);
    }

    public static Component dumpSounds() {
        return handle("sounds", soundLibrary::dump);
    }

    public static Component dumpDimensions() {
        return handle("dimensions", dimensionLibrary::dump);
    }

    public static Component dumpBlockConfigRules() {
        return handle("blockconfigrules", blockLibrary::dumpBlockConfigRules);
    }

    public static Component dumpBlockState() {
        return handle("blockstates", blockLibrary::dumpBlockStates);
    }

    public static Component dumpBlocks(boolean noStates) {
        return handle("blocks", () -> blockLibrary.dumpBlocks(noStates));
    }

    public static Component dumpBlocksByTag() {
        return handle("blocksbytag", blockLibrary::dump);
    }

    public static Component dumpItems() {
        return handle("items", itemLibrary::dump);
    }

    public static Component dumpTags() {
        return handle("tags", tagLibrary::dump);
    }

    public static Component dumpDIRegistrations() {
        return handle("diregistrations", ContainerManager::dumpRegistrations);
    }

    /**
     * Prints the footstep surface-resolution decision chain to chat instead of a file: it
     * describes the exact position the player is standing at, which is what a "this spot sounds
     * wrong" report needs (which branch of resolveSurfaceBlock fired, and why).
     */
    /**
     * Runs the data self-check and prints it to chat.
     * <p>
     * This exists because every failure worth chasing in this port was silent: a tag that loaded
     * with no members, a rule naming a block this version does not have, a factory event with no
     * sound behind it. None of them threw, and none were visible by reading the data files.
     */
    public static Component validateData() {
        try {
            return Component.literal(String.join("\n", org.orecruncher.dsurround.lib.diagnostics.DataValidator.validate()));
        } catch (Throwable t) {
            LOGGER.error(t, "Data validation failed");
            return Component.literal("dsdump validate failed: " + t.getMessage());
        }
    }

    public static Component dumpStepTrace() {
        final var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null)
            return Component.literal("dsdump steps: no client player");
        return Component.literal(String.join("\n", FootstepGenerator.dumpStepTrace(player)));
    }

    private static Component handle(final String operation, final Supplier<Stream<String>> supplier) {

        final String fileName = operation + ".txt";
        var target = directories.getModDumpDirectory().resolve(fileName).toFile();

        try {
            try (PrintStream out = new PrintStream(target)) {
                final Stream<String> stream = supplier.get();
                stream.forEach(out::println);
                out.flush();
            }
            return Component.translatable("dsurround.command.dsdump.success", operation, target.toString());
        } catch (final Throwable t) {
            LOGGER.error(t, "Error writing dump file '%s'", target.toString());
            return Component.translatable("dsurround.command.dsdump.failure", operation, t.getMessage());
        }
    }

}
