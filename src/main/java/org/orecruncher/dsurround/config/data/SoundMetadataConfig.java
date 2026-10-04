package org.orecruncher.dsurround.config.data;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.sounds.SoundSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public record SoundMetadataConfig(Optional<String> title, Optional<String> subtitle, Optional<SoundSource> category, List<CreditEntry> credits) {

    private static final Map<String, SoundSource> SOUND_SOURCE_BY_NAME = Arrays.stream(SoundSource.values()).collect(Collectors.toMap(SoundSource::getName, (category) -> category));
    public static final Codec<SoundSource> SOUND_SOURCE_CODEC = Codec.STRING.comapFlatMap(DataResult.partialGet(SOUND_SOURCE_BY_NAME::get, () -> "unknown sound category type"), SoundSource::getName);
    public static final Codec<SoundMetadataConfig> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            Codec.STRING.optionalFieldOf("ds_title").forGetter(SoundMetadataConfig::title),
            Codec.STRING.optionalFieldOf("subtitle").forGetter(SoundMetadataConfig::subtitle),
            SOUND_SOURCE_CODEC.optionalFieldOf("ds_category").forGetter(SoundMetadataConfig::category),
            Codec.list(CreditEntry.CODEC).optionalFieldOf("ds_credits", ImmutableList.of()).forGetter(SoundMetadataConfig::credits))
            .apply(instance, SoundMetadataConfig::new));

    /**
     * Indicates whether the settings in the instance are the default settings.
     *
     * @return true if the properties are the same as defaults; false otherwise
     */
    public boolean isDefault() {
        return this.title.isEmpty() && this.subtitle.isEmpty() && this.credits.isEmpty() && this.category.isEmpty();
    }

    /**
     * One attribution entry from a {@code ds_credits} block. {@code name} and
     * {@code license} decode leniently (absent - not failed - when the key is
     * missing): the shipped sounds.json carries fifteen entries with
     * author+website only, and on the newer DataFixerUpper that 26.1 ships,
     * {@code optionalFieldOf} no longer swallows a decode error inside a
     * present field - one incomplete credit failed the whole
     * {@code UnboundedMapCodec}, the entire sounds.json came back empty, no
     * DSR sound event registered, and every ambient layer fell back to the
     * missing-sound placeholder. Older DFU (1.20.1 / 1.21.1) silently dropped
     * just the broken list, which is why the same data parsed there. {@code
     * author} stays required: it is the one field every shipped entry has,
     * and a credit without an author is not a credit.
     */
    public record CreditEntry(Optional<String> name, String author, Optional<String> website, Optional<String> license) {
        public static final Codec<CreditEntry> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
                Codec.STRING.optionalFieldOf("name").forGetter(CreditEntry::name),
                Codec.STRING.fieldOf("author").forGetter(CreditEntry::author),
                Codec.STRING.optionalFieldOf("website").forGetter(CreditEntry::website),
                Codec.STRING.optionalFieldOf("license").forGetter(CreditEntry::license))
                .apply(instance, CreditEntry::new));
    }
}