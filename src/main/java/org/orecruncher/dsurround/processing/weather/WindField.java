package org.orecruncher.dsurround.processing.weather;

import net.minecraft.util.Mth;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

import java.util.Random;

/**
 * WIND：全模组唯一的水平风来源 —— 风向 + <b>风等级</b>。
 *
 * <p>雨幕的倾斜、雪的横飘、沙尘幕的漂移，以及风声的三层（床 / 呼啸 / 叶声），
 * 全部问这个类，而不是各自掷随机数：同一时刻只能有一阵风在吹。</p>
 *
 * <h2>风等级是唯一的输出（统一风系统，2026-10-11）</h2>
 *
 * <pre>
 *   shape(t)  = 本场事件的双正弦 + tanh 软削波 + 一条非周期随机游走     0..1
 *   mean      = PRESET[群系].meanLevel[季节] x SEASON_FACTOR[季节] + 本场抖动
 *   depth     = PRESET[群系].gustDepth
 *   level(t)  = clamp(mean + depth x (2 x shape(t) - 1), 地板, 天花板)   0..1
 *   speed     = 0.15 + 7.2 x level                                      格/秒
 * </pre>
 *
 * <p>{@code speed = WIND_LEVEL_MIN_BPS + WIND_LEVEL_SPAN_BPS x level} 就是
 * {@code BiomeSoundHandler} 的折回锚点本身 —— 两端是同一份常量，不是两份各自维护
 * （那正是 2026-10-11 P1 修掉的那个 bug）。</p>
 *
 * <h2>为什么均值与波动必须解耦</h2>
 *
 * <p>改动前是 {@code speed = (MIN + RANGE x S + GUST x G x S) x 群系 x 季节}：
 * 阵风项与强度 S 是<b>乘性绑定</b>的，而最后的标量倍率同时缩放均值与方差。
 * 维护者三个性格用例里最要命的两条判据（恶地 {@code crest <= 6 dB}、树林
 * {@code crest >= 8 dB}）量的都是两者之比，所以 18522 组网格搜索的可行解 = 0：
 * <b>倍率能调"多大声"，调不了"多稳"。</b>现在阵风项是加性的、不含 base
 * （{@code depth x shape}），均值由预设给，两者的比就成了一个可以独立设定的数。</p>
 *
 * <h2>为什么形状是"游戏时钟的纯函数 + 每场事件一组参数"</h2>
 *
 * <p>本模组没有一个"晴天也会跑"的 tick 钩子 —— 一个靠 tick 推进的状态场会在晴天
 * 冻住，然后在暴风雨开始的第一帧突然跳到新值。所以形状仍然是时钟的纯函数；变化的
 * 只是<b>参数</b>：每场事件重摇一次（{@link #rollShape}），由
 * {@code PrecipitationIntensity} 在事件开始时给出种子。同一 tick 永远同一答案。</p>
 *
 * <p>{@link #windShapeAt} 里还有一条<b>非周期</b>分量（确定性哈希的低频随机游走，
 * 权重 {@link #WANDER_WEIGHT}）：正弦和的谱平坦度永远是 0，耳朵听到的是"每隔
 * 90 秒同样的形状回来一次"（实测自相关峰 0.9995）。随机游走是唯一能把它推离 0 的
 * 手段，而哈希保证它不需要状态。</p>
 */
public final class WindField {

    // ================= 统一风系统的量程（唯一定义处） =================

    /**
     * 风等级 0 对应的风速，格/秒。
     *
     * <p>WindField 的常数地板原来是 0.8，后来降到 0.15，而音频那边折回用的还是 0.8
     * —— 两把尺子，低风段整段是死区（2026-10-11 P1 修的就是这一处）。现在这一段
     * 同时是风速的地板和音频折回的锚点，改一个等于改两个。</p>
     */
    public static final float WIND_LEVEL_MIN_BPS = 0.15F;

    /** 风等级 1 比 0 高出的风速，格/秒。<b>level = 1.0 就是 0.15 + 7.2 = 7.35 格/秒。</b> */
    public static final float WIND_LEVEL_SPAN_BPS = 7.2F;

    // ================= 风向 =================

