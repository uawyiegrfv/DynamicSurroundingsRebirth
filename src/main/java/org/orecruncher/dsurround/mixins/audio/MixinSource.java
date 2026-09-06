package org.orecruncher.dsurround.mixins.audio;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;
import org.orecruncher.dsurround.runtime.audio.Conversion;
import org.orecruncher.dsurround.runtime.audio.SoundFXProcessor;
import org.orecruncher.dsurround.runtime.audio.SourceContext;
import org.orecruncher.dsurround.mixinutils.ISourceContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalInt;

@Mixin(Channel.class)
public class MixinSource implements ISourceContext {

    @Unique
    private SourceContext dsurround_data = null;

    @Shadow
    @Final
    private int source;

    @Override
    public int dsurround_getId() {
        return this.source;
    }

    @Override
    public Optional<SourceContext> dsurround_getData() {
        return Optional.ofNullable(this.dsurround_data);
    }

    @Override
    public void dsurround_setData(@Nullable SourceContext data) {
        this.dsurround_data = data;
    }

    /**
     * Called when the sound is ticked by the sound engine. This will set the sound effect properties for the sound
     * at the time of play.
     * @param ci Ignored
     */
    @Inject(method = "play()V", at = @At("HEAD"))
    public void dsurround_onSourcePlay(CallbackInfo ci) {
        try {
            SoundFXProcessor.onSourcePlay((Channel) ((Object) this));
        } catch(final Throwable t) {
            MixinHelpers.LOGGER.error(t, "Error in dsurround_onSourcePlay()!");
        }
    }

    /**
     * Called when the sound is ticked by the sound engine. This will set the sound effect properties for the sound
     * at the time of tick.
     * @param ci Ignored
     */
    @Inject(method = "updateStream()V", at = @At("HEAD"))
    public void dsurround_onSourceTick(CallbackInfo ci) {
        try {
            SoundFXProcessor.tick((Channel) ((Object) this));
        } catch(final Throwable t) {
            MixinHelpers.LOGGER.error(t, "Error in dsurround_onSourceTick()!");
        }
    }

    /**
     * Called when a sounds stops playing.  Any context information sndctrl has generated will be cleaned up.
     * @param ci Ignored
     */
    @Inject(method = "stop()V", at = @At("HEAD"))
    public void dsurround_onSourceStop(CallbackInfo ci) {
        try {
            SoundFXProcessor.stopSoundPlay((Channel) ((Object) this));
        } catch(final Throwable t) {
            MixinHelpers.LOGGER.error(t, "Error in dsurround_onSourceStop()!");
        }
    }

    /**
     * Selects the OpenAL buffer vanilla binds for a static sound, decided at the last
     * possible moment - the upload point inside attachStaticBuffer:
     * <p>
     * - positioned sounds have AL_LINEAR_DISTANCE as their distance model (the queued
     *   configure task ran earlier on this same executor thread) and OpenAL cannot
     *   attenuate a stereo buffer in 3D - it plays glued to the listener at full
     *   volume ("ear-glue"). They get a derived mono AL buffer instead;
     * <p>
     * - the local player's own sounds play without attenuation (AL_NONE) and keep the
     *   stereo buffer, preserving the stereo image of the asset (user ruling).
     * <p>
     * The shared SoundBuffer itself is never modified: both flavors are served from
     * the same stereo asset, exactly like the 1.12.2 engine did.
     *
     * @param buffer Buffer vanilla is about to bind to this channel
     */
    @WrapOperation(method = "attachStaticBuffer(Lcom/mojang/blaze3d/audio/SoundBuffer;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/SoundBuffer;getAlBuffer()Ljava/util/OptionalInt;"))
    private OptionalInt dsurround_selectAlBuffer(SoundBuffer buffer, Operation<OptionalInt> original) {
        try {
            if (SoundFXProcessor.isMonoSelectionEnabled()
                    && AL10.alGetSourcei(this.source, AL10.AL_DISTANCE_MODEL) == AL11.AL_LINEAR_DISTANCE) {
                final int mono = Conversion.getOrCreateMonoAlBuffer(buffer);
                if (mono != 0)
                    return OptionalInt.of(mono);
            }
        } catch (final Throwable t) {
            MixinHelpers.LOGGER.error(t, "Error in dsurround_selectAlBuffer()!");
        }
        return original.call(buffer);
    }
}
