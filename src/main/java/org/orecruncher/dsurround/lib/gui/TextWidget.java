package org.orecruncher.dsurround.lib.gui;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class TextWidget extends AbstractStringWidget {

    public TextWidget(int x, int y, int width, int height, Component component, Font font) {
        super(x, y, width, height, component, font);
    }

    @Override
    public void visitLines(@NotNull ActiveTextCollector output) {
        // 26.1: The collector operates in screen space; anchor the label at the widget's
        // position. Text scrolls horizontally when it is wider than the widget.
        int x = this.getX();
        output.acceptScrollingWithDefaultCenter(this.getMessage(), x, x + this.getWidth(), this.getY(), this.getY() + this.getHeight());
    }
}
