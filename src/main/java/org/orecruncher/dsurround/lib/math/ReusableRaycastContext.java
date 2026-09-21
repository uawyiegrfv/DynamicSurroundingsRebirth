package org.orecruncher.dsurround.lib.math;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.mixins.core.MixinRaycastContextAccessor;

public class ReusableRaycastContext extends ClipContext {

    private final Level world;
    private final MixinRaycastContextAccessor accessor;

    public ReusableRaycastContext(Level world, ClipContext.Block shapeType, ClipContext.Fluid fluidHandling) {
        this(world, Vec3.ZERO, Vec3.ZERO, shapeType, fluidHandling);
    }

    public ReusableRaycastContext(Level world, Vec3 start, Vec3 end, ClipContext.Block shapeType, ClipContext.Fluid fluidHandling) {
        this(world, start, end, shapeType, fluidHandling, GameUtils.getPlayer().orElseThrow());

        // Override the shape context that was passed into the ctor
        this.accessor.dsurround_setShapeContext(CollisionContext.empty());
    }

    public ReusableRaycastContext(Level world, Vec3 start, Vec3 end, ClipContext.Block shapeType, ClipContext.Fluid fluidHandling, Entity entity) {
        super(start, end, shapeType, fluidHandling, entity);

        this.world = world;
        this.accessor = ((MixinRaycastContextAccessor)this);
    }

    /**
     * Raycasts performed since the last {@link #resetRaycasts()}. Diagnostics only: the enhanced audio path
     * is dominated by raycast work, so a per-evaluation count is how its cost is compared against earlier
     * revisions without having to deploy one.
     *
     * <p>{@link java.util.concurrent.atomic.AtomicInteger}, not a plain {@code int}. The audio evaluations
     * run on a worker POOL, so several threads increment this concurrently while the main evaluation thread
     * reads it - a plain int gave lost updates, and the probe's {@code rays=} figure is precisely the number
     * used to judge whether the audio path is affordable. A diagnostic that under-reports the cost it exists
     * to measure is worse than no diagnostic.
     *
     * <p>It still aggregates across concurrent evaluations: {@link #resetRaycasts()} runs per evaluation, so
     * the value a row reports includes whatever other evaluations were in flight. That is a known limitation
     * of per-evaluation counting on a shared pool, not a correctness problem for the use it is put to
     * (comparing revisions), and it is stated here so nobody reads the figure as exactly one evaluation.
     */
    private static final java.util.concurrent.atomic.AtomicInteger raycastCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Clears the raycast counter, called at the start of each audio evaluation. */
    public static void resetRaycasts() {
        raycastCount.set(0);
    }

    /** Raycasts performed since the last reset. */
    public static int raycastCount() {
        return raycastCount.get();
    }

    public BlockHitResult trace(Vec3 start, Vec3 end) {
        raycastCount.incrementAndGet();
        this.setStart(start);
        this.setEnd(end);
        return this.world.clip(this);
    }

    /**
     * Perform trace based on current values of start and end.
     */
    BlockHitResult trace() {
        raycastCount.incrementAndGet();
        return this.world.clip(this);
    }

    public Vec3 getStart() {
        return this.accessor.dsurround_getStartPoint();
    }

    void setStart(Vec3 point) {
        this.accessor.dsurround_setStartPoint(point);
    }

    public Vec3 getEnd() {
        return this.accessor.dsurround_getEndPoint();
    }

    void setEnd(Vec3 point) {
        this.accessor.dsurround_setEndPoint(point);
    }
}
