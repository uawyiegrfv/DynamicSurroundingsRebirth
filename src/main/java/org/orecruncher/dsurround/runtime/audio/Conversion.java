package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.SoundBuffer;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

@SuppressWarnings("unused")
public final class Conversion {

    private static Field formatField;
    private static Field dataField;

    static {
        formatField = findField("format", "f_83794_");
        dataField = findField("data", "f_83793_");
    }

    // mojmap name (dev) or SRG name (production obfuscated)
    private static Field findField(String mojmap, String srg) {
        for (var f : SoundBuffer.class.getDeclaredFields()) {
            if (f.getName().equals(mojmap) || f.getName().equals(srg)) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /**
     * Converts the AudioStreamBuffer into mono if needed.
     * 1.20.1: SoundBuffer keeps data/format private and the accessor mixin (MixinSoundBuffer)
     * cannot be class-loaded from the Sound engine thread (IllegalClassLoadError), so the
     * fields are read/written reflectively instead.
     *
     * @param buffer Audio stream buffer to convert
     */
    public static void convert(final SoundBuffer buffer) {
        if (formatField == null || dataField == null)
            return;
        try {
            final AudioFormat format = (AudioFormat) formatField.get(buffer);

            // If it is already mono return original buffer
            if (format.getChannels() == 1)
                return;

            // If the sample size is not 8 or 16 bits just return the original
            int bits = format.getSampleSizeInBits();
            if (bits != 8 && bits != 16)
                return;

            // Do the conversion.  Essentially, it averages the values in the source buffer based on the sample size.
            boolean bigendian = format.isBigEndian();
            final AudioFormat monoformat = new AudioFormat(
                    format.getEncoding(),
                    format.getSampleRate(),
                    bits,
                    1, // Mono - single channel
                    format.getFrameSize() >> 1,
                    format.getFrameRate(),
                    bigendian);

            final ByteBuffer source = (ByteBuffer) dataField.get(buffer);
            if (source == null) {
                return;
            }

            final int sourceLength = source.limit();
            final int skip = format.getFrameSize();
            for (int i = 0; i < sourceLength; i += skip) {
                final int targetIdx = i >> 1;
                if (bits == 8) {
                    final int c1 = source.get(i) >> 1;
                    final int c2 = source.get(i + 1) >> 1;
                    final int v = c1 + c2;
                    source.put(targetIdx, (byte) v);
                } else {
                    final int c1 = source.getShort(i) >> 1;
                    final int c2 = source.getShort(i + 2) >> 1;
                    final int v = c1 + c2;
                    source.putShort(targetIdx, (short) v);
                }
            }

            // Patch up the old object
            formatField.set(buffer, monoformat);
            source.rewind();
            source.limit(sourceLength >> 1);
        } catch (ReflectiveOperationException ignored) {
            // SoundBuffer layout changed - give up on mono conversion.
        }
    }
}
