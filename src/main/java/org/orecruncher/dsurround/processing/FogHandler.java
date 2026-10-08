package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.player.Player;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.compat.DistantHorizonsCompat;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.fog.HolisticFogRangeCalculator;
import org.orecruncher.dsurround.processing.weather.RouteFuse;

public class FogHandler extends AbstractClientHandler {

    private final HolisticFogRangeCalculator fogCalculator;
    private FogRenderer.FogData lastData;
    /** Whether the range is currently Distant Horizons' business - also gates the one-off logs. */
    private boolean handedOffToDh = false;

    public FogHandler(Configuration config, IModLog logger) {
        super("Fog Handler", config, logger);

        this.fogCalculator = new HolisticFogRangeCalculator(logger, config.fogOptions);
        this.lastData = new FogRenderer.FogData(FogRenderer.FogMode.FOG_TERRAIN);
        this.lastData.start = this.lastData.end = 192F;

        ClientEventHooks.FOG_RENDER_EVENT.register(this::renderFog);
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

    private void renderFog(FogRenderer.FogData data, float renderDistance, float partialTick) {
        // Counted before either gate below: the question the watchdog asks is whether the fog
        // range route is consulted at all on this version, not whether this particular pass is
        // one we modify.
        RouteFuse.feed(RouteFuse.Route.FOG_RANGE);

        // Only the TERRAIN pass, which is what this handler's values describe. The mixin
        // (MixinFogRenderer) already filters on FOG_TERRAIN and FogType.NONE; gating here as
        // well keeps the sibling ports' behaviour in one place.
        if (data.mode != FogRenderer.FogMode.FOG_TERRAIN)
            return;

        // ---- hand-off: Distant Horizons owns the terrain fog when it wants it ----
        //
        // DH's fog mixin also runs against FogRenderer.setupFog, and when it decides to cancel
        // vanilla fog it pushes the fog planes out to 4.2e14 - our values, written a moment
        // earlier, are simply gone. Writing them anyway would be a frame-by-frame fight we always
        // lose, with no way for the player to tell why their fog settings do nothing. So we stand
        // down and say so, once.
        //
        // Note the condition is DH's OWN option, not its presence: with "vanilla fog" left on
        // (its default) DH does not touch the fog and ours applies as usual.
        if (DistantHorizonsCompat.ownsVanillaFog()) {
            if (!this.handedOffToDh) {
                this.handedOffToDh = true;
                this.logger.info("Fog range handed to Distant Horizons: its vanilla-fog option is off, so it overwrites the terrain fog after this hook. DSR's weather fog now reaches the world through DH's LOD fog instead - set DH's fog/vanilla-fog option on to get DSR's own range back.");
            }
            // The range is DH's, but the WEATHER is ours: ask the calculator what our fog would be
            // and push the resulting visibility into DH's far fog, so heavy rain closes the LOD
            // terrain in as well. Without this the near world fogs out and the far world stays
            // crisp - the seam the integration exists to remove.
            if (this.fogCalculator.enabled()) {
                final FogRenderer.FogData ours = this.fogCalculator.render(data, renderDistance, partialTick);
                DistantHorizonsCompat.syncFog(ours.start, ours.end, data.start, data.end);
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

        // 1.21.x: the incoming record's start/end are the terrain fog planes, read back from the
        // shader by MixinFogRenderer.
        final float start = data.start;
        final float end = data.end;

        if (this.fogCalculator.enabled()) {
            this.lastData = this.fogCalculator.render(data, renderDistance, partialTick);
            RenderSystem.setShaderFogStart(this.lastData.start);
            RenderSystem.setShaderFogEnd(this.lastData.end);
            RenderSystem.setShaderFogShape(this.lastData.shape);
            // DH may not own the vanilla fog and still be drawing LOD terrain beyond it; its LOD
            // fog follows the same visibility so the two agree. A no-op when DH is absent or the
            // integration is switched off.
            DistantHorizonsCompat.syncFog(this.lastData.start, this.lastData.end, start, end);
        } else {
            // Preserve for diagnostic trace even though action was not taken
            this.lastData = data;
            DistantHorizonsCompat.releaseAll();
        }
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var text = "Fog: %f/%f, %s, %s ".formatted(this.lastData.start, this.lastData.end, this.lastData.shape, this.lastData.mode);
        var disabledText = this.fogCalculator.getDisabledText();
        if (disabledText.isPresent())
            text += disabledText.get();
        event.add(CollectDiagnosticsEvent.Section.Systems, text);
    }
}
