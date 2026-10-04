package org.orecruncher.dsurround.processing.weather;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

/**
 * 26.1's sky, cloud and sky-brightness route.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every other version reaches the weather-driven colours by redirecting the
 * rain level <em>inside</em> {@code ClientLevel.getSkyColor} and friends. On
 * 26.1 none of those methods exist any more; the colours are precomputed into a
 * {@code LevelRenderState} and NeoForge fires
 * {@code ExtractLevelRenderStateEvent} after vanilla has filled it.</p>
 *
 * <h2>Vanilla has already tinted these colours</h2>
 *
 * <p>The decompiled pipeline tints {@code SKY_COLOR} / {@code CLOUD_COLOR}
 * <em>inside the attribute system</em> (WeatherAttributes RAIN/THUNDER maps,
 * applied while the state is extracted, before this event fires). So the colour
 * this event receives is <em>already weather-blended with vanilla's levels</em>.
 * Re-applying our blend on top would darken twice - which is what the first
 * version of this class did.</p>
 *
 * <p>Instead: undo vanilla's blend (it is two per-channel linear passes towards
 * a fixed fraction of the running luminance, so it inverts in closed form), ease
 * the untinted base colour across biome boundaries, then apply the same blend
 * with <em>our</em> levels. When our levels equal vanilla's the round trip
 * reproduces the input, and the early return below makes that exact.</p>
 */
public final class SkyStateInjector {

    private SkyStateInjector() {
    }

    // Decompiled WeatherAttributes BlendToGray(brightness, factor) arguments:
    // SKY_COLOR   rain (0.6, 0.75)   thunder (0.24, 0.94)
    // CLOUD_COLOR rain (0.24, 0.5)   thunder (0.095, 0.94)
    private static final float SKY_RAIN_BRIGHT = 0.6F;
    private static final float SKY_RAIN_FACTOR = 0.75F;
    private static final float SKY_THUNDER_BRIGHT = 0.24F;
    private static final float SKY_THUNDER_FACTOR = 0.94F;
    private static final float CLOUD_RAIN_BRIGHT = 0.24F;
    private static final float CLOUD_RAIN_FACTOR = 0.5F;
    private static final float CLOUD_THUNDER_BRIGHT = 0.095F;
    private static final float CLOUD_THUNDER_FACTOR = 0.94F;

    // Decompiled WeatherAttributes, FOG_COLOR - a different modifier than the
    // sky and cloud routes: MULTIPLY_RGB, not BLEND_TO_GRAY. Vanilla multiplies
    // the fog by (0.5, 0.5, 0.6) over the plain rain level and by
    // (0.25, 0.25, 0.3) over the thunder level, each lerped in by its own
    // level. Because the passes are per-channel multiplications they invert by
    // plain division, and they commute with the day/night multiplier that sits
    // between the weather layers and the final colour - so a round trip on the
    // final colour is exact at any time of day.
    private static final float FOG_RAIN_R = 0.5F;
    private static final float FOG_RAIN_G = 0.5F;
    private static final float FOG_RAIN_B = 0.6F;
    private static final float FOG_THUNDER_R = 0.25F;
    private static final float FOG_THUNDER_G = 0.25F;
    private static final float FOG_THUNDER_B = 0.3F;

    /**
     * Both colours are ARGB, not RGB: vanilla fills {@code cloudColor} from
     * {@code EnvironmentAttributes.CLOUD_COLOR}, an {@code Integer}, and the
     * cloud pass reads the top byte. Writing back a value with the alpha cleared
     * makes the clouds fully transparent - they vanish outright while everything
     * else looks right, because the sky is a background quad and never consults
     * its alpha. Every pack below has to carry the original alpha through.
     */
    private static final int ALPHA_MASK = 0xFF000000;

    /** Per frame, as a fraction of the remaining distance. ~0.4s to settle. */
    private static final float BASE_TRANSITION = 0.12F;
    private static float skyR = -1F;
    private static float skyG = 0F;
    private static float skyB = 0F;
    private static float cloudR = -1F;
    private static float cloudG = 0F;
    private static float cloudB = 0F;
    private static long baseStamp = Long.MIN_VALUE;
    /** Throttle for the [SKY-26] sample - this fires every frame. */
    private static int probeCounter = 0;

    public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        final ClientLevel level = event.getLevel();
        if (level == null)
            return;

        var state = event.getRenderState();
        var sky = state.skyRenderState;
        if (sky == null)
            return;

