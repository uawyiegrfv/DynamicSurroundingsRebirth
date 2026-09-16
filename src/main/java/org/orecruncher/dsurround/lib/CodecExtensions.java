package org.orecruncher.dsurround.lib;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.*;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.lib.block.BlockStateMatcher;
import org.orecruncher.dsurround.lib.block.MatchOnBlockTag;

import java.util.Optional;
import java.util.function.Function;

/**
 * Extensions to the Codec serialization framework.
 */
public interface CodecExtensions<A> extends Codec<A> {

    /**
     * Checks that a string is a valid format specification for BlockState
     */
    static Codec<IMatcher<BlockState>> checkBlockStateSpecification(boolean allowTags) {
        final Function<IMatcher<BlockState>, DataResult<IMatcher<BlockState>>> func = value -> {
            if (!allowTags && value instanceof MatchOnBlockTag)
                return DataResult.error(() -> String.format("Current context does not allow block matching based on tags (%s)", value));
            return DataResult.success(value);
        };
        return BlockStateMatcher.CODEC.flatXmap(func, func);
    }

    static <A> Optional<A> deserialize(String content, Codec<A> codec) {
        return deserializeWithError(content, codec).value();
    }

    /**
     * The result of a decode attempt, together with why it failed.
     *
     * @param value       the decoded value, or empty when decoding failed
     * @param errorDetail the failure reason, or null on success. Callers that know WHICH file the
     *                    content came from record this, so a broken file is reported as
     *                    "file X could not be read: reason" instead of silently having no content.
     */
    record DecodeResult<A>(Optional<A> value, String errorDetail) {
    }

    static <A> DecodeResult<A> deserializeWithError(String content, Codec<A> codec) {
        try {
            var jsonElement = JsonParser.parseString(content);
            var dynamic = new Dynamic<>(JsonOps.INSTANCE, jsonElement);
            DataResult<A> result = codec.parse(dynamic);

            var error = result.error();
            if (error.isPresent())
                return new DecodeResult<>(Optional.empty(), error.get().message());

            // resultOrPartial keeps the "partial" warnings on the log as before
            return new DecodeResult<>(result.resultOrPartial(Library.LOGGER::warn), null);
        } catch (Throwable t) {
            Library.LOGGER.error(t, "Unable to parse input");
            return new DecodeResult<>(Optional.empty(), t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    static <A> Optional<String> serialize(Codec<A> codec, A entity) {
        try {
            // Not intended for high volume. If such a case arises, the builder
            // can be created statically and reused.
            return codec.encode(entity, JsonOps.INSTANCE, JsonOps.INSTANCE.empty()).result()
                    .map(je ->
                            new GsonBuilder()
                                    .setPrettyPrinting()
                                    .create()
                                    .toJson(je)
                    );
        } catch (Throwable t) {
            Library.LOGGER.error(t, "Unable to serialize entity");
        }

        return Optional.empty();
    }
}
