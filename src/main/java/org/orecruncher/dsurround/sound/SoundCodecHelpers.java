package org.orecruncher.dsurround.sound;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.UniformFloat;
import org.orecruncher.dsurround.lib.IdentityUtils;

public class SoundCodecHelpers {

    public static final Codec<SoundSource> SOUND_CATEGORY_CODEC = Codec.STRING.xmap(SoundCodecHelpers::lookupSoundSource, SoundSource::getName);
    public static final Codec<SoundInstance.Attenuation> ATTENUATION_CODEC = Codec.STRING.xmap(SoundCodecHelpers::lookupAttenuation, SoundInstance.Attenuation::name);
    public static final Codec<SoundEvent> SOUND_EVENT_CODEC = Codec.either(IdentityUtils.CODEC, SoundEvent.DIRECT_CODEC)
            .xmap(either -> either.map(SoundEvent::createVariableRangeEvent, x -> x), Either::right);

    public static final Codec<FloatProvider> SOUND_PROPERTY_RANGE = Codec.either(Codec.FLOAT, RangeProperty.CODEC)
            .xmap((either) -> either.map(ConstantFloat::of, rangeProperty -> rangeProperty.max > rangeProperty.min
                    // UniformFloat.of() throws "Max must exceed min" when max <= min. The factory
                    // list codec decodes the whole sound_factories.json as one list, so a single
                    // {min==max} entry used to reject the ENTIRE file - factories cached: 0 - and
                    // every factory whose location is not itself a sound event (brush step, footstep
                    // accents, toolbar, ...) went silent. Decode min==max as a constant instead.
                    ? UniformFloat.of(rangeProperty.min, rangeProperty.max)
                    : ConstantFloat.of(rangeProperty.min)),
                    floatProvider -> {
                        throw new RuntimeException("Not gonna happen");
                    });

    public record RangeProperty(float min, float max) {
        static Codec<RangeProperty> CODEC = RecordCodecBuilder.create((instance) ->
                instance.group(
                        Codec.FLOAT.optionalFieldOf("min", 1.0F).forGetter(RangeProperty::min),
                        Codec.FLOAT.optionalFieldOf("max", 1.0F).forGetter(RangeProperty::max)
                ).apply(instance, RangeProperty::new));
    }

    private static SoundSource lookupSoundSource(String string) {
        for (var c : SoundSource.values())
            if (c.getName().equalsIgnoreCase(string))
                return c;
        return SoundSource.AMBIENT;
    }

    private static SoundInstance.Attenuation lookupAttenuation(String string) {
        for (var c : SoundInstance.Attenuation.values())
            if (c.name().equalsIgnoreCase(string))
                return c;
        return SoundInstance.Attenuation.LINEAR;
    }

}
