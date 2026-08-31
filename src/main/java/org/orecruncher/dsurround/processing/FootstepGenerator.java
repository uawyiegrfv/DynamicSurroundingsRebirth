package org.orecruncher.dsurround.processing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.config.libraries.impl.VariatorLibrary;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A2-1: Dynamic Surroundings own footstep generator, ported from the original 1.12.2
 * Generator. Detects walk/run/jump/land states from the local player each tick and
 * plays the block-specific step sound (remapped to DS sounds by the sound mappings),
 * replacing the vanilla step trigger for the player.
 */
public class FootstepGenerator extends AbstractClientHandler {

    private static final ResourceLocation LAND = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "player.land");
    private static final ResourceLocation JUMP = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "player.jump");
    private static final ISoundLibrary SOUND_LIBRARY = ContainerManager.resolve(ISoundLibrary.class);
    private static final org.orecruncher.dsurround.config.libraries.IItemLibrary ITEM_LIBRARY =
            ContainerManager.resolve(org.orecruncher.dsurround.config.libraries.IItemLibrary.class);

    // Stride, cadence and volume come from the player's Variator (variators.json). The run
    // stride is a small increment over the walk stride, like the original (which had a single
    // stride). Kept as fields so the data-driven variator can be overridden.
    private static final VariatorLibrary VARIATORS = ContainerManager.resolve(VariatorLibrary.class);

    // For a deliberate jump, the fall distance that qualifies as a heavy landing. A jump in
    // place falls ~1.25 blocks; jumping up one block lands on a higher platform and falls
    // only ~0.25, which should be a normal step.
    private static final float JUMP_LAND_DISTANCE_MIN = 0.9F;
    // Landing echo: delay in ticks (1 = ~50ms, the 1.12.2 land compositions' delay -
    // at 50ms the echo fuses with the primary thud into ONE heavier hit; at 100ms it
    // separated into a second equal-volume hit, killing the landing/step hierarchy)
    // and the echo volume relative to the primary.
    // Landing echo delay. The 1.12.2 data is a fixed 50ms (mcp.json "delay": 50 →
    // delayMin = delayMax), but its SoundPlayer queues the echo with a millisecond due
    // time and only checks the queue once per tick (SoundPlayer.think), so the real
    // playback delay is 50-100ms depending on where the due time falls in the tick, and
    // the echo is dropped entirely when the client hitches. Sampling 1-2 ticks per
    // landing reproduces that interval variation (one sample per landing - both feet
    // and the armor echo share it, like sounds due in the same tick window).
    private static final int LAND_ECHO_DELAY_MIN_TICKS = 1;
    private static final int LAND_ECHO_DELAY_MAX_TICKS = 2;
    private static final float LAND_ECHO_VOLUME = 1.0F;
    // Landing-only gain boost. The engine clamps each voice's gain at 1.0 and the walk
    // step already carries the variator multiplier, so the landing thud needs real
    // headroom of its own to read heavier than a step. Boosting only playLand keeps
    // walk/run volumes untouched; 1.6 puts a 0.6-volume landing primary at ~0.96 per
    // voice - just under the clamp - restoring the landing>step hierarchy.
    private static final float LAND_GAIN_BOOST = 1.78F;
    // Lateral offset of each foot from the block centre when a landing plays, ported
    // from the 1.12.2 findAssociation DISTANCE_TO_CENTER.
    private static final double FOOT_LATERAL_OFFSET = 0.2D;
    // Climbing steps play the vanilla surface step sound; the boost was left at 1.0
    // (no amplification) after user feedback that louder values were too strong.
    private static final float CLIMB_VOLUME_BOOST = 1.0F;

    private static float strideWalk() { return VARIATORS.getPlayerVariator().stride(); }
    private static float strideRun() { return VARIATORS.getPlayerVariator().stride() * 1.06F; }
    private static float strideLadder() { return VARIATORS.getPlayerVariator().strideLadder(); }
    private static float landHardDistanceMin() { return VARIATORS.getPlayerVariator().landHardDistanceMin(); }
    private static float footstepVolume() { return VARIATORS.getPlayerVariator().volumeScale(); }
    // Config-driven volume multiplier applied to every footstep sound (sound-options slider).
    private float dsFootstepVolume() { return (float) this.config.soundOptions.footstepVolume; }

    // Per-material landing composition ported from the original 1.12.2 mcp.json land
    // entries: primary "thud" + optional walk layer at 50% + delayed echo. Keyed on the
    // resolved footstep material factory path. The primary is often a heavier material's
    // run sound (e.g. concrete_run for stone), not the material's own.
    private record LandComposition(ResourceLocation primary, ResourceLocation secondary, ResourceLocation echo) {}

    private static ResourceLocation fs(String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
    }

    // Shared compositions reused by multiple materials (identical land behaviour).
    private static final LandComposition WOOD_LAND = new LandComposition(fs("footsteps.wood"), null, fs("footsteps.wood"));
    private static final LandComposition STONE_LAND = new LandComposition(fs("footsteps.concrete_run"), fs("footsteps.stone"), fs("footsteps.stone_run"));
    // 1.12.2 grass land = [grass_run, delayed(50ms) grass_run] - NO walk layer: the
    // walk recording is literally a footstep, and embedding it in the landing made
    // the landing read as "another footstep" (the grass step/land similarity).
    private static final LandComposition GRASS_LAND = new LandComposition(fs("footsteps.grass_run"), null, fs("footsteps.grass_run"));
    private static final LandComposition BLUNTWOOD_LAND = new LandComposition(fs("footsteps.bluntwood"), null, fs("footsteps.bluntwood"));

    private static final Map<String, LandComposition> LAND_COMPOSITIONS = Map.ofEntries(
            Map.entry("footsteps.stone", new LandComposition(fs("footsteps.concrete_run"), fs("footsteps.stone"), fs("footsteps.stone_run"))),
            Map.entry("footsteps.dirt", new LandComposition(fs("footsteps.dirt_land"), fs("footsteps.dirt"), fs("footsteps.dirt_run"))),
            Map.entry("footsteps.grass", GRASS_LAND),
            Map.entry("footsteps.gravel", new LandComposition(fs("footsteps.gravel_run"), fs("footsteps.gravel"), fs("footsteps.gravel_run"))),
            Map.entry("footsteps.sand", new LandComposition(fs("footsteps.sand_run"), fs("footsteps.sand"), fs("footsteps.sand_run"))),
            Map.entry("footsteps.snow", new LandComposition(fs("footsteps.snow_run"), fs("footsteps.snow"), fs("footsteps.snow_run"))),
            Map.entry("footsteps.wood", WOOD_LAND),
            Map.entry("footsteps.log", WOOD_LAND),
            Map.entry("footsteps.rug", new LandComposition(fs("footsteps.rug"), null, fs("footsteps.rug"))),
            Map.entry("footsteps.metalbar", new LandComposition(fs("footsteps.metalbar"), null, fs("footsteps.metalbar"))),
            Map.entry("footsteps.metalbox", new LandComposition(fs("footsteps.metalbox"), null, fs("footsteps.metalbox"))),
            Map.entry("footsteps.squeakywood", new LandComposition(fs("footsteps.squeakywood"), fs("footsteps.squeakywood"), fs("footsteps.wood"))),
            Map.entry("footsteps.weakice", new LandComposition(fs("footsteps.weakice"), fs("footsteps.weakice"), fs("footsteps.weakice"))),
            Map.entry("footsteps.bluntwood", BLUNTWOOD_LAND),
            Map.entry("footsteps/ladder", new LandComposition(fs("footsteps.bluntwood"), fs("footsteps.bluntwood"), fs("footsteps.bluntwood"))),
            Map.entry("footsteps.mud", new LandComposition(fs("footsteps.mud"), null, fs("footsteps.mud"))),
            Map.entry("footsteps.quicksand", new LandComposition(fs("footsteps.sand_run"), fs("footsteps.quicksand"), fs("footsteps.quicksand"))),
            Map.entry("footsteps.muffledice", STONE_LAND),
            Map.entry("footsteps.glass", new LandComposition(fs("footsteps.wood"), fs("footsteps.glass"), fs("footsteps.wood"))),
            Map.entry("footsteps.marble", new LandComposition(fs("footsteps.marble_run"), fs("footsteps.marble"), fs("footsteps.marble_run"))),
            Map.entry("footsteps.concrete", new LandComposition(fs("footsteps.concrete_run"), fs("footsteps.concrete"), fs("footsteps.concrete_run"))),
            Map.entry("footsteps.lino", STONE_LAND),
            Map.entry("footsteps.organic", new LandComposition(fs("footsteps.dirt_land"), fs("footsteps.mud"), fs("footsteps.mud"))),
            // Dry organic matter (pumpkins, mushroom blocks, cocoa, cake) lands with a
            // grass-like thud in the original (organic_dry), not the muddy organic.
            Map.entry("footsteps.organic_dry", GRASS_LAND),
            // Grass paths land like grass (1.12.2: minecraft:grass_path -> grass).
            // NOTE: the dirt_path factory location uses a slash (footsteps/dirt_path),
            // unlike most materials which use dots — the key must match the resolved
            // material path exactly.
            Map.entry("footsteps/dirt_path", GRASS_LAND),
            Map.entry("footsteps.leaves_through", new LandComposition(fs("footsteps.dirt_land"), fs("footsteps.dirt"), fs("footsteps.dirt_run"))),
            // Leaf litter lands with a single heavier crunch - a dedicated landing recording
            // for the primary, no secondary layer and no delayed echo.
            Map.entry("footsteps.leaves_crunch", new LandComposition(fs("footsteps.leaves_crunch_land"), null, null)));

    private final IAudioPlayer audioPlayer;

    private boolean isFlying = false;
    private boolean wasRunning = false;
    private boolean didJump = false;
    private double fallDistance = 0D;
    private boolean isRightFoot = false;
    private double distanceWalked = 0D;
    private double dmwBase = 0D;
    private double yPosition = 0D;
    private Vec3 lastPos;
    // Leaf-litter steps play once per block position (mirroring the brush-step "messyPos"
    // dedup in StepThroughBrushEffect): lingering on the same litter cell does not
    // re-trigger the crunch on every stride - it fires again only after leaving the
    // cell and re-entering (the position changes).
    private BlockPos lastLeafLitterPos;

    // Delayed landing echo: the land layers play again ~1-2 ticks (50-100ms) later,
    // matching the real playback distribution of the 1.12.2 delayed land composition
    // (see LAND_ECHO_DELAY_*_TICKS above).
    private record PendingEcho(net.minecraft.client.resources.sounds.SoundInstance sound, long playTick) {}

    private final ArrayDeque<PendingEcho> pendingEchoes = new ArrayDeque<>(8);
    private long tickCount = 0;

    public FootstepGenerator(Configuration config, IAudioPlayer audioPlayer, IModLog logger) {
        super("Footstep Generator", config, logger);
        this.audioPlayer = audioPlayer;
    }

    @Override
    public void process(final Player player) {
        this.tickCount++;

        // Master switch: when footsteps are disabled (or the footstep volume slider is
        // at zero, restoring the vanilla footsteps), drop any pending echoes so they
        // don't fire after re-enabling.
        if (!this.config.entityEffects.enableFootstepSounds || this.config.soundOptions.footstepVolume <= 0) {
            this.pendingEchoes.clear();
            return;
        }

        // Play any delayed landing echoes whose time has come (scheduled by playLand).
        while (!this.pendingEchoes.isEmpty() && this.pendingEchoes.peek().playTick() <= this.tickCount) {
            this.audioPlayer.play(this.pendingEchoes.poll().sound());
        }

        final Vec3 pos = player.position();
        final boolean onGround = player.onGround();
        final boolean onLadder = player.onClimbable();
        final boolean inWater = player.isInWater();
        final boolean sneaking = player.isShiftKeyDown();

        // Airborne / landing state. Leaving the ground with upward motion is a deliberate
        // jump - remember it so landing from a jump always plays the heavy landing sound.
        // Landing: a deliberate jump or a genuine fall plays the material landing sound,
        // while a small drop (stepping down a block while moving) plays a normal step.
        if ((onGround || onLadder) == this.isFlying) {
            this.isFlying = !this.isFlying;
            if (this.isFlying) {
                if (player.getDeltaMovement().y > 0) {
                    this.didJump = true;
                    if (this.config.entityEffects.enablePlayerJumpSound && VARIATORS.getPlayerVariator().playJump() && !sneaking) {
                        this.playJump(player);
                    }
                }
            } else {
                // A deliberate jump gets the heavy landing only if there was a real fall
                // (jumping up one block falls little and should be a normal step). A non-jump
                // fall needs to be a genuine drop (1.5 blocks) for the heavy landing.
                final boolean heavyLand = this.didJump
                        ? this.fallDistance > JUMP_LAND_DISTANCE_MIN
                        : this.fallDistance > landHardDistanceMin();
                if (this.config.entityEffects.enablePlayerLandSound && heavyLand && !sneaking) {
                    this.playLand(player);
                } else if (this.fallDistance > 0 && !sneaking) {
                    // Small fall / step down a block: play the material's normal walk sound.
                    this.playStep(player, this.wasRunning);
                }
                this.didJump = false;
                // Reset so the descent doesn't double as a stride step.
                this.lastPos = pos;
                this.yPosition = pos.y;
            }
        }
        if (this.isFlying)
            this.fallDistance = player.fallDistance;

        // Walking / running: accumulate horizontal distance (scaled like the original's
        // distanceWalkedOnStepModified *= 0.6) and step at stride intervals. Stepping DOWN
        // one block is detected explicitly below (reliable) rather than via distance.
        // Water is deliberately excluded: swimming/suspending a block off the riverbed
        // (onGround == false) must not generate footsteps, only actually walking on the
        // ground (onGround) or climbing does.
        if (onGround || onLadder) {
            double step = 0D;
            if (this.lastPos != null) {
                final double dx = pos.x - this.lastPos.x;
                final double dz = pos.z - this.lastPos.z;
                step = Math.hypot(dx, dz);
                // Climbing a ladder is mostly vertical motion; the horizontal distance
                // alone never accumulates enough to trigger a step while climbing.
                if (onLadder)
                    step += Math.abs(pos.y - this.lastPos.y);
                this.distanceWalked += step * 0.6D;
            }
            this.lastPos = pos;

            // Running is based on the player's sprint state (reliable - a speed threshold
            // sits below normal walking speed and made brisk walking play the heavier run
            // sounds intermittently). Normal walking always uses walk sounds.
            final boolean running = player.isSprinting();
            this.wasRunning = running;

            // Stepped down one block (the ground level dropped more than a slab)? Play a
            // step immediately - this is what makes walking down stairs/ledges reliable.
            boolean steppedDown = onGround && !inWater && !sneaking && this.yPosition - pos.y > 0.4;
            if (steppedDown) {
                this.playStep(player, running);
                this.dmwBase = this.distanceWalked;
            } else {
                final float stride = onLadder && !onGround ? strideLadder() : (running ? strideRun() : strideWalk());
                if (this.distanceWalked - this.dmwBase > stride) {
                    this.playStep(player, running);
                    this.dmwBase = this.distanceWalked;
                }
            }
        } else {
            this.lastPos = pos;
        }

        if (onGround)
            this.yPosition = pos.y;
    }

    private void playStep(final Player player, final boolean running) {
        if (player.isSpectator() || player.isSilent())
            return;

        final boolean climbing = player.onClimbable() && !player.onGround();
        final var pos = player.blockPosition().below();
        // While climbing, the surface is the climbable block the player is inside
        // (ladder/vine/bamboo/...), not whatever resolveSurfaceBlock finds below.
        final var state = climbing
                ? player.level().getBlockState(player.blockPosition())
                : resolveSurfaceBlock(player, player.level(), pos);
        if (this.logger.isDebugging())
            this.logger.debug("Step at %s, pos %s, state %s, footY %.3f", player.blockPosition(), pos, state, player.position().y);
        if (state.isAir() || !state.getFluidState().isEmpty())
            return;

        // Raise the step event so accent handlers (armor clank, floor squeak, wet surface)
        // play alongside the generated step. The vanilla step event does not reach the tail
        // injector because the player's vanilla step is cancelled to avoid double sounds.
        ClientEventHooks.ENTITY_STEP_EVENT.raise().onStep(player, pos, state);

        final var stepSound = state.getSoundType().getStepSound();
        ResourceLocation soundLoc = stepSound.getLocation();
        var accents = List.<ResourceLocation>of();

        // Climbing (ladder/vine/bamboo/...) plays the vanilla surface step sound louder,
        // matching the original 1.12.2 mod, instead of the DS per-material replacement.
        if (!climbing) {
            // If the step sound is remapped to a DS footstep material, play that material
            // sound directly so we can pick the walk/run variant. Running uses the material's
            // *_run sound event when one exists, giving the heavier cadence of the original.
            var remap = SOUND_LIBRARY.getRemappedSound(stepSound, state);
            if (remap.isPresent()) {
                soundLoc = remap.get().factory();
                accents = remap.get().accents();
                if (running) {
                    // getSound() returns the MISSING placeholder for unregistered sounds, so use
                    // isSoundRegistered() to detect the run variant actually exists.
                    var runLoc = materialVariant(soundLoc, "_run");
                    if (runLoc != null)
                        soundLoc = runLoc;
                }
            }
        }

        // Leaf litter follows the brush-step "messyPos" convention: play only once per
        // block position so an entity walking around within the same litter cell does not
        // re-trigger the crunch every stride. It fires again only after the entity leaves
        // the cell and re-enters (the position changes).
        if (false /* LeafLitterBlock is 1.20.3+; not in 1.20.1 */) {
            var feetPos = player.blockPosition();
            if (feetPos.equals(this.lastLeafLitterPos))
                return;
            this.lastLeafLitterPos = feetPos;
        }

        var feetPos = player.blockPosition();
        // Resolve the factory by its location (sound_factories.json maps the location to
        // a sound event). Some step materials have a dedicated factory whose location is
        // not itself a sound event (e.g. footsteps/dirt_path -> footsteps.gravel), so
        // SoundFactoryBuilder.create(soundLoc) would look it up as an event and play the
        // MISSING placeholder (silent). Use the factory registry instead.
        var sound = SOUND_LIBRARY.getSoundFactoryOrDefault(soundLoc)
                // Play at the player's feet block so the sound remap (which looks at the block
                // below the sound position) resolves the surface block we are standing on.
                // Base footstep volume is below the landing volume so the landing stands out
                // (the original played steps at ~0.4 scale and the landing at full).
                .createAtLocation(feetPos, footstepVolume() * dsFootstepVolume() * (climbing ? CLIMB_VOLUME_BOOST : 1.0F));
        this.audioPlayer.play(sound);

        // Layer the simultaneous accents on top of the main step sound (e.g. subtle brush
        // rustle on grass, leaves rustle on leaves), matching the original 1.12.2 mcp.json
        // simultaneous acoustic compositions.
        for (var accent : accents) {
            var accentSound = SOUND_LIBRARY.getSoundFactoryOrDefault(accent)
                    .createAtLocation(feetPos, dsFootstepVolume());
            this.audioPlayer.play(accentSound);
        }
    }

    private void playLand(final Player player) {
        // 1.12.2 hard landings go through playMultifoot(): the land composition is
        // evaluated once per foot, so TWO independent voices play simultaneously (plus
        // two more when the delayed layer fires) and SUM in the mixer. That channel
        // summation is the only way a landing can read heavier than a footstep: the
        // engine clamps a single voice's gain at 1.0 (SoundVolumeEvaluator.getAdjustedVolume),
        // so any volume multiplier above 1.0 - in JSON or in code - is a silent no-op.
        // This is why every previous attempt to "raise the landing volume" had no effect.
        final float scale = footstepVolume() * dsFootstepVolume() * LAND_GAIN_BOOST;
        final int echoDelay = LAND_ECHO_DELAY_MIN_TICKS
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(LAND_ECHO_DELAY_MAX_TICKS - LAND_ECHO_DELAY_MIN_TICKS + 1);
        var feetPos = player.blockPosition();
        var material = resolveMaterial(player);

        // Left/right foot positions: the block centre offset 0.2 blocks laterally off
        // the facing direction (1.12.2 findAssociation semantics).
        var feetCenter = Vec3.atCenterOf(feetPos);
        double yawRad = Math.toRadians(player.getYRot());
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        var leftFoot = feetCenter.add(-rightX * FOOT_LATERAL_OFFSET, 0, -rightZ * FOOT_LATERAL_OFFSET);
        var rightFoot = feetCenter.add(rightX * FOOT_LATERAL_OFFSET, 0, rightZ * FOOT_LATERAL_OFFSET);

        var comp = material.flatMap(m -> Optional.ofNullable(LAND_COMPOSITIONS.get(m.getPath()))).orElse(null);
        if (comp != null) {
            // Per-foot playback of the material composition: primary thud x2 + walk@50
            // layer x2 + delayed echo x2, exactly like playMultifoot + the 1.12.2 land
            // entries in mcp.json.
            var primary = SOUND_LIBRARY.getSoundFactoryOrDefault(comp.primary());
            this.audioPlayer.play(primary.createAtLocation(leftFoot, scale));
            this.audioPlayer.play(primary.createAtLocation(rightFoot, scale));
            if (comp.secondary() != null) {
                var secondary = SOUND_LIBRARY.getSoundFactoryOrDefault(comp.secondary());
                this.audioPlayer.play(secondary.createAtLocation(leftFoot, 0.5F * scale));
                this.audioPlayer.play(secondary.createAtLocation(rightFoot, 0.5F * scale));
            }
            if (comp.echo() != null) {
                var echo = SOUND_LIBRARY.getSoundFactoryOrDefault(comp.echo());
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocation(leftFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocation(rightFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
            }
        } else {
            // Fallback: material's own land/run + walk@50 + echo, also per foot.
            var landLoc = resolveLandSound(player);
            var baseLoc = landLoc;
            if (landLoc.getPath().endsWith("_land")) {
                baseLoc = ResourceLocation.fromNamespaceAndPath(landLoc.getNamespace(), landLoc.getPath().substring(0, landLoc.getPath().length() - 5));
            } else if (landLoc.getPath().endsWith("_run")) {
                baseLoc = ResourceLocation.fromNamespaceAndPath(landLoc.getNamespace(), landLoc.getPath().substring(0, landLoc.getPath().length() - 4));
            }
            var primary = SOUND_LIBRARY.getSoundFactoryOrDefault(landLoc);
            this.audioPlayer.play(primary.createAtLocation(leftFoot, scale));
            this.audioPlayer.play(primary.createAtLocation(rightFoot, scale));
            if (!baseLoc.equals(landLoc)) {
                var base = SOUND_LIBRARY.getSoundFactoryOrDefault(baseLoc);
                this.audioPlayer.play(base.createAtLocation(leftFoot, 0.5F * scale));
                this.audioPlayer.play(base.createAtLocation(rightFoot, 0.5F * scale));
            }
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocation(leftFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocation(rightFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
        }

        // Armor clank on landing - play the effective armor's walk accent now and a delayed
        // run accent echo, matching the original armor_medium/heavy land composition. The
        // effective armor is the first non-empty slot (feet, then legs, then chest).
        var armor = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
        if (armor.isEmpty())
            armor = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS);
        if (armor.isEmpty())
            armor = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        ITEM_LIBRARY.getEquipableStepAccentSound(armor)
                .ifPresent(f -> this.audioPlayer.play(f.createAtLocation(feetPos, dsFootstepVolume())));
        ITEM_LIBRARY.getEquipableStepAccentSoundRun(armor)
                .ifPresent(f -> this.pendingEchoes.add(new PendingEcho(f.createAtLocation(feetPos, LAND_ECHO_VOLUME * dsFootstepVolume()), this.tickCount + echoDelay)));
    }

    /**
     * Resolves the footstep material factory for the block below the player (for looking up
     * its landing composition), or empty if no remap applies.
     */
    private static Optional<ResourceLocation> resolveMaterial(final Player player) {
        var state = resolveSurfaceBlock(player, player.level(), player.blockPosition().below());
        if (state.isAir() || !state.getFluidState().isEmpty())
            return Optional.empty();
        var stepSound = state.getSoundType().getStepSound();
        return SOUND_LIBRARY.getRemappedSound(stepSound, state).map(r -> r.factory());
    }

    /**
     * Resolves the block the player is standing on. If the position below is air (the player
     * is hanging over a block edge), scans horizontally for the nearest solid block - the
     * same edge-handling the sound remapping uses. Prefers vanilla's precise supporting
     * block (mainSupportingBlockPos, resolved via collision boxes) when the player is on
     * the ground, which correctly picks the block actually stood on even when the player
     * straddles an edge next to a snow layer in the row below.
     */
    private static BlockState resolveSurfaceBlock(Player player, Level level, BlockPos pos) {
        // Priority order (see the footstep material resolution):
        // 1. A snow layer or leaf litter the player's feet are in (pos.above()). The player
        //    stands on these and their step sound must win over the block below. Vanilla's
        //    mainSupportingBlockPos cannot be used for this: a 1-layer snow and leaf litter
        //    are registered noCollision() (an almost empty collision box), so collision
        //    detection reports the block underneath.
        // 2. Vanilla's collision-derived support block (mainSupportingBlockPos). This is the
        //    most reliable "what is the player actually standing on" answer: it uses the
        //    entity's collision box against block shapes, so thin/partial blocks
        //    (trapdoors, doors, buttons, pressure plates, carpets) that merely neighbour
        //    the feet never get misreported as the walked surface (the naive pos /
        //    pos.above() heuristics would grab them whenever the player stands at an edge).
        // 3. The block the player's feet are in (pos.above()) - handles other visible
        //    non-solid surfaces. Restricted to blocks with a visible shape and not
        //    vegetation (tall grass has a shape but is walked through).
        // 4. The block directly below the feet (pos).
        // 5. Horizontal scan for the nearest solid block when the player is over an edge.

        var footState = level.getBlockState(pos.above());
        if (footState.getFluidState().isEmpty()
                && (footState.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock
                        || false /* LeafLitterBlock 1.20.3+ */))
            return footState;

        // The single pos.above() probe above misses the block-edge case: standing at the
        // edge of a snow layer / leaf litter patch, the feet overlap two cells and
        // blockPosition() (floor of the feet centre) can land on the snow-free neighbour,
        // so the surface falls through to the block underneath. A 1-layer snow has a
        // zero-height collision shape (getCollisionShape uses SHAPES[LAYERS-1]) and leaf
        // litter is noCollision(), so vanilla's collision-derived mainSupportingBlockPos
        // can't see them either. Scan every cell the feet actually overlap, like vanilla's
        // findSupportingBlock does with the entity AABB, but using the visual surface.
        var feetBox = player.getBoundingBox();
        int xMin = Mth.floor(feetBox.minX);
        int xMax = Mth.floor(feetBox.maxX - 1.0E-3D);
        int zMin = Mth.floor(feetBox.minZ);
        int zMax = Mth.floor(feetBox.maxZ - 1.0E-3D);
        int feetY = pos.getY() + 1;
        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                var cell = level.getBlockState(new BlockPos(x, feetY, z));
                if (cell.getFluidState().isEmpty()
                        && (cell.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock
                                || false /* LeafLitterBlock 1.20.3+ */)) {
                    return cell;
                }
            }
        }

        var supportPos = player.mainSupportingBlockPos.orElse(null);
        if (supportPos != null && player.onGround()) {
            var support = level.getBlockState(supportPos);
            if (!support.isAir() && support.getFluidState().isEmpty())
                return support;
        }

        if (!footState.isAir() && footState.getFluidState().isEmpty()
                && !(footState.getBlock() instanceof net.minecraft.world.level.block.BushBlock /* VegetationBlock is 1.20.5+ */)
                && !footState.getShape(level, pos.above()).isEmpty())
            return footState;

        var state = level.getBlockState(pos);
        if (!state.isAir() && state.getFluidState().isEmpty())
            return state;

        if (state.isAir()) {
            for (var dir : Direction.Plane.HORIZONTAL) {
                var neighbor = level.getBlockState(pos.relative(dir));
                if (!neighbor.isAir() && neighbor.isSolid()) {
                    state = neighbor;
                    break;
                }
            }
        }
        return state;
    }

    /**
     * Resolves the landing sound for the block below the player. Prefers the material's
     * dedicated *_land recording (distinct "thud"), then the *_run sound, then the base
     * sound - matching the original 1.12.2 land composition. Falls back to the generic
     * player.land when no remap applies.
     */
    private ResourceLocation resolveLandSound(final Player player) {
        var state = resolveSurfaceBlock(player, player.level(), player.blockPosition().below());
        if (state.isAir() || !state.getFluidState().isEmpty())
            return LAND;

        var stepSound = state.getSoundType().getStepSound();
        var remap = SOUND_LIBRARY.getRemappedSound(stepSound, state);
        if (remap.isPresent()) {
            var material = remap.get().factory();
            var landLoc = materialVariant(material, "_land");
            if (landLoc != null)
                return landLoc;
            var runLoc = materialVariant(material, "_run");
            if (runLoc != null)
                return runLoc;
            return material;
        }
        return LAND;
    }

    /**
     * Returns the material sound with the given suffix (e.g. footsteps.snow + _run =
     * footsteps.snow_run) if it is registered, or null if it doesn't exist. Some
     * materials have no run/land/wander recording.
     */
    @Nullable
    private static ResourceLocation materialVariant(ResourceLocation material, String suffix) {
        var variant = ResourceLocation.fromNamespaceAndPath(material.getNamespace(), material.getPath() + suffix);
        return SOUND_LIBRARY.isSoundRegistered(variant) ? variant : null;
    }

    private void playJump(final Player player) {
        // A2-9: Match the original 1.12.2 two-layer jump: the generic "grunt"
        // (dsurround:player.jump) plus a material-specific wander sound for the
        // block below, mirroring the original's simulateJumpingLanding which
        // played the _JUMP acoustic and the material's jump acoustic. Both
        // layers resolve through the JSON factory registry so their configured
        // pitch randomization applies (a bare SoundFactoryBuilder always played
        // at a constant pitch, which is why jumps had no variation).
        var grunt = SOUND_LIBRARY.getSoundFactoryOrDefault(JUMP).createAsAdditional();
        this.audioPlayer.play(grunt);

        // Material-specific jump sound (e.g. snow_wander on snow, stone_wander on
        // stone). resolveMaterial() gives the footstep material factory; the wander
        // variant is the same path with a _wander suffix. Not every material has a
        // wander recording - those simply skip the extra layer.
        resolveMaterial(player).ifPresent(material -> {
            var wanderLoc = materialVariant(material, "_wander");
            if (wanderLoc != null) {
                var feetPos = player.blockPosition();
                var wander = SOUND_LIBRARY.getSoundFactoryOrDefault(wanderLoc);
                this.audioPlayer.play(wander.createAtLocation(feetPos, dsFootstepVolume()));
            }
        });
    }

    @Override
    public void onDisconnect() {
        this.pendingEchoes.clear();
        // Reset the motion state as well: this handler is a singleton, and the first tick
        // of the next world would otherwise compute the stride from the old world's
        // coordinates - a phantom step and possibly a bogus hard-landing sound.
        this.wasRunning = false;
        this.didJump = false;
        this.isFlying = false;
        this.fallDistance = 0D;
        this.distanceWalked = 0D;
        this.dmwBase = 0D;
        this.yPosition = 0D;
        this.lastPos = null;
        this.lastLeafLitterPos = null;
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems, "Footsteps: stride walk %.2f run %.2f, dist %.2f".formatted(strideWalk(), strideRun(), this.distanceWalked - this.dmwBase));
    }
}
