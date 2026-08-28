package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.fog.FogData;
import org.orecruncher.dsurround.processing.fog.HolisticFogRangeCalculator;

/**
 * 1.20.1 port: the 1.21.x FogRenderer rework (setupFog returning a FogData record stored
 * in the camera render state) does not exist here. Forge 1.20.1 exposes the equivalent
 * hook through ViewportEvent.RenderFog, which fires with the fog's near/far plane
 * distances after the fog environment has been applied. We adjust the environmental
 * (atmospheric) fog range in place.
 */
public class FogHandler extends AbstractClientHandler {

    private final HolisticFogRangeCalculator fogCalculator;
    private FogData lastData;

    public FogHandler(Configuration config, IModLog logger) {
        super("Fog Handler", config, logger);

        this.fogCalculator = new HolisticFogRangeCalculator(logger, config.fogOptions);
        this.lastData = new FogData(192F, 192F);

        MinecraftForge.EVENT_BUS.addListener(this::renderFog);
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
        // Only atmospheric (clear-air) fog is modified; water/lava/powdered-snow keep
        // vanilla. 1.20.1 has no FogType.ATMOSPHERIC; the "no fluid" type is FogType.NONE.
        if (event.getType() != FogType.NONE)
            return;

        // 1.20.1: near plane == FogData.start (renderDistanceStart), far plane == FogData.end.
        final float start = event.getNearPlaneDistance();
        final float end = event.getFarPlaneDistance();

        if (this.fogCalculator.enabled()) {
            this.lastData = this.fogCalculator.render(
                    new FogData(start, end),
                    GameUtils.getMC().options.getEffectiveRenderDistance(),
                    (float) event.getPartialTick());
            event.setNearPlaneDistance(this.lastData.renderDistanceStart);
            event.setFarPlaneDistance(this.lastData.renderDistanceEnd);
            // Forge 1.20.1 的 onFogRender 在事件未取消时会跳过用事件值重设雾，
            // 所以这里直接写 RenderSystem，确保雾真正生效。
            RenderSystem.setShaderFogStart(this.lastData.renderDistanceStart);
            RenderSystem.setShaderFogEnd(this.lastData.renderDistanceEnd);
        } else {
            // Preserve for diagnostic trace even though action was not taken.
            this.lastData = new FogData(start, end);
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
