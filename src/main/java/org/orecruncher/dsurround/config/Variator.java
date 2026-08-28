package org.orecruncher.dsurround.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Per-entity footstep parameters, ported from the original 1.12.2 Variator. Controls the
 * step cadence (stride), run threshold, volume, jump/land behavior and footprint settings
 * for a class of entity (player, child, quadruped, skeleton, ...).
 */
public record Variator(
        int immobileDuration,
        boolean eventOnJump,
        float landHardDistanceMin,
        float speedToJumpAsMultifoot,
        float speedToRun,
        float stride,
        float strideStair,
        float strideLadder,
        float quadrupedMultiplier,
        boolean playWander,
        boolean quadruped,
        boolean playJump,
        float distanceToCenter,
        boolean hasFootprint,
        float footprintScale,
        float volumeScale) {

    public static final Variator DEFAULT = new Variator(200, true, 0.9F, 0.005F, 0.22F,
            0.75F, 0.4875F, 1.0F, 1.25F, true, false, false, 0.2F, true, 1.0F, 1.0F);

    public static final Codec<Variator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("immobileDuration", DEFAULT.immobileDuration()).forGetter(Variator::immobileDuration),
            Codec.BOOL.optionalFieldOf("eventOnJump", DEFAULT.eventOnJump()).forGetter(Variator::eventOnJump),
            Codec.FLOAT.optionalFieldOf("landHardDistanceMin", DEFAULT.landHardDistanceMin()).forGetter(Variator::landHardDistanceMin),
            Codec.FLOAT.optionalFieldOf("speedToJumpAsMultifoot", DEFAULT.speedToJumpAsMultifoot()).forGetter(Variator::speedToJumpAsMultifoot),
            Codec.FLOAT.optionalFieldOf("speedToRun", DEFAULT.speedToRun()).forGetter(Variator::speedToRun),
            Codec.FLOAT.optionalFieldOf("stride", DEFAULT.stride()).forGetter(Variator::stride),
            Codec.FLOAT.optionalFieldOf("strideStair", DEFAULT.strideStair()).forGetter(Variator::strideStair),
            Codec.FLOAT.optionalFieldOf("strideLadder", DEFAULT.strideLadder()).forGetter(Variator::strideLadder),
            Codec.FLOAT.optionalFieldOf("quadrupedMultiplier", DEFAULT.quadrupedMultiplier()).forGetter(Variator::quadrupedMultiplier),
            Codec.BOOL.optionalFieldOf("playWander", DEFAULT.playWander()).forGetter(Variator::playWander),
            Codec.BOOL.optionalFieldOf("quadruped", DEFAULT.quadruped()).forGetter(Variator::quadruped),
            Codec.BOOL.optionalFieldOf("playJump", DEFAULT.playJump()).forGetter(Variator::playJump),
            Codec.FLOAT.optionalFieldOf("distanceToCenter", DEFAULT.distanceToCenter()).forGetter(Variator::distanceToCenter),
            Codec.BOOL.optionalFieldOf("hasFootprint", DEFAULT.hasFootprint()).forGetter(Variator::hasFootprint),
            Codec.FLOAT.optionalFieldOf("footprintScale", DEFAULT.footprintScale()).forGetter(Variator::footprintScale),
            Codec.FLOAT.optionalFieldOf("volumeScale", DEFAULT.volumeScale()).forGetter(Variator::volumeScale))
            .apply(instance, Variator::new));
}
