package org.orecruncher.dsurround.lib.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.IMatcher;
import org.orecruncher.dsurround.lib.IdentityUtils;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.diagnostics.DataDiagnostics;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public abstract class BlockStateMatcher implements IMatcher<BlockState> {

    private static final IModLog LOGGER = Library.LOGGER;

    public static final Codec<IMatcher<BlockState>> CODEC = Codec.STRING
            .comapFlatMap(
                    BlockStateMatcher::manifest,
                    IMatcher::toString).stable();

    public static final String TAG_TYPE = "#";

    private static DataResult<IMatcher<BlockState>> manifest(String blockId) {
        try {
            return DataResult.success(create(blockId, true, true));
        } catch (Throwable t) {
            // An unknown block id or tag must NOT fail the decode. The surrounding
            // Codec.list(...) aborts the WHOLE list as soon as a single element fails, and
            // the resource loader then throws the entire file away (deserialize returns
            // empty on an error result). A single stale entry - someone copying a rule
            // between game versions, or a typo in a mod's own dsconfigs file - would
            // therefore silently disable every other rule in that file, vanilla mappings
            // included, with nothing but a warn line to show for it.
            // Keep the entry, make it match nothing, and say so loudly.
            LOGGER.warn("Unable to resolve block specification '%s' on this version; the entry will never match: %s",
                    blockId, t.getMessage());
            DataDiagnostics.fail("unresolvable block in a rule", blockId);
            return DataResult.success(new MatchOnNothing(blockId));
        }
    }

    /**
     * A block specification that could not be resolved against this game version's block
     * registry. It is deliberately kept in place - so it survives encode/decode round trips
     * and still shows up in the configuration dumps - but matches nothing.
     */
    private record MatchOnNothing(String specification) implements IMatcher<BlockState> {

        @Override
        public boolean match(BlockState state) {
            return false;
        }

        @Override
        public String toString() {
            return this.specification;
        }
    }

    public static IMatcher<BlockState> asGeneric(final BlockState state) {
        return create(state.getBlock());
    }

    public static IMatcher<BlockState> create(final BlockState state) {
        return new MatchOnBlockState(state);
    }

    public static IMatcher<BlockState> create(final Block block) {
        return new MatchOnBlock(block);
    }

    public static IMatcher<BlockState> create(final String blockId) throws BlockStateParseException {
        return create(blockId, true, true);
    }

    public static IMatcher<BlockState> create(final String blockId, boolean allowTags, boolean allowMaterials) throws BlockStateParseException {
        if (blockId.startsWith(TAG_TYPE))
            if (allowTags)
                return createTagMatcher(blockId);
            else
                throw new BlockStateParseException(String.format("Block id %s is for a tag, and it is not permitted in this context", blockId));
        return createBlockStateMatcher(BlockStateParser.parse(blockId));
    }

    private static BlockStateMatcher createTagMatcher(String tagId) throws BlockStateParseException {
        try {
            var id = IdentityUtils.resolveIdentifier(Constants.MOD_ID, tagId);
            return new MatchOnBlockTag(id);
        } catch (Throwable ignored) {
            throw new BlockStateParseException(String.format("%s is not a valid block tag", tagId));
        }
    }

    private static BlockStateMatcher createBlockStateMatcher(final BlockStateParser.ParseResult result) throws BlockStateParseException {
        final Block block = result.getBlock();
        final BlockState defaultState = block.defaultBlockState();
        final StateDefinition<Block, BlockState> container = block.getStateDefinition();
        if (container.getPossibleStates().size() == 1) {
            // Easy case - it's always an identical match because there are no other properties
            return new MatchOnBlock(defaultState.getBlock());
        }

        if (!result.hasProperties()) {
            // No property specification so this is a generic
            return new MatchOnBlock(block);
        }

        final Map<String, String> properties = result.getProperties();
        final Map<Property<?>, Comparable<?>> props = new IdentityHashMap<>(properties.size());

        // Blow out the property list
        for (final Map.Entry<String, String> entry : properties.entrySet()) {
            final String s = entry.getKey();
            final Property<?> prop = container.getProperty(s);
            if (prop != null) {
                final Optional<?> optional = prop.getValue(entry.getValue());
                if (optional.isPresent()) {
                    props.put(prop, (Comparable<?>) optional.get());
                } else {
                    var msg = String.format("Value '%s' for property '%s' not found for block '%s'", entry.getValue(), s, result.getBlockName());
                    throw new BlockStateParseException(msg);
                }
            } else {
                var msg = String.format("Property %s not found for block %s", s, result.getBlockName());
                throw new BlockStateParseException(msg);
            }
        }

        return new MatchOnBlockState(defaultState, new BlockStateProperties(props));
    }

    public abstract boolean isEmpty();

    public abstract boolean match(BlockState state);

}