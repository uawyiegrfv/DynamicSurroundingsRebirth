package org.orecruncher.dsurround.processing;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.fog.HolisticFogRangeCalculator;

/**
 * 26.1: FogRenderer was reworked (setupFog now returns FogData stored in the camera render
 * state). The old mixin + FOG_RENDER_EVENT hook is replaced by the native NeoForge
 * ViewportEvent.RenderFog, which fires with the mutable FogData after the fog environment
 * has been applied. We adjust the environmental fog range in place.
 */
public class FogHandler extends AbstractClientHandler {

    private final HolisticFogRangeCalculator fogCalculator;
    private FogData lastData;

    public FogHandler(Configuration config, IModLog logger) {
        super("Fog Handler", config, logger);

        this.fogCalculator = new HolisticFogRangeCalculator(logger, config.fogOptions);
        this.lastData = new FogData();
        this.lastData.renderDistanceStart = this.lastData.renderDistanceEnd = 192F;

        NeoForge.EVENT_BUS.addListener(this::renderFog);
    }

    @Override
    public void process(final Player player) {
        if (this.fogCalculator.enabled())
            this.fogCalculator.tick();
    }

    @Override
    public void onDisconnect() {
        this.fogCalculator.disconnect();
    }

    private void renderFog(ViewportEvent.RenderFog event) {
        // Only terrain (atmospheric) fog is modified; water/lava/powdered-snow keep vanilla.
        if (event.getType() != FogType.ATMOSPHERIC)
            return;

        var data = event.getFogData();
        if (this.fogCalculator.enabled()) {
            this.lastData = this.fogCalculator.render(data, GameUtils.getMC().options.getEffectiveRenderDistance(), (float) event.getPartialTick());
            // Write the computed range back into the event's fog data (terrain fog = render distance range)
            data.renderDistanceStart = this.lastData.renderDistanceStart;
            data.renderDistanceEnd = this.lastData.renderDistanceEnd;
        } else {
            // Preserve for diagnostic trace even though action was not taken
            this.lastData = data;
        }
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var text = "Fog: %f/%f".formatted(this.lastData.renderDistanceStart, this.lastData.renderDistanceEnd);
        var disabledText = this.fogCalculator.getDisabledText();
        if (disabledText.isPresent())
            text += disabledText.get();
        event.add(CollectDiagnosticsEvent.Section.Systems, text);
    }
}
