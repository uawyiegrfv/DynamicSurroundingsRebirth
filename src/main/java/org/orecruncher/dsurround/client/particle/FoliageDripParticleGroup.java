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
 * 26.1: the foliage droplet's OWN particle group - the plane-aligned quad
 * cannot go through the vanilla single-quad state (one scale, square quads,
 * camera-facing), so it is emitted here as raw geometry, exactly the route
 * {@link IcicleParticleGroup} opened for the icicle's cross.
 *
 * <p>TRANSLUCENT, not the icicle's OPAQUE: the dew texture's soft 210-alpha
 * edge is the point. The particles extract their four corner positions
 * already camera-relative and already stretched (slide) / faded (evaporate) /
 * breathing (rest) - the state only packs and re-emits them, both windings
 * because the translucent layer backface-culls like the opaque one. Falling
 * drops extract a camera-facing billboard through the same sink, so one
 * group carries a drop through its whole life (26.1 assigns a particle to
 * its group at add time - a per-phase group switch would strand it).</p>
 */
public class FoliageDripParticleGroup extends ParticleGroup<FoliageDripParticle> {

    /** The group FoliageDripParticle reports, and the one ModParticles registers. */
    private static final ParticleRenderType TYPE =
            new ParticleRenderType(Constants.MOD_ID + "_foliage_drips");

    /** The vanilla TRANSLUCENT particle pipeline over the particle atlas. */
    private static final RenderType RENDER_TYPE = RenderType.create(
            Constants.MOD_ID + "_foliage_drip",
            RenderSetup.builder(RenderPipelines.TRANSLUCENT_PARTICLE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_PARTICLES)
                    .createRenderSetup());

    private final FoliageRenderState state = new FoliageRenderState();

    public FoliageDripParticleGroup(final ParticleEngine engine) {
        super(engine);
    }

    /** The render type {@link FoliageDripParticle#getGroup()} returns. */
    public static ParticleRenderType type() {
        return TYPE;
    }

    @Override
    public ParticleGroupRenderState extractRenderState(final Frustum frustum, final Camera camera,
                                                       final float partialTickTime) {
        this.state.clear();
        for (final FoliageDripParticle particle : this.particles) {
            if (particle.inView(frustum))
                particle.extractCorners(this.state, camera, partialTickTime);
        }
        return this.state;
    }

    /** One droplet's four corners, camera-relative, plus its sprite window. */
    public interface CornerSink {
        void add(float[] corners, float u0, float u1, float v0, float v1, int argb, int light);
    }

    private static final class FoliageRenderState implements ParticleGroupRenderState, CornerSink {

        private static final int[] ORDER = { 0, 1, 2, 3 };
        private static final int[] REVERSED = { 3, 2, 1, 0 };
        private static final int FLOATS = 12;
        private static final int FLOATS_UV = 4;
        private static final int INTS = 2;

        private float[] corners = new float[FLOATS * 64];
        private float[] uvs = new float[FLOATS_UV * 64];
        private int[] packed = new int[INTS * 64];
        private int count;

        @Override
        public void clear() {
            this.count = 0;
        }

        @Override
        public void add(final float[] corners, final float u0, final float u1,
                        final float v0, final float v1, final int argb, final int light) {
            if (this.count * INTS == this.packed.length) {
                final float[] oldC = this.corners;
                final float[] oldU = this.uvs;
                final int[] oldP = this.packed;
                this.corners = new float[oldC.length * 2];
                this.uvs = new float[oldU.length * 2];
                this.packed = new int[oldP.length * 2];
                System.arraycopy(oldC, 0, this.corners, 0, oldC.length);
                System.arraycopy(oldU, 0, this.uvs, 0, oldU.length);
                System.arraycopy(oldP, 0, this.packed, 0, oldP.length);
            }
            System.arraycopy(corners, 0, this.corners, this.count * FLOATS, FLOATS);
            final int u = this.count * FLOATS_UV;
            this.uvs[u] = u0;
            this.uvs[u + 1] = u1;
            this.uvs[u + 2] = v0;
            this.uvs[u + 3] = v1;
            final int t = this.count * INTS;
            this.packed[t] = argb;
            this.packed[t + 1] = light;
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
                final int u = n * FLOATS_UV;
                final int t = n * INTS;
                final float u0 = this.uvs[u], u1 = this.uvs[u + 1], v0 = this.uvs[u + 2], v1 = this.uvs[u + 3];
                final int argb = this.packed[t];
                final int light = this.packed[t + 1];
                final float[] c = this.corners;
                // Corner order (-1,-1) (-1,1) (1,1) (1,-1) maps to (c0,c1,c2,c3);
                // both windings, the backface cull would otherwise hide half.
                final int[][] seqs = { ORDER, REVERSED };
                for (final int[] seq : seqs) {
                    for (final int i : seq) {
                        buffer.addVertex(pose, c[i * 3], c[i * 3 + 1], c[i * 3 + 2])
                                .setUv(i == 0 || i == 3 ? u0 : u1, i == 0 || i == 1 ? v0 : v1)
                                .setColor(argb)
                                .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
                    }
                }
            }
        }
    }
}
