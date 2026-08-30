package org.orecruncher.dsurround.processing.aurora;

/**
 * Factory for spawning auroras. Produces the shader renderer when its
 * pipeline is available; falls back to the classic vertex-band renderer if
 * the shader variant fails to initialize (e.g. pipeline registration did not
 * happen for this session).
 */
public final class AuroraFactory {

    private AuroraFactory() {
    }

    public static IAurora produce(final long seed) {
        try {
            return new AuroraShader(seed);
        } catch (final Throwable ignored) {
            return new AuroraClassic(seed);
        }
    }
}
