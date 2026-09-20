package org.orecruncher.dsurround;

import org.orecruncher.dsurround.config.CompassStyle;
import org.orecruncher.dsurround.config.FootprintStyle;
import org.orecruncher.dsurround.config.WaterRippleStyle;
import org.orecruncher.dsurround.lib.config.ConfigurationData;
import org.orecruncher.dsurround.lib.config.ConfigurationData.*;

@ConfigPlacement(folderName = Constants.MOD_ID, fileName = Constants.MOD_ID)
@TranslationRoot(Constants.MOD_ID + ".config")
public class Configuration extends ConfigurationData {

    @Property
    @Comment("Configuration options for modifying logging behavior")
    public final Logging logging = new Logging();

    @Property
    @Comment("Configuration options for modifying Minecraft's Sound System behavior")
    public final SoundSystem soundSystem = new SoundSystem();

    @Property
    @Comment("Configuration options for enhanced sound processing")
    public final EnhancedSounds enhancedSounds = new EnhancedSounds();

    @Property
    @Comment("Configuration options for sounds in general")
    public final SoundOptions soundOptions = new SoundOptions();

    @Property
    @Comment("Configuration options for block effects")
    public final BlockEffects blockEffects = new BlockEffects();

    @Property
    @Comment("Configuration options for entity effects")
    public final EntityEffects entityEffects = new EntityEffects();

    @Property
    @Comment("Configuration options for the damage, healing and critical-hit text popoffs")
    public final PopoffNumbers popoffNumbers = new PopoffNumbers();

    @Property
    @Comment("Configuration options for footstep accent effects")
    public final FootstepAccents footstepAccents = new FootstepAccents();

    @Property
    @Comment("Configuration options for tweaking particle behavior")
    public final ParticleTweaks particleTweaks = new ParticleTweaks();

    @Property
    @Comment("Configuration options for the GUI overlays (compass, clock, map distance, durability highlight)")
    public final CompassAndClockOptions compassAndClockOptions = new CompassAndClockOptions();

    @Property
    @Comment("Configuration options for fog effects")
    public final FogOptions fogOptions = new FogOptions();

    @Property
    @Comment("Configuration options for weather storm effects (desert sandstorm, nether dust)")
    public final WeatherOptions weatherOptions = new WeatherOptions();

    @Property
    @Comment("Configuration options for speech bubbles above player and entity heads (ported back from 1.12.2, off by default)")
    public final SpeechBubbles speechBubbles = new SpeechBubbles();

    @Property
    @Comment("Configuration options for aurora (northern lights) rendering")
    public final AuroraOptions auroraOptions = new AuroraOptions();

    @Property
    @Comment("Configuration options for other things")
    public final OtherOptions otherOptions = new OtherOptions();

    public static class Flags {
        public static final int AUDIO_PLAYER = 0x1;
        public static final int BASIC_SOUND_PLAY = 0x2;
        public static final int RESOURCE_LOADING = 0x4;
    }

    public static class Logging {

        @Property
        @Comment("Enables/disables debug logging of the mod")
        public boolean enableDebugLogging = false;

        @Property
        @Comment("Bitmask for toggling various debug traces")
        public int traceMask = 0;

        @Property
        @Comment("Enable/disable chat window notification of newer updates available")
        public boolean enableModUpdateChatMessage = true;

        @Property
        @Comment("Enable/disable filtering display of tags in the diagnostics overlay")
        public boolean filteredTagView = true;

        @Property
        @RestartRequired
        @Comment("Enable/disable registration of client side commands")
        public boolean registerCommands = true;
    }

    public static class SoundSystem {
        @Property
        @IntegerRange(min = 0, max = 20 * 10)
        @Slider
        @Comment("Ticks between culled sound events (0 to disable culling)")
        public int cullInterval = 20;

        @Property
        @Comment("Enables/disables cancellation of sound that a player will not hear")
        public boolean enableSoundPruning = true;
    }

