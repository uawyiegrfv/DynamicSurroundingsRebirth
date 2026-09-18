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
 *
 * <p><b>{@code require = 0} is deliberate.</b> Sound Physics Remastered's Forge 1.20.1 build
 * redirects the <em>same instruction</em> - its {@code LibraryMixin} is
 * {@code @Mixin(com.mojang.blaze3d.audio.Library)} with a {@code @Redirect} at
 * {@code @At(INVOKE, target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J")}
 * (verified by reading that class's constant pool). {@code @Redirect} is exclusive: whichever mod
 * applies second can no longer find the target instruction, and with {@code "required": true}
 * plus {@code defaultRequire: 1} that is a <em>launch crash</em>, not a degradation. Neither mod
 * bundles MixinExtras on Forge 1.20.1, so nothing converts the redirects into composable wrap
 * operations. The mod advertises Sound Physics Remastered as a soft integration, so shipping a
 * combination that cannot start is worse than shipping one where the aux-send request loses:
 * with {@code require = 0} our context setup is simply skipped when another mod claimed the
 * instruction, and the reverb zones fall back to whatever the device offers - SPR replaces the
 * reverb system wholesale anyway. (The NeoForge builds need no such guard: NeoForge ships
 * MixinExtras, which rewrites {@code @Redirect} into a composable wrap operation.)
 *
 * <p>If this ever stops applying on a build without Sound Physics Remastered, the
 * {@code REVERB_INIT ... maxAuxSends=} debug line (enableDebugLogging) shows the regressed
 * aux-send count.
 */
@Mixin(Library.class)
public class MixinSoundLibrary implements ISoundEngine {

    @Shadow
    private long currentDevice;

    public long dsurround_getDevicePointer() {
        return this.currentDevice;
    }

    @Redirect(require = 0, method = "init(Ljava/lang/String;Z)V",
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
