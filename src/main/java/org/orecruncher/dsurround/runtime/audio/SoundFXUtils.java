package org.orecruncher.dsurround.runtime.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.config.libraries.IBlockLibrary;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.math.MathStuff;
import org.orecruncher.dsurround.lib.math.ReusableRaycastContext;
import org.orecruncher.dsurround.lib.math.ReusableRaycastIterator;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.runtime.audio.effects.Effects;
import org.orecruncher.dsurround.runtime.audio.effects.LowPassData;
import org.orecruncher.dsurround.runtime.audio.effects.SourcePropertyFloat;
import org.orecruncher.dsurround.sound.SoundInstanceHandler;

public final class SoundFXUtils {

    /**
     * Hard cap on the segments one material walk may take. The walk covers the whole line, so this bounds the
     * cost of a very distant sound; it is 4x the distance in blocks.
     */
    private static final int OCCLUSION_MAX_SEGMENTS = 192;
    /**
     * How far the clearance search reaches, and the size of each successive step.
     *
     * <p>The offsets grow GEOMETRICALLY: fine near the line, where an opening is close enough for its position
     * to matter, and coarse far out. This drives the EDGE term only. A distant opening used to be carried by a
     * separate listener-openness term on the theory that a knife edge cannot carry it; that term is gone - it
     * was direction blind and cancelled the material walk's loss (see the note at materialLossDb). An opening
     * now contributes through the edge term plus whatever the walk measures along the line.
     * An earlier revision stretched this to 47 blocks on the theory that finding a distant
     * opening would help; measured, it did not - it pulled in unrelated airspace and cost rays for nothing.
     *
     * <p>Max offset = START * GROWTH^(STEPS-1) = 0.75 * 1.3^11, about 13 blocks.
     */
    private static final int CLEARANCE_STEPS = 12;
    private static final float CLEARANCE_STEP = 0.75F;
    private static final float CLEARANCE_STEP_GROWTH = 1.3F;
    /** Azimuths tested at each step, so the clearance is not biased to one plane. */
    private static final int CLEARANCE_AZIMUTHS = 6;
    /**
     * Speed of sound in air, m/s. A Minecraft block is 1 m, so this is used directly with
     * wavelength = c / f to get the Fresnel number of a detour.
     */
    private static final float SPEED_OF_SOUND = 343F;
    /**
     * Smallest path-length detour (blocks) the knife-edge formula is evaluated at. Zero detour means the wave
     * grazes the edge exactly, which is the 0.5 amplitude case, not an infinite one.
     */
    private static final double MIN_EDGE_DETOUR = 1.0E-4D;
    /**
     * Frequency (Hz) at which the material absorption coefficient is defined, so that
     * {@link #materialAbsorptionScale} is exactly 1 there. Every earlier tuning session measured its
     * transmission against this reference, so keeping it fixed means the existing constants stay valid.
     */
    private static final double MATERIAL_REFERENCE_HZ = 500.0D;
    /**
     * Transmission loss in dB per unit of accumulated block occlusion-distance, at the reference frequency.
     *
     * <p>Calibration: one block of stone has occlusion 0.5, so one block costs 2.5 dB and a four-block wall
     * about 10 dB. That is the range a real partition occupies, and it is the range the ear reads as "there
     * is something in the way" rather than as silence.
     *
     * <p><b>Why 5.0 and not the 4.0 this used to be.</b> The per-unit figure is not a property of stone, it
     * is a property of stone AS SEEN THROUGH the rest of the model - and two of those pieces changed. The
     * bands are now combined as an ENERGY average rather than an average of dB (a geometric mean, which is
     * dragged toward the most attenuated band), and the spectral term is additive rather than multiplicative.
     * Both make the same barrier come out lighter, so the constant has to rise to put the result back where
     * it was. Measured across the standard scenes in tools/audio_regression.py, 5.0 lands every scene within
     * about 1.4 dB of the 4.0 figures under the old combination - thin walls and thick barriers alike.
     * Raising it is not a re-tune of the target, it is keeping the target while fixing the arithmetic.
     *
     * <p>This replaced an exponential transmission model, {@code exp(-centerMaterial * 3)}, which applied the
     * loss as an AMPLITUDE rather than in dB: 15 blocks of rock gave {@code exp(-22.5)} = 1e-10, i.e. -200 dB.
     * Real transmission loss grows linearly in dB with thickness (the mass law), so the exponential form
     * over-attenuated thick barriers by roughly 90 dB and pinned them at the 1e-6 amplitude clamp - measured
     * as {@code lossdb=120} (the ceiling) in 26 rows of one session, which is silence, not muffling.
     */
    private static final float MATERIAL_LOSS_DB_PER_UNIT = 5.0F;
    /**
     * Hard ceiling on the material transmission loss. Keeps the term meaningful instead of collapsing into
     * the 1e-6 amplitude clamp, where it stops carrying information and simply zeroes the result.
     *
     * <p>Raised from 60 to 90 dB because the old value clipped STRONG absorbers far too early and flattened
     * the differences between materials: with 4 dB per unit, wool (occlusion 1.0) reached the ceiling at 15
     * blocks of thickness while stone (0.65) reached it at 23, so between 15 and 23 blocks the gap between
     * them compressed and above 23 they were identical. A wool wall is a much better absorber than a stone
     * one at ANY thickness, so the ceiling must sit above the range a player can actually build. At 90 dB the
     * ceiling is not reached until 22.5 blocks of wool or 34.6 of stone.
     *
     * <p>90 dB is still safe: it floors the amplitude at 3.2e-5, well clear of the 1e-6 (-120 dB) clamp that
     * this constant exists to avoid.
     */
    private static final float MATERIAL_MAX_LOSS_DB = 90.0F;
    /**
     * Share of the material loss handed to the direct LEVEL, as opposed to the direct low-pass.
     *
     * <p>The engine derives both from one occlusion value: the filter is {@code exp(-occlusion * 3)} and the
     * level is {@code pow(that, 0.1)}. Passing the full loss meant it was applied twice, and the filter term
     * dominates - behind rock the cutoff reached ~1e-26, which is not "muffled", it is gone. The filter gets
     * the full loss because that IS what muffling is; the level gets this fraction, which keeps an occluded
     * sound quiet without deleting it.
     */
    private static final float MATERIAL_LEVEL_RESTORE = 0.2F;
    /**
     * Gain of the returned-energy term, applied to the early reverb zones.
     *
     * <p>The term it scales is {@code sqrt(reflectivity) * returnedShare}. Measured offline against synthetic
     * terrain (tools/sim_calibrate.py), the returned share is 0.015 on a plain, 0.085 in forest, 0.12 in a wide
     * valley, 0.27 on a hillside into a bowl and 0.35 in a steep valley - a 23x spread that comes entirely from
     * geometry. At 1.0 those become -45 dB on a plain and -17 dB in a valley, so the valley is 27 dB above the
     * plain and level with a cave's tail (-21 dB). There is nothing to tune: the separation is the geometry's.
     *
     * <p>Note what this can and cannot buy. OpenAL applies its reverb tail from the instant the sound starts,
     * so a louder tail is MORE REVERB, never an echo: a real valley echo is direct, then a GAP, then the
     * reflection, and the gap is what makes it read as an echo. Producing a gap needs discrete delayed taps,
     * which this architecture does not have.
     */
    private static final float REFLECTION_DENSITY_GAIN = 1.0F;
    /**
     * Mean free path at which an enclosure's reflections are treated as travelling through open air.
     *
     * <p>Used to scale the brightness floor the early-reflection zones are given. The floor exists
     * because a reflection off a distant wall crosses air rather than the rock that darkens the direct
     * path, so it should not inherit that darkening - but a fixed floor applies that reasoning to a
     * 3x3x3 hut as readily as to a cavern, and the wet path then ends up BRIGHTER than the muffled
     * direct sound of a villager heard through a wall. The ear reports that as reverberation, which is
     * the complaint this addresses.
     *
     * <p>Measured mean free paths (tools/sim_hut_reverb.py): 1.79 m for a 3x3x3 hut, 1.99 m for 3x3x4,
     * 4.69 m for an 8x5x12 hall, 8.77 m for a 20x8x20 cavern. At 6 m a hut keeps 0.30 of the floor and
     * a cavern keeps all of it - a 3.35x separation, which is the size distinction the ear makes and
     * the one the facingShare term could not express.
     */
    private static final float ECHO_SCALE_REFERENCE_M = 6.0F;
    /** Seconds per block of travel, i.e. 1 / 343 m/s, converting a reflection distance into a delay. */
    /**
     * Speed of sound in water, m/s. Air is {@link #SPEED_OF_SOUND} (343), so water is about 4.3x
     * faster and an underwater reflection arrives about 4.3x sooner. The reflection delay
     * interpolates between the two by how much of the path is actually submerged; using the air
     * constant everywhere put underwater reflections at air-timed delays.
     */
    private static final float WATER_SPEED_OF_SOUND = 1480F;
    /** Sky light level at which a position counts as open air; also the top of the 0-15 range. */
    private static final int MAX_SKY_LIGHT = 15;
    private static volatile long currentPass;

    private static final IBlockLibrary BLOCK_LIBRARY = ContainerManager.resolve(IBlockLibrary.class);
    private static final ISeasonalInformation SEASONAL_INFORMATION = ContainerManager.resolve(ISeasonalInformation.class);
    private static final Configuration.EnhancedSounds CONFIG = ContainerManager.resolve(Configuration.EnhancedSounds.class);

    /**
     * Segments a single occlusion ray is split into. Trades precision against cost: each segment is
     * a block-state lookup plus a distance-weighted contribution, and every occlusion ray pays it.
     * <p>
     * Read live from {@link AudioTuning} rather than cached, so {@code /dstune} can change it
     * without a restart. The read happens once per trace, not per ray.
     */
    private static int occlusionSegments() {
        return AudioTuning.occlusionSegments();
    }

    /**
     * Fallback octave bands, used only if the configuration supplies nothing usable. A real sound is
     * broadband and the Fresnel zone scales as 1/sqrt(frequency), so one hill covers the high band's zone
     * while leaving the low band's zone partly clear - which is why a source on a hill heard from the
     * valley is dull but present, not silent. The live values come from {@link AudioTuning}.
     */
    private static final float[] DEFAULT_BAND_FREQUENCIES = {125F, 500F, 2000F};
    /** Fallback band weights: low frequencies dominate because they are the ones that get around. */
    private static final float[] DEFAULT_BAND_WEIGHTS = {0.50F, 0.35F, 0.15F};
    /**
     * How the reverb sends respond to an occluded path.
     *
     * <p>There is no constant here because the answer is not a floor: the wet path takes the direct
     * path's transmission through the send CUTOFFS, which are the wet path's filters, and through
     * {@code pow(sendCutoff, 0.1)} in {@link #finalizeSendGains}, which is the same "the level gets a
     * damped share of the loss" convention the direct path uses via {@link #MATERIAL_LEVEL_RESTORE}.
     * That makes the wet-to-direct ratio independent of how occluded the source is, which is the point:
     * a tail heard from outside a room has crossed the same wall the direct sound crossed, so it must
     * take the same loss - no more and no less.
     *
     * <p>A floor of 0.35 was tried first and removed. At 0.35 the wet path kept -9 dB while the direct
     * path through the same wall lost 6-20 dB, so walking outside a village hut RAISED the wet-to-direct
     * ratio - measured 1.35x through two blocks of wood, 1.64x through a wall with framing, 4.15x
     * through a wall and a hill (tools/sim_wet_ratio.py). The reverb therefore grew relatively louder
     * the more the sound was blocked, which the ear reports as "the villager inside is reverberant".
     *
     * <p>A SEPARATE gain factor was then multiplied into the send gains on top of the cutoffs, and that
     * was worse: it applied the loss to the wet LEVEL a second time, so the wet level carried 110% of
     * the loss dB against the direct path's 20% and the ratio fell as exp(sendCoeff) instead of holding
     * at 1.0. That is what made reverb vanish outright a couple of blocks from an opening even though
     * the source's own cavity was fully excited. See the note at the send site in {@link #calculate}.
     *
     * <p>The reasoning holds for a cave: with the listener inside it there is almost no occlusion to
     * inherit (measured loss 0.5-1 dB), so the long tail the ear reads as "a cave" is preserved.
     */
    /**
     * Number of rays to project when doing reverb calculations.
     */
    private static final int REVERB_RAYS = CONFIG.reverbRays;
    /**
     * Number of bounces a sound wave will make when projecting. Clamped to at least 4:
     * the zone gain math indexes bounceRatio[0..3], so a smaller config value would
     * throw out of bounds and silently disable reverb for every sound.
     */
    private static final int REVERB_RAY_BOUNCES = Math.max(4, CONFIG.reverbBounces);
    /**
     * Maximum distance to trace a reverb ray segment before stopping.
     */
    private static final float MAX_REVERB_DISTANCE = CONFIG.reverbRayTraceDistance;
    /**
     * Step size when sampling the sound-to-listener path for water. Water strongly
     * absorbs high frequencies, so the total path length under water drives the low-pass
     * damping of the sound. Half a block is small enough that any body of water at least
     * one block across is always hit, regardless of where along the path it sits - the
     * sample is walked over the whole path every refresh, no coarse pre-scan (a coarse
     * scan missed narrow water bands when the source or listener bobbed at the surface).
     */
    private static final float WATER_SAMPLE_STEP = 0.5F;
    /**
     * Upper bound on how far the water-path sampling traces from the sound. Sound far
     * beyond this is already heavily attenuated by the engine's distance falloff, and the
     * trace cost stays bounded.
     */
    private static final float MAX_WATER_SAMPLE_DISTANCE = 64F;
    /**
     * Reciprocal of the total number of rays cast.
     */
    private static final float RECIP_TOTAL_RAYS = 1F / (REVERB_RAYS * REVERB_RAY_BOUNCES);
    /**
     * Sound reflection energy coefficient
     */
    private static final float ENERGY_COEFF = 0.75F * 0.25F * RECIP_TOTAL_RAYS;
    /**
     * Sound reflection energy constant
     */
    private static final float ENERGY_CONST = 0.25F * 0.25F * RECIP_TOTAL_RAYS;
    /**
     * Normals for the direction of each of the rays to be cast.
     */
    private static final Vec3[] REVERB_RAY_NORMALS = new Vec3[REVERB_RAYS];
    /**
     * Precalculated vectors to determine end targets relative to an origin.
     */
    private static final Vec3[] REVERB_RAY_PROJECTED = new Vec3[REVERB_RAYS];
    /**
     * Precaluclated direction surface normals as Vec3 instead of Vec3i
     */
    private static final Vec3[] SURFACE_DIRECTION_NORMALS = new Vec3[Direction.values().length];

