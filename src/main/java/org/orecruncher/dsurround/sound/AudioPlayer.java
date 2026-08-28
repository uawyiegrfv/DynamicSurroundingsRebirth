package org.orecruncher.dsurround.sound;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.orecruncher.dsurround.lib.GameUtils;

public class AudioPlayer implements IAudioPlayer {

    // 1.20.1: DI registers SoundManager with a lazy resolver (GameUtils::getSoundManager);
    // when the container is forced to instantiate early (e.g. QuickSoundVolumeOverlay is
    // resolved during client init) the SoundManager is not ready yet and the constructor
    // would receive null. Resolve lazily on each call instead.
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
