package org.orecruncher.dsurround.processing;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Shows a comic "power word" flying out of an entity when it takes a critical
 * hit, ported from the original 1.12.2 EntityHealthPopoffEffect / ParticleTextPopOff.
 * Unlike the earlier GUI-projected version (which grew huge for distant targets and
 * "flew toward the player"), this renders real 3D billboard text in world space at the
 * struck entity, launched along the attack direction and falling under gravity.
 */
public class CritWordHandler {

    private static final String[] CRIT_WORDS = {
            "AIEEE", "AIIEEE", "ARRGH", "AWK", "AWKKKKKK", "BAM", "BANG", "BANG-ETH", "BIFF", "BLOOP", "BLURP", "BOFF",
            "BONK", "CLANK", "CLANK-EST", "CLASH", "CLUNK", "CLUNK-ETH", "CRRAACK", "CRASH", "CRUNCH", "CRUNCH-ETH",
            "EEE-YOW", "FLRBBBBB", "GLIPP", "GLURPP", "KAPOW", "KAYO", "KER-SPLOOSH", "KERPLOP", "KLONK", "KLUNK",
            "KRUNCH", "OOOFF", "OOOOFF", "OUCH", "OUCH-ETH", "OWWW", "OW-ETH", "PAM", "PLOP", "POW", "POWIE",
            "QUNCKKK", "RAKKK", "RIP", "SLOSH", "SOCK", "SPLATS", "SPLATT", "SPLOOSH", "SWAAP", "SWISH", "SWOOSH",
            "THUNK", "THWACK", "THWACKE", "THWAPE", "THWAPP", "UGGH", "URKKK", "VRONK", "WHACK", "WHACK-ETH",
            "WHAM-ETH", "WHAMM", "WHAMMM", "WHAP", "Z-ZWAP", "ZAM", "ZAMM", "ZAMMM", "ZAP", "ZAP-ETH", "ZGRUPPP",
            "ZLONK", "ZLOPP", "ZLOTT", "ZOK", "ZOWIE", "ZWAPP", "ZZWAP", "ZZZZWAP", "ZZZZZWAP"
    };

    // Gold for crit words, red for damage numbers, green for healing (original colors).
    private static final int CRITICAL_TEXT_COLOR = 0xFFFFAA00;
    private static final int DAMAGE_TEXT_COLOR = 0xFFFF5555;
    private static final int HEAL_TEXT_COLOR = 0xFF55FF55;

    private static final int LIFETIME = 12;
    private static final int FADE_START = 6;

    // Physics (original ParticleTextPopOff): upward bounce + gravity, growing text.
    private static final float GRAVITY = 0.8F;
    private static final float GROW_FACTOR = 1.08F;
    private static final float HORIZONTAL_SPEED = 0.05F;
    private static final float UP_SPEED = 0.10F;

    private static final class CritWord {
        final String text;
        final int color;
        double prevX, prevY, prevZ;
        double x, y, z;
        double vx, vy, vz;
        float scale;
        int age;

        CritWord(String text, int color, double x, double y, double z, double vx, double vy, double vz) {
            this.text = text;
            this.color = color;
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.scale = 1.0F;
        }
    }

    // Don't render words beyond this depth (blocks) - they'd be unreadably tiny.
    private static final float MAX_RENDER_DEPTH = 40F;

    private final Configuration config;
    private final IModLog logger;
    private final IRandomizer random = Randomizer.current();
    private final ObjectArray<CritWord> active = new ObjectArray<>(4);
    private final Matrix4f viewProj = new Matrix4f();
    private final Vector4f clip = new Vector4f();

