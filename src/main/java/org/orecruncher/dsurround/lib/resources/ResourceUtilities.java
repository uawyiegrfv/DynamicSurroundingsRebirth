package org.orecruncher.dsurround.lib.resources;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagFile;
import net.minecraft.tags.TagKey;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.platform.IMinecraftDirectories;

import static org.orecruncher.dsurround.Configuration.Flags.RESOURCE_LOADING;

import java.util.*;

@SuppressWarnings("unused")
public final class ResourceUtilities {

    private final ModConfigResourceFinder modConfigHelper;
    private final DiskResourceFinder diskResourceHelper;
    private final ClientResourceFinder resourceFinder;
    private final ServerResourceFinder packResourceFinder;
    private final IModLog logger;

    ResourceUtilities(IModLog modLog, IMinecraftDirectories minecraftDirectories, ResourceManager resourceManager) {
        this.logger = modLog;
        this.modConfigHelper = new ModConfigResourceFinder(modLog, resourceManager, "dsconfigs");
        this.diskResourceHelper = new DiskResourceFinder(modLog, minecraftDirectories.getModDataDirectory());
        this.resourceFinder = new ClientResourceFinder(modLog, resourceManager);
        this.packResourceFinder = new ServerResourceFinder(modLog);
    }

    /**
     * <p>
     * Scans the local disk as well as resource packs and JARs locating and creating accessors for the resource file
     * in question.
     * </p>
     * <p>
     * For mods, it is assumed that the resources will be found in the "dsconfigs" within the mod's assets
     * folder (ex. minecraft:dsconfigs/*.json).
     * </p>
     * <p>
     * For disk, it is assumed to be in the "dsurround/configs" folder in the configuration directory. Each folder
     * within will be named based on a mod's namespace. For example, if a folder is called "minecraft", those assets
     * within will be loaded if "minecraft" mod is loaded (which is always the case).
     * (Example: .minecraft/assetPath/dsurround/configs/minecraft/*.json)
     * </p>
     * @param assetPath Path to the asset that is of interest
     * @return A collection of resource accessors that match the assetPath criteria
     */
    public <T> Collection<DiscoveredResource<T>> findModResources(Codec<T> codec, final String assetPath) {
        var result = new ObjectArray<DiscoveredResource<T>>();
        result.addAll(this.modConfigHelper.find(codec, assetPath));
        result.addAll(this.diskResourceHelper.find(codec, assetPath));
        return result;
    }

    /**
     * Same as {@link #findModResources(Codec, String)} but additionally accepts the per-namespace
     * aggregate file `&lt;namespace&gt;/dsurround.json`, whose single {@code section} is decoded with
     * the same codec. This restores the original 1.12.2 convenience of writing one file per mod
     * instead of one file per data type.
     *
     * <p>Precedence: for a given namespace, a dedicated `&lt;section&gt;.json` wins over the section
     * inside the aggregate file. That keeps the built-in data authoritative for the namespaces that
     * ship it while allowing mods and users to adapt everything else in one place.
     *
     * @param assetPath the dedicated file name, e.g. "blocks"; used both to look up that file and
     *                  as the section key inside the aggregate file
     */
    public <T> Collection<DiscoveredResource<T>> findModResources(final Codec<T> codec, final String assetPath,
                                                                  final boolean includeAggregate) {
        final String section = assetPath.endsWith(".json")
                ? assetPath.substring(0, assetPath.length() - ".json".length())
                : assetPath;

        final var dedicated = findModResources(codec, assetPath);
        if (!includeAggregate)
            return dedicated;

        // The dedicated file has already been read by the mods' asset finder, so its namespace is
        // authoritative and the aggregate lookup must not decode it a second time.
        final var aggregate = new ObjectArray<DiscoveredResource<T>>();
        final var seen = new HashSet<String>();
        dedicated.forEach(r -> seen.add(r.namespace()));

        for (final var finder : new IResourceFinder[] { this.modConfigHelper, this.diskResourceHelper }) {
            for (final var r : AggregateDataFile.find(finder, this.logger, codec, section, null)) {
                if (seen.add(r.namespace())) {
                    this.logger.debug(RESOURCE_LOADING, "[%s] - '%s' taken from the aggregate %s of namespace %s",
                            assetPath, section, AggregateDataFile.FILE_NAME, r.namespace());
                    aggregate.add(r);
                }
            }
        }

        var result = new ObjectArray<DiscoveredResource<T>>();
        result.addAll(dedicated);
        result.addAll(aggregate);
        return result;
    }

    /**
     * <p>
     * Scans resource packs locating resource files. The resource path is relative to the root of a mods asset
     * folder (example: minecraft:resourcePath).
     *<p>
     * If a mod is not loaded, resource locations with that namespace will be filtered out.  For example, if
     * there was an asset folder "assets/modnotfound/dsconfig" it would be ignored.
     *
     * @param assetPath The path of the asset to find within the various resource locations
     * @return Collection of accessors to retrieve resource configurations.
     */
    public <T> Collection<DiscoveredResource<T>> findResources(Codec<T> codec, String assetPath) {
        return this.resourceFinder.find(codec, assetPath);
    }

    /**
     * Processes local tag definitions from resource packs and mods. Since Dynamic Surroundings is
     * a client mod special tagging is lost when connecting to a remote server. This routine helps
     * backfill that knowledge gap.
     *
     * @param tagKey Tag key instance that is the subject of the search
     * @return Collection of TagFile instances that were found
     */
    public Collection<TagFile> findClientTagFiles(TagKey<?> tagKey) {
        var result = new ObjectArray<DiscoveredResource<TagFile>>();
        var tagDir = Registries.tagsDirPath(tagKey.registry());
        var tagFolder = "%s/%s".formatted(tagDir, tagKey.location().getPath());
        var tagFolderPack = "%s:%s".formatted(tagKey.location().getNamespace(), tagFolder);
        result.addAll(this.packResourceFinder.find(TagFile.CODEC, tagFolderPack));
        result.addAll(this.modConfigHelper.find(TagFile.CODEC, tagFolder));
        result.addAll(this.diskResourceHelper.find(TagFile.CODEC, tagFolder));
        return result.stream().map(DiscoveredResource::resourceContent).toList();
    }

    public static ResourceUtilities createForCurrentState() {
        return createForResourceManager(GameUtils.getMC().getResourceManager());
    }

    public static ResourceUtilities createForResourceManager(ResourceManager resourceManager) {
        var logger = ContainerManager.resolve(IModLog.class);
        var directories = ContainerManager.resolve(IMinecraftDirectories.class);
        return new ResourceUtilities(logger, directories, resourceManager);
    }
}
