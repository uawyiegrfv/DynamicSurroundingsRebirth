package org.orecruncher.dsurround.runtime.audio.effects;

import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.orecruncher.dsurround.runtime.audio.SourceContext;

public final class Effects {
    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    private static final Configuration.EnhancedSounds CONFIG = ContainerManager.resolve(Configuration.EnhancedSounds.class);
    // General config settings that need to make their way somewhere
    // 26.1: the mixin now requests 4 auxiliary sends at context creation (matching 1.21.1),
    // so all four reverb zones apply. Slightly below the original 1.12.2 baseline per
    // user tuning: cave/room reverb was still a touch too strong at 0.7.
    // 1.20.1 forensics: reverted to the exact 26.1 values. Brute-force boosting made
    // reverb loud EVERYWHERE, which proves the wet path is wired correctly and
    // localises the defect to the near-zero cavern send gains, not effect tuning.
    // 2026-09-04: user tuning - the default (reverbIntensity=1.0) was still too strong,
    // so the baseline is reduced by another 0.6x (0.6 * 0.6). reverbIntensity is live:
    // 1.667 restores the previous default strength, 0.0 disables reverb entirely.
    private static final float GLOBAL_REVERB_MULTIPLIER = 0.36F;

    public static final float GLOBAL_BLOCK_ABSORPTION = 1F;
    public static final float SNOW_AIR_ABSORPTION_FACTOR = 5F;
    public static final float RAIN_AIR_ABSORPTION_FACTOR = 2F;

    public static final ReverbData reverbData0;
    public static final ReverbData reverbData1;
    public static final ReverbData reverbData2;
    public static final ReverbData reverbData3;
    private static final AuxSlot auxSlot0 = new AuxSlot();
    private static final AuxSlot auxSlot1 = new AuxSlot();
    private static final AuxSlot auxSlot2 = new AuxSlot();
    private static final AuxSlot auxSlot3 = new AuxSlot();
    private static final ReverbEffectSlot reverb0 = new ReverbEffectSlot();
    private static final ReverbEffectSlot reverb1 = new ReverbEffectSlot();
    private static final ReverbEffectSlot reverb2 = new ReverbEffectSlot();
    private static final ReverbEffectSlot reverb3 = new ReverbEffectSlot();

    // 26.1: the four zones are mapped onto however many auxiliary sends the device
    // actually supports, with a FIXED zone i -> send i binding. Most OpenAL devices
    // expose only 2 sends, while this system computes four reverb zones (small room
    // -> cavern); on such devices only the two short zones play.
    private static final AuxSlot[] AUX_SLOTS = { auxSlot0, auxSlot1, auxSlot2, auxSlot3 };
    // 1.20.1: per-source send filters, as in 26.1. OpenAL filters are shared parameter
    // blobs: a global filter has every concurrently playing source overwrite the
    // parameters of all others. The lazy creation on the sound-engine thread is safe -
    // the EARTEST run proved alSource3i/filter calls execute with a current context there.
    private static final ReverbEffectSlot[] REVERB_SLOTS = { reverb0, reverb1, reverb2, reverb3 };
    private static final ReverbData[] REVERB_DATA = new ReverbData[4];
    private static int activeSends = 0;
    private static long applyCounter = 0;
    // Last reverb intensity applied to the OpenAL effect slots; tracks config changes so
    // the intensity slider takes effect live without a restart.
    private static float lastReverbIntensity = Float.NaN;

    static {
        reverbData0 = new ReverbData();
        reverbData0.diffusion = EXTEfx.AL_EAXREVERB_DEFAULT_DIFFUSION;
        reverbData0.lateReverbGain = EXTEfx.AL_EAXREVERB_DEFAULT_LATE_REVERB_GAIN;
        reverbData0.airAbsorptionGainHF = EXTEfx.AL_EAXREVERB_DEFAULT_AIR_ABSORPTION_GAINHF;

        reverbData0.decayTime = 0.15F;
        reverbData0.gain = 0.2F * 0.85F * GLOBAL_REVERB_MULTIPLIER;
        reverbData0.gainHF = 0.99F;

        reverbData1 = new ReverbData();
        reverbData1.diffusion = EXTEfx.AL_EAXREVERB_DEFAULT_DIFFUSION;
        reverbData1.lateReverbGain = EXTEfx.AL_EAXREVERB_DEFAULT_LATE_REVERB_GAIN;
        reverbData1.airAbsorptionGainHF = EXTEfx.AL_EAXREVERB_DEFAULT_AIR_ABSORPTION_GAINHF;

        reverbData1.decayTime = 0.55F;
        reverbData1.gain = 0.3F * 0.85F * GLOBAL_REVERB_MULTIPLIER;
        reverbData1.gainHF = 0.99F;

        reverbData2 = new ReverbData();
        reverbData2.diffusion = EXTEfx.AL_EAXREVERB_DEFAULT_DIFFUSION;
        reverbData2.lateReverbGain = EXTEfx.AL_EAXREVERB_DEFAULT_LATE_REVERB_GAIN;
        reverbData2.airAbsorptionGainHF = EXTEfx.AL_EAXREVERB_DEFAULT_AIR_ABSORPTION_GAINHF;

        reverbData2.decayTime = 1.68F;
        reverbData2.gain = 0.5F * 0.85F * GLOBAL_REVERB_MULTIPLIER;
        reverbData2.gainHF = 0.99F;

        reverbData3 = new ReverbData();
        reverbData3.diffusion = EXTEfx.AL_EAXREVERB_DEFAULT_DIFFUSION;
        reverbData3.lateReverbGain = EXTEfx.AL_EAXREVERB_DEFAULT_LATE_REVERB_GAIN;
        reverbData3.airAbsorptionGainHF = EXTEfx.AL_EAXREVERB_DEFAULT_AIR_ABSORPTION_GAINHF;

        reverbData3.decayTime = 4.142F;
        reverbData3.gain = 0.4F * 0.85F * GLOBAL_REVERB_MULTIPLIER;
        reverbData3.gainHF = 0.89F;

        REVERB_DATA[0] = reverbData0;
        REVERB_DATA[1] = reverbData1;
        REVERB_DATA[2] = reverbData2;
        REVERB_DATA[3] = reverbData3;
    }

