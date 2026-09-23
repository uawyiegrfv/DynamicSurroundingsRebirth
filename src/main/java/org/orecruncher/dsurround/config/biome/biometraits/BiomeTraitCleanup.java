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

    /**
     * Read-only counterpart of {@link #analyze}: true when the set holds a pair this
     * analyzer would have resolved. It changes nothing - it exists to report trait sets
     * the analyzer chain never got to see.
     *
     * <p>Why it is needed: config rules merge traits <em>after</em> the analyzers have run
     * ({@code BiomeLibrary.applyTraits}), and {@code clearTraits} replaces the set outright,
     * so a rule can leave behind a combination such as COLD + HOT that analyze() would never
     * have allowed.
     *
     * <p>Why the cleanup is NOT simply moved after the rules: that would also strip traits a
     * rule deliberately declares - a pack marking a desert COLD would silently lose it,
     * because analyze() lets HOT win over COLD. Traits reach scripts as plain booleans, so a
     * contradiction costs some ambience coherence; silently discarding a declared trait costs
     * the pack its intent. Reporting is the smaller of the two evils.
     *
     * <p>Keep the pairs below in step with {@link #analyze} - this is the one place where the
     * two are duplicated.
     */
    public static boolean hasContradiction(final BiomeTraits traits) {
        return (traits.contains(NETHER) && (traits.contains(OVERWORLD) || traits.contains(THEEND)))
                || (traits.contains(THEEND) && traits.contains(OVERWORLD))
                || (traits.contains(DENSE) && traits.contains(SPARSE))
                || (traits.contains(WET) && traits.contains(DRY))
                || (traits.contains(COLD) && traits.contains(HOT))
                // TEMPERATE is the weakest temperature claim, so analyze() drops it whenever an
                // explicit COLD or HOT is present. That asymmetry is the mirror image of the
                // COLD+HOT pair above and is reported for the same reason: it is a combination
                // the analyzer chain would have resolved, so seeing it means a rule reintroduced
                // it. Bounded cost - this runs once per biome at load, not per tick.
                || (traits.contains(TEMPERATE) && (traits.contains(COLD) || traits.contains(HOT)))
                || (traits.contains(RIVER) && (traits.contains(OCEAN) || traits.contains(DEEP)))
                || (traits.contains(DEEP) && traits.contains(WATER));
    }
}
