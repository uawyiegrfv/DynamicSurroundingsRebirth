package org.orecruncher.dsurround.effects.particles;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.FootprintStyle;

/**
 * A footprint left on the ground, ported from the original 1.12.2 MoteFootprint.
 * 1.20.1 port: extends TextureSheetParticle and lays its quad flat on the ground in a
 * custom render(). Like the original, the print is a 1:2 rectangle sampling the full
 * 32px cell height (the visible print occupies the middle band; the empty margins
 * space out consecutive prints). The print fades out quadratically, matching the
 * original.
 */
public class FootprintParticle extends TextureSheetParticle {

    private static final ResourceLocation FOOTPRINT_TEXTURE = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "particle/footprint");
    private static final int LIFETIME = 200;
    private static final int ATLAS_WIDTH = 256;

    // Print rectangle in world units, matching the 1.12.2 MoteFootprint (WIDTH=0.125,
    // LENGTH=WIDTH*2 as half-extents -> a 0.25 x 0.5 print).
    private static final float HALF_WIDTH = 0.125F;
    private static final float HALF_LENGTH = 0.25F;

    private final float texU1;
    private final float texU2;
    private final float texV1;
    private final float texV2;
    private final float sinYaw;
    private final float cosYaw;

    public FootprintParticle(FootprintStyle style, boolean isRight, float yaw, ClientLevel world, double x, double y, double z) {
        super(world, x, y, z);
        this.setSprite(ParticleUtils.getSprite(FOOTPRINT_TEXTURE));

        this.lifetime = LIFETIME;
        this.alpha = 0.4F;

        this.sinYaw = Mth.sin(yaw);
        this.cosYaw = Mth.cos(yaw);

        // Sit just above the block face to avoid z-fighting.
        this.y += 0.02D;

        // Each style cell (32px wide) holds a mirrored left/right print pair. Column 0 of
        // a cell is a hard separator edge (alpha 255), and the right print's soft edge
        // runs one column into the next cell - so the 1.12.2 windows are [cell+1,
        // cell+17) for the left print and [cell+17, cell+33) for the right (both give a
        // symmetric 4/4 texel margin around the dark core). Quad edges are anchored on
        // texel CENTERS: bilinear filtering at an exact texel-boundary UV blends the
        // neighbouring column into the edge pixel, and for the left print that neighbour
        // is the alpha-255 separator - it rendered as a dark stripe down one side of the
        // print (the "left wide, right narrow" artifact). 1.12.2 did not hit this because
        // it sampled the raw texture with nearest filtering.
        int cellPx = style.ordinal() * 32;
        int loTexel = cellPx + (isRight ? 17 : 1);
        this.texU1 = (loTexel + 0.5F) / ATLAS_WIDTH;
        this.texU2 = (loTexel + 15.5F) / ATLAS_WIDTH;
        this.texV1 = 0F;
        this.texV2 = 1F;
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

        // Fade out quadratically over the lifetime.
        float f = (this.age + partialTick) / ((float) this.lifetime + 1F);
        f = f * f;
        float alpha = Mth.clamp(1.0F - f, 0F, 1F) * 0.4F;

        float halfW = HALF_WIDTH;
        float halfL = HALF_LENGTH;
        float u0 = this.getU0(), u1 = this.getU1(), v0 = this.getV0(), v1 = this.getV1();
        int light = this.getLightColor(partialTick);

        // Local print rectangle (1:2) in the XZ plane, rotated about Y by yaw. Corner/UV
        // order matches the 1.12.2 MoteFootprint in world space: texture +V (heel) points
        // backward, texture +U runs along the print's lateral axis, and the toe (texture
        // top, v0) ends up on the walk direction.
        float dx0 = -halfW, dz0 = -halfL;
        float dx1 = -halfW, dz1 = halfL;
        float dx2 = halfW, dz2 = halfL;
        float dx3 = halfW, dz3 = -halfL;

        float rx0 = dx0 * this.cosYaw - dz0 * this.sinYaw;
        float rz0 = dx0 * this.sinYaw + dz0 * this.cosYaw;
        float rx1 = dx1 * this.cosYaw - dz1 * this.sinYaw;
        float rz1 = dx1 * this.sinYaw + dz1 * this.cosYaw;
        float rx2 = dx2 * this.cosYaw - dz2 * this.sinYaw;
        float rz2 = dx2 * this.sinYaw + dz2 * this.cosYaw;
        float rx3 = dx3 * this.cosYaw - dz3 * this.sinYaw;
        float rz3 = dx3 * this.sinYaw + dz3 * this.cosYaw;

        consumer.vertex(x + rx0, y, z + rz0).uv(u1, v1).color(this.rCol, this.gCol, this.bCol, alpha).uv2(light).endVertex();
        consumer.vertex(x + rx1, y, z + rz1).uv(u1, v0).color(this.rCol, this.gCol, this.bCol, alpha).uv2(light).endVertex();
        consumer.vertex(x + rx2, y, z + rz2).uv(u0, v0).color(this.rCol, this.gCol, this.bCol, alpha).uv2(light).endVertex();
        consumer.vertex(x + rx3, y, z + rz3).uv(u0, v1).color(this.rCol, this.gCol, this.bCol, alpha).uv2(light).endVertex();
    }

    @Override
    protected float getU0() {
        return (float) Mth.lerp(this.texU1, this.sprite.getU0(), this.sprite.getU1());
    }

    @Override
    protected float getU1() {
        return (float) Mth.lerp(this.texU2, this.sprite.getU0(), this.sprite.getU1());
    }

    @Override
    protected float getV0() {
        return (float) Mth.lerp(this.texV1, this.sprite.getV0(), this.sprite.getV1());
    }

    @Override
    protected float getV1() {
        return (float) Mth.lerp(this.texV2, this.sprite.getV0(), this.sprite.getV1());
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        // Remove the print if its supporting block was removed or its surface dropped.
        final var below = BlockPos.containing(this.x, this.y - 0.05D, this.z);
        final var belowState = this.level.getBlockState(below);
        if (belowState.isAir()) {
            this.remove();
        } else {
            final var shape = belowState.getShape(this.level, below);
            if (!shape.isEmpty()) {
                final double surfaceY = below.getY() + shape.max(Direction.Axis.Y);
                if (this.y - surfaceY > 0.35D)
                    this.remove();
            }
        }
    }
}
