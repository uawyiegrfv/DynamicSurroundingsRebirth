package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.processing.weather.PrecipitationRenderer;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.tags.BiomeTags;

/**
 * A17: drives the desert sandstorm and nether dust rain.
 *
 * <p>Visual layering follows the user-approved split: while a desert is clear the
 * only effect is the distant-horizon yellow tint provided by the biome fog color
 * (MixinBiome getFogColor injection - covers no GUI); when it rains, the 1.12.2
 * StormRenderer dust rain fades in - per-column vertical quads over every desert
 * column in a ±10 block grid (fancy graphics; ±5 on fast), textured with the
 * intensity-graded 64x256 dust strips with scrolling UVs, tinted by the biome
 * dustColor - on top of a light ambient dust particle drift. The nether keeps its
 * dark dust rain with a very faint veil.
 *
 * <p>Both tints fade asymmetrically: rain-driven states appear over ~0.5s and
 * retreat over ~1.2s so the screen never pops when a storm starts or stops, and
 * cave suppression rides the same smoothing.
 */
public class WeatherStormHandler extends AbstractClientHandler {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private static final String MOD_ID = "dsurround";

    // Intensity-graded dust strips (1.12.2 Weather.Properties). World-space veil
    // textures - a 64x256 tiling sheet of dust specks (vanilla rain.png layout).
    private static final ResourceLocation DUST_CALM = new ResourceLocation(MOD_ID, "textures/environment/dust_calm.png");
    private static final ResourceLocation DUST_LIGHT = new ResourceLocation(MOD_ID, "textures/environment/dust_light.png");
    private static final ResourceLocation DUST_GENTLE = new ResourceLocation(MOD_ID, "textures/environment/dust_gentle.png");
    private static final ResourceLocation DUST_MODERATE = new ResourceLocation(MOD_ID, "textures/environment/dust_moderate.png");
    private static final ResourceLocation DUST_HEAVY = new ResourceLocation(MOD_ID, "textures/environment/dust_heavy.png");
    private static final ResourceLocation DUST_STRONG = new ResourceLocation(MOD_ID, "textures/environment/dust_strong.png");
    private static final ResourceLocation DUST_INTENSE = new ResourceLocation(MOD_ID, "textures/environment/dust_intense.png");
    private static final ResourceLocation DUST_TORRENTIAL = new ResourceLocation(MOD_ID, "textures/environment/dust_torrential.png");


    /**
     * Where the horizon tint samples the biome fog colour. Centre plus a small
     * ring.
     *
     * <p>Reading <b>one</b> point was the border flicker. Standing on a biome
     * boundary the sampled biome alternates between the two, so the eased
     * colour spends all its time chasing a target that keeps changing and never
     * settles - which is exactly what "it flickers, and the colour matches
     * neither side" looks like. Averaging over a ring makes the target a stable
     * blend at the boundary instead of an alternating one, so the easing has
     * something fixed to converge on.</p>
     */
    private static final float[][] FOG_SAMPLES = {
            { 0F, 0F }, { 12F, 0F }, { -12F, 0F }, { 0F, 12F }, { 0F, -12F }
    };

    // Fade rates per tick: 0.1 -> ~0.5s fade-in, 0.04 -> ~1.2s fade-out (retreat is
    // deliberately slower so a stopping storm does not pop).
    private static final float TINT_FADE_IN = 0.10F;
    private static final float TINT_FADE_OUT = 0.04F;

    private final Scanners scanners;
    /**
     * How desert-y it is where the player is, eased.
     *
     * <p>The sandstorm used to be switched by a boolean tag test, so crossing a
     * desert boundary stepped the veil target from zero to full - and the veil
     * is dust-coloured, which is why the flash matched neither neighbouring
     * biome's sky. Easing the presence costs one lerp and turns the step into a
     * fade.</p>
     */
    private float desertWeight = 0F;

