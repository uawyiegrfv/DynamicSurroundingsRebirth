package org.orecruncher.dsurround.lib.gui;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@SuppressWarnings("unused")
public class WarmToast implements Toast {
    private static final Profile DEFAULT_PROFILE = new Profile(ResourceLocation.withDefaultNamespace("toast/advancement"), 5000, ColorPalette.GOLD, ColorPalette.WHITE);

    // 1.20.1: vanilla 1.21 renders toasts from the GUI sprite atlas ("toast/advancement").
    // 1.20.1 has no such atlas; the advancement toast background is a strip inside
    // minecraft:textures/gui/toasts.png (256x256, strip at u 0..160 / v 0..32).
    private static final ResourceLocation TOASTS_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/toasts.png");

    private static final int MAX_LINE_SIZE = 200;
    private static final int MIN_LINE_SIZE = 100;
    private static final int LINE_SPACING = 12;
    private static final int MARGIN = 10;

    private final Profile profile;

    private Component title;
    private List<FormattedCharSequence> messageLines;
    private long lastChanged;
    private boolean changed;
    private final int width;
    private Toast.Visibility wantedVisibility = Toast.Visibility.SHOW;

    public static WarmToast multiline(Minecraft minecraft, Component title, Component body) {
        return multiline(minecraft, DEFAULT_PROFILE, title, body);
    }

    public static WarmToast multiline(Minecraft minecraft, Profile profile, Component title, Component body) {
        var font = minecraft.font;
        var list = font.split(body, MAX_LINE_SIZE);
        var titleSize = Math.min(MAX_LINE_SIZE, Math.max(MIN_LINE_SIZE, font.width(title)));
        var lineSize = list.stream().mapToInt(font::width).max().orElse(MIN_LINE_SIZE);
        int width = Math.max(titleSize, lineSize) + MARGIN * 3;
        return new WarmToast(profile, title, list, width);
    }

    private WarmToast(Profile profile, Component title, List<FormattedCharSequence> body, int width) {
        this.profile = profile;
        this.title = title;
        this.messageLines = body;
        this.width = width;
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return MARGIN * 2 + Math.max(this.messageLines.size(), 1) * LINE_SPACING;
    }

    public void reset(Component component, @Nullable Component component2) {
        this.title = component;
        this.messageLines = nullToEmpty(component2);
        this.changed = true;
    }

    @Override
    public Toast.Visibility render(@NotNull GuiGraphics graphics, @NotNull ToastComponent manager, long lastChanged) {
        // 1.20.1: Toast.update()/extractRenderState() do not exist - merged into render().
        if (this.changed) {
            this.lastChanged = lastChanged;
            this.changed = false;
        }

        double displayTime = (double) this.profile.displayTime * manager.getNotificationDisplayTimeMultiplier();
        this.wantedVisibility = (double) (lastChanged - this.lastChanged) < displayTime ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;

        if (this.wantedVisibility == Toast.Visibility.HIDE)
            return this.wantedVisibility;

        Font font = manager.getMinecraft().font;
        int i = this.width();
        if (i == 160 && this.messageLines.size() <= 1) {
            // 1.20.1: the 1.21 sprite "toast/advancement" does not exist as a texture file;
            // draw the advancement strip of vanilla textures/gui/toasts.png (256x256).
            graphics.blit(TOASTS_TEXTURE, 0, 0, 0, 0, i, this.height());
        } else {
            int renderHeight = this.height();
            int lineRenderCount = Math.min(4, renderHeight - 28);
            this.renderBackgroundRow(graphics, i, 0, 0, 28);

            for (int n = 28; n < renderHeight - lineRenderCount; n += 10) {
                this.renderBackgroundRow(graphics, i, 16, n, Math.min(16, renderHeight - n - lineRenderCount));
            }

            this.renderBackgroundRow(graphics, i, 32 - lineRenderCount, renderHeight - lineRenderCount, lineRenderCount);
        }

        if (this.messageLines.isEmpty()) {
            graphics.drawString(font, this.title, 18, LINE_SPACING, this.profile.titleColor.getValue(), false);
        } else {
            graphics.drawString(font, this.title, 18, 7, this.profile.titleColor.getValue(), false);

            for (int j = 0; j < this.messageLines.size(); ++j) {
                graphics.drawString(font, this.messageLines.get(j), 18, 18 + j * LINE_SPACING, this.profile.bodyColor.getValue(), false);
            }
        }

        return this.wantedVisibility;
    }

    private void renderBackgroundRow(GuiGraphics graphics, int i, int j, int k, int l) {
        // 1.20.1: same 9-slice layout as vanilla SystemToast.renderBackgroundRow, but the
        // advancement toast strip of toasts.png lives at v 0..32 (vanilla SystemToast uses
        // v 64..96), so the v offset is j directly.
        int m = j == 0 ? 20 : 5;
        int n = Math.min(60, i - m);
        graphics.blit(TOASTS_TEXTURE, 0, k, 0, j, m, l);

        for (int o = m; o < i - n; o += 64) {
            graphics.blit(TOASTS_TEXTURE, o, k, 32, j, Math.min(64, i - o - n), l);
        }

        graphics.blit(TOASTS_TEXTURE, i - n, k, 160 - n, j, n, l);
    }

    private static ImmutableList<FormattedCharSequence> nullToEmpty(@Nullable Component component) {
        return component == null ? ImmutableList.of() : ImmutableList.of(component.getVisualOrderText());
    }

    public record Profile(ResourceLocation sprite, int displayTime, TextColor titleColor, TextColor bodyColor) {

    }
}
