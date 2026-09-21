package org.orecruncher.dsurround.sound;

import net.minecraft.client.resources.sounds.SoundInstance;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;

/**
 * Logging wrapper around {@link AudioPlayer}, registered as the {@code IAudioPlayer} singleton so the
 * play/stop traffic of the whole mod can be traced from one place. Everything is gated behind
 * {@link Configuration.Flags#AUDIO_PLAYER}, so with debug logging off this is a pass-through and the
 * only cost is one extra virtual call per play/stop.
 *
 * <p>Ported from the 1.21.1/26.1 builds, which have had it since the port; 1.20.1 previously registered
 * {@link AudioPlayer} directly, so the three builds now expose the same debug surface.
 */
public class AudioPlayerDebug extends AudioPlayer {

    private final IModLog logger;

    public AudioPlayerDebug(IModLog logger) {
        this.logger = ModLog.createChild(logger, "AudioPlayer");
    }

    @Override
    public void play(SoundInstance sound) {
        this.logger.debug(Configuration.Flags.AUDIO_PLAYER, () -> String.format("PLAYING %s", formatSound(sound)));
        super.play(sound);
    }

    @Override
    public void stop(SoundInstance sound) {
        this.logger.debug(Configuration.Flags.AUDIO_PLAYER, () -> String.format("STOPPING %s", formatSound(sound)));
        super.stop(sound);
    }

    @Override
    public void stopAll() {
        this.logger.debug(Configuration.Flags.AUDIO_PLAYER, "STOPPING all sounds");
        super.stopAll();
    }

    protected String formatSound(SoundInstance sound) {
        return AudioUtilities.debugString(sound);
    }
}