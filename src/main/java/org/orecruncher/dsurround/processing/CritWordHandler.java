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

    /** Default lifetime; the live value comes from the config (popoffNumbers.lifetimeTicks). */
    private static final int DEFAULT_LIFETIME = 17;
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
        int age;
        /** true while growing; flips to false once the scale passes the configured peak. */
        boolean growing = true;
        /** set once the drift has been reversed at the midpoint. */
        boolean driftFlipped = false;
        /** +1 while the text drifts away from the attacker, -1 after the flip. */
        double drift = 1.0D;
        /** the launch velocity, kept so the drift can be reversed on the flip. */
        double vx0, vz0;

        final float worldUnitsPerFontPx;
        /**
         * True when this word belongs to the local player. Recorded so the render pass can hide the
         * player's own numbers in first person even when the camera changed after they were created
         * (see isOwnTextInFirstPerson).
         */
        final boolean ownedByLocalPlayer;

        CritWord(String text, int color, double x, double y, double z, double vx, double vy, double vz,
                 float worldUnitsPerFontPx, boolean ownedByLocalPlayer) {
            this.text = text;
            this.color = color;
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;

            this.worldUnitsPerFontPx = worldUnitsPerFontPx;
            this.ownedByLocalPlayer = ownedByLocalPlayer;
            this.vx0 = vx;
            this.vz0 = vz;
        }
    }

    // ---- "is this text the local player's own?" -------------------------------------------
    // 1.12.2 decided this once, when the number was created (EntityHealthPopoffEffect:104
    // "Don't display if it is the current player in first person view"). Deciding only there
    // leaves a hole: a number created in third person and still alive when the player returns to
    // first person (F5) keeps drawing across the player's own view for the rest of its life. So
    // the owner is recorded at creation and re-checked on every frame.

    /**
     * True when this entity is the local player whose camera we are rendering.
     * <p>
     * This is a TYPE test on purpose, and must stay one. In single player the damage and heal events
     * that create these words are dispatched on the SERVER thread (that is where authoritative
     * health lives), while {@code Minecraft.getInstance().player} is a client field written by the
     * client thread. Comparing the entity against that field answered {@code false} for the player's
     * own damage on the server thread and {@code true} for the very same entity on the render thread
     * one tick later - so the text was recorded as "not mine" and every later suppression check,
     * which is keyed on that flag, let it through. {@code instanceof} cannot race, and it is what
     * the 1.12.2 original used (EnvironState.isPlayer).
     */
    private static boolean isLocalPlayer(final LivingEntity entity) {
        return entity.getId() == clientPlayerId();
    }

    /** The client player's entity id, or -1 when there is none (title screen, disconnect). */
    private static int clientPlayerId() {
        var player = Minecraft.getInstance().player;
        return player == null ? -1 : player.getId();
    }

    /**
     * True when the text belongs to the local player AND the camera is the player's own eyes.
     * <p>
     * Both halves are needed. The id half is what makes this work at all: the damage and heal events
     * that create these numbers fire on the SERVER thread in single player, where the entity is the
     * integrated server's {@code ServerPlayer} - a different object, and a different CLASS, from the
     * client's {@code LocalPlayer}. Testing {@code instanceof LocalPlayer} there answered "not me" for
     * the player's own damage, so the number was recorded as belonging to somebody else and every
     * later suppression check let it through. An entity id carries no such trap: it is the same value
     * on both sides and readable from any thread. 1.12.2 did the equivalent (EnvironState.isPlayer
     * compared the entity's UUID).
     */
    private static boolean isOwnTextInFirstPerson(final LivingEntity entity) {
        var mc = Minecraft.getInstance();
        if (mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON)
            return false;
        return entity.getId() == clientPlayerId();
    }



    /** True when the camera is the local player's own eyes. */
    private static boolean ownCameraFirstPerson() {
        return Minecraft.getInstance().options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON;
    }


    // Don't render words beyond this depth (blocks) - they'd be unreadably tiny.
    private static final float MAX_RENDER_DEPTH = 40F;


    public static CritWordHandler INSTANCE;

    private final Configuration config;
    private final IModLog logger;
    private final IRandomizer random = Randomizer.current();
    private final ObjectArray<CritWord> active = new ObjectArray<>(4);
    private final Map<Integer, Float> lastHealth = new HashMap<>();

    /**
     * A hit whose amount is not known yet, waiting for its health sync.
     *
     * <p>Only used when the exact value did not arrive - see {@link #resolvePendingHits}. 1.20.1's
     * vanilla damage packet carries no amount, so without this mod's own message a client has nothing
     * but the entity's health to go on, and the health sync is a separate packet with no ordering
     * guarantee against the damage event.
     */
    private record PendingHit(float healthBefore, double sourceX, double sourceZ) {}

    private final Map<Integer, PendingHit> pendingHits = new HashMap<>();
    private final Map<Integer, Integer> pendingHitAge = new HashMap<>();

    /** Ticks a recorded hit waits for its health sync before being abandoned. */
    private static final int PENDING_HIT_TIMEOUT_TICKS = 10;

    // Animation values, refreshed from config on every spawn so a config edit applies without a
    // restart. 1.12.2's own numbers are the defaults: grow 1.08, shrink 0.96, peak 3x.
    private float growFactor = 1.12F;
    private float gravityScale = 0.57F;
    private float shrinkFactor = 0.93F;
    private float maxScale = 4.0F;
    private int peakTick = 4;
    private float sizeScale = 1.0F;
    private int lifetime = DEFAULT_LIFETIME;
    private float driftScale = 1.0F;

    /** Pull the current popoff settings; called when a word is spawned. */
    /** Fade begins at the same fraction of the life as the original (6 of 12). */
    private int fadeStart() {
        return Math.max(1, (this.lifetime + 1) / 2);
    }

    private void refreshAnimationSettings() {
        final var cfg = this.config.popoffNumbers;
        this.sizeScale = cfg.sizePercent / 100.0F;
        this.growFactor = cfg.growFactor / 100.0F;
        this.maxScale = cfg.maxScalePercent / 100.0F;
        this.driftScale = cfg.driftPercent / 100.0F;
        this.lifetime = Math.max(3, cfg.lifetimeTicks);
        this.gravityScale = cfg.gravityPercent / 100.0F;
        this.peakTick = Math.max(1, Math.min(this.lifetime - 2, cfg.peakTickTicks));

        // Derive the shrink rate so the text returns to its starting size. Deriving it (rather than
        // exposing it) is what makes "fast growth" and "same size at the end" hold simultaneously:
        // a user-chosen shrink rate cannot satisfy both.
        // Shrink rate for equal start and end sizes.
        //
        // The text is DRAWN before the scale is advanced, so the frames actually shown run from
        // age 0 (size 1) up to the peak at age = peakTick and back down to the last frame at
        // age = lifetime - 1. With n1 = peakTick rise frames and n2 = lifetime - 1 - peakTick fall
        // frames, requiring last == first means
        //     g^n1 * shrink^n2 == 1     ->     shrink = g ^ (-n1 / n2)
        // (the exponent is n1/n2, not n1/(n2-1): there are exactly n2 multiplies from the peak to
        // the final frame). Getting this wrong makes the text end visibly smaller than it started,
        // which is what the user spotted.
        final int shrinkTicks = this.lifetime - 1 - this.peakTick;
        final int shrinkSteps = Math.max(1, shrinkTicks);
        this.shrinkFactor = shrinkTicks > 0 && this.growFactor > 1.0F
                ? (float) Math.pow(this.growFactor, -this.peakTick / (double) shrinkSteps)
                : 1.0F;
        this.lifetime = Math.max(2, cfg.lifetimeTicks);
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
        if (isOwnTextInFirstPerson(entity))
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
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(entity)));
        }

        // Critical hit (>= 40% of max health): an extra comic word one block up.
        if (showCrits && damage >= entity.getMaxHealth() / 2.5F) {
            final String word = pickWord() + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 1.0D, entity.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz), CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(entity)));
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
        if (isOwnTextInFirstPerson(entity))
            return;

        // Show the actual health restored: clamped to how much the entity can heal
        // (so a full-health mob shows nothing), no "+" prefix.
        final int healable = Math.max(0, Math.round(entity.getMaxHealth() - entity.getHealth()));
        final int actual = Math.min(Math.round(event.getAmount()), healable);
        if (actual <= 0)
            return;

        this.active.add(new CritWord(String.valueOf(actual), HEAL_TEXT_COLOR,
                    entity.getX(), entity.getY() + entity.getBbHeight() + 0.5D, entity.getZ(),
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), ADDITION_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(entity)));
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

            // The simulation advances `age` once per TICK, but this runs once per FRAME - so a size
            // or alpha read straight from entry.age steps at 20 Hz and reads as choppy. Interpolating
            // it is the same treatment the position already gets above, and it is visual only: `age`
            // still decides when the entry dies, and the growth curve, peak tick, lifetime and fade
            // window are all unchanged.
            final float renderAge = entry.age + partialTick;

            final double sqDist = (px - camPos.x) * (px - camPos.x)
                    + (py - camPos.y) * (py - camPos.y)
                    + (pz - camPos.z) * (pz - camPos.z);

            // Too close to the eye to draw. A world-space text a few centimetres from the camera
            // projects across the whole viewport, so the number for whatever is standing on top of
            // the player smears over the screen and reads as the player's own text. This is what a
            // first-person report of "my own damage number" actually was - the damaging entity was
            // close enough that its number landed inside the player's head. Nothing useful can be
            // shown at that distance, so it is dropped.

            // The local player's own numbers are not drawn while looking through their own eyes.
            // 1.12.2 refused to CREATE them in first person; this refuses to DRAW them, which also
            // covers a word created in third person that outlives the switch back to first person.
            if (entry.ownedByLocalPlayer && ownCameraFirstPerson())
                continue;

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
            if (renderAge > this.fadeStart())
                alpha = (int) (255F * (this.lifetime - renderAge)
                        / (float) (this.lifetime - this.fadeStart()));
            // Clamp: the truncating cast could go negative on the last frame, which handed an
            // invalid alpha to the colour.
            alpha = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
            int color = (entry.color & 0x00FFFFFF) | (alpha << 24);

            float textScale = this.computeTextScale(entry, renderAge, depth, mc, height);
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
     * Text size for a given age of the animation, as a factor of the base size.
     * <p>
     * Derived from the age rather than accumulated into the entry, so the first and last frames are
     * exactly equal by construction: {@code sizeAtAge(0) == sizeAtAge(lifetime - 1) == 1}. The peak
     * sits on the frame at {@code peakTickTicks} and equals {@code grow ^ peakTick}, which is what
     * makes the rise read as fast and the fall as slow (the rise spends ~6 frames reaching the peak,
     * the fall ~13 coming back).
     * <p>
     * The old code multiplied a stored scale once per tick before drawing, so the first frame was
     * already {@code grow} (1.14) while the shrink aimed at 1.0 - the number ended visibly smaller
     * than it started, which the user spotted immediately.
     */
    private float sizeAtAge(final float age) {
        final float peakSize = (float) Math.pow(this.growFactor, this.peakTick);
        final float size = age <= this.peakTick
                ? (float) Math.pow(this.growFactor, age)
                : (float) (peakSize * Math.pow(this.shrinkFactor, age - this.peakTick));
        return this.maxScale > 0.0F ? Math.min(size, this.maxScale) : size;
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
    private float computeTextScale(final CritWord entry, final float renderAge, final float depth,
                                   final Minecraft mc, final float guiHeight) {
        final float fov = mc.options.fov().get();
        final float tanHalfFov = (float) Math.tan(Math.toRadians(fov) / 2.0D);

        final float worldUnitsPerFontPx =
                entry.worldUnitsPerFontPx * sizeAtAge(renderAge) * this.sizeScale;
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
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), ADDITION_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living)));
                }

                this.lastHealth.put(id, current);
            }
            // Settle damage events that are still waiting on their health sync.
            this.resolvePendingHits(mc);

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
            w.vy -= 0.04D * GRAVITY * this.gravityScale;
            w.x += w.vx;
            w.y += w.vy;
            w.z += w.vz;
            // 1.12.2 grows by 1.08 per frame and shrinks by 0.96 once the scale passes SIZE*3.
            // All three numbers are configurable, and the horizontal drift reverses with the
            // scale flip so the text is thrown at the target and then pulled back.
            // Symmetric by construction: grow for the first half of the life, shrink for the
            // second. With shrink == 1/grow the text ends at exactly the size it started, and the
            // peak lands on the middle tick - which is the behaviour observed in the original.
            // Deciding the flip by the clock (not by a scale threshold) keeps that symmetry intact.
            // Fast growth, slow shrink, and the shrink rate is derived so the text ends at exactly
            // the size it started:
            //     shrink = grow ^ (-peakTick / (lifetime - 1 - peakTick))
            // The drift reverses at the peak.
            if (w.age >= this.peakTick && !w.driftFlipped) {
                w.driftFlipped = true;
                w.drift = -1.0D;
            }
            w.vx = w.vx0 * w.drift;
            w.vz = w.vz0 * w.drift;
            return ++w.age >= this.lifetime;
        });
    }

    /**
     * Exact damage, from this mod's own server -&gt; client message.
     *
     * <p>Preferred over the health-delta path below, which cannot be exact: vanilla's damage packet
     * carries no amount, and the health sync that would supply it arrives independently, so a delta
     * measured at packet time is sometimes zero and the hit is dropped. This carries
     * {@code LivingHurtEvent.getAmount()} instead - the real post-mitigation value.
     */
    public static void onExactDamage(final int entityId, final float damage,
                                     final double sourceX, final double sourceY, final double sourceZ) {
        if (INSTANCE != null)
            INSTANCE.displayDamage(entityId, damage, sourceX, sourceZ);
    }

    /**
     * Shows a damage number from a known amount. Shared by the exact-damage path and the
     * health-delta fallback so the two cannot drift apart.
     */
    private void displayDamage(final int entityId, final float damage,
                               final double sourceX, final double sourceZ) {
        if (damage < 0.5F)
            return;
        if (!this.config.entityEffects.showDamageNumbers && !this.config.entityEffects.showCritWords)
            return;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(entityId) instanceof LivingEntity living))
            return;
        if (isOwnTextInFirstPerson(living))
            return;

        // A killing blow against a nearly-dead mob should read the health it had, not the
        // attacker's full hit.
        final int delta = Math.max(1, Math.round(Math.min(damage, living.getHealth() + damage)));

        double dx, dz;
        final double dirX = living.getX() - sourceX;
        final double dirZ = living.getZ() - sourceZ;
        final double len = Math.hypot(dirX, dirZ);
        if (len > 0.001) {
            dx = dirX / len;
            dz = dirZ / len;
        } else {
            final double angle = this.random.nextDouble() * Math.PI * 2;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
        }

        // An exact value supersedes any hit still waiting on its health sync for this entity.
        this.pendingHits.remove(entityId);
        this.pendingHitAge.remove(entityId);

        refreshAnimationSettings();

        if (this.config.entityEffects.showDamageNumbers) {
            this.active.add(new CritWord(String.valueOf(delta), DAMAGE_TEXT_COLOR,
                    living.getX(), living.getY() + living.getBbHeight() + 0.5D, living.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz),
                    CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living)));
        }
        if (this.config.entityEffects.showCritWords && damage >= living.getMaxHealth() / 2.5F) {
            final String word = pickWord() + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    living.getX(), living.getY() + living.getBbHeight() + 1.0D, living.getZ(),
                    launchX(dx, dz), launchY(), launchZ(dx, dz),
                    CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living)));
        }
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
        if (isOwnTextInFirstPerson(entity))
            return;

        // Fallback path. The exact amount arrives in this mod's own server message, and only when the
        // server has the mod and this client has handshaked the channel. What is left here is the
        // entity's health - which must NOT be read at this moment.
        //
        // The health sync is a SEPARATE packet from this damage event, with no ordering guarantee
        // against it, and onTick writes the same tracker every tick. Measuring here therefore found
        // previous == current whenever the sync had already landed, produced a delta of 0, and
        // DISCARDED the hit - not shown wrong, absent. That is the reported "weapons dealing 15+
        // damage show as only 9": the big hits are the ones most likely to have been synced already.
        //
        // So the hit is recorded, and measured once the health has actually moved.
        final int id = entity.getId();
        final float current = entity.getHealth();
        final float previous = this.lastHealth.getOrDefault(id, current);

        // Keep the FIRST hit's `previous`: it is the only pre-hit health available, and a second hit
        // inside the same window would overwrite it with an already-reduced value.
        this.pendingHits.putIfAbsent(id, new PendingHit(previous,
                sourcePos == null ? entity.getX() : sourcePos.x,
                sourcePos == null ? entity.getZ() : sourcePos.z));
        this.pendingHitAge.put(id, 0);

        // If the health already rose this is a heal, not a hit; keep the tracker honest without
        // letting it mask a drop.
        if (current > previous)
            this.lastHealth.put(id, current);
    }

    /**
     * Settles recorded hits once their health sync has arrived.
     *
     * <p>Called from onTick after the tracker update, so this reads the newest health the client has.
     * Reading the health is sound: {@code LivingEntity.getHealth()} returns a synced entity data item,
     * so it is the server's value as soon as the sync packet lands.
     */
    private void resolvePendingHits(final Minecraft mc) {
        if (this.pendingHits.isEmpty())
            return;

        this.pendingHits.entrySet().removeIf(entry -> {
            final int id = entry.getKey();
            final PendingHit pending = entry.getValue();

            if (mc.level == null || !(mc.level.getEntity(id) instanceof LivingEntity living)) {
                this.pendingHitAge.remove(id);
                return true;
            }
            // A removed or dead entity will never deliver another health update.
            if (living.isRemoved() || !living.isAlive()) {
                this.pendingHitAge.remove(id);
                return true;
            }

            final float drop = pending.healthBefore() - living.getHealth();
            final int age = this.pendingHitAge.merge(id, 1, Integer::sum);

            if (drop < 0.5F) {
                // A fully absorbed hit never moves the health, and neither does one whose sync never
                // arrives; give up after the timeout rather than holding the entry forever.
                if (age < PENDING_HIT_TIMEOUT_TICKS)
                    return false;
                this.pendingHitAge.remove(id);
                return true;
            }

            this.pendingHitAge.remove(id);
            this.lastHealth.put(id, living.getHealth());
            displayDamage(id, drop, pending.sourceX(), pending.sourceZ());
            return true;
        });
    }
}