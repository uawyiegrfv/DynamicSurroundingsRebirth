package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.scanner.shoreline.ShoreFront;
import org.orecruncher.dsurround.processing.scanner.shoreline.ShoreWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the shore wave front around the player and draws it as debug lines.
 *
 * <p>This is deliberately lines, not quads: the line geometry is what the
 * offline model proves (closed, axis-aligned, window independent), and drawing
 * it first keeps "is the geometry right" separate from "does the texture look
 * right". The window is re-scanned every {@link #REBUILD_TICKS} ticks; the
 * phase comes from wall time so the line moves without a tick-rate dependency.
 */
public final class ShoreWaveHandler extends AbstractClientHandler {

    private static final int REBUILD_TICKS = 10;
    private static final long PERIOD_MS = 8000L;

    private final Configuration.ShoreWaveOptions options;
    private record Draw(double x0, double z0, double x1, double z1, float y) {}

    private List<Draw> segments = List.of();
    private int seaLevel = 63;
    private static volatile double lastP = 0.5D;
    private int counter;

    public ShoreWaveHandler(final Configuration config, final Configuration.ShoreWaveOptions options,
                            final IModLog logger) {
        super("Shore Wave", config, logger);
        this.options = options;
        MinecraftForge.EVENT_BUS.addListener(this::doRender);
    }

    @Override
    public boolean doTick(final long tick) {
        return ++this.counter >= REBUILD_TICKS;
    }

    public static double lastP() { return lastP; }

    @Override
    public void process(final Player player) {
        this.counter = 0;
        if (!this.options.renderDebugLines) {
            this.segments = List.of();
            return;
        }
        final ShoreWindow window = ShoreWindow.capture(player.level(), player.blockPosition(), this.options.windowSize);
        this.seaLevel = window.seaLevel();
        final double phase = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        final double p = 0.55D + 0.40D * Math.sin(phase * Math.PI * 2.0D);
        lastP = p;
        final ShoreFront.Result r = ShoreFront.build(window, p, this.options.runup);

        final List<Draw> world = new ArrayList<>(r.segs().size());
        for (final ShoreFront.Seg s : r.segs()) {
            world.add(new Draw(s.x0() + window.originX(), s.z0() + window.originZ(),
                    s.x1() + window.originX(), s.z1() + window.originZ(),
                    this.seaLevel + (float) this.options.debugLift));
        }
        this.segments = world;

        if (this.logger.isDebugging())
            this.logger.debug("[Shore] p=%.2f segs=%d | %s", p, world.size(), window.stats());
    }

    public void doRender(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        if (this.segments.isEmpty())
            return;

        final Minecraft mc = Minecraft.getInstance();
        final var buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        final PoseStack pose = event.getPoseStack();
        final var cam = event.getCamera().getPosition();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        final var m = pose.last().pose();
        for (final Draw s : this.segments) {
            vc.vertex(m, (float) s.x0(), s.y(), (float) s.z0()).color(0.85F, 1.0F, 1.0F, 0.9F).normal(0, 1, 0).endVertex();
            vc.vertex(m, (float) s.x1(), s.y(), (float) s.z1()).color(0.85F, 1.0F, 1.0F, 0.9F).normal(0, 1, 0).endVertex();
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