    /** 风向扫掠一整圈所需的 tick 数。越小转得越快。
     *  18000 tick = 15 分钟一圈 = 24 度/分钟。 */
    private static final float WIND_VEER_CYCLE_TICKS = 18000F;

    /** 快摆动的幅度，弧度。0.35 约 20 度。 */
    private static final float WIND_VEER_WOBBLE_RAD = 0.35F;

    /** 快摆动的周期，tick。2400 tick = 2 分钟。 */
    private static final float WIND_VEER_WOBBLE_PERIOD_TICKS = 2400F;

    /** 倾斜方向的符号。1F = 雨丝朝风去的那一侧倒。
     *  实机如果发现倒向相反，把这里改成 -1F —— 一处，三版同改。 */
    public static final float SLANT_SIGN = 1F;

    // ================= 阵风形状（每场事件重摇） =================
    //
    // 形状照 _wind_audit/wind_design_verify.py 的 shape_series 实现：两条正弦
    // （权重 0.7 / 0.3，第二条的周期是第一条的 2.2~3.5 倍）+ tanh 软削波。
    // 三个性格用例的验收数字就是在这一条形状上算出来的，所以这里必须逐字对应。

    /** tanh 软削波系数。k = 1.8 与离线复算一致：把正弦的圆顶削平、过零点留陡。 */
    private static final double SHAPE_SOFT_CLIP = 1.8D;

    /** 第一条正弦的权重，剩下给第二条。 */
    private static final double SHAPE_WEIGHT_A = 0.70D;

    /** 第二条正弦相对第一条的周期比范围（每场事件在区间内摇一次）。 */
    private static final double SHAPE_RATIO_MIN = 2.2D;
    private static final double SHAPE_RATIO_MAX = 3.5D;

    /** 没有事件时（沙尘幕、诊断在事件外被问）用的默认周期与相位。 */
    private static final double SHAPE_PERIOD_DEFAULT_S = 150D;
    private static final double SHAPE_PHASE_DEFAULT = 1.7D;

    /**
     * 非周期分量的幅度，<b>落在 0.15 ~ 0.25 之间</b>（风场审查 S7）：0.20 = 形状上
     * 加一条 ±0.10 的零均值扰动，也就是形状整个 0..1 量程的 20%。
     *
     * <p>是<b>零均值扰动</b>，不是"把周期项按 0.8 缩放再混进来"——这一条是实测定的：
     * 混进来会把周期项的摆幅一起压掉两成，于是树林（mean 0.09 / depth 0.36）越过
     * 0.35 的阵风峰从 3~4 个/10min 掉到 0.7 个，直接踩穿维护者用例 F2 的下限
     * （1~6 个/10min）。扰动不改变周期项的摆幅，只把每一次峰的高度轻轻推一下。</p>
     */
    private static final double WANDER_WEIGHT = 0.20D;

    /**
     * 随机游走的四个八度周期，秒。互不通约，跨 1.4 个数量级。刻意全部是<b>低频</b>：
     * 最快的 23 s 比一个阵风峰（0.2~0.6/min ⇒ 100~300 s）还慢一档，所以它做的是
     * "这一次峰比上一次高一点"，不会在峰上再切出小尖 —— 6 s 级的八度会，实测把
     * 树林越 0.35 的峰从 3~4 个/10min 顶到 6.0（判据上限就是 6）。
     */
    private static final double[] WANDER_PERIODS_S = { 23.0D, 71.0D, 211.0D, 601.0D };

    /** 事件内状态。{@link #rollShape} 写，{@link #windShapeAt} 读；同一 tick 同一答案。 */
    private static long shapeSeed = 0L;
    private static double shapePeriodA = SHAPE_PERIOD_DEFAULT_S;
    private static double shapePeriodB = SHAPE_PERIOD_DEFAULT_S * 2.5D;
    private static double shapePhase = SHAPE_PHASE_DEFAULT;
    private static double wanderSeed = 0.5D;
    private static float meanJitter = 0F;

    // ================================================================

    private static final double TWO_PI = Math.PI * 2D;

    /** 游戏 tick 与秒的换算。UV 的数学用格/tick，参数表用格/秒。 */
    private static final float TICKS_PER_SECOND = 20F;

