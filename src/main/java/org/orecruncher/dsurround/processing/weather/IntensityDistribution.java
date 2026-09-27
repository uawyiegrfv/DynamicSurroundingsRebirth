package org.orecruncher.dsurround.processing.weather;

import java.util.Random;

/**
 * <p>The first of the two rolls that make up a rain event: the shape of the base
 * intensity distribution.</p>
 *
 * <h2>Why a curve instead of {@code mode +/- spread}</h2>
 *
 * <p>{@code mode +/- spread} can only say "concentrated" or "spread out". It cannot
 * say <em>skewed</em> - that a biome mostly gets drizzle and occasionally a
 * deluge - and it cannot say <em>two-peaked</em>, which is what a monsoon biome
 * actually looks like (a dry mode and a wet mode, rarely anything between).
 * Those are the properties that make one biome's rain feel different from
 * another's, and they need a distribution, not a centre and a width.</p>
 *
 * <h2>Representation</h2>
 *
 * <p>A piecewise-linear CDF over the offset range [-1, 1], where -1 means
 * {@code mode - spread} and +1 means {@code mode + spread}. Sampling is
 * inverse-transform: draw one uniform, walk the CDF, interpolate. Costs a
 * handful of comparisons per rain event, so it is free.</p>
 *
 * <p>The offset form is deliberate. A curve written in absolute intensity would
 * have to be rewritten for every season; this one rides on whatever
 * {@code modeFor(season)} returns, so the seasonal tables keep working
 * unchanged and the curve only says <em>how the roll is distributed around
 * them</em>.</p>
 *
 * <p>A profile with no curve keeps the legacy triangular roll - see
 * {@link PrecipitationProfile#sampleBase}.</p>
 */
public final class IntensityDistribution {

    /** Control point offsets, ascending, in [-1, 1]. */
    private final float[] xs;
    /** Cumulative probability at {@link #xs}, ascending, ending at 1. */
    private final float[] cdf;

    private IntensityDistribution(final float[] xs, final float[] cdf) {
        this.xs = xs;
        this.cdf = cdf;
    }

    /**
     * Draw one base intensity offset.
     *
     * @param random the source - one call to {@code nextFloat()}
     * @return an offset in [xs[0], xs[last]]
     */
    public float sample(final Random random) {
        return sampleUniform(random.nextFloat());
    }

    /**
     * The same sample with the uniform supplied, so the distribution can be
     * exercised deterministically by tests and by the diagnostics command.
     *
     * @param u a uniform draw in [0, 1]
     * @return an offset in [xs[0], xs[last]]
     */
    public float sampleUniform(final float u) {
        final float p = u < 0F ? 0F : (u > 1F ? 1F : u);
        for (int i = 1; i < cdf.length; i++) {
            if (p <= cdf[i]) {
                final float span = cdf[i] - cdf[i - 1];
                final float t = span <= 0F ? 0F : (p - cdf[i - 1]) / span;
                return xs[i - 1] + (xs[i] - xs[i - 1]) * t;
            }
        }
        return xs[xs.length - 1];
    }

    /**
     * Mean offset. Diagnostics only - it is what the config dump reports as
     * "this profile's rain tends to land here".
     *
     * @return the expected offset, in [-1, 1]
     */
    public float mean() {
        float sum = 0F;
        for (int i = 1; i < cdf.length; i++)
            sum += (cdf[i] - cdf[i - 1]) * (xs[i] + xs[i - 1]) * 0.5F;
        return sum;
    }

    /**
     * Build from interleaved {@code offset, cumulativeProbability} pairs. The
     * probability half is normalised, so a caller can write weights that do not
     * happen to end at exactly 1.
     *
     * @param points interleaved {@code x, p, x, p, ...} - at least two pairs
     * @return the distribution
     */
    public static IntensityDistribution of(final float... points) {
        if (points.length < 4 || points.length % 2 != 0)
            throw new IllegalArgumentException("need interleaved x,p pairs");
        final int n = points.length / 2;
        final float[] xs = new float[n];
        final float[] cdf = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points[i * 2];
            cdf[i] = points[i * 2 + 1];
        }
        final float total = cdf[n - 1];
        if (total > 0F)
            for (int i = 0; i < n; i++)
                cdf[i] /= total;
        return new IntensityDistribution(xs, cdf);
    }

    /**
     * A symmetric, triangular-ish curve - the five-point polyline that stands in
     * for the legacy {@code mode +/- spread} roll. Provided so a profile can move
     * onto a curve without changing what it rolls today; it is <em>not</em> the
     * default path (a null curve still uses the exact legacy formula).
     *
     * @return a symmetric distribution centred on 0
     */
    public static IntensityDistribution symmetric() {
        // Triangular CDF on [-1,1] sampled at fifths: F(x) = (x+1)^2 / 2 below 0,
        // mirrored above. -> 0, .125, .5, .875, 1
        return of(-1F, 0F, -0.5F, 0.125F, 0F, 0.5F, 0.5F, 0.875F, 1F, 1F);
    }

    /**
     * Skewed towards the weak end: mostly drizzle with a long tail into real
     * downpours. The shape of a place that gets a lot of grey weather and
     * occasional cloudbursts.
     *
     * @return a distribution whose mass sits below the mode
     */
    public static IntensityDistribution skewedWeak() {
        return of(-1F, 0F, -0.6F, 0.45F, -0.2F, 0.72F, 0.2F, 0.86F, 0.6F, 0.95F, 1F, 1F);
    }

    /**
     * Skewed towards the strong end: when it rains it rains properly, with drizzle
     * as the exception. The shape of a convective tropical climate, whose wet
     * season is defined by how hard it falls rather than how often.
     *
     * @return a distribution whose mass sits above the mode
     */
    public static IntensityDistribution skewedStrong() {
        return of(-1F, 0F, -0.4F, 0.08F, 0F, 0.18F, 0.3F, 0.42F, 0.7F, 0.78F, 1F, 1F);
    }

    /**
     * Two modes with a gap between them: the dry-season drizzle and the
     * monsoon-season deluge, with little in between. This is the one shape
     * {@code mode +/- spread} cannot approximate at all.
     *
     * @return a bimodal distribution
     */
    public static IntensityDistribution bimodal() {
        return of(-1F, 0F, -0.7F, 0.30F, -0.45F, 0.42F, 0F, 0.55F,
                0.5F, 0.60F, 0.75F, 0.70F, 1F, 1F);
    }
}
