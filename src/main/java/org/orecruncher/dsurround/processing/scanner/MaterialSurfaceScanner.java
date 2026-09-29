package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.scanner.CuboidScanner;
import org.orecruncher.dsurround.lib.scanner.ScanContext;

/**
 * Material surfaces within reach of the player, found by the shared cuboid
 * scanner instead of by a scan of our own.
 *
 * <p>The first version of this feature scanned the whole box inside one tick and
 * did it once a second. That is a spike: roughly three thousand block reads
 * arriving together, which is exactly the shape of cost this project tries to
 * avoid. {@link CuboidScanner} already solves it - it walks the volume a slice
 * at a time, so the same work is spread over about twenty ticks, and it also
 * carries the parts a hand-rolled scan tends to get wrong: resetting when the
 * player changes dimension, clamping to the world height, and delta-scanning the
 * sliver that comes into range when the player walks.</p>
 *
 * <p>Results are double-buffered. {@link #pending} accumulates while a pass runs
 * and is only published to {@link #ready} once the pass completes, so a caller
 * never sees a half-built set - reading one would look like "no surfaces in
 * reach" and would fade out loops that should still be playing. When a pass
 * finishes the next one starts immediately, which keeps the published set at
 * most one pass old and stops surfaces the player has walked away from
 * lingering as candidates.</p>
 */
public final class MaterialSurfaceScanner extends CuboidScanner {

    /** Horizontal reach. Six, not eight: reach is the cheap thing to give up. */
    public static final int HORIZONTAL_RANGE = 6;
    /**
     * Vertical reach, symmetric because the cuboid scanner is. The deeper
     * downward window the hand-rolled scan had existed to reach water below the
     * player; water is parked until there is a clip for it, and it can be given
     * an asymmetric window then if it comes back.
     */
    public static final int VERTICAL_RANGE = 8;

    private ObjectArray<Surface> pending = new ObjectArray<>(16);
    private ObjectArray<Surface> ready = new ObjectArray<>(16);

    public MaterialSurfaceScanner(final ScanContext locus) {
        super(locus, "MaterialSurfaceScanner", HORIZONTAL_RANGE);
        // CuboidScanner only publishes a uniform-range constructor. Widen
        // vertically through the protected setter instead: a roof overhead is
        // the common case and a horizontal range of six does not reach it.
        // This setter does not reset the scan, but the point iterator is null
        // until the first tick, which builds it from whatever range is current.
        this.setRange(HORIZONTAL_RANGE, VERTICAL_RANGE, HORIZONTAL_RANGE);
    }

    /** The last complete pass. Never a partial one. */
    public ObjectArray<Surface> surfaces() {
        return this.ready;
    }

    @Override
    public void resetFullScan() {
        super.resetFullScan();
        this.pending = new ObjectArray<>(16);
    }

    @Override
    public void tick() {
        super.tick();
        // A pass just finished: publish it and start the next one.
        if (this.scanFinished && this.pending.size() > 0) {
            final ObjectArray<Surface> finished = this.pending;
            this.pending = this.ready;
            this.ready = finished;
            this.resetFullScan();
        }
    }

    /**
     * Overridden only to keep the forgotten half of the pair honest: a completed
     * pass with nothing in it still has to be published, or a player standing in
     * open country would keep being offered the surfaces from wherever they were
     * a minute ago.
     */
    public boolean publishEmptyPass() {
        if (this.scanFinished && this.ready.size() > 0 && this.pending.size() == 0) {
            this.ready = new ObjectArray<>(16);
            this.resetFullScan();
            return true;
        }
        return false;
    }

    @Override
    public void blockScan(final Level world, final BlockState state, final BlockPos pos,
                          final IRandomizer rand) {
        final int material = classify(state);
        if (material < 0)
            return;
        // Open to the sky: nothing sitting on top of it, so rain actually
        // reaches it. A block buried in a wall is not rained on. Only asked of
        // blocks that already matched, so it is not a cost on every block.
        if (!world.getBlockState(pos.above()).isAir())
            return;
        this.pending.add(new Surface(pos.immutable(), material));
    }

    /** Which of our materials this block is, or -1 if none of them. */
    private static int classify(final BlockState state) {
        // Water is parked: the clip still does not read as rain on water, and no
        // sound is better than a wrong one until a proper source turns up.
        // Re-arming is one line here plus flipping the constant in the handler.
        if (state.getFluidState().is(FluidTags.WATER))
            return -1;
        final var type = state.getSoundType();
        if (type == SoundType.GLASS)
            return 0;
        if (type == SoundType.METAL)
            return 1;
        return -1;
    }

    /** A place rain can land, and which of our materials it is. */
    public record Surface(BlockPos pos, int material) {
    }
}
