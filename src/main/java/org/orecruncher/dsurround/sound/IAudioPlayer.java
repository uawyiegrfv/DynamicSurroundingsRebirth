package org.orecruncher.dsurround.sound;

import net.minecraft.client.resources.sounds.SoundInstance;

public interface IAudioPlayer {

    void play(SoundInstance sound);

    void stop(SoundInstance sound);

    void stopAll();

    boolean isPlaying(SoundInstance sound);

    /**
     * Whether the sound engine is currently able to accept a play. False while it is torn down or
     * rebuilt (resource reload, audio device change, enhanced-sounds toggle) and while the OpenAL
     * device is disconnected. Playing into it in that window is what upstream 0.4.4 added this guard
     * for; see {@link AudioPlayer#isSoundSystemAvailable()}.
     */
    boolean isSoundSystemAvailable();
}
