package org.orecruncher.dsurround.lib.resources;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.lib.CodecExtensions;
import org.orecruncher.dsurround.lib.diagnostics.DataDiagnostics;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;

import java.util.Optional;

import static org.orecruncher.dsurround.Configuration.Flags.RESOURCE_LOADING;

public abstract class AbstractResourceFinder implements IResourceFinder {

    protected final IModLog logger;

    protected AbstractResourceFinder(IModLog logger) {
        this.logger = ModLog.createChild(logger, "ResourceFinder");
    }

    /**
     * True when a resource path matches the requested path EXACTLY.
     *
     * <p>A plain {@code endsWith(requested)} is not enough: it makes {@code blocks.json} match
     * {@code dried_mud_blocks.json} and {@code variators.json} match {@code entity_variators.json},
     * so those files get decoded with a codec meant for something else and fail - pointing the blame
     * at data files that are perfectly valid.
     *
     * <p>Rules:
     * <ul>
     *   <li>a namespace-qualified request must equal the whole resource id
     *       ({@code dsurround:tags/block/effects/x.json});</li>
     *   <li>otherwise the resource path must equal the request, or sit under a directory:
     *       {@code blocks.json} matches {@code blocks.json} and {@code sub/blocks.json}, but never
     *       {@code dried_mud_blocks.json}.</li>
     * </ul>
     * The tag lookup relies on the directory form: it asks for
     * {@code tags/block/effects/foot_overlay.json} while the real path is
     * {@code dsconfigs/tags/block/effects/foot_overlay.json}.
     */
    protected static boolean resourcePathMatches(final String resourcePath, final String requested) {
        if (resourcePath.equals(requested))
            return true;
        return resourcePath.endsWith("/" + requested);
    }

    protected <T> Optional<T> decode(ResourceLocation location, String content, Codec<T> decoder) {
        this.logger.debug(RESOURCE_LOADING, "[%s] - Decoding resource", location);
        final var result = CodecExtensions.deserializeWithError(content, decoder);

        // A file that fails to decode once behaved exactly like a file that was never there: the
        // mod carried on with no data and nothing said so outside a debug line. Record it so the
        // self-check can name the file and the reason.
        if (result.value().isEmpty() && result.errorDetail() != null) {
            DataDiagnostics.fail("undecodable file",
                    String.format("%s: %s", location, result.errorDetail()));
            // Also name the codec that rejected it: the same file is read by several loaders, and
            // "which codec" is what makes a failure actionable (a tag read as an array, say).
            this.logger.warn("Decode failure for %s using %s", location, decoder);
        }

        if (this.logger.isTracing(RESOURCE_LOADING)) {
            if (result.value().isPresent())
                this.logger.debug(RESOURCE_LOADING, "[%s] - Content successfully decoded", location);
            else
                this.logger.debug(RESOURCE_LOADING, "[%s] - Content could not be decoded", location);
        }

        return result.value();
    }
}