    static {

        // Would have been cool to have a direction vec as a 3d as well as 3i.
        for (final Direction d : Direction.values()) {
            SURFACE_DIRECTION_NORMALS[d.ordinal()] = Vec3.atLowerCornerOf(d.getNormal());
        }

        // Pre-calculate the known vectors that will be projected off a sound source when casting about to establish
        // reverb effects.
        for (int i = 0; i < REVERB_RAYS; i++) {
            final double longitude = MathStuff.ANGLE * i;
            final double latitude = Math.asin(((double) i / REVERB_RAYS) * 2.0D - 1.0D);

            REVERB_RAY_NORMALS[i] = new Vec3(
                    Math.cos(latitude) * Math.cos(longitude),
                    Math.cos(latitude) * Math.sin(longitude),
                    Math.sin(latitude)
            ).normalize();

            REVERB_RAY_PROJECTED[i] = REVERB_RAY_NORMALS[i].scale(MAX_REVERB_DISTANCE);
        }

    }

    /**
     * Position of the first solid hit on the CENTRE occlusion ray from the most recent
     * calculation. It is the ring centre for the diffraction probe: sound bends around
     * this occluder's edges. Null when the centre ray is clear, which also gates the
     * compensation off.
     */
    @Nullable
    private Vec3 lastOccluderPos;
    /**
     * The point the wave escapes through beside the first obstacle, or null when the search found no opening.
     * Used for diagnostics only now - the material term measures the straight line, and the edge term carries
     * what an opening contributes.
     */
    @Nullable
    private Vec3 lastEdgePoint;
    /**
     * Reverb geometry from the most recent measurement, reported by the probe as {@code rv=}.
     *
     * <p>These exist because the reverb is a GLOBAL system: every scene goes through the same four zones,
     * so a change intended for one of them lands on all of them. Before this, tuning it meant guessing -
     * the session had already broken things three times by changing a formula without an observation point
     * (the material walk's reach, the wrong path, the openness range). What is needed to see the difference
     * between a room, a cave and a valley is the SHAPE of the reflections: how far away they are, how much
     * energy comes back, and how much of the space is seen from the reflection points.
     */
    /** Mean distance to the first reflection, in blocks. Small = a room, large = a valley wall. */
    private float lastReverbFirstDistance;
    /** Submerged share (0..1) of the direct path, for the reflection-tap speed of sound. */
    private float lastSubmergedShare;
    /**
     * Farthest reflection of any bounce, in blocks, measured FROM THE LISTENER (a reflection reaches the
     * ear after travelling from the surface).
     *
     * <p>Needed because the FIRST reflection cannot tell a room from a valley: a listener standing on the
     * ground hits the ground first, so the first bounce is a few blocks away in both cases (measured: median
     * 5.9 m across four scenes). A valley's far wall only appears in the LATER bounces. This is the number
     * that should separate the two, and it is measured before anything is changed on the strength of it.
     */
    private float lastReverbFarthest;
    /** Fraction of rays that found a reflection at all. Low = open sky, high = surrounded by surfaces. */
    private float lastReverbHitFraction;
    /** Mean reflectivity of the first bounce. Low = soft ground (grass, leaves), high = stone. */
    private float lastReverbReflectivity;
    /**
     * How many bounces completed in open air (clear sky above). Feeds the send-cutoff weights, and is
     * NOT the same quantity as {@link #lastReturnedBounces} - see the note in traceReverb.
     */
    private int lastReverbShared;
    /** How many bounces came back off a surface facing the source: the early-reflection numerator. */
    private int lastReturnedBounces;
    /**
     * Early-reflection share contributed by distant reflectors, reported by the probe as {@code erf=}.
     *
     * <p>A valley echo is not a late diffuse tail, it is an EARLY DISCRETE reflection off one large
     * distant surface - which is why summing it into the late zones could never carry it. This is the
     * measured share of the space that is "a distant wall, and it reflects", and it is added to the early
     * zones instead. See traceReverb for why it cannot affect a room or a cave.
     */
    private float lastEarlyReflectionGain;
    /**
     * Mean distance between consecutive reflections, in blocks: the mean free path of the space, reported by
     * the probe as {@code mfp=}.
     *
     * <p>This is the physically correct way to tell a cave from a valley, and it replaces an earlier attempt
     * that used the FARTHEST reflection instead. Distance alone cannot separate them: a large hall has far
     * walls too, and it SHOULD reverberate (a cathedral does). What actually differs is the DENSITY of the
     * reflections - a cave packs them a few blocks apart so they fuse into a diffuse tail, while a valley
     * leaves tens of blocks of open air between them, which is what makes an echo discrete and audible
     * rather than a wash.
     *
     * <p>Measured before anything is changed on the strength of it, because the probe data already showed
     * that the long reverb zones are currently driven by MATERIAL (exactly zero below reflectivity 0.4) when
     * they should be driven by GEOMETRY. That is the defect this number is meant to fix.
     */
    private float lastReverbMeanFreePath;
    /**
     * Share of the reflections that came off a surface FACING the listener, from the most recent
     * measurement, reported by the probe as {@code face=}. 1 means every reflection was off a vertical wall,
     * 0 means every one was off the ground or a ceiling. This is the geometric quantity that separates a
     * valley from a plain, and it is reported so the separation can be verified rather than assumed.
     */
    private float lastFacingShare;
    /**
     * Occlusion accumulation along the centre fan ray only (the direct
     * source-to-player line). Unlike the full-fan average, which clips incidental
     * terrain off to the side and climbs to 5-10 in the open world, this tracks how
     * much solid the straight path truly passes through (a thin wall ~0.8, a few
     * blocks of rock between the surface and a cave ~3+). Used to close the
     * compensation for genuinely thick path obstacles such as the ground over a cave.
     */
    /** Material accumulated along the opening path on the most recent measurement; the probe reports it as
     * {@code material=} and the straight-line value beside it as {@code line=}, so the saving is visible. */
    private float lastCenterOcclusion;
    /** Excess attenuation in dB from the Fresnel-zone model on the most recent measurement. */
    private float lastZoneLossDb;
    /** Whether an edge path was found at all on the most recent measurement. */
    private boolean lastEdgeFound;
    /** The clearance height (blocks) the edge loss was computed from, for diagnostics. */
    private float lastEdgeDelta;
    /** The raw material amplitude and the reach of the material walk from the most recent measurement. */
    private float lastMaterialSum;
    /** Segments the material walk actually consumed, and the line length it was walking. Diagnostics for
     * whether a zero material reading means "the ray stopped short" or "the line is genuinely clear". */
    private int lastWalkSegments;
    private double lastWalkDistance;
    /** Per-band edge loss in dB from the most recent measurement, for diagnostics. */
    private final float[] lastEdgeDb = new float[8];

    private final SourceContext source;

    public SoundFXUtils(final SourceContext source) {
        this.source = source;
    }

    /** Scratch result of the reverb ray trace; filled in by {@link #traceReverb}. */
    private static final class ReverbTrace {
        float sendGain0;
        float sendGain1;
        float sendGain2;
        float sendGain3;
        float sendCutoff0;
        float sendCutoff1;
        float sendCutoff2;
        float sendCutoff3;
        final float[] bounceRatio = new float[REVERB_RAY_BOUNCES];
        /**
         * Reflection-density contribution, added to the early zones AFTER the reflectivity weighting.
         *
         * <p>It cannot be folded into sendGain1/2 directly: finalizeSendGains multiplies those by
         * {@code bounceRatio^3} / {@code bounceRatio^4}, which for outdoor terrain (reflectivity 0.35) is
         * 0.043 - enough to crush a 0.23 contribution back to 0.010 and make it inaudible. That weighting
         * belongs to the DIFFUSE field, where the number of bounces really does depend on the material; an
         * early reflection off a distant wall is a single reflection whose strength is already accounted for
         * in the term itself.
         */
        /** Brightness to impose on the early-reflection zones; see the comment in traceReverb. */
        float earlyReflectionCutoff;
        /**
         * Lower bound for the DIRECT path's brightness, from the share of reflections that can reach the
         * listener. Zero in the open; in an enclosed space it stops the direct sound from being darkened by
         * the very rock the reflections travelled around.
         */
        float directCutoffFloor;
        /**
         * Share of bounces whose reflection point has a clear straight line to the ear, scaled by the ray
         * budget and by 64 (see {@code connectedAirspace} in traceReverb). A CONNECTIVITY measure: it says
         * how much of the reflecting surface around the source can reach the listener at all, rather than
         * whether the straight line between them is clear.
         *
         * <p>Measured by line of sight ALONE. It used to be ORed with a "sky visible above" test, which is
         * vertical and therefore direction blind: a shaft dug upward to the surface lit a whole column and
         * marked every bounce under it as connected. See the note in traceReverb.
         */
        float connectedAirspace;
        /**
         * Share of bounces sitting under open sky. Observation point only - the probe reports it as
         * {@code sky=} so the skylight's behaviour can be watched without letting it gate anything.
         */
        float outdoorShare;
    }


