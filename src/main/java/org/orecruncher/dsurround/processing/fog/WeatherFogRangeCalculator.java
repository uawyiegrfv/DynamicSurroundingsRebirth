package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.fog.FogData;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;

public class WeatherFogRangeCalculator extends VanillaFogRangeCalculator {

    // Reused per frame; render() always overwrites both range fields before returning.
    private final FogData reusableResult = new FogData();


    protected static final float START_IMPACT = 0.9F;
    protected static final float END_IMPACT = 0.4F;
    // Cap the rain fog so it stays close to the player (the render-distance basis alone
    // would push it out to ~154 blocks at 16 render distance, noticeably farther than vanilla).
    protected static final float MAX_RAIN_FOG_END = 96F;

    protected WeatherFogRangeCalculator(Configuration.FogOptions fogOptions) {
        super("Weather", fogOptions);
    }

    @Override
    public boolean enabled() {
        return this.fogOptions.enableWeatherFog;
    }

    @Override
    @NotNull
    public FogData render(@NotNull final FogData data, float renderDistance, float partialTick) {
        float rainStr = GameUtils.getWorld().map(w -> w.getRainLevel(partialTick)).orElseThrow();
        if (rainStr > 0) {

            final float startScale = 1F - (START_IMPACT * rainStr);
            final float endScale = 1F - (END_IMPACT * rainStr);
            // Cap the far plane so rain fog stays close to the player; the render-distance
            // basis alone would push it out to ~154 blocks. The near plane must stay at or
            // below the far plane (a start > end range is rejected by the Holistic combiner
            // and logged every frame).
            final float end = Math.min(data.renderDistanceEnd * endScale, MAX_RAIN_FOG_END);
            final float start = Math.min(data.renderDistanceStart * startScale, end);
            final FogData result = this.reusableResult;
            result.renderDistanceStart = start;
            result.renderDistanceEnd = end;
            return result;
        }

        return data;
    }
}
