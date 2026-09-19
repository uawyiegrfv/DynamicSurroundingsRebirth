package org.orecruncher.dsurround.runtime.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
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
     * Rings of rays inside the 0.6-block occlusion cone. The fan is one centre ray plus four rays
     * per ring (5 rays = one ring = the original behaviour, 9 = two, 13 = three).
     * <p>
     * This is the knob behind "the volume jumps as I walk past a row of pillars": with a single
     * ring the averaged occlusion moves in four discrete steps as a blocking block crosses from one
     * ray to the next, whereas finer rings make the value slide.
     */
    private static int occlusionFanRings() {
        return AudioTuning.occlusionFanRings();
    }

    /**
     * Length of the source-enclosure / player-openness probe rays. Must reach the
     * walls of a normal room - a 4-block probe could not see the room's walls from a
     * source in its centre, so a closed room still read as open and the compensation
     * stayed active. 12 blocks covers ordinary building scale at a fraction of the
     * reverb trace cost.
     */
    private static final float PROBE_RAY_DISTANCE = 12F;
    /**
     * Base attenuation of a single diffraction edge: the fraction of the direct
     * signal left after the wave bends around one wall edge, before the detour-length
     * falloff below. A single knife-edge is roughly -3 to -6 dB, so ~0.5-0.9 fits.
     */
    private static final float EDGE_LOSS = 0.9F;
    /**
     * Falloff of diffraction with the detour length ΔL - the extra distance the wave
     * travels bending around the edge over the straight line. Larger = steeper; a
     * thin wall (ΔL ~2) keeps most of EDGE_LOSS, a long way around (ΔL ~10) keeps
     * little.
     */
    private static final float DIFFRACTION_FALLOFF = 0.12F;
    /**
     * Fraction of the diffraction compensation applied to the reverb sends vs the
     * direct signal. Diffracted energy arrives late and low-frequency, and the reverb
     * bus already carries reflected energy, so it is lifted less than the direct.
     */
    private static final float DIFFRACTION_REVERB_SCALE = 0.5F;
    /**
     * Auxiliary floor for the diffraction restore. A wide/thick obstacle has a
     * reachable edge but a long detour, so the geometric ΔL falloff drives the
     * diffraction toward 0 and the sound behind it to near-silence. As long as an
     * edge IS reachable (diffraction > 0) and neither end is sealed (the openness/
     * enclosure gates below), this floor keeps a faint-but-audible low-frequency
     * restore instead of letting a large-but-finite obstacle mute the sound entirely.
     * Thin walls (small ΔL) already exceed this value, so their behaviour is unchanged.
     */
    private static final float DIFFRACTION_FLOOR = 0.3F;
    /**
     * Radii (blocks) of the detour rings probed around the occluder, smallest first.
     * A thin wall clears at the smallest radius; a wide wall needs a larger ring to
     * reach past its edge. The probe stops at the first radius that finds a path.
     * Extended to 16 so a wall roughly 30 blocks wide still has a reachable edge;
     * beyond that the obstacle is treated as terrain and stays muffled.
     */
    private static final float[] DETOUR_RADII = {1.5F, 3F, 5F, 8F, 12F, 16F, 24F, 32F};

    /**
     * How strongly the obstacle's thickness suppresses the restored high frequencies. The detour
     * probe cannot see thickness (its rings sit in the plane perpendicular to the bearing, so their
     * offsets slide along a flat wall's face), but the direct-path occlusion DOES measure it - so the
     * restore is scaled by the direct path's transmission raised to this exponent. 0 = ignore
     * thickness, 1 = proportional to it; 0.25 keeps a useful spread without over-muffling.
     */
    private static final double DIFFRACTION_THICKNESS_EXPONENT = 0.25D;

    /**
     * How many of the detour rings are summed. Read live from {@link AudioTuning} so
     * {@code /dstune} can change it without a restart.
     */
    private static int diffractionRings() {
        return AudioTuning.diffractionRings();
    }

    /** Fraction of the lost level an edge detour may bring back. Read live. */
    private static float diffractionLevelStrength() {
        return AudioTuning.diffractionLevelStrength();
    }

    /** Fraction of the lost highs an edge detour may bring back. Read live. */
    private static float diffractionHfStrength() {
        return AudioTuning.diffractionHfStrength();
    }
    /**
     * Waypoints per detour ring. 8 samples the perpendicular directions (around the
     * sides, over the top, under) densely enough to find the shortest edge while
     * keeping the probe cost small.
     */
    private static final int DETOUR_SAMPLES = 8;

    /**
     * Samples per aperture plane. Each sample costs one trace to the listener, so a plane with 8
     * samples is the same order of cost as the single 16-point disc it replaces, and the plane count
     * multiplies that (the default of 2 planes therefore costs about what the fixed disc did).
     */
    private static final int APERTURE_SAMPLES = 8;
    /** Concentric rings the samples are spread over: an inner half-radius ring plus the zone edge. */
    private static final int APERTURE_RINGS = 2;
    /**
     * Speed of sound in air, m/s. A Minecraft block is 1 m, so this is used directly with
     * wavelength = c / f to get the Fresnel zone radius in blocks.
     */
    private static final float SPEED_OF_SOUND = 343F;
    /** Hard cap on the aperture radius in blocks, so a very low frequency cannot trace for ever. */
    private static final float MAX_APERTURE_RADIUS = 16F;
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
     * Weight of the cone's rim rays against the axis rays. The axis carries the sound, so the rim
     * counts for less; this is what keeps a lone pillar clipping one rim ray from swinging the result.
     */
    private static final float AXIS_WEIGHT_FLOOR = 0.5F;
    /**
     * Hard cap on the segments a single aperture trace may walk. The aperture is an area average, so a long
     * trace crossing dozens of blocks does not need exact resolution - the cap bounds the cost of one
     * evaluation no matter how far the sound is. Measured at 32 (12:40 session): 432 raycasts per evaluation
     * against the pre-change budget of 193, because every segment advance is another raycast. 12 keeps the
     * area average while removing most of that excess.
     */
    private static final int APERTURE_MAX_SEGMENTS = 12;
    /**
     * Gain of the wavelength-dependent diffraction loss. The knife-edge loss is 0..1 (0.5 at the
     * shadow boundary, rising as the detour clears); this scales how much of it is applied, so the
     * model stays tunable against the game's own reverb instead of being taken on faith.
     */
    private static final float DIFFRACTION_LOSS_SCALE = 0.75F;
    /**
     * Distance the listener-openness rays travel. Long enough to leave a normal room (a 5-block ray would
     * report every room as sealed), short enough that open terrain reads as open.
     */
    private static final float OPENNESS_PROBE_DISTANCE = 16F;
    /** Directions the silhouette walk tries around the line. Four covers the four quadrants of the plane. */
    private static final int SILHOUETTE_DIRECTIONS = 4;
    /** Steps the silhouette walk takes outward before giving up and calling the obstacle impassable. */
    private static final int SILHOUETTE_STEPS = 8;
    /** Step size in blocks. Small enough to land near the silhouette, large enough to stay cheap. */
    private static final float SILHOUETTE_STEP = 0.75F;
    /**
     * How much of the reverb sends survives a fully occluded path. Not 0: a sealed room still has a
     * tail (that is what reverb IS), it is simply the tail of the muffled sound. At 0.35 a fully
     * occluded source keeps about -9 dB of send against a clear one.
     */
    private static final float SEND_OCCLUSION_FLOOR = 0.35F;
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
     * Occlusion accumulation along the centre fan ray only (the direct
     * source-to-player line). Unlike the full-fan average, which clips incidental
     * terrain off to the side and climbs to 5-10 in the open world, this tracks how
     * much solid the straight path truly passes through (a thin wall ~0.8, a few
     * blocks of rock between the surface and a cave ~3+). Used to close the
     * compensation for genuinely thick path obstacles such as the ground over a cave.
     */
    private float lastCenterOcclusion;
    /**
     * Fraction of the aperture disc that carries sound from the source to the listener on the most
     * recent calculation. This is the around-the-corner energy: it is ~1 in the open (including past
     * a lone pillar, where the disc is nearly all clear) and ~0 behind a wall that spans the whole
     * disc. It is what keeps a single obstacle on the straight line from muffling the sound like a
     * sealed room does.
     */
    private float lastApertureLeak;
    /** Fresnel zone radius (blocks) used by the most recent aperture measurement, for diagnostics. */
    private float lastFresnelRadius;
    /** The fan's own average from the most recent measurement, before the aperture blend. */
    private float lastFanAverage;
    /** Excess attenuation in dB from the Fresnel-zone model on the most recent measurement. */
    private float lastZoneLossDb;
    /** Cost of the ring-based diffraction probe on the most recent measurement, microseconds. */
    private double lastDiffractionCostUs;
    /** Waypoints the edge search accepted on the most recent measurement, for diagnostics. */
    private int lastEdgeWaypoints;
    /** Whether an edge path was found at all on the most recent measurement. */
    private boolean lastEdgeFound;
    /**
     * Fraction of solid angle around the listener from which a ray travels far enough to count as open.
     * Outdoors this is 1; inside a room it is the share taken by the opening. It is what makes a room muffle
     * naturally instead of by a fixed floor: sound arriving from outside is heard through the opening, so the
     * open share of the ear's surroundings IS the direct-sound attenuation.
     */
    private float lastListenerOpenness = 1F;
    /** Attenuation in dB the listener's own openness implies on the most recent measurement. */
    private float lastOpennessLossDb;
    /** Which term produced the most recent occlusion: 0 = zone model, 1 = material sum, 2 = openness. */
    private int lastOcclusionSource;
    /** The raw zone coefficient and the raw material sum from the most recent measurement. */
    private float lastZoneCoefficient = -1F;
    private float lastMaterialSum;
    /** Per-band edge loss in dB from the most recent measurement, for diagnostics. */
    private final float[] lastEdgeDb = new float[8];
    /** The smallest detour the edge search found, for diagnostics. */
    private float lastEdgeDelta = -1F;

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

        // Diffraction compensation. A wall mutes the straight line to the player, but
        // sound bends around its edges - unless one end is genuinely sealed in. The
        // restore is driven by a single physical quantity, the detour length of the
        // shortest unobstructed path around the occluder, so it needs no per-scenario
        // fudge factors (see applyDiffraction). The compensation also lifts the reverb
        // sends (scaled down): they share the same straight-line failure mode, and a
        // hugged wall stayed muffled through the reverb path even after the direct was
        // restored.
        if (this.lastCenterOcclusion > 0F) {
            // Seed with the pre-diffraction cutoff: the original code produced
            // max(compensation, exp(sendCoeff)) for the high-frequency gain, so starting from 0
            // here would silently drop the occlusion term whenever the compensation is lower.
            final float[] hfOut = new float[]{directCutoff};
            directCutoff = applyDiffraction(ctx, soundPos, ctx.playerEyePosition, directCutoff, snap,
                    reverb, hfOut);
            directHfCutoff = hfOut[0];
        } else {
            // Direct line is clear (or occlusion is skipped): ease the compensation
            // back to zero so leaving a shadow fades rather than pops.
            this.source.smoothDiffraction(0F, snap);
        }

        float directGain = (float) MathStuff.pow(directCutoff, 0.1);

        finalizeSendGains(reverb);

        // The occlusion has to reach the reverb tail, not just the direct filter. Behind a wall the
        // energy that would have fed the room is blocked too, so the tail must get quieter as well
        // as darker. Only the DARKENING was reaching it before (the send cutoffs were lifted, never
        // lowered, and sendGain never saw the occlusion at all), which left a heavily muffled direct
        // sound sitting under a bright, undiminished reverb - heard as "occlusion does not work".
        final float sendOcclusionGain = SEND_OCCLUSION_FLOOR
                + (1F - SEND_OCCLUSION_FLOOR) * MathStuff.clamp1(directHfCutoff);
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
        AudioTuning.recordTrace(String.format(
                "cat=%s skipped=%b occlusion=%.3f cutoff(occl)=%.4f cutoff(final)=%.4f hf(final)=%.4f "
                        + "level=%.4f hfRestore=%.3f levelRestore=%.3f centerOccl=%.3f leak=%.3f send=%.3f "
                        + "fresnel=%.2f fan=%.3f zonedb=%.1f cost=%.0fus ring=%.0fus "
                        + "wp=%d edge=%b delta=%.2f edgedb=%.1f/%.1f/%.1f rays=%d open=%.2f openloss=%.1f src=%d zonec=%.3f matsum=%.3f",
                this.source.getCategory(), skipOcclusion(this.source.getCategory()), occlusionAccumulation,
                MathStuff.exp(sendCoeff), directCutoff, directHfCutoff, directGain,
                directHfCutoff <= 0F ? 0F : (directHfCutoff - MathStuff.exp(sendCoeff)) / Math.max(1e-6F, 1F - MathStuff.exp(sendCoeff)),
                directCutoff <= 0F ? 0F : (directCutoff - MathStuff.exp(sendCoeff)) / Math.max(1e-6F, 1F - MathStuff.exp(sendCoeff)),
                this.lastCenterOcclusion, this.lastApertureLeak, sendOcclusionGain,
                this.lastFresnelRadius, this.lastFanAverage, this.lastZoneLossDb,
                (System.nanoTime() - evaluationStart) / 1000.0D, this.lastDiffractionCostUs,
                this.lastEdgeWaypoints, this.lastEdgeFound, this.lastEdgeDelta,
                this.lastEdgeDb[0], this.lastEdgeDb[1], this.lastEdgeDb[2],
                ReusableRaycastContext.raycastCount(),
                this.lastListenerOpenness, this.lastOpennessLossDb,
                this.lastOcclusionSource, this.lastZoneCoefficient, this.lastMaterialSum));

        uploadSettings(reverb, directHfCutoff, directGain, waterFactor, waterGainFactor, airAbsorptionFactor);
    }

    /**
     * Projects the reverb rays from the sound position and accumulates the four zone
     * send gains/cutoffs plus the per-bounce reflection ratios.
     */
    private void traceReverb(final WorldContext ctx, final Vec3 soundPos, final float sendCoeff, final ReverbTrace out) {

        float sharedAirspace = 0F;

        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);

        for (int i = 0; i < REVERB_RAYS; i++) {

            Vec3 origin = soundPos;
            Vec3 target = origin.add(REVERB_RAY_PROJECTED[i]);

            var rayHit = traceContext.trace(origin, target);

            if (isMiss(rayHit))
                continue;

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
                    totalRayDistance += lastHitPos.distanceTo(rayHit.getLocation());

                    lastHitPos = rayHit.getLocation();
                    lastHitNormal = surfaceNormal(rayHit.getDirection());
                    lastRayDir = newRayDir;
                    lastHitBlock = rayHit.getBlockPos();

                    // Cast a ray back at the player.  If it is a miss there is a path back from the reflection
                    // point to the player meaning they share the same airspace.
                    final Vec3 finalRayStart = MathStuff.addScaled(lastHitPos, lastHitNormal, 0.01F);
                    var finalRayHit = traceContext.trace(finalRayStart, ctx.playerEyePosition);
                    if (isMiss(finalRayHit)) {
                        sharedAirspace += 1.0F;
                    }
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

        sharedAirspace *= RECIP_TOTAL_RAYS * 64F;

        final float sharedAirspaceWeight0 = MathStuff.clamp1(sharedAirspace / 20.0F);
        final float sharedAirspaceWeight1 = MathStuff.clamp1(sharedAirspace / 15.0F);
        final float sharedAirspaceWeight2 = MathStuff.clamp1(sharedAirspace / 10.0F);
        final float sharedAirspaceWeight3 = MathStuff.clamp1(sharedAirspace / 10.0F);

        final float exp1 = (float) MathStuff.exp(sendCoeff);
        final float exp2 = (float) MathStuff.exp(sendCoeff * 1.5F);
        out.sendCutoff0 = exp1 * (1.0F - sharedAirspaceWeight0) + sharedAirspaceWeight0;
        out.sendCutoff1 = exp1 * (1.0F - sharedAirspaceWeight1) + sharedAirspaceWeight1;
        out.sendCutoff2 = exp2 * (1.0F - sharedAirspaceWeight2) + sharedAirspaceWeight2;
        out.sendCutoff3 = exp2 * (1.0F - sharedAirspaceWeight3) + sharedAirspaceWeight3;
    }

    /**
     * Diffraction compensation. A wall mutes the straight line to the player, but
     * sound bends around its edges - unless one end is genuinely sealed in. The
     * restore is driven by a single physical quantity, the detour length of the
     * shortest unobstructed path around the occluder, so it needs no per-scenario
     * fudge factors:
     *   - a thin wall (or a hugged 1x2 wall) clears at a small ring radius -> small
     *     detour -> strong restore;
     *   - a wide wall needs a larger ring -> longer detour -> weaker restore;
     *   - a cave roof finds no edge at any radius -> no diffraction -> the base
     *     occlusion keeps it muffled;
     *   - a buried source (enclosure ~1) or a sealed-in player (openness ~0) has no
     *     free edge to bend around, so the restore is gated off.
     *
     * @return the updated direct cutoff; the reverb cutoffs are lifted in place.
     */
    private float applyDiffraction(final WorldContext ctx, final Vec3 soundPos, final Vec3 listener,
            float directCutoff, final boolean snap, final ReverbTrace reverb, final float[] hfOut) {
        // The ring probe's own cost, reported separately: it is the one component that could be retired
        // in exchange for more realism elsewhere (the aperture answers the same question with an area,
        // material weighting and wavelength).
        final long diffractionStart = System.nanoTime();
        final float geometricDiffraction = calculateDiffraction(ctx, soundPos, listener, this.lastOccluderPos);
        this.lastDiffractionCostUs = (System.nanoTime() - diffractionStart) / 1000.0D;
        // Aperture contribution (the genuinely Huygens form of the same idea). The ring probe above
        // can only see edges it happens to sample; the aperture disc measures how much of the
        // wavefront area between the two ends is actually clear, which is what decides whether a
        // lone pillar muffs the sound or not. Take the larger of the two: a thin wall is best
        // described by its edge (rings), a scattered obstacle by the clear area around it
        // (aperture). Both are still fractions of what the direct path lost.
        final float apertureTerm = this.lastApertureLeak * AudioTuning.apertureStrength();
        // Auxiliary factor: a wide obstacle still has a reachable edge, but the
        // long detour makes the geometric falloff inaudible. Floor it (only when an
        // edge was actually found) so a large-but-open obstacle stays faintly
        // audible; a buried source / sealed player is still muted by the gates
        // below. With no edge (diffraction 0) the floor must NOT lift it.
        final float diffraction = Math.max(
                Math.max(geometricDiffraction, apertureTerm),
                Math.max(geometricDiffraction, apertureTerm) > 0F ? DIFFRACTION_FLOOR : 0F);
        // Gate the ring probe's restore on true sealing only. A linear (1-enclosure) or raw openness
        // over-penalises normal partial occlusion - a source sitting next to a wall (enclosure ~0.5) or a
        // player hugging one (openness ~0.67) still has free edges to diffract around. Cubing keeps those
        // mid-values nearly un-penalised and only shuts the gate off as one end becomes genuinely sealed.
        // This applies to the RING probe only; the edge term is not gated, because the silhouette walk
        // already proves its paths are reachable.
        final float enclosure = calculateSourceEnclosure(ctx, soundPos);
        final float openness = calculatePlayerOpenness(ctx, listener);
        final float gate = MathStuff.clamp1(
                (1F - (float) Math.pow(enclosure, 3.0)) * (1F - (float) Math.pow(1.0 - openness, 3.0)));
        final float compensation = MathStuff.clamp1(diffraction * gate);
        // Time-smooth the restore so crossing a room boundary (openness and
        // enclosure both step at once) fades the muffling instead of snapping it.
        final float smoothedComp = this.source.smoothDiffraction(compensation, snap);
        // What the direct path's material left, as measured along the line: the parameter arrived
        // as exp(sendCoeff), i.e. the pre-diffraction cutoff.
        final float occlusionCutoff = directCutoff;

        // A detour brings back a FRACTION of what the direct path lost - never a fixed value and
        // never all of it. The original max(smoothedComp, occlusionCutoff) let a 1-block stone wall
        // restore the highs from -26 dB to -1.4 dB (the wall effectively vanished) and threw away
        // the only quantity that measures thickness.
        final float levelRestore = MathStuff.clamp1(smoothedComp * diffractionLevelStrength());
        final float thicknessFactor = (float) MathStuff.pow(occlusionCutoff, DIFFRACTION_THICKNESS_EXPONENT);
        final float hfRestore = MathStuff.clamp1(smoothedComp * diffractionHfStrength() * thicknessFactor);

        directCutoff = occlusionCutoff + (1F - occlusionCutoff) * levelRestore;
        final float directHf = occlusionCutoff + (1F - occlusionCutoff) * hfRestore;
        hfOut[0] = Math.max(directHf, hfOut[0]);
        final float reverbComp = directHf * DIFFRACTION_REVERB_SCALE;
        reverb.sendCutoff0 = Math.max(reverb.sendCutoff0, reverbComp);
        reverb.sendCutoff1 = Math.max(reverb.sendCutoff1, reverbComp);
        reverb.sendCutoff2 = Math.max(reverb.sendCutoff2, reverbComp);
        reverb.sendCutoff3 = Math.max(reverb.sendCutoff3, reverbComp);
        return directCutoff;
    }

    /** Applies the bounce-ratio scaling and clamps the send gains. */
    private static void finalizeSendGains(final ReverbTrace reverb) {
        reverb.sendGain1 *= reverb.bounceRatio[1];
        reverb.sendGain2 *= (float) MathStuff.pow(reverb.bounceRatio[2], 3.0);
        reverb.sendGain3 *= (float) MathStuff.pow(reverb.bounceRatio[3], 4.0);

        reverb.sendGain0 = MathStuff.clamp1(reverb.sendGain0);
        reverb.sendGain1 = MathStuff.clamp1(reverb.sendGain1);
        reverb.sendGain2 = MathStuff.clamp1(reverb.sendGain2 * 1.05F - 0.05F);
        reverb.sendGain3 = MathStuff.clamp1(reverb.sendGain3 * 1.05F - 0.05F);

        reverb.sendGain0 *= (float) MathStuff.pow(reverb.sendCutoff0, 0.1);
        reverb.sendGain1 *= (float) MathStuff.pow(reverb.sendCutoff1, 0.1);
        reverb.sendGain2 *= (float) MathStuff.pow(reverb.sendCutoff2, 0.1);
        reverb.sendGain3 *= (float) MathStuff.pow(reverb.sendCutoff3, 0.1);
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

    private float calculateOcclusion(final WorldContext ctx, final Vec3 origin, final Vec3 target) {

        // Shortcut if occlusion isn't to happen for this sound
        if (skipOcclusion(this.source.getCategory())) {
            this.lastOccluderPos = null;
            this.lastCenterOcclusion = 0F;
            this.lastApertureLeak = 0F;
            this.lastZoneLossDb = 0F;
            this.lastListenerOpenness = 1F;
            this.lastOpennessLossDb = 0F;
            return 0F;
        }

        assert ctx.world != null;
        assert ctx.player != null;

        // A single ray along the direct source->player line is binary: a block muffles only
        // when it lies exactly on that one line, so a small obstacle (a 1x1 block near the
        // source) or a pit wall flips the sound on/off as the player moves a block. Averaging
        // a tight cone of rays around the player's eye instead makes the value track the
        // fraction of the sound cone that actually reaches the player: a solid wall still
        // blocks all of them, an opening lets some through, and a small block only occludes
        // the part of the cone it occupies.
        //
        // The fan is built around the source->player bearing (the rays diverge perpendicular
        // to it), so it stays meaningful at any relative position - sideways, above, or
        // below the source. A world-axis fan would collapse to a single ray when the player
        // stands directly over/under the source. The spread is small (0.6 blocks around the
        // eye) and the results are time-smoothed, so a stray ray clipping the ground near
        // the player barely moves the averaged value. Kept to 5 rays so the added cost stays
        // a small fraction of the reverb trace (32x4). The ray count is configurable
        // (occlusionFanRays) for anyone who wants a smoother transition.
        // First solid occlusion hit on the CENTRE ray only. This is where the wall sits
        // along the direct path, and it is the ring centre for the diffraction probe. The
        // off-axis fan rays below must not pollute it, or the compensation would trigger
        // (and be centred) on incidental terrain clipping far to the side of the eye.
        // Trace from just outside the source's own solid block (a jukebox) so its body is
        // not counted as an occluder: otherwise the centre ray hits the source itself,
        // lastOccluderPos lands inside the source, and the diffraction ring centres on the
        // wrong place (killing the compensation for solid-block sources).
        final Vec3 rayOrigin = stepOutOfSolid(ctx.world, origin, target);
        final Vec3[] centerOccluder = new Vec3[]{null};
        float centerFactor = traceOcclusion(ctx, rayOrigin, target, centerOccluder);
        this.lastOccluderPos = centerOccluder[0];
        // The aperture is only interesting when something is actually on the straight line: with a
        // clear line there is nothing to go around, and the compensation is gated off anyway. The flag
        // matters below: with no occluder the leak is 0, which is NOT the same statement as "the
        // aperture is blocked", and treating it as such would throw the fan away in exactly the case
        // the fan exists for (centre line clear, edge of the cone blocked).
        final boolean apertureRan = this.lastOccluderPos != null;
        if (apertureRan) {
            // The open solid angle around the ear, measured directly rather than inferred from a fixed
            // number: this is what a room contributes to the direct sound's attenuation. (The
            // enclosure/openness gate that used to be computed here is gone from this path - the silhouette
            // walk validates its own waypoints, so the gate was a redundant second penalty. The probes
            // themselves still serve the ring probe's compensation in applyDiffraction, which only matters
            // when the occlusionFresnelZone model is switched off.)
            this.lastListenerOpenness = measureListenerOpenness(ctx, target);
            this.lastOpennessLossDb = AudioTuning.opennessLossDb() * (1F - this.lastListenerOpenness);
            this.lastApertureLeak = calculateApertureLeak(ctx, origin, target);
        } else {
            // Nothing on the straight line, so the Fresnel zone is not blocked at any plane: clear the
            // zone loss, or the zone model would keep applying the PREVIOUS evaluation's wall to a line
            // that is now open.
            this.lastApertureLeak = 0F;
            this.lastZoneLossDb = 0F;
            this.lastListenerOpenness = 1F;
            this.lastOpennessLossDb = 0F;
        }
        // Weight the centre ray's score by how much of the wavefront actually gets through. This is
        // the whole point of the aperture: the centre ray answers "is something on the line?", so
        // several obstacles on it used to muffle the sound exactly like a sealed room, even with the
        // whole world open around it - which is what "in the open the occlusion is still very strong"
        // was. A leak of 1 (nothing but air around the obstacle, or no obstacle at all) leaves the
        // score untouched; a wall that spans the disc takes it to ~0, so the wall still mutes.
        centerFactor *= 1F - AudioTuning.apertureStrength() * (1F - this.lastApertureLeak);
        this.lastCenterOcclusion = centerFactor;
        float factor = centerFactor;
        int rays = 1;
        // Where the centre ray's share of the average is left, and what it is blended with below.
        final float centerWeight = centerFactor;

        final Vec3 dir = target.subtract(origin).normalize();
        // Two orthogonal axes perpendicular to the bearing. Use the world Y axis (or X if the bearing
        // is nearly vertical) to seed the cross product so the axes stay well-defined.
        final Vec3 seed = Math.abs(dir.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 axis1 = dir.cross(seed).normalize();
        final Vec3 axis2 = dir.cross(axis1).normalize();
        // The cone is an ANGLE off the axis, not a fixed offset at the listener. A fixed offset is
        // distance-dependent and was 0.86 degrees at 40 blocks, so the rays were parallel to within a
        // fraction of a block - they could not sample a cone, and (measured on 1.20.1) reported
        // occlusion 5.08 against a completely clear centre ray, because a ray 0.6 blocks off an eye
        // 1.62 blocks up hits the ground beside the player at full weight.
        final double halfAngle = Math.toRadians(AudioTuning.occlusionConeDegrees());
        final float cosHalf = (float) Math.cos(halfAngle);
        final float sinHalf = (float) Math.sin(halfAngle);
        final int fanRays = AudioTuning.occlusionFanRays() - 1;
        float fanWeighted = 0F;
        float fanWeightTotal = 0F;
        final double rayLength = target.distanceTo(rayOrigin);
        for (int i = 0; i < fanRays; i++) {
            // Half the rays sit on the axis (so the straight line is properly represented) and the rest
            // are spread around the cone's rim, which is where an opening or an obstacle edge shows up.
            final boolean onAxis = i < fanRays / 2;
            final double azimuth = 2.0D * Math.PI * i / Math.max(1, fanRays - fanRays / 2);
            final Vec3 rayDir;
            if (onAxis) {
                rayDir = dir;
            } else {
                final Vec3 offset = axis1.scale(Math.cos(azimuth) * sinHalf)
                        .add(axis2.scale(Math.sin(azimuth) * sinHalf));
                rayDir = dir.scale(cosHalf).add(offset).normalize();
            }
            // The axis carries the sound and the rim of the cone much less, so a lone pillar clipping
            // one rim ray cannot swing the result the way an unweighted average did.
            final float weight = onAxis ? 1F : AXIS_WEIGHT_FLOOR;
            fanWeighted += traceOcclusion(ctx, rayOrigin, target.add(rayDir.scale(rayLength)), null) * weight;
            fanWeightTotal += weight;
            rays++;
        }

        final float fanAverage = fanWeightTotal > 0F ? fanWeighted / fanWeightTotal : centerFactor;
        // The cone rays can still be harsher than the axis: a rim ray grazing a hillside a few blocks
        // from the player is real geometry at full weight, while the axis is clear. The aperture
        // measures the same question with an actual area and material weighting, so let it decide how
        // much the cone is trusted: the clearer the aperture, the more the axis wins. A low leak means
        // the line really is walled off, the cone keeps its say, and a wall still mutes.
        final float fanWeight = apertureRan
                ? 1F - AudioTuning.apertureStrength() * this.lastApertureLeak
                : 1F;
        this.lastFanAverage = fanAverage;
        // The zone model replaces both the material sum AND the fan when it has a measurement: the
        // coefficient it produces is the one whose exp(-c * absorption) equals the physically derived
        // attenuation.
        final float zoneCoefficient = zoneOcclusionCoefficient(Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F);
        this.lastZoneCoefficient = zoneCoefficient;
        final float occlusion;
        if (zoneCoefficient >= 0F) {
            occlusion = zoneCoefficient;
            this.lastOcclusionSource = 0;
        } else {
            // No zone measurement (the centre ray is clear, so there is nothing to measure around), or the
            // zone model is off. The centre ray is then the honest answer on its own: it is the line the
            // sound travels. The fan is NOT blended in here, because a 12-degree cone is 1.7 blocks wide at
            // 8 blocks and therefore sweeps the wall beside or behind the listener, which drove the sound to
            // silence on lines with nothing on them - measured on 1.20.1 as occlusion 4.450 against a
            // completely clear centre ray (0.000). It is still computed and reported as fan=, and it still
            // drives the occlusion when the zone model is switched off.
            final float blended = centerWeight + (fanAverage - centerWeight) * fanWeight;
            occlusion = AudioTuning.occlusionFresnelZone() ? centerWeight : blended;
            this.lastOcclusionSource = 1;
        }
        this.lastMaterialSum = occlusion;
        // The listener's own surroundings contribute their own attenuation, and it comes from a
        // measurement rather than a constant: sound reaching an ear inside a room arrives only through the
        // opening, so the open share of the solid angle around that ear is the direct-sound loss. Outdoors
        // the share is 1 and this is exactly 0, so open terrain is untouched; a sealed room has almost no
        // open share and is muffled by construction; a large opening lands in between on its own.
        if (this.lastOpennessLossDb <= 0F)
            return occlusion;
        final float absorption = Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F;
        final float openTerm = this.lastOpennessLossDb / Math.max(1.0E-6F, absorption);
        if (openTerm > occlusion) {
            this.lastOcclusionSource = 2;
            return openTerm;
        }
        return occlusion;
    }

    /**
     * Fraction of the solid angle around the listener from which a ray travels far enough to be considered
     * open. Directions are spread over a Fibonacci sphere so the samples are even and there is no axis bias.
     *
     * <p>This is the measurement a room contributes: a listener in the open has every direction open and the
     * fraction is 1; a listener in a sealed room has only the opening, and the fraction is that share. Each
     * direction costs one raycast, so the cost is fixed and independent of geometry.
     */
    private float measureListenerOpenness(final WorldContext ctx, final Vec3 eye) {
        final int rays = AudioTuning.opennessRays();
        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        final float step = 1F / rays;
        int open = 0;
        for (int i = 0; i < rays; i++) {
            // Fibonacci sphere: even spacing with no clustering at the poles.
            final double y = 1.0D - 2.0D * (i + 0.5D) * step;
            final double r = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            final double phi = Math.PI * (1.0D + Math.sqrt(5.0D)) * i;
            final Vec3 dir = new Vec3(Math.cos(phi) * r, y, Math.sin(phi) * r).normalize();
            if (isMiss(traceContext.trace(eye, eye.add(dir.scale(OPENNESS_PROBE_DISTANCE)))))
                open++;
        }
        return open / (float) rays;
    }

    private float traceOcclusion(final WorldContext ctx, final Vec3 origin, final Vec3 target,
                                 final Vec3[] firstOccluder) {
        float factor = 0F;

        Vec3 lastHit = origin;
        BlockState lastState = ctx.world.getBlockState(BlockPos.containing(lastHit.x(), lastHit.y(), lastHit.z()));
        var traceContext = new ReusableRaycastContext(ctx.world, origin, target, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY);
        var itr = new ReusableRaycastIterator(traceContext);
        final int segments = occlusionSegments();
        for (int i = 0; i < segments; i++) {
            if (itr.hasNext()) {
                var result = itr.next();
                final float occlusion = getOcclusion(lastState);
                final double rayDistance = lastHit.distanceTo(result.getLocation());
                // Occlusion is scaled by the distance traveled through the block.
                factor += (float) (occlusion * rayDistance);
                if (occlusion > 0F && firstOccluder != null && firstOccluder[0] == null) {
                    // Record where the line ENTERS the first obstacle - its near surface - rather than the
                    // segment's midpoint. The wave bends around the silhouette, which is on that face; the
                    // midpoint of a hill's first solid segment is deep inside the hill, and anchoring there
                    // put both the detour search and the aperture plane inside rock (measured: no detour
                    // waypoint accepted at all, and leak = 0.000 on every band while the listener stood in
                    // open air).
                    firstOccluder[0] = lastHit;
                }
                lastHit = result.getLocation();
                lastState = ctx.world.getBlockState(result.getBlockPos());
            } else {
                break;
            }
        }

        return factor;
    }

    /**
     * Aperture sampling: the practical, incoherent form of the Huygens-Fresnel principle.
     *
     * <p>A straight-line occlusion probe answers "is anything on the line?", which is the wrong
     * question. Sound reaches the listener through the whole wavefront between the two ends, and
     * only the part of that wavefront actually blocked by solid matter is lost. Sampling a disc of
     * secondary sources on the plane through the first obstacle and adding up the energy that
     * survives the trip to the source and on to the listener gives the fraction of the direct sound
     * that still arrives - so a lone pillar (most of the disc clear) barely muffles, while a wall
     * that spans the disc muffles completely.
     *
     * <p>Each sample casts two short rays (source->sample and sample->listener) instead of the ring
     * probe's waypoint validation, so this costs about the same as the probe it complements.
     *
     * <p>The material matters on both legs: a sample is weighted by what the source leg leaves
     * after travelling through block material, and a sample whose listener leg is blocked
     * contributes nothing at all.
     *
     * @return 0..1, the fraction of the aperture that carries sound through to the listener.
     */
    private float calculateApertureLeak(final WorldContext ctx, final Vec3 source, final Vec3 listener) {
        final Vec3 direct = listener.subtract(source);
        final double directLen = direct.length();
        if (directLen < 0.01D)
            return 1F;

        // The aperture is the FIRST FRESNEL ZONE, R = sqrt(wavelength * d1 * d2 / (d1 + d2)), measured in
        // octave bands. The zone scales as 1/sqrt(frequency), so one hill covers a high frequency's
        // narrow zone completely while leaving a low frequency's wide zone partly clear - which is why a
        // source on a hill heard from the valley is dull but present with the valley's reverberation
        // behind it, not silenced. A single-frequency measurement could not express that.
        final float configuredRadius = AudioTuning.apertureRadius();
        final boolean wavelength = AudioTuning.realismWavelength();
        final int bandCount = bandCount();
        final int planes = AudioTuning.aperturePlanes();
        final float absorption = Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F;

        // Orthonormal frame perpendicular to the bearing, shared by every band and plane.
        final Vec3 d = direct.scale(1.0D / directLen);
        final Vec3 seed = Math.abs(d.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 u = d.cross(seed).normalize();
        final Vec3 v = d.cross(u).normalize();

        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        final Vec3 sourceLegOrigin = stepOutOfSolid(ctx.world, source, listener);

        this.lastFresnelRadius = 0F;
        float worstClear = 1F;
        float excessDb = 0F;
        // The best edge path around the occluder, per band. A wavefront takes the most effective path
        // available, so the loss is the LESS pessimistic of "the aperture is blocked" and "the wave bends
        // over the edge". Without this a hill - whose crest lies outside the Fresnel zone, so the zone
        // model cannot see it - reads as a blocked aperture and comes out at the full 25 dB.
        final float[] edgeDb = edgeAmplitudePerBand(ctx, source, listener);
        for (int band = 0; band < bandCount; band++) {
            final float bandHz = bandFrequency(band);
            final double bandWavelength = SPEED_OF_SOUND / Math.max(1F, bandHz);
            final float bandClear = measureBandClear(ctx, source, listener, direct, directLen, d, u, v,
                    traceContext, sourceLegOrigin, configuredRadius, wavelength, planes, absorption,
                    bandWavelength);
            if (bandClear < 0F)
                continue;                       // no plane could be sampled for this band
            worstClear = Math.min(worstClear, bandClear);
            // Combine the bands with the low band dominant: it is the one that reaches the listener
            // around the obstacle, and the ear weights it that way too.
            // Two paths, added as amplitudes. The material path is what the wall's own occlusion
            // coefficient produces; the edge path is what bends around the obstacle's silhouette. They
            // arrive independently and add incoherently, so the result is louder than either alone - and
            // combining them with min() instead (as this did) let any reachable edge cancel the material
            // coefficient, which is why a wall stopped muffling as soon as the silhouette walk found its
            // corner.
            float bandAmplitude = bandAmplitude(bandClear);
            if (edgeDb != null) {
                // No gate here: the silhouette walk only accepts a waypoint that sees BOTH the source and
                // the listener, so a found edge is a real path and no edge contributes nothing. Scaling by
                // the enclosure/openness gate on top of that was a second, heuristic penalty - and it
                // suppressed the contribution where the geometry merely looks enclosed while a path exists
                // (a listener under a roof overhang, a source in a cave mouth). The gate was needed when the
                // search was fixed-radius rings that accepted unreachable waypoints; that search is gone.
                bandAmplitude = Math.min(1F, bandAmplitude + edgeDb[band]);
            }
            // Back to dB once, after the combination.
            excessDb += bandWeight(band)
                    * (float) (-20.0D * Math.log10(Math.max(1.0E-6F, bandAmplitude)));
        }
        this.lastZoneLossDb = excessDb;
        return MathStuff.clamp1(worstClear);
    }

    /**
     * One octave band: samples every aperture plane at this band's zone radius and returns the worst
     * plane's clear fraction, or -1 when no plane could be sampled.
     */
    private float measureBandClear(final WorldContext ctx, final Vec3 source, final Vec3 listener,
                                   final Vec3 direct, final double directLen, final Vec3 d, final Vec3 u,
                                   final Vec3 v, final ReusableRaycastContext traceContext,
                                   final Vec3 sourceLegOrigin, final float configuredRadius,
                                   final boolean wavelength, final int planes, final float absorption,
                                   final double bandWavelength) {
        // Per plane: the zone weight sampled and the weight that is clear.
        final float[] planeWeight = new float[planes];
        final float[] planeClearWeight = new float[planes];
        float taken = 0F;

        for (int p = 1; p <= planes; p++) {
            // The plane has to sit where the wavefront is actually blocked - the obstacle's near
            // surface - not at a fixed fraction of the line. With the line running through a hill, evenly
            // spaced planes land INSIDE the hill, every sample is rock, the zone reads clear = 0 and the
            // loss goes to its maximum; that is what made the result bimodal (0.1 dB when a detour path
            // existed, 25.2 dB when it did not, nothing in between). One plane always sits on the
            // occluder; the rest are spread between it and the two ends, so a second obstacle along the
            // way is still caught.
            final Vec3 planeCentre;
            if (this.lastOccluderPos == null) {
                planeCentre = MathStuff.addScaled(source, direct, (float) p / (planes + 1));
            } else if (p == 1) {
                planeCentre = this.lastOccluderPos;
            } else {
                // Spread the remaining planes between the occluder and whichever end they belong to.
                final double occluderFraction = MathStuff.clamp1(
                        (float) (source.distanceTo(this.lastOccluderPos) / Math.max(0.01D, directLen)));
                final int side = (p - 2) % 2;                    // 0 = source side, 1 = listener side
                final int step = (p - 2) / 2 + 1;
                final int stepsOnSide = Math.max(1, (planes - 1) / 2);
                final double along = side == 0
                        ? occluderFraction * (1.0D - (double) step / (stepsOnSide + 1))
                        : occluderFraction + (1.0D - occluderFraction) * ((double) step / (stepsOnSide + 1));
                planeCentre = MathStuff.addScaled(source, direct, (float) MathStuff.clamp1((float) along));
            }
            // Distances from each end to the plane that was chosen, so the zone radius belongs to this
            // plane's position rather than to an assumed fraction.
            final double d1 = source.distanceTo(planeCentre);
            final double d2 = planeCentre.distanceTo(listener);
            final float planeRadius;
            if (wavelength) {
                planeRadius = (float) Math.min(MAX_APERTURE_RADIUS,
                        Math.sqrt(bandWavelength * Math.max(0.01D, d1) * Math.max(0.01D, d2)
                                / Math.max(0.02D, d1 + d2)));
            } else {
                planeRadius = configuredRadius;
            }
            if (planeRadius <= 0.05F)
                continue;
            // The widest zone measured (the lowest band) is the one reported: it is the one that decides
            // whether an obstacle is a wall or a nuisance.
            if (planeRadius > this.lastFresnelRadius)
                this.lastFresnelRadius = planeRadius;

            for (int r = 1; r <= APERTURE_RINGS; r++) {
                // Inner ring at half the zone radius, outer ring at the zone edge: the two radii bracket
                // the first Fresnel zone instead of sampling a fixed disc uniformly.
                final float ringRadius = planeRadius * r / APERTURE_RINGS;
                final int inRing = Math.max(1, APERTURE_SAMPLES / APERTURE_RINGS);
                for (int k = 0; k < inRing; k++) {
                    // Offset each ring's start angle so samples do not line up along the disc's axes.
                    final double angle = 2.0D * Math.PI * (k + r * 0.5D) / inRing;
                    final Vec3 sample = planeCentre
                            .add(u.scale(Math.cos(angle) * ringRadius))
                            .add(v.scale(Math.sin(angle) * ringRadius));
                    // Zone weight: the first Fresnel zone matters most along its centre and not at all at
                    // its edge, so a sample's importance falls off with its distance from the direct
                    // line. This is what makes a small obstacle in the middle of the zone nearly harmless
                    // while an obstacle covering the whole zone blocks the sound.
                    final float zoneWeight = (float) MathStuff.exp(-2.0D * (ringRadius * ringRadius)
                            / Math.max(1.0E-4D, planeRadius * planeRadius));
                    planeWeight[p - 1] += zoneWeight;
                    taken += zoneWeight;
                    // A sample inside solid matter is not a secondary source at all: there is no air there
                    // to carry the wave, and stepping it out would let it escape through a thick wall and
                    // report the wall as transparent. It blocks its share of the zone.
                    if (isSolidBlock(ctx.world, sample))
                        continue;
                    // Both legs are a BINARY question - does this secondary source see the source, and does
                    // it see the listener - so each is ONE raycast. They used to walk up to twelve segments
                    // per leg to measure material THICKNESS, which the aperture never uses; that segment
                    // walk was the dominant cost of the whole occlusion path (96 legs, measured at 597
                    // raycasts per evaluation against a pre-change budget of 193).
                    if (!isMiss(traceContext.trace(sourceLegOrigin, sample)))
                        continue;
                    if (!isMiss(traceContext.trace(sample, listener)))
                        continue;
                    planeClearWeight[p - 1] += zoneWeight;
                }
            }
        }
        if (taken <= 0F)
            return -1F;
        // The wavefront's clear fraction is decided by the WORST plane it has to cross, not by the
        // product: one ray cannot pass a plane that blocks it, however open the others are.
        float bandClear = 1F;
        for (int i = 0; i < planes; i++) {
            if (planeWeight[i] <= 0F)
                continue;
            bandClear = Math.min(bandClear, MathStuff.clamp1(planeClearWeight[i] / planeWeight[i]));
        }
        return bandClear;
    }

    /**
     * Excess attenuation in dB for one band's clear fraction.
     *
     * <p>From the standard Fresnel result: an obstacle covering a small part of the zone costs almost
     * nothing, and only a covered zone goes silent. The falloff is the FOURTH power of the blocked
     * fraction, not the square - with a square a plane that is 10% clear still costs 20.5 dB, so almost
     * any partial obstruction saturated (measured: 36% of 1814 evaluations pinned at the two-plane
     * maximum of 50.4 dB). At the fourth power: 0.03 dB when 90% clear, 2.6 dB at 80%, 9.6 dB at 50%,
     * 16.4 dB at 10%.
     */
    private static float bandAmplitude(final float clear) {
        final float blocked = 1F - clear;
        final float blockedSq = blocked * blocked;
        final float db = 0.2F + AudioTuning.occlusionLossDb() * blockedSq * blockedSq;
        return (float) Math.pow(10.0D, -db / 20.0D);
    }

    /**
     * The occlusion coefficient the rest of the pipeline consumes, derived from the zone model's excess
     * attenuation instead of a material sum. The pipeline turns the coefficient into
     * exp(-coefficient * absorption), so inverting that gives the coefficient that produces exactly the
     * measured loss - nothing downstream has to know which model ran.
     */
    private float zoneOcclusionCoefficient(final float absorption) {
        if (!AudioTuning.occlusionFresnelZone() || this.lastZoneLossDb <= 0F)
            return -1F;
        final double transmission = Math.pow(10.0D, -this.lastZoneLossDb / 20.0D);
        return (float) (-Math.log(Math.max(1.0E-6D, transmission)) / Math.max(1.0E-6F, absorption));
    }

    /**
     * How much block material at a given distance along the line still counts. 1 up to the focus
     * distance, then 1 / (1 + (d - focus)/focus). With the focus at 0 every block counts fully (the
     * previous behaviour).
     */
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

    private static double occlusionDistanceWeight(final double along, final double totalDistance) {
        final float focus = AudioTuning.occlusionFocusDistance();
        if (focus <= 0F)
            return 1.0D;
        // Measure from whichever end is nearer: the effect is local to the source AND to the listener.
        final double fromNearestEnd = Math.min(along, Math.max(0.0D, totalDistance - along));
        if (fromNearestEnd <= focus)
            return 1.0D;
        return 1.0D / (1.0D + (fromNearestEnd - focus) / focus);
    }

    /**
     * Measures how sealed the sound source is: a short ray up each world axis from
     * the source counts the directions blocked by solid geometry. A source buried in
     * a 6x6x6 cube scores ~1.0 no matter which way the player stands, which removes
     * the per-direction asymmetry of the player-facing occlusion fan; a source on
     * open ground scores ~0.17 (just the ground below).
     * <p>
     * The probe starts on the source's own surface, not its centre: a source that is
     * itself a solid block (a jukebox) would otherwise read every axis as blocked by
     * its own body and report as fully enclosed, silently killing the compensation
     * for that sound.
     */
    private float calculateSourceEnclosure(final WorldContext ctx, final Vec3 origin) {
        int blocked = 0;
        final BlockPos originPos = BlockPos.containing(origin);
        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        for (final Direction d : Direction.values()) {
            final Vec3 dir = SURFACE_DIRECTION_NORMALS[d.ordinal()];
            // Step outwards until leaving the source's own block before probing, so
            // the source body itself never counts as enclosure. Any further solid
            // (earth around a buried jukebox, a wool mass) still blocks the ray.
            Vec3 probeStart = origin;
            for (int step = 0; step < 8 && BlockPos.containing(probeStart).equals(originPos); step++)
                probeStart = probeStart.add(dir.scale(0.5));
            final Vec3 target = probeStart.add(dir.scale(PROBE_RAY_DISTANCE));
            if (!isMiss(traceContext.trace(probeStart, target)))
                blocked++;
        }
        return blocked / (float) Direction.values().length;
    }

    /**
     * Measures how open the space around the player's ear is, the mirror of the
     * source enclosure. A player hugging a thin wall in open terrain still has most
     * directions open, so sound can diffract around the wall to reach them; a player
     * in a sealed room does not, and stays muffled even when the source is outside.
     */
    private float calculatePlayerOpenness(final WorldContext ctx, final Vec3 eye) {
        int open = 0;
        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        for (final Direction d : Direction.values()) {
            final Vec3 target = eye.add(SURFACE_DIRECTION_NORMALS[d.ordinal()].scale(PROBE_RAY_DISTANCE));
            if (isMiss(traceContext.trace(eye, target)))
                open++;
        }
        return open / (float) Direction.values().length;
    }

    /**
     * Finds the shortest unobstructed detour path around the occluder and returns the
     * diffraction attenuation for its extra length ΔL over the straight line. Waypoints
     * are sampled on rings centred on the occluder in the plane perpendicular to the
     * source->player bearing, so the rings cover "around the sides", "over the top" and
     * "under" uniformly; the radius grows until a valid path is found (a wide wall needs
     * a larger ring to reach past its edge). A valid waypoint must itself be clear and
     * see both ends; ΔL = |S->W| + |W->P| − |S->P|. Returns 0 when no edge is reachable
     * (both ends sealed in / buried), leaving the base occlusion to muffle the sound.
     */
    private float calculateDiffraction(final WorldContext ctx, final Vec3 source, final Vec3 player,
                                       final Vec3 occluder) {
        final Vec3 direct = player.subtract(source);
        final double directLen = direct.length();
        if (directLen < 0.01D)
            return 0F;

        // Orthonormal frame perpendicular to the direct bearing: u/v span the plane the
        // occluder sits in, so the ring samples every perpendicular direction.
        final Vec3 d = direct.scale(1.0D / directLen);
        final Vec3 seed = Math.abs(d.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 u = d.cross(seed).normalize();
        final Vec3 v = d.cross(u).normalize();

        // Weighted sum over every sample instead of the shortest unobstructed path. Taking the
        // minimum made the value jump whenever the "best" edge changed sides, and - because the
        // old code returned on the first ring that found anything - the whole result depended on a
        // single sample of the innermost ring. Summing over rings removes that dependence and
        // lowers the restore, because a distant ring's longer detour contributes less.
        //
        // This is the practical, incoherent form of the Huygens-Fresnel idea: treat the reachable
        // waypoints as secondary sources and add up their contributions, ignoring phase. A shorter
        // detour contributes more (1/(1 + falloff*delta)); nearer rings are weighted higher so a
        // far ring cannot dominate just by having more clear samples.
        //
        // Known limitation, NOT fixed here: the rings sit in the plane perpendicular to the
        // bearing, so their offsets slide ALONG a flat wall's face. A wall therefore reads about
        // the same at 1 block and at 2 blocks thick; thickness sensitivity would need samples
        // offset along the bearing as well.
        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        final int rings = Math.min(diffractionRings(), DETOUR_RADII.length);
        float weighted = 0F;
        float totalWeight = 0F;
        for (int r = 0; r < rings; r++) {
            final float radius = DETOUR_RADII[r];
            final float ringWeight = 1F / (1F + radius);
            for (int k = 0; k < DETOUR_SAMPLES; k++) {
                totalWeight += ringWeight;
                final double angle = (2.0D * Math.PI * k) / DETOUR_SAMPLES;
                final Vec3 waypoint = occluder
                        .add(u.scale(Math.cos(angle) * radius))
                        .add(v.scale(Math.sin(angle) * radius));
                // Skip waypoints sitting inside solid matter; they cannot be a free edge.
                if (isSolidBlock(ctx.world, waypoint))
                    continue;
                // Both legs must be unobstructed for the wave to actually bend this way.
                // A source that is itself a solid block (a jukebox) starts the trace
                // inside its own body and would hit itself immediately, so step the
                // origin out onto its surface first; only real obstacles then count.
                if (!isMiss(traceContext.trace(stepOutOfSolid(ctx.world, source, waypoint), waypoint)))
                    continue;
                if (!isMiss(traceContext.trace(waypoint, player)))
                    continue;
                final float delta = (float) (source.distanceTo(waypoint) + waypoint.distanceTo(player) - directLen);
                weighted += ringWeight * edgeAmplitude(delta, AudioTuning.realismFrequencyHz());
            }
        }
        if (totalWeight <= 0F)
            return 0F;
        return EDGE_LOSS * (weighted / totalWeight);
    }

    /**
     * Knife-edge amplitude for a detour of {@code delta} blocks at {@code frequencyHz}.
     *
     * <p>The Fresnel number n = sqrt(2 * dL / lambda) counts the half-wavelengths the detour costs, and the
     * standard knife-edge result is 0.5 at the shadow boundary (n = 0) rising towards 1 as the detour
     * clears. Without the wavelength term every frequency diffracted alike, which is wrong in the one way
     * that matters: a low frequency bends around an obstacle that stops a high one outright.
     */
    private static float edgeAmplitude(final float delta, final float frequencyHz) {
        if (!AudioTuning.realismWavelength())
            return 1F / (1F + DIFFRACTION_FALLOFF * delta);
        final double lambda = SPEED_OF_SOUND / Math.max(1F, frequencyHz);
        final double n = Math.sqrt(Math.max(0.0D, 2.0D * delta / lambda));
        final float loss = 0.5F + (float) (0.5D * Math.tanh(n));
        return 1F - DIFFRACTION_LOSS_SCALE * (1F - loss);
    }

    /**
     * Excess attenuation in dB of the best edge path around the occluder, per band, or a large value when
     * no edge is reachable at all.
     *
     * <p>This is what makes a hill behave like a hill. The Fresnel-zone model answers "how much of the
     * field survives inside the geometric shadow", and a hill's crest lies OUTSIDE the zone, so the zone
     * model reads the aperture as fully blocked and reports its maximum. Physically the wave bends over
     * the crest, and because the knife-edge Fresnel number is strongly frequency dependent the low band
     * bends with almost no loss while the high band does not - the "dull but audible" result.
     *
     * <p>Finding the crest is a silhouette walk, not a ring sample. Fixed-radius rings around the occluder
     * answered a two-state question (a point cleared, or none did) and produced a two-state result: 0.1 dB
     * or the blocked-aperture maximum, nothing between. Marching outward from where the line enters the
     * obstacle and stopping at the first offset that sees BOTH ends lands on the silhouette - where the
     * line grazes the obstacle - and measures the detour directly instead of assuming a radius. The detour
     * length then varies continuously as the obstacle moves relative to the line, which is what gives a
     * continuum of losses rather than a switch.
     *
     * <p>Every direction that clears contributes its own figure and the smallest wins, so a route around
     * the side is found even when the one over the top is blocked.
     *
     * @return array of dB per band index, or null when there is no occluder to work from.
     */
    private float[] edgeAmplitudePerBand(final WorldContext ctx, final Vec3 source, final Vec3 player) {
        if (this.lastOccluderPos == null)
            return null;
        final Vec3 direct = player.subtract(source);
        final double directLen = direct.length();
        if (directLen < 0.01D)
            return null;
        final Vec3 d = direct.scale(1.0D / directLen);
        final Vec3 seed = Math.abs(d.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 u = d.cross(seed).normalize();
        final Vec3 v = d.cross(u).normalize();
        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        final int bands = bandCount();
        // Amplitudes, so the floor is zero rather than a large dB value: no edge means no contribution.
        final float[] best = new float[bands];
        final Vec3 anchor = this.lastOccluderPos;
        final Vec3 sourceLegOrigin = stepOutOfSolid(ctx.world, source, anchor);
        int accepted = 0;
        float smallestDelta = -1F;

        for (int dir = 0; dir < SILHOUETTE_DIRECTIONS; dir++) {
            final double angle = 2.0D * Math.PI * dir / SILHOUETTE_DIRECTIONS;
            final Vec3 axis = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle))).normalize();
            for (int step = 1; step <= SILHOUETTE_STEPS; step++) {
                final Vec3 point = anchor.add(axis.scale(SILHOUETTE_STEP * step));
                // A point inside solid matter cannot be a path; keep walking outward.
                if (isSolidBlock(ctx.world, point))
                    continue;
                // The wave needs to see both ends from here. The first offset that does is the silhouette.
                if (!isMiss(traceContext.trace(sourceLegOrigin, point)))
                    continue;
                if (!isMiss(traceContext.trace(point, player)))
                    continue;
                accepted++;
                final float delta = (float) (source.distanceTo(point) + point.distanceTo(player) - directLen);
                if (smallestDelta < 0F || delta < smallestDelta)
                    smallestDelta = delta;
                for (int band = 0; band < bands; band++) {
                    // Amplitude, because the combination with the material path is a sum of amplitudes.
                    final float amplitude = edgeAmplitude(delta, bandFrequency(band));
                    if (amplitude > best[band])
                        best[band] = amplitude;
                }
                // This direction found its silhouette; a further step would only cost more detour.
                break;
            }
        }

        this.lastEdgeWaypoints = accepted;
        this.lastEdgeFound = accepted > 0;
        this.lastEdgeDelta = smallestDelta;
        for (int band = 0; band < bands; band++) {
            if (band < this.lastEdgeDb.length)
                this.lastEdgeDb[band] = (float) (-20.0D * Math.log10(Math.max(1.0E-6F, best[band])));
        }
        return best;
    }

    private static float calculateWeatherAbsorption(final WorldContext ctx, final Vec3 pt1, final Vec3 pt2) {
        assert ctx.world != null;

        if (!ctx.isPrecipitating)
            return 1F;

        final BlockPos low = BlockPos.containing(pt1);
        final BlockPos mid = BlockPos.containing(MathStuff.addScaled(pt1, pt2, 0.5F));
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

    // DIAG(1.20.1-reverb): one line per distinct block id - verifies the reflectance
    // tag chain at runtime. The stone family must resolve to MAX=1.0; a value of
    // 0.35 (LOW) means the dsconfigs tag lookup silently fell back to DEFAULT,
    // which shifts every reflection delay into zone0 and starves zone1-3.

    private static float getReflectivity(BlockState state) {
        // Use the weak form because the BlockInfo may not be filled out when
        // the FX system needs to evaluate. The info object should only
        // be filled out by the render thread.
        final float refl = BLOCK_LIBRARY.getBlockInfoWeak(state).getSoundReflectivity();
        return refl;
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

    private static boolean isMiss(@Nullable final BlockHitResult result) {
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    private static boolean skipOcclusion(SoundSource category) {
        return !CONFIG.enableOcclusionProcessing
                || category == SoundSource.MASTER
                || category == SoundSource.MUSIC;
    }

}