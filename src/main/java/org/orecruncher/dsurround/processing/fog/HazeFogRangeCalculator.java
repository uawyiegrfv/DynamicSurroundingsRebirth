package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.fog.FogData;
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

    // Reused per frame; render() always overwrites both range fields before returning.
    private final FogData reusableResult = new FogData();


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
    public FogData render(@NotNull final FogData data, float renderDistance, float partialTick) {
        var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return data;

        final float cloudY = this.dimensionInformation.getCloudHeight();

        final FogBand cloudBand = FogBand.cloudBand(cloudY);
        final FogBand highBand = new FogBand(HIGH_BAND_LOW, HIGH_BAND_CORE - 5, HIGH_BAND_CORE + 5, HIGH_BAND_HIGH);

        final float eyeY = (float) player.getEyeY();

        float bestEnd = data.renderDistanceEnd;
        bestEnd = Math.min(bestEnd, this.bandEnd(cloudBand, eyeY, data.renderDistanceEnd));
        bestEnd = Math.min(bestEnd, this.bandEnd(highBand, eyeY, data.renderDistanceEnd));

        if (bestEnd >= data.renderDistanceEnd)
            return data;

        final FogData result = this.reusableResult;
        result.renderDistanceEnd = bestEnd;
        // Start is always half the end so the Holistic combiner never rejects it
        // (start > end) even when the vanilla renderDistanceStart is large.
        result.renderDistanceStart = bestEnd * 0.5F;
        return result;
    }

    /**
     * Fade the far plane from vanilla at the band edges to the capped distance in the
     * core, so entering/exiting the band isn't abrupt. Returns data.renderDistanceEnd
     * when the eye is outside the band.
     */
    private float bandEnd(FogBand band, float eyeY, float vanillaEnd) {
        if (eyeY <= band.lowY() || eyeY >= band.highY())
            return vanillaEnd;

        float t;
        if (eyeY < band.coreLow())
            t = (eyeY - band.lowY()) / (band.coreLow() - band.lowY());
        else if (eyeY > band.coreHigh())
            t = (band.highY() - eyeY) / (band.highY() - band.coreHigh());
        else
            t = 1F;

        return Mth.lerp(t, vanillaEnd, MAX_HAZE_END);
    }
}
