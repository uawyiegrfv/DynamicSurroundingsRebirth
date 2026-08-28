package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.config.libraries.IBiomeLibrary;
import org.orecruncher.dsurround.lib.GameUtils;

public class BiomeFogRangeCalculator extends VanillaFogRangeCalculator {

    // Reused per frame; render() always overwrites both range fields before returning.
    private final FogData reusableResult = new FogData();


    private static final float SCALE_SMOOTH_ALPHA = 0.01F;

    private final IBiomeLibrary biomeLibrary;

    private BlockPos lastBlockPos;
    private float activeScale;
    private float targetScale;

    public BiomeFogRangeCalculator(IBiomeLibrary biomeLibrary, Configuration.FogOptions fogOptions) {
        super("Biome", fogOptions);
        this.biomeLibrary = biomeLibrary;
        this.activeScale = this.targetScale = 0F;
        this.lastBlockPos = BlockPos.ZERO;
    }

    @Override
    public boolean enabled() {
        return this.fogOptions.enableBiomeFog;
    }

    @Override
    @NotNull
    public FogData render(@NotNull final FogData data, float renderDistance, float partialTick) {

        // Slow low-pass filter toward the sampled target. The per-block fog-density
        // sample is noisy (oscillates a few tenths each block), so a slow filter flattens
        // both the per-block quantization and the noise instead of chasing them.
        if (Float.compare(this.activeScale, this.targetScale) != 0)
            this.activeScale += (this.targetScale - this.activeScale) * SCALE_SMOOTH_ALPHA;

        // Apply the configurable density scale (0 = no biome fog, 1 = default).
        final float effectiveScale = Math.min(this.activeScale * (float) this.fogOptions.biomeFogDensity, 1F);
        if (Float.compare(effectiveScale, 0F) == 0)
            return data;

        var scale = 1F - effectiveScale;
        final FogData result = this.reusableResult;
        result.renderDistanceEnd = data.renderDistanceEnd * scale;
        result.renderDistanceStart = data.renderDistanceStart * scale * scale;
        return result;
    }

    @Override
    public void tick() {
        // Only need to sample if the player moves position
        var currentPosition = GameUtils.getPlayer().map(Entity::getOnPos).orElseThrow();
        if (this.lastBlockPos.equals(currentPosition))
            return;
        this.lastBlockPos = currentPosition;
        this.targetScale = this.sampleArea(currentPosition, 6);
    }

    @Override
    public void disconnect() {
        this.activeScale = this.targetScale = 0F;
        this.lastBlockPos = BlockPos.ZERO;
    }

    private float sampleArea(BlockPos pos, int range) {
        final BiomeManager biomeManager = GameUtils.getWorld().map(Level::getBiomeManager).orElseThrow();
        var iterator =BlockPos.withinManhattan(pos, range, range, range).iterator();
        float intensityAccum = 0F;
        float intensityCount = 0;
        while(iterator.hasNext()) {
            var p = iterator.next();
            final Biome b = biomeManager.getNoiseBiomeAtPosition(p).value();
            final BiomeInfo info = this.biomeLibrary.getBiomeInfo(b);
            intensityAccum += info.getFogDensity().getIntensity();
            intensityCount++;
        }

        return intensityAccum / intensityCount;
    }
}
