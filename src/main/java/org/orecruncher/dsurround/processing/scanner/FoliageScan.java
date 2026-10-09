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
 * it). The box is deliberately small and never leaves the loaded chunks the
 * client is guaranteed to hold: at the configured range of 16 it is 33x33x7,
 * about 7.6k block reads a pass, against the eave scanner's 29k. The 7 is
 * {@link #UP_RANGE} plus {@link #DOWN_RANGE} plus one; the down side used to be
 * six, and that part of the box was the part nothing ever looked at.
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

    /**
     * Round 12: a box spends a third of itself on ground behind the player, which is the
     * part nobody can see. These stretch the sweep along where the player is looking and
     * pull it in behind, as two half-ellipses joined at the player (front
     * {@link #FAN_FORWARD}, back {@link #FAN_BACK}, sideways {@link #FAN_SIDE}, all in
     * units of {@code range}). At the default 16 that reaches <b>26 blocks ahead and 19
     * to either side</b> while walking 94% of the columns the box did - so the coat now
     * starts twenty-odd blocks out for slightly LESS work than before.
     */
    private static final float FAN_FORWARD = 1.65F;
    private static final float FAN_SIDE = 1.19F;
    private static final float FAN_BACK = 0.50F;
    /** Vertical reach above the player. */
    public static final int UP_RANGE = 4;
    /**
     * Vertical reach below the player.
     *
     * <p>Was six. The window is centred on the player, not on the terrain, so the reach below only
     * matters while the player is <em>above</em> the plants - standing on a wall, or in the air -
     * and everything it does cover has to be within the four blocks above as well. Two keeps the
     * cases that are actually seen (a slope one block down, a jump, standing on a plant's own
     * block) and drops four layers off every sweep: 33x33x11 becomes 33x33x7, a third of the block
     * reads gone from the most expensive of the three ground layers. Plants that were in range
     * and drop out are not torn down - the handlers' staleness pass keeps them until the plant
     * itself goes - so the visible effect is a slower fill from above, not a flicker.</p>
     */
    public static final int DOWN_RANGE = 2;

    /** Trace switch - driven by the handlers from the logging config. */
    public static volatile boolean TRACE = false;

    /** Logged once per session when the shader switch suppresses the plant layers. */
    private static volatile boolean shaderSuppressionReported = false;

    /**
     * Whether the plant-attached layers may run right now.
     *
     * <p>A shader pack that makes plants sway moves the plant in its vertex shader - the geometry
     * the player sees is displaced, while a particle anchored to that plant stays where the scan
     * found it. There is no way for us to follow: the displacement lives in the pack's GLSL, the
     * pack does not publish its parameters, and our particles are not drawn through the plant's
     * program. So the only honest lever is to stop drawing them, and this is that lever - with the
     * eave layers deliberately left alone, because blocks do not sway.</p>
     *
     * <p>Off by default: plenty of packs do not sway plants at all, and silently removing three
     * features for every shader user would be a worse default than one config line. The startup
     * summary names the switch whenever a pack is in use.</p>
     */
    public static boolean plantLayersAllowed(final org.orecruncher.dsurround.Configuration config) {
        if (config == null || !config.weatherOptions.disableFoliageLayersWithShaders)
            return true;
        return !org.orecruncher.dsurround.compat.ModIds.shadersInUse();
    }

    /** One line, once, when the switch above actually takes the layers down. */
    public static void reportShaderSuppression(final org.orecruncher.dsurround.lib.logging.IModLog logger) {
        if (shaderSuppressionReported)
            return;
        shaderSuppressionReported = true;
        logger.info("Plant-attached layers (leaf drips / dew / snow) are switched off because a shader"
                + " pack is in use and some packs sway the plants out from under a particle. Eave drips"
                + " and icicles are unaffected. Set weatherOptions.disableFoliageLayersWithShaders=false"
                + " to keep all three layers anyway.");
    }

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

    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode,
                                                final int range) {
        return scan(world, center, mode, range, 0F, 0F);
    }

    /**
     * The same sweep, narrowed to what the player can actually see. {@code fx}/{@code fz}
     * is the horizontal facing (a unit vector); pass 0/0 for the plain box, which is what
     * the dew and rain layers still ask for. Ground that swings into view as the player
     * turns is swept then, and anything already dressed is kept out to its layer's own
     * keep distance regardless of shape.
     */
    public static ObjectArray<FoliageSpot> scan(final Level world, final BlockPos center, final Mode mode,
                                                final int range, final float fx, final float fz) {
        if (TRACE)
            java.util.Arrays.fill(STATS, 0);
        final ObjectArray<FoliageSpot> result = new ObjectArray<>(64);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int rx = Math.max(2, range);
        final boolean fan = fx != 0F || fz != 0F;
        // The sweep is wider than rx now, so the walk has to reach past it - a box-sized
        // loop clipped the fan back to exactly the old 16 and quietly did nothing.
        final int w = fan ? (int) Math.ceil(Math.max(FAN_SIDE, Math.max(FAN_FORWARD, FAN_BACK)) * rx) : rx;

        for (int dx = -w; dx <= w; dx++) {
            for (int dz = -w; dz <= w; dz++) {
                if (fan) {
                    final float forward = dx * fx + dz * fz;
                    final float side = dx * -fz + dz * fx;
                    final float a = (forward >= 0F ? FAN_FORWARD : FAN_BACK) * rx;
                    final float b = FAN_SIDE * rx;
                    final float u = forward / a;
                    final float v = side / b;
                    if (u * u + v * v > 1F)
                        continue;
                }
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
        // Round 12: sugar cane. It is a cross model exactly like grass, but
        // SugarCaneBlock extends Block rather than BushBlock and it is in none of the
        // three tags below, so it never once made the list - the maintainer asked why.
        // (Cactus is the same shape of omission and stays out on purpose: snow sitting
        // on a cactus is not a picture anyone asked for.)
        if (state.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE))
            return true;
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
