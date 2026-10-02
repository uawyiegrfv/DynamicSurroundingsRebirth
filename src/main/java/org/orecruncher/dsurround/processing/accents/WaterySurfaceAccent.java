package org.orecruncher.dsurround.processing.accents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.tags.BlockEffectTags;

class WaterySurfaceAccent implements IFootstepAccentProvider {

    private static final Identifier WETSURFACE_FACTORY = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "footstep/wetsurface");

    /**
     * Multiplier on the wet-surface recording's configured volume when the
     * ground is as wet as it can get.
     *
     * <p>Written as a multiplier and not as a volume on purpose: 0.45 in the
     * data file stays the one knob for "how loud is a splash", and this curve
     * only says how much of it the weather has earned. The line runs from
     * {@link #WET_STEP_SCALE_MIN} up to this.</p>
     *
     * <p>Raised from 1.35 after the first listen (2026-10-02). It used to cross
     * 1.0 at the wetness half-intensity rain settles at, which held the level
     * the maintainer had approved - and that turned out to hold it too low: the
     * whole line sat below the flat 0.45 the accent used to play at. At 2.0 the
     * range is about 3 dB louder throughout, and the top gains a little more
     * than the bottom because the top was the end that came out quietest.</p>
     *
     * <p>2.0 is also the ceiling: the data file says 0.45, so anything above
     * this starts clipping. Louder than this means raising the 0.45, not this.</p>
     */
    private static final float WET_STEP_SCALE_MAX = 2.0F;

    /**
     * The other end: a ground that has only just started taking water. Well
     * below 1 because "it has started raining" is not "there is water", and
     * a full-volume splash out of a dry surface is the artefact this is fixing.
     */
    private static final float WET_STEP_SCALE_MIN = 0.06F;

    /**
     * Below this wetness there is no splash at all rather than a quiet one. The
     * difference matters: a very quiet splash still reads as "the ground is
     * damp", and at the start of a storm it is not, it is dry.
     *
     * <p>0.12 on the SPLASH curve is a steady intensity of about 0.04 - in other
     * words, rain you would have to look for. It is also what buys the delay:
     * the rise is exponential, so it climbs fastest at the very start and would
     * put a first splash under a second after the rain began. Only the
     * threshold keeps that from happening, and it is the knob to turn if the
     * delay wants to be longer or shorter.</p>
     */
    private static final float WET_STEP_MIN_WETNESS = 0.12F;

    private final Configuration config;

    WaterySurfaceAccent(Configuration config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return this.config.footstepAccents.enableWetSurfaceAccents;
    }

    @Override
    public void collect(LivingEntity entity, BlockPos pos, BlockState state, boolean isWaterLogged, ObjectArray<ISoundFactory> acoustics) {

        // A surface is wet in one of two ways, and only one of them has anything
        // to do with the weather.
        //
        // Wet by construction: a waterlogged block, or one the WATERY_STEP tag
        // flags as holding water - a lily pad, say. Whether it is raining is
        // irrelevant to it, so it splashes at the level the data file declares
        // whether or not a cloud has ever been near it.
        boolean byConstruction = isWaterLogged;

        if (!byConstruction)
            byConstruction = FootstepAccents.TAG_LIBRARY.is(BlockEffectTags.WATERY_STEP, state);

        // Check the block above because it may be flagged as having a wet effect, like a lily pad.
        if (!byConstruction) {
            var world = entity.level();
            var up = pos.above();
            byConstruction = FootstepAccents.TAG_LIBRARY.is(BlockEffectTags.WATERY_STEP, world.getBlockState(up));

            // Wet because rain is falling on it. This is the case that has to
            // wait for the water - see addRainWet().
            //
            // A lock is a hypothesis, not a weather report. /dsprecip lock 0.5
            // means "assume it is raining this hard"; asking the vanilla
            // weather instead leaves this accent silent for the whole lock,
            // which is the one situation the lock exists to create - the curves
            // could then never be compared at two levels. The biome still has
            // to be a rainy one, so a lock in a desert stays dry.
            final boolean locked = PrecipitationIntensity.forced() >= 0F
                    || PrecipitationResponse.WET.isLocked();
            if (!byConstruction && (locked || world.isRainingAt(up))) {
                // Get the precipitation type at the location
                var precipitation = world.precipitationAt(up);
                if (precipitation == Biome.Precipitation.RAIN) {
                    this.addRainWet(acoustics);
                    return;
                }
            }
        }

        if (byConstruction)
            this.addWetSurface(acoustics, 1F);
    }

    /**
     * Adds the wet-surface recording at a multiplier on the volume its data file
     * declares.
     */
    private void addWetSurface(ObjectArray<ISoundFactory> acoustics, float volumeScale) {
        SOUND_LIBRARY.getSoundFactory(WETSURFACE_FACTORY)
                .ifPresent(factory -> acoustics.add(factory.scaled(volumeScale)));
    }

    /**
     * The rain-wet case, and the reason this provider grew a second path.
     *
     * <p>1.12.2 scaled this very recording - {@code RainSplashAcoustic} set
     * {@code volumeScale = intensity * 0.8F} - but it read the intensity live,
     * so the first footstep of a drizzle splashed at nearly full puddle volume
     * out of ground that was still dry. How loud a splash is depends on how much
     * water has gathered, which is what {@code PrecipitationResponse.WET}
     * supplies: the same shaped curve the ground splash density uses, delayed
     * until the water is actually there.</p>
     *
     * <p>Below {@link #WET_STEP_MIN_WETNESS} nothing is added at all. A very
     * quiet splash would still say "the ground is damp", and at that point it
     * is not.</p>
     */
    private void addRainWet(ObjectArray<ISoundFactory> acoustics) {
        // Graded precipitation switched off means vanilla behaviour, and the
        // vanilla wet-surface accent is one fixed level. Following our curve
        // while the player has turned the curve off is the same mistake the
        // ambient consumers made before the split.
        if (!PrecipitationIntensity.grading()) {
            this.addWetSurface(acoustics, 1F);
            return;
        }

        final float wetness = PrecipitationResponse.WET.value();
        if (wetness < WET_STEP_MIN_WETNESS)
            return;

        this.addWetSurface(acoustics,
                WET_STEP_SCALE_MIN + (WET_STEP_SCALE_MAX - WET_STEP_SCALE_MIN) * wetness);
    }
}