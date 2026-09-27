package org.orecruncher.dsurround.processing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.processing.weather.PrecipitationRenderer;
import org.orecruncher.dsurround.mixinutils.IBiomeExtended;
import org.orecruncher.dsurround.tags.BiomeTags;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A17 (particle version): drives the desert sandstorm and nether dust rain.
 *
 * <p>Visual layering follows the user-approved split: while a desert is clear the
 * only effect is the distant-horizon yellow tint provided by the biome fog color
 * (biomes.json "fogColor"); when it rains, a dust veil fades into the world around
 * the player (the 1.12.2 StormRenderer mechanism: the intensity-graded 64x256 dust
 * strips rendered as world-space quads with scrolling UVs, tinted by the biome
 * dustColor) on top of a light ambient dust particle drift. The nether keeps its
 * dark dust rain with a very faint veil.
 *
 * <p>26.1 has no Biome.getFogColor() (the fog color moved into the environment
 * attribute system), so the horizon tint is applied through the NeoForge
 * ViewportEvent.ComputeFogColor hook: the vanilla fog color (which already carries
 * the day/night and weather adjustments) is modulated toward the configured desert
 * color with a smoothed weight. The modulation is multiplicative so the vanilla
 * brightness is preserved at all times of day. The tint is data driven - it
 * applies to every biome with a configured fogColor (desert haze, swamp fog, ...),
 * gated by the weatherOptions.enableBiomeFogColor switch.
 *
 * <p>Both tints fade asymmetrically: rain-driven states appear over ~0.5s and
 * retreat over ~1.2s so the screen never pops when a storm starts or stops, and
 * cave suppression rides the same smoothing.
 */
public class WeatherStormHandler extends AbstractClientHandler {

    private static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private static final String MOD_ID = "dsurround";

    // Intensity-graded dust strips (1.12.2 Weather.Properties). World-space veil
    // textures - a 64x256 tiling sheet of dust specks, NOT a particle sprite.
    private static final Identifier DUST_CALM = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_calm.png");
    private static final Identifier DUST_LIGHT = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_light.png");
    private static final Identifier DUST_GENTLE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_gentle.png");
    private static final Identifier DUST_MODERATE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_moderate.png");
    private static final Identifier DUST_HEAVY = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_heavy.png");
    private static final Identifier DUST_STRONG = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_strong.png");
    private static final Identifier DUST_INTENSE = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_intense.png");
    private static final Identifier DUST_TORRENTIAL = Identifier.fromNamespaceAndPath(MOD_ID, "textures/environment/dust_torrential.png");


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

