package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.FogRenderer;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.MinecraftClock;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.lib.seasons.SeasonPhase;

// 26.1: SimpleWeightedRandomList was removed; use a small weighted table instead.
public class MorningFogRangeCalculator extends VanillaFogRangeCalculator {


    // Morning fog time window and density are configurable via FogOptions:
    //   morningFogStartHour (5.0), morningFogPeakHour (6.0), morningFogEndHour (8.0),
    //   morningFogDensity (1.0 = default).

    // Near-plane reserve at peak dawn, per fog type (blocks). Mirrors the 1.12.2
    // FogType reserves: heavier mornings reach closer to the player (thicker
    // mist), lighter ones keep the haze farther away. View-distance independent.
    protected static float reserveOf(final FogDensity density) {
        return switch (density) {
            case HEAVY -> 5F;
            case MEDIUM -> 8F;
            case NORMAL -> 10F;
            case LIGHT -> 15F;
            default -> 10F;
        };
    }

    // Convert an hour-of-day (0..24, 6AM = 6.0) to the celestial-degree convention
    // used by DayCycle (270 = 6AM dawn; degrees = 270 + (hour - 6) * 15).
    private static int hourToAngle(double hour) {
        double deg = 270D + (hour - 6D) * 15D;
        deg %= 360D;
        if (deg < 0D)
            deg += 360D;
        return (int) Math.round(deg);
    }

    // Effective morning window per fog type, in hours - the 1.12.2 FogType enum
    // adjustments expressed against the user-configured start/peak/end hours:
    // MEDIUM widens the window by 1h per side, HEAVY by 2h per side, LIGHT hugs the
    // dawn peak for half an hour each side, NORMAL keeps the configured window.
    private record WindowHours(double start, double peak, double end) {
    }

    private WindowHours effectiveWindow(final FogDensity type) {
        final double start = this.fogOptions.morningFogStartHour;
        final double peak = this.fogOptions.morningFogPeakHour;
        final double end = this.fogOptions.morningFogEndHour;
        return switch (type) {
            case HEAVY -> new WindowHours(start - 2D, peak, end + 2D);
            case MEDIUM -> new WindowHours(start - 1D, peak, end + 1D);
            case LIGHT -> new WindowHours(peak - 0.5D, peak, peak + 0.5D);
            default -> new WindowHours(start, peak, end);
        };
    }

    protected final ISeasonalInformation seasonInfo;
    protected final MinecraftClock clock;
    protected int fogDay = -1;
    protected SeasonPhase lastPhase = null;
    protected FogDensity type = FogDensity.NONE;

    public MorningFogRangeCalculator(ISeasonalInformation seasonInfo, Configuration.FogOptions fogOptions) {
        super("Morning", fogOptions);
        this.seasonInfo = seasonInfo;
        this.clock = new MinecraftClock();
    }

    @Override
    public boolean enabled() {
        return this.fogOptions.enableMorningFog;
    }

