package org.orecruncher.dsurround.processing;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.Variator;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.config.libraries.impl.VariatorLibrary;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creature footstep engine for creatures WITHOUT a dedicated vanilla step
 * sound (entity_variators.json - the humanoid mobs, creepers, endermen and the
 * small light-footed set). Ported from the original 1.12.2 Generator: one
 * per-entity state machine drives walk/run strides, landings (per-material
 * compositions), stop scuffs and take-off scuffs at the creature's variator
 * volume, while the vanilla step sound is suppressed for managed entities.
 * Everything else - the ~30 mobs that already play their own
 * entity.&lt;mob&gt;.step recording (cow != sheep != zombie != skeleton) - is
 * NOT managed and keeps vanilla footsteps, exactly as 1.12.2 left its
 * unconfigured mobs. The local player keeps the dedicated FootstepGenerator.
 */
public class CreatureFootstepGenerator extends AbstractClientHandler {

    private static final ISoundLibrary SOUND_LIBRARY = ContainerManager.resolve(ISoundLibrary.class);
    private static final VariatorLibrary VARIATORS = ContainerManager.resolve(VariatorLibrary.class);

    // Deliberate-jump heavy landing floor (parity with the player engine).
    private static final float JUMP_LAND_DISTANCE_MIN = 0.9F;
    // Landing gain. The player engine needs 1.78 to punch through the voice gain
    // clamp at ~0.9 walk volume; creature walks sit at ~0.4 so there is no clamp
    // headroom problem - a mild boost plus the multi-voice composition sum carries
    // the landing weight.
    private static final float LAND_GAIN_BOOST = 1.3F;
    // Stop-scuff volume relative to a step (player engine parity).
    private static final float WANDER_VOLUME_FACTOR = 0.85F;

    private final IAudioPlayer audioPlayer;
    private final Map<LivingEntity, CreatureState> states = new WeakHashMap<>();
    private final ArrayDeque<PendingEcho> pendingEchoes = new ArrayDeque<>(8);
    private long tickCount = 0;
    private int managedCount = 0;

    private record PendingEcho(SoundInstance sound, long playTick) {
    }

    private static final class CreatureState {
        double distanceWalked;
        double dmwBase;
        double yPosition;
        double xMovec;
        double zMovec;
        boolean scalStat;
        boolean isFlying;
        double fallDistance;
        boolean didJump;
        // Quadruped gait (1.12.2 GeneratorQP): four-beat hoof counter with a random
        // pond^2 stride modulation spacing the two diagonal hoof-pair beats.
        // Currently unassigned in entity_variators.json (1.12.2 stock data also never
        // referenced quadruped) - assign the "quadruped" variator to enable it.
        int hoof;
        float nextWalkDistanceMultiplier = 0.05F;
        Vec3 lastPos;
    }

    public CreatureFootstepGenerator(Configuration config, IAudioPlayer audioPlayer, IModLog logger) {
        super("Creature Footstep Generator", config, logger);
        this.audioPlayer = audioPlayer;
    }

