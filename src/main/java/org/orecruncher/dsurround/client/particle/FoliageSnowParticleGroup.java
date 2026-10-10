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
 * <p>Round 47: the layer is <b>TRANSLUCENT</b>, which is what this unit always
 * needed. The opaque pipeline discards a vertex alpha, so the melt/fade pass
 * could only ever shrink, and - the reason this was changed - an opaque quad
 * drawn in the opaque phase is depth-written and then covered by the cutout
 * plants it sits inside of, which is why a hundred submitted units read as
 * "one snow block". {@link IcicleParticleGroup}, whose units are visible,
 * submits the translucent pipeline too, and 1.20.1's snow sheet is translucent
 * for the same reason.</p>
 */
public class FoliageSnowParticleGroup extends ParticleGroup<FoliageSnowParticle> {

    /** The group FoliageSnowParticle reports, and the one ModParticles registers. */
    private static final ParticleRenderType TYPE =
            new ParticleRenderType(Constants.MOD_ID + "_foliage_snow");

    private static final RenderType RENDER_TYPE = RenderType.create(
            Constants.MOD_ID + "_foliage_snow_unit",
            RenderSetup.builder(RenderPipelines.TRANSLUCENT_PARTICLE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_PARTICLES)
                    .createRenderSetup());

    private final SnowRenderState state = new SnowRenderState();

    /**
     * Round 43 render counters, read by FoliageSnowHandler's periodic line.
     * 26.1 is the one version where a unit can be built, spawned and still not
     * reach the screen, and these three split the failure open:
     * held = units the group owns (0 => never reached the engine),
     * inView = units the frustum kept (0 => culled),
     * quads = geometry handed to the collector (non-zero and still invisible
     * => the pipeline, not the layer).
     */
    public static int lastHeld = 0;
    public static int lastInView = 0;
    public static int lastSubmitted = 0;

    /**
     * Round 43, and the reason these exist: the maintainer reports "始终只有一朵花
     * 且只有一块雪" while the group is submitting a dozen quads a frame. Two very
     * different failures look like that from outside -
     * <ul>
     * <li>every unit sitting in the SAME block (blocks=1) => placement: the
     *     anchors collapsed, and a dozen stacked quads do read as one blob;</li>
     * <li>units spread over several blocks (blocks>1) and still one visible
     *     => rendering: the geometry is going out and not coming back.</li>
     * </ul>
     * {@code cells} is the same count at quarter-block resolution, so "one block
     * but four distinct spots" is distinguishable from "one block, one spot".
     */
    public static int lastBlocks = 0;
    public static int lastCells = 0;
    public static float lastSpread = 0F;

    /** The above as one log token. */
    public static String renderLine() {
        return "held=" + lastHeld + " inView=" + lastInView + " quads=" + lastSubmitted
                + " blocks=" + lastBlocks + " cells=" + lastCells
                + " spread=" + String.format("%.2f", lastSpread);
    }

    /** Packs a rounded position into a long so a HashSet can count distinct spots. */
    private static long cell(final double x, final double y, final double z, final double scale) {
        final long a = Math.round(x * scale) + (1L << 20);
        final long b = Math.round(y * scale) + (1L << 20);
        final long c = Math.round(z * scale) + (1L << 20);
        return (a << 42) | (b << 21) | c;
    }

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
        int inView = 0;
        final java.util.HashSet<Long> blocks = new java.util.HashSet<>();
        final java.util.HashSet<Long> cells = new java.util.HashSet<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (final FoliageSnowParticle particle : this.particles) {
            blocks.add(cell(particle.posX(), particle.posY(), particle.posZ(), 1.0D));
            cells.add(cell(particle.posX(), particle.posY(), particle.posZ(), 4.0D));
            if (particle.posX() < minX) minX = (float) particle.posX();
            if (particle.posY() < minY) minY = (float) particle.posY();
            if (particle.posZ() < minZ) minZ = (float) particle.posZ();
            if (particle.posX() > maxX) maxX = (float) particle.posX();
            if (particle.posY() > maxY) maxY = (float) particle.posY();
            if (particle.posZ() > maxZ) maxZ = (float) particle.posZ();
            if (particle.inView(frustum)) {
                inView++;
                particle.extractCorners(this.state, camera, partialTickTime);
            }
        }
        lastHeld = this.particles.size();
        lastInView = inView;
        lastBlocks = blocks.size();
        lastCells = cells.size();
        lastSpread = lastHeld == 0 ? 0F
                : (float) Math.sqrt((maxX - minX) * (maxX - minX)
                        + (maxY - minY) * (maxY - minY)
                        + (maxZ - minZ) * (maxZ - minZ));
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
            lastSubmitted = this.count;
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
                        // ROUND 47 ROOT CAUSE: the corners are stored PER QUAD (the sink copies each
                        // particle's 12 floats to this.count * FLOATS), so they must be read at that
                        // offset. Without it every quad in the group was emitted at quad 0's position:
                        // a hundred units drew as ONE snow blob, and because quad 0 changes from frame
                        // to frame the blob jumped around faster than an enderman. `f` was already
                        // computed one line above and never used - the offset was simply dropped when
                        // the sink moved from one shared array to per-quad storage.
                        buffer.addVertex(pose, c[f + i * 3], c[f + i * 3 + 1], c[f + i * 3 + 2])
                                .setUv(i == 0 || i == 3 ? u0 : u1, i == 0 || i == 1 ? v0 : v1)
                                .setColor(argb)
                                .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
                    }
                }
            }
        }
    }
}
