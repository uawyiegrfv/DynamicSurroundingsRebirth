package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
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

    // Read back what setupColor settled on. The rain input above was measured
    // already and came back smooth, so if the fog is stepping it is happening
    // inside vanilla's own arithmetic - most likely the biome fog, which arrives
    // here as a discrete colour per biome. This reads the result rather than the
    // input, which is the only way to tell those apart.

    @Inject(method = "setupColor", at = @At("TAIL"), require = 0)
    private static void dsurround_fogColorResult(CallbackInfo ci) {
        PrecipitationIntensity.noteFogColorRGB(
                com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor());
    }
}
