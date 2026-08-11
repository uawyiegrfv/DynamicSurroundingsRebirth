package org.orecruncher.dsurround.processing.fog;

import net.minecraft.client.renderer.fog.FogData;
import org.jetbrains.annotations.NotNull;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;

/**
 * Increases fog when the player is down near the bedrock layer, ported from the
 * original 1.12.2 BedrockFogRangeCalculator. The original anchored the gradient at
 * y=32 above the y=0 bedrock of 1.12.2; modern worlds (1.18+) start at y=-64, so the
 * reference height is derived from the world's actual minimum build height. Within
 * 28 blocks above the bedrock layer the fog distance shrinks quadratically with depth.
 */
public class BedrockFogRangeCalculator extends VanillaFogRangeCalculator {

    private static final float BASE_Y = 32F;
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

        // Reference height: the top of the bedrock gradient. In 1.12.2 bedrock was at
        // y=0 and the fog began to clear 32 blocks above; modern worlds put bedrock at
        // the minimum build height (y=-64 in the overworld), so the equivalent clearing
        // height is minY + 32. Use the world's actual minimum so any dimension/version
        // gets the right gradient.
        final double baseY = level.getMinY() + BASE_Y;
        if (player.getY() >= baseY)
            return data;

        final double factor = (player.getY() + 4D - level.getMinY()) / BASE_Y;

        // Original 1.12.2 logic: the fog also depends on local brightness. The bright open
        // surface - e.g. a superflat world whose surface sits at y=0..3, right inside the
        // gradient - pushes d0 above 1.0 and suppresses the fog; only dark, deep mining
        // spots trigger it. The port originally dropped this brightness term, which made the
        // fog appear even on the bright open ground of a superflat world.
        final double brightnessTerm = level.getRawBrightness(player.blockPosition(), 0) / 16.0D;
        final double d0 = brightnessTerm + factor;
        if (d0 >= 1.0D)
            return data;
        final double d0Sq = Math.max(0D, d0);
        final float end = Math.max(20F, Math.min(data.renderDistanceEnd, MAX_FOG_END * (float) (d0Sq * d0Sq)));
        final float start = Math.min(data.renderDistanceStart, end * 0.5F);

        var result = new FogData();
        result.renderDistanceStart = start;
        result.renderDistanceEnd = end;
        return result;
    }
}