    private static float angle = 0F;
    private static float dirX = 1F;
    private static float dirZ = 0F;
    private static float speedBPS = 0F;
    private static float speedBPT = 0F;

    private WindField() {
    }

    /**
     * 每场事件开始时摇一次阵风的形状参数。
     *
     * <p>由 {@code PrecipitationIntensity} 在事件开始时调用，参数取自事件的种子。
     * {@code gustRatePerMin} 来自预设 —— "多久刮一次"是性格的一部分，而它在此之前
     * 是一条写死的 28~120 s 周期，而且完全不作用于风床。</p>
     *
     * @param seed           本场事件的种子（同一个事件必须给同一个值）
     * @param gustRatePerMin 预设的阵风频率，每分钟几个峰；0 = 不刮（WASTELAND）
     * @param levelJitter    预设的跨场次抖动 sigma
     */
    public static void rollShape(final long seed, final float gustRatePerMin, final float levelJitter) {
        shapeSeed = seed;
        final Random random = new Random(seed);
        final double rate = Math.max(0.01D, gustRatePerMin);
        // 每分钟 rate 个阵风峰 ⇒ 基频周期 60/rate 秒。
        shapePeriodA = 60D / rate;
        shapePeriodB = shapePeriodA * (SHAPE_RATIO_MIN + random.nextDouble() * (SHAPE_RATIO_MAX - SHAPE_RATIO_MIN));
        shapePhase = random.nextDouble() * TWO_PI;
        wanderSeed = random.nextDouble() * 1024D;
        // 跨场次抖动：N(0, sigma) 截断 ±2 sigma，每场事件摇一次（不是每 tick）。
        meanJitter = levelJitter <= 0F ? 0F
                : (float) Mth.clamp(random.nextGaussian() * levelJitter, -2D * levelJitter, 2D * levelJitter);
    }

    /** 本场事件摇出来的均值抖动，加在预设的 mean 上。 */
    public static float meanJitter() {
        return meanJitter;
    }

    /** 本场事件的形状种子。诊断，以及"事件换了没有"的判断。 */
    public static long shapeSeed() {
        return shapeSeed;
    }

    /**
     * 风等级，0..1。<b>统一风系统的唯一输出。</b>
     *
     * <p>纯函数：给定预设与游戏时刻，答案永远一样 —— <b>风有意独立于降水。</b></p>
     *
     * <p>这里曾经收一个 {@code strength}（本场降水的分级强度）形参，它只经预设的
     * {@link GustResponse} 影响阵风<b>深度</b>。但七个预设全部是 {@code GustResponse.FLAT}
     * （{@code factor(base) ≡ 1}），所以 {@code depth} 恒等于 {@code gustDepth}，
     * 与降水没有任何关系：离线按 Java 语义逐行复算，CLEAR / drizzle(0.30) / heavy(0.80)
     * 三档<b>逐 tick 完全相同</b>（{@code _weather_comprehensive/wind_sim.py}）。
     * 维护者口径是<b>层只吃风</b>，所以形参删掉，而不是留一个假旋钮。</p>
     *
     * <p>雨的强弱在别处表达：降水自己有 {@code PrecipitationIntensity} 的曲线与摆动，
     * 而 {@link GustResponse} 这个枚举在两个地方各有用途 —— 降水那边用它算雨的
     * 摆动幅度（{@code PrecipitationProfile.gustAmplitudeAt}），风的这边只留预设的
     * 固定深度。想让暴雨把风推大，那是一次新的设计决定，不是把这个死参数接回去。</p>
     *
     * @param profile     群系预设，null 视为没有风
     * @param gameTime    世界游戏时间
     * @param partialTick 帧内插值
     * @return 风等级，clamp 到预设的地板与天花板之间
     */
    public static float levelAt(final WindProfile profile, final long gameTime, final float partialTick) {
        if (profile == null)
            return 0F;
        final float mean = Mth.clamp(profile.meanFor(PrecipitationIntensity.season()) + meanJitter, 0F, 1F);
        final float depth = profile.gustDepth();
        final float shape = windShapeAt(gameTime, partialTick);
        return Mth.clamp(mean + depth * (shape * 2F - 1F), profile.floorLevel(), profile.peakCeiling());
    }

