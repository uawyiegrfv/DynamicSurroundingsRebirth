package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.orecruncher.dsurround.mixinutils.ISoundEngine;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

// 26.1: Library.init was reworked (now takes a DeviceList and builds the OpenAL attribute
// buffer in a separate createAttributes() helper). The enhanced-sound reverb needs 4
// auxiliary sends; modern OpenAL Soft defaults to only 2, so request 4 at context creation
// just like the 1.21.1 build did. If the driver refuses, the effects system degrades to the
// available sends (see Effects.applyReverb).
@Mixin(Library.class)
public class MixinSoundLibrary implements ISoundEngine {

    @Shadow
    private long currentDevice;

    public long dsurround_getDevicePointer() {
        return this.currentDevice;
    }

    @Redirect(method = "init(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V",
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
