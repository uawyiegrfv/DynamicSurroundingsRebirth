package org.orecruncher.dsurround.processing.fog;

import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;

/**
 * Increases fog when the player is down near the bedrock layer, ported from the
 * original 1.12.2 BedrockFogRangeCalculator. The original anchored the gradient at
 * y=32 above the y=0 bedrock of 1.12.2; modern worlds (1.18+) start at y=-64, so the
 * reference height is derived from the world's actual minimum build height. The fog now
 * hugs the bedrock layer only: within 10 blocks above it (minY..minY+10, i.e. -64..-54 in
 * the overworld) the fog distance shrinks quadratically with depth, so the deep dark /
 * ancient city (floor around y=-51) is completely free of bedrock fog.
 */
public class BedrockFogRangeCalculator extends VanillaFogRangeCalculator {

    // Reused per frame; render() always overwrites both range fields before returning.
    private final FogData reusableResult = new FogData();


    private static final float BASE_Y = 10F;
    private static final float MAX_FOG_END = 100F;

    public BedrockFogRangeCalculator(Configuration.FogOptions fogOptions) {
        super("Bedrock", fogOptions);
    }

    @Override
    public boolean enabled() {
        return this.fogOptions.enableBedrockFog;
    }

    @Override
    @NotNull
    public FogData render(@NotNull final FogData data, float renderDistance, float partialTick) {
        var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return data;

        final var level = player.level();

        // Reference height: the top of the bedrock gradient. The fog only applies within
        // BASE_Y (10) blocks above the world's minimum build height (minY..minY+10, i.e.
        // -64..-54 in the overworld), so deep biomes like the deep dark (floor ~-51) stay
        // clear. Use the world's actual minimum so any dimension/version gets the right
        // gradient.
        final double baseY = level.getMinBuildHeight() + BASE_Y;
        if (player.getY() >= baseY)
            return data;

        final double factor = (player.getY() + 4D - level.getMinBuildHeight()) / BASE_Y;

        // Original 1.12.2 logic: the fog also depends on local brightness. The bright open
        // surface - e.g. a superflat world whose surface sits at y=0..3, right inside the
        // gradient - pushes d0 above 1.0 and suppresses the fog; only dark, deep mining
        // spots trigger it. The port originally dropped this brightness term, which made the
        // fog appear even on the bright open ground of a superflat world.
        final double brightnessTerm = level.getLightEngine().getRawBrightness(player.blockPosition(), 0) / 16.0D;
        final double d0 = brightnessTerm + factor;
        if (d0 >= 1.0D)
            return data;
        final double d0Sq = Math.max(0D, d0);
        final float end = Math.max(20F, Math.min(data.renderDistanceEnd, MAX_FOG_END * (float) (d0Sq * d0Sq)));
        final float start = Math.min(data.renderDistanceStart, end * 0.5F);

        final FogData result = this.reusableResult;
        result.renderDistanceStart = start;
        result.renderDistanceEnd = end;
        return result;
    }
}
