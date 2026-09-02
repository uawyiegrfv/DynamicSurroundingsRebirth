package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IDimensionInformation;
import org.orecruncher.dsurround.lib.GameUtils;

/**
 * Adds haze when the player is near the cloud layer, ported from the original
 * 1.12.2 HazeFogRangeCalculator. A band around the dimension's cloud height
 * (15 below to 25 above, with a 10-tall core) scales the fog distance down.
 */
public class HazeFogRangeCalculator extends VanillaFogRangeCalculator {


    private static final int BAND_OFFSETS = 15;
    private static final int BAND_CORE_SIZE = 10;
    // Cap the fog end like the morning fog so the band is clearly visible.
    private static final float MAX_HAZE_END = 128F;

    // Second band at the top of the build height (26.1 raised the ceiling to 320).
    private static final int HIGH_BAND_LOW = 280;
    private static final int HIGH_BAND_HIGH = 320;
    private static final int HIGH_BAND_CORE = 300;

    private record FogBand(float lowY, float coreLow, float coreHigh, float highY) {
        static FogBand cloudBand(float cloudY) {
            return new FogBand(cloudY - BAND_OFFSETS, cloudY, cloudY + BAND_CORE_SIZE, cloudY + BAND_OFFSETS + BAND_CORE_SIZE);
        }
    }

    private final IDimensionInformation dimensionInformation;

    public HazeFogRangeCalculator(Configuration.FogOptions fogOptions, IDimensionInformation dimensionInformation) {
        super("Haze", fogOptions);
        // Injected once instead of resolving through the (synchronized) container on
        // every rendered frame.
        this.dimensionInformation = dimensionInformation;
    }

    @Override
    public boolean enabled() {
        return this.fogOptions.enableElevationHaze;
    }

    @Override
    @NotNull
    public FogRenderer.FogData render(@NotNull final FogRenderer.FogData data, float renderDistance, float partialTick) {
        var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return data;

        final float cloudY = this.dimensionInformation.getCloudHeight();

        final FogBand cloudBand = FogBand.cloudBand(cloudY);
        final FogBand highBand = new FogBand(HIGH_BAND_LOW, HIGH_BAND_CORE - 5, HIGH_BAND_CORE + 5, HIGH_BAND_HIGH);

        final float eyeY = (float) player.getEyeY();

        float bestStart = data.start;
        float bestEnd = data.end;
        for (final var range : new BandRange[] {
                this.bandRange(cloudBand, eyeY, data.start, data.end),
                this.bandRange(highBand, eyeY, data.start, data.end) }) {
            if (range != null) {
                bestStart = Math.min(bestStart, range.start());
                bestEnd = Math.min(bestEnd, range.end());
            }
        }

        if (bestEnd >= data.end && bestStart >= data.start)
            return data;

        var result = new FogRenderer.FogData(data.mode);
        result.end = bestEnd;
        result.start = bestStart;
        return result;
    }

    /**
     * Fog range while the eye is inside the band: the far plane eases from vanilla at
     * the band edges to the capped distance in the core, and the near plane blends
     * along with it (same pattern as the morning fog) so no fog pops in mid-range at
     * the band boundary. Smoothstep weighting gives the fade zero slope at the edges,
     * so the density starts and ends at nothing. Returns null when the eye is outside
     * the band.
     */
    private BandRange bandRange(FogBand band, float eyeY, float vanillaStart, float vanillaEnd) {
        if (eyeY <= band.lowY() || eyeY >= band.highY())
            return null;

        float t;
        if (eyeY < band.coreLow())
            t = (eyeY - band.lowY()) / (band.coreLow() - band.lowY());
        else if (eyeY > band.coreHigh())
            t = (band.highY() - eyeY) / (band.highY() - band.coreHigh());
        else
            t = 1F;

        // Smoothstep: zero slope at the band edges, density fades from true zero.
        t = t * t * (3F - 2F * t);

        final float end = Mth.lerp(t, vanillaEnd, MAX_HAZE_END);
        final float start = Mth.lerp(t, vanillaStart, MAX_HAZE_END * 0.5F);
        return new BandRange(start, end);
    }

    private record BandRange(float start, float end) {}
}
