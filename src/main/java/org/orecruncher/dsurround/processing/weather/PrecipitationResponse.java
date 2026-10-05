package org.orecruncher.dsurround.processing.weather;

import net.minecraft.util.Mth;

/**
 * <p>How one <em>consumer</em> of precipitation intensity turns it into its own
 * driving value.</p>
 *
 * <h2>Why there is no single coefficient</h2>
 *
 * <p>Intensity is one number, but sky darkness, fog density, rain sound and the
 * rain geometry itself are <em>different senses</em> and they do not track the
 * same number linearly. Scaling all of them by the raw intensity is what makes
 * a graded storm feel wrong: a drizzle darkens the sky as much as a downpour
 * does, and the sky snaps dark the instant rain starts while the rain itself is
 * still building.</p>
 *
 * <p>Three separate things have to be tuned per channel, and this class carries
 * all three:</p>
 *
 * <ul>
 *   <li><b>{@code gamma}</b> - the shape. Above 1 a channel barely reacts to
 *       light rain and only comes alive near the top (sky darkness: drizzle does
 *       not dim the world). Below 1 it saturates early (fog: even light rain
 *       hangs in the air).</li>
 *   <li><b>{@code saturateAt}</b> - the intensity at which the channel is spent.
 *       Fog is fully thick well before the maximum downpour; sky darkness is
 *       not.</li>
 *   <li><b>{@code riseSeconds} / {@code fallSeconds}</b> - how sluggishly the
 *       channel follows up and down. These are deliberately <em>not</em> the
 *       same number: humidity arrives faster than it leaves, and sky colour lags
 *       in both directions. Getting the ratio wrong is what produces "the sky
 *       went dark ten seconds before the rain did".</li>
 * </ul>
 *
 * <h2>The head and the tail are separate problems</h2>
 *
 * <p>The middle of a storm is easy - the channel sits on its target. The two
 * ends are where a channel gives itself away, and they need opposite
 * treatment:</p>
 *
 * <ul>
 *   <li><b>Head.</b> The channel must not start moving before the intensity has
 *       committed to rising, otherwise every gust wobble near zero shows up as
 *       the sky breathing. The rise time constant does this.</li>
 *   <li><b>Tail.</b> An exponential decay never actually reaches zero - it only
 *       approaches it. A channel released that way sits at 0.01 forever, which
 *       is invisible on screen but fatal for anything that asks "has this
 *       finished yet": the precipitation takeover holds the frame until every
 *       channel has settled, and a channel that never settles means the frame is
 *       never handed back. So once the target is zero the tail is swept by a
 *       <em>linear</em> term that guarantees arrival within
 *       {@code releaseSeconds}, and anything below {@link #SETTLE_EPSILON} is
 *       snapped to exactly zero.</li>
 * </ul>
 *
 * <h2>Two different intensities feed in</h2>
 *
 * <p>{@link PrecipitationIntensity#intensity()} is phase-gated - it is zero
 * where nothing is actually falling on the player (under a roof, in a cave).
 * {@link PrecipitationIntensity#ambient()} is not. Sky, fog and sound are
 * driven by {@code ambient()} because they describe the weather <em>around</em>
 * the player: stand in a doorway during a downpour and the sky is still dark
 * and it is still loud. The rain geometry is driven by {@code intensity()}
 * because nothing is landing on you. Which one a channel reads is stated per
 * constant below.</p>
 */
public final class PrecipitationResponse {

    /**
     * L1 root - the rain geometry itself.
     *
     * <p>Fed the already-low-passed gated intensity, so it adds no smoothing of
     * its own: doing so would double the lag and make the attack read as
     * sluggish. Everything that happens because rain physically landed derives
     * from this one - see {@link #SURFACE}.</p>
     */
    public static final PrecipitationResponse GEOMETRY =
            new PrecipitationResponse("geometry", 1F, true, 1F, 0F, 0F, 0F, 0F);

    /**
     * L1 root - the atmospheric process behind the event: what the air is doing,
     * as opposed to what is falling out of it. Sky, clouds and fog all derive
     * from here.
     *
     * <p>Its constants are the fastest of the atmospheric family, because humidity
     * is the first thing you notice when rain starts and the fog has to be able
     * to come up early. Sky and clouds add their own delay on top rather than
     * keeping a clock of their own - see the class comment on why.</p>
     */
    public static final PrecipitationResponse ATMOSPHERE =
            new PrecipitationResponse("atmosphere", 1F, 1F, 2F, 5F, 7F);

