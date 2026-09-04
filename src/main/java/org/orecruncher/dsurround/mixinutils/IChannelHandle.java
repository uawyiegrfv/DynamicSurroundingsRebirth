package org.orecruncher.dsurround.mixinutils;

import com.mojang.blaze3d.audio.Channel;

public interface IChannelHandle {
    Channel dsurround_getSource();

    /**
     * Reclaims the channel: releases the handle (returns the OpenAL source to the
     * library pool and deregisters any sound-FX context). Implemented by the
     * ChannelHandle mixin; used by the stuck-channel reaper.
     */
    void dsurround_reap();
}
