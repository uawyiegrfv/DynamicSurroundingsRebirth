package org.orecruncher.dsurround.runtime.audio;

import com.google.common.base.MoreObjects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.runtime.audio.effects.Effects;
import org.orecruncher.dsurround.runtime.audio.effects.LowPassData;
import org.orecruncher.dsurround.runtime.audio.effects.SourcePropertyFloat;

import java.util.concurrent.Callable;

public final class SourceContext implements Callable<Void> {

    // Frequency of sound effect updates in thread schedule ticks. Twice a second (10 ticks):
    // occlusion/reverb follow the player quickly enough that a wall crossing or terrain
    // boundary does not feel laggy, while the background ray-trace load stays bounded by
    // MAX_SOURCES_PER_PASS in SoundFXProcessor.
    private static final int UPDATE_FEQUENCY_TICKS = 10;
    // Time-smoothing for the water damping factor. The sampled underwater path length can jump
    // by a block when an entity bobs at the water surface (or the player wades). Alpha picks a
    // ~0.8 second settle time: fast enough that a change is not laggy, slow enough to smooth
    // the per-update jumps.
    private static final float WATER_SMOOTH_ALPHA = 0.85F;
    // Diffraction restore settles on its own time constant so it can be tuned
    // independently of the water/occlusion smoothing. Same 0.85 (~0.8s) baseline.
    private static final float DIFFRACTION_SMOOTH_ALPHA = 0.85F;

    private final Object sync = new Object();
    private final LowPassData lowPass0;
    private final LowPassData lowPass1;
    private final LowPassData lowPass2;
    private final LowPassData lowPass3;
    private final LowPassData direct;
    private final SourcePropertyFloat airAbsorb;
    private final SoundFXUtils fxProcessor;

    private final int sourceId;

    private SoundInstance sound;
    private Vec3 pos;
    private SoundSource category = SoundSource.MASTER;

    private boolean isEnabled;
    private int updateCount;
    private float smoothedWaterFactor = 1.0F;
    private boolean waterFactorInitialized;
    private float smoothedOcclusion = 0F;
    private boolean occlusionInitialized;
    private float smoothedDiffraction = 0F;
    private boolean diffractionInitialized;
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
     * Retrieves the low-pass data for the given reverb zone (0..3). 26.1: zones are ranked and
     * mapped onto the available auxiliary sends by the effect manager.
     */
    public LowPassData getLowPass(int zone) {
        return switch (zone) {
            case 0 -> this.lowPass0;
            case 1 -> this.lowPass1;
            case 2 -> this.lowPass2;
            default -> this.lowPass3;
        };
    }

    public Vec3 getPosition() {
        return this.pos;
    }


    public SoundSource getCategory() {
        return this.category;
    }

    /**
     * Time-smooths the water damping factor toward the target, snapping to it on the first
     * evaluation so a freshly played sound is not initially over-damped. When {@code snap}
     * is true (the player just entered/left water) the value is set directly so the change
     * is audible immediately. Called from the background sound-processing thread.
     */
    public float smoothWaterFactor(final float target, final boolean snap) {
        if (!this.waterFactorInitialized || snap) {
            this.smoothedWaterFactor = target;
            this.waterFactorInitialized = true;
        } else {
            this.smoothedWaterFactor += (target - this.smoothedWaterFactor) * WATER_SMOOTH_ALPHA;
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
            this.smoothedOcclusion = target;
            this.occlusionInitialized = true;
        } else {
            this.smoothedOcclusion += (target - this.smoothedOcclusion) * WATER_SMOOTH_ALPHA;
        }
        return this.smoothedOcclusion;
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
            this.smoothedDiffraction = target;
            this.diffractionInitialized = true;
        } else {
            this.smoothedDiffraction += (target - this.smoothedDiffraction) * DIFFRACTION_SMOOTH_ALPHA;
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
                // 26.1: upload through the effect manager so the reverb zones are mapped onto
                // the number of auxiliary sends the device actually supports.
                Effects.applyReverb(this);
                AudioUtilities.validate("SourceHandler::tick");
            }
        }
    }

    /**
     * Called by the sound processing thread when scheduling work items for sound updates.  This routine should only
     * be called by the background thread.
     *
     * @return true the work item should be scheduled; false otherwise
     */
    public boolean shouldExecute() {
        return (this.updateCount++ % UPDATE_FEQUENCY_TICKS) == 0;
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
        this.updateCount = Randomizer.current().nextInt(UPDATE_FEQUENCY_TICKS);
        this.tick();
    }

    private void updateImpl() {
        try {
            //if (this.sound.getId().getPath().contains("stone"))
                this.fxProcessor.calculate(SoundFXProcessor.getWorldContext());
        } catch (final Throwable ignore) {
            // Suppress.  Times that I have seen this fire was due to a world unloading and the background
            // processing threads tripping over dead objects.
        }
    }

    private void captureState() {
        if (this.sound != null) {
            this.pos = new Vec3(this.sound.getX(), this.sound.getY(), this.sound.getZ());
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .addValue(AudioUtilities.debugString(this.sound))
                .toString();
    }

}