    public static class EnhancedSounds {
        @Property
        @RestartRequired
        @Comment("Enable/disable enhanced sound processing (reverb, occlusion, etc)")
        public boolean enableEnhancedSounds = true;

        @Property
        @IntegerRange(min = 0, max = 8)
        @Slider
        @RestartRequired
        @Comment("Number of background threads to use for enhanced sound processing (0 means use internal default)")
        public int backgroundThreadWorkers = 0;

        @Property
        @Comment("Enable/disable on the fly conversion of stereo sounds to mono as needed")
        public boolean enableMonoConversion = true;

        @Property
        @Comment("Experimental: tries to free sound channels that have become stuck. Off by default because it can crash the game")
        public boolean enableChannelReaper = false;

        @Property
        @Comment("Enable/disable sound occlusion processing (sound muffling behind blocks). On by default: it is the point of the enhanced audio path, and the model behind it (first Fresnel zone clear fraction, wavelength dependent, multi-plane) is measured rather than guessed. Turning it off restores plain distance attenuation")
        public boolean enableOcclusionProcessing = true;

        @Property
        @IntegerRange(min = 1, max = 32)
        @RestartRequired
        @Comment("Occlusion only: segments a source-to-player ray is split into when measuring how much block material it passes through. Higher = finer distance-weighted occlusion at slightly more cost per ray")
        public int occlusionSegments = 5;

        @Property
        @Comment("Evaluate the environment on the sound thread when a sound starts (default: correct occlusion/reverb on the very first frame) or defer it to the background worker (less sound-thread work when many sounds start in the same tick, at the cost of up to one worker cycle - 50 ms - of default settings at the start of each sound)")
        public boolean evaluateOnSoundThread = true;

        @Property
        @Comment("Occlusion only: use the real wavelength of sound instead of treating every frequency alike. Diffraction loss then follows the Fresnel number of the detour around the obstacle's edge (low frequencies bend around obstacles, high frequencies do not). This is the physically correct direction, and the reason a low rumble carries around a corner while a high clink does not. Turn off to get the previous frequency-blind behaviour back")
        public boolean realismWavelength = true;

        @Property
        @DoubleRange(min = 100.0D, max = 4000.0D)
        @Comment("Occlusion only: the representative frequency in Hz used for the wavelength above. The engine applies ONE low-pass filter per source, so the model has to pick a single band; 500 Hz is a low-mid voice/thud band, where a game's audible occlusion is dominated, and it makes the model generous (low frequencies bend around obstacles) rather than harsh")
        public double realismFrequencyHz = 500.0D;

        @Property
        @DoubleRange(min = 0.0D, max = 64.0D)
        @Comment("Occlusion only: how far along the source-to-listener line block material still counts fully. Beyond that distance the contribution is divided by (1 + d/this), so a wall a few blocks from the source can no longer silence a sound 40 blocks away, while rock genuinely between the two ends still muffles. 0 disables the weighting (every block counts fully, the previous behaviour)")
        public double occlusionFocusDistance = 8.0D;

        @Property
        @Comment("Diagnostics: write the audio evaluation trace to the log (about one line every 250 ms while sounds are being processed). Off by default so a normal session stays quiet; '/dstune probe true' turns it on for a session, and '/dstune' always shows the most recent trace whether or not this is on")
        public boolean logAudioTrace = false;

        @Property
        @Comment("Occlusion only: relative weight of each octave band (lowest first) when the per-band attenuations are combined. The defaults (0.50 low, 0.35 mid, 0.15 high) follow the principle that low frequencies are the ones that reach the listener around an obstacle; they are a judgement call rather than a measured spectrum, so they are exposed for testing. '/dstune bandWeights 0.7,0.25,0.05' makes a hill even more transparent")
        public String bandWeights = "0.50,0.35,0.15";

        @Property
        @Comment("Occlusion only: the octave bands in Hz the Fresnel zone is measured in, lowest first. The zone radius scales as 1/sqrt(frequency), so the lowest band decides whether a large obstacle (a hill) is a wall or a nuisance. '/dstune bandFrequencies 63,250,1000' shifts the whole model down an octave")
        public String bandFrequencies = "125,500,2000";

