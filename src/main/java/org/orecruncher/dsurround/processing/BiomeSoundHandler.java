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
    // Rain landing on a surface. Seven materials: glass, metal, wood, log,
    // stone and leaves, plus water, which is parked until a clip for it
    // turns up. See the
    // comment on handleRainMaterial(). Extracted from the footstep pools by
    // tools/_make_material_rain_assets.py.
    private static final ResourceLocation RAIN_MATERIAL_GLASS =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass");
    private static final ResourceLocation RAIN_MATERIAL_METAL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal");
    private static final ResourceLocation RAIN_MATERIAL_WATER =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water");

    private static final ResourceLocation[] RAIN_MATERIAL_GLASS_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_METAL_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_WATER_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_WOOD_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_LOG_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_STONE_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_LEAVES_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.heavy"),};
    private static final ResourceLocation[][] RAIN_MATERIAL_TIERS = {
            RAIN_MATERIAL_GLASS_TIERS, RAIN_MATERIAL_METAL_TIERS, RAIN_MATERIAL_WATER_TIERS,
            RAIN_MATERIAL_WOOD_TIERS, RAIN_MATERIAL_LOG_TIERS,
            RAIN_MATERIAL_STONE_TIERS, RAIN_MATERIAL_LEAVES_TIERS};
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
    // A gust every 25-60 s before the amplitude divisor, against the 5-16 s the
    // first pass used. At the old rate the canopy never stopped rustling, which
    // reads as noise rather than weather - what is wanted is the occasional big
    // gust that comes through and takes the leaves with it.
    // Widened: 70 s at the low end down to 18 s at full intensity (was 60/25),
    // before the amplitude divisor. The maintainer could not tell that the
    // pacing was coupled to intensity at all, so make the span obvious.
    private static final float RAIN_LEAF_INTERVAL_MAX_S = 70F;
    private static final float RAIN_LEAF_INTERVAL_MIN_S = 18F;
    /**
    /**
     * How far the wind bed may fall below, and how far it may rise above, the
     * level the intensity asks for. A bed pinned to one level is a loop with a
     * volume knob, and the maintainer asked for the swells to bite harder.
     * The ceiling is separate from the floor because the complaint was about
     * the crest specifically - lowering the shared bed gain instead would have
     * dragged the trough down with it, and the trough is already at the level
     * that stopped reading as "barely there".
     */
    private static final float RAIN_WIND_SWELL_FLOOR = 0.42F;
    private static final float RAIN_WIND_SWELL_PEAK = 0.8F;
    private static final float RAIN_WIND_SWELL_PERIOD_A_S = 14F;
    private static final float RAIN_WIND_SWELL_PERIOD_B_S = 23F;
    /**
     * Extra level for a gust in a thunderstorm. A thunderstorm has to outshout
     * its own rain; at full intensity the gusts were being buried by it.
     */
    private static final float RAIN_GUST_STORM_GAIN = 1.3F;
    /**
     * Rain landing on a material surface: a bounded number of continuous loops
     * spread over whatever surfaces are in reach.
     *
     * <p>Not one per material. One per material stopped different materials
     * competing and started the identical bug one level down - a whole glass
     * roof produced exactly one sound, and every other block of glass was
     * silent, because the slot had claimed the material. Rain on a surface is a
     * field: a large expanse should carry several loops spread across it, and
     * they get louder together simply because there are more of them.</p>
     *
     * <p>This is also the performance answer. The cap is on the total, so a
     * field of glass costs what a single pane costs, many materials in reach
     * can neither stutter nor fall silent, and adding more materials later does
     * not change the budget.</p>
     */
    private static final int RAIN_MATERIAL_MAX_LOOPS = 8;
    /**
     * Shortest gap between two preemptions, in ticks. Three seconds: long
     * enough that a run of unrelated neighbour updates cannot keep cycling
     * the layer, short enough that a block just placed is never far from
     * being heard.
     */
    private static final long RAIN_MATERIAL_PREEMPT_COOLDOWN = 60L;
    /**
     * The number of simultaneous loops this layer's level was tuned at. More
     * voices than this are normalised down to it, so a scene growing does not
     * mean the layer getting louder - which is the whole of the ninth
     * complaint. Two, not four: eight loops at the old anchor still arrived
     * six decibels above one pane, because ten times the log of eight is nine
     * and four only gave three of it back. At two, a plank floor, a plank
     * floor with logs in it and a whole village all land within a decibel of
     * each other, and the extra slots buy coverage instead of level.
     */
    private static final int RAIN_MATERIAL_TUNED_VOICES = 2;
    /**
     * How many loops one group of materials may hold at once, and which group
     * each material belongs to. Both are indexed the way the scanner's
     * MATERIAL_ENABLED is: glass, metal, water, wood, log, stone, leaves.
     *
     * <p>Planks and logs are one group on purpose. They are one SoundType to
     * the game and one material to the ear, split only by the vanilla log tag,
     * and a wooden wall is both at once. Counting them separately let a
     * village hold four wooden loops where a greenhouse held two glass ones -
     * twice the drops and twice the level, which is exactly the tenth report,
     * wood in quantity reading as hail.</p>
     *
     * <p>The cap itself is the half of this that is harshness rather than
     * level. A second loop buys spread - a field instead of a point. A third
     * buys level and a busy, comb-filtered quality, because it is the same
     * drops at the same spectrum a fraction of a second apart, and no amount
     * of trimming makes that read as rain.</p>
     */
    private static final int RAIN_MATERIAL_MAX_PER_GROUP = 2;
    private static final int[] RAIN_MATERIAL_GROUP = {0, 1, 2, 3, 3, 4, 5};

    /**
     * How far apart two loops of the same group must be, in blocks squared.
     *
     * <p>Twelfth log, measured rather than guessed: of the seventy picks that
     * had another loop already sounding, twenty-one landed within four blocks
     * of it and sixteen landed on the block right next to it. Two loops of
     * one clip one block apart are not a surface, they are one surface twice
     * - three decibels of level plus a comb filter wherever the two copies
     * disagree, which is the layer reading as loud and busy instead of wide.
     * That is the "competition" still left in the eleventh log: not two
     * materials fighting over a slot, but two copies of one material sharing
     * a spot.</p>
     *
     * <p>Four blocks, so a surface has to be about five wide before it earns
     * a second anchor. A small wooden platform now gets one loop, which is
     * also the honest answer - rain on three planks is quieter than rain on
     * a roof, and the level dropping in exactly those scenes is the point.</p>
     */
    private static final long RAIN_MATERIAL_MIN_SPREAD_SQ = 16L;
    /**
     * How much a material nothing is playing yet is worth, in blocks squared.
     *
     * <p>It was 10000 - larger than any squared distance this box can express,
     * which makes it not a preference but a first round: every material with a
     * loop already sounding was rejected outright, and only among what was
     * left did distance get a say. The thirteenth log is what that costs.
     * Both slots were holding wood and log; one was given back, and the pick
     * walked past planks three blocks away to reach a log ten blocks off and
     * anchored there. Some forty of these in two hundred seconds, each one a
     * fade out and a fade in somewhere new.</p>
     *
     * <p>The size it had existed to stop one expanse taking every slot. The
     * per-group cap above does that job now and does it better: a group cannot
     * take a third loop, so the slots that are left have nowhere to go but
     * somewhere else. What remains for this dial is preferring variety at
     * roughly equal distance, and sixty squared blocks - call it eight blocks
     * - is the size of that.</p>
     */
    private static final double RAIN_MATERIAL_NEW_MATERIAL_BIAS = 60.0;
    /**
     * Where being away from another loop stops being worth anything, squared.
     *
     * <p>The score was {@code nearest - 0.5 * toPlayer} with {@code nearest}
     * unbounded, so "spread the loops over the surface" was really "put the
     * second one as far from the first as the box allows" - the rim, six to
     * ten blocks out, which is a loop being quiet rather than wide. Past some
     * separation the two already read as two places, and buying more buys
     * nothing; past six blocks it stops counting and distance to the player
     * decides on its own. The hard floor below - MIN_SPREAD_SQ - still keeps
     * them from landing on each other, so this cap cannot collapse them back
     * into one by removing the reason to move apart.</p>
     */
    private static final double RAIN_MATERIAL_SPREAD_CAP_SQ = 36.0;
    /**
     * Only for the diagnostic line, and for the intensity curve below: under
     * this the layer sits at its floor rather than going silent, so a drizzle
     * still has drops in it.
     */
    private static final float RAIN_MATERIAL_MIN_INTENSITY = 0.2F;
    /**
     * Global trim, applied to every loop of this layer before the per-material
     * trim below. One, and it should stay one: the array below is where the
     * levels live now, and two dials that both mean "quieter" is how a number
     * ends up meaning nothing.
     */
    private static final float RAIN_MATERIAL_VOLUME = 1.0F;
    /**
     * Level per material, indexed the way the scanner's MATERIAL_ENABLED is:
     * glass, metal, water, wood, log, stone, leaves.
     *
     * <p>They are not all the same number because the eleventh report says
     * they should not be: glass and metal had come out "a bit quiet", planks
     * and logs "about right", out of one global trim that had been cut twice.
     * Cutting everything together was the error. He called glass and metal
     * perfect long before any of this and never once said they were loud -
     * wood was the material he called loud, twice, and in quantity. So glass
     * and metal come back up and wood stays where the tenth round put it.</p>
     */
    private static final float[] RAIN_MATERIAL_TRIM =
            {0.7F, 0.7F, 1.0F, 0.5F, 0.5F, 1.0F, 1.0F};
    /** Lowest scale the intensity curve reaches - a drizzle still has some. */
    private static final float RAIN_MATERIAL_VOLUME_FLOOR = 0.55F;
    private static final float RAIN_MATERIAL_TIER_MEDIUM = 0.4F;
    private static final float RAIN_MATERIAL_TIER_HEAVY = 0.7F;
    /**
     * How far past a threshold the intensity has to go the other way before
     * the tier moves back. The eleventh log has the intensity sitting at 0.50
     * and at 1.00 - either side of the heavy threshold - and every crossing
     * used to fade the whole layer and pick it again.
     */
    private static final float RAIN_MATERIAL_TIER_HYSTERESIS = 0.06F;
    /**
     * How many ticks a spot has to keep looking wrong before its loop is
     * dropped. One bad read used to cost a loop, and the beat after it picked
     * somewhere else; that is the churn in the log. A quarter of a second is
     * short enough that a block he breaks still goes quiet at once, and long
     * enough that a read which disagrees for three ticks does not move the
     * sound. Zero gives back the old instant-drop behaviour.
     */
    private static final int RAIN_MATERIAL_INVALID_GRACE_TICKS = 5;
    /**
     * How long a candidate set stays usable, in ticks. The volume is walked
     * again every beat even when the player has not moved a block and nothing
     * in the world has changed, which is most beats of most sessions. A second
     * of staleness is inaudible next to a set that cannot have changed; a
     * move or a block update walks it again at once.
     */
    private static final long RAIN_MATERIAL_SCAN_CACHE_TICKS = 20L;
    /**
     * How often the biome gate is re-asked, in ticks. The answer is a biome
     * lookup and a temperature read, and it does not change while he stands
     * in the same place.
     */
    private static final long RAIN_MATERIAL_BIOME_CHECK_TICKS = 20L;
    /**
     * Cadence for re-filling the anchored surfaces. Half a second, not one:
     * the set is now walked synchronously on this beat instead of assembled
     * over the twenty ticks before it, so a shorter beat costs real work -
     * roughly six thousand block reads a second while it rains, which is the
     * price the maintainer asked to pay later rather than now. In exchange,
     * walking under a glass roof is heard inside half a second and every
     * candidate handed to the allocator came from the box the player is in.
     */
    private static final int RAIN_MATERIAL_SCAN_TICKS = 10;
    /**
     * Shortest wait before a block update may pull the allocation beat early.
     * One beat shorter than the cadence above: laying a floor is dozens of
     * updates, and each one would otherwise buy a scan of its own.
     */
    private static final int RAIN_MATERIAL_REACT_TICKS = 5;
    /**
     * Squared distance past which a loop gives its slot back.
     *
     * <p>Deliberately larger than the scan box. Releasing the instant a spot
     * leaves the box would make every step through a village buy a fade and a
     * re-pick - the churn that was the whole complaint two rounds ago - and a
     * surface the allocator has stopped being shown is still making real
     * sound. Twelve blocks is past where this layer can be heard, and still
     * tight enough that walking out of a scene frees its slot at once.</p>
     *
     * <p>The sixth log needed this to be a hard rule, not a preference: it
     * ended with seven of eight loops playing a village twenty-five to thirty
     * blocks behind him, every one of them still a valid block of wood with
     * the sky above it.</p>
     */
    private static final long RAIN_MATERIAL_RELEASE_DIST_SQ = 144L;
    /**
     * What being near the player is worth against being away from the loops
     * already playing, in squared blocks per squared block. Farthest-point
     * sampling alone parks the loops on the rim of the box: maximally spread,
     * and maximally quiet for it. Half is a compromise - clustered enough to
     * be heard from where he stands, spread enough to read as a field rather
     * than a single source. Zero gives back the old rim-only behaviour.
     */
    private static final double RAIN_MATERIAL_PROXIMITY_WEIGHT = 0.5;

    // Search volume around the player. Not a single ground sample: surfaces can
    // be overhead (a metal roof) or off to the side (a glass wall) as easily as
    // underfoot. Stride 1 - every column. The first pass used 2 to halve the
    // column count and it cost far more than it saved: a lone pane or a single
    // block of metal simply fell between the sampled columns and was not found,
    // which reads as the whole feature being broken.
    // Radius is 6, not 8: reach is the cheap thing to give up and the beat is
    // ten times faster, so a narrower box is how the cost stays flat.
    private static final int RAIN_MATERIAL_RADIUS = 6;
    private static final int RAIN_MATERIAL_UP = 8;
    // Deep enough to reach water you are standing above. A lake or a pool is
    // usually below the player rather than level with them, and that is the
    // case the shallow window kept missing - water's special case is reach
    // downward, not a different way of finding it.
    private static final int RAIN_MATERIAL_DOWN = 12;
    private static final int RAIN_MATERIAL_STRIDE = 1;

    /**
     * Per-source trim for the three-source canopy surround. Perceived level
     * goes with volume * sqrt(voices), so three sources at full volume land
     * 4.8 dB above one - enough to bury the wind they are meant to ride with.
     * Applied at the rain call site and not inside {@code playLeafSurround},
     * because that method is shared with the sunny gust and sunny behaviour is
     * not ours to change.
     */
    private static final float RAIN_LEAF_SOURCE_COMP = 0.58F;
    // No delay constants any more. The wind->leaves lag is in the clips
    // themselves - they were recorded that way - and scheduling a second track
    // a few ticks later only doubled the wind.

    private BackgroundSoundLoop rainWindLoop;
    private Object rainWindAsset;
    private long rainGustNextTick;
    private long rainMaterialNextTick;
    // DIAGNOSTIC: shape of the last candidate set - how many surfaces and how
    // many of each material, not which container they arrived in. Comparing by
    // identity always said "new", because the allocator hands back a fresh
    // container on every beat, and the result was a scan line every half
    // second: 449 of them in one log, burying the 37 picks underneath.
    private long rainMatLastFingerprint = -1L;
    /** Shape of the last refresh line, so a steady scene stops repeating it. */
    private long rainMatLastRefreshShape = -1L;
    private int rainMatScanPass = 0;
    private long rainMatScanPassLogTick = -1000L;
    private long rainMatRefreshLogTick = -1000L;
    // Set by a block update, cleared once the allocator has acted on it.
    private boolean rainMatReevaluate = false;
    private long rainMatLastPreemptTick = -RAIN_MATERIAL_PREEMPT_COOLDOWN;
    private long rainMatLastRefreshTick = 0L;
    /** A slot was given back this tick because its scene was left behind. */
    private boolean rainMatSlotFreed = false;
    private final BackgroundSoundLoop[] rainMaterialLoops =
            new BackgroundSoundLoop[RAIN_MATERIAL_MAX_LOOPS];
    private final Object[] rainMaterialAssets = new Object[RAIN_MATERIAL_MAX_LOOPS];
    private final net.minecraft.core.BlockPos[] rainMaterialSpots =
            new net.minecraft.core.BlockPos[RAIN_MATERIAL_MAX_LOOPS];
    private final int[] rainMaterialMaterials = new int[RAIN_MATERIAL_MAX_LOOPS];
    private final int[] rainMaterialTiers = new int[RAIN_MATERIAL_MAX_LOOPS];
    /** Ticks this spot has looked invalid for - see the grace constant. */
    private final int[] rainMaterialInvalidTicks = new int[RAIN_MATERIAL_MAX_LOOPS];
    /**
     * What this layer costs, counted rather than argued about. The maintainer
     * asked how many milliseconds a tick it runs and whether it can be felt in
     * the low frames, and the only honest answer to either is the one the game
     * prints: two nanoTime reads around the layer each tick, two more around
     * each scan, no sampling and no estimates anywhere in it.
     */
    private static final long RAIN_MATERIAL_PERF_LOG_TICKS = 200L;
    /** Minimum gap between "scan pass" lines. The candidate set changes
     *  every beat he is walking, so profiling (below) is what keeps the
     *  shape readable; this only keeps it from being written every beat.
     *  0 restores one line per changed set. */
    private static final long RAIN_MATERIAL_SCAN_LOG_MIN_TICKS = 40L;
    /** How often a beat that picked nothing still prints. Never is too
     *  little - a scene that stays silent has to be visible - but every
     *  beat is four lines a second about nothing happening. */
    private static final long RAIN_MATERIAL_REFRESH_LOG_TICKS = 100L;
    /** Resolved once per tick. When it is false none of the timers below run
     *  at all - the maintainer asked for the diagnostics to be a switch he can
     *  leave off, and a switch that still paid for the measurement would not
     *  be one: round 15 measured the logging as the most expensive thing in
     *  the allocator. Two boolean reads per tick is the entire cost of off. */
    private boolean rainMatTrace = false;
    private long rainMatPerfTicks = 0L;
    private long rainMatPerfNanos = 0L;
    private long rainMatPerfMaxNanos = 0L;
    private long rainMatPerfLogTick = -1000L;
    private int rainMatScanCount = 0;
    private long rainMatScanNanos = 0L;
    private long rainMatScanMaxNanos = 0L;
    private long rainMatValidNanos = 0L;
    private long rainMatValidMaxNanos = 0L;
    private int rainMatRefreshCount = 0;
    private long rainMatRefreshNanos = 0L;
    private long rainMatRefreshMaxNanos = 0L;
    private long rainMatScaleNanos = 0L;
    private long rainMatScaleMaxNanos = 0L;
    private long rainMatRestMaxNanos = 0L;
    /** The tier the layer is playing, for the hysteresis in the allocator. */
    private int rainMaterialTier = -1;
    private long rainMatBiomeCheckTick = -1000L;
    private boolean rainMatBiomeAllows = true;
    private long rainMatBiomeLogTick = -1000L;
    private Object rainMatScanCache = null;
    private Object rainMatScanAt = null;
    private long rainMatScanTick = -1000L;
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
        // Block updates reach every other cuboid-driven system through this
        // hook. The material layer never subscribed, so a block placed during
        // rain was invisible until the next full pass walked over it.
        org.orecruncher.dsurround.eventing.ClientEventHooks.BLOCK_UPDATE
                .register(this::onMaterialBlockUpdates);
    }

    /**
     * Client-side block updates, applied to the material candidate set at once.
     *
     * <p>The scanner is only created once rain starts, so a dry world has
     * nothing to update and pays nothing.</p>
     */
    private void onMaterialBlockUpdates(final java.util.Collection<net.minecraft.core.BlockPos> positions) {
        // A dry world still costs nothing: the flag is only read by the rain
        // layer, and nothing else reacts to it.
        if (positions.isEmpty())
            return;
        // Nothing to patch any more. The candidate set is walked from the
        // live world at the moment it is read, so a change only has to be
        // noticed - it cannot be missed by a set published earlier.
        this.rainMatReevaluate = true;
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
        // This layer runs every tick, not on the biome scan beat. It used to
        // ride SCAN_INTERVAL, which gave its scanner a fifth of the slices and
        // stretched one pass over seconds; a loop anchored from a pass that
        // old was checking against a world several seconds newer than itself.
        // Perceived latency - a block placed in the rain staying silent - came
        // from there. It pays for that by being the only thing here timed.
        if (player.isAlive()) {
            // Timed here rather than inside the method: that method has six
            // ways out, and wrapping each one is how one gets missed.
            // Everything in here that ought to cost something is timed where
            // it runs. What does not land in one of those buckets is reported
            // as "rest" instead of being assumed free - see rainMaterialPerf.
            this.rainMatTrace = this.logger.isTracing(Configuration.Flags.RAIN_MATERIAL);
            if (this.rainMatTrace) {
                final long beforeScan = this.rainMatScanNanos;
                final long beforeValid = this.rainMatValidNanos;
                final long beforeRefresh = this.rainMatRefreshNanos;
                final long beforeScale = this.rainMatScaleNanos;
                final long stamp = System.nanoTime();
                handleRainMaterial(player);
                final long took = System.nanoTime() - stamp;
                final long rest = took
                        - (this.rainMatScanNanos - beforeScan)
                        - (this.rainMatValidNanos - beforeValid)
                        - (this.rainMatRefreshNanos - beforeRefresh)
                        - (this.rainMatScaleNanos - beforeScale);
                rainMaterialPerf(took, rest);
            } else {
                handleRainMaterial(player);
            }
        }
        if ((this.tickCount.getTickCount() % SCAN_INTERVAL) == 0) {
            handleBiomeSounds(player);
        }
    }

    @Override
    public void onConnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        this.rainGustNextTick = 0L;
    }

    @Override
    public void onDisconnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        this.rainGustNextTick = 0L;
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
                // handleRainMaterial is called from process() every tick.
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
    /**
     * A raindrop landing on a surface near the player.
     *
     * <p>Three materials, not the sixty the footstep system knows: glass, metal
     * and water. They are the ones that read instantly - a greenhouse roof, a
     * corrugated shelter, a lake - and the maintainer asked for the extraction
     * to be proven on three before it is generalised.</p>
     *
     * <p>The surface is found by looking, not by assuming. Rain lands on whatever
     * is open to the sky: the ground underfoot, but equally a metal roof
     * overhead or a pane of glass off to one side. So the search collects every
     * surface in a volume around the player that is (a) one of our materials and
     * (b) open above, then picks one of them and plays the drop there. Nothing
     * about the player's own position is assumed - which also means it keeps
     * working while flying, where "the block under the player" is air.</p>
     *
     * <p>Only materials with a clip behind them are wired up; everything else
     * returns no match. Stone, wood and leaves are all waiting on their own
     * clips, deliberately - three first.</p>
     */
    private void handleRainMaterial(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds()) {
            fadeRainMaterial();
            return;
        }
        // Gate on the envelope this layer is scaled by, not on vanilla's rain
        // ramp. rainingNow() reads Level.getRainLevel(), and the graded system
        // never writes that level - by decision - so it stays 0 for the whole of
        // a storm we drive ourselves. Measured: 48 samples over five minutes of
        // DSR-driven torrential rain, ramp=0.000 every time, rainingNow() false
        // every time, and so this layer returned at its first gate every tick
        // while PrecipitationRenderer - which gates on takeoverReady() - was
        // drawing torrential rain beside it. ambient() is what the volume is
        // computed from two lines below: if it is zero the layer is silent
        // anyway, so this gate costs nothing and now agrees with the renderer.
        if (PrecipitationIntensity.ambient() <= 0F) {
            // Rate-limited: a closed gate must be VISIBLE. The one below is
            // debug level and never reaches the log, which is why this looked
            // like "no surfaces found" for a whole session.
            if (this.tickCount.getTickCount() % 200 == 0)
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] gate closed: ambient=%.3f rainingNow=%s",
                        PrecipitationIntensity.ambient(),
                        PrecipitationIntensity.rainingNow());
            fadeRainMaterial();
            return;
        }
        // Only where rain is the thing that falls. See rainMaterialAllowedHere().
        if (!rainMaterialAllowedHere(player)) {
            fadeRainMaterial();
            return;
        }
        // Nothing to pump. The candidate set is built by the allocator, on its
        // own beat and always against the world as it is right now.
        final float intensity = PrecipitationIntensity.ambient();
        final long now = this.tickCount.getTickCount();
        // Two things make an anchored loop give its slot back, both checked
        // every tick: the block is no longer what it was, or it is no longer
        // where the listener is. Two block reads and one subtraction per slot,
        // which is nothing next to eight loops playing a scene he has left.
        // Walking the volume is the expensive part, and that stays on its own
        // beat below.
        var level = player.level();
        final var here = player.blockPosition();
        final long validStamp = this.rainMatTrace ? System.nanoTime() : 0L;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
            if (this.rainMaterialSpots[i] == null)
                continue;
            final var anchored = this.rainMaterialSpots[i];
            final long gap = distanceSq(here, anchored);
            final boolean changed = !spotStillValid(level, anchored,
                    this.rainMaterialMaterials[i]);
            final boolean left = gap > RAIN_MATERIAL_RELEASE_DIST_SQ;
            if (!changed && !left) {
                this.rainMaterialInvalidTicks[i] = 0;
                continue;
            }
            // A spot that looks wrong is given a few ticks to keep looking
            // wrong before its loop is dropped. The eleventh log has picks
            // replacing a pick from eleven ticks earlier, which is not a scene
            // changing, it is a decision changing its mind.
            final int grace = ++this.rainMaterialInvalidTicks[i];
            if (grace < RAIN_MATERIAL_INVALID_GRACE_TICKS)
                continue;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d INVALID tick=%d "
                            + "pos=(%d,%d,%d) mat=%d nowMat=%d gapSq=%d reason=%s grace=%d",
                    i, now, anchored.getX(), anchored.getY(), anchored.getZ(),
                    this.rainMaterialMaterials[i],
                    materialSound(level.getBlockState(anchored)), gap,
                    changed ? "block changed" : "left behind", grace);
            fadeMaterialLoop(i);
            // Refill at the react gap rather than waiting out the beat. This
            // slot did not come free because something went wrong; it came
            // free because the player walked somewhere else, and half a second
            // of silence where he now stands is what reads as unreliable.
            this.rainMatSlotFreed = true;
        }
        if (this.rainMatTrace) {
            final long validTook = System.nanoTime() - validStamp;
            this.rainMatValidNanos += validTook;
            if (validTook > this.rainMatValidMaxNanos)
                this.rainMatValidMaxNanos = validTook;
        }
        // Two ways into the allocator: the standing beat, and the world
        // changing underneath us. The second is what makes a block placed in
        // the rain audible in a few ticks instead of waiting out the beat;
        // it is rate-limited because building is not one update, it is forty.
        if (now - this.rainMatLastRefreshTick >= RAIN_MATERIAL_SCAN_TICKS
                || ((this.rainMatReevaluate || this.rainMatSlotFreed)
                    && now - this.rainMatLastRefreshTick >= RAIN_MATERIAL_REACT_TICKS)) {
            final long refreshStamp = this.rainMatTrace ? System.nanoTime() : 0L;
            refreshMaterialLoops(player, intensity);
            if (this.rainMatTrace) {
                final long refreshTook = System.nanoTime() - refreshStamp;
                this.rainMatRefreshCount++;
                this.rainMatRefreshNanos += refreshTook;
                if (refreshTook > this.rainMatRefreshMaxNanos)
                    this.rainMatRefreshMaxNanos = refreshTook;
            }
        }
        float t = (intensity - RAIN_MATERIAL_MIN_INTENSITY) / (1F - RAIN_MATERIAL_MIN_INTENSITY);
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        // Starting on tick one means ambient is still near zero, so bring the
        // level up over the first MIN_INTENSITY of the envelope. Without this the
        // layer arrives at FLOOR scale immediately; the loop player eases, but
        // easing towards a target that is already high is not a fade in.
        float fadeIn = intensity / RAIN_MATERIAL_MIN_INTENSITY;
        fadeIn = fadeIn < 0F ? 0F : (fadeIn > 1F ? 1F : fadeIn);
        final float vol = fadeIn
                * (RAIN_MATERIAL_VOLUME_FLOOR + (1F - RAIN_MATERIAL_VOLUME_FLOOR) * t);
        // Perceived level goes with the square root of the number of voices,
        // so eight loops at this volume would arrive nearly 3 dB above the four
        // this layer was tuned with. Hold the total instead: scenes of four or
        // fewer are untouched, and everything past that spends its voices on
        // being somewhere else rather than on being louder.
        int voices = 0;
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            if (this.rainMaterialLoops[k] != null)
                voices++;
        final float voiceComp = voices > RAIN_MATERIAL_TUNED_VOICES
                ? (float) Math.sqrt((double) RAIN_MATERIAL_TUNED_VOICES / (double) voices)
                : 1.0F;
        final long scaleStamp = this.rainMatTrace ? System.nanoTime() : 0L;
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            if (this.rainMaterialLoops[k] != null)
                this.rainMaterialLoops[k].setScaleTarget(vol * voiceComp);
        if (this.rainMatTrace) {
            final long scaleTook = System.nanoTime() - scaleStamp;
            this.rainMatScaleNanos += scaleTook;
            if (scaleTook > this.rainMatScaleMaxNanos)
                this.rainMatScaleMaxNanos = scaleTook;
        }
    }

    /**
     * Spread a bounded number of loops over the material surfaces in reach.
     *
     * <p>Two jobs on the same beat. First drop anything that is no longer what
     * it was: the block was broken, something was built on top of it, or the
     * tier moved. Then fill the free slots by farthest-point sampling seeded
     * with the spots already playing, so a new slot lands away from the ones
     * already sounding rather than next to them.</p>
     */
    private void refreshMaterialLoops(final Player player, final float intensity) {
        final int tier = rainMaterialTierFor(intensity, this.rainMaterialTier);
        this.rainMaterialTier = tier;

        // Validity is checked every tick by the caller; this is only the tier.
        // One loop per beat, not all of them: dropping the whole layer on a
        // tier change is a silence half a second long and eight new picks, and
        // the intensity crosses the threshold both ways whenever a storm
        // breathes. One at a time the layer changes density over a few seconds
        // instead of blinking.
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
            if (this.rainMaterialSpots[i] != null && this.rainMaterialTiers[i] != tier) {
                fadeMaterialLoop(i);
                break;
            }

        boolean free = false;
        int occupied = 0;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
            if (this.rainMaterialLoops[i] == null)
                free = true;
            else
                occupied++;
        final long diagTick = this.tickCount.getTickCount();
        this.rainMatLastRefreshTick = diagTick;
        // Consumed here rather than left for whoever reaches it next. A block
        // update arriving while a slot happens to be free never hits the
        // preemption branch, so the flag would sit set and pull every later
        // refresh in at the react gap instead of at its own beat.
        final boolean worldChanged = this.rainMatReevaluate;
        this.rainMatReevaluate = false;
        this.rainMatSlotFreed = false;
        // Read the candidate set before deciding whether to give a slot up.
        // The bail-out used to come first, so it could not tell a preemption
        // worth making from one that only costs a fade-out. It also meant
        // the scanner was never ticked while every slot was held, so the
        // world could change underneath the layer without it noticing.
        ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces =
                null;
        if (!free) {
            if (!worldChanged) {
                // DIAGNOSTIC: the allocator bails out here without ever reading
                // the candidate set, so anything that appears while all slots
                // are held stays silent for as long as they stay held.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] LOCKED tick=%d occupied=%d/%d "
                                + "- no slot is free, candidates not even read",
                        diagTick, occupied, RAIN_MATERIAL_MAX_LOOPS);
                return;
            }
            // The world changed: a block was placed or broken. Give up the
            // loop furthest from the player so the new surface can be heard -
            // a block just placed should sound, and a distant loop is the
            // cheapest to lose. Gated on a real block update, never on the
            // periodic beat, or every refresh would reshuffle the layer and
            // churn - which is the instability being fixed here.
            if (diagTick - this.rainMatLastPreemptTick < RAIN_MATERIAL_PREEMPT_COOLDOWN) {
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT-SKIP tick=%d "
                                + "- cooldown, layer left alone", diagTick);
                return;
            }
            if (surfaces == null)
                surfaces = materialSurfaces(player, worldChanged);
            if (!hasUnheardMaterial(surfaces)) {
                // Every material in reach already sounds. Dropping one would
                // trade silence in one spot for the same sound in another,
                // which reads as the layer stuttering for no reason.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT-SKIP tick=%d "
                                + "- nothing in reach that is not already playing",
                        diagTick);
                return;
            }
            this.rainMatLastPreemptTick = diagTick;
            final var origin = player.blockPosition();
            int victim = -1;
            long victimDist = -1L;
            for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
                final var sp = this.rainMaterialSpots[i];
                if (sp == null)
                    continue;
                final long ddx = sp.getX() - (long) origin.getX();
                final long ddy = sp.getY() - (long) origin.getY();
                final long ddz = sp.getZ() - (long) origin.getZ();
                final long d = ddx * ddx + ddy * ddy + ddz * ddz;
                if (d > victimDist) {
                    victimDist = d;
                    victim = i;
                }
            }
            if (victim < 0)
                return;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT tick=%d slot=%d dist=%d "
                            + "- world changed, furthest loop dropped",
                    diagTick, victim, victimDist);
            fadeMaterialLoop(victim);
        }

        if (surfaces == null)
            surfaces = materialSurfaces(player, worldChanged);
        final int[] perMat = new int[7];
        if (surfaces != null)
            for (int q = 0; q < surfaces.size(); q++) {
                final int m = surfaces.get(q).material();
                if (m >= 0 && m < 7)
                    perMat[m]++;
            }
        // Shape, not identity: how many surfaces, and how many of each
        // material. The set is a fresh container every beat, so comparing by
        // reference logged a scan every half second and buried the picks.
        long fingerprint = surfaces == null ? -1L : surfaces.size();
        for (int q = 0; q < 7; q++)
            fingerprint = fingerprint * 8L + perMat[q];
        if (fingerprint != this.rainMatLastFingerprint
                && diagTick - this.rainMatScanPassLogTick >= RAIN_MATERIAL_SCAN_LOG_MIN_TICKS) {
            this.rainMatLastFingerprint = fingerprint;
            this.rainMatScanPassLogTick = diagTick;
            this.rainMatScanPass++;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] scan pass=%d tick=%d surfaces=%d "
                            + "mats=[glass=%d metal=%d wood=%d log=%d stone=%d leaves=%d] "
                            + "center=(%d,%d,%d)",
                    this.rainMatScanPass, diagTick,
                    surfaces == null ? 0 : surfaces.size(),
                    perMat[0], perMat[1], perMat[3], perMat[4], perMat[5], perMat[6],
                    player.blockPosition().getX(), player.blockPosition().getY(),
                    player.blockPosition().getZ());
        }
        // Only when the shape of the allocation changes. A steady scene used to
        // write this line twice a second for as long as it rained.
        final long shape = ((long) tier * 100L + occupied) * 100000L
                + (surfaces == null ? 0 : surfaces.size());
        this.rainMatLastRefreshShape = shape;
        if (surfaces == null || surfaces.size() == 0) {
            // Nothing suitable in reach. Say so, because silence is
            // indistinguishable from "broken" without this line.
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] no material surface in reach (intensity %.2f)",
                    intensity);
            return;
        }
        final var originPos = player.blockPosition();
        int picksThisBeat = 0;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
            if (this.rainMaterialLoops[i] != null)
                continue;
            final var spot = pickSpread(surfaces, i, originPos);
            if (spot == null)
                break;
            final var sound = RAIN_MATERIAL_TIERS[spot.material()][tier];
            var factory = ContainerManager.resolve(ISoundLibrary.class)
                    .getSoundFactoryOrDefault(sound);
            var loop = factory.createBackgroundSoundLoopAt(spot.pos());
            loop.setVolume(RAIN_MATERIAL_VOLUME * RAIN_MATERIAL_TRIM[spot.material()]);
            loop.setScaleTarget(0F);
            this.rainMaterialLoops[i] = loop;
            this.rainMaterialAssets[i] = sound;
            this.rainMaterialSpots[i] = spot.pos();
            this.rainMaterialMaterials[i] = spot.material();
            this.rainMaterialTiers[i] = tier;
            this.rainMaterialInvalidTicks[i] = 0;
            this.audioPlayer.play(loop);
            picksThisBeat++;
            // dy is how far below the player's feet the chosen face is. A
            // surface at dy=-7 is underground, which is how a hidden cave
            // ceiling used to be heard as rain on stone in open grass.
            // near is how far away the loop will be playing from. It is the
            // number to read when the layer sounds distant or absent: it was
            // 5-7 for every pick before this round.
            // spread is how far the new anchor sits from the nearest loop
            // already sounding. Within one group it can no longer be less
            // than RAIN_MATERIAL_MIN_SPREAD_SQ, so what it reports is the
            // cross-material case and how much room the rule actually left.
            final long spreadSq = nearestAnchorDistSq(i);
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d PICK tick=%d spots=%d -> %s "
                            + "at (%d,%d,%d) mat=%d dy=%d near=%d spread=%d tier=%d intensity=%.2f",
                    i, diagTick, surfaces.size(), sound.getPath(), spot.pos().getX(),
                    spot.pos().getY(), spot.pos().getZ(), spot.material(),
                    spot.pos().getY() - originPos.getY(),
                    (int) Math.sqrt(distanceSq(originPos, spot.pos())),
                    spreadSq < 0L ? -1 : (int) Math.sqrt((double) spreadSq), tier,
                    intensity);
        }
        // Printed here, after the beat, rather than before it: most beats pick
        // nothing, and a line about nothing was four sixths of this layer's log
        // traffic and a measurable slice of its cost. A beat that did pick is
        // always printed; one that did not is rate limited, never dropped, so a
        // scene standing silently in front of him still says so.
        if (picksThisBeat > 0
                || diagTick - this.rainMatRefreshLogTick >= RAIN_MATERIAL_REFRESH_LOG_TICKS) {
            if (picksThisBeat == 0)
                this.rainMatRefreshLogTick = diagTick;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] refresh tick=%d tier=%d occupied=%d/%d "
                            + "candidates=%d intensity=%.2f picked=%d",
                    diagTick, tier, occupied, RAIN_MATERIAL_MAX_LOOPS,
                    surfaces == null ? 0 : surfaces.size(), intensity, picksThisBeat);
        }
    }

    /**
     * Whether any material in reach is not currently sounding.
     *
     * <p>Four slots against six materials. Without this check the layer spends
     * its whole budget on whichever expanse the scan reached first, and stepping
     * from stone onto planks changes nothing you can hear.</p>
     */
    private boolean hasUnheardMaterial(final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces) {
        if (surfaces == null)
            return false;
        for (int k = 0; k < surfaces.size(); k++) {
            final int m = surfaces.get(k).material();
            boolean sounding = false;
            for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
                if (this.rainMaterialLoops[i] != null
                        && this.rainMaterialMaterials[i] == m) {
                    sounding = true;
                    break;
                }
            if (!sounding)
                return true;
        }
        return false;
    }

    /** Squared distance between two blocks, in blocks squared. */
    private static long distanceSq(final net.minecraft.core.BlockPos a,
                                   final net.minecraft.core.BlockPos b) {
        final long dx = a.getX() - (long) b.getX();
        final long dy = a.getY() - (long) b.getY();
        final long dz = a.getZ() - (long) b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Bookkeeping for the cost line, and the line itself.
     *
     * <p>Reported every {@code RAIN_MATERIAL_PERF_LOG_TICKS} ticks. {@code per
     * tick} is this layer alone, averaged over the ticks that actually ran it,
     * and the worst tick in the window sits beside it - an average hides
     * exactly the shape that turns up in the low frames. {@code cpu} is the
     * share of the wall clock those ticks consumed: a tick is 50 ms at twenty
     * ticks a second, so it reads directly as what this layer asks of the
     * render thread.</p>
     */
    private void rainMaterialPerf(final long nanos, final long restNanos) {
        this.rainMatPerfTicks++;
        this.rainMatPerfNanos += nanos;
        if (nanos > this.rainMatPerfMaxNanos)
            this.rainMatPerfMaxNanos = nanos;
        if (restNanos > this.rainMatRestMaxNanos)
            this.rainMatRestMaxNanos = restNanos;
        final long now = this.tickCount.getTickCount();
        if (now - this.rainMatPerfLogTick < RAIN_MATERIAL_PERF_LOG_TICKS
                || this.rainMatPerfTicks == 0L)
            return;
        final double budget = (double) this.rainMatPerfTicks * 50000000.0;
        this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] perf ticks=%d  per tick avg=%.3f ms worst=%.3f ms"
                        + "  scan n=%d avg=%.3f worst=%.3f"
                        + "  refresh n=%d avg=%.3f worst=%.3f"
                        + "  valid worst=%.3f  scale worst=%.3f  rest worst=%.3f ms"
                        + "  cpu=%.3f%% of wall",
                this.rainMatPerfTicks,
                this.rainMatPerfNanos / 1000000.0 / (double) this.rainMatPerfTicks,
                this.rainMatPerfMaxNanos / 1000000.0,
                this.rainMatScanCount,
                this.rainMatScanCount == 0 ? 0.0
                        : this.rainMatScanNanos / 1000000.0 / (double) this.rainMatScanCount,
                this.rainMatScanMaxNanos / 1000000.0,
                this.rainMatRefreshCount,
                this.rainMatRefreshCount == 0 ? 0.0
                        : this.rainMatRefreshNanos / 1000000.0 / (double) this.rainMatRefreshCount,
                this.rainMatRefreshMaxNanos / 1000000.0,
                this.rainMatValidMaxNanos / 1000000.0,
                this.rainMatScaleMaxNanos / 1000000.0,
                this.rainMatRestMaxNanos / 1000000.0,
                this.rainMatPerfNanos / budget * 100.0);
        this.rainMatPerfLogTick = now;
        this.rainMatPerfTicks = 0L;
        this.rainMatPerfNanos = 0L;
        this.rainMatPerfMaxNanos = 0L;
        this.rainMatScanCount = 0;
        this.rainMatScanNanos = 0L;
        this.rainMatScanMaxNanos = 0L;
        this.rainMatValidNanos = 0L;
        this.rainMatValidMaxNanos = 0L;
        this.rainMatRefreshCount = 0;
        this.rainMatRefreshNanos = 0L;
        this.rainMatRefreshMaxNanos = 0L;
        this.rainMatScaleNanos = 0L;
        this.rainMatScaleMaxNanos = 0L;
        this.rainMatRestMaxNanos = 0L;
    }

    /**
     * Squared distance from one anchor to the nearest other one, or -1 when
     * it is the only loop sounding. Logged with every pick so the next round
     * can be measured the way this one was.
     */
    private long nearestAnchorDistSq(final int slot) {
        final var pos = this.rainMaterialSpots[slot];
        if (pos == null)
            return -1L;
        long best = -1L;
        for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
            if (j == slot || this.rainMaterialSpots[j] == null)
                continue;
            final long d = distanceSq(pos, this.rainMaterialSpots[j]);
            if (best < 0L || d < best)
                best = d;
        }
        return best;
    }

    /** Still the same material, and still open to the sky? */
    private static boolean spotStillValid(final net.minecraft.world.level.Level level,
                                          final net.minecraft.core.BlockPos pos,
                                          final int material) {
        // Ask exactly what the scanner asked. Two tests that can disagree are
        // how a loop gets anchored, killed on the next tick, anchored again - 
        // the cycle that ran six times on one coordinate in the third log.
        return org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                .rainReaches(level, pos)
                && materialSound(level.getBlockState(pos)) == material;
    }

    /**
     * Where to put the next loop: away from the loops already playing, and not
     * far from the player.
     *
     * <p>Farthest-point sampling alone spreads the loops over whatever the scan
     * reached, which is what stops a large expanse from being one source - but
     * a loop maximally far from the others sits on the rim of the box, six
     * blocks off and correspondingly quiet. Sum of many: the sixth log had all
     * eight picks between five and seven blocks out and none of them under his
     * feet, which is what "not sensitive" sounds like.</p>
     */
    private org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface
            pickSpread(final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces, final int slot,
                       final net.minecraft.core.BlockPos origin) {
        org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface best = null;
        // Not -1.0. Both terms can be negative now, and a candidate scoring
        // below the seed would otherwise be rejected as "no surfaces in reach"
        // and quietly leave the slot empty.
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < surfaces.size(); k++) {
            final var s = surfaces.get(k);
            boolean clash = false;
            double nearest = -1.0;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
                if (j == slot || this.rainMaterialSpots[j] == null)
                    continue;
                final var other = this.rainMaterialSpots[j];
                if (other.equals(s.pos())) {
                    clash = true;
                    break;
                }
                final int dx = other.getX() - s.pos().getX();
                final int dy = other.getY() - s.pos().getY();
                final int dz = other.getZ() - s.pos().getZ();
                final double d = dx * dx + dy * dy + dz * dz;
                if (nearest < 0.0 || d < nearest)
                    nearest = d;
            }
            if (clash)
                continue;
            // Two loops of one group have to be RAIN_MATERIAL_MIN_SPREAD_SQ
            // apart. The cap below already stops a third and a fourth copy;
            // this stops the second copy from landing on top of the first,
            // which is what the cap could not see. See the constant.
            final int group = RAIN_MATERIAL_GROUP[s.material()];
            boolean tooClose = false;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
                if (j == slot || this.rainMaterialSpots[j] == null)
                    continue;
                if (RAIN_MATERIAL_GROUP[this.rainMaterialMaterials[j]] != group)
                    continue;
                if (distanceSq(this.rainMaterialSpots[j], s.pos())
                        < RAIN_MATERIAL_MIN_SPREAD_SQ) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose)
                continue;
            // One group is allowed RAIN_MATERIAL_MAX_PER_GROUP loops and no
            // more, and planks and logs are one group - see the constant.
            // This is what keeps a large expanse from being several copies of
            // one clip stacked on each other.
            int sameGroup = 0;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++)
                if (j != slot && this.rainMaterialLoops[j] != null
                        && RAIN_MATERIAL_GROUP[this.rainMaterialMaterials[j]] == group)
                    sameGroup++;
            if (sameGroup >= RAIN_MATERIAL_MAX_PER_GROUP)
                continue;
            // Material before distance: something no loop is playing yet is
            // lifted past any distance this box can express, which makes the
            // one loop behave like two rounds - fresh materials first, then
            // distance to order whatever is left. Without it four slots land
            // four times on the largest expanse and the planks ten blocks
            // away never get to sound.
            boolean fresh = true;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS && fresh; j++)
                if (this.rainMaterialLoops[j] != null
                        && this.rainMaterialMaterials[j] == s.material())
                    fresh = false;
            // Trade being away from the loops already playing against being
            // next to the player. Nothing anchored yet: -toPlayer makes the
            // first loop land on the surface he is standing at, which used to
            // be a coin toss over every candidate in the box.
            final long pdx = s.pos().getX() - (long) origin.getX();
            final long pdy = s.pos().getY() - (long) origin.getY();
            final long pdz = s.pos().getZ() - (long) origin.getZ();
            final double toPlayer = pdx * pdx + pdy * pdy + pdz * pdz;
            // Saturating, not unbounded - see RAIN_MATERIAL_SPREAD_CAP_SQ.
            final double spread = nearest < 0.0 ? 0.0
                    : Math.min(nearest, RAIN_MATERIAL_SPREAD_CAP_SQ);
            final double base = nearest < 0.0
                    ? -toPlayer
                    : spread - RAIN_MATERIAL_PROXIMITY_WEIGHT * toPlayer;
            final double score = fresh ? base + RAIN_MATERIAL_NEW_MATERIAL_BIAS : base;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void fadeMaterialLoop(final int k) {
        if (this.rainMaterialLoops[k] != null) {
            final var gone = this.rainMaterialSpots[k];
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d FADE tick=%d pos=%s mat=%d",
                    k, this.tickCount.getTickCount(),
                    gone == null ? "none" : gone.toShortString(),
                    this.rainMaterialMaterials[k]);
            this.rainMaterialLoops[k].setScaleTarget(0F);
            this.rainMaterialLoops[k] = null;
            this.rainMaterialAssets[k] = null;
            this.rainMaterialSpots[k] = null;
        }
    }

    private void fadeRainMaterial() {
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            fadeMaterialLoop(k);
    }

    /**
     * Whether rain landing on a surface is a thing that can happen here.
     *
     * <p>Eleventh report: savanna, desert, badlands and every snowy biome were
     * playing this layer, and none of them should. One question covers all
     * four - ask what falls in this biome, and play only where the answer is
     * rain. Desert, badlands and savanna carry {@code has_precipitation:
     * false} in the game's own biome data (read out of
     * {@code data/minecraft/worldgen/biome/*.json} to be sure, rather than
     * from memory), so they answer NONE; a freezing biome answers SNOW.
     * Neither of those is rain on a roof.</p>
     *
     * <p>Asked of the seasonal handler and not of the biome directly, the way
     * the rest of the mod asks it, so a season that makes a temperate biome
     * cold is heard as snow and not as rain. Cached, because it is asked every
     * tick and the answer is a biome lookup plus a temperature read.</p>
     */
    private boolean rainMaterialAllowedHere(final Player player) {
        final long now = this.tickCount.getTickCount();
        if (now - this.rainMatBiomeCheckTick >= RAIN_MATERIAL_BIOME_CHECK_TICKS) {
            this.rainMatBiomeCheckTick = now;
            final var phase = org.orecruncher.dsurround.lib.seasons.SeasonManager.HANDLER
                    .getPrecipitationAt(player.blockPosition());
            this.rainMatBiomeAllows =
                    phase == net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
            if (!this.rainMatBiomeAllows && now - this.rainMatBiomeLogTick >= 200L) {
                this.rainMatBiomeLogTick = now;
                final var at = player.blockPosition();
                // Rate-limited, but it must be visible: a gate that closes
                // silently is indistinguishable from a broken feature.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] biome gate closed: precipitation=%s "
                                + "at (%d,%d,%d) - this biome does not get rain",
                        phase == null ? "?" : phase.name(),
                        at.getX(), at.getY(), at.getZ());
            }
        }
        return this.rainMatBiomeAllows;
    }

    /**
     * Which density tier the intensity asks for, remembering which one is
     * playing so a threshold can be crossed back without a fight.
     *
     * <p>Without this the tier follows the intensity exactly, and the
     * eleventh log has the intensity sitting at 0.50 and at 1.00 with the
     * heavy threshold at 0.70 between them.</p>
     */
    private static int rainMaterialTierFor(final float intensity, final int current) {
        final float h = RAIN_MATERIAL_TIER_HYSTERESIS;
        if (current < 1)
            return intensity >= RAIN_MATERIAL_TIER_HEAVY ? 2
                    : (intensity >= RAIN_MATERIAL_TIER_MEDIUM ? 1 : 0);
        if (current == 1) {
            if (intensity >= RAIN_MATERIAL_TIER_HEAVY)
                return 2;
            return intensity < RAIN_MATERIAL_TIER_MEDIUM - h ? 0 : 1;
        }
        if (intensity >= RAIN_MATERIAL_TIER_HEAVY - h)
            return 2;
        return intensity >= RAIN_MATERIAL_TIER_MEDIUM ? 1 : 0;
    }

    /**
     * The material surfaces in reach, walked now.
     *
     * <p>Not a scan in flight: this is every candidate inside the box around
     * the player's current position, and nothing carries it from one tick to
     * the next. It costs a scan on the allocated beat and nothing anywhere
     * else - and it only costs that while it is raining, because the layer
     * stops calling it the moment the rain does.</p>
     */
    private ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface>
            materialSurfaces(final Player player, final boolean worldChanged) {
        final long now = this.tickCount.getTickCount();
        final var pos = player.blockPosition();
        // Walking the volume is the one real cost this layer has, and it was
        // being paid every beat even when the player had not moved and nothing
        // in the world had changed. A set cannot change while both of those are
        // true, so it is kept for a second. A move or a block update walks it
        // again immediately.
        if (!worldChanged && this.rainMatScanCache != null
                && pos.equals(this.rainMatScanAt)
                && now - this.rainMatScanTick < RAIN_MATERIAL_SCAN_CACHE_TICKS)
            return (ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface>) this.rainMatScanCache;
        final long stamp = this.rainMatTrace ? System.nanoTime() : 0L;
        final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> result =
                org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                        .scan(player.level(), pos);
        if (this.rainMatTrace) {
            final long took = System.nanoTime() - stamp;
            this.rainMatScanCount++;
            this.rainMatScanNanos += took;
            if (took > this.rainMatScanMaxNanos)
                this.rainMatScanMaxNanos = took;
        }
        this.rainMatScanCache = result;
        this.rainMatScanAt = pos;
        this.rainMatScanTick = now;
        return result;
    }

    /**
     * Which material this block is, or -1 if it is none of them or that one is
     * switched off - the scanner's answer, not a copy of it.
     */
    private static int materialSound(final net.minecraft.world.level.block.state.BlockState state) {
        // Deliberately not a second copy of the classification. The scanner
        // decides what a block is and whether that material is switched on;
        // this per-tick check used to carry its own answer, and two answers
        // to one question is how the third log got a spot that was picked,
        // killed on the next tick and picked again, six times on the same
        // coordinate. A material cut in MATERIAL_ENABLED now reads -1 here
        // too, so a loop of it already sounding fails this check and fades.
        return org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                .classify(state);
    }

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
        target *= windSwell(this.tickCount.getTickCount());
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

    /**
     * Slow swell for the wind bed, 0.42-1.0. Two sines at 14 s and 23 s: their
     * beat period is over five minutes, so the shape does not repeat inside any
     * stretch a player is likely to listen to, and the troughs land at a
     * different point every time. The loop player eases towards whatever this
     * returns, so following it costs nothing and adds no seams.
     */
    private static float windSwell(final long tick) {
        final double a = Math.sin(tick * 2D * Math.PI / (RAIN_WIND_SWELL_PERIOD_A_S * 20F));
        final double b = Math.sin(tick * 2D * Math.PI / (RAIN_WIND_SWELL_PERIOD_B_S * 20F) + 1.3D);
        float s = 0.5F + 0.5F * (float) (0.6D * a + 0.4D * b);
        return RAIN_WIND_SWELL_FLOOR + (RAIN_WIND_SWELL_PEAK - RAIN_WIND_SWELL_FLOOR) * s;
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
     * The gust layer, riding on top of the wind bed. Sparse - tens of seconds
     * apart - and one clip per gust.
     *
     * <p>Wooded biomes get a clip that carries its own wind and then its
     * leaves, played from three points at canopy height. Open country gets a
     * clip with wind and no leaves, played unpositioned. The pass before this
     * one played a wind clip <em>and</em> a leaf clip together; every leaf clip
     * in this recording already has wind in it - it is leaves <em>in</em> wind
     * - so that doubled the wind and buried the leaves. One clip, and the
     * wind-then-leaves ordering is the recording's own.</p>
     *
     * <p>Every clip in both pools, and the bed underneath them, comes out of
     * the one 106 s cinetony field recording, so a gust reads as a single
     * event. Bed and gust differ in spectrum - the bed sits near 1.2 kHz, a
     * gust sweeps past 2.7 kHz - and in level. The bed is the weather; the
     * gust is the moment.</p>
     *
     * <p>What separates a gust from rain is the swell, not the spectrum: leaves
     * and rain both rustle somewhere in 2-8 kHz, and a plateau of rustle is
     * indistinguishable from rain. So the clips start before the gust arrives
     * and keep the arrival - see {@code gust_fades} in
     * {@code tools/_make_rain_wind_assets_v2.py}. A 1.5 s fade-in on a clip
     * whose wind peaks two seconds in flattened exactly the thing that made it
     * a gust.</p>
     */
    private void handleRainTexture(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds() || this.scanner.isInside()) {
            this.rainGustNextTick = 0L;
            return;
        }
        final long now = this.tickCount.getTickCount();
        if (now < this.rainGustNextTick)
            return;
        final float intensity = PrecipitationIntensity.ambient();
        var info = this.scanner.playerLogicBiomeInfo();
        final boolean wooded = info != null && isWooded(info);
        final float min = wooded ? RAIN_LEAF_MIN_INTENSITY : RAIN_WIND_MIN_INTENSITY;
        if (intensity < min) {
            this.rainGustNextTick = now + 20L; // re-check in a second
            return;
        }
        float t = (intensity - min) / (1F - min);
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        float amp = PrecipitationIntensity.gustAmplitude();
        amp = amp < 0F ? 0F : (amp > 2F ? 2F : amp);
        final float seconds =
                (RAIN_LEAF_INTERVAL_MAX_S + (RAIN_LEAF_INTERVAL_MIN_S - RAIN_LEAF_INTERVAL_MAX_S) * t)
                        / (0.6F + 0.8F * amp);
        // Louder still when it thunders: a storm has to be heard over its own
        // rain, and that was exactly where the gusts were being buried.
        final float vol = (0.35F + 0.65F * t)
                * (player.level().isThundering() ? RAIN_GUST_STORM_GAIN : 1F);
        // var, not the resource-location type: it is Identifier on 26.1 and
        // ResourceLocation on the other two.
        final var pool = wooded ? RAIN_TEXTURE_LEAF : RAIN_TEXTURE_WIND;
        var factory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactoryOrDefault(pool[RANDOM.nextInt(pool.length)]);
        if (wooded)
            // Canopy surround. The trim keeps three sources from landing 4.8 dB
            // over one; it lives here and not in playLeafSurround because that
            // method is shared with the sunny gust, which is not ours to change.
            playLeafSurround(player, factory, vol * RAIN_LEAF_SOURCE_COMP);
        else
            // No canopy to answer, so no direction either: a gust is everywhere.
            this.audioPlayer.play(factory.createAsAdditional(vol));
        this.rainGustNextTick = now + (long) (seconds * 20F);
        // One line per gust, so tens of seconds apart: is the wooded test
        // answering what we think it is? The trait set is what decided it.
        this.logger.info("[RAIN-GUST] wooded=%s traits=%s intensity=%.2f vol=%.2f next=%.0fs",
                wooded, info == null ? "null" : info.getTraits().toString(),
                intensity, vol, seconds);
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
