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

    private static volatile int occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
    private static volatile boolean evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;
    private static volatile boolean realismWavelength = CONFIG.realismWavelength;
    private static volatile float realismFrequencyHz = clamp((float) CONFIG.realismFrequencyHz, 100F, 4000F);
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

    /** Segments a single occlusion ray is split into. */
    public static int occlusionSegments() {
        return occlusionSegments;
    }

    /** Whether a starting sound is evaluated inline on the sound thread or deferred to the worker. */
    public static boolean evaluateOnSoundThread() {
        return evaluateOnSoundThread;
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


    /**
     * Whether the caller should build a trace string at all.
     *
     * <p>The trace is built by the CALLER, as the argument to {@link #recordTrace}, so its 33-argument
     * {@code String.format} ran on every sound evaluation even with the probe switched off - and off is the
     * default. At about two evaluations per second per source and dozens of concurrent sources, that is
     * several hundred string formats per second spent on a string nobody reads. Call sites must gate the
     * formatting on this.
     */
    public static boolean shouldTrace() {
        return logAudioTrace;
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
            case "occlusionSegments": {
                final int v = clamp(parseInt(key, value), 1, 32);
                final int old = occlusionSegments;
                occlusionSegments = v;
                return describe(key, old, v);
            }
            case "evaluateOnSoundThread": {
                final boolean v = Boolean.parseBoolean(value);
                final boolean old = evaluateOnSoundThread;
                evaluateOnSoundThread = v;
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
                        + "occlusionSegments, evaluateOnSoundThread, "
                        + "realismWavelength, realismFrequencyHz, "
                        + "probe, bandWeights, bandFrequencies");
        }
    }

    /** Restores every value from the config file. */
    public static String reset() {
        occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
        evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;
        realismWavelength = CONFIG.realismWavelength;
        realismFrequencyHz = clamp((float) CONFIG.realismFrequencyHz, 100F, 4000F);
        logAudioTrace = CONFIG.logAudioTrace;
        bandWeights = parseFloats(CONFIG.bandWeights, DEFAULT_BAND_WEIGHTS);
        bandFrequencies = parseFloats(CONFIG.bandFrequencies, DEFAULT_BAND_FREQUENCIES);
        return "Restored from config:\n" + describeAll();
    }

    /** One line per tunable, with the config key that makes a change permanent. */
    public static String describeAll() {
        return String.format(
                "  occlusionSegments        = %d   (config: enhancedSounds.occlusionSegments, 1-32)%n"
                        + "  evaluateOnSoundThread    = %b   (config: enhancedSounds.evaluateOnSoundThread)%n"
                        + "  realismWavelength        = %b   (config: enhancedSounds.realismWavelength; wavelength-dependent diffraction)%n"
                        + "  realismFrequencyHz       = %.0f   (config: enhancedSounds.realismFrequencyHz, 100-4000)%n"
                        + "  probe                    = %b   (config: enhancedSounds.logAudioTrace; write the evaluation trace to the log)%n"
                        + "  bandWeights              = %s   (config: enhancedSounds.bandWeights; relative weight per octave band, lowest first)%n"
                        + "  bandFrequencies          = %s   (config: enhancedSounds.bandFrequencies; octave bands in Hz, lowest first)%n"
                        + "Session overrides only - nothing is written to disk. '/dstune reset' restores the config values.%n"
                        + "Last evaluation: %s",
                occlusionSegments, evaluateOnSoundThread,
                realismWavelength, realismFrequencyHz, logAudioTrace,
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
