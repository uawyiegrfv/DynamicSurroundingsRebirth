package org.orecruncher.dsurround.effects.systems;

import com.google.common.collect.AbstractIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IBlockLibrary;
import org.orecruncher.dsurround.effects.IBlockEffect;
import org.orecruncher.dsurround.effects.IBlockEffectProducer;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.sound.IAudioPlayer;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Basically does what doRandomBlockDisplayTicks() does for vanilla.
 */
public class RandomBlockEffectSystem extends AbstractEffectSystem {

    protected static final IRandomizer RANDOM = Randomizer.current();

    public static final int NEAR_RANGE = 16;
    public static final int FAR_RANGE = 32;
    // Random block positions sampled per pass. The original 667 was tuned for dense effect
    // coverage; since sampling is random and concentrated near the player (triangle
    // distribution), 500 keeps effect density perceptually identical while trimming the
    // per-pass fixed cost (this was the largest entry in the handler profile).
    private static final int ITERATION_COUNT = 500;

    private final IBlockLibrary blockLibrary;
    private final IAudioPlayer audioPlayer;
    private final int range;
    private final int tickInterval;
    private int tickCounter = 0;

    /**
     * @param tickInterval Sample every N ticks. Near-range systems pass 1 (every
     *                     tick); far-range systems can pass 3 to cut their fixed
     *                     per-tick cost by two thirds with no visible change.
     */
    public RandomBlockEffectSystem(IModLog logger, Configuration config, IBlockLibrary blockLibrary, IAudioPlayer audioPlayer, int range, int tickInterval) {
        super(logger, config, "Random(%d block range)".formatted(range));

        this.blockLibrary = blockLibrary;
        this.audioPlayer = audioPlayer;
        this.range = range;
        this.tickInterval = Math.max(1, tickInterval);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void tick(Predicate<IBlockEffect> processingPredicate) {
        super.tick(processingPredicate);

        // Expired effects are cleaned every tick (by super.tick above); the random
        // block scan below is expensive, so it only runs every tickInterval ticks.
        if (++this.tickCounter % this.tickInterval != 0)
            return;

        var player = GameUtils.getPlayer().orElseThrow();
        var world = player.level();

        var iterator = iterateRandomly(Randomizer.current(), ITERATION_COUNT, player.blockPosition(), this.range);

        for (var blockPos : iterator) {
            if (this.hasSystemAtPosition(blockPos))
                continue;

            var state = world.getBlockState(blockPos);
            if (Constants.BLOCKS_TO_IGNORE.contains(state.getBlock()))
                continue;

            var info = this.blockLibrary.getBlockInfo(state);
            if (!info.hasSoundsOrEffects())
                continue;

            final Collection<IBlockEffectProducer> effects = info.getEffectProducers();
            if (!effects.isEmpty()) {
                for (var be : effects) {
                    var effect = be.produce(world, state, blockPos, RANDOM);
                    if (effect.isPresent()) {
                        var e = effect.get();
                        this.systems.put(e.getPosIndex(), e);
                        // Only one effect per block position
                        break;
                    }
                }
            }

            info.getSoundToPlay(RANDOM).ifPresent(s -> {
                var instance = s.createAtLocation(blockPos);
                this.audioPlayer.play(instance);
            });
        }
    }

    @Override
    public void blockScan(Level world, BlockState state, BlockPos pos) {
        // Do nothing - everything is in the tick
    }

    protected Iterable<BlockPos> iterateRandomly(IRandomizer random, int count, BlockPos center, int range) {
        return () -> new AbstractIterator<>() {
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int remaining = count;

            protected BlockPos computeNext() {
                if (this.remaining <= 0) {
                    return this.endOfData();
                } else {
                    --this.remaining;
                    return this.pos.set(
                            random.triangle(center.getX(), range),
                            random.triangle(center.getY(), range),
                            random.triangle(center.getZ(), range));
                }
            }
        };
    }
}
