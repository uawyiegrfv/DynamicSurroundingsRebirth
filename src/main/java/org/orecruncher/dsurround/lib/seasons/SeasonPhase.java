package org.orecruncher.dsurround.lib.seasons;

/**
 * Sub-phase of a season: each season is split into thirds. Deterministic driver for
 * season-aware systems (e.g. the morning fog type table), replacing the old random
 * per-day draw.
 */
public enum SeasonPhase {
    EARLY, MID, LATE
}
