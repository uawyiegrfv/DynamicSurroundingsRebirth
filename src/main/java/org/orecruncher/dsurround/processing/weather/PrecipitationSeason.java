package org.orecruncher.dsurround.processing.weather;

/**
 * <p>The seasonal axis used to look up precipitation intensity. Collapsed to six
 * values on purpose: four temperate seasons, plus the two tropical ones.</p>
 *
 * <p>Serene Seasons models the tropics with its own {@code TropicalSeason}
 * (EARLY_DRY / MID_DRY / LATE_DRY / EARLY_WET / MID_WET / LATE_WET) instead of a
 * four-season cycle, because a tropical year really is "wet season / dry season"
 * rather than spring/summer/autumn/winter - rainfall there tracks the monsoon,
 * not the temperature. Keeping six values means one table covers both models and
 * the caller only has to decide which axis applies. The EARLY/MID/LATE phase can
 * interpolate later; collapsing it now keeps the table readable.</p>
 */
public enum PrecipitationSeason {

    SPRING,
    SUMMER,
    AUTUMN,
    WINTER,
    /** Tropical dry season (Serene Seasons EARLY/MID/LATE_DRY). */
    TROPICAL_DRY,
    /** Tropical wet season (Serene Seasons EARLY/MID/LATE_WET). */
    TROPICAL_WET,

    /**
     * No season information at all - vanilla, or a season mod that reported
     * nothing.
     *
     * <p>Without this the answer was {@code null}, and {@code modeFor(null)}
     * fell back to the <em>summer</em> column: a vanilla world therefore only
     * ever saw one sixth of the table and never the spring/autumn/winter
     * intensities a seasonal world sees over a year.</p>
     *
     * <p>It is deliberately <b>not</b> a seventh hand-written column - see
     * {@code PrecipitationProfile.vanillaMode()} and
     * {@code vanillaSpread()}. It is the four temperate columns collapsed into
     * one distribution, so it cannot drift out of step with them and there is
     * no seventh number per profile to maintain.</p>
     */
    VANILLA;

    /** Number of entries in every per-profile seasonal table. */
    public static final int COUNT = values().length;
}
