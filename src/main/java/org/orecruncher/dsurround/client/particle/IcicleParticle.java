package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;
import net.minecraft.resources.ResourceLocation;

/**
 * The hanging icicle - a persistent eave ornament, not a momentary puff.
 *
 * <p>Shape and size are DECOUPLED. A texture defines a shape (cone / knife /
 * fang - three flavors of taper); the world size comes from the five-tier
 * height table below, scaled by the quad. One texture therefore reads at
 * every size, and the handler rolls shape (from the position hash, so the
 * same eave keeps its mix) and size (from the snowfall-duration curve)
 * independently.</p>
 *
 * <p>Life: it grows out of the roof edge over ~20 seconds (hash-delayed per
 * position), hangs as long as the snow lasts, then either lets go on its own
 * dice or when the snow stops (the handler staggers those), and falls.
 * Landing shatters it - the burst is spawned right here, the sound is booked
 * by the handler on the same clock.</p>
 *
 * <p>Rendering is the maintainer's ruling: two vertical quads crossed at 90
 * degrees, and they do NOT turn with the camera. The cross sits on the BLOCK
 * DIAGONALS (rotated 45 degrees off the axes - the maintainer re-examined the
 * live screenshots and ruled the orientation was not what made the shape read
 * sliced): face any block axis and both planes then show at 45 degrees each.
 * Each plane is emitted twice, once per winding, because a single winding
 * vanishes under the opaque sheet's backface cull - four quads total. The
 * winding conclusion the drip particle documented still anchors it: the
 * unrotated corner order (-1,-1)→(-1,1)→(1,1)→(1,-1) faces -Z.</p>
 *
 * <p>The particle's position is the TIP, not the center: the quads are
 * shifted up by their own half-height, so the quad top - the icicle's root in
 * the texture - sits one full quad-height above the position. The collision
 * box hangs off the position's bottom edge, so the fall ends the frame the
 * tip reaches the ground. While growing, the position walks DOWN as {@code
 * quadSize} increases, keeping the root pinned to the roof edge.</p>
 */
public class IcicleParticle extends TextureSheetParticle {

    /** Ticks from first appearance to full length. 20s: a snowfall fills an eave gradually. */
    public static final int GROWTH_TICKS = 400;
    /** Visual icicle height (blocks) per size tier, 0..4. The maintainer's
     * re-scale: the old grand tier was 0.40 and read too small - the ladder
     * now reaches 0.70, which at an 11-row texture puts texels at exactly
     * block-face density. */
    public static final float[] TIER_HEIGHTS = { 0.14F, 0.22F, 0.34F, 0.50F, 0.70F };
    /** Fraction of full size at first appearance. */
    private static final float GROWTH_START = 0.25F;
    /** cos/sin of 45 degrees - the block-diagonal orientation of the cross. */
    private static final float DIAGONAL = 0.7071068F;

    private final float fullSize;
    /** Y of the root - the roof underside the icicle grows from. Fixed for life. */
    private final double rootY;
    /** Ticks to wait before growth starts (spread from the position hash). */
    private final int growthDelay;
    private boolean falling = false;

    IcicleParticle(final ClientLevel level, final double x, final double rootY, final double z,
                   final float fullQuadSize, final int growthDelay, final TextureAtlasSprite sprite) {
        // fullQuadSize arrives already converted from the tier height and the
        // shape's texture rows; growthDelay rides in through the vy spawn slot
        // (the same trick the drip uses for its hang/fall ticks) - both are
        // decided ONCE by the handler, the delay from the position hash, the
        // size from the snowfall-duration curve.
        super(level, x, rootY, z);
        this.setSprite(sprite);
        this.fullSize = fullQuadSize;
        this.rootY = rootY;
        this.growthDelay = growthDelay;
        // The position is the tip; it starts hanging a starter-length below the root.
        this.quadSize = this.fullSize * GROWTH_START;
        this.y = rootY - 2.0D * this.quadSize;
        this.yo = this.y;
        // Effective immortal while attached - the handler decides when an
        // icicle ends. The safety cap only matters once falling (set there).
        this.lifetime = Integer.MAX_VALUE;
        this.gravity = 0.0F;
        this.hasPhysics = true;
        this.setSize(0.1F, 0.1F);
    }

    /** Lets go now: switch to the fall, and cap the lifetime at the fall's length. */
    public void release() {
        if (this.falling)
            return;
        this.falling = true;
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        this.lifetime = this.age + 100;
    }

    /** Gone without a shatter - the player walked away from it. */
    public void discard() {
        this.remove();
    }

