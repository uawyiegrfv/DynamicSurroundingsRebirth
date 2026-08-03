package org.orecruncher.dsurround.effects.entity;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.tags.ItemEffectTags;

import java.util.Optional;

/**
 * Plays a "use" sound when a bow/crossbow is drawn or a shield is raised,
 * matching the original 1.12.2 EntityBowSoundEffect (which covered both BOW
 * and SHIELD item classes).
 */
public class BowUseEffect extends EntityEffectBase {

    private static final Identifier BOW_PULL_FACTORY = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "bow_pull");
    private static final Identifier SHIELD_USE_FACTORY = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "shield.use");

    protected ItemStack lastActiveStack = ItemStack.EMPTY;

    @Override
    public void tick(EntityEffectInfo info) {
        if (info.isRemoved()) {
            this.lastActiveStack = ItemStack.EMPTY;
            return;
        }

        var entity = info.getEntity();
        final ItemStack currentStack = entity.getUseItem();
        if (isApplicable(currentStack)) {
            if (!ItemStack.matches(currentStack, this.lastActiveStack)) {
                getUseSoundFactory(currentStack)
                        .ifPresent(f -> {
                            var sound = f.attachToEntity(entity);
                            this.playSound(sound);
                        });
                this.lastActiveStack = currentStack;
            }
        } else {
            this.lastActiveStack = ItemStack.EMPTY;
        }
    }

    private static boolean isApplicable(ItemStack stack) {
        // Crossbows are intentionally excluded: vanilla already plays its own
        // charging sound, and the original 1.12.2 mod predates crossbows.
        return TAG_LIBRARY.is(ItemEffectTags.BOWS, stack)
                || TAG_LIBRARY.is(ItemEffectTags.SHIELDS, stack);
    }

    private static Optional<ISoundFactory> getUseSoundFactory(ItemStack stack) {
        if (TAG_LIBRARY.is(ItemEffectTags.SHIELDS, stack))
            return SOUND_LIBRARY.getSoundFactory(SHIELD_USE_FACTORY);
        return SOUND_LIBRARY.getSoundFactory(BOW_PULL_FACTORY);
    }
}