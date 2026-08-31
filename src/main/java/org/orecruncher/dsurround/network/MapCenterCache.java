package org.orecruncher.dsurround.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MapCenterCache {

    private static final Map<Integer, int[]> CENTERS = new ConcurrentHashMap<>();

    private MapCenterCache() {
    }

    public static void put(int mapId, int centerX, int centerZ) {
        CENTERS.put(mapId, new int[] { centerX, centerZ });
    }

    public static int[] get(int mapId) {
        return CENTERS.get(mapId);
    }

    public static void reset() {
        CENTERS.clear();
    }
}
