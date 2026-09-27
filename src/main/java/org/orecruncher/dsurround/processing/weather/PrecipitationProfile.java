package org.orecruncher.dsurround.processing.weather;

import net.minecraft.util.Mth;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.biome.biometraits.BiomeTraits;

import java.util.Random;

/**
 * <p>A named "kind of weather" that a biome tends to get. Biomes are matched to
 * one of these, and the profile supplies the shape of the precipitation
 * intensity distribution rather than a single number.</p>
 *
 * <p>Why presets instead of one table per biome: there are thousands of biomes
 * once modded ones count, and 1.12.2 never had per-biome data either - it only
 * had a handful of behavioural hooks ({@code usesTropicalSeasons}, and the
 * dust/desert flag). A small set of named profiles keeps the data explainable
 * and, crucially, lets a modded biome land somewhere sensible via the trait
 * system even though nobody wrote an entry for it.</p>
 *
 * <p>Matching is deliberately done on {@link BiomeTrait} rather than on biome
 * ids or raw tags. Traits are DSR's normalised, mod-compatible view of a biome:
 * {@code BiomeTagAnalyzer} derives them from the vanilla/mod biome tags, and
 * {@code BiomeNameFallbackAnalyzer} falls back to the biome's own climate
 * numbers (temperature -> COLD/TEMPERATE/HOT, downfall -> DRY/WET) when no tag
 * says anything - so a modded biome with no DSR entry still gets a band.</p>
 *
 * <p>Each profile carries a seasonal table of {@code mode} values plus one
 * {@code spread}: the centre and width of the intensity distribution that is
 * rolled once per rain event. Intensity is a <em>rate</em> - how hard it is
 * precipitating while it precipitates - and the seasonal split is where
 * "this biome in this season" actually lives.</p>
 */
public enum PrecipitationProfile {

    //  Seasonal tables are ordered SPRING, SUMMER, AUTUMN, WINTER,
    //  TROPICAL_DRY, TROPICAL_WET (see PrecipitationSeason).
    //
    //  Ground truth for the dry end, read from vanilla biome data:
    //  has_precipitation=false for desert, badlands (all four variants), savanna,
    //  savanna_plateau and windswept_savanna - downfall 0.0 for all of them. So
    //  the savanna family genuinely does not rain in vanilla and belongs to ARID,
    //  not to a "dry but it does rain" bucket. With Serene Seasons installed the
    //  savanna's tropical WET season is where it gets intense rain and its DRY
    //  season is a hard zero - that is exactly what the TROPICAL_* columns are for.
    //
    //  Spreads are deliberately tight (0.15-0.20): a wide spread makes consecutive
    //  storms feel unrelated to one another, which reads as noise rather than weather.

    /**
     * Deserts, badlands and savanna: essentially dry, unless the monsoon says
     * otherwise. A desert event is short and fitful - it arrives fast and the weak
     * ones never settle, which is what {@code DECAYING} buys: weak rolls wobble
     * hard, so a 0.05 event reads as a cloudburst that could not commit rather than
     * as a drizzle that forgot to stop.
     */
    ARID(
            new float[] { 0.02F, 0.06F, 0.02F, 0.01F, 0.00F, 0.45F },
            0.21F,
            0.00F,
            0.30F,
            8F,
            0.45F,
            GustResponse.DECAYING,
            // Desert precipitation is not a little rain: it is usually nothing and
            // occasionally a cloudburst. No symmetric spread can express that gap,
            // which is what a bimodal shape is for.
            IntensityDistribution.bimodal()),

    /**
     * Open temperate ground: sparse, short-lived rain, convective summer peak.
     * Same decaying wobble as the desert, milder - a plain shower builds faster
     * than a coastal soak and never really steadies.
     */
    OPEN(
            new float[] { 0.40F, 0.50F, 0.35F, 0.20F, 0.10F, 0.55F },
            0.28F,
            0.15F,
            1.00F,
            18F,
            0.34F,
            GustResponse.DECAYING,
            // Convective rain over open ground: mostly light, with a long tail into
            // real downpours. The shape of a place that gets a lot of grey weather
            // and occasional cloudbursts.
            IntensityDistribution.skewedWeak()),

    /**
     * Steady, unremarkable rain - the DEFAULT when nothing else matches, and the
     * baseline every other profile is a deliberate departure from: a 25s attack and
     * a flat wobble of roughly a quarter. Left on the four-argument form on purpose
     * so the numbers here stay visibly "nothing special".
     */
    TEMPERATE(
            new float[] { 0.50F, 0.62F, 0.52F, 0.32F, 0.15F, 0.65F },
            0.28F,
            0.20F,
            1.00F),