    private float netherTint = 0F;
    private float fullscreenTint = 0F;
    // Rain veil state, updated per tick and consumed by the world renderer.
    private ResourceLocation veilTexture = DUST_CALM;
    /**
     * Strongest fullscreen dust wash, reached at dust intensity 1. The old value
     * was a flat 0.7 for every storm that ever blew; grading had to start by
     * admitting that number was a ceiling, not a setting.
     */
    private static final float DUST_VEIL_MAX = 0.70F;
    /**
     * Hard ceiling on the <em>fullscreen</em> dust wash, and the answer to
     * "at full strength you cannot see anything".
     *
     * <p>The veil is two layers - this flat wash plus the 3D sheets - and the
     * sheets overlap along any line of sight, so the occlusion that actually
     * reaches the eye is far more than either layer's alpha suggests. Without
     * an explicit ceiling the top of the range simply buried the world, and
     * with it the rain: the whole point of grading is that you can still see
     * what the storm is doing. These two constants are the visibility knobs.</p>
     */
    private static final float DUST_VEIL_FULLSCREEN_MAX = 0.34F;
    /** As {@link #DUST_VEIL_FULLSCREEN_MAX}, for one 3D dust sheet. */
    private static final float DUST_VEIL_SHEET_MAX = 0.32F;
    /**
     * Shape of the veil's response to dust intensity. Below 1 on purpose: a weak
     * event still has to be visible as haze, which a linear or convex curve at
     * these magnitudes barely manages.
     */
    private static final float DUST_VEIL_GAMMA = 0.55F;
    /**
     * How far a full dust storm pushes the horizon colour towards the dust
     * colour. Kept below 1 so a sandstorm never fully replaces the sky - it is
     * air full of dust, not a tan skybox.
     */
    private static final float DUST_SKY_TINT = 0.85F;
    /**
     * Below this the veil stops thinning out. Same floor the rain uses: past a
     * point turning the intensity down must not turn the dust off.
     */
    private static final float VEIL_MIN_DENSITY = 0.12F;
    /** Band a sheet of dust fades in over, so it appears rather than pops. */
    private static final float VEIL_FADE_BAND = 0.14F;
    /**
     * Sheet half-width at zero and at full strength, as a multiple of the 1.12.2
     * cross-section table. Dust has to close up into a wall at full strength; a
     * weak event leaves gaps between the sheets, which is the point.
     */
    private static final float VEIL_WIDTH_MIN = 0.55F;
    private static final float VEIL_WIDTH_RANGE = 0.65F;
    /**
     * Lateral drift rate at zero and at full strength - <em>this is the dust
     * counterpart of the rain's fall speed</em>. Rain grades how fast a streak
     * drops; dust is blown sideways, so it grades how fast a sheet travels.
     * The pair is chosen so that the middle of the range lands near the old
     * fixed 0.2: a middling storm still looks like it used to and only the two
     * ends move.
     */
    private static final float DUST_DRIFT_MIN = 0.05F;
    private static final float DUST_DRIFT_RANGE = 0.35F;


    private float veilR = 0.85F;
    private float veilG = 0.7F;
    private float veilB = 0.4F;

