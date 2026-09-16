package org.orecruncher.dsurround.processing;

import com.mojang.serialization.Codec;
import org.orecruncher.dsurround.config.libraries.IReloadEvent;
import org.orecruncher.dsurround.config.libraries.ILibrary;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.diagnostics.DataDiagnostics;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;
import org.orecruncher.dsurround.lib.resources.ResourceUtilities;

import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the comic "power words" shown on a critical hit from data, so a mod pack can add its own.
 *
 * <p>1.12.2 kept these in a lang file ({@code critword.0} .. {@code critword.84}), which meant the
 * list could only be replaced wholesale and a pack could not add to it. This uses a plain JSON
 * array, discovered through the same mechanism as every other DS data file, so all of these work:
 *
 * <ul>
 *   <li>{@code assets/&lt;namespace&gt;/dsconfigs/critwords.json} inside a mod jar;</li>
 *   <li>{@code config/dsurround/configs/&lt;namespace&gt;/critwords.json} on disk;</li>
 *   <li>a {@code "critwords"} section of a per-namespace aggregate {@code dsurround.json}
 *       (see CONFIGURATION.md section 4.9).</li>
 * </ul>
 *
 * <p>Sources are merged in load order, so shipping a file ADDS to the built-in list instead of
 * replacing it. An empty or absent file is not an error: it simply leaves the built-in list in
 * place, which is what the fallback in {@link CritWordHandler} is for.
 */
public class CritWordsLibrary implements ILibrary {

    /** A JSON array of strings; each entry is one word, shown with an exclamation mark appended. */
    private static final Codec<List<String>> CODEC = Codec.STRING.listOf();
    private static final String FILE_NAME = "critwords.json";

    /** Hard limit so a malformed file cannot make the list absurdly long. */
    private static final int MAX_WORDS = 4096;

    private final IModLog logger;

    /**
     * The words found in data. Static because {@link CritWordHandler} is a singleton that reads
     * them while spawning a particle; keeping it here avoids a dependency from the handler onto the
     * library container.
     */
    private static volatile List<String> loadedWords = List.of();

    public CritWordsLibrary(IModLog logger) {
        this.logger = ModLog.createChild(logger, "CritWords");
    }

    /**
     * The words to use, or an empty list when no data provided any - the caller then falls back to
     * its built-in defaults.
     */
    public static List<String> words() {
        return loadedWords;
    }

    @Override
    public void reload(final ResourceUtilities resourceUtilities, final IReloadEvent.Scope scope) {
        if (scope == IReloadEvent.Scope.TAGS)
            return;

        final ObjectArray<String> collected = new ObjectArray<>();
        int sources = 0;

        final var results = resourceUtilities.findModResources(CODEC, FILE_NAME, true);
        for (final var result : results) {
            final List<String> words = result.resourceContent();
            if (words.isEmpty()) {
                DataDiagnostics.note("empty crit word file", result.namespace());
                continue;
            }

            sources++;
            for (final String w : words) {
                final String trimmed = w == null ? "" : w.trim();
                if (trimmed.isEmpty())
                    continue;
                if (collected.size() >= MAX_WORDS) {
                    DataDiagnostics.fail("crit word list truncated",
                            FILE_NAME + " exceeded " + MAX_WORDS + " entries");
                    break;
                }
                collected.add(trimmed);
            }
        }

        loadedWords = List.copyOf(collected);
        this.logger.info("[CritWords] %d word(s) from %d source(s)%s",
                loadedWords.size(), sources,
                sources == 0 ? " - using the built-in list" : "");
    }

    @Override
    public Stream<String> dump() {
        return Stream.concat(
                Stream.of("crit words: " + loadedWords.size() + " from data"
                        + (loadedWords.isEmpty() ? " (built-in defaults in use)" : "")),
                loadedWords.stream().map(w -> "  " + w));
    }
}
