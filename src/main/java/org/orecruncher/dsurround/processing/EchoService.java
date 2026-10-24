package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Stage 3 is a BLACKLIST, not a whitelist: modded guns, cannons and aircraft cannot be
     * enumerated, so unknown events default to eligible and the known-bad families are excluded.
     */
    private static final String[] BLACKLIST_PREFIXES = {
            "ambient.weather",      // rain / wind beds
            "ambient.cave",         // cave ambience
            "music.", "ui.",        // music and interface
            "block.bubble_column",  // underwater loop
            "entity.ender_dragon.ambient", // long, low, would only smear
    };
    /** Event-name fragments that mark a continuous / repetitive family rather than a transient. */
    private static final String[] BLACKLIST_FRAGMENTS = {
            ".step", ".footstep", ".swim", ".splash", ".loop", ".breath", ".idle",
    };
    private static final float MIN_ELIGIBLE_VOLUME = 0.15F;
    private static final int MAX_ECHOES_PER_SECOND = 4;
    /** A sound event repeating faster than this is a bed, not a transient. */
    private static final int REPEAT_LIMIT = 3;
    private static final long REPEAT_WINDOW_MS = 1000L;
    /** Echoes are suppressed while the rain/wind bed is loud: they would be masked anyway. */
    private static final float MASKING_AMBIENT = 0.6F;
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
        // Probe from a realistic source position, NOT the player's feet: the foot position has the
        // same x/z as the ear, so a == e and the mirror condition (e > a) rejects every wall. That
        // made the diagnostic read taps=0 everywhere while a real sound a few blocks away would
        // have echoed. Prefer the last sound that played; otherwise two blocks ahead of the eyes.
        Vec3 probe = lastSoundPos;
        if (probe == null || probe.distanceTo(ear) > 64.0D)
            probe = ear.add(player.getLookAngle().scale(2.0D));
        this.taps = EchoSolver.plan(this.walls, probe, ear);

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
        final SoundSource source = sound.getSource();
        final boolean thunder = source == SoundSource.WEATHER || isThunder(sound);
        lastSoundPos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        if (sound.isRelative() || sound.isLooping())
            return;
        if (source == SoundSource.MUSIC || source == SoundSource.MASTER)
            return;
        if (sound.getVolume() < MIN_ELIGIBLE_VOLUME)
            return;
        if (!thunder && !isEligible(sound))
            return;
        final var service = INSTANCE;
        final var walls = currentWalls;
        if (service == null || walls.isEmpty()) {
            reject(sound, "no-walls");
            return;
        }
        final var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;
        final List<EchoTap> taps = EchoSolver.plan(walls, lastSoundPos, player.getEyePosition());
        if (taps.isEmpty()) {
            reject(sound, "no-tap");
            return;
        }
        if (!takeBudget()) {
            reject(sound, "budget");
            return;
        }
        // Rain and wind DO mask an echo, but suppressing it entirely meant nothing ever fired while
        // it was raining - and that is exactly when thunder happens. Scale it instead.
        final float ambient = org.orecruncher.dsurround.processing.PrecipitationIntensity.ambient();
        final float mask = thunder ? 1.0F
                : Math.max(0.35F, Math.min(1.0F, 1.0F - 0.5F * ambient));
        final int count = thunder ? 1 : Math.min(2, taps.size());
        final float gainScale = (float) (thunder ? 0.5F : 1.0F) * (float) ECHO_CONFIG.canyonEchoVolume * mask;
        final float cutoffScale = thunder ? 0.7F : 1.0F;
        if (service.logger.isDebugging()) {
            final EchoTap first = taps.get(0);
            service.logger.debug("[Echo] %s: %d tap(s) walls=%d delay=%.0fms gain=%.2f cut=%.2f mask=%.2f at (%.1f,%.1f,%.1f)",
                    thunder ? "thunder roll" : sound.getLocation(), taps.size(), walls.size(),
                    first.delayMs(), first.gain(), first.cutoff(), mask,
                    first.reflection().x, first.reflection().y, first.reflection().z);
        }
        for (int i = 0; i < count; i++) {
            final EchoTap tap = taps.get(i);
            final int delayTicks = Math.max(1, Math.round(tap.delayMs() / 50.0F));
            final float volume = Math.min(1.0F, sound.getVolume() * tap.gain() * gainScale);
            if (volume < 0.02F)
                continue;
            Minecraft.getInstance().getSoundManager().play(new EchoSoundInstance(
                    sound.getLocation(), source, volume, sound.getPitch(),
                    org.orecruncher.dsurround.lib.random.Randomizer.current(), delayTicks,
                    tap.reflection().x, tap.reflection().y, tap.reflection().z,
                    Math.max(0.1F, tap.cutoff() * cutoffScale)));
        }
    }

    /** Why a candidate produced no echo. Rate-limited: one line a second at debug level. */
    private static void reject(final SoundInstance sound, final String reason) {
        final long now = System.currentTimeMillis();
        if (now - lastRejectLog < 1000L)
            return;
        lastRejectLog = now;
        final var service = INSTANCE;
        if (service != null && service.logger.isDebugging())
            service.logger.debug("[Echo] skip %s reason=%s walls=%d ambient=%.2f",
                    sound.getLocation(), reason, currentWalls.size(),
                    org.orecruncher.dsurround.processing.PrecipitationIntensity.ambient());
    }

    private static volatile Vec3 lastSoundPos;
    private static long lastRejectLog;

    /** Blacklist + repeat-rate gate. Unknown events pass: a modded gun should echo. */
    private static boolean isEligible(final SoundInstance sound) {
        final ResourceLocation id = sound.getLocation();
        final String path = id.getPath();
        for (final String prefix : BLACKLIST_PREFIXES)
            if (path.startsWith(prefix))
                return false;
        for (final String fragment : BLACKLIST_FRAGMENTS)
            if (path.contains(fragment))
                return false;
        return !isBed(id);
    }

    /** A sound event arriving more than {@link #REPEAT_LIMIT} times a second is a bed or a loop. */
    private static boolean isBed(final ResourceLocation id) {
        final long now = System.currentTimeMillis();
        final long[] entry = REPEATS.computeIfAbsent(id, k -> new long[]{now, 0L});
        synchronized (entry) {
            if (now - entry[0] >= REPEAT_WINDOW_MS) {
                entry[0] = now;
                entry[1] = 0L;
            }
            entry[1]++;
            if (entry[1] > REPEAT_LIMIT)
                return true;
        }
        return false;
    }

    private static boolean isThunder(final SoundInstance sound) {
        final String path = sound.getLocation().getPath();
        return path.contains("thunder") || path.contains("lightning");
    }

    private static final java.util.Map<ResourceLocation, long[]> REPEATS = new java.util.concurrent.ConcurrentHashMap<>();

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
