package org.orecruncher.dsurround.config.biome.biometraits;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.config.BiomeTrait;

import java.util.Set;

/**
 * Adds traits to (or removes them from) the set being built for one biome.
 *
 * <p>Mutating a shared set rather than returning a collection is deliberate: the analyzers run in a
 * fixed order and each one reads what the previous produced, and the last one (cleanup) has to be
 * able to REMOVE a trait another analyzer added. A collection-returning interface cannot express
 * either.
 */
public interface IBiomeTraitAnalyzer {

    /** Name used in the per-biome debug log. */
    String name();

    void analyze(@NotNull Identifier id, @NotNull Biome biome, @NotNull Set<BiomeTrait> resultCollection);
}
