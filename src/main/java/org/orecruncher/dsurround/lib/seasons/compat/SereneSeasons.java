package org.orecruncher.dsurround.lib.seasons.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import org.orecruncher.dsurround.config.libraries.IDimensionInformation;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * Serene Seasons integration. Ported to use reflection so the mod has no hard
 * compile-time dependency on the Serene Seasons jar (it is an optional
 * integration: SeasonManager only instantiates this provider when Serene
 * Seasons is actually loaded at runtime).
 *
 * 1.20.1 note: Serene Seasons 9.x SeasonHooks helpers take
 * (Level, Holder<Biome>, BlockPos). The 26.1 port added a trailing sea-level
 * int parameter, which does not exist on 1.20.1.
 */
public class SereneSeasons extends AbstractSeasonProvider {

    private final IDimensionInformation dimensionInformation;

    // Cache for previously computed data
    private Object subSeason;
    private Object tropicalSeason;
    private Component computed;

    public SereneSeasons(IDimensionInformation dimensionInformation) {
        super("Serene Seasons");
        this.dimensionInformation = dimensionInformation;
    }

    @Override
    public Optional<Component> getCurrentSeason() {
        var helper = seasonState();
        var subSeason = invoke(helper, "getSubSeason");
        return Optional.of(Component.literal(subSeason.toString()));
    }

    @Override
    public Optional<Component> getCurrentSeasonTranslated() {
        var helper = seasonState();
        var sub = invoke(helper, "getSubSeason");
        var trop = invoke(helper, "getTropicalSeason");
        if (this.subSeason != sub || this.tropicalSeason != trop) {
            var subSeasonKey = "desc.sereneseasons." + invoke(helper, "getSeason").toString().toLowerCase(Locale.ROOT);
            var tropicalSeasonKey = "desc.sereneseasons." + trop.toString().toLowerCase(Locale.ROOT);
            var subSeasonComponent = Component.translatable(subSeasonKey);
            var tropicalSeasonComponent = Component.translatable(tropicalSeasonKey);
            this.computed = Component.translatable("%s (%s)", subSeasonComponent, tropicalSeasonComponent);
            this.subSeason = sub;
            this.tropicalSeason = trop;
        }

        return Optional.of(this.computed);
    }

    public boolean isSpring() {
        return isSeason("SPRING");
    }

    public boolean isSummer() {
        return isSeason("SUMMER");
    }

    public boolean isAutumn() {
        return isSeason("AUTUMN");
    }

    public boolean isWinter() {
        return isSeason("WINTER");
    }

    private boolean isSeason(String name) {
        return invoke(seasonState(), "getSeason").toString().equals(name);
    }

    @Override
    public Biome.Precipitation getPrecipitationAt(BlockPos blockPos) {
        var level = this.level();
        var biome = level.getBiome(blockPos);
        return (Biome.Precipitation) invokeStatic("sereneseasons.season.SeasonHooks", "getPrecipitationAtSeasonal", level, biome, blockPos);
    }

    @Override
    public float getTemperature(BlockPos blockPos) {
        var level = this.level();
        var biome = level.getBiome(blockPos);
        return (Float) invokeStatic("sereneseasons.season.SeasonHooks", "getBiomeTemperature", level, biome, blockPos);
    }

    @Override
    public ClientLevel level() {
        return this.dimensionInformation.level();
    }

    // ---- reflection plumbing ----

    private Object seasonState() {
        return invokeStatic("sereneseasons.api.season.SeasonHelper", "getSeasonState", this.level());
    }

    private static Object invoke(Object target, String methodName) {
        try {
            return findMethod(target.getClass(), methodName).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Serene Seasons reflection failed: " + methodName, e);
        }
    }

    private static Object invokeStatic(String className, String methodName, Object... args) {
        try {
            Class<?> clazz = Class.forName(className);
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    return m.invoke(null, args);
                }
            }
            throw new NoSuchMethodException(methodName + "/" + args.length + " in " + className);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Serene Seasons reflection failed: " + className + "." + methodName, e);
        }
    }

    private static Method findMethod(Class<?> clazz, String methodName) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                return m;
            }
        }
        throw new IllegalStateException("Serene Seasons reflection failed: no-arg method " + methodName + " in " + clazz.getName());
    }
}