    /**
     * How far into the event we are, 0..1 - this is what takes the sun away.
     *
     * <p>Deliberately not the intensity. The sun going away is about cloud cover
     * <em>arriving</em>, which happens while the storm builds, not about how hard
     * it happens to be raining right now. So this tracks the attack ramp, hits
     * full at the end of the attack and then holds: a storm does not re-expose
     * the sun every time the intensity gusts down. It comes back over the
     * release, which is also where vanilla puts it.</p>
     *
     * <p>Takes the seven-argument constructor, which is what marks a channel as
     * reading the attack clock rather than an intensity.</p>
     */
    public static final PrecipitationResponse SUN =
            new PrecipitationResponse("sun", 1F, 1F, 0F, 2F, 3F, 5F);

    /**
     * Water hanging off a roof edge - the eave drip, on the clock water on a
     * roof actually keeps.
     *
     * <p>A root, not a derived channel, and the reason is the gate. Every
     * drip point is under something by definition - under an eave, under a
     * balcony, under a canopy - and the gated chain ({@link #GEOMETRY} and
     * everything fed from it) is zero exactly there, because intensity() is
     * about what lands on the player. Deriving from it would silence the one
     * spot the effect exists for. It cannot derive from {@link #ATMOSPHERE}
     * either: that root is dust-aware, and a sandstorm must not shake water
     * off a roof. So this reads the ambient intensity directly - a root is
     * where "which intensity feeds me" is decided.</p>
     *
     * <p>gamma 1 / saturateAt 1: pure delay, the {@link #WET} discipline -
     * the shape belongs to the rain, this channel only says when the roof has
     * water to shed.
     * <ul>
     *   <li><b>rise 7s</b> - a roof sheds almost as soon as it is wet, but
     *       not on the very first tick; also short enough to smear the gust
     *       wobble.</li>
     *   <li><b>fall 45s</b> - easing rain thins the sheet on the roof. Was 18s,
     *       which drained the channel fast enough that the tail was over in
     *       about twenty seconds of listening: the rate is quadratic in this
     *       value, so halving the value quarters what you hear.</li>
     *   <li><b>release 150s</b> - the after-rain tail. A saturated roof keeps
     *       dripping long after the cloud has passed, and the maintainer's
     *       floor was "at least 50s of audible drips". The tail is the min of
     *       this sweep and the 18s fall above, and the rate fades with the
     *       value squared - measured offline (tools/_drip_sim.py) the last
     *       plip lands ~55s after the rain stops, thinning from a drip every
     *       0.4s to one every few seconds. Swept linearly so it really
     *       ends. Deliberately beyond {@link #AFTER_FOG}'s dispersal: the
     *       haze and the drips are the same leftover water, and the drips
     *       outlive the mist the way a wet roof outlives wet air.</li>
     * </ul></p>
     *
     * <p>In {@link #ALL} for the usual reason - the idle fast path would
     * freeze a channel left out of it mid-drain, and the cost of holding the
     * takeover frame is invisible by the time the drips reach zero.</p>
     */
    public static final PrecipitationResponse DRIP =
            new PrecipitationResponse("drip", 1F, false, 1F, 0F, 7F, 45F, 150F);

    /**
     * The plant-drip layer's own after-rain tail. Same channel as
     * {@link #DRIP} - the eave's - with one number changed: the maintainer
     * asked for the foliage layer to keep making drops for <b>120s</b> after
     * the rain stops (the eave dries in ~55s), and for that tail to be cut
     * hard the moment it stops and then decay to nothing.
     *
     * <p>A channel, not arithmetic in the handler, because that is what the
     * rest of the after-rain family does ({@link #DRIP}, {@link #AFTER_FOG}):
     * the sweep in {@code releaseSeconds} is what guarantees the tail really
     * arrives at zero instead of sitting on a float residue forever, and
     * being in {@link #ALL} is what stops the idle fast path in
     * {@code PrecipitationIntensity.update()} abandoning it mid-drain.</p>
     *
     * <p>The "cut hard" part is not in here on purpose - the curve itself is
     * the same water either way; the step down is the handler's
     * {@code RESIDUAL_FRACTION}, applied only while the rain is over.</p>
     */
    public static final PrecipitationResponse FOLIAGE_DRIP =
            new PrecipitationResponse("foliageDrip", 1F, false, 1F, 0F, 7F, 120F, 130F);

