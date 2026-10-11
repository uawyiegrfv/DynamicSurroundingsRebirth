package org.orecruncher.dsurround.runtime.audio;

import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 第 0 期取证：门控诊断（默认关闭，只计数、只计时、只打印）。
 *
 * <p><b>怎么开</b>：JVM 参数 {@code -Ddsr.sound.diag=true}（或环境变量 {@code DSR_SOUND_DIAG=1}）。
 * 关闭时本类每一个分支都是死代码（{@link #enabled()} 是 {@code static final boolean}），
 * 日志与行为与改动前逐字相同。刻意<b>不</b>挂在 TRACE/debug 门后：那需要同时打开
 * {@code isDebugging()} 与掩码，等于没加。
 *
 * <p><b>三条基线的口径</b>（哪些是实测、哪些是替代量）：
 * <ol>
 *   <li><b>stream 池占用</b>（实测，引擎自报）：直接读 MC 的
 *       {@code Library.getDebugString()}／26.1 的 {@code getChannelDebugString()}，格式
 *       {@code "Sounds: staticUsed/staticMax + streamUsed/streamMax"}（三版分别用 javap／反编译源码
 *       核对过，格式相同）。每秒打印一次；峰值由挂在 {@code ChannelAccess.scheduleTick} 上的
 *       20 Hz 抽样统计（所以峰值是"20 Hz 采样峰值"，不是逐事件峰值）。<br>
 *       "池满导致播不出来"= {@code Library.acquireChannel(Pool)} 返回 {@code null}（实测，引擎自身的
 *       失败信号；1.20.1/1.21.1 走 {@code SoundEngine.play} 静默返回，26.1 走
 *       {@code PlayResult.NOT_STARTED}）。vanilla 只在 IDE 里才打这条警告，所以这是运行期唯一的可见迹象。</li>
 *   <li><b>各层响度</b>（实测的运行期增益）：床/呼啸/树冠/材质雨四层打印
 *       {@code base}（素材标称音量）、{@code scale}（fade 缓动当前值）、{@code target}（缓动目标）、
 *       以及 {@code vol} = {@code SoundVolumeEvaluator.getAdjustedVolume(loop)}
 *       = base×scale×类别音量×逐声音配置——这一条正是 MC 交给 OpenAL 的音量。
 *       <b>有效 dB = 素材 LUFS-I + 20·log10(vol)</b>（素材 LUFS 用离线 ffmpeg ebur128 量）。
 *       阵风包络与室内系数各自一行。</li>
 *   <li><b>耗时</b>（nanoTime 实测）：<br>
 *       声音线程：{@code snd.playDsp} = {@code SoundFXProcessor.onSourcePlay} 里
 *       {@code ctx.exec()} 的全量 DSP（含混响射线／遮挡）；{@code snd.sourceTick} =
 *       {@code SourceContext::tick} 每次／每源。<br>
 *       主线程：{@code main.process} = {@code BiomeSoundHandler.process()} 整个方法；
 *       {@code main.clientTick} = {@code SoundFXProcessor.clientTick()}；
 *       {@code main.soundStart} = {@code SoundFXProcessor.onSoundPlay()}（新建 SourceContext 的开销）。<br>
 *       另有 {@code tick.dt}：相邻两次 {@code process()} 的墙钟间隔，以及超过 50/100 ms 的次数——
 *       用来分辨"卡顿是帧率还是声音断续"：帧率问题会同时抬高 {@code tick.dt} 与各项耗时，
 *       声音断续则表现为 {@code acqNull} 增长、{@code stream} 贴顶而 {@code tick.dt} 平坦。
 *       每秒一行，按分钟汇总一行。</li>
 * </ol>
 *
 * <p><b>不变量</b>：本类不写任何游戏状态，不读改任何增益／周期／素材／判定；只有计数器与日志。
 */
public final class SoundDiag {

    // ------------------------------------------------------------------ gate

    private static final String PROPERTY = "dsr.sound.diag";
    private static final String ENVIRONMENT = "DSR_SOUND_DIAG";
    private static final boolean ENABLED = resolveEnabled();

    private SoundDiag() {
    }

    /** 维护者的开关。关闭时所有调用点都是死分支。 */
    public static boolean enabled() {
        return ENABLED;
    }

    private static boolean resolveEnabled() {
        try {
            final String p = System.getProperty(PROPERTY);
            if (p != null)
                return !isOff(p);
            final String e = System.getenv(ENVIRONMENT);
            if (e != null)
                return !e.isEmpty() && !isOff(e);
        } catch (final Throwable ignore) {
            // 取值被安全管理器挡住：诊断保持关闭，绝不因此影响游戏。
        }
        return false;
    }

    private static boolean isOff(final String v) {
        return v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off") || v.equals("0");
    }

    /**
     * 日志懒解析：关闭时 {@code ContainerManager} 一次都不会被碰。
     * （本类会在 {@code Library.acquireChannel} 的第一声播放时被加载，那时依赖容器已经就绪，
     * 但"只在真的要打印时才解析"是更稳的写法。）
     */
    private static IModLog logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    }

    // ------------------------------------------------------------------ layer slots

    public static final int LAYER_BED = 0;
    public static final int LAYER_HOWL = 1;
    public static final int LAYER_CANOPY = 2;
    public static final int LAYER_MATERIAL = 3;
    public static final int LAYER_GUST = 4;
    public static final int LAYER_INDOOR = 5;
    private static final int LAYER_COUNT = 6;
    private static final String[] LAYER_NAME = {"bed", "howl", "canopy", "material", "gust", "indoor"};

    private static final String[] layer = new String[LAYER_COUNT];

    // ------------------------------------------------------------------ meters

    private static final long WINDOW_MS = 1000L;
    private static final long MINUTE_MS = 60_000L;

    /** 时间累计器。窗口与分钟各一份；分钟那份由窗口那份在 add 时顺带累加。 */
    private static final class Meter {
        private Meter minute;
        private long count;
        private long max;
        private double sum;

        void link(final Meter m) {
            this.minute = m;
        }

        synchronized void add(final long nanos) {
            this.count++;
            this.sum += nanos;
            if (nanos > this.max)
                this.max = nanos;
            final Meter m = this.minute;
            if (m != null)
                m.add(nanos);
        }

        synchronized void reset() {
            this.count = 0L;
            this.max = 0L;
            this.sum = 0D;
        }

        synchronized long count() {
            return this.count;
        }

        synchronized double avgMs() {
            return this.count == 0L ? 0D : this.sum / 1.0E6D / this.count;
        }

        synchronized double maxMs() {
            return this.max / 1.0E6D;
        }
    }

    private static final Meter mainProcess = new Meter();
    private static final Meter mainClientTick = new Meter();
    private static final Meter mainSoundStart = new Meter();
    private static final Meter sndPlayDsp = new Meter();
    private static final Meter sndSourceTick = new Meter();
    private static final Meter tickInterval = new Meter();

    private static final Meter minuteProcess = new Meter();
    private static final Meter minuteClientTick = new Meter();
    private static final Meter minuteSoundStart = new Meter();
    private static final Meter minutePlayDsp = new Meter();
    private static final Meter minuteSourceTick = new Meter();
    private static final Meter minuteTickInterval = new Meter();

    static {
        mainProcess.link(minuteProcess);
        mainClientTick.link(minuteClientTick);
        mainSoundStart.link(minuteSoundStart);
        sndPlayDsp.link(minutePlayDsp);
        sndSourceTick.link(minuteSourceTick);
        tickInterval.link(minuteTickInterval);
    }

    // ------------------------------------------------------------------ counters

    /** 池满证据：{@code Library.acquireChannel} 返回 null 的次数（按池分）。 */
    private static final AtomicLong nullStream = new AtomicLong();
    private static final AtomicLong nullStatic = new AtomicLong();
    private static final AtomicLong nullStreamTotal = new AtomicLong();
    private static final AtomicLong nullStaticTotal = new AtomicLong();
    private static final AtomicLong okStream = new AtomicLong();
    private static final AtomicLong okStatic = new AtomicLong();
    private static final AtomicLong okStreamTotal = new AtomicLong();
    private static final AtomicLong okStaticTotal = new AtomicLong();

    private static final AtomicLong over50 = new AtomicLong();
    private static final AtomicLong over100 = new AtomicLong();
    private static final AtomicLong minuteOver50 = new AtomicLong();
    private static final AtomicLong minuteOver100 = new AtomicLong();

    private static volatile String poolRaw = "unknown";
    private static volatile int poolStream;
    private static volatile int poolStreamMax;
    private static volatile int poolStatic;
    private static volatile int poolStaticMax;
    private static volatile int poolStreamPeak;
    private static volatile int poolStreamPeakEver;
    private static volatile int dsrCtx;
    private static volatile int dsrStreamCtx;

    private static volatile long windowStartMs;
    private static volatile long minuteStartMs;
    private static volatile long lastProcessAtMs;
    private static volatile long windowSeq;
    private static volatile long minuteSeq;
    private static volatile boolean bannerDone;
    private static volatile boolean flushPending;
    private static volatile boolean sampleErrorLogged;

    // ------------------------------------------------------------------ sound thread hooks

    /** {@code SoundFXProcessor.onSourcePlay} 里 {@code ctx.exec()} 的耗时（声音线程，每次起播一次）。 */
    public static void soundThreadPlayDsp(final long nanos) {
        if (ENABLED)
            sndPlayDsp.add(nanos);
    }

    /** {@code SourceContext::tick} 的耗时（声音线程，每源每声音 tick 一次）。 */
    public static void soundThreadSourceTick(final long nanos) {
        if (ENABLED)
            sndSourceTick.add(nanos);
    }

    // ------------------------------------------------------------------ main thread hooks

    /** {@code SoundFXProcessor.onSoundPlay} 的耗时（主线程，每次起播一次：SourceContext 分配）。 */
    public static void mainThreadSoundPlay(final long nanos) {
        if (ENABLED)
            mainSoundStart.add(nanos);
    }

    /** {@code SoundFXProcessor.clientTick} 的耗时（主线程，每客户端 tick 一次）。 */
    public static void mainThreadClientTick(final long nanos) {
        if (ENABLED)
            mainClientTick.add(nanos);
    }

    /**
     * {@code BiomeSoundHandler.process} 的耗时（主线程，每客户端 tick 一次）。
     * 顺带记录与上一次的墙钟间隔，然后判断该不该开一个新窗口。
     */
    public static void mainThreadProcess(final long nanos) {
        if (!ENABLED)
            return;
        final long now = System.currentTimeMillis();
        final long last = lastProcessAtMs;
        if (last != 0L) {
            final long dt = now - last;
            tickInterval.add(dt * 1_000_000L);
            if (dt > 50L) {
                over50.incrementAndGet();
                minuteOver50.incrementAndGet();
            }
            if (dt > 100L) {
                over100.incrementAndGet();
                minuteOver100.incrementAndGet();
            }
        }
        lastProcessAtMs = now;
        mainProcess.add(nanos);
        if (windowStartMs == 0L) {
            windowStartMs = now;
            minuteStartMs = now;
            return;
        }
        if (now - windowStartMs >= WINDOW_MS)
            flushPending = true;
    }

    /** 主线程：这一个 tick 结束后有没有窗口到期（到期就先采样再 {@link #flush()}）。 */
    public static boolean flushPending() {
        return ENABLED && flushPending;
    }

    // ------------------------------------------------------------------ pool sampling

    /** {@code Library.acquireChannel} 的结果（计数；这是引擎自身的失败信号）。 */
    public static void poolAcquire(final boolean streaming, final boolean acquired) {
        if (!ENABLED)
            return;
        if (streaming) {
            if (acquired) {
                okStream.incrementAndGet();
                okStreamTotal.incrementAndGet();
            } else {
                nullStream.incrementAndGet();
                nullStreamTotal.incrementAndGet();
            }
        } else if (acquired) {
            okStatic.incrementAndGet();
            okStaticTotal.incrementAndGet();
        } else {
            nullStatic.incrementAndGet();
            nullStaticTotal.incrementAndGet();
        }
    }

    /**
     * 每个声音 tick 一次（挂在 {@code ChannelAccess.scheduleTick} 上，约 20 Hz）：
     * 抽样引擎自报的池占用并更新峰值，同时统计 DSR 自己注册的 SourceContext 数。
     */
    public static void poolSample() {
        if (!ENABLED)
            return;
        try {
            final String raw = AudioUtilities.channelPoolDebugString();
            poolRaw = raw;
            final int[] pool = parsePool(raw);
            if (pool != null) {
                poolStatic = pool[0];
                poolStaticMax = pool[1];
                poolStream = pool[2];
                poolStreamMax = pool[3];
                if (poolStream > poolStreamPeak)
                    poolStreamPeak = poolStream;
                if (poolStream > poolStreamPeakEver)
                    poolStreamPeakEver = poolStream;
            }
            final int[] counts = SoundFXProcessor.diagContextCounts();
            if (counts != null) {
                dsrCtx = counts[0];
                dsrStreamCtx = counts[1];
            }
        } catch (final Throwable ignore) {
            // 引擎正在初始化／拆卸：这一拍没有数就没有数，绝不影响声音。
        }
    }

    /**
     * 解析 {@code "Sounds: staticUsed/staticMax + streamUsed/streamMax"}，返回
     * {@code [staticUsed, staticMax, streamUsed, streamMax]}；格式不认识时返回 null
     * （原始字符串照样会打进日志，所以丢的是解析而不是信息）。
     */
    private static int[] parsePool(final String raw) {
        if (raw == null)
            return null;
        final int plus = raw.lastIndexOf('+');
        if (plus < 0)
            return null;
        final int[] stat = parsePair(raw.substring(0, plus));
        final int[] stream = parsePair(raw.substring(plus + 1));
        if (stat == null || stream == null)
            return null;
        return new int[]{stat[0], stat[1], stream[0], stream[1]};
    }

    private static int[] parsePair(final String text) {
        final int colon = text.indexOf(':');
        final String body = (colon >= 0 ? text.substring(colon + 1) : text).trim();
        final int slash = body.indexOf('/');
        if (slash <= 0)
            return null;
        try {
            final int used = Integer.parseInt(body.substring(0, slash).trim());
            final int max = Integer.parseInt(body.substring(slash + 1).trim());
            return new int[]{used, max};
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ layer snapshot

    /** 由 {@code BiomeSoundHandler} 每秒填一次；参数是已经格式化好的 {@code key=value ...} 文本。 */
    public static void layer(final int slot, final String detail) {
        if (!ENABLED || slot < 0 || slot >= LAYER_COUNT)
            return;
        layer[slot] = detail;
    }

    /** 采样本身炸了（不该发生）：只报一次，然后继续。 */
    public static void sampleFailed(final String message) {
        if (!ENABLED || sampleErrorLogged)
            return;
        sampleErrorLogged = true;
        logger().warn("[dsrsnd] layer snapshot failed (%s); the rest of the diagnostics keep running", message);
    }

    // ------------------------------------------------------------------ printing

    /** 主线程：采样完成后调用。打印这一秒的窗口，并在整分钟时补一行汇总。 */
    public static void flush() {
        if (!ENABLED)
            return;
        flushPending = false;
        final long now = System.currentTimeMillis();
        if (!bannerDone) {
            bannerDone = true;
            logger().info("[dsrsnd] phase-0 sound diagnostics are ON (" + PROPERTY + "). "
                    + "One [dsrsnd.pool]/[dsrsnd.gain]/[dsrsnd.time] block per second, one [dsrsnd.minute] line per minute. "
                    + "Nothing in this path changes a gain, a period, an asset or a decision.");
        }
        final double secs = (now - windowStartMs) / 1000.0D;
        printWindow(secs);
        resetWindow();
        windowStartMs = now;
        if (now - minuteStartMs >= MINUTE_MS) {
            printMinute((now - minuteStartMs) / 1000.0D);
            resetMinute();
            minuteStartMs = now;
        }
    }

    private static void printWindow(final double secs) {
        final long ns = nullStream.get();
        final long nt = nullStatic.get();
        logger().info(String.format(Locale.ROOT,
                "[dsrsnd.pool] seq=%d win=%.2fs raw=\"%s\" stream=%d/%d peak=%d/%d static=%d/%d "
                        + "dsrStream=%d dsrCtx=%d acqOk=%d acqNull=%d nullStream=%d nullStatic=%d",
                windowSeq, secs, poolRaw,
                poolStream, poolStreamMax, poolStreamPeak, poolStreamMax,
                poolStatic, poolStaticMax,
                dsrStreamCtx, dsrCtx,
                okStream.get() + okStatic.get(), ns + nt, ns, nt));

        for (int i = 0; i < LAYER_COUNT; i++) {
            final String detail = layer[i];
            logger().info("[dsrsnd.gain] layer=" + LAYER_NAME[i] + " "
                    + (detail == null ? "(no sample yet)" : detail));
        }

        logger().info(String.format(Locale.ROOT,
                "[dsrsnd.time] win=%.2fs | %s | %s | %s | %s | %s | %s over50=%d over100=%d",
                secs,
                meter("main.process", mainProcess),
                meter("main.clientTick", mainClientTick),
                meter("main.soundStart", mainSoundStart),
                meter("snd.playDsp", sndPlayDsp),
                meter("snd.sourceTick", sndSourceTick),
                meter("tick.dt", tickInterval),
                over50.get(), over100.get()));
    }

    private static void printMinute(final double secs) {
        logger().info(String.format(Locale.ROOT,
                "[dsrsnd.minute] seq=%d win=%.1fs | %s | %s | %s | %s | %s | %s over50=%d over100=%d "
                        + "| streamPeak=%d/%d everPeak=%d/%d acqNullTotal=%d (stream=%d static=%d) acqOkTotal=%d",
                minuteSeq, secs,
                meter("main.process", minuteProcess),
                meter("main.clientTick", minuteClientTick),
                meter("main.soundStart", minuteSoundStart),
                meter("snd.playDsp", minutePlayDsp),
                meter("snd.sourceTick", minuteSourceTick),
                meter("tick.dt", minuteTickInterval),
                minuteOver50.get(), minuteOver100.get(),
                poolStreamPeak, poolStreamMax, poolStreamPeakEver, poolStreamMax,
                nullStreamTotal.get() + nullStaticTotal.get(), nullStreamTotal.get(), nullStaticTotal.get(),
                okStreamTotal.get() + okStaticTotal.get()));
    }

    private static String meter(final String name, final Meter meter) {
        return String.format(Locale.ROOT, "%s n=%d avg=%.4f max=%.4f ms", name, meter.count(), meter.avgMs(), meter.maxMs());
    }

    private static void resetWindow() {
        windowSeq++;
        mainProcess.reset();
        mainClientTick.reset();
        mainSoundStart.reset();
        sndPlayDsp.reset();
        sndSourceTick.reset();
        tickInterval.reset();
        over50.set(0L);
        over100.set(0L);
        poolStreamPeak = poolStream;
        okStream.set(0L);
        okStatic.set(0L);
        nullStream.set(0L);
        nullStatic.set(0L);
    }

    private static void resetMinute() {
        minuteSeq++;
        minuteProcess.reset();
        minuteClientTick.reset();
        minuteSoundStart.reset();
        minutePlayDsp.reset();
        minuteSourceTick.reset();
        minuteTickInterval.reset();
        minuteOver50.set(0L);
        minuteOver100.set(0L);
    }
}
