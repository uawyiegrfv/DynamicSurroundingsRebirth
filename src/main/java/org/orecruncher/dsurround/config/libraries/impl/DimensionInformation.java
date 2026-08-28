package org.orecruncher.dsurround.config.libraries.impl;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.config.DimensionInfo;
import org.orecruncher.dsurround.config.libraries.AssetLibraryEvent;
import org.orecruncher.dsurround.config.libraries.IDimensionInformation;
import org.orecruncher.dsurround.config.libraries.IDimensionLibrary;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.events.HandlerPriority;

public class DimensionInformation implements IDimensionInformation {

    private final IDimensionLibrary dimensionLibrary;
    private DimensionInfo info;

    public DimensionInformation(IDimensionLibrary dimensionLibrary) {
        this.dimensionLibrary = dimensionLibrary;

        // Need to reset the cached dimension info whenever the client world
        // changes or if there is a resource reload.
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> this.info = null);
        AssetLibraryEvent.RELOAD.register((x, y) -> this.info = null, HandlerPriority.HIGH);
    }

    public ResourceLocation name() {
        return this.getInfo().getName();
    }

    public ClientLevel level() {
        return GameUtils.getWorld().orElseThrow();
    }

    public int seaLevel() {
        return this.getInfo().getSeaLevel();
    }

    public boolean alwaysOutside() {
        return this.getInfo().alwaysOutside();
    }

    public int getSpaceHeight() {
        return this.getInfo().getSpaceHeight();
    }

    public int getCloudHeight() {
        return this.getInfo().getCloudHeight();
    }

    public boolean getCompassWobble() {
        return this.getInfo().getCompassWobble();
    }

    private DimensionInfo getInfo() {
        if (this.info == null)
            this.info = this.dimensionLibrary.getData(this.level());
        return this.info;
    }
}
