package org.orecruncher.dsurround.processing;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.effects.particles.DustCloudParticle;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.events.HandlerPriority;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Kicks up a burst of dust when a sand or gravel block lands. The landing moment is
 * observed in the client entity's own tick (MixinFallingBlockEntity raises
 * {@link ClientEventHooks#FALLING_BLOCK_LAND_EVENT} when onGround flips true), which is
 * reliable where post-tick events fail: the server removes the entity the same tick it
 * lands, so EntityTickEvent can never see it.
 *
 * Spawns soft coloured dust (sand yellow / gravel grey) at the landed block's top face
 * that billows outward and barely rises, plus a few vanilla smoke motes for body. Only
 * near the local player, and scaled by the particle detail setting.
 */
public class FallDustCloudHandler extends AbstractClientHandler {

    private static final IRandomizer RANDOM = Randomizer.current();
    // Max horizontal distance from the local player for a landing to kick up dust.
    private static final int MAX_RANGE = 48;
    // Dust motes per landing, scaled down by the player's particle setting.
    private static final int DUST_COUNT = 16;

    public FallDustCloudHandler(Configuration config, IModLog logger) {
        super("Fall Dust Cloud", config, logger);
        ClientEventHooks.FALLING_BLOCK_LAND_EVENT.register(this::onLand, HandlerPriority.HIGH);
    }

    private void onLand(FallingBlockEntity falling, Level level, BlockPos pos) {
        if (!this.config.blockEffects.fallingBlockDustEnabled)
            return;
        if (!(level instanceof ClientLevel clientLevel))
            return;

        var player = GameUtils.getPlayer().orElse(null);
        if (player == null || !player.level().equals(level))
            return;
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > MAX_RANGE * MAX_RANGE)
            return;

        var color = cloudColor(falling.getBlockState());
        if (color == null)
            return;

        spawnDust(clientLevel, pos, color);
    }

    private void spawnDust(ClientLevel level, BlockPos pos, float[] color) {
        var status = GameUtils.getGameSettings().particles().get();
        int count = switch (status) {
            case MINIMAL -> 0;
            case DECREASED -> DUST_COUNT / 2;
            case ALL -> DUST_COUNT;
        };
        if (count <= 0)
            return;

        // Dust kicks up from the ground at the landing point - the block settles onto the
        // floor of its cell (pos.y), and the cloud bursts upward from there. The landed
        // block occupies [pos.y, pos.y+1], so particles start at the floor and billow up.
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.1D;
        double pz = pos.getZ() + 0.5D;

        // Flat, outward-spreading dust: near-zero vertical velocity, strong horizontal
        // drift away from the centre, so it billows sideways above the landing point.
        for (int i = 0; i < count; i++) {
            double x = px + (RANDOM.nextDouble() - 0.5D) * 1.0D;
            double y = py + RANDOM.nextDouble() * 0.4D;
            double z = pz + (RANDOM.nextDouble() - 0.5D) * 1.0D;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            double speed = 0.06D + RANDOM.nextDouble() * 0.14D;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            double vy = RANDOM.nextDouble() * 0.02D;   // barely rises
            float scale = 1.2F + RANDOM.nextFloat() * 0.8F;
            var particle = new DustCloudParticle(level, x, y, z, vx, vy, vz, color[0], color[1], color[2], scale);
            GameUtils.getParticleManager().add(particle);
        }
        // A few larger vanilla smoke motes for body.
        for (int i = 0; i < count / 4; i++) {
            double x = px + (RANDOM.nextDouble() - 0.5D) * 0.7D;
            double y = py + RANDOM.nextDouble() * 0.2D;
            double z = pz + (RANDOM.nextDouble() - 0.5D) * 0.7D;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            double speed = 0.04D + RANDOM.nextDouble() * 0.08D;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            level.addParticle(ParticleTypes.LARGE_SMOKE, x, y, z, vx, RANDOM.nextDouble() * 0.01D, vz);
        }
    }

    @Override
    public void onDisconnect() {
        // No persistent state.
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems, "Fall dust: mixin land-triggered");
    }

    /**
     * Dust colour for the falling block; null means the block shouldn't raise a cloud.
     * Sand kicks up a warm pale-yellow dust, gravel a grey-brown.
     */
    private static float[] cloudColor(BlockState state) {
        var block = state.getBlock();
        if (block == Blocks.SAND || block == Blocks.RED_SAND)
            return new float[]{0.82F, 0.72F, 0.45F};
        if (block == Blocks.GRAVEL)
            return new float[]{0.50F, 0.46F, 0.42F};
        return null;
    }
}
