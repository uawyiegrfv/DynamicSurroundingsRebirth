package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.collections.ObjectArray;

/**
 * Every place near the player that rain can land, walked on demand.
 *
 * <p>This used to be a {@code CuboidScanner}: it walked the same box a slice
 * at a time, published results double-buffered when a pass completed, and
 * delta-patched the published set when the player moved. All of that exists to
 * amortise ~2900 block reads over ~20 ticks, and all of it is a lie about
 * freshness. A pass takes several seconds once the layer only gets ticked five
 * times a second, and while the player walks the pass in flight belongs to the
 * cuboid centred where he was when it started - so completion publishes
 * coordinates from a box centred somewhere else. The fifth log has this layer
 * anchor rain twelve blocks up out of a box reaching eight, which is not
 * reachable by any correct scan of the current box no matter how it is tuned;
 * it is reachable only by answering a question that was asked seconds ago.</p>
 *
 * <p>So the set is computed when it is needed, from where the player is now.
 * There is no pass to be mid-way through, no buffer to swap, and no second
 * copy of "is this position still in range" that can disagree with the first.
 * The cost is a spike of block reads instead of twenty slices of them; at one
 * scan a second it is noise next to what a tick of Minecraft does anyway, and
 * the maintainer has asked for effect first and performance after.</p>
 */
public final class MaterialSurfaceScanner {

    /** Horizontal reach. Six, not eight: reach is the cheap thing to give up. */
    public static final int HORIZONTAL_RANGE = 6;
    /**
     * Vertical reach, symmetric. The deeper downward window the very first
     * hand-rolled scan had existed to reach water below the player; water is
     * parked until there is a clip for it, and it can have an asymmetric
     * window back if it ever comes back.
     */
    public static final int VERTICAL_RANGE = 8;

    /**
     * Which materials this layer is allowed to hear at all, indexed by the
     * value {@link #classify} returns: glass, metal, water, wood, log, stone,
     * leaves.
     *
     * <p>Ninth log, and this is a cut rather than a tuning pass: he stood in
     * rain over stone, beside water and under leaves, said wood was all right
     * and the other three were not, and asked for stone, water and leaves to
     * go. They go at the source - a material switched off here is never
     * collected by the scan, so it cannot be picked, and a loop of it already
     * sounding fails its next validity check and fades instead of lingering.</p>
     *
     * <p>Why each of the three is off, because the reasons are not the same
     * and "turn it back on" is a different job for each: stone has now failed
     * twice, the eighth round's re-cut included, and no clip we have reads as
     * rain on stone to him; leaves was never a tuning problem, its source is
     * a continuous rustle with no impact in it to begin with; water has been
     * parked since before it had a clip and still does not have one.</p>
     *
     * <p>Wood and log stay on together: they are one material to the ear and
     * one SoundType to the game, split only by the vanilla log tag, and a
     * village is planks and logs in the same wall. Turning log off is one
     * entry here.</p>
     *
     * <p>Bringing one back is this array and nothing else - the assets, the
     * sound events and the tier lookup were all left in place.</p>
     */
    public static final boolean[] MATERIAL_ENABLED = {
            true,   // glass
            true,   // metal
            false,  // water
            true,   // wood
            true,   // log
            false,  // stone
            false,  // leaves
    };

    private MaterialSurfaceScanner() {
    }

