package org.orecruncher.dsurround.runtime.audio;

import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;

/**
 * Live, mutable overrides for the audio tuning values that are worth A/B testing in place.
 *
 * <p>Why this exists rather than reading the config directly: the acoustic code runs on a worker
 * and a thread pool while the {@code /dstune} command runs on the client thread. Writing a plain
 * {@code double} field on the config object from one thread and reading it from another is a data
 * race, and a torn read of a {@code double} would produce a nonsense filter gain for one
 * evaluation - audible, and exactly the kind of defect this project keeps hunting. Every field
 * here is {@code volatile}, so a write from the command is atomic and immediately visible.
 *
 * <p>These are <b>session overrides</b>: nothing is written back to disk. The config file stays the
 * persistent source of truth, which is deliberate - a tuning value tried once should not silently
 * become permanent. {@code /dstune} with no arguments lists everything, including the config key to
 * edit for a permanent change.
 */
public final class AudioTuning {

    /** As configured, used to restore a value after an experiment. */
    private static final Configuration.EnhancedSounds CONFIG =
            ContainerManager.resolve(Configuration.EnhancedSounds.class);

    private static volatile float diffractionLevelStrength =
            clamp((float) CONFIG.diffractionLevelStrength, 0F, 1F);
    private static volatile float diffractionHfStrength =
            clamp((float) CONFIG.diffractionHfStrength, 0F, 1F);
    private static volatile int diffractionRings = clamp(CONFIG.diffractionRings, 1, 6);
    private static volatile int occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
    private static volatile int occlusionFanRings =
            Math.max(1, (Math.max(1, CONFIG.occlusionFanRays) - 1) / 4);
    private static volatile boolean evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;
    private static volatile float apertureStrength = clamp((float) CONFIG.apertureStrength, 0F, 1F);
    private static volatile float apertureRadius = clamp((float) CONFIG.apertureRadius, 0.5F, 8F);
    private static volatile boolean realismWavelength = CONFIG.realismWavelength;
    private static volatile float realismFrequencyHz = clamp((float) CONFIG.realismFrequencyHz, 100F, 4000F);
    private static volatile int aperturePlanes = clamp(CONFIG.aperturePlanes, 1, 4);
    private static volatile float occlusionFocusDistance = clamp((float) CONFIG.occlusionFocusDistance, 0F, 64F);
    private static volatile float occlusionConeDegrees = clamp((float) CONFIG.occlusionConeDegrees, 0F, 45F);
    private static volatile boolean occlusionFresnelZone = CONFIG.occlusionFresnelZone;
    private static volatile float occlusionLossDb = clamp((float) CONFIG.occlusionLossDb, 0F, 60F);
    private static volatile boolean logAudioTrace = CONFIG.logAudioTrace;
    private static final float[] DEFAULT_BAND_WEIGHTS = {0.50F, 0.35F, 0.15F};
    private static final float[] DEFAULT_BAND_FREQUENCIES = {125F, 500F, 2000F};
    private static volatile float[] bandWeights = parseFloats(CONFIG.bandWeights, DEFAULT_BAND_WEIGHTS);
    private static volatile float[] bandFrequencies = parseFloats(CONFIG.bandFrequencies, DEFAULT_BAND_FREQUENCIES);

    /**
     * The most recent evaluation's numbers, for {@code /dstune}. Diagnostics only: two rounds of
     * acoustic changes produced no audible difference, so before changing anything else we need to
     * see whether the path runs at all and what it computes.
     */
    private static volatile String lastTrace = "(no evaluation recorded yet)";

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    /** Throttle state for the log copy of the probe (diagnostics only). */
    private static volatile String lastLoggedTrace = "";
    private static volatile long lastLoggedAt = 0L;
    private static final long TRACE_LOG_INTERVAL_MS = 250L;

    private AudioTuning() {
    }

    /**
     * Fraction of the LOST level an edge detour may bring back. The original code took
     * {@code max(detour, occlusion)}, which erased the wall as soon as any edge existed nearby.
     */
    public static float diffractionLevelStrength() {
        return diffractionLevelStrength;
    }

