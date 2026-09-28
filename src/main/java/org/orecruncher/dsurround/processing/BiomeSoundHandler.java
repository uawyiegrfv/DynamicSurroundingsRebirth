package org.orecruncher.dsurround.processing;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.config.libraries.IBiomeLibrary;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.SyntheticBiome;
import org.orecruncher.dsurround.config.SoundEventType;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.math.MathStuff;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.sound.BackgroundSoundLoop;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;

public final class BiomeSoundHandler extends AbstractClientHandler {

    public static final int SCAN_INTERVAL = 4;
    public static final int MOOD_SOUND_MIN_RANGE = 8;
    public static final int MOOD_SOUND_MAX_RANGE = 16;

    // Volume scale applied to biome ambient sounds while the player is inside
    // (covered ceiling). Reduces outdoor ambience (birds, insects, wind, animal
    // calls) inside houses and caves while keeping a faint sense of the outside.
    private static final float INDOOR_VOLUME_SCALE = 0.15F;

    private final IBiomeLibrary biomeLibrary;
    private final IAudioPlayer audioPlayer;
    private final ITickCount tickCount;
    private final Scanners scanner;

    // Leaf-wind gust in wooded biomes: an independent intermittent sound so it does not
    // share the mood chance with bird calls etc. Scanned every 4 ticks (~0.2s), so a
    // gust every ~3 minutes in daytime and ~1 minute at night:
    //   day   = 1 / (180s / 0.2s) = 1/900  ~ 0.0011
    //   night = 1 / (60s  / 0.2s) = 1/300  ~ 0.0033
    private static final ResourceLocation LEAF_WIND = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.leaf_wind");
    private static final ResourceLocation WIND_GENERIC = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.wind.plains");
    private static final ResourceLocation[] RAIN_TEXTURE_WIND = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind1"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind2"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind3")};
    private static final ResourceLocation[] RAIN_TEXTURE_LEAF = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaf1"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaf2")};
    /**
     * Loudness lift for the rain wind bed. The assets are quiet field recordings
     * (wind.ogg sits at -34.8 dB RMS); as one-shot acoustics that was fine, as a
     * continuous bed it read as "barely there". All beds share this gain, and the
     * per-asset trims keep their relative matching on top of it.
     */
    private static final float RAIN_WIND_BED_GAIN = 2.5F;

    // ---- rain wind layer (A-line) ------------------------------------------
    // Sunny behavior is untouched: everything below only runs while the graded
    // precipitation system owns the weather, and it fades out with the storm.

    /** Intensity below which a windless biome gets no rain wind bed at all: it
     *  keeps drizzle from howling across the plains and leaves the sound source
     *  free. Biomes with their own wind asset skip this gate - their bed is a
     *  pure proportion of the intensity and dies on its own at the low end. */
    private static final float RAIN_WIND_MIN_INTENSITY = 0.3F;
    /** Leaf gust pacing: frequency rises with intensity, divided by the event's
     *  rolled gust amplitude (convective profiles gust more often than steady
     *  rain - GustResponse semantics, not a second curve). */
    private static final float RAIN_LEAF_MIN_INTENSITY = 0.25F;
    private static final float RAIN_LEAF_INTERVAL_MAX_S = 16F;
    private static final float RAIN_LEAF_INTERVAL_MIN_S = 5F;
    /** Wind -> leaves causality: 0.2 s + up to 0.6 s of jitter, in ticks. */
    private static final long RAIN_LEAF_DELAY_TICKS = 4L;
    private static final long RAIN_LEAF_DELAY_JITTER = 12L;

    private BackgroundSoundLoop rainWindLoop;
    private Object rainWindAsset;
    private long rainLeafNextGustTick;
    private long rainLeafFireTick = -1L;
    private float rainLeafFireVol;
    private boolean rainLeafFirePositioned;
    private ISoundFactory rainLeafFireFactory;
    private static final float LEAF_WIND_DAY_CHANCE = 0.0011F;
    private static final float LEAF_WIND_NIGHT_CHANCE = 0.0033F;

    // Intermittent sculk clicking in the Deep Dark, reminiscent of sculk sensors.
    // Independent of the shared mood chance. Scanned every 4 ticks (~0.2s), so a
    // burst every ~2 minutes: 1 / (120s / 0.2s) = 1/600 ~ 0.0017
    private static final ResourceLocation SCULK_CLICK = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.sculk_click");
    private static final float SCULK_CLICK_CHANCE = 0.0017F;
    private static final ResourceLocation DEEP_DARK_BIOME = ResourceLocation.fromNamespaceAndPath("minecraft", "deep_dark");
    private static final ResourceLocation DEEP_DARK_DRONE = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark");
    private static final ResourceLocation DEEP_DARK_HEARTBEAT = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark_heartbeat");
    // The drone loop is an inherently loud low rumble; scale it down a touch so it
    // sits under the heartbeat instead of dominating the deep-dark ambience.
    private static final float DEEP_DARK_DRONE_VOLUME_SCALE = 0.8F;

    // Scratch map used for calculating what sounds need to be playing
    private final Object2FloatOpenHashMap<ISoundFactory> workMap = new Object2FloatOpenHashMap<>(8, Hash.DEFAULT_LOAD_FACTOR);
    // List of emitters that are managing the currently playing biome-related sounds
    private final ObjectArray<BiomeSoundEmitter> emitters = new ObjectArray<>(8);

    public BiomeSoundHandler(IBiomeLibrary biomeLibrary, IAudioPlayer audioPlayer, ITickCount tickCount, Scanners scanner, Configuration config, IModLog logger) {
        super("Biome Sounds", config, logger);
        this.audioPlayer = audioPlayer;
        this.biomeLibrary = biomeLibrary;
        this.tickCount = tickCount;
        this.scanner = scanner;
        this.workMap.defaultReturnValue(0F);
        this.dimensionInformation = org.orecruncher.dsurround.lib.di.ContainerManager
                .resolve(org.orecruncher.dsurround.config.libraries.IDimensionInformation.class);
    }

    private final org.orecruncher.dsurround.config.libraries.IDimensionInformation dimensionInformation;

    /**
     * Whether biome ambience should play at all: the global sound option AND the dimension's own
     * setting.
     *
     * <p>dimensions.json has carried playBiomeSounds all along, and DimensionInfo parsed and stored it,
     * but there was no accessor and no reader - so a pack that set it to false still got biome ambience.
     * Checking it here covers every caller, because this is the single gate they all pass through.
     */
    private boolean doBiomeSounds() {
        return this.config.soundOptions.enableBiomeSounds && this.dimensionInformation.playBiomeSounds();
    }

    private void generateBiomeSounds() {
        // Get the biomes that have been scanned in the area along with the amount of area
        // each occupies. For each of these biomes, obtain the sounds for that biome into the
        // work array. The volume of each sound will be scaled by the amount of area being
        // occupied. This will result in the sounds for the most dominant biomes being louder
        // than the others.
        final float area = this.scanner.getBiomeArea();
        final boolean inside = this.scanner.isInside();
        // Indoor ambient sounds are heavily attenuated so outdoor ambience does
        // not carry through walls and ceilings.
        for (var kvp : this.scanner.getBiomes().reference2IntEntrySet()) {
            var acoustics = kvp.getKey().findBiomeSoundMatches();
            final float areaScale = 0.05F + 0.95F * (kvp.getIntValue() / area);
            for (var acoustic : acoustics) {
                // The Deep Dark is a naturally underground biome; its own ambience loops
                // must not be attenuated as if they were outdoor sound leaking inside.
                final float scale = (inside && !isDeepDarkAmbience(acoustic)) ? INDOOR_VOLUME_SCALE : 1.0F;
                // The drone additionally gets its own (reduced) volume scale so it does
                // not dominate the heartbeat and other ambience.
                final float droneScale = DEEP_DARK_DRONE.equals(acoustic.getLocation()) ? DEEP_DARK_DRONE_VOLUME_SCALE : 1.0F;
                this.workMap.addTo(acoustic, scale * droneScale * areaScale * dsBiomeVolume());
            }
        }
    }

    @Override
    public void process(final Player player) {
        this.emitters.forEach(BiomeSoundEmitter::tick);
        if ((this.tickCount.getTickCount() % SCAN_INTERVAL) == 0) {
            handleBiomeSounds(player);
        }
    }

    @Override
    public void onConnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        this.rainLeafFireTick = -1L;
    }

    @Override
    public void onDisconnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        this.rainLeafFireTick = -1L;
    }

    private void handleBiomeSounds(final Player player) {
        this.workMap.clear();

        // Only gather data if the player is alive. If the player is dead, the biome sounds will cease playing.
        if (player.isAlive()) {

            final boolean biomeSounds = doBiomeSounds();

            if (biomeSounds)
                generateBiomeSounds();

            // The following will look at the PLAYER and VILLAGE biomes, two artificial biomes
            // that are used to configure effects.
            final ObjectArray<ISoundFactory> playerSounds = new ObjectArray<>();
            final BiomeInfo internalPlayerBiomeInfo = this.biomeLibrary.getBiomeInfo(SyntheticBiome.PLAYER);
            final BiomeInfo internalVillageBiomeInfo = this.biomeLibrary.getBiomeInfo(SyntheticBiome.VILLAGE);
            // The synthetic biomes may not be initialized yet (e.g. while on the main menu
            // or before the first world connect), so guard against null.
            if (internalPlayerBiomeInfo != null)
                playerSounds.addAll(internalPlayerBiomeInfo.findBiomeSoundMatches());
            if (internalVillageBiomeInfo != null)
                playerSounds.addAll(internalVillageBiomeInfo.findBiomeSoundMatches());
            playerSounds.forEach(fx -> this.workMap.put(fx, 1.0F));

            // This will cause extra spot sounds to play, like birds chirping, wolves growling, etc.
            if (biomeSounds) {
                BiomeInfo playerBiome = this.scanner.playerLogicBiomeInfo();
                handleAddOnSounds(player, playerBiome);
                if (internalPlayerBiomeInfo != null)
                    handleAddOnSounds(player, internalPlayerBiomeInfo);
                if (internalVillageBiomeInfo != null)
                    handleAddOnSounds(player, internalVillageBiomeInfo);
                handleLeafWindGust(player);
                handleRainWindBed(player);
                handleRainTexture(player);
                handleSculkClick(player);
            }
        }

        // At this point, we trigger the examination of the existing emitters list, comparing it to the
        // generated work map. Adjustments will be made accordingly.
        queueAmbientSounds();
    }

    private void handleAddOnSounds(Player player, BiomeInfo info) {
        if (info == null)
            return;
        final float indoorScale = this.scanner.isInside() ? INDOOR_VOLUME_SCALE : 1.0F;
        info.getExtraSound(SoundEventType.MOOD, RANDOM).ifPresent(s -> {
            var instance = createMoodInstance(player, s, indoorScale);
            this.audioPlayer.play(instance);
        });

        info.getExtraSound(SoundEventType.ADDITION, RANDOM).ifPresent(s -> {
            var instance = s.createAsAdditional();
            this.audioPlayer.play(instance);
        });
    }

    /**
     * Intermittent gust of wind rustling the leaves in any wooded biome. Independent of the
     * mood chance (which is shared across all mood sounds in a biome), so it never inflates
     * the frequency of bird calls etc. More likely at night. Fires once per scan interval
     * (4 ticks) with its own probability; plays a short gust from three sources spread
     * around the player at canopy height, so the wind sweeps through the treetops.
     */
    private void handleLeafWindGust(Player player) {
        if (doBiomeSounds() && !this.scanner.isInside()) {
            var biome = this.scanner.playerLogicBiomeInfo();
            if (biome != null && isWooded(biome)) {
                // Rain and snow cover the sound; skip then.
                var level = player.level();
                if (level.isRaining())
                    return;

                float chance = DayCycle.isNighttime(level) ? LEAF_WIND_NIGHT_CHANCE : LEAF_WIND_DAY_CHANCE;
                if (RANDOM.nextDouble() < chance) {
                    var factory = ContainerManager.resolve(ISoundLibrary.class)
                            .getSoundFactoryOrDefault(LEAF_WIND);
                    // Surround gust: three sources spread around the player (120 deg apart
                    // with jitter, mixed 8-16 block distances) floating at canopy height
                    // (~6-8 blocks above the ground) so the wind sweeps through the treetops.
                    playLeafSurround(player, factory, 1.0F);
                }
            }
        }
    }

    /**
     * Intermittent sculk clicking in the Deep Dark, reminiscent of sculk sensors.
     * Independent of the shared mood chance, so it never inflates other mood sounds.
     * Plays a short burst of clicks at a random spot near the player.
     */
    private void handleSculkClick(Player player) {
        if (doBiomeSounds()) {
            var biome = this.scanner.playerLogicBiomeInfo();
            if (biome != null && DEEP_DARK_BIOME.equals(biome.getBiomeId())
                    && RANDOM.nextDouble() < SCULK_CLICK_CHANCE) {
                var factory = ContainerManager.resolve(ISoundLibrary.class)
                        .getSoundFactoryOrDefault(SCULK_CLICK);
                var offset = MathStuff.randomPoint(MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
                var instance = factory.createAtLocation(player.getEyePosition().add(offset), dsBiomeVolume());
                this.audioPlayer.play(instance);
            }
        }
    }

    /**
     * The rain wind bed: a continuous, non-attenuated loop whose volume tracks
     * the graded intensity. A biome with its own wind acoustic uses that asset;
     * everywhere else uses the generic wind, gated by a minimum intensity (a
     * drizzle on the plains should not howl, and the source stays free). The
     * bed follows the ungated intensity - wind is an attribute of the storm,
     * not of where the drops land, so it keeps blowing under a roof, muffled.
     */
    private void handleRainWindBed(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds()) {
            fadeRainWind();
            return;
        }
        final float intensity = PrecipitationIntensity.ambient();
        ResourceLocation wind = null;
        float trim = 1F;
        var info = this.scanner.playerLogicBiomeInfo();
        if (info != null) {
            for (var factory : info.findBiomeSoundMatches()) {
                if (isWindFactory(factory)) {
                    wind = factory.getLocation();
                    trim = windTrim(factory);
                    break;
                }
            }
        }
        float target;
        if (wind != null) {
            target = intensity;
        } else {
            wind = WIND_GENERIC;
            target = intensity <= RAIN_WIND_MIN_INTENSITY
                    ? 0F
                    : (intensity - RAIN_WIND_MIN_INTENSITY) / (1F - RAIN_WIND_MIN_INTENSITY);
        }
        if (this.scanner.isInside())
            target *= INDOOR_VOLUME_SCALE;
        if (target <= 0.005F) {
            fadeRainWind();
            return;
        }
        if (this.rainWindLoop == null || !wind.equals(this.rainWindAsset)) {
            // Crossfade on an asset change: the old loop keeps playing until its
            // scale reaches zero and stops itself, the new one eases in.
            fadeRainWind();
            var factory = ContainerManager.resolve(ISoundLibrary.class)
                    .getSoundFactoryOrDefault(wind);
            this.rainWindLoop = factory.createBackgroundSoundLoop();
            this.rainWindLoop.setVolume(trim * RAIN_WIND_BED_GAIN);
            this.rainWindAsset = wind;
            this.rainWindLoop.setScaleTarget(target);
            this.audioPlayer.play(this.rainWindLoop);
        } else {
            this.rainWindLoop.setScaleTarget(target);
        }
    }

    private void fadeRainWind() {
        if (this.rainWindLoop != null) {
            this.rainWindLoop.setScaleTarget(0F); // eases down, stops itself at zero
            this.rainWindLoop = null;
            this.rainWindAsset = null;
        }
    }

    /** Whether this factory is one of the wind assets biomes.json can assign. */
    private static boolean isWindFactory(final ISoundFactory factory) {
        final String loc = factory.getLocation().toString();
        return loc.endsWith("biome.wind")
                || loc.endsWith("biome.wind.hills")
                || loc.endsWith("biome.wind.mountains")
                || loc.endsWith("biome.wind.arctic")
                || loc.endsWith("biome.wind.desert");
    }

    /**
     * Per-asset loudness trim, referenced to wind.ogg (-34.8 dB RMS): the wind
     * assets span ~15 dB and arctic_wind is stereo besides, so one shared curve
     * would make the arctic blast. trim = 10^((ref - asset) / 20).
     */
    private static float windTrim(final ISoundFactory factory) {
        final String loc = factory.getLocation().toString();
        if (loc.endsWith("biome.wind.arctic"))
            return 0.33F; // -25.1 dB, and stereo
        if (loc.endsWith("biome.wind.desert"))
            return 1.86F; // -40.2 dB
        return 1.0F; // wind.ogg is the reference (hills/mountains resolve to it)
    }

    /**
     * The rain texture layer, riding on top of the wind bed. Wooded biomes get
     * the leaf-dominant clips through the canopy surround (wind first, leaves
     * answer 0.2-0.8 s later); everywhere else the wind-dominant clips are
     * layered onto the bed unpositioned - the same wind, gusting differently.
     * Both pools are intensity-paced and their clips are normalized ~8 dB over
     * the bed reference, with the playback volume tracking the intensity so the
     * layer stays just above the bed at every strength instead of jumping out.
     */
    private void handleRainTexture(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds() || this.scanner.isInside()) {
            this.rainLeafFireTick = -1L;
            this.rainLeafNextGustTick = 0L;
            return;
        }
        final long now = this.tickCount.getTickCount();
        if (this.rainLeafFireTick >= 0L) {
            if (now >= this.rainLeafFireTick) {
                fireRainTexture(player);
                this.rainLeafFireTick = -1L;
            }
            return; // a clip is pending or firing; pace one at a time
        }
        if (now < this.rainLeafNextGustTick)
            return;
        final float intensity = PrecipitationIntensity.ambient();
        var info = this.scanner.playerLogicBiomeInfo();
        final boolean wooded = info != null && isWooded(info);
        final float min = wooded ? RAIN_LEAF_MIN_INTENSITY : RAIN_WIND_MIN_INTENSITY;
        if (intensity < min) {
            this.rainLeafNextGustTick = now + 20L; // re-check in a second
            return;
        }
        final ResourceLocation[] pool = wooded ? RAIN_TEXTURE_LEAF : RAIN_TEXTURE_WIND;
        this.rainLeafFireFactory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactoryOrDefault(pool[RANDOM.nextInt(pool.length)]);
        float t = (intensity - min) / (1F - min);
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        float amp = PrecipitationIntensity.gustAmplitude();
        amp = amp < 0F ? 0F : (amp > 2F ? 2F : amp);
        final float seconds =
                (RAIN_LEAF_INTERVAL_MAX_S + (RAIN_LEAF_INTERVAL_MIN_S - RAIN_LEAF_INTERVAL_MAX_S) * t)
                        / (0.6F + 0.8F * amp);
        this.rainLeafNextGustTick = now + (long) (seconds * 20F);
        this.rainLeafFireVol = 0.35F + 0.65F * t;
        this.rainLeafFirePositioned = wooded;
        this.rainLeafFireTick = wooded
                ? now + RAIN_LEAF_DELAY_TICKS + (long) (RANDOM.nextDouble() * RAIN_LEAF_DELAY_JITTER)
                : now;
    }

    private void fireRainTexture(final Player player) {
        if (this.rainLeafFirePositioned) {
            playLeafSurround(player, this.rainLeafFireFactory, this.rainLeafFireVol);
            return;
        }
        // Non-positioned, like the wind bed it rides on - the gust is everywhere
        // at once, not off in a direction. Volume goes in at construction: the
        // field is protected and writing it does not compile.
        var instance = this.rainLeafFireFactory.createAsAdditional(this.rainLeafFireVol);
        this.audioPlayer.play(instance);
    }

    /**
     * The three-source canopy surround: 120 deg apart with jitter, 8-16 blocks
     * out, floating at tree-crown height so the wind sweeps through the
     * treetops. Shared by the sunny gust chance and the rain layer.
     */
    private void playLeafSurround(final Player player, final ISoundFactory factory, final float volScale) {
        final int sources = 3;
        final double baseAngle = RANDOM.nextDouble() * Math.PI * 2D;
        for (int i = 0; i < sources; i++) {
            final double angle = baseAngle + i * (Math.PI * 2D / sources)
                    + (RANDOM.nextDouble() - 0.5D) * 0.8D;
            final double dist = MOOD_SOUND_MIN_RANGE
                    + RANDOM.nextDouble() * (MOOD_SOUND_MAX_RANGE - MOOD_SOUND_MIN_RANGE);
            final double y = player.getY() + 6.0D + RANDOM.nextDouble() * 2.0D;
            var pos = new Vec3(player.getX() + Math.cos(angle) * dist, y,
                    player.getZ() + Math.sin(angle) * dist);
            float vol = dsBiomeVolume() * volScale * (0.75F + RANDOM.nextFloat() * 0.25F);
            var instance = factory.createAtLocation(pos, vol);
            this.audioPlayer.play(instance);
        }
    }

    /** True if this acoustic is one of the Deep Dark's own ambience loops (drone or heartbeat). */
    private static boolean isDeepDarkAmbience(ISoundFactory acoustic) {
        var loc = acoustic.getLocation();
        return DEEP_DARK_DRONE.equals(loc) || DEEP_DARK_HEARTBEAT.equals(loc);
    }

    /** True if the biome is wooded (any tree-bearing biome). */
    private static boolean isWooded(BiomeInfo biome) {
        var traits = biome.getTraits();
        return traits.contains(BiomeTrait.FOREST)
                || traits.contains(BiomeTrait.CONIFEROUS)
                || traits.contains(BiomeTrait.DECIDUOUS)
                || traits.contains(BiomeTrait.JUNGLE);
    }

    private SimpleSoundInstance createMoodInstance(Player player, ISoundFactory factory, float volumeScale) {
        var scale = volumeScale * dsBiomeVolume();
        if (scale == 1.0F)
            return factory.createAsMood(player, MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
        // createAsMood() has no volume control, so rebuild the same random-offset
        // instance manually with an attenuated volume when inside.
        var offset = MathStuff.randomPoint(MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
        return factory.createAtLocation(player.getEyePosition().add(offset), scale);
    }

    // Config-driven volume multiplier applied to biome ambience (sound-options slider).
    private float dsBiomeVolume() { return (float) this.config.soundOptions.biomeVolume; }

    private void queueAmbientSounds() {
        // Iterate through the existing emitters:
        // * If done, remove
        // * If not in the incoming list, fade out
        // * If it does exist, update volume throttle and fade in if needed
        this.emitters.removeIf(entry -> {
            if (entry.isDone()) {
                return true;
            }
            final float volume = this.workMap.getFloat(entry.getSoundEvent());
            if (volume > 0) {
                entry.setVolumeScale(volume);
                if (entry.isFading())
                    entry.fadeIn();
                this.workMap.removeFloat(entry.getSoundEvent());
            } else if (!entry.isFading()) {
                entry.fadeOut();
            }
            return false;
        });

        // Any sounds left in the list are new and need an emitter created.
        this.workMap.forEach((fx, volume) -> {
            final BiomeSoundEmitter e = new BiomeSoundEmitter(this.logger, this.audioPlayer, fx);
            e.setVolumeScale(volume);
            this.emitters.add(e);
        });
    }

    public void clearSounds() {
        this.emitters.forEach(BiomeSoundEmitter::stop);
        this.emitters.clear();
        this.workMap.clear();
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var panelText = event.getSectionText(CollectDiagnosticsEvent.Section.Emitters);
        this.emitters.forEach(backgroundAcousticEmitter -> panelText.add(Component.literal(backgroundAcousticEmitter.toString())));
    }
}
