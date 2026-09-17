package org.orecruncher.dsurround.lib.diagnostics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.tags.BlockEffectTags;

import java.util.List;

/**
 * Runtime data self-check, surfaced through {@code /dsdump validate}.
 * <p>
 * The failures that cost real time in this port were all of one kind: something silently did
 * nothing. A tag parsed to nothing, a rule named a block that does not exist in this version, a
 * factory pointed at an event with no sound behind it. None of them threw, so none were visible -
 * and reading the data files did not help, because the data files looked fine.
 * <p>
 * Two sources feed the report:
 * <ul>
 *   <li>{@link DataDiagnostics} - failures recorded while loading (undecodable file, tag that had
 *       definitions but produced no members, unresolvable block in a rule);</li>
 *   <li>checks that need the game running: whether the event behind a factory is really registered,
 *       and whether the sound configuration entries point at events that exist.</li>
 * </ul>
 */
public final class DataValidator {

    private static final org.orecruncher.dsurround.lib.logging.IModLog LOGGER =
            org.orecruncher.dsurround.lib.logging.ModLog.createChild(
                    org.orecruncher.dsurround.lib.Library.LOGGER, "DataValidator");

    private static final ISoundLibrary SOUND_LIBRARY = ContainerManager.resolve(ISoundLibrary.class);

    private static final java.util.function.Function<String, net.minecraft.resources.ResourceLocation> RESOURCE_ID_PARSER =
            net.minecraft.resources.ResourceLocation::tryParse;

    private static final ITagLibrary TAG_LIBRARY =
            ContainerManager.resolve(ITagLibrary.class);

    /**
     * The DS effect tags whose contents decide whether a player hears anything, and one block that
     * MUST be in each of them. This exists because the worst failure in this area is silent and
     * version-specific: a tag entry naming a block id that does not exist in THIS Minecraft version
     * simply resolves to nothing, so the tag loads "fine" and the feature it drives does nothing.
     * <p>
     * That is not hypothetical - the brush tag shipped {@code minecraft:short_grass} in the 1.20.1
     * repo (the id is {@code grass} there; {@code short_grass} arrived in 1.21) and omitted
     * {@code grass}, so short grass had no brush sound in that version and nobody could see why.
     * <p>
     * The witness list is deliberately tiny: one unambiguous, stable member per tag. It is the
     * canary, not an inventory - the question is "did this version's ids resolve at all", not "is
     * every block accounted for".
     */
    private static final List<TagWitness> TAG_WITNESSES = List.of(
            new TagWitness(BlockEffectTags.BRUSH_STEP, "minecraft:fern",
                    "ferns and grass have no brush-through sound"),
            new TagWitness(BlockEffectTags.STRAW_STEP, "minecraft:sugar_cane",
                    "sugar cane and vines have no dry-straw rustle"),
            new TagWitness(BlockEffectTags.CROP_STEP, "minecraft:wheat",
                    "crops never rustle at any growth stage"),
            new TagWitness(BlockEffectTags.FOOT_OVERLAY, "minecraft:white_carpet",
                    "carpets stop counting as the walked surface"),
            new TagWitness(BlockEffectTags.LEAVES_STEP, "minecraft:oak_leaves",
                    "leaves have no rustle"),
            new TagWitness(BlockEffectTags.WATERY_STEP, "minecraft:lily_pad",
                    "lily pads have no watery step"),
            new TagWitness(BlockEffectTags.FLOOR_SQUEAKS, "minecraft:oak_planks",
                    "wooden floors stop squeaking"));

    /**
     * Animation values whose live (config file) setting must match the shipped default, with the
     * symptom of a mismatch.
     * <p>
     * This exists because of a real, repeated confusion: these are {@code @Hidden} options that no
     * GUI exposes, they are read from {@code config/dsurround/dsurround.json} on every load, and an
     * existing config file WINS over the code default. So a change to a default silently does
     * nothing on an installation whose file already carries the old number - which looks exactly
     * like "the tweak had no effect". Reporting the divergence turns that into a one-line answer.
     */
    private static final List<String[]> TUNED_DEFAULTS = List.of(
            new String[]{"popoffNumbers.lifetimeTicks", "17", "the damage/heal text lives the wrong length of time"},
            new String[]{"popoffNumbers.peakTickTicks", "5", "the text peaks too early or too late"},
            new String[]{"popoffNumbers.gravityPercent", "80", "the text falls too far or too little"},
            new String[]{"popoffNumbers.growFactor", "114", "the text grows at the wrong rate"},
            new String[]{"popoffNumbers.driftPercent", "60", "the text is thrown the wrong distance"});

