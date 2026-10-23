package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.effects.IBlockEffect;
import org.orecruncher.dsurround.effects.IEffectSystem;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.Cacheable;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.scanner.CuboidScanner;
import org.orecruncher.dsurround.lib.scanner.ScanContext;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Cacheable
public class SystemsScanner extends CuboidScanner {

    private static final Predicate<IBlockEffect> EFFECT_PREDICATE = system -> {
        system.tick();
        return system.isDone();
    };

    private final Configuration config;
    private final ObjectArray<IEffectSystem> systems = new ObjectArray<>();

    private int lastRange;

    public SystemsScanner(Configuration config, ScanContext locus) {
        super(locus, "SystemsScanner", config.blockEffects.blockEffectRange);

        this.config = config;
        this.lastRange = config.blockEffects.blockEffectRange;
    }

    public void addEffectSystem(IEffectSystem system) {
        this.systems.add(system);
    }

    public void resetFullScan() {
        super.resetFullScan();
        this.systems.forEach(IEffectSystem::clear);
    }

    @Override
    public void tick() {
        super.tick();

        // If the range changed, we need to reset all effects in process
        if (this.lastRange != this.config.blockEffects.blockEffectRange) {
            this.lastRange = this.config.blockEffects.blockEffectRange;
            this.setRange(this.lastRange);
            return;
        }

        var player = GameUtils.getPlayer().orElseThrow();
        final BlockPos current = player.blockPosition();
        final boolean sittingStill = this.lastPos.equals(current);
        this.lastPos = current;

        Predicate<IBlockEffect> filter;

        if (!sittingStill) {
            var range = this.config.blockEffects.blockEffectRange;
            var blockBox = BoundingBox.fromCorners(current.offset(-range, -range, -range), current.offset(range, range, range));

            filter = system -> {
                if (blockBox.isInside(system.getPos())) {
                    system.tick();
                } else {
                    system.remove();
                }
                return system.isDone();
            };
        } else {
            filter = EFFECT_PREDICATE;
        }

        this.processIfEnabled(true, system -> system.tick(filter));
    }

    protected void processIfEnabled(boolean clearSystems, Consumer<IEffectSystem> systemConsumer) {
        for (var system : this.systems)
            if (system.isEnabled())
                systemConsumer.accept(system);
            else if(clearSystems)
                system.clear();
    }

    @Override
    public boolean doBlockUnscan() {
        return true;
    }

    /**
     * Systems that can act on a given BlockState, cached by state identity.
     *
     * <p>The sweep calls {@code blockScan} for every block in a 65^3 box (about 6000 per
     * tick while walking). Almost all of them are stone, dirt or air, and none of those can
     * start a steam jet or a waterfall - but the old code still paid two virtual calls and
     * their fluid/tag lookups for every one of them. This cache turns that into one identity
     * lookup; the list behind it is short (usually empty).</p>
     *
     * <p>Identity, not equals: BlockStates are interned per state, so identity is both correct
     * and the cheapest comparison available.</p>
     */
    private final java.util.IdentityHashMap<BlockState, java.util.List<IEffectSystem>> relevantByState =
            new java.util.IdentityHashMap<>();

    private java.util.List<IEffectSystem> relevantSystems(final BlockState state) {
        var cached = this.relevantByState.get(state);
        if (cached != null)
            return cached;
        final java.util.List<IEffectSystem> list = new java.util.ArrayList<>(this.systems.size());
        for (var system : this.systems)
            if (system.blockScanRelevant(state))
                list.add(system);
        this.relevantByState.put(state, list);
        return list;
    }

    @Override
    public void blockScan(Level world, BlockState state, BlockPos pos, IRandomizer rand) {
        final var relevant = this.relevantSystems(state);
        for (int i = 0; i < relevant.size(); i++) {
            final var system = relevant.get(i);
            if (system.isEnabled())
                system.blockScan(world, state, pos);
        }
    }

    @Override
    public void blockUnscan(Level world, BlockState state, BlockPos pos, IRandomizer rand) {
        this.processIfEnabled(false, system -> system.blockUnscan(world, state, pos));
    }

    public void gatherDiagnostics(Collection<Component> output) {
        this.systems.forEach(system -> {
            var text = system.gatherDiagnostics();
            if (!system.isEnabled())
                text += " (disabled)";
            output.add(Component.literal(text));
        });
    }
}
