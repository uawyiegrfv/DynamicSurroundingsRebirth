package org.orecruncher.dsurround.processing.aurora;

/**
 * Factory for spawning auroras. In 1.12.2 this chose between the classic
 * immediate-mode renderer and a GLSL shader renderer. 26.1 dropped immediate
 * mode entirely, and a shader version would require a custom RenderPipeline
 * plus shader files that Iris/other shaders would override anyway — so only
 * the classic vertex-band renderer is produced here.
 */
public final class AuroraFactory {

    private AuroraFactory() {
    }

    public static IAurora produce(final long seed) {
        return new AuroraClassic(seed);
    }
}