    private static void checkTunedDefaults(final ObjectArray<String> report) {
        final var config = org.orecruncher.dsurround.lib.config.ConfigurationData
                .getConfig(org.orecruncher.dsurround.Configuration.class);
        int drift = 0;
        for (final String[] entry : TUNED_DEFAULTS) {
            final String key = entry[0];
            final String expected = entry[1];
            final String live = liveValueOf(config, key);
            if (live == null || live.equals(expected))
                continue;
            drift++;
            report.add("  %s = %s in the config file, shipped default is %s -> %s".formatted(
                    key, live, expected, entry[2]));
        }
        if (drift == 0)
            report.add("  tuned animation: defaults and config file agree.");
        else
            report.add("  tuned animation: " + drift + " hidden option(s) differ from the shipped default"
                    + " (the config file wins - this is only a problem if you did not choose it)");
    }

    /** Current value of a "section.field" of the loaded config, or null if it cannot be read. */
    private static String liveValueOf(final Object config, final String key) {
        try {
            final int dot = key.indexOf('.');
            var section = config.getClass().getField(key.substring(0, dot)).get(config);
            final Object value = section.getClass().getField(key.substring(dot + 1)).get(section);
            return String.valueOf(value);
        } catch (final ReflectiveOperationException e) {
            return null;
        }
    }

    private record TagWitness(net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag,
                              String requiredMember,
                              String consequence) {
    }

    /**
     * Prefixes of the sound events DS itself defines. An event in these namespaces that has no
     * metadata has no sound files behind it, so the factory that names it plays silence - the
     * failure mode when an event name carries a typo.
     */
    private static final List<String> DS_EVENT_PREFIXES = List.of(
            "footsteps.", "footstep_accent", "biome.", "armor.", "item.", "waterfall.",
            "toolbar.", "player.", "brush_step", "thunder");

    private static final int MAX_LISTED = 20;

    private DataValidator() {
    }

    /** Builds the validation report as lines of text. */
    public static List<String> validate() {
        final ObjectArray<String> report = new ObjectArray<>();

        report.add("DS data self-check");
        report.add("");

        final List<DataDiagnostics.Finding> problems = DataDiagnostics.problems();
        if (problems.isEmpty()) {
            report.add("Load-time: OK - no failures recorded while loading.");
        } else {
            report.add("Load-time: " + problems.size() + " problem(s)");
            for (final DataDiagnostics.Finding f : problems)
                report.add("  " + f);
        }

        report.add("");
        report.add("Runtime cross-checks:");
        final int before = report.size();
        checkSoundEvents(report);
        checkSoundConfiguration(report);
        checkEffectTags(report);
        checkTunedDefaults(report);
        countUnmappedBlocks(report);
        if (report.size() == before)
            report.add("  OK - nothing to report.");

        final List<DataDiagnostics.Finding> notes = DataDiagnostics.notes();
        if (!notes.isEmpty()) {
            report.add("");
            report.add("Notes:");
            for (final DataDiagnostics.Finding f : notes)
                report.add("  " + f);
        }

        return List.copyOf(report);
    }

    /**
     * Runs the self-check and writes it to the LOG as well as returning it.
     * <p>
     * Chat output cannot be copied, so a report that only goes to chat is of limited use for
     * reporting a problem to someone else. The log copy is the full, greppable record; the caller
     * still shows the same text in chat for immediate reading.
     */
    public static List<String> validateAndLog() {
        final List<String> report = validate();
        for (final String line : report)
            LOGGER.info("%s", line);
        return report;
    }

    /**
     * A DS factory event with no metadata has no sound files behind it.
     */
    private static void checkSoundEvents(final ObjectArray<String> report) {
        final var registered = SOUND_LIBRARY.getRegisteredSoundEvents();
        if (registered.isEmpty()) {
            report.add("  sound events: registry empty - data may not have reloaded yet");
            return;
        }

        // Every footstep material must have a sounds.json entry behind its event. An event that is
        // REGISTERED but has no metadata still raises the event and shows its subtitle, yet plays
        // nothing at all - which is precisely the "correct subtitle, no sound" failure, so name them
        // explicitly instead of relying on the prefix scan below.
        int materialWithoutSound = 0;
        for (final var event : registered) {
            final ResourceLocation location = event.getLocation();
            if (!"dsurround".equals(location.getNamespace()))
                continue;
            if (!location.getPath().startsWith("footsteps"))
                continue;
            if (!SOUND_LIBRARY.getSoundMetadata(location).isDefault())
                continue;
            materialWithoutSound++;
            if (materialWithoutSound <= MAX_LISTED)
                report.add("  footstep event with NO sound behind it: " + location);
        }
        if (materialWithoutSound == 0)
            report.add("  footstep materials: every registered footstep event has sound behind it.");
        else
            report.add("  footstep materials: " + materialWithoutSound
                    + " event(s) are silent (listed above) - these will show a subtitle but play nothing");

        int silent = 0;
        for (final var event : registered) {
            final ResourceLocation location = event.getLocation();
            if (!DS_EVENT_PREFIXES.stream().anyMatch(p -> location.getPath().startsWith(p)))
                continue;
            if (!SOUND_LIBRARY.getSoundMetadata(location).isDefault())
                continue;

            silent++;
            if (silent <= MAX_LISTED)
                report.add("  event with no sound behind it: " + location);
            DataDiagnostics.note("event without sound metadata", location.toString());
        }

        if (silent == 0)
            report.add("  sound events: every DS factory event has metadata.");
        else
            report.add("  sound events: " + silent + " DS event(s) have no metadata"
                    + (silent > MAX_LISTED ? " (first " + MAX_LISTED + " listed)" : ""));
    }

