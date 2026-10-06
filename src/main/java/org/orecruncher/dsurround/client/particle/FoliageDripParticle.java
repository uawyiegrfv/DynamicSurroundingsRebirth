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
    /**
     * Rain-foliage drops wear the eave's blue - READ FROM IT, not copied. It
     * is the same water in the same sky; a second set of numbers here would
     * only be something to drift out of sync.
     */
    private static final float RAIN_R = WaterDripParticle.WATER_R;
    private static final float RAIN_G = WaterDripParticle.WATER_G;
    private static final float RAIN_B = WaterDripParticle.WATER_B;
    private static final float RAIN_ALPHA = 0.95F;
    /** Dew: the pale blue lifted a little further toward blue than rain's. */
    private static final float DEW_R = 0.45F;
    private static final float DEW_G = 0.65F;
    private static final float DEW_B = 1.0F;
    /** Dew is the fainter of the two - it is a film, not a drop. */
    private static final float DEW_ALPHA = 0.55F;

    /**
     * Slide physics, in BLOCKS and TICKS - the units the falling drop uses, so
     * the two can be compared directly. A drop that lets go covers half a
     * block in about four ticks and is doing roughly 0.26 blocks/tick by then;
     * a bead crawling down a blade at a hundredth of that can never be taken
     * for falling. That is the constraint the maintainer set: the slide is
     * slower than the fall, always, and it is the geometry that sets the pace -
     * not a clock.
     */
    /** Gravity's pull along the blade, blocks/tick^2. */
    private static final float SLIDE_ACCEL = 0.0012F;
    /** Hard ceiling for any sliding bead - a blade is never a free fall. */
    private static final float SLIDE_SPEED_CEILING = 0.03F;
    /** ...and a floor, so a bead that meets a flat stretch still finishes. */
    private static final float SLIDE_MIN_FRACTION = 0.15F;
    /** How hard friction drags a surging bead back to its grip-limited speed. */
    private static final float SLIDE_BRAKE = 0.0010F;
    /**
     * How fast the bead may lean into a turn, radians per tick - about six
     * degrees. The lean used to be read straight off the current segment, so a
     * corner rotated the quad in one frame; that was the "闪一下" at every turn.
     */
    private static final float LEAN_RATE = 0.10F;
    /**
     * ...and how far it may lean, about fifty degrees. A blade can tilt a drop,
     * it cannot point one back uphill; the cap is what keeps a stray segment
     * from rolling the bead right over.
     */
    private static final float MAX_LEAN = 0.87F;
    /**
     * Grip knots along the path - how much the blade holds the bead back.
     * This is the variation the maintainer asked for: the speed changes
     * DURING the slide. The old jitter multiplied the whole animation
     * instead, which just made some drops randomly faster than others.
     */
    private static final int GRIP_KNOTS = 5;
    private static final float GRIP_SPREAD = 0.45F;

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
    /** Slide state, in BLOCKS and BLOCKS PER TICK - see {@link #beginSlide}. */
    private float slideLen = 0F;
    /** Cumulative arc length per waypoint - the bead is placed by DISTANCE. */
    private float[] slideCum = null;
    private float slideDist = 0F;
    private float slideVel = 0F;
    private float slideMax = 0F;
    /** How far the bead is leaning off upright, radians - see {@link #updateLean}. */
    private float slideLean = 0F;
    /** Per-slide grip knots: where the blade holds the bead, where it runs. */
    private float[] grip = null;
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
        // Default to the rain look. Vanilla's drip texture is GREYSCALE - it
        // only ever looks like water because the particle tints it, so a drop
        // that is never told which layer it belongs to renders white. That was
        // the bug this replaced; the default is what stops it coming back.
        this.setDew(false);
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
    public boolean beginSlide(final float[] pts, final float maxSpeed, final boolean endWithFall) {
        if (this.phase != Phase.STATIC || pts == null || pts.length < 6)
            return false;
        // The path was baked from ITS OWN column top, which is not where this
        // bead sits. Pinning only the first waypoint to the bead left the
        // SECOND one wherever the chosen path happened to start: sometimes a
        // leap across half the sprite, and when the bead's own anchor sat
        // lower than that start, a climb. A bead cannot climb. Drop every
        // waypoint that is above the bead along the plane's up axis, then
        // start from the bead itself - what is left is all downhill.
        int start = 0;
        float hb = 0F;
        if (this.hasPlane) {
            hb = (float) (this.x * this.ux + this.y * this.uy + this.z * this.uz);
            while (start + 3 < pts.length
                    && pts[start] * this.ux + pts[start + 1] * this.uy
                    + pts[start + 2] * this.uz > hb)
                start += 3;
        }
        final int kept = pts.length / 3 - start / 3;
        if (kept < 1)
            return false;
        // The loop above stops at the LAST waypoint whether or not that one is
        // clear of the bead - and a path baked from a column whose top sits
        // above this anchor can be above it all the way down. Nothing below
        // means nothing to slide down; the caller decides what happens instead.
        // The up axis, not the horizontal one: (ux,uy,uz) is cross(up, normal),
        // which runs across the blade. Comparing heights along it made this
        // trim keep the wrong waypoints - one of the reasons the bead still
        // climbed. (vx,vy,vz) is the world-up projected into the plane, which
        // is what "above" means here.
        if (this.hasPlane
                && pts[start] * this.vx + pts[start + 1] * this.vy + pts[start + 2] * this.vz > hb)
            return false;
        final float[] raw = new float[(kept + 1) * 3];
        raw[0] = (float) this.x;
        raw[1] = (float) this.y;
        raw[2] = (float) this.z;
        System.arraycopy(pts, start, raw, 3, pts.length - start);
        // Chaikin's integer arithmetic collapses a one-pixel step into two
        // IDENTICAL waypoints, and every baked path has a few. A zero-length
        // segment poisons everything downstream: the bead spends a whole slice
        // of its progress going nowhere (the stall), the lean code has no
        // direction to read and falls back to the resting frame - which is
        // upside down relative to the last one, the 180 degree flip the
        // maintainer saw at every turn - and index-space placement gives it
        // the same share of the trip as a two-pixel segment. Drop them.
        final float[] tmp = new float[raw.length];
        int n = 0;
        for (int i = 0; i < raw.length; i += 3) {
            if (n > 0) {
                final float dx = raw[i] - tmp[(n - 1) * 3];
                final float dy = raw[i + 1] - tmp[(n - 1) * 3 + 1];
                final float dz = raw[i + 2] - tmp[(n - 1) * 3 + 2];
                if (dx * dx + dy * dy + dz * dz < 1.0e-8F)
                    continue;
            }
            tmp[n * 3] = raw[i];
            tmp[n * 3 + 1] = raw[i + 1];
            tmp[n * 3 + 2] = raw[i + 2];
            n++;
        }
        if (n < 2)
            return false;
        final float[] path = new float[n * 3];
        System.arraycopy(tmp, 0, path, 0, n * 3);
        // Cumulative arc length, so the bead is placed by DISTANCE and not by
        // waypoint index. Index-space placement hands every segment an equal
        // share of the trip however long it is, and Chaikin's segments differ
        // by a factor of three - that was the jump at a corner.
        final float[] cum = new float[n];
        for (int i = 1; i < n; i++) {
            final float dx = path[i * 3] - path[(i - 1) * 3];
            final float dy = path[i * 3 + 1] - path[(i - 1) * 3 + 1];
            final float dz = path[i * 3 + 2] - path[(i - 1) * 3 + 2];
            cum[i] = cum[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        final float length = cum[n - 1];
        if (length < 1.0e-4F)
            return false;
        this.pathPts = path;
        this.pathLen = path.length;
        this.slideCum = cum;
        this.slideLen = length;
        this.slideDist = 0F;
        this.slideVel = 0F;                  // released from rest, not launched
        this.slideLean = 0F;                 // upright, exactly as it was resting
        this.slideMax = Math.min(maxSpeed, SLIDE_SPEED_CEILING);
        this.grip = new float[GRIP_KNOTS];
        for (int i = 0; i < GRIP_KNOTS; i++)
            this.grip[i] = 1.0F + (this.random.nextFloat() * 2F - 1F) * GRIP_SPREAD;
        this.endWithFall = endWithFall;
        this.phase = Phase.SLIDE;
        return true;
    }

    /** The waypoint index the bead has reached at arc length {@code d}. */
    private int segmentAt(final float d) {
        final int last = this.slideCum.length - 1;
        int i = 0;
        while (i < last - 1 && this.slideCum[i + 1] <= d)
            i++;
        return i;
    }

    /**
     * Eases the bead's lean toward the way it is travelling. The bead's own
     * axis points back UPHILL, so the texture's top stays up and only the
     * bead tilts - a sliding drop leans into the blade, it does not turn over.
     */
    private void updateLean(final float dx, final float dy, final float dz, final float dl) {
        float target = 0F;
        if (dl > 1.0e-6F) {
            float bx = -dx / dl;
            float by = -dy / dl;
            float bz = -dz / dl;
            final float dn = bx * this.nx + by * this.ny + bz * this.nz;
            bx -= this.nx * dn;
            by -= this.ny * dn;
            bz -= this.nz * dn;
            final float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (bl > 1.0e-6F) {
                bx /= bl;
                by /= bl;
                bz /= bl;
                target = (float) Math.atan2(bx * this.ux + by * this.uy + bz * this.uz,
                        bx * this.vx + by * this.vy + bz * this.vz);
            }
        }
        target = Mth.clamp(target, -MAX_LEAN, MAX_LEAN);
        this.slideLean += Mth.clamp(target - this.slideLean, -LEAN_RATE, LEAN_RATE);
    }

    /** The grip at progress {@code s} 0..1 - smooth across the knots, mean ~1. */
    private float gripAt(final float s) {
        if (this.grip == null || this.grip.length < 2)
            return 1.0F;
        final float f = Mth.clamp(s, 0F, 1F) * (this.grip.length - 1);
        final int i = Math.min(this.grip.length - 2, (int) f);
        final float t = f - i;
        return Mth.lerp(t * t * (3F - 2F * t), this.grip[i], this.grip[i + 1]);
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
        return this.slideLen > 0F ? Math.min(1.0F, this.slideDist / this.slideLen) : 0F;
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
                int i = this.segmentAt(this.slideDist);
                // Gravity drives the bead, but only as hard as THIS stretch of
                // blade is steep: a near-flat stretch has almost nothing
                // pulling the bead along it, a steep one lets it run. The pace
                // is read off the geometry, not off a clock.
                float dx = this.pathPts[(i + 1) * 3] - this.pathPts[i * 3];
                float dy = this.pathPts[(i + 1) * 3 + 1] - this.pathPts[i * 3 + 1];
                float dz = this.pathPts[(i + 1) * 3 + 2] - this.pathPts[i * 3 + 2];
                final float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                final float drive = dl > 1.0e-6F ? Math.max(0.25F, -dy / dl) : 1.0F;
                this.updateLean(dx, dy, dz, dl);
                final float t = this.slideProgress();
                final float g = this.gripAt(t);
                this.slideVel += SLIDE_ACCEL * drive * g;
                final float cap = this.slideMax * g;
                if (this.slideVel > cap)
                    this.slideVel = Math.max(cap, this.slideVel - SLIDE_BRAKE);
                final float floor = this.slideMax * SLIDE_MIN_FRACTION;
                if (this.slideVel < floor)
                    this.slideVel = floor;
                this.slideDist += this.slideVel;
                final float d = Math.min(this.slideDist, this.slideLen);
                i = this.segmentAt(d);
                final float seg = this.slideCum[i + 1] - this.slideCum[i];
                final float frac = seg > 1.0e-9F ? (d - this.slideCum[i]) / seg : 0F;
                this.x = Mth.lerp(frac, this.pathPts[i * 3], this.pathPts[(i + 1) * 3]);
                this.y = Mth.lerp(frac, this.pathPts[i * 3 + 1], this.pathPts[(i + 1) * 3 + 1]);
                this.z = Mth.lerp(frac, this.pathPts[i * 3 + 2], this.pathPts[(i + 1) * 3 + 2]);
                if (d >= this.slideLen) {
                    if (this.endWithFall)
                        this.beginFall();
                    else
                        this.beginEvaporate(30);
                }
            }
            case FALL -> {
                // Vanilla's falling drip: gravity 0.06, drag 0.98, splash on
                // hit - read from the eave layer so the two cannot drift.
                this.yd -= WaterDripParticle.VANILLA_GRAVITY;
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

        // ONE size for the drop's whole life. The eave drop does the same and
        // says why: there is no stretch while it hangs - what reads as
        // "stretching" is the texture, which is drawn taller than it is wide.
        // The breathing swell and the slide stretch below both changed the
        // quad between spawn, slide and fall, and that is the "the size keeps
        // changing" the maintainer reported. Only EVAPORATE shrinks, because
        // a drop that blinks out of existence is worse than one that dries up.
        float size = this.baseSize;
        float alpha = 1.0F;
        if (this.phase == Phase.EVAPORATE) {
            final float t = (float) (this.age - this.evapStart) / (float) Math.max(1, this.evapTicks);
            size *= Math.max(0.0F, 1.0F - t);
            if (t > 0.7F)
                alpha = 1.0F - (t - 0.7F) / 0.3F;
        }

        // The quad's axes. Normally the handler's plane basis - but while
        // sliding, the bead LEANS INTO THE TURN: it takes the direction of
        // travel (projected into the plane) as its own up axis. On the old
        // fixed basis a bead running down a curved blade translated sideways
        // like a rigid square and never turned at all.
        float au = this.ux, av = this.uy, aw = this.uz;
        float bu = this.vx, bv = this.vy, bw = this.vz;
        if (this.phase == Phase.SLIDE) {
            // Lean is a ROTATION of the resting frame about the plane normal,
            // not a replacement of it, and the angle is eased a few degrees a
            // tick. The old code took the travel direction as the quad's up
            // axis outright - which not only snapped at every corner, it also
            // pointed the texture's top DOWN the blade: the drop was upside
            // down for the whole slide and flipped the moment it started.
            final float c = Mth.cos(this.slideLean);
            final float s = Mth.sin(this.slideLean);
            au = c * this.ux - s * this.vx;
            av = c * this.uy - s * this.vy;
            aw = c * this.uz - s * this.vz;
            bu = c * this.vx + s * this.ux;
            bv = c * this.vy + s * this.uy;
            bw = c * this.vz + s * this.uz;
        }

        final float hu = size * 0.5F;
        final float hv = size * 0.5F;
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
                buffer.vertex(cx + au * pa + bu * pb,
                                cy + av * pa + bv * pb,
                                cz + aw * pa + bw * pb)
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
        // Half the quad size, because these corners are -1..1 and the on-plane
        // renderer's are -1..1 scaled by half the size too: the falling drop
        // has to be EXACTLY as wide as the one that was resting on the leaf.
        // The full quadSize here made every falling drop twice the size of the
        // drop it came from.
        final float size = this.getQuadSize(partialTicks) * 0.5F;
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
