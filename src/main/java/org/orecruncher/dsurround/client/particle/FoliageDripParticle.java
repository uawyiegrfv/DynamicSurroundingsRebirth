package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * A droplet attached to a plant surface - the rain-foliage drop and the dew
 * bead share this one type ({@code FOLIAGE_DROP}); the handler that spawns it
 * decides the behavior through the setters.
 *
 * <p>Two renderers, switched by phase (maintainer ruling, fifth round): while
 * the droplet RESTS or SLIDES it is a FIXED quad pinned to the plant's own
 * plane - like an icicle, it does not turn with the view or the position -
 * and the moment it FALLS it becomes vanilla's cylindrical billboard with the
 * drip_fall sprite, because a drop flying through air is what billboards are
 * for. The plane basis is handed in by the handler (it has the profile):
 * the "up" of the quad is the WORLD UP projected into the plane, so the
 * highlight always reads as up; the horizontal axis is cross(normal, up) -
 * which is why the dew texture must be mirror-symmetric, the two cross
 * planes of one plant give opposite horizontal axes (混元 review I).</p>
 *
 * <p>The plane basis degenerates on a HORIZONTAL plane (lily pad tops -
 * 混元 review A): projecting world up onto it gives the zero vector, so when
 * {@code |normal . up| > 0.99} the basis is built from the world-north
 * projection instead. A horizontal face has no natural "up"; a bead lying on
 * it does not care about its orientation.</p>
 *
 * <p>Fates (sixth round): the RAIN layer decides at spawn - a slider slides
 * the pre-baked path (in-plane, so the drop visibly rides the blade) and
 * vanishes at the ground; a dropper rests briefly and falls. The DEW layer
 * keeps drops static and rolls a lottery every 200 ticks. Slides end in a
 * quick evaporate; the dew drop's landing sound is a CALLBACK from
 * {@link #tick()}'s ground hit (the icicle layer's live-report lesson: the
 * particle announces the moment, nothing has to predict it) - rain drops
 * simply never enable it.</p>
 *
 * <p>This is a TextureSheetParticle sibling of {@link WaterDripParticle},
 * not a subclass: the parent's age/hang fields are private and its tick is
 * written against them, and the shared file must not be touched while another
 * workstream is in flight - so the ~40 lines of billboard render and fall
 * physics are copied here instead.</p>
 */
public class FoliageDripParticle extends TextureSheetParticle {

    /** The dew layer's landing plip, played by the handler (with its own throttle). */
    public interface LandListener {
        void onLand(double x, double y, double z);
    }

