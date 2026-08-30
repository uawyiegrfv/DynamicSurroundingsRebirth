package org.orecruncher.dsurround.sound;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.orecruncher.dsurround.lib.GameUtils;

public class AudioPlayer implements IAudioPlayer {

    // Resolved lazily on each call instead of constructor injection: the DI container
    // can force this singleton early (e.g. QuickSoundVolumeOverlay during client init)
    // before the SoundManager exists, and a constructor-injected field would then be
    // null for the lifetime of the object. Mirrors the 1.20.1 lineage fix.
    private static SoundManager manager() {
        return GameUtils.getSoundManager();
    }

    @Override
    public void play(SoundInstance sound) {
        manager().play(sound);
    }

    @Override
    public void stop(SoundInstance sound) {
        manager().stop(sound);
    }

    @Override
    public void stopAll() {
        manager().stop();
    }

    @Override
    public boolean isPlaying(SoundInstance sound) {
        return manager().isActive(sound);
    }
}
