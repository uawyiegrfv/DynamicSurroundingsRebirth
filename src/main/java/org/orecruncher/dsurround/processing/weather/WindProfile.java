package org.orecruncher.dsurround.processing.weather;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.biome.biometraits.BiomeTraits;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.resources.ResourceUtilities;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * <p><b>风的性格预设。</b>群系匹配到一个预设，预设给出这场风的<b>形状</b>，而不是一个
 * 大小数字。这是照 {@link PrecipitationProfile} 的架构做的第二套预设，理由与它完全相同：
 * 群系数以千计，逐群系写一张表既没人审得动也没法保证单调；一小撮命名预设加上标签解析，
 * 连没有 DSR 条目的模组群系也能落在一个合理的位置上。</p>
 *
 * <h2>为什么必须有这一层（2026-10-11 审查 §4.3 的结论）</h2>
 *
 * <p>在此之前，"群系 x 季节"的全部差别是一个标量倍率（38 个取值、7.60 dB），而标量
 * <b>同时缩放均值与方差，比值不变</b>。维护者三个性格用例里最要命的那两条正是比值：
 * 恶地要 {@code crest <= 6 dB}（稳），树林要 {@code crest >= 8 dB}（不稳）。18522 组
 * 网格搜索的可行解 = 0 —— 不是参数没调好，是<b>这一层自由度根本不存在</b>。</p>
 *
 * <h2>八个自由度</h2>
 *
 * <table>
 *   <tr><td>{@code meanLevel[6]}</td><td>平时多大（按季节索引）</td></tr>
 *   <tr><td>{@code levelJitter}</td><td>跨场次抖动，每场事件摇一次，N(0,sigma) 截断 ±2 sigma</td></tr>
 *   <tr><td>{@code gustDepth}</td><td>事件内阵风深度，<b>加性</b>，与均值解耦</td></tr>
 *   <tr><td>{@code gustRatePerMin}</td><td>阵风频率（每分钟几个阵风峰）</td></tr>
 *   <tr><td>{@code floorLevel}</td><td>地板：最静到哪</td></tr>
 *   <tr><td>{@code peakCeiling}</td><td>天花板：最响到哪</td></tr>
 *   <tr><td>{@code canopyResponse}</td><td>叶声响应（0 = 无树，1 = 全是树）</td></tr>
 *   <tr><td>{@code gustResponse}</td><td>阵风深度随强度的曲线（复用降水那四条）。<b>B5 起运行期没有读者</b>——见字段说明</td></tr>
 * </table>
 *
 * <p>数值来自风场审查 §5.2 / {@code _wind_audit/wind_design_verify.py} 的搜参结果，
 * 六个季节列全部通过各自的判据。<b>{@code meanLevel} 写的是基准值，运行期还要乘
 * {@link #SEASON_FACTOR}</b>（跨度 1.10/0.92 = 1.20x；原来的季节倍率跨度是 1.33x，
 * 对"草原永远微风"太大了 —— 冬季会让它失败）。</p>
 *
 * <p>参数落在 {@code dsconfigs/wind.json}，可以由数据包/模组覆盖，见 {@link #reload}。
 * 编译进 class 的这一份是默认值，也是 {@code wind.json} 缺失时的全部答案。</p>
 */
public enum WindProfile {

    // 季节列的顺序 = PrecipitationSeason.ordinal()：
    // SPRING, SUMMER, AUTUMN, WINTER, TROPICAL_DRY, TROPICAL_WET

    /**
     * 恶地 / 风袭 / 荒原 / 沙地 —— <b>用例①「始终风大且稳定」</b>。
     *
     * <p>mean 高（0.55）、depth 极浅（0.04）、rate 极低（0.2/min）、地板 0.35：
     * 它没有"静下来"这个状态，只有"大"和"更大一点"。</p>
     */
    EXPOSED_BARREN(level(0.55F), 0.01F, 0.04F, 0.2F, 0.35F, 0.60F, 0.00F, GustResponse.FLAT, 0.25F, 0.60F),

    /**
     * 草原 / 平原 / 沙丘 / 热带草原 —— <b>用例②「平时基本微风」</b>。
     *
     * <p>mean 落在微风带中心（0.08），天花板 0.25 保证永远起不来：判据要的是
     * {@code level ∈ [0,0.25]} 的时间占比 >= 90%，而 cap 是这条判据的结构性保证，
     * 不是调出来的。</p>
     */
    OPEN_LOWLAND(level(0.08F), 0.02F, 0.08F, 0.2F, 0.03F, 0.25F, 0.00F, GustResponse.FLAT, 0.25F, 0.60F),

    /**
     * 阔叶林 / 混合林 —— <b>用例③「微风为主 + 偶发大风 + 叶声」</b>。
     *
     * <p>mean 与草原只差 0.01，性格差别<b>全部来自 depth</b>（0.36 对 0.08，4.5 倍）——
     * 这正是"加性解耦"买到的东西。cap 0.45 是叶层门控（0.35）的上方，所以"偶尔刮大"
     * 与"树叶响"是同一件事。</p>
     */
    WOODLAND(level(0.09F), 0.01F, 0.36F, 0.4F, 0.05F, 0.45F, 1.00F, GustResponse.FLAT, 0.25F, 0.60F),

    /**
     * 密林 / 丛林 / 沼泽 / 蘑菇岛：风被树挡住，均值更低、阵风更频繁但更浅。
     * 叶声仍然是满的 —— 这里叶子最多，风最少。
     */
    DENSE_CANOPY(level(0.06F), 0.01F, 0.18F, 0.5F, 0.03F, 0.30F, 1.00F, GustResponse.FLAT, 0.12F, 0.60F),

    /**
     * 海岸 / 海洋 / 河流 / 沙滩：长周期、稳定的风。rate 0.1/min（十分钟一个阵风峰）
     * 就是"涌浪式"的表达；meanLevel 按季节给，秋冬略高。
     *
     * <p>{@code howlWindMin} 取 0.40 而不是默认的 0.60：它的风等级上限只有 0.55，
     * 按 0.60 判这一层<b>永远不响</b>（离线复算 0.0%），而海岸常年有风正是这个预设
     * 存在的理由。0.40 让这一层四季都在 2.7%~40.6% 之间（四季均 18.6%，秋冬更高，
     * 与它自己的 meanLevel 季节列同向），见 {@code _wind_unified/p4_howl.py}。</p>
     */
    COASTAL_EXPOSED(new float[] { 0.30F, 0.30F, 0.32F, 0.34F, 0.30F, 0.32F },
            0.03F, 0.10F, 0.1F, 0.22F, 0.55F, 0.10F, GustResponse.FLAT, 0.25F, 0.40F),

    /**
     * 高海拔 / 雪线 / 高原 / 冰原：强、冷、低频重。半树（canopy 0.35）。
     *
     * <p>{@code howlWindMin} 取 0.50 而不是默认的 0.60：按 0.60 判，它的风等级
     * （mean 0.35 / depth 0.22 / cap 0.70）只在冬春偶尔越线，离线复算四季均值
     * 2.6% —— 山顶反而是最安静的。0.50 把它拉回 25.7%（四季 18.6%~32.4%，见
     * {@code _wind_unified/p4_howl.py}）。</p>
     */
    ALPINE_PEAK(level(0.35F), 0.04F, 0.22F, 0.6F, 0.18F, 0.70F, 0.35F, GustResponse.FLAT, 0.25F, 0.50F),

    /**
     * 下界 / 末地 / 虚空 / 地下：<b>没有风这个维度</b>。全部为 0 ⇒ 床与层都不存在。
     * 名字沿用降水那边的 {@code PrecipitationProfile.WASTELAND}。
     */
    WASTELAND(level(0.00F), 0.00F, 0.00F, 0.0F, 0.00F, 0.00F, 0.00F, GustResponse.FLAT, 0.25F, 0.60F);

    /** 六个季节列一份的便捷写法（列的顺序见类头）。 */
    private static float[] level(final float value) {
        return new float[] { value, value, value, value, value, value };
    }

    /**
     * 季节对均值的乘法修正，索引同 {@link PrecipitationSeason#ordinal()}。
     *
     * <p>与降水那套的 {@code OVERCAST_SEASON_FACTOR} 同一个做法。跨度刻意收窄到
     * ±10%（现状 1.20/0.90 = 1.33x）：冬季 1.10 是"冷空气活动最频繁"，
     * 热带干季 0.92 是"平稳信风"，而"草原永远微风"这条判据要求它在秋冬仍然
     * 落在 0.25 的天花板下面 —— 1.33x 的跨度会直接让它 FAIL。</p>
     */
    private static final float[] DEFAULT_SEASON_FACTOR = { 1.00F, 0.95F, 1.05F, 1.10F, 0.92F, 1.04F };
    private static float[] SEASON_FACTOR = DEFAULT_SEASON_FACTOR.clone();

    /** 数据文件：{@code assets/<namespace>/dsconfigs/wind.json}，可由数据包覆盖。 */
    private static final String FILE_NAME = "wind.json";

    /** 编译期默认值的一份快照。{@link #reload} 先从这里复位，再覆盖。 */
    private record Defaults(float[] meanLevel, float levelJitter, float gustDepth, float gustRatePerMin,
                            float floorLevel, float peakCeiling, float canopyResponse, GustResponse gustResponse,
                            float leafWindMin, float howlWindMin) {
    }

    /** 叶层门限的默认值，见 {@code leafWindMin}。 */
    private static final float DEFAULT_LEAF_WIND_MIN = 0.25F;

    /** 呼啸门限的默认值，见 {@code howlWindMin}。 */
    private static final float DEFAULT_HOWL_WIND_MIN = 0.60F;

    private final Defaults defaults;

    private float[] meanLevel;
    private float levelJitter;
    private float gustDepth;
    private float gustRatePerMin;
    private float floorLevel;
    private float peakCeiling;
    private float canopyResponse;
    /**
     * 阵风深度随"强度"的曲线。<b>B5 之后运行期没有任何读者</b>：七个预设全是
     * {@code GustResponse.FLAT}，而 {@code WindField.levelAt} 已经把那个强度形参删掉了
     * —— 风有意独立于降水，那条通道本来就恒等于 {@code gustDepth}。
     *
     * <p>字段与 {@code wind.json} 的 {@code gustResponse} 键保留，是因为删掉它要动
     * 数据包格式与 {@code describe()} 那一行（三版一致性脚本读的就是那一行），而它现在
     * 是惰性的、有文档的。真要重新接上"暴雨风更大"，这里就是现成的接口。</p>
     */
    private GustResponse gustResponse;
    /**
     * 叶声层的风等级门限，<b>按预设可配</b>（默认 {@link #DEFAULT_LEAF_WIND_MIN}）。
     *
     * <p>为什么需要它：门限是一条线，而"树叶在多大的风上开始响"是性格的一部分。
     * 密林的风等级（mean 0.06 / depth 0.18 / cap 0.30）按 0.25 判就<b>永远不响</b>
     * —— 实测 30 min 里门控开 0.0%，与 §3.10.3 给它 {@code canopyResponse = 1.00}
     * 的意图正好相反。所以门限跟着预设走，密林取更低的值，而 mean/depth/cap 那三个
     * 数是三用例标定过的，一个都不动。</p>
     */
    private float leafWindMin = DEFAULT_LEAF_WIND_MIN;
    /**
     * 呼啸层的风等级门限，<b>按预设可配</b>（默认 {@link #DEFAULT_HOWL_WIND_MIN}）。
     *
     * <p>与 {@link #leafWindMin} 同一个模式、同一个理由：门限是一条线，而"多大的风
     * 才值得呼啸"是性格的一部分。批次 B 把这条线从 0.35 一刀切提到 0.60，恶地晴天
     * 因此从 100% 落到 20%（想要的），代价是<b>海岸与高山几乎不再呼啸</b>——海岸的
     * 风等级上限只有 0.55，按 0.60 判是 0.0%；高山按 0.60 判只剩 2.6%。所以门限跟着
     * 预设走，而 mean/depth/cap 那三个数是三用例标定过的，一个都不动。</p>
     *
     * <p>数据包可覆盖：{@code wind.json} 的 {@code howlWindMin}。滞回仍是
     * {@code BiomeSoundHandler.HOWL_GATE_HYSTERESIS}（0.06，不在预设里 —— 它是门控的
     * 时间行为，不是风的性格）。</p>
     */
    private float howlWindMin = DEFAULT_HOWL_WIND_MIN;
    /**
     * 这个预设的床素材是不是<b>自带叶声</b>（§3.10.1 要点 5）。
     *
     * <p>不是那 8 个自由度之一：它是一条"这条素材里已经有树叶了"的声明，用来决定还要
     * 不要再叠叶声层。默认 false —— 两种错误的代价不对称：叠多了最多 +1.6 dB
     * （T2b/T2c 实测），而误判成 true 会把叶层整层删掉。</p>
     */
    private boolean assetHasLeaves = false;

    WindProfile(final float[] meanLevel, final float levelJitter, final float gustDepth,
                final float gustRatePerMin, final float floorLevel, final float peakCeiling,
                final float canopyResponse, final GustResponse gustResponse, final float leafWindMin,
                final float howlWindMin) {
        this.defaults = new Defaults(meanLevel.clone(), levelJitter, gustDepth, gustRatePerMin,
                floorLevel, peakCeiling, canopyResponse, gustResponse, leafWindMin, howlWindMin);
        applyDefaults();
    }

    private void applyDefaults() {
        this.meanLevel = this.defaults.meanLevel().clone();
        this.levelJitter = this.defaults.levelJitter();
        this.gustDepth = this.defaults.gustDepth();
        this.gustRatePerMin = this.defaults.gustRatePerMin();
        this.floorLevel = this.defaults.floorLevel();
        this.peakCeiling = this.defaults.peakCeiling();
        this.canopyResponse = this.defaults.canopyResponse();
        this.gustResponse = this.defaults.gustResponse();
        this.leafWindMin = this.defaults.leafWindMin();
        this.howlWindMin = this.defaults.howlWindMin();
    }

    // ================= 取值 =================

    /** 均值，季节索引，<b>未乘季节因子</b>。 */
    public float meanLevelFor(final PrecipitationSeason season) {
        if (season == null)
            return this.meanLevel[PrecipitationSeason.SUMMER.ordinal()];
        if (season == PrecipitationSeason.VANILLA) {
            float sum = 0F;
            for (int i = 0; i < 4; i++)
                sum += this.meanLevel[i];
            return sum / 4F;
        }
        return this.meanLevel[season.ordinal()];
    }

    /** 季节因子，1.0 = 不修正（没有季节模组 / VANILLA）。 */
    public static float seasonFactorFor(final PrecipitationSeason season) {
        if (season == null || season == PrecipitationSeason.VANILLA)
            return 1F;
        return SEASON_FACTOR[season.ordinal()];
    }

    /**
     * 运行期真正用的均值：{@code meanLevelFor(season) x seasonFactorFor(season)}。
     * 这就是三个性格用例离线复算里的 {@code mean}。
     */
    public float meanFor(final PrecipitationSeason season) {
        return Mth.clamp(meanLevelFor(season) * seasonFactorFor(season), 0F, 1F);
    }

    public float levelJitter() {
        return this.levelJitter;
    }

    public float gustDepth() {
        return this.gustDepth;
    }

    public float gustRatePerMin() {
        return this.gustRatePerMin;
    }

    public float floorLevel() {
        return this.floorLevel;
    }

    public float peakCeiling() {
        return this.peakCeiling;
    }

    public float canopyResponse() {
        return this.canopyResponse;
    }

    public GustResponse gustResponse() {
        return this.gustResponse;
    }

    /** 叶声层的风等级门限；见字段说明。 */
    public float leafWindMin() {
        return this.leafWindMin;
    }

    /** 呼啸层的风等级门限；见字段说明。 */
    public float howlWindMin() {
        return this.howlWindMin;
    }

    /** 床素材是否自带叶声；见字段说明。 */
    public boolean assetHasLeaves() {
        return this.assetHasLeaves;
    }

    /**
     * 这个预设"有树吗"——<b>推导量，不是第二个字段</b>：叶声响应 >= 0.5 才算有树。
     * 与 §3.10.3 的逐行对应表一致（WOODLAND / DENSE_CANOPY 是，COASTAL_EXPOSED 的
     * 0.10 与 ALPINE_PEAK 的 0.35 都不是）。
     */
    public boolean hasCanopy() {
        return this.canopyResponse >= 0.5F;
    }

    /** 这个预设有没有风：{@code WASTELAND} 全 0 ⇒ 床与层都不存在。 */
    public boolean isWindless() {
        return this.peakCeiling <= 0F && this.meanLevelFor(null) <= 0F;
    }

    // ================= 群系 -> 预设 =================

    /**
     * 按 {@link BiomeTrait} 优先级解析，命中即返回。
     *
     * <p>顺序刻意"先具体后宽泛"，与 {@code PrecipitationProfile.resolve} 同一条纪律：
     * 维度与遮蔽区最先（那里没有风这个天气维度），然后是群系性格，再是气候带，
     * 最后是开阔地。什么都匹配不上时落到 {@link #OPEN_LOWLAND} —— 模组群系经过
     * {@code BiomeNameFallbackAnalyzer} 的 downfall/temperature 落位后通常也能命中前面的条目。</p>
     *
     * @param traits 群系标签，可为 null
     * @return 匹配到的预设，永不返回 null
     */
    public static WindProfile resolve(final BiomeTraits traits) {
        if (traits == null)
            return OPEN_LOWLAND;

        // 维度与遮蔽区：没有风这个维度。
        if (hasAny(traits, BiomeTrait.NETHER, BiomeTrait.THEEND, BiomeTrait.VOID, BiomeTrait.UNDERGROUND))
            return WASTELAND;

        // 干旱与风蚀：这里"风大且稳定"。
        if (hasAny(traits, BiomeTrait.DESERT, BiomeTrait.SANDY, BiomeTrait.BADLANDS, BiomeTrait.WINDSWEPT))
            return EXPOSED_BARREN;
        if (traits.contains(BiomeTrait.WASTELAND) && !traits.contains(BiomeTrait.SWAMP)
                && !traits.contains(BiomeTrait.COLD))
            return EXPOSED_BARREN;

        // 树：先阔叶林，再郁闭（郁闭比"有森林标签"更具体）。
        if (hasAny(traits, BiomeTrait.DENSE, BiomeTrait.JUNGLE, BiomeTrait.SWAMP, BiomeTrait.MUSHROOM,
                BiomeTrait.CONIFEROUS, BiomeTrait.LUSH))
            return DENSE_CANOPY;
        if (hasAny(traits, BiomeTrait.FOREST, BiomeTrait.DECIDUOUS))
            return WOODLAND;

        // 高海拔与寒冷。
        if (hasAny(traits, BiomeTrait.ICY, BiomeTrait.SNOWY, BiomeTrait.MOUNTAIN, BiomeTrait.PLATEAU))
            return ALPINE_PEAK;

        // 水。
        if (hasAny(traits, BiomeTrait.OCEAN, BiomeTrait.BEACH, BiomeTrait.RIVER))
            return COASTAL_EXPOSED;

        // 开阔地，以及我们没有意见的一切。
        return OPEN_LOWLAND;
    }

    private static boolean hasAny(final BiomeTraits traits, final BiomeTrait... wanted) {
        for (final var t : wanted)
            if (traits.contains(t))
                return true;
        return false;
    }

    // ================= dsconfigs/wind.json =================

    private record Rule(String preset, Optional<List<Float>> meanLevel, Optional<Float> levelJitter,
                        Optional<Float> gustDepth, Optional<Float> gustRatePerMin, Optional<Float> floorLevel,
                        Optional<Float> peakCeiling, Optional<Float> canopyResponse,
                        Optional<GustResponse> gustResponse, Optional<Boolean> assetHasLeaves,
                        Optional<Float> leafWindMin, Optional<Float> howlWindMin) {
        static final Codec<Rule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("preset").forGetter(Rule::preset),
                Codec.list(Codec.FLOAT).optionalFieldOf("meanLevel").forGetter(Rule::meanLevel),
                Codec.FLOAT.optionalFieldOf("levelJitter").forGetter(Rule::levelJitter),
                Codec.FLOAT.optionalFieldOf("gustDepth").forGetter(Rule::gustDepth),
                Codec.FLOAT.optionalFieldOf("gustRatePerMin").forGetter(Rule::gustRatePerMin),
                Codec.FLOAT.optionalFieldOf("floorLevel").forGetter(Rule::floorLevel),
                Codec.FLOAT.optionalFieldOf("peakCeiling").forGetter(Rule::peakCeiling),
                Codec.FLOAT.optionalFieldOf("canopyResponse").forGetter(Rule::canopyResponse),
                GUST_RESPONSE_CODEC.optionalFieldOf("gustResponse").forGetter(Rule::gustResponse),
                Codec.BOOL.optionalFieldOf("assetHasLeaves").forGetter(Rule::assetHasLeaves),
                Codec.FLOAT.optionalFieldOf("leafWindMin").forGetter(Rule::leafWindMin),
                Codec.FLOAT.optionalFieldOf("howlWindMin").forGetter(Rule::howlWindMin)
        ).apply(instance, Rule::new));
    }

    private record WindConfig(Optional<List<Float>> seasonFactor, List<Rule> profiles) {
        static final Codec<WindConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.list(Codec.FLOAT).optionalFieldOf("seasonFactor").forGetter(WindConfig::seasonFactor),
                Codec.list(Rule.CODEC).optionalFieldOf("profiles", List.of()).forGetter(WindConfig::profiles)
        ).apply(instance, WindConfig::new));
    }

    private static final Codec<GustResponse> GUST_RESPONSE_CODEC = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(GustResponse.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "unknown gust response: " + name);
                }
            },
            response -> response.name().toLowerCase(Locale.ROOT));

    private static WindProfile byName(final String name) {
        try {
            return WindProfile.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 把 {@code dsconfigs/wind.json} 叠加到编译期默认值上。
     *
     * <p>先复位再覆盖：一次资源重载里没有出现的预设必须回到默认值，否则上一次数据包的
     * 残留会跟着走到下一个存档。文件不存在、读不动、条目坏掉都只打一行警告——
     * 风不能因为一个数据文件而消失。每个预设写哪些字段是可选的，只覆盖写了的那些。</p>
     *
     * @param resourceUtilities 资源访问器（由 {@code AssetLibraryEvent.RELOAD} 提供）
     */
    public static void reload(final ResourceUtilities resourceUtilities) {
        for (final var profile : values())
            profile.applyDefaults();
        SEASON_FACTOR = DEFAULT_SEASON_FACTOR.clone();

        try {
            for (final var resource : resourceUtilities.findModResources(WindConfig.CODEC, FILE_NAME)) {
                final var config = resource.resourceContent();
                config.seasonFactor().ifPresent(values -> setSeasonFactor(values, resource.namespace()));
                for (final var rule : config.profiles())
                    applyRule(rule, resource.namespace());
            }
        } catch (Throwable t) {
            Library.LOGGER.warn("wind.json could not be read (%s) - the built-in WindProfile table is used", t);
        }

        Library.LOGGER.info("WindProfile: %s", describe());
    }

    private static void setSeasonFactor(final List<Float> values, final String namespace) {
        if (values.size() != SEASON_FACTOR.length) {
            Library.LOGGER.warn("wind.json (%s): seasonFactor has %d values, expected %d - ignored",
                    namespace, values.size(), SEASON_FACTOR.length);
            return;
        }
        for (int i = 0; i < SEASON_FACTOR.length; i++)
            SEASON_FACTOR[i] = values.get(i);
    }

    private static void applyRule(final Rule rule, final String namespace) {
        final WindProfile profile = byName(rule.preset());
        if (profile == null) {
            Library.LOGGER.warn("wind.json (%s): unknown preset '%s' - that entry is skipped", namespace, rule.preset());
            return;
        }
        rule.meanLevel().ifPresent(values -> profile.setMeanLevel(values, namespace));
        rule.levelJitter().ifPresent(v -> profile.levelJitter = v);
        rule.gustDepth().ifPresent(v -> profile.gustDepth = v);
        rule.gustRatePerMin().ifPresent(v -> profile.gustRatePerMin = v);
        rule.floorLevel().ifPresent(v -> profile.floorLevel = v);
        rule.peakCeiling().ifPresent(v -> profile.peakCeiling = v);
        rule.canopyResponse().ifPresent(v -> profile.canopyResponse = v);
        rule.gustResponse().ifPresent(v -> profile.gustResponse = v);
        rule.assetHasLeaves().ifPresent(v -> profile.assetHasLeaves = v);
        rule.leafWindMin().ifPresent(v -> profile.leafWindMin = v);
        rule.howlWindMin().ifPresent(v -> profile.howlWindMin = v);
    }

    private void setMeanLevel(final List<Float> values, final String namespace) {
        if (values.size() != this.meanLevel.length) {
            Library.LOGGER.warn("wind.json (%s): %s.meanLevel has %d values, expected %d - ignored",
                    namespace, name(), values.size(), this.meanLevel.length);
            return;
        }
        for (int i = 0; i < this.meanLevel.length; i++)
            this.meanLevel[i] = values.get(i);
    }

    /** 一行表：诊断与 {@code /dsdump} 用，也是三版一致性脚本读的那一行。 */
    public static String describe() {
        final StringBuilder sb = new StringBuilder();
        for (final var profile : values()) {
            sb.append(profile.name()).append('[');
            for (int i = 0; i < profile.meanLevel.length; i++)
                sb.append(i == 0 ? "" : ",").append(String.format("%.2f", profile.meanLevel[i]));
            sb.append("] j=").append(String.format("%.2f", profile.levelJitter))
                    .append(" d=").append(String.format("%.2f", profile.gustDepth))
                    .append(" r=").append(String.format("%.1f", profile.gustRatePerMin))
                    .append(" f=").append(String.format("%.2f", profile.floorLevel))
                    .append(" c=").append(String.format("%.2f", profile.peakCeiling))
                    .append(" k=").append(String.format("%.2f", profile.canopyResponse))
                    .append(" g=").append(profile.gustResponse.name()).append(" | ");
        }
        return sb.toString();
    }
}