    /**
     * Per-creature Variator selection (1.12.2 FootstepsRegistry.createGenerator).
     * Remote players get the player variator. Creatures WITHOUT a dedicated vanilla
     * step sound (entity_variators.json) are taken over by DS with their assigned
     * variator (default/light/...), and babies get the quiet child variator. The
     * unlisted default is "none" (keep vanilla), so every other mob - including the
     * ~30 that already play their own entity.<mob>.step recording (cow != sheep !=
     * zombie != skeleton) - returns null and keeps its vanilla footsteps.
     */
    static Variator resolveVariator(LivingEntity entity) {
        if (entity instanceof LocalPlayer)
            return null; // driven by FootstepGenerator
        if (entity instanceof AbstractClientPlayer)
            return VARIATORS.getVariator("player");
        var assigned = VARIATORS.getEntityVariator(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        var name = assigned.orElse("none");
        if (name.equals("none"))
            return null;
        if (entity.isBaby())
            return VARIATORS.getVariator("child");
        return VARIATORS.getVariator(name);
    }

    /**
     * Shared with the walkingStepSound mixin: true when the creature engine drives
     * this entity's footsteps and its vanilla step sound must be suppressed. This
     * is the single source of truth - a suppressed but unmanaged entity would walk
     * silently, and an unsuppressed managed entity would double-play.
     */
    public static boolean shouldSuppressVanillaStep(Entity entity) {
        return isManagedGate(entity, ConfigurationData.getConfig(Configuration.class));
    }

    private static boolean isManagedGate(Entity entity, Configuration config) {
        if (!config.entityEffects.enableCreatureFootstepSounds || config.soundOptions.footstepVolume <= 0)
            return false;
        if (!(entity instanceof LivingEntity living))
            return false;
        if (entity instanceof LocalPlayer)
            return false;
        if (entity.isSpectator() || entity.isSilent())
            return false;
        if (entity.isInWater() || entity.isInLava())
            return false; // vanilla swim / strider sounds stay
        var playerOpt = GameUtils.getPlayer();
        if (playerOpt.isEmpty() || !entity.closerThan(playerOpt.get(), config.entityEffects.entityEffectRange))
            return false;
        return resolveVariator(living) != null;
    }

    @Override
    public void process(final Player player) {
        this.tickCount++;
        while (!this.pendingEchoes.isEmpty() && this.pendingEchoes.peek().playTick() <= this.tickCount)
            this.audioPlayer.play(this.pendingEchoes.poll().sound());

        if (!this.config.entityEffects.enableCreatureFootstepSounds || this.config.soundOptions.footstepVolume <= 0) {
            this.pendingEchoes.clear();
            this.states.clear();
            return;
        }

        final int range = this.config.entityEffects.entityEffectRange;
        final var worldBox = AABB.unitCubeFromLowerCorner(player.getEyePosition()).inflate(range + (range >> 1));
        final var entities = player.level().getEntitiesOfClass(LivingEntity.class, worldBox);
        this.managedCount = 0;
        for (var entity : entities) {
            if (!entity.isAlive() || !isManagedGate(entity, this.config)) {
                this.states.remove(entity);
                continue;
            }
            final var variator = resolveVariator(entity);
            this.managedCount++;
            final var state = this.states.computeIfAbsent(entity, e -> new CreatureState());
            tickEntity(entity, state, variator);
        }
    }

    private void tickEntity(final LivingEntity entity, final CreatureState state, final Variator var) {
        final Vec3 pos = entity.position();
        final boolean onGround = entity.onGround();
        final boolean onClimbable = entity.onClimbable();

        // Stop scuff: the 1.12.2 scal mechanism - the dot product of the current
        // motion against the previous tick's motion flips sign when the creature
        // stops or reverses, playing the material's wander recording once.
        final var mov = entity.getDeltaMovement();
        final double scal = mov.x * state.xMovec + mov.z * state.zMovec;
        if (state.scalStat != (scal < 0.001D)) {
            state.scalStat = !state.scalStat;
            if (state.scalStat && var.playWander() && onGround)
                playWander(entity, var);
        }
        state.xMovec = mov.x;
        state.zMovec = mov.z;

        // Airborne / landing state machine (1.12.2 simulateAirborne).
        if ((onGround || onClimbable) == state.isFlying) {
            state.isFlying = !state.isFlying;
            if (state.isFlying) {
                if (mov.y > 0D) {
                    state.didJump = true;
                    if (var.playJump())
                        playJump(entity, var);
                }
            } else {
                final boolean heavyLand = state.didJump
                        ? state.fallDistance > JUMP_LAND_DISTANCE_MIN
                        : state.fallDistance > var.landHardDistanceMin();
                if (heavyLand) {
                    playLand(entity, var);
                } else if (state.fallDistance > 0D) {
                    playStep(entity, var, isRunning(entity, var));
                }
                state.didJump = false;
                state.lastPos = pos;
                state.yPosition = pos.y;
            }
        }
        if (state.isFlying)
            state.fallDistance = entity.fallDistance;

        // Walking strides (1.12.2 simulateFootsteps, distance * 0.6 scaling).
        if (onGround || onClimbable) {
            if (state.lastPos != null) {
                final double dx = pos.x - state.lastPos.x;
                final double dz = pos.z - state.lastPos.z;
                double step = Math.hypot(dx, dz);
                if (onClimbable)
                    step += Math.abs(pos.y - state.lastPos.y);
                state.distanceWalked += step * 0.6D;
            }
            state.lastPos = pos;

            final boolean running = isRunning(entity, var);
            final boolean steppedDown = onGround && state.yPosition - pos.y > 0.4D;
            if (steppedDown) {
                playStep(entity, var, running);
                state.dmwBase = state.distanceWalked;
                steppedHook(entity, state, var, running);
            } else {
                final float stride = var.quadruped() ? quadrupedStride(state, var, running) : var.stride();
                if (state.distanceWalked - state.dmwBase > stride) {
                    playStep(entity, var, running);
                    state.dmwBase = state.distanceWalked;
                    steppedHook(entity, state, var, running);
                }
            }
        } else {
            state.lastPos = pos;
        }

        if (onGround)
            state.yPosition = pos.y;
    }

    // 1.12.2 speedDisambiguator: WALK vs RUN by horizontal speed squared.
    private static boolean isRunning(final LivingEntity entity, final Variator var) {
        final var mov = entity.getDeltaMovement();
        return mov.x * mov.x + mov.z * mov.z > var.speedToRun();
    }

    // 1.12.2 GeneratorQP.stepped: advances the hoof counter; WALK plays the second
    // beat of the diagonal hoof pair, RUN plays an extra beat at hoof 3.
    private void steppedHook(final LivingEntity entity, final CreatureState state, final Variator var, final boolean running) {
        if (!var.quadruped())
            return;
        if (state.hoof == 0 || state.hoof == 2)
            state.nextWalkDistanceMultiplier = RANDOM.nextFloat();
        if (state.hoof >= 3)
            state.hoof = 0;
        else
            state.hoof++;
        if (state.hoof == 3 && running) {
            playStep(entity, var, running);
            state.hoof = 0;
        }
        if (!running)
            playStep(entity, var, running);
    }

    // 1.12.2 GeneratorQP.reevaluateDistance + walkFunction2.
    private float quadrupedStride(final CreatureState state, final Variator var, final boolean running) {
        if (running)
            return state.hoof == 0 ? var.stride() * 0.8F : var.stride() * 0.3F;
        float pond = state.nextWalkDistanceMultiplier;
        pond *= pond;
        pond *= 0.2F;
        if (state.hoof == 1 || state.hoof == 3)
            return var.stride() * pond * var.quadrupedMultiplier();
        return var.stride() * (1F - pond) * var.quadrupedMultiplier();
    }

    private void playStep(final LivingEntity entity, final Variator var, final boolean running) {
        final boolean climbing = entity.onClimbable() && !entity.onGround();
        final var feetPos = entity.blockPosition();
        final var surface = climbing
                ? entity.level().getBlockState(feetPos)
                : FootstepGenerator.resolveSurfaceBlock(entity, entity.level(), feetPos.below());
        if (surface.isAir() || !surface.getFluidState().isEmpty())
            return;

        // Raise the step event so accent handlers (brush rustle, ...) fire for
        // creatures too - the vanilla step event is suppressed for managed entities.
        ClientEventHooks.ENTITY_STEP_EVENT.raise().onStep(entity, feetPos, surface);

        final var stepSound = surface.getSoundType().getStepSound();
        Identifier soundLoc = stepSound.location();
        var accents = List.<Identifier>of();
        if (!climbing) {
            var remap = SOUND_LIBRARY.getRemappedSound(stepSound, surface);
            if (remap.isPresent()) {
                soundLoc = remap.get().factory();
                // NOT_EMITTER equivalent - see FootstepGenerator.NO_FOOTSTEP.
                if (FootstepGenerator.NO_FOOTSTEP.equals(soundLoc))
                    return;
                accents = remap.get().accents();
                if (running) {
                    var runLoc = FootstepGenerator.materialVariant(soundLoc, "_run");
                    if (runLoc != null)
                        soundLoc = runLoc;
                }
            }
        }

        final float volume = var.volumeScale() * dsVolume();
        this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(soundLoc).createAtLocation(feetPos, volume));
        for (var accent : accents)
            this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(accent)
                    .createAtLocation(feetPos, var.volumeScale() * dsVolume()));
    }

    private void playWander(final LivingEntity entity, final Variator var) {
        FootstepGenerator.resolveMaterial(entity).ifPresent(material -> {
            var wanderLoc = FootstepGenerator.resolveWanderSound(material);
            if (wanderLoc != null)
                this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(wanderLoc)
                        .createAtLocation(entity.blockPosition(), var.volumeScale() * WANDER_VOLUME_FACTOR * dsVolume()));
        });
    }

    private void playJump(final LivingEntity entity, final Variator var) {
        // Material scuff on take-off. The 1.12.2 generic "grunt" layer is skipped -
        // a human grunt on a cow would be wrong; quadruped variators enable this.
        FootstepGenerator.resolveMaterial(entity).ifPresent(material -> {
            var wanderLoc = FootstepGenerator.resolveWanderSound(material);
            if (wanderLoc != null)
                this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(wanderLoc)
                        .createAtLocation(entity.blockPosition(), var.volumeScale() * dsVolume()));
        });
    }

    private void playLand(final LivingEntity entity, final Variator var) {
        final float scale = var.volumeScale() * dsVolume() * LAND_GAIN_BOOST;
        final int echoDelay = FootstepGenerator.LAND_ECHO_DELAY_MIN_TICKS
                + ThreadLocalRandom.current().nextInt(FootstepGenerator.LAND_ECHO_DELAY_MAX_TICKS - FootstepGenerator.LAND_ECHO_DELAY_MIN_TICKS + 1);
        final var feetPos = entity.blockPosition();
        final var material = FootstepGenerator.resolveMaterial(entity);

        final var feetCenter = Vec3.atCenterOf(feetPos);
        final double yawRad = Math.toRadians(entity.getYRot());
        final double rightX = -Math.cos(yawRad);
        final double rightZ = -Math.sin(yawRad);
        final var leftFoot = feetCenter.add(-rightX * FootstepGenerator.FOOT_LATERAL_OFFSET, 0, -rightZ * FootstepGenerator.FOOT_LATERAL_OFFSET);
        final var rightFoot = feetCenter.add(rightX * FootstepGenerator.FOOT_LATERAL_OFFSET, 0, rightZ * FootstepGenerator.FOOT_LATERAL_OFFSET);

        var comp = material.flatMap(m -> Optional.ofNullable(FootstepGenerator.LAND_COMPOSITIONS.get(m.getPath()))).orElse(null);
        if (comp != null) {
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
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocation(leftFoot, FootstepGenerator.LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
                this.pendingEchoes.add(new PendingEcho(echo.createAtLocation(rightFoot, FootstepGenerator.LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
            }
        } else {
            // Fallback: material land/run thud per foot + delayed echo (1.12.2
            // playMultifoot semantics without a configured composition).
            var landLoc = FootstepGenerator.resolveLandSound(entity);
            var primary = SOUND_LIBRARY.getSoundFactoryOrDefault(landLoc);
            this.audioPlayer.play(primary.createAtLocation(leftFoot, scale));
            this.audioPlayer.play(primary.createAtLocation(rightFoot, scale));
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocation(leftFoot, FootstepGenerator.LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
            this.pendingEchoes.add(new PendingEcho(primary.createAtLocation(rightFoot, FootstepGenerator.LAND_ECHO_VOLUME * scale), this.tickCount + echoDelay));
        }

        // Landing accents: surface's layered accents (grass+brush, ice+muffledice, ...)
        if (this.config.footstepAccents.enableAccents) {
            final var landState = FootstepGenerator.resolveSurfaceBlock(entity, entity.level(), feetPos.below());
            if (!landState.isAir() && landState.getFluidState().isEmpty()) {
                SOUND_LIBRARY.getRemappedSound(landState.getSoundType().getStepSound(), landState)
                        .ifPresent(remap -> remap.accents().forEach(accent ->
                                this.audioPlayer.play(SOUND_LIBRARY.getSoundFactoryOrDefault(accent)
                                        .createAtLocation(feetCenter, var.volumeScale() * dsVolume()))));
            }
        }
    }

    private float dsVolume() {
        return (float) this.config.soundOptions.footstepVolume;
    }

    @Override
    public void onDisconnect() {
        this.pendingEchoes.clear();
        this.states.clear();
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems, "Creature footsteps: managed %d".formatted(this.managedCount));
    }
}
