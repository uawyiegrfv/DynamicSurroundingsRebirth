package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 1.21.1: MapItemSavedData.decorations is package-private with no public getter
 * (the getDecorations() accessor only appeared in 26.1). Expose it for the
 * treasure-map distance overlay.
 */
@Mixin(MapItemSavedData.class)
public interface MixinMapItemSavedData {

    @Accessor("decorations")
    Map<String, MapDecoration> dsurround_getDecorations();
}