    public boolean isDead() {
        return this.removed;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;

        if (this.falling) {
            // Ballistic on purpose, no drag: the handler books the shatter
            // sound from the analytic fall time of THIS exact integration
            // (0.06 per tick, distance 0.03*n*(n+1)), and drag would desync it.
            this.yd -= 0.06D;
            this.move(this.xd, this.yd, this.zd);
            if (this.onGround || this.age >= this.lifetime) {
                this.shatter();
            }
            return;
        }

        // Growing / attached. Length rides quadSize; the tip (this position)
        // walks down so the root stays on the roof edge.
        final float progress = Mth.clamp((this.age - this.growthDelay) / (float) GROWTH_TICKS, 0.0F, 1.0F);
        final float eased = progress * progress * (3.0F - 2.0F * progress);
        this.quadSize = this.fullSize * (GROWTH_START + (1.0F - GROWTH_START) * eased);
        this.y = this.rootY - 2.0D * this.quadSize;
    }

    /** The shatter: a burst of shard particles, then gone. The sound rides the handler's clock. */
    private void shatter() {
        for (int i = 0; i < 7; i++) {
            this.level.addParticle(ModParticles.ICICLE_LAND,
                    this.x, this.y + 0.05D, this.z,
                    (this.random.nextFloat() - 0.5D) * 0.22D,
                    0.10D + this.random.nextFloat() * 0.12D,
                    (this.random.nextFloat() - 0.5D) * 0.22D);
        }
        this.remove();
    }

    /**
     * Two crossed vertical planes on the BLOCK DIAGONALS, each emitted with
     * both windings, quads shifted up one half-height so the texture's top row
     * (the root) sits one quad-height above the particle position.
     */
    @Override
    public void render(final VertexConsumer buffer, final Camera camera, final float partialTicks) {
        final Vec3 eye = camera.getPosition();
        final float px = (float) (Mth.lerp(partialTicks, this.xo, this.x) - eye.x());
        final float py = (float) (Mth.lerp(partialTicks, this.yo, this.y) - eye.y());
        final float pz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - eye.z());
        final float size = this.getQuadSize(partialTicks);
        final float yBase = py + size;   // quad center line; top lands on the root
        final int light = this.getLightColor(partialTicks);
        final float u0 = this.getU0();
        final float u1 = this.getU1();
        final float v0 = this.getV0();
        final float v1 = this.getV1();

        // The standard corner order (-1,-1) (-1,1) (1,1) (1,-1), UVs paired as
        // the drip pairs them. Both planes are pre-rotated 45 degrees about Y
        // onto the block diagonals: plane A spans the NW-SE horizontal, plane
        // B the NE-SW one.
        final float[] lx = { -1F, -1F, 1F, 1F };
        final float[] ly = { -1F, 1F, 1F, -1F };
        final float[] lu = { u1, u1, u0, u0 };
        final float[] lv = { v1, v0, v0, v1 };
        final int[] order = { 0, 1, 2, 3 };
        final int[] reversed = { 3, 2, 1, 0 };

        for (final int[] seq : new int[][] { order, reversed }) {
            // plane A: spans (diagonal, Y) - face toward the -X/-Z diagonal (order) or its opposite
            for (final int i : seq) {
                buffer.addVertex(px + lx[i] * DIAGONAL * size, yBase + ly[i] * size, pz - lx[i] * DIAGONAL * size)
                        .setUv(lu[i], lv[i])
                        .setColor(this.rCol, this.gCol, this.bCol, this.alpha)
                        .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
            }
            // plane B: the perpendicular diagonal - face toward the +X/-Z diagonal (order) or its opposite
            for (final int i : seq) {
                buffer.addVertex(px - lx[i] * DIAGONAL * size, yBase + ly[i] * size, pz - lx[i] * DIAGONAL * size)
                        .setUv(lu[i], lv[i])
                        .setColor(this.rCol, this.gCol, this.bCol, this.alpha)
                        .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
            }
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        // Hard-pixel texture, full alpha inside the shape: the opaque sheet's
        // alpha discard handles the transparent surround, and opaque sorts
        // against the world the way the drip does.
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    /** One provider per shape; the type carries the shape. The handler owns
     * the position hash and the snowfall-duration curve: it passes the SIZE
     * TIER through the vx spawn slot and the growth delay through vy (both 0
     * when summoned by hand). y rides in as the ROOT - the roof underside. */
    public record Provider(int rows, ResourceLocation spriteId) implements net.minecraft.client.particle.ParticleProvider<SimpleParticleType> {

        @Override
        public net.minecraft.client.particle.Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                                                     final double x, final double y, final double z,
                                                                     final double vx, final double vy, final double vz) {
            final int tier = Mth.clamp((int) Math.round(vx), 0, TIER_HEIGHTS.length - 1);
            // World height -> quad size: the icicle occupies (rows/16) of the
            // quad's full 2*quadSize height.
            final float fullQuadSize = TIER_HEIGHTS[tier] * 16F / (2F * this.rows);
            return new IcicleParticle(level, x, y, z, fullQuadSize,
                    (int) Math.max(0, Math.min(199, Math.round(vy))),
                    ParticleUtils.getSprite(this.spriteId));
        }
    }
}