    /**
     * Every surface in the box around {@code center} that rain reaches, now.
     *
     * <p>{@code getBlockState} answers air outside the loaded chunks, so an
     * unloaded edge of the box costs a hundred and sixty-nine lookups and adds
     * nothing - there is no loaded-chunk test to get wrong, and nothing here
     * can force a chunk to load.</p>
     *
     * <p>The vertical bound is asked of {@code isOutsideBuildHeight} rather
     * than of a min/max accessor, because those two are not called the same
     * thing in every version: {@code getMinBuildHeight} in 1.20.1 and 1.21.1,
     * {@code getMinY} in 26.1, and the shared {@code ScanContext} already
     * carries one version of each. Skipping what is out of bounds is the same
     * answer written once.</p>
     */
    public static ObjectArray<Surface> scan(final Level world, final BlockPos center) {
        final ObjectArray<Surface> result = new ObjectArray<>(64);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -HORIZONTAL_RANGE; dx <= HORIZONTAL_RANGE; dx++) {
            for (int dz = -HORIZONTAL_RANGE; dz <= HORIZONTAL_RANGE; dz++) {
                final int x = center.getX() + dx;
                final int z = center.getZ() + dz;
                for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
                    final int y = center.getY() + dy;
                    if (world.isOutsideBuildHeight(y))
                        continue;
                    cursor.set(x, y, z);
                    final BlockState state = world.getBlockState(cursor);
                    // Air is most of this box. Thirteen columns wide and
                    // seventeen deep around a player standing in the open is
                    // mostly sky, and asking it first means the ignore list
                    // and the material question - the two things that cost
                    // something - are only asked of the blocks that might be
                    // something. The handler times every scan and prints it.
                    if (state.isAir())
                        continue;
                    if (Constants.BLOCKS_TO_IGNORE.contains(state.getBlock()))
                        continue;
                    final int material = classify(state);
                    if (material < 0)
                        continue;
                    // Only asked of blocks that already matched, so it is not
                    // a cost carried by every block in the box.
                    if (!rainReaches(world, cursor))
                        continue;
                    result.add(new Surface(cursor.immutable(), material));
                }
            }
        }
        return result;
    }

    /**
     * Whether rain actually reaches the face on top of {@code pos}.
     *
     * <p>"The block above is air" was the first version of this test, and it
     * holds for the ceiling of a cave eight blocks under a grass field:
     * there is air above that stone too. Every hidden expanse inside the box
     * therefore qualified as somewhere rain was landing, and standing in
     * open grass produced rain on stone because a mine was doing the
     * listening.</p>
     *
     * <p>Heightmap visibility asks the right question - if the sky cannot see
     * it, rain cannot reach it. It is the same call {@code PlayerVariables}
     * already makes to decide whether the player himself is getting wet.</p>
     */
    public static boolean rainReaches(final Level world, final BlockPos pos) {
        return world.canSeeSky(pos.above());
    }

    /**
     * Which of our materials this block is, or -1 if it is none of them or if
     * that one is switched off.
     *
     * <p>The one answer to "what is this block", asked by the scan and by the
     * handler's per-tick validity check alike. Those used to be two copies of
     * the same question, and the third log is what two copies cost: a spot
     * one of them accepted and the other rejected was picked, killed, picked
     * again, six times on the same coordinate.</p>
     */
    public static int classify(final BlockState state) {
        final int material = classifyBlock(state);
        if (material < 0 || !MATERIAL_ENABLED[material])
            return -1;
        return material;
    }

    /** Which material the block is, before the on/off switch is applied. */
    private static int classifyBlock(final BlockState state) {
        // Water is parked: the clip still does not read as rain on water, and no
        // sound is better than a wrong one until a proper source turns up.
        // Re-arming is MATERIAL_ENABLED[2] - see the array above.
        //
        // Guarded by that switch because getFluidState() is the most expensive
        // thing this routine asks, and it was being asked of every block a scan
        // touched - close to three thousand of them per pass - to reach an
        // answer the next line threw away. Switched off, it costs nothing.
        // Returning 2 rather than -1 also makes the re-arm honest: the comment
        // above used to promise MATERIAL_ENABLED[2] was all it took, and it
        // was not, because this line answered -1 whatever the array said.
        if (MATERIAL_ENABLED[2] && state.getFluidState().is(FluidTags.WATER))
            return 2;
        final var type = state.getSoundType();
        if (type == SoundType.GLASS)
            return 0;
        if (type == SoundType.METAL)
            return 1;
        // Logs and planks share SoundType.WOOD, so the vanilla log tag is
        // what separates them - the same split sound_mappings.json makes
        // for footsteps (footsteps.log against footsteps.wood).
        if (type == SoundType.WOOD)
            return !isResonantWood(state) ? -1 : (state.is(BlockTags.LOGS) ? 4 : 3);
        // Leaves are a tag, not a sound type: leaves, azalea leaves and
        // cherry leaves all share SoundType.GRASS with grass itself, so
        // the block tag is the only thing that separates them.
        if (state.is(BlockTags.LEAVES))
            return 6;
        // SoundType.STONE covers stone, cobblestone, stone bricks and
        // their stairs/slabs/walls, and the andesite family - which is
        // what the maintainer asked for when he said every stone type.
        if (type == SoundType.STONE)
            return 5;
        return -1;
    }

    /**
     * Whether a wooden block is the kind rain resonates in.
     *
     * <p>Twelfth report. A fence, a gate, a button, a pressure plate, a door,
     * a trapdoor and a sign all answer {@code SoundType.WOOD}, and every one
     * of them was being heard as rain on wood. They should not be: what this
     * layer is, in the maintainer's own word, is resonance, and resonance
     * belongs to a body with some depth to it - planks over a room, a log in
     * a wall, the slab and the stair cut from the same stock. A button is a
     * sliver fixed to that body and has no body of its own to ring, so rain
     * on it is rain on whatever is behind it, which will usually be the wall
     * this layer is already playing.</p>
     *
     * <p>So this is the short list of what counts, and it is asked by tag
     * rather than written out: planks, wooden slabs, wooden stairs and logs.
     * {@code logs} carries the stripped and the unstripped together - it
     * resolves through {@code logs_that_burn} to {@code oak_logs} and its
     * siblings, and each of those lists the log, the wood block and both
     * stripped forms - so a stripped log is a log here without being named
     * twice.</p>
     *
     * <p>The trade in asking by tag: a modded block that never joined one of
     * these four is silent, even if it is planks. Being wrong that way is
     * quiet and one tag file away from right; being wrong about a fence is
     * the noise this round exists to remove.</p>
     */
    private static boolean isResonantWood(final BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.LOGS);
    }

    /** A place rain can land, and which of our materials it is. */
    public record Surface(BlockPos pos, int material) {
    }
}
