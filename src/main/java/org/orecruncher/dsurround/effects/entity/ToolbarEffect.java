package org.orecruncher.dsurround.effects.entity;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.config.libraries.IItemLibrary;

/**
 * Plays an equip sound when the item in the player's hand changes.  Beyond the
 * hotbar slot scrolling handled by the original, this also catches items moved
 * from the inventory into the selected hotbar slot (or into the offhand), which
 * changes the held item without changing the selected slot.
 *
 * <p>Trigger semantics follow the original 1.12.2 PlayerToolBarSoundEffect: the
 * slot check and the main-hand item check are folded into ONE trigger so a
 * single scroll step plays exactly one sound, and sounds are never cut short -
 * scrolling quickly plays each step's equip sound to completion (they overlap,
 * which is the original's continuous "scroll ratchet" feel).  An earlier
 * iteration stopped the previous equip sound before playing the new one; that
 * truncated every intermediate sound to a ~50ms blip, leaving only the slot
 * the scroll ended on audible.
 */
public class ToolbarEffect extends EntityEffectBase {

    private final IItemLibrary itemLibrary;

    private int lastSlot = -1;
    private ItemStack lastMainHand = ItemStack.EMPTY;
    private ItemStack lastOffHand = ItemStack.EMPTY;

    public ToolbarEffect(IItemLibrary itemLibrary) {
        this.itemLibrary = itemLibrary;
    }

    @Override
    public void tick(final EntityEffectInfo info) {
        if (info.isRemoved())
            return;

        final Player player = (Player) info.getEntity();
        var inventory = player.getInventory();

        // First time through we want to not trigger the equip sound
        if (this.lastSlot == -1) {
            this.lastSlot = inventory.getSelectedSlot();
            this.lastMainHand = player.getMainHandItem().copy();
            this.lastOffHand = player.getOffhandItem().copy();
            return;
        }

        // Main hand: a hotbar slot change is an equip trigger even when the newly
        // selected slot holds the same item type (1.12.2 triggerNewEquipSound =
        // "lastSlot != currentItem || heldItem != lastHeld"). Folding the slot check
        // into this same trigger guarantees one sound per scroll step; a separate
        // slot check would fire again in the same tick because scrolling also swaps
        // the main-hand stack.
        // isSameItem only compares the item type, so durability loss does not
        // falsely trigger the equip sound.
        final ItemStack mainHand = player.getMainHandItem();
        final boolean mainHandTriggered =
                this.lastSlot != inventory.getSelectedSlot() || !ItemStack.isSameItem(mainHand, this.lastMainHand);

        if (mainHandTriggered) {
            if (!mainHand.isEmpty() && !player.isSpectator()) {
                this.playEquipSound(info, player, mainHand);
            }
            this.lastMainHand = mainHand.copy();
        }
        this.lastSlot = inventory.getSelectedSlot();

        // Off hand is an independent trigger (1.12.2 HandTracker): item swap only.
        final ItemStack offHand = player.getOffhandItem();
        if (!ItemStack.isSameItem(offHand, this.lastOffHand)) {
            if (!offHand.isEmpty() && !player.isSpectator()) {
                this.playEquipSound(info, player, offHand);
            }
            this.lastOffHand = offHand.copy();
        }
    }

    private void playEquipSound(final EntityEffectInfo info, final Player player, final ItemStack stack) {
        this.itemLibrary.getItemEquipSound(stack).ifPresent(factory -> {
            SoundInstance instance;
            if (info.isCurrentPlayer(player))
                instance = factory.attachToEntity(player);
            else
                instance = factory.createAtLocation(player);
            this.playSound(instance);
        });
    }
}