    /**
     * Maritime: autumn and winter wettest, summer less so. A coastal low takes a
     * long time to arrive and then just sits there - the longest attack and the
     * smallest wobble of any profile.
     */
    COASTAL(
            new float[] { 0.55F, 0.58F, 0.70F, 0.72F, 0.25F, 0.70F },
            0.28F,
            0.30F,
            0.60F,
            40F,
            0.14F,
            GustResponse.FLAT,
            null),

    /**
     * Swamps, mangroves, lush caves: persistent, fairly even through the year.
     * Slower to build than temperate and calmer once there.
     */
    WETLAND(
            new float[] { 0.58F, 0.62F, 0.60F, 0.50F, 0.30F, 0.70F },
            0.25F,
            0.15F,
            0.70F,
            35F,
            0.22F,
            GustResponse.FLAT,
            null),

    /**
     * Jungle: heavy year round, monsoon-dominated, strongly thundery.
     *
     * <p>The only profile that ships a curve. {@code skewedStrong} is what makes it
     * a <em>rainforest</em> rather than merely "a wet place": the roll is biased
     * above the mode, so most events land heavy and drizzle is the exception. That
     * bias sits on top of the seasonal mode, so the winter and tropical-dry columns
     * still pull it down - the curve says "when it rains here it rains properly",
     * not "it always rains hard here".</p>
     *
     * <p>{@code GROWING} is the other half: drizzle is steady and it is the
     * downpours that arrive in gusts, which is how convective tropical rain
     * actually behaves.</p>
     *
     * <p>The attack is the shortest of any rainy profile. Tropical rain is
     * convective, not frontal: it does not build over forty minutes, it arrives
     * in a few and is already at full force. A slow climb here is the single most
     * recognisably wrong thing a jungle storm can do - it reads as a European
     * drizzle that happens to be standing in a rainforest.</p>
     */
    TROPICAL(
            new float[] { 0.72F, 0.85F, 0.75F, 0.60F, 0.40F, 0.85F },
            0.25F,
            0.70F,
            0.40F,
            7F,
            0.45F,
            GustResponse.GROWING,
            IntensityDistribution.skewedStrong()),

    /**
     * High and cold: summer rain, winter snow, low liquid equivalent in winter.
     * {@code BELL} - middling weather on a mountain slope is the most unsettled
     * kind, while both drizzle and a proper snowfall are steady.
     */
    ALPINE(
            new float[] { 0.38F, 0.52F, 0.34F, 0.22F, 0.10F, 0.50F },
            0.28F,
            0.10F,
            1.00F,
            22F,
            0.30F,
            GustResponse.BELL,
            // As OPEN: mostly light with a tail into heavier falls. High ground gets
            // a lot of drizzle and the odd proper storm.
            IntensityDistribution.skewedWeak()),

    /**
     * Dust storms: <em>the same weather event a desert gets</em>, expressed as
     * airborne dust instead of rain.
     *
     * <p>Why a separate profile at all - {@link #ARID} already covers deserts.
     * Because ARID answers "how much water reaches the ground", and in a desert
     * that is essentially nothing (mode 0.02-0.06). The dust storm is not the
     * water, it is the <em>vigour</em> of the same event: a desert cloudburst
     * evaporates before it lands (virga) and its outflow is what picks the dust
     * up. One number cannot be both "almost no rain" and "a violent storm", so
     * the event carries two magnitudes - see
     * {@code PrecipitationIntensity.dustIntensity()}.</p>
     *
     * <p>Seasonal: dust follows the <em>dry</em> side. The tropical columns are
     * deliberately anti-correlated with ARID's - TROPICAL_DRY is the dusty one
     * (0.62) and TROPICAL_WET is nearly clean (0.15), because in the wet season
     * the same convection actually delivers water instead of blowing dust.</p>
     *
     * <p><b>The distribution is a placeholder.</b> Bimodal is the right family -
     * a dust event is either a faint haze or a real haboob, with little in
     * between, and no symmetric spread can express that gap - but the exact
     * mode / spread numbers are unvalidated. They are here to be replaced once
     * there is something to look at.</p>
     */
    DUST_STORM(
            new float[] { 0.45F, 0.55F, 0.40F, 0.30F, 0.62F, 0.15F },
            0.30F,
            0.00F,
            0.70F,
            12F,
            0.38F,
            GustResponse.GROWING,
            // PLACEHOLDER - see the class comment. Bimodal: haze or haboob.
            IntensityDistribution.bimodal()),

