package org.orecruncher.dsurround.runtime.audio.effects;

import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.orecruncher.dsurround.runtime.audio.SourceContext;

public final class Effects {
    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    // General config settings that need to make their way somewhere
    // 26.1: the mixin now requests 4 auxiliary sends at context creation (matching 1.21.1),
    // so all four reverb zones apply. Slightly below the original 1.12.2 baseline per
    // user tuning: cave/room reverb was still a touch too strong at 0.7.
    // 1.20.1 forensics: reverted to the exact 26.1 values. Brute-force boosting made
    // reverb loud EVERYWHERE, which proves the wet path is wired correctly and
    // localises the defect to the near-zero cavern send gains, not effect tuning.
    private static final float GLOBAL_REVERB_MULTIPLIER = 0.6F;

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

    public static void initialize() {
        // Force-regenerate every EFX object. On sound-system reinit (toggling reverb/
        // occlusion in config, resource reload, device change) the old OpenAL handles
        // point into a destroyed context, so reset them before creating new ones.
        for (final AuxSlot s : AUX_SLOTS)
            s.deinitialize();
        for (final ReverbEffectSlot s : REVERB_SLOTS)
            s.deinitialize();

        activeSends = Math.min(4, AudioUtilities.getMaxAuxSends());
        // DIAG(1.20.1): reverb slots
        org.orecruncher.dsurround.lib.Library.LOGGER.debug("REVERB_INIT activeSends=%d", getActiveSends());
        if (activeSends <= 0)
            return;

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

        final int sourceId = source.getId();

        for (int zone = 0; zone < activeSends; zone++) {
            source.zoneFilter(zone).apply(sourceId, source.getLowPass(zone), zone, AUX_SLOTS[zone]);
        }

        // Diagnostic: steady-state zone mapping, one line per ~second of playtime.
        // DIAG(1.20.1-reverb): temporarily INFO (was debug) - the first-20 probe burns out
        // during world load on minecart spam, so this is the only continuous view of what
        // applyReverb actually sends.
        if (++applyCounter % 140 == 0) {
            var sound = source.getSound();
            var soundId = sound == null ? "?" : sound.getLocation().toString();
            LOGGER.info("REVERB_STEADY src=%d sound=%s pos=%.1f,%.1f,%.1f sends=%d process=[%s,%s,%s,%s] gains=[%.3f,%.3f,%.3f,%.3f] cutoffs=[%.3f,%.3f,%.3f,%.3f] direct=[%.3f,%.3f/%s] filters=[%d,%d,%d,%d]/%d",
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
