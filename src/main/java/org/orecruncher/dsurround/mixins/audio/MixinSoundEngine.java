package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.orecruncher.dsurround.sound.SoundInstanceHandler;
import org.orecruncher.dsurround.sound.SoundVolumeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine {

    @Final
    @Shadow
    private Library library;

    @Inject(method = "loadLibrary()V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Library;init(Ljava/lang/String;Z)V", shift = At.Shift.AFTER))
    public void dsurround_init(CallbackInfo ci) {
        // Spatial audio: initialise OpenAL EFX (aux sends, effects) and sound-processing.
        org.orecruncher.dsurround.runtime.audio.AudioUtilities.initialize(this.library);
    }

    @Inject(method = "destroy()V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Library;cleanup()V", shift = At.Shift.BEFORE))
    public void dsurround_deinit(CallbackInfo ci) {
        org.orecruncher.dsurround.runtime.audio.AudioUtilities.deinitialize(this.library);
    }

    /**
     * 1.20.1: SoundEngine.play returns void (no PlayResult). HEAD cancellation
     * blocks or remaps the play.
     */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void dsurround_play(SoundInstance sound, CallbackInfo ci) {
        try {
            // Check to see if the sound is blocked or being culled
            if (SoundInstanceHandler.shouldBlockSoundPlay(sound))
                ci.cancel();
            // Attempt a remapping if configured to do so
            if (SoundInstanceHandler.remapSoundPlay(sound))
                ci.cancel();
        } catch (Exception t) {
            MixinHelpers.LOGGER.error(t, "Error in dsurround_play()!");
        }
    }

    /**
     * Hook after a sound has been queued to the engine: spatial-audio processing
     * (SoundFXProcessor - reverb / low-pass / occlusion) using the instanceToChannel
     * accessor.
     */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("RETURN"))
    private void dsurround_onSoundPlay(SoundInstance sound, CallbackInfo ci) {
        try {
            var handle = ((MixinSoundEngineAccessor) (Object) this).dsurround_getSources().get(sound);
            if (handle != null)
                org.orecruncher.dsurround.runtime.audio.SoundFXProcessor.onSoundPlay(sound, handle);
        } catch (Throwable ex) {
            MixinHelpers.LOGGER.error(ex, "Error processing sound FX");
        }
    }

    /**
     * Prune sounds the player will not hear, before any channel allocation or
     * per-channel DSP (reverb sends / occlusion low-pass). Both the audio listener
     * and the local player's live eye position are checked (see
     * SoundInstanceHandler.outOfRange) so sounds that land together with a
     * long-distance player teleport are not lost.
     */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;<init>(DDD)V"), cancellable = true)
    private void dsurround_soundRangeCheck(SoundInstance soundInstance, CallbackInfo ci) {
        if (MixinHelpers.soundSystemConfig.enableSoundPruning) {
            if (SoundInstanceHandler.outOfRange(soundInstance, 4)) {
                MixinHelpers.LOGGER.debug(Configuration.Flags.BASIC_SOUND_PLAY, () -> "TOO FAR: " + AudioUtilities.debugString(soundInstance));
                ci.cancel();
            }
        }
    }

    /**
     * Redirect the play() internal volume calculation so the SoundInstance reference is
     * available for per-category config scaling. Fallback to the original float on error
     * (no @Invoker - the AP cannot resolve it in this environment).
     */
    @Redirect(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float dsurround_playGetAdjustedVolume(SoundEngine instance, float f, SoundSource soundSource, SoundInstance sound) {
        try {
            return SoundVolumeEvaluator.getAdjustedVolume(sound);
        } catch (Throwable ex) {
            MixinHelpers.LOGGER.error(ex, "Error calculating sound volume");
        }
        return f;
    }

    /**
     * Update the volume based on current settings and environment (SoundVolumeEvaluator:
     * per-category config scaling). Restored from 26.1 - without this the volumes are
     * notably louder than the 26.1 build.
     */
    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("HEAD"), cancellable = true)
    private void dsurround_calculateVolume(SoundInstance soundInstance, CallbackInfoReturnable<Float> cir) {
        try {
            cir.setReturnValue(SoundVolumeEvaluator.getAdjustedVolume(soundInstance));
        } catch (Throwable ex) {
            MixinHelpers.LOGGER.error(ex, "Error calculating sound volume");
        }
    }

}
