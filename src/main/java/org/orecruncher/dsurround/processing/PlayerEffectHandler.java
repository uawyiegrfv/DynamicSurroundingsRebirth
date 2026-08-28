package org.orecruncher.dsurround.processing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Handles player self state sounds that were present in the original 1.12.2
 * Dynamic Surroundings: heartbeat when health is low, stomach growls when
 * hungry, and a jump grunt.  These are driven from the local player's state
 * each tick and played through the mod's audio player.
 */
public class PlayerEffectHandler extends AbstractClientHandler {

    private static final ResourceLocation HEARTBEAT = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "player.heartbeat");
    private static final ResourceLocation TUMMY = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "player.tummy");

    // Tick delay between heartbeats. The heartbeat.ogg clip is ~0.81s long, so
    // playing every 16 ticks (0.8s) loops it seamlessly like the original mod's
    // periodic sound (repeatDelay 0 = replay as soon as the clip ends).
    private static final int HEARTBEAT_INTERVAL = 16;

    // Tick delay between stomach growls (15 seconds), matching the original mod's
    // periodic sound repeatDelay of 300.
    private static final int HUNGER_INTERVAL = 300;

    private final IAudioPlayer audioPlayer;
    private final ITickCount tickCount;

    private long lastHeartbeatTick = Long.MIN_VALUE;
    private long lastHungerTick = Long.MIN_VALUE;

    public PlayerEffectHandler(Configuration config, IAudioPlayer audioPlayer, ITickCount tickCount, IModLog logger) {
        super("Player Effects", config, logger);
        this.audioPlayer = audioPlayer;
        this.tickCount = tickCount;
    }

    @Override
    public void process(final Player player) {
        handleHeartbeat(player);
        handleHunger(player);
        // Jump and landing sounds are handled by FootstepGenerator (which uses the same
        // enablePlayerJumpSound / enablePlayerLandSound config options).
    }

    private void handleHeartbeat(final Player player) {
        var settings = this.config.entityEffects;
        if (!settings.enablePlayerHeartbeatSound)
            return;

        if (player.isCreative() || player.isSpectator() || !player.isAlive())
            return;

        if (settings.playerHurtThreshold <= 0D)
            return;

        final float threshold = (float) (settings.playerHurtThreshold * player.getMaxHealth());
        if (player.getHealth() > threshold)
            return;

        final long tick = this.tickCount.getTickCount();
        if (this.lastHeartbeatTick == Long.MIN_VALUE)
            this.lastHeartbeatTick = tick;

        if (tick - this.lastHeartbeatTick >= HEARTBEAT_INTERVAL) {
            this.lastHeartbeatTick = tick;
            var sound = SoundFactoryBuilder.create(HEARTBEAT)
                    .category(SoundSource.PLAYERS)
                    .build()
                    .createAsAdditional();
            this.audioPlayer.play(sound);
        }
    }

    private void handleHunger(final Player player) {
        var settings = this.config.entityEffects;
        if (!settings.enablePlayerHungerSound)
            return;

        if (player.isCreative() || player.isSpectator() || !player.isAlive())
            return;

        if (settings.playerHungerThreshold <= 0)
            return;

        final int threshold = settings.playerHungerThreshold;
        if (player.getFoodData().getFoodLevel() > threshold)
            return;

        final long tick = this.tickCount.getTickCount();
        if (this.lastHungerTick == Long.MIN_VALUE)
            this.lastHungerTick = tick;

        if (tick - this.lastHungerTick >= HUNGER_INTERVAL) {
            this.lastHungerTick = tick;
            var sound = SoundFactoryBuilder.create(TUMMY)
                    .category(SoundSource.PLAYERS)
                    .build()
                    .createAsAdditional();
            this.audioPlayer.play(sound);
        }
    }

    @Override
    public void onConnect() {
        this.lastHeartbeatTick = Long.MIN_VALUE;
        this.lastHungerTick = Long.MIN_VALUE;
    }

    @Override
    public void onDisconnect() {
        this.lastHeartbeatTick = Long.MIN_VALUE;
        this.lastHungerTick = Long.MIN_VALUE;
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var settings = this.config.entityEffects;
        var text = "Player effects: heartbeat=%s, hunger=%s, jump=%s".formatted(
                settings.enablePlayerHeartbeatSound ? "enabled" : "disabled",
                settings.enablePlayerHungerSound ? "enabled" : "disabled",
                settings.enablePlayerJumpSound ? "enabled" : "disabled");
        event.add(CollectDiagnosticsEvent.Section.Systems, text);
    }
}
