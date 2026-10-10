package org.orecruncher.dsurround.processing;

import java.util.Iterator;
import java.util.LinkedHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.eventing.ClientState;

/**
 * Model -&gt; attachment profile cache - the 26.1 shape of
 * {@code FoliageProfileCache} (see the 1.20.1 file for the budget rationale).
 *
 * <p>26.1 model plumbing: the renderer's model is a {@link BlockStateModel},
 * obtained from {@code Minecraft.getInstance().getModelManager()
 * .getBlockStateModelSet().get(state)} - the {@code TerrainParticle} route.
 * The set already maps one model per state, so instance identity keys the
 * cache exactly as {@code BakedModel} did on the other two versions.</p>
 */
public final class FoliageProfileCache {

    private static final int CAP = 256;
    private static final long BUILD_BUDGET_NANOS = 5_000_000L;

    private static final LinkedHashMap<BlockStateModel, FoliageProfile> CACHE =
            new LinkedHashMap<>(64, 0.75F, true);

    /** Counters for the diagnostics line. */
    public static int builds = 0;
    public static int overBudget = 0;
    /** Wall time of the last real build, in nanos. The budget's own verdict. */
    public static long lastBuildNanos = 0L;
    /** Slowest build seen since the last reload - is 5ms ever really the problem? */
    public static long worstBuildNanos = 0L;

    static {
        // Review H: fire on every client resource reload (F3+T included).
        ClientState.RESOURCE_RELOAD.register(rm -> CACHE.clear());
    }

    private FoliageProfileCache() {
    }

    public static FoliageProfile get(final Level world, final BlockPos pos, final BlockState state) {
        final BlockStateModel model = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
        FoliageProfile profile = CACHE.get(model);
        if (profile != null)
            return profile;

        final long start = System.nanoTime();
        profile = FoliageProfile.build(model, state);
        final long elapsed = System.nanoTime() - start;
        lastBuildNanos = elapsed;
        if (elapsed > worstBuildNanos)
            worstBuildNanos = elapsed;
        // Round 45: the profile is ALREADY BUILT at this point, so dropping it
        // buys nothing and costs everything - the EMPTY then goes into the cache,
        // so one slow build (almost always the cold-start one, paying for class
        // loading and sprite lookup) permanently disqualified that plant kind.
        // "Slower than budget" is a performance fact, not a correctness verdict:
        // keep the profile, keep the counter as the diagnostic. The build only
        // ever runs once per model anyway (that is what this cache is for).
        if (elapsed > BUILD_BUDGET_NANOS)
            overBudget++;
        builds++;
        CACHE.put(model, profile);
        while (CACHE.size() > CAP) {
            final Iterator<FoliageProfile> it = CACHE.values().iterator();
            it.next();
            it.remove();
        }
        return profile;
    }

    public static void clear() {
        CACHE.clear();
        builds = 0;
        overBudget = 0;
    }

    public static int size() {
        return CACHE.size();
    }
}
