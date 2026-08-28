package org.orecruncher.dsurround.lib.resources;

import com.google.common.collect.ImmutableList;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraft.server.packs.PackType;
import org.orecruncher.dsurround.lib.Library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

public class ResourceLookupHelper {

    private final PackType packType;

    private Collection<Path> rootPaths;

    public ResourceLookupHelper(PackType packType) {
        this.packType = packType;
        this.rootPaths = ImmutableList.of();
    }

    public void refresh() {
        this.rootPaths = this.getResourceRootPaths();
    }

    public Collection<Path> findResourcePaths(String fileNamePattern) {
        // On a dedicated client (single player / no remote server) the SERVER_DATA
        // root paths are legitimately absent; this fired a WARN every tag lookup and
        // flooded the log. It is expected, so drop it to debug.
        if (this.rootPaths.isEmpty())
            Library.LOGGER.debug("No root paths defined for ResourceLookupHelper");

        return this.rootPaths.stream()
                .map(path -> this.findPath(fileNamePattern, path))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<Path> findPath(String fileNamePattern, Path root) {
        Path path = root.resolve(fileNamePattern.replace("/", root.getFileSystem().getSeparator()));
        if (Files.exists(path))
            return Optional.of(path);
        return Optional.empty();
    }

    private Collection<Path> getResourceRootPaths() {
        var pathPrefix = this.packType.getDirectory();
        return ModList.get().getMods()
                .stream()
                .map(mod -> findPath(mod, pathPrefix))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    // 1.20.1: jar file systems must stay open - a try-with-resources closes the fs and
    // invalidates the returned Path (ClosedFileSystemException on later access, e.g. the
    // resource reload on language switch). Cache the mount for the JVM lifetime.
    private static final java.util.Map<Path, java.nio.file.FileSystem> FS_CACHE = new java.util.HashMap<>();

    private static synchronized java.nio.file.FileSystem mount(Path jarPath) {
        return FS_CACHE.computeIfAbsent(jarPath, p -> {
            try {
                return java.nio.file.FileSystems.newFileSystem(p, (ClassLoader) null);
            } catch (final Exception ex) {
                Library.LOGGER.error(ex, "Unable to open mod jar file system %s", p);
                return null;
            }
        });
    }

    static private Optional<Path> findPath(IModInfo container, String file)
    {
        var modFile = container.getOwningFile().getFile();
        var jarPath = modFile.getFilePath();
        // Dev environment: the mod's file path is a plain directory (build/classes or
        // build/resources) - use it directly. A jar path needs a file system mount.
        if (Files.isDirectory(jarPath)) {
            var p = jarPath.resolve(file.replace("/", jarPath.getFileSystem().getSeparator()));
            if (Files.exists(p))
                return Optional.of(p);
            return Optional.empty();
        }
        // Traverse the mod jar's own file system (1.20.1 has no getRootPaths() on SecureJar).
        // FileSystems.newFileSystem throws ProviderNotFoundException (a RuntimeException,
        // not IOException) for unsupported schemes such as file:.
        var fs = mount(jarPath);
        if (fs == null)
            return Optional.empty();
        var root = fs.getRootDirectories().iterator().next();
        var p = root.resolve(file.replace("/", fs.getSeparator()));
        if (Files.exists(p))
            return Optional.of(p);

        return Optional.empty();
    }
}