    /**
     * Overcast strength, 0..1 - the dry-weather sky event, driven by
     * {@code SkyEventScheduler} rather than by any precipitation input.
     *
     * <p>A root, and deliberately fed by nothing. Every other root answers
     * "how hard is it precipitating"; this one answers "the weather today is
     * grey even though nothing is falling", which no intensity can express.
     * It cannot derive from {@link #ATMOSPHERE} either: that root is
     * dust-aware, and a sandstorm is not an overcast.</p>
     *
     * <p>Consumers take it with a {@code max} against their own channel - the
     * read-side blend, not a derivation - so the rain chain and the overcast
     * event never have to agree on a single number. The weights are in
     * {@code PrecipitationIntensity.OVERCAST_*}.</p>
     *
     * <p>The three constants are the yield design:
     * <ul>
     *   <li><b>rise 45s</b> - both the onset of the event and the restore after
     *       a rain has passed. Rain's own darkening releases in 5s, so the
     *       overcast coming back must be slower than that or the two would
     *       cross as a visible pop.</li>
     *   <li><b>fall 5s</b> - the yield. When precipitation starts, this channel
     *       steps aside within 3-8s (the rain's SKY rise is 3s); the sky goes
     *       white - grey - rain-dark as one continuous move.</li>
     *   <li><b>release 8s</b> - the sweep that guarantees the tail arrives.</li>
     * </ul></p>
     *
     * <p>It is <em>not</em> in {@link #anyActive()}. The idle fast path in
     * {@code PrecipitationIntensity.update()} returns before {@code tickAll},
     * and an event that runs for in-game hours would hold that path open for
     * all of them, dragging the five-point biome sampling into every idle
     * tick. So this channel is advanced by the scheduler itself, before the
     * idle branch, and {@link #anyActive()} skips it - which is why the
     * channel also never appears in {@link #tickAll}: its target is the
     * scheduler's business, set through {@link #advance}.</p>
     */
    public static final PrecipitationResponse OVERCAST =
            new PrecipitationResponse("overcast", 1F, false, true, 1F, 0F, 45F, 5F, 8F);

    /**
     * Clear-sky strength, 0..1 - the other dry-weather event: the one that
     * takes the clouds away. Same shape of life as {@link #OVERCAST} - a
     * scheduler event, yielded to rain, restored after - so its javadoc is
     * that channel's, and this one only says what is different.
     *
     * <p>What consumes it is version-specific, because the three renderers
     * disagree about what a cloud is: 26.1's cloud colour is ARGB, so the
     * alpha byte fades the sheet out natively; 1.20.1 and 1.21.1 have no
     * alpha and instead melt the cloud colour into the sky colour and then
     * decline to draw the pass at all past a threshold. The channel is the
     * same either way, which is the point of keeping it here and not in the
     * renderers.</p>
     *
     * <ul>
     *   <li><b>rise 10s</b> - clouds thinning out. Faster than the overcast
     *       onset because disappearing is less of a statement than arriving.</li>
     *   <li><b>fall 6s</b> - rain must bring its clouds back, and quickly:
     *       a rain with a bare sky reads as broken.</li>
     *   <li><b>release 10s</b> - the sweep.</li>
     * </ul>
     */
    public static final PrecipitationResponse CLEAR =
            new PrecipitationResponse("clear", 1F, false, true, 1F, 0F, 10F, 6F, 10F);

    /**
     * L1 root - what happens where the rain lands.
     *
     * <p>Derived from {@link #GEOMETRY}, and at present numerically identical to
     * it. It exists so that sound and ground splash share one clock with the rain
     * you can see. Give each of them its own time constants and they drift apart
     * every time one is retuned, which produces the one artefact a graded storm
     * cannot survive: the sound changing while the rain does not.</p>
     */
    public static final PrecipitationResponse SURFACE =
            new PrecipitationResponse("surface", GEOMETRY, 1F, 1F, 0F, 0F, 0F, 0F);

    /**
     * Ground splash density, derived from {@link #SURFACE}.
     *
     * <p>Early-saturating: the ground is visibly wet long before the rain is at
     * its heaviest. Vanilla then squares this again on its way to a particle
     * count, so the effective curve is steeper than the gamma suggests - see the
     * note in the architecture document.</p>
     */
    public static final PrecipitationResponse SPLASH =
            new PrecipitationResponse("splash", SURFACE, 0.7F, 0.8F, 0F, 0F, 0F, 0F);

