package org.orecruncher.dsurround.effects.entity;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.config.libraries.IItemLibrary;

public class ItemSwingEffect extends EntityEffectBase {

    private final IItemLibrary itemLibrary;
    private boolean isSwinging;

    public ItemSwingEffect(IItemLibrary itemLibrary) {
        this.itemLibrary = itemLibrary;
    }

    @Override
    public void tick(final EntityEffectInfo info) {
        if (info.isRemoved())
            return;

        final LivingEntity entity = info.getEntity();

        // Boats are strange - ignore them for now
        if (entity.getVehicle() instanceof Boat)
            return;

        // Don't use entity.isBlocking() - it has a 5 tick delay which would cause the
        // animation and the sound play to be out of sync.
        var isTriggered = entity.getAttackAnim(1F) > 0 || looksToBeBlocking(entity);

        if (isTriggered) {
            if (!this.isSwinging) {
                ItemStack currentItem;
                if (entity.swinging)
                    currentItem = entity.getItemInHand(InteractionHand.MAIN_HAND);
                else
                    currentItem = entity.getUseItem();

                var factory = this.itemLibrary.getItemSwingSound(currentItem);

                if (factory.isPresent() && freeSwing(entity)) {
                    SoundInstance instance;
                    if (info.isCurrentPlayer(entity)) {
                        instance = factory.get().createAsAdditional();
                    } else {
                        instance = factory.get().attachToEntity(entity);
                    }

                    if (instance != null)
                        this.playSound(instance);
                }

                this.isSwinging = true;
            }
        } else
            this.isSwinging = false;
    }

    protected static boolean looksToBeBlocking(LivingEntity entity) {
        if (!entity.isUsingItem() || entity.getUseItem().isEmpty()) {
            return false;
        }
        Item item = entity.getUseItem().getItem();
        return item.getUseAnimation(entity.getUseItem()) == UseAnim.BLOCK;
    }

    /**
     * Whether the swing should produce the item swing sound.  Mirrors the original
     * 1.12.2 logic: the sound plays on a miss and on an entity hit, and is suppressed
     * only when a block is struck (the block dig sound covers that case).
     */
    protected static boolean freeSwing(LivingEntity entity) {
        var result = rayTrace(entity);
        return result.getType() != HitResult.Type.BLOCK;
    }

    protected static double getReach(final LivingEntity entity) {
        if (entity instanceof LocalPlayer p)
            return p.isCreative() ? 5D : 3D;

        var dist = entity.getBbWidth();
        dist *= 2;
        dist *= dist;
        dist += entity.getBbWidth();
        return dist;
    }

    /**
     * Ray traces from the entity's eyes along its view vector considering both blocks
     * and entities, returning the closest hit (a MISS when nothing is in range).
     */
    protected static HitResult rayTrace(final LivingEntity entity) {
        double range = getReach(entity);
        Vec3 from = entity.getEyePosition();
        Vec3 view = entity.getViewVector(1F);
        Vec3 to = from.add(view.x * range, view.y * range, view.z * range);

        HitResult result = entity.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
        double closest = result.getLocation().distanceToSqr(from);

        AABB searchBox = new AABB(from, to).inflate(1D);
        for (Entity candidate : entity.level().getEntities(entity, searchBox, e -> !e.isSpectator() && e.isPickable())) {
            AABB candidateBox = candidate.getBoundingBox().inflate((double) candidate.getPickRadius());
            var hitVec = candidateBox.clip(from, to);
            if (hitVec.isPresent()) {
                double d = from.distanceToSqr(hitVec.get());
                if (d < closest) {
                    closest = d;
                    result = new EntityHitResult(candidate, hitVec.get());
                }
            }
        }

        return result;
    }
}
