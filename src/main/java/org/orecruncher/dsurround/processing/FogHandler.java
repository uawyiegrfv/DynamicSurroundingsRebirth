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
import org.orecruncher.dsurround.processing.weather.RouteFuse;

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
        // Counted before the gate below: the question the watchdog asks is whether the fog
        // range route is consulted at all on this version, not whether this particular pass is
        // one we modify. Same placement as the sibling ports.
        RouteFuse.feed(RouteFuse.Route.FOG_RANGE);

        // Only terrain (atmospheric) fog is modified; water/lava/powdered-snow keep vanilla.
        if (event.getType() != FogType.ATMOSPHERIC)
            return;

        var data = event.getFogData();
        if (this.fogCalculator.enabled()) {
            this.lastData = this.fogCalculator.render(data, GameUtils.getMC().options.getEffectiveRenderDistance(), (float) event.getPartialTick());
            // Write the computed range back into the event's fog data (terrain fog = render distance range)
            data.renderDistanceStart = this.lastData.renderDistanceStart;
            data.renderDistanceEnd = this.lastData.renderDistanceEnd;
            // And into the atmospheric pair - which is the reason 26.1 has fog at noon.
            //
            // 26.1 splits fog into two ranges and the shader keeps the DENSER of the two
            // (assets/minecraft/shaders/include/fog.glsl, total_fog_value() = max(...)).
            // The atmospheric pair defaults to 0..1024 in the overworld: dimension_type/
            // overworld.json sets no fog_start/fog_end distance, so the attribute keeps
            // its default. That is a permanent grey wash all the way out to ~170 blocks
            // at midday under a clear sky - 1.20.1 has nothing of the kind. This handler
            // used to write only the render-distance pair, so it could THICKEN that wash
            // but never lift it, and no DSR setting could make it go away.
            //
            // Writing the same range into both pairs makes the max() a no-op and hands the
            // atmospheric range to the same calculator - including while it rains, where
            // vanilla would otherwise pile a second, vanilla-rain-driven layer on top of ours.
            // Water, lava and powdered snow are untouched: the gate above is ATMOSPHERIC only.
            data.environmentalStart = this.lastData.renderDistanceStart;
            data.environmentalEnd = this.lastData.renderDistanceEnd;
        } else {
            // Preserve for diagnostic trace even though action was not taken. Copy the
            // two floats instead of holding the event's FogData - vanilla may reuse it.
            this.lastData.renderDistanceStart = data.renderDistanceStart;
            this.lastData.renderDistanceEnd = data.renderDistanceEnd;
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
