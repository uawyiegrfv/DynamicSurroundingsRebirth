package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.effects.particles.FootprintParticle;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.tags.BlockEffectTags;

import java.util.HashMap;
import java.util.Map;

/**
 * Leaves footprints under entities while they walk, ported from the original 1.12.2
 * {@code EntityFootprintEffect}. Instead of depending on the vanilla step event (which is gated on the
 * brush-step entity tag and accent settings), this accumulates horizontal movement each tick and drops
 * a left/right alternating footprint about every step, matching the vanilla step cadence.
 *
 * <p><b>Every living entity, not just the player.</b> 1.12.2 registered this effect for any
 * {@code EntityLivingBase} and merely had a {@code PlayerFootprintEffect} subclass for the player's
 * special cases. This port tracked only the player, so villagers, animals and mobs left nothing -
 * which is a visible regression against the original. The player keeps its own path because its
 * special cases differ (spectator, first person), and creatures are gated on their own config key so
 * the extra particles can be turned off.
 */
public class FootprintHandler {

    // Which blocks take a footprint is DATA, not a hardcoded list: the handler consults
    // #dsurround:effects/footprintable so a mod can opt its own soft ground in, and a
    // modpack can retune the set through a resource pack.

    private final Configuration config;

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);
    private static final org.orecruncher.dsurround.config.libraries.impl.VariatorLibrary VARIATORS =
            ContainerManager.resolve(org.orecruncher.dsurround.config.libraries.impl.VariatorLibrary.class);
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> FOOTPRINTABLE =
            BlockEffectTags.FOOTPRINTABLE;

    // Distance to travel before dropping the next footprint (~one vanilla step).
    private static final double STEP_DISTANCE = 0.9D;

    // Fall distance above which landing drops a pair of footprints. Kept low so an
    // ordinary jump landing also leaves prints.
    private static final double LAND_PRINT_DISTANCE = 0.4D;

    // Lateral offset of each foot's print from the walking axis; the spacing
    // between the two feet is twice this value.  Reduced from the original 0.2
    // (user request: prints a little closer together).
    private static final double FOOT_SPACING = 0.15D;

    /**
     * Per-entity walking state. One instance per tracked entity, keyed by entity id and pruned when the
     * entity leaves or dies, so a mob that walks away does not leave state behind.
     */
    private static final class Track {
        boolean isRightFoot;
        boolean wasOnGround = true;
        double lastAirborneFallDistance;
        Vec3 lastPos;
        double walkDistance;

        void reset() {
            this.isRightFoot = false;
            this.wasOnGround = true;
            this.lastAirborneFallDistance = 0D;
            this.lastPos = null;
            this.walkDistance = 0D;
        }
    }

    private final Map<Integer, Track> tracks = new HashMap<>();

    // Reused across ticks so the per-tick entity sweep allocates nothing per entity.
    private final java.util.List<Integer> seen = new java.util.ArrayList<>();

    /**
     * The entity's Variator, which carries its print size and whether it prints at all.
     *
     * <p>Mirrors CreatureFootstepGenerator's lookup: the local player always uses the "player"
     * variator, everything else is resolved from its entity type with the player's as the fallback.
     */
    private static org.orecruncher.dsurround.config.Variator variatorFor(final LivingEntity entity) {
        if (entity instanceof Player)
            return VARIATORS.getPlayerVariator();
        return VARIATORS.getEntityVariator(
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                .map(VARIATORS::getVariator)
                .orElseGet(VARIATORS::getPlayerVariator);
    }

    public FootprintHandler(Configuration config, IModLog logger) {
        this.config = config;
        ClientState.TICK_END.register(this::onTick);
        ClientState.ON_CONNECT.register(this::onConnect);
        ClientState.ON_DISCONNECT.register(this::onDisconnect);
    }

    private void onConnect(Minecraft client) {
        this.tracks.clear();
    }

    private void onDisconnect(Minecraft client) {
        this.tracks.clear();
    }

    private void onTick(Minecraft client) {
        final boolean playerEnabled = this.config.entityEffects.enableFootprints;
        final boolean creatureEnabled = this.config.entityEffects.enableCreatureFootprints;
        if (!playerEnabled && !creatureEnabled)
            return;
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;

        var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;

        if (!(player.level() instanceof ClientLevel world))
            return;

        // The player first, so its own prints are unaffected by anything below.
        if (playerEnabled && !player.isSpectator())
            this.process(player, world, true);

        if (!creatureEnabled) {
            // Drop any creature state so re-enabling cannot resume from a stale position and drop a
            // print a long way from where the creature actually is.
            if (this.tracks.size() > (playerEnabled ? 1 : 0))
                this.tracks.keySet().removeIf(id -> id != player.getId());
            return;
        }

        // Creatures: the same scan the other entity effects use. A modest radius keeps the per-tick
        // cost proportional to what is actually on screen.
        final double range = Math.min(48.0D, this.config.entityEffects.entityEffectRange);
        this.seen.clear();
        for (var entity : world.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range))) {
            if (entity == player || entity.isRemoved() || !entity.isAlive())
                continue;
            if (entity.isSpectator() || entity.isInvisibleTo(player))
                continue;
            // Only entities actually close enough to see prints from. The inflate above is a box, so
            // its corners reach further than `range`.
            if (entity.distanceToSqr(player) > range * range)
                continue;
            this.seen.add(entity.getId());
            this.process(entity, world, false);
        }

        // Prune state for entities that are gone or no longer near, so the map cannot grow without
        // bound in a busy world.
        if (this.tracks.size() > this.seen.size() + 1) {
            this.tracks.keySet().removeIf(id -> id != player.getId() && !this.seen.contains(id));
        }
    }

    /** Advances one entity's walking state and drops a print when it has moved far enough. */
    private void process(final LivingEntity entity, final ClientLevel world, final boolean isPlayer) {
        // An entity whose variator says it leaves no prints is skipped entirely. variators.json ships
        // hasFootprint per entity, so a pack can already turn prints off for a mob - it simply had no
        // effect, because nothing read the field.
        if (!variatorFor(entity).hasFootprint())
            return;

        final Track track = this.tracks.computeIfAbsent(entity.getId(), id -> new Track());

        final Vec3 pos = entity.position();

        if (!entity.onGround()) {
            // Airborne: track the fall distance (vanilla resets it on landing).
            track.lastAirborneFallDistance = entity.fallDistance;
            track.wasOnGround = false;
            track.lastPos = pos;
            return;
        }

        // Landing: both feet leave prints.
        if (!track.wasOnGround && track.lastAirborneFallDistance > LAND_PRINT_DISTANCE) {
            this.spawnPrint(entity, world, pos, true, FOOT_SPACING);
            this.spawnPrint(entity, world, pos, false, FOOT_SPACING);
        }
        track.wasOnGround = true;

        if (track.lastPos == null) {
            track.lastPos = pos;
            return;
        }

        track.walkDistance += Math.hypot(pos.x - track.lastPos.x, pos.z - track.lastPos.z);
        track.lastPos = pos;

        if (track.walkDistance < STEP_DISTANCE)
            return;
        track.walkDistance = 0D;
        track.isRightFoot = !track.isRightFoot;

        this.spawnPrint(entity, world, pos, track.isRightFoot, FOOT_SPACING);
    }

    /**
     * Drop a single footprint under the entity's foot. Use the entity's facing for
     * both the left/right offset and the print yaw (the per-tick movement vector is
     * tiny and unnormalized, which collapsed the offset to ~0 and gave a garbage yaw).
     * Only prints when the foot is resting on solid ground; probing down produced
     * prints on the block below when walking off an edge. Using the exact foot
     * position also keeps prints on dirt under tall grass.
     */
    private void spawnPrint(LivingEntity entity, ClientLevel world, Vec3 pos, boolean isRight, double distance) {
        // Use the horizontal facing (yaw) for the offset/yaw: getLookAngle() includes
        // pitch, so looking down (e.g. when landing) collapsed the left/right offset
        // to ~0 and the landing pair overlapped.
        final float yaw = entity.getYRot();
        final double yawRad = Math.toRadians(yaw);
        final double dx = -Math.sin(yawRad); // horizontal look X
        final double dz = Math.cos(yawRad);  // horizontal look Z
        final double offset = isRight ? distance : -distance;
        final double x = pos.x - dz * offset;
        final double z = pos.z + dx * offset;

        // The print is offset sideways from the entity, so probe the block under the
        // print's own x/z (not the entity's foot) - a print on a block edge would
        // otherwise hang over the adjacent air and float in mid-air. Use the entity's
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
            // Vegetation the entity walks straight through (grass, flowers, saplings) is
            // not ground - keep looking down so prints still land on the dirt beneath it.
            // This is the only case that may skip a block; see the note below.
            if (FootstepGenerator.isVegetationBlock(probeState))
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
            if (gap <= -0.5D || gap > 0.5D)
                continue;
            // This is the surface the foot is actually resting on, so decide here and do
            // NOT keep probing downward. Skipping a non-printable surface used to fall
            // through to the block underneath it, which drew prints on the ground BELOW
            // slabs (0.5), bottom trapdoors (0.1875), pressure plates (0.0625), carpets
            // (0.0625) and buttons (0.1875) - their tops all sit within the 0.5 window of
            // the block beneath them, so the old loop accepted that block as the surface.
            if (!TAG_LIBRARY.is(FOOTPRINTABLE, probeState))
                return;
            // Sit the print on the block's visible surface (snow layer top), not the
            // entity's foot which sinks slightly into the snow.
            var y = surfaceY;
            // Print size comes from the entity's Variator. variators.json already carries
            // footprintScale per entity (a child's print is smaller, a quadruped's differs), and the
            // value was deserialised but never read - every print was the same size.
            final var variator = variatorFor(entity);
            var particle = new FootprintParticle(this.config.entityEffects.footprintStyle, isRight,
                    (float) yawRad, world, x, y, z, variator.footprintScale());
            GameUtils.getParticleManager().add(particle);
            return;
        }
    }
}