        @Property
        @IntegerRange(min = 16, max = 64)
        @RestartRequired
        @Comment("The number of rays to project around a sound location to calculate reverb effect")
        public int reverbRays = 32;

        @Property
        @IntegerRange(min = 2, max = 8)
        @RestartRequired
        @Comment("The number of reflections the ray calculation will perform before ending a ray calculation")
        public int reverbBounces = 4;

        @Property
        @IntegerRange(min = 64, max = 512)
        @RestartRequired
        @Comment("Total distance a reverb ray will traverse before ending calculation")
        public int reverbRayTraceDistance = 256;

        @Property
        @Slider
        @DoubleRange(min = 0D, max = 2D)
        @Comment("Scales the reverb (echo) intensity (1.0 = default; 0 = no reverb; higher = stronger)")
        public double reverbIntensity = 1.0D;

        @Property
        @Comment("Discrete echo in open spaces: the first reflection is delayed by the measured path length, so a valley returns a distinct echo instead of only a tail. Off = pre-echo behaviour")
        public boolean enableEarlyReflectionEcho = true;

        @Property
        @Comment("Enable/disable damping for sounds whose path to the player passes through water. Reduces both volume and high frequencies (muffling)")
        public boolean enableWaterSoundDamping = true;

        @Property
        @Slider
        @DoubleRange(min = 0.1D, max = 1D)
        @Comment("Fraction of the sound's volume that passes through each block of water between the sound and the player (lower = quieter, 1.0 = no volume change). The volume never drops below a minimum so distant sounds stay audible")
        public double waterSoundDamping = 0.95D;

        @Property
        @Slider
        @DoubleRange(min = 0.1D, max = 1D)
        @Comment("High-frequency (muffling) cut-off per block of water between the sound and the player (lower = more muffled, 1.0 = no muffling). Kept strong so underwater sounds are clearly muffled regardless of direction")
        public double waterSoundMuffle = 0.7D;
    }

    public static class SoundOptions {

        @Property
        @Slider
        @IntegerRange(min = 0, max = 400)
        @Comment("Ambient sounds played by the mod will be multiplied by this factor")
        public int ambientVolumeScaling = 100;

        @Property
        @Comment("Enables replacement of thunder sounds with Dynamic Surroundings' version")
        public boolean replaceThunderSounds = true;

        @Property
        @Comment("Enables playing sounds that are considered scary")
        public boolean allowScarySounds = true;

        @Property
        @Comment("Enables playing biome background music while in creative")
        public boolean playBiomeMusicWhileCreative = false;

        @Property
        @Comment("Enables display of toast messages for credited music")
        public boolean displayToastMessagesForMusic = true;

        @Property
        @Comment("Enables sound remapping when sounds are played")
        public boolean remapSounds = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable distant background thunder rumbling during storms")
        public boolean enableBackgroundThunder = true;

        @Property
        @Comment("Enable/disable ambient biome and village sounds (forest, plains, crickets, etc.)")
        public boolean enableBiomeSounds = true;

        @Property
        @Slider
        @DoubleRange(min = 0, max = 2)
        @Comment("Footstep sounds will be multiplied by this factor")
        public double footstepVolume = 1.0D;

        @Property
        @Slider
        @DoubleRange(min = 0, max = 2)
        @Comment("Biome ambient sounds will be multiplied by this factor")
        public double biomeVolume = 1.0D;

        @Property
        @Slider
        @DoubleRange(min = 0, max = 2)
        @Comment("Player effect sounds (jump, heartbeat, hunger, crafting, hotbar) will be multiplied by this factor")
        public double playerEffectVolume = 1.0D;
    }

    public static class BlockEffects {

        @Property
        @IntegerRange(min = 16, max = 64)
        @Slider
        @Comment("Distance that will be scanned when generating block effects")
        public int blockEffectRange = 32;

        @Property
        @Comment("Enable/disable steam column effect when liquids are adjacent to hot sources, like lava and magma")
        public boolean steamColumnEnabled = true;

