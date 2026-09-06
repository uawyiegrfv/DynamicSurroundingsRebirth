package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import org.lwjgl.openal.AL10;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.WeakHashMap;

public final class Conversion {

    // 1.20.1: SoundBuffer keeps data/format/getAlBuffer non-public and the accessor
    // mixin (MixinSoundBuffer) must not be class-loaded from the sound engine thread
    // (IllegalClassLoadError), so everything is read reflectively instead.
    // mojmap name (dev) or SRG name (production obfuscated).
    private static Field formatField;
    private static Field dataField;
    private static Method getAlBufferMethod;
    private static boolean vanillaWarned;

    static {
        formatField = findField("format", "f_83794_");
        dataField = findField("data", "f_83793_");
        getAlBufferMethod = findMethod("getAlBuffer", "m_83800_");
        if (formatField == null || dataField == null || getAlBufferMethod == null)
            MixinHelpers.LOGGER.warn("Conversion: SoundBuffer members not found (mapping changed?) - stereo positioned sounds will not be localized");
    }

    private static Field findField(String mojmap, String srg) {
        for (var f : SoundBuffer.class.getDeclaredFields()) {
            if (f.getName().equals(mojmap) || f.getName().equals(srg)) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Method findMethod(String mojmap, String srg) {
        for (var m : SoundBuffer.class.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == OptionalInt.class
                    && (m.getName().equals(mojmap) || m.getName().equals(srg))) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // Derived mono AL buffers keyed by the shared stereo SoundBuffer. Only touched
    // from the sound engine thread (attach tasks), so no synchronization. Weak keys
    // let the stereo data be collected on a sound reload; the derived AL buffer ids
    // themselves are reclaimed when the OpenAL context is destroyed.
    private static final WeakHashMap<SoundBuffer, Integer> MONO_AL_BUFFERS = new WeakHashMap<>();

    /**
     * Returns the AL buffer id vanilla would bind for the given SoundBuffer (uploading
     * it on first use). Fallback for every channel that does not get the derived mono
     * flavor - the local player's own sounds (distance model AL_NONE, the stereo image
     * of the asset is wanted) and any sound whose mono derivation returned nothing.
     */
    public static OptionalInt getVanillaAlBuffer(final SoundBuffer buffer) {
        if (getAlBufferMethod != null) {
            try {
                return (OptionalInt) getAlBufferMethod.invoke(buffer);
            } catch (final Throwable t) {
                getAlBufferMethod = null;
                if (!vanillaWarned) {
                    vanillaWarned = true;
                    MixinHelpers.LOGGER.error(t, "Unable to invoke SoundBuffer.getAlBuffer() - static sounds will be silent");
                }
            }
        }
        return OptionalInt.empty();
    }

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
            final AudioFormat format = formatField == null ? null : (AudioFormat) formatField.get(buffer);
            // Already mono (or unknown layout) - the buffer vanilla would bind is the
            // right one; report "nothing to derive" so the vanilla path is used.
            if (format == null || format.getChannels() != 2)
                return 0;
            final int bits = format.getSampleSizeInBits();
            if (bits != 8 && bits != 16)
                return 0;
            final ByteBuffer source = dataField == null ? null : (ByteBuffer) dataField.get(buffer);
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
