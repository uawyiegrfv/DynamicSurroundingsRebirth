package org.orecruncher.dsurround.config.libraries;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.config.IndividualSoundConfigEntry;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundMetadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ISoundLibrary extends ILibrary {

    SoundEvent getSound(final String sound);
    SoundEvent getSound(final Identifier sound);

    /**
     * Returns true if the sound event is registered. Note: {@link #getSound(Identifier)}
     * returns the MISSING placeholder for unregistered sounds (not null), so a simple null
     * check is not sufficient.
     */
    boolean isSoundRegistered(Identifier sound);

    Collection<SoundEvent> getRegisteredSoundEvents();
    SoundMetadata getSoundMetadata(final Identifier sound);
    Optional<ISoundFactory> getSoundFactory(Identifier factoryLocation);
    ISoundFactory getSoundFactoryOrDefault(Identifier factoryLocation);
    ISoundFactory getSoundFactoryForMusic(Music music);

    Optional<SoundInstance> remapSound(SoundInstance soundInstance);

    /**
     * Returns the remapping selected for the given vanilla sound event and block state, if any.
     * The result carries the primary factory plus any layered accent factories (simultaneous
     * acoustic composition, ported from the original 1.12.2 mcp.json acoustics).
     */
    Optional<SoundRemap> getRemappedSound(SoundEvent event, @Nullable BlockState state);

    record SoundRemap(Identifier factory, List<Identifier> accents) {}

    boolean isBlocked(final Identifier id);
    boolean isCulled(final Identifier id);
    float getVolumeScale(SoundSource category, Identifier id);
    Optional<SoundEvent> getRandomStartupSound();
    Collection<IndividualSoundConfigEntry> getIndividualSoundConfigs();
    void saveIndividualSoundConfigs(Collection<IndividualSoundConfigEntry> configs);
}
