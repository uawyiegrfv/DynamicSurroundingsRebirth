package org.orecruncher.dsurround.config.biome.biometraits;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.config.BiomeTrait;

import java.util.Collections;
import java.util.Set;

import static org.orecruncher.dsurround.config.BiomeTrait.*;

/**
 * Adds traits that follow from traits already collected, and that no tag can express.
 *
 * <p>Runs after the tag and name analyzers, so it sees the whole set they produced. Ported from
 * upstream 0.4.4, mapped onto this port's vocabulary: upstream derives AQUATIC / AQUATIC_ICY and we
 * use WATER plus the existing COLD/ICY (the tag analyzer here already maps the is_aquatic_icy tag to
 * WATER + COLD, so this covers the biomes that never got the tag).
 */
public final class BiomeTraitAnalyzer implements IBiomeTraitAnalyzer {

    @Override
    public String name() {
        return "BiomeTraitAnalyzer";
    }

    @Override
    public void analyze(@NotNull ResourceLocation id, @NotNull Biome biome, @NotNull Set<BiomeTrait> resultCollection) {
        // A cave is underground by definition.
        if (resultCollection.contains(CAVES))
            resultCollection.add(UNDERGROUND);

        // Anything frozen is cold, whatever the climate band said.
        if (!Collections.disjoint(resultCollection, Set.of(SNOWY, ICY)))
            resultCollection.add(COLD);

        // Water bodies share an acoustic family.
        if (!Collections.disjoint(resultCollection, Set.of(OCEAN, DEEP, RIVER)))
            resultCollection.add(WATER);

        // Frozen water is its own thing (the original had a dedicated acoustic for it).
        if (resultCollection.containsAll(Set.of(WATER, ICY)))
            resultCollection.add(COLD);
    }
}