        @Property
        @Comment("Enable/disable flame jets produced over lava, etc.")
        public boolean flameJetEnabled = true;

        @Property
        @Comment("Enable/disable bubble columns generated underwater")
        public boolean bubbleColumnEnabled = true;

        @Property
        @Comment("Enable/disable firefly generation")
        public boolean firefliesEnabled = true;

        @Property
        @Comment("Enable/disable dust falling from floating blocks (like dirt ledges)")
        public boolean dustJetEnabled = true;

        @Property
        @Comment("Enable/disable dust clouds kicked up when sand or gravel lands")
        public boolean fallingBlockDustEnabled = true;

        @Property
        @Comment("Enable/disable waterfall effect from flowing water")
        public boolean waterfallsEnabled = true;

        @Property
        @Comment("Enable/disable sounds from waterfalls")
        public boolean enableWaterfallSounds = true;

        @Property
        @Comment("Enable/disable particles from waterfalls")
        public boolean enableWaterfallParticles = true;

        @Property
        @DoubleRange(min = 0D, max = 1D)
        @Comment("Maximum volume of a single waterfall sound loop (0-1). Large cave waterfalls stack many loops; lower this if they get too loud")
        public double waterfallMaxVolume = 0.5D;

        @Property
        @EnumType(WaterRippleStyle.class)
        @Comment("The style of water ripple to render when a drop hits a fluid")
        public WaterRippleStyle waterRippleStyle = WaterRippleStyle.PIXELATED_CIRCLE;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable steam/smoke when rain hits magma or netherrack")
        public boolean enableMagmaSteam = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable the furnace ignite sound effect")
        public boolean furnaceIgniteEnabled = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable enchanting table book sounds")
        public boolean enableEnchantTableSounds = true;
    }

    @Comment("Configuration options for the damage, healing and critical-hit text popoffs")
    public static class PopoffNumbers {
        @Property
        @IntegerRange(min = 50, max = 300)
        @Slider
        @Comment("Text size as a percentage of the default (1.12.2 parity). Raise it if the text looks too small, lower it if too large")
        public int sizePercent = 73;

        @Hidden
        @Property
        @IntegerRange(min = 101, max = 200)
        @Slider
        @Comment("Growth per tick as a percentage (108 = 1.08x, matching the original mod)")
        public int growFactor = 114;

        @Hidden
        @Property
        @IntegerRange(min = 1, max = 10)
        @Slider
        @Comment("Which tick the text reaches its largest. Low values grow fast then shrink slowly")
        public int peakTickTicks = 5;

        @Hidden
        @Property
        @Comment("Upper guard on the text size, as a percentage of the spawn size")
        public int maxScalePercent = 400;

        @Hidden
        @Property
        @IntegerRange(min = -200, max = 200)
        @Slider
        @Comment("Horizontal travel as a percentage of the default. Positive drifts away from the attacker, negative drifts toward it, 0 rises straight up")
        public int driftPercent = 60;

        @Hidden
        @Property
        @IntegerRange(min = 10, max = 200)
        @Slider
        @Comment("How fast the text drops, as a percentage of the original gravity")
        public int gravityPercent = 80;

        @Hidden
        @Property
        @IntegerRange(min = 8, max = 40)
        @Slider
        @Comment("How many ticks the text lives. Higher values make it fall further and linger longer")
        public int lifetimeTicks = 17;
    }

    public static class EntityEffects {

        @Property
        @IntegerRange(min = 16, max = 64)
        @Slider
        @Comment("The maximum range at which entity special effects are applied")
        public int entityEffectRange = 24;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable bow pull sound effect")
        public boolean enableBowPull = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable thrown projectile break sounds (ender pearl, egg, snowball)")
        public boolean enableProjectileBreakSounds = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable breath effect in cold biomes and underwater")
        public boolean enableBreathEffect = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable player toolbar sound effects")
        public boolean enablePlayerToolbarEffect = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable sound effects for blocks on the toolbar")
        public boolean enableToolbarBlockSounds = false;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable item swing sound effects from players and mobs")
        public boolean enableSwingEffect = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable sound effect when walking through dense brush")
        public boolean enableBrushStepEffect = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable heartbeat sound when the player's health is low")
        public boolean enablePlayerHeartbeatSound = true;

