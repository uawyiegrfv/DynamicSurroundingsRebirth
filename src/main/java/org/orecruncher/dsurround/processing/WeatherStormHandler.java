package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.tags.BiomeTags;

/**
 * A17: drives the desert sandstorm and nether dust rain.
 *
 * <p>Visual layering follows the user-approved split: while a desert is clear the
 * only effect is the distant-horizon yellow tint provided by the biome fog color
 * (MixinBiome getFogColor injection - covers no GUI); when it rains, the 1.12.2
 * StormRenderer dust rain fades in - per-column vertical quads over every desert
 * column in a ±10 block grid (fancy graphics; ±5 on fast), textured with the
 * intensity-graded 64x256 dust strips with scrolling UVs, tinted by the biome
 * dustColor - on top of a light ambient dust particle drift. The nether keeps its
 * dark dust rain with a very faint veil.
 *
 * <p>Both tints fade asymmetrically: rain-driven states appear over ~0.5s and
 * retreat over ~1.2s so the screen never pops when a storm starts or stops, and
 * cave suppression rides the same smoothing.
 */
public class WeatherStormHandler extends AbstractClientHandler {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private static final String MOD_ID = "dsurround";

    // Intensity-graded dust strips (1.12.2 Weather.Properties). World-space veil
    // textures - a 64x256 tiling sheet of dust specks (vanilla rain.png layout).
    private static final ResourceLocation DUST_CALM = new ResourceLocation(MOD_ID, "textures/environment/dust_calm.png");
    private static final ResourceLocation DUST_LIGHT = new ResourceLocation(MOD_ID, "textures/environment/dust_light.png");
    private static final ResourceLocation DUST_GENTLE = new ResourceLocation(MOD_ID, "textures/environment/dust_gentle.png");
    private static final ResourceLocation DUST_MODERATE = new ResourceLocation(MOD_ID, "textures/environment/dust_moderate.png");
    private static final ResourceLocation DUST_HEAVY = new ResourceLocation(MOD_ID, "textures/environment/dust_heavy.png");
    private static final ResourceLocation DUST_STRONG = new ResourceLocation(MOD_ID, "textures/environment/dust_strong.png");
    private static final ResourceLocation DUST_INTENSE = new ResourceLocation(MOD_ID, "textures/environment/dust_intense.png");
    private static final ResourceLocation DUST_TORRENTIAL = new ResourceLocation(MOD_ID, "textures/environment/dust_torrential.png");


    // Fade rates per tick: 0.1 -> ~0.5s fade-in, 0.04 -> ~1.2s fade-out (retreat is
    // deliberately slower so a stopping storm does not pop).
    private static final float TINT_FADE_IN = 0.10F;
    private static final float TINT_FADE_OUT = 0.04F;

    private final Scanners scanners;
    private float netherTint = 0F;
    private float fullscreenTint = 0F;
    // Rain veil state, updated per tick and consumed by the world renderer.
    private ResourceLocation veilTexture = DUST_CALM;
    private float veilR = 0.85F;
    private float veilG = 0.7F;
    private float veilB = 0.4F;

    // Nether dust veil colors: each column picks a random red / black / red-brown
    // tint (1.12.2 nether dust look, drawn with the shared dust texture).
    private static final int[][] NETHER_DUST_COLORS = {
            { 0xC0, 0x30, 0x30 }, // red
            { 0x16, 0x10, 0x10 }, // near-black
            { 0x8B, 0x5A, 0x3C }, // red-brown
    };

    private final java.util.Random columnRandom = new java.util.Random();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public WeatherStormHandler(Configuration config, IModLog logger, Scanners scanners) {
        super("Weather Storm", config, logger);
        this.scanners = scanners;

        MinecraftForge.EVENT_BUS.addListener(this::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderGuiPre);
    }

    @Override
    public void onConnect() {
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
    }