    /**
     * 阵风形状，0..1，游戏时钟的纯函数。
     *
     * <p>两条正弦（周期与相位每场事件重摇）+ tanh 软削波，再掺一条非周期的低频
     * 随机游走。0 = 谷，1 = 峰；{@link #levelAt} 把它映射成 {@code mean ± depth}。</p>
     *
     * <p>与旧实现的两处差别都是实测出来的：慢层不再走 power law（那会把掺进去的快层
     * 压成 0.9% 的净贡献），两层按权重<b>线性</b>叠加；事件周期不再写死 1800/460 tick
     * （单条 90 s 周期让 30 min 自相关峰高达 0.9995）。</p>
     */
    public static float windShapeAt(final long gameTime, final float partialTick) {
        final double seconds = ((double) gameTime + (double) partialTick) / (double) TICKS_PER_SECOND;
        final double wave = SHAPE_WEIGHT_A * Math.sin(TWO_PI * seconds / shapePeriodA + shapePhase)
                + (1D - SHAPE_WEIGHT_A) * Math.sin(TWO_PI * seconds / shapePeriodB + shapePhase * 1.7D);
        final double clipped = Math.tanh(wave * SHAPE_SOFT_CLIP) / Math.tanh(SHAPE_SOFT_CLIP);
        final double periodic = (clipped + 1D) * 0.5D;
        return (float) Mth.clamp(periodic + WANDER_WEIGHT * (wander(seconds) - 0.5D), 0D, 1D);
    }

    /**
     * 旧名字，保留给已有的调用方（诊断与树冠/呼啸的包络）。语义与
     * {@link #windShapeAt} 完全相同：0..1 的阵风形状。
     */
    public static float envelopeAt(final long gameTime, final float partialTick) {
        return windShapeAt(gameTime, partialTick);
    }

    /**
     * 非周期分量，0..1：三个八度的确定性值噪声。
     *
     * <p>没有状态 —— 格点上的值由 {@code (格点, 八度, 本场种子)} 哈希出来，所以
     * 同一个 tick 在任何线程、任何版本、任何会话里都是同一个数，也不需要 tick 钩子
     * 去推进它。{@code PrecipitationRenderer.columnHash} 是同一个做法的先例。</p>
     */
    private static double wander(final double seconds) {
        double sum = 0D;
        for (int i = 0; i < WANDER_PERIODS_S.length; i++) {
            final double x = seconds / WANDER_PERIODS_S[i] + wanderSeed * (i + 1);
            final double lattice = Math.floor(x);
            final double fraction = x - lattice;
            final double smooth = fraction * fraction * (3D - 2D * fraction);
            final double a = value(lattice, i);
            final double b = value(lattice + 1D, i);
            sum += a + (b - a) * smooth;
        }
        return sum / WANDER_PERIODS_S.length;
    }

    /** 格点上的伪随机值，0..1。纯哈希，见 {@link #wander}。 */
    private static double value(final double lattice, final int octave) {
        long h = Double.doubleToLongBits(lattice) * 0x9E3779B97F4A7C15L
                + (long) octave * 0xC2B2AE3D27D4EB4FL
                + (long) (wanderSeed * 1_000_000D);
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return (double) (h >>> 11) * 0x1.0p-53;
    }

    /** 风等级 -> 风速，格/秒。渲染与音频共用的那一条映射。 */
    public static float speedOf(final float windLevel) {
        return WIND_LEVEL_MIN_BPS + Mth.clamp(windLevel, 0F, 1F) * WIND_LEVEL_SPAN_BPS;
    }

    /**
     * 某个风等级对应的格/tick。<b>纯函数</b>：不读也不写下面那组缓存。
     *
     * <p>给"用自己的量当风等级、又不想覆盖别人的缓存"的调用方用 —— 沙尘幕就是它
     * （见 {@code WeatherStormHandler.renderVeil}）。</p>
     */
    public static float speedPerTickOf(final float windLevel) {
        return speedOf(windLevel) / TICKS_PER_SECOND;
    }