        try {
            final float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            // Read once: the thunder route is shared by the sky and the clouds, and
            // two calls could bracket a tick boundary.
            final float ourThunder = PrecipitationIntensity.stormRainLevel(level, partialTick);
            final float vanillaRain = level.getRainLevel(partialTick);
            final float vanillaThunder = level.getThunderLevel(partialTick);
            // Vanilla splits its rain level: thunder is counted as thunder, the
            // rest as rain (WeatherAttributes.addLayer). Mirroring the split is
            // what makes the not-owning path exact.
            final float vanillaPlain = Math.max(0F, vanillaRain - vanillaThunder);
            final float ourPlain = Math.max(0F,
                    PrecipitationIntensity.skyColorRainLevel(level, partialTick) - ourThunder);
            // The CLOUD route gets its own level, exactly like the other two
            // versions: their cloud colour read point is
            // max(CLOUD, OVERCAST x OVERCAST_CLOUD_GRAY), which is precisely
            // what PrecipitationIntensity.cloudColorRainLevel returns - the
            // overcast widening rides inside it. Feeding the cloud retint from
            // the sky's plain value instead put the clouds on the SKY channel's
            // clock, so 26.1's rain clouds tinted at the wrong speed whenever
            // the CLOUD and SKY channels diverged (different attack/decay).
            // Subtracting ourThunder mirrors the sky route: the retint applies
            // thunder as its own pass, so the plain value must not carry it.
            final float ourPlainCloud = Math.max(0F,
                    PrecipitationIntensity.cloudColorRainLevel(level, partialTick) - ourThunder);

            // Vanilla smooths the biome FOG across a boundary but never smooths the
            // sky or cloud base colours: those are sampled per block and step the
            // moment the camera crosses into another biome. Advanced once per
            // frame, not per call, so the speed cannot depend on how many callers
            // ask in a given frame.
            final long stamp = level.getGameTime() * 1000L + (long) (partialTick * 1000F);
            final boolean advance = stamp != baseStamp;
            if (advance)
                baseStamp = stamp;

            // The sun/moon/star alpha and sky brightness in one number, exactly as
            // vanilla's SkyRenderer writes it: 1 - rainLevel. When we are not
            // taking over this reproduces vanilla's own value.
            sky.rainBrightness = 1F - PrecipitationIntensity.sunAlphaRainLevel(level, partialTick);

            // The dry-weather sky events must also run on the bit-for-bit-vanilla
            // path below - a cloudless dry day is exactly when that early return
            // fires, and exactly when the events exist - so both paths end with
            // the same applySkyEvents call.
            if (ourPlain == vanillaPlain && ourThunder == vanillaThunder) {
                applySkyEvents(sky, state);
                return; // bit-for-bit vanilla (the events aside) - do not touch the colours
            }

            PrecipitationIntensity.noteSkyColorHook();
            sky.skyColor = retint(sky.skyColor,
                    SKY_RAIN_BRIGHT, SKY_RAIN_FACTOR, SKY_THUNDER_BRIGHT, SKY_THUNDER_FACTOR,
                    vanillaPlain, vanillaThunder, ourPlain, ourThunder, advance, true);

            PrecipitationIntensity.noteCloudColorHook();
            state.cloudColor = retint(state.cloudColor,
                    CLOUD_RAIN_BRIGHT, CLOUD_RAIN_FACTOR, CLOUD_THUNDER_BRIGHT, CLOUD_THUNDER_FACTOR,
                    vanillaPlain, vanillaThunder, ourPlainCloud, ourThunder, advance, false);

            applySkyEvents(sky, state);

            // 26.1 has no biomeColorDetail probe and no getSkyColor to hang one on,
            // so this is the only way to see what the version actually rendered.
            if (++probeCounter >= 100) {
                probeCounter = 0;
                PrecipitationIntensity.diagLog(
                        "[SKY-26] sky=%06X cloud=%06X rainBrightness=%.3f rain=%.3f storm=%.3f",
                        sky.skyColor, state.cloudColor, sky.rainBrightness,
                        PrecipitationIntensity.skyColorRainLevel(level, partialTick), ourThunder);
            }
        } catch (final Throwable t) {
            // Extract runs on the frame path; a throw here would take the frame
            // down. Degrade to vanilla instead - the state was already filled.
            org.orecruncher.dsurround.mixinutils.MixinHelpers.LOGGER
                    .error(t, "SkyStateInjector failed - leaving vanilla render state");
        }
    }

    /**
     * The dry-weather sky events, applied after every other colour decision on
     * BOTH paths out of the extractor (the bit-for-bit-vanilla early return
     * included - a cloudless dry day is exactly when that fires and exactly
     * when the events exist).
     *
     * <p>The overcast runs on the final sky RGB for the same reason its 1.20.1
     * and 1.21.1 implementations do: vanilla's rain blend targets
     * {@code luma * factor}, which is dark by construction, so "sky going
     * white" can only be expressed on the colour that has already been through
     * every darkening pass. The clear event is version-specific: this
     * version's cloud colour is ARGB and the cloud pass itself refuses to draw
     * when the alpha is zero, so the fade is a native alpha interpolation
     * rather than the colour melt the other two versions use.</p>
     */
    private static void applySkyEvents(SkyRenderState sky, LevelRenderState state) {
        final float overcast = SkyEventScheduler.whiteShiftStrength();
        if (overcast > 0F) {
            final int argb = sky.skyColor;
            final float r = (argb >> 16 & 0xFF) / 255F;
            final float g = (argb >> 8 & 0xFF) / 255F;
            final float b = (argb & 0xFF) / 255F;
            final float luma = r * 0.3F + g * 0.59F + b * 0.11F;
            sky.skyColor = (argb & ALPHA_MASK) | pack(
                    r + (SkyEventScheduler.whiteTarget(r, luma) - r) * overcast,
                    g + (SkyEventScheduler.whiteTarget(g, luma) - g) * overcast,
                    b + (SkyEventScheduler.whiteTarget(b, luma) - b) * overcast);
        }
        final float clear = SkyEventScheduler.cloudMeltStrength();
        if (clear > 0F) {
            // ★ The alpha write below is INTENTIONAL and is NOT a regression of
            // the §5.9 bug ("26.1 云被弄没了 - ARGB 的 alpha 被清零"). That bug
            // cleared the alpha by accident while writing RGB; this writes the
            // alpha on purpose, driven by the CLEAR channel, because
            // LevelRenderer only draws the cloud pass when
            // ARGB.alpha(cloudColor) > 0 - alpha is this version's cloud
            // on/off switch. Keep the RGB bytes exactly as retint produced them.
            final int alpha = (int) (255F * (1F - clear) + 0.5F) << 24;
            state.cloudColor = (alpha & 0xFF000000) | (state.cloudColor & 0x00FFFFFF);
        }
    }

    /**
     * Undoes vanilla's weather blend on the fog colour and re-applies it with
     * our levels - the fog-side sibling of {@link #retint}, in the multiplier
     * form the FOG_COLOR attribute uses (see the FOG_* constants above).
     *
     * <p>The caller hands us the colour the ComputeFogColor event received,
     * which already carries vanilla's own rain and thunder blends. When we are
     * grading the weather ourselves our level differs from vanilla's, and the
     * fog would then disagree with the sky we just fixed - most visible as a
     * graded drizzle whose horizon still sits at vanilla's full-storm grey.
     * When our levels equal vanilla's the caller skips this entirely, so the
     * vanilla path stays bit-for-bit.</p>
     *
     * <p>Empirically confirmed against the [FOG-26] probe: a full storm
     * delivered {@code biomeFog x (0.25, 0.25, 0.3)} exactly, i.e. the plain
     * rain term was zero because vanilla's own split counts a full storm as
     * thunder with no plain rain left over.</p>
     *
     * @return the re-applied colour, 0..1 per channel in a 3-element array
     */
    public static float[] fogWeatherRoundTrip(float r, float g, float b,
                                              float vanillaPlain, float vanillaThunder,
                                              float ourPlain, float ourThunder) {
        // ---- undo vanilla's passes: thunder first, then plain rain ----
        r = divideOutMultiplier(r, vanillaThunder, FOG_THUNDER_R);
        g = divideOutMultiplier(g, vanillaThunder, FOG_THUNDER_G);
        b = divideOutMultiplier(b, vanillaThunder, FOG_THUNDER_B);
        r = divideOutMultiplier(r, vanillaPlain, FOG_RAIN_R);
        g = divideOutMultiplier(g, vanillaPlain, FOG_RAIN_G);
        b = divideOutMultiplier(b, vanillaPlain, FOG_RAIN_B);

        // ---- re-apply with our levels: plain rain first, then thunder ----
        r = multiplyInMultiplier(r, ourPlain, FOG_RAIN_R);
        g = multiplyInMultiplier(g, ourPlain, FOG_RAIN_G);
        b = multiplyInMultiplier(b, ourPlain, FOG_RAIN_B);
        r = multiplyInMultiplier(r, ourThunder, FOG_THUNDER_R);
        g = multiplyInMultiplier(g, ourThunder, FOG_THUNDER_G);
        b = multiplyInMultiplier(b, ourThunder, FOG_THUNDER_B);
        return new float[] { r, g, b };
    }

    /** One MULTIPLY_RGB pass multiplies by {@code lerp(1, mult, level)} - undo it. */
    private static float divideOutMultiplier(float c, float level, float mult) {
        if (level <= 0F)
            return c;
        final float d = 1F + (mult - 1F) * level;
        return d < 0.1F ? c : c / d;
    }

    /** Re-applies one MULTIPLY_RGB pass. */
    private static float multiplyInMultiplier(float c, float level, float mult) {
        if (level <= 0F)
            return c;
        return c * (1F + (mult - 1F) * level);
    }

    /**
     * Undo vanilla's weather blend (computed with vanilla's levels), ease the
     * untinted base, then blend with ours. Each pass is
     * {@code c' = (1-p)*c + p*bright*lum(c)} applied by the amount
     * {@code p = factor * level}; the luminance is recomputed between passes,
     * which is what makes the inverse two steps rather than one.
     */
    private static int retint(int argb,
                              float rainBright, float rainFactor,
                              float thunderBright, float thunderFactor,
                              float vanillaPlain, float vanillaThunder,
                              float ourPlain, float ourThunder,
                              boolean advance, boolean isSky) {
        float r = (argb >> 16 & 0xFF) / 255F;
        float g = (argb >> 8 & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;

        // ---- invert pass 2 (thunder), then pass 1 (rain) ----
        final float p2 = thunderFactor * vanillaThunder;
        final float q2 = thunderFactor * thunderBright * vanillaThunder;
        final float d2 = 1F - p2 * (1F - thunderBright);
        final float p1 = rainFactor * vanillaPlain;
        final float q1 = rainFactor * rainBright * vanillaPlain;
        final float d1 = 1F - p1 * (1F - rainBright);
        if (d1 < 0.1F || d2 < 0.1F)
            return argb; // defensively leave vanilla alone
        final float l1 = luminance(r, g, b) / d2;
        r = (r - q2 * l1) / d2;
        g = (g - q2 * l1) / d2;
        b = (b - q2 * l1) / d2;
        final float l0 = luminance(r, g, b) / d1;
        r = (r - q1 * l0) / d1;
        g = (g - q1 * l0) / d1;
        b = (b - q1 * l0) / d1;

        // ---- ease the untinted base (biome-boundary smoothing) ----
        final int base = transition((argb & ALPHA_MASK) | pack(r, g, b), advance, isSky);
        r = (base >> 16 & 0xFF) / 255F;
        g = (base >> 8 & 0xFF) / 255F;
        b = (base & 0xFF) / 255F;

        // ---- apply the same blend with our levels ----
        if (ourPlain > 0F) {
            final float p = rainFactor * ourPlain;
            final float q = rainFactor * rainBright * ourPlain;
            final float lum = luminance(r, g, b);
            r = r * (1F - p) + q * lum;
            g = g * (1F - p) + q * lum;
            b = b * (1F - p) + q * lum;
        }
        if (ourThunder > 0F) {
            final float p = thunderFactor * ourThunder;
            final float q = thunderFactor * thunderBright * ourThunder;
            final float lum = luminance(r, g, b);
            r = r * (1F - p) + q * lum;
            g = g * (1F - p) + q * lum;
            b = b * (1F - p) + q * lum;
        }

        return (argb & ALPHA_MASK) | pack(r, g, b);
    }

    /**
     * Eases one base colour towards its new value, once per frame, and returns
     * it packed. {@code sky} selects which of the two held colours to use.
     */
    private static int transition(int argb, boolean advance, boolean isSky) {
        final float r = (argb >> 16 & 0xFF) / 255F;
        final float g = (argb >> 8 & 0xFF) / 255F;
        final float b = (argb & 0xFF) / 255F;

        if (isSky && skyR < 0F) {
            skyR = r;
            skyG = g;
            skyB = b;
        } else if (!isSky && cloudR < 0F) {
            cloudR = r;
            cloudG = g;
            cloudB = b;
        } else if (advance) {
            if (isSky) {
                skyR += (r - skyR) * BASE_TRANSITION;
                skyG += (g - skyG) * BASE_TRANSITION;
                skyB += (b - skyB) * BASE_TRANSITION;
            } else {
                cloudR += (r - cloudR) * BASE_TRANSITION;
                cloudG += (g - cloudG) * BASE_TRANSITION;
                cloudB += (b - cloudB) * BASE_TRANSITION;
            }
        }

        // The alpha is the caller's, not ours - see ALPHA_MASK.
        return (argb & ALPHA_MASK) | (isSky ? pack(skyR, skyG, skyB) : pack(cloudR, cloudG, cloudB));
    }

    private static int pack(float r, float g, float b) {
        return ((int) (r * 255F) & 0xFF) << 16
                | ((int) (g * 255F) & 0xFF) << 8
                | ((int) (b * 255F) & 0xFF);
    }

    private static float luminance(float r, float g, float b) {
        return r * 0.3F + g * 0.59F + b * 0.11F;
    }
}
