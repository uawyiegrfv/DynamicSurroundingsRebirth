package org.orecruncher.dsurround.processing.fog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.orecruncher.dsurround.config.SoundEventType;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fog density levels. Only the intensity matters now: the morning-fog time
 * window (which the 1.12.2 design encoded here via per-density start/end
 * angles) is owned by {@link MorningFogRangeCalculator}, so those fields were
 * removed.
 */
public enum FogDensity {

    NONE("none", 0F),
    LIGHT("light", 0.3F),
    NORMAL("normal", 0.47F),
    MEDIUM("medium", 0.64F),
    HEAVY("heavy", 0.8F);

    private final String name;
    private final float intensity;

    FogDensity(final String name, final float intensity) {
        this.name = name;
        this.intensity = intensity;
    }

    private static final Map<String, FogDensity> BY_NAME = Arrays.stream(values()).collect(Collectors.toMap(FogDensity::getName, (category) -> category));
    public static final Codec<FogDensity> CODEC = Codec.STRING.comapFlatMap(DataResult.partialGet(BY_NAME::get, () -> "unknown sound event type"), FogDensity::getName);

    public String getName() {
        return this.name;
    }

    public float getIntensity() {
        return this.intensity;
    }
}
