package org.orecruncher.dsurround.processing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.config.libraries.impl.VariatorLibrary;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;

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

    /**
     * Reserved factory location meaning "this block produces no footstep at all" - the
     * modern equivalent of the original 1.12.2 NOT_EMITTER sentinel, which its
     * AcousticResolver translated into "return null" (no association, no sound).
     * A rule in sound_mappings.json can point a block, or a whole block tag, at this
     * value to silence it - e.g. #minecraft:buttons, which the original data marked
     * NOT_EMITTER. Both footstep generators honour it.
     */
    public static final ResourceLocation NO_FOOTSTEP = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "footsteps.none");
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
    // Landing echo delay. The 1.12.2 data is a fixed 50ms (mcp.json "delay": 50 ->
    // delayMin = delayMax), but its SoundPlayer queues the echo with a millisecond due
    // time and only checks the queue once per tick (SoundPlayer.think), so the real
    // playback delay is 50-100ms depending on where the due time falls in the tick, and
    // the echo is dropped entirely when the client hitches. Sampling 1-2 ticks per
    // landing reproduces that interval variation (one sample per landing - both feet
    // and the armor echo share it, like sounds due in the same tick window).
    static final int LAND_ECHO_DELAY_MIN_TICKS = 1;
    static final int LAND_ECHO_DELAY_MAX_TICKS = 2;
    static final float LAND_ECHO_VOLUME = 1.0F;
    // Landing-only gain boost. The walk step already carries the variator multiplier,
    // so the landing thud needs real headroom of its own to read heavier than a step.
    // Boosting only playLand keeps walk/run volumes untouched; 1.7 puts a 0.6-volume
    // landing primary at ~1.02 per voice (the engine clamp is 2F - SoundVolumeEvaluator),
    // restoring the landing>step hierarchy.
    private static final float LAND_GAIN_BOOST = 1.7F;
    // Lateral offset of each foot from the block centre when a landing plays, ported
    // from the 1.12.2 findAssociation DISTANCE_TO_CENTER.
    static final double FOOT_LATERAL_OFFSET = 0.2D;
    // Climbing steps play the vanilla surface step sound; the boost was left at 1.0
    // (no amplification) after user feedback that louder values were too strong.
    private static final float CLIMB_VOLUME_BOOST = 1.0F;
    // Feet-to-collision-top tolerance for "is the entity standing ON this cell's block".
    // An eighth of a pixel: any real floor is at most this far below the feet after vanilla
    // resolved the entity position, while a block the entity stands IN (an open trapdoor /
    // door lying on the cell floor at 3/16, a fence, a wall, a piston head) is far above.
    private static final double STANDING_TOLERANCE = 0.05D;
    // 1.12.2 sampled the cell BELOW the feet and consulted the cell above it only for its
    // explicit overlay substrates (carpet / foliage / messy): carpet, snow layer, lily pad,
    // pressure plates, the four rails, tall grass and vines. Doors and trapdoors are NOT in
    // that set, which is why the original never mis-sounded them. The port's feet-cell probe
    // used to accept ANY explicitly mapped block, so a door or trapdoor sharing the player's
    // cell hijacked the step and the landing sound.
    private static final TagKey<net.minecraft.world.level.block.Block> FOOT_OVERLAY =
            org.orecruncher.dsurround.tags.BlockEffectTags.FOOT_OVERLAY;

    private static float strideWalk() { return VARIATORS.getPlayerVariator().stride(); }
    private static float strideRun() { return VARIATORS.getPlayerVariator().stride() * 1.06F; }
    private static float strideLadder() { return VARIATORS.getPlayerVariator().strideLadder(); }

    /**
     * Stride while climbing stairs. 1.12.2 gave stairs their own {@code strideStair} (default
     * {@code stride * 0.65}: shorter, so the cadence is faster) and switched to it whenever the
     * player rose more than 0.4 blocks. The port carried the field but never read it.
     */
    private static float strideStair(final boolean running) {
        final float stair = VARIATORS.getPlayerVariator().strideStair();
        return running ? stair * 1.06F : stair;
    }
    private static float landHardDistanceMin() { return VARIATORS.getPlayerVariator().landHardDistanceMin(); }
    private static float footstepVolume() { return VARIATORS.getPlayerVariator().volumeScale(); }
    // Config-driven volume multiplier applied to every footstep sound (sound-options slider).
    private float dsFootstepVolume() { return (float) this.config.soundOptions.footstepVolume; }

    // Landing / wander / jump compositions used to be the three tables that lived here. They are
    // data now: each material's own entry in sound_factories.json carries an optional "land" block
    // (primary + secondary + echo, with their scales and the echo delay) plus optional "wander" and
    // "jump" cross-references. The material IS the factory, so its definition belongs with it, and a
    // pack can retune a landing without touching the mod. The per-material rationale that used to be
    // in the comments here was carried over into each entry's _comment.
    //
    // The fallback for a material with no "land" block is the block below in playLand: the material's
    // own land/run thud per foot plus a delayed echo, i.e. 1.12.2 playMultifoot without a composition.

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    // Ladder-only test: `#minecraft:climbable` also covers vines, bamboo and scaffolding, which
    // must keep their own surface sound, so it cannot be used directly.
    //
    // The obvious candidate is the conventional `#c:ladders` tag - but NOTHING DEFINES IT. It is not
    // in this repo, it has never been in its git history, Forge 1.20.1 ships no `c:` tags at all, and
    // while NeoForge ships 111/127 `c:` tags, `c:ladders` is not among them. TagLibrary cannot rescue
    // it either: isInCache() only handles DS's own ModTags, so a foreign tag depends entirely on the
    // game's tag registry, where it does not exist. The result was that ladderClimb was always false,
    // the material remap was skipped while climbing, and a modded ladder played a plain wood step -
    // which is precisely what the earlier commit that introduced this tag claimed to fix.
    //
    // So the tag is now a SUPPLEMENT, not the mechanism. LadderBlock covers the vanilla ladder and
    // every modded ladder that extends it, which is the common case; the tag still admits a modded
    // ladder that does not extend it, for packs that define the convention themselves.
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> LADDERS =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath("c", "ladders"));

    /** True for a ladder (which gets the ladder surface sound) as opposed to a vine or scaffolding. */
    private static boolean isLadder(final net.minecraft.world.level.block.state.BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock)
            return true;
        return TAG_LIBRARY.is(LADDERS, state);
    }

    private final IAudioPlayer audioPlayer;

    private boolean isFlying = false;
    private boolean wasRunning = false;
    private boolean didJump = false;
    private double fallDistance = 0D;
    private double distanceWalked = 0D;
    private double dmwBase = 0D;
    private double yPosition = 0D;
    private Vec3 lastPos;

    // Delayed landing echo: the land layers play again ~1-2 ticks (50-100ms) later,
    // matching the real playback distribution of the 1.12.2 delayed land composition
    // (see LAND_ECHO_DELAY_*_TICKS above).
    private record PendingEcho(net.minecraft.client.resources.sounds.SoundInstance sound, long playTick) {}

    private final ArrayDeque<PendingEcho> pendingEchoes = new ArrayDeque<>(8);
    private long tickCount = 0;

    public FootstepGenerator(Configuration config, IAudioPlayer audioPlayer, IModLog logger) {
        super("Footstep Generator", config, logger);

        // Respawn and dimension change. ClientState only raises ON_DISCONNECT when
        // client.player goes null, which neither of those does, so this listener is the only
        // notification the generator gets. The handler is a DI singleton constructed once, so
        // this cannot register twice.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone event) -> this.resetMotionState());
        this.audioPlayer = audioPlayer;
    }

    // 1.12.2 stop-sound mechanic: the dot product of the current motion against the
    // previous tick's motion flips sign when the player stops or reverses direction -
    // the material's wander recording plays once as the "stopping" foot scuff.
    private double xMovec;
    private double zMovec;
    private boolean scalStat;

    /**
     * Shared with the walkingStepSound mixin: true when DS drives the local player's
     * footsteps and the vanilla step sound must be suppressed. This is the single
     * source of truth - the mixin and this generator read the same predicate, so
     * switching the system off in the config always hands the player back to the
     * vanilla step sound instead of leaving them silent. (Reported issue: disabling
     * "footstep sounds" in the config cancelled the vanilla step while the generator
     * also bailed out, so the player walked with no footsteps at all.)
     */
    public static boolean shouldSuppressVanillaStep() {
        final var config = ConfigurationData.getConfig(Configuration.class);
        return config.entityEffects.enableFootstepSounds && config.soundOptions.footstepVolume > 0;
    }

    @Override
    public void process(final Player player) {
        this.tickCount++;

        // Master switch: when footsteps are disabled (or the footstep volume slider is
        // at zero) the vanilla step sound plays instead. Drop any pending echoes so they
        // don't fire after re-enabling, and reset the motion tracking so the walk that
        // happened while the system was off does not come back as one huge stride (a
        // phantom step) or a bogus hard landing.
        if (!shouldSuppressVanillaStep()) {
            this.pendingEchoes.clear();
            this.lastPos = null;
            this.isFlying = false;
            this.didJump = false;
            this.fallDistance = 0D;
            // yPosition too: it is the baseline of the step-up/step-down test, and it only
            // advances while on the ground. A descent during the disabled period (stairs, a
            // drop, a cave) would otherwise leave it high and fire ONE phantom step on the
            // first tick after re-enabling, for a move the player never made. Seeding it
            // with the current height is safe - yPosition is only ever read as a delta.
            this.yPosition = player.getY();
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
        // Rising more than 0.4 blocks in a tick means a stair or a full block was
        // stepped up; declared here because the travelled distance is updated after the
        // step branch below has closed.
        boolean ascended = false;
        final boolean sneaking = player.isShiftKeyDown();

        // Stop/wander sounds (1.12.2 Generator mechanics): when the dot product of the
        // current motion against the previous tick's motion drops below ~0 (stopped or
        // reversed), play the material's wander recording once as a foot scuff. Skipped
        // in water (original hasSpecialStoppingConditions), when not on the ground
        // (stopping mid flight or mid jump near the ground must not scuff) and for
        // silent/spectator.
        final var mov = player.getDeltaMovement();
        final double scal = mov.x * this.xMovec + mov.z * this.zMovec;
        if (this.scalStat != (scal < 0.001F)) {
            this.scalStat = !this.scalStat;
            if (this.scalStat && onGround && !inWater && !player.isSpectator() && !player.isSilent()
                    && this.config.entityEffects.enableFootstepSounds && this.config.entityEffects.enableStopScuffSound
                    && this.config.soundOptions.footstepVolume > 0) {
                final var material = resolveMaterial(player);
                final var wander = material.map(FootstepGenerator::resolveWanderSound).orElse(null);
                if (wander != null) {
                    this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(wander)
                            .createAtLocationNoAttenuation(pos, dsFootstepVolume() * 0.85F));
                }
            }
        }
        this.xMovec = mov.x;
        this.zMovec = mov.z;

        // Airborne / landing state. Leaving the ground with upward motion is a deliberate
        // jump - remember it so landing from a jump always plays the heavy landing sound.
        // Landing: a deliberate jump or a genuine fall plays the material landing sound,
        // while a small drop (stepping down a block while moving) plays a normal step.
        if ((onGround || onLadder) == this.isFlying) {
            this.isFlying = !this.isFlying;
            if (this.isFlying) {
                if (player.getDeltaMovement().y > 0) {
                    this.didJump = true;
                    // Not while in water. 1.12.2 required BOTH isInWater() == false and the jump input
                    // before it would grunt; the port kept only the upward-motion test. So standing on
                    // the bottom of a pool and pressing space to swim up - an airborne transition with
                    // positive dy - played the jump grunt, and so did a slime-block bounce or any
                    // piston or explosion launch.
                    if (!inWater && this.config.entityEffects.enablePlayerJumpSound
                            && VARIATORS.getPlayerVariator().playJump() && !sneaking) {
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
                } else if (this.fallDistance > 0 && !sneaking && !inWater) {
                    // Small fall / step down a block: play the material's normal walk sound.
                    // Not while in water: entering water IS a small fall as far as fallDistance is
                    // concerned, so without this test the act of hitting the surface played a
                    // footstep - the same field report as the stride branch above.
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
        // Water is excluded, which is what the comment here always claimed but the code did not
        // do: the guard was `onGround || onLadder` alone, and a player swimming against a block
        // (or standing on the bottom) IS onGround, so footsteps played underwater. Reported from
        // the field: "footstep sounds play when i swim while touching a block - footsteps
        // shouldn't really play at all if you're underwater". The stop/wander branch below
        // already had this test; only this branch was missing it.
        //
        // Note `inWater` is `isInWater()`, which is true whenever the player's body is in water -
        // including floating at the surface - so this also covers the "swim touching a block"
        // case where the feet are still above the block.
        if ((onGround || onLadder) && !inWater) {
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
            // step immediately. NOTE: this branch does NOT actually produce the sound heard when
            // walking down stairs, and it never has. The airborne state machine above runs first
            // and, on the exact tick the feet touch the lower step, its small-fall path
            // (fallDistance > 0) plays the step - so going downstairs is handled there, which is
            // also where the "going UP sounds different from going DOWN" asymmetry comes from
            // (the ascent gets strideStair() + this stride branch; the descent gets the walk
            // sound). What this branch still covers is the residual case where the player lands
            // with fallDistance == 0: a one-tick air crossing, e.g. sprinting down a flat stair
            // run. Do not "fix" it into an audible path without re-checking the stair descent -
            // firing here as well would double the step on every step down.
            boolean steppedDown = onGround && !inWater && !sneaking && this.yPosition - pos.y > 0.4;
            // Mirror of the above for the ascent: 1.12.2 tested |yPosition - posY| > 0.4 and then
            // chose strideStair / the UP events by direction. Only the stride survived the port.
            ascended = onGround && !inWater && pos.y - this.yPosition > 0.4D;
            if (steppedDown) {
                this.playStep(player, running);
                this.dmwBase = this.distanceWalked;
            } else {
                // Going upstairs uses its own, shorter stride (1.12.2 switched to strideStair as
                // soon as the player rose 0.4+ blocks). That alone fixes the sluggish climb: the
                // shipped player sidesteps the machine-gun effect that also counting the climb
                // produced.
                //
                // NOT the 1.12.2 value: the original accumulated the FULL 3D distance
                // (sqrt(dX^2+dY^2+dZ^2) * 0.6, Generator.java:196) while the loop above accumulates
                // the HORIZONTAL distance only, so the original's STRIDE_STAIR (0.75 * 0.65 =
                // 0.4875) collects 0.4243 units per riser against the port's 0.3000 - a factor of
                // exactly sqrt(2) on a 45-degree staircase. variators.json therefore ships 0.3 for
                // the player, which is that 0.4875 divided by ~1.41: one step per stair tread,
                // measured by ear against the walking stride of 0.9. Every other variator in the
                // file keeps the original ratio (0.95 * 0.65 = 0.6175, 0.375 * 0.65 = 0.24375);
                // the player is the deliberate exception, so do NOT "restore" 0.4875 here - it
                // halves the stair cadence.
                final float stride;
                if (onLadder && !onGround)
                    stride = strideLadder();
                else if (ascended)
                    stride = strideStair(running);
                else
                    stride = running ? strideRun() : strideWalk();
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

        // Sneak footsteps. 1.12.2 suppressed them outright (its proceedWithStep() returned
        // !isSneaking and gated both playSinglefoot and playMultifoot), so a sneaking player was
        // completely silent. This port deliberately keeps them audible - a silent sneak reads as a
        // bug to a modern player - which is why enableSneakFootstepSounds defaults to true; the
        // option exists so the original behaviour is one click away. Note the vanilla step is
        // still suppressed by the mixin either way, so turning this off gives silence rather than
        // double sounds.
        if (!this.config.entityEffects.enableSneakFootstepSounds && player.isShiftKeyDown())
            return;

        final boolean climbing = player.onClimbable() && !player.onGround();
        final var pos = player.blockPosition().below();
        // While climbing, the surface is the climbable block the player is inside
        // (ladder/vine/bamboo/...), not whatever resolveSurfaceBlock finds below.
        final var state = climbing
                ? player.level().getBlockState(player.blockPosition())
                : resolveSurfaceBlock(player, player.level(), pos);
        if (this.logger.isDebugging()) {
            this.logger.debug("Step at %s, pos %s, state %s, footY %.3f", player.blockPosition(), pos, state, player.position().y);
            // The feet cell is where the trapdoor/door bug lived, so spell it out whenever the
            // step lands on something other than the block under the feet.
            final var feetCell = player.level().getBlockState(player.blockPosition());
            if (!feetCell.equals(state)) {
                final List<String> t = new java.util.ArrayList<>();
                resolveSurfaceBlock(player, player.level(), pos, t);
                t.forEach(this.logger::debug);
            }
        }
        // A waterlogged solid block is still a surface; only air and real fluids are not.
        if (isNotSolidSurface(state))
            return;

        // Raise the step event so accent handlers (armor clank, floor squeak, wet surface)
        // play alongside the generated step. The vanilla step event does not reach the tail
        // injector because the player's vanilla step is cancelled to avoid double sounds.
        ClientEventHooks.ENTITY_STEP_EVENT.raise().onStep(player, pos, state);

        final var stepSound = state.getSoundType().getStepSound();
        ResourceLocation soundLoc = stepSound.getLocation();
        var accents = List.<ResourceLocation>of();

        // Climbing a LADDER is treated like any other surface: the `#c:ladders` rule (present
        // in sound_mappings under every step-sound event a ladder can carry) resolves to the
        // ladder acoustic, which borrows the vanilla ladder recording and plays it louder.
        //
        // Every OTHER climbable (vine, bamboo, scaffolding, cave vines, ...) keeps the old
        // behaviour of playing its own vanilla surface step sound: only ladders opt in.
        //
        // This is the fix for "modded ladders do not use the ladder sound": while climbing,
        // material resolution used to be skipped outright, so a Quark ladder - whose step
        // sound is block.wood.step, not block.ladder.step - played a plain wood step and never
        // reached footsteps/ladder.
        // The second test used to be the bare `#c:ladders` tag, which nothing defines, so this
        // condition was always false and the fix never took effect. isLadder() tests the block type
        // first and the convention tag second.
        final boolean ladderClimb = climbing
                && TAG_LIBRARY.is(net.minecraft.tags.BlockTags.CLIMBABLE, state)
                && isLadder(state);

        // Climbing (ladder/vine/bamboo/...) plays the vanilla surface step sound louder,
        // matching the original 1.12.2 mod, instead of the DS per-material replacement.
        if (!climbing || ladderClimb) {
            // If the step sound is remapped to a DS footstep material, play that material
            // sound directly so we can pick the walk/run variant. Running uses the material's
            // *_run sound event when one exists, giving the heavier cadence of the original.
            var remap = SOUND_LIBRARY.getRemappedSound(stepSound, state);
            if (remap.isPresent()) {
                soundLoc = remap.get().factory();
                // NOT_EMITTER equivalent: the mapping explicitly silences this block
                // (buttons etc.), so play nothing. The accent step event was already
                // raised above and is deliberately left alone - armor clank and floor
                // squeaks are about the player, not about the block underfoot.
                if (NO_FOOTSTEP.equals(soundLoc))
                    return;
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
                .createAtLocationNoAttenuation(feetPos, footstepVolume() * dsFootstepVolume() * (climbing ? CLIMB_VOLUME_BOOST : 1.0F));
        this.audioPlayer.play(sound);

        // Layer the simultaneous accents on top of the main step sound (e.g. subtle brush
        // rustle on grass, leaves rustle on leaves), matching the original 1.12.2 mcp.json
        // simultaneous acoustic compositions.
        for (var accent : accents) {
            var accentSound = SOUND_LIBRARY.getSoundFactoryOrDefault(accent)
                    .createAtLocationNoAttenuation(feetPos, dsFootstepVolume());
            this.audioPlayer.play(accentSound);
        }
    }

    private void playLand(final Player player) {
        // 1.12.2 hard landings go through playMultifoot(): the land composition is
        // evaluated once per foot, so TWO independent voices play simultaneously (plus
        // two more when the delayed layer fires) and SUM in the mixer. That channel
        // summation is the only way a landing can read heavier than a footstep: the
        // engine clamps a single voice's gain at 2F (SoundVolumeEvaluator), so per-voice
        // volume beyond that point is a silent no-op and multi-voice summation is what
        // buys the extra weight.
        final float scale = footstepVolume() * dsFootstepVolume() * LAND_GAIN_BOOST;
        var feetPos = player.blockPosition();
        var material = resolveMaterial(player);
        if (this.logger.isDebugging())
            this.logger.debug("Land at %s, material %s, sound %s, footY %.3f",
                    feetPos, material.map(Object::toString).orElse("(none)"), resolveLandSound(player), player.getY());

        // Left/right foot positions: the block centre offset 0.2 blocks laterally off
        // the facing direction (1.12.2 findAssociation semantics).
        var feetCenter = Vec3.atCenterOf(feetPos);
        double yawRad = Math.toRadians(player.getYRot());
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        var leftFoot = feetCenter.add(-rightX * FOOT_LATERAL_OFFSET, 0, -rightZ * FOOT_LATERAL_OFFSET);
        var rightFoot = feetCenter.add(rightX * FOOT_LATERAL_OFFSET, 0, rightZ * FOOT_LATERAL_OFFSET);

        // The composition now lives on the material's own entry in sound_factories.json, because the
        // material IS that factory. A material with no land block keeps the plain fallback below.
        var comp = material
                .flatMap(SOUND_LIBRARY::getSoundFactory)
                .flatMap(ISoundFactory::getLandSettings)
                .orElse(null);
        // The echo delay is per-composition when the data gives one, otherwise the shared default.
        final int echoDelay = comp != null
                ? comp.echoDelayMinTicks() + java.util.concurrent.ThreadLocalRandom.current().nextInt(
                        Math.max(1, comp.echoDelayMaxTicks() - comp.echoDelayMinTicks() + 1))
                : LAND_ECHO_DELAY_MIN_TICKS
                        + java.util.concurrent.ThreadLocalRandom.current().nextInt(
                                LAND_ECHO_DELAY_MAX_TICKS - LAND_ECHO_DELAY_MIN_TICKS + 1);
        if (comp != null && comp.primary().isPresent()) {
            // Per-foot playback of the material composition: primary thud x2 + walk@50
            // layer x2 + delayed echo x2, exactly like playMultifoot + the 1.12.2 land
            // entries in mcp.json.
            var primary = SOUND_LIBRARY.getSoundFactoryOrDefault(comp.primary().get());
            this.audioPlayer.play(primary.createAtLocationNoAttenuation(leftFoot, scale));
            this.audioPlayer.play(primary.createAtLocationNoAttenuation(rightFoot, scale));
            comp.secondary().ifPresent(loc -> {
                var secondary = SOUND_LIBRARY.getSoundFactoryOrDefault(loc);
                this.audioPlayer.play(secondary.createAtLocationNoAttenuation(leftFoot, comp.secondaryScale() * scale));
                this.audioPlayer.play(secondary.createAtLocationNoAttenuation(rightFoot, comp.secondaryScale() * scale));
            });
            comp.echo().ifPresent(loc -> {
                var echo = SOUND_LIBRARY.getSoundFactoryOrDefault(loc);
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocationNoAttenuation(leftFoot, comp.echoVolume() * scale), this.tickCount + echoDelay));
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocationNoAttenuation(rightFoot, comp.echoVolume() * scale), this.tickCount + echoDelay));
            });
        } else {
            // Fallback: material's own land/run thud per foot + delayed echo (1.12.2
            // playMultifoot semantics without a configured composition).
            var landLoc = resolveLandSound(player);
            // A mute material (the buttons rule points at footsteps.none) must land silently, the
            // same way playStep bails out above. Without this the sentinel is looked up as a sound
            // factory, misses the registry and logs "Unable to locate sound" on every such landing.
            // The armor clank and the land accents below are skipped as well: the material itself
            // is declared mute, so inventing a simultaneous clank for it would undo that decision.
            if (NO_FOOTSTEP.equals(landLoc))
                return;
            var baseLoc = landLoc;
            if (landLoc.getPath().endsWith("_land")) {
                baseLoc = ResourceLocation.fromNamespaceAndPath(landLoc.getNamespace(), landLoc.getPath().substring(0, landLoc.getPath().length() - 5));
            } else if (landLoc.getPath().endsWith("_run")) {
                baseLoc = ResourceLocation.fromNamespaceAndPath(landLoc.getNamespace(), landLoc.getPath().substring(0, landLoc.getPath().length() - 4));
            }
            var primary = SOUND_LIBRARY.getSoundFactoryOrDefault(landLoc);
            this.audioPlayer.play(primary.createAtLocationNoAttenuation(leftFoot, scale));
            this.audioPlayer.play(primary.createAtLocationNoAttenuation(rightFoot, scale));
            if (!baseLoc.equals(landLoc)) {
                var base = SOUND_LIBRARY.getSoundFactoryOrDefault(baseLoc);
                this.audioPlayer.play(base.createAtLocationNoAttenuation(leftFoot, 0.5F * scale));
                this.audioPlayer.play(base.createAtLocationNoAttenuation(rightFoot, 0.5F * scale));
            }
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocationNoAttenuation(leftFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocationNoAttenuation(rightFoot, LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
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
                .ifPresent(f -> this.audioPlayer.play(f.createAtLocationNoAttenuation(feetPos, dsFootstepVolume())));
        ITEM_LIBRARY.getEquipableStepAccentSoundRun(armor)
                .ifPresent(f -> this.pendingEchoes.add(new PendingEcho(f.createAtLocationNoAttenuation(feetPos, LAND_ECHO_VOLUME * dsFootstepVolume()), this.tickCount + echoDelay)));

        // Landing accents: play the surface's layered accents (from the sound mappings,
        // e.g. grass+brush, ice+muffledice, amethyst+crystal) on landing too, so the
        // material combo carries over from steps. Data-driven: one "accent" entry in
        // sound_mappings drives both the step and landing layers.
        if (this.config.footstepAccents.enableAccents) {
            final var landState = resolveSurfaceBlock(player, player.level(), feetPos.below());
            if (!isNotSolidSurface(landState)) {
                SOUND_LIBRARY.getRemappedSound(landState.getSoundType().getStepSound(), landState)
                        .ifPresent(remap -> remap.accents().forEach(accent ->
                                this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(accent)
                                        .createAtLocationNoAttenuation(feetCenter, dsFootstepVolume()))));
            }
        }
    }

    /**
     * True when a state is air, or a fluid block the player wades through rather than a surface.
     *
     * <p>Deliberately NOT "carries a fluid". A waterlogged solid block - stairs, slabs, fences,
     * coral, or a modded block - reports a fluid level in its own cell, yet the player still stands
     * on its top face and expects that block's material. Testing
     * {@code !getFluidState().isEmpty()} as "not a surface" therefore silenced the footstep AND the
     * landing sound of every waterlogged block.
     *
     * <p>A fluid one wades through is a dedicated fluid block (water, lava, and their flowing
     * states); a waterlogged solid is an ordinary block that merely holds a fluid level.
     */
    static boolean isNotSolidSurface(final BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /**
     * True when a solid surface holds a fluid level, i.e. it is waterlogged. Used for reporting and
     * for the watery-surface accent; the two conditions are what {@link #isNotSolidSurface} separates.
     */
    public static boolean isWaterlogged(final BlockState state) {
        return !state.getFluidState().isEmpty() && !isNotSolidSurface(state);
    }

    /** Recognises a block that cannot be walked on, for the whole footstep pipeline. */
    /**
     * Resolves the footstep material factory for the block below the entity (for looking up
     * its landing composition), or empty if no remap applies.
     */

    static Optional<ResourceLocation> resolveMaterial(final Entity entity) {
        var state = resolveSurfaceBlock(entity, entity.level(), entity.blockPosition().below());
        if (isNotSolidSurface(state))
            return Optional.empty();
        var stepSound = state.getSoundType().getStepSound();
        return SOUND_LIBRARY.getRemappedSound(stepSound, state).map(r -> r.factory());
    }

    /**
     * Resolves the block the entity is standing on. If the position below is air (the entity
     * is hanging over a block edge), scans horizontally for the nearest solid block - the
     * same edge-handling the sound remapping uses. Prefers vanilla's precise supporting
     * block (mainSupportingBlockPos, resolved via collision boxes) when the entity is on
     * the ground, which correctly picks the block actually stood on even when the entity
     * straddles an edge next to a snow layer in the row below.
     */
    static BlockState resolveSurfaceBlock(Entity entity, Level level, BlockPos pos) {
        return resolveSurfaceBlock(entity, level, pos, null);
    }

    /**
     * Same as {@link #resolveSurfaceBlock(Entity, Level, BlockPos)} but appends a human
     * readable account of every decision taken to {@code trace} when it is non-null. The
     * trace is what /dsdump steps prints, so a player standing in the spot that sounds
     * wrong can report exactly which branch fired.
     */
    static BlockState resolveSurfaceBlock(Entity entity, Level level, BlockPos pos,
                                          @Nullable final List<String> trace) {
        if (trace != null) {
            trace.add("pos(blockPosition().below()) = %s   feetCell = %s".formatted(pos, pos.above()));
            trace.add("entity.getY() = %.4f   onGround = %s   mainSupportingBlockPos = %s".formatted(
                    entity.getY(), entity.onGround(), entity.mainSupportingBlockPos.map(Object::toString).orElse("(empty)")));
        }
        // Priority order (see the footstep material resolution). Steps 1 and 2 both consult the
        // feet cell and are gated on isStandingOn(): the cell only wins when the player is on
        // the block, not inside it, so an open trapdoor or an open door in the same cell as the
        // player cannot hijack the surface.
        // 1. A snow layer or leaf litter the player's feet are in (pos.above()). The player
        //    stands on these and their step sound must win over the block below. Vanilla's
        //    mainSupportingBlockPos cannot be used for this: a 1-layer snow and leaf litter
        //    are registered noCollision() (an almost empty collision box), so collision
        //    detection reports the block underneath.
        // 2. A block the player's feet are in (pos.above()) that the data explicitly mapped -
        //    the no-collision-box blocks vanilla's collision lookup is blind to (rails, sculk
        //    veins, glow lichen, vines, lily pads, redstone wire, tripwire).
        // 3. Vanilla's collision-derived support block (mainSupportingBlockPos). This is the
        //    most reliable "what is the player actually standing on" answer: it uses the
        //    entity's collision box against block shapes, so thin/partial blocks
        //    (trapdoors, doors, buttons, pressure plates, carpets) that merely neighbour
        //    the feet never get misreported as the walked surface (the naive pos /
        //    pos.above() heuristics would grab them whenever the player stands at an edge).
        // 4. The block the player's feet are in (pos.above()) - handles other visible
        //    non-solid surfaces. Restricted to blocks with a visible shape and not
        //    vegetation (tall grass has a shape but is walked through).
        // 5. The block directly below the feet (pos).
        // 6. Horizontal scan for the nearest solid block when the player is over an edge.

        var footState = level.getBlockState(pos.above());
        traceFootCell(trace, entity, level, pos.above(), footState);
        // 1.20.1 / 1.21.1 have no leaf-litter block (LeafLitterBlock arrives in a later 1.21.x), so
        // this probe is the snow layer only. 26.1 has the real class and tests it inline here.
        if (!isNotSolidSurface(footState)
                && isStandingOn(entity, level, pos.above(), footState)
                && footState.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock) {
            if (trace != null) trace.add("-> snow-layer probe (1a) matched");
            return footState;
        }

        // Blocks with no collision box (rails, sculk veins, glow lichen, vines, lily pads,
        // redstone wire, tripwire, ...) are invisible to vanilla's collision-derived
        // supporting block, so standing on a rail silently reported the block UNDER the rail
        // and the rail's own material never played. For those blocks the feet cell is the
        // answer; the collision lookup below simply cannot see them.
        //
        // The trigger is an explicit entry in the footstep data, not a collision test: if the
        // data author deliberately listed this block, DS is expected to sound like it. That
        // keeps the exception narrow - a cell whose step sound merely happens to differ (tall
        // grass the player walks through, a torch at the feet) is not preferred unless it has
        // been given its own material.
        //
        // The author's intent is about "you stepped ON this", so it must still be the thing
        // underfoot. When the block's own collision box rises from the floor of the cell the
        // player stands in, the player is INSIDE the block, not on it, and the cell is not the
        // surface: an open trapdoor and an open door are 3/16-thick slabs lying on the floor
        // of their cell (Block.boxZ(16, 13, 16) rotated onto the floor), a fence/wall/piston
        // head fills the cell - all of them merely neighbour the feet. Without this check the
        // trapdoor/door cell hijacked the step AND the land sound, so standing next to an open
        // trapdoor or in a doorway sounded like thin wood instead of the floor.
        final boolean overlay = TAG_LIBRARY.is(FOOT_OVERLAY, footState);
        if (trace != null) {
            final boolean standing = isStandingOn(entity, level, pos.above(), footState);
            trace.add("feet cell in #dsurround:effects/foot_overlay = %s (waterlogged = %s)".formatted(
                    overlay, isWaterlogged(footState)));
            trace.add("isStandingOn(feetCell) = %s  -> the cell %s be used".formatted(
                    standing, standing ? "CAN" : "MUST NOT"));
        }
        if (!isNotSolidSurface(footState)
                && isStandingOn(entity, level, pos.above(), footState)
                && overlay) {
            if (trace != null) trace.add("-> foot-overlay probe (1b) matched");
            return footState;
        }

        // The single pos.above() probe above misses the block-edge case: standing at the
        // edge of a snow layer / leaf litter patch, the feet overlap two cells and
        // blockPosition() (floor of the feet centre) can land on the snow-free neighbour,
        // so the surface falls through to the block underneath. A 1-layer snow has a
        // zero-height collision shape (getCollisionShape uses SHAPES[LAYERS-1]) and leaf
        // litter is noCollision(), so vanilla's collision-derived mainSupportingBlockPos
        // can't see them either. Scan every cell the feet actually overlap, like vanilla's
        // findSupportingBlock does with the entity AABB, but using the visual surface.
        var feetBox = entity.getBoundingBox();
        int xMin = Mth.floor(feetBox.minX);
        int xMax = Mth.floor(feetBox.maxX - 1.0E-3D);
        int zMin = Mth.floor(feetBox.minZ);
        int zMax = Mth.floor(feetBox.maxZ - 1.0E-3D);
        int feetY = pos.getY() + 1;
        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                var cellPos = new BlockPos(x, feetY, z);
                var cell = level.getBlockState(cellPos);
                if (!isNotSolidSurface(cell)
                        && isStandingOn(entity, level, cellPos, cell)
                        && cell.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock) {
                    return cell;
                }
            }
        }

        var supportPos = entity.mainSupportingBlockPos.orElse(null);
        if (supportPos != null && entity.onGround()) {
            var support = level.getBlockState(supportPos);
            if (!isNotSolidSurface(support)) {
                if (trace != null) trace.add("-> vanilla mainSupportingBlockPos (3) at %s = %s".formatted(supportPos, support));
                return support;
            }
        }
        if (trace != null) trace.add("mainSupportingBlockPos (3) not usable");

        // There used to be a fifth probe here - "the feet cell by visible shape, if it is in the
        // overlay tag" - kept from the port's earlier structure. It was UNREACHABLE: probe (1b)
        // above already returns the feet cell whenever it is in the overlay tag, is a solid
        // surface and passes isStandingOn, and this copy added only "has a non-empty shape" and a
        // vegetation exclusion. Every overlay block the tag covers (carpets, rails, pressure
        // plates, snow, lily pads, glow lichen, sculk veins) has a non-empty shape and is not a
        // vegetation block, so the earlier probe always fired first - and when it did not fire (a
        // raised block in the feet cell) neither did this one. Removed rather than left as a
        // decoy: with isStandingOn working again, (1b) is the single place where the feet cell can
        // win. (1.12.2's AcousticResolver had the equivalent probe as an explicit "substrate" set;
        // the overlay tag is the port of that set, so nothing was lost.)

        var state = level.getBlockState(pos);
        if (!isNotSolidSurface(state)) {
            if (trace != null) trace.add("-> block below the feet (5) = %s".formatted(state));
            return state;
        }

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
     * True when the block in the given cell is something the entity is standing ON rather than
     * merely standing IN.
     *
     * Blocks that fill the cell (a fence, a wall, a closed door, a piston head) or that lie on
     * its floor (an OPEN trapdoor / OPEN door: Block.boxZ(16.0, 13.0, 16.0) rotated onto the
     * floor gives a 3/16-thick slab at y 0..3/16) have a collision box rising above the feet,
     * so the feet are inside it. Such a cell is not the walked surface even when the data gives
     * the block an explicit footstep material.
     *
     * The counterpart case is the reason the check cannot simply require a collision box: the
     * blocks this probe exists for (rails, sculk veins, glow lichen, vines, lily pads, redstone
     * wire, tripwire, and a 1-layer snow or leaf litter) have an EMPTY collision shape or one
     * that is flush with the floor, and the player does stand on them. Those keep the
     * walk-through branch.
     */
    /** Appends the feet-cell detail the diagnostic needs: the block, its collision box and
     * whether that box rises above the feet (which is what decides isStandingOn). */
    private static void traceFootCell(@Nullable final List<String> trace, final Entity entity,
                                      final Level level, final BlockPos cell, final BlockState state) {
        if (trace == null)
            return;
        var shape = state.getCollisionShape(level, cell);
        trace.add("feet cell %s = %s".formatted(cell, state));
        trace.add("   sound type = %s   step event = %s".formatted(
                state.getSoundType(), state.getSoundType().getStepSound().getLocation()));
        trace.add("   collision shape = %s   top Y = %s".formatted(
                shape.isEmpty() ? "(empty)" : shape.bounds().toString(),
                shape.isEmpty() ? "(none)" : "%.4f".formatted(shape.max(Direction.Axis.Y))));
        trace.add("   isStandingOn = %s   (feet %.4f + tolerance %.2f)".formatted(
                isStandingOn(entity, level, cell, state), entity.getY(), STANDING_TOLERANCE));
        trace.add("   vanilla getOnPos(0.2) = %s   collisionExtendsVertically = %s".formatted(
                entity.getOnPosLegacy(), state.collisionExtendsVertically(level, cell, entity)));
        if (TAG_LIBRARY.is(net.minecraft.tags.BlockTags.TRAPDOORS, state)
                || TAG_LIBRARY.is(net.minecraft.tags.BlockTags.DOORS, state))
            trace.add("   NOTE: this cell holds a TRAPDOOR/DOOR (open=%s). NOT a foot overlay, so it never wins; the sound comes from the block below".formatted(
                    state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                            ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN).toString()
                            : "false"));
    }

    /**
     * Builds the /dsdump steps report for the current player: the complete decision chain of
     * the surface resolution plus the material and landing sound it produces. Printed to chat
     * so a player can report the exact branch taken at the position that sounds wrong.
     */
    public static List<String> dumpStepTrace(final Player player) {
        final List<String> trace = new java.util.ArrayList<>();
        final var pos = player.blockPosition().below();
        trace.add("=== DS footstep surface resolution ===");
        trace.add("player %s   feet Y %.4f   onGround %s".formatted(player.blockPosition(), player.getY(), player.onGround()));
        trace.add("climbing(=%s) = %s".formatted(player.onClimbable(), player.onClimbable() && !player.onGround()));
        final var state = resolveSurfaceBlock(player, player.level(), pos, trace);
        trace.add("RESULT: %s".formatted(state));
        final var stepSound = state.getSoundType().getStepSound();
        trace.add("   step event = %s".formatted(stepSound.getLocation()));
        final Optional<ISoundLibrary.SoundRemap> remap = SOUND_LIBRARY.getRemappedSound(stepSound, state);
        if (remap.isPresent()) {
            trace.add("   material   = %s".formatted(remap.get().factory()));
            trace.add("   accents    = %s".formatted(remap.get().accents()));
        } else {
            trace.add("   material   = (no DS remap -> vanilla sound)");
        }
        trace.add("   land sound = %s".formatted(resolveLandSound(player)));
        trace.add("   material(resolveMaterial) = %s".formatted(
                resolveMaterial(player).map(Object::toString).orElse("(none)")));
        return trace;
    }

    private static boolean isStandingOn(final Entity entity, final Level level, final BlockPos cell,
                                        final BlockState state) {
        // A floor layer: the block's own top surface is at (or a hair below) the feet, so the
        // entity is on it. This is decidable from the collision shape alone and holds for every
        // overlay the tag covers - a carpet (1/16), a pressure plate (1/16), a rail and a 1-layer
        // snow (both empty collision shapes), a lily pad. A door or an open trapdoor beside the
        // player has its top face AT THE CELL CEILING (shape y 0..1), so it is rejected, which is
        // what makes the door/trapdoor cells stop hijacking the sound.
        //
        // getCollisionShape returns the shape in CELL-LOCAL coordinates (0..1, a fence 1.5), so it
        // must be lifted by the cell's own Y before being compared with the entity's world Y. The
        // earlier form compared 1.5 against ~64 and was therefore ALWAYS true - the guard below
        // was inert and /dsdump steps printed "isStandingOn = true" for every cell. Nothing
        // audibly broke while it was inert because #dsurround:effects/foot_overlay holds only flat
        // overlays (carpets, rails, plates, snow, lily pads, glow lichen, sculk veins); a raised
        // block added to that tag by a datapack would have re-created the door/hijacked-sound bug.
        var shape = state.getCollisionShape(level, cell);
        if (shape.isEmpty())
            return true;
        return cell.getY() + shape.max(Direction.Axis.Y) <= entity.getY() + STANDING_TOLERANCE;
    }

    /**
     * Resolves the landing sound for the block below the entity. Prefers the material's
     * dedicated *_land recording (distinct "thud"), then the *_run sound, then the base
     * sound - matching the original 1.12.2 land composition. Falls back to the generic
     * player.land when no remap applies.
     */
    static ResourceLocation resolveLandSound(final Entity entity) {
        var state = resolveSurfaceBlock(entity, entity.level(), entity.blockPosition().below());
        // Waterlogged blocks keep their own landing recording; only air and real fluids fall back.
        if (isNotSolidSurface(state))
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
    static ResourceLocation materialVariant(ResourceLocation material, String suffix) {
        var variant = ResourceLocation.fromNamespaceAndPath(material.getNamespace(), material.getPath() + suffix);
        return SOUND_LIBRARY.isSoundRegistered(variant) ? variant : null;
    }

    /**
     * Resolves the stop/take-off scuff (wander/jump) sound for a footstep material,
     * honouring the 1.12.2 cross-material overrides (e.g. metal box -> marble scrape),
     * falling back to the material's own _wander recording.
     */
    @Nullable
    static ResourceLocation resolveWanderSound(ResourceLocation material) {
        var override = SOUND_LIBRARY.getSoundFactory(material).flatMap(ISoundFactory::getWanderSound);
        return override.isPresent() ? override.get() : materialVariant(material, "_wander");
    }

    /**
     * Resolves the take-off scuff (JUMP) sound for a footstep material. 1.12.2 declares
     * EventType.JUMP(WANDER), so a material without an explicit jump acoustic scuffs with its
     * wander recording; a material's own "jump" field in sound_factories.json is what
     * overrides that for the few materials where the two differ.
     */
    @Nullable
    static ResourceLocation resolveJumpSound(ResourceLocation material) {
        var override = SOUND_LIBRARY.getSoundFactory(material).flatMap(ISoundFactory::getJumpSound);
        return override.isPresent() ? override.get() : resolveWanderSound(material);
    }

    private void playJump(final Player player) {
        // A2-9: Match the original 1.12.2 two-layer jump: the generic "grunt"
        // (dsurround:player.jump) plus a material-specific take-off scuff for the
        // block below, mirroring the original's simulateJumpingLanding which
        // played the _JUMP acoustic and the material's jump acoustic. Both
        // layers resolve through the JSON factory registry so their configured
        // pitch randomization applies (a bare SoundFactoryBuilder always played
        // at a constant pitch, which is why jumps had no variation).
        var grunt = SOUND_LIBRARY.getSoundFactoryOrDefault(JUMP).createAsAdditional();
        this.audioPlayer.play(grunt);

        // Material-specific take-off scuff (e.g. snow_wander on snow, stone_wander on
        // stone). resolveMaterial() gives the footstep material factory; the acoustic is
        // that material's JUMP recording, which 1.12.2 resolves to its wander recording
        // unless the material's "jump" field overrides it. Not every material has a
        // wander recording - those simply skip the extra layer.
        resolveMaterial(player).ifPresent(material -> {
            var jumpLoc = resolveJumpSound(material);
            if (jumpLoc != null) {
                var feetPos = player.blockPosition();
                var scuff = SOUND_LIBRARY.getSoundFactoryOrDefault(jumpLoc);
                this.audioPlayer.play(scuff.createAtLocationNoAttenuation(feetPos, dsFootstepVolume()));
            }
        });
    }

    @Override
    public void onDisconnect() {
        this.resetMotionState();
    }

    /**
     * Forgets everything derived from where the player was and how they were moving.
     *
     * <p>Called on disconnect AND on respawn or a dimension change. The second one was missing: the
     * only reset hooks were onDisconnect and the disabled-master-switch branch, and ClientState only
     * raises ON_DISCONNECT when {@code client.player} goes null - which a respawn or a portal trip
     * never does. So the singleton kept the previous position, and two things went wrong. Dying on the
     * ground and respawning elsewhere made the first tick accumulate the whole teleport as horizontal
     * distance, which crossed the stride threshold and played a footstep at the spawn point. Dying in
     * mid-air left {@code isFlying} true with the pre-death fall distance, so the first grounded tick
     * looked like a landing and played the full heavy-landing composition.
     */
    private void resetMotionState() {
        this.pendingEchoes.clear();
        this.wasRunning = false;
        this.didJump = false;
        this.isFlying = false;
        this.fallDistance = 0D;
        this.distanceWalked = 0D;
        this.dmwBase = 0D;
        this.yPosition = 0D;
        this.lastPos = null;
        // The stop-scuff state machine as well, or the first tick in the new location can fire a
        // spurious material scuff (the same un-primed-state defect as on a fresh join).
        this.xMovec = 0D;
        this.zMovec = 0D;
        this.scalStat = false;
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems, "Footsteps: stride walk %.2f run %.2f, dist %.2f".formatted(strideWalk(), strideRun(), this.distanceWalked - this.dmwBase));
    }

    static boolean isVegetationBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }
}