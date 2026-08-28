package org.orecruncher.dsurround.config.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.orecruncher.dsurround.config.ItemTypeMatcher;
import org.orecruncher.dsurround.lib.IMatcher;
import org.orecruncher.dsurround.lib.IdentityUtils;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven per-item equip/swing sound override (item_sounds.json).
 *
 * <p>Selectors in {@code items} are item IDs ({@code "minecraft:netherite_sword"}) or item
 * tags ({@code "#minecraft:swords"}). {@code equip} and {@code swing} are optional
 * sound-factory locations (see {@code sound_factories.json}) that override the item-class
 * default for that action. Rules are processed in order; the first match wins.</p>
 */
public record ItemSoundConfigRule(List<IMatcher<Item>> items, Optional<Identifier> equip, Optional<Identifier> swing) {

    public static final Codec<ItemSoundConfigRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(ItemTypeMatcher.CODEC).fieldOf("items").forGetter(ItemSoundConfigRule::items),
            IdentityUtils.CODEC.optionalFieldOf("equip").forGetter(ItemSoundConfigRule::equip),
            IdentityUtils.CODEC.optionalFieldOf("swing").forGetter(ItemSoundConfigRule::swing)
    ).apply(instance, ItemSoundConfigRule::new));
}
