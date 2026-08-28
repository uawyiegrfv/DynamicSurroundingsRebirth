package org.orecruncher.dsurround.processing.aurora;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.orecruncher.dsurround.processing.aurora.AuroraColor.ColorF;

/**
 * Classic (non-shader) aurora renderer. Ported from 1.12.2 Dynamic Surroundings
 * (MIT).
 *
 * <p>The 1.12.2 implementation streamed triangles through the immediate-mode
 * {@code Tessellator} with a POSITION_COLOR format and an additive blend.
 * 1.20.1 has no immediate mode, so the same band geometry is submitted through
 * {@code MultiBufferSource} using {@code RenderType.debugQuads()} — that
 * pipeline is POSITION_COLOR + QUADS + translucent blend, which matches
 * the original look without a custom shader.
 */
public final class AuroraClassic extends AuroraBase {

    private static final float SCALE_XZ = 0.5F;
    private static final float SCALE_Y = 8.0F;

    public AuroraClassic(final long seed) {
        super(seed);
    }

    @Override
    public void update() {
        super.update();
        this.band.update();
    }

    /**
     * Compatibility shim for the {@link IAurora} contract. 1.20.1 rendering needs
     * the level-render PoseStack, so the real work happens in
     * {@link #render(PoseStack, float)}.
     */
    @Override
    public void render(final float partialTick) {
    }

    /**
     * Renders the aurora. The pose stack must be the level-render pose stack
     * (world/camera transform already applied); this method only pushes the
     * player-relative translation, so vertices are world-aligned around the
     * player.
     */
    public void render(final PoseStack poseStack, final float partialTick) {
        if (this.player == null)
            return;

        final VertexConsumer consumer = Minecraft.getInstance().renderBuffers().bufferSource()
                .getBuffer(RenderType.debugQuads());

        final double tranY = getTranslationY(partialTick);
        final double tranX = getTranslationX(partialTick);
        final double tranZ = getTranslationZ(partialTick);

        poseStack.pushPose();
        try {
            for (int b = 0; b < this.bandCount; b++) {
                poseStack.pushPose();
                poseStack.translate(tranX, tranY, tranZ + this.offset * b);
                poseStack.scale(SCALE_XZ, SCALE_Y, SCALE_XZ);
                renderBand(poseStack, consumer, partialTick);
                poseStack.popPose();
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Renders one band strip relative to the current pose origin.
     */
    private void renderBand(final PoseStack poseStack, final VertexConsumer consumer, final float partialTick) {

        final float alpha = getAlpha();
        final ColorF base = getBaseColor();
        final ColorF fade = getFadeColor();

        this.band.translate(partialTick);
        final Panel[] array = this.band.getNodeList();

        for (int i = 0; i < array.length - 1; i++) {

            final Panel node = array[i];

            final double posY = node.getModdedY();
            final double posX = node.tetX;
            final double posZ = node.tetZ;
            final double tetX = node.tetX2;
            final double tetZ = node.tetZ2;

            final double posX2;
            final double posZ2;
            final double tetX2;
            final double tetZ2;
            final double posY2;

            if (i < array.length - 2) {
                final Panel nodePlus = array[i + 1];
                posX2 = nodePlus.tetX;
                posZ2 = nodePlus.tetZ;
                tetX2 = nodePlus.tetX2;
                tetZ2 = nodePlus.tetZ2;
                posY2 = nodePlus.getModdedY();
            } else {
                posX2 = tetX2 = node.posX;
                posZ2 = tetZ2 = node.getModdedZ();
                posY2 = 0.0D;
            }

            // Front face
            quad(poseStack, consumer,
                    posX, 0, posZ, posX, posY, posZ,
                    posX2, posY2, posZ2, posX2, 0, posZ2,
                    base, fade, alpha);
            // Bottom
            quad(poseStack, consumer,
                    posX, 0, posZ, posX2, 0, posZ2,
                    tetX2, 0, tetZ2, tetX, 0, tetZ,
                    base, fade, alpha);
            // Back face
            quad(poseStack, consumer,
                    tetX, 0, tetZ, tetX, posY, tetZ,
                    tetX2, posY2, tetZ2, tetX2, 0, tetZ2,
                    base, fade, alpha);
        }
    }

    /**
     * Emits one quad with a vertical gradient: the base colour at the bottom
     * edges, fading to transparent at the top edges.
     */
    private static void quad(final PoseStack poseStack, final VertexConsumer consumer,
            final double x0, final double y0, final double z0,
            final double x1, final double y1, final double z1,
            final double x2, final double y2, final double z2,
            final double x3, final double y3, final double z3,
            final ColorF base, final ColorF fade, final float alpha) {
        final var pose = poseStack.last().pose();
        // Bottom vertices get the base colour; top vertices fade to transparent.
        consumer.vertex(pose, (float) x0, (float) y0, (float) z0)
                .color(base.red, base.green, base.blue, alpha).endVertex();
        consumer.vertex(pose, (float) x1, (float) y1, (float) z1)
                .color(fade.red, fade.green, fade.blue, 0.0F).endVertex();
        consumer.vertex(pose, (float) x2, (float) y2, (float) z2)
                .color(fade.red, fade.green, fade.blue, 0.0F).endVertex();
        consumer.vertex(pose, (float) x3, (float) y3, (float) z3)
                .color(base.red, base.green, base.blue, alpha).endVertex();
    }

    @Override
    public String toString() {
        return "<CLASSIC> " + super.toString();
    }
}
