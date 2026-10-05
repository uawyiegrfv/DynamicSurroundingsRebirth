package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;
import org.orecruncher.dsurround.lib.collections.ObjectArray;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every place near the player where water hanging off a roof edge would drop
 * from, and where it would land, walked on demand.
 *
 * <p>The walk is a plain volume, borrowed from the material scanner: every
 * non-air block in the box around the player is asked the three drip shapes
 * in turn. An earlier version walked the {@code MOTION_BLOCKING} heightmap
 * instead - a drip roof is a motion-blocking block with sky on its top face,
 * so scanning only each column's top should have been enough, and it is -
 * in a clean world. The fourth live test said otherwise: structures dripped
 * from one spot instead of all of them, and the only thing the heightmap
 * route can do about a world that disagrees with it is stay wrong. The
 * volume costs more reads (~17k a pass against ~3k) and still measures in
 * tenths of a millisecond every {@code SCAN_TICKS}; the maintainer has
 * already ruled effect first.</p>
 *
 * <p>Three ways a block becomes a drip point, none of which consults a
 * {@code StairsShape}:
 * <ol>
 *   <li><b>The standard edge.</b> A block whose top face can hold a water
 *       film ({@code isFaceSturdy(UP)}, slabs passed by tag) with at least
 *       one horizontal neighbor that does not continue the wet surface - it
 *       is the edge of the roof, not its interior. The open faces are
 *       recorded unnormalized: the player side leans per axis, so a corner
 *       drop sits at the corner instead of half a block inside it.</li>
 *   <li><b>The canopy.</b> A leaf column drips from under the lowest leaf,
 *       no face test - vanilla's own leaf drip does not ask whether the leaf
 *       is at the edge of the canopy either, it asks only whether rain
 *       reaches the top of the leaf ({@code isRainingAt(pos.above())} in
 *       {@code LeavesBlock#animateTick}). Multi-layer canopies are walked
 *       down bounded, because the leaf that receives the rain is the top one
 *       and the drip point lives below the whole stack.</li>
 *   <li><b>The standing-stair nose.</b> A bottom-half stair fails the
 *       sturdy-top test by shape, and it is what roof eaves are actually
 *       built from. Water runs down the stair's front face and leaves from
 *       the nose, so the point is the low-side air block at the stair's own
 *       level and the face it leaves over looks back at the stair - gated on
 *       that block being open, which is what keeps hillside staircases out:
 *       their next step is in the way.</li>
 * </ol></p>
 *
 * <p>Whether rain reaches a roof's top face is vanilla's own triple -
 * {@code Level.isRainingAt} as read off both jars, minus {@code isRaining()}:
 * canSeeSky, then the heightmap check, then the biome answering RAIN. The
 * heightmap term is what knows a glass canopy is sheltering a face that
 * skylight still shines through; the biome term is what keeps drips out of
 * deserts and out of snowfall, exactly as vanilla's leaf drip behaves.</p>
 *
 * <p>Every position in here is an <b>immutable</b> {@code BlockPos}, on
 * purpose: on a {@code MutableBlockPos} the {@code below()}/{@code
 * relative()} family MOVES THE MUTABLE and returns it, and the second live
 * test was lost to exactly that - the stored "roof" quietly became the
 * stored "point".</p>
 *
 * <p>{@code getBlockState} answers air outside loaded chunks, so there is no
 * loaded-chunk test to get wrong; the twelve block radius does not reach
 * past the chunks the client is guaranteed to hold anyway.</p>
 */
public final class DripScanner {

    /** Horizontal reach. Twelve: an eave you cannot hear (16 block attenuation) is not worth a point. */
    /**
     * The ear reaches 16 blocks; the scan used to stop at 12, so the outer
     * four blocks of a long eave could never enter the pool - which is the
     * shape of "one end of the roof drips and the rest does not".
     */
    public static final int HORIZONTAL_RANGE = 16;
    /** Vertical reach above the player: bridges and gutters overhead. */
    public static final int UP_RANGE = 14;
    /** Vertical reach below: an eave you stand under is at eye level, but balconies are lower. */
    public static final int DOWN_RANGE = 12;
    /** A drop that falls further than this reads as rain, not as a drip from an edge. */
    public static final int MAX_FALL = 8;
    /**
     * Shortest fall that reads as a drip. Was 2; a one-block clearance under
     * a low canopy or a stair nose still reads fine now that the drop hangs
     * and stretches before it falls - the hang is what sells it.
     */
    public static final int MIN_FALL = 1;
    /**
     * Stair noses need more room: the fifth live test caught stair courses
     * above the eave dripping onto the course below - that water is
     * <b>flowing</b> down the roof, not dripping off it. A nose whose drop
     * lands one block down lands on the next stair; two or more means it
     * actually left the structure.
     */
    public static final int MIN_FALL_STAIRS = 2;
    /** How deep a leaf canopy is walked down looking for the drip point under it. */
    public static final int LEAF_WALK = 6;

    /**
     * Trace switch, driven by the handler from the logging config. When on,
     * the scan records a per-reason histogram and sample coordinates; when
     * off, not a single extra instruction beyond the boolean read.
     */
    public static volatile boolean TRACE = false;

    public static final int STAT_AIR = 0;
    public static final int STAT_BLACKLIST = 1;
    public static final int STAT_RAIN = 2;
    public static final int STAT_FILM = 3;
    public static final int STAT_NOFACE = 4;
    public static final int STAT_POINT = 5;
    public static final int STAT_LANDING = 6;
    public static final int STAT_OK = 7;
    public static final int STAT_COUNT = 8;
    public static final int[] STATS = new int[STAT_COUNT];
    public static final String[] STAT_NAMES = {
            "air", "blacklist", "rainUnreachable", "notWaterTight", "noOpenFace",
            "pointBlocked", "noLanding", "ok"
    };
    /** One sample position per rejection reason, for the trace log. */
    public static final Map<String, BlockPos> SAMPLES = new LinkedHashMap<>();
    private static int sampleSeq = 0;

    private DripScanner() {
    }

    /**
     * 正置楼梯的内转角：两片屋面在这里交汇，雨水往这个角上汇。只读形状的名字，
     * 不引入 StairsShape 类型 —— 三个版本里它的位置并不一致。
     */
    private static boolean isInnerCorner(final BlockState state) {
        if (!state.hasProperty(BlockStateProperties.STAIRS_SHAPE))
            return false;
        return String.valueOf(state.getValue(BlockStateProperties.STAIRS_SHAPE)).startsWith("INNER");
    }

    private static void note(final int stat, final BlockPos pos) {
        if (!TRACE)
            return;
        STATS[stat]++;
        if (TRACE && pos != null && stat != STAT_AIR) {
            sampleSeq++;
            SAMPLES.put(STAT_NAMES[stat] + "#" + sampleSeq, pos.immutable());
            while (SAMPLES.size() > 12) {
                final var it = SAMPLES.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /**
     * One drip. {@code point} is where the drop forms, {@code roof} the block
     * holding the water, {@code faces} the mask of point-cell faces the drop
     * may leave over (bit i = i-th of {@code Direction.Plane.HORIZONTAL}) -
     * for a roof edge those are the roof's open sides, for a stair nose the
     * single face toward the stair - {@code undersideY} is the height of the
     * roof's underside above the roof block's base (0 for a full cube or a
     * bottom slab, 0.5 for a top slab or an inverted stair), and
     * {@code landing}/water where the drop hits, with {@code fall} the travel
     * in blocks.
     */
    public record DripPoint(BlockPos point, BlockPos roof, int faces, double undersideY,
                            BlockPos landing, boolean water, int fall) {
    }

    /**
     * Every drip point in the box around {@code center}, now. Rejection
     * reasons are counted (and sampled when {@link #TRACE} is on) so a live
     * test can answer "why is my roof silent" from one log line.
     */
    public static ObjectArray<DripPoint> scan(final Level world, final BlockPos center) {
        return scan(world, center, false);
    }

    /**
     * {@code snowAllowed} widens the biome gate of {@code rainReachesTop} to
     * also accept SNOW biomes: the icicle layer asks for exactly this eave
     * geometry, but in the biomes where snow falls - the RAIN-only reading
     * rejected every roof there by definition. The drip layer keeps the
     * two-arg call, so its semantics are byte-for-byte what they were.
     */
    public static ObjectArray<DripPoint> scan(final Level world, final BlockPos center, final boolean snowAllowed) {
        if (TRACE) {
            Arrays.fill(STATS, 0);
            SAMPLES.clear();
        }
        final ObjectArray<DripPoint> result = new ObjectArray<>(64);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -HORIZONTAL_RANGE; dx <= HORIZONTAL_RANGE; dx++) {
            for (int dz = -HORIZONTAL_RANGE; dz <= HORIZONTAL_RANGE; dz++) {
                final int x = center.getX() + dx;
                final int z = center.getZ() + dz;
                for (int y = center.getY() + UP_RANGE; y >= center.getY() - DOWN_RANGE; y--) {
                    if (world.isOutsideBuildHeight(y))
                        continue;
                    cursor.set(x, y, z);
                    final BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        note(STAT_AIR, cursor);
                        continue;
                    }
                    if (isBlacklisted(state)) {
                        note(STAT_BLACKLIST, cursor);
                        continue;
                    }
                    final DripPoint point = buildPoint(world, new BlockPos(x, y, z), state, snowAllowed);
                    if (point != null) {
                        note(STAT_OK, roofOf(point));
                        result.add(point);
                    }
                }
            }
        }
        return result;
    }

    private static BlockPos roofOf(final DripPoint point) {
        return point.roof();
    }

    /**
     * Runs the three drip shapes against one block. Shared by the scan and
     * by fire-time validation - there is exactly one answer to "is this
     * still a drip point", and two copies of it is the material scanner's
     * third-log pick/kill loop again. Every rejection records its reason.
     */
    public static DripPoint buildPoint(final Level world, final BlockPos roof, final BlockState state) {
        return buildPoint(world, roof, state, false);
    }

    public static DripPoint buildPoint(final Level world, final BlockPos roof, final BlockState state, final boolean snowAllowed) {
        // Rain has to reach the top face whatever the shape is - asked once,
        // here, so the per-shape code stays about geometry.
        if (!rainReachesTop(world, roof.above(), snowAllowed)) {
            note(STAT_RAIN, roof);
            return null;
        }

        // Standing stairs: the nose drip. Their top face is only half there,
        // so the standard test rejects them. {@code FACING} points at the
        // stair's HIGH side - the direction it ascends toward - and water
        // leaves from the LOW side, the opposite one; the maintainer's first
        // description had it right ("楼梯低的那一边"). The drop hangs at the
        // stair's own nose, the bottom edge of its low-side face, so the
        // point is the low-side air block at the stair's own level, and the
        // face it leaves over looks back at the stair.
        if (isStandingStairs(state)) {
            final Direction low = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            final BlockPos point = roof.relative(low);
            if (!isPassable(world, point)) {
                note(STAT_POINT, roof);
                return null;
            }
            // A stair sitting on something does not shed off its own nose:
            // the water runs down onto the block underneath it instead of
            // leaving the structure. Only a stair with clear air below it
            // lets go - the landing walk below only ever looked sideways
            // from the nose, so a stair on solid ground still read as an
            // eave and dripped.
            final BlockPos belowStair = roof.below();
            if (!world.getBlockState(belowStair).getCollisionShape(world, belowStair).isEmpty()) {
                note(STAT_LANDING, roof);
                return null;
            }
            final DripPoint drip = withLanding(world, point, roof,
                    faceBit(low.getOpposite()), undersideOf(world, roof, state), MIN_FALL_STAIRS);
            if (drip == null)
                note(STAT_LANDING, roof);
            return drip;
        }

        // Everything else has to be able to hold a water film on top - one
        // predicate, asked here and by fire-time validation alike. Torches,
        // rails and signs are turned away here, before their (empty) shapes
        // are ever asked for an underside.
        if (!holdsWaterFilm(world, roof, state)) {
            note(STAT_FILM, roof);
            return null;
        }

        if (state.is(BlockTags.LEAVES)) {
            // The canopy. Vanilla drips from the leaf that receives the rain,
            // which is the top one; the point lives under the lowest leaf of
            // the stack. No face test - a leaf mid-canopy drips as happily as
            // one at the rim, and the player side wanders the whole underside.
            BlockPos point = roof.below();
            int depth = 0;
            while (depth < LEAF_WALK && world.getBlockState(point).is(BlockTags.LEAVES)) {
                point = point.below();
                depth++;
            }
            if (!isPassable(world, point)) {
                note(STAT_POINT, roof);
                return null;
            }
            // The drop hangs under the LOWEST leaf of the stack, not under the
            // one the scan happened to be looking at. Leaving roof as the top
            // leaf put every canopy's droplet inside the crown, because the
            // handler hangs the drop at roof.getY() + undersideY.
            final BlockPos lowest = point.above();
            final DripPoint drip = withLanding(world, point, lowest, 0,
                    undersideOf(world, lowest, world.getBlockState(lowest)), MIN_FALL);
            if (drip == null)
                note(STAT_LANDING, roof);
            return drip;
        }

        // Poles first: a post has no rim to leave over, it lets go from the
        // middle of its own underside.
        if (isPoleLike(state)) {
            final BlockPos point = roof.below();
            if (!isPassable(world, point)) {
                note(STAT_POINT, roof);
                return null;
            }
            final DripPoint drip = withLanding(world, point, roof, FACE_CENTER,
                    undersideOf(world, roof, state), MIN_FALL);
            if (drip == null)
                note(STAT_LANDING, roof);
            return drip;
        }

        // The faces of the point cell the drop may leave over: the sides
        // where the wet surface actually ends. The mask is left unnormalized
        // on purpose: the player side leans per axis, so a corner drop sits
        // at the corner instead of half a block inside it.
        int faces = 0;
        int index = 0;
        for (final Direction d : Direction.Plane.HORIZONTAL) {
            // 一条边要算"屋檐"，得看三个方向：水平邻居开放，斜下方也开放，斜上方也开放。
            // 少查任意一个，阶梯式屋顶就会每一级、以及每一行接壤边的对面都滴水 ——
            // 那就是他看到的"屋子里面到处在滴"。
            final BlockPos side = roof.relative(d);
            if (isEdgeOpen(world, side)
                    && isEdgeOpen(world, side.below())
                    && isEdgeOpen(world, side.above()))
                faces |= 1 << index;
            index++;
        }
        if (faces == 0) {
            note(STAT_NOFACE, roof);
            return null;
        }

        final BlockPos point = roof.below();
        if (!isPassable(world, point)) {
            note(STAT_POINT, roof);
            return null;
        }
        final double underside = undersideOf(world, roof, state);
        // 正置转角楼梯的拐角：两片屋面在这里交汇，水往这个角上汇，标记出来让
        // DripHandler 给它更高的选中概率和更快的水线。
        final int allFaces = isInnerCorner(state) ? faces | FACE_CORNER : faces;
        final DripPoint drip = withLanding(world, point, roof, allFaces, underside, MIN_FALL);
        if (drip == null)
            note(STAT_LANDING, roof);
        return drip;
    }

    /**
     * Whether a drop that is already hanging still has something to hang from.
     *
     * Deliberately narrower than {@link #isStillValid}: sky access and the landing
     * are answered from a chunk heightmap that a freshly loaded chunk has not
     * updated yet, so asking about them here tells drops that were spawned a tick
     * ago to let go - that is the appears-and-vanishes flicker. Only the block
     * itself and the air under it are stable enough to act on.
     */
    public static boolean isStillHeld(final Level world, final DripPoint point) {
        if (world.getBlockState(point.roof()).isAir())
            return false;
        return isPassable(world, point.point());
    }

    /**
     * Fire-time validation: the same predicates, asked about stored
     * positions. Sky access is what notices a canopy grown over the roof or
     * a block placed on it; the roof-state term is what notices the roof
     * being dug out.
     */
    public static boolean isStillValid(final Level world, final DripPoint point) {
        final BlockState roofState = world.getBlockState(point.roof());
        if (roofState.isAir())
            return false;
        if (isStandingStairs(roofState)) {
            if (!isPassable(world, point.point()))
                return false;
        } else if (!holdsWaterFilm(world, point.roof(), roofState)) {
            return false;
        } else if (!isPassable(world, point.point())) {
            return false;
        }
        if (!rainReachesTop(world, point.roof().above()))
            return false;
        final BlockState landing = world.getBlockState(point.landing());
        if (point.water()) {
            return !landing.getFluidState().isEmpty();
        }
        return !landing.getCollisionShape(world, point.landing()).isEmpty();
    }

    /**
     * Whether rain reaches the top face of {@code roof} - vanilla's
     * {@code isRainingAt} triple minus the {@code isRaining()} gate, because
     * the after-rain tail has to keep its points while the sky has already
     * cleared. The channel owns "is there water to drip"; this only owns
     * "could this face have caught any".
     */
    public static boolean rainReachesTop(final Level world, final BlockPos face) {
        return rainReachesTop(world, face, false);
    }

    public static boolean rainReachesTop(final Level world, final BlockPos face, final boolean snowAllowed) {
        // Glass and bottom slabs pass skylight, so the light test alone would
        // say yes under a glass canopy; the heightmap is what knows better.
        if (!world.canSeeSky(face))
            return false;
        // With snow allowed, one motion-blocking block above the roof is the
        // snow layer the ongoing snowfall has put there - the heightmap then
        // reads face.getY()+1, and that layer is exactly what an icicle grows
        // from, not cover. Real cover sits higher and still fails.
        if (world.getHeight(Heightmap.Types.MOTION_BLOCKING, face.getX(), face.getZ())
                > face.getY() + (snowAllowed ? 1 : 0))
            return false;
        final var biome = world.getBiome(face).value();
        // 1.20.1/1.21.1: one-arg. 26.1 takes the sea level explicitly - the
        // same call vanilla's own precipitationAt() makes.
        final Biome.Precipitation precip = biome.getPrecipitationAt(face);
        return precip == Biome.Precipitation.RAIN || (snowAllowed && precip == Biome.Precipitation.SNOW);
    }

    /** Finds where a drop from {@code point} would land, within {@code MAX_FALL} and past {@code minFall}. */
    private static DripPoint withLanding(final Level world, final BlockPos point, final BlockPos roof,
                                         final int faces, final double undersideY, final int minFall) {
        BlockPos cursor = point.below();
        for (int i = 1; i <= MAX_FALL; i++) {
            if (world.isOutsideBuildHeight(cursor.getY()))
                return null;
            final BlockState state = world.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) {
                // A drop into lava is a hiss and a plume, not a plip - that is
                // a different effect and this one does not do it.
                if (state.getFluidState().is(FluidTags.LAVA))
                    return null;
                return i >= minFall
                        ? new DripPoint(point, roof, faces, undersideY, cursor, true, i)
                        : null;
            }
            if (!state.getCollisionShape(world, cursor).isEmpty()) {
                return i >= minFall
                        ? new DripPoint(point, roof, faces, undersideY, cursor, false, i)
                        : null;
            }
            cursor = cursor.below();
        }
        return null;
    }

    /** An air block the drop can form in and fall through - tall grass under the eave counts, water does not. */
    private static boolean isPassable(final Level world, final BlockPos pos) {
        final BlockState state = world.getBlockState(pos);
        // 木栅栏豁免：栅栏是镂空的 —— 屋檐上流下来的水会从它的空隙穿过去，
        // 冰锥也一样能挂在栅栏后面。它的碰撞形状非空，但那不该被当成实心
        // 阻挡。只留流体检查（水下的格子仍然不行）。
        //   注意与 isPoleLike() 是两件事：那个是让栅栏柱**自己当屋檐**
        //   （水从柱底滴），这个是不让栅栏**把别人的屋檐挡死**。
        //   冰锥复用 DripScanner.scan()，所以这一处同时惠及滴水与冰锥。
        if (isWoodenFence(state))
            return state.getFluidState().isEmpty();
        return state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty();
    }

    /**
     * A fence, for the "do not let it block the eave above" exemption.
     *
     * <p>Asked by <em>class</em>, not by tag: {@code BlockTags.FENCES} does not
     * cover iron bars (see {@link #isPoleLike}), and a tag lookup depends on
     * the tag data being loaded, which is one more thing that can silently
     * disagree between the three versions. {@code FenceBlock} is the same class
     * in all three, and every wooden fence is one - nether brick fences extend
     * it too, which is correct here because they are just as openwork.
     * Iron bars stay excluded: they are a different block and a different look.</p>
     */
    private static boolean isWoodenFence(final BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
    }

    /**
     * The one answer to "can this face hold the film of water a drip needs",
     * asked by the scan and by fire-time validation alike. Slabs pass by tag:
     * both halves are water-tight surfaces, and whether the game's
     * FULL-support test likes a face sitting at half height is a placement
     * detail, not a physics verdict. The version that asked the full-support
     * test in the scan and the strict test in validation spent its days
     * killing exactly the slabs the scan had accepted - two answers to one
     * question is the material scanner's third-log pick/kill loop again.
     */
    private static boolean holdsWaterFilm(final Level world, final BlockPos pos, final BlockState state) {
        return state.isFaceSturdy(world, pos, Direction.UP, SupportType.FULL)
                || state.is(BlockTags.SLABS)
                || isPoleLike(state);
    }

    /**
     * Fence posts and iron bars. Their top face is not sturdy, so the general
     * rule turns them away - but a post is thick, and where one stands over
     * open air (a lamp post, a railing with nothing beneath) water running
     * down it lets go from the middle of its underside, which is what a post
     * actually does. Fences standing on solid ground are rejected by the
     * landing walk like anything else, so opening these up does not flood the
     * pool with every fence in a village.
     */
    private static boolean isPoleLike(final BlockState state) {
        // Iron bars are NOT in BlockTags.FENCES - they are their own block,
        // carrying no fence tag in any of the three versions. Named by class
        // rather than by tag because there is no tag to ask.
        return state.is(BlockTags.FENCES)
                || state.is(net.minecraft.world.level.block.Blocks.IRON_BARS);
    }

    /**
     * Faces value meaning "hang in the middle of the cell, exactly". Leaves
     * use {@code 0}, which the handler sprays across the cell; a post has one
     * place it drips from, so it needs its own value.
     */
    public static final int FACE_CENTER = 0x10;


    /** Mark on a drip point: the corner of an upright stair run, where two roof planes meet. */
    public static final int FACE_CORNER = 0x40;

    /**
     * Whether water reaching this side of the roof can leave over it - the
     * seam test. The third live test had slabs dripping where two slabs
     * joined: a slab is not a full-collision block, so the slab next door
     * read as an open side. What decides a seam is whether the neighbor
     * <b>continues the wet surface</b>: a face that holds a film (any slab
     * half, a full cube, an inverted stair) receives the sheet instead of
     * letting it fall, and so does a standing stair's tread one step down.
     * Fences, walls, panes and signs hold nothing and stay open.
     */
    private static boolean isEdgeOpen(final Level world, final BlockPos pos) {
        final BlockState state = world.getBlockState(pos);
        if (!world.getFluidState(pos).isEmpty())
            return false;
        if (holdsWaterFilm(world, pos, state))
            return false;
        return !isStandingStairs(state);
    }

    private static boolean isStandingStairs(final BlockState state) {
        return state.is(BlockTags.STAIRS)
                && state.hasProperty(BlockStateProperties.HALF)
                && state.getValue(BlockStateProperties.HALF) == Half.BOTTOM;
    }

    /**
     * The height of the roof's underside above its own base: 0 for a full
     * cube or a bottom slab, 0.5 for a top slab or an inverted stair. The
     * shape is never empty here - callers only ask after a block that holds
     * a film or is a stair - and an empty shape's {@code min} is +Infinity,
     * which would hang the drop a very long way away.
     */
    private static double undersideOf(final Level world, final BlockPos pos, final BlockState state) {
        // A top slab and an inverted stair keep their bottom face half a block
        // up. Asking the shape is not enough here: these report a full-cube
        // collision shape, so the minimum came back as 0 and the drop hung in
        // mid air half a block below where it should have been. The block
        // state knows, so ask it - and only for the two families that can be
        // half-height, behind hasProperty so anything else is untouched.
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.TOP)
            return 0.5D;
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                        == net.minecraft.world.level.block.state.properties.Half.TOP)
            return 0.5D;
        final var shape = state.getCollisionShape(world, pos);
        return shape.isEmpty() ? 0.0D : shape.min(Direction.Axis.Y);
    }

    /** Bit of {@code dir} in the {@code Direction.Plane.HORIZONTAL} face mask. */
    private static int faceBit(final Direction dir) {
        int index = 0;
        for (final Direction d : Direction.Plane.HORIZONTAL) {
            if (d == dir)
                return 1 << index;
            index++;
        }
        return 0;
    }

    /**
     * The material veto, deliberately empty in this version: the maintainer
     * wants the general rule first - stone cliff lips, glass canopies and
     * whatever else qualifies - and the throttle is the pool budget, not a
     * list. If a family of surfaces turns out too busy, the recommendation on
     * file is a <b>blacklist</b>, not a whitelist: name the natural blocks
     * (the stone family, grass block, dirt, sand, gravel, terracotta) and let
     * every modded and architectural block keep dripping. A whitelist would
     * silence every modded block that never registered - the resonant-wood
     * trade, and the loud side of it. Adding an entry is one {@code ||} here.
     */
    private static boolean isBlacklisted(final BlockState state) {
        return false;
    }
}
