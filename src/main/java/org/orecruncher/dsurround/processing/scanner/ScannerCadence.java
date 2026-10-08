package org.orecruncher.dsurround.processing.scanner;

/**
 * The scan cadences the ground layers share, in one place.
 *
 * <p>Five layers scan the world around the player - eaves and icicles through
 * {@link DripScanner} (33x33x27, 29403 block reads), the three foliage layers through
 * {@link FoliageScan} (33x33x7 at the configured range) - and each of them decided
 * independently when a scan was due. That is how the same number ends up written five times with
 * four different values, and how one layer ends up with a spike the others do not have: the
 * walk test is the trigger that scales with the player's speed, and three of the five had no
 * ceiling on it at all.</p>
 *
 * <p>Only the shared numbers live here. A layer's own period stays with the layer, because that
 * one really does differ: twenty ticks for the eaves, sixty for the icicles, thirty for the rain
 * foliage, and the snow and dew layers run on their own clocks for reasons their comments give.</p>
 */
public final class ScannerCadence {

    /**
     * Floor on how often a MOVE-triggered rescan may fire, in ticks.
     *
     * <p>Every layer that watches the player's position has the same two triggers: a period, and
     * "the player moved far enough that the box no longer covers what they can see". The second
     * one is the one that has to be rationed, because its rate is the player's speed - at elytra
     * speed (about 1.5 blocks a tick) a four-block test is satisfied every three ticks, so the
     * scan ran six times as often as its period intended and the cost scaled with how fast the
     * player travelled. The maintainer's report was exactly that: fine standing still, a spike in
     * flight.</p>
     *
     * <p>Ten ticks is chosen to be under the period of every layer that uses it (twenty is the
     * shortest), so on foot it never binds - a walked-up-to plant or eave is answered inside half
     * a second, which is faster than the eye can attribute - while a fast traveller is held to two
     * scans a second instead of six.</p>
     *
     * <p>It rations only the EXTRA scans. A layer's period and its still-valid checks are
     * untouched, so a stationary player sees no change at all.</p>
     */
    public static final int MOVE_RESCAN_MIN_TICKS = 10;

    private ScannerCadence() {
    }
}
