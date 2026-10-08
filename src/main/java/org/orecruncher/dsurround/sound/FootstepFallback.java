package org.orecruncher.dsurround.sound;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.compat.ModIds;
import org.orecruncher.dsurround.config.SoundMapping;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.block.MatchOnBlockTag;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The B7-A footstep fallback: the answer for a block whose step sound left the table our
 * sound mappings are keyed by.
 *
 * <p>The whole material system hangs off one key - the vanilla step <em>event</em>
 * ({@code SoundLibrary.getRemappedSound} does {@code soundRemappings.get(event.location())}
 * and nothing else). A mod that rewrites block sound groups (IMB11's Sounds, formerly
 * {@code sonance}) hands a block a {@link SoundType} built from events of its own, the key
 * misses, and the block quietly drops back to its own step sound - the player hears the
 * wrong material, or nothing where the mod registered no sound. The reports name logs,
 * trapdoors and barrels.</p>
 *
 * <p><b>Behaviour, not presence.</b> Being installed says nothing: Sounds ships a
 * {@code disableBlocksEntirely} switch, and the same mod leaves plenty of blocks alone. The
 * test is therefore "does this block still report a vanilla sound group": a vanilla block
 * always gets its {@link SoundType} from one of the constants declared on that class, so an
 * instance that is not one of them (compared field by field, not by reference - a mod that
 * rebuilds an identical SoundType has not changed what the player hears) was put there by
 * somebody else. Only {@code minecraft:} blocks are judged: a modded block may hand out a
 * SoundType of its own making, nothing in the public API tells that apart from a replaced one,
 * and rewriting the footsteps of every modded block is not what this is for.</p>
 *
 * <p><b>The table, in order, and why that order</b> (the answer is always produced by the same
 * mapping rules the normal path uses - this class only recovers <em>which</em> step event the
 * block would have carried, then lets {@link SoundMapping#findMatch} do its usual block/tag
 * refinement in the data's own order):</p>
 * <ol>
 * <li><b>方块本身 - 我们自己的数据点名了这个方块.</b> A rule that lists the block was written
 *     for the event that block actually carries, so it recovers the event <em>and</em> the
 *     material in one step. This is our own deliberate decision about that block, which is why
 *     it outranks an inference drawn from what another mod left behind.</li>
 * <li><b>方块本身 - 方块自己的 SoundType.</b> Many replacement groups keep some vanilla sounds
 *     ({@code block.wood.break}, {@code block.glass.fall}, ...) and only swap the step; the
 *     vanilla palette says which step event belongs to that same family, and the block itself
 *     is then telling us what it is. Refused when the retained sounds name two families.</li>
 * <li><b>方块标签.</b> A family tag the block carries (WOOL / DIRT / SAND / LOGS / PLANKS /
 *     LEAVES / ...) names the family directly. Coarser than a block, so it comes after it - and
 *     only the tags whose vanilla blocks all share one sound family are listed.</li>
 * <li><b>原版默认.</b> {@code SoundType.STONE}, the default the vanilla palette itself gives a
 *     block whose family is unknown. A generic stone step is a poor material for the handful of
 *     metal blocks that carry no family tag, but it is what the block would have sounded like
 *     in vanilla, and it beats silence or a second step sound layered over the other mod's.</li>
 * </ol>
 *
 * <p>Nothing here invents audio: every factory comes out of our own {@code sound_mappings}
 * data, so the fallback can never disagree with the normal path about what a material sounds
 * like - only about which material applies.</p>
 */
public final class FootstepFallback {

    /** {@code SoundType.STONE}: what vanilla plays for a block whose family it does not know. */
    private static final Identifier VANILLA_DEFAULT = minecraft("block.stone.step");

    private static final IModLog LOGGER = Library.LOGGER;

    /**
     * Family tags whose vanilla blocks all share one sound family, mapped to the step event of
     * that family. Deliberately short: every entry is a statement about <em>vanilla's</em>
     * assignment of {@link SoundType} to the blocks in the tag, verifiable against the vanilla
     * tag data, and never about what a particular sound mod does. Tags that mix families
     * (doors, trapdoors, pressure plates, buttons, signs - the iron/stone ones) are left out or
     * represented only by their wooden variant, because a wrong family here is worse than the
     * generic default below.
     */
    private static final List<FamilyTag> FAMILY_TAGS = List.of(
            new FamilyTag(BlockTags.LOGS, "block.wood.step"),
            new FamilyTag(BlockTags.PLANKS, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_DOORS, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_TRAPDOORS, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_FENCES, "block.wood.step"),
            new FamilyTag(BlockTags.FENCE_GATES, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_BUTTONS, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_PRESSURE_PLATES, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_SLABS, "block.wood.step"),
            new FamilyTag(BlockTags.WOODEN_STAIRS, "block.wood.step"),
            new FamilyTag(BlockTags.ALL_SIGNS, "block.wood.step"),
            new FamilyTag(BlockTags.ALL_HANGING_SIGNS, "block.wood.step"),
            new FamilyTag(BlockTags.BEEHIVES, "block.wood.step"),
            new FamilyTag(BlockTags.BAMBOO_BLOCKS, "block.bamboo_wood.step"),
            new FamilyTag(BlockTags.LEAVES, "block.grass.step"),
            new FamilyTag(BlockTags.SAPLINGS, "block.grass.step"),
            new FamilyTag(BlockTags.WOOL, "block.wool.step"),
            new FamilyTag(BlockTags.WOOL_CARPETS, "block.wool.step"),
            new FamilyTag(BlockTags.SAND, "block.sand.step"),
            new FamilyTag(BlockTags.DIRT, "block.dirt.step"),
            new FamilyTag(BlockTags.TERRACOTTA, "block.stone.step"),
            new FamilyTag(BlockTags.STONE_BRICKS, "block.stone.step"),
            new FamilyTag(BlockTags.BASE_STONE_OVERWORLD, "block.stone.step"),
            new FamilyTag(BlockTags.CAULDRONS, "block.stone.step"),
            new FamilyTag(BlockTags.BUTTONS, "block.stone.step"),
            new FamilyTag(BlockTags.BEACON_BASE_BLOCKS, "block.metal.step"),
            new FamilyTag(BlockTags.ICE, "block.glass.step"),
            new FamilyTag(BlockTags.SNOW, "block.snow.step"),
            new FamilyTag(BlockTags.CANDLES, "block.candle.step"),
            new FamilyTag(BlockTags.CORAL_BLOCKS, "block.coral_block.step"),
            new FamilyTag(BlockTags.CRYSTAL_SOUND_BLOCKS, "block.amethyst_block.step"),
            new FamilyTag(BlockTags.WART_BLOCKS, "block.wart_block.step"),
            new FamilyTag(BlockTags.NYLIUM, "block.nylium.step"));

    /**
     * The constants declared on {@link SoundType}, read once. Every vanilla block takes its
     * sound group from this set, which is what makes "not one of these" a usable answer to
     * "was this block's sound group replaced".
     */
    private static volatile List<SoundType> vanillaSoundTypes;

    /**
     * Every break/place/hit/fall event of a vanilla family to that family's step event, built
     * from the constants above. An event shared by two families with different step events is
     * dropped rather than guessed: a wrong family is worse than falling through to the tag level.
     */
    private static volatile Map<Identifier, Identifier> familyStepEvents;

    private final IModLog logger;
    private final ITagLibrary tagLibrary;
    private final Map<Identifier, SoundMapping> soundRemappings;

    /**
     * Probe verdict per block. A verdict cannot change while the game runs (the other mod's
     * config is read when it applies its group, and a toggle is picked up by the reload that
     * clears this), so it is worth caching: the probe compares a block's sound group against
     * every vanilla constant.
     */
    private final Map<Block, Boolean> probeCache = new IdentityHashMap<>();

    /** Resolution per block state, for the same reason and with the same lifetime. */
    private final Map<BlockState, Optional<Found>> resolutionCache = new IdentityHashMap<>();

    /** Blocks already reported, so the per-block diagnostic is one line and not one per step. */
    private final Set<Block> reported = new ObjectOpenHashSet<>();

    private boolean failureLogged;
    private boolean engagementLogged;

    public FootstepFallback(final IModLog logger, final ITagLibrary tagLibrary,
                            final Map<Identifier, SoundMapping> soundRemappings) {
        this.logger = logger;
        this.tagLibrary = tagLibrary;
        this.soundRemappings = soundRemappings;
    }

    /** Drops everything derived from the loaded mappings. Called when the libraries reload. */
    public void clear() {
        this.probeCache.clear();
        this.resolutionCache.clear();
        this.reported.clear();
        this.engagementLogged = false;
    }

    /**
     * The fallback material for a block whose step event did not resolve, or empty when the
     * block is untouched, unknown, or nothing could be established.
     */
    public Optional<ISoundLibrary.SoundRemap> resolve(@Nullable final BlockState state) {
        if (state == null)
            return Optional.empty();

        // Presence is a gate, never the decision - the decision is the probe below. Without a
        // known sound-replacing mod there is nothing to defend against, and the block keeps
        // today's behaviour without the probe ever running.
        if (!ModIds.isAny(ModIds.SOUNDS, ModIds.SONANCE))
            return Optional.empty();

        try {
            if (!isSoundTypeReplaced(state))
                return Optional.empty();
            var found = this.resolutionCache.computeIfAbsent(state, this::lookup);
            if (found.isEmpty())
                return Optional.empty();
            var remap = found.get().remap;
            report(state, found.get());
            return Optional.of(remap);
        } catch (Throwable t) {
            // Never silent: a throw on this path is indistinguishable from "no fallback", and
            // that is the one answer this class exists to stop giving.
            if (!this.failureLogged) {
                this.failureLogged = true;
                this.logger.error(t, "Footstep fallback failed; affected blocks keep their own step sound.");
            }
            return Optional.empty();
        }
    }

    private Optional<Found> lookup(final BlockState state) {
        for (var candidate : new Identifier[] { eventFromOurData(state), eventFromSoundType(state), eventFromFamilyTags(state), VANILLA_DEFAULT }) {
            if (candidate == null)
                continue;
            var mapping = this.soundRemappings.get(candidate);
            if (mapping == null)
                continue;
            var match = mapping.findMatch(state);
            if (match.isEmpty())
                continue;
            var remap = new ISoundLibrary.SoundRemap(match.get().factory(), match.get().accent());
            return Optional.of(new Found(remap, candidate));
        }
        return Optional.empty();
    }

    /**
     * Returns true when the block reports a sound group that is not one of vanilla's.
     */
    private boolean isSoundTypeReplaced(final BlockState state) {
        var block = state.getBlock();
        var cached = this.probeCache.get(block);
        if (cached != null)
            return cached;
        var replaced = probe(block, state);
        this.probeCache.put(block, replaced);
        return replaced;
    }

    private boolean probe(final Block block, final BlockState state) {
        // Only vanilla blocks are judged - see the class comment. Anything else answers "no",
        // which is today's behaviour for it.
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null || !"minecraft".equals(id.getNamespace()))
            return false;

        var type = state.getSoundType();
        for (var vanilla : vanillaSoundTypes())
            if (sameSound(type, vanilla))
                return false;
        return true;
    }

    /**
     * Whether two sound groups are the same to the ear: the same recordings at the same
     * volume and pitch. Compared by value rather than by reference so that a mod rebuilding an
     * identical group is not reported as a takeover.
     */
    private static boolean sameSound(final SoundType a, final SoundType b) {
        return Float.compare(a.getVolume(), b.getVolume()) == 0
                && Float.compare(a.getPitch(), b.getPitch()) == 0
                && a.getBreakSound().location().equals(b.getBreakSound().location())
                && a.getStepSound().location().equals(b.getStepSound().location())
                && a.getPlaceSound().location().equals(b.getPlaceSound().location())
                && a.getHitSound().location().equals(b.getHitSound().location())
                && a.getFallSound().location().equals(b.getFallSound().location());
    }

    private static List<SoundType> vanillaSoundTypes() {
        var types = vanillaSoundTypes;
        if (types != null)
            return types;
        synchronized (FootstepFallback.class) {
            if (vanillaSoundTypes == null) {
                var found = new ArrayList<SoundType>(128);
                try {
                    for (Field field : SoundType.class.getFields()) {
                        if (Modifier.isStatic(field.getModifiers()) && field.getType() == SoundType.class)
                            if (field.get(null) instanceof SoundType type)
                                found.add(type);
                    }
                } catch (Throwable t) {
                    // Fail closed: an empty palette means "every block still looks vanilla",
                    // which is exactly the behaviour without this class. Logged here because
                    // this runs at most once per process.
                    LOGGER.error(t, "Unable to read the vanilla SoundType constants; the footstep fallback stays off");
                    found.clear();
                }
                vanillaSoundTypes = found;
            }
            return vanillaSoundTypes;
        }
    }

    private static Map<Identifier, Identifier> familyStepEvents() {
        var map = familyStepEvents;
        if (map != null)
            return map;
        synchronized (FootstepFallback.class) {
            if (familyStepEvents == null) {
                var built = new HashMap<Identifier, Identifier>(512);
                var ambiguous = new ObjectOpenHashSet<Identifier>();
                for (var type : vanillaSoundTypes()) {
                    var step = type.getStepSound().location();
                    for (var event : new SoundEvent[] { type.getBreakSound(), type.getStepSound(), type.getPlaceSound(), type.getHitSound(), type.getFallSound() }) {
                        var location = event.location();
                        var existing = built.put(location, step);
                        if (existing != null && !existing.equals(step))
                            ambiguous.add(location);
                    }
                }
                for (var location : ambiguous)
                    built.remove(location);
                familyStepEvents = built;
            }
            return familyStepEvents;
        }
    }

    /**
     * Level 1: the step event our own sound mappings associate with this very block. A rule that
     * names a block was written for the event that block carries in the game version the data
     * was authored against, so the event is recoverable from the rule even though the block no
     * longer reports it. Two events naming the same block (the tuff family is deliberately
     * listed under three) say nothing definite, so both are refused.
     */
    @Nullable
    private Identifier eventFromOurData(final BlockState state) {
        Identifier found = null;
        for (var entry : this.soundRemappings.entrySet()) {
            for (var rule : entry.getValue().rules()) {
                if (rule.isDefaultRule())
                    continue;
                for (var matcher : rule.blocks()) {
                    // Tags are handled by their own level: they are a coarser statement.
                    if (matcher instanceof MatchOnBlockTag || !matcher.match(state))
                        continue;
                    if (found == null)
                        found = entry.getKey();
                    else if (!found.equals(entry.getKey()))
                        return null;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Level 2: the family of the block's own sound group, read from whichever of its five sounds
     * still points at a vanilla event. Refused when they disagree - that is a mixed group, and
     * nothing in it says which family the block belongs to.
     */
    @Nullable
    private Identifier eventFromSoundType(final BlockState state) {
        var type = state.getSoundType();
        var families = familyStepEvents();
        Identifier found = null;
        for (var event : new SoundEvent[] { type.getBreakSound(), type.getStepSound(), type.getPlaceSound(), type.getHitSound(), type.getFallSound() }) {
            var family = families.get(event.location());
            if (family == null)
                continue;
            if (found == null)
                found = family;
            else if (!found.equals(family))
                return null;
        }
        return found;
    }

    /**
     * Level 3: the family named by a tag the block carries. Refused when two family tags point at
     * different families.
     */
    @Nullable
    private Identifier eventFromFamilyTags(final BlockState state) {
        Identifier found = null;
        for (var entry : FAMILY_TAGS) {
            if (!this.tagLibrary.is(entry.tag(), state))
                continue;
            var family = minecraft(entry.stepEvent());
            if (found == null)
                found = family;
            else if (!found.equals(family))
                return null;
        }
        return found;
    }

    private void report(final BlockState state, final Found found) {
        var block = state.getBlock();
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (!this.reported.add(block)) {
            this.logger.debug("Footstep fallback: %s -> %s (from %s)", id, found.remap.factory(), found.event);
            return;
        }
        if (!this.engagementLogged) {
            // One unmistakable line, on the first block that needs it: this is the answer to
            // "did the fallback table do anything", and it names the mod we think took over.
            this.engagementLogged = true;
            this.logger.info("%s replaced the vanilla sound group of one or more blocks; "
                            + "footstep materials for them are resolved by the fallback table "
                            + "(block rule -> block sound group -> block tags -> vanilla default). "
                            + "First: %s -> %s (from %s)",
                    ModIds.firstPresent(ModIds.SOUNDS, ModIds.SONANCE), id, found.remap.factory(), found.event);
        } else {
            this.logger.debug("Footstep fallback: %s -> %s (from %s)", id, found.remap.factory(), found.event);
        }
    }

    private static Identifier minecraft(final String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    /** The remap handed back plus the step event it came from, for the diagnostic line. */
    private record Found(ISoundLibrary.SoundRemap remap, Identifier event) {}

    /** A family tag and the step event of the vanilla sound family its blocks share. */
    private record FamilyTag(TagKey<Block> tag, String stepEvent) {}
}
