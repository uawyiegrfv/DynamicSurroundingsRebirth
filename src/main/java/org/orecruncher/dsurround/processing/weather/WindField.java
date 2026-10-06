package org.orecruncher.dsurround.processing.weather;

import net.minecraft.util.Mth;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.biome.biometraits.BiomeTraits;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

/**
 * WIND：全模组唯一的水平风来源 —— 风向 + 风速。
 *
 * <p>雨幕的倾斜、雪的横飘、沙尘幕的漂移，以及以后要接进来的风声层，
 * 全部问这个类，而不是各自掷随机数：同一时刻只能有一阵风在吹。</p>
 *
 * <h2>为什么是"游戏时钟的纯函数"而不是一个带状态的场</h2>
 *
 * <p>本模组没有一个"晴天也会跑"的 tick 钩子。降水渲染挂在
 * {@code AFTER_WEATHER} 阶段里，没下雨时 {@code active()} 直接返回，
 * 压根走不到绘制；沙尘幕同理（tint 太低就 return）。一个靠 tick 推进的
 * 状态场会在晴天冻住，然后在暴风雨开始的第一帧突然跳到新方向 ——
 * 正好是最不能接受的那种突变。所以风向和风速都直接由游戏时钟算出来：
 * {@link #update} 在每次绘制前调一次把结果缓存下来，
 * {@link #angleAt} / {@link #speedAt} 是同一套数学的无缓存版本，
 * 给没有绘制通道的调用方（脚本变量集）用。</p>
 *
 * <h2>风向</h2>
 *
 * <pre>
 *   angle(t) = 2*PI * t / WIND_VEER_CYCLE_TICKS
 *            + WIND_VEER_WOBBLE_RAD * sin(2*PI * t / WIND_VEER_WOBBLE_PERIOD_TICKS)
 * </pre>
 *
 * <p>一个慢速整圈扫掠叠一个更快的摆动，两者都是时间的连续函数，所以风向
 * 永远不会跳变：扫掠让同一场雨从头到尾一直在缓慢转向，摆动让它不至于像
 * 被一根绳子匀速拖着走。不同场次风向不同，是因为它们发生在不同的时刻。</p>
 *
 * <p>约定：0 弧度指向 +X，朝 +Z 旋转，风矢量 = {@code (cos a, sin a)}
 * 落在 {@code (x, z)} 上。这个约定本身是任意的，重要的是所有消费者一致。</p>
 *
 * <h2>风速</h2>
 *
 * <pre>
 *   bps = (WIND_SPEED_MIN_BPS
 *        + WIND_SPEED_RANGE_BPS * strength
 *        + WIND_GUST_SPEED_BPS * gustAmplitude * strength) * biomeScale * seasonScale
 * </pre>
 *
 * <p>{@code biomeScale} 是群系风力系数，{@code seasonScale} 是季节风力系数
 * （见 {@link #seasonScale()}）。strength 和 gustAmplitude 都不带群系信息、
 * 也不带季节信息（gustiness 是每场事件摇出来的 0.5~1.5），没有这两项的话全
 * 世界任何地方一年四季的风都一样大。</p>
 *
 * <p>{@code strength} 是分级降水强度，离散阵风（squall）的大小已经折进
 * 去了 —— 一阵狂风在这里的表现就是风变急，而本类不需要知道 squall 存在。
 * {@code gustAmplitude} 是本场事件摇出的阵风幅度（剖面响应曲线 ×
 * gustiness），就是雨风声层拿来给树叶阵风打拍子的同一个量，所以听得见的
 * 风和看得见的风对"这场雨有多不安定"的判断是一致的。它再乘一次强度，
 * 免得一场还没起来的暴风雨就已经是十二级风。</p>
 */
public final class WindField {

    // ================= 可调参数：实测之后只动这一段 =================

