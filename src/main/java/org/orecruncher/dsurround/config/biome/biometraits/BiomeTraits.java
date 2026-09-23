package org.orecruncher.dsurround.config.biome.biometraits;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.lib.collections.ObjectArray;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BiomeTraits {

    private static final ObjectArray<IBiomeTraitAnalyzer> traitAnalyzer = new ObjectArray<>(4);

    static {
        traitAnalyzer.add(new BiomeTagAnalyzer());
        traitAnalyzer.add(new BiomeNameFallbackAnalyzer());
        traitAnalyzer.add(new BiomeTraitAnalyzer());
        // Must be last: it removes contradictions the others introduced.
        traitAnalyzer.add(new BiomeTraitCleanup());
    }

    private final Set<BiomeTrait> traits;
    private boolean updatedByMerge;

    BiomeTraits(Collection<BiomeTrait> traits) {
        this.traits = new HashSet<>(traits);
    }

    public static BiomeTraits createFrom(Identifier id, Biome biome) {
        // One shared mutable set: the analyzers chain (each reads what the previous produced) and
        // the cleanup pass has to be able to REMOVE, which a collection-returning analyzer cannot
        // express. Order is fixed - see the registration above.
        final Set<BiomeTrait> traits = EnumSet.noneOf(BiomeTrait.class);
        for (var analyzer : traitAnalyzer)
            analyzer.analyze(id, biome, traits);
        return new BiomeTraits(traits);
    }

    public static BiomeTraits from(BiomeTrait... traits) {
        return new BiomeTraits(List.of(traits));
    }

    /**
     * Drops every trait so a rule carrying {@code clearTraits} can take over from the auto-detected
     * set. Also sets the merge marker: the resulting set is a product of configuration, and
     * diagnostics should not present it as whatever the analyzers detected.
     */
    public void clear() {
        this.traits.clear();
        this.updatedByMerge = true;
    }

    public void mergeTraits(Collection<BiomeTrait> traits) {
        int count = this.traits.size();
        this.traits.addAll(traits);
        this.updatedByMerge = this.updatedByMerge || count != this.traits.size();
    }

    public boolean contains(String trait) {
        return contains(BiomeTrait.of(trait));
    }

    public boolean contains(BiomeTrait trait) {
        return this.traits.contains(trait);
    }

    public void forEach(Consumer<BiomeTrait> consumer) {
        for (var t : this.traits)
            consumer.accept(t);
    }

    public String toString() {
        var temp = this.traits
                .stream()
                .map(BiomeTrait::getName)
                .collect(Collectors.joining(", "));

        var fmt = this.updatedByMerge ? "*[%s]" : "[%s]";
        return fmt.formatted(temp);
    }
}
