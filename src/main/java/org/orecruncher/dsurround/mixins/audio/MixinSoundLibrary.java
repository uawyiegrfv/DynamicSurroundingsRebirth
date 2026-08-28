package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.lwjgl.system.MemoryStack;
import org.orecruncher.dsurround.mixinutils.ISoundEngine;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * 1.20.1: exposes the OpenAL device pointer (ISoundEngine) and requests 4 auxiliary
 * sends at context creation (reverb zones 0..3). 1.20.1's Library.init passes a null
 * attribute list, so build one from scratch; if the driver refuses the extra sends the
 * effects system degrades to the available sends.
 */
@Mixin(Library.class)
public class MixinSoundLibrary implements ISoundEngine {

    @Shadow
    private long currentDevice;

    public long dsurround_getDevicePointer() {
        return this.currentDevice;
    }

    @Redirect(method = "init(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long dsurround_createContextWithAuxSends(long device, IntBuffer attrList) {
        if (AudioUtilities.doEnhancedSounds()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 1.20.1 passes null. Build the capability list from scratch, matching the
                // Fabric 1.20.1 reference: enable the SOFT output limiter AND request 4 aux
                // sends (reverb zones 0..3). Without the output-limiter attribute the context
                // behaves like a bare default context on some OpenAL Soft builds.
                IntBuffer newAttr = stack.callocInt(5);
                newAttr.put(SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT).put(ALC10.ALC_TRUE);
                newAttr.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4);
                newAttr.put(0);
                newAttr.flip();

                long context = ALC10.alcCreateContext(device, newAttr);
                if (context != 0L)
                    return context;
            }
        }

        // Fall back to the vanilla attribute list (may be null) if the driver refused.
        return ALC10.alcCreateContext(device, attrList);
    }
}
