package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import org.orecruncher.dsurround.mixinutils.ISoundEngine;
import org.orecruncher.dsurround.runtime.audio.SoundDiag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exposes the OpenAL device pointer so {@code AudioUtilities} can query the device
 * (it casts the engine to {@link ISoundEngine} unconditionally, so this part must
 * ALWAYS be applied).
 *
 * <p>The context-creation hooks that used to live here have been split out into
 * {@link MixinSoundLibraryContext}. That split exists because the two halves have
 * completely different collision profiles: this accessor is additive and cannot
 * conflict with anything, while the other one touches the
 * {@code ALC10.alcCreateContext} call that Sound Physics Remastered also touches.
 */
@Mixin(Library.class)
public class MixinSoundLibrary implements ISoundEngine {

    @Shadow
    private long currentDevice;

    public long dsurround_getDevicePointer() {
        return this.currentDevice;
    }

    /**
     * DIAG ONLY (see SoundDiag): counts the engine's own "the pool is full" signal.
     *
     * <p>{@code Library.acquireChannel} returns null exactly when the requested pool has no free
     * slot (or, far more rarely, when OpenAL refuses to mint a source). vanilla logs that only
     * when {@code SharedConstants.IS_RUNNING_IN_IDE}, so without this counter a stream pool that
     * has run dry is invisible in a normal session - the sound simply never starts.
     */
    @Inject(method = "acquireChannel", at = @At("RETURN"))
    private void dsurround_onAcquireChannel(final Library.Pool pool, final CallbackInfoReturnable<Channel> cir) {
        try {
            SoundDiag.poolAcquire(pool == Library.Pool.STREAMING, cir.getReturnValue() != null);
        } catch (final Throwable ignore) {
            // diagnostics must never be able to break sound allocation
        }
    }
}
