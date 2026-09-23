package org.orecruncher.dsurround.sound;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.orecruncher.dsurround.mixins.audio.MixinSoundEngineAccessor;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;

public class AudioPlayer implements IAudioPlayer {

    private final SoundManager manager;

    public AudioPlayer(SoundManager manager) {
        this.manager = manager;
    }

    @Override
    public void play(SoundInstance sound) {
        // Never hand a sound to an engine that is not there. The engine is torn down and rebuilt on a
        // resource reload, an audio device change and the enhanced-sounds toggle, and a play landing in
        // that window goes into a manager whose OpenAL device is already gone.
        if (!isSoundSystemAvailable())
            return;
        this.manager.play(sound);
    }

    @Override
    public void stop(SoundInstance sound) {
        this.manager.stop(sound);
    }

    @Override
    public void stopAll() {
        this.manager.stop();
    }

    @Override
    public boolean isPlaying(SoundInstance sound) {
        return this.manager.isActive(sound);
    }

    @Override
    public boolean isSoundSystemAvailable() {
        final SoundEngine engine = AudioUtilities.getSoundSystem();
        if (engine == null)
            return false;
        final MixinSoundEngineAccessor accessor = (MixinSoundEngineAccessor) engine;
        if (!accessor.dsurround_isLoaded())
            return false;
        final Library library = accessor.dsurround_getLibrary();
        return library != null && !library.isCurrentDeviceDisconnected();
    }
}
