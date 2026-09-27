package org.orecruncher.dsurround.mixins.core;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.CustomEnvironmentEffectsRendererManager;
import net.neoforged.neoforge.client.CustomWeatherEffectRenderer;
import org.orecruncher.dsurround.processing.weather.PrecipitationRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 26.1 precipitation hook - the one thing that kept {@code hookCalls} at 0.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>26.1 looks the weather renderer up through an <em>environment attribute</em>:</p>
 *
 * <pre>
 * public static CustomWeatherEffectRenderer getCustomWeatherEffectRenderer(Identifier id) {
 *     if (NeoForgeEnvironmentAttributes.DEFAULT_CUSTOM_WEATHER_EFFECTS.equals(id))
 *         return null;                       // &lt;-- the whole problem
 *     return CUSTOM_WEATHER_EFFECT_RENDERERS.get(id);
 * }
 * </pre>
 *
 * <p>{@code CUSTOM_WEATHER_EFFECTS} is server-synced world data, so a client-only
 * mod cannot set it. A vanilla overworld therefore always resolves to
 * {@code minecraft:default}, which returns null, so our registered renderer is
 * never consulted: no suppression, no drawing, and {@code hookCalls} stays 0.</p>
 *
 * <h2>Why here and not in the registration</h2>
 *
 * <p>Registering under a non-default key is already done
 * ({@code PrecipitationRenderer.onRegisterWeatherEffectRenderer}). Nothing a
 * client can do makes the dimension point at that key, so the only place left is
 * the lookup itself.</p>
 *
 * <h2>Why it only fills in nulls</h2>
 *
 * <p>The override applies <b>only when vanilla produced nothing</b>. A dimension
 * that genuinely has its own custom weather renderer (a modded dimension, a
 * datapack) keeps it - taking those over would be a much worse bug than the one
 * being fixed. {@code require = 0} because this rides on a NeoForge internal:
 * if the method ever changes, we degrade to today's behaviour (no graded
 * precipitation on 26.1) instead of failing at startup.</p>
 */
@Mixin(value = CustomEnvironmentEffectsRendererManager.class, priority = 900)
public class MixinCustomEnvironmentEffectsRendererManager {

    @Inject(
            method = "getCustomWeatherEffectRenderer(Lnet/minecraft/resources/Identifier;)"
                    + "Lnet/neoforged/neoforge/client/CustomWeatherEffectRenderer;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private static void dsurround_forceGradedWeatherRenderer(
            Identifier id, CallbackInfoReturnable<CustomWeatherEffectRenderer> cir) {
        if (cir.getReturnValue() != null)
            return;
        var ours = PrecipitationRenderer.registeredWeatherRenderer();
        if (ours != null)
            cir.setReturnValue(ours);
    }
}
