package org.orecruncher.dsurround.lib.diagnostics;

import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.logging.ModLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the failures that this mod would otherwise swallow.
 * <p>
 * Nearly every bug that cost real debugging time in this port had the same shape: data or resources
 * failed to load, and the only trace was a debug-level log line that is invisible by default. A tag
 * that decodes to nothing, a file whose JSON the lenient reader rejects, a rule naming a block that
 * does not exist in this version - all of them simply "did not work", with no hint about why.
 * <p>
 * This class gives those failures a home so they can be reported together, once, after the data has
 * finished loading:
 * <ul>
 *   <li>{@link #fail} records a failure that loses data (a file that could not be decoded, a rule
 *       pointing at a block that does not exist, a tag that loaded empty);</li>
 *   <li>{@link #note} records something worth knowing that is not necessarily broken.</li>
 * </ul>
 * Findings are merged by kind+detail, so a failure repeated across reloads is described once with a
 * count instead of flooding the log.
 */
public final class DataDiagnostics {

    private static final IModLog LOGGER = ModLog.createChild(Library.LOGGER, "DataDiagnostics");

    /** A failure that results in missing or inert behaviour. */
    public record Finding(String kind, String detail, int count) {
        @Override
        public String toString() {
            return this.count > 1
                    ? String.format("[%s] %s (%d occurrences)", this.kind, this.detail, this.count)
                    : String.format("[%s] %s", this.kind, this.detail);
        }
    }

    private static final Map<String, Integer> PROBLEMS = new LinkedHashMap<>();
    private static final Map<String, Integer> NOTES = new LinkedHashMap<>();

    static {
        // Emit the collected findings once the client is in a world, which is after every data
        // library has reloaded. Without this the failures stay invisible exactly as before.
        org.orecruncher.dsurround.eventing.ClientState.ON_CONNECT.register(
                client -> report(),
                org.orecruncher.dsurround.lib.events.HandlerPriority.LOW);
    }

    private DataDiagnostics() {
    }

    /** Records a failure that loses data. Safe to call from any thread; duplicates are counted. */
    public static void fail(final String kind, final String detail) {
        add(PROBLEMS, kind, detail);
    }

    /** Records a non-fatal observation. */
    public static void note(final String kind, final String detail) {
        add(NOTES, kind, detail);
    }

    private static synchronized void add(final Map<String, Integer> target, final String kind, final String detail) {
        // the key keeps kind and detail apart even if one contains the other's separator
        final String key = kind + "\u0000" + detail;
        target.merge(key, 1, Integer::sum);
    }

    private static synchronized List<Finding> toList(final Map<String, Integer> source) {
        final List<Finding> result = new ArrayList<>(source.size());
        for (final Map.Entry<String, Integer> e : source.entrySet()) {
            final int split = e.getKey().indexOf('\u0000');
            result.add(new Finding(e.getKey().substring(0, split), e.getKey().substring(split + 1), e.getValue()));
        }
        return result;
    }

    public static synchronized List<Finding> problems() {
        return toList(PROBLEMS);
    }

    public static synchronized List<Finding> notes() {
        return toList(NOTES);
    }

    public static synchronized boolean hasProblems() {
        return !PROBLEMS.isEmpty();
    }

    public static synchronized void clear() {
        PROBLEMS.clear();
        NOTES.clear();
    }

    /**
     * Emits the collected findings. Called after all data has loaded, so one log read is enough to
     * see everything that did not work.
     */
    public static synchronized void report() {
        final List<Finding> problems = toList(PROBLEMS);
        final List<Finding> notes = toList(NOTES);

        if (problems.isEmpty()) {
            LOGGER.info("Data self-check: no problems found%s",
                    notes.isEmpty() ? "" : String.format(" (%d note(s))", notes.size()));
        } else {
            LOGGER.warn("Data self-check: %d problem(s) - the data named below did NOT load or does nothing",
                    problems.size());
            for (final Finding f : problems)
                LOGGER.warn("  %s", f);
        }

        for (final Finding f : notes)
            LOGGER.info("  note: %s", f);
    }
}
