package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * 26.1: {@code Library.init} was reworked (now takes a DeviceList and builds the OpenAL
 * attribute buffer in a separate {@code createAttributes()} helper). The enhanced-sound
 * reverb needs 4 auxiliary sends; modern OpenAL Soft defaults to only 2, so request 4 at
 * context creation just like the 1.21.1 build did. If the driver refuses, the effects
 * system degrades to the available sends (see Effects.applyReverb).
 *
 * <p><b>This mixin is vetoed when Sound Physics Remastered is present</b> - see
 * {@code DSurroundMixinPlugin}. That is not a workaround, it is the correct behaviour:
 *
 * <ol>
 *   <li>SPR's own {@code LibraryMixin} also hooks {@code ALC10.alcCreateContext} inside
 *       {@code Library.init}, and base {@code @Redirect} is exclusive. Our first attempt used
 *       {@code require = 0} so that <em>we</em> would not fail - that was wrong: we still consumed
 *       the instruction, so SPR failed instead and the 1.20.1 game died at launch with
 *       {@code InjectionError: ... sound_physics_remastered.mixins.json:LibraryMixin failed
 *       injection check, (0/1) succeeded}. Not claiming the instruction is the only fix that lets
 *       both mods load.</li>
 *   <li>Nothing is lost: {@code AudioUtilities} already puts SPR in {@code autoDisabledBecauseOf},
 *       so with SPR present {@code doEnhancedSounds()} is false and this redirect would have fallen
 *       through to the plain vanilla call anyway. Confirmed in the 1.20.1 run log:
 *       {@code Enhanced sound processing is auto disabled due to the presence of the mod
 *       "sound_physics_remastered"}. The device's aux sends then come from SPR's context, and
 *       {@code MAX_AUX_SENDS} is read back from the real device, so the reverb adapts.</li>
 *   <li>{@code require = 0} is kept as a second line of defence: if some other mod claims this
 *       instruction, we stand down quietly rather than taking the launch down with us. The
 *       {@code REVERB_INIT ... maxAuxSends=} debug line (enableDebugLogging) reveals the
 *       consequence, so it does not hide silently.</li>
 * </ol>
 *
 * <p>NeoForge ships MixinExtras, which normally rewrites base {@code @Redirect} into a composable
 * wrap operation, so this build would likely survive a collision anyway; the veto is kept so the
 * three repo copies behave identically and so a future SPR build cannot surprise us.
 */
@Mixin(Library.class)
public class MixinSoundLibraryContext {

    @Redirect(require = 0, method = "init(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long dsurround_createContextWithAuxSends(long device, IntBuffer attrList) {
        if (AudioUtilities.doEnhancedSounds()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Copy the vanilla attributes (HRTF, mono sources), then request 4 aux sends.
                IntBuffer newAttr = stack.callocInt(attrList.remaining() + 3);
                attrList.rewind();
                while (attrList.hasRemaining()) {
                    int key = attrList.get();
                    if (key == 0)
                        break;
                    newAttr.put(key).put(attrList.get());
                }
                newAttr.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4);
                newAttr.put(0);
                newAttr.flip();

                long context = ALC10.alcCreateContext(device, newAttr);
                if (context != 0L)
                    return context;
            }
        }

        // Fall back to the vanilla attribute list if the driver refused the extra sends.
        attrList.rewind();
        return ALC10.alcCreateContext(device, attrList);
    }
}
