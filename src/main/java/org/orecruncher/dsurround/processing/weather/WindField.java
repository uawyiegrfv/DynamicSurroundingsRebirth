package org.orecruncher.dsurround.processing.weather;

import net.minecraft.util.Mth;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.biome.biometraits.BiomeTraits;

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
 *        + WIND_GUST_SPEED_BPS * gustAmplitude * strength) * biomeScale
 * </pre>
 *
 * <p>{@code biomeScale} 是群系风力系数，见 {@link #biomeScale}。strength 和
 * gustAmplitude 都不带群系信息（gustiness 是每场事件摇出来的 0.5~1.5），
 * 没有这一项的话全世界任何群系的风都一样大。</p>
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

    /** 没有降水时的风速，格/秒。不设成 0：完全静风会让人以为功能没开，
     *  而且它也是沙尘幕在风暴起来之前的漂移底线。 */
    private static final float WIND_SPEED_MIN_BPS = 0.8F;

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
        final float bps = speedAt(strength, gustAmplitude, biomeScale);
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
     */
    public static float speedAt(float strength, float gustAmplitude, float biomeScale) {
        final float s = Mth.clamp(strength, 0F, 1F);
        final float g = Mth.clamp(gustAmplitude, 0F, 1F);
        final float b = biomeScale > 0F ? biomeScale : 1F;
        return (WIND_SPEED_MIN_BPS + WIND_SPEED_RANGE_BPS * s + WIND_GUST_SPEED_BPS * g * s) * b;
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
