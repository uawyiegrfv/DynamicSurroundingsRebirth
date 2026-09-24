package org.orecruncher.dsurround.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Answers "what block is this entity standing on" when that block belongs to a Create contraption.
 *
 * <p>A moving contraption takes its blocks out of the world - {@code removeBlocksFromWorld} sets
 * each one to air - and keeps them in {@code Contraption.getBlocks()}, a plain map keyed by
 * contraption-local position. So every world-grid probe the footstep resolution makes sees air, and
 * a player riding a contraption gets no footstep at all plus the generic landing sound.
 *
 * <p>Create solves the same problem for its own step sounds in
 * {@code foundation/mixin/client/EntityContraptionInteractionMixin}, and this follows its three
 * steps exactly: find the contraption, convert the world sample point to a contraption-local
 * position, read the block out of the map. Sampling 0.2 below the entity is the offset Create uses.
 *
 * <p><b>Reflection, deliberately.</b> Create has no release for 26.1, so a compile-time dependency
 * could not be carried by all three builds while they share one source file, and Create's entry
 * points are in {@code content.contraptions} - an internal package with no stability marker at all.
 * Nothing here names a Create type, so this class loads and returns null without Create present. If
 * Create is there but its API moved, one WARN names the failure and the lookup switches itself off,
 * rather than throwing on every footstep.
 *
 * <p>The whole thing is best-effort by construction: null means "not on a contraption, or Create
 * could not be read", and the caller falls back to the world grid - which is today's behaviour. A
 * failed resolution is therefore never worse than not having this class.
 */
public final class CreateContraptionCompat {

    private static final String CONTRAPTION_ENTITY =
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final String CONTRAPTION =
            "com.simibubi.create.content.contraptions.Contraption";
    private static final String CONTRAPTION_COLLIDER =
            "com.simibubi.create.content.contraptions.ContraptionCollider";

    /** Create samples this far below the entity for its own contraption step sounds. */
    private static final double SAMPLE_OFFSET = 0.2D;

    /** How far the contraption entity may extend past the standing entity and still count. */
    private static final double SEARCH_INFLATE = 1.0D;

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);

    private static boolean resolved;
    private static boolean usable;

    private static Class<?> contraptionEntityClass;
    private static Method getContraption;
    private static Method getBlocks;
    private static Method isHiddenInPortal;
    private static Method worldToLocalPos;
    /** Resolved on first use: the state accessor of whatever StructureBlockInfo class Create has. */
    private static volatile Method blockInfoState;

    private CreateContraptionCompat() {
    }

    /**
     * @return the block the entity is standing on inside a contraption, or null when it is not on
     *         one, when Create is absent, or when Create's API could not be read
     */
    @Nullable
    public static BlockState surface(final Entity entity, final Level level) {
        if (!resolve())
            return null;
        try {
            return probe(entity, level);
        } catch (final Throwable t) {
            // Almost certainly a Create-side rename. Stand down for good so the log gets one line
            // instead of one per footstep, and fall back to the world grid from here on.
            usable = false;
            LOGGER.warn("Create contraption lookup failed and has been disabled: %s", t);
            return null;
        }
    }

    /** Resolves Create's API once. Absence is normal; a failure is reported once. */
    private static boolean resolve() {
        if (resolved)
            return usable;
        resolved = true;
        try {
            contraptionEntityClass = Class.forName(CONTRAPTION_ENTITY);
            final Class<?> contraptionClass = Class.forName(CONTRAPTION);
            final Class<?> colliderClass = Class.forName(CONTRAPTION_COLLIDER);

            getContraption = contraptionEntityClass.getMethod("getContraption");
            getBlocks = contraptionClass.getMethod("getBlocks");
            isHiddenInPortal = contraptionClass.getMethod("isHiddenInPortal", BlockPos.class);
            worldToLocalPos = colliderClass.getMethod("worldToLocalPos", Vec3.class, contraptionEntityClass);
            usable = true;
        } catch (final ClassNotFoundException absent) {
            // The normal case: Create simply is not installed. Not worth a line in the log.
        } catch (final Throwable t) {
            LOGGER.warn("Create is installed but its contraption API could not be read (%s). "
                    + "Footsteps on moving contraptions will stay silent.", t);
        }
        return usable;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static BlockState probe(final Entity entity, final Level level) throws Exception {
        // Contraptions are real entities on the client, so a box query finds them without touching
        // any of Create's internals. Create unions this with its own loaded-contraption registry;
        // the box alone is sufficient here and is the part least likely to move.
        final List<?> candidates = level.getEntitiesOfClass(
                (Class) contraptionEntityClass,
                entity.getBoundingBox().inflate(SEARCH_INFLATE));

        for (final Object candidate : candidates) {
            final Object contraption = getContraption.invoke(candidate);
            if (contraption == null)
                continue;

            // Convert first, then step down in the CONTRAPTION's space. Subtracting world-Y before
            // the transform - which is what Create does for its own probe - is only right while the
            // contraption is level. On a tilted one, world-down is not the deck's down, and the
            // sample lands beside the block the player is standing on rather than inside it, which
            // is how a tilted contraption ended up silent.
            final Vec3 feet = (Vec3) worldToLocalPos.invoke(null, entity.position(), candidate);
            final Vec3 localVec = feet.subtract(0.0D, SAMPLE_OFFSET, 0.0D);
            final BlockPos localPos = BlockPos.containing(localVec);

            final Map<?, ?> blocks = (Map<?, ?>) getBlocks.invoke(contraption);
            final Object info = blocks.get(localPos);
            if (info == null)
                continue;

            // A block can be inside the contraption's data but hidden by a portal; Create skips
            // those for interaction and so do we.
            if (Boolean.TRUE.equals(isHiddenInPortal.invoke(contraption, localPos)))
                continue;

            final BlockState state = (BlockState) stateOf(info).invoke(info);
            if (state != null && !state.isAir())
                return state;
        }
        return null;
    }

    /** The state accessor of Create's block info record, resolved once from a live instance. */
    private static Method stateOf(final Object info) throws NoSuchMethodException {
        Method method = blockInfoState;
        if (method == null) {
            method = info.getClass().getMethod("state");
            blockInfoState = method;
        }
        return method;
    }
}
