package org.orecruncher.dsurround.lib;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.Optional;

public final class GameUtils {
    private GameUtils() {

    }

    // Client methods
    public static Optional<Player> getPlayer() {
        return Optional.ofNullable(getMC().player);
    }

    public static Optional<ClientLevel> getWorld() {
        return Optional.ofNullable(getMC().level);
    }

    public static Optional<RegistryAccess> getRegistryManager() {
        return getWorld().map(ClientLevel::registryAccess);
    }

    public static Optional<Screen> getCurrentScreen() {
        return Optional.ofNullable(getMC().screen);
    }

    public static void setScreen(Screen screen) {
        getMC().setScreen(screen);
    }

    public static ParticleEngine getParticleManager() {
        return getMC().particleEngine;
    }

    public static Options getGameSettings() {
        return getMC().options;
    }

    public static Font getTextRenderer() {
        return getMC().font;
    }

    public static StringSplitter getTextHandler() {
        return getTextRenderer().getSplitter();
    }

    public static SoundManager getSoundManager() {
        return getMC().getSoundManager();
    }

    public static ResourceManager getResourceManager() {
        return getMC().getResourceManager();
    }

    public static TextureManager getTextureManager() {
        return getMC().getTextureManager();
    }

    public static boolean isInGame() {
        return getWorld().isPresent() && getPlayer().isPresent();
    }

    public static boolean isPaused()
    {
        return getMC().isPaused();
    }

    public static boolean isSinglePlayer()
    {
        return getMC().isSingleplayer();
    }

    public static Minecraft getMC() {
        return Objects.requireNonNull(Minecraft.getInstance());
    }

    public static Optional<String> getServerBrand() {
        // 1.20.1: the dedicated server's brand is not exposed on the client (unlike
        // 1.21's ClientPacketListener#serverBrand). Fall back to the client's own mod
        // loader name as a modded-server proxy (integrated server == the client brand;
        // a dedicated vanilla server is pessimistically treated as modded, which only
        // enables the faster registry shortcut lookup, never breaks tags).
        return Optional.ofNullable(net.minecraft.client.ClientBrandRetriever.getClientModName());
    }

    public static MinecraftServerType getServerType() {
        return getServerBrand().map(MinecraftServerType::fromBrand).orElse(MinecraftServerType.VANILLA);
    }
}