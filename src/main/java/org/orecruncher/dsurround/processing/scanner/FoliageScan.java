package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.orecruncher.dsurround.processing.FoliageProfile;
import org.orecruncher.dsurround.processing.FoliageProfileCache;
import org.orecruncher.dsurround.lib.collections.ObjectArray;

/**
 * Where the foliage-drip layers can attach: a plain volume walk around the
 * player (the drip scanner's framework, a different predicate - plants are
 * not overhangs, so this is a sibling of {@link DripScanner}, not a branch of
 * it). The box is deliberately small: radius 10 / up 4 / down 6 is 4851 block
 * reads a pass against the eave scanner's 29k, and never leaves the loaded
 * chunks the client is guaranteed to hold.
 *
 * <p>The predicate chain, in cost order:</p>
 * <ol>
 *   <li>air / fluid - the bulk of the box, one read each;</li>
 *   <li>candidate set - a class/tag test, still no model access (bushes,
 *       flowers, crops past the sprout stages, saplings; leaves excluded -
 *       the eave scanner's canopy branch already drips from leaf bottoms,
 *       and doubling it up would be noise);</li>
 *   <li>sky - RAIN asks the eave scanner's full triple (canSeeSky +
 *       heightmap + biome RAIN: "is rain landing here"); DEW asks only
 *       canSeeSky + heightmap ("is the sky open") - whether a biome CAN rain
 *       says nothing about whether dew forms. Leaves and glass both sit in
 *       the MOTION_BLOCKING heightmap, so covered plants pass neither.</li>
 *   <li>profile - the model alpha mask must have produced anchors; built
 *       once per model and cached.</li>
 * </ol>
 *
 * <p>Sky access is asked at SCAN time only, exactly once per spot; the
 * handlers' staleness passes never re-ask the heightmap (a freshly loaded
 * chunk's heightmap lags - asking it there is the appears-and-vanishes
 * flicker the eave layer already paid for).</p>
 */
public final class FoliageScan {

    public enum Mode {
        /** Rain landing on the plant: full {@code rainReachesTop} triple. */
        RAIN,
        /** Morning dew: sky open, no biome precipitation term. */
        DEW,
        /**
         * Snow settling on the plant. Same sky test as DEW - "can snow reach
         * this blade" is exactly "is the sky open above it". The snow PHASE
         * and the cold-temperature gates are the handler's, asked per spot
         * (icicle lesson: the column's own phase, not the player's).
         */
        SNOW
    }

    /** Default horizontal reach (the config's foliageScanRange drives the real one). */
    public static final int RANGE_XZ = 10;
    /** Vertical reach above the player. */
    public static final int UP_RANGE = 4;
    /** Vertical reach below the player. */
    public static final int DOWN_RANGE = 6;

    /** Trace switch - driven by the handlers from the logging config. */
    public static volatile boolean TRACE = false;

    public static final int STAT_AIR = 0;
    public static final int STAT_NOT_CANDIDATE = 1;
    public static final int STAT_SKY = 2;
    public static final int STAT_NO_PROFILE = 3;
    public static final int STAT_OK = 4;
    public static final int STAT_COUNT = 5;
    public static final int[] STATS = new int[STAT_COUNT];
    public static final String[] STAT_NAMES = {
            "air", "notCandidate", "skyBlocked", "noProfile", "ok"
    };

    /** One attachable plant: position, its state, and its ready profile. */
    public record FoliageSpot(BlockPos pos, BlockState state, FoliageProfile profile) {
    }

    private FoliageScan() {
    }

    private static void note(final int stat) {
        if (TRACE)
            STATS[stat]++;
    }

    /**
     * Every attachable plant in the box around {@code center}, now. Rejection
     * reasons are counted (when {@link #TRACE} is on) so a live test can
     * answer "why is nothing dripping" from one log line.
     */
    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode) {
        return scan(world, center, mode, RANGE_XZ);
    }

    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode, final int range) {
        if (TRACE)
            java.util.Arrays.fill(STATS, 0);
        final ObjectArray<FoliageSpot> result = new ObjectArray<>(64);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int rx = Math.max(2, range);

        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rx; dz <= rx; dz++) {
                final int x = center.getX() + dx;
                final int z = center.getZ() + dz;
                for (int y = center.getY() + UP_RANGE; y >= center.getY() - DOWN_RANGE; y--) {
                    if (world.isOutsideBuildHeight(y))
                        continue;
                    cursor.set(x, y, z);
                    final BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        note(STAT_AIR);
                        continue;
                    }
                    if (!isCandidate(state)) {
                        note(STAT_NOT_CANDIDATE);
                        continue;
                    }
                    final BlockPos pos = new BlockPos(x, y, z);
                    if (!skyOpen(world, pos, mode)) {
                        note(STAT_SKY);
                        continue;
                    }
                    final FoliageProfile profile = FoliageProfileCache.get(world, pos, state);
                    if (profile.isEmpty()) {
                        note(STAT_NO_PROFILE);
                        continue;
                    }
                    note(STAT_OK);
                    result.add(new FoliageSpot(pos, state, profile));
                }
            }
        }
        return result;
    }

    /**
     * The one answer to "is this block a plant that can carry droplets".
     * Bushes, flowers, crops past the sprout stages and saplings. Crops'
     * first stages are near-empty textures (survey §15: wheat age0 is 2.7%
     * opaque with all anchors in the bottom half) - turning them away here
     * saves the profile build and the empty anchor set.
     */
    private static boolean isCandidate(final BlockState state) {
        if (state.is(BlockTags.LEAVES))
            return false;
        if (!state.getFluidState().isEmpty())
            return false;
        if (state.hasProperty(BlockStateProperties.AGE_7)
                && state.getValue(BlockStateProperties.AGE_7) <= 1)
            return false;   // wheat/carrots/potatoes sprouts
        if (state.hasProperty(BlockStateProperties.AGE_3)
                && state.getValue(BlockStateProperties.AGE_3) <= 0)
            return false;   // beetroots sprout
        if (state.getBlock() instanceof BushBlock)
            return true;
        return state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS);
    }

    /** Sky test per mode - asked once per spot, at scan time, never again. */
    private static boolean skyOpen(final Level world, final BlockPos pos, final Mode mode) {
        if (mode == Mode.RAIN)
            return DripScanner.rainReachesTop(world, pos.above());
        // DEW and SNOW share the answer: "can precipitation reach this blade"
        // is "is the sky open above it". Whether it is RAIN or SNOW up there
        // is a PHASE question, and that is the handler's (per spot).
        return dewSkyOpen(world, pos);
    }

    /**
     * "Is the sky directly open above this plant" - canSeeSky plus the
     * MOTION_BLOCKING heightmap. The heightmap is what knows about glass
     * (which skylight shines through) and leaves; the biome precipitation
     * term of {@code rainReachesTop} is deliberately absent - dew does not
     * care whether this biome calls its weather RAIN.
     */
    public static boolean dewSkyOpen(final Level world, final BlockPos pos) {
        if (!world.canSeeSky(pos.above()))
            return false;
        return world.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                <= pos.getY() + 1;
    }
}
