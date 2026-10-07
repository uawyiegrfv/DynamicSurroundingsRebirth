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
 * 26.1: the snow unit's OWN particle group - cloned from {@link
 * FoliageDripParticleGroup} (same plane-aligned quad, emitted as raw geometry
 * through {@code submitCustomGeometry} because the vanilla single-quad state
 * allows only one scale per quad, i.e. square, camera-facing quads).
 *
 * <p>The pipeline was <b>OPAQUE</b> and is <b>TRANSLUCENT</b> as of round 7.
 * Opaque disables blending, so a vertex alpha is discarded there - which is
 * why the snow layer could only ever melt by SHRINKING, and the maintainer
 * asked for the opposite: "直接缩小不太好，还不如下滑一大段距离以后直接慢慢
 * 隐去消失". A fade needs the blending pipeline. The sprite bodies are still
 * fully opaque, so a resting unit looks exactly as it did. Cloning {@link
 * IcicleParticleGroup} would have been the other route; this one keeps the
 * droplet's corner extraction, which the snow unit inherits unchanged.</p>
 */
public class FoliageSnowParticleGroup extends ParticleGroup<FoliageSnowParticle> {

    /** The group FoliageSnowParticle reports, and the one ModParticles registers. */
    private static final ParticleRenderType TYPE =
            new ParticleRenderType(Constants.MOD_ID + "_foliage_snow");

    /** The vanilla OPAQUE particle pipeline over the particle atlas. */
    private static final RenderType RENDER_TYPE = RenderType.create(
            Constants.MOD_ID + "_foliage_snow_unit",
            RenderSetup.builder(RenderPipelines.TRANSLUCENT_PARTICLE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_PARTICLES)
                    .createRenderSetup());

    private final SnowRenderState state = new SnowRenderState();

    public FoliageSnowParticleGroup(final ParticleEngine engine) {
        super(engine);
    }

    /** The render type {@link FoliageSnowParticle#getGroup()} returns. */
    public static ParticleRenderType type() {
        return TYPE;
    }

    @Override
    public ParticleGroupRenderState extractRenderState(final Frustum frustum, final Camera camera,
                                                       final float partialTickTime) {
        this.state.clear();
        for (final FoliageSnowParticle particle : this.particles) {
            if (particle.inView(frustum))
                particle.extractCorners(this.state, camera, partialTickTime);
        }
        return this.state;
    }

    /**
     * One unit's four corners, camera-relative, plus its sprite window.
     *
     * <p>It EXTENDS the droplet group's sink rather than redeclaring it: the
     * snow unit inherits {@code extractCorners} from {@link FoliageDripParticle},
     * and that method's parameter is {@link FoliageDripParticleGroup.CornerSink}.
     * A fresh interface with the same method would not be that type - the
     * compiler said exactly that, and the group would silently draw nothing.</p>
     */
    public interface CornerSink extends FoliageDripParticleGroup.CornerSink {
    }

    private static final class SnowRenderState implements ParticleGroupRenderState, CornerSink {

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