    /**
     * How wet the ground is - the quantity {@link #SPLASH} already reads, put on
     * the clock water actually keeps.
     *
     * <p>Derived from {@link #SPLASH} with no shaping of its own so the two can
     * never disagree about how wet the ground is; everything this channel adds
     * is <em>delay</em>. That delay is the whole reason it exists. Rain has to
     * land before there is anything to splash, so this cannot follow the
     * intensity the way the geometry does: in the first seconds of a storm the
     * ground is still dry, and a footstep that splashes then is the one thing
     * this curve is here to prevent. Water also leaves slower than it arrives -
     * puddles do not evaporate the moment the cloud passes - hence a fall twice
     * the rise.</p>
     *
     * <p>It also refuses to follow gusts, because ground wetness is accumulated,
     * not instantaneous: a squall that doubles the rain for twenty seconds does
     * not double the water on the ground, and a swell that eases off does not
     * dry it. The rise time is what turns the gust wobble into the steady "it
     * has been raining a while" reading.</p>
     *
     * <p>Being in {@link #ALL} means it also holds the precipitation release
     * until it has dried - see {@link #anyActive()} - which is deliberate. The
     * alternative is a channel the idle fast path abandons, and that path runs
     * before {@link #tickAll}: a channel left out of {@code anyActive()} would
     * freeze mid-wet and never drain. The extra seconds cost nothing visible,
     * because by then every channel that draws anything is already at zero.</p>
     */
    public static final PrecipitationResponse WET =
            new PrecipitationResponse("wet", SPLASH, 1F, 1F, 0F, 12F, 24F, 50F);

    /**
     * Rain sound level, derived from {@link #SURFACE}.
     *
     * <p>Slightly sub-linear - perceived loudness is compressive. The rise and
     * fall here are <em>extra</em> delay on top of the surface curve, not a clock
     * of its own, and they exist for a specific reason: the geometry channel is
     * redrawn every frame and carries the gust wobble, and sound that followed it
     * exactly would breathe audibly. Sound therefore trails the rain slightly
     * rather than being slaved to it.</p>
     */
    public static final PrecipitationResponse AUDIO =
            new PrecipitationResponse("audio", SURFACE, 0.85F, 1F, 0F, 1F, 2.5F, 4F);

    /**
     * Rain fog density, derived from {@link #ATMOSPHERE}. Low gamma: humidity is
     * the <em>first</em> thing you notice when rain starts, long before it is
     * falling hard, so fog has to come up early or the rain looks like it is
     * falling through dry air. Saturates below the top end - past a point more
     * rain does not mean more haze, it means less visibility, which is a
     * different effect.
     */
    public static final PrecipitationResponse FOG_DIST =
            new PrecipitationResponse("fog", ATMOSPHERE, 0.7F, 1F, 0F, 0F, 0F, 0F);

    /**
     * Rain fog <em>colour</em>, derived from {@link #FOG_DIST} with no delay at
     * all. Distance and colour are two readings of the same air; giving them
     * separate clocks produces thick fog tinted like a clear day.
     */
    public static final PrecipitationResponse FOG_COLOR =
            new PrecipitationResponse("fogColor", FOG_DIST, 1F, 1F, 0F, 0F, 0F, 0F);

    /**
     * The haze that outlives the rain - the other half of the rain fog, on a
     * clock of its own.
     *
     * <p>Rain fog is really two things at once, and they want to end at
     * different times. Pulling in the far plane <em>is</em> the loss of view
     * distance, and that should come back as soon as the rain does. What is
     * left - the near plane, the visible haze - is water still hanging in the
     * air, and real air does not clear the moment the cloud passes. So the two
     * are split: {@link #FOG_DIST} keeps driving the far plane as before, and
     * this channel drives the haze alone.</p>
     *
     * <p>Derived from {@link #FOG_DIST} with no shaping, so the haze can never
     * disagree with the rain about how thick the air got - the same discipline
     * that keeps {@link #SPLASH} and {@link #WET} from drifting apart. The
     * three constants are the whole point of it:
     * <ul>
     *   <li><b>rise 0</b> - during rain it tracks exactly, or it would trail
     *       the storm it is supposed to be part of.</li>
     *   <li><b>fall 45s</b> - a peak hold. Without it a gust that eased off
     *       would take the haze down with it, and the haze is accumulated, not
     *       instantaneous.</li>
     *   <li><b>release 50s</b> - a game hour is 50 real seconds, which is the
     *       dispersal time that was asked for. The tail is swept linearly, so
     *       it genuinely arrives at zero instead of hovering above it.</li>
     * </ul></p>
     *
     * <p>It has to sit in {@link #ALL}: the idle fast path in
     * {@code PrecipitationIntensity.update()} returns before {@code tickAll()},
     * so a channel left out of {@code anyActive()} would freeze mid-haze and
     * never drain. The cost is the precipitation takeover holding the frame
     * that much longer, which is invisible - by then every channel that draws
     * anything has already reached zero.</p>
     */
    public static final PrecipitationResponse AFTER_FOG =
            new PrecipitationResponse("afterFog", FOG_DIST, 1F, 1F, 0F, 0F, 45F, 50F);

