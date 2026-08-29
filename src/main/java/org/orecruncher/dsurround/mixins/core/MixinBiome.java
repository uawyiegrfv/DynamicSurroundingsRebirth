package org.orecruncher.dsurround.mixins.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class MixinBiome implements IBiomeExtended {

    @Unique
    private BiomeInfo dsurround_info;

    @Final
    @Shadow
    private Biome.ClimateSettings climateSettings;

    @Final
    @Shadow
    private BiomeSpecialEffects specialEffects;

    @Override
    public BiomeSpecialEffects dsurround_getSpecialEffects() {
        return this.specialEffects;
    };

    @Override
    public BiomeInfo dsurround_getInfo() {
        return this.dsurround_info;
    }

    @Override
    public void dsurround_setInfo(BiomeInfo info) {
        this.dsurround_info = info;
    }

    @Override
    public Biome.ClimateSettings dsurround_getWeather() {
        return this.climateSettings;
    }

    /**
     * Obtain fog color from Dynamic Surroundings' config if available. This drives the
     * distant-horizon yellow haze over deserts (biomes.json "fogColor") - vanilla's
     * FogRenderer.setupColor samples Biome.getFogColor() every frame and blends
     * cross-biome transitions itself, so no GUI is covered and no extra smoothing is
     * needed. Copied from the fabric port's MixinBiome.
     *
     * @param cir Mixin callback result
     */
    @Inject(method = "getFogColor()I", at = @At("HEAD"), cancellable = true)
    public void dsurround_getFogColor(CallbackInfoReturnable<Integer> cir) {
        if (org.orecruncher.dsurround.Client.Config != null
                && org.orecruncher.dsurround.Client.Config.weatherOptions.enableBiomeFogColor
                && this.dsurround_info != null) {
            var color = this.dsurround_info.getFogColor();
            if (color != null)
                cir.setReturnValue(color.getValue());
        }
    }

    // 26.1: Biome.getTemperature(BlockPos) was removed. The base temperature
    // is exposed through ClimateSettings.temperature().
    @Override
    public float dsurround_getTemperature(BlockPos pos) {
        return this.climateSettings.temperature();
    }

    // 26.1: Biome.getFogColor() and Biome.getBackgroundMusic() were removed in 26.1
    // (the fog renderer was removed and biome background music was reworked).
    // The corresponding DS overrides are therefore no longer applicable.
}