    /**
     * 没有降水时的风速，格/秒。
     *
     * <p>原来是 0.8，注释写的是"完全静风会让人以为功能没开"。但常数地板正是
     * "雨从来没有竖直过"的直接原因：强度 0 也吹 0.8 格/秒，配上下落速度 0.25
     * 格/tick 和增益 0.5，光这一项就给出 4.6 度的倾角。现在真正的静歇由
     * {@link #WIND_ENV_FLOOR} 兜底（包络最低点，而不是恒定值），所以这里可以
     * 降到 0.15 —— 该有风的时候照样有风，只是不再是"永远有风"。</p>
     */
    private static final float WIND_SPEED_MIN_BPS = 0.15F;

    /** 强度拉满时额外增加的风速，格/秒。 */
    private static final float WIND_SPEED_RANGE_BPS = 4.2F;

    /** 阵风幅度拉满、且强度拉满时额外增加的风速，格/秒。 */
    private static final float WIND_GUST_SPEED_BPS = 3.0F;

    /** 风向扫掠一整圈所需的 tick 数。越小转得越快。
     *  18000 tick = 15 分钟一圈 = 24 度/分钟。 */
    private static final float WIND_VEER_CYCLE_TICKS = 18000F;

    /** 快摆动的幅度，弧度。0.35 约 20 度。 */
    private static final float WIND_VEER_WOBBLE_RAD = 0.35F;

    /** 快摆动的周期，tick。2400 tick = 2 分钟。 */
    private static final float WIND_VEER_WOBBLE_PERIOD_TICKS = 2400F;

    // ---- 阵风包络（让"无风"的表现真的存在） ----

    /**
     * 风速包络的最低点。风不会真的静到 0 —— 那样会让人以为功能没开 ——
     * 但静歇期要静到"雨看着是竖的"：0.08 配强度 0.5（基准 3.75 格/秒）
     * 给出 0.30 格/秒，配增益 0.5 与下落 0.406 格/tick ⇒ 倾角约 1.1 度，
     * 肉眼就是竖直。再往上抬到 0.15 就只剩 2 度，仍然看得出来在斜。
     */
    private static final float WIND_ENV_FLOOR = 0.08F;

    /**
     * 包络里慢周期占多少，剩下的是快周期。0.85 = 慢的为主、快的只掺一点。
     *
     * <p>纯慢正弦的静歇占比更高（27% 对 19%），但一条正弦是严格 90 秒一次
     * 的节拍器 —— "太规律"是踩过的坑。掺 15% 快周期把节拍打散，代价是静歇
     * 少一点，值。</p>
     */
    /**
     * 包络的 gamma。&lt;1 会把整条曲线往上顶（抬高均值），但 u-&gt;0 时仍收敛到
     * {@link #WIND_ENV_FLOOR}，所以深静歇照旧 —— 这是"均值要大、静歇还要在"
     * 唯一能两全的旋钮。
     *
     * <p>离线扫过 3 个下限 x 6 个 gamma（30 分钟逐 tick），取 0.70：
     * 强度 0.5 的平均倾角 7.1 度 -&gt; 8.1 度（改动前恒定 13.0），
     * 而静歇（&lt;3 度）占比 19% -&gt; 8%（强度 0.25 仍有 20%）。
     * 再往下压到 0.55 均值能到 8.8，但强度 0.5 的静歇只剩 4%，不值。</p>
     */
    private static final double WIND_ENV_GAMMA = 0.70D;

    private static final double WIND_ENV_SLOW_WEIGHT = 0.85D;

    /**
     * 慢包络的周期，tick。1800 tick = 90 秒 —— 一次"起风 / 静下来"的呼吸。
     * 调大 = 静歇更久更少；调小 = 来回得更勤。
     */
    private static final float WIND_ENV_PERIOD_SLOW_TICKS = 1800F;

    /**
     * 快包络的周期，tick。460 tick = 23 秒 —— 叠在慢包络上的阵头。
     * 两层相乘，所以真正的满风要两层同时到峰（不常见），
     * 正是"阵风是稀有的、静歇是常态"这个分布。
     */
    private static final float WIND_ENV_PERIOD_FAST_TICKS = 460F;

    /** 快包络的相位，避开与慢包络同相（否则两层永远同时到峰，就没有静歇了）。 */
    private static final double WIND_ENV_FAST_PHASE = 1.7D;

