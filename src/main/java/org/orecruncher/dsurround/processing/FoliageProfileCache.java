package org.orecruncher.dsurround.processing;

import java.util.Iterator;
import java.util.LinkedHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.eventing.ClientState;

/**
 * Model -&gt; attachment profile cache. The key is the {@link BakedModel}
 * INSTANCE: {@code BlockModelShaper} hands out one model per blockstate (with
 * variant rotations already baked into the vertices), so instance identity
 * dedupes states, resource packs and mods with zero registration work.
 *
 * <p>Budget (文档 §6.1): hard cap of 256 models - a 16x16 mask plus anchors
 * and paths is a few KB each, so the ceiling is 1-2 MB worst case. Entries
 * beyond the cap evict oldest (access-order map). The 5 ms budget is kept as a
 * COUNTER only - see the round-45 note in {@link #get}: it must never decide
 * whether a plant kind is allowed to carry snow.</p>
 *
 * <p>Cache invalidation (混元 review H): the mount point already existed -
 * {@code ClientState.RESOURCE_RELOAD} is raised by the mod's client reload
 * listener ({@code RegisterClientReloadListenersEvent} →
 * {@code ReloadListener}, DSurround.java), so F3+T and resource-pack changes
 * both clear this cache before the old model instances can go stale.</p>
 */
public final class FoliageProfileCache {

    private static final int CAP = 256;
    private static final long BUILD_BUDGET_NANOS = 5_000_000L;

    private static final LinkedHashMap<BakedModel, FoliageProfile> CACHE =
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

    /**
     * The profile for this state's model, built on first sight. Empty models
     * (non-plant geometry) are cached too, so the scan never re-asks.
     */
    public static FoliageProfile get(final Level world, final BlockPos pos, final BlockState state) {
        final BakedModel model = Minecraft.getInstance().getBlockRenderer()
                .getBlockModelShaper().getBlockModel(state);
        FoliageProfile profile = CACHE.get(model);
        if (profile != null)
            return profile;

        final long start = System.nanoTime();
        profile = FoliageProfile.build(model, state, state.getSeed(pos));
        final long elapsed = System.nanoTime() - start;
        lastBuildNanos = elapsed;
        if (elapsed > worstBuildNanos)
            worstBuildNanos = elapsed;
        // Round 45: the profile is ALREADY BUILT at this point, so dropping it
        // buys nothing and costs everything - the EMPTY then goes into the cache,
        // so one slow build (almost always the cold-start one, paying for class
        // loading and sprite lookup) permanently disqualified that plant kind.
        // That is the whole of "普通草不长雪、其它植物正常": a meadow is grass, so
        // grass is the model built first and the one that paid the cold cost - and
        // it then stayed the one kind that never got snow again for the session.
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
        lastBuildNanos = 0L;
        worstBuildNanos = 0L;
    }

    public static int size() {
        return CACHE.size();
    }
}
