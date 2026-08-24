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


    // Morning fog time window, in celestial degrees (tick0 = 6AM = 270deg).
    // Matches the 1.12.2 behaviour the user observed:
    //   fog starts at tick 23000 (5AM, 255deg)
    //   peaks at tick 24000 (6AM dawn, 270deg)
    //   fully gone by tick 26000 (8AM, 300deg)
    protected static final float FOG_START_ANGLE = 255F;
    protected static final float FOG_PEAK_ANGLE = 270F;
    protected static final float FOG_END_ANGLE = 300F;

    // Peak fog density: how close the far plane comes at dawn. Lower = denser
    // fog (shorter view distance). The user wanted a denser peak than before.
    protected static final float PEAK_FOG_END = 48F;

    // Fraction of the fog end used as the gradient width (start = end - width).
    // Keeps the fog a smooth haze instead of a hard wall when the range
    // collapses.
    protected static final float GRADIENT_FRACTION = 0.35F;

    // Maximum distance (world blocks) the morning fog wall can sit at. The fog
    // wall distance is decided by density and time of day only - it must NOT
    // scale with the render distance, otherwise turning the view distance up
    // pushes the fog past the horizon (a "no morning fog" look at long range).
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
            var angle = DayCycle.getCelestialAngleDegrees(GameUtils.getWorld().orElseThrow());
            if (angle >= FOG_START_ANGLE && angle <= FOG_END_ANGLE) {
                // Triangular strength curve: ramps up from start to the dawn peak,
                // then ramps down from the peak to full dispersal at FOG_END.
                final float strength;
                if (angle <= FOG_PEAK_ANGLE) {
                    strength = (angle - FOG_START_ANGLE) / (FOG_PEAK_ANGLE - FOG_START_ANGLE);
                } else {
                    strength = 1F - (angle - FOG_PEAK_ANGLE) / (FOG_END_ANGLE - FOG_PEAK_ANGLE);
                }
                // Density of the day's fog scales the overall strength.
                final float factor = strength * this.type.getIntensity();

                // At the window edges the strength is zero — do not rewrite the fog
                // range at all, otherwise the near plane pops to a different value
                // the instant the window is entered/left (a visible fog wall flash).
                if (factor <= 0.0001F)
                    return data;

                // The fog wall distance is anchored by the density and the time of
                // day only, NOT by the render distance. Interpolating between the
                // render end and a fixed cap made the wall drift past the horizon
                // at high view distances (density intensity never reaches 1, so the
                // wall sat at ~565-1024 blocks at 32 chunks - effectively invisible
                // morning fog). At peak dawn the wall sits at the density's peakEnd;
                // outside the peak it recedes toward MAX_FOG_VIEW_END. The render
                // end is only used as an upper clamp so very small view distances
                // (where the world itself ends sooner) still show the haze.
                final float wallEnd = this.peakEnd(this.type)
                        + (MAX_FOG_VIEW_END - this.peakEnd(this.type)) * (1F - strength);
                final float newEnd = Math.min(wallEnd, data.renderDistanceEnd);
                final float gradient = Math.max(newEnd * GRADIENT_FRACTION, 1F);
                final float newStart = Math.max(newEnd - gradient, 0F);

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