package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes over the rain-driven <em>colour</em> of the sky and the clouds.
 *
 * <p>This is the part that {@code MixinLevelRendererWeather} cannot reach. That
 * mixin changes the rain level {@code LevelRenderer.renderSky} reads, which is
 * the sky's brightness and the alpha of the sun, moon and stars - but it is not
 * the colour. The colour is computed in {@code ClientLevel.getSkyColor} and
 * {@code ClientLevel.getCloudColor}, each of which reads the rain level for
 * itself, and each of which applies a desaturation of up to 0.95:</p>
 *
 * <pre>
 * float rain = this.getRainLevel(partialTick);
 * if (rain > 0) {
 *     float lum = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.6F;
 *     float t = 1.0F - rain * 0.95F;
 *     r = r * t + lum * (1 - t);
 * }
 * </pre>
 *
 * <p>At full rain that is very nearly a full grey-out, so it is a large effect
 * that went entirely untouched - which is why the sky read as "dimmer but not
 * actually wet". Redirecting the single {@code getRainLevel} inside each method
 * leaves all of that maths in place and only changes the number it is fed, so
 * the whole vanilla look is preserved and merely driven by our curve.</p>
 *
 * <p>Both handlers are instance methods with the receiver as the first argument:
 * a static {@code @Redirect} handler fails to apply and takes the game down at
 * startup. {@code require = 0} because these are self-calls and a rewritten
 * pipeline may not have them; if the injection misses, the sky simply stays
 * vanilla rather than breaking the client.</p>
 */
@Mixin(value = ClientLevel.class, priority = 900)
public abstract class MixinClientLevelWeatherColors {

    @Redirect(
            method = "getSkyColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_skyColorRainLevel(ClientLevel level, float partialTick) {
        // Counted before the ownership check, exactly like skyHooks: the question
        // this answers is "was the mixin consulted at all", not "did we take over".
        PrecipitationIntensity.noteSkyColorHook();
        return PrecipitationIntensity.skyColorRainLevel(level, partialTick);
    }

    @Redirect(
            method = "getCloudColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_cloudColorRainLevel(ClientLevel level, float partialTick) {
        PrecipitationIntensity.noteCloudColorHook();
        return PrecipitationIntensity.cloudColorRainLevel(level, partialTick);
    }

    // ---- biome transition on the sky BASE colour --------------------------
    //
    // Vanilla smooths the biome fog across a boundary (FogRenderer carries
    // BIOME_FOG_TRANSITION_TIME, targetBiomeFog and fogRed/G/B) but it does
    // NOT smooth the sky base colour: that one is sampled per block from
    // BiomeManager and steps the instant the camera crosses into another
    // biome. Savanna and forest differ enough to see (6EB1FF vs 79A6FF), which
    // is the border flicker.
    //
    // Advanced once per FRAME, not once per call: renderSky and
    // FogRenderer.setupColor both ask for the sky colour every frame, and
    // stepping per call would make the speed depend on how many callers there
    // happen to be - i.e. on frame rate and on code we do not own.

    /** Per frame, as a fraction of the remaining distance. ~0.4s to settle. */
    private static final float SKY_BASE_TRANSITION = 0.12F;
    private static float skyBaseR = -1F;
    private static float skyBaseG = 0F;
    private static float skyBaseB = 0F;
    private static long skyBaseStamp = Long.MIN_VALUE;

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void dsurround_smoothSkyBase(Vec3 pos, float partialTick,
                                         CallbackInfoReturnable<Vec3> cir) {
        final Vec3 value = cir.getReturnValue();
        if (value == null)
            return;
        final ClientLevel self = (ClientLevel) (Object) this;
        final long stamp = self.getGameTime() * 1000L + (long) (partialTick * 1000F);
        if (stamp != skyBaseStamp) {
            skyBaseStamp = stamp;
            if (skyBaseR < 0F) {
                // First frame: adopt rather than fade in from black.
                skyBaseR = (float) value.x;
                skyBaseG = (float) value.y;
                skyBaseB = (float) value.z;
            } else {
                skyBaseR += ((float) value.x - skyBaseR) * SKY_BASE_TRANSITION;
                skyBaseG += ((float) value.y - skyBaseG) * SKY_BASE_TRANSITION;
                skyBaseB += ((float) value.z - skyBaseB) * SKY_BASE_TRANSITION;
            }
        }
        cir.setReturnValue(new Vec3(skyBaseR, skyBaseG, skyBaseB));
    }

    // ---- the second pass: thunder -------------------------------------------
    //
    // Vanilla desaturates for rain, then desaturates AGAIN for thunder, and that
    // second pass is the one that makes a thunderstorm look like night: the
    // luminance it preserves is 0.2 against the rain pass's 0.6. Taking over only
    // the rain pass left the sky grey and wet but never dark, so a
    // full-intensity storm still read as an overcast afternoon.

    @Redirect(
            method = "getSkyColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),
            require = 0)
    private float dsurround_skyThunderLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.stormRainLevel(level, partialTick);
    }

    @Redirect(
            method = "getCloudColor",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),
            require = 0)
    private float dsurround_cloudThunderLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.stormRainLevel(level, partialTick);
    }

    // ---- sky light ------------------------------------------------------------
    //
    // getSkyDarken is what actually dims the world during a storm - it is a
    // separate mechanism from the sky colour and reads both levels for itself.
    // Leaving it on vanilla's numbers meant the ground stayed lit under a sky
    // that had gone dark.

    @Redirect(
            method = "getSkyDarken",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_darkenRainLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.skyLightRainLevel(level, partialTick);
    }

    @Redirect(
            method = "getSkyDarken",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),
            require = 0)
    private float dsurround_darkenThunderLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.stormRainLevel(level, partialTick);
    }

    // ---- the world light level ----------------------------------------------
    //
    // getSkyDarken dims the whole world, not just the sky, so a jump here shows
    // up as "the sky flickered" even though nothing about the sky changed. It
    // has to be ruled out before anything else can be blamed.

    @Inject(method = "getSkyDarken", at = @At("RETURN"), require = 0)
    private void dsurround_skyDarkenResult(float partialTick, CallbackInfoReturnable<Float> cir) {
        final var v = cir.getReturnValue();
        if (v != null)
            PrecipitationIntensity.noteSkyDarkenValue((ClientLevel) (Object) this, v);
    }
}
