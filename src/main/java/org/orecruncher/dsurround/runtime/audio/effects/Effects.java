package org.orecruncher.dsurround.runtime.audio.effects;

import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.orecruncher.dsurround.runtime.audio.SourceContext;

import java.util.Arrays;

public final class Effects {
    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    // General config settings that need to make their way somewhere
    // 26.1: the mixin now requests 4 auxiliary sends at context creation (matching 1.21.1),
    // so all four reverb zones apply. Kept at the original 1.12.2 baseline: raising it to
    // 1.0 made cave/room reverb noticeably too strong.
    private static final float GLOBAL_REVERB_MULTIPLIER = 0.7F;

    public static final float GLOBAL_BLOCK_ABSORPTION = 1F;
    public static final float SNOW_AIR_ABSORPTION_FACTOR = 5F;
    public static final float RAIN_AIR_ABSORPTION_FACTOR = 2F;

    public static final ReverbData reverbData0;
    public static final ReverbData reverbData1;
    public static final ReverbData reverbData2;
    public static final ReverbData reverbData3;
    public static final AuxSlot auxSlot0 = new AuxSlot();
    public static final AuxSlot auxSlot1 = new AuxSlot();
    public static final AuxSlot auxSlot2 = new AuxSlot();
    public static final AuxSlot auxSlot3 = new AuxSlot();
    public static final ReverbEffectSlot reverb0 = new ReverbEffectSlot();
    public static final ReverbEffectSlot reverb1 = new ReverbEffectSlot();
    public static final ReverbEffectSlot reverb2 = new ReverbEffectSlot();
    public static final ReverbEffectSlot reverb3 = new ReverbEffectSlot();
    public static final LowPassFilterSlot filter0 = new LowPassFilterSlot();
    public static final LowPassFilterSlot filter1 = new LowPassFilterSlot();
    public static final LowPassFilterSlot filter2 = new LowPassFilterSlot();
    public static final LowPassFilterSlot filter3 = new LowPassFilterSlot();
    public static final LowPassFilterSlot direct = new LowPassFilterSlot();

    // 26.1: expose the zones/slots by index so the reverb can be mapped onto however many
    // auxiliary sends the device actually supports. Most OpenAL devices expose only 2 sends,
    // while this system computes four reverb zones (small room -> cavern).
    private static final AuxSlot[] AUX_SLOTS = { auxSlot0, auxSlot1, auxSlot2, auxSlot3 };
    private static final ReverbEffectSlot[] REVERB_SLOTS = { reverb0, reverb1, reverb2, reverb3 };
    private static final LowPassFilterSlot[] FILTERS = { filter0, filter1, filter2, filter3 };
    private static final ReverbData[] REVERB_DATA = new ReverbData[4];
    // Which reverb zone (0..3) is currently bound to each initialized send; -1 = none.
    private static final int[] assignedZone = { -1, -1, -1, -1 };
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
        activeSends = Math.min(4, AudioUtilities.getMaxAuxSends());
        if (activeSends <= 0)
            return;

        for (int i = 0; i < activeSends; i++) {
            AUX_SLOTS[i].initialize();
            REVERB_SLOTS[i].initialize();
            FILTERS[i].initialize();
            REVERB_DATA[i].setProcess(true);
            REVERB_SLOTS[i].apply(REVERB_DATA[i], AUX_SLOTS[i]);
            assignedZone[i] = i;
        }

        direct.initialize();

        Arrays.fill(assignedZone, activeSends, assignedZone.length, -1);
    }

    public static void deinitialize() {
        for (int i = 0; i < activeSends; i++) {
            AUX_SLOTS[i].deinitialize();
            REVERB_SLOTS[i].deinitialize();
            FILTERS[i].deinitialize();
            REVERB_DATA[i].setProcess(false);
            assignedZone[i] = -1;
        }

        direct.deinitialize();
        activeSends = 0;
    }

    /**
     * Applies the reverb data for a sound source. The four computed reverb zones (small room
     * through cavern) are ranked by reflection energy and assigned to the device's available
     * auxiliary sends, so a device exposing only 2 sends still receives the dominant reverb
     * components (e.g. the cavern reverb for caves) without AL_INVALID_VALUE errors.
     */
    public static void applyReverb(final SourceContext source) {
        if (activeSends <= 0 || !source.isEnabled())
            return;

        final int sourceId = source.getId();

        // Rank the zones by low-pass gain (most energetic reflection first)
        final Integer[] zones = { 0, 1, 2, 3 };
        Arrays.sort(zones, (a, b) -> Float.compare(source.getLowPass(b).gain, source.getLowPass(a).gain));

        for (int send = 0; send < activeSends; send++) {
            final int zone = zones[send];
            // Only re-upload the fixed reverb effect when the zone changes
            if (assignedZone[send] != zone) {
                REVERB_SLOTS[send].apply(REVERB_DATA[zone], AUX_SLOTS[send]);
                assignedZone[send] = zone;
            }
            FILTERS[send].apply(sourceId, source.getLowPass(zone), send, AUX_SLOTS[send]);
        }

        // Diagnostic: logged at debug level (enableDebugLogging) so the reverb zone mapping
        // can be inspected. Throttled to once per ~7 seconds per source.
        if (++applyCounter % 140 == 0) {
            var sound = source.getSound();
            var soundId = sound == null ? "?" : sound.getIdentifier().toString();
            LOGGER.debug("REVERB src=%d sound=%s sends=%d zones=[%d,%d] gains=[%.3f,%.3f,%.3f,%.3f] cutoffs=[%.3f,%.3f,%.3f,%.3f] direct=[%.3f,%.3f]",
                    sourceId, soundId, activeSends, zones[0], zones[1],
                    source.getLowPass(0).gain, source.getLowPass(1).gain, source.getLowPass(2).gain, source.getLowPass(3).gain,
                    source.getLowPass(0).gainHF, source.getLowPass(1).gainHF, source.getLowPass(2).gainHF, source.getLowPass(3).gainHF,
                    source.getDirect().gain, source.getDirect().gainHF);
        }

        // Occlusion / direct path filter and air absorption are independent of the aux sends
        direct.apply(sourceId, source.getDirect());
        source.getAirAbsorb().apply(sourceId);
    }
}
