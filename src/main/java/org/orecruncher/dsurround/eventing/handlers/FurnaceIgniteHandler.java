package org.orecruncher.dsurround.eventing.handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.effects.BlockEffectUtils;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;

/**
 * Plays a one-shot "ignite" whoosh when a furnace, blast furnace or smoker
 * transitions from unlit to lit.  Detection rides on the client side block
 * state change notifications provided by MixinWorld (Level.onBlockStateChange),
 * so no server side component is needed.  Bulk section updates (chunk loads)
 * do not route through that hook, which naturally suppresses sounds for
 * furnaces that were already lit.
 */
public final class FurnaceIgniteHandler {

    private static final ResourceLocation IGNITE_FACTORY = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "furnace_ignite");
    private static final Configuration.BlockEffects CONFIG = ContainerManager.resolve(Configuration.BlockEffects.class);

    private FurnaceIgniteHandler() {

    }

    /**
     * Called from BlockUpdateHandler for every client side block state change.
     */
    public static void blockStateChanged(final BlockPos pos, final BlockState oldState, final BlockState newState) {
        // Cheap filter first: only interested in unlit -> lit transitions
        if (BlockEffectUtils.IS_LIT_FURNACE.test(oldState) || !BlockEffectUtils.IS_LIT_FURNACE.test(newState))
            return;

        if (!CONFIG.furnaceIgniteEnabled)
            return;

        // Resolve lazily: the sound library loads its data during resource loading
        final ISoundFactory factory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactory(IGNITE_FACTORY)
                .orElse(null);

        if (factory == null)
            return;

        GameUtils.getPlayer().ifPresent(player -> {
            final double range = CONFIG.blockEffectRange;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) < range * range) {
                ContainerManager.resolve(IAudioPlayer.class).play(factory.createAtLocation(pos));
            }
        });
    }
}