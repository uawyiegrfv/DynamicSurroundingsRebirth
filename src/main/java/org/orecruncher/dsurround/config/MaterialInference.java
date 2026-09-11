package org.orecruncher.dsurround.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.Constants;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Infers a Dynamic Surroundings footstep material from a block's registry name.
 *
 * <p>Why this exists: the sound_mappings rules match on explicit block ids, so a modded
 * block that is semantically a copy of a vanilla one (every biome mod ships its own
 * sandstone / marble / etc.) falls through to the generic default and takes the wrong
 * material. Auditing a single instance turned up 186 modded sandstone blocks resolving to
 * plain {@code stone} for exactly this reason - the vanilla rule lists the vanilla ids and
 * nothing else. Rather than hand-maintaining a block list per mod (which is also
 * version-specific), the fallback between "explicit rule" and "generic default" is filled
 * by looking at what the block is called.
 *
 * <p>Precedence is deliberate: <b>explicit rule &gt; name inference &gt; generic default</b>.
 * Inference only runs when nothing but the default rule matched, so it can never override
 * data someone wrote on purpose.
 *
 * <p>The keyword table is intentionally short and boring. It only carries names that are
 * unambiguous about the material, and matching is on word boundaries - a plain substring
 * test is not safe here, because {@code slate} is inside {@code deepslate} and would turn
 * every deepslate block into marble.
 */
public final class MaterialInference {

    /**
     * Keyword to material, most specific first ({@code raw_copper} has to be tested before
     * {@code copper}). Keys are matched against the block's registry path.
     */
    private static final List<Map.Entry<String, String>> KEYWORDS = List.of(
            Map.entry("raw_copper", "copper"),
            Map.entry("sandstone", "concrete"),
            Map.entry("limestone", "marble"),
            Map.entry("permafrost", "marble"),
            Map.entry("marble", "marble"),
            Map.entry("jasper", "marble"),
            Map.entry("shale", "marble"),
            Map.entry("obsidian", "lino"),
            Map.entry("copper", "copper"),
            Map.entry("thatch", "leaves_through"));

    /**
     * A name containing one of these is very unlikely to be the material it otherwise
     * looks like: {@code snowdrop}, {@code snowblossom_sapling} and
     * {@code snowblossom_leaves} are plants, not snow.
     */
    private static final List<String> NOT_A_MATERIAL = List.of(
            "potted", "sapling", "leaves", "flower", "blossom", "drops", "seed", "sprout",
            "vine", "bush", "berry", "grass");

    private MaterialInference() {
    }

    /**
     * @param state block under the entity's feet; may be null
     * @return the inferred footstep factory, or empty when the name says nothing useful
     */
    public static Optional<ResourceLocation> infer(@Nullable final BlockState state) {
        if (state == null)
            return Optional.empty();

        final var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null)
            return Optional.empty();

        final String path = key.getPath();
        for (final String word : NOT_A_MATERIAL)
            if (path.contains(word))
                return Optional.empty();

        for (final var entry : KEYWORDS)
            if (matchesWord(path, entry.getKey()))
                return Optional.of(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                        "footsteps." + entry.getValue()));

        return Optional.empty();
    }

    /**
     * Word-boundary match: the keyword has to be the whole path, its first segment, or a
     * later segment. {@code deepslate} does not match {@code slate}, {@code soul_sandstone}
     * does match {@code sandstone}.
     */
    private static boolean matchesWord(final String path, final String keyword) {
        return path.equals(keyword)
                || path.startsWith(keyword + "_")
                || path.contains("_" + keyword);
    }
}
