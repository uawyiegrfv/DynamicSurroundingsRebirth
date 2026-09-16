package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

import java.util.HashMap;
import java.util.Map;

/**
 * Shows a comic "power word" flying out of an entity when it takes a critical hit,
 * ported from the original 1.12.2 EntityHealthPopoffEffect / ParticleTextPopOff. It
 * projects the world-space spawn position to the screen each frame (GUI overlay) and
 * animates it (rise + gravity + grow), falling under gravity along the attack direction.
 */
public class CritWordHandler {

    /**
     * Built-in words, used when no data file provides any. Kept as the fallback so the feature
     * works out of the box; see {@code critwords.json} and CONFIGURATION.md for adding to it.
     */
    private static final String[] BUILT_IN_CRIT_WORDS = {
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

    // ---- numbers taken from 1.12.2 ParticleTextPopOff ------------------------------------
    // Physics: the original passes (0.001, 0.05 * BOUNCE_STRENGTH, 0.001) with
    // BOUNCE_STRENGTH = 1.5 and then NORMALISES the vector to a total magnitude of 0.12. Using a
    // constant UP_SPEED with an independent horizontal component is not equivalent: it made the
    // text rise less and fall deeper than the original.
    private static final float GRAVITY = 0.8F;
    /** Total launch speed; the direction is normalised to exactly this (1.12.2: 0.12). */
    private static final float MOTION_MAGNITUDE = 0.12F;

    /**
     * Text size for the comic power word: 1.12.2 draws it at
     * {@code particleScale(3.0) * 0.008 = 0.024} world units per font pixel, as a fixed
     * world-space billboard.
     */
    private static final float CRIT_WORLD_UNITS_PER_FONT_PX = 0.024F;

    /**
     * Text size for the damage / heal number, which 1.12.2 spawns as a separate particle of the
     * same class. The port shares one handler, so the smaller size is expressed here; it matches
     * the value SpeechBubbleHandler uses for its bubbles so the two features agree on scale.
     */
    private static final float ADDITION_WORLD_UNITS_PER_FONT_PX = 0.015F;

    private static final class CritWord {
        final String text;
        final int color;
        double prevX, prevY, prevZ;
        double x, y, z;
        double vx, vy, vz;
        float scale;
        int age;
        /** true while growing; flips to false once the scale passes the configured peak. */
        boolean growing = true;
        /** +1 while the text drifts away from the attacker, -1 after the flip. */
        double drift = 1.0D;
        /** the launch velocity, kept so the drift can be reversed on the flip. */
        double vx0, vz0;

        final float worldUnitsPerFontPx;

        CritWord(String text, int color, double x, double y, double z, double vx, double vy, double vz,
                 float worldUnitsPerFontPx) {
            this.text = text;
            this.color = color;
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.scale = 1.0F;
            this.worldUnitsPerFontPx = worldUnitsPerFontPx;
            this.vx0 = vx;
            this.vz0 = vz;
        }
    }

    // Don't render words beyond this depth (blocks) - they'd be unreadably tiny.
    private static final float MAX_RENDER_DEPTH = 40F;

    public static CritWordHandler INSTANCE;

    private final Configuration config;
    private final IModLog logger;
    private final IRandomizer random = Randomizer.current();
    private final ObjectArray<CritWord> active = new ObjectArray<>(4);
    private final Map<Integer, Float> lastHealth = new HashMap<>();

    // Animation values, refreshed from config on every spawn so a config edit applies without a
    // restart. 1.12.2's own numbers are the defaults: grow 1.08, shrink 0.96, peak 3x.
    private float growFactor = 1.08F;
    private float shrinkFactor = 0.96F;
    private float maxScale = 3.0F;
    private float sizeScale = 1.0F;
    private float driftScale = 1.0F;

    /** Pull the current popoff settings; called when a word is spawned. */
    private void refreshAnimationSettings() {
        final var cfg = this.config.popoffNumbers;
        this.sizeScale = cfg.sizePercent / 100.0F;
        this.growFactor = cfg.growFactor / 100.0F;
        this.shrinkFactor = cfg.shrinkFactor / 100.0F;
        this.maxScale = cfg.maxScalePercent / 100.0F;
        this.driftScale = cfg.driftPercent / 100.0F;
    }
    private final Matrix4f viewProj = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private static final Matrix4f CAPTURED_VIEW_PROJ = new Matrix4f();

    public CritWordHandler(Configuration config, IModLog logger) {
        INSTANCE = this;
        this.config = config;
        this.logger = logger;
        // 1.20.1: NeoForge.EVENT_BUS -> MinecraftForge.EVENT_BUS, and the NeoForge
        // LivingHurtEvent fires before the damage is applied, so a killing blow still
        // shows its number (the entity is still alive at that point; the 26.1 build
        // relies on LivingDamageEvent.Pre for the same reason).
        MinecraftForge.EVENT_BUS.addListener(this::onLivingDamage);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingHeal);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderStage);
        ClientState.TICK_END.register(this::onTick);
    }

    public void onLivingDamage(LivingHurtEvent event) {
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

        final float damage = event.getAmount();
        // Show the damage actually dealt: a killing blow against a nearly-dead mob
        // should read its remaining health, not the attacker's full hit.
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

        refreshAnimationSettings();

        // Damage number above the entity (original used the top + 0.5).
        if (showNumbers) {
            this.active.add(new CritWord(String.valueOf(delta), DAMAGE_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 0.5D, entity.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX));
        }

        // Critical hit (>= 40% of max health): an extra comic word one block up.
        if (showCrits && damage >= entity.getMaxHealth() / 2.5F) {
            final String word = pickWord() + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 1.0D, entity.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX));
            this.logger.debug("Crit word [%s] at %s", word, entity.blockPosition());
        }
    }

    /**
     * Picks a word: the data-provided list when there is one, otherwise the built-in defaults.
     * The data list is loaded by {@link CritWordsLibrary} from {@code critwords.json} using the
     * same discovery as every other DS data file, so mod packs can extend it without replacing it.
     */
    private String pickWord() {
        final java.util.List<String> fromData = CritWordsLibrary.words();
        if (!fromData.isEmpty())
            return fromData.get(this.random.nextInt(fromData.size()));
        return BUILT_IN_CRIT_WORDS[this.random.nextInt(BUILT_IN_CRIT_WORDS.length)];
    }

    private void onLivingHeal(LivingHealEvent event) {
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
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), ADDITION_WORLD_UNITS_PER_FONT_PX));
    }

    /**
     * A launch velocity with a TOTAL magnitude of {@link #MOTION_MAGNITUDE}, mirroring 1.12.2,
     * which normalises the initial motion vector to 0.12.
     * <p>
     * The original passes {@code (0.001, 0.05 * 1.5, 0.001)} - a purely vertical vector whose
     * horizontal parts are negligible (0.001 against 0.075) - and then normalises it. So the
     * behaviour to reproduce is "launch almost straight up at 0.12, tilted slightly along the
     * attack direction". The tilt is what makes the word drift away from the attacker.
     *
     * @param dx unit direction away from the attacker on X (0 when there is no attacker)
     * @param dz unit direction away from the attacker on Z
     */
    /**
     * Launch velocity components, each with a TOTAL magnitude of {@link #MOTION_MAGNITUDE},
     * mirroring 1.12.2 which normalises its initial motion vector to 0.12.
     * <p>
     * The original passes {@code (0.001, 0.05 * 1.5, 0.001)} - an almost purely vertical vector -
     * and normalises it, so the behaviour is "launch nearly straight up at 0.12, tilted slightly
     * along the attack direction". Exposed as three components because that is what the CritWord
     * constructor takes.
     */
    private double launchX(final double dx, final double dz) {
        return horizontalMagnitude() * this.driftScale * unitX(dx, dz);
    }

    private double launchZ(final double dx, final double dz) {
        return horizontalMagnitude() * this.driftScale * unitZ(dx, dz);
    }

    private static double launchY() {
        // 1.12.2's proportions: the vertical part dominates the negligible horizontal parts
        final double verticalShare = 0.075D / Math.sqrt(0.075D * 0.075D + 2.0D * 0.001D * 0.001D);
        return MOTION_MAGNITUDE * verticalShare;
    }

    private static double horizontalMagnitude() {
        final double vy = launchY();
        return Math.sqrt(Math.max(0.0D, MOTION_MAGNITUDE * MOTION_MAGNITUDE - vy * vy));
    }

    private static double unitX(final double dx, final double dz) {
        final double len = Math.sqrt(dx * dx + dz * dz);
        return len < 1.0E-6D ? 0.0D : dx / len;
    }

    private static double unitZ(final double dx, final double dz) {
        final double len = Math.sqrt(dx * dx + dz * dz);
        return len < 1.0E-6D ? 0.0D : dz / len;
    }

    private void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;
        CAPTURED_VIEW_PROJ.set(event.getProjectionMatrix());
        CAPTURED_VIEW_PROJ.mul(event.getPoseStack().last().pose());
    }

    public void renderGui(GuiGraphics graphics, float partialTick) {
        if (this.active.isEmpty())
            return;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        var font = mc.font;
        final float width = mc.getWindow().getGuiScaledWidth();
        final float height = mc.getWindow().getGuiScaledHeight();
        this.viewProj.set(CAPTURED_VIEW_PROJ);
        final var camPos = mc.gameRenderer.getMainCamera().getPosition();

        for (var entry : this.active) {
            final double px = Mth.lerp(partialTick, entry.prevX, entry.x);
            final double py = Mth.lerp(partialTick, entry.prevY, entry.y);
            final double pz = Mth.lerp(partialTick, entry.prevZ, entry.z);

            this.clip.set((float) (px - camPos.x), (float) (py - camPos.y), (float) (pz - camPos.z), 1.0F);
            this.viewProj.transform(this.clip);
            if (this.clip.w <= 0.001F || this.clip.w > MAX_RENDER_DEPTH)
                continue;

            // Skip words occluded by blocks between the camera and the text (the 1.20.1
            // port originally lacked this, so numbers showed through walls).
            final var hit = mc.level.clip(new ClipContext(
                    camPos, new Vec3(px, py, pz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS)
                continue;

            final float depth = this.clip.w;
            final float sx = (this.clip.x / depth * 0.5F + 0.5F) * width;
            final float sy = (1.0F - (this.clip.y / depth * 0.5F + 0.5F)) * height;

            int alpha = 255;
            if (entry.age > FADE_START)
                alpha = (int) (255F * (LIFETIME - entry.age) / (float) (LIFETIME - FADE_START));
            int color = (entry.color & 0x00FFFFFF) | (alpha << 24);

            final float textScale = this.computeTextScale(entry, depth, mc, height);
            final int drawX = -font.width(entry.text) / 2 + 1;
            final int drawY = -font.lineHeight / 2 + 1;

            graphics.pose().pushPose();
            try {
                graphics.pose().translate(sx, sy, 0.0F);
                graphics.pose().scale(textScale, textScale, 1.0F);
                graphics.drawString(font, entry.text, drawX + 1, drawY + 1, 0xFF000000, false);
                graphics.drawString(font, entry.text, drawX, drawY, color, false);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    /**
     * Text scale for the 2D GUI draw, derived so that a fixed WORLD-SPACE text height projects the
     * same way it did in 1.12.2.
     * <p>
     * A quad of world height {@code H} subtends {@code H / (2 * depth * tan(fov/2))} of the viewport,
     * and GUI pixels are the viewport divided by the GUI scale factor - the same factor implicit in
     * {@code getGuiScaledHeight()}. So
     * <pre>
     *     guiScale = worldUnitsPerFontPixel * growth * (guiHeight / 2) / (depth * tan(fov/2))
     * </pre>
     * which is exactly what SpeechBubbleHandler does for its bubbles; the only difference between
     * the two features is the world size (1.12.2: 0.024 for the crit word, 0.015 for a bubble).
     */
    private float computeTextScale(final CritWord entry, final float depth, final Minecraft mc,
                                   final float guiHeight) {
        final float fov = mc.options.fov().get();
        final float tanHalfFov = (float) Math.tan(Math.toRadians(fov) / 2.0D);

        final float worldUnitsPerFontPx = entry.worldUnitsPerFontPx * entry.scale * this.sizeScale;
        float scale = worldUnitsPerFontPx * (guiHeight / 2.0F) / (float) (depth * tanHalfFov);

        // guard rails only: never microscopic, never absurdly large on screen
        return Mth.clamp(scale, 0.10F, 6.0F);
    }

    private void onTick(Minecraft mc) {
        // Track health of nearby living entities so handleClientDamage's damage delta is
        // accurate (health is known before the hit, not just from the first damage packet),
        // and detect mob healing client-side in multiplayer (where LivingHealEvent only
        // fires server-side, so onLivingHeal never sees a mob's heal).
        if (mc.level != null && mc.player != null) {
            final double range = 48.0D;
            final boolean multiplayer = !mc.hasSingleplayerServer();
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living))
                    continue;
                if (living.isRemoved() || !living.isAlive())
                    continue;
                if (living.distanceToSqr(mc.player) > range * range)
                    continue;

                final int id = entity.getId();
                final float current = living.getHealth();
                final float previous = this.lastHealth.getOrDefault(id, current);

                // Heal detection: health went up. Single-player is covered by onLivingHeal
                // (shared EVENT_BUS), so only run this in multiplayer.
                if (multiplayer && current > previous + 0.5F && this.config.entityEffects.showDamageNumbers
                        && !(living instanceof LocalPlayer && mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON)) {
                    final int amount = Math.round(current - previous);
                    this.active.add(new CritWord(String.valueOf(amount), HEAL_TEXT_COLOR,
                    living.getX(), living.getY() + living.getBbHeight() + 0.5D, living.getZ(),
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), ADDITION_WORLD_UNITS_PER_FONT_PX));
                }

                this.lastHealth.put(id, current);
            }
            // Prune entities that left the range or died.
            this.lastHealth.keySet().removeIf(id -> {
                var e = mc.level.getEntity(id);
                return !(e instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()
                        || living.distanceToSqr(mc.player) > range * range;
            });
        }
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
            // 1.12.2 grows by 1.08 per frame and shrinks by 0.96 once the scale passes SIZE*3.
            // All three numbers are configurable, and the horizontal drift reverses with the
            // scale flip so the text is thrown at the target and then pulled back.
            if (w.growing) {
                w.scale *= this.growFactor;
                if (w.scale >= this.maxScale) {
                    w.growing = false;
                    w.drift = -1.0D;
                }
            } else {
                w.scale *= this.shrinkFactor;
            }
            w.vx = w.vx0 * w.drift;
            w.vz = w.vz0 * w.drift;
            return ++w.age >= LIFETIME;
        });
    }

    /** Client-side damage notification from the damage-event packet (1.20.1 has no client damage event). */
    public static void onClientDamage(LivingEntity entity, Vec3 sourcePos) {
        if (INSTANCE != null)
            INSTANCE.handleClientDamage(entity, sourcePos);
    }

    private void handleClientDamage(LivingEntity entity, Vec3 sourcePos) {
        final boolean showNumbers = this.config.entityEffects.showDamageNumbers;
        final boolean showCrits = this.config.entityEffects.showCritWords;
        if (!showNumbers && !showCrits)
            return;
        var mc = Minecraft.getInstance();
        if (entity instanceof LocalPlayer && mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON)
            return;

        // Damage amount is not in the 1.20.1 packet; estimate it from the health delta.
        final int id = entity.getId();
        final float current = entity.getHealth();
        final float previous = this.lastHealth.getOrDefault(id, current);
        final float damage = Math.max(0F, previous - current);
        this.lastHealth.put(id, current);

        // Launch direction: away from the source position (the attacker), like 26.1.
        double dx = 0D, dz = 0D;
        if (sourcePos != null) {
            double dirX = entity.getX() - sourcePos.x;
            double dirZ = entity.getZ() - sourcePos.z;
            final double len = Math.hypot(dirX, dirZ);
            if (len > 0.001) {
                dx = dirX / len;
                dz = dirZ / len;
            }
        } else {
            double angle = this.random.nextDouble() * Math.PI * 2;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
        }

        // Damage number above the entity (top + 0.5).
        if (showNumbers && damage >= 0.5F) {
            final int delta = Math.max(1, Math.round(damage));
            this.active.add(new CritWord(String.valueOf(delta), DAMAGE_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 0.5D, entity.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX));
        }

        // Critical hit (>= 40% of max health): an extra comic word one block up.
        if (showCrits && damage >= entity.getMaxHealth() / 2.5F) {
            final String word = pickWord() + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 1.0D, entity.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX));
        }
    }
}