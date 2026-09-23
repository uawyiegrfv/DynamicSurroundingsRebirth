package org.orecruncher.dsurround.sound;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.orecruncher.dsurround.lib.IdentityUtils;

import java.util.Optional;

/**
 * How a landing, a stop-scuff and a take-off are put together for one footstep material.
 *
 * <p>A landing is not a single sound: it is a primary layer, an optional quieter layer at half
 * volume, and an optional echo that repeats a couple of ticks later. That structure used to be a
 * Java table ({@code FootstepGenerator.LAND_COMPOSITIONS}); it lives on the material's factory
 * entry in {@code sound_factories.json} now, because the material <em>is</em> the factory.
 *
 * <p>{@code wander} and {@code jump} are separate one-shot cross-references, not part of the
 * landing: in the original data a material could scuff or take off with a <em>different</em>
 * material's recording. When {@code jump} is absent it falls back to {@code wander}, which is what
 * the original {@code EventType.JUMP(WANDER)} declaration meant.
 *
 * <p>All three layers are optional, and a material with no {@code land} block keeps the plain
 * behaviour: the material's own sound, no secondary layer, no echo.
 */
public record LandSettings(
        Optional<Identifier> primary,
        Optional<Identifier> secondary,
        Optional<Identifier> echo,
        float secondaryScale,
        float echoVolume,
        int echoDelayMinTicks,
        int echoDelayMaxTicks) {

    /** Defaults match the constants the Java table used before this was data. */
    public static final float DEFAULT_SECONDARY_SCALE = 0.5F;
    public static final float DEFAULT_ECHO_VOLUME = 1.0F;
    public static final int DEFAULT_ECHO_DELAY_MIN_TICKS = 1;
    public static final int DEFAULT_ECHO_DELAY_MAX_TICKS = 2;

    /**
     * IdentityUtils.CODEC is deliberate: it applies the same "no namespace means dsurround" rule the
     * {@code factory} field uses in biomes.json and blocks.json, so a pack can write
     * {@code "footsteps.stone_run"} and mean {@code dsurround:footsteps.stone_run}.
     */
    public static final Codec<LandSettings> CODEC = RecordCodecBuilder.create((instance) ->
            instance.group(
                    IdentityUtils.CODEC.optionalFieldOf("primary").forGetter(LandSettings::primary),
                    IdentityUtils.CODEC.optionalFieldOf("secondary").forGetter(LandSettings::secondary),
                    IdentityUtils.CODEC.optionalFieldOf("echo").forGetter(LandSettings::echo),
                    Codec.FLOAT.optionalFieldOf("secondaryScale", DEFAULT_SECONDARY_SCALE).forGetter(LandSettings::secondaryScale),
                    Codec.FLOAT.optionalFieldOf("echoVolume", DEFAULT_ECHO_VOLUME).forGetter(LandSettings::echoVolume),
                    Codec.INT.optionalFieldOf("echoDelayMinTicks", DEFAULT_ECHO_DELAY_MIN_TICKS).forGetter(LandSettings::echoDelayMinTicks),
                    Codec.INT.optionalFieldOf("echoDelayMaxTicks", DEFAULT_ECHO_DELAY_MAX_TICKS).forGetter(LandSettings::echoDelayMaxTicks)
            ).apply(instance, LandSettings::new));
}
