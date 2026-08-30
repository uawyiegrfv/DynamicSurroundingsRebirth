package org.orecruncher.dsurround.effects.blocks.producers;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.effects.IBlockEffect;
import org.orecruncher.dsurround.effects.particles.DustParticle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.scripting.Script;

import java.util.Optional;

/**
 * A2-8: Dust jet - spawns a small burst of dust particles from a floating block (air below),
 * ported from the original 1.12.2 DustJetEffect/ParticleDustJet.
 */
public class DustJetProducer extends BlockEffectProducer {

    private static final int BURST_COUNT = 3;

    public DustJetProducer(Script chance, Script conditions) {
        super(chance, conditions);
    }

    @Override
    protected Optional<IBlockEffect> produceImpl(Level world, BlockState state, BlockPos pos, IRandomizer rand) {
        // Only shed dust when the block is floating (air below).
        if (!world.getBlockState(pos.below()).isAir())
            return Optional.empty();

        for (int i = 0; i < BURST_COUNT; i++) {
            var particle = new DustParticle((ClientLevel) world, pos.getX() + 0.5D, pos.getY() - 0.2D, pos.getZ() + 0.5D);
            GameUtils.getParticleManager().add(particle);
        }
        return Optional.empty();
    }
}
