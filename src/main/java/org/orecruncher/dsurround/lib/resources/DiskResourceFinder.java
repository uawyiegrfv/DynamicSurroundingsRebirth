package org.orecruncher.dsurround.lib.resources;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import dev.architectury.platform.Platform;
import net.minecraft.resources.Identifier;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

import static org.orecruncher.dsurround.Configuration.Flags.RESOURCE_LOADING;

public class DiskResourceFinder extends AbstractResourceFinder {

    private final Collection<Path> namespacesOnDisk = new ObjectArray<>();

    public DiskResourceFinder(IModLog logger, Path diskLocation) {
        super(logger);

        // List the folders on disk and validate against loaded mods.
        try (var directoryList = Files.newDirectoryStream(diskLocation, Files::isDirectory)) {
            directoryList.forEach(p -> {
                var modNamespace = p.getFileName().toString();
                if (Platform.isModLoaded(modNamespace))
                    this.namespacesOnDisk.add(p);
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public <T> Collection<DiscoveredResource<T>> find(Codec<T> codec, String assetPath) {

        // Fast optimization. The vast majority of users will not have local config information.
        if (this.namespacesOnDisk.isEmpty())
            return ImmutableList.of();

        Collection<DiscoveredResource<T>> result = new ObjectArray<>();

        var fileName = assetPath;
        if (!fileName.endsWith(".json"))
            fileName = fileName + ".json";

        // Namespaces on disk should have been collected/pruned so what remains
        // is what needs to be checked.
        for (var path : this.namespacesOnDisk) {
            var filePath = Paths.get(path.toString(), fileName);
            if (Files.exists(filePath)) {
                this.logger.debug(RESOURCE_LOADING, "[%s] - Processing %s file from disk", assetPath, filePath.toString());
                var namespace = path.getFileName().toString();
                var location = Identifier.fromNamespaceAndPath(namespace, assetPath);
                try {
                    var content = Files.readString(filePath);
                    this.decode(location, content, codec).ifPresent(e -> result.add(new DiscoveredResource<>(namespace, e)));
                    this.logger.debug(RESOURCE_LOADING, "[%s] - Completed decode of %s", assetPath, filePath);
                } catch (Throwable t) {
                    this.logger.error(t, "[%s] Unable to read resource stream for path %s", assetPath, location);
                }
            }
        }

        return result;
    }
    @Override
    public Collection<RawTextResource> findRaw(final String path) {
        if (this.namespacesOnDisk.isEmpty())
            return ImmutableList.of();

        final String fileName = path.endsWith(".json") ? path : path + ".json";
        final Collection<RawTextResource> result = new ObjectArray<>();
        for (var dir : this.namespacesOnDisk) {
            var filePath = Paths.get(dir.toString(), fileName);
            if (!Files.exists(filePath))
                continue;
            try {
                result.add(new RawTextResource(dir.getFileName().toString(), filePath.toString(),
                        Files.readString(filePath)));
            } catch (Throwable t) {
                this.logger.error(t, "[%s] - Unable to read %s", path, filePath);
            }
        }
        return result;
    }
}