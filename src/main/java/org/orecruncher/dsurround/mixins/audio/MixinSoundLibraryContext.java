package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.lwjgl.system.MemoryStack;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * Requests 4 auxiliary sends at OpenAL context creation (reverb zones 0..3). 1.20.1's
 * {@code Library.init} passes a null attribute list, so one is built from scratch; if the
 * driver refuses the extra sends the effects system degrades to what the device offers.
 *
 * <p><b>This mixin is vetoed when Sound Physics Remastered is present</b> - see
 * {@code DSurroundMixinPlugin}. That is not a workaround, it is the correct behaviour:
 *
 * <ol>
 *   <li>SPR's own {@code LibraryMixin} is {@code @Mixin(com.mojang.blaze3d.audio.Library)} with a
 *       plain {@code @Redirect} at
 *       {@code @At(INVOKE, target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J")}
 *       and no {@code require = 0}. It AUGMENTS the existing attribute buffer with its own
 *       attributes, so it genuinely needs to own that call.</li>
 *   <li>{@code @Redirect} is exclusive. Whoever applies second cannot find the instruction. Our
 *       first attempt at this used {@code require = 0} to stop <em>ourselves</em> from failing -
 *       that was wrong: we still consumed the instruction, so SPR failed instead and the game
 *       still died at launch with
 *       {@code InjectionError: ... Redirector injected(JLjava/nio/IntBuffer;)J in
 *       sound_physics_remastered.mixins.json:LibraryMixin failed injection check, (0/1) succeeded}.
 *       The only fix that lets both mods load is to not claim the instruction at all.</li>
 *   <li>Nothing is lost by standing down. {@code AudioUtilities} already puts SPR in
 *       {@code autoDisabledBecauseOf}, so with SPR present {@code doEnhancedSounds()} is false and
 *       this redirect would have fallen through to the plain vanilla call anyway - it was a no-op
 *       that happened to be fatal. The device's aux sends come from SPR's context, and
 *       {@code MAX_AUX_SENDS} is read back from the real device, so the reverb adapts.</li>
 *   <li>{@code require = 0} is kept as a second line of defence: if some other mod claims this
 *       instruction, we stand down quietly rather than taking the launch down with us. The
 *       {@code REVERB_INIT ... maxAuxSends=} debug line (enableDebugLogging) reveals the
 *       consequence, so it does not hide silently.</li>
 * </ol>
 *
 * <p>NeoForge builds do not need the veto: NeoForge ships MixinExtras, which rewrites base
 * {@code @Redirect} into a composable wrap operation, so the two mods' hooks coexist there.
 */
@Mixin(Library.class)
public class MixinSoundLibraryContext {

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
