package org.orecruncher.neoforge;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.gui.overlay.OverlayManager;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.processing.aurora.AuroraRenderPipelines;

@Mod(value = Constants.MOD_ID)
public final class NeoForgeMod {

    private Client client;

    public NeoForgeMod(ModContainer container, IEventBus modBus) {
        // Common (both sides): register the server->client network payloads. Optional
        // so a client without DS can still connect (the server-side sender checks for
        // DS before sending).
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1.0").optional();
            registrar.playToClient(org.orecruncher.dsurround.network.WeatherPayload.TYPE,
                    org.orecruncher.dsurround.network.WeatherPayload.STREAM_CODEC,
                    org.orecruncher.dsurround.network.WeatherPayload::handle);
            registrar.playToClient(org.orecruncher.dsurround.network.MapCenterPayload.TYPE,
                    org.orecruncher.dsurround.network.MapCenterPayload.STREAM_CODEC,
                    org.orecruncher.dsurround.network.MapCenterPayload::handle);
        });

        // Server side (also fires in single-player's integrated server): push the
        // overworld weather to nether players.
        NeoForge.EVENT_BUS.register(new org.orecruncher.dsurround.server.WeatherSyncService());
        NeoForge.EVENT_BUS.register(new org.orecruncher.dsurround.server.MapCenterSyncService());

        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(this::onRegisterGuiLayersEvent);
            modBus.addListener(AuroraRenderPipelines::onRegisterShaders);

            this.client = new Client();
            this.client.construct();
            this.client.initializeClient();

            if (ModList.get().isLoaded(Constants.CLOTH_CONFIG_NEOFORGE))
                container.registerExtensionPoint(IConfigScreenFactory.class, new ModConfigMenu());
        }
        // Dedicated server: no client-side registration here. Server-only logic
        // (network payload registration) is wired up in later stages.
    }

    @SubscribeEvent
    public void onRegisterGuiLayersEvent(RegisterGuiLayersEvent event) {
        // Add the overlay manager to the render layers of Gui. 26.1: the layer
        // callback receives a GuiGraphics.
        OverlayManager dsurround_overlayManager = ContainerManager.resolve(OverlayManager.class);
        event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "layer/overlaymanager"), dsurround_overlayManager::render);

        // Crit words are drawn by a GUI layer after projecting their world position.
        var critWordHandler = ContainerManager.resolve(org.orecruncher.dsurround.processing.CritWordHandler.class);
        event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "layer/critwords"), critWordHandler::renderGui);

        // Speech bubbles project to the screen the same way (player chat + entity chat).
        var speechBubbleHandler = ContainerManager.resolve(org.orecruncher.dsurround.processing.SpeechBubbleHandler.class);
        event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "layer/speechbubbles"), speechBubbleHandler::renderGui);

        // Desert sandstorm / nether dust yellow veil tint. registerBelowAll puts it at
        // the start of the render order (drawn first, underneath every HUD element,
        // including the Xaero minimap) - NeoForge 26.1 layer ordering is natural:
        // registerAboveAll renders LAST, i.e. on top of everything.
        var weatherStormHandler = ContainerManager.resolve(org.orecruncher.dsurround.processing.WeatherStormHandler.class);
        event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "layer/weatherstorm"), weatherStormHandler::renderGui);

        // Quick per-sound volume overlay (hold Ctrl+`).
        var quickVolumeOverlay = ContainerManager.resolve(org.orecruncher.dsurround.gui.overlay.QuickSoundVolumeOverlay.class);
        // The vanilla CAMERA_OVERLAYS layer draws the screen-corner vignette, which darkens
        // anything rendered below it (a belowAll layer gets its bottom-left corner shaded at
        // night). Render this small panel above it so its colours stay true.
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "layer/quicksoundvolume"), quickVolumeOverlay::render);
    }
}