package org.orecruncher.dsurround.processing.aurora;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;

/**
 * Shader-driven aurora renderer. Clean-room rewrite for 26.1 (MIT).
 *
 * <p>Submits one continuous vertical ribbon quad strip per band through the
 * buffered {@code MultiBufferSource} using a custom {@link RenderType} whose
 * pipeline runs {@code dsurround:core/aurora}. All visual work (curtain,
 * vertical rays, spectrum ramp, shimmer) happens in the fragment shader; the
 * geometry is just the sheet the shader paints on, so each segment spans the
 * full unit height (0..1) and the shader's own envelope shapes the edges.
 *
 * <p>A dimmer, larger back layer is drawn behind the front layer for parallax
 * depth. Per the project's visual spec the back layer alpha stays at or below
 * 0.35 so the two additive layers do not overexpose.
 */
public final class AuroraShader extends AuroraBase {

    /**
     * The band path runs mostly along X, so X scale is the ribbon length
     * across the sky and Z scale is the curtain's side-to-side width. Split
     * scales let length and width be tuned independently.
     */
    private static final float SCALE_X = 0.55F;
    private static final float SCALE_Z = 0.26F;
    /**
     * Curtain height scale. Ribbon vertices span unit height 0..1, so this is
     * the full curtain height in blocks (~120, matching the classic renderer's
     * 7-18 node height x 8 scale = 56-144 blocks).
     */
    private static final float SCALE_Y = 120.0F;

    /** Back layer stays close to the front so the two read as one thick curtain, not two sheets. */
    private static final float BACK_LAYER_ALPHA_FACTOR = 0.35F;
    private static final float BACK_LAYER_SCALE_X = 0.60F;
    private static final float BACK_LAYER_SCALE_Z = 0.30F;
    private static final float BACK_LAYER_SCALE_Y = 126.0F;
    private static final float BACK_LAYER_Z_BIAS = 6.0F;

    /** Fraction of the classic 20-40 block band spacing; keeps bands visually stacked. */
    private static final float BAND_OFFSET_FACTOR = 0.5F;

    private final RenderType renderType;

    public AuroraShader(final long seed) {
        super(seed);
        this.renderType = this.band.length >= 128 ? AuroraRenderPipelines.TYPE_128 : AuroraRenderPipelines.TYPE_64;
    }

    @Override
    public void update() {
        super.update();
        this.band.update();
    }

    /**
     * Compatibility shim for the {@link IAurora} contract; the real work
     * happens in {@link #render(PoseStack, float)}.
     */
    @Override
    public void render(final float partialTick) {
    }

    /**
     * Renders the aurora. The pose stack must be the level-render pose stack
     * (world/camera transform already applied); only the player-relative
     * translation is pushed here so vertices stay world-aligned around the
     * player.
     */
    public void render(final PoseStack poseStack, final float partialTick) {
        if (this.player == null)
            return;

        final VertexConsumer consumer = Minecraft.getInstance().renderBuffers().bufferSource()
                .getBuffer(this.renderType);

        final double tranY = getTranslationY(partialTick);
        final double tranX = getTranslationX(partialTick);
        final double tranZ = getTranslationZ(partialTick);

        final int alphaByte = (int) (getAlpha() * 255.0F);
        if (alphaByte <= 0)
            return;

        poseStack.pushPose();
        try {
            for (int b = 0; b < this.bandCount; b++) {
                final double zOff = tranZ + this.offset * BAND_OFFSET_FACTOR * b;

                // Back layer: bigger, further away, much dimmer.
                poseStack.pushPose();
                poseStack.translate(tranX, tranY, zOff - BACK_LAYER_Z_BIAS);
                poseStack.scale(BACK_LAYER_SCALE_X, BACK_LAYER_SCALE_Y, BACK_LAYER_SCALE_Z);
                renderRibbon(poseStack, consumer, partialTick, (int) (alphaByte * BACK_LAYER_ALPHA_FACTOR));
                poseStack.popPose();

                // Front layer.
                poseStack.pushPose();
                poseStack.translate(tranX, tranY, zOff);
                poseStack.scale(SCALE_X, SCALE_Y, SCALE_Z);
                renderRibbon(poseStack, consumer, partialTick, alphaByte);
                poseStack.popPose();
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Emits the band as one continuous vertical quad strip. {@code u} runs
     * along the band from 0 to 1, {@code v} from 0 at the bottom to 1 at the
     * top; the fragment shader maps both onto its curtain domain.
     */
    private void renderRibbon(final PoseStack poseStack, final VertexConsumer consumer,
            final float partialTick, final int alphaByte) {

        this.band.translate(partialTick);
        final Panel[] array = this.band.getNodeList();
        final var pose = poseStack.last().pose();

        for (int i = 0; i < array.length - 1; i++) {
            final Panel node = array[i];
            final Panel next = array[i + 1];

            final float u0 = (float) i / (float) (array.length - 1);
            final float u1 = (float) (i + 1) / (float) (array.length - 1);

            final float x0 = node.posX;
            final float z0 = node.getModdedZ();
            final float x1 = next.posX;
            final float z1 = next.getModdedZ();

            consumer.addVertex(pose, x0, 0.0F, z0).setUv(u0, 0.0F).setColor(255, 255, 255, alphaByte);
            consumer.addVertex(pose, x0, 1.0F, z0).setUv(u0, 1.0F).setColor(255, 255, 255, alphaByte);
            consumer.addVertex(pose, x1, 1.0F, z1).setUv(u1, 1.0F).setColor(255, 255, 255, alphaByte);
            consumer.addVertex(pose, x1, 0.0F, z1).setUv(u1, 0.0F).setColor(255, 255, 255, alphaByte);
        }
    }

    @Override
    public String toString() {
        return "<SHADER> " + super.toString();
    }

    /** @return the render type in use, for diagnostics. */
    @Nullable
    RenderType renderType() {
        return this.renderType;
    }
}
