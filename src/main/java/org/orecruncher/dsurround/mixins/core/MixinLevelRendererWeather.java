package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ThreadLocalRandom;

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

    // ---- rain sound and ground splash (N7) ---------------------------------
    //
    // tickRain owns both, and both are graded by changing what it is fed rather
    // than by reimplementing it. The whole method - heightmap sampling, the
    // +/-10 range, the RAIN phase gate that leaves snow silent, lava to SMOKE,
    // the halving under reduced particles - is vanilla's, and stays vanilla's.
    //
    // The return value of the hook matters and is the opposite of what it looks
    // like: in the bytecode, false lets vanilla carry on and true returns early
    // (offset 19 jumps to 23 on false; 22 is the return). We want vanilla to
    // carry on, because that is where the splash positions and the rain sound
    // come from, so the hook answers false - see PrecipitationRenderer.

    @Redirect(
            method = "tickRain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 0)
    private float dsurround_splashRainLevel(ClientLevel level, float partialTick) {
        return PrecipitationIntensity.splashRainLevel(level, partialTick);
    }

    // Both rain one-shots - WEATHER_RAIN_ABOVE at 0.1 and WEATHER_RAIN at 0.2 -
    // are the same call with the same descriptor, so one handler takes them both
    // and reads the volume off the argument. No ordinal needed, and none wanted:
    // pinning one would silently drop the other.
    @Redirect(
            method = "tickRain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            require = 0)
    private void dsurround_rainSound(ClientLevel level, BlockPos pos, SoundEvent event,
                                     SoundSource source, float volume, float pitch, boolean global) {
        final boolean played;
        SoundEvent chosen = event;
        float gain = 1F;
        if (!PrecipitationIntensity.ownsAmbient()) {
            played = true;
        } else if (ThreadLocalRandom.current().nextFloat() < PrecipitationIntensity.rainAudioDensity()) {
            // Which clip this one drop is. See nextRainAudioHeavy(): the blend
            // is a choice made per one-shot - dithered, not drawn - so the stack
            // cross-fades between the calm and the heavy texture without a
            // second voice and without a filter ever being involved.
            if (!PrecipitationIntensity.nextRainAudioHeavy()) {
                chosen = PrecipitationIntensity.rainCalmSound();
                gain = PrecipitationIntensity.rainCalmGain();
            }
            // Clamped: the calm clip is played several times louder than the
            // vanilla one because it was recorded that much quieter, and a
            // volume above 1 buys nothing but clipping.
            volume = Math.min(1F, volume * gain * PrecipitationIntensity.rainAudioVolumeScale());
            played = true;
        } else {
            played = false;
        }
        PrecipitationIntensity.noteRainAudio(played);
        if (played)
            level.playLocalSound(pos, chosen, source, volume, pitch, global);
    }
}
