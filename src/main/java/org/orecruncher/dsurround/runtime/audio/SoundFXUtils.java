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
     * <p>Calibration: one block of stone has occlusion 0.5, so one block costs 2 dB and a four-block wall
     * about 8 dB. That is the range a real partition occupies, and it is the range the ear reads as "there is
     * something in the way" rather than as silence.
     *
     * <p>This replaced an exponential transmission model, {@code exp(-centerMaterial * 3)}, which applied the
     * loss as an AMPLITUDE rather than in dB: 15 blocks of rock gave {@code exp(-22.5)} = 1e-10, i.e. -200 dB.
     * Real transmission loss grows linearly in dB with thickness (the mass law), so the exponential form
     * over-attenuated thick barriers by roughly 90 dB and pinned them at the 1e-6 amplitude clamp - measured
     * as {@code lossdb=120} (the ceiling) in 26 rows of one session, which is silence, not muffling.
     */
    private static final float MATERIAL_LOSS_DB_PER_UNIT = 4.0F;
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
    /** Skylight at a position that can see the sky in full. */
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
     * How much of the reverb sends survives a fully occluded path.
     *
     * <p>ZERO, and that is a correction rather than a tuning choice. This was 0.35, introduced when the
     * sends first became occluded at all, with the reasoning that "a sealed room still has a tail, it is
     * simply the tail of the muffled sound". The reasoning is right about the room and wrong about the
     * listener: that tail belongs INSIDE the room. A listener standing outside it hears the tail only
     * after it has crossed the same wall the direct sound crossed, so it must take the same loss.
     *
     * <p>At 0.35 the wet path kept -9 dB while the direct path through the same wall lost 6-20 dB, so
     * walking outside a village hut RAISED the wet-to-direct ratio - measured 1.35x through two blocks of
     * wood, 1.64x through a wall with framing, 4.15x through a wall and a hill (tools/sim_wet_ratio.py).
     * The reverb therefore grew relatively louder the more the sound was blocked, which the ear reports
     * as "the villager inside is reverberant".
     *
     * <p>At 0 the wet path inherits the direct path's transmission exactly, so the ratio stays at 1.0
     * however the source is occluded. A cave is unaffected: with the listener inside it there is almost
     * no occlusion to inherit (measured loss 0.5-1 dB, giving 0.94-0.96 against 0.96-0.93 at 0.35), so
     * the long tail the ear reads as "a cave" is preserved.
     */
    private static final float SEND_OCCLUSION_FLOOR = 0.0F;
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
     * Farthest reflection of any bounce, in blocks, measured from the source.
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
        float earlyReflection;
        /** Brightness to impose on the early-reflection zones; see the comment in traceReverb. */
        float earlyReflectionCutoff;
        /**
         * Lower bound for the DIRECT path's brightness, from the share of reflections that come back through
         * the listener's own airspace. Zero in the open; in an enclosed space it stops the direct sound from
         * being darkened by the very rock the reflections travelled around.
         */
        float directCutoffFloor;
        /**
         * Raw count of bounces whose reflection point sits in the ear's airspace, scaled by the ray budget and
         * by 64 (see {@code openAirspace} in traceReverb). A SPACE-CONNECTIVITY measure: it says how much of
         * the reflecting surface around the source can reach the listener at all, rather than whether the
         * straight line between them is clear. It already drives the send cutoffs; the send GAINS use it too
         * now, so a reverberant field still arrives through an opening the direct path cannot use.
         */
        float openAirspace;
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

        // Fabric original value: GLOBAL_BLOCK_ABSORPTION * 3.0. Temporarily raised to 4.0
        // to make a single wool wall more obvious, but the user asked for the original back.
        final float absorptionCoeff = Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F;
        final float airAbsorptionFactor = calculateWeatherAbsorption(ctx, soundPos, ctx.playerEyePosition);
        // Real ray-traced occlusion, time-smoothed so a geometric boundary (a ray starting
        // to clip the ground a few blocks away) fades instead of snapping the muffling.
        final float occlusionAccumulation = this.source.smoothOcclusion(
                calculateOcclusion(ctx, soundPos, ctx.playerEyePosition), snap);
        final float sendCoeff = -occlusionAccumulation * absorptionCoeff;

        // Broadband restore value: drives the level through pow(x, 0.1) and, unless diffraction
        // damps it, the high-frequency gain as well.
        float directCutoff = (float) MathStuff.exp(sendCoeff);
        // High-frequency restore value. Equal to directCutoff unless diffraction applies with
        // diffractionHfDamping > 1, which damps the restored highs without touching the level.
        float directHfCutoff = directCutoff;

        // Handle any dampening effects from the player, like head in water
        directCutoff *= 1F - ctx.auralDampening;

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

        finalizeSendGains(reverb, this.lastReverbMeanFreePath);

        // The occlusion has to reach the reverb tail, not just the direct filter. Behind a wall the
        // energy that would have fed the room is blocked too, so the tail must get quieter as well
        // as darker. Only the DARKENING was reaching it before (the send cutoffs were lifted, never
        // lowered, and sendGain never saw the occlusion at all), which left a heavily muffled direct
        // sound sitting under a bright, undiminished reverb - heard as "occlusion does not work".
        // The sends are the reverb AMOUNT, accumulated from the SOURCE's rays, and they are gated by how much
        // of the reflecting surface can reach the listener at all - NOT by whether the straight line from the
        // source happens to be clear. Gating by the line alone conflated "can I hear the direct sound" with
        // "is there a reverberant field here": in a mine with a one-block hole between two levels, standing
        // next to the hole made the line clear and the reverb loud, while standing away from it drove the
        // whole tail to zero - even though the source's own cavity is fully excited and the field leaks
        // through the hole regardless.
        //
        // max() of the two is deliberate: strictly additive, so this can only ADD reverb where the space is
        // connected but the line is blocked. A sealed room with the listener outside still reads ~0 on both
        // terms, so the village-hut fix the floor was set to 0 for survives.
        final float spaceShare = MathStuff.clamp1(reverb.openAirspace / 20.0F);
        final float sendOcclusionGain = SEND_OCCLUSION_FLOOR
                + (1F - SEND_OCCLUSION_FLOOR) * Math.max(MathStuff.clamp1(directHfCutoff), spaceShare);
        reverb.sendGain0 *= sendOcclusionGain;
        reverb.sendGain1 *= sendOcclusionGain;
        reverb.sendGain2 *= sendOcclusionGain;
        reverb.sendGain3 *= sendOcclusionGain;

        if (ctx.player.isUnderWater()) {
            reverb.sendCutoff0 *= 0.4F;
            reverb.sendCutoff1 *= 0.4F;
            reverb.sendCutoff2 *= 0.4F;
            reverb.sendCutoff3 *= 0.4F;
        }

        // Damping when the path between the sound and the listener passes through water.
        // Water strongly absorbs high frequencies and reduces the perceived volume, so a
        // sound heard across a body of water (e.g. underwater -> shore, or the reverse)
        // should sound muffled and quieter. The volume uses the square root of the low-pass
        // factor (plus a floor) so a shallow crossing is clearly audible while a long
        // underwater path never goes fully silent - just heavily muffled. Stacks with the
        // player-underwater damping above, which mirrors the real double damping
        // (propagation path + ear submerged).
        final Vec3 rawSourcePos = this.source.getPosition();
        final float waterLength = calculateWaterPathLength(ctx, rawSourcePos, ctx.playerEyePosition);
        // Submerged SHARE of the direct path, kept for the reflection-tap delay, which is computed in a
        // different method. Water carries sound ~4.3x faster than air, so a reflection that travels
        // underwater must arrive sooner; see the tap site.
        final float straightDistance = (float) rawSourcePos.distanceTo(ctx.playerEyePosition);
        this.lastSubmergedShare = straightDistance > 0.01F
                ? MathStuff.clamp1(waterLength / straightDistance)
                : 0F;
        // The low-pass (muffling) and the volume use separate per-block factors. Muffling
        // is kept strong so underwater sound is clearly muffled in every direction and
        // masks the reverb system's cut-off jitter in deep water; the volume uses a gentler
        // curve (with a floor) so distant sounds stay audible instead of vanishing.
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
                            + "level=%.4f send=%.3f space=%.3f material=%.3f lossdb=%.1f edge=%b edgedb=%.1f/%.1f/%.1f "
                            + "clear=%.2f walk=%d/%.1fm "
                            + "rv=%.1fm/%.2f/%.2f/%.2f far=%.0fm mfp=%d ret=%d face=%.3f erf=%.4f "
                            + "g=%.3f,%.3f,%.3f,%.3f "
                            + "rays=%d cost=%.0fus",
                    this.source.getCategory(), skipOcclusion(this.source.getCategory()), occlusionAccumulation,
                    MathStuff.exp(sendCoeff), directCutoff, directHfCutoff, directGain,
                    sendOcclusionGain, spaceShare, this.lastMaterialSum, this.lastZoneLossDb,
                    this.lastEdgeFound,
                    this.lastEdgeDb[0], this.lastEdgeDb[1], this.lastEdgeDb[2], this.lastEdgeDelta,
                    this.lastWalkSegments, this.lastWalkDistance,
                    this.lastReverbFirstDistance, this.lastReverbHitFraction,
                    this.lastReverbReflectivity, this.lastReverbFarthest,
                    this.lastReverbMeanFreePath, this.lastReverbShared, this.lastReturnedBounces, this.lastFacingShare,
                    this.lastEarlyReflectionGain,
                    reverb.sendGain0, reverb.sendGain1, reverb.sendGain2, reverb.sendGain3,
                    ReusableRaycastContext.raycastCount(),
                    (System.nanoTime() - evaluationStart) / 1000.0D));
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
        // Bounces in open air, i.e. with clear sky above. Feeds the send-cutoff weights, which is what it
        // has always fed; it is a different quantity from the one above and must stay separate.
        float openBounces = 0F;
        // Whether any bounce point of this ray so far has a clear straight line to the ear. See the
        // two-test note at the openBounces increment: the line test is the one that works INDOORS.
        boolean earVisible = false;
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
                    // when the reflection point is in the same airspace as the ear. TWO tests, and both are
                    // needed because each one is blind exactly where the other works:
                    //
                    //   * a clear straight line to the ear. This is the original mod's test and it is the one
                    //     that works INDOORS: a cave's walls are a few blocks away, so their reflection points
                    //     can see the listener. It reads zero outdoors, because the ear sits ~1.6 blocks above
                    //     the ground and a grazing line is blocked by any terrain rise.
                    //   * open sky above. This works OUTDOORS, where the line test fails, and reads zero
                    //     underground.
                    //
                    // Replacing the line test with the sky test alone - which an earlier revision did, to fix
                    // the outdoor case - silently removed the indoor case with it, and a cave lost its long
                    // bright tail because sendCutoff collapsed to exp(sendCoeff) there.
                    // `openBounces` is a COUNT, so this only has to be established once per RAY: if the
                    // ear was visible from an earlier bounce point of this same ray, the count is already
                    // decided and no later bounce can change it. Re-casting it on every bounce was 96 wasted
                    // raycasts per evaluation on defaults - about a third of the budget - for no effect on
                    // the result.
                    if (!earVisible) {
                        final Vec3 bounceAirStart = MathStuff.addScaled(lastHitPos, lastHitNormal, 0.01F);
                        if (isMiss(traceContext.trace(bounceAirStart, ctx.playerEyePosition)))
                            earVisible = true;
                    }
                    if (earVisible || openAir(ctx, lastHitPos)) {
                        openBounces += 1.0F;
                    }
                    facingSum += returned;

                    // Farthest reflection of any bounce, from the source. This is where a valley's far wall
                    // shows up; the first bounce is the ground under the listener in every scene.
                    farthest = Math.max(farthest, (float) soundPos.distanceTo(lastHitPos));
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
        final float returnedShare = returnedBounces * RECIP_TOTAL_RAYS;
        final float openAirspace = openBounces * RECIP_TOTAL_RAYS * 64F;
        out.openAirspace = openAirspace;

        // Observation point. `ret` is the raw count of returning bounces, so it reads as "of 128 bounces,
        // how many came back off a surface that faced the source" - a valley scores far above a plain.
        this.lastReverbFirstDistance = firstHits > 0 ? firstDistanceSum / firstHits : 0F;
        this.lastReverbReflectivity = firstHits > 0 ? firstReflectivitySum / firstHits : 0F;
        this.lastReverbHitFraction = firstHits / (float) REVERB_RAYS;
        this.lastReverbShared = (int) openBounces;
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

        final float sharedAirspaceWeight0 = MathStuff.clamp1(openAirspace / 20.0F);
        final float sharedAirspaceWeight1 = MathStuff.clamp1(openAirspace / 15.0F);
        final float sharedAirspaceWeight2 = MathStuff.clamp1(openAirspace / 10.0F);
        final float sharedAirspaceWeight3 = MathStuff.clamp1(openAirspace / 10.0F);

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
        // the rays, so it is divided by the number of rays. Earlier versions divided by rays x bounces (128,
        // far larger than the number of terms because most rays escape to the sky) and then by a "reference"
        // rays x 8, which starved the term 8x and left every scene at 0.00-0.01 - the reason a valley was
        // silent. There is no threshold and no proxy: the number IS the geometry.
        final float facingShare = facingSum / (float) REVERB_RAYS;
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
            Effects.reflectionTapGain = reflectionTap;
            Effects.reflectionTapDelay = reflectionTapDelay;
        }
        out.earlyReflection = reflectionTap;
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
        final float reflectionScale = Math.min(1.0F, this.lastReverbMeanFreePath / ECHO_SCALE_REFERENCE_M);
        out.earlyReflectionCutoff = MathStuff.clamp1(
                (0.35F + 0.65F * facingShare * 4.0F) * reflectionScale);
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
     *   <li><b>material</b> - what the block material along the path transmits. When the search has found an
     *       opening, the path measured is the BENT one through it (source -> opening -> listener); otherwise it
     *       is the straight line. This is the direct path, and it is what makes a wall's THICKNESS matter: one
     *       block of stone (occlusion 0.5) transmits about 0.83, a four-block wall about 0.22.</li>
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
     * <p>Measuring the bent path rather than the straight line is what lets an enclosed space with an opening
     * sound open. It is also what removes the snap at the mouth of a cave: the straight line's rock content
     * goes from "all of it" to "none of it" within a block or two, while the bent path measures air the whole
     * way and changes gradually as the opening moves across the line.
     *
     * <p>Both terms are returned in dB and then divided by the absorption coefficient, because the caller
     * applies {@code exp(-occlusion * absorption)} to get the amplitude back. The two conversions therefore
     * cancel; they are kept because the probe reports dB, which is the readable form.
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

        final float absorption = Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F;
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
        float weightedDb = 0F;
        float weightTotal = 0F;
        for (int band = 0; band < bands; band++) {
            // The material term is per band: a wall is far less transparent to a high frequency than to a
            // low one (the mass law), so sharing one coefficient across the bands understated how dull a
            // wall sounds. See materialAbsorptionScale.
            final float bandLossDb = materialLossDb * materialAbsorptionScale(bandFrequency(band));
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
            final float db = (float) (-20.0D * Math.log10(Math.max(1.0E-6F, amplitude)));
            final float weight = bandWeight(band);
            weightedDb += weight * db;
            weightTotal += weight;
        }
        final float lossDb = weightTotal > 0F ? weightedDb / weightTotal : 0F;

        // The occlusion value drives the direct low-pass as exp(-occlusion * absorption), and the engine also
        // derives the direct LEVEL from it as pow(that, 0.1). Feeding it the full transmission loss applied
        // that loss TWICE - once as a filter, once as level - and drove directCutoff to ~1e-26 behind rock,
        // which is silence, not muffling. The filter gets the full loss (that is what muffling is); the level
        // gets a heavily damped share of it, which is what MATERIAL_LEVEL_RESTORE is for.
        final float levelLossDb = lossDb * MATERIAL_LEVEL_RESTORE;

        this.lastZoneLossDb = lossDb;
        return levelLossDb / Math.max(1.0E-6F, absorption);
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
    private static float passTapGain;

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
        if (index < 0 || index >= use.length)
            return 1F / use.length;
        return use[index];
    }

    /**
     * How much of the material absorption applies at {@code frequencyHz}, relative to
     * {@link #MATERIAL_REFERENCE_HZ}.
     *
     * <p>A wall's transmission falls with frequency - the mass law, where a partition's transmission loss
     * grows about 6 dB per octave above its coincidence frequency, so the amplitude exponent goes as
     * sqrt(f). Before this the material term used ONE coefficient for every band, which is the one place the
     * model was still frequency-blind: it made a wall equally transparent to a 2 kHz clink and a 125 Hz
     * rumble, when the rumble is precisely the part that gets through.
     *
     * <p>This is a power-law approximation, not a per-material absorption spectrum: the block library stores
     * one occlusion value per block, not one per octave band, and adding that would mean a data change across
     * 132 dsconfig files for a difference the ear reads as "duller behind a wall", not as a spectral shape.
     *
     * <p>With {@code realismWavelength} off there is a single band at {@link AudioTuning#realismFrequencyHz()},
     * and this returns exactly 1 for the default 500 Hz - so the frequency-blind behaviour is preserved.
     */
    private static float materialAbsorptionScale(final float frequencyHz) {
        final double ratio = Math.max(100.0D, frequencyHz) / MATERIAL_REFERENCE_HZ;
        return (float) Math.sqrt(ratio);
    }

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
        final double n = Math.sqrt(Math.max(0.0D, 2.0D * delta / lambda));
        final float loss = 0.5F + (float) (0.5D * Math.tanh(n));
        // STANDARD knife-edge amplitude: 0.5 (-6 dB) at the shadow boundary, rising to 1 in the lit
        // region. This used to be 1 - 0.75*(1 - loss), a fitted form that put the boundary at 0.625
        // (-4.1 dB) instead. The fitted constant had no physical basis and the standard value is the one
        // the reference acoustic model uses. Dropping it makes the edge term weaker, which is intended.
        //
        // The result is then scaled by how much the bent path spreads. Without that term the amplitude
        // saturated almost immediately - measured, even a 5 cm detour returned 70-87% of the energy and a
        // 1 m detour returned 89-100% - and it did not depend on distance or on the size of the opening
        // AT ALL, so a single small opening anywhere within the search range let most of the sound
        // through. That is the model behind "a sound fifteen blocks above a mine is still fully audible".
        // A wave that goes around an edge has travelled further than one that goes straight, and its
        // energy is spread over the larger wavefront: for AMPLITUDE that is the ratio of the straight
        // distance to the bent path length.
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
     *   n    = sqrt(2 * dL / lambda)                             the Fresnel parameter
     *   loss = 0.5 + 0.5 * tanh(n)                               0.5 at grazing, 1 once clear
     *   A    = 1 - 0.75 * (1 - loss)                             0.625 at grazing, 1 when clear
     * </pre>
     * with d1 and d2 the distances from the source and the listener to the obstacle.
     *
     * <p>Using the detour rather than h is what makes the term vanish for a barrier the wave cannot get around.
     * The previous form fed h straight into n, so a wide wall - where no opening is found at all and h was
     * clamped to the search limit - produced a LARGE n and therefore loss 1 and amplitude 1: a wide wall read as
     * fully transparent, and since the amplitudes add, the material term was masked by it. Measured over one
     * session that made the edge contribute exactly 0.0 dB in 207 of 207 rows, 197 of them the clamped case.
     * A wall wider than the search reaches now yields no edge contribution at all, which is the honest reading:
     * what gets through it gets through by transmission, and the material term already measures that.
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
        // No opening within the search: the barrier is wider than the search reaches. There is no edge path to
        // add, so the direct transmission through the material is all there is. Treating the search limit as a
        // real clearance instead would read the widest walls as the most transparent ones.
        if (clearance < 0F) {
            this.lastEdgePoint = null;
            return null;
        }
        this.lastEdgePoint = opening;

        // The detour the bent path costs over the straight line. The anchor lies on the line, so
        // d1 + d2 = directLen and the detour is the two hypotenuses minus that.
        final double d1 = Math.max(0.5D, source.distanceTo(this.lastOccluderPos));
        final double d2 = Math.max(0.5D, listener.distanceTo(this.lastOccluderPos));
        final double detour = Math.sqrt(d1 * d1 + (double) clearance * clearance)
                + Math.sqrt(d2 * d2 + (double) clearance * clearance) - (d1 + d2);
        this.lastEdgeDelta = clearance;
        this.lastEdgeFound = true;

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
