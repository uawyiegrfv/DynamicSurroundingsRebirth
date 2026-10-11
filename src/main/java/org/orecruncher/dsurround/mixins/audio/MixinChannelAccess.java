package org.orecruncher.dsurround.mixins.audio;

import net.minecraft.client.sounds.ChannelAccess;
import org.orecruncher.dsurround.runtime.audio.SoundDiag;
import org.orecruncher.dsurround.runtime.audio.SoundFXProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Exposes the end of vanilla's per-tick channel sweep to the stuck-channel reaper.
 * Running after the sweep (on the sound engine thread) means the reaper can safely
 * walk the live channel set and reclaim channels that never reached AL_STOPPED.
 */
@Mixin(ChannelAccess.class)
public abstract class MixinChannelAccess {

    @Shadow
    @Final
    private Set<ChannelAccess.ChannelHandle> channels;

    @Inject(method = "scheduleTick()V", at = @At("TAIL"))
    private void dsurround_afterSweep(CallbackInfo ci) {
        try {
            // The identity Set is typed Set<ChannelHandle>; the reaper treats entries as
            // IChannelHandle (implemented by the ChannelHandle mixin).
            @SuppressWarnings("unchecked")
            Set<Object> raw = (Set<Object>) (Set<?>) this.channels;
            SoundFXProcessor.afterChannelSweep(raw);
        } catch (final Throwable ignore) {
        }
    }

    /**
     * DIAG ONLY (see SoundDiag): samples the engine's channel-pool occupancy once per sound tick
     * (this hook runs once per vanilla channel sweep, about 20 Hz) so the reported peak is not
     * limited to the once-a-second print.
     */
    @Inject(method = "scheduleTick()V", at = @At("HEAD"))
    private void dsurround_poolSample(CallbackInfo ci) {
        try {
            SoundDiag.poolSample();
        } catch (final Throwable ignore) {
        }
    }
}
