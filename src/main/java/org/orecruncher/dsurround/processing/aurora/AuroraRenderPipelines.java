package org.orecruncher.dsurround.processing.aurora;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.orecruncher.dsurround.Constants;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Render types for the aurora shader renderer.
 *
 * <p>1.20.1 has no 26.1 {@code RenderPipeline}/{@code RenderSetup} API; the
 * equivalent is a {@link ShaderInstance} registered via
 * {@link RegisterShadersEvent} plus a {@link RenderType} whose
 * {@link RenderStateShard.ShaderStateShard} supplies that shader. The 26.1
 * pipeline's per-variant {@code ASPECT} shader define cannot be injected through
 * {@link ShaderInstance}, so the two variants use two shader file pairs
 * ({@code core/aurora_64} / {@code core/aurora_128}) with the aspect hardcoded
 * in the fragment shader.
 */
public final class AuroraRenderPipelines {

    private static ShaderInstance aurora64Shader;
    private static ShaderInstance aurora128Shader;

    public static RenderType TYPE_64;
    public static RenderType TYPE_128;

    /**
     * Additive blend matching the original 1.12.2 aurora look. The fragment
     * shader always writes alpha 1.0 and scales RGB by its own intensity, so
     * {@code SRC_ALPHA} effectively becomes a pure additive path.
     */
    private static final RenderStateShard.TransparencyStateShard AURORA_TRANSPARENCY =
            new RenderStateShard.TransparencyStateShard("aurora_transparency",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(
                                GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.SRC_ALPHA,
                                GlStateManager.SourceFactor.ONE,
                                GlStateManager.DestFactor.ZERO);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** Depth-test against the skybox but never write depth: the sheet is see-through. */
    private static final RenderStateShard.DepthTestStateShard AURORA_DEPTH =
            new RenderStateShard.DepthTestStateShard("<= aurora", 515); // GL_LEQUAL

    private AuroraRenderPipelines() {
    }

    public static void onRegisterShaders(final RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "aurora_64"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> aurora64Shader = s);
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "aurora_128"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> aurora128Shader = s);
        } catch (final IOException ex) {
            throw new RuntimeException("Unable to register dsurround aurora shaders", ex);
        }

        TYPE_64 = create("dsurround:aurora_64", () -> aurora64Shader);
        TYPE_128 = create("dsurround:aurora_128", () -> aurora128Shader);
    }

    private static RenderType create(final String name, final Supplier<ShaderInstance> shader) {
        final RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                .setTransparencyState(AURORA_TRANSPARENCY)
                .setDepthTestState(AURORA_DEPTH)
                .setCullState(new RenderStateShard.CullStateShard(false))
                .setLightmapState(new RenderStateShard.LightmapStateShard(false))
                .setOverlayState(new RenderStateShard.OverlayStateShard(false))
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                .createCompositeState(false);
        return RenderType.create(name, DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS,
                RenderType.TRANSIENT_BUFFER_SIZE, false, false, state);
    }
}
