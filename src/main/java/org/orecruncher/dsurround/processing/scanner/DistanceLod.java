package org.orecruncher.dsurround.processing.scanner;

/**
 * How much detail an object gets for how far away it is - three tiers, two scales, and the
 * hysteresis that makes the tiers usable.
 *
 * <h2>Why this is a class and not four constants</h2>
 *
 * <p>Six ground layers each decide how many particles one plant or one point is worth, and the
 * snow layer had already worked out the right shape for that decision: nothing inside ten blocks,
 * a little less to sixteen, less again beyond. The other layers had no such thing at all, which is
 * how one layer ends up with a sane budget and another fills the roster with the far corner of the
 * field. This is that decision in one place, so the layers share it rather than each inventing a
 * number.</p>
 *
 * <h2>The hysteresis is the point</h2>
 *
 * <p>A pure function of distance is wrong here, and the snow layer paid for finding that out: the
 * coat is sized per scan, so walking towards a plant re-sizes it, and a bare threshold makes the
 * snow visibly GROW as the player approaches - "走近了雪才长出来". Raising the far scale from 0.45
 * to 0.70 hid most of it; what actually removes it is remembering which tier an object was last in
 * and only moving when the distance clears a threshold by a margin.</p>
 *
 * <p>So the enter and leave distances differ: a tier is entered at its own threshold and left only
 * two blocks closer. An object that is walked towards holds its thin coat until the player is
 * clearly inside the nearer tier, and one that is walked away from keeps its thick coat until the
 * player is clearly outside. The remaining changes are rare and tied to real movement, which is
 * what the eye accepts.</p>
 *
 * <p>Callers keep the tier; this class never holds state, because the state belongs to the object
 * (a plant, a drip point) and the object already exists in every layer that needs it.</p>
 */
public final class DistanceLod {

    /** Full detail: at or inside {@link #MID_ENTER_SQ} gets scale 1. */
    public static final int TIER_NEAR = 0;
    /** Reduced: the coat a mid-distance object wears. */
    public static final int TIER_MID = 1;
    /** Least: everything past the far threshold. */
    public static final int TIER_FAR = 2;

    /** Entered past ten blocks (the snow layer's own calibration). */
    public static final double MID_ENTER_SQ = 10.0D * 10.0D;
    /** ...and left only inside eight, so the boundary is not a knife edge. */
    public static final double MID_LEAVE_SQ = 8.0D * 8.0D;
    /** Entered past sixteen blocks. */
    public static final double FAR_ENTER_SQ = 16.0D * 16.0D;
    /** ...and left only inside fourteen. */
    public static final double FAR_LEAVE_SQ = 14.0D * 14.0D;

    /**
     * Round 10's values: 0.70 at the far edge, 0.85 in the middle. Both were raised from 0.45/0.70
     * because the far edge read as half-melted; the budget is spent on RANGE, not on making the
     * distance look thinner than it is.
     */
    public static final float MID_SCALE = 0.85F;
    public static final float FAR_SCALE = 0.70F;

    private DistanceLod() {
    }

    /**
     * The tier {@code distanceSq} belongs to, given the tier the object was last in.
     *
     * @param current    the object's remembered tier ({@link #TIER_NEAR} for a new object)
     * @param distanceSq squared distance from the player, the same measure the layers already use
     * @return the tier to store back on the object
     */
    public static int nextTier(final int current, final double distanceSq) {
        return switch (current) {
            case TIER_MID -> distanceSq > FAR_ENTER_SQ ? TIER_FAR
                    : (distanceSq < MID_LEAVE_SQ ? TIER_NEAR : TIER_MID);
            case TIER_FAR -> distanceSq < MID_LEAVE_SQ ? TIER_NEAR
                    : (distanceSq < FAR_LEAVE_SQ ? TIER_MID : TIER_FAR);
            default -> distanceSq > FAR_ENTER_SQ ? TIER_FAR
                    : (distanceSq > MID_ENTER_SQ ? TIER_MID : TIER_NEAR);
        };
    }

    /** The multiplier a tier applies to an object's per-item count. */
    public static float scaleFor(final int tier) {
        return switch (tier) {
            case TIER_MID -> MID_SCALE;
            case TIER_FAR -> FAR_SCALE;
            default -> 1F;
        };
    }

    /** Convenience for callers that do not keep a tier: the scale for a fresh judgement. */
    public static float scaleAt(final double distanceSq) {
        return scaleFor(nextTier(TIER_NEAR, distanceSq));
    }
}
