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
     * Maximum number of segments to check when ray tracing for occlusion.
     */
    private static final int OCCLUSION_SEGMENTS = 5;
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
     * Waypoints per detour ring. 8 samples the perpendicular directions (around the
     * sides, over the top, under) densely enough to find the shortest edge while
     * keeping the probe cost small.
     */
    private static final int DETOUR_SAMPLES = 8;
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
        // A source inside a solid block keeps its own position as the reverb ray starting
        // point. Offsetting it toward the player moves the rays off the source's centre, so
        // the shared-airspace part of the reverb becomes direction-dependent as the player
        // circles the source (reverbDC above still varied 0.4-3.3 even after the occlusion
        // rays were averaged). Keeping the starting point at the symmetric centre keeps both
        // the occlusion fan and the reverb rays symmetric.
        final Vec3 soundPos;
        if (isSolidBlock(ctx.world, sourcePos))
            soundPos = sourcePos;
        else
            soundPos = offsetPositionIfSolid(ctx.world, sourcePos, ctx.playerEyePosition);

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

        float directCutoff = (float) MathStuff.exp(sendCoeff);

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
            directCutoff = applyDiffraction(ctx, soundPos, ctx.playerEyePosition, directCutoff, snap, reverb);
        } else {
            // Direct line is clear (or occlusion is skipped): ease the compensation
            // back to zero so leaving a shadow fades rather than pops.
            this.source.smoothDiffraction(0F, snap);
        }

        float directGain = (float) MathStuff.pow(directCutoff, 0.1);

        finalizeSendGains(reverb);

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

        uploadSettings(reverb, directCutoff, directGain, waterFactor, waterGainFactor, airAbsorptionFactor);
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
            float directCutoff, final boolean snap, final ReverbTrace reverb) {
        final float geometricDiffraction = calculateDiffraction(ctx, soundPos, listener, this.lastOccluderPos);
        // Auxiliary factor: a wide obstacle still has a reachable edge, but the
        // long detour makes the geometric falloff inaudible. Floor it (only when an
        // edge was actually found) so a large-but-open obstacle stays faintly
        // audible; a buried source / sealed player is still muted by the gates
        // below. With no edge (diffraction 0) the floor must NOT lift it.
        final float diffraction = geometricDiffraction > 0F ? Math.max(geometricDiffraction, DIFFRACTION_FLOOR) : 0F;
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
        directCutoff = Math.max(smoothedComp, directCutoff);
        final float reverbComp = smoothedComp * DIFFRACTION_REVERB_SCALE;
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
    private void uploadSettings(final ReverbTrace reverb, final float directCutoff, final float directGain,
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
            direct.gainHF = directCutoff * waterFactor;
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
        // a small fraction of the reverb trace (32x4).
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
        float factor = traceOcclusion(ctx, rayOrigin, target, centerOccluder);
        this.lastCenterOcclusion = factor;
        this.lastOccluderPos = centerOccluder[0];
        int rays = 1;

        final Vec3 dir = target.subtract(origin).normalize();
        // Two orthogonal axes perpendicular to the bearing; the fan targets are points in
        // that plane around the player's eye. Use the world Y axis (or X if the bearing is
        // nearly vertical) to seed the cross product so the axes stay well-defined.
        final Vec3 seed = Math.abs(dir.y()) < 0.9D ? new Vec3(0D, 1D, 0D) : new Vec3(1D, 0D, 0D);
        final Vec3 axis1 = dir.cross(seed).normalize();
        final Vec3 axis2 = dir.cross(axis1).normalize();
        for (final float s : new float[]{-1F, 1F}) {
            factor += traceOcclusion(ctx, rayOrigin, target.add(axis1.scale(0.6F * s)), null);
            factor += traceOcclusion(ctx, rayOrigin, target.add(axis2.scale(0.6F * s)), null);
            rays += 2;
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
        for (int i = 0; i < OCCLUSION_SEGMENTS; i++) {
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

        final ReusableRaycastContext traceContext = new ReusableRaycastContext(ctx.world, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        for (final float radius : DETOUR_RADII) {
            float bestDelta = Float.POSITIVE_INFINITY;
            for (int k = 0; k < DETOUR_SAMPLES; k++) {
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
                if (delta < bestDelta)
                    bestDelta = delta;
            }
            if (bestDelta < Float.POSITIVE_INFINITY)
                return EDGE_LOSS / (1F + DIFFRACTION_FALLOFF * bestDelta);
        }
        return 0F;
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

    private static boolean isMiss(@Nullable final BlockHitResult result) {
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    private static boolean skipOcclusion(SoundSource category) {
        return !CONFIG.enableOcclusionProcessing
                || category == SoundSource.MASTER
                || category == SoundSource.MUSIC;
    }

}