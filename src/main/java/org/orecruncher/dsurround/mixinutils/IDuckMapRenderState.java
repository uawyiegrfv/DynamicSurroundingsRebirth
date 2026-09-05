package org.orecruncher.dsurround.mixinutils;

/**
 * Duck interface for the MapRenderState mixin: carries the map id and scale from
 * extractRenderState (where the server data is available) to render (where the
 * overlay text is submitted). The vanilla 26.1 render() no longer receives the
 * MapItemSavedData, so these have to ride on the render state.
 */
public interface IDuckMapRenderState {

    int dsurround_getMapId();

    void dsurround_setMapId(int id);

    int dsurround_getScale();

    void dsurround_setScale(int scale);
}
