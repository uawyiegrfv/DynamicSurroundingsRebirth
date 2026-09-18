package org.orecruncher.dsurround.mixins.audio;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.nio.IntBuffer;

/**
 * Requests 4 auxiliary sends at OpenAL context creation (reverb zones 0..3), because modern
 * OpenAL Soft defaults to only 2 and the long-reverb zones need sends 2/3. If the driver
 * refuses, the effects system degrades to the available sends (see Effects.applyReverb).
 *
 * <p><b>This mixin is vetoed when Sound Physics Remastered is present</b> - see
 * {@code DSurroundMixinPlugin}.
 *
 * <p>On NeoForge this is not strictly required for stability: NeoForge ships MixinExtras, which
 * rewrites base {@code @Redirect} / {@code @ModifyArgs} into composable wrap operations, so our
 * {@code @WrapOperation} and SPR's hooks would coexist here (that is why the 1.20.1 build - which
 * has no MixinExtras - was the one that crashed with
 * {@code InjectionError: ... LibraryMixin failed injection check}). It is done anyway for two
 * reasons: the three repo copies stay structurally identical, which is the whole point of the
 * drift work; and a future Sound Physics build for a newer version could reach for a mechanism
 * that does not compose.
 *
 * <p>Nothing is lost by standing down: {@code AudioUtilities} already puts SPR in
 * {@code autoDisabledBecauseOf}, so with SPR present {@code doEnhancedSounds()} is false and both
 * hooks below fall through to the plain vanilla behaviour. Verified in the 1.20.1 run log:
 * {@code Enhanced sound processing is auto disabled due to the presence of the mod
 * "sound_physics_remastered"}.
 */
@Mixin(Library.class)
public class MixinSoundLibraryContext {

    /**
     * This will resize the capability buffer to accommodate additional settings
     */
    @ModifyConstant(method = "init(Ljava/lang/String;Z)V", constant = @Constant(intValue = 3))
    private int dsurround_modifyIntBufferSize(int size) {
        return AudioUtilities.doEnhancedSounds() ? 5 : 3;
    }

    /**
     * Rewrite the capability buffer.  We only do this if advanced processing is enabled.
     */
    @WrapOperation(method = "init(Ljava/lang/String;Z)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long dsurround_buildCapabilities(long deviceHandle, IntBuffer attrList, Operation<Long> original) {
        if (AudioUtilities.doEnhancedSounds()) {
            // Buffer should have been resized by the constant modification above
            attrList.clear();
            // From the original code
            attrList.put(SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT).put(ALC10.ALC_TRUE);
            // Increase sends channels
            attrList.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4);
            // Done!
            attrList.put(0);
            attrList.flip();
            return ALC10.alcCreateContext(deviceHandle, attrList);
        } else {
            return original.call(deviceHandle, attrList);
        }
    }
}