    private static final net.minecraft.sounds.SoundEvent DUST_HIT_SOUND = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
            new ResourceLocation(MOD_ID, "dust"));
    private final java.util.Random dustRandom = new java.util.Random();
    // 1.12.2 StormSplashRenderer wobbles the impact volume with a slow simplex noise
    // term so successive impacts drift instead of jumping independently. Same
    // algorithm as 1.12.2's NoiseGeneratorSimplex; seeded constant so the wobble is
    // reproducible across sessions.
    private static final net.minecraft.world.level.levelgen.synth.SimplexNoise DUST_VOLUME_NOISE =
            new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(0L));
    private int rainSoundCounter = 0;

    // Nether dust veil colors: each column picks a random red / black / red-brown
    // tint (1.12.2 nether dust look, drawn with the shared dust texture).
    private static final int[][] NETHER_DUST_COLORS = {
            { 0xC0, 0x30, 0x30 }, // red
            { 0x16, 0x10, 0x10 }, // near-black
            { 0x8B, 0x5A, 0x3C }, // red-brown
    };

    // 1.12.2 StormRenderer: a 32x32 table of per-column half-widths. Each entry is
    // the unit vector perpendicular to the player->column radius, scaled to 0.5, so
    // every column's quad is a sheet lying across the radius - the columns together
    // read as slanted curtains sweeping around the player instead of axis-aligned
    // rectangles. Indexed by (gridZ - playerZ + 16) * 32 + (gridX - playerX + 16).
    // Ported from 1.12.2 RAIN_X_COORDS / RAIN_Y_COORDS (StormRenderer static init);
    // the centre column is 0/0 there and would be NaN, so it falls back to a fixed
    // half-width to keep the column under the player visible.
    // Half-width of each column's strip, in blocks. 1.12.2 uses 0.5, which makes
    // neighbouring strips exactly touch with zero overlap - under the radial layout
    // that reads as a set of separate sheets rather than one curtain. Widened so
    // adjacent strips overlap roughly 80%. This is the density knob: raise it for a
    // thicker / more opaque curtain, lower it for a thinner one.
    private static final float VEIL_STRIP_HALF_WIDTH = 0.9F;
    private static final float[] RAIN_X_COORDS = new float[1024];
    private static final float[] RAIN_Y_COORDS = new float[1024];

    static {
        for (int i = 0; i < 32; ++i) {
            for (int j = 0; j < 32; ++j) {
                final float dx = j - 16F;
                final float dz = i - 16F;
                final float r = (float) Math.sqrt(dx * dx + dz * dz);
                final int idx = i << 5 | j;
                if (r < 0.0001F) {
                    RAIN_X_COORDS[idx] = VEIL_STRIP_HALF_WIDTH;
                    RAIN_Y_COORDS[idx] = 0.0F;
                } else {
                    RAIN_X_COORDS[idx] = -dz / r * VEIL_STRIP_HALF_WIDTH;
                    RAIN_Y_COORDS[idx] = dx / r * VEIL_STRIP_HALF_WIDTH;
                }
            }
        }
    }

    private final java.util.Random columnRandom = new java.util.Random();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    // Per-column veil cache: biome match + surface height, plus the packed light at
    // each column's band position. Cleared every 40 ticks (2s) so world edits and
    // daylight shifts show up while a storm runs. When the player stands still this
    // cuts the ~1300 block queries per frame down to map lookups only.
    private static final long COLUMN_CACHE_TTL = 40L;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> columnCache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap lightCache = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private long columnCacheTick = Long.MIN_VALUE;
    // Dimension the cache was built for - crossing dimensions invalidates it
    // immediately (the same column coordinates exist in every dimension).
    private net.minecraft.resources.ResourceKey<Level> cacheDimension;

    public WeatherStormHandler(Configuration config, IModLog logger, Scanners scanners) {
        super("Weather Storm", config, logger);
        this.scanners = scanners;

        MinecraftForge.EVENT_BUS.addListener(this::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderGuiPre);
    }

    private boolean veilBroken = false;

    @Override
    public void onConnect() {
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
    }

    @Override
    public void onDisconnect() {
        // Reset so the yellow haze does not linger into the next world/session.
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
        // Per-world state: caches keyed by dimension + game time, session flags,
        // and the precipitation statics. Without this, weather from one world
        // bleeds into the next (and a stale TTL freezes the column cache).
        org.orecruncher.dsurround.processing.PrecipitationIntensity.onWorldLeft();
        org.orecruncher.dsurround.processing.weather.PrecipitationRenderer.onWorldLeft();
        this.columnCache.clear();
        this.lightCache.clear();
        this.veilBroken = false;
    }

    @Override
    public void process(final Player player) {
        var level = player.level();
        if (!(level instanceof ClientLevel clientLevel))
            return;


        var biome = level.getBiome(player.blockPosition()).value();
        // Feed the player's biome to the precipitation profile matcher. This only
        // resolves and reports for now - it does not change intensity yet - but it
        // gives the biome-driven grading (N2/N3) a resolved profile to build on and
        // makes the matcher observable: one log line per biome crossing.
        PrecipitationIntensity.update(this.logger, clientLevel,
                this.scanners.playerLogicBiomeInfo(), player.blockPosition());
        boolean nether = level.dimension() == Level.NETHER;
        boolean desert = TAG_LIBRARY.is(BiomeTags.IS_DESERT, biome) || TAG_LIBRARY.is(BiomeTags.IS_BADLANDS, biome);
        this.desertWeight = fade(this.desertWeight, desert ? 1F : 0F);
        // The nether has no sky so its own Level.isRaining() is always false. The server
        // pushes the overworld's rain state via WeatherMessage (WeatherSyncState cache).
        boolean raining;
        if (nether) {
            raining = org.orecruncher.dsurround.network.WeatherSyncState.isRaining();
        } else {
            // A diagnostics lock is by definition used when the world is not
            // raining, so honour it here too - otherwise /dsprecip lock in a
            // desert shows the rain curtains and no sandstorm at all.
            raining = level.isRaining() || org.orecruncher.dsurround.processing.
                    PrecipitationIntensity.forced() >= 0F;
        }

        float netherTarget = 0F;
        float fullscreenTarget = 0F;
        if (nether && this.config.weatherOptions.enableNetherDust) {
            // Nether dust veil, weather-driven like the desert sandstorm: only rains
            // during rain/thunder, fades out on clear (1.12.2 WeatherGeneratorNether).
            if (raining) {
                netherTarget = 0.7F;
                this.veilTexture = DUST_MODERATE;
            } else {
                this.veilTexture = DUST_CALM;
            }
        } else if (desert && this.config.weatherOptions.enableDesertSandstorm) {
            if (!this.scanners.isInside()) {
                float r = 0.85F, g = 0.7F, b = 0.4F;
                var info = ((IBiomeExtended) (Object) biome).dsurround_getInfo();
                if (info != null) {
                    var dust = info.getDustColor();
                    if (dust != null) {
                        r = ((dust.getValue() >> 16) & 0xFF) / 255F;
                        g = ((dust.getValue() >> 8) & 0xFF) / 255F;
                        b = (dust.getValue() & 0xFF) / 255F;
                    }
                }

                if (raining) {
                    // Sandstorm: the dust veil fades in and a stream of ambient dust
                    // particles blows with the wind. The horizon tint (fog color) stays.
                    // Graded. This used to be a flat 0.7 for every storm, which
                    // is why two sandstorms at the same strength looked alike.
                    // The veil is how hard the storm is blowing - see
                    // PrecipitationIntensity.dustIntensity().
                    fullscreenTarget = DUST_VEIL_MAX
                            * (float) Math.pow(PrecipitationIntensity.dustIntensity(),
                                    DUST_VEIL_GAMMA) * this.desertWeight;
                    this.veilTexture = dustTexture(clientLevel);
                    this.veilR = r;
                    this.veilG = g;
                    this.veilB = b;
                } else {
                    // Clear desert: no fullscreen tint (the horizon fog color is the
                    // effect); a light drift of calm dust for ambience.
                    this.veilTexture = DUST_CALM;
                    this.veilR = r;
                    this.veilG = g;
                    this.veilB = b;
                }
            }
        }

        this.netherTint = fade(this.netherTint, netherTarget);
        this.fullscreenTint = fade(this.fullscreenTint, fullscreenTarget);

        float dustIntensity = Math.max(this.netherTint, this.fullscreenTint);
        // 1.12.2 StormSplashRenderer accumulating throttle: fires ~once every 3 ticks
        // (~7/s), so the 2s rumble overlaps into a continuous surround wind, not gaps.
        if (dustIntensity > 0.02F && this.dustRandom.nextInt(3) < this.rainSoundCounter++) {
            this.rainSoundCounter = 0;
            playDustImpactSound(player, dustIntensity);
        }
    }

    private void playDustImpactSound(Player player, float tint) {
        var level = player.level();
        int range = 10;
        int rx = player.blockPosition().getX() + this.dustRandom.nextInt(range * 2 + 1) - range;
        int rz = player.blockPosition().getZ() + this.dustRandom.nextInt(range * 2 + 1) - range;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, rx, rz);
        // 1.12.2 ServerDrivenTracker.getCurrentVolume(): 0.05 + 0.95 * intensityLevel.
        // 1.12.2 StormSplashRenderer.calculateRainSoundVolume() then wobbles that base
        // by +/-25% with a slow simplex term (one cycle roughly every 5s).
        final float baseVolume = Math.min(1F, 0.05F + 0.95F * tint);
        final float bounds = baseVolume * 0.25F;
        final float adjust = Mth.clamp(
                (float) (DUST_VOLUME_NOISE.getValue((level.getGameTime() % 24000L) / 100.0, 1.0) / 5.0D),
                -bounds, bounds);
        float volume = Mth.clamp(baseVolume + adjust, 0F, 1F);
        float pitch = 0.9F + (this.dustRandom.nextFloat() - this.dustRandom.nextFloat()) * 0.1F;
        level.playLocalSound(rx + 0.5D, surface + 0.5D, rz + 0.5D, DUST_HIT_SOUND,
                net.minecraft.sounds.SoundSource.WEATHER, volume, pitch, false);
    }

    /**
     * Smoothly approaches the target, snapping when close. Fades in faster than they
     * retreat so storms build with the rain and linger briefly after it stops.
     */
    private static float fade(float current, float target) {
        if (current == target)
            return target;
        if (Math.abs(target - current) < 0.005F)
            return target;
        float rate = target > current ? TINT_FADE_IN : TINT_FADE_OUT;
        return current + (target - current) * rate;
    }

    /**
     * Picks the dust strip for the current weather intensity. Thresholds match the
     * 1.12.2 Weather.Properties levels; thunderstorms push the intensity up a tier.
     */
    private static ResourceLocation dustTexture(ClientLevel level) {
        // Read through PrecipitationIntensity, not level.getRainLevel() directly: it is
        // the single intensity choke point, so grading layers added later land here and
        // nothing can turn this read into a feedback loop.
        // The dust storm's own intensity, not the vanilla rain level.
        //
        // This used to read the vanilla rain level, which saturates five seconds
        // into any weather - so every sandstorm hit DUST_TORRENTIAL and the eight
        // graded strips were dead code. Thundering no longer bumps it either:
        // that was compensating for the rain level being a step function, and
        // the dust roll already knows whether the world is storming (sampleBase
        // skews heavy when it is).
        float intensity = PrecipitationIntensity.dustIntensity();
        if (intensity >= 1F) return DUST_TORRENTIAL;
        if (intensity >= 0.875F) return DUST_INTENSE;
        if (intensity >= 0.75F) return DUST_STRONG;
        if (intensity >= 0.625F) return DUST_HEAVY;
        if (intensity >= 0.5F) return DUST_MODERATE;
        if (intensity >= 0.365F) return DUST_GENTLE;
        if (intensity >= 0.25F) return DUST_LIGHT;
        return DUST_CALM;
    }

    /**
     * GUI layer callback: draws the fullscreen dust veil while a sandstorm is active
     * (or in the nether). RenderGuiEvent.Pre fires at the very top of ForgeGui.render,
     * before any HUD overlay is drawn, so the veil sits underneath the minimap, chat
     * and every other HUD element. The desert-clear state draws nothing here - its
     * horizon tint comes from the biome fog color.
     */
    public void onRenderGuiPre(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
        final float intensity = Math.max(this.netherTint, this.fullscreenTint);
        if (intensity <= 0.005F)
            return;

        var graphics = event.getGuiGraphics();
        var mc = Minecraft.getInstance();
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        final int alpha = (int) (Math.min(intensity * 0.6F, DUST_VEIL_FULLSCREEN_MAX) * 255F);
        if (alpha <= 0)
            return;
        // Yellow-brown dust haze, 0xD8B266. Rendered with the guiOverlay render type -
        // NO_DEPTH_TEST + color-only write mask - so the fullscreen veil can never write
        // into the GUI depth buffer. The previous plain fill() (RenderType.gui(), LEQUAL
        // + depth write) stamped the z=0 plane depth across the whole screen before any
        // HUD drew, which killed Xaero's minimap - the one HUD that actively depth-tests
        // and depth-clears inside the GUI. The old disableDepthTest()/enableDepthTest()
        // wrapper was a no-op: GuiGraphics.fill() only enqueues vertices, and
        // GuiGraphics.flush() draws them with the render type's own state shards and then
        // force-enables depth test. This mirrors vanilla's own fullscreen overlays
        // (spyglass/frozen/vignette), which all use RenderType.guiOverlay().
        final boolean nether = mc.level != null && mc.level.dimension() == Level.NETHER;
        final int color = (alpha << 24) | (nether ? 0x00C07A5A : 0x00D8B266);
        graphics.fill(RenderType.guiOverlay(), 0, 0, width, height, color);
    }

    /**
     * The sandstorm dust rain, ported from the 1.12.2 StormRenderer: per-column thin
     * vertical quads over every desert column in a ±range grid (fancy 10 / fast 5),
     * heightmap-clipped to a band around the player, textured with the intensity
     * strip (full-width U, quarter-height V scrolling downward per column), tinted
     * by the biome dustColor, alpha fading with distance. Rendered right after the
     * vanilla weather pass so terrain occlusion behaves like rain.
     */
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
            return;
        if (this.veilBroken)
            return;
        try {
            this.renderVeil(event);
        } catch (Throwable t) {
            // One deterministic failure must not hit the event bus every frame -
            // degrade to vanilla for the session, same philosophy as the
            // precipitation renderer's broken flag.
            this.veilBroken = true;
            this.logger.error(t, "Sandstorm veil render failed - disabling until restart");
            try {
                Tesselator.getInstance().end();
            } catch (Throwable ignored) {
                // buffer was not building; nothing to clean up
            }
        }
    }

    private void renderVeil(RenderLevelStageEvent event) {

        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null)
            return;
        final boolean nether = level.dimension() == Level.NETHER;
        final float tint = nether ? this.netherTint : this.fullscreenTint;
        if (tint < 0.02F)
            return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partialTick = event.getPartialTick();
        int ticks = (int) level.getGameTime();
        int range = Minecraft.useFancyGraphics() ? 10 : 5;
        int playerX = Mth.floor(player.getX());
        int playerY = Mth.floor(player.getY());
        int playerZ = Mth.floor(player.getZ());
        float veilAlpha = Math.min(tint * 0.6F, DUST_VEIL_SHEET_MAX);
        // The veil's own shape channels. Driven by the tint rather than by the
        // dust roll directly, because the nether's veil is a fixed-strength
        // effect with no dust roll behind it - normalising against
        // DUST_VEIL_MAX keeps it where it has always been instead of
        // re-grading it into something else.
        final float veilNow = Mth.clamp(tint / DUST_VEIL_MAX, 0F, 1F);
        final float drift = DUST_DRIFT_MIN + DUST_DRIFT_RANGE * veilNow;
        final float widthScale = VEIL_WIDTH_MIN + VEIL_WIDTH_RANGE * veilNow;
        final float veilDensity = Math.max(veilNow, VEIL_MIN_DENSITY);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, this.veilTexture);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        var dimId = level.dimension();
        if (dimId != this.cacheDimension) {
            this.cacheDimension = dimId;
            this.columnCache.clear();
            this.lightCache.clear();
            this.columnCacheTick = ticks;
        } else if (ticks - this.columnCacheTick >= COLUMN_CACHE_TTL) {
            this.columnCache.clear();
            this.lightCache.clear();
            this.columnCacheTick = ticks;
        }

        for (int gridZ = playerZ - range; gridZ <= playerZ + range; gridZ++) {
            for (int gridX = playerX - range; gridX <= playerX + range; gridX++) {
                this.cursor.set(gridX, 0, gridZ);
                long colKey = BlockPos.asLong(gridX, 0, gridZ);
                long[] col = this.columnCache.get(colKey);
                if (col == null) {
                    var columnBiome = level.getBiome(this.cursor).value();
                    boolean match = nether || TAG_LIBRARY.is(BiomeTags.IS_DESERT, columnBiome) || TAG_LIBRARY.is(BiomeTags.IS_BADLANDS, columnBiome);
                    int surface = nether ? Integer.MIN_VALUE : level.getHeight(Heightmap.Types.MOTION_BLOCKING, gridX, gridZ);
                    col = new long[] { match ? 1L : 0L, surface };
                    this.columnCache.put(colKey, col);
                }
                if (col[0] == 0L)
                    continue;

                int k2, l2;
                if (nether) {
                    // Nether: the veil surrounds the player (the nether is a cave - MOTION_BLOCKING
                    // reports the bedrock ceiling, far above the player, so clamping to surface
                    // would collapse every column to k2 >= l2 and draw nothing).
                    k2 = playerY - range;
                    l2 = playerY + range;
                } else {
                    int surface = (int) col[1];
                    k2 = Math.max(playerY - range, surface);
                    l2 = Math.max(playerY + range, surface);
                }
                if (k2 >= l2)
                    continue;

                int seed = (gridZ << 16) ^ gridX;
                this.columnRandom.setSeed(seed);
                // Density channel one: which sheets of dust exist at all.
                // The veil used to draw every column of the grid at any strength,
                // so a faint haze was a uniform field turned down - it read as
                // "the world got dimmer", not "there is dust blowing". Same
                // stratified rank the rain uses: a per-column coin flip clumps,
                // and the survivor set has to be world-anchored or the field
                // shimmers as the player walks.
                final float columnFade = (veilDensity
                        - PrecipitationRenderer.columnRank(gridX, gridZ)) / VEIL_FADE_BAND;
                if (columnFade <= 0F)
                    continue;
                final float columnAlpha = columnFade > 1F ? 1F : columnFade;
                // 1.12.2 radial sheet: the cross-section is perpendicular to the
                // player->column radius, so the columns sweep around the player. This
                // replaces two nextFloat() draws - which also restores the 1.12.2 random
                // sequence (setSeed -> nextDouble -> nextGaussian), so the per-column UV
                // shear and nether dust colours now match 1.12.2 exactly.
                final int coordIdx = (gridZ - playerZ + 16) * 32 + gridX - playerX + 16;
                // Density channel two: how wide each sheet is.
                float rainX = RAIN_X_COORDS[coordIdx] * widthScale;
                float rainY = RAIN_Y_COORDS[coordIdx] * widthScale;
                // 1.12.2 StormRenderer dust curtain: SLOW scroll (512-tick loop) plus
                // per-column UV shear - each column drifts sideways at its own gaussian
                // rate, reading as multi-angle particle curtains.
                // Animation clock. 1.12.2 feeds this from EntityRenderer.rendererUpdateCount,
                // which is incremented in EntityRenderer.updateRenderer() - called from
                // Minecraft.runTick(), i.e. once per game tick: the same 20 Hz rate as
                // level.getGameTime(). So the scroll speed already matches 1.12.2. It is
                // evaluated as a double because these offsets grow without bound and, as
                // float, quantize (then freeze) once the accumulated offset passes ~1e6.
                final double animClock = (double) ticks + partialTick;
                double d8 = ((ticks & 511) + partialTick) / 512.0;
                // Lateral drift, graded: the dust counterpart of the rain's fall speed.
                // The 0.2F here used to be a constant, so every sandstorm blew at the
                // same rate however hard it was actually blowing.
                double d9 = this.columnRandom.nextDouble() + animClock * drift * this.columnRandom.nextGaussian();
                double d10 = this.columnRandom.nextDouble() + animClock * this.columnRandom.nextGaussian() * 0.001D;

                double d6 = gridX + 0.5 - player.getX();
                double d7 = gridZ + 0.5 - player.getZ();
                float f3 = Mth.sqrt((float) (d6 * d6 + d7 * d7)) / range;
                float alpha = ((1.0F - f3 * f3) * 0.3F + 0.5F) * veilAlpha * columnAlpha;
                long lightKey = BlockPos.asLong(gridX, k2, gridZ);
                long lightVal;
                if (this.lightCache.containsKey(lightKey)) {
                    lightVal = this.lightCache.get(lightKey);
                } else {
                    lightVal = (LevelRenderer.getLightColor(level, this.cursor.set(gridX, k2, gridZ)) * 3 + 15728880) / 4;
                    this.lightCache.put(lightKey, lightVal);
                }
                int light = (int) lightVal;
                int slX16 = light >> 16 & 0xFFFF;
                int blX16 = light & 0xFFFF;

                float x0 = gridX - rainX + 0.5F - (float) cam.x;
                float z0 = gridZ - rainY + 0.5F - (float) cam.z;
                float x1 = gridX + rainX + 0.5F - (float) cam.x;
                float z1 = gridZ + rainY + 0.5F - (float) cam.z;
                float y0 = k2 - (float) cam.y;
                float y1 = l2 - (float) cam.y;
                float v0 = k2 * 0.25F + (float) d8;
                float v1 = l2 * 0.25F + (float) d8;
                // The float overload expects 0..1 - passing the 0..255 ints overflowed the
                // byte conversion and read as a near-invisible wash.
                int cr, cg, cb;
                if (nether) {
                    var dustColor = NETHER_DUST_COLORS[this.columnRandom.nextInt(NETHER_DUST_COLORS.length)];
                    cr = dustColor[0];
                    cg = dustColor[1];
                    cb = dustColor[2];
                } else {
                    cr = (int) (this.veilR * 255F);
                    cg = (int) (this.veilG * 255F);
                    cb = (int) (this.veilB * 255F);
                }
                int alphaInt = (int) (alpha * 255F);

                buffer.vertex(x0, y0, z0).uv((float) d9, v0 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x1, y0, z1).uv(1F + (float) d9, v0 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x1, y1, z1).uv(1F + (float) d9, v1 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
                buffer.vertex(x0, y1, z0).uv((float) d9, v1 + (float) d10).color(cr, cg, cb, alphaInt).uv2(slX16, blX16).endVertex();
            }
        }

        tesselator.end();


        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

}