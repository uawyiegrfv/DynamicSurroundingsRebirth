package org.orecruncher.dsurround;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.config.libraries.*;
import org.orecruncher.dsurround.config.libraries.impl.*;
import org.orecruncher.dsurround.gui.keyboard.KeyBindings;
import org.orecruncher.dsurround.gui.overlay.OverlayManager;
import org.orecruncher.dsurround.gui.overlay.QuickSoundVolumeOverlay;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.config.IConfigScreenFactoryProvider;
import org.orecruncher.dsurround.lib.config.compat.ClothAPIFactoryProvider;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.events.HandlerPriority;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;
import org.orecruncher.dsurround.config.libraries.AssetLibraryEvent;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.lib.seasons.SeasonManager;
import org.orecruncher.dsurround.lib.version.IVersionChecker;
import org.orecruncher.dsurround.lib.version.VersionChecker;
import org.orecruncher.dsurround.lib.version.VersionResult;
import org.orecruncher.dsurround.processing.Handlers;
import org.orecruncher.dsurround.runtime.ConditionEvaluator;
import org.orecruncher.dsurround.runtime.IConditionEvaluator;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.AudioPlayer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class Client {

    /**
     * Basic configuration settings
     */
    public static Configuration Config;

    private final IModLog logger;
    private CompletableFuture<Optional<VersionResult>> versionInfo;

    public Client() {
        // Bootstrap library functions
        this.logger = Library.LOGGER;

        ContainerManager.getRootContainer()
                .registerSingleton(IModLog.class, this.logger)
                .registerSingleton(IConfigScreenFactoryProvider.class, ClothAPIFactoryProvider.class);

        // Setup debug trace on the logger. It's not guaranteed that we
        // are the first getting the log file, so we can't rely
        // on the event hook.  (ModMenu can trigger this when it looks for
        // the hook in our mod before we had a chance to initialize.)
        Config = ConfigurationData.getConfig(Configuration.class);
        if (this.logger instanceof ModLog ml) {
            ml.setDebug(Config.logging.enableDebugLogging);
            ml.setTraceMask(Config.logging.traceMask);
        }

        // Hook the config load event so set we can set the debug flags when
        // the config changes.
        Configuration.CONFIG_CHANGED.register(cfg -> {
            if (cfg instanceof Configuration config) {
                if (this.logger instanceof ModLog ml) {
                    ml.setDebug(config.logging.enableDebugLogging);
                    ml.setTraceMask(config.logging.traceMask);
                }
            }
        });
    }

    public void construct() {
        this.logger.info("[%s] Bootstrapping", Constants.MOD_ID);

        Library.initialize();

        // Register the Minecraft sound manager using a factory. Avoids issue with ModernUI and their dinger.
        ContainerManager.getRootContainer().registerFactory(SoundManager.class, GameUtils::getSoundManager);

        this.logger.info("[%s] Boostrap completed", Constants.MOD_ID);
    }

    public void initializeClient() {
        this.logger.info("[%s] Client initializing", Constants.MOD_ID);

        // (Forge RegisterClientCommandsEvent / RegisterClientReloadListenersEvent)

        // Do the handlers
        Handlers.registerHandlers();

        ClientState.STARTED.register(this::onComplete, HandlerPriority.VERY_HIGH);
        ClientState.ON_CONNECT.register(this::onConnect, HandlerPriority.LOW);

        // Register core services
        ContainerManager.getRootContainer()
                .registerSingleton(Config)
                .registerSingleton(Config.logging)
                .registerSingleton(Config.soundSystem)
                .registerSingleton(Config.enhancedSounds)
                .registerSingleton(Config.soundOptions)
                .registerSingleton(Config.blockEffects)
                .registerSingleton(Config.entityEffects)
                .registerSingleton(Config.footstepAccents)
                .registerSingleton(Config.particleTweaks)
                .registerSingleton(Config.compassAndClockOptions)
                .registerSingleton(Config.fogOptions)
                .registerSingleton(Config.otherOptions)
                .registerSingleton(IConditionEvaluator.class, ConditionEvaluator.class)
                .registerSingleton(IVersionChecker.class, VersionChecker.class)
                .registerSingleton(ITagLibrary.class, TagLibrary.class)
                .registerSingleton(ISoundLibrary.class, SoundLibrary.class)
                .registerSingleton(IBiomeLibrary.class, BiomeLibrary.class)
                .registerSingleton(IDimensionLibrary.class, DimensionLibrary.class)
                .registerSingleton(IDimensionInformation.class, DimensionInformation.class)
                .registerFactory(ISeasonalInformation.class, () -> SeasonManager.HANDLER)
                .registerSingleton(IBlockLibrary.class, BlockLibrary.class)
                .registerSingleton(IItemLibrary.class, ItemLibrary.class)
                .registerSingleton(IEntityEffectLibrary.class, EntityEffectLibrary.class)
                .registerSingleton(VariatorLibrary.class)
                .registerSingleton(OverlayManager.class)
                .registerSingleton(QuickSoundVolumeOverlay.class);

        // Depending on debug settings, enable the appropriate player
        // TODO(1.20.1): AudioPlayerDebug (debug audio player) deferred with spatial-audio phase
        ContainerManager.getRootContainer().registerSingleton(IAudioPlayer.class, AudioPlayer.class);

        // RELOAD listeners for the data-driven libraries (sound factories, tags, biomes...).
        var container = ContainerManager.getRootContainer();
        AssetLibraryEvent.RELOAD.register(container.resolve(ISoundLibrary.class)::reload, HandlerPriority.VERY_HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(ITagLibrary.class)::reload, HandlerPriority.VERY_HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(IBiomeLibrary.class)::reload, HandlerPriority.HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(IBlockLibrary.class)::reload, HandlerPriority.HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(IItemLibrary.class)::reload, HandlerPriority.HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(IEntityEffectLibrary.class)::reload, HandlerPriority.HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(IDimensionLibrary.class)::reload, HandlerPriority.HIGH);
        AssetLibraryEvent.RELOAD.register(container.resolve(VariatorLibrary.class)::reload, HandlerPriority.HIGH);

        // Kick off version checking if configured.  This should run in parallel with initialization.
        if (Config.logging.enableModUpdateChatMessage)
            this.versionInfo = CompletableFuture.supplyAsync(ContainerManager.resolve(IVersionChecker.class)::getUpdateText);
        else
            this.versionInfo = CompletableFuture.completedFuture(Optional.empty());

        KeyBindings.register();

        // Force instantiation: QuickSoundVolumeOverlay registers its tick/scroll/sound
        // listeners in the constructor; a lazy singleton is otherwise never created.
        ContainerManager.resolve(QuickSoundVolumeOverlay.class);

        this.logger.info("[%s] Client initialization complete", Constants.MOD_ID);
    }

    public void onComplete(Minecraft client) {
        // Force the handlers to be instantiated: Handlers registers its per-tick
        // listeners (TICK_END etc.) in the constructor, and registerSingleton is lazy.
        ContainerManager.getRootContainer().resolve(Handlers.class);

        // Manual RELOAD raise: the reload listener's first raise fires before this
        // onComplete registration, so the sound factories / data-driven configuration
        // would otherwise never load (silent DS sounds, empty sound config screen).
        AssetLibraryEvent.RELOAD.raise()
                .onReload(org.orecruncher.dsurround.lib.resources.ResourceUtilities.createForCurrentState(),
                        org.orecruncher.dsurround.config.libraries.IReloadEvent.Scope.RESOURCES);

        // Multiplayer tag sync: reload the TAGS-scoped library branches when the server
        // pushes updated tags (26.1 parity; previously there were 0 TAG_SYNC registrations,
        // so libraries with a Scope.TAGS branch never refreshed their caches).
        ClientState.TAG_SYNC.register(event -> {
            this.logger.info("Tag sync event received - reloading libraries");
            AssetLibraryEvent.RELOAD.raise()
                    .onReload(org.orecruncher.dsurround.lib.resources.ResourceUtilities.createForCurrentState(),
                            org.orecruncher.dsurround.config.libraries.IReloadEvent.Scope.TAGS);
        }, HandlerPriority.VERY_HIGH);
    }

    private void onConnect(Minecraft minecraftClient) {
        // Display version information when joining a game and when a chat window is available.
        try {
            var versionQueryResult = this.versionInfo.get();
            if (versionQueryResult.isPresent()) {
                var result = versionQueryResult.get();
                this.logger.info("Update to %s version %s is available", result.displayName(), result.version());
                var player = GameUtils.getPlayer();
                player.ifPresent(p -> p.sendSystemMessage(result.getChatText()));
            } else if (Config.logging.enableModUpdateChatMessage) {
                this.logger.info("The mod version is current");
            }
        } catch (Throwable t) {
            this.logger.error(t, "Unable to process version information");
        }
    }
}
