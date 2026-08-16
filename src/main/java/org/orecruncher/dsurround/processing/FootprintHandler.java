package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.effects.particles.FootprintParticle;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.util.Set;

/**
 * Leaves footprints under the player while walking, ported from the original
 * 1.12.2 EntityFootprintEffect. Instead of depending on the vanilla step event
 * (which is gated on the brush-step entity tag and accent settings), this
 * accumulates horizontal movement each tick and drops a left/right alternating
 * footprint about every half block, matching the vanilla step cadence.
 */
public class FootprintHandler {

    // Only these materials leave prints, matching the original FOOTPRINT_MATERIAL
    // whitelist (clay, grass, ground, sand, snow). Wood/stone don't. Ice is
    // deliberately excluded - the player slides across ice without leaving prints
    // (a user-requested deviation from the original, which included Material.ICE).
    private static final Set<Block> FOOTPRINT_BLOCKS = Set.of(
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.MUD,
            Blocks.SAND, Blocks.RED_SAND, Blocks.SUSPICIOUS_SAND, Blocks.SOUL_SAND, Blocks.GRAVEL,
            Blocks.SNOW, Blocks.SNOW_BLOCK,
            Blocks.CLAY);

    private final Configuration config;

    // Distance to travel before dropping the next footprint (~one vanilla step).
    private static final double STEP_DISTANCE = 0.9D;

    // Fall distance above which landing drops a pair of footprints. Kept low so an
    // ordinary jump landing also leaves prints.
    private static final double LAND_PRINT_DISTANCE = 0.4D;

    private boolean isRightFoot = false;
    private boolean wasOnGround = true;
    private double lastAirborneFallDistance = 0D;
    private Vec3 lastPos;
    private double walkDistance;

    public FootprintHandler(Configuration config, IModLog logger) {
        this.config = config;
        ClientState.TICK_END.register(this::onTick);
        ClientState.ON_CONNECT.register(this::onConnect);
        ClientState.ON_DISCONNECT.register(this::onDisconnect);
    }

    private void onConnect(Minecraft client) {
        this.lastPos = null;
        this.walkDistance = 0D;
        this.isRightFoot = false;
        this.wasOnGround = true;
        this.lastAirborneFallDistance = 0D;
    }

    private void onDisconnect(Minecraft client) {
        this.lastPos = null;
        this.walkDistance = 0D;
    }

    private void onTick(Minecraft client) {
        if (!this.config.entityEffects.enableFootprints)
            return;
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;

        var player = GameUtils.getPlayer().orElse(null);
        if (player == null || player.isSpectator())
            return;

        if (!(player.level() instanceof ClientLevel world))
            return;

        final Vec3 pos = player.position();

        final boolean onGround = player.onGround();
        if (!onGround) {
            // Airborne: track the fall distance (vanilla resets it on landing).
            this.lastAirborneFallDistance = player.fallDistance;
            this.wasOnGround = false;
            return;
        }

        // Landing: both feet leave prints.
        if (!this.wasOnGround && this.lastAirborneFallDistance > LAND_PRINT_DISTANCE) {
            this.spawnPrint(player, world, pos, true, 0.2D);
            this.spawnPrint(player, world, pos, false, 0.2D);
        }
        this.wasOnGround = true;

        if (this.lastPos == null) {
            this.lastPos = pos;
            return;
        }

        this.walkDistance += Math.hypot(pos.x - this.lastPos.x, pos.z - this.lastPos.z);
        this.lastPos = pos;

        if (this.walkDistance < STEP_DISTANCE)
            return;
        this.walkDistance = 0D;
        this.isRightFoot = !this.isRightFoot;

        this.spawnPrint(player, world, pos, this.isRightFoot, 0.2D);
    }

    /**
     * Drop a single footprint under the player's foot. Use the player's facing for
     * both the left/right offset and the print yaw (the per-tick movement vector is
     * tiny and unnormalized, which collapsed the offset to ~0 and gave a garbage yaw).
     * Only prints when the foot is resting on solid ground; probing down produced
     * prints on the block below when walking off an edge. Using the exact foot
     * position also keeps prints on dirt under tall grass.
     */
    private void spawnPrint(Player player, ClientLevel world, Vec3 pos, boolean isRight, double distance) {
        // Use the horizontal facing (yaw) for the offset/yaw: getLookAngle() includes
        // pitch, so looking down (e.g. when landing) collapsed the left/right offset
        // to ~0 and the landing pair overlapped.
        final float yaw = player.getYRot();
        final double yawRad = Math.toRadians(yaw);
        final double dx = -Math.sin(yawRad); // horizontal look X
        final double dz = Math.cos(yawRad);  // horizontal look Z
        final double offset = isRight ? distance : -distance;
        final double x = pos.x - dz * offset;
        final double z = pos.z + dx * offset;

        // The print is offset sideways from the player, so probe the block under the
        // print's own x/z (not the player's foot) - a print on a block edge would
        // otherwise hang over the adjacent air and float in mid-air. Use the player's
        // foot level as the reference height. The foot rests on the topmost surface at
        // or just below it; the ±0.5 window means only the foot level and the level
        // below can qualify, and descending from the foot level the first hit is the
        // highest surface (snow top wins over the block below it, and a gap over a
        // block edge is rejected because its surface is far below the foot).
        final int px = Mth.floor(x);
        final int pz = Mth.floor(z);
        final double referenceY = pos.y;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int py = Mth.floor(referenceY); py > Mth.floor(referenceY) - 2; py--) {
            probe.set(px, py, pz);
            var probeState = world.getBlockState(probe);
            if (!FOOTPRINT_BLOCKS.contains(probeState.getBlock()))
                continue;
            // Use the visual shape (getShape), not the collision shape: a snow layer's
            // collision box is one layer lower than its visible surface (LAYERS-1 vs
            // LAYERS), so the collision top for 1 layer is 0 and 2 layers reads as 1
            // layer - prints would sit on the wrong (lower) surface. The visible shape
            // gives the actual snow surface the player sees.
            var shape = probeState.getShape(world, probe);
            if (shape.isEmpty())
                continue;
            double surfaceY = py + shape.max(Direction.Axis.Y);
            double gap = referenceY - surfaceY;
            if (gap > -0.5D && gap <= 0.5D) {
                // Sit the print on the block's visible surface (snow layer top), not the
                // player's foot which sinks slightly into the snow.
                var y = surfaceY;
                var particle = new FootprintParticle(this.config.entityEffects.footprintStyle, isRight, (float) yawRad, world, x, y, z);
                GameUtils.getParticleManager().add(particle);
                return;
            }
        }
    }
}