    /**
     * Sky darkening, and with it the fading of the sun, moon and stars, derived
     * from {@link #ATMOSPHERE}.
     *
     * <p>They are the same number. {@code LevelRenderer.renderSky} reads the rain
     * level once, computes {@code 1.0F - rainLevel}, and uses that both as the
     * alpha of the sun/moon/stars pass and as the sky brightness - so one curve
     * drives all of it.</p>
     *
     * <p>Gamma above 1 so a drizzle barely tints the sky. The old 1.7 / 0.9
     * pairing was far too shy: at intensity 0.3 it produced 0.15, i.e.
     * {@code 1 - 0.15 = 0.85} alpha on the sun, so the sun stayed out during a
     * downpour and it read as "rain, but sunny".</p>
     */
    public static final PrecipitationResponse SKY =
            new PrecipitationResponse("sky", ATMOSPHERE, 1F, 1F, 0F, 3F, 4F, 5F);

    /**
     * Cloud darkening, derived from {@link #SKY} so the two can never drift out
     * of step.
     *
     * <p>The curve is deliberately stronger than the sky's, not weaker, and that
     * is the whole point. Vanilla runs the same maths for both - same base
     * colour, same desaturation - so the two come out numerically identical.
     * They do not look identical: the cloud sheet is a bright white texture
     * multiplied by that colour, while the sky is the colour itself, so at equal
     * values the clouds read noticeably lighter than the sky behind them.
     *
     * <p>So matching the sky's number is not matching the sky's <em>look</em>.
     * Clouds need more desaturation to land at the same apparent brightness,
     * which is what the lower saturation point and gamma buy. If clouds still
     * read too light during a storm, pull {@code saturateAt} down further -
     * that is the knob, and it is the only one that should need touching.
     */
    public static final PrecipitationResponse CLOUD =
            new PrecipitationResponse("cloud", SKY, 0.9F, 0.9F, 0F, 1F, 1F, 1F);

    /**
     * How stormy the sky looks - the second desaturation vanilla applies for
     * thunder, which is what makes a thunderstorm read as night rather than as
     * heavy rain.
     *
     * <p>Vanilla runs two passes: one for rain (luminance kept at 0.6) and then a
     * second for thunder (luminance cut to 0.2). Taking the rain pass alone gives
     * a grey, wet sky but never a dark one, which is why a full-intensity storm
     * still looked like an overcast afternoon. High gamma so it only really
     * arrives near the top of the range: a shower should not borrow the
     * apocalypse.</p>
     *
     * <p>This does not replace vanilla thunder. Where vanilla is actually
     * thundering its own level still wins - see
     * {@code PrecipitationIntensity.stormRainLevel}.</p>
     */
    public static final PrecipitationResponse STORM =
            new PrecipitationResponse("storm", ATMOSPHERE, 4F, 1F, 0F, 4F, 5F, 6F);

    /**
     * Sky light - what dims the world, as opposed to what colours the sky.
     *
     * <p>Vanilla feeds the same rain level to both, which is why they always
     * agreed. Splitting them is what lets either be inspected alone, and what
     * lets the light be tuned without dragging the colour with it. It keeps the
     * sky's shaping so the split changes nothing until someone changes it.</p>
     */
    public static final PrecipitationResponse SKY_LIGHT =
            new PrecipitationResponse("skyLight", ATMOSPHERE, 1F, 0.6F, 0F, 3F, 4F, 5F);

    private static final PrecipitationResponse[] ALL = {
            // Roots first, then derived in dependency order: tickAll walks this
            // array exactly once, so a parent must already have advanced this tick.
            // The two sky-event channels sit with the roots for find()/diagnostics,
            // but tickAll skips them (see schedulerDriven) and so does anyActive().
            GEOMETRY, ATMOSPHERE, SUN, DRIP, FOLIAGE_DRIP, OVERCAST, CLEAR,
            SURFACE, SPLASH, WET, AUDIO,
            FOG_DIST, FOG_COLOR, AFTER_FOG, SKY, CLOUD, SKY_LIGHT, STORM
    };

