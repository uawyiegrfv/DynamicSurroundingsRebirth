package org.orecruncher.dsurround.config;

/**
 * Style of footprint to render, matching the original 1.12.2 FootprintStyle.
 * The footprint.png atlas holds one 32x32 cell per style, each cell containing
 * a mirrored left/right print.
 */
public enum FootprintStyle {
    SHOE,
    SQUARE,
    HORSESHOE,
    BIRD,
    PAW,
    SQUARE_SOLID,
    LOWRES_SQUARE;

    public static FootprintStyle getStyle(final int v) {
        if (v < 0 || v >= values().length)
            return LOWRES_SQUARE;
        return values()[v];
    }
}
