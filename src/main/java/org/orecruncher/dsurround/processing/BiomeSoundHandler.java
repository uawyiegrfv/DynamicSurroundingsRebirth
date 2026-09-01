package org.orecruncher.dsurround.processing;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
    private static final Identifier LEAF_WIND = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "biome.leaf_wind");
    private static final float LEAF_WIND_DAY_CHANCE = 0.0011F;
    private static final float LEAF_WIND_NIGHT_CHANCE = 0.0033F;

    // Intermittent sculk clicking in the Deep Dark, reminiscent of sculk sensors.
    // Independent of the shared mood chance. Scanned every 4 ticks (~0.2s), so a
    // burst every ~2 minutes: 1 / (120s / 0.2s) = 1/600 ~ 0.0017
    private static final Identifier SCULK_CLICK = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "biome.sculk_click");
    private static final float SCULK_CLICK_CHANCE = 0.0017F;
    private static final Identifier DEEP_DARK_BIOME = Identifier.fromNamespaceAndPath("minecraft", "deep_dark");
    private static final Identifier DEEP_DARK_DRONE = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark");
    private static final Identifier DEEP_DARK_HEARTBEAT = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark_heartbeat");
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
    }

    private boolean doBiomeSounds() {
        return this.config.soundOptions.enableBiomeSounds;
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
    }

    @Override
    public void onDisconnect() {
        clearSounds();
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
        if (this.config.soundOptions.enableBiomeSounds && !this.scanner.isInside()) {
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
                        float vol = dsBiomeVolume() * (0.75F + RANDOM.nextFloat() * 0.25F);
                        var instance = factory.createAtLocation(pos, vol);
                        this.audioPlayer.play(instance);
                    }
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
        if (this.config.soundOptions.enableBiomeSounds) {
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
