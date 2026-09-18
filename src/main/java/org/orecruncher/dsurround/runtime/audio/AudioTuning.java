package org.orecruncher.dsurround.runtime.audio;

import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.di.ContainerManager;

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

    private static volatile float diffractionHfCeiling =
            clamp((float) CONFIG.diffractionHfCeiling, 0.05F, 1F);
    private static volatile int diffractionRings = clamp(CONFIG.diffractionRings, 1, 6);
    private static volatile int occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
    private static volatile int occlusionFanRings =
            Math.max(1, (Math.max(1, CONFIG.occlusionFanRays) - 1) / 4);
    private static volatile boolean evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;

    private AudioTuning() {
    }

    /**
     * Ceiling on the high-frequency gain that diffraction may restore. 1.0 = no ceiling (the
     * original behaviour, which let a wall bring the highs back almost completely); 0.25 caps the
     * restored highs near -12 dB, so a source behind a wall stays audible but clearly muffled.
     */
    public static float diffractionHfCeiling() {
        return diffractionHfCeiling;
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

    /** Total rays the occlusion fan traces, for diagnostics. */
    public static int occlusionFanRays() {
        return 1 + 4 * occlusionFanRings;
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Applies a named override. Returns a human-readable description of what changed, or throws
     * {@link IllegalArgumentException} naming the valid keys.
     */
    public static String set(final String key, final String value) {
        switch (key) {
            case "diffractionHfCeiling": {
                final float v = clamp(parseFloat(key, value), 0.05F, 1F);
                final float old = diffractionHfCeiling;
                diffractionHfCeiling = v;
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
            default:
                throw new IllegalArgumentException("Unknown key '" + key + "'. Try: "
                        + "diffractionHfCeiling, diffractionRings, occlusionSegments, "
                        + "occlusionFanRays, evaluateOnSoundThread");
        }
    }

    /** Restores every value from the config file. */
    public static String reset() {
        diffractionHfCeiling = clamp((float) CONFIG.diffractionHfCeiling, 0.05F, 1F);
        diffractionRings = clamp(CONFIG.diffractionRings, 1, 6);
        occlusionSegments = Math.max(1, CONFIG.occlusionSegments);
        occlusionFanRings = Math.max(1, (Math.max(1, CONFIG.occlusionFanRays) - 1) / 4);
        evaluateOnSoundThread = CONFIG.evaluateOnSoundThread;
        return "Restored from config:\n" + describeAll();
    }

    /** One line per tunable, with the config key that makes a change permanent. */
    public static String describeAll() {
        return String.format(
                "  diffractionHfCeiling   = %.2f   (config: enhancedSounds.diffractionHfCeiling, 0.05-1.0; 1.0 = no ceiling = original)%n"
                        + "  diffractionRings       = %d   (config: enhancedSounds.diffractionRings, 1-6; %d probes per measurement)%n"
                        + "  occlusionSegments      = %d   (config: enhancedSounds.occlusionSegments, 1-32)%n"
                        + "  occlusionFanRays       = %d   (config: enhancedSounds.occlusionFanRays; rounded to 1 + 4n)%n"
                        + "  evaluateOnSoundThread  = %b   (config: enhancedSounds.evaluateOnSoundThread)%n"
                        + "Session overrides only - nothing is written to disk. '/dstune reset' restores the config values.",
                diffractionHfCeiling, diffractionRings, diffractionRings * 8,
                occlusionSegments, occlusionFanRays(), evaluateOnSoundThread);
    }

    // ------------------------------------------------------------------ helpers

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
