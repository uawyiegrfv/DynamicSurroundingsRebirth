package org.orecruncher.dsurround.tags;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.orecruncher.dsurround.Constants;

import java.util.Collection;
import java.util.HashSet;

public class ItemEffectTags {

    static final Collection<TagKey<Item>> TAGS = new HashSet<>();

    public static final TagKey<Item> AXES = of("axes");
    public static final TagKey<Item> BOOKS = of("books");
    public static final TagKey<Item> BOWS = of("bows");
    public static final TagKey<Item> CROSSBOWS = of("crossbows");
    public static final TagKey<Item> POTIONS = of("potions");
    public static final TagKey<Item> SHIELDS = of("shields");
    public static final TagKey<Item> SWORDS = of("swords");
    public static final TagKey<Item> TOOLS = of("tools");
    public static final TagKey<Item> COMPASSES = of("compasses");
    public static final TagKey<Item> COMPASS_WOBBLE = of("compass_wobble");
    public static final TagKey<Item> CLOCKS = of("clocks");
    public static final TagKey<Item> ARMOR_LEATHER = of("armor/leather");
    public static final TagKey<Item> ARMOR_CHAIN = of("armor/chain");
    public static final TagKey<Item> ARMOR_IRON = of("armor/iron");
    public static final TagKey<Item> ARMOR_GOLD = of("armor/gold");
    public static final TagKey<Item> ARMOR_DIAMOND = of("armor/diamond");
    public static final TagKey<Item> ARMOR_NETHERITE = of("armor/netherite");

    /**
     * Legacy compat class. In 1.12.2 this was Construct's Armory (c4.conarm) slime armor,
     * which had an acoustic of its own; the recordings still ship (armor.slimey_walk / _run).
     * No modern mod provides slime armor, so this tag ships EMPTY - it exists so a mod, or a
     * pack's own dsconfigs data, can opt in without this code learning a mod-specific rule.
     */
    public static final TagKey<Item> ARMOR_SLIMEY = of("armor/slimey");

    private static TagKey<Item> of(String id) {
        var tagKey = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "effects/" + id));
        TAGS.add(tagKey);
        return tagKey;
    }

}
