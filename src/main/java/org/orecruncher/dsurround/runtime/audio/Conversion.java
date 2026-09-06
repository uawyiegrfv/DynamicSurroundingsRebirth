package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import org.lwjgl.openal.AL10;
import org.orecruncher.dsurround.mixins.audio.MixinSoundBuffer;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;

public final class Conversion {

    // Derived mono AL buffers keyed by the shared stereo SoundBuffer. Only touched
    // from the sound engine thread (attach tasks), so no synchronization. Weak keys
    // let the stereo data be collected on a sound reload; the derived AL buffer ids
    // themselves are reclaimed when the OpenAL context is destroyed.
    private static final WeakHashMap<SoundBuffer, Integer> MONO_AL_BUFFERS = new WeakHashMap<>();

    /**
     * Returns (deriving on first use) a mono AL buffer for the shared stereo
     * SoundBuffer without touching it. Called at the upload point (attachStaticBuffer)
     * for positioned sounds: OpenAL cannot attenuate a stereo buffer in 3D - it plays
     * glued to the listener at full volume regardless of distance ("ear-glue") - while
     * a mono buffer is localized normally. Returns the AL buffer id, or 0 when a mono
     * flavor is not applicable (already-mono asset, exotic sample format, upload
     * failure); callers then bind the vanilla stereo buffer instead.
     */
    public static int getOrCreateMonoAlBuffer(final SoundBuffer buffer) {
        final Integer cached = MONO_AL_BUFFERS.get(buffer);
        if (cached != null)
            return cached;
        final int al = createMonoAlBuffer(buffer);
        MONO_AL_BUFFERS.put(buffer, al);
        return al;
    }

    private static int createMonoAlBuffer(final SoundBuffer buffer) {
        try {
            final MixinSoundBuffer accessor = (MixinSoundBuffer) buffer;
            final AudioFormat format = accessor.dsurround_getFormat();
            // Already mono (or unknown layout) - the buffer vanilla would bind is the
            // right one; report "nothing to derive" so the vanilla path is used.
            if (format == null || format.getChannels() != 2)
                return 0;
            final int bits = format.getSampleSizeInBits();
            if (bits != 8 && bits != 16)
                return 0;
            final ByteBuffer source = accessor.dsurround_getSample();
            if (source == null)
                return 0;

            // Average L/R per frame into a fresh direct buffer - the shared stereo
            // data stays untouched for the player-facing AL_NONE channels.
            final ByteBuffer src = source.duplicate();
            src.rewind();
            final int frameSize = format.getFrameSize();
            final int frames = src.limit() / frameSize;
            final ByteBuffer mono = ByteBuffer.allocateDirect(frames * (frameSize >> 1)).order(src.order());
            if (bits == 8) {
                for (int i = 0; i < frames; i++) {
                    final int base = i * frameSize;
                    mono.put((byte) ((src.get(base) + src.get(base + 1)) >> 1));
                }
            } else {
                for (int i = 0; i < frames; i++) {
                    final int base = i * frameSize;
                    mono.putShort((short) ((src.getShort(base) + src.getShort(base + 2)) >> 1));
                }
            }
            mono.flip();

            final int al = AL10.alGenBuffers();
            AL10.alBufferData(al, bits == 16 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_MONO8,
                    mono, (int) format.getSampleRate());
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteBuffers(al);
                return 0;
            }
            return al;
        } catch (final Throwable t) {
            MixinHelpers.LOGGER.error(t, "Mono AL buffer derivation failed - falling back to the stereo buffer");
            return 0;
        }
    }
}
