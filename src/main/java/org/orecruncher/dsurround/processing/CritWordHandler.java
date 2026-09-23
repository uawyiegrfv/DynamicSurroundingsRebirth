package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
        /** Entity whose word this is; excluded from the entity occlusion test so it never hides its own word. */
        final int ownerId;

        // ---- CRITWORD diagnostics (Configuration.Flags.CRIT_WORD) -------------------------
        /** Alpha/scale/renderAge recorded on the previous frame, to catch a non-monotonic jump. */
        int diagPrevAlpha = -1;
        float diagPrevScale = -1F;
        float diagPrevRenderAge = -1F;
        /** Which render pass last drew this entry, and how many times within that pass. */
        int diagLastFrame = -1;
        int diagDrawsThisFrame;
        /** Animation clock: last age seen and the fraction actually used for it. */
        int lastAge = -1;
        float lastPartial = -1F;
        /** Consecutive frames this word read as blocked; see the occlusion test. */
        int occluded;
        /**
         * Symmetric half of the occlusion hysteresis. Hiding takes two blocked frames; showing has
         * to take two CLEAR ones as well, otherwise a word that is only blocked for a frame or two
         * comes straight back and the pair of transitions reads as a blink.
         */
        int clearRun;
        boolean hidden;
        /** The renderAge implied by the clock above; kept only so the diagnostic can show it. */
        float lastRenderAge = -1F;

        // ---- per-frame rate limit + end-of-life audit -------------------------------------
        /** Final textScale of the previous frame, and the renderAge that produced it. */
        float lastScale = -1F;
        float lastScaleAge = -1F;
        /**
         * Audit, reported once per word in [CRITWORD-LIFE] instead of once per frame. Per-frame
         * logging floods and log4j drops lines under load, which is why a single-frame event kept
         * escaping every instrumentation that sampled frames. One line per word cannot be dropped.
         */
        float auditMaxScaleRatio = 1F;
        /** Last alpha actually handed to the draw call; the fade must never rise. See the clamp. */
        int lastAlpha = -1;
        int auditMaxAlphaUp;
        int auditFrames;
        int auditHidden;
        float lastDrawPartial = -1F;

        CritWord(String text, int color, double x, double y, double z, double vx, double vy, double vz,
                 float worldUnitsPerFontPx, boolean ownedByLocalPlayer, int ownerId) {
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
            this.ownerId = ownerId;
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


    private final Configuration config;
    private final IModLog logger;
    private final IRandomizer random = Randomizer.current();
    private final ObjectArray<CritWord> active = new ObjectArray<>(4);
    private final Map<Integer, Float> lastHealth = new HashMap<>();
    /** Render-pass counter for the CRITWORD diagnostics. */
    private int diagFrame;
    /**
     * Real frame counter, advanced by the LEVEL render stage rather than by renderGui.
     *
     * <p>The duplicate-draw check has to know whether two draws happened in the same FRAME, and it
     * previously used the interpolation fraction for that. It cannot: partialTick is quantised to
     * 1/50 (measured - only 51 distinct values exist), so two different frames routinely carry the
     * same value and the check reported false duplicates. It also previously used {@code diagFrame},
     * which is incremented INSIDE renderGui, so a second invocation in one frame would have advanced
     * it too and the check could never fire at all.
     */
    private int frameId;

    // Animation values, refreshed from config on every spawn so a config edit applies without a
    // restart. 1.12.2's own numbers are the defaults: grow 1.08, shrink 0.96, peak 3x.
    private float growFactor = 1.28F;
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
    /** Scratch vector for the CRITWORD projection probe; see the block in renderGui. */
    private final Vector4f clipUp = new Vector4f();
    private static final Matrix4f CAPTURED_VIEW_PROJ = new Matrix4f();

    public CritWordHandler(Configuration config, IModLog logger) {
        this.config = config;
        this.logger = logger;
        // 1.12.2 EntityHealthPopoffEffect parity: damage, heal and crit words all come from the
        // client-side health poll in onTick - no damage/heal events (server-side only, and the
        // amount semantics differ per version) and no packets (the client already knows every
        // nearby entity's health). Identical behaviour single- and multiplayer, no server needed.
        NeoForge.EVENT_BUS.addListener(this::onRenderStage);
        ClientState.TICK_END.register(this::onTick);
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
        this.frameId++;
        // 1.21.1: the PoseStack passed to this event no longer carries the camera view
        // rotation; the model-view matrix is provided as an explicit field instead.
        CAPTURED_VIEW_PROJ.set(event.getProjectionMatrix());
        CAPTURED_VIEW_PROJ.mul(event.getModelViewMatrix());
    }

    public void renderGui(GuiGraphics graphics, net.minecraft.client.DeltaTracker tracker) {
        final float partialTick = tracker.getGameTimeDeltaPartialTick(false);
        if (this.active.isEmpty())
            return;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        var font = mc.font;
        final float width = mc.getWindow().getGuiScaledWidth();
        final float height = mc.getWindow().getGuiScaledHeight();
        this.viewProj.set(CAPTURED_VIEW_PROJ);
        final var cam = mc.gameRenderer.getMainCamera();
        final var camPos = cam.getPosition();

        // ---- CRITWORD diagnostics ---------------------------------------------------------
        // Off unless BOTH debug logging is on AND traceMask has the CRIT_WORD bit (8). When off
        // this is one boolean read per frame; the String.format calls below never run.
        //
        // It logs the environment the size formula reads (window size, GUI scale, FOV), the
        // per-entry inputs and the computed result, and it flags the two things that cannot be
        // seen in the source:
        //   [CRITWORD-JUMP] alpha or scale went UP between frames - the reported "flashes
        //                   bright just before it disappears". The fade is monotonic in
        //                   renderAge, so any upward step means renderAge itself stepped back.
        //   [CRITWORD-DUP]  the same entry drawn more than once in ONE frame - the
        //                   ObjectArray add/removeIf race (see the note on `active`).
        final boolean diag = this.logger.isTracing(Configuration.Flags.CRIT_WORD);
        final int diagFrame = diag ? ++this.diagFrame : 0;
        if (diag && (diagFrame % 40) == 1) {
            final float diagFov = mc.options.fov().get();
            this.logger.debug("[CRITWORD-ENV] frame=%d win=%dx%d guiScale=%.2f guiScaled=%.0fx%.0f "
                            + "fov=%.1f tanHalf=%.4f sizeScale=%.3f grow=%.4f shrink=%.4f peak=%d life=%d "
                            + "fadeStart=%d active=%d",
                    diagFrame, mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    mc.getWindow().getGuiScale(), width, height, diagFov,
                    (float) Math.tan(Math.toRadians(diagFov) / 2.0D),
                    this.sizeScale, this.growFactor, this.shrinkFactor, this.peakTick, this.lifetime,
                    this.fadeStart(), this.active.size());
        }

        for (var entry : this.active) {
            // --- monotonic render clock -------------------------------------------------------
            // partialTick wraps to near 0 at every tick boundary, while `age` only advances in
            // onTick (ClientState.TICK_END). A frame drawn between the wrap and the tick
            // therefore sees `age + ~0` AND `lerp(~0, prev, cur)` - nearly a full tick LESS than
            // the frame before, on both the animation clock and the position. Alpha is a falling
            // ramp in renderAge, so a rewind is a step UP in opacity; the position simply jerks
            // backwards. Either one reads as the reported "flashes bright for an instant near the
            // end of the shrink". Holding the fraction until the tick actually lands keeps both
            // monotonic; the next tick resets it.
            if (entry.lastAge != entry.age)
                entry.lastPartial = -1F;
            final float partial = entry.lastPartial >= 0F
                    ? Math.max(partialTick, entry.lastPartial)
                    : partialTick;
            entry.lastAge = entry.age;
            entry.lastPartial = partial;

            final double px = Mth.lerp(partial, entry.prevX, entry.x);
            final double py = Mth.lerp(partial, entry.prevY, entry.y);
            final double pz = Mth.lerp(partial, entry.prevZ, entry.z);

            // The simulation advances `age` once per TICK, but this runs once per FRAME - so a size
            // or alpha read straight from entry.age steps at 20 Hz and reads as choppy. Interpolating
            // it is the same treatment the position already gets above, and it is visual only: `age`
            // still decides when the entry dies, and the growth curve, peak tick, lifetime and fade
            // window are all unchanged.
            final float renderAge = entry.age + partial;
            entry.lastRenderAge = renderAge;

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

            // Occlusion, with hysteresis. The ray runs from the camera to a point half a block above
            // the mob's head, so as the player or the word moves it grazes geometry - a bare per-frame
            // test therefore flips on and off, and the word blinks out for a frame at a time. That
            // is what "flashes for an instant" looks like from the outside. Requiring two
            // consecutive blocked frames before hiding removes the single-frame blink without
            // giving up the test: a word genuinely behind a wall still disappears, one tick later.
            final var hit = mc.level.clip(new ClipContext(
                    camPos, new Vec3(px, py, pz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            boolean blocked = hit.getType() != HitResult.Type.MISS;
            if (!blocked)
                blocked = isEntityBlocking(mc, camPos, new Vec3(px, py, pz), entry.ownerId);
            if (blocked) {
                entry.clearRun = 0;
                entry.occluded = Math.min(2, entry.occluded + 1);
                if (entry.occluded >= 2)
                    entry.hidden = true;
            } else {
                entry.occluded = 0;
                if (entry.hidden) {
                    // Symmetric hysteresis: a word that went behind something has to come back for
                    // two clear frames before it is shown again. Hiding took two frames, so showing
                    // has to as well - otherwise a one-frame dip behind a fence post produces hide,
                    // then show, and the pair is visible as a blink.
                    if (++entry.clearRun >= 2) {
                        entry.hidden = false;
                        entry.clearRun = 0;
                    }
                }
            }
            if (entry.hidden) {
                entry.auditHidden++;
                continue;
            }

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
            // The fade is monotonic by definition, so enforce it here rather than trusting every
            // input. renderAge already cannot run backwards, but alpha also depends on `lifetime`
            // and `fadeStart`, which are handler fields refreshed on damage events - a word whose
            // animation parameters changed underneath it would otherwise get a brighter frame for
            // free. This is the last guard between the arithmetic above and the pixels below.
            if (entry.lastAlpha >= 0 && alpha > entry.lastAlpha)
                alpha = entry.lastAlpha;
            entry.lastAlpha = alpha;
            int color = (entry.color & 0x00FFFFFF) | (alpha << 24);

            // --- vertical scale, measured from the matrix that produced `depth` -------------
            // The old code took tan(fov/2) from the OPTIONS SLIDER, which is not the FOV the
            // projection matrix was built from. Measured in game: sprinting pushes the real FOV
            // to 1.1x the slider (98 -> 107.8 degrees), so every word was ~19% too large for as
            // long as the player sprinted, then snapped back when they stopped - a size that
            // changed with how you were moving, not with where the word was. See measureTanHalf.
            final float tanHalfActual = this.measureTanHalf(cam, camPos, px, py, pz, depth, height);
            float textScale = this.computeTextScale(entry, renderAge, depth, mc, height, tanHalfActual);

            // ---- per-frame rate limit on the size -----------------------------------------
            // Everything the size depends on is smooth: the curve is a function of renderAge, which
            // is clamped monotonic, and depth only moves as fast as the camera and the word do. So a
            // frame that comes out much larger than the one before it is NOT animation - it is the
            // projection matrix having been something else for that frame (it is captured during the
            // level render stage and consumed here, in the GUI stage, so a skipped or duplicated
            // level pass hands this a matrix the player never saw). That produces a single frame of
            // huge, and therefore very visible, text, which is what "flashes bright for an instant"
            // looks like when it is not the alpha doing it.
            //
            // The bound is the curve's own growth between the two frames, so the animation is
            // untouched at any frame rate, plus a tolerance for genuinely closing distance.
            if (entry.lastScale > 0F && entry.lastScaleAge >= 0F) {
                final float prevCurve = sizeAtAge(entry.lastScaleAge);
                final float curCurve = sizeAtAge(renderAge);
                final float expected = prevCurve > 1.0E-4F
                        ? entry.lastScale * (curCurve / prevCurve)
                        : entry.lastScale;
                if (expected > 0F) {
                    final float ratio = textScale / expected;
                    if (ratio > entry.auditMaxScaleRatio)
                        entry.auditMaxScaleRatio = ratio;
                    final float cap = expected * MAX_SCALE_STEP_PER_FRAME;
                    if (textScale > cap) {
                        this.logger.debug("[CRITWORD-SPIKE] text=%s renderAge=%.3f scale %.3f -> %.3f "
                                        + "capped to %.3f (curve allows %.3f) depth=%.3f tanHalf=%.4f",
                                entry.text, renderAge, entry.lastScale, textScale, cap, expected,
                                depth, tanHalfActual);
                        textScale = cap;
                    }
                }
            }
            entry.lastScale = textScale;
            entry.lastScaleAge = renderAge;
            entry.auditFrames++;

            // ---- CRITWORD diagnostics (see the block at the top of this method) -----------
            if (diag) {
                // Detected on the interpolation fraction, NOT on diagFrame. diagFrame is incremented
                // inside this method, so if this method were ever invoked twice in one frame it would
                // increment twice as well and the test would never fire. Two calls in one frame share
                // the same partialTick; two consecutive frames never do.
                if (entry.diagLastFrame == this.frameId) {
                    entry.diagDrawsThisFrame++;
                    this.logger.debug("[CRITWORD-DUP] frame=%d text=%s drawn=%d times in ONE frame "
                                    + "(handler invoked twice: two draws composite to a brighter word)",
                            this.frameId, entry.text, entry.diagDrawsThisFrame);
                } else {
                    entry.diagLastFrame = this.frameId;
                    entry.diagDrawsThisFrame = 1;
                }
                entry.lastDrawPartial = partialTick;

                // Only ALPHA going up, or renderAge going BACKWARDS, is a real flash. The old
                // test also fired when the scale went up, which is simply the growth phase - it
                // reported 879 of 879 "jumps" in a session with no flash at all, so the number
                // was measuring the animation, not the bug.
                final boolean jumped = entry.diagPrevAlpha >= 0
                        && (alpha > entry.diagPrevAlpha || renderAge < entry.diagPrevRenderAge);
                if (entry.diagPrevAlpha >= 0 && alpha > entry.diagPrevAlpha)
                    entry.auditMaxAlphaUp = Math.max(entry.auditMaxAlphaUp, alpha - entry.diagPrevAlpha);
                if (jumped) {
                    this.logger.debug("[CRITWORD-JUMP] text=%s renderAge %.3f -> %.3f | alpha %d -> %d | "
                                    + "scale %.4f -> %.4f  (non-monotonic: this is the flash)",
                            entry.text, entry.diagPrevRenderAge, renderAge,
                            entry.diagPrevAlpha, alpha, entry.diagPrevScale, textScale);
                }
                // ONE SHORT LINE PER FRAME, for the whole life.
                //
                // This is the line that is supposed to catch the flash, so it is logged every single
                // frame and kept deliberately tiny. The detailed [CRITWORD-DRAW] below used to be the
                // every-frame one during the fade, at roughly 250 characters a line; at 300+ fps that
                // is a flood, and log4j drops lines under load. That is very likely why a single-frame
                // event survived every instrumentation: the rarer it was, the more likely the one
                // line that would have shown it was the one dropped. Short lines do not get dropped.
                this.logger.debug("[CRITWORD-F] %x r=%.3f a=%d s=%.4f d=%.3f",
                        System.identityHashCode(entry) & 0xFFFF, renderAge, alpha, textScale, depth);
                final float tanHalfMatrix = tanHalfActual;
                // Detailed context, sampled. No longer every frame - the compact line above owns that.
                if (jumped || (diagFrame % 20) == 1) {
                    final float guiPxH = textScale * font.lineHeight;
                    this.logger.debug("[CRITWORD-DRAW] id=%x text=%s age=%d renderAge=%.3f ptick=%.3f "
                                    + "depth=%.3f sizeAtAge=%.4f textScale=%.4f | onScreen guiPxH=%.1f "
                                    + "physPxH=%.1f fracOfScreenH=%.5f | tanHalfMatrix=%.4f tanHalfOptions=%.4f "
                                    + "fovRatio=%.3f | alpha=%d occ=%d at=%.0f,%.0f",
                            System.identityHashCode(entry) & 0xFFFF, entry.text, entry.age, renderAge, partial,
                            depth, sizeAtAge(renderAge),
                            textScale, guiPxH, guiPxH * mc.getWindow().getGuiScale(),
                            guiPxH / height, tanHalfMatrix,
                            (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0D),
                            (float) (Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0D) / tanHalfMatrix),
                            alpha, entry.occluded, sx, sy);
                }
                entry.diagPrevAlpha = alpha;
                entry.diagPrevScale = textScale;
                entry.diagPrevRenderAge = renderAge;
            }

            // Vanilla Font.adjustColor (called by drawInternal on every color it renders)
            // rewrites any color whose alpha is below 4 to fully opaque:
            //     if ((color & 0xFC000000) == 0) color |= 0xFF000000;
            // The fade's last steps - alpha 3, 2, 1, and the 0 produced while a
            // dead-at-next-tick entry waits for onTick - therefore render at FULL
            // brightness for a frame or two right before the word vanishes. That is the
            // single-frame "flash" every monotonic guard failed to catch: the alpha we
            // log is what we HAND to drawString, and the override happens inside it,
            // below anything instrumentation can see. At <= 1.6% opacity the word is
            // indistinguishable from gone, so simply do not draw those frames.
            if (alpha < 4)
                continue;

            final int drawX = -font.width(entry.text) / 2 + 1;
            final int drawY = -font.lineHeight / 2 + 1;

            graphics.pose().pushPose();
            try {
                // Deliberately NOT snapped to whole GUI pixels.
                //
                // Pixel snapping was added while chasing the "flashes bright for one frame" report,
                // on the theory that sub-pixel origins changed the glyph rasteriser's coverage from
                // frame to frame. That theory was wrong: the flash was vanilla Font.adjustColor
                // forcing alpha < 4 to fully opaque (see the guard above). Snapping only cost
                // sub-pixel smoothness - at 300+ fps the origin advances less than a pixel per
                // frame, so rounding made the text sit still and then jump. Do not re-add it
                // unless a real rasterisation artifact is demonstrated.
                graphics.pose().translate(sx, sy, 0.0F);
                graphics.pose().scale(textScale, textScale, 1.0F);
                // The shadow has to fade with the text. It used to be drawn at a fixed opaque
                // 0xFF000000 while the text above it faded, so in the last frames the coloured
                // number had all but gone and what was left was a solid black silhouette that
                // then vanished in one step - the number appeared to change character at the end
                // of its life instead of fading out. 1.12.2 could get away with an opaque shadow
                // because it never faded anything; this port does, so both layers have to.
                graphics.drawString(font, entry.text, drawX + 1, drawY + 1, alpha << 24, false);
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
     * already {@code grow} (128) while the shrink aimed at 1.0 - the number ended visibly smaller
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
                                   final Minecraft mc, final float guiHeight,
                                   final float tanHalfActual) {
        // Only a fallback for the frame where the measurement below is unavailable (the offset
        // point landed behind the camera). The measured value is the one that is actually in use.
        final float tanHalfFov = Float.isNaN(tanHalfActual)
                ? (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0D)
                : tanHalfActual;

        final float pixelsPerWorldUnit = (guiHeight / 2.0F) / (float) (depth * tanHalfFov);

        // Cap the PEAK of the curve, not each frame's value. Clamping frame by frame flattened the
        // top of the animation as soon as the text came close enough to reach the cap - measured,
        // 29% of frames in ATM10 sat pinned at it. That is also why sizePercent looked like it only
        // moved the START of the animation: the peak could not grow any further, so only the ends
        // moved, and the visible size contrast collapsed.
        //
        // Scaling the whole curve by one factor keeps the 4x contrast intact and turns the cap into
        // a "too close to draw this big" limit instead of a distortion of the shape.
        float curveScale = 1.0F;
        final float peakScale = entry.worldUnitsPerFontPx * sizeAtAge(this.peakTick)
                * this.sizeScale * pixelsPerWorldUnit;
        if (peakScale > MAX_TEXT_SCALE)
            curveScale = MAX_TEXT_SCALE / peakScale;

        final float worldUnitsPerFontPx =
                entry.worldUnitsPerFontPx * sizeAtAge(renderAge) * this.sizeScale;
        float scale = worldUnitsPerFontPx * pixelsPerWorldUnit * curveScale;

        return Mth.clamp(scale, MIN_TEXT_SCALE, MAX_TEXT_SCALE);
    }

    /** Guard rails: never microscopic, never absurdly large on screen. */
    private static final float MIN_TEXT_SCALE = 0.10F;
    private static final float MAX_TEXT_SCALE = 6.0F;

    /**
     * Largest the drawn size may grow in ONE frame, as a multiple of what the animation curve itself
     * asks for. The curve is smooth and the depth only moves as fast as the camera does, so anything
     * past this is not the animation - it is the projection matrix having been a different one for
     * that frame. See the rate limit in the render pass. Generous on purpose: at 20 fps the growth
     * phase itself steps about 1.26x per frame, so this must stay above that.
     */
    private static final float MAX_SCALE_STEP_PER_FRAME = 1.35F;

    /**
     * The vertical half-FOV the projection matrix was ACTUALLY built with, recovered by measuring
     * it rather than reading it.
     *
     * <p>One world unit of vertical offset at depth {@code d} covers {@code 1 / (2 * d * tanHalf)}
     * of the viewport, so projecting a known one-block offset with the very same matrix that
     * produced {@code depth} and reading back how many GUI pixels it spans gives {@code tanHalf}
     * directly. Nothing about the projection has to be assumed: a perspective matrix, a modded
     * one, or a non-perspective one all fall out of the same two points.
     *
     * <p>This replaces {@code tan(toRadians(options.fov()) / 2)}. The slider is only the base
     * value; the matrix carries the sprint/speed/nausea terms, {@code fovEffectScale}, and
     * anything a mod did through {@code ComputeFov}. Reading the slider while dividing by a depth
     * from the matrix silently scaled every word by the ratio between the two.
     *
     * @return the measured tan(fov/2), or NaN when the offset point cannot be projected
     */
    private float measureTanHalf(final net.minecraft.client.Camera cam, final Vec3 camPos,
                                 final double px, final double py, final double pz,
                                 final float depth, final float guiHeight) {
        final var up = cam.getUpVector();
        this.clipUp.set((float) (px - camPos.x) + up.x(), (float) (py - camPos.y) + up.y(),
                (float) (pz - camPos.z) + up.z(), 1.0F);
        this.viewProj.transform(this.clipUp);
        if (this.clipUp.w <= 0.001F)
            return Float.NaN;
        final float pxPerBlock = Math.abs(
                (1.0F - (this.clipUp.y / this.clipUp.w * 0.5F + 0.5F)) * guiHeight
                        - (1.0F - (this.clip.y / depth * 0.5F + 0.5F)) * guiHeight);
        if (pxPerBlock < 1.0E-4F)
            return Float.NaN;
        return (guiHeight / 2.0F) / (depth * pxPerBlock);
    }

    /**
     * Entity occlusion: true when some entity's bounding box lies on the camera-to-word segment.
     * The word's owner and the local player are excluded - the owner would otherwise hide its own
     * word, and in first person the camera sits inside the player's own box. Invisible and removed
     * entities don't render, so they cannot block (1.12.2 got the same behaviour for free from the
     * depth buffer). The cheap AABB distance check skips anything whose nearest point is already
     * farther than the word itself.
     */
    private static boolean isEntityBlocking(Minecraft mc, Vec3 camPos, Vec3 target, int ownerId) {
        final double sqDist = camPos.distanceToSqr(target);
        for (final var entity : mc.level.entitiesForRendering()) {
            if (entity.getId() == ownerId || entity == mc.player || entity.isInvisible() || entity.isRemoved())
                continue;
            if (entity.getBoundingBox().distanceToSqr(camPos) >= sqDist)
                continue;
            if (entity.getBoundingBox().clip(camPos, target).isPresent())
                return true;
        }
        return false;
    }

    /** Spawn the damage number and, when the actual loss qualifies, a crit word above the entity. */
    private void spawnDamageWords(LivingEntity living, int delta, boolean showNumbers, boolean showCrits) {
        refreshAnimationSettings();
        // Damage number above the entity (original used the top + 0.5).
        if (showNumbers) {
            this.active.add(new CritWord(String.valueOf(delta), DAMAGE_TEXT_COLOR,
                    living.getX(), living.getY() + living.getBbHeight() + 0.5D, living.getZ(),
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living), living.getId()));
        }
        // Critical hit: the actual health loss reached 40% of max health (1.12.2's threshold) -
        // an extra comic word one block up.
        if (showCrits && delta >= (int) (living.getMaxHealth() / 2.5F)) {
            final String word = pickWord() + "!";
            this.active.add(new CritWord(word, CRITICAL_TEXT_COLOR,
                    living.getX(), living.getY() + living.getBbHeight() + 1.0D, living.getZ(),
                    launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), CRIT_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living), living.getId()));
            this.logger.debug("Crit word [%s] at %s", word, living.blockPosition());
        }
    }

    private void spawnHealWord(LivingEntity living, int amount) {
        refreshAnimationSettings();
        this.active.add(new CritWord(String.valueOf(amount), HEAL_TEXT_COLOR,
                living.getX(), living.getY() + living.getBbHeight() + 0.5D, living.getZ(),
                launchX(0.0D, 0.0D), launchY(), launchZ(0.0D, 0.0D), ADDITION_WORLD_UNITS_PER_FONT_PX, isLocalPlayer(living), living.getId()));
    }

    private void onTick(Minecraft mc) {
        // Track health of nearby living entities: the client already knows every nearby entity's
        // health (entity metadata), so the polled difference is the ACTUAL health change - after
        // armor, resistance and absorption - which is exactly what 1.12.2 displayed. Works the
        // same in single- and multiplayer with no server-side component, and keeps every mutation
        // of `active` on the client thread (the old event handlers ran on the server thread in
        // singleplayer, racing the render thread).
        if (mc.level != null && mc.player != null) {
            final double range = 48.0D;
            final boolean showNumbers = this.config.entityEffects.showDamageNumbers;
            final boolean showCrits = this.config.entityEffects.showCritWords;
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living))
                    continue;
                if (living.isRemoved() || !living.isAlive())
                    continue;
                if (living.distanceToSqr(mc.player) > range * range)
                    continue;

                final int id = entity.getId();
                final float current = living.getHealth();
                final Float previous = this.lastHealth.put(id, current);
                if (previous == null)
                    continue;
                final float change = current - previous;
                if (change == 0.0F || (!showNumbers && !showCrits) || isOwnTextInFirstPerson(living))
                    continue;

                if (change < 0.0F) {
                    // Damage: actual health lost, rounded up like the original (min 1).
                    spawnDamageWords(living, Math.max(1, Mth.ceil(-change)), showNumbers, showCrits);
                } else if (showNumbers) {
                    // Heal: actual health restored, rounded up like the original (min 1).
                    spawnHealWord(living, Math.max(1, Mth.ceil(change)));
                }
            }
            // Prune entities that left the range, were removed, or died. A tracked entity whose
            // health is now zero died since the last sample: show the killing blow - its last
            // sampled health is exactly the final loss, the same delta 1.12.2's poll saw. Entities
            // that vanished without a corpse (despawn, chunk unload) are dropped silently.
            this.lastHealth.keySet().removeIf(id -> {
                var e = mc.level.getEntity(id);
                if (e instanceof LivingEntity living) {
                    if (living.isAlive() && !living.isRemoved()
                            && living.distanceToSqr(mc.player) <= range * range)
                        return false;
                    if (living.getHealth() <= 0.0F && (showNumbers || showCrits)
                            && !isOwnTextInFirstPerson(living)) {
                        final float loss = this.lastHealth.getOrDefault(id, 0.0F);
                        if (loss > 0.0F)
                            spawnDamageWords(living, Math.max(1, Mth.ceil(loss)), showNumbers, showCrits);
                    }
                }
                return true;
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
            final boolean dead = ++w.age >= this.lifetime;
            if (dead && this.logger.isTracing(Configuration.Flags.CRIT_WORD))
                // One line per WORD, not per frame. Every earlier attempt instrumented frames, and
                // log4j drops lines when the volume is high, so the rarer the event the more likely
                // it was to be the one dropped. A per-word summary cannot be dropped, and it audits
                // the whole life: whether the alpha ever went up, whether the size ever jumped past
                // what the curve allows, and how many frames the word was actually drawn for.
                this.logger.debug("[CRITWORD-LIFE] id=%x text=%s frames=%d hidden=%d alphaUp=%d "
                                + "maxScaleRatio=%.3f  (alphaUp>0 or maxScaleRatio>%.2f is the flash)",
                        System.identityHashCode(w) & 0xFFFF, w.text, w.auditFrames, w.auditHidden,
                        w.auditMaxAlphaUp, w.auditMaxScaleRatio, MAX_SCALE_STEP_PER_FRAME);
            return dead;
        });
    }
}
