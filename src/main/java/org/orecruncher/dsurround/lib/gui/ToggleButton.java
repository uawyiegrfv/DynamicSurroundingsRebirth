package org.orecruncher.dsurround.lib.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public abstract class ToggleButton extends Button {

    private final ResourceLocation onSprite;
    private final ResourceLocation offSprite;
    private final int iconSize;

    private boolean isOn;

    protected ToggleButton(boolean initialState, ResourceLocation onSprite, ResourceLocation offSprite, OnPress onPress) {
        this(initialState, onSprite, offSprite, onPress, 20);
    }

    protected ToggleButton(boolean initialState, ResourceLocation onSprite, ResourceLocation offSprite, OnPress onPress, int iconSize) {
        super(0, 0, 20, 20, Component.empty(), onPress, DEFAULT_NARRATION);

        this.isOn = initialState;
        this.onSprite = onSprite;
        this.offSprite = offSprite;
        this.iconSize = iconSize;
    }

    public void setOn(boolean flag) {
        this.isOn = flag;
    }

    public boolean toggle() {
        return this.isOn = !this.isOn;
    }

    // Basically what ImageButton does but simplified. The icon png may be 16x16 while
    // the widget is 20x20 - pass the actual texture size so sampling does not run off.
    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        GuiHelpers.blitSprite(graphics, this.getSpriteToRender(), this.getX(), this.getY(), this.width, this.height, this.iconSize, this.iconSize);
    }

    private ResourceLocation getSpriteToRender() {
        return this.isOn ? this.onSprite : this.offSprite;
    }
}
