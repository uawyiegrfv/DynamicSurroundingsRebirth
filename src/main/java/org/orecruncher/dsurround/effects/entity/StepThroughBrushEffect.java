package org.orecruncher.dsurround.effects.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.tags.BlockEffectTags;
import org.orecruncher.dsurround.mixinutils.ILivingEntityExtended;

import java.util.List;

public class StepThroughBrushEffect extends EntityEffectBase {

    private static final long BRUSH_INTERVAL = 2;
    private static final ResourceLocation BRUSH_SOUND = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "brush_step/brush");
    private static final ResourceLocation STRAW_SOUND = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "brush_step/straw");

    private final ITickCount tickCount;
    private final ITagLibrary tagLibrary;
    private long lastBrushCheck;
    // The block the last brush sound was triggered for, mirroring the original
    // 1.12.2 simulateBrushes "messyPos" tracking: only play once per block position
    // so an entity standing in (or walking around within) the same brush block does
    // not re-trigger the sound every interval. It fires again only after the entity
    // leaves the block and re-enters (the position changes).
    private BlockPos lastBrushPos;

    // One instance per entity, created through the DI container; the newest one is kept here so
    // /dsdump brush can read the live dedup state without the command knowing about instances.
    private static volatile StepThroughBrushEffect lastInstance;

    public StepThroughBrushEffect(ITickCount tickCount, ITagLibrary tagLibrary) {
        this.tickCount = tickCount;
        this.tagLibrary = tagLibrary;
        lastInstance = this;
    }

    /**
     * Builds the /dsdump brush report: the whole brush-through decision chain at the player's
     * current position. It exists because "no brush sound here" has four independent causes -
     * the cell is not tagged, the entity is not moving, the interval gate has not elapsed, or the
     * position is already deduped - and only the last two are stateful, so a data file cannot
     * answer the question.
     */
    public static List<String> dumpBrushTrace(final Player player) {
        final List<String> trace = new java.util.ArrayList<>();
        trace.add("=== DS brush-through trace ===");
        var instance = lastInstance;
        if (instance == null) {
            trace.add("no StepThroughBrushEffect instance has been created yet (nothing to trace)");
            return trace;
        }

        var world = player.level();
        var feetPos = player.blockPosition();
        var grid = instance.tagLibrary;

        trace.add("player %s   feet Y %.4f   onGround %s".formatted(feetPos, player.getY(), player.onGround()));
        var movement = player.getDeltaMovement();
        trace.add("movement x=%.4f z=%.4f  (shouldProcess needs |x| or |z| > 0.01, or a jump)"
                .formatted(movement.x, movement.z));
        trace.add("jumping = %s".formatted(((ILivingEntityExtended) player).dsurround_isJumping()));

        trace.add("lastBrushPos = %s   (a sound fires only when the feet cell CHANGES;"
                .formatted(instance.lastBrushPos));
        trace.add("                 standing still inside one brush cell stays silent by design)");

        var cell = world.getBlockState(feetPos);
        trace.add("");
        trace.add("feet cell %s = %s".formatted(feetPos, cell));
        trace.add("   straw_step = %s   brush_step = %s   crop_step = %s".formatted(
                grid.is(BlockEffectTags.STRAW_STEP, cell),
                grid.is(BlockEffectTags.BRUSH_STEP, cell),
                grid.is(BlockEffectTags.CROP_STEP, cell)));
        var headPos = feetPos.above();
        var head = world.getBlockState(headPos);
        trace.add("head cell %s = %s".formatted(headPos, head));
        trace.add("   straw_step = %s   brush_step = %s   crop_step = %s".formatted(
                grid.is(BlockEffectTags.STRAW_STEP, head),
                grid.is(BlockEffectTags.BRUSH_STEP, head),
                grid.is(BlockEffectTags.CROP_STEP, head)));

        trace.add("");
        trace.add("verdict: " + verdict(instance, player, world, feetPos));
        return trace;
    }

    private static String verdict(StepThroughBrushEffect instance, Player player, Level world, BlockPos feetPos) {
        var crop = instance.cropAt(world, feetPos);
        if (crop == ProcessResult.MISS)
            crop = instance.cropAt(world, feetPos.above());
        if (crop == ProcessResult.MATCHED_SILENT)
            return "crop in this cell is NOT grown enough - deliberately silent";
        if (crop == ProcessResult.PLAYED)
            return "crop foliage (age-based) - a brush/straw rustle belongs here";

        if (instance.tagLibrary.is(BlockEffectTags.STRAW_STEP, world.getBlockState(feetPos))
                || instance.tagLibrary.is(BlockEffectTags.STRAW_STEP, world.getBlockState(feetPos.above())))
            return "straw foliage present - sound belongs here (only the dedup gate can suppress it)";
        if (instance.tagLibrary.is(BlockEffectTags.BRUSH_STEP, world.getBlockState(feetPos))
                || instance.tagLibrary.is(BlockEffectTags.BRUSH_STEP, world.getBlockState(feetPos.above())))
            return "brush foliage present - sound belongs here (only the dedup gate can suppress it)";

        return "NEITHER cell is tagged: no brush sound is expected here. If this block should rustle,"
                + " add it to dsconfigs/tags/block/effects/brush_step.json (or straw_step.json).";
    }

    @Override
    public void tick(final EntityEffectInfo info) {
        var currentCount = this.tickCount.getTickCount();
        if (currentCount > this.lastBrushCheck) {
            this.lastBrushCheck = currentCount + BRUSH_INTERVAL;
            if (info.isRemoved())
                return;
            var entity = info.getEntity();
            if (shouldProcess(entity)) {
                var world = entity.level();
                var pos = entity.blockPosition();

                // Only trigger once per block position (1.12.2 messyPos dedup).
                if (pos.equals(this.lastBrushPos))
                    return;
                this.lastBrushPos = pos;

                // Order matters. Crops come first because their foliage sound depends on the
                // growth age, and straw before brush for the rest (1.12.2 checked the "straw"
                // substrate first). A crop that is too young reports MATCHED_SILENT, which stops
                // the chain on purpose instead of falling through to a generic brush rustle.
                if (this.processCrop(world, pos) == ProcessResult.MISS)
                    if (this.process(BlockEffectTags.STRAW_STEP, STRAW_SOUND, world, pos) == ProcessResult.MISS)
                        this.process(BlockEffectTags.BRUSH_STEP, BRUSH_SOUND, world, pos);
            }
        }
    }

    /**
     * Plays the brush/straw sound for a block (or the block above it) that matches the
     * tag, if any. The original 1.12.2 played these at a fixed volume (brush = 0.65,
     * straw = 1.0) regardless of the plant's height; the factory config drives the
     * loudness, so no per-block scaling is applied.
     */
    private ProcessResult process(TagKey<Block> effectTag, ResourceLocation factory, Level world, BlockPos blockPos) {
        // Check the feet cell, the block above (standing at a plant's base), and the block
        // below (walking over a plant whose top is under the feet position) - the 1.12.2
        // original matched the block at the entity's "mouth" level plus the ground cell.
        var block = world.getBlockState(blockPos);
        if (this.tagLibrary.is(effectTag, block)) {
            this.playSoundEffect(blockPos, factory);
            return ProcessResult.PLAYED;
        }
        var headPos = blockPos.above();
        block = world.getBlockState(headPos);
        if (this.tagLibrary.is(effectTag, block)) {
            this.playSoundEffect(headPos, factory);
            return ProcessResult.PLAYED;
        }
        // 1.20.1: no below() probe - a flying player above a grass field would otherwise
        // trigger the brush sound while airborne (26.1 checks feet cell + head only).
        return ProcessResult.MISS;
    }

    /**
     * Crop foliage, resolved from the block's growth state. Direct port of the 1.12.2 BlockMap
     * macro entries, which could not be expressed as a plain block tag because the answer depends
     * on {@code age}:
     *
     * <pre>
     *   #wheat  age 0-1 nothing, 2-3 brush, 4-5 brush+straw, 6-7 straw
     *   #crop   age 0-3 nothing, 4-7 brush
     *   #beets  age 0-1 nothing, 2-3 brush
     * </pre>
     *
     * All three reduce to one rule on the normalised age (0 at the lowest value of the block's
     * {@code age} property, 1 at the highest): below 0.25 silent, below 0.75 brush, at or above
     * that straw, with the 0.75..1.0 band layering brush on top of the straw to reproduce
     * 1.12.2's brush_straw_transition rows. That reproduces every row of the table, because a
     * fully grown crop always sits at the maximum age - 1.12.2's "#crop at age 7" is wheat only,
     * whose 8 values make 7/7 = 1.0 = straw. It also gives any modded crop in the tag the same
     * semantics without needing its own entry: a crop that only emits near maturity stays silent
     * until then, which is the 1.12.2 behaviour.
     *
     * <p>The block needs an int {@code age} property. Anything in the tag without one is treated
     * as not-a-crop (MISS) so it can still fall through to the brush/straw tags.
     */
    private ProcessResult processCrop(Level world, BlockPos blockPos) {
        var result = cropAt(world, blockPos);
        if (result != ProcessResult.MISS)
            return result;
        return cropAt(world, blockPos.above());
    }

    private ProcessResult cropAt(Level world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!this.tagLibrary.is(BlockEffectTags.CROP_STEP, state))
            return ProcessResult.MISS;

        if (!state.hasProperty(BlockStateProperties.AGE_7))
            return ProcessResult.MISS;
        var age = state.getValue(BlockStateProperties.AGE_7);
        // getPossibleValues() is a Collection, not a List, so the bounds are folded out of it.
        var maxAge = Integer.MIN_VALUE;
        var minAge = Integer.MAX_VALUE;
        for (var v : BlockStateProperties.AGE_7.getPossibleValues()) {
            if (v > maxAge) maxAge = v;
            if (v < minAge) minAge = v;
        }
        var span = maxAge - minAge;
        if (span <= 0)
            return ProcessResult.MISS;

        var normalizedAge = (float) (age - minAge) / span;

        // Immature: silent. The cell matched, so the caller must NOT fall through to the generic
        // brush/straw tags - a young crop is a crop that makes no noise, not a plant.
        if (normalizedAge < 0.25F)
            return ProcessResult.MATCHED_SILENT;

        if (normalizedAge < 0.75F) {
            this.playSoundEffect(pos, BRUSH_SOUND);
            return ProcessResult.PLAYED;
        }

        // Fully grown wheat/beets rustle like dry straw; the transition rows mix the two.
        this.playSoundEffect(pos, STRAW_SOUND);
        if (normalizedAge < 1.0F)
            this.playSoundEffect(pos, BRUSH_SOUND);
        return ProcessResult.PLAYED;
    }

    private enum ProcessResult {
        /** No tagged block in this cell. */
        MISS,
        /** A tagged block, but the state says it makes no sound (an immature crop). */
        MATCHED_SILENT,
        /** A sound was played. */
        PLAYED
    }

    private static boolean shouldProcess(LivingEntity entity) {
        if (entity.isSilent() || entity.isSpectator())
            return false;
        // The original 1.12.2 used motionX/motionZ (actual horizontal movement) rather
        // than the input axes xxa/zza: mobs move via AI navigation where xxa/zza stay 0,
        // so the input-based check never fired for them. Use the horizontal delta movement
        // with a small threshold - the vertical component is always non-zero (gravity) and
        // a strict != 0 picks up floating-point jitter of a stationary mob, which would
        // otherwise make a mob standing in brush play the sound on every interval.
        var movement = entity.getDeltaMovement();
        if (Math.abs(movement.x) > 0.01 || Math.abs(movement.z) > 0.01)
            return true;
        return ((ILivingEntityExtended)entity).dsurround_isJumping();
    }

    private void playSoundEffect(BlockPos pos, ResourceLocation factory) {
       SOUND_LIBRARY.getSoundFactory(factory)
               .ifPresent(f -> {
                   var soundInstance = f.createAtLocation(pos, (float) ConfigurationData.getConfig(Configuration.class).soundOptions.footstepVolume);
                   this.playSound(soundInstance);
               });
    }
}
