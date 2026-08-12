package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.aurora.AuroraClassic;
import org.orecruncher.dsurround.processing.aurora.AuroraFactory;
import org.orecruncher.dsurround.processing.aurora.IAurora;
import org.orecruncher.dsurround.processing.Scanners;

/**
 * Handles the spawn/update/render of auroras. Ported from 1.12.2 Dynamic
 * Surroundings (MIT).
 *
 * <p>1.12.2 used {@code RenderWorldLastEvent} + the immediate-mode Tessellator.
 * 26.1 has no immediate mode, so rendering happens on the native NeoForge
 * {@link RenderLevelStageEvent.AfterSky} hook, submitting POSITION_COLOR quads
 * through {@code MultiBufferSource} ({@code RenderTypes.debugQuads()}).
 */
public class AuroraEffectHandler extends AbstractClientHandler {

    // Celestial-angle window for aurora visibility: ~10pm (150deg) to ~2am (210deg).
    protected static final float AURORA_WINDOW_START = 150F;
    protected static final float AURORA_WINDOW_END = 210F;

    // A forward jump in the celestial angle larger than this between two updates means time
    // was skipped (sleeping in a bed, or a /time command): natural progression is only
    // ~0.015 deg/tick, so anything over 30 degrees is an unambiguous skip.
    protected static final float TIME_SKIP_THRESHOLD = 30F;

    private final Scanners scanners;

    private IAurora current;
    private Identifier dimensionId;
    private float lastCelestialAngle = -1F;

    public AuroraEffectHandler(Configuration config, Scanners scanners, IModLog logger) {
        super("Aurora Effect", config, logger);
        this.scanners = scanners;

        NeoForge.EVENT_BUS.addListener(this::doRender);
    }

    @Override
    public void onConnect() {
        this.current = null;
        this.lastCelestialAngle = -1F;
    }

    @Override
    public void onDisconnect() {
        this.current = null;
        this.lastCelestialAngle = -1F;
    }

    private boolean canAuroraStay() {
        if (!this.config.auroraOptions.enableAurora)
            return false;

        final var world = GameUtils.getWorld().orElse(null);
        final var player = GameUtils.getPlayer().orElse(null);
        if (world == null || player == null)
            return false;

        // The aurora is only visible around midnight (a few hours either side).
        // DayCycle angle convention: noon = 0 degrees, midnight = 180 degrees.
        // A window of ~4 hours around midnight (10pm - 2am) maps to 150..210 deg.
        final float angle = DayCycle.getCelestialAngleDegrees(world);
        if (angle < AURORA_WINDOW_START || angle > AURORA_WINDOW_END)
            return false;

        // Render distance must be large enough to see the skybox band.
        if (Minecraft.getInstance().options.getEffectiveRenderDistance() < 6)
            return false;

        // Only cold biomes host auroras.
        final var info = this.scanners.playerLogicBiomeInfo();
        if (info == null)
            return false;
        final var traits = info.getTraits();
        return traits.contains(BiomeTrait.SNOWY) || traits.contains(BiomeTrait.ICY);
    }

    private boolean canSpawnAurora() {
        return this.current == null && canAuroraStay();
    }

    @Override
    public void process(final Player player) {

        // Sleeping skips straight to morning, so the celestial angle jumps far beyond the
        // natural ~0.015 deg/tick progression. Without this the aurora would keep fading
        // for the next 20-25 seconds under a fully bright sky; kill it outright instead
        // (the wake-up fade covers the pop).
        final float angle = DayCycle.getCelestialAngleDegrees(player.level());
        if (this.current != null && this.lastCelestialAngle >= 0F
                && angle - this.lastCelestialAngle > TIME_SKIP_THRESHOLD) {
            this.logger.debug("Time skip detected, killing aurora");
            this.current = null;
        }
        this.lastCelestialAngle = angle;

        // Process the current aurora
        final Identifier currentDimension = player.level().dimension().identifier();
        if (this.current != null) {
            // If completed or the player changed dimensions we want to kill outright
            if (this.current.isComplete() || !currentDimension.equals(this.dimensionId)
                    || !this.config.auroraOptions.enableAurora) {
                this.current = null;
            } else {
                this.current.update();
                final boolean isDying = this.current.isDying();
                final boolean canStay = canAuroraStay();
                if (isDying && canStay) {
                    this.logger.debug("Unfading aurora...");
                    this.current.setFading(false);
                } else if (!isDying && !canStay) {
                    this.logger.debug("Aurora fade...");
                    this.current.setFading(true);
                }
            }
        }

        // If there isn't a current aurora see if it needs to spawn.
        // The seed is derived from the in-game day so every player sees the same
        // aurora on a given night (matching the 1.12.2 behaviour).
        if (canSpawnAurora()) {
            final long day = player.level().getOverworldClockTime() / 24000L;
            this.current = AuroraFactory.produce(day);
            this.logger.debug("New aurora [%s]", this.current.toString());
        }

        // Set the dimension in case it changed
        this.dimensionId = currentDimension;
    }

    @SubscribeEvent
    public void doRender(final RenderLevelStageEvent.AfterSky event) {
        // Currently only the classic renderer exists; guard the cast so a future
        // renderer implementation can't crash the frame with a ClassCastException.
        if (!(this.current instanceof AuroraClassic classic))
            return;

        final float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        classic.render(event.getPoseStack(), partialTick);
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var text = "Aurora: " + (this.current == null ? "NONE" : this.current.toString());
        event.add(CollectDiagnosticsEvent.Section.Systems, text);
    }
}
