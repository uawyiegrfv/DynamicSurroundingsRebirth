package org.orecruncher.dsurround.config.libraries.impl;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.config.Variator;
import org.orecruncher.dsurround.config.libraries.IReloadEvent;
import org.orecruncher.dsurround.config.libraries.ILibrary;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;
import org.orecruncher.dsurround.lib.resources.ResourceUtilities;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads the per-entity footstep variators from variators.json, ported from the original
 * 1.12.2 mcp.json variators section.
 */
public class VariatorLibrary implements ILibrary {

    private static final Codec<Map<String, Variator>> CODEC = Codec.unboundedMap(Codec.STRING, Variator.CODEC);
    private static final String FILE_NAME = "variators.json";
    private static final Codec<Map<String, String>> ENTITY_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final String ENTITY_FILE_NAME = "entity_variators.json";

    private final IModLog logger;
    private Map<String, Variator> variators = Map.of();
    private Map<String, String> entityVariators = Map.of();
    private int version;

    public VariatorLibrary(IModLog logger) {
        this.logger = ModLog.createChild(logger, "Variators");
    }

    @Override
    public void reload(ResourceUtilities resourceUtilities, IReloadEvent.Scope scope) {
        this.version++;
        if (scope == IReloadEvent.Scope.TAGS)
            return;

        Map<String, Variator> loaded = new HashMap<>();
        var findResults = resourceUtilities.findModResources(CODEC, FILE_NAME);
        findResults.forEach(result -> loaded.putAll(result.resourceContent()));
        this.variators = loaded;

        Map<String, String> loadedEntities = new HashMap<>();
        var entityResults = resourceUtilities.findModResources(ENTITY_CODEC, ENTITY_FILE_NAME);
        entityResults.forEach(result -> loadedEntities.putAll(result.resourceContent()));
        this.entityVariators = loadedEntities;

        this.logger.info("[Variators] %d variators, %d entity assignments loaded; version is now %d",
                this.variators.size(), this.entityVariators.size(), this.version);
    }

    public Variator getVariator(String name) {
        return this.variators.getOrDefault(name, Variator.DEFAULT);
    }

    /** Resolves the variator name assigned to an entity type (entity_variators.json), if any. */
    public Optional<String> getEntityVariator(ResourceLocation entityTypeId) {
        return Optional.ofNullable(this.entityVariators.get(entityTypeId.toString()));
    }

    public Variator getPlayerVariator() {
        return getVariator("player");
    }

    @Override
    public Stream<String> dump() {
        return this.variators.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .sorted();
    }
}
