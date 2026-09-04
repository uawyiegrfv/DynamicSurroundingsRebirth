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
        org.orecruncher.dsurround.runtime.audio.AudioUtilities.captureSoundEngine((SoundEngine) (Object) this);
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
     * Hook DURING play(), immediately after the channel-configuration task has been queued
     * to the sound engine but BEFORE the buffer-load continuation is submitted. For sounds
     * whose decoded buffer is already cached, vanilla's thenAccept continuation runs
     * synchronously on the play() caller and hands Channel.attachStaticBuffer to the sound
     * engine thread right away; a RETURN-injection would race that thread and frequently
     * lose, leaving the SourceContext unattached at attachStaticBuffer time. doMonoConversion
     * then silently skipped the stereo->mono conversion and DS footsteps played glued to the
     * ear. Injecting here closes that race: the instanceToChannel entry is already present
     * (vanilla populates it before queueing the configuration) and no buffer attach can have
     * been queued yet.
     */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V", shift = At.Shift.AFTER))
    private void dsurround_onSoundPlay(SoundInstance sound, CallbackInfo ci) {
        try {
            var handle = ((MixinSoundEngineAccessor) (Object) this).dsurround_getSources().get(sound);
            if (handle != null) {
                org.orecruncher.dsurround.runtime.audio.SoundFXProcessor.onSoundPlay(sound, handle);
                // Eager shared-buffer conversion (second safety net against the
                // intermittent ear-glue - see SoundFXProcessor.convertSharedBuffer).
                if (org.orecruncher.dsurround.runtime.audio.SoundFXProcessor.shouldConvertToMono(sound)) {
                    final var snd = sound.getSound();
                    if (snd != null) {
                        final String path = snd.getPath().toString();
                        ((MixinSoundEngineAccessor) (Object) this).dsurround_getSoundBuffers()
                                .getCompleteBuffer(snd.getPath())
                                .thenAccept(buffer -> org.orecruncher.dsurround.runtime.audio.SoundFXProcessor
                                        .convertSharedBuffer(buffer, path));
                    }
                }
            }
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
