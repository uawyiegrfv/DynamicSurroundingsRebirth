package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.processing.weather.SkyEventScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

    // ---- the overcast grey, moved to where the value is actually used --------
    //
    // This used to be a TAIL injection on setupColor: read the shader fog colour
    // back, lift it towards grey, write it back. It never reached the screen.
    // setupColor does not touch the shader fog colour at all - it only computes
    // and stores the three static fields (fogRed/fogGreen/fogBlue) - and
    // LevelRenderer calls levelFogColor() immediately afterwards, which pushes
    // those fields into RenderSystem and overwrites anything a TAIL handler had
    // written. The overcast grey was therefore dead code that looked alive, and
    // the maintainer's report - a grey sky over a blue horizon - was exactly
    // what the code said would happen.
    //
    // Redirecting the arguments of that one call is the only place the lift can
    // take effect: it is the last write before the colour is used, it covers the
    // sky fog and the terrain fog in the same move (they read one value), and it
    // uses the same target function and the same channel the sky colour does, so
    // the two are in step by construction rather than by matched clocks.
    //
    // It now runs three times per frame rather than once (LevelRenderer calls
    // levelFogColor from renderLevel, renderSky and renderClouds); the three
    // calls in a frame carry the same value, so the result is idempotent.
    //
    // require = 0, as everywhere in this mixin: if a mappings change moves the
    // call, the overcast grey is absent rather than the client being dead.

    @Redirect(
            method = "levelFogColor",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V"),
            require = 0)
    private static void dsurround_fogColorOvercast(float red, float green, float blue) {
        float outRed = red;
        float outGreen = green;
        float outBlue = blue;
        final float overcast = SkyEventScheduler.whiteShiftStrength();
        if (overcast > 0F) {
            // Doubled: at the channel's own pace the fog converged about twice
            // as slowly as the sky (the maintainer's blue horizon ring - the
            // sky went grey while the fog behind it was still blue). Halving
            // the lag keeps the two in step. At overcast 0 the pass is off and
            // the fog is byte-for-byte vanilla's.
            final float strength = Math.min(1F, overcast * 2F);
            final float luma = red * 0.3F + green * 0.59F + blue * 0.11F;
            outRed = red + (SkyEventScheduler.whiteTarget(red, luma) - red) * strength;
            outGreen = green + (SkyEventScheduler.whiteTarget(green, luma) - green) * strength;
            outBlue = blue + (SkyEventScheduler.whiteTarget(blue, luma) - blue) * strength;
        }
        // The 3-argument form leaves the alpha channel alone, exactly as vanilla
        // called it - the lift is a colour operation and must not touch fog alpha.
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(outRed, outGreen, outBlue);
    }
}
