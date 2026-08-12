package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.FootprintStyle;

/**
 * A footprint left on the ground, ported from the original 1.12.2 MoteFootprint.
 * 26.1 rewrite: extends SingleQuadParticle and lays its quad flat on the ground.
 * The 26.1 particle pipeline only renders square quads, so we sample a square
 * central sub-region of the footprint atlas cell and rotate the print so one
 * edge faces the direction of travel. The print fades out quadratically,
 * matching the original (alpha 0.4 * (1 - f^2)).
 */
public class FootprintParticle extends SingleQuadParticle {

    private static final Identifier FOOTPRINT_TEXTURE = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "particle/footprint");
    private static final float TEXEL_WIDTH = 1F / 8F;
    private static final float HALF_TEXEL = TEXEL_WIDTH / 2F;
    private static final int LIFETIME = 200;

    private final float texU1;
    private final float texU2;
    private final float texV1;
    private final float texV2;
    private final float yaw;

    public FootprintParticle(FootprintStyle style, boolean isRight, float yaw, ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, ParticleUtils.getSprite(FOOTPRINT_TEXTURE));

        this.lifetime = LIFETIME;
        this.quadSize = 0.12F;
        this.alpha = 0.4F;
        this.yaw = yaw;

        // Sit just above the block face to avoid z-fighting.
        this.y += 0.02D;

        // Sample a square 16x16 sub-region of the cell: half the width, and the
        // middle vertical band where the print sits. Solid square styles then
        // render as a clean square instead of a stretched rectangle.
        float u = style.ordinal() * TEXEL_WIDTH + (isRight ? HALF_TEXEL : 0F);
        this.texU1 = u + 1 / 256F;
        this.texU2 = u + HALF_TEXEL - 1 / 256F;
        this.texV1 = 0.25F;
        this.texV2 = 0.75F;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        // Turn the print to face the direction of travel, then lay it flat on the
        // ground. The 26.1 quad is built in the XY plane with the texture top (+Y) at the
        // toe; folding it flat with rotationX(-90) and then rotating by rotationY(yaw)
        // would point the toe at (-sin yaw, -cos yaw) - mirrored across the N/S axis, so
        // walking east/west looked right but north/south prints pointed backwards (the
        // original 1.12.2 MoteFootprint compensated with -rotation + 180). Rotating by
        // (PI - yaw) maps texture top onto the true forward (-sin yaw, cos yaw) in every
        // world direction.
        Quaternionf rotation = new Quaternionf().rotationY((float) (Math.PI - this.yaw))
                .mul(new Quaternionf().rotationX(-Mth.HALF_PI));

        // Fade out quadratically over the lifetime.
        float f = (this.age + partialTick) / ((float) this.lifetime + 1F);
        f = f * f;
        this.alpha = Mth.clamp(1.0F - f, 0F, 1F) * 0.4F;

        this.extractRotatedQuad(state, camera, rotation, partialTick);
    }

    @Override
    protected float getU0() {
        return Mth.lerp(this.texU1, this.sprite.getU0(), this.sprite.getU1());
    }

    @Override
    protected float getU1() {
        return Mth.lerp(this.texU2, this.sprite.getU0(), this.sprite.getU1());
    }

    @Override
    protected float getV0() {
        return Mth.lerp(this.texV1, this.sprite.getV0(), this.sprite.getV1());
    }

    @Override
    protected float getV1() {
        return Mth.lerp(this.texV2, this.sprite.getV0(), this.sprite.getV1());
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

        // The print rests on a block's surface; if that block was removed (mined, moved,
        // exploded) or its surface dropped (a multi-layer snow stack partially removed),
        // the print would otherwise keep floating in mid-air until its lifetime expires.
        // Probe just below the print - the particle sits ~0.02 above the surface, so the
        // block under y-0.05 is the supporting block. Compare against the visual surface
        // (getShape, same convention as spawnPrint's snow-layer handling): a print is
        // unsupported if the surface is more than a third of a block below it.
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
