package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.tags.BiomeTags;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A17 (particle version): drives the desert sandstorm and nether dust rain.
 *
 * <p>Visual layering follows the user-approved split: while a desert is clear the
 * only effect is the distant-horizon yellow tint provided by the biome fog color
 * (biomes.json "fogColor"); when it rains, a dust veil fades into the world around
 * the player (the 1.12.2 StormRenderer mechanism: the intensity-graded 64x256 dust
 * strips rendered as world-space quads with scrolling UVs, tinted by the biome
 * dustColor) on top of a light ambient dust particle drift. The nether keeps its
 * dark dust rain with a very faint veil.
 *
 * <p>26.1 has no Biome.getFogColor() (the fog color moved into the environment
 * attribute system), so the horizon tint is applied through the NeoForge
 * ViewportEvent.ComputeFogColor hook: the vanilla fog color (which already carries
 * the day/night and weather adjustments) is modulated toward the configured desert
 * color with a smoothed weight. The modulation is multiplicative so the vanilla
 * brightness is preserved at all times of day. The tint is data driven - it
 * applies to every biome with a configured fogColor (desert haze, swamp fog, ...),
 * gated by the weatherOptions.enableBiomeFogColor switch.
 *
 * <p>Both tints fade asymmetrically: rain-driven states appear over ~0.5s and
 * retreat over ~1.2s so the screen never pops when a storm starts or stops, and
 * cave suppression rides the same smoothing.
 */
public class WeatherStormHandler extends AbstractClientHandler {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private static final String MOD_ID = "dsurround";

    // Intensity-graded dust strips (1.12.2 Weather.Properties). World-space veil
    // textures - a 64x256 tiling sheet of dust specks, NOT a particle sprite.
    private static final Identifier DUST_CALM = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_calm.png");
    private static final Identifier DUST_LIGHT = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_light.png");
    private static final Identifier DUST_GENTLE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_gentle.png");
    private static final Identifier DUST_MODERATE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_moderate.png");
    private static final Identifier DUST_HEAVY = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_heavy.png");
    private static final Identifier DUST_STRONG = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_strong.png");
    private static final Identifier DUST_INTENSE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_intense.png");
    private static final Identifier DUST_TORRENTIAL = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_torrential.png");


    // Fade rates per tick: 0.1 -> ~0.5s fade-in, 0.04 -> ~1.2s fade-out (retreat is
    // deliberately slower so a stopping storm does not pop).
    private static final float TINT_FADE_IN = 0.10F;
    private static final float TINT_FADE_OUT = 0.04F;

    // Veil geometry (1.12.2 StormRenderer): a ±range one-block-column grid, one thin
    // diagonal quad per desert column, heightmap-clipped to a band around the player.
    private static final int VEIL_RANGE = 10;

    private final Scanners scanners;
    private float netherTint = 0F;
    private float fullscreenTint = 0F;
    // Rain veil state, updated per tick and consumed by the world renderer.
    private Identifier veilTexture = DUST_CALM;
    private final java.util.Random columnRandom = new java.util.Random();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
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

    // Desert horizon tint state (see class comment).
    private float horizonWeight = 0F;
    private float horizonR = 1F;
    private float horizonG = 1F;
    private float horizonB = 1F;