    private Effects() {

    }

    public static int getActiveSends() {
        return activeSends;
    }

    /**
     * Recomputes each reverb zone's wet gain from the configurable intensity. Called on
     * every sound-system (re)init so changing the slider takes effect without a restart.
     */
    private static void refreshReverbIntensity() {
        final float intensity = (float) CONFIG.reverbIntensity;
        reverbData0.gain = 0.2F * 0.85F * GLOBAL_REVERB_MULTIPLIER * intensity;
        reverbData1.gain = 0.3F * 0.85F * GLOBAL_REVERB_MULTIPLIER * intensity;
        reverbData2.gain = 0.5F * 0.85F * GLOBAL_REVERB_MULTIPLIER * intensity;
        reverbData3.gain = 0.4F * 0.85F * GLOBAL_REVERB_MULTIPLIER * intensity;
    }

    /**
     * Applies the reverb intensity when the config changes. The intensity slider has no
     * restart requirement, so the zone gains and their OpenAL effect slots are refreshed
     * lazily on the next sound processing pass (SourceContext.tick runs on the sound
     * thread). An intensity of 0 fully silences the wet (echo) path.
     */
    private static void refreshIntensityIfChanged() {
        final float intensity = (float) CONFIG.reverbIntensity;
        if (intensity != lastReverbIntensity) {
            lastReverbIntensity = intensity;
            refreshReverbIntensity();
            for (int i = 0; i < activeSends; i++)
                REVERB_SLOTS[i].apply(REVERB_DATA[i], AUX_SLOTS[i]);
        }
    }

    /** Last early-reflection delay pushed to the effect slots, to avoid redundant AL calls. */
    private static float lastReflectionsDelay = Float.NaN;

    /**
     * Sets the FIRST-REFLECTION delay of the reverb, which is what decides whether a space is heard as
     * REVERB or as an ECHO. This is one physical mechanism with two perceptual regimes, not two modes:
     *
     * <pre>
     *   gap &lt; ~50 ms  -> the reflection fuses with the direct sound  -> heard as REVERB
     *   gap &gt; ~50 ms  -> the reflection separates from it            -> heard as an ECHO
     * </pre>
     *
     * <p>The ~50 ms boundary is the Haas fusion window, a property of the auditory system rather than a
     * preference, so it is used as a constant and NOT as a tuning knob. A cave's walls are 3-18 blocks
     * away, so its first reflection arrives 10-40 ms late and is heard as reverb; a valley's walls are
     * 45-120 blocks away, so its first reflection arrives 130-340 ms late and is heard as an echo. The
     * SAME formula produces both - there is no branch on scene type anywhere.
     *
     * <p>Above the threshold the reflection is a discrete event, so it needs to be one event and not a
     * train of them: feedback-style repeats are deliberately not used, because they read as a metallic
     * flutter rather than as a valley. The tail length is left to {@code decayTime}, which the zone
     * parameters already set from the measured geometry.
     *
     * <p>Delay is a property of the AUX SLOT, not of a source, so this is a global value: every source
     * sharing a zone shares its reflection delay. Driving it per source is not possible through EFX.
     *
     * @param seconds measured round-trip gap between the direct sound and the first reflection
     */
    public static void setEarlyReflectionDelay(final float seconds) {
        if (activeSends <= 0)
            return;
        if (Float.isNaN(lastReflectionsDelay) || Math.abs(seconds - lastReflectionsDelay) >= DELAY_UPDATE_EPSILON) {
            lastReflectionsDelay = seconds;
            for (int i = 0; i < activeSends; i++) {
                REVERB_DATA[i].reflectionsDelay = seconds;
                REVERB_SLOTS[i].apply(REVERB_DATA[i], AUX_SLOTS[i]);
            }
        }
    }

