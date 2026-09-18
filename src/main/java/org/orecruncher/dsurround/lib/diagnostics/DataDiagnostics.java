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
        // The full self-check (not just the load-time findings) is logged, because chat output
        // cannot be copied and the log is what someone can actually read afterwards.
        org.orecruncher.dsurround.eventing.ClientState.ON_CONNECT.register(
                client -> reportAndValidate(),
                org.orecruncher.dsurround.lib.events.HandlerPriority.LOW);
    }

    /**
     * Logs the findings, then the complete self-check, so a single log read covers everything.
     * Kept here rather than in the command layer so it runs without the user typing anything.
     */
    private static void reportAndValidate() {
        report();
        try {
            for (final String line : org.orecruncher.dsurround.lib.diagnostics.DataValidator.validate())
                LOGGER.info("%s", line);
        } catch (Throwable t) {
            LOGGER.warn("Data self-check could not complete: %s", t.getMessage());
        }
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

    /** Distinct findings per kind, so the one-line summary says what is wrong, not just how much. */
    private static Map<String, Integer> summariseByKind(final List<Finding> findings) {
        final Map<String, Integer> byKind = new LinkedHashMap<>();
        for (final Finding f : findings)
            byKind.merge(f.kind(), 1, Integer::sum);
        return byKind;
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
            // ONE line. Itemising every problem here duplicated the self-check that runs
            // immediately afterwards, and on a build whose data still carries blocks from other
            // game versions the itemised form produced a wall of warnings in which the ids that
            // were actually wrong were indistinguishable from the ones merely absent. The
            // breakdown by kind is what makes the count actionable; the itemised list still
            // reaches the log at info level, or /dsdump validate on demand.
            final StringBuilder kinds = new StringBuilder();
            for (final Map.Entry<String, Integer> e : summariseByKind(problems).entrySet()) {
                if (kinds.length() > 0)
                    kinds.append(", ");
                kinds.append(e.getKey()).append('=').append(e.getValue());
            }
            LOGGER.warn("Data self-check: %d problem(s) - the data named below did NOT load or does nothing (%s); the itemised list follows below or via /dsdump validate",
                    problems.size(), kinds);
        }

        for (final Finding f : notes)
            LOGGER.info("  note: %s", f);
    }
}
