package org.orecruncher.dsurround.lib.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.util.Collection;

/**
 * Support for ONE data file per namespace carrying every section of the mod's data.
 *
 * <p>The mod's own data is split by TYPE (`dsconfigs/blocks.json`, `dsconfigs/biomes.json`,
 * `dsconfigs/dimensions.json`, ...) because that is the natural shape for the built-in content.
 * Writing an adapter for a third-party mod that way means spreading that mod's entries across half
 * a dozen files. The original 1.12.2 mod instead used ONE file per mod
 * (`data/biomesoplenty.json`) holding every section, and this class restores that convenience:
 *
 * <pre>
 * config/dsurround/configs/&lt;namespace&gt;/dsurround.json
 * {
 *   "blocks": [ { "blocks": ["somemod:baz"], "effects": [ { "effect": "fire_jet", "spawnChance": "0.005" } ] } ],
 *   "biomes": [ { "biomeSelector": "biome.id == 'somemod:magic'", "acoustics": [ { "factory": "biome.wind" } ] } ]
 * }
 * </pre>
 *
 * <p>Each section is decoded with the SAME codec the dedicated file uses, so an entry means exactly
 * the same thing in either place. A section that is absent, null or of the wrong shape contributes
 * nothing and is reported in the log instead of breaking the load: a hand-written file must never
 * be able to break the mod's data with a single typo.
 */
public final class AggregateDataFile {

    /** The name of the per-namespace aggregate file. */
    public static final String FILE_NAME = "dsurround.json";

    private AggregateDataFile() {
    }

    /**
     * Reads one section out of every aggregate file the finder can see.
     *
     * @param finder    the finder to read through (the mods' assets, the disk config folder, packs)
     * @param logger    destination for diagnostics
     * @param codec     the section's codec - the same one the dedicated file uses
     * @param section   the section key inside the aggregate file (e.g. "blocks")
     * @param namespace the only namespace to accept, or null for all of them
     * @param <T>       the section type
     * @return the decoded sections; empty when no namespace carries an aggregate file
     */
    public static <T> Collection<DiscoveredResource<T>> find(final IResourceFinder finder,
                                                             final IModLog logger,
                                                             final Codec<T> codec,
                                                             final String section,
                                                             final String namespace) {
        final Collection<DiscoveredResource<T>> result = new ObjectArray<>();

        for (final RawTextResource file : finder.findRaw(FILE_NAME)) {
            if (namespace != null && !namespace.equals(file.namespace()))
                continue;

            final JsonElement root;
            try {
                root = JsonParser.parseString(file.content());
            } catch (Throwable t) {
                logger.warn("[%s] - %s is not valid JSON; ignoring it", file.path(), FILE_NAME);
                continue;
            }
            if (!root.isJsonObject())
                continue;

            final JsonElement sectionElement = root.getAsJsonObject().get(section);
            if (sectionElement == null || sectionElement.isJsonNull())
                continue;

            final var parsed = codec.parse(JsonOps.INSTANCE, sectionElement);
            if (parsed.error().isPresent()) {
                logger.warn("[%s] - section '%s' of %s could not be read: %s",
                        file.path(), section, FILE_NAME, parsed.error().get().message());
                continue;
            }
            parsed.result().ifPresent(value -> result.add(new DiscoveredResource<>(file.namespace(), value)));
        }

        return result;
    }
}
