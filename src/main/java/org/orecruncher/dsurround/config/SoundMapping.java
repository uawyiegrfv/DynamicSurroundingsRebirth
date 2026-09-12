package org.orecruncher.dsurround.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.config.data.SoundMappingConfigRule;
import org.orecruncher.dsurround.lib.IMatcher;
import org.orecruncher.dsurround.lib.collections.ObjectArray;

import java.util.List;
import java.util.Optional;

public record SoundMapping(ResourceLocation soundEvent, ObjectArray<Mapping> rules) {

    public static SoundMapping of(SoundMappingConfigRule rule) {
        ObjectArray<Mapping> mappings = new ObjectArray<>(rule.rules().size());
        rule.rules().forEach(r -> mappings.add(Mapping.of(r)));
        return new SoundMapping(rule.soundEvent(), mappings);
    }

    public boolean isBlockStateNeeded() {
        return !this.rules.isEmpty() && !this.rules.getFirst().isDefaultRule();
    }

    public Optional<Mapping.MatchResult> findMatch(@Nullable BlockState state) {
        for (var rule : this.rules) {
            var result = rule.findMatch(state);
            if (result.isEmpty())
                continue;
            // Nothing but the catch-all default matched, so the block is not covered by any
            // explicit rule. Before settling for the generic material, see whether the block's
            // own name says what it is - that is what makes a modded "black_sandstone" resolve
            // to concrete instead of stone without anyone maintaining a per-mod block list.
            // Explicit rules have already had their chance, so inference can never override
            // deliberate data.
            if (rule.isDefaultRule()) {
                var inferred = MaterialInference.infer(state);
                if (inferred.isPresent())
                    return Optional.of(new Mapping.MatchResult(inferred.get(),
                            accentsFor(inferred.get(), result.get().accent())));
            }
            return result;
        }
        return Optional.empty();
    }

    /**
     * The accent belongs to the <em>material</em>, not to the block list that happens to name
     * it. Vanilla sandstone resolves to {@code concrete} <em>plus} a sand accent; a modded
     * sandstone that only reaches {@code concrete} through name inference has to get that same
     * accent, otherwise it plays the primary layer without the sand sub-sound that vanilla
     * sandstone has. Borrow the accent from whichever rule in this mapping already produces
     * the inferred factory, falling back to the default rule's accent when the material is not
     * used by any other rule.
     */
    private List<ResourceLocation> accentsFor(final ResourceLocation factory,
                                              final List<ResourceLocation> fallback) {
        for (var rule : this.rules)
            if (rule.factory().equals(factory) && !rule.accent().isEmpty())
                return rule.accent();
        return fallback;
    }

    public void merge(SoundMappingConfigRule mapping) {
        if (!this.soundEvent.equals(mapping.soundEvent()))
            throw new RuntimeException("Unable to merge sound mapping rule - factories do not match");

        for (var rule : mapping.rules()) {
            var mapped = Mapping.of(rule);

            // Find the first applicable rule that matches the factory that is needed.
            // It's possible to have multiple rules with the same factory. It can occur when
            // merging two different rule definitions where one has the factory as a default
            // and the other has block matchers.
            var existingRule = this.rules.stream().filter(r -> r.factory().equals(rule.factory())).findFirst();
            if (existingRule.isPresent()) {
                // If it is a default rule, we do not want to merge. Instead, we
                // insert prior. We need to preserve the existing rule as default.
                if (existingRule.get().isDefaultRule()) {
                    this.insertBeforeDefaultRule(mapped);
                } else {
                    // Need to add block matcher definitions
                    existingRule.get().merge(mapped);
                }
            } else {
                // Need to add the rule. If the last rule in the collection is all matches, we need
                // to insert prior. Otherwise, we append.
                var last = this.rules.getLast();
                if (last.isDefaultRule()) {
                    this.insertBeforeDefaultRule(mapped);
                } else {
                    this.rules.add(mapped);
                }
            }
        }
    }

    private void insertBeforeDefaultRule(Mapping mapping) {
        var last = this.rules.getLast();
        if (!last.isDefaultRule())
            throw new RuntimeException("Last rule in sound mapping configuration is not default");
        this.rules.remove(last);
        this.rules.add(mapping);
        this.rules.add(last);
    }

    public record Mapping(ObjectArray<IMatcher<BlockState>> blocks, ResourceLocation factory, List<ResourceLocation> accent) {

        public static Mapping of(SoundMappingConfigRule.MappingRule mappingRule) {
            ObjectArray<IMatcher<BlockState>> blocks = new ObjectArray<>(mappingRule.blocks().size());
            blocks.addAll(mappingRule.blocks());
            return new Mapping(blocks, mappingRule.factory(), mappingRule.accent());
        }

        public Optional<MatchResult> findMatch(@Nullable BlockState state) {
            if (this.isDefaultRule())
                return Optional.of(new MatchResult(this.factory, this.accent));
            // Since the rules have BlockState matching if a null state is provided
            // return empty - nothing could be matched.
            if (state == null)
                return Optional.empty();
            for( var rule : this.blocks) {
                if (rule.match(state))
                    return Optional.of(new MatchResult(this.factory, this.accent));
            }
            return Optional.empty();
        }

        /**
         * Result of a sound mapping match: the primary factory plus any layered accent factories.
         */
        public record MatchResult(ResourceLocation factory, List<ResourceLocation> accent) {}

        public boolean isDefaultRule() {
            return this.blocks.isEmpty();
        }

        public void merge(Mapping rule) {
            if (!this.factory.equals(rule.factory()))
                throw new RuntimeException("Unable to add mapping rule - factories do not match");
            this.blocks.addAll(rule.blocks());
        }
    }
}
