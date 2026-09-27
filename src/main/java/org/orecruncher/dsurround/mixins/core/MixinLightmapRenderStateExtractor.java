package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1's "the world goes dark when it rains" route.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every other version reaches it by redirecting the rain level inside
 * {@code ClientLevel.getSkyDarken(float)}. On 26.1 that method is gone; the
 * render-side value lives in {@code LightmapRenderState.skyFactor} (public,
 * mutable), filled by {@code LightmapRenderStateExtractor.extract}. There is no
 * NeoForge event for it, so this has to be a mixin.</p>
 *
 * <h2>Why a ratio, and why these constants</h2>
 *
 * <p>Decompiled 26.1 weather pipeline: the sky-light factor starts from a
 * timeline value (1.0 by day, 0.24 at night) that is <em>multiplied</em> by the
 * weather share, {@code keep = (1 - 0.3125*plainRain) * (1 - 0.52734375*thunder)}
 * where {@code plainRain = rainLevel - thunderLevel} (vanilla splits its rain
 * level; thunder is counted as thunder, the rest as rain). The end flash is then
 * added on top. So {@code skyFactor = timeline * keep + flash}, and scaling by
 * {@code keep(ours) / keep(vanilla)} cancels the unknown timeline factor and
 * lands exactly on {@code timeline * keep(ours) + flash}.</p>
 *
 * <p>When we are not taking over, our levels <em>are</em> vanilla's, the ratio is
 * exactly 1, and the frame is left untouched - the route degrades to vanilla
 * rather than to a third behaviour.</p>
 *
 * <h2>Why the needsUpdate gate</h2>
 *
 * <p>Vanilla only refills {@code skyFactor} on frames where its extractor's
 * {@code needsUpdate} flag is set (once per game tick, 20 Hz); the render state
 * object is reused across the frames in between. Scaling on every frame would
 * compound - each tick's frames would multiply the previous frame's already
 * scaled value again, sawtooth towards the floor and snap back on the next
 * refill. Gating on the flag scales exactly once per vanilla refill.</p>
 */
@Mixin(value = LightmapRenderStateExtractor.class, priority = 900)
public abstract class MixinLightmapRenderStateExtractor {

    /** Decompiled WeatherAttributes: SKY_LIGHT_FACTOR rain blend target. */
    private static final float RAIN_TARGET = 0.24F;
    private static final float RAIN_ALPHA = 0.3125F;
    private static final float THUNDER_ALPHA = 0.52734375F;

    @Inject(method = "extract", at = @At("TAIL"), require = 0)
    private void dsurround_skyFactor(LightmapRenderState state, float partialTick,
                                     CallbackInfo ci) {
        // Vanilla refills the factor only on these frames; scaling on the others
        // would compound (see the class doc).
        if (state == null || !state.needsUpdate)
            return;
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null)
            return;

        final float ourRain = PrecipitationIntensity.skyLightRainLevel(level, partialTick);
        final float ourThunder = PrecipitationIntensity.stormRainLevel(level, partialTick);
        final float vanillaRain = level.getRainLevel(partialTick);
        final float vanillaThunder = level.getThunderLevel(partialTick);
        final float ourPlain = Math.max(0F, ourRain - ourThunder);
        final float vanillaPlain = Math.max(0F, vanillaRain - vanillaThunder);
        if (ourPlain == vanillaPlain && ourThunder == vanillaThunder)
            return;

        final float keepVanilla = weatherKeep(vanillaPlain, vanillaThunder);
        if (keepVanilla <= 0.05F)
            return;
        final float keepOurs = weatherKeep(ourPlain, ourThunder);
        final float scaled = state.skyFactor * (keepOurs / keepVanilla);
        // No upper clamp: vanilla's own end flash legitimately pushes the factor
        // above 1, and clipping it would dim lightning even when we own nothing.
        state.skyFactor = Math.max(0F, scaled);
    }

    /** The share of the base factor vanilla's weather keeps: 1 with no weather. */
    private static float weatherKeep(float plainRain, float thunder) {
        return (1F - RAIN_ALPHA * plainRain) * (1F - THUNDER_ALPHA * thunder);
    }
}
