package org.orecruncher.dsurround.processing.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

import java.util.Random;

/**
 * Graded rain and snow rendering (N4) - the 1.12.2 StormRenderer ported.
 *
 * <h2>Why we take over instead of layering</h2>
 *
 * <p>1.12.2 replaced the dimension's weather renderer outright
 * ({@code RenderWeather extends IRenderHandler}, installed through
 * {@code WorldProvider.setWeatherRenderer()}). We do the same thing in spirit:
 * vanilla rain and snow are suppressed and ours are drawn in their place. Vanilla
 * always draws the <em>same number</em> of rain columns and only scales alpha by
 * the rain level, so layering on top of it cannot express intensity - it would
 * just draw two rains.</p>
 *
 * <h2>How the takeover is wired</h2>
 *
 * <p>The suppression hook ({@code renderSnowAndRain}) only answers "vanilla, don't
 * bother" and draws nothing. The geometry is drawn from the
 * {@code AFTER_WEATHER} level stage instead - the same path the sandstorm veil
 * already uses and the same point in the frame vanilla would have drawn at. Two
 * reasons for the split: the stage event fires unconditionally, so a suppressed
 * vanilla pass cannot take our draw down with it, and it keeps one drawing
 * implementation across all three versions instead of three.</p>
 *
 * <p>{@code tickRain} is suppressed - we return false, which drops vanilla's
 * ground splash particles <em>and</em> its rain sound. Neither has a replacement
 * yet, so both are currently <em>absent</em>, not vanilla. Do not read this as
 * "vanilla handles it".</p>
 *
 * <h2>The 1.12.2 algorithm</h2>
 *
 * <p>Per-block-column in a +/-range grid around the player, one vertical quad
 * whose cross-section is the unit vector perpendicular to the player->column
 * radius scaled to {@link #STRIP_HALF_WIDTH} - the columns together read as
 * slanted curtains sweeping around the player. Rain and snow are
 * <em>different animations</em>, which is easy to get wrong by copying one to the
 * other:</p>
 *
 * <pre>
 *   RAIN: d5  = (((ticks + seed) &amp; 31) + partialTick) / 32.0 * (3.0 + random.nextDouble())
 *         v   = y * 0.25 + d5                 // fast fall, per-column rate
 *         alpha = ((1 - f3^2) * 0.5 + 0.5) * alphaRatio
 *         light = combinedLight               // used as-is
 *
 *   SNOW: d8  = ((ticks &amp; 511) + partialTick) / 512.0      // slow 512-tick scroll
 *         d9  = random.nextDouble() + f1 * 0.01 * random.nextGaussian()   // U shear
 *         d10 = random.nextDouble() + f1 * random.nextGaussian() * 0.001  // V shear
 *         v   = y * 0.25 + d8 + d10
 *         alpha = ((1 - f3^2) * 0.3 + 0.5) * alphaRatio
 *         light = combinedLight               // vanilla: no brightening
 *                (1.12.2 brightened snow here; modern vanilla does not)
 * </pre>
 *
 * <p>Note the snow drift factor is {@code 0.01}, not the {@code 0.2} the dust
 * veil uses - dust was widened on purpose, snow must not be.</p>
 *
 * <h2>Where intensity enters</h2>
 *
 * <ul>
 *   <li><b>Texture</b> - {@code Weather.Properties.mapRainStrength(intensity)},
 *       evaluated on the <em>current ramping</em> value (1.12.2
 *       {@code setCurrentIntensity}), so a storm visibly escalates calm ->
 *       light -> ... as it builds and de-escalates as it stops.</li>
 *   <li><b>Alpha</b> - {@code alphaRatio = intensity / peak}, 1.12.2's
 *       {@code rainStrength / getMaxIntensityLevel()}.</li>
 * </ul>
 *
 * <p>The per-column phase (rain vs snow vs nothing) comes from
 * {@code PrecipitationIntensity.phase}, which delegates to Serene Seasons, so
 * snow on a peak next to rain in the valley - and no rain under a roof - come for
 * free. Phases are cached per column for {@link #CACHE_TTL} ticks because the
 * query does a biome lookup and a heightmap read.</p>
 */
public final class PrecipitationRenderer {

    private static final String MOD_ID = "dsurround";

