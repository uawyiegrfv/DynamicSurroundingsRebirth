package org.orecruncher.dsurround.processing;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.SoundFactoryBuilder;

/**
 * Plays a crafting sound when the local player takes a crafted item from the
 * crafting table (ported from the original 1.12.2 CraftingSoundEffect). The
 * vanilla ItemCraftedEvent fires from ResultSlot#checkTakeAchievements, which
 * runs on the client menu as well, so filtering to the client player's world
 * keeps this strictly client-side. Throttled to one sound per 30 ticks like
 * the original.
 */
public class CraftingSoundEffectHandler {

    private static final Identifier CRAFTING = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "crafting");

    // Original mod throttles crafting sounds to at most one per 30 ticks.
    private static final int CRAFT_SOUND_THROTTLE = 30;

    private final Configuration config;
    private final IAudioPlayer audioPlayer;
    private final ITickCount tickCount;
    private long lastCraftTick = Long.MIN_VALUE;

    public CraftingSoundEffectHandler(Configuration config, IAudioPlayer audioPlayer, ITickCount tickCount) {
        this.config = config;
        this.audioPlayer = audioPlayer;
        this.tickCount = tickCount;
        NeoForge.EVENT_BUS.addListener(this::onItemCrafted);
    }

    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!this.config.entityEffects.enableCraftingSound)
            return;

        if (!(event.getEntity() instanceof Player player))
            return;

        // Only respond when the crafting happened on the client side.
        if (!player.level().isClientSide())
            return;

        final long tick = this.tickCount.getTickCount();
        if (this.lastCraftTick != Long.MIN_VALUE && tick - this.lastCraftTick < CRAFT_SOUND_THROTTLE)
            return;
        this.lastCraftTick = tick;

        var sound = SoundFactoryBuilder.create(CRAFTING)
                .category(SoundSource.PLAYERS)
                .build()
                .createAsAdditional();
        this.audioPlayer.play(sound);
    }
}
