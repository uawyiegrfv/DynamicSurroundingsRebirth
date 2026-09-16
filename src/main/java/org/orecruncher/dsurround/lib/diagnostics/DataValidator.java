package org.orecruncher.dsurround.lib.diagnostics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;

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

    private static final ISoundLibrary SOUND_LIBRARY = ContainerManager.resolve(ISoundLibrary.class);

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
     * A DS factory event with no metadata has no sound files behind it.
     */
    private static void checkSoundEvents(final ObjectArray<String> report) {
        final var registered = SOUND_LIBRARY.getRegisteredSoundEvents();
        if (registered.isEmpty()) {
            report.add("  sound events: registry empty - data may not have reloaded yet");
            return;
        }

        int silent = 0;
        for (final var event : registered) {
            final Identifier location = event.location();
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

            final Identifier id = BuiltInRegistries.BLOCK.getKey(block);
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