    /** Nowhere with a weather cycle: nether, end, void, underground. */
    WASTELAND(
            new float[] { 0F, 0F, 0F, 0F, 0F, 0F },
            0F,
            0F,
            0F,
            // Literal, not DEFAULT_ATTACK_SECONDS: an enum constant cannot forward
            // reference a static field declared later in the same class.
            25F,
            0F,
            GustResponse.FLAT,
            null);

    /** Centre of the intensity distribution, indexed by {@link PrecipitationSeason}. */
    private final float[] seasonalMode;
    /** Width of the intensity distribution, 0..1. */
    public final float spread;
    /** How much this profile favours thunder on top of the intensity roll, 0..1. */
    public final float thunderBias;
    /** How strongly seasons move this profile, 0 (immune) .. 1 (fully exposed). */
    public final float seasonSensitivity;
    /** Seconds an event takes to climb from nothing to its rolled peak. */
    public final float attackSeconds;
    /** Size of the gusting wobble at {@link #gustResponse}'s worst point, 0..1. */
    public final float gustAmplitude;
    /** Which intensities actually wobble - see {@link GustResponse}. */
    public final GustResponse gustResponse;
    /** Shape of the base roll, or null for the legacy triangular roll. */
    private final IntensityDistribution distribution;

    /** Seconds an event takes to reach its peak, when a profile says nothing. */
    public static final float DEFAULT_ATTACK_SECONDS = 25F;
    /** Wobble size, when a profile says nothing. Matches the pre-curve constant. */
    public static final float DEFAULT_GUST_AMPLITUDE = 0.26F;

    /**
     * Chance that an event is an outlier, pulled towards one end of the range
     * instead of drawn from the profile's shape. See the comment in
     * {@link #sampleBase(PrecipitationSeason, Random, boolean)} for why this
     * exists; 0 disables outliers entirely and returns every profile to its
     * bounded span.
     */
    public static final float EXTREME_RATE = 0.14F;

    PrecipitationProfile(final float[] seasonalMode, final float spread,
                         final float thunderBias, final float seasonSensitivity) {
        this(seasonalMode, spread, thunderBias, seasonSensitivity,
                DEFAULT_ATTACK_SECONDS, DEFAULT_GUST_AMPLITUDE, GustResponse.FLAT, null);
    }

    PrecipitationProfile(final float[] seasonalMode, final float spread,
                         final float thunderBias, final float seasonSensitivity,
                         final float attackSeconds, final float gustAmplitude,
                         final GustResponse gustResponse,
                         final IntensityDistribution distribution) {
        this.seasonalMode = seasonalMode;
        this.spread = spread;
        this.thunderBias = thunderBias;
        this.seasonSensitivity = seasonSensitivity;
        this.attackSeconds = attackSeconds;
        this.gustAmplitude = gustAmplitude;
        this.gustResponse = gustResponse == null ? GustResponse.FLAT : gustResponse;
        this.distribution = distribution;
    }

    /** The four temperate columns - everything a year without a monsoon has. */
    private static final int TEMPERATE_SEASONS = 4;

    /**
     * Centre to use when there is no calendar: the mean of the four temperate
     * columns.
     *
     * <p>Derived rather than tabulated on purpose. A seventh hand-written
     * column would be eight more numbers that have to be kept in step with the
     * four they summarise, and every one of them is a place for the two to
     * drift apart silently.</p>
     */
    public float vanillaMode() {
        float sum = 0F;
        for (int i = 0; i < TEMPERATE_SEASONS; i++)
            sum += this.seasonalMode[i];
        return sum / TEMPERATE_SEASONS;
    }

    /**
     * Centre to roll against when there is no calendar: <b>one of the four
     * temperate columns, drawn per event</b>.
     *
     * <p>Not their mean, and not the mean with a widened spread. Widening the
     * spread was the first attempt and it is wrong for a reason worth recording:
     * every curve here is skewed, and skew is multiplicative in the width - the
     * median sits a fixed <em>fraction of the width</em> below the mode, so a
     * wider roll pushes the median down proportionally. Measured on OPEN: summer
     * alone gives a median of 0.36; mean-of-seasons with the spread widened by
     * half the seasonal range gives 0.16 - weaker than <em>every</em> season it
     * was built from, including winter. That is the opposite of "see the whole
     * year".</p>
     *
     * <p>Drawing a season instead keeps each event's distribution exactly the
     * shape that season has. A vanilla world therefore gets spring events and
     * winter events rather than one lukewarm blend, the union is precisely what
     * a seasonal world sees over a year, and it costs one integer per event.</p>
     */
    private float vanillaMode(final Random random) {
        return this.seasonalMode[random.nextInt(TEMPERATE_SEASONS)];
    }