    /**
     * Half-width of a column's strip in blocks. 1.12.2 uses 0.5, which makes
     * neighbouring strips exactly touch - a continuous curtain. This is the density
     * knob; the dust veil runs at 0.9 because the sparse dust specks read as too
     * thin at 0.5, but rain streaks tile fine at the 1.12.2 value.
     */
    private static final float STRIP_HALF_WIDTH = 0.5F;

    /** Intensity band a curtain fades in over. Without it a curtain pops in at full
     * alpha the instant the intensity passes its rank, which reads as flicker.
     * Wide enough now that a curtain is a visible fade rather than a blink, which
     * is what lets the density channel carry the intensity on its own. */
    private static final float COLUMN_FADE_BAND = 0.12F;

    /** Curtain half-width at zero and at full intensity, as a multiple of the 1.12.2
     * 0.5 - thin threads for drizzle, wide overlapping curtains for a downpour. */
    private static final float STRIP_MIN_SCALE = 0.7F;
    private static final float STRIP_RANGE = 0.6F;

    /**
     * Intensity window over which a column grows a second, independently phased
     * streak - literally more streaks inside the same curtain.
     *
     * <p>This used to be a single threshold at 0.72, which doubled the amount of
     * geometry in one frame: the field went from one streak per column to two
     * with nothing in between, so climbing through that intensity read as a step
     * rather than as the rain getting heavier, and it stepped back down just as
     * abruptly. The extra streak now fades in over a band, so the curtain
     * density is continuous in intensity.</p>
     */
    private static final float SECOND_LAYER_FROM = 0.52F;
    private static final float SECOND_LAYER_TO = 0.88F;

    /**
     * How the opacity follows {@code intensity / peak}. Below 1 the rain becomes
     * visible early and then firms up, instead of staying invisible for the first
     * stretch of every storm: the low end is already carried by curtain count,
     * width, streaks and texture band, so a linear opacity on top pressed it
     * twice, and with the alpha cull that meant the opening seconds drew nothing
     * at all. A power curve rather than a floor, because a floor pops when the
     * intensity reaches zero.
     */
    private static final float RAIN_ALPHA_GAMMA = 0.4F;

    /** Hard cap on layers. Also the vertex buffer multiplier on 26.1. */
    private static final int MAX_LAYERS = 2;

    /**
     * <p>Which columns exist at a given intensity is decided by a <em>stratified</em>
     * rank, not by an independent per-column coin flip.</p>
     *
     * <p>The distinction is the whole ballgame. With an independent hash each
     * column is a Bernoulli trial, so the columns that survive at low intensity
     * form a Poisson cloud: they clump, and the gaps between clumps are far
     * larger than the average spacing. At 10% intensity the average spacing is
     * about three blocks but the typical worst gap is eight, which means the
     * player routinely stands in a bald patch with every remaining curtain
     * somewhere off in the distance - the rain looks lopsided rather than thin.
     * Turning the intensity down did not thin the rain evenly, it punched holes
     * in it.</p>
     *
     * <p>Stratifying fixes it. The world is cut into {@link #STRATUM}-sized tiles
     * and inside every tile the columns are ordered by an 8x8 Bayer matrix, so
     * <em>any</em> threshold keeps a share of every tile instead of a random
     * subset of the plane. Gaps are bounded by the tile size and the survivor set
     * has no direction to it.</p>
     *
     * <p>The tile itself is rotated, mirrored and phase-shifted from a hash of the
     * tile coordinates. Without that every tile would keep the same cells and the
     * Bayer ordering would show up as a visible diagonal lattice - a uniform
     * field, but an obviously artificial one.</p>
     */
    private static final int STRATUM = 8;
    private static final int STRATUM_SHIFT = 3;
    private static final int STRATUM_MASK = STRATUM - 1;

