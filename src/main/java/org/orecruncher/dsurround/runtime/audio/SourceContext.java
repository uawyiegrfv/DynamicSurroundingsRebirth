package org.orecruncher.dsurround.runtime.audio;

import com.google.common.base.MoreObjects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.runtime.audio.effects.Effects;
import org.orecruncher.dsurround.runtime.audio.effects.LowPassData;
import org.orecruncher.dsurround.runtime.audio.effects.LowPassFilterSlot;
import org.orecruncher.dsurround.runtime.audio.effects.SourcePropertyFloat;

import java.util.concurrent.Callable;

public final class SourceContext implements Callable<Void> {

    // Frequency of sound effect updates in thread schedule ticks, by how FAR the source is.
    //
    // Near (within 16 blocks) is twice a second: occlusion and reverb follow the player quickly
    // enough that a wall crossing or a terrain boundary does not feel laggy. Further out the
    // source is quieter, its geometry changes more slowly in the player's frame, and an update
    // that lands a second late is inaudible under the smoothing - so those pay for themselves
    // less often.
    //
    // This is what scales. A flat interval meant every registered context was evaluated at the
    // full rate no matter how far away or how quiet: measured with a swarm of magma cubes, 58
    // contexts x 2/s x ~211 raycasts came to roughly 24k raycasts a second. MAX_SOURCES_PER_PASS
    // does not protect against that - it only caps how many come due in ONE pass, and with the
    // staggered start below only about six of 58 are due in any pass, so it never engages.
    private static final int UPDATE_FREQUENCY_TICKS_NEAR = 10;
    private static final int UPDATE_FREQUENCY_TICKS_MID = 20;
    private static final int UPDATE_FREQUENCY_TICKS_FAR = 30;
    private static final double NEAR_BLOCKS = 16.0D;
    private static final double MID_BLOCKS = 32.0D;

    /**
     * The evaluation cadence the smoothing constants defined below were tuned against, in ticks.
     *
     * <p>NOT the nominal near band (10). exec() re-staggers the modulo phase after every evaluation,
     * so the real gap is uniform over [1, interval] and the near band's true mean gap is
     * (10 + 1) / 2 = 5.5 ticks. The alphas were picked against that real cadence, so that is what the
     * reference has to be: using the nominal 10 would make the near band - the one the player notices
     * most - settle about 50% slower than it does today, which is the opposite of what this change is
     * for.
     *
     * <p>Those alphas are expressed per-UPDATE, so with the interval varying 10/20/30 ticks by
     * distance the same alpha gives a three-fold different response time in seconds. smoothAlpha()
     * re-bases them on the time that actually passed, using this as the reference, which keeps the
     * near band exactly as responsive as it is now and speeds the distant bands up to match it.
     *
     * <p>Derived from the constant rather than hard-coded, so it follows if the near band changes.
     */
    private static final double SMOOTH_REFERENCE_TICKS = (UPDATE_FREQUENCY_TICKS_NEAR + 1) / 2.0D;

    /**
     * How far the listener may travel before a source's cached geometry counts as stale enough to
     * re-evaluate early, in blocks (squared for the comparison). The distance bands decide how OFTEN
     * a source is evaluated - that is the cost control and it stays; this bounds how far the player
     * can move BETWEEN those evaluations, which is what fast movement needs.
     */
    private static final double LISTENER_MOVE_TRIGGER_SQ = 2.0D * 2.0D;

    /**
     * Shortest gap, in passes, between two displacement-triggered re-evaluations of the SAME source.
     *
     * <p>Derived from the source's own distance band rather than fixed. The trigger exists to bound how
     * far the listener travels between evaluations - NOT to raise the evaluation rate - so it must not
     * outrun what the band was designed to cost. A flat floor does exactly that: at 3 passes every
     * source runs at 20/3 = 6.7 evaluations a second no matter how far away it is, which is 1.83x the
     * near band's real rate but 5.17x the far band's, and the bands would stop scaling - the one thing
     * they exist to do.
     *
     * <p>A third of the band bounds each band by its own cadence: worst case 1.83x near, 1.75x mid,
     * 1.55x far (measured against each band's REAL mean gap of (interval + 1) / 2, not the nominal
     * interval), while still letting the trigger roughly double a source's rate when the geometry is
     * genuinely moving.
     */
    private int displacementIntervalFloorPasses() {
        return Math.max(2, updateIntervalTicks() / 3);
    }

