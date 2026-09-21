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

        // Reset the cached dimension info whenever the client world changes, or on a resource reload.
        //
        // LoggingIn alone is NOT "whenever the client world changes": Forge fires it only from
        // ClientPacketListener.handleLogin. A dimension change goes through handleRespawn and fires
        // ClientPlayerNetworkEvent.Clone instead, so a cached overworld DimensionInfo survived a
        // portal trip indefinitely. The consequences were audible and wrong: in the Nether,
        // alwaysOutside() stayed false, so CeilingScanner surveyed the bedrock ceiling, reported it
        // as "inside", and BiomeSoundHandler scaled every biome loop to 15% volume - the Nether
        // ambience went nearly silent. seaLevel() also stayed 63, which sent most Nether positions to
        // the UNDERGROUND/INSIDE synthetic biomes instead of nether ones.
        //
        // Both sibling ports use ClientLifecycleEvent.CLIENT_LEVEL_LOAD for this. That event does not
        // exist in Forge 1.20.1, so the equivalent here is the pair: LoggingIn for the initial join
        // and Clone for every respawn or dimension change.
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> this.info = null);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone event) -> this.info = null);
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

    public boolean playBiomeSounds() {
        return this.getInfo().playBiomeSounds();
    }

    private DimensionInfo getInfo() {
        if (this.info == null)
            this.info = this.dimensionLibrary.getData(this.level());
        return this.info;
    }
}