    /** 倾斜方向的符号。1F = 雨丝朝风去的那一侧倒。
     *  实机如果发现倒向相反，把这里改成 -1F —— 一处，三版同改。 */
    public static final float SLANT_SIGN = 1F;

    // ================= 群系风力系数（让不同地方的风不一样大） =================
    //
    // 风速的另外两个输入（strength、gustAmplitude）都不带群系信息，所以原来
    // 全世界任何群系的风都一样大。这里用 BiomeTraits 的标签推一个倍率补上那一维。
    // 刻意不新增配置文件字段：标签已经够用，省掉改 biomes.json 和三版配置同步。
    //
    // 调风力就改下面这两张表（一一对应），别的地方都不用动。
    // WindField.java 三版逐字相同，改这里等于三版同时改。

    /** 会被放大/缩小的群系标签，与 {@link #WIND_BIOME_SCALE_VALUES} 一一对应。
     *  表里没有的标签一律按 1.0 处理，不用重复列进来。 */
    private static final BiomeTrait[] WIND_BIOME_SCALE_TRAITS = {
            BiomeTrait.WINDSWEPT,   // 风袭/风蚀 —— 名字里就写着风大
            BiomeTrait.MOUNTAIN,    // 山地。BiomeTrait 没有 PEAK，山峰也归这条
            BiomeTrait.HILLS,       // 丘陵
            BiomeTrait.PLAINS,      // 平原：开阔，没有遮挡
            BiomeTrait.SPARSE,      // 植被稀疏
            BiomeTrait.FOREST,      // 森林：树挡风
            BiomeTrait.DENSE,       // 密林/郁闭：基本吹不进来
    };

    /** 上面每个标签对应的风力倍率。 */
    private static final float[] WIND_BIOME_SCALE_VALUES = {
            1.35F,
            1.25F,
            1.15F,
            1.10F,
            1.10F,
            0.85F,
            0.75F,
    };

    // ================= 季节风力系数（让不同季节的风不一样大） =================
    //
    // 与上面的群系表并列的第二张倍率表，道理与群系那一维完全相同：strength
    // 和 gustAmplitude 都不带季节信息，不补这一维的话一年四季风都一样大。
    //
    // 取值理由（真实气象里冬天的平均风速明显高于夏天，因为驱动天气系统的南北
    // 温差冬季最大）：
    //   冬季 —— 冷空气活动最频繁，取最强；
    //   秋季 —— 锋面过渡季，阵性大风多，次强；
    //   春季 —— 冷暖交替，取常年平均，作基准 1.0；
    //   夏季 —— 以热力对流为主，平均风速最低；
    //   热带湿季 —— 季风/辐合带带来的持续性大风，略高于 1.0；
    //   热带干季 —— 只有平稳的哈德利信风，极少阵性大风，低于 1.0；
    //   无季节信息（VANILLA 或没有季节模组）—— 1.0，不修正。
    //
    // 调这张表就改下面这几个常量，别的地方都不用动。WindField.java 三版逐字
    // 相同，改这里等于三版同时改。