    /** Season-agnostic centre - the summer value, for callers with no season data. */
    public float mode() {
        return seasonalMode[PrecipitationSeason.SUMMER.ordinal()];
    }

    /**
     * Centre of the intensity distribution for a season, 0..1.
     *
     * @param season the season to look up - null falls back to {@link #mode()}
     * @return distribution centre in [0, 1]
     */
    public float modeFor(PrecipitationSeason season) {
        if (season == null)
            return mode();
        if (season == PrecipitationSeason.VANILLA)
            return vanillaMode();
        return seasonalMode[season.ordinal()];
    }

    /**
     * <b>Roll one.</b> Base intensity for one rain event: the seasonal mode pushed
     * around by this profile's curve (or by the legacy triangle when the profile
     * has no curve). Called once per event, not per tick - the result is the event's
     * ceiling and stays put for its whole duration.
     *
     * @param season the season column to centre on - null falls back to summer
     * @param random the source
     * @return base intensity in [0, 1]
     */
    public float sampleBase(PrecipitationSeason season, Random random) {
        return sampleBase(season, random, false);
    }

    /**
     * Roll one, with the storm flag.
     *
     * <p>When the world is thundering the roll is pushed towards the heavy end.
     * Thunderstorms are not simply rain that happens to be accompanied by noise -
     * they are the top of the convective scale, and a thunderstorm rolled at 0.2
     * is a contradiction the player can see: lightning and a drizzle. Rather than
     * add a second distribution per profile, the draw is skewed: the uniform is
     * square-rooted, which keeps it continuous on [0, 1] and moves its mass
     * upwards without ever producing an impossible value.</p>
     *
     * @param season the season column to centre on - null falls back to summer
     * @param random the source
     * @param stormy true when the world is thundering as this event starts
     * @return base intensity in [0, 1]
     */
    public float sampleBase(PrecipitationSeason season, Random random, boolean stormy) {
        // VANILLA rolls against one of the four temperate columns, not
        // against their mean - see vanillaMode(Random).
        final float mode = season == PrecipitationSeason.VANILLA
                ? vanillaMode(random)
                : modeFor(season);

        // Outlier storms, drawn before the shape.
        //
        // Every shape above is bounded: offset lives on [-1, 1], so the base can
        // never leave [mode - spread, mode + spread]. That is not a tuning
        // problem and not a bug in the sampling - it is what a bounded support
        // means. A normal distribution has unbounded support, which is why even
        // a badly centred one still throws up the occasional freak; these cannot
        // throw one up at any probability, because out there the probability is
        // exactly zero. A plain where the base sits between 0.30 and 0.70 has no
        // cloudbursts, ever, and no reason to expect one.
        //
        // So an outlier is not drawn as an offset - it is drawn as a pull towards
        // whichever end of the range it belongs at. Most events stay exactly
        // where they were (the median does not move); a few land far outside the
        // profile's usual span, which is what makes the next storm worth waiting
        // for. Heavier more often than lighter, because that is the direction
        // weather surprises in.
        if (random.nextFloat() < EXTREME_RATE) {
            if (random.nextFloat() > 0.35F)
                return Mth.clamp(mode + (1F - mode) * (0.55F + random.nextFloat() * 0.45F), 0F, 1F);
            return Mth.clamp(mode * (0.15F + random.nextFloat() * 0.35F), 0F, 1F);
        }

        if (distribution == null) {
            // Legacy shape: the mean of two uniforms is triangular on 0..1 with
            // its peak at 0.5, so it maps straight onto mode +/- spread.
            float t = (random.nextFloat() + random.nextFloat()) * 0.5F;
            if (stormy)
                t = (float) Math.sqrt(t);
            return Mth.clamp(mode + (t * 2F - 1F) * this.spread, 0F, 1F);
        }
        float offset = distribution.sample(random);
        if (stormy)
            offset = Math.max(offset, 0.15F);
        return Mth.clamp(mode + offset * this.spread, 0F, 1F);
    }

