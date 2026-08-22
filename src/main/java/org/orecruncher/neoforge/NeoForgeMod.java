package org.orecruncher.neoforge;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.gui.overlay.OverlayManager;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.processing.aurora.AuroraRenderPipelines;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeMod {

    private final Client client;

    public NeoForgeMod(ModContainer container, IEventBus modBus) {
        modBus.addListener(this::onRegisterGuiLayersEvent);
        modBus.addListener(AuroraRenderPipelines::onRegisterPipelines);

        this.client = new Client();
        this.client.construct();
        this.client.initializeClient();

        if (ModList.get().isLoaded(Constants.CLOTH_CONFIG_NEOFORGE))
            container.registerExtensionPoint(IConfigScreenFactory.class, new ModConfigMenu());

        // 26.1: TagCollector was folded into RegistryDataCollector; fire DS's tag sync
        // event from the native NeoForge hook instead of a mixin.
        NeoForge.EVENT_BUS.addListener(this::onTagsUpdated);
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent.ClientPacketReceived event) {
        ClientState.TAG_SYNC.raise().onTagSync(event.getRegistries());
    }

    @SubscribeEvent
    public void onRegisterGuiLayersEvent(RegisterGuiLayersEvent event) {
        // Add the overlay manager to the render layers of Gui. 26.1: the layer
        // callback receives a GuiGraphicsExtractor.
        OverlayManager dsurround_overlayManager = ContainerManager.resolve(OverlayManager.class);
        event.registerBelowAll(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "layer/overlaymanager"), dsurround_overlayManager::render);

        // Crit words are drawn by a GUI layer after projecting their world position.
        var critWordHandler = ContainerManager.resolve(org.orecruncher.dsurround.processing.CritWordHandler.class);
        event.registerBelowAll(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "layer/critwords"), critWordHandler::renderGui);

        // Desert yellow dust haze overlay (A17).
        var weatherStormHandler = ContainerManager.resolve(org.orecruncher.dsurround.processing.WeatherStormHandler.class);
        event.registerBelowAll(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "layer/weatherstorm"), weatherStormHandler::renderGui);

        // Quick per-sound volume overlay (hold Ctrl+`).
        var quickVolumeOverlay = ContainerManager.resolve(org.orecruncher.dsurround.gui.overlay.QuickSoundVolumeOverlay.class);
        event.registerBelowAll(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "layer/quicksoundvolume"), quickVolumeOverlay::render);
    }
}
