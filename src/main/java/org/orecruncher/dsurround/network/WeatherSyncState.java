package org.orecruncher.dsurround.network;

/**
 * Client-side cache of the overworld rain state, pushed by the server via
 * WeatherPayload. Used by WeatherStormHandler for the nether dust (the nether's own
 * Level.isRaining() is always false because it has no sky).
 */
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
