package org.orecruncher.dsurround.lib;

import net.minecraft.world.level.Level;
import org.orecruncher.dsurround.Constants;

public enum DayCycle {

    NO_SKY("NoSky"),
    SUNRISE("Sunrise"),
    SUNSET("Sunset"),
    DAYTIME("Daytime"),
    NIGHTTIME("Nighttime");

    // Thresholds are in degrees.  Noon is 0 degrees, and midnight is 180
    private static final float DAYTIME_THRESHOLD = 274;
    private static final float SUNRISE_THRESHOLD = DAYTIME_THRESHOLD - 15F;
    private static final float NIGHTTIME_THRESHOLD = 94F;
    private static final float SUNSET_THRESHOLD = NIGHTTIME_THRESHOLD - 15F;

    private final String localizeString;

    DayCycle(final String localName) {
        this.localizeString = Constants.MOD_ID + ".format." + localName;
    }

    public static boolean isDaytime(final Level world) {
        return getCycle(world) == DayCycle.DAYTIME;
    }

    public static boolean isNighttime(final Level world) {
        return getCycle(world) == DayCycle.NIGHTTIME;
    }

    public static boolean isSunrise(final Level world) {
        return getCycle(world) == DayCycle.SUNRISE;
    }

    public static boolean isSunset(final Level world) {
        return getCycle(world) == DayCycle.SUNSET;
    }

    /**
     * Celestial angle in degrees for the given world. Convention: tick 0 is dawn
     * (6AM); noon is 0 degrees, midnight is 180 degrees. The overworld clock
     * accumulates ticks across days, so it is reduced modulo 24000 and offset by
     * -6 hours (+18000 mod 24000) to line up with the 0=noon convention.
     */
    public static float getCelestialAngleDegrees(final Level world) {
        return ((world.getDayTime() % 24000L + 18000L) % 24000L) / 24000F * 360F;
    }

    public static DayCycle getCycle(final Level world) {
        if (world.dimensionType().hasCeiling() || !world.dimensionType().hasSkyLight())
            return DayCycle.NO_SKY;

        final float angleDegrees = getCelestialAngleDegrees(world);

        if (angleDegrees > DAYTIME_THRESHOLD)
            return DayCycle.DAYTIME;
        if (angleDegrees > SUNRISE_THRESHOLD)
            return DayCycle.SUNRISE;
        if (angleDegrees > NIGHTTIME_THRESHOLD)
            return DayCycle.NIGHTTIME;
        if (angleDegrees > SUNSET_THRESHOLD)
            return DayCycle.SUNSET;
        return DayCycle.DAYTIME;
    }

    public static float getMoonSize(final Level world) {
        return 1.0F; // 26.1: Level.getMoonBrightness() removed
    }

    public String getFormattedName() {
        return Localization.load(this.localizeString);
    }

}