    /** Channel by diagnostics name, or null. See PrecipitationCommand. */
    public static PrecipitationResponse find(String name) {
        if (name == null)
            return null;
        for (var c : ALL) {
            if (c.name().equalsIgnoreCase(name))
                return c;
        }
        return null;
    }

    /** Every channel in dependency order, for the unlock-all path. */
    public static PrecipitationResponse[] values() {
        return ALL.clone();
    }

    /**
     * Below this the channel is declared settled and pinned to exactly zero.
     * Nothing below it is visible on any channel, and leaving a residue here is
     * what made the release hang.
     */
    private static final float SETTLE_EPSILON = 0.003F;

    private final String name;
    /** Curve exponent: &gt;1 holds the channel back, &lt;1 brings it forward. */
    private final float gamma;
    /** True when this channel reads the phase-gated intensity, false for ambient. */
    private final boolean gated;
    /**
     * The channel this one is derived from, or null for a root curve.
     *
     * <p>A derived channel reads its parent's <em>output</em>, not the raw
     * intensity, and its rise/fall/release are extra delay on top of the parent
     * rather than a clock of its own. That is the whole mechanism: it is what
     * makes it impossible to retune one consumer into a different phase from the
     * thing it is supposed to agree with.</p>
     */
    private final PrecipitationResponse parent;
    /** True when this channel reads the attack clock instead of an intensity. */
    private final boolean attackDriven;
    /**
     * True when this channel is fed by whichever of rain and dust is worse - see
     * {@link #ATMOSPHERE} and {@link #DUST_ATMOSPHERE_WEIGHT}.
     */
    private final boolean dustAware;
    /**
     * True when this channel is driven by the sky-event scheduler rather than
     * by a precipitation input.
     *
     * <p>Three consequences, all of them the same fact seen from different
     * sides: {@link #tickAll} skips the channel (its target is the scheduler's
     * business, handed in through {@link #advance}); {@link #anyActive()}
     * ignores it (the idle fast path must stay reachable while an event runs
     * for in-game hours); and {@link #slowestChannel()} still reports it
     * (diagnostics want to see it holding the frame).</p>
     */
    private final boolean schedulerDriven;
    /** Intensity at which the channel is fully spent. */
    private final float saturateAt;
    /** Value the channel holds at zero intensity. */
    private final float floor;
    /** Time constant while catching up to a rising target, in seconds. 0 = instant. */
    private final float riseSeconds;
    /** Time constant while easing down to a non-zero target, in seconds. 0 = instant. */
    private final float fallSeconds;
    /**
     * Upper bound, in seconds, on how long the channel takes to reach zero once
     * the target has gone to zero. The tail is swept linearly so it is
     * guaranteed to arrive - see the class comment.
     */
    private final float releaseSeconds;

    private volatile float current = 0F;

    /**
     * Value this channel is pinned to, or null when it is following its input.
     * A pinned channel is assigned rather than eased - see {@link #tick}.
     */
    private volatile Float locked = null;

    private PrecipitationResponse(String name, float gamma, boolean gated,
                                  float saturateAt, float floor,
                                  float riseSeconds, float fallSeconds, float releaseSeconds) {
        this(name, gamma, gated, false, false, false, saturateAt, floor, riseSeconds, fallSeconds,
                releaseSeconds, null);
    }

    /**
     * Scheduler-driven variant. The extra {@code schedulerDriven} flag is what
     * takes the channel out of {@link #tickAll} and {@link #anyActive()} - see
     * the field comment for why both are load-bearing.
     */
    private PrecipitationResponse(String name, float gamma, boolean gated, boolean schedulerDriven,
                                  float saturateAt, float floor,
                                  float riseSeconds, float fallSeconds, float releaseSeconds) {
        // Master order is (gated, attackDriven, dustAware, schedulerDriven) - the
        // flags sit in slots three to six.
        this(name, gamma, gated, false, false, schedulerDriven, saturateAt, floor, riseSeconds,
                fallSeconds, releaseSeconds, null);
    }

