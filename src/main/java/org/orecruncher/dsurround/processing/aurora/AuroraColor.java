package org.orecruncher.dsurround.processing.aurora;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Colour palettes used by the aurora. Ported from 1.12.2 Dynamic Surroundings
 * (MIT). The original relied on OreCruncher's lib {@code Color} (an immutable
 * float-component colour); 26.1 has no equivalent, so a minimal immutable
 * float colour is provided here instead.
 */
public final class AuroraColor {

    /** Immutable RGB colour with components in the [0,1] range. */
    public static final class ColorF {
        public final float red;
        public final float green;
        public final float blue;

        public ColorF(final float r, final float g, final float b) {
            this.red = r;
            this.green = g;
            this.blue = b;
        }

        /** Scale brightness by {@code (1 + factor)} and clamp to [0,1]. */
        public ColorF luminance(final float factor) {
            final float s = 1.0F + factor;
            return new ColorF(
                    Math.min(1.0F, this.red * s),
                    Math.min(1.0F, this.green * s),
                    Math.min(1.0F, this.blue * s));
        }

        @Override
        public String toString() {
            return "[%d,%d,%d]".formatted(
                    (int) (this.red * 255), (int) (this.green * 255), (int) (this.blue * 255));
        }
    }

    /**
     * Color that forms the base of the aurora and is the brightest.
     */
    public final ColorF baseColor;

    /**
     * Color that forms the top of the aurora and usually fades to black.
     */
    public final ColorF fadeColor;

    /**
     * Mid-band color for aurora styles that use it.
     */
    public final ColorF middleColor;

    private static final List<AuroraColor> COLOR_SETS = new ArrayList<>();

    private static final float WARMER = 0.3F;
    private static final float COOLER = -0.3F;

    private static ColorF c(final int r, final int g, final int b) {
        return new ColorF(r / 255.0F, g / 255.0F, b / 255.0F);
    }

    static {
        COLOR_SETS.add(new AuroraColor(c(0x0, 0xff, 0x99), c(0x33, 0xff, 0x00)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0x00, 0xff), c(0x00, 0xff, 0x00))); // blue -> green
        COLOR_SETS.add(new AuroraColor(c(0xff, 0x00, 0xff), c(0x00, 0xff, 0x00))); // magenta -> green
        COLOR_SETS.add(new AuroraColor(c(0x4b, 0x00, 0x82), c(0x00, 0xff, 0x00))); // indigo -> green
        COLOR_SETS.add(new AuroraColor(c(0x40, 0xe0, 0xd0), c(0x90, 0xee, 0x90))); // turquoise -> light green
        COLOR_SETS.add(new AuroraColor(c(0xff, 0xff, 0x00), c(0xff, 0x00, 0x00))); // yellow -> red
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00), c(0xff, 0x00, 0x00))); // green -> red
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00), c(0xff, 0xff, 0x00))); // green -> yellow
        COLOR_SETS.add(new AuroraColor(c(0xff, 0x00, 0x00), c(0xff, 0xff, 0x00))); // red -> yellow
        COLOR_SETS.add(new AuroraColor(c(0x00, 0x00, 0x80), c(0x4b, 0x00, 0x82))); // navy -> indigo
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0xff), c(0xff, 0x00, 0xff))); // cyan -> magenta
        COLOR_SETS.add(new AuroraColor(c(0x7c, 0xfc, 0x00), c(0xff, 0x00, 0x00), c(0x00, 0x00, 0xff))); // aurora

        // Warmer versions
        COLOR_SETS.add(new AuroraColor(c(0xff, 0xff, 0x00).luminance(WARMER), c(0xff, 0x00, 0x00).luminance(WARMER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00).luminance(WARMER), c(0xff, 0x00, 0x00).luminance(WARMER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00).luminance(WARMER), c(0xff, 0xff, 0x00).luminance(WARMER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0x00, 0xff).luminance(WARMER), c(0x00, 0xff, 0x00).luminance(WARMER)));
        COLOR_SETS.add(new AuroraColor(c(0x4b, 0x00, 0x82).luminance(WARMER), c(0x00, 0xff, 0x00).luminance(WARMER)));
        COLOR_SETS.add(new AuroraColor(c(0x7c, 0xfc, 0x00).luminance(WARMER), c(0xff, 0x00, 0x00).luminance(WARMER),
                c(0x00, 0x00, 0xff).luminance(WARMER)));

        // Cooler versions
        COLOR_SETS.add(new AuroraColor(c(0xff, 0xff, 0x00).luminance(COOLER), c(0xff, 0x00, 0x00).luminance(COOLER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00).luminance(COOLER), c(0xff, 0x00, 0x00).luminance(COOLER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0xff, 0x00).luminance(COOLER), c(0xff, 0xff, 0x00).luminance(COOLER)));
        COLOR_SETS.add(new AuroraColor(c(0x00, 0x00, 0xff).luminance(COOLER), c(0x00, 0xff, 0x00).luminance(COOLER)));
        COLOR_SETS.add(new AuroraColor(c(0x4b, 0x00, 0x82).luminance(COOLER), c(0x00, 0xff, 0x00).luminance(COOLER)));
        COLOR_SETS.add(new AuroraColor(c(0x7c, 0xfc, 0x00).luminance(COOLER), c(0xff, 0x00, 0x00).luminance(COOLER),
                c(0x00, 0x00, 0xff).luminance(COOLER)));
    }

    private AuroraColor(final ColorF base, final ColorF fade) {
        this(base, fade, base);
    }

    private AuroraColor(final ColorF base, final ColorF fade, final ColorF mid) {
        this.baseColor = base;
        this.fadeColor = fade;
        this.middleColor = mid;
    }

    public static AuroraColor get(final Random random) {
        final int idx = random.nextInt(COLOR_SETS.size());
        return COLOR_SETS.get(idx);
    }

    public static int testId() {
        return COLOR_SETS.size() - 1;
    }
}
