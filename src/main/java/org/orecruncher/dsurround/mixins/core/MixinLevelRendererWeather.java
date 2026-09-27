package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes the sky and cloud darkening through our intensity curve.
 *
 * <h2>Why this mixin exists</h2>
 *
 * <p>Suppressing vanilla precipitation does <em>not</em> touch the sky. Vanilla
 * reads {@code ClientLevel.getRainLevel()} directly inside {@code renderSky} and
 * {@code renderClouds}, and neither goes anywhere near the
 * {@code renderSnowAndRain} hook we already own - so with rain suppression on,
 * the sky still darkened on vanilla's five second ramp while the rain itself
 * followed our curve. Two tracks, visibly out of step.</p>
 *
 * <p>1.12.2 did not have this problem and did not solve it: its only
 * {@code EntityRenderer} mixin hooked {@code addRainParticles} (ground splash),
 * and nothing in that codebase touches sky or cloud colour. There is no prior
 * art to copy - this is new.</p>
 *
 * <h2>Why {@code @Redirect} and why {@code require = 0}</h2>
 *
 * <p>Redirecting the single {@code getRainLevel} call inside each method leaves
 * the rest of the method byte-for-byte alone, which matters because the
 * alternative - capturing the local variable the result is stored into - depends
 * on a slot index that moves with every recompile.</p>
 *
 * <p>{@code require = 0} is deliberate. {@code @Redirect} is exclusive: if some
 * other mod has already taken the same instruction, one of the two has to lose,
 * and with {@code "required": true} plus {@code defaultRequire: 1} losing means
 * the client dies during startup. Failing to inject costs us the sky effect on
 * that version and nothing else, so we take that. If it ever happens, the
 * {@code skyHook} / {@code cloudHook} counters in the diagnostics stay at 0 and
 * say so out loud instead of leaving a silent regression.</p>
 *
 * <p>Also note two things that are load-bearing. The handlers are deliberately
 * <em>not</em> static: Mixin matches the handler's {@code static} modifier against
 * the target, and a static handler here fails the whole mixin with
 * {@code InvalidInjectionException: 'static' modifier of handler method does not
 * match target} - which, with {@code "required": true}, takes the client down at
 * startup and takes every mod that mixes into {@code LevelRenderer} down with it.
 * And the handler reads {@code getRainLevel} itself on the fallback path, which is
 * safe because the redirect only rewrites the call site inside
 * {@code renderSky} / {@code renderClouds}, not calls made from here.</p>
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererWeather {

    @Redirect(
            method = "renderSky",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_skyRainLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.sunAlphaRainLevel(level, partialTick);
    }

    // renderClouds has no getRainLevel call in any of our versions - the cloud
    // colour routes through ClientLevel.getCloudColor, which
    // MixinClientLevelWeatherColors already covers. The redirect that used to
    // live here never applied (cloudHooks stayed 0 forever) and has been
    // removed rather than kept as a misleading diagnostic.
    // ---- what is actually multiplied into the sky ----------------------------
    //
    // renderSky pushes a colour through setShaderColor and then draws the sky
    // quad, so whatever lands here is multiplied into the whole sky. That makes
    // it the single most useful number for a sky that misbehaves.
    //
    // ordinal = 0 matters: the method calls setShaderColor several times - once
    // for the fog colour before the sky quad and again afterwards to restore
    // white. Without it the instrument alternates between those unrelated values
    // every frame and reports tens of thousands of jumps that are not real.

    @Redirect(
            method = "renderSky",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V",
                    ordinal = 0),
            require = 0)
    private void dsurround_renderSkyShaderColor(float r, float g, float b, float a) {
        PrecipitationIntensity.noteSkyShaderColor(null, r, g, b);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, a);
    }

    // ---- the sky base colour ------------------------------------------------
    //
    // The first attempt used @Inject at RETURN on ClientLevel.getSkyColor and
    // silently failed to apply, which is exactly the failure mode require = 0
    // hides. This hooks the call site instead, the same way the rest of this
    // mixin works, which is both more reliable and gives us the level - the
    // earlier attempt had none and could only log "biome=error".

    @Redirect(
            method = "renderSky",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private net.minecraft.world.phys.Vec3 dsurround_skyColorResult(ClientLevel level,
                                                                   net.minecraft.world.phys.Vec3 pos,
                                                                   float partialTick) {
        final var v = level.getSkyColor(pos, partialTick);
        if (v != null)
            PrecipitationIntensity.noteSkyColorRGB(level, (float) v.x, (float) v.y, (float) v.z,
                    pos.x, pos.y, pos.z, partialTick);
        return v;
    }
}
