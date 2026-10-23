package org.orecruncher.dsurround.processing.scanner;

import net.minecraft.core.BlockBox;
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

    // ---- view-direction fan -------------------------------------------------
    //
    // The sweep used to be a box centred on the player, so half of it was behind them:
    // block effects (steam jets, waterfalls) are things you walk towards. This is the
    // same trick the foliage scan already uses - a horizontal ellipse around the facing.
    private static final float FAN_FORWARD = 1.35F;
    private static final float FAN_SIDE = 0.90F;
    private static final float FAN_BACK = 0.35F;
    /** Turning further than this re-aims the cone and restarts the sweep. */
    private static final float FAN_RESWEEP_DEGREES = 30F;

    private float fanX;
    private float fanZ;
    private float fanYaw = Float.NaN;

    private void updateFan() {
        final var player = GameUtils.getPlayer().orElse(null);
        if (player == null) {
            this.fanX = 0F;
            this.fanZ = 0F;
            return;
        }
        final float yaw = player.getYRot();
        if (!Float.isNaN(this.fanYaw)
                && Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - this.fanYaw)) < FAN_RESWEEP_DEGREES)
            return;
        this.fanYaw = yaw;
        final double rad = Math.toRadians(yaw);
        this.fanX = (float) -Math.sin(rad);
        this.fanZ = (float) Math.cos(rad);
        if (!this.scanFinished)
            this.resetFullScan();
    }

    private boolean insideFan(final BlockPos pos) {
        if (this.fanX == 0F && this.fanZ == 0F)
            return true;
        final var centre = this.locus.getScanCenter();
        final float dx = pos.getX() - centre.getX();
        final float dz = pos.getZ() - centre.getZ();
        final float forward = dx * this.fanX + dz * this.fanZ;
        final float side = dx * -this.fanZ + dz * this.fanX;
        final float range = Math.max(1, this.config.blockEffects.blockEffectRange);
        final float a = (forward >= 0F ? FAN_FORWARD : FAN_BACK) * range;
        final float b = FAN_SIDE * range;
        return (forward * forward) / (a * a) + (side * side) / (b * b) <= 1F;
    }

    /**
     * Skips points outside the cone without paying getBlockState or a system call for
     * them; the iterator itself is unchanged.
     */
    @Override
    protected BlockPos nextPos(final BlockPos.MutableBlockPos workingPos, final IRandomizer rand) {
        if (this.scanFinished)
            return null;
        int checked = 0;
        BlockPos point;
        while ((point = this.fullRange.peek()) != null) {
            this.fullRange.next();
            if (!this.locus.isOutOfHeightLimit(point.getY()) && this.insideFan(point))
                return point;
            if (++checked >= this.blocksPerTick)
                return null;
        }
        this.scanFinished = true;
        return null;
    }
    @Override

    public void tick() {
        this.updateFan();
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
            var blockBox = BlockBox.of(current.offset(-range, -range, -range), current.offset(range, range, range));

            filter = system -> {
                if (blockBox.contains(system.getPos())) {
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
     * Systems that can act on a given BlockState, cached by state identity. Almost every
     * block in a 65^3 sweep is stone, dirt or air and cannot start anything; the old code
     * still paid two virtual calls and their fluid/tag lookups per block for no result.
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
        for (int k = 0; k < relevant.size(); k++) {
            final var system = relevant.get(k);
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
