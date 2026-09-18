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
    private static final float[] DETOUR_RADII = {1.5F, 3F, 5F, 8F, 12F, 16F};

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
     * Total aperture samples per evaluation. Each sample costs two short traces, so this is kept
     * near the cost of the ring probe it complements (3 rings x 8 waypoints = 24 validated
     * waypoints, each with two traces).
     */
    private static final int APERTURE_SAMPLES = 16;
    /** Concentric rings the aperture samples are spread over (equal-area: outer rings get more). */
    private static final int APERTURE_RINGS = 2;
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
                        + "level=%.4f hfRestore=%.3f levelRestore=%.3f centerOccl=%.3f leak=%.3f send=%.3f",
                this.source.getCategory(), skipOcclusion(this.source.getCategory()), occlusionAccumulation,
                MathStuff.exp(sendCoeff), directCutoff, directHfCutoff, directGain,
                directHfCutoff <= 0F ? 0F : (directHfCutoff - MathStuff.exp(sendCoeff)) / Math.max(1e-6F, 1F - MathStuff.exp(sendCoeff)),
                directCutoff <= 0F ? 0F : (directCutoff - MathStuff.exp(sendCoeff)) / Math.max(1e-6F, 1F - MathStuff.exp(sendCoeff)),
                this.lastCenterOcclusion, this.lastApertureLeak, sendOcclusionGain));

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
        final float geometricDiffraction = calculateDiffraction(ctx, soundPos, listener, this.lastOccluderPos);
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
        final float enclosure = calculateSourceEnclosure(ctx, soundPos);
        final float openness = calculatePlayerOpenness(ctx, listener);
        // Gate the restore on true sealing only. A linear (1-enclosure) or raw
        // openness over-penalises normal partial occlusion - a source sitting next
        // to a wall (enclosure ~0.5) or a player hugging one (openness ~0.67) still
        // has free edges to diffract around. Cubing keeps those mid-values nearly
        // un-penalised and only shuts the gate off as one end becomes genuinely
        // sealed (enclosure -> 1 / openness -> 0), which the ring probe also sees.
        final float enclGate = 1F - (float) Math.pow(enclosure, 3.0);
        final float openGate = 1F - (float) Math.pow(1.0 - openness, 3.0);
        final float compensation = MathStuff.clamp1(diffraction * openGate * enclGate);
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
        // clear line there is nothing to go around, and the compensation is gated off anyway.
        this.lastApertureLeak = this.lastOccluderPos != null
                ? calculateApertureLeak(ctx, origin, target)
                : 0F;
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

        final Vec3 dir = target.subtract(origin).normalize();
        // Two orthogonal axes perpendicular to the bearing; the fan targets are points in
        // that plane around the player's eye. Use the world Y axis (or X if the bearing is
        // nearly vertical) to seed the cross product so the axes stay well-defined.
        final Vec3 seed = Math.abs(dir.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 axis1 = dir.cross(seed).normalize();
        final Vec3 axis2 = dir.cross(axis1).normalize();
        final int rings = occlusionFanRings();
        for (int ring = 1; ring <= rings; ring++) {
            // The innermost ring is the original 0.6-block offset; extra rings fill the cone between
            // the centre ray and that edge, so the same cone is sampled more finely without widening it.
            final float spread = 0.6F * ring / rings;
            for (final float s : new float[]{-1F, 1F}) {
                factor += traceOcclusion(ctx, rayOrigin, target.add(axis1.scale(spread * s)), null);
                factor += traceOcclusion(ctx, rayOrigin, target.add(axis2.scale(spread * s)), null);
                rays += 2;
            }
        }

        return factor / rays;
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
                    // Record the centre of the first solid segment: this is where the
                    // wall sits along the path, driving the diffraction compensation.
                    // addScaled is base + addened*scale, so the midpoint is
                    // lastHit + 0.5*(hit - lastHit), NOT addScaled(lastHit, hit, 0.5).
                    firstOccluder[0] = MathStuff.addScaled(lastHit, result.getLocation().subtract(lastHit), 0.5F);
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
        final float radius = AudioTuning.apertureRadius();
        final Vec3 direct = listener.subtract(source);
        final double directLen = direct.length();
        if (directLen < 0.01D)
            return 1F;

        final Vec3 d = direct.scale(1.0D / directLen);
        final Vec3 seed = Math.abs(d.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 u = d.cross(seed).normalize();
        final Vec3 v = d.cross(u).normalize();

        // Centre the disc on the obstacle that blocks the line when one was found; otherwise (the
        // line is clear, so this only runs to restore a previously muffled sound) use the midpoint.
        final Vec3 centre = this.lastOccluderPos != null
                ? this.lastOccluderPos
                : MathStuff.addScaled(source, direct, 0.5F);

        // The absorption coefficient is the same one that turns the accumulated occlusion into a
        // cutoff, so a sample is weighted in exactly the units the direct path uses.
        final float absorption = Effects.GLOBAL_BLOCK_ABSORPTION * 3.0F;

        final ReusableRaycastContext traceContext =
                new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);

        final Vec3 sourceLegOrigin = stepOutOfSolid(ctx.world, source, centre);
        final int samples = APERTURE_SAMPLES;
        final int rings = APERTURE_RINGS;
        float total = 0F;
        int taken = 0;
        for (int r = 1; r <= rings; r++) {
            // Equal-area rings: the outer rings get proportionally more samples, so the disc is
            // sampled evenly instead of over-weighting the middle.
            final float ringRadius = radius * r / rings;
            final int inRing = Math.max(1, Math.round(samples * (2F * r - 1F) / (rings * rings)));
            for (int k = 0; k < inRing; k++) {
                // Offset each ring's start angle so samples do not line up along the disc's axes.
                final double angle = 2.0D * Math.PI * (k + r * 0.5D) / inRing;
                final Vec3 sample = centre
                        .add(u.scale(Math.cos(angle) * ringRadius))
                        .add(v.scale(Math.sin(angle) * ringRadius));
                // A sample sitting inside solid matter is not a secondary source at all: there is no
                // air there to carry the wave, and stepping it out would let it escape through a
                // thick wall and report the wall as transparent. Count it in the denominator as
                // fully blocked instead.
                if (isSolidBlock(ctx.world, sample)) {
                    taken++;
                    continue;
                }
                // Leg 1: how much material the source->sample path passes through. A sample buried
                // behind solid matter scores a large occlusion and therefore contributes almost nothing.
                final float sourceOcclusion = traceOcclusionSum(ctx, traceContext, sourceLegOrigin, sample);
                final float sourceTransmission = (float) MathStuff.exp(-sourceOcclusion * absorption);
                taken++;
                if (sourceTransmission <= 0.001F)
                    continue;
                // Leg 2: the sample must actually see the listener. A blocked leg means the energy
                // went into a wall and never arrived, so that part of the aperture is lost.
                if (!isMiss(traceContext.trace(sample, listener)))
                    continue;
                total += sourceTransmission;
            }
        }
        if (taken <= 0)
            return 1F;
        return MathStuff.clamp1(total / taken);
    }

    /**
     * Distance-weighted occlusion accumulation along one segment, using a caller-supplied reusable
     * raycast context. Same measure as {@link #traceOcclusion} but with no first-occluder bookkeeping
     * and no per-call context allocation, because the aperture samples it many times per evaluation.
     */
    private static float traceOcclusionSum(final WorldContext ctx, final ReusableRaycastContext traceContext,
                                           final Vec3 origin, final Vec3 target) {
        float factor = 0F;
        Vec3 lastHit = origin;
        BlockState lastState = ctx.world.getBlockState(BlockPos.containing(lastHit.x(), lastHit.y(), lastHit.z()));
        traceContext.trace(origin, target);
        final var itr = new ReusableRaycastIterator(traceContext);
        final int segments = occlusionSegments();
        for (int i = 0; i < segments; i++) {
            if (!itr.hasNext())
                break;
            final var result = itr.next();
            final double rayDistance = lastHit.distanceTo(result.getLocation());
            factor += (float) (getOcclusion(lastState) * rayDistance);
            lastHit = result.getLocation();
            lastState = ctx.world.getBlockState(result.getBlockPos());
        }
        return factor;
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
                weighted += ringWeight / (1F + DIFFRACTION_FALLOFF * delta);
            }
        }
        if (totalWeight <= 0F)
            return 0F;
        return EDGE_LOSS * (weighted / totalWeight);
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