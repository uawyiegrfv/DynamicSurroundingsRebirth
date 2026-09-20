package org.orecruncher.dsurround.runtime.audio.effects;

import net.minecraft.util.Mth;
import org.lwjgl.openal.AL10;
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
     * Reports, once per sound-system init, whether a real echo is possible and how much room there is.
     *
     * <p>Why this exists: an echo needs an effect that produces a DISCRETE delayed tap. Measured from the
     * OpenAL Soft source, AL_EAXREVERB's "early reflections" cannot: its own comment says the taps
     * "decorrelate the 4-channel signal to approximate an average room response" through a Gerzon all-pass
     * filter that "helps smooth out the reverb tail". Feeding a measured delay into
     * AL_EAXREVERB_REFLECTIONS_DELAY therefore only delays a diffused pattern - which is exactly why it was
     * heard as "part of the reverb" rather than as an echo.
     *
     * <p>AL_EFFECT_ECHO is a genuine delay line. Before building anything on it, this probe establishes the
     * two facts that decide the design, and it only READS state plus writes to a throwaway effect object, so
     * it cannot disturb a running sound system:
     *
     * <ol>
     *   <li>whether the runtime accepts AL_EFFECT_ECHO and its parameters</li>
     *   <li>how many auxiliary sends the device really allows - an echo send has to come from somewhere,
     *       because a source can only carry ALC_MAX_AUXILIARY_SENDS sends in total</li>
     * </ol>
     */
    private static void probeEchoSupport() {
        final int maxSends = AudioUtilities.getMaxAuxSends();
        int echoSlot = 0;
        String verdict;
        try {
            echoSlot = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(echoSlot, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
            final int type = EXTEfx.alGetEffecti(echoSlot, EXTEfx.AL_EFFECT_TYPE);
            if (type != EXTEfx.AL_EFFECT_ECHO) {
                verdict = "REJECTED (type reads back as " + type + ")";
            } else {
                EXTEfx.alEffectf(echoSlot, EXTEfx.AL_ECHO_DELAY, 0.2F);
                EXTEfx.alEffectf(echoSlot, EXTEfx.AL_ECHO_DAMPING, 0.5F);
                EXTEfx.alEffectf(echoSlot, EXTEfx.AL_ECHO_FEEDBACK, 0.0F);
                final float delay = EXTEfx.alGetEffectf(echoSlot, EXTEfx.AL_ECHO_DELAY);
                final int err = AL10.alGetError();
                verdict = err == AL10.AL_NO_ERROR
                        ? String.format("SUPPORTED (delay reads back %.3f s)", delay)
                        : String.format("ERROR 0x%X", err);
            }
        } catch (final Throwable t) {
            verdict = "THREW " + t.getClass().getSimpleName();
        } finally {
            if (echoSlot != 0) {
                try {
                    EXTEfx.alDeleteEffects(echoSlot);
                } catch (final Throwable ignored) {
                    // the context may be gone; nothing to clean up
                }
            }
        }

        // A source can carry at most maxSends sends, and all four are already taken by the reverb zones,
        // so an echo send cannot be ADDED - something has to give. Report the arithmetic plainly.
        LOGGER.info("ECHO_PROBE effect=%s | maxAuxSends=%d activeSends=%d freeSends=%d | %s",
                verdict, maxSends, activeSends, Math.max(0, maxSends - activeSends),
                maxSends > activeSends
                        ? "room for an echo send"
                        : "NO spare send: an echo send must displace a reverb zone");
    }

    /** The effect handle carrying the discrete echo, or 0 when the device refused it. */
    private static int echoEffect = 0;
    /** True once the echo effect has been attached to the last aux slot. */
    private static boolean echoActive = false;

    /**
     * Attaches a genuine delay-line echo to an aux slot, replacing the reverb zone it carried.
     *
     * <p>Every step is verified and every failure path is a plain "no": if the runtime will not give us an
     * AL_EFFECT_ECHO the caller falls back to the reverb zone, so a device that lacks it degrades to the
     * previous behaviour instead of losing the wet path entirely. That matters because a throw here happens
     * inside the effect upload, and the FX evaluation is wrapped in a catch-all that would otherwise swallow
     * it and silently disable occlusion and reverb for every sound - which has already happened once in this
     * project.
     *
     * @return true when the echo is in place and the caller should skip its reverb zone
     */
    private static boolean tryEchoOn(final int index) {
        try {
            final int slot = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(slot, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
            if (EXTEfx.alGetEffecti(slot, EXTEfx.AL_EFFECT_TYPE) != EXTEfx.AL_EFFECT_ECHO) {
                EXTEfx.alDeleteEffects(slot);
                LOGGER.info("ECHO_SLOT send=%d refused by the device; keeping the reverb zone", index);
                return false;
            }
            // One tap, no repeats. Feedback is left at zero on purpose: a train of repeats reads as a
            // metallic flutter rather than as a valley, and a real valley returns one dominant reflection.
            EXTEfx.alEffectf(slot, EXTEfx.AL_ECHO_DELAY, EXTEfx.AL_ECHO_DEFAULT_DELAY);
            EXTEfx.alEffectf(slot, EXTEfx.AL_ECHO_FEEDBACK, 0.0F);
            EXTEfx.alEffectf(slot, EXTEfx.AL_ECHO_DAMPING, 0.5F);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                EXTEfx.alDeleteEffects(slot);
                LOGGER.info("ECHO_SLOT send=%d parameter upload failed; keeping the reverb zone", index);
                return false;
            }
            EXTEfx.alAuxiliaryEffectSloti(AUX_SLOTS[index].getSlot(), EXTEfx.AL_EFFECTSLOT_EFFECT, slot);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                EXTEfx.alDeleteEffects(slot);
                LOGGER.info("ECHO_SLOT send=%d could not attach to the aux slot; keeping the reverb zone", index);
                return false;
            }
            echoEffect = slot;
            echoActive = true;
            return true;
        } catch (final Throwable t) {
            LOGGER.info("ECHO_SLOT send=%d threw %s; keeping the reverb zone", index, t.getClass().getSimpleName());
            return false;
        }
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
    /** Last early-reflection gain pushed to the effect slots. */
    private static float lastReflectionsGain = Float.NaN;

    /**
     * Sets the early reflection of the reverb: WHEN it arrives and HOW LOUD it is. These two together are
     * the whole of the reverb-or-echo behaviour, and they are the EAXREVERB parameters OpenAL provides for
     * exactly this purpose - not a layer on top of the tail.
     *
     * <p><b>Time decides reverb or echo.</b> The gap between the direct sound and the first reflection is
     * what the ear uses:
     *
     * <pre>
     *   gap &lt; ~50 ms  -&gt; the reflection fuses with the direct sound -&gt; heard as REVERB
     *   gap &gt; ~50 ms  -&gt; the reflection separates from it           -&gt; heard as an ECHO
     * </pre>
     *
     * <p>The ~50 ms boundary is the Haas fusion window, a property of the auditory system rather than a
     * preference, so it is a constant and NOT a tuning knob. A cave's walls are 3-18 blocks away, so its
     * first reflection arrives 10-40 ms late and is heard as reverb; a valley's walls are 45-120 blocks
     * away, so its first reflection arrives 130-340 ms late and is heard as an echo. The SAME formula
     * produces both - there is no branch on scene type anywhere.
     *
     * <p><b>Level decides whether there is one at all.</b> Above the fusion threshold the reflection is a
     * discrete event, so it must be a single event: feedback-style repeats are deliberately NOT used,
     * because they read as a metallic flutter rather than as a valley. The tail length is left to
     * {@code decayTime}, which the zone parameters already derive from the measured geometry.
     *
     * <p>Delay and gain are properties of the AUX SLOT, not of a source, so these are global values: every
     * source sharing a zone shares its reflection. Driving them per source is not possible through EFX.
     *
     * @param returnedShare share of rays that come back off a surface facing the source (0..1); a plain
     *                      measures ~0.015 and a valley ~0.35, so this is what keeps a plain silent
     * @param seconds       measured round-trip gap between the direct sound and the first reflection
     */
    public static void setEarlyReflection(final float gain, final float seconds) {
        if (activeSends <= 0)
            return;

        // The caller has already applied the physics - spherical spreading over the reflection's longer path
        // and the material it bounced off - so this is a plain clamp into the parameter's range.
        final float clampedGain = Mth.clamp(gain, 0F, EXTEfx.AL_EAXREVERB_MAX_REFLECTIONS_GAIN);

        final boolean delayMoved = Float.isNaN(lastReflectionsDelay)
                || Math.abs(seconds - lastReflectionsDelay) >= DELAY_UPDATE_EPSILON;
        final boolean gainMoved = Float.isNaN(lastReflectionsGain)
                || Math.abs(clampedGain - lastReflectionsGain) >= GAIN_UPDATE_EPSILON;
        if (!delayMoved && !gainMoved)
            return;

        lastReflectionsDelay = seconds;
        lastReflectionsGain = clampedGain;

        // When a real echo is in place, its delay is set on the echo effect and the reverb zones are left
        // alone - driving AL_EAXREVERB_REFLECTIONS_DELAY as well would be the "two paths for one quantity"
        // mistake this project has already paid for twice.
        if (echoActive && echoEffect != 0) {
            final float echoDelay = Mth.clamp(seconds, EXTEfx.AL_ECHO_MIN_DELAY, EXTEfx.AL_ECHO_MAX_DELAY);
            EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_DELAY, echoDelay);
            // A distant wall returns a duller reflection than a near one, because air absorbs high
            // frequencies over the longer path. Damping rises with the distance travelled.
            EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_DAMPING,
                    Mth.clamp(echoDelay / EXTEfx.AL_ECHO_MAX_DELAY * 0.9F, 0.0F, EXTEfx.AL_ECHO_MAX_DAMPING));
            return;
        }

        for (int i = 0; i < activeSends; i++) {
            REVERB_DATA[i].reflectionsDelay = seconds;
            REVERB_DATA[i].reflectionsGain = clampedGain;
            REVERB_SLOTS[i].apply(REVERB_DATA[i], AUX_SLOTS[i]);
        }
    }

    /**
     * How much the reflection delay must move before the effect slots are re-uploaded.
     *
     * <p>Re-attaching an effect to its slot is a real OpenAL call, and the value is recomputed on every
     * sound evaluation (up to 20/s per source). 5 ms is far below the ~50 ms perceptual boundary and well
     * below the resolution at which a delay change is audible, so it collapses the great majority of
     * updates to nothing while keeping the audible behaviour continuous.
     */
    private static final float DELAY_UPDATE_EPSILON = 0.005F;
    /** Same idea for the gain: 0.02 is a fraction of a decibel, inaudible as a step. */
    private static final float GAIN_UPDATE_EPSILON = 0.02F;

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
        probeEchoSupport();
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

            // The LAST send carries the discrete echo instead of a reverb zone.
            //
            // Why the last one, and why this costs nothing: that zone's send gain is multiplied by
            // bounceRatio^4 (SoundFXUtils.finalizeSendGains), and bounceRatio is a mean reflectivity - so for
            // grass 0.15^4 = 0.0005 and even for stone 0.65^4 = 0.18. Measured over 314 probe rows in 564 log
            // files, that zone's gain was EXACTLY ZERO in every one of them: it has never contributed audio.
            // The formula is byte-identical to the initial Rebirth port in all three repos, so it is not
            // something this work broke - the zone was born inert.
            //
            // A discrete echo needs AL_EFFECT_ECHO: measured from the OpenAL Soft source, AL_EAXREVERB's
            // "early reflections" are decorrelated through a Gerzon all-pass filter and are documented as
            // helping "smooth out the reverb tail", so they can only ever be part of the reverb.
            if (i == activeSends - 1 && CONFIG.enableEarlyReflectionEcho && tryEchoOn(i)) {
                LOGGER.info("ECHO_SLOT send=%d effect=AL_EFFECT_ECHO (zone %d replaced; that zone measured 0.0%% of the tail)",
                        i, i);
                continue;
            }

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
        // The echo effect dies with the context. Deleting it explicitly matters: a sound-system re-init
        // (toggling a config option, a resource reload, a device change) runs this and then creates a new
        // one, so without the delete every re-init would leak an OpenAL effect object.
        if (echoEffect != 0) {
            try {
                EXTEfx.alDeleteEffects(echoEffect);
            } catch (final Throwable ignored) {
                // the context may already be gone; nothing left to release
            }
        }
        echoEffect = 0;
        echoActive = false;
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