        @Property
        @DoubleRange(min = 0D, max = 1D)
        @Comment("Fraction of max health below which the heartbeat sound plays (0 disables)")
        public double playerHurtThreshold = 0.25D;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable stomach growl sound when the player is hungry")
        public boolean enablePlayerHungerSound = true;

        @Property
        @IntegerRange(min = 0, max = 20)
        @Comment("Food level (hunger bar) at or below which the stomach growl plays (0 disables)")
        public int playerHungerThreshold = 8;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable jump sound when the player leaves the ground")
        public boolean enablePlayerJumpSound = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable landing sound when the player falls from a height")
        public boolean enablePlayerLandSound = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable the mod's custom footstep sounds for the player. When disabled, the vanilla footstep sounds are used instead")
        public boolean enableFootstepSounds = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable the scuff sound played when the player stops or turns sharply. This is the material's 'wander' recording in the original 1.12.2 data")
        public boolean enableStopScuffSound = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable footstep sounds while sneaking. The original 1.12.2 mod played no footsteps at all while sneaking; this port keeps them audible by default because a sneaking player would otherwise hear nothing, but the behaviour can be switched back")
        public boolean enableSneakFootstepSounds = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable creature footstep sounds (per-creature material footsteps with cadence, volume, landing and stop sounds)")
        public boolean enableCreatureFootstepSounds = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Infer a footstep material from a block's name when no explicit rule matches (a modded *_sandstone becomes concrete instead of plain stone). Explicit rules always take precedence")
        public boolean inferFootstepMaterial = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable crafting sound when an item is crafted")
        public boolean enableCraftingSound = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable player footprints while walking")
        public boolean enableFootprints = true;

        @Property
        @EnumType(FootprintStyle.class)
        @Comment("Style of footprints")
        public FootprintStyle footprintStyle = FootprintStyle.LOWRES_SQUARE;

        @Property
        @RestartRequired(client = false)
        @Comment("Display a comic power word when an entity takes a critical hit")
        public boolean showCritWords = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Display damage and healing numbers above entities")
        public boolean showDamageNumbers = true;
    }

    public static class FootstepAccents {
        @Property
        @Comment("Enable/disable foot step accents globally")
        public boolean enableAccents = true;

        @Property
        @Comment("Enable/disable accents for armor that is worn")
        public boolean enableArmorAccents = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Infer an armor's weight class (light/medium/heavy/crystal) from its material stats when no explicit armor tag matches, so modded armor gets footstep accents instead of silence. The armor tags always take precedence")
        public boolean inferArmorClass = true;

        @Property
        @Comment("Enable/disable accents when it is raining or blocks are waterlogged")
        public boolean enableWetSurfaceAccents = true;

        @Property
        @Comment("Enable/disable accents when the player is walking on squeaky blocks")
        public boolean enableFloorSqueaks = true;
    }

    public static class ParticleTweaks {
        @Property
        @Comment("Enable/disable showing of projectile particle trails")
        public boolean suppressProjectileParticleTrails = false;

        @Property
        @RestartRequired(client = false)
        @Comment("Suppress rendering of the player's potion particles")
        public boolean suppressPotionParticles = false;
    }

    public static class CompassAndClockOptions {
        @Property
        @Comment("Enable/disable display of the clock display when holding a clock item")
        public boolean enableClock = true;

        @Property
        @Comment("Enable/disable display of the compass display when holding a compass item")
        public boolean enableCompass = true;

        @Property
        @Comment("Style of compass rendering")
        @EnumType(CompassStyle.class)
        public CompassStyle compassStyle = CompassStyle.TRANSPARENT_WITH_INDICATOR;

        @Property
        @Comment("Scales the display by the specified amount")
        @DoubleRange(min = 0.5D, max = 4D)
        public double scale = 1D;

