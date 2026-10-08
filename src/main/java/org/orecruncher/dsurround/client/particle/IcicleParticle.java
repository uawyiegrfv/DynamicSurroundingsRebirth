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
     * v2 re-scale: the grand tier is ONE block. World width is decoupled from
     * height and clamped to 0.06..0.15 (see the provider). */
    public static final float[] TIER_HEIGHTS = { 0.14F, 0.22F, 0.34F, 0.50F, 1.00F };

    /**
     * Round 12 body builds: slim / mid / stout. The width ceiling per build - the old
     * single ceiling was 0.15 for every icicle.
     *
     * <p>The crossed quads lie on the block DIAGONAL, so the room a quad has is the
     * diagonal, sqrt(2) - which is where that ceiling comes from in principle. Nobody
     * needs a block-wide icicle: 0.30 is a fat one, and it is the widest whose crossing
     * still fits inside the block once the spot is pulled in (see {@code icicleSpot}).</p>
     */
    public static final float[] WIDTH_MAX = { 0.15F, 0.23F, 0.30F };
    /** Width multiplier per build, on top of the shape's own proportion. */
    public static final float[] WIDTH_GAIN = { 1.0F, 1.5F, 2.0F };
    /** Length rides with the build so a stout icicle stays a spike and not a lump. */
    public static final float[] LENGTH_GAIN = { 1.0F, 1.08F, 1.18F };
    /** Fraction of full size at first appearance. */
    private static final float GROWTH_START = 0.25F;
    /** Ticks from melt start to fully gone, if no drop roll succeeds first.
     *  The maintainer's ruling: the melt has to read slower than the growth,
     *  so 20s of growing gets three minutes of melting (was two). */
    public static final int MELT_TICKS = 3600;
    /** cos/sin of 45 degrees - the block-diagonal orientation of the cross. */
    private static final float DIAGONAL = 0.7071068F;

    private final float fullSize;
    /** Quad half-width / half-height: the width decoupling (1.0 = square). */
    private final float widthScale;
    /** Y of the root - the roof underside the icicle grows from. Fixed for life. */
    private final double rootY;
    /** Ticks to wait before growth starts (spread from the position hash). */
    /** The size tier this icicle grew to - scales the shatter burst. */
    private final int tier;
    private final int growthDelay;
    private boolean falling = false;
    /** Melt phase (after the snow stops): the icicle shrinks back toward its
     * root, and the handler rolls for drops and drips while it does. */
    private boolean melting = false;
    private int meltAge = 0;
    /** Growth clock, frozen while melting (BUG-6) and resumed on stopMelting()
     *  (BUG-5) - see {@link #tick}. */
    private int growthClock = 0;
    /**
     * Round 11: how far this icicle has grown, in ticks. Exposed so the handler
     * can carry it across a recycle - an icicle that goes out of range is
     * discarded, and without this it restarted from nothing (four hundred ticks
     * of growth replayed) every time you walked away and came back.
     */
    public int growthClock() {
        return this.growthClock;
    }

    /** Continues from a clock the handler remembered, instead of from zero. */
    public void resumeGrowthAt(final int ticks) {
        this.growthClock = Math.max(0, ticks);
    }

    /** How much of its length the melt has taken so far. It survives a melt
     *  CANCELLED by fresh snow, so regrowth starts from the size the icicle
     *  actually is instead of snapping back to full. */
    private float meltFactor = 1.0F;

    IcicleParticle(final ClientLevel level, final double x, final double rootY, final double z,
                   final float fullQuadSize, final float widthScale, final int tier,
                   final int growthDelay, final TextureAtlasSprite sprite) {
        // fullQuadSize arrives already converted from the tier height and the
        // shape's texture rows; widthScale holds the shape's world width
        // inside the 0.06..0.15 band; growthDelay rides in through the vy
        // spawn slot (the same trick the drip uses for its hang/fall ticks) -
        // all decided ONCE by the handler, the delay from the position hash,
        // the size from the precipitation-event curve.
        super(level, x, rootY, z);
        this.setSprite(sprite);
        this.fullSize = fullQuadSize;
        this.widthScale = widthScale;
        this.tier = tier;
        this.rootY = rootY;
        this.growthDelay = growthDelay;
        // The position is the tip; it starts hanging a starter-length below the root.
        this.quadSize = this.fullSize * GROWTH_START;
        this.y = rootY - 2.0D * this.quadSize;
        this.yo = this.y;
        // 位置是「尖端」，碰撞箱必须跟着一起走。悬挂期从不调 move()，
        // 于是箱子自构造起就停在 rootY；掉落首帧 move() → 原版
        // setLocationFromBoundingbox() 会把 y 重设成箱底，
        // 尖端就被一把拽回根部 —— 那一下就是"先被提上去"。
        // 每次改位置都用 setPos 同步箱子，掉落才不会跳。
        this.setPos(x, this.y, z);
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
        // Re-snap the collision box to the current tip: it went stale at the
        // spawn position (the attached phase walks the position field without
        // moving the box), and the first fall move resolved its collision
        // against that old box.
        this.setSize(0.1F, 0.1F);
    }

    /** Snow's over: melt - shrink back toward the root until dropped or gone. */
    public void startMelting() {
        if (this.melting)
            return;
        this.melting = true;
        this.meltAge = 0;
    }

    /** Snow again: stop melting (BUG-5). The length already lost stays lost -
     *  {@link #meltFactor} is retained, so the icicle carries on growing from
     *  its CURRENT size instead of jumping back to its old one. */
    public void stopMelting() {
        if (!this.melting)
            return;
        this.melting = false;
        this.meltAge = 0;
    }

    public boolean isMelting() {
        return this.melting;
    }

    /** Gone without a shatter - the player walked away from it. */
    public void discard() {
        this.remove();
    }

    public boolean isDead() {
        return this.removed;
    }

    /** Live tip position - where melt drips form (the handler reads these). */
    public double tipX() {
        return this.x;
    }

    public double tipY() {
        return this.y;
    }

    public double tipZ() {
        return this.z;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;

        if (this.falling) {
            // Ballistic on purpose, no drag - but NOT because anything predicts
            // the landing any more: the shatter sound plays from shatter() on
            // the tick the tip actually breaks, so there is no analytic clock
            // left to desync. Gravity is the vanilla particle's 0.04/tick
            // (0.06 is the DRIP's own ballistic constant - that layer really
            // does solve its own fall time, the icicle never shared it).
            this.yd -= 0.04D;
            this.move(this.xd, this.yd, this.zd);
            if (this.onGround || this.age >= this.lifetime) {
                this.shatter();
            }
            return;
        }

        // Growing and/or melting. Length rides quadSize; the tip (this
        // position) walks down while growing and back UP while melting - the
        // root stays pinned to the roof edge either way.
        // BUG-6: the growth curve used to keep running through the melt, and
        // during the first ~20s it climbs faster than the melt eats away, so an
        // icicle that started melting young visibly GREW while melting. The
        // clock freezes for the melt instead; leaving it frozen after a resume
        // is also what keeps the length continuous (BUG-5).
        if (!this.melting)
            this.growthClock++;
        final float progress = Mth.clamp((this.growthClock - this.growthDelay) / (float) GROWTH_TICKS, 0.0F, 1.0F);
        final float eased = progress * progress * (3.0F - 2.0F * progress);
        float length = this.fullSize * (GROWTH_START + (1.0F - GROWTH_START) * eased);
        if (this.melting) {
            this.meltAge++;
            if (this.meltAge >= MELT_TICKS) {
                // Melted away entirely - no shatter, no sound, just gone.
                this.remove();
                return;
            }
            this.meltFactor = 1.0F - this.meltAge / (float) MELT_TICKS;
        }
        length *= this.meltFactor;
        this.quadSize = length;
        this.y = this.rootY - 2.0D * this.quadSize;
        // 同上：改了位置就要同步碰撞箱，否则掉落首帧会被原版拽回旧箱底。
        this.setPos(this.x, this.y, this.z);
    }

    /** Set once by {@code IcicleHandler}: plays the ice crack the instant this
     *  icicle breaks. See BUG-1/2 in {@code _icicle_review_2.md} - why listens
     *  here rather than scheduling a landing tick ahead of time. */
    @FunctionalInterface
    public interface ShatterListener {
        void onShatter(double x, double y, double z, int tier);
    }

    private static volatile ShatterListener shatterListener;

    public static void setShatterListener(final ShatterListener listener) {
        shatterListener = listener;
    }

    /**
     * The shatter, scaled by the size tier: a grand icicle throws twice as
     * many, visibly larger chunks with a wider spread; a nub pops into a
     * handful of chips. The crack plays on THIS tick, straight from here -
     * see {@link #setShatterListener}.
     */
    private void shatter() {
        final int count = 4 + this.tier * 2;
        final ShatterListener listener = shatterListener;
        if (listener != null)
            listener.onShatter(this.x, this.y, this.z, this.tier);
        // 散开收紧到约原来的六成：原来碎块会飞出老远，看着像爆炸不是碎冰。
        final double spread = 0.010D + 0.006D * this.tier;
        for (int i = 0; i < count; i++) {
            // Downward-biased on purpose: the live video showed the burst
            // reading as an upward-floating puff when the chunks spawned
            // rising. Ice chips drop.
            this.level.addParticle(ModParticles.ICICLE_LAND,
                    this.x, this.y + 0.05D, this.z,
                    (this.random.nextFloat() - 0.5D) * 2.0D * spread,
                    -0.04D + this.random.nextFloat() * 0.05D,
                    (this.random.nextFloat() - 0.5D) * 2.0D * spread);
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
                buffer.addVertex(px + lx[i] * DIAGONAL * size * widthScale, yBase + ly[i] * size, pz - lx[i] * DIAGONAL * size * widthScale)
                        .setUv(lu[i], lv[i])
                        .setColor(this.rCol, this.gCol, this.bCol, this.alpha)
                        .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF);
            }
            // plane B: the perpendicular diagonal - face toward the +X/-Z diagonal (order) or its opposite
            for (final int i : seq) {
                buffer.addVertex(px - lx[i] * DIAGONAL * size * widthScale, yBase + ly[i] * size, pz - lx[i] * DIAGONAL * size * widthScale)
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
    public record Provider(int rows, int shapeW, float heightFactor, ResourceLocation spriteId) implements net.minecraft.client.particle.ParticleProvider<SimpleParticleType> {

        @Override
        public net.minecraft.client.particle.Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                                                     final double x, final double y, final double z,
                                                                     final double vx, final double vy, final double vz) {
            final int tier = Mth.clamp((int) Math.round(vx), 0, TIER_HEIGHTS.length - 1);
            // Round 12 build: 0 slim, 1 mid, 2 stout - carried in the vz slot, which
            // used to be a constant zero. It rides the same stable hash as the shape, so
            // an eave keeps its character: the same rim grows the same bodies every time.
            final int build = Mth.clamp((int) Math.round(vz), 0, WIDTH_MAX.length - 1);
            final float height = TIER_HEIGHTS[tier] * this.heightFactor * LENGTH_GAIN[build];
            final float size = height * 8F / this.rows;
            // World width of the shape = quad width x (shapeW/16). The old ceiling was
            // 0.15 for everything, which is why every icicle read as a needle; a stout
            // one may go to twice that PROVIDED its hanging spot is pulled in far enough
            // to keep the crossed quads inside the block - see IcicleHandler.icicleSpot.
            // The quad is non-square: widthScale = shape width / quad height.
            final float worldWidth = Mth.clamp(height * 0.6F * this.shapeW / 16F * WIDTH_GAIN[build],
                    0.06F, WIDTH_MAX[build]);
            final float widthScale = worldWidth * 16F / this.shapeW / (2F * size);
            return new IcicleParticle(level, x, y, z, size, widthScale, tier,
                    (int) Math.max(0, Math.min(199, Math.round(vy))),
                    ParticleUtils.getSprite(this.spriteId));
        }
    }
}