    @Override
    @NotNull
    public FogRenderer.FogData render(@NotNull final FogRenderer.FogData data, float renderDistance, float partialTick) {

        if (this.type != FogDensity.NONE) {
            final var window = this.effectiveWindow(this.type);
            final int startAngle = hourToAngle(window.start());
            final int peakAngle = hourToAngle(window.peak());
            final int endAngle = hourToAngle(window.end());
            // Guard against a misconfigured window (peak must sit strictly between start and end).
            if (startAngle >= peakAngle || peakAngle >= endAngle)
                return data;

            var angle = DayCycle.getCelestialAngleDegrees(GameUtils.getWorld().orElseThrow());
            if (angle >= startAngle && angle <= endAngle) {
                // Triangular strength curve: ramps up from start to the dawn peak,
                // then ramps down from the peak to full dispersal at the end.
                final float strength;
                if (angle <= peakAngle) {
                    strength = (angle - startAngle) / (peakAngle - startAngle);
                } else {
                    strength = 1F - (angle - peakAngle) / (endAngle - peakAngle);
                }
                // At the window edges the strength is exactly zero: return the vanilla
                // range untouched. The near plane is pulled in continuously with the
                // strength curve, so the haze builds up and disperses without any pop
                // when the window opens or closes.
                if (strength <= 0F)
                    return data;

                // Layered morning haze, matching the original 1.12.2 feel: the vanilla far
                // plane is NEVER pulled in, so the visible view distance is not reduced.
                // Instead the NEAR plane is drawn toward the player in proportion to the
                // time-of-day strength and the density scaling, stretching the linear fog
                // gradient from a small reserve distance all the way to the vanilla far
                // plane - distance reads as progressively thicker mist while nearby
                // terrain keeps a subtle morning haze.
                final float density = (float) Math.max(0D, this.fogOptions.morningFogDensity);
                final float reserve = reserveOf(this.type);
                // Some fog passes report a zero/tiny near plane (the 1.20.1 sky pass does);
                // pulling the start there is a no-op at best and pushes it outward at worst,
                // and the holistic min-merge would discard it regardless. Leave them be.
                if (data.start <= reserve)
                    return data;
                final float pull = (data.start - reserve) * strength * density;
                final float newStart = Math.max(reserve, data.start - pull);

                var result = new FogRenderer.FogData(data.mode);
                result.start = newStart;
                result.end = data.end;
                return result;
            }
        }
        return data;
    }

    @Override
    public void tick() {
        // Recompute the fog type when the Minecraft day or the season sub-phase changes
        // (the lookup is a pure function, so re-evaluating on change is all it takes).
        GameUtils.getWorld().ifPresent(this.clock::update);
        final int day = this.clock.getDay();
        final SeasonPhase phase = this.seasonInfo.getSeasonPhase();
        if (this.fogDay != day || this.lastPhase != phase) {
            this.fogDay = day;
            this.lastPhase = phase;
            this.type = this.isFogAllowed() ? getFogType() : FogDensity.NONE;
        }
    }

    @Override
    public void disconnect() {
        this.fogDay = -1;
        this.lastPhase = null;
        this.type = FogDensity.NONE;
    }

    private boolean isFogAllowed() {
        // 26.1: DimensionType#natural was removed; surface worlds have sky light
        return GameUtils.getWorld().map(w -> w.dimensionType().hasSkyLight()).orElse(false);
    }

    // 1.12.2 SeasonFogRangeCalculator parity: the fog type is a DETERMINISTIC lookup
    // of (season, sub-phase) - the old per-day random draw is gone. Midsummer mornings
    // deterministically have no fog at all (original behavior - expectable, unlike the
    // old random roll). Without a real seasonal provider (vanilla fallback) the base
    // NORMAL morning applies, like the original without a seasons mod.
    @NotNull
    protected FogDensity getFogType() {
        if (!this.seasonInfo.hasSeasonalCycle())
            return FogDensity.NORMAL;
        final SeasonPhase phase = this.seasonInfo.getSeasonPhase();
        if (this.seasonInfo.isSpring())
            return switch (phase) {
                case EARLY -> FogDensity.MEDIUM;
                case MID -> FogDensity.HEAVY;
                case LATE -> FogDensity.NORMAL;
            };
        if (this.seasonInfo.isSummer())
            return switch (phase) {
                case EARLY -> FogDensity.LIGHT;
                case MID -> FogDensity.NONE;
                case LATE -> FogDensity.LIGHT;
            };
        if (this.seasonInfo.isAutumn())
            return switch (phase) {
                case EARLY -> FogDensity.NORMAL;
                case MID -> FogDensity.MEDIUM;
                case LATE -> FogDensity.HEAVY;
            };
        if (this.seasonInfo.isWinter())
            return switch (phase) {
                case EARLY -> FogDensity.MEDIUM;
                case MID -> FogDensity.LIGHT;
                case LATE -> FogDensity.NORMAL;
            };
        return FogDensity.NONE;
    }
}