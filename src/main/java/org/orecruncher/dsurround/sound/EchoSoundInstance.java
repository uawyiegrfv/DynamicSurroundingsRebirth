package org.orecruncher.dsurround.sound;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The delayed copy that makes a canyon echo. It is an ordinary positional sound with three extras:
 * a start delay (the reflection's extra travel time, in ticks), a playback position at the
 * reflection point instead of the source, and a low-pass cutoff the pipeline applies so the echo is
 * darker than the direct sound.
 *
 * <p>The delay is carried by the engine's own delay field, so no scheduler is needed and the sound
 * is dropped automatically when the world changes.</p>
 */
public class EchoSoundInstance extends SimpleSoundInstance {

    private final float cutoff;

    public EchoSoundInstance(final ResourceLocation location, final SoundSource source,
                             final float volume, final float pitch, final RandomSource random,
                             final int delayTicks, final double x, final double y, final double z,
                             final float cutoff) {
        super(location, source, volume, pitch, random, false, delayTicks,
                SoundInstance.Attenuation.LINEAR, x, y, z, false);
        this.cutoff = cutoff;
    }

    /** Low-pass multiplier applied to the direct filter: 1 = untouched, 0.2 = heavily dulled. */
    public float cutoff() {
        return this.cutoff;
    }
}
