package org.orecruncher.dsurround.lib.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.lib.GameUtils;

import java.util.ArrayList;
import java.util.Collection;

public class GuiHelpers {

    private final static String ELLIPSES = "...";

    /**
     * 1.20.1: GuiGraphics has no blitSprite(ResourceLocation) and there is no GUI sprite
     * atlas (both added 1.20.2+). The DS control icons are plain PNGs under
     * textures/gui/sprites/... - blit the texture file directly.
     */
    public static void blitSprite(GuiGraphics graphics, ResourceLocation location, int x, int y, int width, int height) {
        // 1.20.1: GuiGraphics.blit(ResourceLocation,...) resolves via SimpleTexture, which
        // opens the location verbatim - no "textures/" prefix and no ".png" suffix are
        // added (unlike the 1.20.2+ GUI sprite atlas). Map the 1.21-style sprite id
        // "controls/play" onto the full resource path "textures/gui/sprites/controls/play.png".
        var tex = texturePath(location);
        graphics.blit(tex, x, y, 0.0F, 0.0F, width, height, width, height);
    }

    public static void blitSprite(GuiGraphics graphics, ResourceLocation location, int x, int y, int width, int height, int texWidth, int texHeight) {
        // Texture-size-aware variant. Never sample beyond the texture (a 16x16 icon in a
        // 20x20 button would otherwise run u/v past 1.0 and glitch); draw at most the
        // texture size, centred in the widget.
        var tex = texturePath(location);
        int dw = Math.min(width, texWidth);
        int dh = Math.min(height, texHeight);
        int dx = x + (width - dw) / 2;
        int dy = y + (height - dh) / 2;
        graphics.blit(tex, dx, dy, 0.0F, 0.0F, dw, dh, texWidth, texHeight);
    }

    private static ResourceLocation texturePath(ResourceLocation location) {
        return new ResourceLocation(location.getNamespace(), "textures/gui/sprites/" + location.getPath() + ".png");
    }

    /**
     * Gets the text associated with the given language key that is formatted so that a line is <= the width
     * specified.
     *
     * @param key       Translation key for the associated text
     * @param width     Maximum width of a line
     * @param style     The style to apply to each of the resulting split lines
     * @return Collection of Components for the given key
     */
    public static Collection<Component> getTrimmedTextCollection(final String key, final int width, final Style style) {
        var text = Component.translatable(key);
        return getTrimmedTextCollection(text, width, style);
    }

    public static Collection<Component> getTrimmedTextCollection(Component text, int width, Style style) {
        var result = new ArrayList<Component>();
        var textHandler = GameUtils.getTextHandler();
        textHandler.splitLines(text, width, style).forEach(line -> result.add(Component.literal(line.getString()).withStyle(style)));
        return result;
    }

    /**
     * Gets the text associated with the given language key.  Text is truncated to the specified width and an
     * ellipses append if necessary.
     *
     * @param key        Translation key for the associated text
     * @param width      Maximum width of the text in GUI pixels
     * @param formatting Formatting to apply to the text
     * @return FormattedText fitting the criteria specified
     */
    public static FormattedText getTrimmedText(final String key, final int width, @Nullable final ChatFormatting... formatting) {
        var fr = GameUtils.getTextRenderer();
        var cm = GameUtils.getTextHandler();

        final Style style = prefixHelper(formatting);
        final FormattedText text = Component.translatable(key);
        if (fr.width(text) > width) {
            final int ellipsesWidth = fr.width(ELLIPSES);
            final int trueWidth = width - ellipsesWidth;
            final FormattedText str = cm.headByWidth(text, trueWidth, style);
            return Component.literal(str.getString() + ELLIPSES);
        }
        final FormattedText str = cm.headByWidth(text, width, style);
        return Component.literal(str.getString());
    }

    private static Style prefixHelper(@Nullable final ChatFormatting[] formatting) {
        final Style style;
        if (formatting != null && formatting.length > 0)
            style = Style.EMPTY.applyFormats(formatting);
        else
            style = Style.EMPTY;
        return style;
    }
}