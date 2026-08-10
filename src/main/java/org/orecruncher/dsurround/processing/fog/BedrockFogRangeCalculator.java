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

        // Reference height: the top of the bedrock gradient. In 1.12.2 bedrock was at
        // y=0 and the fog began to clear 32 blocks above; modern worlds put bedrock at
        // the minimum build height (y=-64 in the overworld), so the equivalent clearing
        // height is minY + 32. Use the world's actual minimum so any dimension/version
        // gets the right gradient.
        final double baseY = player.level().getMinY() + BASE_Y;
        if (player.getY() >= baseY)
            return data;

        double factor = (player.getY() + 4D - player.level().getMinY()) / BASE_Y;
        if (factor < 0D)
            factor = 0D;
        factor *= factor;

        // The original only pulled the far plane in; the Holistic combiner rejects
        // ranges where start > end, so bring the near plane along with it. The hard
        // 5-block floor made the deepest bedrock fog near-impenetrable; raise it so the
        // deepest gradient still leaves some visibility.
        final float end = Math.max(20F, Math.min(data.renderDistanceEnd, MAX_FOG_END * (float) factor));
        final float start = Math.min(data.renderDistanceStart, end * 0.5F);

        var result = new FogData();
        result.renderDistanceStart = start;
        result.renderDistanceEnd = end;
        return result;
    }
}
