package org.orecruncher.dsurround.processing.aurora;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.orecruncher.dsurround.Constants;

/**
 * Render pipelines for the aurora shader renderer.
 *
 * <p>Two pipeline variants exist, one per ribbon geometry length (64/128
 * nodes); the only difference is the {@code ASPECT} shader define that keeps
 * the ray noise domain proportional to the ribbon's width/height ratio.
 * Dynamic time comes from the vanilla {@code GameTime} global uniform, and
 * fade in/out comes through the vertex color alpha, so no custom per-frame
 * uniforms are needed (those cannot be bound through the standard buffered
 * {@code RenderType} path).
 */
public final class AuroraRenderPipelines {

    private static final Identifier VERTEX_SHADER = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/aurora");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/aurora");

    /**
     * Additive blend matching the original 1.12.2 aurora look. The fragment
     * shader always writes alpha 1.0 and scales RGB by its own intensity, so
     * {@code SRC_ALPHA} effectively becomes a pure additive path.
     */
    private static final BlendFunction AURORA_BLEND =
            new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.SRC_ALPHA, SourceFactor.ONE, DestFactor.ZERO);

    /** Depth-test against the skybox but never write depth: the sheet is see-through. */
    private static final DepthStencilState AURORA_DEPTH =
            new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false);

    public static final RenderPipeline AURORA_64 = build(4.5F);
    public static final RenderPipeline AURORA_128 = build(9.0F);

    public static final RenderType TYPE_64 = RenderType.create("dsurround:aurora_64",
            RenderSetup.builder(AURORA_64).createRenderSetup());
    public static final RenderType TYPE_128 = RenderType.create("dsurround:aurora_128",
            RenderSetup.builder(AURORA_128).createRenderSetup());

    private AuroraRenderPipelines() {
    }

    private static RenderPipeline build(final float aspect) {
        return RenderPipeline.builder(
                        RenderPipelines.MATRICES_PROJECTION_SNIPPET,
                        RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "aurora_" + (int) aspect))
                .withVertexShader(VERTEX_SHADER)
                .withFragmentShader(FRAGMENT_SHADER)
                .withShaderDefine("ASPECT", aspect)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withColorTargetState(new ColorTargetState(AURORA_BLEND))
                .withDepthStencilState(AURORA_DEPTH)
                .withCull(false)
                .build();
    }

    // Registered from the mod bus via a method reference; not annotated with
    // @OnlyIn — NeoForge 26.1 logs a startup error for that annotation.
    public static void onRegisterPipelines(final RegisterRenderPipelinesEvent event) {
        event.registerPipeline(AURORA_64);
        event.registerPipeline(AURORA_128);
    }
}
