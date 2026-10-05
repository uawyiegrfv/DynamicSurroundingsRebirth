package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.FogRenderer;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;

public class WeatherFogRangeCalculator extends VanillaFogRangeCalculator {


    /** 雨雾把近平面推到多近。0.9 时近平面停在约 17 格处，于是 20 格以内
     * 完全没有雾 —— 维护者要的"雨雾那种湿润感"就是在这一段。
     * 0.95 把它拉到约 8.6 格，近处立刻有雾。
     * 注意它只影响"雨雾"那一份（start 是朝 targetStart 按 rainStr 插值的），
     * 小雨时 rainStr 小、start 仍贴近原值，不会被波及。 */
    protected static final float START_IMPACT = 0.95F;
    protected static final float END_IMPACT = 0.4F;
    // Cap the rain fog so it stays close to the player (the render-distance basis alone
    // would push it out to ~154 blocks at 16 render distance, noticeably farther than vanilla).
    protected static final float MAX_RAIN_FOG_END = 96F;

    /**
     * Floor for the rain fog's FAR plane, as a fraction of the clear (vanilla)
     * far plane. <b>This is the trade knob: smaller = thicker, wetter fog near
     * the player; larger = thinner fog and less of the cloud layer sitting
     * inside it.</b>
     *
     * <p>Guard first, clamp second - the far plane is
     * {@code max(clearEnd * this, min(clearEnd * (1 - END_IMPACT), MAX_RAIN_FOG_END / density))}.
     * The inner term can never exceed {@code clearEnd * 0.6}, so anything
     * above 0.6 makes the guard the ONLY thing deciding the far plane and both
     * END_IMPACT and MAX_RAIN_FOG_END go dead. 0.8 did exactly that: at 12
     * chunks it moved the far plane from 96 to 153.6 blocks and, because fog
     * opacity is {@code (d - start) / (end - start)}, dropped the near-field
     * density by roughly 40% - the "no fog anywhere near me, no wet" report.
     * 0.55 puts it at 105.6, within 10% of where it was before the guard
     * existed, so the thickness comes back.</p>
     *
     * <p>The "below about 0.5 the low clouds start being swallowed again" that
     * used to sit here was built on the cloud pass reading the TERRAIN range.
     * It does not. {@code LevelRenderer.renderLevel} calls
     * {@code setupFog(FOG_SKY, renderDistance, ...)} before {@code renderSky},
     * and {@code renderClouds} then only re-reads the fog COLOUR
     * ({@code FogRenderer.levelFogColor()}) - the range the cloud shader sees is
     * the sky's, 0..render distance, and all three versions gate the weather
     * fog on the terrain/atmospheric pass, so this number never reaches it.
     * Which means going below 0.5 is safe as far as the cloud sheet goes, and
     * "the clouds look white" is a fog-COLOUR question - answered by
     * {@code PrecipitationIntensity.fogColorStormLevel}, not by this.</p>
     *
     * <p>Expressed as a fraction rather than in blocks so it scales with the
     * render distance: an absolute floor would cancel the rain fog entirely at
     * low render distances. 1.0 = the far plane never moves (vanilla).</p>
     */
    protected static final float CLOUD_GUARD_END_FRACTION = 0.55F;

    /**
     * How close to the player the after-rain haze may reach, in blocks. Larger
     * than the morning-mist reserves on purpose: what lingers after a storm is
     * a thin haze spread over a long range, not the thick bank you get at dawn.
     * Morning mist uses 5-15 by density; this sits well outside that.
     */
    protected static final float AFTER_FOG_RESERVE = 24F;