    /** 冬季：冷空气活动频繁，风速常年偏高的季节。 */
    private static final float WIND_SEASON_SCALE_WINTER = 1.20F;
    /** 秋季：锋面过渡季，阵性大风多。 */
    private static final float WIND_SEASON_SCALE_AUTUMN = 1.15F;
    /** 春季：冷暖交替，基准。 */
    private static final float WIND_SEASON_SCALE_SPRING = 1.00F;
    /** 夏季：热力对流为主，平均风速最低。 */
    private static final float WIND_SEASON_SCALE_SUMMER = 0.90F;
    /** 热带湿季：季风带来持续性大风。 */
    private static final float WIND_SEASON_SCALE_TROPICAL_WET = 1.10F;
    /** 热带干季：平稳信风，少阵风。 */
    private static final float WIND_SEASON_SCALE_TROPICAL_DRY = 0.95F;

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
     * 每次绘制前调一次，把当前时刻的风缓存下来。
     *
     * @param gameTime      世界游戏时间（level.getGameTime()）
     * @param partialTick   帧内插值，让转向连续
     * @param strength      当前强度，0..1（降水强度 / 沙尘幕的 veilNow）
     * @param gustAmplitude 本场事件的阵风幅度，0..1；沙尘幕传 0
     * @param biomeScale    群系风力系数，见 {@link #biomeScale}；不区分群系时传 1F
     */
    public static void update(long gameTime, float partialTick, float strength, float gustAmplitude,
                              float biomeScale) {
        final float a = angleAt(gameTime, partialTick);
        // 渲染路径乘阵风包络：同一场雨会自己一忽儿竖直一忽儿斜。
        final float bps = speedAt(gameTime, partialTick, strength, gustAmplitude, biomeScale);
        angle = a;
        dirX = Mth.cos(a);
        dirZ = Mth.sin(a);
        speedBPS = bps;
        speedBPT = bps / TICKS_PER_SECOND;
    }

    /**
     * 某个游戏时刻的风向，弧度。0 = +X，朝 +Z 旋转。纯函数。
     */
    public static float angleAt(long gameTime, float partialTick) {
        final double t = (double) gameTime + (double) partialTick;
        return (float) (TWO_PI * (t / (double) WIND_VEER_CYCLE_TICKS)
                + WIND_VEER_WOBBLE_RAD
                * Math.sin(TWO_PI * (t / (double) WIND_VEER_WOBBLE_PERIOD_TICKS)));
    }

    /**
     * 风速，格/秒。纯函数。
     *
     * <p>季节倍率在这里乘进去 —— 和群系倍率一样走同一条路，所以任何问本类的
     * 调用方（渲染、沙尘、脚本变量集、风声层）拿到的都是同一阵风，不需要各自
     * 补一次季节修正。</p>
     */
    /**
     * 风速，格/秒。纯函数。<strong>不带阵风包络</strong> —— 给没有绘制通道的
     * 调用方（风声层、脚本变量集）用。
     *
     * <p>为什么不给他们也乘包络：风声层把这风速折成风床音量，而"静歇期风床
     * 掉没 ⇒ 听感像风声没了"是踩过的坑（见 {@code BiomeSoundHandler} 里
     * {@code RAIN_WIND_MIN_INTENSITY} 的注释）。包络让瞬时风速在静歇期掉到
     * 15%，照搬到音频上就是每隔一两分钟风声消失一次。风床代表的是这场风暴
     * 的<em>潜力</em>而不是这一瞬间的阵风，所以这里读不带包络的基准值。</p>
     */
    public static float speedAt(float strength, float gustAmplitude, float biomeScale) {
        final float s = Mth.clamp(strength, 0F, 1F);
        final float g = Mth.clamp(gustAmplitude, 0F, 1F);
        final float b = biomeScale > 0F ? biomeScale : 1F;
        return (WIND_SPEED_MIN_BPS + WIND_SPEED_RANGE_BPS * s + WIND_GUST_SPEED_BPS * g * s) * b * seasonScale();
    }

    /**
     * 渲染用的风速，格/秒 —— 上面那条再乘一个阵风包络。
     *
     * <p>只有 {@link #update} 调它，而 {@code update} 写的缓存只被
     * {@link #tangentialAlong}（雨/雪/沙尘幕的倾斜与拖影）读到，所以包络
     * 只影响看得见的东西。</p>
     */
    public static float speedAt(long gameTime, float partialTick, float strength,
                                float gustAmplitude, float biomeScale) {
        return speedAt(strength, gustAmplitude, biomeScale) * envelopeAt(gameTime, partialTick);
    }

    /**
     * 阵风包络，0.15 .. 1.0，游戏时钟的纯函数 —— 与 {@link #angleAt} 同一条
     * 纪律：本模组没有晴天也跑的 tick 钩子，一个靠 tick 推进的状态场会在晴天
     * 冻住，然后在暴风雨第一帧突然跳到新值。纯函数没有这个问题。
     *
     * <p>两层正弦相乘：慢的那层决定"这一阵风起来没有"，快的那层在它上面叠
     * 阵头。相乘而不是相加，因为相乘才有真正的静歇（两层都低的时候接近 0），
     * 而相加永远掉不到底 —— 那和原来的常数地板没区别。</p>
     */
    public static float envelopeAt(long gameTime, float partialTick) {
        final double t = (double) gameTime + (double) partialTick;
        final double slow = 0.5 + 0.5 * Math.sin(TWO_PI * (t / (double) WIND_ENV_PERIOD_SLOW_TICKS));
        final double fast = 0.5 + 0.5 * Math.sin(TWO_PI * (t / (double) WIND_ENV_PERIOD_FAST_TICKS)
                + WIND_ENV_FAST_PHASE);
        // 加权和，不是积。slow×fast 只在两层同时到峰时才接近 1，深静歇因此
        // 极其罕见 —— 实测"倾角<3°"只占 3% 的时间，等于没有静歇。改成加权和
        // 之后升到约 19%。
        //
        // 两条都是 0..1 的正弦，和也在 0..1，均值 0.5 ⇒ 平均风速约降到改动前的
        // 六成（倾角均值 13° → 7.1°）。这是刻意的：他要的是"雨不全是斜的"，
        // 平均降下来正是那个方向，而峰值仍然够到满值。
        final double u = WIND_ENV_SLOW_WEIGHT * slow + (1.0 - WIND_ENV_SLOW_WEIGHT) * fast;
        return (float) (WIND_ENV_FLOOR + (1.0 - WIND_ENV_FLOOR) * Math.pow(u, WIND_ENV_GAMMA));
    }

    /**
     * 一个群系的风力倍率，1.0 = 不修正。
     *
     * <p>多个标签同时命中时返回<strong>偏离 1.0 最远</strong>的那一条，不累乘 ——
     * 山地里 WINDSWEPT 和 MOUNTAIN 常见同时成立，累乘出 1.69 这种值会让风失真。
     * 表外的标签直接返回 1.0。</p>
     *
     * <p>调用点每帧/每 tick 只查一次（见 {@code PrecipitationIntensity.update}）：
     * 一次查询是 7 次 HashSet 查找，放进 per-column 循环里会乘上几百列。</p>
     */
    public static float biomeScale(final BiomeTraits traits) {
        if (traits == null)
            return 1F;
        float result = 1F;
        float bestDeviation = 0F;
        for (int i = 0; i < WIND_BIOME_SCALE_TRAITS.length; i++) {
            if (!traits.contains(WIND_BIOME_SCALE_TRAITS[i]))
                continue;
            final float deviation = Math.abs(WIND_BIOME_SCALE_VALUES[i] - 1F);
            if (deviation > bestDeviation) {
                bestDeviation = deviation;
                result = WIND_BIOME_SCALE_VALUES[i];
            }
        }
        return result;
    }

    /**
     * 当前 {@code PrecipitationIntensity} 正在用的那一列的季节风力倍率。
     * 1.0 = 不修正（没有季节模组，或 VANILLA 列）。
     */
    public static float seasonScale() {
        return seasonScale(PrecipitationIntensity.season());
    }

    /**
     * 某个季节的风力倍率，1.0 = 不修正。
     *
     * <p>{@code null}（季节系统还没给出答案）和 VANILLA 都返回 1.0。</p>
     */
    public static float seasonScale(final PrecipitationSeason season) {
        if (season == null)
            return 1F;
        return switch (season) {
            case WINTER -> WIND_SEASON_SCALE_WINTER;
            case AUTUMN -> WIND_SEASON_SCALE_AUTUMN;
            case SPRING -> WIND_SEASON_SCALE_SPRING;
            case SUMMER -> WIND_SEASON_SCALE_SUMMER;
            case TROPICAL_WET -> WIND_SEASON_SCALE_TROPICAL_WET;
            case TROPICAL_DRY -> WIND_SEASON_SCALE_TROPICAL_DRY;
            case VANILLA -> 1F;
        };
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
    public static float tangentialAlong(float ax, float az) {
        return (dirX * ax + dirZ * az) * speedBPT;
    }
}
