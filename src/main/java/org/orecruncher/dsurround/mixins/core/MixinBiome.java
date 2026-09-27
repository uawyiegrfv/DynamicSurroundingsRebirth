package org.orecruncher.dsurround.mixins.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.tags.BiomeTags;
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
        // The biome fog colour is sampled per block by FogRenderer, so on a
        // border it steps between the two biomes every frame. Vanilla does
        // smooth that (FogRenderer carries BIOME_FOG_TRANSITION_TIME) but it
        // restarts the transition whenever the target changes, so a target
        // that flips every frame is passed straight through with no smoothing
        // at all. That is why the flicker only appeared once we made
        // neighbouring biomes differ - the fixed filter and the dust increment
        // both do exactly that.
        //
        // So what goes back is the average over the player's surroundings
        // rather than the colour of the biome being asked about, advanced once
        // per tick. Standing on a border then yields a stable blend instead of
        // whichever side the camera block happens to land on this frame.
        final int averaged = dsurround_averagedFogColor();
        if (averaged != NO_OVERRIDE)
            cir.setReturnValue(averaged);

    }

    /** Returned by {@link #dsurround_averagedFogColor} when the fog is left alone. */
    @Unique
    private static final int NO_OVERRIDE = Integer.MIN_VALUE;

    /** Sampling offsets for the fog average, in blocks. Matches the horizon tint. */
    @Unique
    private static final int[][] FOG_SAMPLES = { { 0, 0 }, { 12, 0 }, { -12, 0 }, { 0, 12 }, { 0, -12 } };

    /** Per tick, as a fraction of the remaining distance. ~0.4s to settle. */
    @Unique
    private static final float FOG_TRANSITION = 0.12F;

    @Unique
    private static float fogBaseR = -1F;
    @Unique
    private static float fogBaseG = 0F;
    @Unique
    private static float fogBaseB = 0F;
    @Unique
    private static long fogBaseStamp = Long.MIN_VALUE;
    @Unique
    private static ITagLibrary fogTagLibrary = null;

    /**
     * The fog colour for the player's <em>surroundings</em>: the configured
     * colour and the dust increment averaged over a handful of nearby
     * positions, so a border reads as a blend instead of alternating between
     * its two sides.
     */
    @Unique
    private static int dsurround_averagedFogColor() {
        if (org.orecruncher.dsurround.Client.Config == null
                || !org.orecruncher.dsurround.Client.Config.weatherOptions.enableBiomeFogColor)
            return NO_OVERRIDE;
        try {
            var player = org.orecruncher.dsurround.lib.GameUtils.getPlayer().orElse(null);
            if (player == null)
                return NO_OVERRIDE;
            var level = player.level();
            final float dust = org.orecruncher.dsurround.processing.PrecipitationIntensity
                    .dustIntensity();
            final boolean dustOn = dust > 0.01F
                    && org.orecruncher.dsurround.Client.Config.weatherOptions.enableDesertSandstorm;
            if (dustOn && fogTagLibrary == null)
                fogTagLibrary = ContainerManager.resolve(ITagLibrary.class);

            float sr = 0F, sg = 0F, sb = 0F;
            final BlockPos origin = player.blockPosition();
            for (int[] offset : FOG_SAMPLES) {
                final Biome biome = level.getBiome(origin.offset(offset[0], 0, offset[1])).value();
                // IBiomeExtended is a mixin interface: Biome does not implement
                // it as far as javac knows, so the cast has to go through
                // Object. At runtime every Biome is a MixinBiome and does.
                final IBiomeExtended extended = (IBiomeExtended) (Object) biome;
                final BiomeInfo info = extended.dsurround_getInfo();
                int c = extended.dsurround_getSpecialEffects().getFogColor();
                if (info != null && info.getFogColor() != null)
                    c = info.getFogColor().getValue();
                // The same question the dust veil asks: IS_DESERT || IS_BADLANDS.
                // Asking it here as well is the point - the horizon used to key
                // off "does this biome have any config at all", and since
                // BiomeInfo is created for anything with so much as a sound
                // entry, that made savanna (which has sounds) turn dusty while
                // the river next to it (which has none) stayed blue.
                if (dustOn && info != null && fogTagLibrary != null
                        && (fogTagLibrary.is(BiomeTags.IS_DESERT, biome)
                                || fogTagLibrary.is(BiomeTags.IS_BADLANDS, biome))) {
                    var dustColor = info.getDustColor();
                    if (dustColor != null)
                        c = dsurround_mix(c, dustColor.getValue(), dust * DUST_SKY_TINT);
                }
                sr += (c >> 16 & 0xFF);
                sg += (c >> 8 & 0xFF);
                sb += (c & 0xFF);
            }

            final float n = FOG_SAMPLES.length;
            final float tr = sr / n, tg = sg / n, tb = sb / n;
            final long stamp = level.getGameTime();
            if (fogBaseR < 0F) {
                // First sample: adopt, never fade in from black.
                fogBaseR = tr;
                fogBaseG = tg;
                fogBaseB = tb;
                fogBaseStamp = stamp;
            } else if (stamp != fogBaseStamp) {
                fogBaseStamp = stamp;
                fogBaseR += (tr - fogBaseR) * FOG_TRANSITION;
                fogBaseG += (tg - fogBaseG) * FOG_TRANSITION;
                fogBaseB += (tb - fogBaseB) * FOG_TRANSITION;
            }
            return ((int) fogBaseR << 16) | ((int) fogBaseG << 8) | (int) fogBaseB;
        } catch (Throwable t) {
            // No player, no level, not the render thread: leave vanilla alone
            // rather than guessing. Biome.getFogColor() has callers we do not
            // control, and some of them run before a world exists.
            return NO_OVERRIDE;
        }
    }

    /** How far a full dust storm pushes the horizon colour towards the dust colour. */
    private static final float DUST_SKY_TINT = 0.85F;

    private static int dsurround_mix(int from, int to, float weight) {
        final float w = weight < 0F ? 0F : (weight > 1F ? 1F : weight);
        final int r = (int) ((from >> 16 & 0xFF) * (1F - w) + (to >> 16 & 0xFF) * w);
        final int g = (int) ((from >> 8 & 0xFF) * (1F - w) + (to >> 8 & 0xFF) * w);
        final int b = (int) ((from & 0xFF) * (1F - w) + (to & 0xFF) * w);
        return r << 16 | g << 8 | b;
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
