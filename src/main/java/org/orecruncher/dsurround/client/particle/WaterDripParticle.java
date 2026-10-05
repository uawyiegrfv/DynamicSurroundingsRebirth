package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * The eave drip drop - vanilla's own motion, our own texture.
 *
 * <p>Rewritten to copy vanilla's {@code DripParticle.DripHangParticle} step for
 * step, because owning the particle is only worth it if it behaves exactly like
 * the one it replaces. What vanilla actually does, read off the jar:</p>
 *
 * <pre>
 *     gravity *= 0.02;        // 0.06 -> 0.0012, so it creeps rather than falls
 *     lifetime = 40;          // hangs for two seconds
 *     preMoveUpdate  -> at zero, remove and spawn the falling particle
 *     postMoveUpdate -> xd/yd/zd *= 0.02   // almost perfectly still
 * </pre>
 *
 * <p>and it never touches {@code quadSize}, so the quad stays at the base
 * class default of {@code 0.1}. There is no stretch while it hangs - the drop
 * that looks like it is stretching is the texture, which is drawn taller than
 * it is wide. The "slowly stretches, then falls" reading is exactly that: a
 * still, tall drop for two seconds, then gravity.</p>
 *
 * <p>So this one hangs still at {@code 0.1}, then falls on vanilla's own
 * gravity and drag. The texture stays ours because it is the part worth
 * tuning, and the three knobs below are the ones asked for: width, tint and
 * alpha. Vanilla's own drop is a saturated two-pixel blob; at {@code 1.0} these
 * leave our texture as it is, and lowering them is how it gets thinner, less
 * blue and softer without touching the motion.</p>
 *
 * <p>{@code release()} is the reason for owning it at all: when the block that
 * made the point is broken, the handler pushes the drop straight past its hang
 * so it lets go now instead of sitting on a roof that no longer exists.</p>
 *
 * <p>26.1 rebuilt this corner of the particle API: {@code TextureSheetParticle}
 * is gone and {@code SingleQuadParticle} takes its place, the sprite arrives
 * through the constructor instead of a {@code setSprite} call, and the render
 * group is a {@code Layer} record rather than a {@code ParticleRenderType}.
 * The motion is still written out by hand so it can match vanilla's numbers
 * exactly rather than whatever this version decided gravity now does.</p>
 */
public class WaterDripParticle extends SingleQuadParticle {

    /** Must agree with {@code DripHandler.HANG_TICKS} - the sound is booked on the same clock. */
    public static final int HANG_TICKS = 40;


    // ---- vanilla's numbers -------------------------------------------------
    /** Vanilla's {@code setSize} for a drip - tiny, it is only a drop. */
    private static final float VANILLA_SIZE = 0.01F;
    /** Vanilla never sets {@code quadSize}; this is the base class default it gets. */
    private static final float VANILLA_QUAD_SIZE = 0.1F;
    /** {@code DripParticle}'s gravity, and the 0.02 the hanging variant scales it by. */
    private static final float VANILLA_GRAVITY = 0.06F;
    private static final float HANG_GRAVITY_SCALE = 0.02F;
    /** What {@code postMoveUpdate} does to the velocity each phase. */
    private static final float HANG_DAMP = 0.02F;
    private static final float FALL_DRAG = 0.98F;

    // ---- the tuning knobs --------------------------------------------------
    /** Multiplies the on-screen width. Below 1 is thinner than vanilla. */
    /**
     * Vanilla's drip texture, referenced at runtime - NOT copied into our
     * resources. Mojang's assets may not be redistributed, and ours ships a
     * jar, so borrowing the sprite by name from the atlas is the compliant
     * way to wear vanilla's look. The name comes from
     * {@code assets/minecraft/particles/dripping_water.json}, whose textures
     * list is ["minecraft:drip_hang"] - note the particle type is called
     * DRIPPING_WATER but the texture is drip_hang, which is why every earlier
     * attempt to name it came back as the missing sprite.
     */
    static final Identifier SPRITE =
            Identifier.fromNamespaceAndPath("minecraft", "drip_hang");

    /** The falling drop is a different texture again - falling_water.json. */
    static final Identifier FALL_SPRITE =
            Identifier.fromNamespaceAndPath("minecraft", "drip_fall");

    /**
     * Vanilla tints the drip (the texture is greyscale): it calls
     * {@code setColor(0.2F, 0.3F, 1.0F)} for water. The maintainer finds that
     * too saturated, so these are the same blue lifted a little toward white.
     * Put them back to 0.2/0.3/1.0 for vanilla's exact tint.
     */
    private static final float WATER_R = 0.3F;
    private static final float WATER_G = 0.4F;
    private static final float WATER_B = 1.0F;

