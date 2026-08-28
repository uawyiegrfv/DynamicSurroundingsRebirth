package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.fog.FogData;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.MinecraftClock;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;

// 26.1: SimpleWeightedRandomList was removed; use a small weighted table instead.
public class MorningFogRangeCalculator extends VanillaFogRangeCalculator {

    // Reused per frame; render() always overwrites both range fields before returning.
    private final FogData reusableResult = new FogData();


    // Morning fog time window and density are configurable via FogOptions:
    //   morningFogStartHour (5.0), morningFogPeakHour (6.0), morningFogEndHour (8.0),
    //   morningFogDensity (1.0 = default).

    // Fraction of the fog end used as the gradient width (start = end - width).
    // Keeps the fog a smooth haze instead of a hard wall when the range
    // collapses.
    protected static final float GRADIENT_FRACTION = 0.35F;

    // Defensive fallback for the (unreachable) NONE density case in peakEnd().
    // The real clear-sky distance is taken from the vanilla fog range at runtime.
    protected static final float MAX_FOG_VIEW_END = 256F;

    // Fog wall distance at peak dawn (6AM), per density, view-distance independent:
    //   HEAVY 48 (dense, short view), MEDIUM 64, NORMAL 96, LIGHT 160.
    protected static float peakEnd(final FogDensity density) {
        return switch (density) {
            case HEAVY -> 48F;
            case MEDIUM -> 64F;
            case NORMAL -> 96F;
            case LIGHT -> 160F;
            default -> MAX_FOG_VIEW_END;
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
    public FogData render(@NotNull final FogData data, float renderDistance, float partialTick) {

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
                // range untouched. The range is blended continuously from the vanilla
                // clear-sky range (strength=0) down to the fixed peak (strength=1), so
                // there is no discontinuity when the window opens or closes.
                if (strength <= 0F)
                    return data;

                // Blend the far plane from the vanilla clear-sky distance to the fixed
                // peak. The peak is anchored by the density only (not the render
                // distance), so the wall reaches peakEnd at dawn regardless of view
                // distance; the clear-sky anchor is the actual vanilla far plane, which
                // keeps the fog continuous at the window edges (no fog-wall flash or
                // sudden view-distance drop at 5AM/8AM).
                final float peakEndDist = Math.max(8F, peakEnd(this.type) / Math.max((float) this.fogOptions.morningFogDensity, 0.01F));
                final float newEnd = Math.min(
                        peakEndDist + (data.renderDistanceEnd - peakEndDist) * (1F - strength),
                        data.renderDistanceEnd);

                // Blend the near plane the same way so the haze gradient stays coherent
                // and never pops at the edges.
                final float peakStart = peakEndDist * (1F - GRADIENT_FRACTION);
                final float newStart = Math.max(0F, Math.min(
                        peakStart + (data.renderDistanceStart - peakStart) * (1F - strength),
                        newEnd));

                final FogData result = this.reusableResult;
                result.renderDistanceStart = newStart;
                result.renderDistanceEnd = newEnd;
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