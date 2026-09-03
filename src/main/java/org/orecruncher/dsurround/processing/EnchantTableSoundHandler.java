package org.orecruncher.dsurround.processing;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.ISoundFactory;

import java.util.WeakHashMap;

/**
 * Plays sounds for the enchanting table book animations.  The vanilla client
 * ticker (EnchantingTableBlockEntity#bookAnimationTick) opens the book when a
 * player comes within 3 blocks and randomly turns the book's page target while
 * a player is nearby.  Detection rides on that ticker via
 * MixinEnchantingTableBook, reading the public animation fields:
 *
 * - book opening: oOpen == 0 && open > 0 (first tick of the opening ramp)
 * - page turn:    flipT changed while the book is fully open (vanilla picks a
 *                 new random page target roughly once every 2 seconds, often
 *                 skipping several pages at once)
 *
 * The opening uses the DS heavy page flip; the page turns use the vanilla
 * book page rustle so frequent flips stay subtle.
 */
public final class EnchantTableSoundHandler {

    private static final ResourceLocation BOOK_OPEN = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "enchant/book_open");
    private static final ResourceLocation PAGE_TURN = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "enchant/page_turn");

    private static final Configuration.EntityEffects CONFIG = ContainerManager.resolve(Configuration.EntityEffects.class);

    // Last seen page target per block entity; weak keys so unloaded tables
    // drop out automatically.
    private static final WeakHashMap<EnchantingTableBlockEntity, Float> lastPageTarget = new WeakHashMap<>();

    private EnchantTableSoundHandler() {

    }

    /**
     * Called from MixinEnchantingTableBook at the tail of the vanilla
     * client side book animation ticker.
     */
    public static void bookAnimationTick(final Level level, final BlockPos pos, final EnchantingTableBlockEntity blockEntity) {
        if (!CONFIG.enableEnchantTableSounds)
            return;

        // Strictly client side (the vanilla ticker is client side only anyway).
        if (!level.isClientSide())
            return;

        // Book opening: previous tick fully closed, this tick starting to open.
        if (blockEntity.oOpen == 0.0F && blockEntity.open > 0.0F) {
            play(BOOK_OPEN, pos);
            lastPageTarget.remove(blockEntity);
            return;
        }

        // Page turns: only while the book has been fully open for more than one
        // tick, so the fluttering during the opening animation stays silent.
        if (blockEntity.oOpen >= 1.0F && blockEntity.open >= 1.0F) {
            final Float last = lastPageTarget.get(blockEntity);
            if (last == null) {
                lastPageTarget.put(blockEntity, blockEntity.flipT);
            } else if (last != blockEntity.flipT) {
                lastPageTarget.put(blockEntity, blockEntity.flipT);
                play(PAGE_TURN, pos);
            }
        } else if (blockEntity.open < 1.0F) {
            lastPageTarget.remove(blockEntity);
        }
    }

    private static void play(final ResourceLocation factoryId, final BlockPos pos) {
        GameUtils.getPlayer().ifPresent(player -> {
            // The vanilla animation only runs for tables with a player within
            // 3 blocks; gate to the local player so other players' tables in
            // multiplayer stay silent.
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 3.3D * 3.3D)
                return;

            final ISoundFactory factory = ContainerManager.resolve(ISoundLibrary.class)
                    .getSoundFactory(factoryId)
                    .orElse(null);

            if (factory == null)
                return;

            ContainerManager.resolve(IAudioPlayer.class).play(factory.createAtLocation(pos));
        });
    }
}