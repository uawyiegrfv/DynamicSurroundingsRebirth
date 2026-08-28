package org.orecruncher.dsurround.processing.fog;

/**
 * Lightweight fog-range holder for the 1.20.1 port.
 *
 * NeoForge 26.1 used the 1.21 net.minecraft.client.renderer.fog.FogData type, whose
 * renderDistanceStart/renderDistanceEnd fields map to the terrain fog's near/far
 * planes. 1.20.1's FogRenderer$FogData is package-private, so the fog calculators
 * carry their ranges in this small mutable holder instead.
 *
 * renderDistanceStart == near plane (FogRenderer$FogData.start)
 * renderDistanceEnd   == far plane  (FogRenderer$FogData.end)
 */
public class FogData {
    public float renderDistanceStart;
    public float renderDistanceEnd;

    public FogData() {
        this(0F, 0F);
    }

    public FogData(final float start, final float end) {
        this.renderDistanceStart = start;
        this.renderDistanceEnd = end;
    }
}
