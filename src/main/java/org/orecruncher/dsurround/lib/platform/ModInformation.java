package org.orecruncher.dsurround.lib.platform;

import net.minecraft.SharedConstants;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.version.SemanticVersion;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class ModInformation implements IMinecraftDirectories {

    // TODO: Move into external resources?
    // Version-check URL: points at the ported mod's own update feed (create a
    // versions.json in the repo root, mirroring the upstream format).
    private static final URI modUpdate = URI.create("https://raw.githubusercontent.com/uawyiegrfv/DynamicSurroundingsRebirth/main/versions.json");
    private static final String modCurseForge = "https://www.curseforge.com/minecraft/mc-mods/dynamic-surroundings-rebirth";
    private static final String modModrinth = "https://modrinth.com/mod/dynamic-surroundings-rebirth";

    private final String modId;
    private final String displayName;
    private final SemanticVersion version;

    private final Path modConfigDirectory;
    private final Path modDataDirectory;
    private final Path modDumpDirectory;

    public ModInformation(String modId, String displayName, SemanticVersion version) {
        this.modId = modId;
        this.displayName = displayName;
        this.version = version;
        this.modConfigDirectory = getConfigPath(modId);
        this.modDataDirectory = this.modConfigDirectory.resolve("configs");
        this.modDumpDirectory = this.modConfigDirectory.resolve("dumps");

        createPath(this.modDataDirectory);
        createPath(this.modDumpDirectory);
    }

    public String modId() {
        return this.modId;
    }

    public String displayName() {
        return this.displayName;
    }

    public SemanticVersion version() {
        return this.version;
    }

    public Path getModConfigDirectory() {
        return this.modConfigDirectory;
    }

    public Path getModDataDirectory() {
        return this.modDataDirectory;
    }

    public Path getModDumpDirectory() {
        return this.modDumpDirectory;
    }

    public Optional<URL> getUpdateUrl() {
        try {
            return Optional.of(modUpdate.toURL());
        } catch (MalformedURLException ignored) {
        }
        return Optional.empty();
    }

    public String curseForgeLink() {
        return modCurseForge;
    }

    public String modrinthLink() {
        return modModrinth;
    }

    public String getBranding() {
        return String.format("%s %s-%s", this.displayName, SharedConstants.getCurrentVersion().getName(), this.version);
    }

    public static Optional<ModInformation> getModInformation(String modId) {
        return PlatformCompat.getModContainer(modId)
                .flatMap(container -> {
                    try {
                        var info = container.getModInfo();
                        var displayName = info.getDisplayName();
                        var version = parseVersionSafely(info.getVersion().toString());
                        return Optional.of(new ModInformation(modId, displayName, version));
                    } catch (Throwable t) {
                        Library.LOGGER.error(t, "Unable to build mod information for %s", modId);
                        return Optional.empty();
                    }
                });
    }

    // ForgeGradle userdev reports "0.0NONE" as the mod version (no jar manifest);
    // fall back to 0.0.0 instead of failing the whole mod.
    private static SemanticVersion parseVersionSafely(String versionString) {
        try {
            return SemanticVersion.parse(versionString);
        } catch (Throwable t) {
            return new SemanticVersion(0, 0, 0, new String[0], new String[0]);
        }
    }

    public static Optional<SemanticVersion> getMinecraftVersion() {
        return getModVersion("minecraft");
    }

    public static Optional<SemanticVersion> getModVersion(String namespace) {
        var container = PlatformCompat.getModContainer(namespace);
        if (container.isPresent()) {
            try {
                var version = container.get().getModInfo().getVersion().toString();
                return Optional.of(SemanticVersion.parse(version));
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }

    public static Optional<String> getModDisplayName(String modId) {
        var container = PlatformCompat.getModContainer(modId);
        if (container.isPresent()) {
            try {
                return Optional.of(container.get().getModInfo().getDisplayName());
            } catch (Throwable t) {
                Library.LOGGER.error(t, "Unable to get display name for %s", modId);
            }
        }
        return Optional.empty();
    }

    public static Path getConfigPath(final String modId) {
        var configDir = PlatformCompat.getConfigFolder();
        var configPath = configDir.resolve(Objects.requireNonNull(modId));

        if (Files.notExists(configPath))
            try {
                Files.createDirectory(configPath);
            } catch (final IOException ex) {
                Library.LOGGER.error(ex, "Unable to create directory path %s", configPath.toString());
                configPath = configDir;
            }

        return configPath;
    }

    private static void createPath(final Path path) {
        try {
            Files.createDirectories(path);
        } catch (final Throwable t) {
            Library.LOGGER.error(t, "Unable to create data path %s", path.toString());
        }
    }
}