    @Override
    public void onDisconnect() {
        // Reset so the yellow haze does not linger into the next world/session.
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
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

        float netherTarget = 0F;
        float fullscreenTarget = 0F;
        if (nether && this.config.weatherOptions.enableNetherDust) {
            // Constant dark dust drifting in the nether with its very faint veil.
            netherTarget = 0.10F;
            this.veilTexture = DUST_CALM;
        } else if (desert && this.config.weatherOptions.enableDesertSandstorm) {
            if (!this.scanners.isInside()) {
                float r = 0.85F, g = 0.7F, b = 0.4F;
                var info = ((IBiomeExtended) (Object) biome).dsurround_getInfo();
                if (info != null) {
                    var dust = info.getDustColor();
                    if (dust != null) {
                        r = ((dust.getValue() >> 16) & 0xFF) / 255F;
                        g = ((dust.getValue() >> 8) & 0xFF) / 255F;
                        b = (dust.getValue() & 0xFF) / 255F;
                    }
                }

                if (raining) {
                    // Sandstorm: the dust veil fades in and a stream of ambient dust
                    // particles blows with the wind. The horizon tint (fog color) stays.
                    fullscreenTarget = 0.7F;
                    this.veilTexture = dustTexture(clientLevel);
                    this.veilR = r;
                    this.veilG = g;
                    this.veilB = b;
                } else {
                    // Clear desert: no fullscreen tint (the horizon fog color is the
                    // effect); a light drift of calm dust for ambience.
                    this.veilTexture = DUST_CALM;
                    this.veilR = r;
                    this.veilG = g;
                    this.veilB = b;
                }
            }
        }

        this.netherTint = fade(this.netherTint, netherTarget);
        this.fullscreenTint = fade(this.fullscreenTint, fullscreenTarget);
    }

    /**
     * Smoothly approaches the target, snapping when close. Fades in faster than they
     * retreat so storms build with the rain and linger briefly after it stops.
     */
    private static float fade(float current, float target) {
        if (current == target)
            return target;
        if (Math.abs(target - current) < 0.005F)
            return target;
        float rate = target > current ? TINT_FADE_IN : TINT_FADE_OUT;
        return current + (target - current) * rate;
    }

    /**
     * Picks the dust strip for the current weather intensity. Thresholds match the
     * 1.12.2 Weather.Properties levels; thunderstorms push the intensity up a tier.
     */
    private static ResourceLocation dustTexture(ClientLevel level) {
        float intensity = level.getRainLevel(1F) + (level.isThundering() ? 0.25F : 0F);
        if (intensity >= 1F) return DUST_TORRENTIAL;
        if (intensity >= 0.875F) return DUST_INTENSE;
        if (intensity >= 0.75F) return DUST_STRONG;
        if (intensity >= 0.625F) return DUST_HEAVY;
        if (intensity >= 0.5F) return DUST_MODERATE;
        if (intensity >= 0.365F) return DUST_GENTLE;
        if (intensity >= 0.25F) return DUST_LIGHT;
        return DUST_CALM;
    }

    /**
     * GUI layer callback: draws the fullscreen dust veil while a sandstorm is active
     * (or in the nether). RenderGuiEvent.Pre fires at the very top of ForgeGui.render,
     * before any HUD overlay is drawn, so the veil sits underneath the minimap, chat
     * and every other HUD element. The desert-clear state draws nothing here - its
     * horizon tint comes from the biome fog color.
     */
    public void onRenderGuiPre(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
        final float intensity = Math.max(this.netherTint, this.fullscreenTint);
        if (intensity <= 0.005F)
            return;

        var graphics = event.getGuiGraphics();
        var mc = Minecraft.getInstance();
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        final int alpha = (int) (intensity * 255F * 0.7F);
        if (alpha <= 0)
            return;
        // Yellow-brown dust haze, 0xD8B266. Rendered with the guiOverlay render type -
        // NO_DEPTH_TEST + color-only write mask - so the fullscreen veil can never write
        // into the GUI depth buffer. The previous plain fill() (RenderType.gui(), LEQUAL
        // + depth write) stamped the z=0 plane depth across the whole screen before any
        // HUD drew, which killed Xaero's minimap - the one HUD that actively depth-tests
        // and depth-clears inside the GUI. The old disableDepthTest()/enableDepthTest()
        // wrapper was a no-op: GuiGraphics.fill() only enqueues vertices, and
        // GuiGraphics.flush() draws them with the render type's own state shards and then
        // force-enables depth test. This mirrors vanilla's own fullscreen overlays
        // (spyglass/frozen/vignette), which all use RenderType.guiOverlay().
        final boolean nether = mc.level != null && mc.level.dimension() == Level.NETHER;
        final int color = (alpha << 24) | (nether ? 0x00C07A5A : 0x00D8B266);
        graphics.fill(RenderType.guiOverlay(), 0, 0, width, height, color);
    }

