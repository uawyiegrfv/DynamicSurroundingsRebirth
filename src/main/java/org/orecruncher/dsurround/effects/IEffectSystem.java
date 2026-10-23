package org.orecruncher.dsurround.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

public interface IEffectSystem {

    /**
     * Performs lifecycle operations for systems
     */
    void tick(Predicate<IBlockEffect> processingPredicate);

    /**
     * Indicates the effect system is enabled for processing
     */
    boolean isEnabled();

    /**
     * Whether blockScan can do anything for this state, judged from the STATE ALONE.
     * The scanner caches the answer per BlockState and skips the call when false.
     */
    default boolean blockScanRelevant(BlockState state) {
        return true;
    }

    /**
     * Invoked when a new block comes into the scan area
     */
    void blockScan(Level world, BlockState state, BlockPos pos);

    /**
     * Invoked when a block position leaves the scan area
     */
    void blockUnscan(Level world, BlockState state, BlockPos pos);

    /**
     * Invoked when the system should clear because the area scanner reset
     */
    void clear();

    /**
     * Invoked when diagnostic information about the system is requested
     */
    String gatherDiagnostics();

}
