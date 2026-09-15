package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.config.DimensionInfo;
import org.orecruncher.dsurround.config.libraries.IDimensionLibrary;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decides whether the player is "inside" - the flag that attenuates the biome ambience and gates
 * the storm/fog effects.
 *
 * <p>Replaces the original ceiling-coverage test. That one was ported verbatim from 1.12.2's
 * CeilingCoverage (same 7x7 survey, same 1 - 65/176 threshold) and counted a cell as covered when
 * ANY motion-blocking block sat between the precipitation height and the player's head. It calls
 * the player inside as soon as enough cells are covered, so an eaves, a doorway, a porch or a tree
 * canopy silences the ambience the moment the player steps under a roof edge.
 *
 * <p>This test asks the question the ambience actually cares about:
 *
 * <ul>
 *   <li>a horizontal face (E/W/S/N) is BLOCKED when the player cannot walk out that way within
 *       {@link #MAX_WALK} blocks. Rays are cast across the width a player occupies and over the
 *       standing head room, and every one of them must hit a sound-blocking surface. A 1-wide
 *       doorway, a window, a 3-wide hole in a wall or the gap between pillars therefore keeps the
 *       face open - which is right: those let the outside in.</li>
 *   <li>the overhead face is BLOCKED when a sound-blocking surface sits within {@link #MAX_UP}
 *       blocks above the head.</li>
 *   <li>the floor is deliberately ignored: it is always there and says nothing about enclosure.</li>
 * </ul>
 *
 * <p>Result: inside when at least three faces are blocked and there is a ceiling above, or when all
 * four are blocked. An eaves blocks overhead and nothing else, so it stays outside; a sealed hut, a
 * corridor, a cave pocket and even a 9x9 hall stay inside. A face whose rays all end in
 * sound-transparent blocks (a glass box) only counts as half a face, so glass alone cannot seal a
 * room. Measured against 13 scenes while designing this: 12 correct, the miss being a 2x2 shaft
 * that a 0.6-wide player fills so completely that two of its four faces really are open passages.
 */
public final class EnclosureScanner extends AbstractScanner {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    // Survey every 8 ticks (0.4s). The flag only gates ambient-sound attenuation and a fog blend, so
    // a 0.4s response is fine, and it matches the cadence of the scanner this replaces.
    private static final int SURVEY_INTERVAL = 8;

    /** How far the player must be able to walk in a direction for that face to count as open. */
    public static final double MAX_WALK = 6.0D;
    /** How far above the head a surface still counts as a ceiling. */
    public static final double MAX_UP = 6.0D;
    /** March step. Small enough to never skip a 3/16 glass pane or a chain. */
    private static final double STEP = 0.125D;
    /** Weight of a face whose rays all end in sound-transparent blocks (a glass wall/roof). */
    private static final float PARTIAL_FACE_WEIGHT = 0.5F;

    // The lateral offsets a 0.6-wide player occupies, and the heights a wall has to cover.
    private static final double[] LATERAL_OFFSETS = { -0.4D, 0.0D, 0.4D };
    private static final double[] HEAD_ROOM = { 1.7D, 2.2D };
    private static final double EYE_HEIGHT = 1.62D;
    private static final double OVERHEAD_START = 0.2D;
    // Five sample columns for the overhead test: the player's cell plus its corners.
    private static final double[] UP_OFFSETS = { 0.0D, 0.4D, -0.4D };

    private static final HorizontalFace[] FACES = {
            new HorizontalFace("E", 1.0D, 0.0D),
            new HorizontalFace("W", -1.0D, 0.0D),
            new HorizontalFace("S", 0.0D, 1.0D),
            new HorizontalFace("N", 0.0D, -1.0D),
    };

    /** Tags whose members let sound through: a glass roof or a hedge is not a ceiling. */
    private static final ObjectArray<TagKey<Block>> SOUND_PASSERS = new ObjectArray<>();

    static {
        SOUND_PASSERS.add(BlockTags.LEAVES);
        SOUND_PASSERS.add(BlockTags.FENCES);
        SOUND_PASSERS.add(BlockTags.FENCE_GATES);
        SOUND_PASSERS.add(BlockTags.WALLS);
        SOUND_PASSERS.add(BlockTags.IMPERMEABLE);
        SOUND_PASSERS.add(BlockTags.ALL_SIGNS);
        SOUND_PASSERS.add(BlockTags.CANDLES);
        SOUND_PASSERS.add(BlockTags.SAPLINGS);
        SOUND_PASSERS.add(BlockTags.CROPS);
        // Vanilla has no tag for iron bars or chains (BlockTags.BARS exists only from 1.21.2 on),
        // so DS carries one; it is tiny and other mods can add their own grilles to it.
        SOUND_PASSERS.add(org.orecruncher.dsurround.tags.BlockEffectTags.SOUND_PASSER);
    }

    // march() return codes
    private static final double NOTHING = -1.0D;   // crossed only air
    private static final double PARTIAL = -2.0D;   // crossed a sound-passing block, no blocker

    private final IDimensionLibrary dimensionLibrary;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    private boolean reallyInside = false;
    private boolean overheadBlocked = false;
    private int blockedFaces = 0;
    private double faceScore = 0.0D;
    private final double[] faceDistance = new double[FACES.length];
    private final boolean[] faceBlocked = new boolean[FACES.length];
    private double overheadDistance = Double.MAX_VALUE;

    public EnclosureScanner(IDimensionLibrary dimensionLibrary) {
        this.dimensionLibrary = dimensionLibrary;
    }

    @Override
    public void tick(long tickCount) {
        if (tickCount % SURVEY_INTERVAL != 0)
            return;

        final Level world = GameUtils.getWorld().orElse(null);
        final var player = GameUtils.getPlayer().orElse(null);
        if (world == null || player == null)
            return;

        final DimensionInfo dimInfo = this.dimensionLibrary.getData(world);
        if (dimInfo.alwaysOutside()) {
            reset();
            return;
        }

        final double feetY = player.getY();
        final double x = player.getX();
        final double z = player.getZ();

        int blocked = 0;
        double score = 0.0D;
        for (int i = 0; i < FACES.length; i++) {
            final double distance = surveyFace(world, FACES[i], x, z, feetY);
            this.faceDistance[i] = distance;
            this.faceBlocked[i] = distance > 0.0D;
            if (this.faceBlocked[i]) {
                blocked++;
                score += 1.0D;
            } else if (distance == PARTIAL) {
                score += PARTIAL_FACE_WEIGHT;
            }
        }
        this.blockedFaces = blocked;
        this.faceScore = score;

        double overhead = Double.MAX_VALUE;
        for (final double ox : UP_OFFSETS) {
            for (final double oz : UP_OFFSETS) {
                final double d = march(world, x + ox, feetY + EYE_HEIGHT + OVERHEAD_START, z + oz,
                        0.0D, 1.0D, 0.0D, MAX_UP);
                if (d > 0.0D && d < overhead)
                    overhead = d;
            }
        }
        this.overheadBlocked = overhead < Double.MAX_VALUE;
        this.overheadDistance = overhead;

        this.reallyInside = (blocked >= 4) || (blocked >= 3 && this.overheadBlocked);
    }

    private void reset() {
        this.reallyInside = false;
        this.overheadBlocked = false;
        this.blockedFaces = 0;
        this.faceScore = 0.0D;
        Arrays.fill(this.faceBlocked, false);
        Arrays.fill(this.faceDistance, 0.0D);
        this.overheadDistance = Double.MAX_VALUE;
    }

    /**
     * Casts the ray fan of one horizontal face.
     *
     * @return the distance to the nearest blocking surface when the face is BLOCKED, {@link #PARTIAL}
     *         when every ray is stopped but only by sound-transparent blocks, or 0 when the player
     *         can walk out through this face.
     */
    private double surveyFace(final Level world, final HorizontalFace face, final double x, final double z,
                              final double feetY) {
        int blockedRays = 0;
        int partialRays = 0;
        int freeRays = 0;
        double nearest = Double.MAX_VALUE;
        for (final double off : LATERAL_OFFSETS) {
            for (final double height : HEAD_ROOM) {
                // the lateral axis is perpendicular to the face direction
                final double lx = -face.dirZ();
                final double lz = face.dirX();
                final double d = march(world, x + lx * off, feetY + height, z + lz * off,
                        face.dirX(), 0.0D, face.dirZ(), MAX_WALK);
                if (d == NOTHING) {
                    freeRays++;
                } else if (d == PARTIAL) {
                    partialRays++;
                } else {
                    blockedRays++;
                    if (d < nearest)
                        nearest = d;
                }
            }
        }
        final int total = blockedRays + partialRays + freeRays;
        if (freeRays > 0)
            return 0.0D;                                // there is a way out
        // Every lane is stopped. A face stopped only by sound-transparent blocks (a glass wall) is
        // not a wall: it counts as half, never as a full face.
        if (partialRays > total / 2)
            return PARTIAL;
        return nearest == Double.MAX_VALUE ? MAX_WALK : nearest;
    }

    /**
     * Marches from (x,y,z) along the direction (dx,dy,dz) up to {@code maxDistance}.
     *
     * @return the distance to the first sound-blocking block, {@link #PARTIAL} when only
     *         sound-transparent non-air blocks were crossed, or {@link #NOTHING} when everything
     *         crossed was air.
     */
    private double march(final Level world, final double x, final double y, final double z,
                         final double dx, final double dy, final double dz, final double maxDistance) {
        boolean sawNonAir = false;
        double t = STEP;
        while (t <= maxDistance) {
            this.probe.set(
                    Mth.floor(x + dx * t),
                    Mth.floor(y + dy * t),
                    Mth.floor(z + dz * t));
            final BlockState state = world.getBlockState(this.probe);
            if (!state.isAir()) {
                if (blocksSound(state))
                    return t;
                sawNonAir = true;
            }
            t += STEP;
        }
        return sawNonAir ? PARTIAL : NOTHING;
    }

    /** True when the block stops sound (and therefore counts as a wall, ceiling, door or roof). */
    private boolean blocksSound(final BlockState state) {
        // Nothing that lets a player pass can seal a room either.
        if (!state.blocksMotion())
            return false;
        for (final TagKey<Block> tag : SOUND_PASSERS) {
            if (TAG_LIBRARY.is(tag, state))
                return false;
        }
        return true;
    }

    /** True when the player is considered indoors. */
    public boolean isReallyInside() {
        return this.reallyInside;
    }

    /** True when a ceiling was found within {@link #MAX_UP} blocks above the head. */
    public boolean isOverheadBlocked() {
        return this.overheadBlocked;
    }

    /** How many of the four horizontal faces are blocked (0-4). */
    public int getBlockedFaces() {
        return this.blockedFaces;
    }

    /**
     * Fraction of the four horizontal faces that are blocked, where a face sealed only by
     * sound-transparent blocks counts half. Kept for the diagnostics overlay.
     */
    public float getCoverageRatio() {
        return (float) (this.faceScore / FACES.length);
    }

    /** Per-face report for {@code /dsdump enclosure}. */
    public List<String> describe(final Level world) {
        final var player = GameUtils.getPlayer().orElse(null);
        final List<String> out = new ArrayList<>();
        if (player == null)
            return out;
        final DimensionInfo dimInfo = this.dimensionLibrary.getData(world);
        out.add("player %s   feet Y %.3f".formatted(player.blockPosition(), player.getY()));
        out.add("dimension alwaysOutside = %s".formatted(dimInfo.alwaysOutside()));
        for (int i = 0; i < FACES.length; i++) {
            out.add("  face %s: %s   (nearest sound stop %s)".formatted(
                    FACES[i].name(),
                    this.faceBlocked[i] ? "BLOCKED" : (this.faceDistance[i] == PARTIAL ? "glass/open" : "OPEN"),
                    this.faceDistance[i] > 0 ? "%.2f".formatted(this.faceDistance[i]) : "-"));
        }
        out.add("  overhead: %s   (nearest sound stop %s)".formatted(
                this.overheadBlocked ? "BLOCKED" : "OPEN",
                this.overheadDistance < Double.MAX_VALUE ? "%.2f".formatted(this.overheadDistance) : "-"));
        out.add("blocked faces = %d/4   overhead blocked = %s   score = %.2f".formatted(
                this.blockedFaces, this.overheadBlocked, this.faceScore));
        out.add("VERDICT: %s    (rule: 4 faces blocked, or 3 faces + overhead)".formatted(
                this.reallyInside ? "INSIDE" : "OUTSIDE"));
        out.add("constants: MAX_WALK=%.1f MAX_UP=%.1f lateral=%s headroom=%s".formatted(
                MAX_WALK, MAX_UP, Arrays.toString(LATERAL_OFFSETS), Arrays.toString(HEAD_ROOM)));
        return out;
    }

    private record HorizontalFace(String name, double dirX, double dirZ) {}
}