    private static LandListener landListener = null;

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
    static final ResourceLocation DEW_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "drip_hang");
    static final ResourceLocation FALL_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "drip_fall");

    /**
     * The size vanilla's hanging drip renders at (WaterDripParticle's
     * quadSize base: {@code VANILLA_QUAD_SIZE * 2 * WIDTH_SCALE}).
     */
    private static final float VANILLA_DRIP_QUAD_SIZE = 0.2F;

    private enum Phase {
        STATIC, SLIDE, FALL, EVAPORATE
    }

    private Phase phase = Phase.STATIC;
    /** &gt;0: a rain dropper that falls on its own at this age. ≤0: handler-driven (dew, sliders). */
    private final int restTicks;
    /** Dew drops plip; rain drops are silent by ruling. */
    private boolean soundOnLand = false;
    /** The plant plane, in WORLD-relative offsets - set by the handler. */
    private float nx, ny, nz, ux, uy, uz, vx, vy, vz;
    private boolean hasPlane = false;
    /** Slide path, WORLD coordinates, xyz triplets. */
    private float[] pathPts = null;
    private int pathLen = 0;
    private int slideTicks = 0;
    private int slideStart = -1;
    private boolean endWithFall = false;
    /** Evaporate window. */
    private int evapStart = -1;
    private int evapTicks = 100;
    private final TextureAtlasSprite fallSprite;
    private final float baseSize;

    FoliageDripParticle(final ClientLevel level, final double x, final double y, final double z,
                        final int restTicks, final double sizeScale, final TextureAtlasSprite sprite) {
        super(level, x, y, z);
        this.setSprite(sprite);
        this.restTicks = restTicks;
        this.hasPhysics = true;
        this.gravity = 0.0F;
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
    }

    // ---- handler-driven behavior -------------------------------------------

    /** Pins the plant plane (unit normal + two unit tangents, world axes). */
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

    /**
     * Slides the pre-baked path. {@code pts} is world-space xyz triplets;
     * {@code endWithFall} says the path ended in mid-air (double-plant upper
     * halves) and the drop should let go instead of evaporating.
     */
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

    /** Lets go: vanilla fall on the falling sprite, from this instant. */
    public void beginFall() {
        if (this.phase == Phase.FALL || this.phase == Phase.EVAPORATE)
            return;
        this.phase = Phase.FALL;
        this.setSprite(this.fallSprite);
        this.lifetime = this.age + 200;
        this.xd = 0.0F;
        this.yd = -0.02F;
        this.zd = 0.0F;
    }

    /** Shrinks out over {@code ticks} (rain sliders: quick; dew window end: slow). */
    public void beginEvaporate(final int ticks) {
        if (this.phase == Phase.EVAPORATE || this.phase == Phase.FALL)
            return;
        this.phase = Phase.EVAPORATE;
        this.evapStart = this.age;
        this.evapTicks = Math.max(1, ticks);
        this.lifetime = this.age + this.evapTicks + 2;
    }

    /** True while the drop is resting on the plant (the dew lottery's target set). */
    public boolean isStatic() {
        return this.phase == Phase.STATIC;
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

    /** 1.20.1's Particle exposes {@code removed} as a field; this is the predicate form. */
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
                // Rain droppers fall on their own clock; dew waits for the lottery.
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
                if (t >= 1.0F) {
                    if (this.endWithFall)
                        this.beginFall();
                    else
                        this.beginEvaporate(30);
                }
            }
            case FALL -> {
                // Vanilla's falling drip: gravity 0.06, drag 0.98, splash on hit.
                this.yd -= 0.06F;
                this.move(this.xd, this.yd, this.zd);
                if (this.onGround) {
                    this.level.addParticle(ParticleTypes.SPLASH, this.x, this.y, this.z, 0.0D, 0.0D, 0.0D);
                    if (this.soundOnLand && landListener != null)
                        landListener.onLand(this.x, this.y, this.z);
                    this.remove();
                    return;
                }
                this.xd *= 0.98F;
                this.yd *= 0.98F;
                this.zd *= 0.98F;
                if (this.age >= this.lifetime)
                    this.remove();
            }
            case EVAPORATE -> {
                if (this.age >= this.evapStart + this.evapTicks)
                    this.remove();
            }
        }
    }

    // ---- render -------------------------------------------------------------

    @Override
    public void render(final VertexConsumer buffer, final Camera camera, final float partialTicks) {
        if (this.phase == Phase.FALL || !this.hasPlane) {
            this.renderBillboard(buffer, camera, partialTicks);
            return;
        }

        final Vec3 eye = camera.getPosition();
        final float cx = (float) (Mth.lerp(partialTicks, this.xo, this.x) - eye.x());
        final float cy = (float) (Mth.lerp(partialTicks, this.yo, this.y) - eye.y());
        final float cz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - eye.z());

        float size = this.baseSize;
        float alpha = 1.0F;
        if (this.phase == Phase.STATIC) {
            // The bead breathes - a barely-there swell that says "liquid".
            size *= 1.0F + 0.05F * Mth.sin(this.age * 0.16F);
        } else if (this.phase == Phase.EVAPORATE) {
            final float t = (float) (this.age - this.evapStart) / (float) Math.max(1, this.evapTicks);
            size *= Math.max(0.0F, 1.0F - t);
            if (t > 0.7F)
                alpha = 1.0F - (t - 0.7F) / 0.3F;
        }

        // Slide stretch: elongate along the motion's dominant in-plane axis
        // (the path lies IN this plane, so the drop visibly rides the blade).
        float su = 1.0F, sv = 1.0F;
        if (this.phase == Phase.SLIDE && this.pathLen >= 6) {
            final int segs = this.pathLen / 3 - 1;
            final float t = this.slideProgress();
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
        final int light = this.getLightColor(partialTicks);
        final float u0 = this.getU0();
        final float u1 = this.getU1();
        final float v0 = this.getV0();
        final float v1 = this.getV1();
        final float[] la = {-1F, -1F, 1F, 1F};
        final float[] lb = {-1F, 1F, 1F, -1F};
        final float[] lu = {u1, u1, u0, u0};
        final float[] lv = {v1, v0, v0, v1};
        final float r = Mth.clamp(this.rCol, 0F, 1F);
        final float g = Mth.clamp(this.gCol, 0F, 1F);
        final float b = Mth.clamp(this.bCol, 0F, 1F);
        final float a = Mth.clamp(this.alpha * alpha, 0F, 1F);

        for (final int[] seq : new int[][] {{0, 1, 2, 3}, {3, 2, 1, 0}}) {
            for (final int i : seq) {
                final float pa = la[i] * hu, pb = lb[i] * hv;
                buffer.vertex(cx + this.ux * pa + this.vx * pb,
                                cy + this.uy * pa + this.vy * pb,
                                cz + this.uz * pa + this.vz * pb)
                        .uv(lu[i], lv[i])
                        .color(r, g, b, a)
                        .uv2(light)
                        .endVertex();
            }
        }
    }

    /**
     * The falling drop - copied from {@link WaterDripParticle#render}: the
     * cylindrical billboard (rotateY only, the drop always stands upright).
     * Copied rather than inherited because the parent's fields are private
     * and the shared file stays untouched.
     */
    private void renderBillboard(final VertexConsumer buffer, final Camera camera, final float partialTicks) {
        final Vector3f[] corners = new Vector3f[] {
                new Vector3f(-1.0F, -1.0F, 0.0F),
                new Vector3f(-1.0F, 1.0F, 0.0F),
                new Vector3f(1.0F, 1.0F, 0.0F),
                new Vector3f(1.0F, -1.0F, 0.0F)
        };
        final Vec3 eye = camera.getPosition();
        final float px = (float) (Mth.lerp(partialTicks, this.xo, this.x) - eye.x());
        final float py = (float) (Mth.lerp(partialTicks, this.yo, this.y) - eye.y());
        final float pz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - eye.z());
        final Quaternionf rotation = new Quaternionf().rotateY((float) Math.atan2(px, pz));
        final float size = this.getQuadSize(partialTicks);
        final int light = this.getLightColor(partialTicks);
        final float u0 = this.getU0();
        final float u1 = this.getU1();
        final float v0 = this.getV0();
        final float v1 = this.getV1();
        final float[] us = {u1, u1, u0, u0};
        final float[] vs = {v1, v0, v0, v1};
        for (int i = 0; i < 4; i++) {
            final Vector3f corner = corners[i];
            corner.rotate(rotation);
            corner.mul(size);
            corner.add(px, py, pz);
            buffer.vertex((double) corner.x(), (double) corner.y(), (double) corner.z())
                    .uv(us[i], vs[i])
                    .color(this.rCol, this.gCol, this.bCol, this.alpha)
                    .uv2(light)
                    .endVertex();
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        // Translucent: the dew texture's soft 210-alpha edge is the point.
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /**
     * Spawn params: {@code restTicks} (&gt;0 = a rain dropper that self-falls;
     * 0 = handler-driven), {@code sizeScale}, unused. The handler casts the
     * result back and drives it.
     */
    public record Provider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double restTicks, final double sizeScale, final double unused) {
            return new FoliageDripParticle(level, x, y, z, (int) restTicks, sizeScale,
                    ParticleUtils.getSprite(DEW_SPRITE));
        }
    }
}