    public void calculate(final @NotNull WorldContext ctx) {

        assert ctx.player != null;
        assert ctx.world != null;
        assert this.source.getSound() != null;

        if (ctx.isNotValid()
                || !this.source.isEnabled()
                || !SoundInstanceHandler.inRange(ctx.playerEyePosition, this.source.getSound())
                || this.source.getPosition().equals(Vec3.ZERO)) {
            this.clearSettings();
            return;
        }

        final Vec3 sourcePos = this.source.getPosition();
        // Offset the ray starting point out of solid matter, ALWAYS - exactly as the reference
        // implementation does.
        //
        // A block source (block.break, block.place and the rest) reports the block's own centre,
        // which is inside a solid block by definition. Skipping the offset for those means every
        // block sound traces its reverb rays from INSIDE the block: the fan hits the block's own
        // inner faces within a fraction of a block, so the mid and long reverb zones (1.68 s and
        // 4.14 s) receive almost nothing. Measured on 1.20.1 before this fix, at the same world
        // position: block.stone.place reached a zone-2 gain of 0.12-0.18 and block.deepslate.break
        // 0.32, while the player's own footsteps - whose origin sits in air and therefore did take
        // the offset - reached 0.45-0.57. It is heard as "a short drag with no room tail behind it",
        // on block sounds only, which is exactly the report.
        //
        // Moving the origin 0.876 blocks toward the player lands it just outside the surface, where
        // the rays can reach the room again.
        //
        // An earlier revision skipped the offset when the source was solid, to stop a source's
        // reverb from varying as the player walked around it. That variation is genuine room
        // acoustics (different reflection paths from different angles), the value is recomputed on
        // every update, and the reference implementation lives with it - so the unconditional call
        // is both correct and faithful.
        final Vec3 soundPos = offsetPositionIfSolid(ctx.world, sourcePos, ctx.playerEyePosition);

        // Cost of one evaluation, reported by the probe below. The occlusion work traces two aperture
        // planes of 8 samples plus a cone per evaluation, so this is the number that says whether that
        // is affordable rather than assuming it: two nanoTime calls on a worker thread.
        final long evaluationStart = System.nanoTime();
        ReusableRaycastContext.resetRaycasts();

        // Snap flag read once at the top for all smoothing (occlusion + water factor).
        final boolean snap = this.source.isImmediateUpdate();

        final float airAbsorptionFactor = calculateWeatherAbsorption(ctx, soundPos, ctx.playerEyePosition);
        // Real ray-traced occlusion, time-smoothed so a geometric boundary (a ray starting
        // to clip the ground a few blocks away) fades instead of snapping the muffling.
        final float occlusionAccumulation = this.source.smoothOcclusion(
                calculateOcclusion(ctx, soundPos, ctx.playerEyePosition), snap);
        // The occlusion measurement IS a decibel loss (see calculateOcclusion), so the exponent is its
        // negation directly - see the note on the removed round trip there.
        final float sendCoeff = -occlusionAccumulation;

        // Broadband restore value: drives the level through pow(x, 0.1) and, unless diffraction
        // damps it, the high-frequency gain as well.
        float directCutoff = (float) MathStuff.exp(sendCoeff);
        // High-frequency restore value. Equal to directCutoff unless diffraction applies with
        // diffractionHfDamping > 1, which damps the restored highs without touching the level.
        float directHfCutoff = directCutoff;

        // Handle any dampening effects from the player, like head in water
        directCutoff *= 1F - ctx.auralDampening;

        // Damping when the path between the sound and the listener passes through water.
        // Water strongly absorbs high frequencies and reduces the perceived volume, so a
        // sound heard across a body of water (e.g. underwater -> shore, or the reverse)
        // should sound muffled and quieter.
        //
        // Measured BEFORE the reverb trace on purpose. The trace's reflection-tap delay reads the
        // submerged share (see the tap site in traceReverb) to interpolate the speed of sound between
        // air (343 m/s) and water (1480 m/s); reading it after the trace meant the tap always used the
        // PREVIOUS evaluation's water state, so a reflection that had just gone underwater arrived on an
        // air-timed delay for one update, and the first evaluation of any sound always used air.
        final Vec3 rawSourcePos = this.source.getPosition();
        final float waterLength = calculateWaterPathLength(ctx, rawSourcePos, ctx.playerEyePosition);
        // Submerged SHARE of the direct path. Water carries sound ~4.3x faster than air, so a reflection
        // that travels underwater must arrive sooner; see the tap site.
        final float straightDistance = (float) rawSourcePos.distanceTo(ctx.playerEyePosition);
        this.lastSubmergedShare = straightDistance > 0.01F
                ? MathStuff.clamp1(waterLength / straightDistance)
                : 0F;

        final ReverbTrace reverb = new ReverbTrace();
        traceReverb(ctx, soundPos, sendCoeff, reverb);

        // The direct path gets a brightness floor from the reflections, after the trace has measured how many
        // of them come back through the listener's own airspace. This is the original mod's rule, restored:
        // an enclosed space darkens the direct sound hard, and without the floor nothing puts the brightness
        // back, which is what made a cave sound thin rather than reverberant.
        //
        // It can only RAISE the cutoff, so an open scene (where the floor is zero) is unaffected.
        directCutoff = Math.max(directCutoff, reverb.directCutoffFloor);
        directHfCutoff = Math.max(directHfCutoff, reverb.directCutoffFloor);

        // The edge path is already part of the occlusion (see calculateOcclusion), so there is nothing to
        // restore here. This used to run a second, ring-based diffraction probe over the top of it, which
        this.source.smoothDiffraction(0F, snap);

        float directGain = (float) MathStuff.pow(directCutoff, 0.1);

        // ROOM SIZE, BLENDED ACROSS THE TWO SPACES. finalizeSendGains scales the diffuse sends by a mean
        // free path, and that used to be the SOURCE's alone - so how reverberant a sound arrived depended
        // on where the sound was and never on where the listener was. Standing in a cave could not make
        // the world sound reverberant, because nothing in the system measured the listener's room.
        //
        // The blend is weighted by how CONNECTED the two spaces are, and that is what keeps it from
        // double-counting: room size has to be applied ONCE. When the source's reflections can reach the
        // listener (connectedShare -> 1) the sound arrives through the source's space, so that space sets
        // the scale; when they cannot, the listener hears it only through their own room, so that room
        // sets it. At connectedShare = 1 the blend is exactly the source's mean free path.
        //
        // Measured on a real session (608 probe rows): the rows with connectedShare >= 0.8 - the same
        // space, which is 52% of them - come out bit-for-bit unchanged, and every change lands on the
        // cross-space rows, which is the case this exists to fix.
        //
        // The reference is the same 20 the probe's `conn=` uses and the same one that gates the send
        // cutoffs in traceReverb; there is deliberately only one notion of "connected" in the model.
        final float connectedShare = MathStuff.clamp1(reverb.connectedAirspace / 20.0F);
        // The listener's own space is only measured when it can still change the answer. At
        // connectedShare 1 the blend collapses onto the source's mean free path, so casting the fan
        // would be pure cost - and that is 39% of measured evaluations. The short-circuit is EXACT,
        // not an approximation: the term it skips would be multiplied by exactly zero.
        final float effectiveMeanFreePath = connectedShare >= 1.0F
                ? this.lastReverbMeanFreePath
                : connectedShare * this.lastReverbMeanFreePath
                        + (1F - connectedShare) * cachedListenerMeanFreePath(ctx);
        finalizeSendGains(reverb, effectiveMeanFreePath);

        // The occlusion reaches the reverb tail through the send CUTOFFS, which finalizeSendGains applies
        // as the wet path's filter and again as pow(sendCutoff, 0.1). It is deliberately NOT multiplied
        // into the send GAINS as well.
        //
        // It used to be, as `sendGainN *= sendOcclusionGain`, which put the loss on the wet LEVEL a second
        // time. The sends already take pow(sendCutoffN, 0.1) - the same "the level gets a damped share of
        // the loss" convention the direct path uses through MATERIAL_LEVEL_RESTORE - and the extra factor
        // applied the remaining 100% on top. The wet level therefore carried 110% of the loss dB against
        // the direct path's 20%, so the wet-to-direct ratio fell as exp(sendCoeff) instead of holding at
        // 1.0. That is what made reverb disappear entirely a couple of blocks away from an opening, even
        // though the source's own cavity was fully excited and its field leaks through regardless.
        //
        // The form that factor took - max(direct transmission, a space-connectivity share) - was also how
        // the skylight contamination reached the reverb amount: the connectivity share came from a count
        // that ORed a line-of-sight test with a "sky visible above" test, and the sky test is vertical
        // and therefore direction blind. Removing the gate removes that path with it. Connectivity still
        // drives the send cutoffs, where it belongs, and is now measured by line of sight alone.
        // The send gains are the reverb AMOUNT, and they are the only quantity the trace produces that
        // nothing else smooths - see SourceContext.smoothSendGains for why they need it. Placed right
        // after finalizeSendGains so it runs on the finished gains (bounce weighting, room size, clamps,
        // cutoff) and not on an intermediate sum.
        final float[] sendGains = {reverb.sendGain0, reverb.sendGain1, reverb.sendGain2, reverb.sendGain3};
        this.source.smoothSendGains(sendGains, snap);
        reverb.sendGain0 = sendGains[0];
        reverb.sendGain1 = sendGains[1];
        reverb.sendGain2 = sendGains[2];
        reverb.sendGain3 = sendGains[3];

        if (ctx.player.isUnderWater()) {
            reverb.sendCutoff0 *= 0.4F;
            reverb.sendCutoff1 *= 0.4F;
            reverb.sendCutoff2 *= 0.4F;
            reverb.sendCutoff3 *= 0.4F;
        }

        // The low-pass (muffling) and the volume use separate per-block factors, both driven by the
        // submerged path length measured above. Muffling is kept strong so underwater sound is clearly
        // muffled in every direction and masks the reverb system's cut-off jitter in deep water; the
        // volume uses the square root of that factor (plus a floor) so a shallow crossing stays clearly
        // audible while a long underwater path never goes fully silent - just heavily muffled. Stacks
        // with the player-underwater damping above, which mirrors the real double damping (propagation
        // path + ear submerged).
        final float muffleFactor = (float) Math.pow(CONFIG.waterSoundMuffle, waterLength);
        final float gainFactor = (float) Math.pow(CONFIG.waterSoundDamping, waterLength);
        // Smooth toward the target so an entity bobbing at the water surface (or the player
        // wading) doesn't make the volume audibly jump from one 1-second refresh to the next.
        final float waterFactor = this.source.smoothWaterFactor(muffleFactor, snap);
        final float waterGainFactor = Math.max(0.15F, (float) Math.sqrt(gainFactor));


        // Probe for /dstune. Two rounds of changes were inaudible, so record what was actually
        // computed: if these numbers do not move when standing behind a wall, the path is not
        // running for the sound being listened to; if they do move and nothing is heard, the
        // problem is the filter or the audibility of the band, not the model.
        if (AudioTuning.logAudioTrace()) {
            AudioTuning.recordTrace(String.format(
                    "cat=%s skipped=%b occlusion=%.3f cutoff(occl)=%.4f cutoff(final)=%.4f hf(final)=%.4f "
                            + "level=%.4f conn=%.3f sky=%.3f material=%.3f lossdb=%.1f edge=%b edgedb=%.1f/%.1f/%.1f "
                            + "clear=%.2f walk=%d/%.1fm "
                            + "rv=%.1fm/%.2f/%.2f/%.2f far=%.0fm mfp=%d ret=%d face=%.3f erf=%.4f "
                            + "g=%.3f,%.3f,%.3f,%.3f "
                            + "rays=%d cost=%.0fus lmfp=%.2f water=%.1fm sub=%.2f muffle=%.4f wf=%.4f wg=%.4f aural=%.2f hfApplied=%.4f gainApplied=%.4f",
                    this.source.getCategory(), skipOcclusion(this.source.getCategory()), occlusionAccumulation,
                    MathStuff.exp(sendCoeff), directCutoff, directHfCutoff, directGain,
                    MathStuff.clamp1(reverb.connectedAirspace / 20.0F), reverb.outdoorShare,
                    this.lastMaterialSum, this.lastZoneLossDb,
                    this.lastEdgeFound,
                    this.lastEdgeDb[0], this.lastEdgeDb[1], this.lastEdgeDb[2], this.lastEdgeDelta,
                    this.lastWalkSegments, this.lastWalkDistance,
                    this.lastReverbFirstDistance, this.lastReverbHitFraction,
                    this.lastReverbReflectivity, this.lastReverbFarthest,
                    this.lastReverbMeanFreePath, this.lastReverbShared, this.lastReturnedBounces, this.lastFacingShare,
                    this.lastEarlyReflectionGain,
                    reverb.sendGain0, reverb.sendGain1, reverb.sendGain2, reverb.sendGain3,
                    ReusableRaycastContext.raycastCount(),
                    (System.nanoTime() - evaluationStart) / 1000.0D,
                    // The same value the sends used, read back out of the cache rather than cast again -
                    // the probe should report what was applied, not a second measurement.
                    cachedListenerMeanFreePath(ctx),
                    // Water values, added because this is the one subsystem the trace did not
                    // report - and the one a "underwater sounds different between ports" report
                    // needs. hfApplied is the decisive number: it is exactly what is handed to
                    // OpenAL as AL_LOWPASS_GAINHF (direct.gainHF = directHfCutoff * waterFactor in
                    // uploadSettings). If hfApplied matches on two ports but they sound different,
                    // the difference is in the OpenAL driver, not in this mod.
                    waterLength, this.lastSubmergedShare, muffleFactor, waterFactor, waterGainFactor,
                    ctx.auralDampening,
                    directHfCutoff * waterFactor, directGain * waterGainFactor));
        }

        uploadSettings(reverb, directHfCutoff, directGain, waterFactor, waterGainFactor, airAbsorptionFactor);
    }

