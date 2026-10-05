package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * A droplet attached to a plant surface - the 26.1 shape of
 * {@code FoliageDripParticle} (see the 1.20.1 file for the full behavior
 * rationale; only the rendering differs).
 *
 * <p>26.1 rendering: this version has no vertex-stage render() to override -
 * particles EXTRACT state - and the vanilla single-quad state can only draw a
 * square, camera-facing quad. The rest/slide/evaporate phases need a quad
 * PINNED to the plant's own plane, so they are extracted here as four
 * explicit corner positions and emitted by {@link FoliageDripParticleGroup}
 * as raw geometry (the route the icicle's cross opened). The FALL phase
 * extracts a camera-facing billboard through the same sink: 26.1 assigns a
 * particle to its group at add time, so one group must carry a drop through
 * its whole life - the billboard math (yaw-only, the drop stays upright) is
 * this class's own extract-time work.</p>
 *
 * <p>The plane basis degenerates on a HORIZONTAL plane (lily pad tops - 混元
 * review A): projecting world up onto it gives the zero vector, so when
 * {@code |normal . up| > 0.99} the basis is built from the world-north
 * projection instead.</p>
 */
public class FoliageDripParticle extends SingleQuadParticle {

    /** The dew layer's landing plip, played by the handler (with its own throttle). */
    public interface LandListener {
        void onLand(double x, double y, double z);
    }

    private static volatile LandListener landListener = null;

    public static void setLandListener(final LandListener listener) {
        landListener = listener;
    }

    /**
     * VANILLA'S OWN drip sprite, not a custom one. The hand-made dew_drop
     * texture read as a grain of sugar / a snowball at droplet scale - too
     * bright, too round, and far too detailed for a 1-pixel bead against
     * Minecraft's 16x16 blocks. The eave layer has always used
     * {@code minecraft:drip_hang} and it is the right look here too.
     */
    static final Identifier DEW_SPRITE =
            Identifier.fromNamespaceAndPath("minecraft", "drip_hang");
    static final Identifier FALL_SPRITE =
            Identifier.fromNamespaceAndPath("minecraft", "drip_fall");

    /**
     * The size vanilla's hanging drip renders at (WaterDripParticle's
     * quadSize base: {@code VANILLA_QUAD_SIZE * 2 * WIDTH_SCALE}).
     */
    private static final float VANILLA_DRIP_QUAD_SIZE = 0.2F;

    /**
     * Dew and rain no longer share the eave's colour. Both wore
     * {@link WaterDripParticle}'s blue, so against falling rain the
     * rain-foliage drops were invisible - "分不清" - and dew, which is not
     * falling water at all, read as just more rain.
     *
     * <ul>
     *   <li><b>DEW is white and half-transparent.</b> Dew is condensation
     *       sitting on a leaf, not water falling out of the sky, and a pale
     *       bead is what it looks like.</li>
     *   <li><b>RAIN is a pale blue</b> - the eave's blue lifted toward white
     *       ({@code 0.3/0.4/1.0} to {@code 0.55/0.75/1.0}), so it still says
     *       water but no longer disappears into the rain falling behind it.</li>
     * </ul>
     *
     * The eave's own drops keep their saturated blue: they hang against roofs
     * and sky, not against a wall of falling rain, so they do not have this
     * problem.
     */
    private static final float DEW_R = 1.0F;
    private static final float DEW_G = 1.0F;
    private static final float DEW_B = 1.0F;
    /** Dew is the fainter of the two - it is a film, not a drop. */
    private static final float DEW_ALPHA = 0.55F;
    private static final float RAIN_R = 0.55F;
    private static final float RAIN_G = 0.75F;
    private static final float RAIN_B = 1.0F;
    private static final float RAIN_ALPHA = 0.95F;

    private enum Phase {
        STATIC, SLIDE, FALL, EVAPORATE
    }

    private Phase phase = Phase.STATIC;
    /** &gt;0: a rain dropper that falls on its own at this age. ≤0: handler-driven (dew, sliders). */
    private final int restTicks;
    /** Dew drops plip; rain drops are silent by ruling. */
    private boolean soundOnLand = false;
    /** The plant plane basis, WORLD axes - set by the handler. */
    private float nx, ny, nz, ux, uy, uz, vx, vy, vz;
    private boolean hasPlane = false;
    /** Slide path, WORLD coordinates, xyz triplets. */
    private float[] pathPts = null;
    private int pathLen = 0;
    private int slideTicks = 0;
    private int slideStart = -1;
    private boolean endWithFall = false;
    private int evapStart = -1;
    private int evapTicks = 100;
    private final TextureAtlasSprite fallSprite;
    private final float baseSize;

    FoliageDripParticle(final ClientLevel level, final double x, final double y, final double z,
                        final int restTicks, final double sizeScale, final TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
        this.restTicks = restTicks;
        this.hasPhysics = true;
        this.friction = 1.0F;
        this.lifetime = 24000;   // managed by the phases; nothing times out silently
        // The eave drop's own size rule, copied from WaterDripParticle:
        // a sizeScale of 0 (or less) means "take vanilla's variance"
        // (0.5..1.0), a positive one pins the size exactly. The eave layer
        // passes 0 so every drop differs - the maintainer asked for the same
        // roll here, and a row of beads all cut to one size is exactly the
        // plastic look the custom texture already had.
        final float scale = (float) (sizeScale > 0.0D ? sizeScale : 1.0D);
        final float variance = sizeScale > 0.0D ? 1.0F : (this.random.nextFloat() * 0.5F + 0.5F);
        this.baseSize = VANILLA_DRIP_QUAD_SIZE * scale * variance;
        this.quadSize = this.baseSize;
        this.setSize(0.02F, 0.02F);
        this.fallSprite = ParticleUtils.getSprite(FALL_SPRITE);
        // Vanilla's drip texture is GREYSCALE - it only ever looks like water
        // because the particle tints it. Without this the drop renders white,
        // which is exactly what the maintainer saw. Tint from the eave layer's
        // own constants so both layers wear the same colour.
        // Default to the rain look. Vanilla's drip texture is GREYSCALE - it
        // only ever looks like water because the particle tints it, so a drop
        // that is never told which layer it belongs to renders white. That was
        // the bug this replaced; the default is what stops it coming back.
        this.setDew(false);
    }

    // ---- handler-driven behavior -------------------------------------------

    public void setPlane(final float nx, final float ny, final float nz,
                         final float ux, final float uy, final float uz,
                         final float vx, final float vy, final float vz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.hasPlane = true;
    }

    /** Dew drops plip when they land; rain drops never call this. */
    public void setSoundOnLand() {
        this.soundOnLand = true;
    }

    public void beginSlide(final float[] pts, final int ticks, final boolean endWithFall) {
        if (this.phase != Phase.STATIC)
            return;
        this.pathPts = pts;
        this.pathLen = pts.length;
        this.slideTicks = Math.max(1, ticks);
        this.slideStart = this.age;
        this.endWithFall = endWithFall;
        this.phase = Phase.SLIDE;
    }

    public void beginFall() {
        if (this.phase == Phase.FALL || this.phase == Phase.EVAPORATE)
            return;
        this.phase = Phase.FALL;
        this.setSprite(this.fallSprite);
        this.lifetime = this.age + 200;
        this.xd = 0.0D;
        this.yd = -0.02D;
        this.zd = 0.0D;
    }

    public void beginEvaporate(final int ticks) {
        if (this.phase == Phase.EVAPORATE || this.phase == Phase.FALL)
            return;
        this.phase = Phase.EVAPORATE;
        this.evapStart = this.age;
        this.evapTicks = Math.max(1, ticks);
        this.lifetime = this.age + this.evapTicks + 2;
    }

    /**
     * How far along the slide path the drop is, 0..1 - EASED, so the drop
     * starts slow and accelerates: a drop let go from rest covers distance as
     * t^2, and a linear ramp was half of the "grasshopper" the maintainer
     * saw. Constant speed reads as a bead dragged along a wire, no weight.
     */
    private float slideProgress() {
        final float raw = Math.min(1.0F,
                (float) (this.age - this.slideStart) / (float) Math.max(1, this.slideTicks));
        return raw * raw;
    }

    /**
     * Which layer this drop belongs to - rain and dew wear different colours
     * now (see the constants above). The handler knows which one it is making;
     * the particle just wears it.
     */
    public void setDew(final boolean dew) {
        if (dew) {
            this.setColor(DEW_R, DEW_G, DEW_B);
            this.alpha = DEW_ALPHA;
        } else {
            this.setColor(RAIN_R, RAIN_G, RAIN_B);
            this.alpha = RAIN_ALPHA;
        }
    }

    public boolean isStatic() {
        return this.phase == Phase.STATIC;
    }

    public boolean isRemoved() {
        return this.removed;
    }

    // ---- tick ---------------------------------------------------------------

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;
        switch (this.phase) {
            case STATIC -> {
                if (this.restTicks > 0 && this.age >= this.restTicks)
                    this.beginFall();
            }
            case SLIDE -> {
                final int segs = this.pathLen / 3 - 1;
                final float t = this.slideProgress();
                final float f = t * segs;
                final int i = Math.min(segs - 1, (int) f);
                final float frac = f - i;
                this.x = Mth.lerp(frac, this.pathPts[i * 3], this.pathPts[i * 3 + 3]);
                this.y = Mth.lerp(frac, this.pathPts[i * 3 + 1], this.pathPts[i * 3 + 4]);
                this.z = Mth.lerp(frac, this.pathPts[i * 3 + 2], this.pathPts[i * 3 + 5]);
                this.setPos(this.x, this.y, this.z);
                if (t >= 1.0F) {
                    if (this.endWithFall)
                        this.beginFall();
                    else
                        this.beginEvaporate(30);
                }
            }
            case FALL -> {
                this.yd -= 0.04D;
                this.move(this.xd, this.yd, this.zd);
                if (this.onGround) {
                    this.level.addParticle(ParticleTypes.SPLASH, this.x, this.y, this.z, 0.0D, 0.0D, 0.0D);
                    if (this.soundOnLand && landListener != null)
                        landListener.onLand(this.x, this.y, this.z);
                    this.remove();
                    return;
                }
                this.xd *= 0.98D;
                this.yd *= 0.98D;
                this.zd *= 0.98D;
                if (this.age >= this.lifetime)
                    this.remove();
            }
            case EVAPORATE -> {
                if (this.age >= this.evapStart + this.evapTicks)
                    this.remove();
            }
        }
    }

    // ---- extraction ---------------------------------------------------------

    /** 26.1: Particle's position fields are protected - the group asks here. */
    boolean inView(final Frustum frustum) {
        return frustum.pointInFrustum(this.x, this.y, this.z);
    }

    /**
     * Computes this frame's four corners (camera-relative) and hands them to
     * the group. Plane phases build the corners off the plane basis; the fall
     * phase builds a yaw-only billboard, copied from the 1.20.1 render.
     */
    void extractCorners(final FoliageDripParticleGroup.CornerSink sink, final Camera camera, final float partialTick) {
        final Vec3 pos = camera.position();
        final float cx = (float) (Mth.lerp(partialTick, this.xo, this.x) - pos.x());
        final float cy = (float) (Mth.lerp(partialTick, this.yo, this.y) - pos.y());
        final float cz = (float) (Mth.lerp(partialTick, this.zo, this.z) - pos.z());

        // ONE size for the drop's whole life. The eave drop does the same and
        // says why: there is no stretch while it hangs - what reads as
        // "stretching" is the texture, which is drawn taller than it is wide.
        // The breathing swell and the slide stretch both changed the quad
        // between spawn, slide and fall, and that is the "the size keeps
        // changing" the maintainer reported. Only EVAPORATE shrinks, because
        // a drop that blinks out of existence is worse than one that dries up.
        float size = this.baseSize;
        float alpha = 1.0F;
        boolean billboard = this.phase == Phase.FALL || !this.hasPlane;

        if (this.phase == Phase.EVAPORATE) {
            final float t = (float) (this.age - this.evapStart) / (float) Math.max(1, this.evapTicks);
            size *= Math.max(0.0F, 1.0F - t);
            if (t > 0.7F)
                alpha = 1.0F - (t - 0.7F) / 0.3F;
        }

        final float hu = size * 0.5F;
        final float hv = size * 0.5F;
        final float[] corners = new float[12];

        if (billboard) {
            // Yaw-only billboard: the drop always stands upright (the 1.20.1
            // render's derivation - atan2(px, pz), not the camera's pitch).
            final float yaw = (float) Math.atan2(cx, cz);
            final float sin = Mth.sin(yaw), cos = Mth.cos(yaw);
            // local (a, b) -> world: a*(cos,0,-sin), b*(0,1,0)
            final float axx = cos, axy = 0F, axz = -sin;
            final float byx = 0F, byy = 1F, byz = 0F;
            fillCorners(corners, cx, cy, cz, axx, axy, axz, byx, byy, byz, hu, hv);
        } else {
            fillCorners(corners, cx, cy, cz, this.ux, this.uy, this.uz, this.vx, this.vy, this.vz, hu, hv);
        }

        sink.add(corners, this.getU0(), this.getU1(), this.getV0(), this.getV1(),
                ARGB.colorFromFloat(Mth.clamp(this.alpha * alpha, 0F, 1F),
                        Mth.clamp(this.rCol, 0F, 1F), Mth.clamp(this.gCol, 0F, 1F), Mth.clamp(this.bCol, 0F, 1F)),
                this.getLightCoords(partialTick));
    }

    private static void fillCorners(final float[] out, final float cx, final float cy, final float cz,
                                    final float axx, final float axy, final float axz,
                                    final float byx, final float byy, final float byz,
                                    final float hu, final float hv) {
        final float[] la = {-1F, -1F, 1F, 1F};
        final float[] lb = {-1F, 1F, 1F, -1F};
        for (int i = 0; i < 4; i++) {
            final float pa = la[i] * hu, pb = lb[i] * hv;
            out[i * 3] = cx + axx * pa + byx * pb;
            out[i * 3 + 1] = cy + axy * pa + byy * pb;
            out[i * 3 + 2] = cz + axz * pa + byz * pb;
        }
    }

    /** One group for the whole life - 26.1 assigns particles to groups at add time. */
    @Override
    public ParticleRenderType getGroup() {
        return FoliageDripParticleGroup.type();
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        // The dew texture's soft edge wants the translucent pipeline; the
        // vanilla drip_fall sprite is translucent the same way.
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    /** Spawn params: {@code restTicks} (&gt;0 = self-falling dropper), {@code sizeScale}, unused. */
    public record Provider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double restTicks, final double sizeScale, final double unused,
                                       final net.minecraft.util.RandomSource random) {
            return new FoliageDripParticle(level, x, y, z, (int) restTicks, sizeScale,
                    ParticleUtils.getSprite(DEW_SPRITE));
        }
    }
}
