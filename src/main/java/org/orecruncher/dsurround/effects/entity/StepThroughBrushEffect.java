package org.orecruncher.dsurround.effects.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.tags.BlockEffectTags;
import org.orecruncher.dsurround.mixinutils.ILivingEntityExtended;

public class StepThroughBrushEffect extends EntityEffectBase {

    private static final long BRUSH_INTERVAL = 2;
    private static final Identifier BRUSH_SOUND = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "brush_step/brush");
    private static final Identifier STRAW_SOUND = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "brush_step/straw");

    private final ITickCount tickCount;
    private final ITagLibrary tagLibrary;
    private long lastBrushCheck;
    // The block the last brush sound was triggered for, mirroring the original
    // 1.12.2 simulateBrushes "messyPos" tracking: only play once per block position
    // so an entity standing in (or walking around within) the same brush block does
    // not re-trigger the sound every interval. It fires again only after the entity
    // leaves the block and re-enters (the position changes).
    private BlockPos lastBrushPos;

    public StepThroughBrushEffect(ITickCount tickCount, ITagLibrary tagLibrary) {
        this.tickCount = tickCount;
        this.tagLibrary = tagLibrary;
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

                if (!this.process(BlockEffectTags.STRAW_STEP, STRAW_SOUND, world, pos))
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
    private boolean process(TagKey<Block> effectTag, Identifier factory, Level world, BlockPos blockPos) {
        var block = world.getBlockState(blockPos);
        if (this.tagLibrary.is(effectTag, block)) {
            this.playSoundEffect(blockPos, factory);
            return true;
        }
        var headPos = blockPos.above();
        block = world.getBlockState(headPos);
        if (this.tagLibrary.is(effectTag, block)) {
            this.playSoundEffect(headPos, factory);
            return true;
        }
        return false;
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

    private void playSoundEffect(BlockPos pos, Identifier factory) {
       SOUND_LIBRARY.getSoundFactory(factory)
               .ifPresent(f -> {
                   var soundInstance = f.createAtLocation(pos, (float) ConfigurationData.getConfig(Configuration.class).soundOptions.footstepVolume);
                   this.playSound(soundInstance);
               });
    }
}
