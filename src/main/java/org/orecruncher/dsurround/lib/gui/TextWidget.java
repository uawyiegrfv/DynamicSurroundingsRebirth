package org.orecruncher.dsurround.lib.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.network.chat.Component;

public class TextWidget extends AbstractStringWidget {

    public TextWidget(int x, int y, int width, int height, Component component, Font font) {
        super(x, y, width, height, component, font);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        graphics.drawCenteredString(this.getFont(), this.getMessage(),
                this.getX() + this.getWidth() / 2,
                this.getY() + (this.getHeight() - this.getFont().lineHeight) / 2,
                0xFFFFFFFF);
    }
}
