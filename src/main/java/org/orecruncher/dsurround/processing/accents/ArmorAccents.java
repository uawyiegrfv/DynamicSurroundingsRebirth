package org.orecruncher.dsurround.processing.accents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IItemLibrary;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.sound.ISoundFactory;

import java.util.Optional;
import java.util.function.Function;

class ArmorAccents implements IFootstepAccentProvider {

    private final Configuration config;
    private final IItemLibrary itemLibrary;

    ArmorAccents(Configuration config, IItemLibrary itemLibrary) {
        this.config = config;
        this.itemLibrary = itemLibrary;
    }

    @Override
    public boolean isEnabled() {
        return this.config.footstepAccents.enableArmorAccents;
    }

    @Override
    public void collect(LivingEntity entity, BlockPos pos, BlockState posState, boolean isWaterLogged, ObjectArray<ISoundFactory> acoustics) {
        // Running uses the heavier run variant, walking the walk variant - matching the
        // original's armor walk/run accents.
        final boolean running = entity.isSprinting();

        // Foot armor uses its dedicated foot accent (e.g. heavy_foot) where available.
        var footAccent = this.itemLibrary.getEquipableFootAccentSound(entity.getItemBySlot(EquipmentSlot.FEET));
        footAccent.ifPresent(acoustics::add);

        java.util.function.Function<ItemStack, Optional<ISoundFactory>> accentResolver = running
                ? this.itemLibrary::getEquipableStepAccentSoundRun
                : this.itemLibrary::getEquipableStepAccentSound;
        var legs = accentResolver.apply(entity.getItemBySlot(EquipmentSlot.LEGS));
        legs.ifPresentOrElse(
                acoustics::add,
                () -> {
                    var chest = accentResolver.apply(entity.getItemBySlot(EquipmentSlot.CHEST));
                    chest.ifPresent(acoustics::add);
                }
        );
    }
}