    /**
     * Fraction of the LOST high frequencies an edge detour may bring back. Much lower than the level
     * strength, because edge diffraction attenuates short wavelengths first.
     */
    public static float diffractionHfStrength() {
        return diffractionHfStrength;
    }

    /** Number of detour rings the diffraction probe sums over (8 rays each). */
    public static int diffractionRings() {
        return diffractionRings;
    }

    /** Segments a single occlusion ray is split into. */
    public static int occlusionSegments() {
        return occlusionSegments;
    }

    /** Rings of rays inside the occlusion cone (the fan is one centre ray plus four per ring). */
    public static int occlusionFanRings() {
        return occlusionFanRings;
    }

    /** Whether a starting sound is evaluated inline on the sound thread or deferred to the worker. */
    public static boolean evaluateOnSoundThread() {
        return evaluateOnSoundThread;
    }

    /**
     * Fraction of the LOST level the aperture may carry around an obstacle. The aperture is the disc
     * of secondary sources on the wavefront between the two ends - the practical (incoherent) form
     * of Huygens-Fresnel: energy that reaches the listener through a clear part of that disc has gone
     * around the obstacle, so a lone pillar must not muffle the sound like a solid wall does.
     */
    public static float apertureStrength() {
        return apertureStrength;
    }

    /** Radius of the aperture disc in blocks. */
    public static float apertureRadius() {
        return apertureRadius;
    }

    /**
     * Whether the real wavelength of sound is used. With this off every frequency attenuates alike,
     * which is the one error in the model that is qualitative rather than a matter of precision: in
     * reality a low frequency bends around an obstacle that stops a high one outright.
     */
    public static boolean realismWavelength() {
        return realismWavelength;
    }

    /** Representative frequency in Hz (the engine gives one filter per source, so one band). */
    public static float realismFrequencyHz() {
        return realismFrequencyHz;
    }

    /** Number of aperture planes sampled along the source-to-listener line. */
    public static int aperturePlanes() {
        return aperturePlanes;
    }

    /**
     * Distance over which block material counts fully for occlusion. Beyond it the contribution is
     * divided by (1 + d/focus). 0 = every block counts fully.
     */
    public static float occlusionFocusDistance() {
        return occlusionFocusDistance;
    }

    /**
     * Half-angle in degrees of the cone the occlusion rays sample. An angle, so it behaves the same at
     * any distance; 0 puts every ray on the axis.
     */
    public static float occlusionConeDegrees() {
        return occlusionConeDegrees;
    }

    /** Whether the occlusion comes from the Fresnel-zone clear fraction rather than a material sum. */
    public static boolean occlusionFresnelZone() {
        return occlusionFresnelZone;
    }

    /** Excess attenuation in dB when an aperture plane is completely covered. */
    public static float occlusionLossDb() {
        return occlusionLossDb;
    }

    /** Whether the evaluation trace is written to the log. Off by default. */
    public static boolean logAudioTrace() {
        return logAudioTrace;
    }

    /** Relative weight of each octave band, lowest first. */
    public static float[] bandWeights() {
        return bandWeights;
    }

    /** Octave bands in Hz, lowest first. */
    public static float[] bandFrequencies() {
        return bandFrequencies;
    }

    /** Total rays the occlusion fan traces, for diagnostics. */
    public static int occlusionFanRays() {
        return 1 + 4 * occlusionFanRings;
    }

    /**
     * Called from the audio thread at the end of every evaluation. Stores the line for {@code
     * /dstune} and writes it to the LOG whenever it changes, because chat text cannot be copied out.
     * Rate limited and change-gated: 32 sources evaluating twice a second would otherwise flood the
     * log, while a transition (walking behind a wall) still produces a readable handful of lines.
     */
    public static void recordTrace(final String trace) {
        // The last trace is always kept, so '/dstune' can show it on demand without the log being
        // written to for the whole session.
        lastTrace = trace;
        if (!logAudioTrace)
            return;
        final long now = System.currentTimeMillis();
        if (trace.equals(lastLoggedTrace) || now - lastLoggedAt < TRACE_LOG_INTERVAL_MS)
            return;
        lastLoggedTrace = trace;
        lastLoggedAt = now;
        LOGGER.info("[dstune] %s", trace);
    }

