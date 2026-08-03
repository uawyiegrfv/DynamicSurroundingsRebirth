package org.orecruncher.dsurround.gui.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.orecruncher.dsurround.lib.di.Cacheable;

@Cacheable
public abstract class AbstractOverlay {

    public AbstractOverlay() {

    }

    // 26.1: GUI rendering now uses GuiGraphicsExtractor.
    public abstract void render(GuiGraphicsExtractor graphics, float partialTick);

    public void tick(Minecraft client) {

    }

}
