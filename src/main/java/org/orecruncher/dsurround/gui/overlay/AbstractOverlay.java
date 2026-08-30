package org.orecruncher.dsurround.gui.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.orecruncher.dsurround.lib.di.Cacheable;

@Cacheable
public abstract class AbstractOverlay {

    public AbstractOverlay() {

    }

    // 26.1: GUI rendering now uses GuiGraphics.
    public abstract void render(GuiGraphics graphics, float partialTick);

    public void tick(Minecraft client) {

    }

}