    /** The most recent evaluation's numbers. */
    public static String lastTrace() {
        return lastTrace;
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Applies a named override. Returns a human-readable description of what changed, or throws
     * {@link IllegalArgumentException} naming the valid keys.
     */
    public static String set(final String key, final String value) {
        switch (key) {
            case "diffractionLevelStrength": {
                final float v = clamp(parseFloat(key, value), 0F, 1F);
                final float old = diffractionLevelStrength;
                diffractionLevelStrength = v;
                return describe(key, old, v);
            }
            case "diffractionHfStrength": {
                final float v = clamp(parseFloat(key, value), 0F, 1F);
                final float old = diffractionHfStrength;
                diffractionHfStrength = v;
                return describe(key, old, v);
            }
            case "diffractionRings": {
                final int v = clamp(parseInt(key, value), 1, 6);
                final int old = diffractionRings;
                diffractionRings = v;
                return describe(key, old, v) + " (" + (v * 8) + " detour probes per measurement)";
            }
            case "occlusionSegments": {
                final int v = clamp(parseInt(key, value), 1, 32);
                final int old = occlusionSegments;
                occlusionSegments = v;
                return describe(key, old, v);
            }
            case "occlusionFanRays": {
                final int requested = clamp(parseInt(key, value), 1, 25);
                final int rings = Math.max(1, (requested - 1) / 4);
                final int old = occlusionFanRays();
                occlusionFanRings = rings;
                return describe(key, old, occlusionFanRays())
                        + " (rounded to 1 + 4n; use 5, 9, 13, 17, 21 or 25)";
            }
            case "evaluateOnSoundThread": {
                final boolean v = Boolean.parseBoolean(value);
                final boolean old = evaluateOnSoundThread;
                evaluateOnSoundThread = v;
                return describe(key, old, v);
            }
            case "apertureStrength": {
                final float v = clamp(parseFloat(key, value), 0F, 1F);
                final float old = apertureStrength;
                apertureStrength = v;
                return describe(key, old, v);
            }
            case "apertureRadius": {
                final float v = clamp(parseFloat(key, value), 0.5F, 8F);
                final float old = apertureRadius;
                apertureRadius = v;
                return describe(key, old, v);
            }
            case "realismWavelength": {
                final boolean v = Boolean.parseBoolean(value);
                final boolean old = realismWavelength;
                realismWavelength = v;
                return describe(key, old, v);
            }
            case "realismFrequencyHz": {
                final float v = clamp(parseFloat(key, value), 100F, 4000F);
                final float old = realismFrequencyHz;
                realismFrequencyHz = v;
                return describe(key, old, v);
            }
            case "aperturePlanes": {
                final int v = clamp(parseInt(key, value), 1, 4);
                final int old = aperturePlanes;
                aperturePlanes = v;
                return describe(key, old, v);
            }
            case "occlusionFocusDistance": {
                final float v = clamp(parseFloat(key, value), 0F, 64F);
                final float old = occlusionFocusDistance;
                occlusionFocusDistance = v;
                return describe(key, old, v);
            }
            case "occlusionConeDegrees": {
                final float v = clamp(parseFloat(key, value), 0F, 45F);
                final float old = occlusionConeDegrees;
                occlusionConeDegrees = v;
                return describe(key, old, v);
            }
            case "occlusionFresnelZone": {
                final boolean v = Boolean.parseBoolean(value);
                final boolean old = occlusionFresnelZone;
                occlusionFresnelZone = v;
                return describe(key, old, v);
            }
            case "occlusionLossDb": {
                final float v = clamp(parseFloat(key, value), 0F, 60F);
                final float old = occlusionLossDb;
                occlusionLossDb = v;
                return describe(key, old, v);
            }
            case "probe": {
                final boolean v = Boolean.parseBoolean(value);
                final boolean old = logAudioTrace;
                logAudioTrace = v;
                return describe(key, old, v) + " (the last trace is always kept for '/dstune')";
            }
            case "bandWeights": {
                final float[] v = parseFloats(value, null);
                if (v == null || v.length == 0)
                    throw new IllegalArgumentException("bandWeights expects comma separated numbers, e.g. 0.50,0.35,0.15");
                final String old = describeArray(bandWeights);
                bandWeights = v;
                return describe(key, old, describeArray(v));
            }
            case "bandFrequencies": {
                final float[] v = parseFloats(value, null);
                if (v == null || v.length == 0)
                    throw new IllegalArgumentException("bandFrequencies expects comma separated Hz, e.g. 125,500,2000");
                final String old = describeArray(bandFrequencies);
                bandFrequencies = v;
                return describe(key, old, describeArray(v));
            }
            default:
                throw new IllegalArgumentException("Unknown key '" + key + "'. Try: "
                        + "diffractionLevelStrength, diffractionHfStrength, diffractionRings, "
                        + "occlusionSegments, occlusionFanRays, evaluateOnSoundThread, "
                        + "apertureStrength, apertureRadius, realismWavelength, realismFrequencyHz, "
                        + "aperturePlanes, occlusionFocusDistance, occlusionConeDegrees, "
                        + "occlusionFresnelZone, occlusionLossDb, probe, bandWeights, bandFrequencies");
        }
    }

    /** Restores every value from the config file. */
    public static String reset() {
        diffractionLevelStrength = clamp((float) CONFIG.diffractionLevelStrength, 0F, 1F);
        diffractionHfStrength = clamp((float) CONFIG.diffractionHfStrength, 0F, 1F);
        diffractionRings = clamp(CONFIG.diffractionRings, 1, 6);
        occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
        occlusionFanRings = Math.max(1, (Math.max(1, CONFIG.occlusionFanRays) - 1) / 4);
        evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;
        apertureStrength = clamp((float) CONFIG.apertureStrength, 0F, 1F);
        apertureRadius = clamp((float) CONFIG.apertureRadius, 0.5F, 8F);
        realismWavelength = CONFIG.realismWavelength;
        realismFrequencyHz = clamp((float) CONFIG.realismFrequencyHz, 100F, 4000F);
        aperturePlanes = clamp(CONFIG.aperturePlanes, 1, 4);
        occlusionFocusDistance = clamp((float) CONFIG.occlusionFocusDistance, 0F, 64F);
        occlusionConeDegrees = clamp((float) CONFIG.occlusionConeDegrees, 0F, 45F);
        occlusionFresnelZone = CONFIG.occlusionFresnelZone;
        occlusionLossDb = clamp((float) CONFIG.occlusionLossDb, 0F, 60F);
        logAudioTrace = CONFIG.logAudioTrace;
        bandWeights = parseFloats(CONFIG.bandWeights, DEFAULT_BAND_WEIGHTS);
        bandFrequencies = parseFloats(CONFIG.bandFrequencies, DEFAULT_BAND_FREQUENCIES);
        return "Restored from config:\n" + describeAll();
    }

    /** One line per tunable, with the config key that makes a change permanent. */
    public static String describeAll() {
        return String.format(
                "  diffractionLevelStrength = %.2f   (config: enhancedSounds.diffractionLevelStrength, 0-1; fraction of the LOST level a detour may restore)%n"
                        + "  diffractionHfStrength    = %.2f   (config: enhancedSounds.diffractionHfStrength, 0-1; same for the LOST highs - keep this well below the level)%n"
                        + "  diffractionRings         = %d   (config: enhancedSounds.diffractionRings, 1-6; %d probes per measurement)%n"
                        + "  occlusionSegments        = %d   (config: enhancedSounds.occlusionSegments, 1-32)%n"
                        + "  occlusionFanRays         = %d   (config: enhancedSounds.occlusionFanRays; rounded to 1 + 4n)%n"
                        + "  evaluateOnSoundThread    = %b   (config: enhancedSounds.evaluateOnSoundThread)%n"
                        + "  apertureStrength         = %.2f   (config: enhancedSounds.apertureStrength, 0-1; around-the-corner energy through the aperture disc)%n"
                        + "  apertureRadius           = %.1f   (config: enhancedSounds.apertureRadius, 0.5-8 blocks)%n"
                        + "  realismWavelength        = %b   (config: enhancedSounds.realismWavelength; wavelength-dependent diffraction + Fresnel-sized aperture)%n"
                        + "  realismFrequencyHz       = %.0f   (config: enhancedSounds.realismFrequencyHz, 100-4000)%n"
                        + "  aperturePlanes           = %d   (config: enhancedSounds.aperturePlanes, 1-4)%n"
                        + "  occlusionFocusDistance   = %.1f   (config: enhancedSounds.occlusionFocusDistance, 0-64 blocks; 0 = every block counts fully)%n"
                        + "  occlusionConeDegrees     = %.1f   (config: enhancedSounds.occlusionConeDegrees, 0-45; half-angle of the sampled cone)%n"
                        + "  occlusionFresnelZone     = %b   (config: enhancedSounds.occlusionFresnelZone; zone clear fraction instead of a material sum)%n"
                        + "  occlusionLossDb          = %.1f   (config: enhancedSounds.occlusionLossDb, 0-60; excess attenuation for a fully covered plane)%n"
                        + "  probe                    = %b   (config: enhancedSounds.logAudioTrace; write the evaluation trace to the log)%n"
                        + "  bandWeights              = %s   (config: enhancedSounds.bandWeights; relative weight per octave band, lowest first)%n"
                        + "  bandFrequencies          = %s   (config: enhancedSounds.bandFrequencies; octave bands in Hz, lowest first)%n"
                        + "Session overrides only - nothing is written to disk. '/dstune reset' restores the config values.%n"
                        + "Last evaluation: %s",
                diffractionLevelStrength, diffractionHfStrength, diffractionRings, diffractionRings * 8,
                occlusionSegments, occlusionFanRays(), evaluateOnSoundThread,
                apertureStrength, apertureRadius,
                realismWavelength, realismFrequencyHz, aperturePlanes, occlusionFocusDistance,
                occlusionConeDegrees, occlusionFresnelZone, occlusionLossDb, logAudioTrace,
                describeArray(bandWeights), describeArray(bandFrequencies), lastTrace);
    }

    // ------------------------------------------------------------------ helpers

    /** Parses a comma separated list of numbers, or returns {@code fallback} when the text is unusable. */
    private static float[] parseFloats(final String text, final float[] fallback) {
        if (text == null)
            return fallback;
        final String[] parts = text.split(",");
        final float[] out = new float[parts.length];
        int n = 0;
        for (final String part : parts) {
            try {
                out[n++] = Float.parseFloat(part.trim());
            } catch (NumberFormatException ignored) {
                // drop the unusable entry
            }
        }
        if (n == 0)
            return fallback;
        final float[] trimmed = new float[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Renders an array for the chat output. */
    private static String describeArray(final float[] values) {
        if (values == null || values.length == 0)
            return "(none)";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(values[i] == Math.rint(values[i])
                    ? String.valueOf((long) values[i])
                    : String.format(java.util.Locale.ROOT, "%.2f", values[i]));
        }
        return sb.toString();
    }

    private static float parseFloat(final String key, final String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects a number, got '" + value + "'");
        }
    }

    private static int parseInt(final String key, final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects a whole number, got '" + value + "'");
        }
    }

    private static float clamp(final float v, final float lo, final float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String describe(final String key, final Object old, final Object now) {
        return String.format("%s: %s -> %s  (session only; edit the config to persist)", key, old, now);
    }
}