    /**
     * <b>Roll two's shape.</b> How much wobble applies at a given base intensity,
     * before the per-event gustiness multiplier. Curve and amplitude together are
     * what separate "a steady soak" from "a fitful shower" at the same average
     * intensity.
     *
     * @param base the rolled base intensity, 0..1
     * @return effective amplitude in [0, 1]
     */
    public float gustAmplitudeAt(float base) {
        return this.gustResponse.amplitudeAt(base, this.gustAmplitude);
    }

    /** Whether this profile ships a curve rather than the legacy triangle. */
    public boolean hasCurve() {
        return this.distribution != null;
    }

    /**
     * Mean offset the curve applies, 0 when there is no curve. Diagnostics only -
     * the number the config dump prints next to a profile.
     *
     * @return mean offset in [-1, 1]
     */
    public float curveBias() {
        return this.distribution == null ? 0F : this.distribution.mean();
    }

    /**
     * Resolve the profile for a set of biome traits.
     *
     * <p>Order matters and is intentionally specific-first: the biome
     * "personality" traits (jungle, swamp, desert...) are checked before the
     * coarse climate bands, and the climate bands before the terrain traits.
     * Anything unmatched falls through to {@link #TEMPERATE}, which is also
     * what a modded biome with no recognisable traits gets.</p>
     *
     * @param traits the biome's traits - may be null
     * @return the matching profile, never null
     */
    public static PrecipitationProfile resolve(final BiomeTraits traits) {
        if (traits == null)
            return TEMPERATE;

        // Dimensions and covered areas: no weather cycle at all.
        if (hasAny(traits, BiomeTrait.NETHER, BiomeTrait.THEEND, BiomeTrait.VOID,
                BiomeTrait.UNDERGROUND))
            return WASTELAND;

        // Biome personality first - these are more specific than climate bands.
        if (hasAny(traits, BiomeTrait.JUNGLE))
            return TROPICAL;
        if (hasAny(traits, BiomeTrait.SWAMP, BiomeTrait.MUSHROOM, BiomeTrait.LUSH))
            return WETLAND;

        // The three dry families. Vanilla marks desert, badlands (all four
        // variants), savanna, savanna_plateau and windswept_savanna as
        // has_precipitation=false, so SAVANNA belongs here - it is not a
        // "dry but it still rains" biome. Its wet season is handled by the
        // TROPICAL_WET column, not by moving it out of ARID.
        if (hasAny(traits, BiomeTrait.DESERT, BiomeTrait.SANDY, BiomeTrait.SAVANNA))
            return ARID;

        // Water.
        if (hasAny(traits, BiomeTrait.OCEAN, BiomeTrait.BEACH, BiomeTrait.RIVER))
            return COASTAL;

        // Cold / high ground: snow-dominated rather than just "less rain".
        if (hasAny(traits, BiomeTrait.ICY, BiomeTrait.MOUNTAIN, BiomeTrait.PLATEAU))
            return ALPINE;

        // Climate bands - also the path a modded biome reaches via
        // BiomeNameFallbackAnalyzer's downfall/temperature thresholds.
        if (hasAny(traits, BiomeTrait.WET))
            return WETLAND;
        if (hasAny(traits, BiomeTrait.DRY))
            return ARID;

        // Open ground, and everything we have no opinion about.
        if (hasAny(traits, BiomeTrait.PLAINS))
            return OPEN;

        return TEMPERATE;
    }

    /**
     * The dust-storm profile for a set of biome traits, or null where the biome
     * has no dust to blow.
     *
     * <p><b>Defined as "whatever {@link #resolve} calls arid", not by a second
     * copy of the trait test.</b> It used to re-test DESERT/SANDY/SAVANNA here,
     * which is not the same predicate {@code resolve} uses - that one also
     * reaches {@link #ARID} through the DRY band and through traits this list
     * never mentioned. The overlap was most of the truth and not all of it, so
     * biomes existed that were arid for rain and had no dust at all, which is
     * not a thing that can be true: a desert cloudburst is the dust.</p>
     *
     * <p>Asking the rain resolver is what makes the two answers the same answer.
     * It also means a new dry biome only has to be classified once.</p>
     *
     * @param traits the biome's traits - may be null
     * @return {@link #DUST_STORM}, or null when this biome gets no dust storm
     */
    public static PrecipitationProfile resolveDust(final BiomeTraits traits) {
        if (traits == null)
            return null;
        return resolve(traits) == ARID ? DUST_STORM : null;
    }

    private static boolean hasAny(final BiomeTraits traits, final BiomeTrait... wanted) {
        for (var t : wanted)
            if (traits.contains(t))
                return true;
        return false;
    }
}