    /**
     * Projects the reverb rays from the sound position and accumulates the four zone
     * send gains/cutoffs plus the per-bounce reflection ratios.
     */
    private void traceReverb(final WorldContext ctx, final Vec3 soundPos, final float sendCoeff, final ReverbTrace out) {

        // Bounces whose surface faces back along the incoming ray, i.e. that RETURN energy to the source.
        // Used for the early-reflection term. A second condition used to be applied - an unobstructed line
        // to the ear - and it read zero outdoors; see the long note in the bounce loop.
        float returnedBounces = 0F;
        // Bounces whose reflection point can REACH the listener: a clear straight line from the reflection
        // point to the ear. This is a CONNECTIVITY measure and it is the only thing that feeds the send
        // cutoffs and the direct-path floor.
        float connectedBounces = 0F;
        // Bounces with clear sky above. Observation point only - the probe reports it as `sky=`. It is
        // deliberately kept out of every calculation; see the note at the increment below.
        float outdoorBounces = 0F;
        // Read ONCE. The skylight count feeds the probe's `sky=` field and nothing else, so with the probe
        // off there is no reason to pay for a light lookup on every bounce - up to 128 per evaluation,
        // each one a chunk-level brightness query.
        final boolean traceProbe = AudioTuning.logAudioTrace();
        // Observation-point accumulators. See the lastReverb* fields for why they exist.
        float firstDistanceSum = 0F;
        float firstReflectivitySum = 0F;
        int firstHits = 0;
        float farthest = 0F;
        // Mean free path: the distance between consecutive reflections, summed over every ray and normalised
        // by the full ray x bounce count, so a ray that escapes contributes zero.
        double pathSum = 0D;
        // How much of the reflection comes off surfaces that RETURN energy to the listener. Two earlier
        // versions of this were wrong and both are worth recording:
        //   * |normal.y| alone    - "vertical" is not "facing the listener". A wall with its back to you has
        //                           |y| = 0 too. Worse, in a plain the upward and sideways rays eventually
        //                           strike distant terrain, whose normals are slanted, so a plain scored as
        //                           high as a valley (measured 0.43-0.98 in every scene).
        //   * reflection HEIGHT   - a one-block room's reflections are at ear height as well.
        // The measure below is the returned energy: the surface must face back along the incoming direction.
        // A second condition - a clear path to the ear - was removed after it was measured to zero every
        // outdoor scene. See the note in the bounce loop.
        float facingSum = 0F;
        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);

        for (int i = 0; i < REVERB_RAYS; i++) {

            // Whether this ray has so far found a bounce point with a clear straight line to the ear.
            //
            // PER RAY, not per trace. It was declared outside this loop, so once ONE ray's bounce point
            // could see the ear, every later ray's every bounce was counted as connected - the flag was
            // never reset. That saturated the weights built from this count at 1.0 in almost any scene,
            // which pinned the send cutoffs at "no darkening at all" and the direct-path floor at its
            // maximum, and made the probe's connectivity column unable to move.
            boolean earVisible = false;

            Vec3 origin = soundPos;
            Vec3 target = origin.add(REVERB_RAY_PROJECTED[i]);

            var rayHit = traceContext.trace(origin, target);

            if (isMiss(rayHit))
                continue;

            // First reflection: its distance is what separates a room from a valley, and its reflectivity is
            // what decides whether the reflection survives at all.
            firstHits++;
            firstDistanceSum += (float) origin.distanceTo(rayHit.getLocation());
            firstReflectivitySum += getReflectivity(ctx.world.getBlockState(rayHit.getBlockPos()));

            // Additional bounces
            BlockPos lastHitBlock = rayHit.getBlockPos();
            Vec3 lastHitPos = rayHit.getLocation();
            Vec3 lastHitNormal = surfaceNormal(rayHit.getDirection());
            Vec3 lastRayDir = REVERB_RAY_NORMALS[i];

            double totalRayDistance = origin.distanceTo(rayHit.getLocation());

            // Secondary ray bounces
            for (int j = 0; j < REVERB_RAY_BOUNCES; j++) {

                final float blockReflectivity = getReflectivity(ctx.world.getBlockState(lastHitBlock));
                final float energyTowardsPlayer = blockReflectivity * ENERGY_COEFF + ENERGY_CONST;

                final Vec3 newRayDir = MathStuff.reflection(lastRayDir, lastHitNormal);
                origin = MathStuff.addScaled(lastHitPos, newRayDir, 0.01F);
                target = MathStuff.addScaled(origin, newRayDir, MAX_REVERB_DISTANCE);

                rayHit = traceContext.trace(origin, target);
                final boolean missed = isMiss(rayHit);

                if (missed) {
                    totalRayDistance += lastHitPos.distanceTo(ctx.playerEyePosition);
                } else {
                    out.bounceRatio[j] += blockReflectivity;
                    // Segment length between this reflection and the previous one. Captured BEFORE
                    // lastHitPos is reassigned: using it afterwards measured the new point against itself and
                    // reported a mean free path of exactly 0.0 in every one of 414 probe rows.
                    final double segment = lastHitPos.distanceTo(rayHit.getLocation());
                    totalRayDistance += segment;
                    pathSum += segment;

                    lastHitPos = rayHit.getLocation();
                    lastHitNormal = surfaceNormal(rayHit.getDirection());
                    lastRayDir = newRayDir;
                    lastHitBlock = rayHit.getBlockPos();

                    // Does this reflection actually RETURN energy to the listener? The surface must face BACK
                    // along the direction the wave arrived from. The wave arrives along newRayDir (the
                    // direction the trace was cast in), so the surface returns it if its normal opposes that
                    // direction. Testing against the direction to the LISTENER instead is subtly wrong: a wall
                    // can face the listener without having been illuminated by the source at all.
                    //
                    // There used to be a SECOND condition here - an unobstructed straight path from this
                    // reflection point to the listener's ear - and it was the single thing that made a valley
                    // silent. Measured offline against synthetic terrain (tools/sim_gate_sweep.py):
                    //
                    //     scene            without it   with it
                    //     steep valley        0.347       0.000
                    //     hill -> bowl        0.272       0.000
                    //     wide valley         0.120       0.000
                    //     plain               0.015       0.010
                    //     room                0.106       0.106
                    //
                    // It zeroes EVERY outdoor scene while leaving an indoor one untouched, because the ear
                    // sits ~1.6 blocks above the ground: a reflection point on a valley wall sees it at a
                    // grazing angle, so any terrain rise in between blocks the straight line. Indoors every
                    // reflection point is in the same room, the test passes, and the bug is invisible. It also
                    // cost one extra raycast per bounce, up to 128 per trace.
                    //
                    // `towardsSource > 0` already answers the question the gate was meant to answer: a plain's
                    // reflections are its own ground, whose normal points up, so a ray leaving the source
                    // upward and reflecting off the ground again contributes nothing.
                    final double towardsSource = -lastHitNormal.dot(newRayDir);

                    // Reflectivity-weighted, accumulated as an ABSOLUTE total and normalised at the end by the
                    // number of RAYS. Two earlier normalisations were wrong and both are worth recording:
                    //   * a MEAN over the bounces that happened to hit. A plain hits very few surfaces, so the
                    //     mean was dominated by whichever distant slope one ray happened to strike: measured, a
                    //     flat plain scored 0.85 while only 0.05 of its bounces could return anything at all.
                    //   * the total divided by rays x bounces (128). Most rays escape to the sky after one or
                    //     two bounces, so the divisor was far larger than the number of terms and a valley
                    //     collapsed from 0.35 to 0.02. Dividing by a "reference" rays x 8 was a fudge that still
                    //     starved it 8x and left every scene at 0.00-0.01.
                    // The numerator is a sum over the rays, so the divisor is the number of rays.
                    final float returned = blockReflectivity * (float) Math.max(0.0D, towardsSource);
                    if (returned > 0F) {
                        returnedBounces += 1.0F;
                    }
                    // A bounce counts towards the send cutoffs - which is what makes a tail BRIGHT and long -
                    // when the reflection point can REACH the listener. ONE test now: a clear straight line
                    // from the reflection point to the ear.
                    //
                    // There used to be a second test ORed into the same count - open sky above the
                    // reflection point - on the reasoning that the two are blind in opposite places: the
                    // line test reads zero outdoors (the ear sits ~1.6 blocks above the ground, so a
                    // grazing line is blocked by any terrain rise) while the sky test reads zero
                    // underground. The union is not safe, for two reasons:
                    //
                    //   * the sky test is a VERTICAL measurement and therefore direction blind. A shaft dug
                    //     upward to the surface lights a whole column and marks every bounce under it as
                    //     connected, including bounces that face away from the listener entirely. That is
                    //     the same class of defect as the removed listener-openness term: a quantity that
                    //     cannot see direction was being allowed to raise a transmission. Digging a hole
                    //     to the sky made a source three levels below suddenly audible and reverberant.
                    //   * the outdoor case the sky test was added to rescue does not need rescuing.
                    //     Outdoors the direct path is barely occluded, so sendCoeff is about 0 and the
                    //     send cutoffs are already about 1; neither the floor nor the cutoff has anything
                    //     left to add there.
                    //
                    // So the sky count is kept, but only as an observation point. It gates nothing.
                    //
                    // `connectedBounces` is a COUNT, so this only has to be established once per RAY: if the
                    // ear was visible from an earlier bounce point of this same ray, the count is already
                    // decided and no later bounce can change it. Re-casting it on every bounce was 96 wasted
                    // raycasts per evaluation on defaults - about a third of the budget - for no effect on
                    // the result.
                    if (!earVisible) {
                        final Vec3 bounceAirStart = MathStuff.addScaled(lastHitPos, lastHitNormal, 0.01F);
                        if (isMiss(traceContext.trace(bounceAirStart, ctx.playerEyePosition)))
                            earVisible = true;
                    }
                    if (earVisible) {
                        connectedBounces += 1.0F;
                    }
                    if (traceProbe && openAir(ctx, lastHitPos)) {
                        outdoorBounces += 1.0F;
                    }
                    facingSum += returned;

                    // Farthest reflection of any bounce, measured FROM THE LISTENER. A reflection reaches
                    // the ear after travelling from the surface, so what matters is how far the surface is
                    // from the EAR, not from the source.
                    //
                    // Measured from the SOURCE it was useless, and the probe showed it: `far` read 1-16 m in
                    // scenes whose `rv` (first-reflection distance) was 23-76 m. Rays leave the source in every
                    // direction, so they strike whatever nearby terrain is in the way, while a distant valley
                    // wall subtends a tiny solid angle and is almost never sampled. A valley therefore looked
                    // like it had a wall a few blocks away, while a narrow stone gorge, where the walls really
                    // ARE close to the listener, reported the strongest reflection.
                    farthest = Math.max(farthest, (float) ctx.playerEyePosition.distanceTo(lastHitPos));
                }

                assert totalRayDistance >= 0;
                final float reflectionDelay = (float) totalRayDistance * 0.12F * blockReflectivity;

                final float cross0 = 1.0F - MathStuff.clamp1(Math.abs(reflectionDelay - 0.0F));
                final float cross1 = 1.0F - MathStuff.clamp1(Math.abs(reflectionDelay - 1.0F));
                final float cross2 = 1.0F - MathStuff.clamp1(Math.abs(reflectionDelay - 2.0F));
                final float cross3 = MathStuff.clamp1(reflectionDelay - 2.0F);

                out.sendGain0 += cross0 * energyTowardsPlayer * 6.4F;
                out.sendGain1 += cross1 * energyTowardsPlayer * 12.8F;
                out.sendGain2 += cross2 * energyTowardsPlayer * 12.8F;
                out.sendGain3 += cross3 * energyTowardsPlayer * 12.8F;

                // Nowhere to bounce off of, stop bouncing!
                if (missed) {
                    break;
                }
            }
        }

        out.bounceRatio[0] = out.bounceRatio[0] / REVERB_RAYS;
        out.bounceRatio[1] = out.bounceRatio[1] / REVERB_RAYS;
        out.bounceRatio[2] = out.bounceRatio[2] / REVERB_RAYS;
        out.bounceRatio[3] = out.bounceRatio[3] / REVERB_RAYS;

        // The cutoff weights below want a normalised 0..1 share; the raw counts are kept for the probe.
        final float connectedAirspace = connectedBounces * RECIP_TOTAL_RAYS * 64F;
        out.connectedAirspace = connectedAirspace;
        out.outdoorShare = MathStuff.clamp1(outdoorBounces * RECIP_TOTAL_RAYS * 64F / 20.0F);

        // Observation point. `ret` is the raw count of returning bounces, so it reads as "of 128 bounces,
        // how many came back off a surface that faced the source" - a valley scores far above a plain.
        this.lastReverbFirstDistance = firstHits > 0 ? firstDistanceSum / firstHits : 0F;
        this.lastReverbReflectivity = firstHits > 0 ? firstReflectivitySum / firstHits : 0F;
        this.lastReverbHitFraction = firstHits / (float) REVERB_RAYS;
        this.lastReverbShared = (int) connectedBounces;
        this.lastReturnedBounces = (int) returnedBounces;
        this.lastReverbFarthest = farthest;
        // Mean free path, normalised by rays x bounces rather than by the bounces that happened.
        //
        // As a mean over hits it was inflated by escapes: in a plain most rays leave for the sky and never
        // bounce, so the few that do - striking distant terrain - dominated the average. Measured, a flat
        // plain reported mfp 139 m, which is not a property of a plain. Dividing by the full ray x bounce
        // count makes an escaping ray contribute zero, so the number falls towards zero exactly where the
        // space is open and there is nothing to reflect off.
        this.lastReverbMeanFreePath = (float) (pathSum / (double) (REVERB_RAYS * REVERB_RAY_BOUNCES));

        final float sharedAirspaceWeight0 = MathStuff.clamp1(connectedAirspace / 20.0F);
        final float sharedAirspaceWeight1 = MathStuff.clamp1(connectedAirspace / 15.0F);
        final float sharedAirspaceWeight2 = MathStuff.clamp1(connectedAirspace / 10.0F);
        final float sharedAirspaceWeight3 = MathStuff.clamp1(connectedAirspace / 10.0F);

