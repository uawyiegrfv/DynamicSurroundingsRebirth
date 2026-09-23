package org.orecruncher.dsurround.config.biome.biometraits;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;

import java.util.*;

import static java.util.Map.entry;
import static org.orecruncher.dsurround.config.BiomeTrait.*;

/**
 * Traits for a biome that the tag analyzer could not classify.
 *
 * <p>Why this exists: the tag analyzer can only see biomes that something tagged. We ship adaptation
 * tags for a handful of mods, so every OTHER modded biome previously came back with no traits at all
 * - and a biome with no traits gets no ambience. This infers from the biome id and from the actual
 * climate instead, so an unadapted modded forest still sounds like a forest.
 *
 * <p>Vanilla biomes are answered from an explicit table (exact, not inferred); anything else falls
 * through to name families and then to climate. Ported from upstream 0.4.4's
 * BiomeNameFallbackAnalyzer, with the traits mapped onto this port's vocabulary - upstream has
 * separate BIRCH_FOREST / DARK_FOREST / SNOWY_PLAINS / AQUATIC / SHALLOW_OCEAN traits and we express
 * those through FOREST / SNOWY / WATER / OCEAN, which is what the tag analyzer here already does.
 */
public final class BiomeNameFallbackAnalyzer implements IBiomeTraitAnalyzer {

