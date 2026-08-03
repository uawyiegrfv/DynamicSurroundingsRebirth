package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
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
 * every ~20-40 seconds while the world is thundering.
 */
public class ThunderHandler {

    private static final Identifier THUNDER = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "thunder");

    // Interval bounds in ticks between background rumbles (20s / 40s).
    private static final int MIN_INTERVAL = 400;
    private static final int MAX_INTERVAL = 800;

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
        if (!world.isThundering()) {
            this.nextThunderTick = 0;
            return;
        }

        final long tick = this.tickCount.getTickCount();
        if (this.nextThunderTick == 0)
            this.nextThunderTick = tick + MIN_INTERVAL + this.random.nextInt(MAX_INTERVAL - MIN_INTERVAL);
        if (tick < this.nextThunderTick)
            return;

        this.nextThunderTick = tick + MIN_INTERVAL + this.random.nextInt(MAX_INTERVAL - MIN_INTERVAL);

        // Pick a distant random position around the player.
        final double angle = this.random.nextDouble() * 2.0 * Math.PI;
        final double dist = 40.0 + this.random.nextDouble() * 40.0;
        final double x = player.getX() + Math.cos(angle) * dist;
        final double z = player.getZ() + Math.sin(angle) * dist;
        final double y = player.getY() + 10.0 + this.random.nextDouble() * 20.0;

        var sound = SoundFactoryBuilder.create(THUNDER)
                .category(SoundSource.WEATHER)
                .build()
                .createAtLocation(x, y, z, 1.0F);
        this.audioPlayer.play(sound);
        this.logger.info("[ThunderHandler] Background thunder at (%.0f,%.0f,%.0f)", x, y, z);
    }
}
