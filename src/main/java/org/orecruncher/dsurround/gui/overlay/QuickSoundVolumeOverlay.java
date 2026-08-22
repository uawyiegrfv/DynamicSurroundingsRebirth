package org.orecruncher.dsurround.gui.overlay;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.config.IndividualSoundConfigEntry;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.gui.keyboard.KeyBindings;
import org.orecruncher.dsurround.gui.sound.ConfigSoundInstance;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.sound.IAudioPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Quick per-sound volume overlay: hold Ctrl+` to pop a small panel at the
 * bottom-left (mirroring the vanilla subtitle rows on the right) listing the
 * sounds that were on the subtitle display at the moment the hotkey was
 * pressed. The list is frozen while the overlay is open.
 *
 * <p>Interaction is deliberately NOT a Screen so WASD and mouse look keep
 * working: visibility is polled from the key mapping each tick, arrow keys
 * are polled with edge detection, and the mouse wheel is consumed through
 * the cancellable NeoForge {@link InputEvent.MouseScrollingEvent} (1% per
 * notch, also cancels the hotbar slot scroll). Volume edits go straight into
 * the live {@link IndividualSoundConfigEntry} objects - playback reads the
 * scale on every sound play, so the change is audible immediately - and are
 * persisted through {@link ISoundLibrary#saveIndividualSoundConfigs} with a
 * short debounce plus a final save when the keys are released.
 */
public class QuickSoundVolumeOverlay implements SoundEventListener {

    private static final ISoundLibrary SOUND_LIBRARY = ContainerManager.resolve(ISoundLibrary.class);
    private static final IAudioPlayer AUDIO_PLAYER = ContainerManager.resolve(IAudioPlayer.class);

    private static final int MAX_ENTRIES = 8;
    private static final int MAX_TRACKED = 32;
    // Vanilla subtitles linger 3000ms x notificationDisplayTime; mirror that
    // window so the snapshot matches what is (or just was) on screen.
    private static final long LINGER_BASE_MS = 3000L;
    private static final long PREVIEW_THROTTLE_MS = 150L;
    private static final long SAVE_DEBOUNCE_MS = 750L;
    // Held left/right acceleration: nothing for the first 400ms (single nudge
    // on press), then the step ramps 1% -> 5% over the next 2.4s of holding.
    private static final long ACCEL_DELAY_MS = 400L;
    private static final long ACCEL_RAMP_MS = 600L;

    // Panel geometry: rows of 12px growing upward from guiHeight - 35, the
    // same anchor height the vanilla subtitle overlay uses on the right side.
    private static final int ROW_HEIGHT = 12;
    private static final int PANEL_WIDTH = 170;
    private static final int BOTTOM_MARGIN = 35;
    private static final int PADDING = 3;
    // The slider stretches to fill whatever the name leaves free, but never
    // below this; the percentage label keeps a reserved strip on the right.
    private static final int MIN_SLIDER_WIDTH = 48;
    private static final int PERCENT_WIDTH = 32;

    private static final int COLOR_BG = 0xD0000000;
    private static final int COLOR_BG_SELECTED = 0xD0303030;
    // ARGB: the alpha byte is mandatory - a bare 0xRRGGBB renders fully transparent.
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFFFFAA00;

    /** One recently heard subtitled sound; mirrors vanilla SubtitleOverlay.Subtitle semantics. */
    private static final class RecentSound {
        final Identifier id;
        Component text;
        final float range;
        final List<PlayedAt> playedAt = new ArrayList<>();

        RecentSound(final Identifier id, final Component text, final float range, final Vec3 location) {
            this.id = id;
            this.text = text;
            this.range = range;
            this.playedAt.add(new PlayedAt(location, Util.getMillis()));
        }

        void refresh(final Vec3 location) {
            this.playedAt.removeIf(p -> p.location.equals(location));
            this.playedAt.add(new PlayedAt(location, Util.getMillis()));
        }

        void purgeOlderThan(final double maxAgeMs) {
            final long now = Util.getMillis();
            this.playedAt.removeIf(p -> now - p.time > maxAgeMs);
        }

        boolean isAudibleFrom(final Vec3 camera) {
            if (Float.isInfinite(this.range))
                return !this.playedAt.isEmpty();
            return this.playedAt.stream().anyMatch(p -> camera.closerThan(p.location, this.range));
        }
    }

    private record PlayedAt(Vec3 location, long time) {
    }

    private final Map<Identifier, RecentSound> recent = new Object2ObjectOpenHashMap<>();
    // Frozen while open: pairs of (config entry, display name).
    private final List<IndividualSoundConfigEntry> entries = new ArrayList<>();
    private final List<Component> names = new ArrayList<>();

    private boolean visible;
    private int selection;
    private boolean dirty;
    private long lastSaveAt;
    private long lastPreviewAt;
    private boolean listenerRegistered;
    private boolean prevUp;
    private boolean prevDown;
    // Left/right nudge the selected volume; holding accelerates.
    private boolean prevLeft;
    private boolean prevRight;
    private long leftHeldSince;
    private long rightHeldSince;

    public QuickSoundVolumeOverlay() {
        org.orecruncher.dsurround.eventing.ClientState.TICK_END.register(this::tick);
        org.orecruncher.dsurround.eventing.ClientState.ON_CONNECT.register(this::onConnect);
        NeoForge.EVENT_BUS.addListener(this::onScroll);
    }

    private void onConnect(final Minecraft client) {
        // The SoundManager lives for the whole client session; registering once
        // is enough, and unlike the vanilla subtitle overlay we do not gate on
        // the showSubtitles option (the feature is useful with them off too).
        if (!this.listenerRegistered) {
            final SoundManager soundManager = client.getSoundManager();
            soundManager.addListener(this);
            this.listenerRegistered = true;
        }
        this.recent.clear();
    }

    @Override
    public void onPlaySound(final SoundInstance sound, final WeighedSoundEvents soundEvent, final float range) {
        // Mirror the vanilla subtitle overlay: only sounds WITH a subtitle ever
        // reach the display, and repeats refresh the same entry.
        final Component subtitle = soundEvent.getSubtitle();
        if (subtitle == null)
            return;
        final Identifier id = sound.getIdentifier();
        if (id == null)
            return;
        final Vec3 location = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        final RecentSound existing = this.recent.get(id);
        if (existing != null) {
            existing.text = subtitle;
            existing.refresh(location);
        } else {
            if (this.recent.size() >= MAX_TRACKED)
                this.recent.values().stream().min(Comparator.comparingLong(r -> r.playedAt.isEmpty() ? 0L : r.playedAt.getLast().time)).ifPresent(r -> this.recent.remove(r.id));
            this.recent.put(id, new RecentSound(id, subtitle, range, location));
        }
    }

    private void tick(final Minecraft client) {
        // Key mappings only update outside of screens, so the overlay naturally
        // hides whenever a GUI/chat takes focus.
        final boolean want = client.player != null && client.screen == null
                && KeyBindings.quickSoundVolume.isDown()
                && isCtrlDown();

        if (want && !this.visible)
            this.openSnapshot(client);
        else if (!want && this.visible)
            this.close();

        if (!this.visible)
            return;

        // Arrow selection with edge detection: polling keeps the overlay
        // input-capture free; arrows have no default gameplay binding.
        final var window = client.getWindow();
        final boolean up = InputConstants.isKeyDown(window, InputConstants.KEY_UP);
        final boolean down = InputConstants.isKeyDown(window, InputConstants.KEY_DOWN);
        if (up && !this.prevUp)
            this.moveSelection(-1);
        if (down && !this.prevDown)
            this.moveSelection(1);
        this.prevUp = up;
        this.prevDown = down;

        final long now = Util.getMillis();
        final boolean left = InputConstants.isKeyDown(window, InputConstants.KEY_LEFT);
        final boolean right = InputConstants.isKeyDown(window, InputConstants.KEY_RIGHT);
        this.leftHeldSince = this.tickVolumeKey(left, this.prevLeft, this.leftHeldSince, now, -1);
        this.rightHeldSince = this.tickVolumeKey(right, this.prevRight, this.rightHeldSince, now, 1);
        this.prevLeft = left;
        this.prevRight = right;

        if (this.dirty && now - this.lastSaveAt > SAVE_DEBOUNCE_MS)
            this.save();
    }

    /**
     * One nudge on the initial press (-/+1%), then after a short delay the
     * hold accelerates: the per-tick step ramps from 1% up to 5% (about one
     * full 0-400% sweep per second at the cap).
     *
     * @return the updated held-since timestamp for this key
     */
    private long tickVolumeKey(final boolean down, final boolean wasDown, final long heldSince, final long now, final int direction) {
        if (!down)
            return 0L;
        if (!wasDown) {
            if (heldSince == 0L)
                this.adjustSelected(direction);
            return heldSince == 0L ? now : heldSince;
        }
        if (heldSince == 0L)
            return 0L;
        final long held = now - heldSince;
        if (held > ACCEL_DELAY_MS) {
            final int step = 1 + (int) Math.min(4L, (held - ACCEL_DELAY_MS) / ACCEL_RAMP_MS);
            this.adjustSelected(direction * step);
        }
        return heldSince;
    }

    private static boolean isCtrlDown() {
        final var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private void openSnapshot(final Minecraft client) {
        this.entries.clear();
        this.names.clear();
        this.selection = 0;
        this.visible = true;
        this.dirty = false;

        // Replicate the vanilla subtitle display's own filtering so the panel
        // lists exactly what is on the subtitle overlay right now: entries
        // linger for 3000ms x notificationDisplayTime and only count while
        // audible from the listener (same rule SubtitleOverlay.Subtitle uses).
        final long linger = (long) (LINGER_BASE_MS * client.options.notificationDisplayTime().get());
        final Vec3 camera = client.getSoundManager().getListenerTransform().position();
        this.recent.values().stream()
                .peek(r -> r.purgeOlderThan(linger))
                .filter(r -> !r.playedAt.isEmpty() && r.isAudibleFrom(camera))
                .sorted(Comparator.comparingLong((RecentSound r) -> r.playedAt.getLast().time).reversed())
                .limit(MAX_ENTRIES)
                .forEach(r -> {
                    this.entries.add(findOrCreateEntry(r.id));
                    this.names.add(r.text);
                });

        // Audition the initially selected sound so a single-entry snapshot can
        // be previewed too (arrow-change previews alone would never fire).
        this.preview();
    }

    /** Existing config entries are shared instances with the library map, so mutating them is audible immediately. */
    private static IndividualSoundConfigEntry findOrCreateEntry(final Identifier id) {
        return SOUND_LIBRARY.getIndividualSoundConfigs().stream()
                .filter(e -> e.soundEventId.equals(id))
                .findFirst()
                .orElseGet(() -> new IndividualSoundConfigEntry(id));
    }

    private void close() {
        this.visible = false;
        if (this.dirty)
            this.save();
    }

    private void save() {
        // Re-saving the live collection rebuilds the library's lookup map
        // (picking up brand-new entries) and writes soundconfig.json.
        SOUND_LIBRARY.saveIndividualSoundConfigs(SOUND_LIBRARY.getIndividualSoundConfigs());
        this.dirty = false;
        this.lastSaveAt = Util.getMillis();
    }

    private void moveSelection(final int delta) {
        if (this.entries.isEmpty())
            return;
        this.selection = Mth.positiveModulo(this.selection + delta, this.entries.size());
        this.preview();
    }

    private void adjustSelected(final int percentPerNotch) {
        if (this.entries.isEmpty())
            return;
        final IndividualSoundConfigEntry entry = this.entries.get(this.selection);
        entry.volumeScale = Mth.clamp(entry.volumeScale + percentPerNotch, 0, 400);
        // A brand-new entry (sound never configured before) is not part of the
        // library collection yet - add it so the debounced save keeps it.
        final boolean known = SOUND_LIBRARY.getIndividualSoundConfigs().stream().anyMatch(e -> e == entry);
        if (!known)
            SOUND_LIBRARY.getIndividualSoundConfigs().add(entry);
        this.dirty = true;
    }

    private void preview() {
        if (this.entries.isEmpty())
            return;
        final long now = Util.getMillis();
        if (now - this.lastPreviewAt < PREVIEW_THROTTLE_MS)
            return;
        this.lastPreviewAt = now;

        final IndividualSoundConfigEntry entry = this.entries.get(this.selection);
        final var metadata = SOUND_LIBRARY.getSoundMetadata(entry.soundEventId);
        final SoundSource category = metadata != null ? metadata.getCategory() : SoundSource.MASTER;
        // ConfigSoundInstance is exempt from category/block scaling in the
        // pipeline, matching the config screen's audition behavior.
        AUDIO_PLAYER.play(ConfigSoundInstance.create(entry.soundEventId, category, () -> entry.volumeScale / 100F));
    }

    private void onScroll(final InputEvent.MouseScrollingEvent event) {
        if (!this.visible)
            return;
        final double delta = event.getScrollDeltaY() != 0 ? event.getScrollDeltaY() : event.getScrollDeltaX();
        if (delta == 0)
            return;
        this.adjustSelected((int) Math.signum(delta));
        // Consume the scroll: the wheel adjusts volume instead of switching
        // the hotbar slot while the overlay is up.
        event.setCanceled(true);
    }

    /**
     * GUI layer callback (registered below all vanilla layers). Purely visual -
     * input is handled in {@link #tick} and {@link #onScroll}.
     */
    public void render(final GuiGraphicsExtractor graphics, final DeltaTracker tracker) {
        if (!this.visible)
            return;

        final Minecraft client = Minecraft.getInstance();
        final var font = client.font;
        final int screenH = graphics.guiHeight();

        final int rows = this.entries.size();
        final int emptyRows = rows == 0 ? 1 : 0;
        // Header (title + hint) + entry rows, growing upward from the subtitle line.
        final int panelHeight = 2 * ROW_HEIGHT + (rows + emptyRows) * ROW_HEIGHT + 2 * PADDING;
        final int panelX = 2;
        final int panelY = screenH - BOTTOM_MARGIN - panelHeight;

        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, COLOR_BG);

        int y = panelY + PADDING;
        graphics.text(font, Component.translatable("dsurround.text.quickvolume.title"), panelX + PADDING, y, COLOR_TEXT, false);
        y += ROW_HEIGHT;
        graphics.text(font, Component.translatable("dsurround.text.quickvolume.hint"), panelX + PADDING, y, COLOR_DIM, false);
        y += ROW_HEIGHT;

        if (rows == 0) {
            graphics.text(font, Component.translatable("dsurround.text.quickvolume.empty"), panelX + PADDING, y, COLOR_DIM, false);
            return;
        }

        // Layout per row: marker | name (only as wide as needed) | slider
        // (fills the rest) | percentage. A long name is trimmed so the slider
        // always keeps its minimum width instead of pushing it around.
        final int nameX = panelX + PADDING + 6;
        final int percentX = panelX + PANEL_WIDTH - PADDING - PERCENT_WIDTH + 2;
        final int maxNameWidth = percentX - nameX - MIN_SLIDER_WIDTH - 4;

        for (int i = 0; i < rows; i++) {
            final boolean selected = i == this.selection;
            if (selected)
                graphics.fill(panelX + 1, y - 1, panelX + PANEL_WIDTH - 1, y + ROW_HEIGHT - 1, COLOR_BG_SELECTED);

            // Selection marker
            graphics.text(font, selected ? ">" : " ", panelX + PADDING, y, COLOR_ACCENT, false);

            // Name, trimmed to fit
            final Component name = this.names.get(i);
            final String trimmed = font.plainSubstrByWidth(name.getString(), maxNameWidth);
            final int nameWidth = font.width(trimmed);
            graphics.text(font, trimmed, nameX, y, COLOR_TEXT, false);

            final IndividualSoundConfigEntry entry = this.entries.get(i);
            // Slider starts right after the name and stretches to the percentage.
            final int sliderX = nameX + nameWidth + 4;
            final int sliderW = Math.max(MIN_SLIDER_WIDTH, percentX - 3 - sliderX);
            final int sliderY = y + ROW_HEIGHT / 2 - 2;

            // Mini slider track + fill + knob (visual parity with the options slider)
            graphics.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 4, 0xFF000000);
            final int fill = (int) ((sliderW - 2) * Mth.clamp(entry.volumeScale / 400F, 0F, 1F));
            graphics.fill(sliderX + 1, sliderY + 1, sliderX + 1 + Math.max(fill, entry.volumeScale > 0 ? 1 : 0), sliderY + 3, selected ? 0xFF808080 : 0xFF606060);
            graphics.fill(sliderX + 1 + fill, sliderY - 1, sliderX + 4 + fill, sliderY + 5, COLOR_TEXT);

            // Percentage (or "off" at zero, gold above 100)
            final String label = entry.volumeScale == 0
                    ? Component.translatable("options.off").getString()
                    : entry.volumeScale + "%";
            final int color = entry.volumeScale == 0 ? COLOR_DIM : entry.volumeScale > 100 ? COLOR_ACCENT : COLOR_TEXT;
            graphics.text(font, label, panelX + PANEL_WIDTH - PADDING - font.width(label), y, color, false);

            y += ROW_HEIGHT;
        }
    }
}
