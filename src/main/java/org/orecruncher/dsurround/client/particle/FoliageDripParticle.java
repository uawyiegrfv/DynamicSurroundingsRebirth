package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.LevelRenderer;
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
    /** The eave drop's quad size - package-visible so the snow unit can
     *  convert its own wanted size into the sizeScale the constructor takes. */
    static final float VANILLA_DRIP_QUAD_SIZE = 0.2F;

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
    /**
     * Round 12: every bead gets its own pace. They all used to run at the same
     * ceiling, so a queue of them sliding down the same blade kept its spacing
     * forever - "互相之间距离都不咋变". Now one outruns the one ahead of it.
     * This is a CONSTANT per bead, not a wobble: the wobble is the grip below,
     * and an individual pace is what makes them differ from each other.
     */
    private static final float PACE_SPREAD = 0.35F;
    /** ...and a floor, so a bead that meets a flat stretch still finishes. */
    private static final float SLIDE_MIN_FRACTION = 0.15F;
    /**
     * Where the slide hands over to the fade - see the SLIDE branch.
     *
     * <p>Round 44: 1.00, was 0.92. The 0.92 was never about when the fade
     * should start; it was the arithmetic that let the fade's OWN sink
     * (0.06..0.11 of a block, see {@link #beginFade}) land the quad exactly ON
     * the path's last waypoint instead of past it. Past it was the block below,
     * and a particle inside a solid block reads that block's light - zero - so
     * it rendered BLACK for as long as it lasted. That is the same trap the
     * path baker's ground clearance was shaped around, and it is cured at the
     * source now: the light override above questions a totally black reading
     * and takes the light from up to three blocks higher, so a bead down at
     * the base of its blade is lit like the blade. With that reason gone the
     * hand-off no longer has to run ahead of the geometry - the bead travels
     * to the last waypoint, which is the bottom of the plant, and only then
     * begins to go.</p>
     */
    private static final float SLIDE_FADE_HANDOFF = 1.00F;
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
        STATIC, SLIDE, FALL, FADE, EVAPORATE
    }

    /**
     * How far a fading unit sinks per tick, in blocks - see {@link #beginFade}.
     * A whole fade window covers about 0.06 of a block, one plant pixel: the
     * unit settles into the leaf rather than hanging in the air and blinking
     * out.
     */
    private static final float FADE_SINK_PER_TICK = 0.06F / 60F;

    private Phase phase = Phase.STATIC;
    /** &gt;0: a rain dropper that falls on its own at this age. ≤0: handler-driven (dew, sliders). */
    private final int restTicks;
    /** Dew drops plip; rain drops are silent by ruling. */
    private boolean soundOnLand = false;
    /**
     * Round 44: the block this particle is attached to, and the block that was
     * standing there when it attached. A particle that has lost its plant has
     * to let go, and only the particle can promise that for all of them.
     *
     * <p>The handlers do check the units they still hold - snow and dew every
     * 2 ticks, the rain layer's residuals once a second - but a unit handed to
     * an exit is deliberately OFF those lists: a snow unit trimmed away by the
     * depth quota, a plant evicted from a full roster, a rain slider whose spot
     * has expired out of the pool. Those particles keep riding a blade that is
     * no longer there, which is the maintainer's report verbatim: "破坏植物后
     * 粒子留在原地继续动画，应该立即掉落".</p>
     */
    private net.minecraft.core.BlockPos anchorPos = null;
    private net.minecraft.world.level.block.Block anchorBlock = null;
    /**
     * The anchor is read on a stagger - every 4th tick for any one particle,
     * phase-shifted at construction so a scan batch does not all read the same
     * chunk section on the same tick. Four ticks is 0.2s, under the time a
     * sliding bead covers a fifth of a pixel.
     */
    private static final int ANCHOR_CHECK_MASK = 3;
    private final int anchorPhase = this.random.nextInt(ANCHOR_CHECK_MASK + 1);
    /** The plant plane basis, WORLD axes - set by the handler. */
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
    /** This bead's own pace - see PACE_SPREAD. Rolled once, at construction. */
    private float pace = 1.0F;
    /** How far the bead is leaning off upright, radians - see {@link #updateLean}. */
    private float slideLean = 0F;
    /** Per-slide grip knots: where the blade holds the bead, where it runs. */
    private float[] grip = null;
    private boolean endWithFall = false;
    /**
     * &gt;0: when the slide finishes the unit FADES over this many ticks instead
     * of falling off the tip or shrinking where it stopped. Round 7: "the
     * disappear animation is hard to watch, shrinking is not good - let it
     * slide a good distance and then just fade out".
     */
    private int slideFade = 0;
    /** Fade window - the phase that does NOT shrink. See {@link #beginFade}. */
    private int fadeStart = -1;
    private int fadeTicks = 1;
    private int evapStart = -1;
    private int evapTicks = 100;
    /**
     * What scatters when this unit hits the ground in the FALL phase, and how
     * many pieces of it. Both left at 0/null and nothing happens - the eave
     * drop keeps its vanilla splash. The snow layer fills them in: a clump
     * that lands bursts into crumbs.
     */
    protected net.minecraft.core.particles.ParticleOptions breakParticle = null;
    protected int breakCount = 0;
    protected TextureAtlasSprite fallSprite;
    /**
     * The fall's own physics. Defaults are the vanilla falling drip's, read
     * off the eave layer so the two cannot drift; snow overrides all three in
     * FoliageSnowParticle (round 9: "掉落速度能慢些就好了，这样看起来感觉轻盈蓬松"
     * - a snowflake does not fall like a drop of water).
     * <p>{@code fallDrift} is a nudge sideways at the moment it lets go, in
     * blocks per tick, in a random compass direction: without it a slow fall
     * reads as a weightless thing sliding down a wire.</p>
     */
    protected double fallGravity = WaterDripParticle.VANILLA_GRAVITY;
    protected double fallDrag = 0.98;
    protected double fallDrift = 0.0;
    /** How fast it is already moving the instant it lets go (blocks per tick). */
    protected double fallKick = 0.02;
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
        // Round 12: the variance used to be 0.5..1.0, which put a bead at 0.10..0.20 of a
        // block - smaller than the smallest snow unit (0.18) and easy to miss on a leaf.
        // 0.75..1.35 keeps the spread (a row of identical beads is the plastic look) and
        // lifts the mean by 40%. Only callers that pass 0 get this roll; the snow layer
        // passes an explicit scale and keeps variance 1.0.
        final float variance = sizeScale > 0.0D ? 1.0F : (this.random.nextFloat() * 0.6F + 0.75F);
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

    /**
     * Round 44: tells the particle which block it rides and what stood there
     * when it attached. Every spawn site calls it - the anchor check in
     * {@link #tick()} is the only thing that covers the units no handler is
     * holding any more.
     */
    public void setAnchor(final net.minecraft.core.BlockPos pos,
                          final net.minecraft.world.level.block.Block block) {
        this.anchorPos = pos == null ? null : pos.immutable();
        this.anchorBlock = block;
    }

    /** True when the plant this particle was attached to is no longer there. */
    private boolean anchorGone() {
        if (this.anchorPos == null || this.anchorBlock == null || this.level == null)
            return false;
        // An unloaded anchor is not evidence: getBlockState answers AIR for a
        // chunk that is not there, and a bead would let go the moment its
        // chunk left the view. Same guard the icicle layer reads the world
        // through (WorldUtils).
        if (!this.level.isLoaded(this.anchorPos))
            return false;
        return this.level.getBlockState(this.anchorPos).getBlock() != this.anchorBlock;
    }

    /** Dew drops plip when they land; rain drops never call this. */
    public void setSoundOnLand() {
        this.soundOnLand = true;
    }

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
        return this.installPath(raw, maxSpeed, endWithFall, GRIP_SPREAD);
    }

    /**
     * Straight down and nothing else - the melting snow unit's exit, round 8:
     * "下滑动画就尽量不要转弯了，直接竖直下落就行了". The old exit rode the
     * plane's baked slide path, and that path follows the leaf's OUTLINE:
     * measured offline over the real sprites it averages 80 degrees of turn
     * and 0.16 lateral drift per unit of fall, which is the swing he saw.
     * This one is a two-point path from where the unit already stands
     * straight down, run through the very same physics - so the pace and the
     * ending are unchanged, and the lean comes out at zero (the direction of
     * travel IS the plane's up axis, so there is nothing to lean into).
     */
    public boolean beginSink(final float drop, final float maxSpeed, final int fadeTicks) {
        if (this.phase != Phase.STATIC || !(drop > 0F))
            return false;
        final float[] raw = new float[] {
                (float) this.x, (float) this.y, (float) this.z,
                (float) this.x, (float) this.y - drop, (float) this.z
        };
        // No grip down the chute. A bead riding a blade stutters because the
        // blade's surface stutters; snow settling through still air has
        // nothing to catch on, and a spread here would make the slowest part
        // of the whole effect the only one that visibly speeds up and slows
        // down - offline, GRIP_SPREAD put the step/mean ratio at 1.78.
        if (!this.installPath(raw, maxSpeed, false, 0F))
            return false;
        this.slideFade = Math.max(1, fadeTicks);
        return true;
    }

    /**
     * The tail both exits share: dedupe, arc length, then arm the slide.
     * Split out in round 8 so {@link #beginSink} can reuse the pace, the
     * lean and the ending without also reusing the baked path's trimming -
     * which is exactly what made the old exit turn.
     */
    /**
     * Round 12: a slide is only ever allowed to go DOWN. Returns the path prefixed with the
     * bead's own position and reduced to the waypoints below it, or null when there is
     * nothing left under the bead - in which case the caller falls back to letting go
     * (or to staying put), which is what "no room to slide" has always meant.
     */
    private float[] clipBelow(final float[] pts) {
        final int n = pts.length / 3;
        if (n < 2)
            return null;
        final float[] out = new float[(n + 1) * 3];
        out[0] = (float) this.x;
        out[1] = (float) this.y;
        out[2] = (float) this.z;
        int m = 1;
        // Start at the waypoint nearest the bead and keep EVERYTHING after it - only the
        // waypoints above the bead are skipped, and only until the path starts dropping.
        // Filtering by height alone (the first cut) threw away the mid-path stretches that
        // run sideways or level, which is why a bead on tall grass vanished halfway down:
        // its remaining path had been clipped to nothing.
        int start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            final float dx = pts[i * 3] - (float) this.x;
            final float dy = pts[i * 3 + 1] - (float) this.y;
            final float dz = pts[i * 3 + 2] - (float) this.z;
            final float d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                start = i;
            }
        }
        final float here = (float) this.y;
        while (start < n - 1 && pts[start * 3 + 1] >= here)
            start++;
        for (int i = start; i < n; i++) {
            out[m * 3] = pts[i * 3];
            out[m * 3 + 1] = pts[i * 3 + 1];
            out[m * 3 + 2] = pts[i * 3 + 2];
            m++;
        }
        if (m < 2)
            return null;
        final float[] res = new float[m * 3];
        System.arraycopy(out, 0, res, 0, m * 3);
        return res;
    }

    private boolean installPath(final float[] raw, final float maxSpeed, final boolean endWithFall,
                                final float gripSpread) {
        // Chaikin's integer arithmetic collapses a one-pixel step into two
        // IDENTICAL waypoints, and every baked path has a few. A zero-length
        // segment poisons everything downstream: the bead spends a whole slice
        // of its progress going nowhere (the stall), the lean code has no
        // direction to read and falls back to the resting frame - which is
        // upside down relative to the last one, the 180 degree flip the
        // maintainer saw at every turn - and index-space placement gives it
        // the same share of the trip as a two-pixel segment. Drop them.
        // Round 12: a caller hands over the WHOLE baked path, which starts at the top of the
        // blade, but the bead is already partway down it. Interpolating from waypoint zero
        // teleported it back up to the leaf's crown before it started to slide - the "有时候
        // 会往上爬" in the round-12 report. A slide can only go down: start from where the bead
        // actually is and keep only what lies below it. (beginSink already passes
        // [here, here-drop], so clipping leaves it exactly as it was.)
        final float[] below = this.clipBelow(raw);
        if (below == null)
            return false;
        final float[] tmp = new float[below.length];
        int n = 0;
        for (int i = 0; i < below.length; i += 3) {
            if (n > 0) {
                final float dx = below[i] - tmp[(n - 1) * 3];
                final float dy = below[i + 1] - tmp[(n - 1) * 3 + 1];
                final float dz = below[i + 2] - tmp[(n - 1) * 3 + 2];
                if (dx * dx + dy * dy + dz * dz < 1.0e-8F)
                    continue;
            }
            tmp[n * 3] = below[i];
            tmp[n * 3 + 1] = below[i + 1];
            tmp[n * 3 + 2] = below[i + 2];
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
        this.pace = 1.0F + (this.random.nextFloat() * 2F - 1F) * PACE_SPREAD;
        this.slideVel = 0F;                  // released from rest, not launched
        this.slideLean = 0F;                 // upright, exactly as it was resting
        this.slideMax = Math.min(maxSpeed * this.pace, SLIDE_SPEED_CEILING);
        this.grip = new float[GRIP_KNOTS];
        for (int i = 0; i < GRIP_KNOTS; i++)
            this.grip[i] = 1.0F + (this.random.nextFloat() * 2F - 1F) * gripSpread;
        this.endWithFall = endWithFall;
        this.phase = Phase.SLIDE;
        return true;
    }

    /**
     * Round 12: buried beads. A bead that slides past the blade's tip and into the ground
     * takes the light of the block it is inside - and the inside of a solid block is black,
     * so the bead goes black the moment it crosses the surface. That is the same trap the
     * rain curtains fell into (see PrecipitationRenderer): only a totally black reading is
     * questioned, and only upwards, so a bead in the open is left exactly as it was.
     */
    @Override
    protected int getLightCoords(final float partialTicks) {
        final int raw = super.getLightCoords(partialTicks);
        if (raw != 0 || this.level == null)
            return raw;
        final int bx = Mth.floor(this.x);
        final int bz = Mth.floor(this.z);
        final int by = Mth.floor(this.y);
        for (int step = 1; step <= 3; step++) {
            final int up = by + step;
            if (this.level.isOutsideBuildHeight(up))
                break;
            final int lit = LevelRenderer.getLightCoords(this.level, new net.minecraft.core.BlockPos(bx, up, bz));
            if (lit != 0)
                return lit;
        }
        return raw;
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

    /**
     * Lets go: vanilla fall on the falling sprite, from this instant.
     *
     * <p>Round 44: this now takes from every phase but FALL - EVAPORATE is the
     * one that mattered. Refusing an evaporating bead was right while the only
     * caller was the dew window closing over a plant that is still standing;
     * it is wrong the moment the anchor is gone, because the bead then spends
     * the rest of its 600-tick shrink hanging in the air where the blade used
     * to be. Water on a broken blade falls.</p>
     */
    public void beginFall() {
        if (this.phase == Phase.FALL)
            return;
        this.phase = Phase.FALL;
        this.setSprite(this.fallSprite);
        this.lifetime = this.age + 200;
        this.xd = 0.0D;
        this.yd = -this.fallKick;
        this.zd = 0.0D;
        if (this.fallDrift > 0.0) {
            final double ang = this.random.nextDouble() * Math.PI * 2.0;
            this.xd = Math.cos(ang) * this.fallDrift;
            this.zd = Math.sin(ang) * this.fallDrift;
        }
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
     * Fades out where it stands over {@code ticks} - and does NOT shrink.
     * {@link #beginEvaporate} scales the quad down, which on a snow unit reads
     * as the snow being sucked into the leaf; that is the round 7 complaint
     * verbatim ("消失动画有点难绷，直接缩小不太好"). This one holds the quad at
     * its size, sinks it about a plant pixel, and takes the alpha away from
     * the first tick - so a unit that has already slid to the bottom of the
     * blade simply stops being there.
     */
    public void beginFade(final int ticks) {
        if (this.phase == Phase.FADE || this.phase == Phase.FALL)
            return;
        this.phase = Phase.FADE;
        this.fadeStart = this.age;
        this.fadeTicks = Math.max(1, ticks);
        this.lifetime = this.age + this.fadeTicks + 2;
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
    }

    /**
     * A slide that ends in a FADE rather than a fall or an in-place shrink -
     * the melting snow unit's exit. Same path, same physics; only the ending
     * differs. The fade only takes if the slide actually started.
     */
    public boolean beginSlide(final float[] pts, final float maxSpeed, final int fadeTicks) {
        final boolean started = this.beginSlide(pts, maxSpeed, false);
        if (started)
            this.slideFade = Math.max(1, fadeTicks);
        return started;
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
    }    /** True while the drop is resting on the plant (the dew lottery's target set). */
    public boolean isStatic() {
        return this.phase == Phase.STATIC;
    }

    /**
     * Bursts into {@code count} pieces of {@code options} where it stands - the
     * snow crumb's burst. Deliberately tight: the maintainer asked for
     * vanilla's snowball break but "原版的那个粒子太大，弹射范围太远太高了",
     * so the throw is a couple of hundredths of a block a tick and the lift
     * barely clears the floor.
     */
    private void scatter(final net.minecraft.core.particles.ParticleOptions options, final int count) {
        for (int i = 0; i < count; i++) {
            final double ang = this.random.nextDouble() * Math.PI * 2.0D;
            final double spd = 0.016D + this.random.nextDouble() * 0.018D;
            this.level.addParticle(options, this.x, this.y + 0.02D, this.z,
                    Math.cos(ang) * spd,
                    0.020D + this.random.nextDouble() * 0.040D,
                    Math.sin(ang) * spd);
        }
    }

    public boolean isRemoved() {
        return this.removed;
    }

    // ---- tick ---------------------------------------------------------------

    @Override
    public void tick() {
        // Round 44: the plant can be gone long before anything else notices.
        // The handlers release the units they still track; this is the backstop
        // for the ones they do not, and for a unit already handed to an exit
        // (a fade that has started, a bead evaporating where it stands).
        // Staggered: every 4th tick, per particle.
        if (this.phase != Phase.FALL
                && ((this.age + this.anchorPhase) & ANCHOR_CHECK_MASK) == 0
                && this.anchorGone())
            this.beginFall();
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
                this.setPos(this.x, this.y, this.z);
                // Round 12: hand the last stretch to the fade instead of finishing and THEN
                // fading - stopping dead before dissolving is what read as a stutter. FADE
                // keeps sinking on its own (FADE_SINK_PER_TICK), so the bead dissolves WHILE
                // it drops the last few pixels. The arithmetic lines up: a full 0.36 drop
                // leaves 0.108 below the hand-off, and the fade's 110 ticks cover 0.11.
                final float handOff = this.slideFade > 0 ? this.slideLen * SLIDE_FADE_HANDOFF : this.slideLen;
                if (d >= handOff) {
                    if (this.slideFade > 0)
                        this.beginFade(this.slideFade);
                    else if (this.endWithFall)
                        this.beginFall();
                    else
                        this.beginEvaporate(30);
                }
            }
            case FALL -> {
                // Vanilla's falling drip: gravity 0.06, drag 0.98, splash on
                // hit. 0.06 is DripParticle's own number and the eave layer
                // reads it straight off WaterDripParticle.VANILLA_GRAVITY -
                // this file used to say 0.04, which made 26.1's drops land
                // noticeably slower than the other two versions'. Read it from
                // the same place so the three cannot drift again.
                this.yd -= this.fallGravity;
                this.move(this.xd, this.yd, this.zd);
                if (this.onGround) {
                    // A snow unit that lands bursts into crumbs; everything
                    // else keeps the eave drop's vanilla splash.
                    if (this.breakParticle != null && this.breakCount > 0)
                        this.scatter(this.breakParticle, this.breakCount);
                    else
                        this.level.addParticle(ParticleTypes.SPLASH, this.x, this.y, this.z, 0.0D, 0.0D, 0.0D);
                    if (this.soundOnLand && landListener != null)
                        landListener.onLand(this.x, this.y, this.z);
                    this.remove();
                    return;
                }
                this.xd *= this.fallDrag;
                this.yd *= this.fallDrag;
                this.zd *= this.fallDrag;
                if (this.age >= this.lifetime)
                    this.remove();
            }
            case FADE -> {
                // Sink a hair while the alpha goes: about one plant pixel over
                // the whole window, so the unit settles into the leaf instead
                // of hanging in mid air and blinking out.
                this.y -= FADE_SINK_PER_TICK;
                if (this.age >= this.fadeStart + this.fadeTicks)
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
        // Round 8: "掉落时面向玩家，这个不好，建议还是原来的朝向". The
        // billboard re-aims the quad at the camera every frame - over a full
        // circle of camera bearings its normal sweeps the whole 360 degrees,
        // so a falling snowflake turned to keep facing you. A unit that has
        // a plane keeps that plane's basis while it falls: the same face it
        // showed while it was sitting on the leaf.
        boolean billboard = !this.hasPlane;

        if (this.phase == Phase.EVAPORATE) {
            final float t = (float) (this.age - this.evapStart) / (float) Math.max(1, this.evapTicks);
            size *= Math.max(0.0F, 1.0F - t);
            if (t > 0.7F)
                alpha = 1.0F - (t - 0.7F) / 0.3F;
        } else if (this.phase == Phase.FADE) {
            // NO shrink - that is the entire point of the phase. The quad keeps
            // its size and the alpha goes from the very first tick, so a unit
            // that has already slid to the bottom of the blade just stops
            // being there. (Needs the translucent sheet: on the opaque one a
            // vertex alpha is discarded - see FoliageSnowParticle.)
            final float t = (float) (this.age - this.fadeStart) / (float) Math.max(1, this.fadeTicks);
            alpha = 1.0F - Mth.clamp(t, 0F, 1F);
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
            fillCorners(corners, cx, cy, cz, au, av, aw, bu, bv, bw, hu, hv);
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
