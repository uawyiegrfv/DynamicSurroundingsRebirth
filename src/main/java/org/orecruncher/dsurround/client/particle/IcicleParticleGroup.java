package org.orecruncher.dsurround.client.particle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.orecruncher.dsurround.Constants;

/**
 * 26.1: the icicle's OWN particle group - the one thing that version could not
 * borrow from the other two.
 *
 * <p>Why it exists at all: 26.1 dispatches particles by {@code getGroup()} into
 * a {@code ParticleGroup}, and the vanilla single-quad group stores ONE scale
 * per quad ({@code QuadParticleRenderState.add}), so every quad it draws is
 * SQUARE in world space. The icicle's quad is not square: its height comes from
 * the size table while its width is clamped to the maintainer's 0.06..0.15 band
 * independently (a wide icicle clips into blocks or reads as floating; a narrow
 * one vanishes). Squaring that circle meant cutting the height off at 0.70
 * blocks - see BUG-9 in {@code _icicle_review_2.md}.</p>
 *
 * <p>So the icicle leaves that group and gets its own: {@link
 * #extractRenderState} reads the live particles, and the state emits the
 * crossed diagonal planes itself through {@code
 * SubmitNodeCollector.submitCustomGeometry}, which takes vertices - height and
 * width are then two independent numbers and nothing has to give. The render
 * type is registered against this group's factory by {@code ModParticles} on
 * NeoForge's {@code RegisterParticleGroupsEvent}.</p>
 */
public class IcicleParticleGroup extends ParticleGroup<IcicleParticle> {

    /** The group IcicleParticle reports, and the one ModParticles registers. */
    private static final ParticleRenderType TYPE =
            new ParticleRenderType(Constants.MOD_ID + "_icicles");

    /** The vanilla OPAQUE particle pipeline and the particle atlas, rebuilt as
     *  a RenderType because custom geometry is submitted as one. */
    private static final RenderType RENDER_TYPE = RenderType.create(
            Constants.MOD_ID + "_icicle",
            RenderSetup.builder(RenderPipelines.OPAQUE_PARTICLE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_PARTICLES)
                    .createRenderSetup());

    private final IcicleRenderState state = new IcicleRenderState();

    public IcicleParticleGroup(final ParticleEngine engine) {
        super(engine);
    }

    /** The render type {@link IcicleParticle#getGroup()} returns. */
    public static ParticleRenderType type() {
        return TYPE;
    }

    @Override
    public ParticleGroupRenderState extractRenderState(final Frustum frustum, final Camera camera,
                                                       final float partialTickTime) {
        this.state.clear();
        for (final IcicleParticle particle : this.particles) {
            // Particle's position fields are protected, so the frustum test is
            // asked on the particle rather than read from here.
            if (particle.inView(frustum))
                particle.extractCross(this.state, camera, partialTickTime);
        }
        return this.state;
    }

    /** One icicle's cross, handed over by the particle during extraction. y is
     *  the quad's CENTRE line (one half-height above the tip), halfHeight and
     *  halfWidth are the quad's own half-extents - the two are independent. */
    public interface CrossSink {
        void add(float x, float y, float z, float halfHeight, float halfWidth,
                 float u0, float u1, float v0, float v1, int color, int light);
    }

    /**
     * The extracted cross set, drawn by hand: two vertical planes on the block
     * diagonals, each emitted with both windings because the opaque layer's
     * backface cull would otherwise hide half of every plane. Coordinates
     * arrive already relative to the camera (the particle extracts them that
     * way), so a fresh identity PoseStack goes in.
     */
    private static final class IcicleRenderState implements ParticleGroupRenderState, CrossSink {

        private static final float DIAGONAL = 0.7071068F;
        /** The unrotated corner order (-1,-1) (-1,1) (1,1) (1,-1) - the winding
         *  the drip particle documented as facing -Z. */
        private static final float[] LX = { -1F, -1F, 1F, 1F };
        private static final float[] LY = { -1F, 1F, 1F, -1F };
        private static final int[] ORDER = { 0, 1, 2, 3 };
        private static final int[] REVERSED = { 3, 2, 1, 0 };
        private static final int[][] SEQUENCES = { ORDER, REVERSED };
        private static final int FLOATS = 9;
        private static final int INTS = 2;

        private float[] values = new float[FLOATS * 16];
        private int[] packed = new int[INTS * 16];
        private int count;

        @Override
        public void clear() {
            this.count = 0;
        }

        @Override
        public void add(final float x, final float y, final float z, final float halfHeight,
                        final float halfWidth, final float u0, final float u1,
                        final float v0, final float v1, final int color, final int light) {
            if (this.count * INTS == this.packed.length) {
                final float[] oldValues = this.values;
                final int[] oldPacked = this.packed;
                this.values = new float[oldValues.length * 2];
                this.packed = new int[oldPacked.length * 2];
                System.arraycopy(oldValues, 0, this.values, 0, oldValues.length);
                System.arraycopy(oldPacked, 0, this.packed, 0, oldPacked.length);
            }
            int f = this.count * FLOATS;
            this.values[f++] = x;
            this.values[f++] = y;
            this.values[f++] = z;
            this.values[f++] = halfHeight;
            this.values[f++] = halfWidth;
            this.values[f++] = u0;
            this.values[f++] = u1;
            this.values[f++] = v0;
            this.values[f] = v1;
            int t = this.count * INTS;
            this.packed[t++] = color;
            this.packed[t] = light;
            this.count++;
        }

        @Override
        public void submit(final SubmitNodeCollector collector, final CameraRenderState camera) {
            if (this.count == 0)
                return;
            collector.submitCustomGeometry(new PoseStack(), RENDER_TYPE, this::emit);
        }

        private void emit(final PoseStack.Pose pose, final VertexConsumer buffer) {
            for (int n = 0; n < this.count; n++) {
                final int f = n * FLOATS;
                final int t = n * INTS;
                final float x = this.values[f];
                final float y = this.values[f + 1];
                final float z = this.values[f + 2];
                final float h = this.values[f + 3];
                final float w = this.values[f + 4];
                final float u0 = this.values[f + 5];
                final float u1 = this.values[f + 6];
                final float v0 = this.values[f + 7];
                final float v1 = this.values[f + 8];
                final int color = this.packed[t];
                final int light = this.packed[t + 1];
                for (final int[] seq : SEQUENCES) {
                    this.plane(pose, buffer, seq, false, x, y, z, h, w, u0, u1, v0, v1, color, light);
                    this.plane(pose, buffer, seq, true, x, y, z, h, w, u0, u1, v0, v1, color, light);
                }
            }
        }

        /** One vertical plane; {@code flipped} gives the perpendicular diagonal
         *  instead (the other half of the cross). */
        private void plane(final PoseStack.Pose pose, final VertexConsumer buffer, final int[] seq,
                           final boolean flipped, final float x, final float y, final float z,
                           final float h, final float w, final float u0, final float u1,
                           final float v0, final float v1, final int color, final int light) {
            for (int k = 0; k < 4; k++) {
                final int i = seq[k];
                final float ox = LX[i] * DIAGONAL * w;
                final float px = flipped ? x - ox : x + ox;
                final float pz = z - ox;
                final float u = i < 2 ? u1 : u0;
                final float v = i == 1 || i == 2 ? v0 : v1;
                buffer.addVertex(pose, px, y + LY[i] * h, pz)
                        .setUv(u, v)
                        .setColor(color)
                        .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
            }
        }
    }
}