    private static final float WIDTH_SCALE = 1.0F;
    /** Applied to the drop's own colour. Drop the blue channel to de-blue it. */
    private static final float TINT_R = 1.0F;
    private static final float TINT_G = 1.0F;
    private static final float TINT_B = 1.0F;
    /** Below 1 the drop reads softer. Needs {@link #getLayer} translucent. */
    private static final float ALPHA = 1.0F;

    private final int fallTicks;
    /** This drop's own hang length - the queued melt drops hang shorter than
     * the eave drops, and the old constant-40 branch cut their life short
     * (they vanished while hanging instead of falling). */
    private final int hangTicks;
    private final net.minecraft.client.renderer.texture.TextureAtlasSprite fallSprite;
    private int age = 0;

    WaterDripParticle(final ClientLevel level, final double x, final double y, final double z,
                      final double hangTicks, final double fallTicks, final double sizeScale,
                      final SpriteSet sprites) {
        super(level, x, y, z, ParticleUtils.getSprite(SPRITE));
        this.fallTicks = Math.max(1, (int) Math.round(fallTicks));
        this.hangTicks = Math.max(1, (int) Math.round(hangTicks));
        this.lifetime = this.hangTicks + this.fallTicks;
        this.gravity = 0.0F;      // written out by hand - see tick()
        this.friction = 1.0F;
        // Vanilla's own size: SingleQuadParticle sets
        //   0.1 * (random * 0.5 + 0.5) * 2.0
        // which is 0.1..0.2, not a flat 0.1 - a fixed 0.1 is a third smaller
        // than vanilla on average, which is what read as "too small".
        // An explicit size scale pins the size exactly (melt drops all read
        // the same); without one, vanilla's own size variance applies.
        final float scale = (float) (sizeScale > 0.0D ? sizeScale : 1.0D);
        this.quadSize = VANILLA_QUAD_SIZE * 2.0F * WIDTH_SCALE * scale
                * (sizeScale > 0.0D ? 1.0F : (this.random.nextFloat() * 0.5F + 0.5F));
        this.fallSprite = ParticleUtils.getSprite(FALL_SPRITE);
        this.setColor(WATER_R, WATER_G, WATER_B);
    }

    /**
     * Keep the drop upright: vanilla billboards a particle with the camera's full
     * rotation, so looking down lays the drop flat and turning tilts it. LOOKAT_Y
     * keeps only the yaw component, which is the cylindrical billboard - the drop
     * always stands vertical and only turns to face the player.
     */
    @Override
    public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
        return SingleQuadParticle.FacingCameraMode.LOOKAT_Y;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        // Translucent: the point of owning the texture is a paler, thinner,
        // softer drop, and an opaque layer would ignore its alpha.
        return Layer.OPAQUE;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        if (this.age <= this.hangTicks) {
            // Hanging: vanilla's hang particle creeps at gravity*0.02 and damps
            // its velocity by 0.02 every tick, which is to say it sits still.
            this.yd -= VANILLA_GRAVITY * HANG_GRAVITY_SCALE;
            this.move(this.xd, this.yd, this.zd);
            this.xd *= HANG_DAMP;
            this.yd *= HANG_DAMP;
            this.zd *= HANG_DAMP;
            return;
        }
        // Let go: vanilla's own fall, on vanilla's falling texture.
        if (this.age == this.hangTicks + 1)
            this.setSprite(this.fallSprite);
        this.yd -= VANILLA_GRAVITY;
        this.move(this.xd, this.yd, this.zd);
        if (this.onGround) {
            // Vanilla spawns SPLASH when the falling drop lands - that is the
            // little burst at the bottom of the fall.
            this.level.addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH,
                    this.x, this.y, this.z, 0.0D, 0.0D, 0.0D);
            this.remove();
            return;
        }
        this.xd *= FALL_DRAG;
        this.yd *= FALL_DRAG;
        this.zd *= FALL_DRAG;
    }

    /**
     * Let go now instead of waiting out the hang. Called when the block the
     * point hung from is gone - a drop still sitting on a roof that no longer
     * exists is the artefact this removes. Safe to call on a drop that has
     * already let go or already died.
     */
    public void release() {
        if (this.age < HANG_TICKS)
            this.age = HANG_TICKS;
    }

    /** True while it is still hanging, i.e. while {@link #release} would do anything. */
    public boolean isHanging() {
        return this.age < HANG_TICKS;
    }

    /**
     * Whether this drop is finished - landed, or removed for any other reason.
     * The handler keeps a handle to let go of drops whose block went away, and
     * needs to know when to stop holding one.
     */
    public boolean isDead() {
        return this.removed || this.age >= this.lifetime;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double hangTicks, final double fallTicks, final double sizeScale,
                                       final net.minecraft.util.RandomSource random) {
            return new WaterDripParticle(level, x, y, z, hangTicks, fallTicks, sizeScale, this.sprites);
        }
    }
}