    private static final Map<String, Set<BiomeTrait>> VANILLA_TRAITS = Map.<String, Set<BiomeTrait>>ofEntries(
            entry("plains", t(OVERWORLD, PLAINS, TEMPERATE, SPARSE)),
            entry("sunflower_plains", t(OVERWORLD, PLAINS, TEMPERATE, FLORAL, SPARSE)),
            entry("snowy_plains", t(OVERWORLD, PLAINS, COLD, SNOWY, SPARSE)),
            entry("ice_spikes", t(OVERWORLD, PLAINS, COLD, SNOWY, ICY, RARE, SPARSE)),
            entry("desert", t(OVERWORLD, DESERT, HOT, DRY, SANDY, SPARSE)),
            entry("swamp", t(OVERWORLD, SWAMP, TEMPERATE, WET, DENSE)),
            entry("mangrove_swamp", t(OVERWORLD, SWAMP, HOT, WET, DENSE)),
            entry("forest", t(OVERWORLD, FOREST, TEMPERATE, DECIDUOUS, DENSE)),
            entry("flower_forest", t(OVERWORLD, FOREST, TEMPERATE, DECIDUOUS, FLORAL, DENSE)),
            entry("birch_forest", t(OVERWORLD, FOREST, TEMPERATE, DECIDUOUS, DENSE)),
            entry("dark_forest", t(OVERWORLD, FOREST, TEMPERATE, DECIDUOUS, DENSE, SPOOKY)),
            entry("old_growth_birch_forest", t(OVERWORLD, FOREST, TEMPERATE, DECIDUOUS, DENSE, RARE)),
            entry("old_growth_pine_taiga", t(OVERWORLD, FOREST, TAIGA, COLD, CONIFEROUS, DENSE, RARE)),
            entry("old_growth_spruce_taiga", t(OVERWORLD, FOREST, TAIGA, COLD, CONIFEROUS, DENSE, RARE)),
            entry("taiga", t(OVERWORLD, FOREST, TAIGA, COLD, CONIFEROUS, DENSE)),
            entry("snowy_taiga", t(OVERWORLD, FOREST, TAIGA, COLD, SNOWY, CONIFEROUS, DENSE)),
            entry("savanna", t(OVERWORLD, SAVANNA, HOT, DRY, SPARSE)),
            entry("savanna_plateau", t(OVERWORLD, SAVANNA, PLATEAU, HOT, DRY, SPARSE)),
            entry("windswept_hills", t(OVERWORLD, HILLS, MOUNTAIN, WINDSWEPT, TEMPERATE, SPARSE)),
            entry("windswept_gravelly_hills", t(OVERWORLD, HILLS, MOUNTAIN, WINDSWEPT, TEMPERATE, SPARSE)),
            entry("windswept_forest", t(OVERWORLD, FOREST, HILLS, MOUNTAIN, WINDSWEPT, TEMPERATE, SPARSE)),
            entry("windswept_savanna", t(OVERWORLD, SAVANNA, HILLS, WINDSWEPT, HOT, DRY, SPARSE)),
            entry("jungle", t(OVERWORLD, JUNGLE, FOREST, HOT, WET, DENSE)),
            entry("sparse_jungle", t(OVERWORLD, JUNGLE, FOREST, HOT, WET, SPARSE)),
            entry("bamboo_jungle", t(OVERWORLD, JUNGLE, FOREST, HOT, WET, DENSE)),
            entry("badlands", t(OVERWORLD, BADLANDS, WASTELAND, HOT, DRY, SPARSE, SANDY)),
            entry("eroded_badlands", t(OVERWORLD, BADLANDS, WASTELAND, HOT, DRY, SPARSE, SANDY, RARE)),
            entry("wooded_badlands", t(OVERWORLD, BADLANDS, WASTELAND, HOT, DRY, SPARSE, SANDY)),
            entry("meadow", t(OVERWORLD, PLAINS, MOUNTAIN, TEMPERATE, FLORAL, SPARSE)),
            entry("cherry_grove", t(OVERWORLD, FOREST, MOUNTAIN, TEMPERATE, FLORAL, DECIDUOUS, DENSE)),
            entry("grove", t(OVERWORLD, FOREST, MOUNTAIN, COLD, SNOWY, CONIFEROUS, DENSE)),
            entry("snowy_slopes", t(OVERWORLD, MOUNTAIN, COLD, SNOWY, SPARSE)),
            entry("frozen_peaks", t(OVERWORLD, MOUNTAIN, COLD, SNOWY, ICY, SPARSE)),
            entry("jagged_peaks", t(OVERWORLD, MOUNTAIN, COLD, SNOWY, SPARSE)),
            entry("stony_peaks", t(OVERWORLD, MOUNTAIN, HOT, DRY, SPARSE)),
            entry("river", t(OVERWORLD, RIVER, WET, TEMPERATE)),
            entry("frozen_river", t(OVERWORLD, RIVER, WET, COLD, SNOWY, ICY)),
            entry("beach", t(OVERWORLD, BEACH, WET, TEMPERATE, SANDY, SPARSE)),
            entry("snowy_beach", t(OVERWORLD, BEACH, WET, COLD, SNOWY, SANDY, SPARSE)),
            entry("stony_shore", t(OVERWORLD, BEACH, WET, TEMPERATE, SPARSE)),
            entry("warm_ocean", t(OVERWORLD, OCEAN, WET, HOT)),
            entry("lukewarm_ocean", t(OVERWORLD, OCEAN, WET, TEMPERATE)),
            entry("deep_lukewarm_ocean", t(OVERWORLD, OCEAN, WET, DEEP, TEMPERATE)),
            entry("ocean", t(OVERWORLD, OCEAN, WET, TEMPERATE)),
            entry("deep_ocean", t(OVERWORLD, OCEAN, WET, DEEP, TEMPERATE)),
            entry("cold_ocean", t(OVERWORLD, OCEAN, WET, COLD)),
            entry("deep_cold_ocean", t(OVERWORLD, OCEAN, WET, DEEP, COLD)),
            entry("frozen_ocean", t(OVERWORLD, OCEAN, WET, COLD, ICY)),
            entry("deep_frozen_ocean", t(OVERWORLD, OCEAN, WET, DEEP, COLD, ICY)),
            entry("mushroom_fields", t(OVERWORLD, MUSHROOM, MAGICAL, TEMPERATE, RARE, DENSE)),
            entry("dripstone_caves", t(OVERWORLD, UNDERGROUND, CAVES, TEMPERATE, DRY)),
            entry("lush_caves", t(OVERWORLD, UNDERGROUND, CAVES, LUSH, TEMPERATE, WET, DENSE)),
            entry("deep_dark", t(OVERWORLD, UNDERGROUND, CAVES, SPOOKY, RARE, TEMPERATE)),
            entry("pale_garden", t(OVERWORLD, FOREST, SPOOKY, RARE, TEMPERATE, DECIDUOUS, DENSE)),
            entry("sulfur_caves", t(OVERWORLD, UNDERGROUND, CAVES, HOT, DRY, WASTELAND, RARE)),
            entry("nether_wastes", t(NETHER, HOT, DRY, WASTELAND)),
            entry("crimson_forest", t(NETHER, HOT, DRY, FOREST)),
            entry("warped_forest", t(NETHER, HOT, DRY, FOREST)),
            entry("soul_sand_valley", t(NETHER, HOT, DRY, WASTELAND, SPOOKY)),
            entry("basalt_deltas", t(NETHER, HOT, DRY, WASTELAND)),
            entry("the_end", t(THEEND, DRY, WASTELAND)),
            entry("end_highlands", t(THEEND, DRY, WASTELAND)),
            entry("end_midlands", t(THEEND, DRY, WASTELAND)),
            entry("small_end_islands", t(THEEND, DRY, WASTELAND)),
            entry("end_barrens", t(THEEND, DRY, WASTELAND)),
            entry("the_void", t(VOID, DRY, WASTELAND))
    );