    /**
     * The rank is anchored to <b>world</b> coordinates, never to the player.
     *
     * <p>That is deliberate and it is the one thing that cannot be traded away.
     * A player-centred rank would put the pattern exactly where it is wanted,
     * but the player moves: cross one block boundary and every column's offset
     * from the player changes, so every column's rank changes and the whole
     * field re-rolls. With a fade band that reads as the rain shimmering while
     * you walk, which is far worse than an occasional bald patch. Anchoring to
     * the world keeps a column's rank fixed for as long as that column exists,
     * so columns only ever appear and disappear at the edge of the grid.</p>
     *
     * <p>The cost of that anchoring is that the player can land in a gap of the
     * pattern, which is what {@link #CENTER_BIAS} pays back.</p>
     */
    private static final float CENTER_BIAS = 0.18F;
    /** Radius, in blocks, over which the centre weighting falls off. */
    private static final float CENTER_RADIUS = 7F;
    /**
     * Intensity at which the centre weighting is fully applied. It is scaled down
     * towards zero intensity so that a storm building from nothing does not
     * appear as a small disc of rain hugging the player - at drizzle the field
     * has to arrive everywhere at once, and only once there is a field does it
     * make sense to favour the middle of it.
     */
    private static final float CENTER_BIAS_FULL_AT = 0.15F;

    /**
     * Floor on the intensity the <em>geometry</em> is thinned with. Thinning is a
     * sampling problem: below roughly twenty curtains in a 21x21 grid no scheme
     * reads as even, it reads as a few clumps in two or three directions. So below
     * this the field holds, and low intensity is carried by the continuous
     * channels (streaks per curtain, width, alpha, texture band) instead. Applied
     * to the geometry only - fog, sky and sound are scalars and go lower freely.
     */
    private static final float MIN_GEOMETRY_INTENSITY = 0.12F;

    /** 8x8 ordered-dither matrix: a spatially hierarchical permutation of 0..63. */
    private static final int[] BAYER8 = {
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
    };

    /**
     * The stratified rank of a world column, 0..1. Lower means "this column is
     * among the first to appear as the intensity rises".
     *
     * <p>Pure and dependant only on world coordinates, so a column keeps its rank
     * for as long as it is inside the grid.</p>
     */
    /** As the rain's density channel, shared with the dust veil. Public
     *  because WeatherStormHandler grades the sandstorm with the same
     *  stratified rank - see the comment there. */
    public static float columnRank(int gridX, int gridZ) {
        final int tileX = gridX >> STRATUM_SHIFT;
        final int tileZ = gridZ >> STRATUM_SHIFT;
        int localX = gridX & STRATUM_MASK;
        int localZ = gridZ & STRATUM_MASK;

        // Per-tile orientation and phase. Cheap integer hash - this runs for every
        // column of the grid every frame.
        final int tileHash = (tileX * 73856093) ^ (tileZ * 19349663);
        final int rotation = tileHash & 3;
        final int mirrored = (tileHash >>> 2) & 1;
        final int phase = (tileHash >>> 3) & 63;

        for (int i = 0; i < rotation; i++) {
            final int swap = localX;
            localX = localZ;
            localZ = STRATUM_MASK - swap;
        }
        if (mirrored == 1)
            localX = STRATUM_MASK - localX;

        return (float) ((BAYER8[localZ * STRATUM + localX] + phase) & 63) / 64F;
    }

    /**
     * Per-column cross-section table, 1.12.2 {@code StormRenderer.RAIN_X_COORDS} /
     * {@code RAIN_Y_COORDS}. Indexed {@code (dz + 16) * 32 + (dx + 16)}. The centre
     * column is a 0/0 division in 1.12.2 and would be NaN, so it falls back to a
     * fixed half-width - otherwise the column under the player never draws.
     */
    private static final float[] STRIP_X = new float[1024];
    private static final float[] STRIP_Y = new float[1024];

    static {
        for (int i = 0; i < 32; ++i) {
            for (int j = 0; j < 32; ++j) {
                final float dx = j - 16F;
                final float dz = i - 16F;
                final float r = (float) Math.sqrt(dx * dx + dz * dz);
                final int idx = i << 5 | j;
                if (r < 0.0001F) {
                    STRIP_X[idx] = STRIP_HALF_WIDTH;
                    STRIP_Y[idx] = 0F;
                } else {
                    STRIP_X[idx] = -dz / r * STRIP_HALF_WIDTH;
                    STRIP_Y[idx] = dx / r * STRIP_HALF_WIDTH;
                }
            }
        }
    }

