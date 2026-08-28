package org.orecruncher.dsurround.effects.particles;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.WaterRippleStyle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.gui.ColorPalette;

/**
 * Expanding ring drawn flat on the water surface. 1.20.1 port: extends TextureSheetParticle
 * and draws its flat quad in a custom render(). The strip texture is stitched into the
 * vanilla particle atlas and the WaterRippleStyle frame UVs are remapped into the sprite's
 * atlas bounds.
 */
public class WaterRippleParticle extends TextureSheetParticle {

    private static final ResourceLocation RIPPLE_TEXTURE = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "particle/pixel_ripples");
    private static final float TEX_SIZE_HALF = 0.5F;
    private static final int BLOCKS_FROM_FADE = 5;
    private static final int MAX_BLOCKS_FADE = 12;

    // Start the animation at frame 2: the very first frame has only a handful of opaque
    // pixels which the particle shader (alpha < 0.1 -> discard) drops entirely.
    private static final int START_FRAME_INDEX = 2;

    private final WaterRippleStyle rippleStyle;
    private final float growthRate;
    private float scaledWidth;
    private float texU1;
    private float texU2;
    private float texV1;
    private float texV2;
    private final float defaultColorAlpha;

    public WaterRippleParticle(WaterRippleStyle rippleStyle, ClientLevel world, double x, double y, double z) {
        super(world, x, y, z);
        this.setSprite(ParticleUtils.getSprite(RIPPLE_TEXTURE));

        this.rippleStyle = rippleStyle;
        this.lifetime = rippleStyle.getMaxAge();

        if (rippleStyle.doScaling()) {
            this.growthRate = this.lifetime / 500F;
            this.quadSize = this.growthRate * 1.8F;
            this.scaledWidth = this.quadSize * TEX_SIZE_HALF;
        } else {
            this.growthRate = 0F;
            this.quadSize = 1.8F;
            this.scaledWidth = 0.5F;
        }

        // Sit slightly proud of the surface so the ring is not depth-occluded by the water
        this.y += 0.05D;

        var player = GameUtils.getPlayer().orElseThrow();
        var cameraPos = BlockPos.containing(player.getEyePosition(1.0f));
        var position = BlockPos.containing(this.x, this.y, this.z);

        var colorRgb = this.level.getBiome(position).value().getWaterColor();
        this.setColor(ColorPalette.getRed(colorRgb) / 255F, ColorPalette.getGreen(colorRgb) / 255F, ColorPalette.getBlue(colorRgb) / 255F);

        float distance = (float) Mth.clamp(
                Math.sqrt(cameraPos.distSqr(position)) - BLOCKS_FROM_FADE,
                0,
                MAX_BLOCKS_FADE
        );
        this.alpha = this.defaultColorAlpha = 0.60F * (MAX_BLOCKS_FADE - distance) / MAX_BLOCKS_FADE;

        this.texU1 = rippleStyle.getU1(START_FRAME_INDEX);
        this.texU2 = rippleStyle.getU2(START_FRAME_INDEX);
        this.texV1 = rippleStyle.getV1(START_FRAME_INDEX);
        this.texV2 = rippleStyle.getV2(START_FRAME_INDEX);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        Vec3 cam = camera.getPosition();
        float x = (float) (Mth.lerp(partialTick, this.xo, this.x) - cam.x());
        float y = (float) (Mth.lerp(partialTick, this.yo, this.y) - cam.y());
        float z = (float) (Mth.lerp(partialTick, this.zo, this.z) - cam.z());

        float half = this.getQuadSize(partialTick) / 2F;
        int light = this.getLightColor(partialTick);
        float u0 = this.getU0(), u1 = this.getU1(), v0 = this.getV0(), v1 = this.getV1();

        // Flat on the XZ plane, facing up.
        consumer.vertex(x - half, y, z - half).uv(u1, v1).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        consumer.vertex(x - half, y, z + half).uv(u1, v0).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        consumer.vertex(x + half, y, z + half).uv(u0, v0).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        consumer.vertex(x + half, y, z - half).uv(u0, v1).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
    }

    @Override
    public float getQuadSize(float tickDelta) {
        // Fixed quad size; the ripple ring expands inside the quad via the frame animation.
        return this.scaledWidth;
    }

    @Override
    protected float getU0() {
        return remapU(this.texU1);
    }

    @Override
    protected float getU1() {
        return remapU(this.texU2);
    }

    @Override
    protected float getV0() {
        return remapV(this.texV1);
    }

    @Override
    protected float getV1() {
        return remapV(this.texV2);
    }

    // Mth.lerp(delta, start, end): the frame UV (0..1) is the interpolation delta
    private float remapU(float u) {
        return (float) Mth.lerp(u, this.sprite.getU0(), this.sprite.getU1());
    }

    private float remapV(float v) {
        return (float) Mth.lerp(v, this.sprite.getV0(), this.sprite.getV1());
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            if (this.rippleStyle.doScaling()) {
                this.quadSize += this.growthRate;
                this.scaledWidth = this.quadSize * TEX_SIZE_HALF;
            }

            if (this.rippleStyle.doAlpha()) {
                this.alpha = this.defaultColorAlpha * (float) (this.lifetime - this.age)/this.lifetime;
            }

            // Animate frames from START_FRAME_INDEX onward (skip the discard-prone tiny frames)
            int frame = Math.min(this.age + START_FRAME_INDEX, 12);
            this.texU1 = this.rippleStyle.getU1(frame);
            this.texU2 = this.rippleStyle.getU2(frame);
            this.texV1 = this.rippleStyle.getV1(frame);
            this.texV2 = this.rippleStyle.getV2(frame);
        }
    }
}