        final float exp1 = (float) MathStuff.exp(sendCoeff);
        final float exp2 = (float) MathStuff.exp(sendCoeff * 1.5F);
        out.sendCutoff0 = exp1 * (1.0F - sharedAirspaceWeight0) + sharedAirspaceWeight0;
        out.sendCutoff1 = exp1 * (1.0F - sharedAirspaceWeight1) + sharedAirspaceWeight1;
        out.sendCutoff2 = exp2 * (1.0F - sharedAirspaceWeight2) + sharedAirspaceWeight2;
        out.sendCutoff3 = exp2 * (1.0F - sharedAirspaceWeight3) + sharedAirspaceWeight3;

        // The physical reading is that a reflection arriving through open air has crossed no rock, so the
        // direct path's material darkening is the wrong filter for it - the same argument already made for
        // the early-reflection cutoffs below. Reported to the caller through the trace so the direct filter
        // can use it.
        final float averageSharedAirspace = (sharedAirspaceWeight0 + sharedAirspaceWeight1
                + sharedAirspaceWeight2 + sharedAirspaceWeight3) * 0.25F;
        out.directCutoffFloor = Math.max((float) Math.sqrt(averageSharedAirspace) * 0.2F, 0F);

        // ------------------------------------------------------- returned energy (open space)
        //
        // A valley echo is physically an EARLY reflection off a distant surface. Summing it into the late
        // zones could never carry it, because reflectivity is multiplied into that chain FOUR times
        // (bounceRatio, the per-bounce energy, the delay, and zone2/3's refl^3 / refl^4), so natural terrain -
        // grass 0.15, dirt 0.35, against stone's 1.0 - is crushed. Measured: zone2 and zone3 send gains are
        // EXACTLY ZERO for every row below reflectivity 0.4, which is where all outdoor terrain sits.
        //
        // The term is the share of the rays that come back off a surface facing the source. It is a sum over
        // Divided by rays x bounces - the number of terms actually summed - so the result really is the
        // SHARE its name claims, in 0..1.
        //
        // It used to be divided by the number of RAYS alone, on the reasoning that "most rays escape to
        // the sky, so the divisor is far larger than the number of terms" and the term was being starved.
        // That reasoning holds OUTDOORS and fails everywhere else - which is the same mistake as the
        // skylight gate: a conclusion drawn in one kind of scene and applied to all of them. Measured in
        // a cave/mine session (291 probe rows, 1.20.1), face was over 1.0 in 290 of 290 rows, median 2.36
        // and max 3.28, because underground almost every ray keeps bouncing and the 128 terms really are
        // all there. A share that can reach 4 is not a share.
        final float facingShare = facingSum * RECIP_TOTAL_RAYS;
        this.lastFacingShare = facingShare;
        // Scaled by how far the reflection actually travelled before it arrived.
        //
        // The boost below describes an EARLY reflection off a DISTANT surface - a valley wall, a cavern
        // wall - which is a separate arrival and therefore feeds the tail. In a small room the first
        // reflection is 1-2 m away: it arrives WITH the direct sound and fuses into it, and boosting the
        // tail for it is simply making a small room loud. Measured on a 3x3x3 hut, this term was 0.66
        // against a geometry contribution of 0.22, so it was 75% of the wet path - g1 logged at 0.889,
        // the wet path sitting at 65% of the dry path. A cave is unaffected: its first reflection is
        // 5-15 m away and the scale is 1.
        //
        // The measurement already exists as lastReverbFirstDistance (the probe's rv=) and is set above.
        // The reference is the same one used for the brightness scale, because the physical statement is
        // the same: a reflection stops being part of the direct sound only once it has travelled far
        // enough through air.
        final float reflectionDistanceScale =
                Math.min(1.0F, this.lastReverbFirstDistance / ECHO_SCALE_REFERENCE_M);
        // Smoothed: the raw value is a ratio of two 32-ray averages and was measured to swing by up to 0.36
        // between evaluations 0.4 s apart, which made the tail appear and vanish at random.
        // This is the DISCRETE early reflection, and it is now routed to the discrete tap
        // (AL_EAXREVERB_REFLECTIONS_GAIN) instead of into the diffuse sends. The distinction is the
        // whole point, and getting it wrong is what put reverb in a valley:
        //
        //   * a diffuse tail requires a CLOSED space for reflections to accumulate in
        //   * a valley returns a single slap off a wall
        //
        // facingShare measures the second - the share of rays that come back off a surface facing the
        // source - and it is independent of how open the space is, so feeding it into a send with a
        // 0.55 s or 1.68 s decay gave a valley the tail of a room.
        final float reflectionTap = this.source.smoothEarlyReflection(
                (float) Math.sqrt(MathStuff.clamp1(this.lastReverbReflectivity))
                        * facingShare * REFLECTION_DENSITY_GAIN * reflectionDistanceScale,
                this.source.isImmediateUpdate());
        // Delay from the first-reflection distance: a near wall fuses with the direct sound, a distant
        // one arrives as a separate event. OpenAL clamps the value to its own 0..0.3 s range.
        //
        // The speed of sound follows the path: water is ~1480 m/s against air's 343, so a reflection
        // that travels underwater arrives ~4.3x sooner. lastSubmergedShare is measured alongside the
        // water damping (the submerged share of the direct path) and is the right proxy here, because
        // the reflection and the direct sound cross the same body of water.
        final float speedOfSound = SPEED_OF_SOUND
                + (WATER_SPEED_OF_SOUND - SPEED_OF_SOUND) * this.lastSubmergedShare;
        final float reflectionTapDelay = this.lastReverbFirstDistance / speedOfSound;