    /** 1.12.2 {@code Weather.Properties} levels, in ascending order. */
    private static final float[] LEVELS = { 0.125F, 0.25F, 0.365F, 0.5F, 0.625F, 0.75F, 0.875F, 1.0F };
    private static final String[] LEVEL_NAMES = {
            "calm", "light", "gentle", "moderate", "heavy", "strong", "intense", "torrential"
    };

    private static final ResourceLocation[] RAIN_TEXTURES = build("rain");
    private static final ResourceLocation[] SNOW_TEXTURES = build("snow");

    private static ResourceLocation[] build(String kind) {
        var result = new ResourceLocation[LEVEL_NAMES.length];
        for (int i = 0; i < LEVEL_NAMES.length; i++)
            result[i] = ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/environment/" + kind + "_" + LEVEL_NAMES[i] + ".png");
        return result;
    }

    /**
     * Grid radius in blocks. 1.12.2 ties this to fancy graphics (10 fast / 5
     * fancy); the dust veil already runs a fixed 10 on 1.21.1 and 26.1, so the
     * precipitation grid matches it instead of diverging per version.
     */
    private static final int RANGE = 10;

    private static final long CACHE_TTL = 40L;

    private static final Random columnRandom = new Random();
    private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> surfaceCache =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap phaseCache =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightCache =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static long cacheTick = Long.MIN_VALUE;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> cacheDimension;

    private PrecipitationRenderer() {
    }

    /** Registers the game-bus listener. Called from the mod constructor. */
    public static void bootstrap() {
        NeoForge.EVENT_BUS.addListener(PrecipitationRenderer::onRenderLevelStage);
    }