    /** 风速 -> 风等级。{@code BiomeSoundHandler} 的折回就是这一条。 */
    public static float levelOf(final float bps) {
        return Mth.clamp((bps - WIND_LEVEL_MIN_BPS) / WIND_LEVEL_SPAN_BPS, 0F, 1F);
    }

    /**
     * 每次绘制前调一次，把当前时刻的风缓存下来。
     *
     * <p><b>唯一写入者。</b>下面那一组 static（angle/dirX/dirZ/speedBPS/speedBPT）原来有
     * 两个写入者：雨渲染器写 {@code windLevel()}，沙尘幕写 {@code veilNow}（幕自己的
     * 强度），两者写同一个槽，谁后写谁赢 —— 正确性完全依赖调用顺序，任何"不先 update
     * 就读"的第三方会拿到上一次是谁写的残留值。沙尘幕现在走纯函数
     * （{@link #angleAt} + {@link #speedPerTickOf}，方向与速度放在它自己的局部变量里），
     * 所以这里只剩降水渲染一个调用点。<b>不要再往这里灌第二个物理量。</b></p>
     *
     * @param gameTime    世界游戏时间（level.getGameTime()）
     * @param partialTick 帧内插值，让转向连续
     * @param windLevel   当前风等级，0..1（{@code PrecipitationIntensity.windLevel()}）
     */
    public static void updateFromWind(final long gameTime, final float partialTick, final float windLevel) {
        final float a = angleAt(gameTime, partialTick);
        final float bps = speedOf(windLevel);
        angle = a;
        dirX = Mth.cos(a);
        dirZ = Mth.sin(a);
        speedBPS = bps;
        speedBPT = bps / TICKS_PER_SECOND;
    }

    /**
     * 某个游戏时刻的风向，弧度。0 = +X，朝 +Z 旋转。纯函数。
     */
    public static float angleAt(final long gameTime, final float partialTick) {
        final double t = (double) gameTime + (double) partialTick;
        return (float) (TWO_PI * (t / (double) WIND_VEER_CYCLE_TICKS)
                + WIND_VEER_WOBBLE_RAD
                * Math.sin(TWO_PI * (t / (double) WIND_VEER_WOBBLE_PERIOD_TICKS)));
    }

    /** 缓存的风向，弧度。 */
    public static float angleRad() {
        return angle;
    }

    /** 缓存的风矢量 X 分量（单位向量）。 */
    public static float dirX() {
        return dirX;
    }

    /** 缓存的风矢量 Z 分量（单位向量）。 */
    public static float dirZ() {
        return dirZ;
    }

    /** 缓存的风速，格/秒。 */
    public static float speed() {
        return speedBPS;
    }

    /** 缓存的风速，格/tick —— UV 计算用的单位。 */
    public static float speedPerTick() {
        return speedBPT;
    }

    /**
     * 风在<em>幕的横轴</em>上的分量，格/tick。
     *
     * <p>传入的 (ax, az) 必须是<strong>几何摆顶点用的那一条轴</strong>，也就是
     * (stripX, stripY) 归一化后的方向。顶点 u0 在 {@code 中心 - strip}、u1 在
     * {@code 中心 + strip}，所以 u 增加的方向就是这条轴，Δu 加在它上面才对得上。</p>
     *
     * <p>不要用"从相机位置推出来的切向" {@code (-cz, cx)/r} 来代替它：幕的几何
     * 朝向是按玩家整数方块查表得到的（1.12.2 的 RAIN_X_COORDS），跟相对相机的
     * 连续切向不是同一个方向。远处的幕两者几乎重合所以看不出来；近处的幕能差
     * 到 90 度，投影的符号会翻 —— 表现就是"贴脸的雨朝反方向斜"，以及抬头看
     * 四周时各幕倾角互相打架、像旋风。脚下那一幕更极端：查表给的是固定的 +X
     * fallback，连续切向却是任意方向，完全不相关。</p>
     *
     * <p>剩余限制：幕是平面，径向分量表达不出来，风正对或正背相机吹时那一幕
     * 的倾角会消失。这是围栏模型的固有代价，不是 bug。</p>
     */
    public static float tangentialAlong(final float ax, final float az) {
        return (dirX * ax + dirZ * az) * speedBPT;
    }
}