    public CritWordHandler(Configuration config, IModLog logger) {
        this.config = config;
        this.logger = logger;
        NeoForge.EVENT_BUS.addListener(this::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(this::onLivingHeal);
        ClientState.TICK_END.register(this::onTick);
    }

    public void onLivingDamage(LivingDamageEvent.Pre event) {
        final boolean showNumbers = this.config.entityEffects.showDamageNumbers;
        final boolean showCrits = this.config.entityEffects.showCritWords;
        if (!showNumbers && !showCrits)
            return;

        final LivingEntity entity = event.getEntity();
        if (entity.isRemoved() || !entity.isAlive())
            return;

        // Don't show for the local player in first-person view.
        var mc = Minecraft.getInstance();
        if (entity instanceof LocalPlayer && mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON)
            return;

        final float damage = event.getNewDamage();
        final int delta = Math.max(1, Math.round(Math.min(damage, entity.getHealth())));

        // Launch direction: away from the attacker.
        double dx = 0, dz = 0;
        var attacker = event.getSource().getEntity();
        if (attacker != null) {
            var dir = entity.position().subtract(attacker.position());
            final double len = Math.hypot(dir.x, dir.z);
            if (len > 0.001) {
                dx = dir.x / len;
                dz = dir.z / len;
            }
        }

        // Damage number above the entity (original used the top + 0.5).
        if (showNumbers) {
            this.active.add(new CritWord(String.valueOf(delta), DAMAGE_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 0.5D, entity.getZ(),
                    dx * HORIZONTAL_SPEED, UP_SPEED, dz * HORIZONTAL_SPEED));
        }

        // Critical hit (>= 40% of max health): an extra comic word one block up.
        if (showCrits && damage >= entity.getMaxHealth() / 2.5F) {
            final String word = CRIT_WORDS[this.random.nextInt(CRIT_WORDS.length)] + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 1.0D, entity.getZ(),
                    dx * HORIZONTAL_SPEED, UP_SPEED, dz * HORIZONTAL_SPEED));
            this.logger.debug("Crit word [%s] at %s", word, entity.blockPosition());
        }
    }

    public void onLivingHeal(LivingHealEvent event) {
        if (!this.config.entityEffects.showDamageNumbers)
            return;

        final LivingEntity entity = event.getEntity();
        if (entity.isRemoved() || !entity.isAlive())
            return;

        var mc = Minecraft.getInstance();
        if (entity instanceof LocalPlayer && mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON)
            return;

        // Show the actual health restored: clamped to how much the entity can heal
        // (so a full-health mob shows nothing), no "+" prefix.
        final int healable = Math.max(0, Math.round(entity.getMaxHealth() - entity.getHealth()));
        final int actual = Math.min(Math.round(event.getAmount()), healable);
        if (actual <= 0)
            return;

        this.active.add(new CritWord(String.valueOf(actual), HEAL_TEXT_COLOR,
                entity.getX(), entity.getY() + entity.getBbHeight() + 0.5D, entity.getZ(),
                0.0D, UP_SPEED, 0.0D));
    }

    private void onTick(Minecraft mc) {
        // NOTE: ObjectArray has no remove(int), so an indexed remove(i) would be boxed
        // to remove(Object) and silently never remove - leaking entries until the render
        // pass hangs. Use removeIf.
        this.active.removeIf(w -> {
            w.prevX = w.x;
            w.prevY = w.y;
            w.prevZ = w.z;
            w.vy -= 0.04D * GRAVITY;
            w.x += w.vx;
            w.y += w.vy;
            w.z += w.vz;
            w.scale *= GROW_FACTOR;
            return ++w.age >= LIFETIME;
        });
    }

    /**
     * GUI layer callback. The word's 3D world position is projected to screen and drawn
     * with a distance-based scale (far targets render smaller, like a real 3D text would)
     * so distant crits no longer blow up toward the player.
     */
    public void renderGui(GuiGraphicsExtractor graphics, DeltaTracker tracker) {
        if (this.active.isEmpty())
            return;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        var camera = mc.gameRenderer.getMainCamera();
        final float width = mc.getWindow().getGuiScaledWidth();
        final float height = mc.getWindow().getGuiScaledHeight();
        final var font = mc.font;

        camera.getViewRotationProjectionMatrix(this.viewProj);
        final var camPos = camera.position();
        final float partialTick = tracker.getGameTimeDeltaPartialTick(false);

        // Occlusion check shared by all words: a word behind any block (wall, terrain)
        // is hidden, mirroring vanilla name-tag line-of-sight handling.
        final var level = mc.level;
        final var eye = camPos;

        var pose = graphics.pose();

        for (var entry : this.active) {
            // Interpolate the world position between ticks so the moving word is smooth
            // at render framerate, not stuttery at 20 TPS.
            final double px = Mth.lerp(partialTick, entry.prevX, entry.x);
            final double py = Mth.lerp(partialTick, entry.prevY, entry.y);
            final double pz = Mth.lerp(partialTick, entry.prevZ, entry.z);

            // Cheap projection first: words behind the camera or beyond the render depth
            // are dropped before the (comparatively expensive) occlusion raycast runs.
            this.clip.set(
                    (float) (px - camPos.x),
                    (float) (py - camPos.y),
                    (float) (pz - camPos.z),
                    1.0F);
            this.viewProj.transform(this.clip);
            if (this.clip.w <= 0.001F || this.clip.w > MAX_RENDER_DEPTH)
                continue; // behind the camera or too far away

            // Skip words occluded by blocks between the camera and the text.
            final var hit = level.clip(new ClipContext(eye, new Vec3(px, py, pz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS)
                continue;

            final float depth = this.clip.w;
            final float sx = (this.clip.x / depth * 0.5F + 0.5F) * width;
            final float sy = (1.0F - (this.clip.y / depth * 0.5F + 0.5F)) * height;

            // Distance fade + shrink: scale by 1/depth so a word on a far target stays small.
            float textScale = Mth.clamp(entry.scale * 2.0F / depth, 0.25F, 2.0F);

            int alpha = 255;
            if (entry.age > FADE_START)
                alpha = (int) (255F * (LIFETIME - entry.age) / (float) (LIFETIME - FADE_START));
            int color = (entry.color & 0x00FFFFFF) | (alpha << 24);

            final int drawX = -font.width(entry.text) / 2 + 1;
            final int drawY = -font.lineHeight / 2 + 1;

            pose.pushMatrix();
            try {
                pose.translate(sx, sy);
                pose.scale(textScale, textScale);
                graphics.text(font, entry.text, drawX + 1, drawY + 1, 0xFF000000, false);
                graphics.text(font, entry.text, drawX, drawY, color, false);
            } finally {
                pose.popMatrix();
            }
        }
    }
}