    @Override
    public String name() {
        return "BiomeNameFallbackAnalyzer";
    }

    @Override
    public void analyze(@NotNull Identifier id, @NotNull Biome biome, @NotNull Set<BiomeTrait> resultCollection) {
        final String path = id.getPath().toLowerCase(Locale.ROOT);

        // A vanilla biome is answered exactly from the table, and nothing else is inferred for it.
        if ("minecraft".equals(id.getNamespace())) {
            final var vanilla = VANILLA_TRAITS.get(path);
            if (vanilla != null) {
                resultCollection.addAll(vanilla);
                return;
            }
        }

        addNameBasedTraits(path, resultCollection);
        addClimateTraits(biome, resultCollection);
    }

    /**
     * Name families. Deliberately conservative - a wrong trait is worse than a missing one, because
     * the trait selects which ambience plays.
     */
    private static void addNameBasedTraits(final String path, final Set<BiomeTrait> results) {
        if (path.contains("nether")) {
            results.add(NETHER); results.add(HOT); results.add(DRY);
        }
        if (path.contains("end") && !path.contains("endless")) {
            results.add(THEEND); results.add(DRY);
        }
        if (path.contains("cave") || path.contains("caves") || path.contains("underground")) {
            results.add(UNDERGROUND); results.add(CAVES);
        }
        if (path.contains("sulfur") || path.contains("sulphur")) {
            results.add(OVERWORLD); results.add(UNDERGROUND); results.add(CAVES);
            results.add(HOT); results.add(DRY); results.add(WASTELAND);
        }
        if (path.contains("ocean")) {
            results.add(OVERWORLD); results.add(OCEAN); results.add(WET);
            if (path.contains("deep")) results.add(DEEP);
        }
        if (path.contains("river")) {
            results.add(OVERWORLD); results.add(RIVER); results.add(WET);
        }
        if (path.contains("forest") || path.contains("woods") || path.contains("grove")) {
            results.add(OVERWORLD); results.add(FOREST); results.add(DENSE);
        }
        if (path.contains("taiga") || path.contains("pine") || path.contains("spruce")) {
            results.add(TAIGA); results.add(CONIFEROUS);
        }
        if (path.contains("jungle")) {
            results.add(JUNGLE); results.add(HOT); results.add(WET);
        }
        if (path.contains("swamp") || path.contains("bog") || path.contains("marsh")) {
            results.add(SWAMP); results.add(WET);
        }
        if (path.contains("desert")) {
            results.add(DESERT); results.add(HOT); results.add(DRY); results.add(SANDY);
        }
        if (path.contains("badlands") || path.contains("wasteland")) {
            results.add(BADLANDS); results.add(WASTELAND); results.add(HOT); results.add(DRY);
        }
        if (path.contains("beach") || path.contains("shore")) {
            results.add(OVERWORLD); results.add(BEACH); results.add(WET);
        }
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice")) {
            results.add(COLD); results.add(SNOWY);
        }
        if (path.contains("peak") || path.contains("slope") || path.contains("mountain")) {
            results.add(MOUNTAIN);
        }
        if (path.contains("flower") || path.contains("meadow") || path.contains("cherry")) {
            results.add(FLORAL);
        }
        if (path.contains("dark") || path.contains("ominous") || path.contains("pale")) {
            results.add(SPOOKY);
        }
        if (path.contains("magic") || path.contains("magik")) {
            results.add(MAGICAL);
        }
    }

    /** Climate from the biome's own numbers, so a modded biome still gets a temperature band. */
    private static void addClimateTraits(final Biome biome, final Set<BiomeTrait> results) {
        // BiomeInfo's construction depends on this routine, so it has to read the biome's raw
        // climate rather than go back through BiomeInfo.
        final var climate = ((IBiomeExtended) (Object) biome).dsurround_getWeather();
        final float temperature = climate.temperature();
        final float downfall = climate.downfall();

        if (temperature <= 0.15F)
            results.add(COLD);
        else if (temperature >= 0.95F)
            results.add(HOT);
        else
            results.add(TEMPERATE);

        if (downfall <= 0.15F)
            results.add(DRY);
        else if (downfall >= 0.70F)
            results.add(WET);
    }

    private static Set<BiomeTrait> t(final BiomeTrait... traits) {
        final EnumSet<BiomeTrait> result = EnumSet.noneOf(BiomeTrait.class);
        Collections.addAll(result, traits);
        return result;
    }
}