    /**
     * Dust-aware variant. Only {@link #ATMOSPHERE} uses it.
     *
     * <p>Every atmospheric channel - sky, clouds, light, fog and the storm
     * darkening - derives from ATMOSPHERE, and ATMOSPHERE used to read the rain
     * intensity alone. In a desert that is the ARID roll, which sits near 0.02,
     * so during a full sandstorm the sky stayed blue, the clouds stayed white,
     * the light never dimmed and no storm darkening was ever applied. The dust
     * was the only thing on screen that knew a storm was blowing.</p>
     *
     * <p>Taking the larger of the two at the <em>root</em> rather than adding a
     * parallel set of channels is what keeps them in phase: the derived
     * channels read their parent's output, so sky, cloud, fog and storm cannot
     * disagree about how far into the event they are. That is the whole point
     * of the derived-channel discipline.</p>
     */
    private PrecipitationResponse(String name, float gamma, float saturateAt,
                                  float riseSeconds, float fallSeconds, float releaseSeconds) {
        this(name, gamma, false, false, true, false, saturateAt, 0F, riseSeconds, fallSeconds,
                releaseSeconds, null);
    }

    /**
     * Attack-driven variant: no {@code gated} parameter, which is what marks the
     * channel as reading the attack clock. See {@link #SUN}.
     */
    private PrecipitationResponse(String name, float gamma,
                                  float saturateAt, float floor,
                                  float riseSeconds, float fallSeconds, float releaseSeconds) {
        // (gated, attackDriven, dustAware, schedulerDriven) - SUN reads the
        // attack clock, and nothing else about it changes.
        this(name, gamma, false, true, false, false, saturateAt, floor, riseSeconds, fallSeconds,
                releaseSeconds, null);
    }

    /**
     * Derived variant. Note there is no {@code gated} flag: a derived channel
     * inherits its input from the parent, so asking it whether to read the gated
     * or the ambient intensity would be asking it to second-guess the chain it
     * was just handed.
     */
    private PrecipitationResponse(String name, PrecipitationResponse parent, float gamma,
                                  float saturateAt, float floor,
                                  float extraRise, float extraFall, float extraRelease) {
        this(name, gamma, false, false, false, false, saturateAt, floor, extraRise, extraFall,
                extraRelease, parent);
    }

    private PrecipitationResponse(String name, float gamma, boolean gated, boolean attackDriven,
                                  boolean dustAware, boolean schedulerDriven, float saturateAt, float floor,
                                  float riseSeconds, float fallSeconds, float releaseSeconds,
                                  PrecipitationResponse parent) {
        this.name = name;
        this.gamma = gamma;
        this.gated = gated;
        this.attackDriven = attackDriven;
        this.dustAware = dustAware;
        this.schedulerDriven = schedulerDriven;
        this.saturateAt = saturateAt;
        this.floor = floor;
        this.riseSeconds = riseSeconds;
        this.fallSeconds = fallSeconds;
        this.releaseSeconds = releaseSeconds;
        this.parent = parent;
    }

    /** Pin this channel to a value. Takes effect on the next tick, with no easing. */
    public void lock(float value) {
        this.locked = value;
        this.current = value;
    }

    /** Release a pin; the channel resumes following its input. */
    public void unlock() {
        this.locked = null;
    }

    public boolean isLocked() {
        return this.locked != null;
    }

    /** Advance every channel by one tick. Call once per tick, from the ticker. */
    /**
     * How much of a dust storm counts as atmosphere, relative to the same
     * intensity of rain.
     *
     * <p>Below 1: a sandstorm darkens the sky somewhat less than an equal
     * downpour does. Sand scatters light rather than absorbing it, which is
     * why a real haboob goes ochre and dim rather than slate grey and dark -
     * and the dust veil already carries the colour. It is still close to 1,
     * because a full sandstorm genuinely does take the light out of the day.</p>
     */
    private static final float DUST_ATMOSPHERE_WEIGHT = 0.9F;

    public static void tickAll(float gatedIntensity, float ambientIntensity,
                               float attackProgress, float dustIntensity, float dtSeconds) {
        // Dependency order matters. ALL lists every parent before its children, so
        // a single forward pass gives each derived channel a parent that has
        // already been advanced this tick - otherwise a chain would be a tick deep
        // per link and the ends of the chain would lag the rain by that much.
        // Scheduler-driven channels are skipped: their target is not any intensity
        // we hold here, it is the event state the scheduler hands to advance().
        for (var response : ALL) {
            if (response.schedulerDriven)
                continue;
            final float raw;
            if (response.attackDriven)
                raw = attackProgress;
            else if (response.parent != null)
                raw = response.parent.current;
            else if (response.dustAware)
                // Whichever of the two is worse right now, at the root. See the
                // comment on the dust-aware constructor.
                raw = Math.max(ambientIntensity, dustIntensity * DUST_ATMOSPHERE_WEIGHT);
            else
                raw = response.gated ? gatedIntensity : ambientIntensity;
            response.tick(raw, dtSeconds);
        }
    }

