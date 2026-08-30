package org.orecruncher.dsurround.mixins.core;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.Music;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

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

    @Invoker("getTemperature")
    public abstract float dsurround_getTemperature(BlockPos pos);

    /**
     * 1.21.1: the official build's DS fog-color injection into Biome.getFogColor() is
     * deliberately NOT carried over. In 1.21.1 vanilla, FogRenderer feeds getFogColor()
     * into the global fog color that the cloud shader blends with, so overriding it
     * tints the clouds (e.g. yellow desert clouds) - an effect neither the 1.20.1 nor
     * the 26.1 build has. The 26.1 build has no Biome.getFogColor() at all: its biome
     * fog look is range-only (fog calculators) plus the ComputeFogColor horizon tint,
     * which this port mirrors. The fog-color entries in biomes.json therefore only
     * drive the horizon tint on this version.
     *
     * Check the biome configuration for a background soundtrack for the biome. If one is present,
     * return it. Otherwise, let Minecraft do its thing.
     *
     * NOTE: If a biome has been configured with a background sound via data pack, it is folded into
     * the selection weight table.
     */
    @Inject(method = "getBackgroundMusic()Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void dsurround_getBackgroundMusic(CallbackInfoReturnable<Optional<Music>> cir) {
        if (this.dsurround_info == null) {
            // Can be null after things like a teleport
            cir.setReturnValue(Optional.empty());
        } else {
            var result = this.dsurround_info.getBackgroundMusic(Randomizer.current());
            if (result.isPresent())
                cir.setReturnValue(result);
        }
    }
}
