package org.orecruncher.dsurround;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.client.gui.screens.Screen;
import org.orecruncher.dsurround.commands.Commands;
import net.minecraft.client.Minecraft;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.registry.ReloadListener;
import org.orecruncher.dsurround.processing.aurora.AuroraRenderPipelines;


/**
 * 1.20.1 Forge platform entry.
 * Replaces 26.1 NeoForgeMod: mod-bus events (overlays, reload listeners),
 * game-bus events (commands, tags) and Cloth config screen registration.
 */
@Mod(Constants.MOD_ID)
public final class DSurround {

    private final Client client;
    private boolean started;

    public DSurround() {
        // 1.20.1: @Mod constructors take no parameters; obtain bus/container via FMLJavaModLoadingContext
        var context = FMLJavaModLoadingContext.get();
        var modBus = context.getModEventBus();
        // 1.20.1 has no FMLJavaModLoadingContext.getModContainer(); use ModList
        ModContainer container = ModList.get().getModContainerById(Constants.MOD_ID).orElseThrow();

        this.client = new Client();
        this.client.construct();
        this.client.initializeClient();

        // Mod-bus (IModBusEvent) registrations
        modBus.addListener(this::onRegisterGuiOverlays);
        modBus.addListener(this::onRegisterClientReloadListeners);
        modBus.addListener((net.minecraftforge.client.event.RegisterKeyMappingsEvent e) -> org.orecruncher.dsurround.gui.keyboard.KeyBindings.register(e));
        // Custom aurora shader programs (ShaderInstance) registered during resource reload.
        modBus.addListener(AuroraRenderPipelines::onRegisterShaders);

        // Cloth Config configuration screen
        if (ModList.get().isLoaded(Constants.CLOTH_CONFIG_NEOFORGE)) {
            container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(this::createConfigScreen));
        }

        // Game-bus registrations
        MinecraftForge.EVENT_BUS.addListener(this::onTagsUpdated);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterClientCommands);

        // ClientState phase events (26.1 MixinMinecraftClient equivalent):
        // STARTED fires on the first client tick (main loop start); TICK_START/END
        // map to ClientTickEvent.Pre/Post.
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.START) {
                if (!this.started) {
                    this.started = true;
                    ClientState.STARTED.raise().onStart(Minecraft.getInstance());
                }
                ClientState.TICK_START.raise().onTickStart(Minecraft.getInstance());
            } else {
                ClientState.TICK_END.raise().onTickEnd(Minecraft.getInstance());
            }
        });
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (org.orecruncher.dsurround.lib.config.ConfigurationData.getConfig(Configuration.class).logging.registerCommands)
            Commands.register(event.getDispatcher(), event.getBuildContext());
    }

    private void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        // This fires AssetLibraryEvent.RELOAD (Scope.RESOURCES) which loads the
        // sound factories and other data-driven configuration.
        event.registerReloadListener(new ReloadListener());
    }

    private void onTagsUpdated(TagsUpdatedEvent event) {
        ClientState.TAG_SYNC.raise().onTagSync(event.getRegistryAccess());
    }

    private void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        // GUI phase: overlay layers (OverlayManager renders clock/compass/diagnostics).
        event.registerAboveAll("dsurround", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                org.orecruncher.dsurround.lib.di.ContainerManager.resolve(org.orecruncher.dsurround.gui.overlay.OverlayManager.class).render(graphics, partialTick));
        // Quick sound volume overlay sits below the vanilla layers (subtitle-style panel).
        event.registerBelowAll("dsurround_quick_volume", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                org.orecruncher.dsurround.lib.di.ContainerManager.resolve(org.orecruncher.dsurround.gui.overlay.QuickSoundVolumeOverlay.class).render(graphics, partialTick));
        // Crit words (comic damage/heal power words) project 3D world positions to the screen.
        event.registerBelowAll("dsurround_crit_words", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                org.orecruncher.dsurround.lib.di.ContainerManager.resolve(org.orecruncher.dsurround.processing.CritWordHandler.class).renderGui(graphics, partialTick));
        // Desert sandstorm / nether dust yellow haze tint.
        event.registerBelowAll("dsurround_weather_storm", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                org.orecruncher.dsurround.lib.di.ContainerManager.resolve(org.orecruncher.dsurround.processing.WeatherStormHandler.class).renderGui(graphics, partialTick));
    }

    private Screen createConfigScreen(Screen parent) {
        var logger = ContainerManager.resolve(IModLog.class);
        var provider = ContainerManager.resolve(org.orecruncher.dsurround.lib.config.IConfigScreenFactoryProvider.class);
        logger.info("Forge calling to get config screen");
        var factory = provider.getModConfigScreenFactory(Configuration.class);
        return factory.map(f -> (Screen) f.create(parent)).orElse(parent);
    }
}