    /** Reset every channel to zero - used when a world is left or a lock is taken. */
    public static void resetAll() {
        for (var response : ALL)
            response.current = 0F;
    }

    /**
     * Whether any channel is still holding a non-zero value.
     *
     * <p>This is the release condition for the precipitation takeover and the
     * reason the tail is swept rather than left to decay exponentially. The
     * takeover owns the frame while any consumer still wants something from it;
     * handing it back while fog is still at 0.2 is what made rain fog snap
     * clear the instant the rain stopped.</p>
     */
    public static boolean anyActive() {
        for (var response : ALL)
            // Sky-event channels are excluded on purpose. They sit above zero for
            // in-game hours at a time, and anyActive() is the idle fast path's last
            // gate - including them would drag the five-point biome sampling into
            // every dry tick for as long as an event lasted. The read points get
            // the same effect from ownsAmbient(), which asks the channels directly.
            if (!response.schedulerDriven && response.current > 0F)
                return true;
        return false;
    }

    /** Diagnostics only: the channel still holding the highest value, or "none". */
    public static String slowestChannel() {
        float best = 0F;
        String who = "none";
        for (var response : ALL) {
            if (response.current > best) {
                best = response.current;
                who = response.name;
            }
        }
        return who;
    }

    /** The channel's current driving value, 0..1. Read this from render and audio. */
    public float value() {
        return this.current;
    }

    public String name() {
        return this.name;
    }

    /** Unsmoothed curve - exposed so the diagnostics can show the target too. */
    public float shape(float intensity) {
        float x = Mth.clamp(intensity / this.saturateAt, 0F, 1F);
        return this.floor + (1F - this.floor) * (float) Math.pow(x, this.gamma);
    }

    private void tick(float intensity, float dtSeconds) {
        this.easeTo(this.shape(intensity), dtSeconds);
    }

    /**
     * Advance toward an explicit target - the sky-event scheduler's entry point.
     *
     * <p>{@link #tick} computes its target from an intensity this class never
     * sees; the scheduler-driven channels have no input intensity at all, their
     * target <em>is</em> the event state. Same easing, same lock discipline,
     * same tail sweep - only the source of the target differs, which is why
     * this is public while {@code tick} is not.</p>
     */
    public void advance(float target, float dtSeconds) {
        this.easeTo(target, dtSeconds);
    }

    private void easeTo(float target, float dtSeconds) {
        // A lock is applied, not approached. Easing towards it would make a
        // pinned channel indistinguishable from one that is simply slow, which
        // is exactly the confusion a lock exists to remove.
        final Float pinned = this.locked;
        if (pinned != null) {
            this.current = pinned;
            return;
        }

        if (this.riseSeconds <= 0F && this.fallSeconds <= 0F && this.releaseSeconds <= 0F) {
            this.current = target;
            return;
        }

        if (target >= this.current) {
            // Head: catching up to a target above us. Near zero this is what keeps
            // a gust wobble from showing up as the channel breathing.
            final float alpha = this.riseSeconds <= 0F
                    ? 1F
                    : (float) (1D - Math.exp(-dtSeconds / this.riseSeconds));
            this.current += (target - this.current) * alpha;
            return;
        }

        if (target <= 0F) {
            // Tail. Exponential alone asymptotes, so a linear sweep runs alongside
            // it and whichever gets there first wins: the decay looks exponential
            // while it is large and finishes on a bounded schedule instead of
            // hovering just above zero indefinitely.
            final float step = this.releaseSeconds <= 0F
                    ? this.current
                    : dtSeconds / this.releaseSeconds;
            final float eased = this.fallSeconds <= 0F
                    ? 0F
                    : this.current * (float) Math.exp(-dtSeconds / this.fallSeconds);
            this.current = Math.max(0F, Math.min(eased, this.current - step));
            if (this.current < SETTLE_EPSILON)
                this.current = 0F;
            return;
        }

        // Middle: easing down towards a target that is still above zero.
        final float alpha = this.fallSeconds <= 0F
                ? 1F
                : (float) (1D - Math.exp(-dtSeconds / this.fallSeconds));
        this.current += (target - this.current) * alpha;
    }
}
