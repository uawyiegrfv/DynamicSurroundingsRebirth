package org.orecruncher.dsurround.processing;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.effects.particles.StormDustParticle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.tags.BiomeTags;

/**
 * A17 (particle version): drives the desert sandstorm and nether dust rain.
 * Spawns drifting dust particles around the player and exposes a dust intensity
 * value that a GUI layer turns into the desert yellow screen tint.
 */
public class WeatherStormHandler extends AbstractClientHandler {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);
    private static final IRandomizer RANDOM = Randomizer.current();

    private float dustIntensity = 0F;

    public WeatherStormHandler(Configuration config, IModLog logger) {
        super("Weather Storm", config, logger);
    }

    @Override
    public void process(final Player player) {
        var level = player.level();
        if (!(level instanceof ClientLevel clientLevel))
            return;

        var biome = level.getBiome(player.blockPosition()).value();
        boolean nether = level.dimension() == Level.NETHER;
        boolean desert = TAG_LIBRARY.is(BiomeTags.IS_DESERT, biome) || TAG_LIBRARY.is(BiomeTags.IS_BADLANDS, biome);
        boolean raining = level.isRaining();

        float target = 0F;
        if (nether && this.config.weatherOptions.enableNetherDust) {
            // Constant dark dust drifting in the nether.
            target = 0.10F;
            spawnStorm(clientLevel, player, 0.35F, 0.3F, 0.3F, false, 2);
        } else if (desert && this.config.weatherOptions.enableDesertSandstorm) {
            // Desert yellow haze, stronger while it rains (a sandstorm). The original's
            // storm rendered a dense dust screen, so the rainy state gets a heavy tint
            // and a thick stream of particles.
            target = raining ? 0.7F : 0.12F;
            spawnStorm(clientLevel, player, 0.85F, 0.7F, 0.4F, true, raining ? 12 : 1);
        }

        // Smoothly transition the overlay intensity.
        this.dustIntensity += (target - this.dustIntensity) * 0.1F;
    }

    /**
     * Overlay alpha (0..1) used by the desert yellow tint GUI layer.
     */
    public float getDustIntensity() {
        return this.dustIntensity;
    }

    /**
     * GUI layer callback: draws a subtle yellow-brown haze over the screen while in a
     * desert (stronger during a sandstorm) or the nether, like the original's dust tint.
     */
    public void renderGui(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker tracker) {
        final float intensity = this.dustIntensity;
        if (intensity <= 0.005F)
            return;

        var mc = net.minecraft.client.Minecraft.getInstance();
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        final int alpha = (int) (intensity * 255F * 0.7F);
        if (alpha <= 0)
            return;
        // Yellow-brown dust haze, 0xD8B266.
        final int color = (alpha << 24) | 0x00D8B266;
        graphics.fill(0, 0, width, height, color);
    }

    private static void spawnStorm(ClientLevel level, Player player, float r, float g, float b, boolean windy, int count) {
        // Respect the player's particle setting (Minimal/Decreased/All): scale the
        // spawn rate down so a sandstorm can't flood the particle budget on weak
        // settings. This mirrors the vanilla behaviour for weather particles.
        var particleStatus = GameUtils.getGameSettings().particles().get();
        count = switch (particleStatus) {
            case MINIMAL -> 0;
            case DECREASED -> count / 2;
            case ALL -> count;
        };
        if (count <= 0)
            return;

        for (int i = 0; i < count; i++) {
            double x = player.getX() + (RANDOM.nextDouble() - 0.5D) * 16.0D;
            double y = player.getY() + RANDOM.nextDouble() * 6.0D - 2.0D;
            double z = player.getZ() + (RANDOM.nextDouble() - 0.5D) * 16.0D;
            var particle = new StormDustParticle(level, x, y, z, r, g, b, windy);
            GameUtils.getParticleManager().add(particle);
        }
    }
}
