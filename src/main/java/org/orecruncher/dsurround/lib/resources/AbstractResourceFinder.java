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

    protected <T> Optional<T> decode(ResourceLocation location, String content, Codec<T> decoder) {
        this.logger.debug(RESOURCE_LOADING, "[%s] - Decoding resource", location);
        final var result = CodecExtensions.deserializeWithError(content, decoder);

        // A file that fails to decode once behaved exactly like a file that was never there: the
        // mod carried on with no data and nothing said so outside a debug line. Record it so the
        // self-check can name the file and the reason.
        if (result.value().isEmpty() && result.errorDetail() != null)
            DataDiagnostics.fail("undecodable file",
                    String.format("%s: %s", location, result.errorDetail()));

        if (this.logger.isTracing(RESOURCE_LOADING)) {
            if (result.value().isPresent())
                this.logger.debug(RESOURCE_LOADING, "[%s] - Content successfully decoded", location);
            else
                this.logger.debug(RESOURCE_LOADING, "[%s] - Content could not be decoded", location);
        }

        return result.value();
    }
}
