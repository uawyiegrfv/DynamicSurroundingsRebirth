package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.renderer.state.MapRenderState;
import org.orecruncher.dsurround.mixinutils.IDuckMapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries the map id and scale on the render state so MixinMapRenderer can compute
 * the treasure distance during render(): the vanilla 26.1 render() only receives the
 * state, while the id/scale are known at extractRenderState time.
 */
@Mixin(MapRenderState.class)
public class MixinMapRenderState implements IDuckMapRenderState {

    @Unique
    private int dsurround_mapId = -1;

    @Unique
    private int dsurround_scale = 0;

    @Override
    public int dsurround_getMapId() {
        return this.dsurround_mapId;
    }

    @Override
    public void dsurround_setMapId(int id) {
        this.dsurround_mapId = id;
    }

    @Override
    public int dsurround_getScale() {
        return this.dsurround_scale;
    }

    @Override
    public void dsurround_setScale(int scale) {
        this.dsurround_scale = scale;
    }
}