    public WeatherStormHandler(Configuration config, IModLog logger, Scanners scanners) {
        super("Weather Storm", config, logger);
        this.scanners = scanners;

        NeoForge.EVENT_BUS.addListener(this::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(this::onAfterWeather);
    }

    @Override
    public void onConnect() {
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
        this.horizonWeight = 0F;
    }

    @Override
    public void onDisconnect() {
        // Reset so the yellow haze does not linger into the next world/session.
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
        this.horizonWeight = 0F;
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
        float r = 0.85F, g = 0.7F, b = 0.4F;

        if (nether && this.config.weatherOptions.enableNetherDust) {
            // Nether dust veil: visible dust drift (shared desert dust texture),
            // red/black/red-brown per-column tint.
            netherTarget = 0.10F;
            this.veilTexture = DUST_MODERATE;
        } else if (desert && this.config.weatherOptions.enableDesertSandstorm) {
            if (!this.scanners.isInside()) {
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
        updateHorizonTint(biome, this.config.weatherOptions.enableBiomeFogColor);
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
     * Tracks the desert horizon tint: weight eases toward 1 while in a biome with a
     * configured fogColor, toward 0 otherwise; the color eases toward the configured
     * fog color so crossing between biomes does not pop.
     */
    private void updateHorizonTint(net.minecraft.world.level.biome.Biome biome, boolean enabled) {
        if (enabled) {
            var info = ((IBiomeExtended) (Object) biome).dsurround_getInfo();
            if (info != null) {
                var color = info.getFogColor();
                if (color != null) {
                    float tr = ((color.getValue() >> 16) & 0xFF) / 255F;
                    float tg = ((color.getValue() >> 8) & 0xFF) / 255F;
                    float tb = (color.getValue() & 0xFF) / 255F;
                    this.horizonR += (tr - this.horizonR) * 0.1F;
                    this.horizonG += (tg - this.horizonG) * 0.1F;
                    this.horizonB += (tb - this.horizonB) * 0.1F;
                    this.horizonWeight = Math.min(1F, this.horizonWeight + TINT_FADE_IN);
                    return;
                }
            }
        }
        this.horizonWeight = Math.max(0F, this.horizonWeight - TINT_FADE_OUT);
    }

    /**
     * Applies the desert horizon tint to the atmospheric fog color. The vanilla color
     * already includes day/night and weather adjustments, so the configured color is
     * normalized (max channel = 1) and applied multiplicatively - only the hue is
     * imposed, brightness stays vanilla.
     */
    private void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (this.horizonWeight <= 0.01F)
            return;
        // Only the atmospheric (horizon) fog - not underwater/lava/powdered snow.
        if (event.getCamera().getFluidInCamera() != FogType.NONE)
            return;

        float max = Math.max(this.horizonR, Math.max(this.horizonG, this.horizonB));
        if (max <= 0.001F)
            return;
        float mR = this.horizonR / max;
        float mG = this.horizonG / max;
        float mB = this.horizonB / max;
        float w = this.horizonWeight;

        event.setRed(lerp(event.getRed(), event.getRed() * mR, w));
        event.setGreen(lerp(event.getGreen(), event.getGreen() * mG, w));
        event.setBlue(lerp(event.getBlue(), event.getBlue() * mB, w));
    }

    private static float lerp(float from, float to, float delta) {
        return from + (to - from) * delta;
    }

    /**
     * Picks the dust strip for the current weather intensity. Thresholds match the
     * 1.12.2 Weather.Properties levels; thunderstorms push the intensity up a tier.
     */
    private static Identifier dustTexture(ClientLevel level) {
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
     * (or in the nether). Registered via registerBelowAll so it renders beneath every
     * HUD element, including the Xaero minimap. The desert-clear state draws nothing
     * here - its horizon tint comes from the fog color modulation.
     */
    public void renderGui(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker tracker) {
        final float intensity = Math.max(this.netherTint, this.fullscreenTint);
        if (intensity <= 0.005F)
            return;

        var mc = Minecraft.getInstance();
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        final int alpha = (int) (intensity * 255F * 0.7F);
        if (alpha <= 0)
            return;
        // Desert haze is yellow-brown; the nether haze is a pale red-brown.
        final boolean nether = mc.level != null && mc.level.dimension() == Level.NETHER;
        final int color = (alpha << 24) | (nether ? 0x00C07A5A : 0x00D8B266);
        graphics.fill(0, 0, width, height, color);
    }

    /**
     * The sandstorm dust veil: camera-facing vertical quads in a grid around the
     * player, textured with the intensity-graded dust strip and scrolling UVs - the
     * 1.12.2 StormRenderer mechanism. Rendered into the vanilla weather render target
     * right after the vanilla weather pass (same pipeline family, so terrain
     * occlusion and translucency behave like rain).
     */
    private void onAfterWeather(RenderLevelStageEvent.AfterWeather event) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null)
            return;
        final boolean nether = level.dimension() == Level.NETHER;
        final float tint = nether ? this.netherTint : this.fullscreenTint;
        if (tint < 0.02F)
            return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int ticks = (int) level.getGameTime();
        int range = VEIL_RANGE;
        int playerX = Mth.floor(player.getX());
        int playerY = Mth.floor(player.getY());
        int playerZ = Mth.floor(player.getZ());
        float veilAlpha = nether ? 0.5F : (tint * 0.7F);

        // 26.1: blend/depth/cull state lives on the WEATHER render pipelines - no
        // fixed-function calls needed (RenderSystem.enableBlend et al. are gone).

        int quadCount = (range * 2 + 1) * (range * 2 + 1);
        var dustTexture = mc.getTextureManager().getTexture(this.veilTexture);

        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(quadCount * DefaultVertexFormat.PARTICLE.getVertexSize() * 4)) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

            int quads = 0;
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
                    int alpha = (int) (((1.0F - f3 * f3) * 0.3F + 0.5F) * veilAlpha * 255F);
                    int light = (LevelRenderer.getLightCoords(level, this.cursor.set(gridX, k2, gridZ)) * 3 + 15728880) / 4;
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
                    int argb = (alpha << 24) | (cr << 16) | (cg << 8) | cb;

                    float x0 = gridX - rainX + 0.5F - (float) cam.x;
                    float z0 = gridZ - rainY + 0.5F - (float) cam.z;
                    float x1 = gridX + rainX + 0.5F - (float) cam.x;
                    float z1 = gridZ + rainY + 0.5F - (float) cam.z;
                    float y0 = k2 - (float) cam.y;
                    float y1 = l2 - (float) cam.y;
                    float v0 = k2 * 0.25F + (float) d8;
                    float v1 = l2 * 0.25F + (float) d8;

                    bufferBuilder.addVertex(x0, y0, z0).setUv((float) d9, v0 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x1, y0, z1).setUv(1F + (float) d9, v0 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x1, y1, z1).setUv(1F + (float) d9, v1 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x0, y1, z0).setUv((float) d9, v1 + (float) d10).setColor(argb).setLight(light);
                    quads++;
                }
            }

