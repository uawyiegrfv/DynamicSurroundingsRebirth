package org.orecruncher.dsurround.processing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.math.TimerEMA;

/**
 * Opt-in cost report for the log, one greppable line per module.
 *
 * <p>Why this exists: the per-module tick timers already existed (every
 * {@code AbstractClientHandler} owns a {@link TimerEMA} that {@code Handlers} feeds
 * each tick) but they were only visible on the diagnostics HUD, which cannot be
 * logged, compared across runs or fed to a script. The render side had no timers at
 * all. This class turns both into a periodic log line, gated on debug logging.</p>
 *
 * <p>Format, deliberately machine readable:</p>
 * <pre>
 * [PERF] tick modules=21 total=1.842 ms
 * [PERF] tick module=BiomeSound ms=0.412 pct=0.824
 * [PERF] render module=weather.rain ms=0.231 pct=1.386
 * </pre>
 *
 * <p>{@code pct} is the share of a 50 ms tick for the tick rows and of a 16.67 ms
 * frame for the render rows. Values are EMAs (100 periods), so a single report is
 * already smoothed and consecutive reports can be compared directly.</p>
 *
 * <p>Cost when disabled: {@link #mark()} returns 0 and every caller's record is a
 * single compare. When enabled it is two {@code System.nanoTime()} calls per render
 * entry - about 50 ns, against a 16 ms frame.</p>
 */
public final class PerfStats {

    /** 200 ticks at 20 Hz - one report every 10 s. */
    public static final int REPORT_INTERVAL_TICKS = 200;

    private static final float TICK_BUDGET_MS = 50F;
    private static final float FRAME_BUDGET_MS = 1000F / 60F;

    /** Render-side EMAs, keyed by module name. Insertion ordered for a stable log. */
    private static final Map<String, TimerEMA> RENDER_TIMERS = new LinkedHashMap<>();

    private PerfStats() {
    }

    /** True when the debug switch is on. Read per call so a config change takes effect at once. */
    public static boolean enabled() {
        final var cfg = Client.Config;
        return cfg != null && cfg.logging.enableDebugLogging;
    }

    /** Start mark for a timed block. Returns 0 when disabled, which {@link #record} ignores. */
    public static long mark() {
        return enabled() ? System.nanoTime() : 0L;
    }

    /** End a block started with {@link #mark()}. Cheap no-op for a 0 mark. */
    public static void record(final String module, final long mark) {
        if (mark == 0L)
            return;
        final long delta = System.nanoTime() - mark;
        RENDER_TIMERS.computeIfAbsent(module, n -> new TimerEMA("render." + n, 100)).update(delta);
    }

    /**
     * Emit one report: tick modules (already smoothed by their own timers) and the render
     * modules recorded since the last report.
     *
     * @param log     the mod log; the caller has already checked {@code isDebugging()}
     * @param total   the whole handler-tick timer
     * @param modules per-module tick timers, one per handler
     * @param tick    the tick counter, printed so two reports can be told apart
     */
    public static void report(final IModLog log, final TimerEMA total,
                              final List<TimerEMA> modules, final long tick) {
        final List<TimerEMA> sorted = new ArrayList<>(modules);
        sorted.sort(Comparator.comparingDouble(TimerEMA::getMSecs).reversed());

        log.debug("[PERF] tick modules=%d total=%.3f ms", sorted.size(), total.getMSecs());
        for (final TimerEMA t : sorted) {
            final double ms = t.getMSecs();
            log.debug("[PERF] tick module=%s ms=%.3f pct=%.2f", t.name(), ms, ms / TICK_BUDGET_MS * 100.0D);
        }
        for (final Map.Entry<String, TimerEMA> e : RENDER_TIMERS.entrySet()) {
            final double ms = e.getValue().getMSecs();
            log.debug("[PERF] render module=%s ms=%.3f pct=%.2f", e.getKey(), ms, ms / FRAME_BUDGET_MS * 100.0D);
        }
        log.debug("[PERF] report tick=%d interval=%d", tick, REPORT_INTERVAL_TICKS);
    }
}