    /**
     * Every individual sound configuration entry must name a registered event, or the user's volume
     * and mute settings for it do nothing.
     */
    private static void checkSoundConfiguration(final ObjectArray<String> report) {
        int dangling = 0;
        for (final var entry : SOUND_LIBRARY.getIndividualSoundConfigs()) {
            // IndividualSoundConfigEntry exposes public fields, not accessors
            if (SOUND_LIBRARY.isSoundRegistered(entry.soundEventId))
                continue;
            dangling++;
            if (dangling <= MAX_LISTED)
                report.add("  soundconfig entry for an unregistered event: " + entry.soundEventId);
        }

        if (dangling == 0)
            report.add("  sound config: every entry names a registered event.");
        else
            report.add("  sound config: " + dangling + " entry(ies) name events that do not exist");
    }

    /**
     * Do the effect tags actually contain what this Minecraft version has?
     * <p>
     * A tag entry that names a block id absent from this version resolves to nothing without any
     * error: the tag still loads, still reports members, and simply does not cover the block the
     * author meant. The failure is invisible in the data file (which looks right) and invisible at
     * runtime (which is just quiet), so it is checked here against a witness block per tag.
     */
    private static void checkEffectTags(final ObjectArray<String> report) {
        int missing = 0;
        for (final TagWitness witness : TAG_WITNESSES) {
            final var id = RESOURCE_ID_PARSER.apply(witness.requiredMember());
            if (id == null) {
                report.add("  TAG WITNESS BUG: unparsable id " + witness.requiredMember());
                continue;
            }
            final var block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block == null) {
                // The witness itself does not exist in this version - that is a bug in this check,
                // not in the data, so say so plainly rather than blaming the tag file.
                report.add("  TAG WITNESS BUG: %s does not exist in this version".formatted(witness.requiredMember()));
                continue;
            }
            if (TAG_LIBRARY.is(witness.tag(), block.defaultBlockState()))
                continue;
            missing++;
            report.add("  effect tag %s does NOT contain %s -> %s".formatted(
                    witness.tag().location(), witness.requiredMember(), witness.consequence()));
        }

        if (missing == 0)
            report.add("  effect tags: every checked tag contains this version's blocks.");
        else
            report.add("  effect tags: " + missing + " tag(s) are missing a block this version has"
                    + " (a tag entry naming an id from ANOTHER Minecraft version does exactly this)");
    }

    /**
     * How many blocks have a step sound that no explicit DS rule covers.
     * <p>
     * A high count is not an error by itself - modded blocks the data has never heard of are
     * expected to fall through to the catch-all. What matters is the shape: a count that suddenly
     * includes ordinary vanilla blocks means a mapping file stopped loading, which from in-game
     * looks like "footsteps became generic".
     */
    private static void countUnmappedBlocks(final ObjectArray<String> report) {
        int total = 0;
        int vanillaUnmapped = 0;
        final ObjectArray<String> samples = new ObjectArray<>();

        for (final var block : BuiltInRegistries.BLOCK) {
            total++;
            final var state = block.defaultBlockState();
            final var stepSound = state.getSoundType().getStepSound();
            if (SOUND_LIBRARY.hasExplicitRemap(stepSound, state))
                continue;

            final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!"minecraft".equals(id.getNamespace()))
                continue;
            if ("minecraft:air".equals(id.toString()))
                continue;

            vanillaUnmapped++;
            if (samples.size() < 10)
                samples.add(id.toString());
        }

        report.add(String.format("  block coverage: %d blocks; %d vanilla block(s) fall through to the "
                + "catch-all material instead of an explicit rule", total, vanillaUnmapped));
        if (vanillaUnmapped > 0)
            report.add("    samples: " + String.join(", ", samples));
    }
}
