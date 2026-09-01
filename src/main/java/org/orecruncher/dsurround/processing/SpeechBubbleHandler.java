package org.orecruncher.dsurround.processing;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * Speech bubbles, ported back from the original 1.12.2 mod (the 26.1 rewrite dropped
 * the feature). Pure client-side: no capabilities and no network packets. Two
 * independent features, both off by default (1.12.2 defaults):
 *
 * - Player chat bubbles: a chat message received on the client is shown in a bubble
 *   above the sender's head (ClientChatReceivedEvent; 1.12.2 used a server packet but
 *   the visible result is the same, including the local player seeing their own bubble).
 * - Entity chat: villagers/zombies/skeletons/witches/squids "speak" lines from weighted
 *   tables (chat/&lt;lang&gt;.lang resources) at random intervals, with villagers switching
 *   to their flee lines when a hostile mob is nearby.
 *
 * Rendering is the 1.12.2 SpeechDataRenderer style: a flat translucent black rectangle
 * (no rounded corners) with centered gold text (no depth copy), 9px line spacing, positioned 0.25 above
 * the entity's head, projected to the screen like CritWordHandler.
 */
public class SpeechBubbleHandler {

    // 1.12.2 EntityChatData defaults and per-entity timer overrides (setTimers calls).
    private static final int DEFAULT_CHAT_INTERVAL = 400;
    private static final int DEFAULT_CHAT_RANDOM = 1200;
    private static final Map<String, int[]> CHAT_TIMERS = Map.of(
            "squid", new int[] { 600, DEFAULT_CHAT_RANDOM },
            "villager.flee", new int[] { 250, 200 });

    // 1.12.2 RenderContext / SpeechBubbleData layout constants.
    private static final int MIN_TEXT_WIDTH = 60;
    private static final int MAX_TEXT_WIDTH = 180;
    private static final double BUBBLE_MARGIN = 4.0D;
    private static final int LINE_HEIGHT = 9;
    private static final int MAX_BUBBLES_PER_ENTITY = 4;

    // 1.12.2 SpeechDataRenderer colors: black 50% background, gold text. The 1.12.2
    // gray "depth" copy underneath was dropped on request (read as a white layer).
    private static final int BG_COLOR = 0x80000000;
    private static final int FG_COLOR = 0xFCFFAA00;

    // Squid messages use this token to display a random vanilla splash text
    // (1.12.2 EntityChatEffect SPLASH_TOKEN).
    private static final String SPLASH_TOKEN = "$MINECRAFT$";

    // 1.12.2 VillagerChatEffect threat scan: hostile within 8 blocks that the villager
    // can see (the AvoidEntity AI set, since we are client-only and have no server
    // fleeing capability).
    private static final double VILLAGER_THREAT_RANGE_SQ = 64.0D;

    private static final class ChatLine {
        final int weight;
        final String text;

        ChatLine(int weight, String text) {
            this.weight = weight;
            this.text = text;
        }
    }

    private static final class ChatTable {
        int base = DEFAULT_CHAT_INTERVAL;
        int rand = DEFAULT_CHAT_RANDOM;
        final List<ChatLine> lines = new ArrayList<>();
        int totalWeight;

        boolean isEmpty() {
            return this.lines.isEmpty();
        }
    }

    private record Bubble(List<String> lines, long expireTick) {}

    private static final class EntityChatState {
        long nextChatTick;
        boolean fleeing;
    }

    private final Configuration config;
    private final IRandomizer random = Randomizer.current();

    private long tick;
    private String loadedLanguage;
    private final Map<String, ChatTable> chatTables = new HashMap<>();
    private final List<String> splashTexts = new ArrayList<>();

    private final Map<UUID, List<Bubble>> playerBubbles = new HashMap<>();
    private final Map<Integer, List<Bubble>> entityBubbles = new HashMap<>();
    private final Map<Integer, EntityChatState> entityChatStates = new HashMap<>();

    // View/projection captured at the level render stage (see CritWordHandler).
    private static final Matrix4f CAPTURED_VIEW_PROJ = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final Vector4f clip = new Vector4f();