    // Veil geometry (1.12.2 StormRenderer): a ±range one-block-column grid, one thin
    // diagonal quad per desert column, heightmap-clipped to a band around the player.
    private static final int VEIL_RANGE = 10;

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
    private Identifier veilTexture = DUST_CALM;
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
            Identifier.fromNamespaceAndPath(MOD_ID, "dust"));
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

    // Desert horizon tint state (see class comment).
    private float horizonWeight = 0F;
    /** Throttle for the [FOG-26] sample - the fog event fires every frame. */
    private int fogProbeCounter = 0;
    private float horizonR = 1F;
    private float horizonG = 1F;
    private float horizonB = 1F;

    public WeatherStormHandler(Configuration config, IModLog logger, Scanners scanners) {
        super("Weather Storm", config, logger);
        this.scanners = scanners;

        NeoForge.EVENT_BUS.addListener(this::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(this::onAfterWeather);
    }

    private boolean veilBroken = false;

    @Override
    public void onConnect() {
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
        this.horizonWeight = 0F;
    }

    @Override
    public void onDisconnect() {
        // Reset so the yellow haze does not linger into the next world/session.
        this.netherTint = 0F;
        this.fullscreenTint = 0F;
        this.horizonWeight = 0F;
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
        // pushes the overworld's rain state via WeatherPayload (WeatherSyncState cache).
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
        float r = 0.85F, g = 0.7F, b = 0.4F;

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
        updateHorizonTint(clientLevel, player.blockPosition(),
                this.config.weatherOptions.enableBiomeFogColor);

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
        int range = net.minecraft.client.Minecraft.getInstance().options.graphicsPreset().get()
                == net.minecraft.client.GraphicsPreset.FANCY ? 10 : 5;
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
     * Tracks the desert horizon tint: weight eases toward 1 while in a biome with a
     * configured fogColor, toward 0 otherwise; the color eases toward the configured
     * fog color so crossing between biomes does not pop.
     */
    private void updateHorizonTint(ClientLevel level, net.minecraft.core.BlockPos pos,
                                   boolean enabled) {
        // The target is the AVERAGE of the configured fog colour over a small
        // ring, not the colour of the one biome under the player's feet. See
        // FOG_SAMPLES: a single sample alternates across a border and the ease
        // below then spends forever chasing it.
        float tr = 0F;
        float tg = 0F;
        float tb = 0F;
        int hits = 0;
        if (enabled) {
            for (final float[] sample : FOG_SAMPLES) {
                this.cursor.set(pos.getX() + (int) sample[0], pos.getY(),
                        pos.getZ() + (int) sample[1]);
                var at = level.getBiome(this.cursor).value();
                var info = ((IBiomeExtended) (Object) at).dsurround_getInfo();
                if (info == null)
                    continue;
                var color = info.getFogColor();
                if (color == null)
                    continue;
                tr += ((color.getValue() >> 16) & 0xFF) / 255F;
                tg += ((color.getValue() >> 8) & 0xFF) / 255F;
                tb += (color.getValue() & 0xFF) / 255F;
                hits++;
            }
        }
        if (hits > 0) {
            tr /= hits;
            tg /= hits;
            tb /= hits;
            this.horizonR += (tr - this.horizonR) * 0.1F;
            this.horizonG += (tg - this.horizonG) * 0.1F;
            this.horizonB += (tb - this.horizonB) * 0.1F;
            this.horizonWeight = Math.min(1F, this.horizonWeight + TINT_FADE_IN);
            return;
        }
        this.horizonWeight = Math.max(0F, this.horizonWeight - TINT_FADE_OUT);
    }

    /**
     * Applies the desert horizon tint to the atmospheric fog color. The vanilla color
     * already includes day/night and weather adjustments, so the configured color is
     * normalized (max channel = 1) and applied multiplicatively - only the hue is
     * imposed, brightness stays vanilla.
     */
    private void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        // Counted so the diagnostic line can tell "this route is live" from
        // "this route never ran".
        PrecipitationIntensity.noteFogColorHook();

        // 26.1 has no FogRenderer, so unlike the other versions there is no
        // second rain pass to redirect - and no way to tell from the code
        // whether vanilla already folded rain into the colour it hands us
        // here. Sample the incoming colour against the sky colour a few times
        // a minute: if the fog still equals the sky while it is raining, rain
        // darkening is missing on this version and belongs right here.
        if (++this.fogProbeCounter >= 100) {
            this.fogProbeCounter = 0;
            var level = net.minecraft.client.Minecraft.getInstance().level;
            this.logger.info("[FOG-26] in=%.3f,%.3f,%.3f rain=%.3f storm=%.3f dust=%.3f hw=%.3f",
                    event.getRed(), event.getGreen(), event.getBlue(),
                    level == null ? -1F
                            : PrecipitationIntensity.fogColorRainLevel(level, 1F),
                    level == null ? -1F : PrecipitationIntensity.stormRainLevel(level, 1F),
                    PrecipitationIntensity.dustIntensity(), this.horizonWeight);
        }

        final float dust = PrecipitationIntensity.dustIntensity();
        if (dust > 0.01F) {
            final float dMax = Math.max(this.veilR, Math.max(this.veilG, this.veilB));
            if (dMax > 0.001F) {
                final float w = dust * DUST_SKY_TINT;
                event.setRed(lerp(event.getRed(), event.getRed() * (this.veilR / dMax), w));
                event.setGreen(lerp(event.getGreen(), event.getGreen() * (this.veilG / dMax), w));
                event.setBlue(lerp(event.getBlue(), event.getBlue() * (this.veilB / dMax), w));
            }
        }
        if (this.horizonWeight <= 0.01F)
            return;
        // Only the atmospheric (horizon) fog - not underwater/lava/powdered snow.
        if (event.getCamera().getFluidInCamera() != FogType.NONE)
            return;

        float max = Math.max(this.horizonR, Math.max(this.horizonG, this.horizonB));
        if (max <= 0.001F)
            return;
        float mR = this.horizonR / max;
        float mG = this.horizonG / max;
        float mB = this.horizonB / max;
        float w = this.horizonWeight;

        event.setRed(lerp(event.getRed(), event.getRed() * mR, w));
        event.setGreen(lerp(event.getGreen(), event.getGreen() * mG, w));
        event.setBlue(lerp(event.getBlue(), event.getBlue() * mB, w));
    }

    private static float lerp(float from, float to, float delta) {
        return from + (to - from) * delta;
    }

    /**
     * Picks the dust strip for the current weather intensity. Thresholds match the
     * 1.12.2 Weather.Properties levels; thunderstorms push the intensity up a tier.
     */
    private static Identifier dustTexture(ClientLevel level) {
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
     * (or in the nether). Registered via registerBelowAll so it renders beneath every
     * HUD element, including the Xaero minimap. The desert-clear state draws nothing
     * here - its horizon tint comes from the fog color modulation.
     */
    public void renderGui(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker tracker) {
        final float intensity = Math.max(this.netherTint, this.fullscreenTint);
        if (intensity <= 0.005F)
            return;

        var mc = Minecraft.getInstance();
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        final int alpha = (int) (Math.min(intensity * 0.6F, DUST_VEIL_FULLSCREEN_MAX) * 255F);
        if (alpha <= 0)
            return;
        // Desert haze is yellow-brown; the nether haze is a pale red-brown.
        final boolean nether = mc.level != null && mc.level.dimension() == Level.NETHER;
        final int color = (alpha << 24) | (nether ? 0x00C07A5A : 0x00D8B266);
        graphics.fill(0, 0, width, height, color);
    }

    /**
     * The sandstorm dust veil: camera-facing vertical quads in a grid around the
     * player, textured with the intensity-graded dust strip and scrolling UVs - the
     * 1.12.2 StormRenderer mechanism. Rendered into the vanilla weather render target
     * right after the vanilla weather pass (same pipeline family, so terrain
     * occlusion and translucency behave like rain).
     */
    private void onAfterWeather(RenderLevelStageEvent.AfterWeather event) {
        if (this.veilBroken)
            return;
        try {
            this.renderVeil(event);
        } catch (Throwable t) {
            // One deterministic failure must not hit the event bus every frame -
            // degrade to vanilla for the session. 26.1's encoder pipeline has no
            // shared building state to clean up.
            this.veilBroken = true;
            this.logger.error(t, "Sandstorm veil render failed - disabling until restart");
        }
    }

    private void renderVeil(RenderLevelStageEvent.AfterWeather event) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null)
            return;
        final boolean nether = level.dimension() == Level.NETHER;
        final float tint = nether ? this.netherTint : this.fullscreenTint;
        if (tint < 0.02F)
            return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int ticks = (int) level.getGameTime();
        int range = net.minecraft.client.Minecraft.getInstance().options.graphicsPreset().get()
                == net.minecraft.client.GraphicsPreset.FANCY ? 10 : 5;
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

        // 26.1: blend/depth/cull state lives on the WEATHER render pipelines - no
        // fixed-function calls needed (RenderSystem.enableBlend et al. are gone).

        int quadCount = (range * 2 + 1) * (range * 2 + 1);
        var dustTexture = mc.getTextureManager().getTexture(this.veilTexture);

        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(quadCount * DefaultVertexFormat.PARTICLE.getVertexSize() * 4)) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

            int quads = 0;
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
                    // The veil used to draw every column of the grid at any
                    // strength, so a faint haze was a uniform field turned
                    // down - it read as "the world got dimmer", not "there is
                    // dust blowing". Same stratified rank the rain uses: a
                    // per-column coin flip clumps, and the survivor set has to
                    // be world-anchored or the field shimmers as you walk.
                    final float columnFade = (veilDensity
                            - PrecipitationRenderer.columnRank(gridX, gridZ)) / VEIL_FADE_BAND;
                    if (columnFade <= 0F)
                        continue;
                    final float columnAlpha = columnFade > 1F ? 1F : columnFade;
                    // 1.12.2 radial sheet: the cross-section is perpendicular to the
                    // player->column radius, so the columns sweep around the player. This
                    // replaces two nextFloat() draws - which also restores the 1.12.2
                    // random sequence (setSeed -> nextDouble -> nextGaussian), so the
                    // per-column UV shear and nether dust colours now match 1.12.2 exactly.
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
                    // same rate no matter how hard it was blowing.
                    double d9 = this.columnRandom.nextDouble() + animClock * drift * this.columnRandom.nextGaussian();
                    double d10 = this.columnRandom.nextDouble() + animClock * this.columnRandom.nextGaussian() * 0.001D;

                    double d6 = gridX + 0.5 - player.getX();
                    double d7 = gridZ + 0.5 - player.getZ();
                    float f3 = Mth.sqrt((float) (d6 * d6 + d7 * d7)) / range;
                    int alpha = (int) (((1.0F - f3 * f3) * 0.3F + 0.5F) * veilAlpha * columnAlpha * 255F);
                    long lightKey = BlockPos.asLong(gridX, k2, gridZ);
                    long lightVal;
                    if (this.lightCache.containsKey(lightKey)) {
                        lightVal = this.lightCache.get(lightKey);
                    } else {
                        lightVal = (LevelRenderer.getLightCoords(level, this.cursor.set(gridX, k2, gridZ)) * 3 + 15728880) / 4;
                        this.lightCache.put(lightKey, lightVal);
                    }
                    int light = (int) lightVal;
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
                    int argb = (alpha << 24) | (cr << 16) | (cg << 8) | cb;

                    float x0 = gridX - rainX + 0.5F - (float) cam.x;
                    float z0 = gridZ - rainY + 0.5F - (float) cam.z;
                    float x1 = gridX + rainX + 0.5F - (float) cam.x;
                    float z1 = gridZ + rainY + 0.5F - (float) cam.z;
                    float y0 = k2 - (float) cam.y;
                    float y1 = l2 - (float) cam.y;
                    float v0 = k2 * 0.25F + (float) d8;
                    float v1 = l2 * 0.25F + (float) d8;

                    bufferBuilder.addVertex(x0, y0, z0).setUv((float) d9, v0 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x1, y0, z1).setUv(1F + (float) d9, v0 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x1, y1, z1).setUv(1F + (float) d9, v1 + (float) d10).setColor(argb).setLight(light);
                    bufferBuilder.addVertex(x0, y1, z0).setUv((float) d9, v1 + (float) d10).setColor(argb).setLight(light);
                    quads++;
                }
            }

            if (quads == 0)
                return;

            GpuBuffer vertexBuffer;
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            try (MeshData mesh = bufferBuilder.buildOrThrow()) {
                vertexBuffer = RenderPipelines.WEATHER_NO_DEPTH_WRITE.getVertexFormat()
                    .uploadImmediateVertexBuffer(mesh.vertexBuffer());
                var autoIndices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                indexBuffer = autoIndices.getBuffer(mesh.drawState().indexCount());
                indexType = autoIndices.type();
            }

            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());

            var weatherTarget = OutputTarget.WEATHER_TARGET.getRenderTarget();
            GpuTextureView colorTexture = weatherTarget.getColorTextureView();
            GpuTextureView depthTexture = weatherTarget.getDepthTextureView();
            var renderPipeline = Minecraft.useShaderTransparency()
                ? RenderPipelines.WEATHER_DEPTH_WRITE
                : RenderPipelines.WEATHER_NO_DEPTH_WRITE;

            try (var renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "DSurround Dust Veil", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
                renderPass.setPipeline(renderPipeline);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.bindTexture(
                    "Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                );
                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.setVertexBuffer(0, vertexBuffer);
                renderPass.bindTexture("Sampler0", dustTexture.getTextureView(),
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
                renderPass.drawIndexed(0, 0, quads * 6, 1);
            }
        }
    }

}