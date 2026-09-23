package org.orecruncher.dsurround.config.biome.biometraits;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.config.BiomeTrait;

import java.util.Set;

import static org.orecruncher.dsurround.config.BiomeTrait.*;

/**
 * Removes traits that contradict one another. MUST run last.
 *
 * <p>Why it is needed: three analyzers now contribute, and they can disagree about the same biome -
 * the climate bands say COLD while the vanilla table says TEMPERATE, a name says DRY while the
 * climate says WET. Downstream code treats traits as facts, so leaving both in produces incoherent
 * ambience rather than a blend. This is also the reason the analyzer interface mutates a set instead
 * of returning one: removal cannot be expressed any other way. Ported from upstream 0.4.4.
 */
public final class BiomeTraitCleanup implements IBiomeTraitAnalyzer {

    @Override
    public String name() {
        return "BiomeTraitCleanup";
    }

    @Override
    public void analyze(@NotNull Identifier id, @NotNull Biome biome, @NotNull Set<BiomeTrait> resultCollection) {
        // Dimensions are exclusive.
        if (resultCollection.contains(NETHER))
            resultCollection.removeAll(Set.of(OVERWORLD, THEEND));

        if (resultCollection.contains(THEEND))
            resultCollection.remove(OVERWORLD);

        // Vegetation density is one or the other.
        if (resultCollection.contains(DENSE))
            resultCollection.remove(SPARSE);

        // Moisture is one or the other.
        if (resultCollection.contains(WET))
            resultCollection.remove(DRY);

        // Temperature bands are mutually exclusive. TEMPERATE is removed FIRST because it is the
        // weakest claim: an explicit COLD or HOT from a tag or from the climate wins over the
        // default band.
        if (resultCollection.contains(COLD) || resultCollection.contains(HOT))
            resultCollection.remove(TEMPERATE);

        if (resultCollection.contains(HOT))
            resultCollection.remove(COLD);

        // A river is not an ocean, and a deep ocean is not a shallow one.
        if (resultCollection.contains(RIVER))
            resultCollection.removeAll(Set.of(OCEAN, DEEP));

        if (resultCollection.contains(DEEP))
            resultCollection.remove(WATER);
    }
}