        @Property
        @Comment("Enable/disable showing the distance to the treasure target on explorer maps")
        public boolean enableTreasureDistance = true;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable red pulsing frame on the selected hotbar slot when the item's durability is low")
        public boolean enableLowDurabilityHighlight = true;

        @Property
        @IntegerRange(min = 1, max = 50)
        @Slider
        @Comment("Durability percentage at or below which the low durability highlight shows")
        public int lowDurabilityThreshold = 10;
    }

    public static class WeatherOptions {
        @Property
        @Comment("Enable/disable the desert sandstorm dust effect and yellow screen tint")
        public boolean enableDesertSandstorm = true;

        @Property
        @Comment("Enable/disable the nether dust rain effect")
        public boolean enableNetherDust = false;

        @Property
        @Comment("Enable/disable the biome fog color tint (biomes.json fogColor - desert haze, swamp fog, etc.)")
        public boolean enableBiomeFogColor = true;
    }

    public static class FogOptions {
        @Property
        @Comment("Enable/disable fog effects")
        public boolean enableFogEffects = true;

        @Property
        @Comment("Enable/disable morning fog effect")
        public boolean enableMorningFog = true;

        @Property
        @Comment("Enable/disable biome fog effect")
        public boolean enableBiomeFog = true;

        @Property
        @Comment("Enable/disable weather fog effect")
        public boolean enableWeatherFog = true;

        @Property
        @Comment("Increase fog at bedrock layers")
        public boolean enableBedrockFog = true;

        @Property
        @Comment("Higher the player elevation the more haze that is experienced")
        public boolean enableElevationHaze = true;

        @Property
        @DoubleRange(min = 0D, max = 24D)
        @Comment("Morning fog start time, in hours of day (5.0 = 5AM, 6.0 = 6AM, 8.0 = 8AM)")
        public double morningFogStartHour = 5.0D;

        @Property
        @DoubleRange(min = 0D, max = 24D)
        @Comment("Morning fog peak time, in hours of day (6.0 = 6AM)")
        public double morningFogPeakHour = 6.0D;

        @Property
        @DoubleRange(min = 0D, max = 24D)
        @Comment("Morning fog end time, in hours of day (8.0 = 8AM)")
        public double morningFogEndHour = 8.0D;

        @Property
        @Slider
        @DoubleRange(min = 0D, max = 4D)
        @Comment("Scales morning fog haze (4.0 = default; higher = mist reaches closer; does not change view distance. Morning fog type follows the season sub-phase: midsummer mornings have none)")
        public double morningFogDensity = 4.0D;

        @Property
        @Slider
        @DoubleRange(min = 0D, max = 2D)
        @Comment("Scales biome fog density (1.0 = default; 0 = disable biome fog)")
        public double biomeFogDensity = 1.0D;

        @Property
        @Slider
        @DoubleRange(min = 0.25D, max = 4D)
        @Comment("Scales weather (rain) fog density (1.0 = default; higher = denser/closer fog)")
        public double weatherFogDensity = 1.0D;
    }

    public static class OtherOptions {
        @Property
        @Comment("Enable/disable playing random sound at the Minecraft finish loading to main screen")
        public boolean playRandomSoundOnStartup = true;
    }

    public static class AuroraOptions {
        @Property
        @Comment("Enable/disable aurora processing and rendering")
        public boolean enableAurora = true;
    }

    public static class SpeechBubbles {

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable speech bubbles above player heads for chat messages")
        public boolean enableSpeechBubbles = false;

        @Property
        @RestartRequired(client = false)
        @Comment("Enable/disable chat bubbles above villagers and other mobs")
        public boolean enableEntityChat = false;

        @Property
        @DoubleRange(min = 5D, max = 15D)
        @Comment("Number of seconds to display a speech bubble before removing it")
        public double speechBubbleDuration = 7.0D;

        @Property
        @IntegerRange(min = 16, max = 32)
        @Comment("Range (blocks) at which a speech bubble is visible")
        public int speechBubbleRange = 16;
    }
}
