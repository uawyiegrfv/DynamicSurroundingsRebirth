package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Grades the ground splash and the rain sound without reimplementing either.
 *
 * <h2>Why this class and not {@code LevelRenderer}</h2>
 *
 * <p>26.1 moved rain ticking out of {@code LevelRenderer} (which no longer has
 * a {@code tickRain} at all) into
 * {@code WeatherEffectRenderer.tickRainParticles}. The body is still the old
 * one - heightmap sampling over the weather radius, the +/-10 band, the RAIN
 * phase gate that leaves snow silent, lava and campfires to SMOKE, the halving
 * under reduced particles - and it is still the only place the two rain
 * one-shots are fired from. So this is the 26.1 counterpart of 1.20.1's and
 * 1.21.1's {@code MixinLevelRendererWeather}, targeting the method that moved.</p>
 *
 * <h2>Why the hook answers false</h2>
 *
 * <p>The NeoForge custom-renderer hook at the top of the method is
 * {@code if (renderer != null && renderer.tickRain(...)) return;}: true means
 * vanilla stops, false means vanilla carries on. We want vanilla to carry on -
 * that is where the splash positions and the sound come from - so
 * {@code PrecipitationRenderer.GradedPrecipitation.tickRain} answers false, and
 * the grading happens here, from the inside. (Offsets: {@code ifeq 29} at 25,
 * {@code return} at 28.)</p>
 *
 * <h2>Why {@code require = 0}</h2>
 *
 * <p>The config sets {@code defaultRequire: 1}, and {@code @Redirect} is
 * exclusive - if another mod has already taken one of these call sites, one of
 * us loses, and losing with {@code required: true} kills the client during
 * startup. Failing to inject costs us the graded rain sound on this version and
 * nothing else, so we take that.</p>
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer {

    @Redirect(
            method = "tickRainParticles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_splashRainLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.splashRainLevel(level, partialTick);
    }

    // Both rain one-shots - WEATHER_RAIN_ABOVE at 0.1 and WEATHER_RAIN at 0.2 -
    // are the same call with the same descriptor, so one handler takes them both
    // and reads the volume off the argument. No ordinal needed, and none wanted:
    // pinning one would silently drop the other.
    @Redirect(
            method = "tickRainParticles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            require = 0)
    private void dsurround_rainSound(ClientLevel level, BlockPos pos, SoundEvent event,
                                     SoundSource source, float volume, float pitch, boolean global) {
        final boolean played;
        SoundEvent chosen = event;
        float gain = 1F;
        if (!PrecipitationIntensity.ownsAmbient()) {
            played = true;
        } else if (ThreadLocalRandom.current().nextFloat() < PrecipitationIntensity.rainAudioDensity()) {
            // Which clip this one drop is. See nextRainAudioHeavy(): the blend
            // is a choice made per one-shot - dithered, not drawn - so the stack
            // cross-fades between the calm and the heavy texture without a
            // second voice and without a filter ever being involved.
            if (!PrecipitationIntensity.nextRainAudioHeavy()) {
                chosen = PrecipitationIntensity.rainCalmSound();
                gain = PrecipitationIntensity.rainCalmGain();
            }
            // Clamped: the calm clip is played several times louder than the
            // vanilla one because it was recorded that much quieter, and a
            // volume above 1 buys nothing but clipping.
            volume = Math.min(1F, volume * gain * PrecipitationIntensity.rainAudioVolumeScale());
            played = true;
        } else {
            played = false;
        }
        PrecipitationIntensity.noteRainAudio(played);
        if (played)
            level.playLocalSound(pos, chosen, source, volume, pitch, global);
    }
}
