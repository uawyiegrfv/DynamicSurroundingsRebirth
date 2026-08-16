package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.WaterRippleStyle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.gui.ColorPalette;

/**
 * Expanding ring drawn flat on the water surface. 26.1 rewrite: TextureSheetParticle is
 * gone, so the particle now extends SingleQuadParticle and submits its quad through
 * extract() instead of drawing with a VertexConsumer in render(). The strip texture is
 * stitched into the vanilla particle atlas (assets/dsurround/textures/particle/), and the
 * WaterRippleStyle frame UVs are remapped into the sprite's atlas bounds.
 */
public class WaterRippleParticle extends SingleQuadParticle {

    private static final Identifier RIPPLE_TEXTURE = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "particle/pixel_ripples");
    private static final float TEX_SIZE_HALF = 0.5F;
    private static final int BLOCKS_FROM_FADE = 5;
    private static final int MAX_BLOCKS_FADE = 12;

    private final WaterRippleStyle rippleStyle;

    private final float growthRate;
    private float scaledWidth;
    private float texU1;
    private float texU2;
    private float texV1;
    private float texV2;
    private final float defaultColorAlpha;

    public WaterRippleParticle(WaterRippleStyle rippleStyle, ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, ParticleUtils.getSprite(RIPPLE_TEXTURE));

        this.rippleStyle = rippleStyle;
        this.lifetime = rippleStyle.getMaxAge();

        if (rippleStyle.doScaling()) {
            this.growthRate = this.lifetime / 500F;
            this.quadSize = this.growthRate;
            this.scaledWidth = this.quadSize * TEX_SIZE_HALF;
        } else {
            this.growthRate = 0F;
            this.quadSize = 1F;
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

    // Start the animation at frame 1: the very first frame has only a handful of opaque
    // pixels which the particle shader (alpha < 0.1 -> discard) drops entirely.
    private static final int START_FRAME_INDEX = 2;

    // Constant rotation - cached instead of allocating a Quaternionf every frame.
    private static final Quaternionf FLAT_ROTATION = new Quaternionf().rotationX(-Mth.HALF_PI);

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    /**
     * The ripple lies flat on the water surface; rotate the default billboard quad 90°
     * about X so its local XY plane maps onto the world XZ plane. The rotation must be
     * negative so the quad faces UP: 26.1 particle pipelines cull back faces by default
     * (RenderPipeline.Builder#cull defaults to true), and a +90° rotation leaves the
     * quad's normal pointing down - invisible from above.
     */
    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        this.extractRotatedQuad(state, camera, FLAT_ROTATION, partialTick);
    }

    @Override
    public float getQuadSize(float tickDelta) {
        // Fixed quad size; the ripple ring expands inside the quad via the frame animation.
        // (Growing the quad from 0 made the ripple invisible for its first frames.)
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
        return Mth.lerp(u, this.sprite.getU0(), this.sprite.getU1());
    }

    private float remapV(float v) {
        return Mth.lerp(v, this.sprite.getV0(), this.sprite.getV1());
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
