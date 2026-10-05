package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.processing.weather.SkyEventScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the rain-driven <em>colour</em> of the fog.
 *
 * <p>{@code FogRenderer.setupColor} reads the rain level twice over. It starts
 * from {@code ClientLevel.getSkyColor}, which is already rain-desaturated and is
 * already ours via {@link MixinClientLevelWeatherColors}, and then applies its
 * own second pass:</p>
 *
 * <pre>
 * float f1 = 1 - rain * 0.5F;   // red and green
 * float f2 = 1 - rain * 0.4F;   // blue, dropped less
 * fogRed *= f1; fogGreen *= f1; fogBlue *= f2;
 * </pre>
 *
 * <p>So rain does not merely darken fog, it warms it - blue falls less than red
 * and green. Redirecting only the sky colour would leave this second pass on
 * vanilla's number, and the result would be our desaturation fighting vanilla's
 * warm shift. Both routes have to read the same curve, which is what this does.
 * </p>
 *
 * <p>The channel is {@code FOG_COLOR}, derived from {@code FOG_DIST} with no
 * delay at all: distance and colour are two readings of the same air, and
 * letting them drift apart produces thick fog tinted like a clear day.</p>
 * <p>The handler is <b>static</b> because {@code setupColor} is static. A
 * {@code @Redirect} handler has to match the staticness of the method it sits in,
 * not of the call it rewrites - the handler here is static while the call it
 * replaces ({@code ClientLevel.getRainLevel}) is an instance call, and that is
 * correct. Get this wrong and the mixin fails to apply at startup, which is
 * fatal, so it is worth stating explicitly.</p>
 */
@Mixin(value = FogRenderer.class, priority = 900)
public abstract class MixinFogRendererWeatherColor {

    @Redirect(
            method = "setupColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private static float dsurround_fogColorRainLevel(ClientLevel level, float partialTick) {
        PrecipitationIntensity.noteFogColorHook();
        return PrecipitationIntensity.fogColorRainLevel(level, partialTick);
    }

    /**
     * The other half of vanilla's weather pass, which this mixin used to leave
     * on vanilla's own number. {@code setupColor} runs two independent channels
     * off two independent levels - a rain channel (red and green towards 0.5,
     * blue towards 0.6) and then a thunder channel (all three towards 0.5) -
     * and it is the second one that lets a real thunderstorm's fog go near
     * black. Taking over only the rain read meant our own heavy weather got
     * half the treatment and stopped there: bright biome fog times one 0.5,
     * which is precisely the maintainer's "rain paints everything white, a
     * real thunderstorm looks right".
     *
     * <p>Fed from {@link PrecipitationIntensity#stormRainLevel}, the same level
     * {@link MixinClientLevelWeatherColors} hands to
     * {@code ClientLevel.getSkyDarken} - so the fog now grades together with
     * the darkening the world, the rain streaks and the clouds already follow,
     * instead of meeting it only by accident. While we do not own the weather
     * the call returns vanilla's thunder level untouched, and the redirect is
     * {@code require = 0}, so a mapping change degrades to vanilla rather than
     * failing at startup.</p>
     *
     * <p>The level it feeds is <b>not</b> plain {@code stormRainLevel}: STORM
     * is ATMOSPHERE^4, so at anything short of a real thunderstorm it sits
     * near zero and this pass contributes nothing - bright biome fog times the
     * rain pass alone, which is the "rain paints everything white" reading. It
     * now takes the larger of that and
     * {@link PrecipitationIntensity#fogColorStormLevel}, an intensity curve of
     * its own, so heavy rain grades in long before it thunders and still tops
     * out at vanilla's own storm darkness.</p>
     */
    @Redirect(
            method = "setupColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),
            require = 0)
    private static float dsurround_fogColorThunderLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.fogColorStormLevel(level, partialTick);
    }

    // ---- the overcast grey on the final fog colour ---------------------------
    //
    // The fog colour's BASE comes from the biome fog (the CubicSampler lambda
    // inside setupColor), not from the sky colour, so the grey the sky gets in
    // MixinClientLevelWeatherColors never reaches it and the horizon stays blue
    // under a grey sky - the maintainer's "远处还是蓝色的". setupColor runs once
    // per frame and settles ONE colour for the sky fog and the terrain fog
    // alike, so lifting its settled value here re-tints both in the same move,
    // with the same target function and the same channel the sky uses - the
    // sync is by construction, not by matched clocks. The small overcast feed
    // into the rain pass above (see fogColorRainLevel) is vanilla's own
    // arithmetic joining in; this tail pass is the part that matches the sky.

    @Inject(method = "setupColor", at = @At("TAIL"), require = 0)
    private static void dsurround_overcastFog(CallbackInfo ci) {
        final float overcast = SkyEventScheduler.whiteShiftStrength();
        if (overcast <= 0F)
            return;
        // Doubled against the channel: at the channel's own pace the fog
        // converged about twice as slowly as the sky (the blue horizon ring).
        final float strength = Math.min(1F, overcast * 2F);
        final float[] fog = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
        if (fog == null || fog.length < 3)
            return;
        final float luma = fog[0] * 0.3F + fog[1] * 0.59F + fog[2] * 0.11F;
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(
                fog[0] + (SkyEventScheduler.whiteTarget(fog[0], luma) - fog[0]) * strength,
                fog[1] + (SkyEventScheduler.whiteTarget(fog[1], luma) - fog[1]) * strength,
                fog[2] + (SkyEventScheduler.whiteTarget(fog[2], luma) - fog[2]) * strength,
                fog.length > 3 ? fog[3] : 1.0F);
    }
}
