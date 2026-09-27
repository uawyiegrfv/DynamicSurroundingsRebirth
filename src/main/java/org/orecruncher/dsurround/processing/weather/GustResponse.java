package org.orecruncher.dsurround.processing.weather;

/**
 * <p>The second of the two rolls: how much of a profile's gust amplitude actually
 * applies at a given base intensity.</p>
 *
 * <p>The amplitude alone is not enough. A flat wobble means every storm gusts the
 * same amount, which is not how weather behaves - and more importantly it throws
 * away a whole axis of "what kind of place is this":</p>
 *
 * <ul>
 *   <li><b>Decaying</b> - light rain is showery and fitful, heavy rain settles
 *       into a steady soak. This is the convective summer shower: it starts and
 *       stops, and the weak ones never commit.</li>
 *   <li><b>Growing</b> - the reverse. Drizzle is steady, and it is the downpours
 *       that come in gusts. Tropical and thundery weather behaves like this.</li>
 *   <li><b>Bell</b> - middling rain is the most unsettled; both drizzle and
 *       downpour are steady. Reads as "can't make its mind up".</li>
 *   <li><b>Flat</b> - the same wobble whatever the intensity. The legacy
 *       behaviour, and the right choice for a steady maritime soak.</li>
 * </ul>
 *
 * <p>All curves are scaled to peak at 1, so {@code amplitude} keeps its meaning
 * ("this much wobble at the curve's worst point") whichever curve is chosen.</p>
 */
public enum GustResponse {

    /** Same wobble at every intensity - the legacy behaviour. */
    FLAT {
        @Override
        public float factor(final float base) {
            return 1F;
        }
    },

    /** Light rain gusts, heavy rain settles. Convective showers. */
    DECAYING {
        @Override
        public float factor(final float base) {
            return 1F - base;
        }
    },

    /** Drizzle is steady, downpours come in gusts. Thundery and tropical. */
    GROWING {
        @Override
        public float factor(final float base) {
            return base;
        }
    },

    /** Middling rain is the most unsettled of all. */
    BELL {
        @Override
        public float factor(final float base) {
            return 4F * base * (1F - base);
        }
    };

    /**
     * How much of {@code amplitude} applies at this intensity.
     *
     * @param base the rolled base intensity, 0..1
     * @return a scaling factor in [0, 1]
     */
    public abstract float factor(final float base);

    /**
     * The wobble actually used at this intensity.
     *
     * @param base      the rolled base intensity, 0..1
     * @param amplitude the profile's amplitude, 0..1
     * @return the effective amplitude, 0..1
     */
    public final float amplitudeAt(final float base, final float amplitude) {
        return amplitude * factor(base < 0F ? 0F : (base > 1F ? 1F : base));
    }
}