    /**
     * How much the reflection delay must move before the effect slots are re-uploaded.
     *
     * <p>Re-attaching an effect to its slot is a real OpenAL call, and the delay is recomputed on every
     * sound evaluation (up to 20/s per source). 5 ms is far below the ~50 ms perceptual boundary and well
     * below the resolution at which a delay change is audible, so it collapses the great majority of
     * updates to nothing while keeping the audible behaviour continuous.
     */
    private static final float DELAY_UPDATE_EPSILON = 0.005F;

    public static void initialize() {
        // Force-regenerate every EFX object. On sound-system reinit (toggling reverb/
        // occlusion in config, resource reload, device change) the old OpenAL handles
        // point into a destroyed context, so reset them before creating new ones.
        for (final AuxSlot s : AUX_SLOTS)
            s.deinitialize();
        for (final ReverbEffectSlot s : REVERB_SLOTS)
            s.deinitialize();

        activeSends = Math.min(4, AudioUtilities.getMaxAuxSends());
        // The effect objects are brand new, so any previously pushed reflection delay is gone with the
        // old context. Reset the cache so the next evaluation re-uploads instead of being skipped.
        lastReflectionsDelay = Float.NaN;
        // DIAG(1.20.1): reverb slots
        org.orecruncher.dsurround.lib.Library.LOGGER.debug("REVERB_INIT activeSends=%d", getActiveSends());
        if (activeSends <= 0)
            return;

        refreshReverbIntensity();
        lastReverbIntensity = (float) CONFIG.reverbIntensity;

        for (int i = 0; i < activeSends; i++) {
            AUX_SLOTS[i].initialize();
            REVERB_SLOTS[i].initialize();
            REVERB_DATA[i].setProcess(true);
            // Fixed binding: send i always carries zone i. The reverb effect parameters
            // are static per zone, so the binding never needs to change afterwards.
            REVERB_SLOTS[i].apply(REVERB_DATA[i], AUX_SLOTS[i]);
            // DIAG(1.20.1): confirm the OpenAL objects are real (non-zero handles)
            org.orecruncher.dsurround.lib.Library.LOGGER.debug("REVERB_SLOT[%d] aux=%d effect=%d effectGain=%.3f decay=%.2f",
                    i, AUX_SLOTS[i].getSlot(), REVERB_SLOTS[i].getSlot(), REVERB_DATA[i].gain, REVERB_DATA[i].decayTime);
        }
    }

    public static void deinitialize() {
        for (int i = 0; i < activeSends; i++) {
            AUX_SLOTS[i].deinitialize();
            REVERB_SLOTS[i].deinitialize();
            REVERB_DATA[i].setProcess(false);
        }

        activeSends = 0;
        lastReflectionsDelay = Float.NaN;
    }

    /**
     * Applies the reverb data for a sound source. Each send carries a fixed reverb
     * zone, while the per-send low-pass shaping and the direct-path filter use the
     * source's OWN filter objects: OpenAL filters are shared parameter blobs, so a
     * global filter would have every concurrently playing source overwrite the
     * parameters of all others.
     */
    public static void applyReverb(final SourceContext source) {
        if (activeSends <= 0 || !source.isEnabled())
            return;

        refreshIntensityIfChanged();

        final int sourceId = source.getId();

        for (int zone = 0; zone < activeSends; zone++) {
            source.zoneFilter(zone).apply(sourceId, source.getLowPass(zone), zone, AUX_SLOTS[zone]);
        }

        // Diagnostic: steady-state zone mapping, one line per ~second of playtime.
        // debug level - this runs on the sound engine thread and logging I/O here can
        // perturb timing (Heisenbug lesson). Enable debug for reverb tracing.
        if (++applyCounter % 140 == 0) {
            var sound = source.getSound();
            var soundId = sound == null ? "?" : sound.getLocation().toString();
            LOGGER.debug("REVERB_STEADY src=%d sound=%s pos=%.1f,%.1f,%.1f sends=%d process=[%s,%s,%s,%s] gains=[%.3f,%.3f,%.3f,%.3f] cutoffs=[%.3f,%.3f,%.3f,%.3f] direct=[%.3f,%.3f/%s] filters=[%d,%d,%d,%d]/%d",
                    sourceId, soundId, source.getPosition().x, source.getPosition().y, source.getPosition().z, activeSends,
                    source.getLowPass(0).doProcess(), source.getLowPass(1).doProcess(),
                    source.getLowPass(2).doProcess(), source.getLowPass(3).doProcess(),
                    source.getLowPass(0).gain, source.getLowPass(1).gain, source.getLowPass(2).gain, source.getLowPass(3).gain,
                    source.getLowPass(0).gainHF, source.getLowPass(1).gainHF, source.getLowPass(2).gainHF, source.getLowPass(3).gainHF,
                    source.getDirect().gain, source.getDirect().gainHF, source.getDirect().doProcess(),
                    source.zoneFilter(0).getSlot(), source.zoneFilter(1).getSlot(),
                    source.zoneFilter(2).getSlot(), source.zoneFilter(3).getSlot(),
                    source.directFilter().getSlot());
        }

        // Occlusion / direct path filter and air absorption are independent of the aux sends
        source.directFilter().apply(sourceId, source.getDirect());
        source.getAirAbsorb().apply(sourceId);
    }
}
