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
 * <h2>Why the affine form, and why these constants</h2>
 *
 * <p>Decompiled 26.1 weather pipeline: the sky-light factor starts from a
 * timeline value (1.0 by day, 0.24 at night) which the weather layers do not
 * scale - each one <em>interpolates towards its own target</em>, the rainy-day
 * floor {@code RAIN_TARGET = 0.24}. Applied in vanilla's order (rain, then
 * thunder) that leaves a multiplier and a constant,
 * {@code keep = (1 - 0.3125*plainRain) * (1 - 0.52734375*thunder)} and
 * {@code addend = RAIN_TARGET * (1 - keep)}, where
 * {@code plainRain = rainLevel - thunderLevel} (vanilla splits its rain level;
 * thunder is counted as thunder, the rest as rain). So
 * {@code skyFactor = timeline * keep + addend}, and the exact inverse is
 * {@code timeline = (skyFactor - addend(vanilla)) / keep(vanilla)}: swap in our
 * own {@code keep} and {@code addend} and the frame lands on
 * {@code timeline * keep(ours) + addend(ours)}.</p>
 *
 * <p>Scaling the factor by {@code keep(ours) / keep(vanilla)} instead - the
 * first version of this mixin - scales the floor along with the timeline, which
 * is not what vanilla does: night 0.24 -> 0.3164 (+32%), +135% with thunder,
 * and above 1.0 by day, a value the attribute cannot hold.</p>
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
        // AFFINE, not a ratio. 26.1's SKY_LIGHT_FACTOR interpolates the timeline
        // towards the rainy-day floor (RAIN_TARGET) instead of scaling it, i.e.
        //   skyFactor = timeline * keep + RAIN_TARGET * (1 - keep)
        // so the additive term belongs to the weather and must be swapped along
        // with the multiplier. Scaling the whole factor by keep(ours)/keep(vanilla)
        // scaled the floor too: night 0.24 -> 0.3164 (+32%), +135% with thunder,
        // and above 1.0 by day - which is not a value a light factor can hold.
        //
        // Undo vanilla's affine step, then apply ours:
        //   timeline = (factor - addend(vanilla)) / keep(vanilla)
        //   factor'  = timeline * keep(ours) + addend(ours)
        final float addendVanilla = weatherAddend(vanillaPlain, vanillaThunder);
        final float addendOurs = weatherAddend(ourPlain, ourThunder);
        final float scaled = (state.skyFactor - addendVanilla) * (keepOurs / keepVanilla) + addendOurs;
        // No upper clamp: vanilla's own end flash legitimately pushes the factor
        // above 1, and clipping it would dim lightning even when we own nothing.
        state.skyFactor = Math.max(0F, scaled);
    }

    /** The share of the base factor vanilla's weather keeps: 1 with no weather. */
    private static float weatherKeep(float plainRain, float thunder) {
        return (1F - RAIN_ALPHA * plainRain) * (1F - THUNDER_ALPHA * thunder);
    }

    /**
     * The additive half: how much of RAIN_TARGET the weather has pulled in.
     *
     * <p>{@code 1 - keep} expands to exactly this, which is the same number the
     * decompiled weather attributes blend towards
     * {@code SKY_LIGHT_FACTOR}'s rain target by:</p>
     * <pre>
     *   1 - keep = 1 - (1 - a*plain)*(1 - b*thunder)
     *            = a*plain*(1 - b*thunder) + b*thunder
     * </pre>
     */
    private static float weatherAddend(float plainRain, float thunder) {
        final float oneMinusKeep = RAIN_ALPHA * plainRain * (1F - THUNDER_ALPHA * thunder)
                + THUNDER_ALPHA * thunder;
        return RAIN_TARGET * oneMinusKeep;
    }
}