    /**
     * Mod-bus: installs the overworld effects object whose only job is to tell
     * vanilla not to draw rain. Everything else (sky, clouds, fog colour, sun) is
     * inherited from {@link DimensionSpecialEffects.OverworldEffects}.
     */
    public static void onRegisterDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ResourceLocation.withDefaultNamespace("overworld"), new GradedPrecipitationEffects());
        note("registered dimension effects for overworld");
    }

    /**
     * Whether we draw precipitation this frame. Also the takeover condition: when
     * this is false vanilla keeps drawing, so any failure mode degrades to vanilla
     * behaviour rather than to a world with no rain at all.
     */
    private static volatile boolean lastActive = false;
    /** How many times the suppression hook has been consulted. Stays 0 if the hook
     * was never installed (wrong registration key, or another mod won the slot). */
    private static volatile int hookCalls = 0;
    /** How many times our own draw stage fired. See {@link #renderCalls()}. */
    private static volatile int renderCalls = 0;

    /**
     * How many times vanilla asked {@code tickRain} - the ground splash particle
     * and rain sound pass.
     *
     * <p>This exists to settle one specific disagreement. We return false from
     * {@code tickRain}, which by the bytecode should suppress vanilla's splash
     * particles <em>and</em> its rain sound outright. Maintainer testing reported
     * both still present and unvarying. Three explanations fit, and this counter
     * separates them:</p>
     *
     * <ul>
     * <li>{@code tick=0} while {@code hook>0} - our effects object never reaches
     *     this path at all, so the suppression was never applied.</li>
     * <li>{@code tick=0} and {@code hook=0} - another mod owns the effects slot.</li>
     * <li>{@code tick>0} - we are being asked and are declining, so anything on
     *     screen is coming from somewhere else (or the report was about the
     *     curtains, not the splashes).</li>
     * </ul>
     */
    private static volatile int tickHooks = 0;

    private static boolean active() {
        boolean now = false;
        if (!broken) {
            var config = Client.Config;
            // A diagnostics lock bypasses the config switch on purpose: the whole point
            // of /dsprecip lock is to answer "does the chain work at all", and answering
            // that must not depend on a toggle the user may have turned off. It is the
            // only path that can draw while the feature is disabled.
            final boolean locked = PrecipitationIntensity.forced() >= 0F;
            final boolean enabled = locked
                    || (config != null && config.weatherOptions.enableGradedPrecipitation);
            now = enabled && PrecipitationIntensity.takeoverReady();
        }
        if (now != lastActive) {
            lastActive = now;
            note("takeover=%s intensity=%.3f peak=%.3f hookCalls=%d tickHooks=%d renderCalls=%d config=%s locked=%s broken=%s",
                    now,
                    PrecipitationIntensity.intensity(),
                    PrecipitationIntensity.peak(),
                    hookCalls,
                    tickHooks,
                    renderCalls,
                    Client.Config == null ? "null" : String.valueOf(Client.Config.weatherOptions.enableGradedPrecipitation),
                    PrecipitationIntensity.forced() >= 0F,
                    broken);
        }
        return now;
    }

    /** Diagnostics only. Logging must never be able to break rendering. */
    private static void note(String fmt, Object... args) {
        try {
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .info("Precipitation: " + fmt, args);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static final class GradedPrecipitationEffects extends DimensionSpecialEffects.OverworldEffects {
        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
                                         LightTexture lightTexture, double camX, double camY, double camZ) {
            hookCalls++;
            return active();
        }

        @Override
        public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
            // Counted before the answer, and the answer is unconditional, so the
            // counter reads "vanilla asked" rather than "we took it over". That is
            // the whole point - see the field comment on tickHooks.
            tickHooks++;
            // Bytecode, not intuition: the caller does ifeq 23 / 22: return, so
            // false is "vanilla, carry on" and true is "vanilla, stop". We want
            // vanilla to carry on - tickRain is where the ground splash
            // positions and both rain one-shots come from, and N7 grades them
            // from the inside (see MixinLevelRendererWeather). Returning true
            // here deleted both, which is what "why is the rain silent" turned
            // out to be.
            return false;
        }
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
            return;
        // Counted before the active() gate so the diagnostics can tell "the stage never
        // fires" (stays 0) apart from "the stage fires but we decline to draw".
        renderCalls++;
        if (!active())
            return;
        try {
            render(event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } catch (Throwable t) {
            // Never let a render failure take the frame down; the next frame falls
            // back to vanilla because active() drives the takeover too.
            org.orecruncher.dsurround.lib.logging.IModLog logger =
                    org.orecruncher.dsurround.lib.di.ContainerManager.resolve(
                            org.orecruncher.dsurround.lib.logging.IModLog.class);
            logger.error(t, "PrecipitationRenderer threw while rendering precipitation");
            // The Tesselator is a process-wide singleton: a throw mid-loop leaves
            // its BufferBuilder building, and the next begin() - the sandstorm
            // veil - would throw "Already building" and take the frame down.
            try {
                Tesselator.getInstance().clear();
            } catch (Throwable ignored) {
                // not building; nothing to clean up
            }
            disable();
        }
    }

    private static volatile boolean broken = false;

    private static void disable() {
        broken = true;
    }

    /**
     * Clears session state when a world is left. The caches are keyed by
     * dimension and game time, which go stale across worlds that share the key
     * (a new world's clock can read lower than the old one's, freezing the TTL
     * forever), and a one-off render exception must not stay broken across
     * sessions.
     */
    public static void onWorldLeft() {
        broken = false;
        resetLevelHysteresis();
        phaseCache.clear();
        surfaceCache.clear();
        lightCache.clear();
        cacheDimension = null;
    }

    private static void render(Camera camera, float partialTick) {
        if (broken)
            return;
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null)
            return;

        final float intensity = PrecipitationIntensity.intensity();
        final float peak = PrecipitationIntensity.peak();
        final float alphaRatio = peak > 0.0001F
                ? (float) Math.pow(Mth.clamp(intensity / peak, 0F, 1F), RAIN_ALPHA_GAMMA)
                : 0F;
        if (alphaRatio <= 0F)
            return;

        final int rainLevel = levelIndex(intensity);
        final int range = RANGE;
        final int ticks = (int) level.getGameTime();
        final Vec3 cam = camera.getPosition();
        final int playerX = Mth.floor(player.getX());
        final int playerY = Mth.floor(player.getY());
        final int playerZ = Mth.floor(player.getZ());

        refreshCaches(level, ticks);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        if (PrecipitationIntensity.forced() >= 0F) {
            // Diagnostics lock: draw the locked level as whatever this place
            // actually precipitates, not as rain everywhere. The lock used to
            // force RAIN because the per-column phase gate needs
            // level.isRaining() to be true - see potentialPhase(), which asks
            // the same question without that test.
            drawPhase(level, camera, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.RAIN, RAIN_TEXTURES[rainLevel], 1F, false, true, intensity);
            drawPhase(level, camera, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.SNOW, SNOW_TEXTURES[rainLevel], 1F, true, true, intensity);
        } else {
            drawPhase(level, camera, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.RAIN, RAIN_TEXTURES[rainLevel], alphaRatio, false, false, intensity);
            drawPhase(level, camera, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.SNOW, SNOW_TEXTURES[rainLevel], alphaRatio, true, false, intensity);
        }

        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void drawPhase(ClientLevel level, Camera camera, Vec3 cam, float partialTick,
                                  int ticks, int range, int playerX, int playerY, int playerZ,
                                  Biome.Precipitation want, ResourceLocation texture,
                                  float alphaRatio, boolean snow, boolean ignorePhase,
                                  float intensity) {
        var mc = Minecraft.getInstance();
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        final int wanted = want.ordinal();
        final double animClock = (double) ticks + partialTick;
        int quads = 0;

        for (int gridZ = playerZ - range; gridZ <= playerZ + range; gridZ++) {
            for (int gridX = playerX - range; gridX <= playerX + range; gridX++) {
                long colKey = BlockPos.asLong(gridX, 0, gridZ);
                long[] col = surfaceCache.get(colKey);
                if (col == null) {
                    int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gridX, gridZ);
                    col = new long[] { surface };
                    surfaceCache.put(colKey, col);
                }
                final int surface = (int) col[0];
                final int k2 = Math.max(playerY - range, surface);
                final int l2 = Math.max(playerY + range, surface);
                if (k2 >= l2)
                    continue;

                long phaseKey = BlockPos.asLong(gridX, k2, gridZ);
                if (ignorePhase) {
                    // Locked: phase() would answer NONE everywhere because the
                    // world is not raining, so ask what this column would drop
                    // instead. Not cached - a lock is a diagnostics tool and the
                    // lookup is per column per frame only while one is held.
                    cursor.set(gridX, k2, gridZ);
                    if (PrecipitationIntensity.potentialPhase(cursor).ordinal() != wanted)
                        continue;
                } else {
                    if (!phaseCache.containsKey(phaseKey)) {
                        cursor.set(gridX, k2, gridZ);
                        phaseCache.put(phaseKey, PrecipitationIntensity.phase(cursor).ordinal());
                    }
                    if (phaseCache.get(phaseKey) != wanted)
                        continue;
                }

                final int seed = (gridZ << 16) ^ gridX;

                // Density channel one: which columns exist at all. STRATIFIED, not
                // random - see columnRank(). An independent per-column coin flip
                // clumps, and at low intensity the survivors ended up lopsided
                // around the player: a few curtains out at the edge of the grid and
                // none overhead. Stratifying bounds the gaps, so turning the rain
                // down thins it evenly across the plane instead of punching holes
                // in it.
                final float density = Math.max(intensity, MIN_GEOMETRY_INTENSITY);
                final float columnHash = columnRank(gridX, gridZ);

                // The rank has to be world-anchored or the field shimmers as the
                // player walks, which leaves the player free to stand in one of its
                // gaps. This lifts the threshold for the columns around them. It is
                // a smooth function of the camera position so walking cannot pop
                // it, and it is scaled down towards zero intensity so a storm
                // building from nothing does not arrive as a disc hugging the
                // player.
                final double centreX = gridX + 0.5 - cam.x;
                final double centreZ = gridZ + 0.5 - cam.z;
                final float centreDist2 = (float) (centreX * centreX + centreZ * centreZ)
                        / (CENTER_RADIUS * CENTER_RADIUS);
                final float centreWeight = centreDist2 >= 1F
                        ? 0F
                        : CENTER_BIAS * (1F - centreDist2) * (1F - centreDist2)
                        * Mth.clamp(intensity / CENTER_BIAS_FULL_AT, 0F, 1F);
                final float threshold = density + centreWeight;

                final float columnFade = (threshold - columnHash) / COLUMN_FADE_BAND;
                if (columnFade <= 0F)
                    continue;
                final float columnAlpha = columnFade > 1F ? 1F : columnFade;

                // Density channel two: how wide each curtain is.
                final float widthScale = STRIP_MIN_SCALE + STRIP_RANGE * density;

                // Density channel three: streaks per curtain. Faded in over a band
                // rather than switched on at a threshold - see SECOND_LAYER_FROM.
                final float secondWeight = Mth.clamp(
                        (density - SECOND_LAYER_FROM) / (SECOND_LAYER_TO - SECOND_LAYER_FROM),
                        0F, 1F);
                final int layers = secondWeight > 0F ? MAX_LAYERS : 1;

                final int idx = (gridZ - playerZ + 16) * 32 + gridX - playerX + 16;
                final float stripX = STRIP_X[idx] * widthScale;
                final float stripY = STRIP_Y[idx] * widthScale;

                // Measured from the camera rather than from the player's block, so
                // the edge falloff is centred on where the view actually is instead
                // of being off by up to a block.
                final double d6 = centreX;
                final double d7 = centreZ;
                final float f3 = Mth.sqrt((float) (d6 * d6 + d7 * d7)) / range;
                final float alpha = (snow
                        ? ((1.0F - f3 * f3) * 0.3F + 0.5F)
                        : ((1.0F - f3 * f3) * 0.5F + 0.5F)) * alphaRatio * columnAlpha;
                if (alpha <= 0.002F)
                    continue;

                int light;
                if (lightCache.containsKey(phaseKey)) {
                    light = lightCache.get(phaseKey);
                } else {
                    int raw = LevelRenderer.getLightColor(level, cursor.set(gridX, k2, gridZ));
                    // Snow/dust only: 1.12.2 brightens towards full light so the flakes
                    // do not disappear into a dark sky. Rain uses the raw value.
                    // Rain, snow and dust all take the light where they actually are.
            //
            // This used to brighten snow towards full light -
            // (combinedLight * 3 + LightTexture.FULL_BRIGHT) / 4 - so flakes
            // stayed visible after dark. That is a 1.12.2 behaviour, not a
            // modern one: vanilla 1.20's weather renderer carries no
            // FULL_BRIGHT term at all (javap over LevelRenderer: zero
            // occurrences), so snow that never dims at night was our
            // artefact rather than vanilla's.
            light = raw;
                    lightCache.put(phaseKey, light);
                }
                for (int layer = 0; layer < layers; layer++) {
                    // The extra streak fades in instead of appearing at full
                    // strength, so curtain density is continuous in intensity.
                    final float layerAlpha = layer == 0 ? 1F : secondWeight;
                    columnRandom.setSeed(seed + layer * 0x9E3779B9);
                    float u0, u1, v0, v1;
                    if (snow) {
                        final double d8 = ((ticks & 511) + partialTick) / 512.0;
                        final double d9 = columnRandom.nextDouble()
                                + animClock * 0.01F * (float) columnRandom.nextGaussian();
                        final double d10 = columnRandom.nextDouble()
                                + animClock * (float) columnRandom.nextGaussian() * 0.001D;
                        u0 = (float) d9;
                        u1 = 1F + (float) d9;
                        v0 = k2 * 0.25F + (float) d8 + (float) d10;
                        v1 = l2 * 0.25F + (float) d8 + (float) d10;
                    } else {
                        // 1.12.2: ((renderCount + seed) & 31) - Java binds + tighter than &,
                        // so this is ((ticks + seed) & 31), not ticks + (seed & 31).
                        final double d5 = (((ticks + seed) & 31) + partialTick) / 32.0
                                * (1.5D + 2.5D * intensity + columnRandom.nextDouble());
                        u0 = 0F;
                        u1 = 1F;
                        v0 = k2 * 0.25F + (float) d5;
                        v1 = l2 * 0.25F + (float) d5;
                    }

                    final float x0 = gridX - stripX + 0.5F - (float) cam.x;
                    final float z0 = gridZ - stripY + 0.5F - (float) cam.z;
                    final float x1 = gridX + stripX + 0.5F - (float) cam.x;
                    final float z1 = gridZ + stripY + 0.5F - (float) cam.z;
                    final float y0 = k2 - (float) cam.y;
                    final float y1 = l2 - (float) cam.y;
                    final int alphaInt = (int) (alpha * layerAlpha * 255F);
                    final int argb = (alphaInt << 24) | 0x00FFFFFF;

                    // White for both: 1.12.2 tints only DUST (biome dustColor), rain and
                    // snow use Color.WHITE.
                    buffer.addVertex(x0, y0, z0).setUv(u0, v0).setColor(argb).setLight(light);
                    buffer.addVertex(x1, y0, z1).setUv(u1, v0).setColor(argb).setLight(light);
                    buffer.addVertex(x1, y1, z1).setUv(u1, v1).setColor(argb).setLight(light);
                    buffer.addVertex(x0, y1, z0).setUv(u0, v1).setColor(argb).setLight(light);
                    quads++;
                }
            }
        }

        // See the note in the 1.20.1 port: Tesselator is a shared singleton and its
        // builder must always be closed, even when nothing was drawn. build() returns
        // null for an empty batch, so an early return only leaked the builder.
        var mesh = buffer.build();
        if (mesh != null)
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
    }

    private static void refreshCaches(ClientLevel level, int ticks) {
        var dim = level.dimension();
        if (dim != cacheDimension) {
            cacheDimension = dim;
            clearCaches(ticks);
        } else if (ticks - cacheTick >= CACHE_TTL) {
            clearCaches(ticks);
        }
    }

    private static void clearCaches(int ticks) {
        surfaceCache.clear();
        phaseCache.clear();
        lightCache.clear();
        cacheTick = ticks;
    }

    /**
     * 1.12.2 {@code Weather.Properties.mapRainStrength}: first level at or above the
     * intensity. Index into {@link #LEVELS}.
     */
    /**
     * How far past a boundary intensity must travel before the texture level
     * switches. Intensity is continuous but the levels are not, so without this a
     * value hovering on a boundary flips the texture back and forth every few
     * frames - the single most visible way graded rain can look broken.
     */
    private static final float LEVEL_HYSTERESIS = 0.02F;
    private static int lastLevel = -1;

    /** Unhysteretic mapping - which band an intensity falls in. Pure. */
    private static int rawLevel(float intensity) {
        for (int i = 0; i < LEVELS.length; i++)
            if (intensity <= LEVELS[i])
                return i;
        return LEVELS.length - 1;
    }

    /**
     * 1.12.2 {@code Weather.Properties.mapRainStrength}: first level at or above the
     * intensity. Index into {@link #LEVELS}.
     *
     * <p>Carries hysteresis so the level only changes once intensity has clearly
     * committed to the next band. Reset when a lock is applied, because a lock is a
     * deliberate jump to a level and must land immediately.</p>
     */
    private static int levelIndex(float intensity) {
        final int raw = rawLevel(intensity);
        final int prev = lastLevel;
        if (prev < 0 || raw == prev)
            return lastLevel = raw;
        if (raw > prev && intensity > LEVELS[prev] + LEVEL_HYSTERESIS)
            return lastLevel = raw;
        if (raw < prev && intensity < (prev > 0 ? LEVELS[prev - 1] : 0F) - LEVEL_HYSTERESIS)
            return lastLevel = raw;
        return prev;
    }

    /** Name of the texture level an intensity maps to - diagnostics only. */
    public static String levelName(float intensity) {
        return LEVEL_NAMES[rawLevel(intensity)];
    }

    /** Called when a lock is applied so the next frame lands on the locked level. */
    public static void resetLevelHysteresis() {
        lastLevel = -1;
    }

    /**
     * Diagnostics only: how many times our draw stage fired. Stays 0 when that stage
     * never fires (a rewritten render pipeline, most often Sodium or Embeddium),
     * which is the one failure mode {@link #hookCalls()} cannot see - the suppression
     * hook can be consulted all day while our own draw pass never runs.
     */
    public static int renderCalls() {
        return renderCalls;
    }

    /** Diagnostics only: how many times vanilla asked whether it should draw rain. */
    public static int hookCalls() {
        return hookCalls;
    }

    /**
     * Diagnostics only: how many times vanilla asked {@code tickRain}, the pass
     * that spawns ground splash particles and plays the rain sound. See the field
     * comment on {@code tickHooks} for what the value tells you.
     */
    public static int tickHooks() {
        return tickHooks;
    }

    /** Diagnostics only: true after a render failure permanently disabled the pass. */
    public static boolean isBroken() {
        return broken;
    }
}
