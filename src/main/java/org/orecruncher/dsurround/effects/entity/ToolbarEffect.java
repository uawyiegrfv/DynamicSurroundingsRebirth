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

        // Hotbar slot scrolling.
        if (this.lastSlot != inventory.getSelectedSlot()) {
            final ItemStack currentStack = inventory.getItem(inventory.getSelectedSlot());
            if (!currentStack.isEmpty() && !player.isSpectator()) {
                this.playEquipSound(info, player, currentStack);
            }
            this.lastSlot = inventory.getSelectedSlot();
        }

        // Held item change without a slot change (e.g. moving an item from the
        // inventory into the selected hotbar slot, or swapping the offhand).
        final ItemStack mainHand = player.getMainHandItem();
        if (!ItemStack.matches(mainHand, this.lastMainHand)) {
            if (!mainHand.isEmpty() && !player.isSpectator()) {
                this.playEquipSound(info, player, mainHand);
            }
            this.lastMainHand = mainHand.copy();
        }

        final ItemStack offHand = player.getOffhandItem();
        if (!ItemStack.matches(offHand, this.lastOffHand)) {
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