    public SpeechBubbleHandler(Configuration config) {
        this.config = config;
        MinecraftForge.EVENT_BUS.addListener(this::onClientChat);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderStage);
        ClientState.TICK_END.register(this::onTick);
    }

    private void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;
        CAPTURED_VIEW_PROJ.set(event.getProjectionMatrix());
        CAPTURED_VIEW_PROJ.mul(event.getPoseStack().last().pose());
    }

    private void onClientChat(ClientChatReceivedEvent event) {
        if (!this.config.speechBubbles.enableSpeechBubbles || event.isSystem())
            return;
        final UUID sender = event.getSender();
        if (sender == null)
            return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        var player = mc.level.getPlayerByUUID(sender);
        if (player == null)
            return;
        // 1.12.2 filtered the range server side; here the local player always sees
        // their own bubble, others only within the configured range.
        final int range = this.config.speechBubbles.speechBubbleRange;
        if (player != mc.player && player.distanceToSqr(mc.player) > (double) range * range)
            return;
        // The chat arrives decorated as "<Name> message" (vanilla chat.type.text format
        // in every language); the bubble shows only the message body.
        final String text = event.getMessage().getString().replaceFirst("^<[^>]*>\\s*", "");
        this.addBubble(this.playerBubbles.computeIfAbsent(sender, k -> new ArrayList<>()),
                text, this.bubbleExpiry());
    }

    private void onTick(Minecraft mc) {
        this.tick++;
        if (mc.level == null || mc.player == null) {
            this.clearAll();
            return;
        }

        this.playerBubbles.values().removeIf(bubbles -> {
            bubbles.removeIf(b -> b.expireTick() <= this.tick);
            return bubbles.isEmpty();
        });

        if (this.config.speechBubbles.enableEntityChat) {
            this.loadChatData(mc);
            this.entityChatScan(mc);
        } else if (!this.entityBubbles.isEmpty() || !this.entityChatStates.isEmpty()) {
            this.entityBubbles.clear();
            this.entityChatStates.clear();
        }
    }

    private void clearAll() {
        this.playerBubbles.clear();
        this.entityBubbles.clear();
        this.entityChatStates.clear();
    }

    private long bubbleExpiry() {
        return this.tick + (int) (this.config.speechBubbles.speechBubbleDuration * 20D);
    }

    private void addBubble(List<Bubble> bubbles, String text, long expiry) {
        if (text == null || text.isBlank())
            return;
        var font = Minecraft.getInstance().font;
        // 1.12.2 SpeechBubbleData: strip formatting codes, word wrap to the max width.
        final List<String> lines = wrapText(font, text.replaceAll("(\\u00A7.)", ""));
        if (lines.isEmpty())
            return;
        bubbles.add(new Bubble(lines, expiry));
        while (bubbles.size() > MAX_BUBBLES_PER_ENTITY)
            bubbles.remove(0);
    }

    // ------------------------------------------------------------------
    // Entity chat
    // ------------------------------------------------------------------

    private void entityChatScan(Minecraft mc) {
        final long now = this.tick;
        // Scan every 3rd tick: this is the entity survey only, not playback, so
        // bubble triggers shift by at most 2 ticks (imperceptible for random chatter).
        if (now % 3 != 0)
            return;
        final int range = this.config.speechBubbles.speechBubbleRange;
        final double rangeSq = (double) range * range;

        for (var entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > rangeSq)
                continue;
            if (entity.isRemoved() || !entity.isAlive())
                continue;

            // Villagers have their own effect: child check and flee tables.
            if (entity instanceof Villager villager) {
                if (villager.isBaby())
                    continue;
                this.updateVillagerChat(mc, villager, now);
            } else {
                final String key = chatKey(entity);
                if (key == null)
                    continue;
                final var table = this.chatTables.get(key);
                if (table == null || table.isEmpty())
                    continue;
                this.updateChat(mc, entity.getId(), key, table, now);
            }
        }

        // Prune state for entities that are gone or dead.
        this.entityChatStates.keySet().removeIf(id -> {
            var e = mc.level.getEntity(id);
            return !(e instanceof LivingEntity living) || living.isRemoved() || !living.isAlive();
        });
        this.entityBubbles.keySet().removeIf(id -> {
            var e = mc.level.getEntity(id);
            if (!(e instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                this.entityChatStates.remove(id);
                return true;
            }
            var bubbles = this.entityBubbles.get(id);
            if (bubbles != null) {
                bubbles.removeIf(b -> b.expireTick() <= this.tick);
                if (bubbles.isEmpty()) {
                    this.entityChatStates.remove(id);
                    return true;
                }
            }
            return false;
        });
    }

    private void updateChat(Minecraft mc, int entityId, String key, ChatTable table, long now) {
        final var state = this.entityChatStates.computeIfAbsent(entityId, id -> {
            var s = new EntityChatState();
            s.nextChatTick = now + this.nextChatDelay(table);
            return s;
        });
        if (now < state.nextChatTick)
            return;
        final String message = this.pickMessage(table);
        if (message != null)
            this.addBubble(this.entityBubbles.computeIfAbsent(entityId, id -> new ArrayList<>()),
                    message, this.bubbleExpiry());
        state.nextChatTick = now + this.nextChatDelay(table);
    }

    private void updateVillagerChat(Minecraft mc, Villager villager, long now) {
        final boolean threatened = this.villagerThreatened(mc, villager);
        final var normal = this.chatTables.get("villager");
        final var flee = this.chatTables.get("villager.flee");
        if (normal == null || normal.isEmpty())
            return;

        final var state = this.entityChatStates.computeIfAbsent(villager.getId(), id -> {
            var s = new EntityChatState();
            s.nextChatTick = now + this.nextChatDelay(normal);
            return s;
        });

        if (threatened && flee != null && !flee.isEmpty()) {
            state.fleeing = true;
            if (now >= state.nextChatTick) {
                final String message = this.pickMessage(flee);
                if (message != null)
                    this.addBubble(this.entityBubbles.computeIfAbsent(villager.getId(), id -> new ArrayList<>()),
                            message, this.bubbleExpiry());
                state.nextChatTick = now + this.nextChatDelay(flee);
            }
        } else {
            if (state.fleeing) {
                // Calmed down: reschedule the normal chat, like the 1.12.2 runningScared reset.
                state.fleeing = false;
                state.nextChatTick = now + this.nextChatDelay(normal);
            }
            if (now >= state.nextChatTick) {
                final String message = this.pickMessage(normal);
                if (message != null)
                    this.addBubble(this.entityBubbles.computeIfAbsent(villager.getId(), id -> new ArrayList<>()),
                            message, this.bubbleExpiry());
                state.nextChatTick = now + this.nextChatDelay(normal);
            }
        }
    }

    private long nextChatDelay(ChatTable table) {
        final int rand = Math.max(1, table.rand);
        return table.base + this.random.nextInt(rand);
    }

    private String pickMessage(ChatTable table) {
        if (table.isEmpty() || table.totalWeight <= 0)
            return null;
        int roll = this.random.nextInt(table.totalWeight);
        String text = null;
        for (var line : table.lines) {
            roll -= line.weight;
            if (roll < 0) {
                text = line.text;
                break;
            }
        }
        if (text == null)
            return null;
        if (SPLASH_TOKEN.equals(text) && !this.splashTexts.isEmpty())
            text = this.splashTexts.get(this.random.nextInt(this.splashTexts.size()));
        return text;
    }

    private boolean villagerThreatened(Minecraft mc, Villager villager) {
        for (var e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(villager) > VILLAGER_THREAT_RANGE_SQ)
                continue;
            if (!(e instanceof Monster monster))
                continue;
            // 1.12.2 EntitySelectors.CAN_AI_TARGET + EntitySenses.canSee. The mob classes
            // are the AvoidEntity AI set from the original EntityVillager.
            if (!(monster instanceof Zombie || monster instanceof Evoker || monster instanceof Vex
                    || monster instanceof Vindicator))
                continue;
            if (!monster.isAlive() || monster.isSpectator() || monster.isInvisible())
                continue;
            if (villager.hasLineOfSight(monster))
                return true;
        }
        return false;
    }

    private static String chatKey(Entity entity) {
        if (entity instanceof Player)
            return null;
        return EntityType.getKey(entity.getType()).getPath();
    }

    // ------------------------------------------------------------------
    // Chat table loading (chat/<lang>.lang resources)
    // ------------------------------------------------------------------

    private void loadChatData(Minecraft mc) {
        final String language = mc.getLanguageManager().getSelected();
        if (language.equals(this.loadedLanguage) && !this.chatTables.isEmpty())
            return;
        this.loadedLanguage = language;
        this.chatTables.clear();
        this.splashTexts.clear();

        final var resourceManager = mc.getResourceManager();
        Resource resource = resourceManager
                .getResource(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "chat/" + language + ".lang"))
                .orElse(null);
        if (resource == null)
            resource = resourceManager
                    .getResource(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "chat/en_us.lang"))
                    .orElse(null);
        if (resource != null) {
            try (var reader = new BufferedReader(new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null)
                    this.parseChatLine(line);
            } catch (final Throwable t) {
                // Ignore - the feature simply stays silent.
            }
        }

        // Vanilla splash texts for the squid $MINECRAFT$ token.
        final var splash = resourceManager
                .getResource(ResourceLocation.withDefaultNamespace("texts/splashes.txt"))
                .orElse(null);
        if (splash != null) {
            try (var reader = new BufferedReader(
                    new InputStreamReader(splash.open(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty())
                        this.splashTexts.add(line);
                }
            } catch (final Throwable ignored) {
            }
        }
    }

    // Format (1.12.2 chat/en_us.lang header): chat.<entity>.<index>=weight,text
    private static final Pattern KEY_PATTERN = Pattern.compile("chat\\.([a-zA-Z.]*)\\.[0-9]*$");
    private static final Pattern VALUE_PATTERN = Pattern.compile("^([0-9]*),(.*)");

    private void parseChatLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#"))
            return;
        final int eq = line.indexOf('=');
        if (eq < 0)
            return;
        final var keyMatcher = KEY_PATTERN.matcher(line.substring(0, eq));
        if (!keyMatcher.matches())
            return;
        final var valueMatcher = VALUE_PATTERN.matcher(line.substring(eq + 1));
        if (!valueMatcher.matches())
            return;
        final String key = keyMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
        final var table = this.chatTables.computeIfAbsent(key, k -> {
            var t = new ChatTable();
            final int[] timers = CHAT_TIMERS.get(k);
            if (timers != null) {
                t.base = timers[0];
                t.rand = timers[1];
            }
            return t;
        });
        try {
            final int weight = Integer.parseInt(valueMatcher.group(1));
            table.lines.add(new ChatLine(weight, valueMatcher.group(2)));
            table.totalWeight += weight;
        } catch (final NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Greedy word wrap at MAX_TEXT_WIDTH. Breaks character-wise when a single word
     * (or CJK run without spaces) exceeds the width, so Chinese lines wrap correctly.
     */
    private static List<String> wrapText(Font font, String text) {
        final List<String> out = new ArrayList<>();
        for (final String paragraph : text.split("\n")) {
            var current = new StringBuilder();
            for (final String word : paragraph.split(" ", -1)) {
                var candidate = current.length() == 0 ? word : current + " " + word;
                if (font.width(candidate) <= MAX_TEXT_WIDTH) {
                    current.append(current.length() == 0 ? word : " " + word);
                    continue;
                }
                if (font.width(word) > MAX_TEXT_WIDTH) {
                    // Hard break the word character by character.
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                    for (int i = 0; i < word.length(); ) {
                        int end = i + 1;
                        while (end < word.length()
                                && font.width(word.substring(i, end + 1)) <= MAX_TEXT_WIDTH)
                            end++;
                        out.add(word.substring(i, end));
                        i = end;
                    }
                    continue;
                }
                out.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
            if (current.length() > 0 || out.isEmpty())
                out.add(current.toString());
        }
        return out;
    }

    public void renderGui(GuiGraphics graphics, float partialTick) {
        if (this.playerBubbles.isEmpty() && this.entityBubbles.isEmpty())
            return;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        final var font = mc.font;
        final float width = mc.getWindow().getGuiScaledWidth();
        final float height = mc.getWindow().getGuiScaledHeight();
        this.viewProj.set(CAPTURED_VIEW_PROJ);
        final var camPos = mc.gameRenderer.getMainCamera().getPosition();

        for (var entry : this.playerBubbles.entrySet()) {
            final var player = mc.level.getPlayerByUUID(entry.getKey());
            if (player == null)
                continue;
            final var lines = this.currentLines(entry.getValue());
            if (!lines.isEmpty())
                this.renderBubble(graphics, mc, font, width, height, camPos, player, lines, partialTick);
        }
        for (var entry : this.entityBubbles.entrySet()) {
            final var entity = mc.level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living))
                continue;
            final var lines = this.currentLines(entry.getValue());
            if (!lines.isEmpty())
                this.renderBubble(graphics, mc, font, width, height, camPos, living, lines, partialTick);
        }
    }

    private static List<String> currentLines(List<Bubble> bubbles) {
        final List<String> lines = new ArrayList<>();
        for (var bubble : bubbles)
            lines.addAll(bubble.lines());
        return lines;
    }

    private void renderBubble(GuiGraphics graphics, Minecraft mc, Font font, float width, float height,
            Vec3 camPos, Entity entity, List<String> lines, float partialTick) {
        // 1.12.2 canBeSeen: invisible entities don't get bubbles, and there must be line
        // of sight (checked with a raycast below, like the name-tag occlusion).
        if (entity.isInvisible() && entity != mc.player)
            return;
        final int range = this.config.speechBubbles.speechBubbleRange;
        if (entity.distanceToSqr(mc.player) > (double) range * range)
            return;

        final Vec3 pos = entity.getPosition(partialTick);
        final double bx = pos.x;
        final double by = pos.y + entity.getBbHeight() + 0.25D;
        final double bz = pos.z;

        this.clip.set((float) (bx - camPos.x), (float) (by - camPos.y), (float) (bz - camPos.z), 1.0F);
        this.viewProj.transform(this.clip);
        if (this.clip.w <= 0.001F || this.clip.w > range)
            return;

        final var hit = mc.level.clip(new ClipContext(
                camPos, new Vec3(bx, by, bz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS)
            return;

        final float depth = this.clip.w;
        final float sx = (this.clip.x / depth * 0.5F + 0.5F) * width;
        final float sy = (1.0F - (this.clip.y / depth * 0.5F + 0.5F)) * height;

        // 1.12.2 billboard scale: 0.015 world units per font pixel. Convert to screen
        // pixels at the projected depth: pixels per world unit = (height/2) / (depth * tan(fov/2)).
        final float fov = mc.options.fov().get();
        final float tanHalfFov = (float) Math.tan(Math.toRadians(fov) / 2D);
        final float scale = Mth.clamp(0.015F * (height / 2F) / (depth * tanHalfFov), 0.15F, 4F);

        int textWidth = MIN_TEXT_WIDTH;
        for (final String line : lines)
            textWidth = Math.max(textWidth, font.width(line));
        final int n = lines.size();
        final int left = -(int) (textWidth / 2D + BUBBLE_MARGIN);
        final int right = (int) (textWidth / 2D + BUBBLE_MARGIN);
        final int top = -(int) (n * LINE_HEIGHT + BUBBLE_MARGIN);
        final int bottom = (int) BUBBLE_MARGIN;

        final var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(sx, sy, 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.fill(left, top, right, bottom, BG_COLOR);
            int linesLeft = n;
            for (final String line : lines) {
                final int offset = -linesLeft * LINE_HEIGHT;
                final int margin = -font.width(line) / 2;
                graphics.drawString(font, line, margin, offset, FG_COLOR, false);
                linesLeft--;
            }
        } finally {
            pose.popPose();
        }
    }

    /** Disconnect / world change cleanup (handler is a singleton). */
    public void onDisconnect() {
        this.clearAll();
        this.loadedLanguage = null;
        this.chatTables.clear();
        this.splashTexts.clear();
    }
}
