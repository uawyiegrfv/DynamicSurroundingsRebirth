package org.orecruncher.dsurround.effects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

/**
 * Rain drops hitting magma or netherrack puff up smoke/steam, ported from the
 * original 1.12.2 NetherSplashRenderer (Enable Netherrack and Magma Splash).
 * 26.1: piggybacks on the vanilla rain drop particle and spawns a smoke particle
 * when the drop lands on one of those blocks.
 */
public final class MagmaSplashHandler {

    private static final Configuration.BlockEffects CONFIG = ContainerManager.resolve(Configuration.BlockEffects.class);

    private MagmaSplashHandler() {
    }

    public static void onRainParticle(ClientLevel world, Vec3 position) {
        if (!CONFIG.enableMagmaSteam)
            return;

        // The block BELOW the drop, not the one containing it.
        //
        // The vanilla rain drop spawns at surfaceY + max(shape.max(Y), fluidHeight), which is
        // exactly surfaceY + 1.0 for a full block, and BlockPos.containing floors - so it landed in
        // the AIR cell above the surface. Magma and netherrack are full blocks, so the old lookup
        // could never see them and this handler was unreachable: the enableMagmaSteam option did
        // nothing at all. The 1.12.2 original passed the BlockState itself, which is why it worked.
        final BlockPos pos = BlockPos.containing(position).below();
        final var block = world.getBlockState(pos).getBlock();
        if (block != Blocks.MAGMA_BLOCK && block != Blocks.NETHERRACK)
            return;

        world.addParticle(ParticleTypes.SMOKE,
                position.x, position.y + 0.1D, position.z,
                0.0D, 0.05D, 0.0D);
    }
}
