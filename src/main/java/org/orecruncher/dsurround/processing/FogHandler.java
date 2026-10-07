package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.fog.FogData;
import org.orecruncher.dsurround.compat.DistantHorizonsCompat;
import org.orecruncher.dsurround.processing.fog.HolisticFogRangeCalculator;
import org.orecruncher.dsurround.processing.weather.RouteFuse;

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
    /** Whether the range is currently Distant Horizons' business - also gates the one-off logs. */
    private boolean handedOffToDh = false;

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
        // Every DH override this session placed is handed back, so the next world starts from the
        // player's own DH settings rather than from our last weather frame.
        DistantHorizonsCompat.onWorldLeft();
    }

    private void renderFog(ViewportEvent.RenderFog event) {
        // Counted before either gate below: the question the watchdog asks is whether Forge fires
        // this event at all on 1.20.1 (it is the port's stand-in for the 1.21 fog record), not
        // whether this particular pass is one we modify.
        RouteFuse.feed(RouteFuse.Route.FOG_RANGE);
        // Only atmospheric (clear-air) fog is modified; water/lava/powdered-snow keep
        // vanilla. 1.20.1 has no FogType.ATMOSPHERIC; the "no fluid" type is FogType.NONE.
        if (event.getType() != FogType.NONE)
            return;

        // ...and only the TERRAIN pass, which is what this handler's values describe.
        //
        // The FOG_SKY pass fires TWICE per frame (LevelRenderer.renderLevel calls setupFog with
        // FogMode.FOG_SKY directly, and again from a lambda at the head of renderSky), so without this
        // gate the frame's fog is written FOUR times in total - three times by this handler, twice of
        // those against the sky's own base - instead of once against the terrain's. The sibling ports
        // already gate on FOG_TERRAIN (see dsurround-neoforge-1.21.1 MixinFogRenderer), so this is also
        // a port divergence rather than a deliberate difference.
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN)
            return;

        // ---- hand-off: Distant Horizons owns the terrain fog when it wants it ----
        //
        // Its mixin runs at the RETURN of this very method, i.e. after the Forge event we are
        // inside, and when it cancels vanilla fog it pushes the fog planes out to 4.2e14 - our
        // values, written a moment earlier, are simply gone. Writing them anyway would be a
        // frame-by-frame fight we always lose, with no way for the player to tell why their fog
        // settings do nothing. So we stand down and say so, once.
        //
        // Note the condition is DH's OWN option, not its presence: with "vanilla fog" left on
        // (its default) DH does not touch the fog and ours applies as usual.
        if (DistantHorizonsCompat.ownsVanillaFog()) {
            if (!this.handedOffToDh) {
                this.handedOffToDh = true;
                this.logger.info("Fog range handed to Distant Horizons: its vanilla-fog option is off, so it overwrites the terrain fog after this hook. DSR's weather fog now reaches the world through DH's LOD fog instead - set DH's fog/vanilla-fog option on to get DSR's own range back.");
            }
            // The range is DH's, but the WEATHER is ours: ask the calculator what our fog would be and
            // push the resulting visibility into DH's far fog, so heavy rain closes the LOD terrain in
            // as well. Without this the near world fogs out and the far world stays crisp - the seam
            // the integration exists to remove.
            if (this.fogCalculator.enabled()) {
                final FogData ours = this.fogCalculator.render(
                        new FogData(event.getNearPlaneDistance(), event.getFarPlaneDistance()),
                        GameUtils.getMC().options.getEffectiveRenderDistance(),
                        (float) event.getPartialTick());
                DistantHorizonsCompat.syncFog(ours.renderDistanceStart, ours.renderDistanceEnd,
                        event.getNearPlaneDistance(), event.getFarPlaneDistance());
            } else {
                DistantHorizonsCompat.releaseAll();
            }
            return;
        }
        if (this.handedOffToDh) {
            // DH's option went back on mid-session (its config screen applies live).
            this.handedOffToDh = false;
            this.logger.info("Fog range back with DSR: Distant Horizons is no longer overriding the terrain fog.");
        }

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
            // DH may not own the vanilla fog and still be drawing LOD terrain beyond it; its LOD fog
            // follows the same visibility so the two agree. A no-op when DH is absent or the
            // integration is switched off.
            DistantHorizonsCompat.syncFog(this.lastData.renderDistanceStart, this.lastData.renderDistanceEnd,
                    start, end);
            // Forge 1.20.1 的 onFogRender 在事件未取消时会跳过用事件值重设雾，
            // 所以这里直接写 RenderSystem，确保雾真正生效。
            RenderSystem.setShaderFogStart(this.lastData.renderDistanceStart);
            RenderSystem.setShaderFogEnd(this.lastData.renderDistanceEnd);
        } else {
            // Preserve for diagnostic trace even though action was not taken.
            this.lastData = new FogData(start, end);
            DistantHorizonsCompat.releaseAll();
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