    /**
     * How much of the view distance the after-rain haze is allowed to hold
     * back, as a fraction of the clear range.
     *
     * <p>0 means "never touch the far plane", which is the morning-mist
     * discipline and was where this started - and it is the reason the first
     * pass came out too thin to see. Fog opacity is {@code (d - start) /
     * (end - start)}, so with the far plane fully back out the haze cannot get
     * thick however close the near plane is pulled: at 60 blocks it tops out
     * around 31% even with the reserve at zero, against 56% for rain fog. The
     * weight was never in the near plane, it was in the far one all along.</p>
     *
     * <p>0.3 keeps 70% of the distance, which roughly doubles the haze's
     * strength at mid range. Both planes travel back out together as the haze
     * decays, so dispersal still reads as the world opening up - it just clears
     * at the same time instead of after. This is the one knob for "too thin"
     * or "too heavy".</p>
     */
    protected static final float AFTER_FOG_END_IMPACT = 0.3F;

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
        // While we own the precipitation, fog follows OUR intensity curve rather than
        // the vanilla rain level. Vanilla's level is a 5s ramp that hits 1.0 whether
        // the storm is a drizzle or a downpour, which is why fog used to snap in at
        // full thickness the instant rain started and then sit there while the rain
        // itself was still building. The FOG response is early-rising and saturates
        // below the top end: humidity is the first thing you notice, and past a point
        // more rain does not mean more haze.
        float rainStr = org.orecruncher.dsurround.processing.PrecipitationIntensity.ownsAmbient()
                ? org.orecruncher.dsurround.processing.weather.PrecipitationResponse.FOG_DIST.value()
                : GameUtils.getWorld().map(w -> w.getRainLevel(partialTick)).orElseThrow();
        // The haze that outlives the rain. Read even when rainStr is 0: the rain
        // is over by the time this is doing its work.
        final float afterStr = this.fogOptions.enableAfterRainFog
                ? org.orecruncher.dsurround.processing.weather.PrecipitationResponse.AFTER_FOG.value()
                : 0F;

        if (rainStr > 0 || afterStr > 0) {

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
            // Cloud-layer guard: however dense the weather gets, the far plane
            // may not come closer than CLOUD_GUARD_END_FRACTION of the clear
            // range, so the cloud layer stays outside the fog it would
            // otherwise be replaced by. See the constant above.
            final float cloudGuardEnd = clearEnd * CLOUD_GUARD_END_FRACTION;
            final float targetEnd = Math.max(cloudGuardEnd,
                    Math.min(clearEnd * (1F - END_IMPACT), MAX_RAIN_FOG_END / densityScale));
            final float targetStart = Math.min(clearStart * (1F - START_IMPACT), targetEnd);

            float end = clearEnd + (targetEnd - clearEnd) * rainStr;
            float start = clearStart + (targetStart - clearStart) * rainStr;

            // The far-plane half of the residue. Guarded the same way the near
            // plane is: during rain the end is already inside this target, and
            // interpolating "towards" a target you are already inside would
            // push it back out and undo the rain fog.
            if (afterStr > 0F) {
                final float afterEnd = clearEnd * (1F - AFTER_FOG_END_IMPACT);
                if (end > afterEnd)
                    end += (afterEnd - end) * afterStr;
            }

            // After-rain haze, applied the way morning mist is: near plane only,
            // never the far one. That is what makes the two halves separable -
            // the far plane above is the view distance, and it is already on its
            // way back out; this only decides how much haze is left hanging in
            // the space it is coming back into. Pulling the far plane here would
            // undo the very separation this exists for.
            //
            // The guard matters: during rain, start is already inside the reserve
            // (rain fog reaches much closer than this), and pulling it "towards"
            // a reserve it is already inside would push it back out and thin the
            // rain fog. Morning mist has the same guard for the same reason.
            if (afterStr > 0F && start > AFTER_FOG_RESERVE) {
                final float pull = (start - AFTER_FOG_RESERVE) * afterStr * densityScale;
                start = Math.max(AFTER_FOG_RESERVE, start - pull);
            }

            var result = new FogRenderer.FogData(data.mode);
            result.start = Math.max(0F, Math.min(start, end));
            result.end = end;
            return result;
        }

        return data;
    }
}
