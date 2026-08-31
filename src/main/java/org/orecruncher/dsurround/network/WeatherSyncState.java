package org.orecruncher.dsurround.network;

public final class WeatherSyncState {

    private static volatile boolean raining;

    private WeatherSyncState() {
    }

    public static boolean isRaining() {
        return raining;
    }

    public static void setRaining(final boolean value) {
        raining = value;
    }

    public static void reset() {
        raining = false;
    }
}
