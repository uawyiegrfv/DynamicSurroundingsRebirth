package org.orecruncher.dsurround.processing.aurora;

/**
 * Factory for spawning auroras. Produces the shader renderer when its pipeline is available; falls
 * back to the classic vertex-band renderer if the shader variant fails to initialize.
 *
 * <p>What the fallback actually covers: {@link AuroraShader}'s constructor now throws when the render
 * pipeline was never registered, which is the case this fallback was written for. Before that check
 * existed the constructor could not fail, so the fallback was unreachable and a missing pipeline
 * surfaced later as an NPE inside the render callback instead.
 *
 * <p>It does NOT cover a GLSL compile error or a missing {@code core/aurora_64.json}. Those surface
 * earlier as a RuntimeException out of {@code RegisterShadersEvent}, and
 * {@code GameRenderer.reloadShaders} only catches IOException - so a resource pack that breaks the
 * shader source gives a startup crash carrying the GLSL info log rather than a silent downgrade.
 * That is the better outcome for a diagnosable failure, but it is not what the old wording implied.
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
