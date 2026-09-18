package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import org.orecruncher.dsurround.mixinutils.ISoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes the OpenAL device pointer so {@code AudioUtilities} can query the device
 * (it casts the engine to {@link ISoundEngine} unconditionally, so this part must
 * ALWAYS be applied).
 *
 * <p>The context-creation hook that used to live here has been split out into
 * {@link MixinSoundLibraryContext}. That split exists because the two halves have
 * completely different collision profiles: this accessor is additive and cannot
 * conflict with anything, while the other one claims an instruction that Sound
 * Physics Remastered also claims.
 */
@Mixin(Library.class)
public class MixinSoundLibrary implements ISoundEngine {

    @Shadow
    private long currentDevice;

    public long dsurround_getDevicePointer() {
        return this.currentDevice;
    }
}
