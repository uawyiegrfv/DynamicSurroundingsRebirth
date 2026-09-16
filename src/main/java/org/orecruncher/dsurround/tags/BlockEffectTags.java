package org.orecruncher.dsurround.tags;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.orecruncher.dsurround.Constants;

import java.util.Collection;
import java.util.HashSet;

public class BlockEffectTags {

    static final Collection<TagKey<Block>> TAGS = new HashSet<>();

    public static final TagKey<Block> FIREFLIES = of("fireflies");
    public static final TagKey<Block> FLOOR_SQUEAKS = of("floor_squeaks");
    public static final TagKey<Block> BRUSH_STEP = of("brush_step");
    public static final TagKey<Block> LEAVES_STEP = of("leaves_step");
    public static final TagKey<Block> STRAW_STEP = of("straw_step");
    public static final TagKey<Block> WATERY_STEP = of("watery_step");
    public static final TagKey<Block> STEAM_PRODUCERS = of("steam_producers");
    public static final TagKey<Block> HEAT_PRODUCERS = of("heat_producers");
    // Referenced by sound_mappings as brick/terracotta, dried-mud and wax families. They
    // need a constant here or TagLibrary.is() answers false for every query and the rules
    // that use them are silently inert.
    public static final TagKey<Block> BRICKSTONE_FAMILY = of("brickstone_family");
    public static final TagKey<Block> DRIED_MUD_BLOCKS = of("dried_mud_blocks");
    public static final TagKey<Block> WAX_BLOCKS = of("wax_blocks");
    // The 1.12.2 "overlay" substrates (carpet / foliage / messy): the blocks that may sit
    // in the same cell as the player's feet and still count as the walked surface.
    public static final TagKey<Block> FOOT_OVERLAY = of("foot_overlay");
    // Soft ground that takes a footprint impression: soil, sand, snow, clay, gravel.
    // Hard or slippery blocks (stone, wood, ICE) are excluded on purpose.
    public static final TagKey<Block> FOOTPRINTABLE = of("footprintable");

    private static TagKey<Block> of(String id) {
        var tagKey = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "effects/" + id));
        TAGS.add(tagKey);
        return tagKey;
    }
}
