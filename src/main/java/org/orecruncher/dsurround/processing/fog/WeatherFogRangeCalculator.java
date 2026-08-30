package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.FogRenderer;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;

public class WeatherFogRangeCalculator extends VanillaFogRangeCalculator {


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
    public FogRenderer.FogData render(@NotNull final FogRenderer.FogData data, float renderDistance, float partialTick) {
        float rainStr = GameUtils.getWorld().map(w -> w.getRainLevel(partialTick)).orElseThrow();
        if (rainStr > 0) {

            // Blend both planes from the clear-sky (vanilla) range at rainStr=0 to the
            // rain-fog range at rainStr=1 using the same rain factor, so the near and
            // far planes move together. The far-plane target is capped (MAX_RAIN_FOG_END)
            // so rain fog stays close to the player, but applied as an interpolation
            // target rather than a hard clamp — the old hard clamp made the far plane
            // jump to 96 the instant rain began, showing a sharp fog boundary line.
            final float clearStart = data.start;
            final float clearEnd = data.end;

            // Density scale: higher = denser (closer fog). Shrinks the far-plane cap.
            final float densityScale = Math.max((float) this.fogOptions.weatherFogDensity, 0.01F);
            final float targetEnd = Math.min(clearEnd * (1F - END_IMPACT), MAX_RAIN_FOG_END / densityScale);
            final float targetStart = Math.min(clearStart * (1F - START_IMPACT), targetEnd);

            final float end = clearEnd + (targetEnd - clearEnd) * rainStr;
            final float start = clearStart + (targetStart - clearStart) * rainStr;

            var result = new FogRenderer.FogData(data.mode);
            result.start = Math.max(0F, Math.min(start, end));
            result.end = end;
            return result;
        }

        return data;
    }
}
