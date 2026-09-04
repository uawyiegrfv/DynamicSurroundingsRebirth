package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.FogRenderer;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.MinecraftClock;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;

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

    private record FogChoice(FogDensity density, int weight) {
    }

    private static final FogChoice[] SPRING_FOG = {
            new FogChoice(FogDensity.NORMAL, 30),
            new FogChoice(FogDensity.MEDIUM, 20),
            new FogChoice(FogDensity.HEAVY, 10),
    };

    private static final FogChoice[] SUMMER_FOG = {
            new FogChoice(FogDensity.LIGHT, 20),
            new FogChoice(FogDensity.NONE, 10),
    };

    private static final FogChoice[] AUTUMN_FOG = {
            new FogChoice(FogDensity.NORMAL, 10),
            new FogChoice(FogDensity.MEDIUM, 20),
            new FogChoice(FogDensity.HEAVY, 10),
    };

    private static final FogChoice[] WINTER_FOG = {
            new FogChoice(FogDensity.LIGHT, 20),
            new FogChoice(FogDensity.NORMAL, 20),
            new FogChoice(FogDensity.MEDIUM, 10),
    };

    protected final ISeasonalInformation seasonInfo;
    protected final MinecraftClock clock;
    protected int fogDay = -1;
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
            final int startAngle = hourToAngle(this.fogOptions.morningFogStartHour);
            final int peakAngle = hourToAngle(this.fogOptions.morningFogPeakHour);
            final int endAngle = hourToAngle(this.fogOptions.morningFogEndHour);
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
                final float pull = Math.max(0F, data.start - reserve) * strength * density;
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
        // Determine if fog is going to be done this Minecraft day
        GameUtils.getWorld().ifPresent(this.clock::update);
        final int day = this.clock.getDay();
        if (this.fogDay != day) {
            this.fogDay = day;
            this.type = this.isFogAllowed() ? getFogType() : FogDensity.NONE;
        }
    }

    @Override
    public void disconnect() {
        this.fogDay = -1;
        this.type = FogDensity.NONE;
    }

    private boolean isFogAllowed() {
        // 26.1: DimensionType#natural was removed; surface worlds have sky light
        return GameUtils.getWorld().map(w -> w.dimensionType().hasSkyLight()).orElse(false);
    }

    @NotNull
    protected FogDensity getFogType() {
        FogChoice[] selections;
        if (this.seasonInfo.isSpring())
            selections = SPRING_FOG;
        else if (this.seasonInfo.isSummer())
            selections = SUMMER_FOG;
        else if (this.seasonInfo.isAutumn())
            selections = AUTUMN_FOG;
        else if (this.seasonInfo.isWinter())
            selections = WINTER_FOG;
        else
            // Shouldn't get here, but...
            return FogDensity.NONE;

        int totalWeight = 0;
        for (FogChoice choice : selections)
            totalWeight += choice.weight();

        int roll = Randomizer.current().nextInt(totalWeight);
        for (FogChoice choice : selections) {
            roll -= choice.weight();
            if (roll < 0)
                return choice.density();
        }

        return FogDensity.NONE;
    }
}