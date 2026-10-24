package org.orecruncher.dsurround.processing;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IBlockLibrary;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.echo.EchoDetector;
import org.orecruncher.dsurround.processing.echo.EchoSolver;
import org.orecruncher.dsurround.processing.echo.EchoTap;
import org.orecruncher.dsurround.processing.echo.EchoWall;

import java.util.List;

/**
 * Stage 1 of the canyon echo: DETECTION ONLY. It finds the reflective walls around the player and
 * solves where an echo from a source at the player's own feet would land, then reports it. Nothing
 * is played from here - the point of this stage is to see, in real terrain, which places produce a
 * usable tap and how late it is, before committing to the audio side.
 */
public final class EchoService extends AbstractClientHandler {

    /** Rebuild the wall set twice a second; the terrain does not change faster than that. */
    private static final int DETECT_INTERVAL_TICKS = 10;
    /** Re-solve the taps this often even without a detection, so the log has a steady sample. */
    private static final int REPORT_INTERVAL_TICKS = 200;

    private final IBlockLibrary blocks;

    private int tickCounter;
    private int reportCounter;
    private List<EchoWall> walls = List.of();
    private List<EchoTap> taps = List.of();
    private String lastReport = "";

    public EchoService(final Configuration config, final IModLog logger, final IBlockLibrary blocks) {
        super("Echo", config, logger);
        this.blocks = blocks;
    }

    @Override
    public void onDisconnect() {
        this.walls = List.of();
        this.taps = List.of();
        this.lastReport = "";
    }

    @Override
    public boolean doTick(final long tick) {
        return ++this.tickCounter >= DETECT_INTERVAL_TICKS;
    }

    @Override
    public void process(final Player player) {
        this.tickCounter = 0;
        // The listener's ear, and the same ear for the probe source: an echo of a sound at the
        // player's own feet is the common case (footsteps, a block broken next to them) and is what
        // a diagnostic walk should be judging.
        final Vec3 ear = player.getEyePosition();
        this.walls = new EchoDetector(player.level(), ear, this.blocks).detect();
        this.taps = EchoSolver.plan(this.walls, player.position(), ear);

        if (++this.reportCounter >= REPORT_INTERVAL_TICKS / DETECT_INTERVAL_TICKS) {
            this.reportCounter = 0;
            final String report = describe(player);
            // Only log when the picture changed: a steady scene must not fill the log.
            if (!report.equals(this.lastReport)) {
                this.lastReport = report;
                if (this.logger.isDebugging())
                    this.logger.debug("[Echo] %s", report);
            }
        }
    }

    private String describe(final Player player) {
        final StringBuilder sb = new StringBuilder();
        sb.append("pos=(%d,%d,%d) walls=%d taps=%d".formatted(
                player.blockPosition().getX(), player.blockPosition().getY(),
                player.blockPosition().getZ(), this.walls.size(), this.taps.size()));
        for (final EchoWall wall : this.walls) {
            sb.append(" wall[az=%.0f d=%.0f refl=%.2f]".formatted(
                    wall.azimuthDeg(), wall.distance(), wall.reflectivity()));
        }
        for (final EchoTap tap : this.taps) {
            sb.append(" tap[%.0fms g=%.3f cut=%.2f -> (%.1f,%.1f,%.1f)]".formatted(
                    tap.delayMs(), tap.gain(), tap.cutoff(),
                    tap.reflection().x, tap.reflection().y, tap.reflection().z));
        }
        return sb.toString();
    }

    @Override
    protected void gatherDiagnostics(final CollectDiagnosticsEvent event) {
        event.add(CollectDiagnosticsEvent.Section.Systems, "Echo: " + this.walls.size() + " walls, "
                + this.taps.size() + " taps");
        for (final EchoTap tap : this.taps)
            event.add(CollectDiagnosticsEvent.Section.Systems,
                    "  echo %.0fms gain %.2f".formatted(tap.delayMs(), tap.gain()));
    }
}
