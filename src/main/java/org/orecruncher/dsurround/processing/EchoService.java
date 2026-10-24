package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IBlockLibrary;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.echo.EchoDetector;
import org.orecruncher.dsurround.processing.echo.EchoSolver;
import org.orecruncher.dsurround.processing.echo.EchoTap;
import org.orecruncher.dsurround.processing.echo.EchoWall;
import org.orecruncher.dsurround.sound.EchoSoundInstance;

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

    private static final Identifier TNT_EXPLODE = Identifier.fromNamespaceAndPath("minecraft", "entity.generic.explode");
    /** Stage 2: one event only, and the switch defaults to off. */
    private static final float MIN_ELIGIBLE_VOLUME = 0.15F;
    private static final int MAX_ECHOES_PER_SECOND = 4;
    private static final Configuration.EnhancedSounds ECHO_CONFIG =
            ContainerManager.resolve(Configuration.EnhancedSounds.class);

    /** Published for the sound-play hook, which runs outside this handler's thread context. */
    private static volatile EchoService INSTANCE;
    private static volatile List<EchoWall> currentWalls = List.of();
    private static long budgetWindowStart;
    private static int budgetUsed;

    private final IBlockLibrary blocks;

    private int tickCounter;
    private int reportCounter;
    private List<EchoWall> walls = List.of();
    private List<EchoTap> taps = List.of();
    private String lastReport = "";

    public EchoService(final Configuration config, final IModLog logger, final IBlockLibrary blocks) {
        super("Echo", config, logger);
        this.blocks = blocks;
        INSTANCE = this;
    }

    @Override
    public void onDisconnect() {
        this.walls = List.of();
        this.taps = List.of();
        currentWalls = List.of();
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
        currentWalls = this.walls;
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

    /**
     * Called for every sound the enhanced pipeline accepts. Stage 2 answers only for a TNT
     * explosion: it is the one event in vanilla that is short, loud and rare enough for a canyon
     * echo to be heard over the ambience, and it gives a clean A/B against the switch.
     */
    public static void onSoundPlay(final SoundInstance sound) {
        if (!ECHO_CONFIG.enableCanyonEcho || sound instanceof EchoSoundInstance)
            return;
        // Only the explosion event for now. Broadening this is stage 3's job (a blacklist, not a
        // whitelist: modded guns and cannons cannot be enumerated).
        if (!TNT_EXPLODE.equals(sound.getIdentifier()))
            return;
        if (sound.isRelative() || sound.getSource() == SoundSource.MUSIC
                || sound.getSource() == SoundSource.MASTER)
            return;
        if (sound.getVolume() < MIN_ELIGIBLE_VOLUME)
            return;
        final var service = INSTANCE;
        final var walls = currentWalls;
        if (service == null || walls.isEmpty())
            return;
        final var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;
        final List<EchoTap> taps = EchoSolver.plan(walls,
                new Vec3(sound.getX(), sound.getY(), sound.getZ()), player.getEyePosition());
        if (taps.isEmpty() || !takeBudget())
            return;
        if (INSTANCE.logger.isDebugging()) {
            final EchoTap first = taps.get(0);
            INSTANCE.logger.debug("[Echo] TNT echo: %d tap(s) walls=%d delay=%.0fms gain=%.2f cut=%.2f at (%.1f,%.1f,%.1f)",
                    taps.size(), walls.size(), first.delayMs(), first.gain(), first.cutoff(),
                    first.reflection().x, first.reflection().y, first.reflection().z);
        }
        // One, or at most two taps even in a canyon: more than that reads as reverberation, which
        // the EFX sends already provide.
        final int count = Math.min(2, taps.size());
        for (int i = 0; i < count; i++) {
            final EchoTap tap = taps.get(i);
            final int delayTicks = Math.max(1, Math.round(tap.delayMs() / 50.0F));
            final float volume = (float) Math.min(1.0F,
                    sound.getVolume() * tap.gain() * ECHO_CONFIG.canyonEchoVolume);
            if (volume < 0.02F)
                continue;
            Minecraft.getInstance().getSoundManager().play(new EchoSoundInstance(
                    sound.getIdentifier(), sound.getSource(), volume, sound.getPitch(),
                    org.orecruncher.dsurround.lib.random.Randomizer.current(), delayTicks,
                    tap.reflection().x, tap.reflection().y, tap.reflection().z, tap.cutoff()));
        }
    }

    /** At most {@link #MAX_ECHOES_PER_SECOND} echoes a second, so a barrage cannot turn into a chorus. */
    private static boolean takeBudget() {
        final long now = System.currentTimeMillis();
        if (now - budgetWindowStart >= 1000L) {
            budgetWindowStart = now;
            budgetUsed = 0;
        }
        if (budgetUsed >= MAX_ECHOES_PER_SECOND)
            return false;
        budgetUsed++;
        return true;
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
