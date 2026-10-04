package org.orecruncher.dsurround.client.particle;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.Constants;
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
 * <p>1.20.1 is the odd one out in two ways. It has no Architectury, and
 * Forge's event only exposes the plain {@code register(type, provider)}
 * overload here, so the provider cannot be handed a {@code SpriteSet}; the
 * sprite is pulled from the particle atlas on demand through the same
 * {@code ParticleUtils} helper the rest of the mod's particles use. And the
 * sprite is set after construction rather than passed to the super
 * constructor.</p>
 */
public class WaterDripParticle extends TextureSheetParticle {

    /** Must agree with {@code DripHandler.HANG_TICKS} - the sound is booked on the same clock. */
    public static final int HANG_TICKS = 40;


    /**
     * Our sprite id, but vanilla's texture - water_drip.png IS vanilla's
     * drip_hang.png. Borrowing vanilla's sprite directly does not work (looked
     * up by name the atlas hands back the missing sprite; via vanilla's
     * SpriteSet the particle never renders), so the texture was copied into
     * our own resource and registered the way ours already was.
     */
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
    static final ResourceLocation SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "drip_hang");

    /** The falling drop is a different texture again - falling_water.json. */
    static final ResourceLocation FALL_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "drip_fall");

    /**
     * Vanilla tints the drip (the texture is greyscale): it calls
     * {@code setColor(0.2F, 0.3F, 1.0F)} for water. The maintainer finds that
     * too saturated, so these are the same blue lifted a little toward white.
     * Put them back to 0.2/0.3/1.0 for vanilla's exact tint.
     */
    private static final float WATER_R = 0.3F;
    private static final float WATER_G = 0.4F;
    private static final float WATER_B = 1.0F;

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
    private static final float WIDTH_SCALE = 1.0F;
    // No tint and no alpha: the texture is vanilla's own and the sheet is
    // opaque, exactly as vanilla draws it. Tinting or fading it was the whole
    // reason the drop used to read as wrong next to vanilla's.

    private final int fallTicks;
    private final net.minecraft.client.renderer.texture.TextureAtlasSprite fallSprite;
    private int age = 0;

    WaterDripParticle(final ClientLevel level, final double x, final double y, final double z,
                      final double hangTicks, final double fallTicks, final TextureAtlasSprite sprite) {
        super(level, x, y, z);
        this.setSprite(sprite);
        this.fallTicks = Math.max(1, (int) Math.round(fallTicks));
        this.lifetime = (int) Math.round(hangTicks) + this.fallTicks;
        this.gravity = 0.0F;      // written out by hand - see tick()
        this.setSize(VANILLA_SIZE, VANILLA_SIZE);
        // Vanilla's own size: SingleQuadParticle sets
        //   0.1 * (random * 0.5 + 0.5) * 2.0
        // which is 0.1..0.2, not a flat 0.1 - a fixed 0.1 is a third smaller
        // than vanilla on average, which is what read as "too small".
        this.quadSize = VANILLA_QUAD_SIZE
                * (this.random.nextFloat() * 0.5F + 0.5F) * 2.0F * WIDTH_SCALE;
        this.fallSprite = ParticleUtils.getSprite(FALL_SPRITE);
        this.setColor(WATER_R, WATER_G, WATER_B);
    }

    @Override
    /**
     * Keep the drop upright. Vanilla billboards a particle with the camera's
     * whole rotation, so a drop tilts with the view: look down and it lies flat,
     * turn and it swings. Rebuilding the rotation from the camera's yaw alone
     * gives the cylindrical billboard - the drop always stands vertical and
     * only turns to face the player.
     */
    public void render(final VertexConsumer buffer, final Camera camera, final float partialTicks) {
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
        // 只绕 Y 轴转向玩家，不带俯仰 —— 水滴永远竖直，同时任何水平角度都能看见。
        //
        // 方向是推出来的，不是凑的。四个角点的绕序是 (-1,-1) -> (-1,1) -> (1,1) -> (1,-1)，
        // 从 +Z 看过去是顺时针，所以这个面的正面朝 -Z（法线 = -Z）。rotateY(t) 把 -Z
        // 送到 (-sin t, 0, -cos t)；px/pz 是粒子相对相机的坐标，所以"从粒子指向相机"
        // 的水平向量是 (-px, -pz)。令 -sin t = -px、-cos t = -pz ⇒ t = atan2(px, pz)。
        // 反过来写（atan2(-px,-pz)）就是正面背对相机，会被背面剔除掉 —— 整个看不见。
        // 也不要用 camera.getYRot() + 180：那个 180 是猜的，猜错就是"只有侧面看得到"。
        final Quaternionf rotation = new Quaternionf().rotateY((float) Math.atan2(px, pz));
        final float size = this.getQuadSize(partialTicks);
        final int light = this.getLightColor(partialTicks);
        final float u0 = this.getU0();
        final float u1 = this.getU1();
        final float v0 = this.getV0();
        final float v1 = this.getV1();
        final float[] us = { u1, u1, u0, u0 };
        final float[] vs = { v1, v0, v0, v1 };
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

    public ParticleRenderType getRenderType() {
        // Translucent, not opaque: the point of owning the texture is a paler,
        // thinner, softer drop, and an opaque quad would ignore its alpha.
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
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
        if (this.age <= HANG_TICKS) {
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
        if (this.age == HANG_TICKS + 1)
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

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double hangTicks, final double fallTicks, final double unused) {
            return new WaterDripParticle(level, x, y, z, hangTicks, fallTicks,
                    ParticleUtils.getSprite(SPRITE));
        }
    }
}