            if (quads == 0)
                return;

            GpuBuffer vertexBuffer;
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            try (MeshData mesh = bufferBuilder.buildOrThrow()) {
                vertexBuffer = RenderPipelines.WEATHER_NO_DEPTH_WRITE.getVertexFormat()
                    .uploadImmediateVertexBuffer(mesh.vertexBuffer());
                var autoIndices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                indexBuffer = autoIndices.getBuffer(mesh.drawState().indexCount());
                indexType = autoIndices.type();
            }

            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());

            var weatherTarget = OutputTarget.WEATHER_TARGET.getRenderTarget();
            GpuTextureView colorTexture = weatherTarget.getColorTextureView();
            GpuTextureView depthTexture = weatherTarget.getDepthTextureView();
            var renderPipeline = Minecraft.useShaderTransparency()
                ? RenderPipelines.WEATHER_DEPTH_WRITE
                : RenderPipelines.WEATHER_NO_DEPTH_WRITE;

            try (var renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "DSurround Dust Veil", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
                renderPass.setPipeline(renderPipeline);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.bindTexture(
                    "Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                );
                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.setVertexBuffer(0, vertexBuffer);
                renderPass.bindTexture("Sampler0", dustTexture.getTextureView(),
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
                renderPass.drawIndexed(0, 0, quads * 6, 1);
            }
        }
    }

}