    /**
     * The sandstorm dust rain, ported from the 1.12.2 StormRenderer: per-column thin
     * vertical quads over every desert column in a ±range grid (fancy 10 / fast 5),
     * heightmap-clipped to a band around the player, textured with the intensity
     * strip (full-width U, quarter-height V scrolling downward per column), tinted
     * by the biome dustColor, alpha fading with distance. Rendered right after the
     * vanilla weather pass so terrain occlusion behaves like rain.
     */
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
            return;

        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null)
            return;
        final boolean nether = level.dimension() == Level.NETHER;
        final float tint = nether ? this.netherTint : this.fullscreenTint;
        if (tint < 0.02F)
            return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partialTick = event.getPartialTick();
        int ticks = (int) level.getGameTime();
        int range = Minecraft.useFancyGraphics() ? 10 : 5;
        int playerX = Mth.floor(player.getX());
        int playerY = Mth.floor(player.getY());
        int playerZ = Mth.floor(player.getZ());
        float veilAlpha = nether ? 0.5F : (tint * 0.7F);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, this.veilTexture);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        for (int gridZ = playerZ - range; gridZ <= playerZ + range; gridZ++) {
            for (int gridX = playerX - range; gridX <= playerX + range; gridX++) {
                this.cursor.set(gridX, 0, gridZ);
                var columnBiome = level.getBiome(this.cursor).value();
                if (!nether && !TAG_LIBRARY.is(BiomeTags.IS_DESERT, columnBiome) && !TAG_LIBRARY.is(BiomeTags.IS_BADLANDS, columnBiome))
                    continue;

                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gridX, gridZ);
                int k2 = Math.max(playerY - range, surface);
                int l2 = Math.max(playerY + range, surface);
                if (k2 >= l2)
                    continue;

                int seed = (gridZ << 16) ^ gridX;
                this.columnRandom.setSeed(seed);
                float rainX = this.columnRandom.nextFloat();
                float rainY = this.columnRandom.nextFloat();
                // 1.12.2 StormRenderer dust curtain: SLOW scroll (512-tick loop) plus
                // per-column UV shear - each column drifts sideways at its own gaussian
                // rate, reading as multi-angle particle curtains.
                double d8 = ((ticks & 511) + partialTick) / 512.0;
                double d9 = this.columnRandom.nextDouble() + (ticks + partialTick) * 0.2F * this.columnRandom.nextGaussian();
                double d10 = this.columnRandom.nextDouble() + (ticks + partialTick) * this.columnRandom.nextGaussian() * 0.001D;

                double d6 = gridX + 0.5 - player.getX();
                double d7 = gridZ + 0.5 - player.getZ();
                float f3 = Mth.sqrt((float) (d6 * d6 + d7 * d7)) / range;
                float alpha = ((1.0F - f3 * f3) * 0.3F + 0.5F) * veilAlpha;
                int light = (LevelRenderer.getLightColor(level, this.cursor.set(gridX, k2, gridZ)) * 3 + 15728880) / 4;
                int slX16 = light >> 16 & 0xFFFF;
                int blX16 = light & 0xFFFF;

                float x0 = gridX - rainX + 0.5F - (float) cam.x;
                float z0 = gridZ - rainY + 0.5F - (float) cam.z;
                float x1 = gridX + rainX + 0.5F - (float) cam.x;
                float z1 = gridZ + rainY + 0.5F - (float) cam.z;
                float y0 = k2 - (float) cam.y;
                float y1 = l2 - (float) cam.y;
                float v0 = k2 * 0.25F + (float) d8;
                float v1 = l2 * 0.25F + (float) d8;
                // The float overload expects 0..1 - passing the 0..255 ints overflowed the
                // byte conversion and read as a near-invisible wash.
                int cr, cg, cb;
                if (nether) {
                    var dustColor = NETHER_DUST_COLORS[this.columnRandom.nextInt(NETHER_DUST_COLORS.length)];
                    cr = dustColor[0];
                    cg = dustColor[1];
                    cb = dustColor[2];
                } else {
                    cr = (int) (this.veilR * 255F);
                    cg = (int) (this.veilG * 255F);
                    cb = (int) (this.veilB * 255F);
                }
                int alphaInt = (int) (alpha * 255F);

                buffer.vertex(x0, y0, z0).uv((float) d9, v0 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x1, y0, z1).uv(1F + (float) d9, v0 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x1, y1, z1).uv(1F + (float) d9, v1 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x0, y1, z0).uv((float) d9, v1 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
            }
        }

        tesselator.end();


        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

}