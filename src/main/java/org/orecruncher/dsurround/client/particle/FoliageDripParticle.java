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

    static final Identifier DEW_SPRITE =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "dew_drop");
    static final Identifier FALL_SPRITE =
            Identifier.fromNamespaceAndPath("minecraft", "drip_fall");

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
        final float scale = (float) (sizeScale > 0.0D ? sizeScale : 1.0D);
        this.baseSize = 0.09F * scale;
        this.quadSize = this.baseSize;
        this.setSize(0.02F, 0.02F);
        this.fallSprite = ParticleUtils.getSprite(FALL_SPRITE);
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
                final float t = Math.min(1.0F, (float) (this.age - this.slideStart) / (float) this.slideTicks);
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

        float size = this.baseSize;
        float alpha = 1.0F;
        float su = 1.0F, sv = 1.0F;
        boolean billboard = this.phase == Phase.FALL || !this.hasPlane;

        if (this.phase == Phase.STATIC) {
            size *= 1.0F + 0.05F * Mth.sin(this.age * 0.16F);
        } else if (this.phase == Phase.EVAPORATE) {
            final float t = (float) (this.age - this.evapStart) / (float) Math.max(1, this.evapTicks);
            size *= Math.max(0.0F, 1.0F - t);
            if (t > 0.7F)
                alpha = 1.0F - (t - 0.7F) / 0.3F;
        } else if (this.phase == Phase.SLIDE && this.pathLen >= 6) {
            final int segs = this.pathLen / 3 - 1;
            final float t = Math.min(1.0F, (float) (this.age - this.slideStart) / (float) this.slideTicks);
            final int i = Math.min(segs - 1, (int) (t * segs));
            final float dx = this.pathPts[i * 3 + 3] - this.pathPts[i * 3];
            final float dy = this.pathPts[i * 3 + 4] - this.pathPts[i * 3 + 1];
            final float dz = this.pathPts[i * 3 + 5] - this.pathPts[i * 3 + 2];
            final float du = Math.abs(dx * this.ux + dy * this.uy + dz * this.uz);
            final float dv = Math.abs(dx * this.vx + dy * this.vy + dz * this.vz);
            if (du >= dv) {
                su = 1.0F + 0.35F * t;
                sv = 1.0F - 0.15F * t;
            } else {
                sv = 1.0F + 0.35F * t;
                su = 1.0F - 0.15F * t;
            }
        }

        final float hu = size * 0.5F * su;
        final float hv = size * 0.5F * sv;
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
