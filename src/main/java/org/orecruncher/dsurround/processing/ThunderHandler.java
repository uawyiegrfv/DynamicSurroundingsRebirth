package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Distant background thunder rumbling during storms, ported from the original
 * 1.12.2 WeatherHandler. The original fired a ThunderEvent on a simulated storm
 * tracker; here we simply play the thunder sound at a random distant position
 * while the world is thundering, paced by N6 - 15 to 60 seconds, denser and
 * louder the harder it is raining.
 */
public class ThunderHandler {

    private static final Identifier THUNDER = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "thunder");

    // N6 (1.12.2 SimulationTracker.nextThunderEvent): the wait until the next
    // background rumble, in ticks. The floor never moves; the span is what
    // intensity buys back, so a drizzle waits 15-60s between rolls and a
    // downpour 15-37s.
    private static final int N6_FLOOR_TICKS = 300;
    private static final int N6_SPAN_TICKS = 450;

    /**
     * Rumble level at zero intensity, as a fraction of full. The gate below is
     * vanilla's {@code isThundering()}, which is already the far end of the
     * range, so the level is what tells a heavy storm's roll apart from a light
     * one's - a distant roll carries further in a downpour.
     */
    private static final float VOLUME_FLOOR = 0.45F;

    /**
     * What "distant" costs, as a fraction of full scale. The rumble is placed
     * out of reach of attenuation on purpose, so this is the only thing that
     * says how far away it is - and it is why the level is written as an
     * absolute here instead of riding the data file's.
     */
    private static final float RUMBLE_LEVEL = 0.7F;

    // Diagnostics. A rumble every quarter minute to a minute is impossible to
    // sit and wait for, and without a countdown there is no way to tell "not
    // due yet" from "never going to fire" - which is exactly the confusion that
    // sent this handler back for a second look.
    private static volatile long nextRumbleDue = 0L;
    private static volatile long lastSeenTick = 0L;
    private static volatile float lastRumbleVolume = 0F;

    /** Seconds until the next rumble and the level it will fire at, or "none". */
    public static String thunderDiagnostics() {
        final long due = nextRumbleDue;
        if (due <= 0L)
            return "none";
        return "%.0fs@%.2f".formatted(Math.max(0L, due - lastSeenTick) / 20F, lastRumbleVolume);
    }

    private final Configuration config;
    private final IModLog logger;
    private final IAudioPlayer audioPlayer;
    private final ITickCount tickCount;
    private final IRandomizer random = Randomizer.current();

    private long nextThunderTick = 0;

    public ThunderHandler(Configuration config, IModLog logger, IAudioPlayer audioPlayer, ITickCount tickCount) {
        this.config = config;
        this.logger = logger;
        this.audioPlayer = audioPlayer;
        this.tickCount = tickCount;
        ClientState.TICK_END.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        if (!this.config.soundOptions.enableBackgroundThunder)
            return;
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;

        var player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return;

        var world = player.level();
        // The gate is vanilla's, and it is already the far end of the range -
        // a shower has no distant rumble. A lock overrides it: /dsprecip lock
        // asks what the chain does at one intensity, and "is it thundering" is
        // a separate roll the player may not have sitting in front of them.
        if (!world.isThundering() && PrecipitationIntensity.forced() < 0F) {
            this.nextThunderTick = 0;
            nextRumbleDue = 0L;
            return;
        }

        // N6: the rumble is paced by how hard it is raining, not by a flat
        // clock. Intensity is read ungated - distant thunder is weather
        // happening around the player, not something landing on them, so it
        // must not stop because they stepped under a roof.
        final float intensity = Mth.clamp(PrecipitationIntensity.ambient(), 0F, 1F);

        final long tick = this.tickCount.getTickCount();
        lastSeenTick = tick;
        if (this.nextThunderTick == 0)
            this.nextThunderTick = tick + this.nextInterval(intensity);
        if (tick < this.nextThunderTick) {
            nextRumbleDue = this.nextThunderTick;
            return;
        }

        this.nextThunderTick = tick + this.nextInterval(intensity);
        nextRumbleDue = this.nextThunderTick;

        // Pick a distant random position around the player.
        final double angle = this.random.nextDouble() * 2.0 * Math.PI;
        final double dist = 40.0 + this.random.nextDouble() * 40.0;
        final double x = player.getX() + Math.cos(angle) * dist;
        final double z = player.getZ() + Math.sin(angle) * dist;
        final double y = player.getY() + 10.0 + this.random.nextDouble() * 20.0;

        final float volume = RUMBLE_LEVEL * (VOLUME_FLOOR + (1F - VOLUME_FLOOR) * intensity);

        // No attenuation of its own. The rumble is placed 40-80 blocks out on
        // purpose - it is meant to sound like weather happening somewhere else
        // - and LINEAR attenuation dies at 16 blocks, so every one of these was
        // being played at zero. Distance is carried by the level instead, which
        // is also what the intensity term is for.
        var sound = SoundFactoryBuilder.create(THUNDER)
                .category(SoundSource.WEATHER)
                .build()
                .createAtLocationNoAttenuation(x, y, z, volume);
        this.audioPlayer.play(sound);
        lastRumbleVolume = volume;
        this.logger.debug("[ThunderHandler] rumble at (%.0f,%.0f,%.0f) intensity=%.2f volume=%.2f next=%d",
                x, y, z, intensity, volume, this.nextThunderTick - tick);
    }

    /**
     * N6 - 1.12.2's {@code nextThunderEvent(intensity)} unchanged:
     * {@code nextInt(450 * (2 - intensity)) + 300}. Intensity is 0..1 so the
     * span runs 450..900, i.e. 15-37s between rolls at full intensity against
     * 15-60s at none.
     */
    private int nextInterval(float intensity) {
        final int span = Math.max(1, (int) (N6_SPAN_TICKS * (2F - intensity)));
        return N6_FLOOR_TICKS + this.random.nextInt(span);
    }
}
