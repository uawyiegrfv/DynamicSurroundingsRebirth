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

                // 26.1: data.renderDistanceStart is NOT ~0 (it's the near terrain
                // plane, e.g. ~230 at 16 chunks) — unlike 1.12.2 where the old
                // formula's base was ~0. The 1.12.2 shift/clamp math would collapse
                // start onto end (a hard fog wall with no gradient = "chunk loading
                // glitch"). Instead pull the far plane toward the dense-fog cap as
                // the factor grows, and always derive start as a fixed fraction
                // below end so the haze gradient is preserved.
                final float newEnd = data.renderDistanceEnd
                        - (data.renderDistanceEnd - PEAK_FOG_END) * factor;
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