    // Time-smoothing for the water damping factor. The sampled underwater path length can jump
    // by a block when an entity bobs at the water surface (or the player wades). Alpha picks a
    // ~0.8 second settle time: fast enough that a change is not laggy, slow enough to smooth
    // the per-update jumps.
    private static final float WATER_SMOOTH_ALPHA = 0.85F;
    // Diffraction restore settles on its own time constant so it can be tuned
    // independently of the water/occlusion smoothing. Same 0.85 (~0.8s) baseline.
    private static final float DIFFRACTION_SMOOTH_ALPHA = 0.85F;
    // Occlusion smoothing gets its own constant so tuning it never accidentally changes
    // the water damping (same 0.85 baseline).
    private static final float OCCLUSION_SMOOTH_ALPHA = 0.85F;
    // The early-reflection gain is a ratio of two 32-ray averages, so it is noisier than the occlusion
    // value it sits beside; it gets a slower constant of its own so calming it never changes the others.
    // 0.5 settles in about 0.7 s at the 0.5 s update interval: long enough to average the sampling noise,
    // short enough that walking out of a valley still fades rather than lagging.
    private static final float EARLY_REFLECTION_SMOOTH_ALPHA = 0.5F;
    /**
     * Time-smoothing for the four reverb send gains.
     *
     * <p>These were the ONE quantity the evaluation produced that nothing smoothed. Occlusion, water and
     * the early reflection all ease toward their target; the send gains were written straight to OpenAL,
     * so every 0.5 s the whole reverb amount jumped to a fresh sample.
     *
     * <p>That sample is noisy. The gains come from 32 rays x 4 bounces, and the mean free path alone was
     * measured to swing by up to 53 blocks within a single second as the source moved - which matters
     * because the room-size scale is {@code min(1, mfp/6)}, so that swing moves sendGain1 and sendGain2
     * between zero and their full value. The audible result was reverb appearing and disappearing in
     * scenes that should not change.
     *
     * <p>Slower than the occlusion constant (0.85) because there is more sampling noise to average out,
     * faster than the early reflection's (0.5) because a reverb amount that lags too far behind the
     * listener's movement reads as a fault in itself.
     */
    private static final float SEND_GAIN_SMOOTH_ALPHA = 0.7F;

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);

    private final Object sync = new Object();
    private final LowPassData lowPass0;
    private final LowPassData lowPass1;
    private final LowPassData lowPass2;
    private final LowPassData lowPass3;
    private final LowPassData direct;
    // OpenAL filters are shared parameter blobs: every source needs its own filter
    // objects or concurrent sources overwrite each other's low-pass shaping.
    private final LowPassFilterSlot[] zoneFilters = {
            new LowPassFilterSlot(), new LowPassFilterSlot(), new LowPassFilterSlot(), new LowPassFilterSlot()
    };
    private final LowPassFilterSlot directFilter = new LowPassFilterSlot();
    private final SourcePropertyFloat airAbsorb;
    private final SoundFXUtils fxProcessor;

    private final int sourceId;

    private SoundInstance sound;
    // Written by the evaluation on the pool thread (captureState) and read by the worker when it
    // sorts the due list by distance, so it is genuinely cross-thread. A stale read only mis-orders
    // that sort, but the read should still be visible.
    private volatile Vec3 pos;
    private SoundSource category = SoundSource.MASTER;

    private boolean isEnabled;
    private int updateCount;

    // How many passes actually elapsed before this evaluation, so the smoothing can hold a constant
    // response time in seconds. volatile: written by the evaluation on the pool, read on the worker.
    private volatile double ticksSinceLastEvaluation = SMOOTH_REFERENCE_TICKS;
    private volatile long lastEvalPass;
    private volatile boolean lastEvalRecorded;
    private volatile Vec3 lastEvalListenerPos;
    private volatile boolean lastEvalListenerValid;
    private float smoothedWaterFactor = 1.0F;
    private boolean waterFactorInitialized;
    private float smoothedOcclusion = 0F;
    private boolean occlusionInitialized;
    private float smoothedDiffraction = 0F;
    private boolean diffractionInitialized;
    /** Smoothed early-reflection gain; see {@link #smoothEarlyReflection}. */
    private float smoothedEarlyReflection = 0F;
    private boolean earlyReflectionInitialized;
    /** Smoothed reverb send gains, one per zone; see {@link #smoothSendGains}. */
    private final float[] smoothedSendGains = new float[4];
    private boolean sendGainsInitialized;
    // Set by the sound processor when the player just entered/left water. The next
    // evaluation snaps the smoothing state straight to its target instead of easing, so
    // entering/exiting water responds with no audible lag.
    private volatile boolean immediateUpdate;

    public SourceContext(int sourceId) {
        this.sourceId = sourceId;
        this.lowPass0 = new LowPassData();
        this.lowPass1 = new LowPassData();
        this.lowPass2 = new LowPassData();
        this.lowPass3 = new LowPassData();
        this.direct = new LowPassData();
        this.airAbsorb = new SourcePropertyFloat(EXTEfx.AL_AIR_ABSORPTION_FACTOR, EXTEfx.AL_DEFAULT_AIR_ABSORPTION_FACTOR, EXTEfx.AL_MIN_AIR_ABSORPTION_FACTOR, EXTEfx.AL_MAX_AIR_ABSORPTION_FACTOR);
        this.pos = Vec3.ZERO;
        this.fxProcessor = new SoundFXUtils(this);
    }

    public Object sync() {
        return this.sync;
    }

    public int getId() {
        return this.sourceId;
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    public void enable() {
        this.isEnabled = true;
    }

    public LowPassData getLowPass0() {
        return this.lowPass0;
    }

    public LowPassData getLowPass1() {
        return this.lowPass1;
    }

    public LowPassData getLowPass2() {
        return this.lowPass2;
    }

    public LowPassData getLowPass3() {
        return this.lowPass3;
    }

    public LowPassData getDirect() {
        return this.direct;
    }

    public SourcePropertyFloat getAirAbsorb() {
        return this.airAbsorb;
    }

    /**
     * Retrieves the low-pass data for the given reverb zone (0..3). 26.1: zones are mapped
     * onto the available auxiliary sends with a fixed zone i -> send i binding.
     */
    public LowPassData getLowPass(int zone) {
        return switch (zone) {
            case 0 -> this.lowPass0;
            case 1 -> this.lowPass1;
            case 2 -> this.lowPass2;
            default -> this.lowPass3;
        };
    }

    /** This source's low-pass filter for the given reverb zone. Sound-engine-thread use only. */
    public LowPassFilterSlot zoneFilter(final int zone) {
        return this.zoneFilters[Math.floorMod(zone, this.zoneFilters.length)];
    }

    /** This source's direct-path low-pass filter. Sound-engine-thread use only. */
    public LowPassFilterSlot directFilter() {
        return this.directFilter;
    }

    public Vec3 getPosition() {
        return this.pos;
    }


    public SoundSource getCategory() {
        return this.category;
    }

    /** Eases a scalar toward its target: {@code current + (target - current) * alpha}. */
    private static float ease(final float current, final float target, final float alpha) {
        return current + (target - current) * alpha;
    }

    /**
     * Re-bases a per-update smoothing constant on the time that actually passed, so the response
     * time stays constant in SECONDS no matter which distance band the source is in.
     *
     * <p>This is the counterpart to keeping the distance bands: the bands are the performance
     * mechanism and stay as they are, so the gap between evaluations legitimately varies from about
     * one tick to a second and a half. A fixed alpha then means the same sound settles in ~0.3 s
     * near and ~1.5 s far, and after a long gap a single 0.85 step is a large audible jump. Solving
     * a0 per reference interval and rescaling by dt gives the continuous-time equivalent: at
     * dt == reference it is exactly the original alpha, longer gaps approach 1 (which is where a
     * continuous filter with the same time constant already would be), shorter ones shrink so the
     * fade cannot outrun real time.
     */
    private float smoothAlpha(final float alpha0) {
        final double dt = this.ticksSinceLastEvaluation;
        final double ratio = dt / SMOOTH_REFERENCE_TICKS;
        if (ratio == 1.0D)
            return alpha0;
        return (float) (1.0D - Math.pow(1.0D - alpha0, ratio));
    }

    /**
     * Time-smooths the water damping factor. The sampled underwater path length can jump
     * by a block when an entity bobs at the water surface (or the player wades); smoothing
     * turns the jump into a fade. When {@code snap} is true (the player just entered/left
     * water) the value is set directly so the change is audible immediately.
     */
    public float smoothWaterFactor(final float target, final boolean snap) {
        if (!this.waterFactorInitialized || snap) {
            this.waterFactorInitialized = true;
            this.smoothedWaterFactor = target;
        } else {
            this.smoothedWaterFactor = ease(this.smoothedWaterFactor, target, smoothAlpha(WATER_SMOOTH_ALPHA));
        }
        return this.smoothedWaterFactor;
    }

    /**
     * Time-smooths the ray-traced occlusion value. The occlusion ray can jump sharply when
     * the player crosses a geometric boundary (e.g. a ray starting to clip the ground a few
     * blocks away), which makes the muffling flip abruptly; smoothing it turns the jump into
     * a fade.
     */
    public float smoothOcclusion(final float target, final boolean snap) {
        if (!this.occlusionInitialized || snap) {
            this.occlusionInitialized = true;
            this.smoothedOcclusion = target;
        } else {
            this.smoothedOcclusion = ease(this.smoothedOcclusion, target, smoothAlpha(OCCLUSION_SMOOTH_ALPHA));
        }
        return this.smoothedOcclusion;
    }

    /**
     * Time-smooths the early-reflection gain toward its target.
     *
     * <p>Measured jitter without this: between consecutive evaluations less than 0.4 s apart the gain
     * swung by up to 0.36 - from 0.152 to 0.512, and from 0.482 down to 0.136 - so a scene either had a
     * tail or did not, apparently at random. The user described exactly that: "the places that should not
     * have it sometimes do and sometimes do not".
     *
     * <p>The cause is sampling, not the model. The gain is built from the mean free path and the returned
     * energy share, both averaged over only 32 rays, and the mean free path alone was measured to swing by
     * up to 53 blocks within a single second as the source moved. Easing the result is what the occlusion
     * and water terms already do for the same reason.
     *
     * <p>Snaps on the first evaluation, so a freshly played sound is not initially silent.
     */
    public float smoothEarlyReflection(final float target, final boolean snap) {
        if (!this.earlyReflectionInitialized || snap) {
            this.earlyReflectionInitialized = true;
            this.smoothedEarlyReflection = target;
        } else {
            this.smoothedEarlyReflection = ease(this.smoothedEarlyReflection, target, smoothAlpha(EARLY_REFLECTION_SMOOTH_ALPHA));
        }
        return this.smoothedEarlyReflection;
    }

    /**
     * Time-smooths the four reverb send gains, in place.
     *
     * <p>Snaps on the first evaluation and whenever {@code snap} is set (the player just entered or left
     * water), so a freshly played sound does not fade its reverb in and a water transition is immediate.
     *
     * @param gains the four zone gains, updated to their smoothed values on return
     */
    public void smoothSendGains(final float[] gains, final boolean snap) {
        if (!this.sendGainsInitialized || snap) {
            this.sendGainsInitialized = true;
            System.arraycopy(gains, 0, this.smoothedSendGains, 0, this.smoothedSendGains.length);
        } else {
            for (int i = 0; i < this.smoothedSendGains.length; i++)
                this.smoothedSendGains[i] = ease(this.smoothedSendGains[i], gains[i], smoothAlpha(SEND_GAIN_SMOOTH_ALPHA));
        }
        System.arraycopy(this.smoothedSendGains, 0, gains, 0, this.smoothedSendGains.length);
    }

    /**
     * Time-smooths the diffraction compensation toward the target. The compensation
     * jumps when the player crosses a room boundary - the openness and enclosure
     * probes change in a single step, so the direct restore would snap. Easing it
     * turns the indoor/outdoor toggle into a fade. Snaps on the first evaluation so
     * a freshly played sound is not initially damped.
     */
    public float smoothDiffraction(final float target, final boolean snap) {
        if (!this.diffractionInitialized || snap) {
            this.diffractionInitialized = true;
            this.smoothedDiffraction = target;
        } else {
            this.smoothedDiffraction = ease(this.smoothedDiffraction, target, smoothAlpha(DIFFRACTION_SMOOTH_ALPHA));
        }
        return this.smoothedDiffraction;
    }

    /**
     * Requests the next evaluation to snap its smoothing state instead of easing. Set when
     * the player enters or leaves water so the damping reacts with no lag.
     */
    public void markImmediate() {
        this.immediateUpdate = true;
    }

    /**
     * Consumes the immediate-update flag (set by {@link #markImmediate()}).
     */
    public boolean isImmediateUpdate() {
        final boolean result = this.immediateUpdate;
        this.immediateUpdate = false;
        return result;
    }

    public void attachSound(final SoundInstance sound) {
        this.sound = sound;
        this.category = sound.getSource();
        captureState();
    }

    @Nullable
    public SoundInstance getSound() {
        return this.sound;
    }

    /**
     * Called on the SoundSource update thread when updating status.  Do not call from the client thread or bad things
     * can happen.
     */
    public void tick() {
        if (this.isEnabled()) {
            synchronized (this.sync()) {
                this.ensureFilters();
                // 1.20.1: upload through the effect manager so the reverb zones are mapped onto
                // the number of auxiliary sends the device actually supports.
                Effects.applyReverb(this);
                AudioUtilities.validate("SourceHandler::tick");
            }
        }
    }

    /**
     * Called on the sound engine thread when the source is terminated: releases this
     * source's OpenAL filter objects so they do not leak in the device.
     */
    public void stop() {
        this.isEnabled = false;
        synchronized (this.sync()) {
            for (final LowPassFilterSlot filter : this.zoneFilters)
                filter.deinitialize();
            this.directFilter.deinitialize();
        }
    }

    /** Sound-engine-thread only: lazily creates this source's filter objects. */
    private void ensureFilters() {
        for (final LowPassFilterSlot filter : this.zoneFilters)
            filter.initialize();
        this.directFilter.initialize();
    }

    /**
     * Called by the sound processing thread when scheduling work items for sound updates.  This routine should only
     * be called by the background thread.
     *
     * @return true the work item should be scheduled; false otherwise
     */
    public boolean shouldExecute() {
        if ((this.updateCount++ % updateIntervalTicks()) == 0)
            return true;
        return this.listenerMovedSinceLastEvaluation();
    }

    /**
     * True when the listener has travelled far enough since this source was last evaluated that its
     * cached occlusion/water geometry is worth recomputing.
     *
     * <p>The distance bands say nothing about how far the player moves in between two scheduled
     * updates: sprinting from far to near crosses several blocks, so the muffling arrives late and
     * then has to cover the whole change in one step. This closes that hole the same way the water
     * and skylight triggers in SoundFXProcessor already do - "the next scheduled evaluation is up to
     * half a second away, force it now" - but deliberately WITHOUT snapping, because here the
     * geometry changed gradually and should be eased into rather than jumped to.
     *
     * <p>Cost: one squared-distance compare against a snapshot, so a stationary player pays nothing.
     */
    private boolean listenerMovedSinceLastEvaluation() {
        if (!this.lastEvalListenerValid)
            return false;
        if (SoundFXProcessor.passCounter() - this.lastEvalPass < this.displacementIntervalFloorPasses())
            return false;
        final WorldContext ctx = SoundFXProcessor.getWorldContext();
        if (ctx == null)
            return false;
        return this.lastEvalListenerPos.distanceToSqr(ctx.playerEyePosition) > LISTENER_MOVE_TRIGGER_SQ;
    }

    /**
     * How often this source is re-evaluated, by distance from the ear.
     *
     * <p>The interval is re-read every scheduling tick rather than cached, so a source the player
     * walks towards starts updating at the near rate immediately. A changing interval shifts the
     * modulo phase, which at worst costs one early or late update when crossing a threshold - and
     * the send gains are time-smoothed, so that is not audible as a step.
     */
    private int updateIntervalTicks() {
        final WorldContext ctx = SoundFXProcessor.getWorldContext();
        if (ctx == null)
            return UPDATE_FREQUENCY_TICKS_NEAR;
        final double distance = this.getPosition().distanceTo(ctx.playerEyePosition);
        if (distance <= NEAR_BLOCKS)
            return UPDATE_FREQUENCY_TICKS_NEAR;
        if (distance <= MID_BLOCKS)
            return UPDATE_FREQUENCY_TICKS_MID;
        return UPDATE_FREQUENCY_TICKS_FAR;
    }

    @Override
    public Void call() {
        this.captureState();
        this.updateImpl();
        return null;
    }

    /**
     * Called by the thread pool when executing the task
     */
    public void exec() {
        this.captureState();
        this.updateImpl();
        this.updateCount = Randomizer.current().nextInt(updateIntervalTicks());
        this.tick();
    }

    private void updateImpl() {
        // Measure how long it really was since this source was last evaluated. The interval is
        // distance-banded and re-staggered, so the real gap ranges from about one tick to a second
        // and a half; the smoothing needs the real figure to keep its response time constant.
        final WorldContext ctx = SoundFXProcessor.getWorldContext();
        final long pass = SoundFXProcessor.passCounter();
        this.ticksSinceLastEvaluation = this.lastEvalRecorded
                ? Math.max(1L, pass - this.lastEvalPass)
                : SMOOTH_REFERENCE_TICKS;
        try {
            this.fxProcessor.calculate(ctx);
        } catch (final Throwable t) {
            // Suppress to keep a failing source from killing the processing thread, but
            // leave an error breadcrumb: this catch previously hid real defects (e.g. an
            // unregistered accessor mixin throwing ClassCastException on every evaluation,
            // silently disabling reverb for every sound).
            LOGGER.error(t, "REVERB_CALCFAIL sound=%s", AudioUtilities.debugString(this.sound));
        }
        this.lastEvalPass = pass;
        this.lastEvalRecorded = true;
        // Remember where the listener was for this evaluation so the next scheduling pass can tell
        // whether the geometry has moved enough to be worth an early re-evaluation (shouldExecute).
        if (ctx != null) {
            this.lastEvalListenerPos = ctx.playerEyePosition;
            this.lastEvalListenerValid = true;
        }
    }

    private void captureState() {
        // Runs on a pool thread, so this is a cross-thread read of a vanilla SoundInstance the
        // sound engine owns. It is deliberately defensive: a throw here would escape as an
        // ExecutionException that SoundFXProcessor swallows at task.get(), leaving the source on a
        // stale position with nothing in the log. Keep the previous position instead.
        try {
            final SoundInstance instance = this.sound;
            if (instance != null) {
                this.pos = new Vec3(instance.getX(), instance.getY(), instance.getZ());
            }
        } catch (final Throwable ignored) {
            // keep the last known position
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .addValue(AudioUtilities.debugString(this.sound))
                .toString();
    }

}