        // The tap is a property of the effect SLOT, so it is GLOBAL - one value for every sound in the
        // zone. Writing it straight from each source evaluation meant the value was decided by whichever
        // source happened to be evaluated last, so it depended on evaluation order rather than on the
        // space. Keeping the strongest reflection seen in this pass makes it order independent and still
        // says the true thing ("a reflection this strong exists in this space"). Reset by beginPass().
        if (reflectionTap >= passTapGain) {
            passTapGain = reflectionTap;
            // Published as a PAIR under the reader's lock, so the gain and the delay always describe
            // the same reflection.
            synchronized (Effects.TAP_LOCK) {
                Effects.reflectionTapGain = reflectionTap;
                Effects.reflectionTapDelay = reflectionTapDelay;
            }
        }
        // Reported by the probe as `erf=`. It used to be written into the trace as well, but nothing read
        // it there - the discrete arrival goes to the slot's reflection tap, and the diffuse sends get
        // nothing from it.
        this.lastEarlyReflectionGain = reflectionTap;
        // The early-reflection zones are kept BRIGHT. A reflection off a distant wall travels through AIR,
        // so it never crosses the rock that darkens the direct path - the occlusion cutoff is the wrong
        // filter for it. Without this the tail inherited the direct path's darkening and a valley sounded
        // like "a weakened cave", which is exactly what the user reported. Scaled by the returned share, so
        // an open plain gets no brightening at all.
        // Scaled by how far a reflection actually travels before it hits something. The reflection is kept
        // bright because it crossed AIR rather than the rock that darkens the direct path - and how much
        // air it crossed IS the mean free path.
        //
        // Scaling only the 0.35 floor was tried first and did not work: for a hut the facingShare term is
        // 0.78, so the scaled floor was a small part of the sum and the saturated term dominated - measured,
        // a 3x3x3 hut moved just 1.000 -> 0.884 and stayed brighter than its own direct sound through a
        // wall. The whole expression has to scale, because the whole expression is what says "this
        // reflection crossed open air".
        //
        // Measured (tools/verify_brightness_fix.py): a 3x3x3 hut goes 1.000 -> 0.337, darker than every
        // direct path through its wall; a cavern and a stone hall are unchanged at 1.000; a wooden hall
        // keeps 0.883, so it still reads as a hall rather than as a hut.
        //
        // The 0.65 coefficient is now applied to facingShare DIRECTLY, with no multiplier. There used to
        // be a *4.0 on it, which made sense only while facingShare was divided by the number of rays
        // instead of by rays x bounces and so ran about 4x too large. With the divisor fixed, leaving the
        // 4.0 in place saturates the whole expression: it reaches 1.0 at facingShare = 0.25, and since
        // the measured median is about 0.59 the brightness floor was pinned at its maximum in essentially
        // every scene - which lifted the send cutoffs to 1.0 and stopped occlusion reaching the wet path
        // at all.
        final float reflectionScale = Math.min(1.0F, this.lastReverbMeanFreePath / ECHO_SCALE_REFERENCE_M);
        out.earlyReflectionCutoff = MathStuff.clamp1(
                (0.35F + 0.65F * facingShare) * reflectionScale);
    }

    /** Applies the bounce-ratio scaling and clamps the send gains. */
    private static void finalizeSendGains(final ReverbTrace reverb, final float meanFreePath) {
        reverb.sendGain1 *= reverb.bounceRatio[1];
        reverb.sendGain2 *= (float) MathStuff.pow(reverb.bounceRatio[2], 3.0);
        reverb.sendGain3 *= (float) MathStuff.pow(reverb.bounceRatio[3], 4.0);

        // Reflection density, applied AFTER the reflectivity weighting. The weighting above models how many
        // bounces a diffuse field gets before it dies, which genuinely depends on the material; an early
        // reflection off a distant surface is one reflection, and its strength is already in the term. Adding
        // it before the weighting made it inaudible outdoors, where bounceRatio^3 is 0.043.
        //
        // ---------------------------------------------------------------- room size
        //
        // Everything above is blind to how BIG the space is, which is why a small stone room had reverb.
        // The mean free path is the Sabine mean free path and is what the trace already computes as
        // pathSum / (rays x bounces); a room's diffuse field strength is set by it. Large spaces sit above
        // the reference and are untouched, which keeps a cave and a hall sounding like themselves.
        //
        // The DISCRETE early reflection is no longer added here - it goes to the reflection tap, which is
        // where a single arrival belongs. Measured with tools/compare_reverb_terms.py, the diffuse send
        // after the bounceRatio weighting reaches 0.80 for stone and 0.14 for wood, so a cave keeps a
        // substantial tail without it. What the discrete term had been producing was the slap, stretched
        // by a 0.55 s / 1.68 s decay.
        final float roomSizeScale = Math.min(1.0F, meanFreePath / ECHO_SCALE_REFERENCE_M);
        reverb.sendGain1 *= roomSizeScale;
        reverb.sendGain2 *= roomSizeScale;

        reverb.sendGain0 = MathStuff.clamp1(reverb.sendGain0);
        reverb.sendGain1 = MathStuff.clamp1(reverb.sendGain1);
        reverb.sendGain2 = MathStuff.clamp1(reverb.sendGain2 * 1.05F - 0.05F);
        reverb.sendGain3 = MathStuff.clamp1(reverb.sendGain3 * 1.05F - 0.05F);

        reverb.sendGain0 *= (float) MathStuff.pow(reverb.sendCutoff0, 0.1);
        reverb.sendGain1 *= (float) MathStuff.pow(reverb.sendCutoff1, 0.1);
        reverb.sendGain2 *= (float) MathStuff.pow(reverb.sendCutoff2, 0.1);
        reverb.sendGain3 *= (float) MathStuff.pow(reverb.sendCutoff3, 0.1);

        // The early-reflection zones are kept bright. A reflection off a distant wall crosses AIR, not rock,
        // so the direct path's occlusion cutoff is the wrong filter for it - inheriting that darkening is why
        // a valley read as "a weakened cave". Only raises: an enclosed space's own cutoff is already higher.
        reverb.sendCutoff1 = Math.max(reverb.sendCutoff1, reverb.earlyReflectionCutoff);
        reverb.sendCutoff2 = Math.max(reverb.sendCutoff2, reverb.earlyReflectionCutoff);
        // Zone 3 carries the 4.142 s tail and needs the same argument as the zones above it: a reflection
        // off a distant wall crosses AIR, not rock, so the direct path's occlusion cutoff is the wrong
        // filter for it. Leaving zone 3 out of this made the longest tail the darkest one, which is
        // backwards - and 1.20.1 had all three while 1.21.1 and 26.1 only had two, so the versions did
        // not sound alike.
        reverb.sendCutoff3 = Math.max(reverb.sendCutoff3, reverb.earlyReflectionCutoff);
    }

    /** Writes the computed effect parameters onto the source under its sync lock. */
    private void uploadSettings(final ReverbTrace reverb, final float directHfCutoff, final float directGain,
            final float waterFactor, final float waterGainFactor, final float airAbsorptionFactor) {

        final LowPassData lp0 = this.source.getLowPass0();
        final LowPassData lp1 = this.source.getLowPass1();
        final LowPassData lp2 = this.source.getLowPass2();
        final LowPassData lp3 = this.source.getLowPass3();
        final LowPassData direct = this.source.getDirect();
        final SourcePropertyFloat prop = this.source.getAirAbsorb();

        synchronized (this.source.sync()) {
            lp0.gain = reverb.sendGain0 * waterGainFactor;
            lp0.gainHF = reverb.sendCutoff0 * waterFactor;
            lp0.setProcess(true);

            lp1.gain = reverb.sendGain1 * waterGainFactor;
            lp1.gainHF = reverb.sendCutoff1 * waterFactor;
            lp1.setProcess(true);

            lp2.gain = reverb.sendGain2 * waterGainFactor;
            lp2.gainHF = reverb.sendCutoff2 * waterFactor;
            lp2.setProcess(true);

            lp3.gain = reverb.sendGain3 * waterGainFactor;
            lp3.gainHF = reverb.sendCutoff3 * waterFactor;
            lp3.setProcess(true);

            direct.gain = directGain * waterGainFactor;
            direct.gainHF = directHfCutoff * waterFactor;
            direct.setProcess(true);

            prop.setValue(airAbsorptionFactor);
            prop.setProcess(true);

        }
    }

    private void clearSettings() {
        synchronized (this.source.sync()) {
            source.getLowPass0().setProcess(false);
            source.getLowPass1().setProcess(false);
            source.getLowPass2().setProcess(false);
            source.getLowPass3().setProcess(false);
            source.getDirect().setProcess(false);
            source.getAirAbsorb().setProcess(false);
        }
    }
    /**
     * The occlusion, from the two routes the sound can take to the listener, combined as amplitudes.
     *
     * <p>Each term is a measurement:
     * <ul>
     *   <li><b>material</b> - what the block material along the STRAIGHT path transmits. This is the direct
     *       path, and it is what makes a wall's THICKNESS matter: one block of stone (occlusion 0.5)
     *       transmits about 0.83, a four-block wall about 0.22. An opening found beside the line does not
     *       change this measurement - the edge term below carries it.</li>
     *   <li><b>edge</b> - the diffracted path around the same opening, per octave band. It is <b>zero when
     *       there is no opening within reach</b>, because a barrier wider than the search reaches has no edge
     *       to bend around.</li>
     * </ul>
     *
     * <p>They arrive independently and add incoherently. A thin wall transmits well through the material and
     * diffracts well around its edge, so it is audible; a thick wall does neither, so it is not. A room needs
     * no separate treatment: its wall's material transmission is low, and the room's own contribution is the
     * reverb, which the reverb trace already measures.
     *
     * <p>The material walk measures the STRAIGHT source-to-listener line, always - even when the edge search
     * has found an opening beside it. The opening is carried entirely by the edge term. Measuring the bent
     * path through the opening instead was implemented once and reverted; this javadoc used to describe it
     * anyway, which is exactly the trap that makes a later reader "fix" something that is not there.
     *
     * <p>Both terms are returned in dB. The caller negates them straight into {@code exp(-x)}, which is
     * what a decibel loss is. An earlier revision divided by the absorption coefficient here and multiplied
     * by the same constant at the call site; the two cancelled exactly, so the round trip changed no value
     * and only obscured the units. It has been removed.
     */
    private float calculateOcclusion(final WorldContext ctx, final Vec3 origin, final Vec3 target) {

        if (skipOcclusion(this.source.getCategory())) {
            this.lastOccluderPos = null;
            this.lastEdgePoint = null;
            this.lastCenterOcclusion = 0F;
            this.lastZoneLossDb = 0F;
            this.lastEdgeFound = false;
            this.lastEdgeDelta = 0F;
            this.lastWalkSegments = 0;
            this.lastWalkDistance = 0D;
            return 0F;
        }

        assert ctx.world != null;
        assert ctx.player != null;

        // The material along the line, and where it enters the first obstacle (the anchor for the edge search).
        final Vec3 rayOrigin = stepOutOfSolid(ctx.world, origin, target);
        final Vec3[] centerOccluder = new Vec3[]{null};
        final float centerMaterial = traceOcclusion(ctx, rayOrigin, target, centerOccluder);
        this.lastOccluderPos = centerOccluder[0];
        this.lastCenterOcclusion = centerMaterial;

        // The opening the wave escapes through, if there is one within reach. This drives the EDGE term only.
        final float[] edgeAmplitude = this.lastOccluderPos != null
                ? edgeAmplitudePerBand(ctx, origin, target)
                : null;
        this.lastEdgeFound = edgeAmplitude != null;

        // The material walk's loss is used AS MEASURED. It used to be scaled by an "open share" read from the
        // LISTENER's sky light, on the reasoning that only energy arriving along a blocked direction has to
        // cross rock. That reasoning is right for a listener at a cave mouth, but sky light is a VERTICAL
        // measurement and therefore direction blind: it cannot tell an opening toward the source from one on
        // the far side. Two consequences, both wrong:
        //
        //   * a hole dug to the sky cancelled the loss for a sound coming from BELOW it - the listener's own
        //     ceiling opening cleared a path through the floor;
        //   * an open scene reads sky light 1, so transmitted became 1, and sqrt(t*t + e*e) then clamped to 1
        //     - which pinned the EDGE term to 1 as well. Outdoor occlusion and outdoor diffraction were both
        //     dead, so the edge model's entire tuning only ever applied indoors, the opposite of its intent.
        //
        // The walk already measures what is actually in the way, along the actual line. A quantity that cannot
        // see direction must not be allowed to cancel it.
        final float materialLossDb = MATERIAL_LOSS_DB_PER_UNIT * centerMaterial;
        // The probe keeps reporting a TRANSMISSION FACTOR, so the column stays comparable with every earlier
        // session. It is taken at the reference frequency, where the per-band scaling is 1.
        this.lastMaterialSum = amplitudeFromDb(materialLossDb);

        final int bands = bandCount();
        float powerSum = 0F;
        float weightTotal = 0F;
        for (int band = 0; band < bands; band++) {
            // The material term is per band: a wall is far less transparent to a high frequency than to a
            // low one (the mass law), so sharing one coefficient across the bands understated how dull a
            // wall sounds. See spectralTiltDb.
            final float bandLossDb = materialLossDb + spectralTiltDb(materialLossDb, bandFrequency(band));
            float amplitude = amplitudeFromDb(bandLossDb);
            // Transmission through the material and diffraction around its edge are two INDEPENDENT
            // paths, so their POWERS add: combining amplitudes means the square root of the sum of
            // squares. Adding the amplitudes let the total exceed the incident energy - a linear sum
            // of two terms each up to 1 reaches 2 - and the clamp below only hid that. This form is
            // bounded by 1 by construction, so the clamp is a safety net rather than part of the model.
            if (edgeAmplitude != null && band < edgeAmplitude.length) {
                final float edge = edgeAmplitude[band];
                amplitude = (float) Math.sqrt(amplitude * amplitude + edge * edge);
            }
            amplitude = MathStuff.clamp1(amplitude);
            // The broadband figure is an ENERGY average, not an average of dB. Averaging dB is a GEOMETRIC
            // mean of the amplitudes, which is dragged toward whichever band is most attenuated - behind
            // a wall that is always the top band - so it reported the model several dB darker than the
            // energy actually arriving. Same power convention as the material/edge combination above.
            final float weight = bandWeight(band);
            powerSum += weight * amplitude * amplitude;
            weightTotal += weight;
        }
        final float lossDb = weightTotal > 0F
                ? (float) (-20.0D * Math.log10(Math.max(1.0E-6F, (float) Math.sqrt(powerSum / weightTotal))))
                : 0F;

        // The level loss drives the direct low-pass as exp(-loss), and the engine also
        // derives the direct LEVEL from it as pow(that, 0.1). Feeding it the full transmission loss applied
        // that loss TWICE - once as a filter, once as level - and drove directCutoff to ~1e-26 behind rock,
        // which is silence, not muffling. The filter gets the full loss (that is what muffling is); the level
        // gets a heavily damped share of it, which is what MATERIAL_LEVEL_RESTORE is for.
        final float levelLossDb = lossDb * MATERIAL_LEVEL_RESTORE;

        this.lastZoneLossDb = lossDb;
        // The level loss in dB, which is exactly what the caller needs for exp(-x). The division by
        // `absorption` that used to be here cancelled exactly against the multiplication by the same
        // constant at the call site; removing the round trip changes no value, it only stops the units
        // from being obscured.
        return levelLossDb;
    }

    /**
     * Amplitude for a transmission loss in dB, floored so it can never reach the -120 dB the 1e-6 clamp
     * imposes: an inaudible-by-clamping term is not a model, it is a hole where the physics should be.
     */
    private static float amplitudeFromDb(final float lossDb) {
        return (float) Math.pow(10.0D, -Math.min(MATERIAL_MAX_LOSS_DB, Math.max(0F, lossDb)) / 20.0D);
    }

    /**
     * The listener's raw skylight level (0-15). Exposed so the sound processor can force an immediate
     * re-evaluation when the listener crosses a boundary - entering or leaving a cave mouth, or digging
     * through to the sky - rather than waiting up to half a second for the next scheduled update. The
     * openness term this was originally added for is gone, but the trigger still matters: it is what keeps
     * the material walk's transition smooth instead of stepped.
     */
    public static int listenerSkyLight(final WorldContext ctx) {
        if (ctx == null || ctx.world == null || ctx.playerEyePosition == null)
            return -1;
        final Vec3 eye = ctx.playerEyePosition;
        return ctx.world.getBrightness(LightLayer.SKY, BlockPos.containing(eye.x(), eye.y(), eye.z()));
    }

    /**
     * Marks the start of a new processing pass. Called by the sound processor before it evaluates its batch.
     */
    public static void beginPass() {
        currentPass++;
        // The reflection tap is global (per effect slot), so it is decided per PASS, not per source:
        // see the tap site. Reset here so each pass keeps its own strongest reflection.
        passTapGain = 0F;
    }

    /**
     * Strongest discrete-reflection tap seen in the current pass. The tap lives on the effect slot, so
     * it is one global value; without this it was overwritten by every source evaluation and ended up
     * being whatever the last-evaluated source computed, which made it order dependent.
     */
    private static volatile float passTapGain;

    private float traceOcclusion(final WorldContext ctx, final Vec3 origin, final Vec3 target,
                                 final Vec3[] firstOccluder) {
        float factor = 0F;

        Vec3 lastHit = origin;
        var traceContext = new ReusableRaycastContext(ctx.world, origin, target, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY);
        var itr = new ReusableRaycastIterator(traceContext);
        // The walk has to cover the whole line. The iterator advances half a block per step, so a fixed segment
        // count measures only the first few blocks - with 5 it stopped after 2.5 blocks and a rock layer fifteen
        // blocks away contributed nothing (measured: material 0.0000 with solid rock in between, which made a
        // surface source fully audible from a mine). The count is derived from the distance instead, with a
        // floor so a short line is still sampled and a cap so a very long one stays affordable.
        final int segments = Math.max(occlusionSegments(),
                Math.min(OCCLUSION_MAX_SEGMENTS, (int) Math.ceil(origin.distanceTo(target) / 0.5D) + 1));
        int walked = 0;
        for (int i = 0; i < segments; i++) {
            if (!itr.hasNext())
                break;
            var result = itr.next();
            final Vec3 hit = result.getLocation();
            final double rayDistance = lastHit.distanceTo(hit);
            if (rayDistance <= 0D) {
                lastHit = hit;
                continue;
            }
            // Attribute the segment to the block it passes THROUGH, sampled at its midpoint. Reading the block
            // at the segment's start instead charged every segment to the block the ray had just left: the
            // obstacle's own block was only counted once the ray was already out the far side, so the segment
            // inside it was charged to the air in front and the LAST hit was never counted at all.
            //
            // The midpoint must be built from the DELTA, not from the absolute hit position. MathStuff.addScaled
            // scales its second argument, so passing the absolute hit gave lastHit + 0.5*hit - a point near half
            // the world coordinate, i.e. a different chunk entirely. Measured effect: material was 0.9710 in
            // 433 of 433 probe rows regardless of distance, because every sample but the first landed on
            // unloaded air and contributed nothing.
            final Vec3 mid = lastHit.lerp(hit, 0.5D);
            final BlockPos midBlock = BlockPos.containing(mid.x(), mid.y(), mid.z());
            final float occlusion = getOcclusion(ctx.world.getBlockState(midBlock));
            // Occlusion is scaled by the distance traveled through the block.
            factor += (float) (occlusion * rayDistance);
            if (occlusion > 0F && firstOccluder != null && firstOccluder[0] == null) {
                // Record the MIDPOINT of the first occluded segment: the middle of the obstacle's body
                // along the line. The edge search runs from here, so starting on the near surface would
                // put the ray's own start inside the obstacle it has to clear, while starting at the
                // entry point left it unable to reach past the silhouette.
                firstOccluder[0] = mid;
            }
            lastHit = hit;
            walked++;
        }

        // Reach, not just result: a zero factor is only meaningful next to how much of the line was covered.
        // Without this a clear line and a walk that stopped early are the same reading, which is exactly how
        // the "material is always zero" question stayed open.
        this.lastWalkSegments = walked;
        this.lastWalkDistance = origin.distanceTo(lastHit);

        return factor;
    }

    /**
     * Frequency for band {@code index}, or the single configured frequency when the wavelength model is
     * off (in which case every band collapses to the same value and the combination is a no-op).
     */
    private static float bandFrequency(final int index) {
        if (!AudioTuning.realismWavelength())
            return AudioTuning.realismFrequencyHz();
        final float[] bands = AudioTuning.bandFrequencies();
        final float[] fallback = bands.length == 0 ? DEFAULT_BAND_FREQUENCIES : bands;
        final int i = Math.max(0, Math.min(fallback.length - 1, index));
        return fallback[i];
    }

    /** Number of bands to measure: one when the wavelength model is off, else the configured count. */
    private static int bandCount() {
        if (!AudioTuning.realismWavelength())
            return 1;
        final float[] bands = AudioTuning.bandFrequencies();
        return bands.length == 0 ? DEFAULT_BAND_FREQUENCIES.length : bands.length;
    }

    /** Weight of band {@code index}, renormalised if the configured arrays get out of step. */
    private static float bandWeight(final int index) {
        final float[] weights = AudioTuning.bandWeights();
        final float[] use = weights.length == 0 ? DEFAULT_BAND_WEIGHTS : weights;
        // A band with no configured weight contributes NOTHING, not a default share. Returning
        // 1/length here meant that shortening the weight list (as the config tooltip invites:
        // '/dstune bandWeights 0.7,0.25,0.05') left every remaining band holding a third of the total
        // weight each, so the high bands - the ones that weigh least in the intended low-tilted set -
        // ended up dominating the combined result.
        if (index < 0 || index >= use.length)
            return 0F;
        return use[index];
    }

    /**
     * Extra transmission loss at {@code frequencyHz} over the 500 Hz reference, in dB. ADDITIVE, not a
     * multiplier: the mass law puts a partition's loss at {@code 20*log10(m*f) - 47}, so the frequency
     * term is a fixed number of dB per octave and is <b>independent of thickness</b>.
     *
     * <p>That independence is the whole point. The previous form was a multiplier of
     * {@code sqrt(f / f_ref)} on the dB loss, which scales the spectral TILT by thickness as well:
     * measured on this model, a 1-block wall tilted 9 dB between 63 Hz and 8 kHz, 5 blocks tilted
     * 46 dB, 10 blocks 92 dB and 20 blocks 185 dB. Beyond a few blocks the top bands are not
     * "attenuated", they are pinned at the {@link #MATERIAL_MAX_LOSS_DB} clamp, which is why a thick
     * barrier came out as a low-frequency rumble with nothing left above it.
     *
     * <p>The saturation term exists because a bare additive tilt would give zero thickness a spectrum.
     * {@code loss / (loss + SPECTRUM_SATURATION_DB)} keeps a thin barrier neutral - which is correct,
     * a thin barrier really is nearly transparent at every frequency - and lets the tilt settle at its
     * full value once the barrier is thick. It is one fitted constant, and it is the only new one here.
     *
     * <p>With {@code realismWavelength} off there is a single band at
     * {@link AudioTuning#realismFrequencyHz()}, which defaults to 500 Hz, so the tilt is zero and the
     * frequency-blind behaviour is preserved.
     */
    private static float spectralTiltDb(final float lossDb500, final float frequencyHz) {
        if (lossDb500 <= 0F)
            return 0F;
        final double octaves = Math.log(Math.max(100.0D, frequencyHz) / MATERIAL_REFERENCE_HZ) / LOG_2;
        final float saturation = lossDb500 / (lossDb500 + SPECTRUM_SATURATION_DB);
        return (float) (MATERIAL_SPECTRUM_DB_PER_OCTAVE * octaves * saturation);
    }

    /**
     * Extra transmission loss per octave of frequency, in dB. See {@link #spectralTiltDb}: this is the
     * mass law's frequency term, which does not depend on how thick the barrier is.
     */
    private static final double MATERIAL_SPECTRUM_DB_PER_OCTAVE = 6.0D;

    /**
     * Loss at which the spectral tilt is half developed, in dB. See {@link #spectralTiltDb}: without
     * this a barrier of zero thickness would still carry a spectrum.
     */
    private static final float SPECTRUM_SATURATION_DB = 12.0F;

    private static final double LOG_2 = Math.log(2.0D);

    // A distance-weighted occlusion sum - material near either end counting for more than material far
    // along the line - lived here, with its own tuning knob. It was removed deliberately when the aperture
    // was changed to ask a binary question per sample instead of walking segments: the walk it weighted no
    // longer exists. The knob outlived it and has been removed with it.

    /**
     * Knife-edge amplitude for a path-length detour of {@code delta} blocks at {@code frequencyHz}.
     *
     * <p>The Fresnel number n = sqrt(2 * dL / lambda) counts the half-wavelengths the detour costs, and the
     * standard knife-edge result is 0.5 at the shadow boundary (n = 0) rising towards 1 as the detour
     * clears. Without the wavelength term every frequency diffracted alike, which is wrong in the one way
     * that matters: a low frequency bends around an obstacle that stops a high one outright.
     */
    private static float edgeAmplitude(final float delta, final float frequencyHz, final float spread) {
        final double lambda = SPEED_OF_SOUND / Math.max(1F, frequencyHz);
        // ITU-R P.526 knife-edge diffraction loss.
        //
        // v is the Fresnel parameter of the DETOUR: v = 2*sqrt(dL/lambda), i.e. sqrt(2) times the n the
        // previous form used (n = sqrt(2*dL/lambda)).
        //
        //   J(v) = 6.9 + 20*log10( sqrt((v-0.1)^2 + 1) + v - 0.1 )      dB
        //   A    = 10^(-J/20)
        //
        // At v = 0 this gives J = 6.03 dB, so A = 0.5 - the same -6 dB shadow boundary the model has
        // always used. The difference from the previous form is the DIRECTION: a larger detour means the
        // receiver is deeper in the shadow, so the loss must GROW, asymptoting at about 6 dB per unit of
        // v. The previous form (0.5 + 0.5*tanh(n)) did the opposite - it rose towards 1 as the detour
        // grew, so going farther around an obstacle made the sound LOUDER.
        //
        // That inversion is what made a distant opening nearly free: a detour of 13 blocks returned -4.3 dB
        // when the correct figure is -35.8 dB.
        final double v = 2.0D * Math.sqrt(Math.max(0.0D, delta / lambda));
        final double shifted = v - 0.1D;
        final double j = 6.9D + 20.0D * Math.log10(Math.sqrt(shifted * shifted + 1.0D) + shifted);
        final float loss = (float) Math.pow(10.0D, -Math.max(0.0D, j) / 20.0D);
        // Scaled by how much the bent path spreads. A wave that goes around an edge has travelled further
        // than one that goes straight, and its energy is spread over the larger wavefront: for AMPLITUDE
        // that is the ratio of the straight distance to the bent path length (1/r spreading).
        return loss * MathStuff.clamp1(spread);
    }

    /**
     * Fraction of the diffracted wave that arrives, per octave band, from the CLEARANCE HEIGHT.
     *
     * <p>The clearance h is how far the nearest opening around the first obstacle stands from the direct line.
     * It is found by walking out perpendicular to the line from the anchor and taking the nearest offset that is
     * not solid and that can see BOTH the source and the listener - no ray is ever cast from inside the
     * obstacle, which is what broke the previous two attempts.
     *
     * <p>The Fresnel-Kirchhoff attenuation follows from the detour the wave actually travels to get through
     * that opening, not from the clearance height itself:
     * <pre>
     *   dL   = sqrt(d1^2 + h^2) + sqrt(d2^2 + h^2) - (d1 + d2)   the extra distance the bent path covers
     *   v    = 2 * sqrt(dL / lambda)                             the Fresnel parameter
     *   J(v) = 6.9 + 20*log10( sqrt((v-0.1)^2 + 1) + v - 0.1 )   dB, the ITU-R P.526 knife-edge loss
     *   A    = 10^(-J/20) * (d1+d2) / bentPath                   amplitude, including 1/r spreading
     * </pre>
     * with d1 and d2 the distances from the source and the listener to the obstacle.
     *
     * <p>A barrier wider than the search reaches is NOT a hard zero. It is evaluated at the search's own
     * reach, which under the loss above is a large attenuation on its own - a 13-block detour comes out
     * around -36 dB, below the material term's transmission, so the power sum is carried by the material
     * and the wall stays opaque. Returning null (a hard zero) instead made the edge term a BINARY switch:
     * see the note at that branch for why that was audible as the muffling flipping on and off.
     *
     * @return array of amplitudes per band, or null when the anchor is missing.
     */
    private float[] edgeAmplitudePerBand(final WorldContext ctx, final Vec3 source, final Vec3 listener) {
        this.lastEdgeFound = false;
        this.lastEdgeDelta = 0F;
        java.util.Arrays.fill(this.lastEdgeDb, 0F);
        if (this.lastOccluderPos == null)
            return null;

        final Vec3 direct = listener.subtract(source);
        final double directLen = direct.length();
        if (directLen < 0.01D)
            return null;
        final Vec3 d = direct.scale(1.0D / directLen);
        final Vec3 seed = Math.abs(d.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 u = d.cross(seed).normalize();
        final Vec3 v = d.cross(u).normalize();
        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        // The source end of the leg, stepped out of its own block so a block source is not its own obstacle.
        final Vec3 sourceLeg = stepOutOfSolid(ctx.world, source, listener);

        // The nearest perpendicular offset that is open AND sees both ends is the opening the wave escapes
        // through. This is an approximation of the obstacle's silhouette: a real edge search would walk the
        // outline of the blocker, which costs far more rays for a difference the ear cannot resolve.
        float clearance = -1F;
        Vec3 opening = null;
        float offset = 0F;
        for (int step = 1; step <= CLEARANCE_STEPS; step++) {
            // Geometric growth, so the search covers tens of blocks without paying for 0.75-block resolution
            // out where it no longer matters.
            offset = step == 1 ? CLEARANCE_STEP : offset * CLEARANCE_STEP_GROWTH;
            for (int k = 0; k < CLEARANCE_AZIMUTHS; k++) {
                final double phi = 2.0D * Math.PI * k / CLEARANCE_AZIMUTHS;
                final Vec3 point = this.lastOccluderPos
                        .add(u.scale(Math.cos(phi) * offset))
                        .add(v.scale(Math.sin(phi) * offset));
                // A point inside rock is not on the edge, and an offset only counts as the clearance when the
                // wave can actually get there AND on to the listener: validating the source leg alone accepted
                // offsets that see the source while still being walled off from the listener, which understated
                // the clearance and left a source fifteen blocks above a mine fully audible.
                if (isSolidBlock(ctx.world, point))
                    continue;
                if (!isMiss(traceContext.trace(sourceLeg, point)))
                    continue;
                if (!isMiss(traceContext.trace(point, listener)))
                    continue;
                clearance = offset;
                opening = point;
                break;
            }
            if (clearance > 0F)
                break;
        }
        // No opening within the search: the barrier is wider than the search reaches. That is NOT a hard
        // zero. The wave still diffracts, just from farther out, and the search's own reach is the honest
        // clearance to evaluate it at - under the knife-edge loss that yields a large attenuation by
        // itself, so the material term carries the power sum and the wall stays opaque.
        //
        // Returning null here made the edge term a BINARY SWITCH, and that was audible. Whenever the
        // material term is heavily attenuated - a thick barrier, which is exactly the case this branch
        // describes - the edge term dominates the power sum: an opening found at 0.75 blocks gave about
        // -3 dB, no opening gave the material term's -33 dB. Moving a few blocks moves the anchor
        // (the midpoint of the first solid segment the walk finds) enough to cross between the two, so
        // the muffling flipped on and off for loud, heavily occluded sources. Evaluating the far case
        // instead of zeroing it turns a 30 dB cliff into a continuous ramp.
        if (clearance < 0F) {
            clearance = offset;
            this.lastEdgePoint = null;
        } else {
            this.lastEdgePoint = opening;
        }

        // The detour the bent path costs over the straight line. The anchor lies on the line, so
        // d1 + d2 = directLen and the detour is the two hypotenuses minus that.
        final double d1 = Math.max(0.5D, source.distanceTo(this.lastOccluderPos));
        final double d2 = Math.max(0.5D, listener.distanceTo(this.lastOccluderPos));
        final double detour = Math.sqrt(d1 * d1 + (double) clearance * clearance)
                + Math.sqrt(d2 * d2 + (double) clearance * clearance) - (d1 + d2);
        this.lastEdgeDelta = clearance;
        // True only when an opening was actually found. When it is false the clearance above is the search
        // limit, which the probe reports in `clear=` - so `edge=false` together with `clear=13.44` reads as
        // "no opening within reach", and the edge term is carrying the far-detour attenuation instead of
        // nothing at all.
        this.lastEdgeFound = opening != null;

        // How much the bent path spreads relative to the straight line. The wave travels d1 + h and
        // h + d2 instead of the direct line, so its amplitude is diluted by the ratio of the two path
        // lengths. This is the distance/aperture dependence the edge term used to be missing.
        final double bentPath = Math.sqrt(d1 * d1 + (double) clearance * clearance)
                + Math.sqrt(d2 * d2 + (double) clearance * clearance);
        final float spread = (float) (directLen / Math.max(1.0E-6D, bentPath));

        final int bands = bandCount();
        final float[] amplitudes = new float[bands];
        for (int band = 0; band < bands; band++) {
            amplitudes[band] = edgeAmplitude((float) Math.max(MIN_EDGE_DETOUR, detour), bandFrequency(band), spread);
            if (band < this.lastEdgeDb.length)
                this.lastEdgeDb[band] = (float) (-20.0D * Math.log10(Math.max(1.0E-6F, amplitudes[band])));
        }
        return amplitudes;
    }

    /**
     * Mean free path of the space the LISTENER is standing in, measured exactly the way
     * {@link #traceReverb} measures the source's space: a ray fan from the ear, bounced
     * {@link #REVERB_RAY_BOUNCES} times, with escaping rays contributing zero.
     *
     * <p>This is the quantity the architecture has been missing. The reverb sends describe the field
     * around the SOURCE; nothing in the system has ever measured the room the listener is actually in,
     * which is why "walk into a cave and everything becomes reverberant" cannot be expressed, and why
     * every attempt to make reverb follow the listener ended up as a gate on the source-to-listener
     * LINE instead - which is unstable, because that line changes when you take two steps.
     *
     * <p>Not {@code sendGain *= enclosure}: that double-counts room size, because
     * {@link #finalizeSendGains} already scales sends 1 and 2 by a mean free path. When listener and
     * source share a space the two are the same number, so a small room would be scaled twice. The form
     * actually used is a BLEND - one room-size scale, fed by whichever space the sound is arriving
     * through, weighted by how connected the two spaces are. See the call site in {@code calculate}.
     *
     * <p>Costs one full ray fan, so the result is cached by ear position - see
     * {@link #cachedListenerMeanFreePath}. The probe still reports it as {@code lmfp=}, which is how the
     * decision to wire it was made: the measured spread is 0.09 to 25.46 across a hut, a hall, a cave
     * and open ground.
     */
    private static float listenerMeanFreePath(final WorldContext ctx) {
        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        double pathSum = 0D;
        for (int i = 0; i < REVERB_RAYS; i++) {
            Vec3 origin = ctx.playerEyePosition;
            Vec3 target = origin.add(REVERB_RAY_PROJECTED[i]);
            var rayHit = traceContext.trace(origin, target);
            if (isMiss(rayHit))
                continue;
            Vec3 lastHitPos = rayHit.getLocation();
            Vec3 lastHitNormal = surfaceNormal(rayHit.getDirection());
            Vec3 lastRayDir = REVERB_RAY_NORMALS[i];
            for (int j = 0; j < REVERB_RAY_BOUNCES; j++) {
                final Vec3 newRayDir = MathStuff.reflection(lastRayDir, lastHitNormal);
                origin = MathStuff.addScaled(lastHitPos, newRayDir, 0.01F);
                target = MathStuff.addScaled(origin, newRayDir, MAX_REVERB_DISTANCE);
                rayHit = traceContext.trace(origin, target);
                if (isMiss(rayHit))
                    break;
                pathSum += lastHitPos.distanceTo(rayHit.getLocation());
                lastHitPos = rayHit.getLocation();
                lastHitNormal = surfaceNormal(rayHit.getDirection());
                lastRayDir = newRayDir;
            }
        }
        return (float) (pathSum / (double) (REVERB_RAYS * REVERB_RAY_BOUNCES));
    }

    /**
     * A cached listener-space measurement. Immutable and published through a volatile field, so two
     * worker threads racing here can only cost one redundant ray fan - neither can observe a
     * half-built result.
     */
    private static final class ListenerSpace {
        final Vec3 earPosition;
        final float meanFreePath;
        final long stampNanos;

        ListenerSpace(final Vec3 earPosition, final float meanFreePath, final long stampNanos) {
            this.earPosition = earPosition;
            this.meanFreePath = meanFreePath;
            this.stampNanos = stampNanos;
        }
    }

    /** Latest listener-space measurement. See {@link #cachedListenerMeanFreePath}. */
    private static volatile ListenerSpace listenerSpace;

    /**
     * How far the ear may move before the listener's space is measured again, in blocks.
     *
     * <p>A room's mean free path does not meaningfully change within a block - it is a whole-space
     * average, not a local one - and the value it feeds is time-smoothed on the way to the sends, so
     * a step of this size is absorbed rather than heard. Measured: widening this from 0.5 to 1.0 and
     * the TTL below from 250 ms to 500 ms roughly halves how often the fan is cast.
     */
    private static final float LISTENER_SPACE_MOVE_BLOCKS = 1.0F;

    /**
     * How long a listener-space measurement stays valid, in nanoseconds. Half a second.
     *
     * <p>At the measured evaluation rate (about 2.8 a second) a shorter TTL would let nearly every
     * evaluation re-cast the same fan for the same room; this is what makes the cache actually bite.
     */
    private static final long LISTENER_SPACE_TTL_NANOS = 500_000_000L;

    /**
     * {@link #listenerMeanFreePath} with a cache.
     *
     * <p>The measurement costs a full ray fan and depends on NOTHING except where the ear is, so without
     * this every source in a pass would cast the same fan and get the same number - the cost would scale
     * with how many sounds are playing instead of with how fast the player is moving. Caching by ear
     * position is what makes wiring it into the sends affordable: walking triggers a handful of fans a
     * second no matter how much is playing, and standing still triggers none.
     *
     * <p>The TTL is a backstop rather than the primary term: standing still in a space that is being
     * dug out would otherwise keep the old number forever.
     */
    private static float cachedListenerMeanFreePath(final WorldContext ctx) {
        final ListenerSpace cached = listenerSpace;
        final long now = System.nanoTime();
        if (cached != null
                && cached.earPosition.distanceTo(ctx.playerEyePosition) < LISTENER_SPACE_MOVE_BLOCKS
                && now - cached.stampNanos < LISTENER_SPACE_TTL_NANOS)
            return cached.meanFreePath;
        final float meanFreePath = listenerMeanFreePath(ctx);
        listenerSpace = new ListenerSpace(ctx.playerEyePosition, meanFreePath, now);
        return meanFreePath;
    }

    private static float calculateWeatherAbsorption(final WorldContext ctx, final Vec3 pt1, final Vec3 pt2) {
        assert ctx.world != null;

        if (!ctx.isPrecipitating)
            return 1F;

        final BlockPos low = BlockPos.containing(pt1);
        // The midpoint of the path, not pt1 + 0.5*pt2: see MathStuff.addScaled - its second argument is a
        // delta. As written this sampled a point far from the source/listener pair, so the "is it raining
        // along the path" test was really testing somewhere else.
        final BlockPos mid = BlockPos.containing(pt1.lerp(pt2, 0.5D));
        final BlockPos high = BlockPos.containing(pt2);

        // Determine the precipitation type at each point
        final Biome.Precipitation rt1 = SEASONAL_INFORMATION.getActivePrecipitation(low);
        final Biome.Precipitation rt2 = SEASONAL_INFORMATION.getActivePrecipitation(mid);
        final Biome.Precipitation rt3 = SEASONAL_INFORMATION.getActivePrecipitation(high);

        // Calculate the impact of weather on dampening
        float factor = calcFactor(rt1, 0.25F);
        factor += calcFactor(rt2, 0.5F);
        factor += calcFactor(rt3, 0.25F);
        factor *= ctx.precipitationStrength;

        return factor;
    }

    /**
     * Measures the total length of the sound-to-listener path that is submerged in water.
     * The whole path is sampled at half-block intervals every refresh, so any water
     * anywhere along the path (source half-submerged, a narrow river the sound crosses, a
     * player wading or diving) is always caught. The cost is a handful of fluid queries
     * per sound per second, negligible next to the reverb ray tracing.
     */
    private static float calculateWaterPathLength(final WorldContext ctx, final Vec3 origin, final Vec3 target) {
        if (!CONFIG.enableWaterSoundDamping)
            return 0F;

        assert ctx.world != null;
        final Vec3 delta = target.subtract(origin);
        final double distance = delta.length();
        if (distance <= 0.01)
            return 0F;

        final Vec3 unit = delta.scale(1.0 / distance);
        final float maxTrace = (float) Math.min(distance, MAX_WATER_SAMPLE_DISTANCE);
        float waterLength = 0F;
        for (float t = 0F; t < maxTrace; t += WATER_SAMPLE_STEP) {
            if (isWater(ctx.world, BlockPos.containing(MathStuff.addScaled(origin, unit, t))))
                waterLength += WATER_SAMPLE_STEP;
        }

        return waterLength;
    }

    private static boolean isWater(final Level world, final BlockPos pos) {
        return world.getFluidState(pos).is(FluidTags.WATER);
    }

    private static float getReflectivity(BlockState state) {
        // Use the weak form because the BlockInfo may not be filled out when
        // the FX system needs to evaluate. The info object should only
        // be filled out by the render thread.
        return BLOCK_LIBRARY.getBlockInfoWeak(state).getSoundReflectivity();
    }

    private static float getOcclusion(BlockState state) {
        // Air does not occlude sound. The weak BlockInfo for air falls back to the
        // library DEFAULT (occlusion 0.5), which would otherwise count every block of
        // open air between source and listener as a solid obstacle - hugely inflating
        // the centre-ray occlusion and killing the diffraction compensation.
        if (state.isAir())
            return 0F;
        // Use the weak form because the BlockInfo may not be filled out when
        // the FX system needs to evaluate. The info object should only
        // be filled out by the render thread.
        return BLOCK_LIBRARY.getBlockInfoWeak(state).getSoundOcclusion();
    }

    private static Vec3 surfaceNormal(final Direction d) {
        return SURFACE_DIRECTION_NORMALS[d.ordinal()];
    }

    private static boolean isSolidBlock(final Level world, final Vec3 pos) {
        final BlockState state = world.getBlockState(BlockPos.containing(pos));
        return state.isSolid() && state.getFluidState().isEmpty();
    }

    /**
     * Steps a point out of whatever solid block it sits in, toward {@code towards}, so a
     * ray started there does not immediately hit the block it started inside. A source
     * that is itself a solid block (a jukebox) would otherwise self-occlude every probe.
     */
    private static Vec3 stepOutOfSolid(final Level world, final Vec3 from, final Vec3 towards) {
        final Vec3 dir = from.vectorTo(towards).normalize();
        Vec3 pos = from;
        for (int i = 0; i < 8 && isSolidBlock(world, pos); i++)
            pos = pos.add(dir.scale(0.5D));
        return pos;
    }

    private static Vec3 offsetPositionIfSolid(final Level world, final Vec3 origin, final Vec3 target) {
        // Restored to the original Fabric implementation: any non-air block (including
        // fluids) offsets the source 0.876 blocks toward the player. This keeps occlusion
        // rays starting just outside the surface rather than from inside the block.
        if (world.getBlockState(BlockPos.containing(origin)) != Blocks.AIR.defaultBlockState()) {
            var normal = origin.vectorTo(target).normalize();
            return MathStuff.addScaled(origin, normal, 0.876F);
        }
        return origin;
    }

    private static float calcFactor(final Biome.Precipitation type, final float base) {
        return type == Biome.Precipitation.NONE ? base : base * (type == Biome.Precipitation.SNOW ? Effects.SNOW_AIR_ABSORPTION_FACTOR : Effects.RAIN_AIR_ABSORPTION_FACTOR);
    }

    /**
     * Is this point in open air, i.e. does it have a clear column to the sky?
     *
     * <p>Used to weight the send cutoffs: a bounce in the open lets the tail stay bright, one under a ceiling
     * does not. This is the ORIGINAL sharedAirspace quantity - a straight line from the reflection to the ear -
     * re-expressed as a column test, because the straight line read zero in every outdoor scene (see the note
     * in traceReverb) and would have taken the cutoffs with it.
     */
    private static boolean openAir(final WorldContext ctx, final Vec3 pos) {
        return ctx.world.getBrightness(LightLayer.SKY, BlockPos.containing(pos)) >= MAX_SKY_LIGHT;
    }

    private static boolean isMiss(@Nullable final BlockHitResult result) {
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    private static boolean skipOcclusion(SoundSource category) {
        return !CONFIG.enableOcclusionProcessing
                || category == SoundSource.MASTER
                || category == SoundSource.MUSIC;
    }

}
