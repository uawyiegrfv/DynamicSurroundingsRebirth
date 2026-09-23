package org.orecruncher.dsurround.mixins.audio;

import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.Listener;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(SoundEngine.class)
public interface MixinSoundEngineAccessor {

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> dsurround_getSources();

    @Accessor("soundBuffers")
    net.minecraft.client.sounds.SoundBufferLibrary dsurround_getSoundBuffers();

    @Accessor("listener")
    Listener dsurround_getListener();

    /** Whether the engine finished loading. Used to avoid playing into a torn-down engine. */
    @Accessor("loaded")
    boolean dsurround_isLoaded();

    /** The OpenAL library, so the device-disconnected state can be queried. */
    @Accessor("library")
    Library dsurround_getLibrary();
}
