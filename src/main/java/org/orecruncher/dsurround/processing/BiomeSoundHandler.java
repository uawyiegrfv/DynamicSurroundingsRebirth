package org.orecruncher.dsurround.processing;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.orecruncher.dsurround.config.libraries.IBiomeLibrary;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.BiomeTrait;
import org.orecruncher.dsurround.config.SyntheticBiome;
import org.orecruncher.dsurround.config.SoundEventType;
import org.orecruncher.dsurround.config.biome.BiomeInfo;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.DayCycle;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.math.MathStuff;
import org.orecruncher.dsurround.sound.IAudioPlayer;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;
import org.orecruncher.dsurround.runtime.audio.SoundDiag;
import org.orecruncher.dsurround.sound.BackgroundSoundLoop;
import org.orecruncher.dsurround.sound.ISoundFactory;
import org.orecruncher.dsurround.sound.SoundVolumeEvaluator;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.processing.weather.WindField;
import org.orecruncher.dsurround.processing.weather.WindProfile;

public final class BiomeSoundHandler extends AbstractClientHandler {

    public static final int SCAN_INTERVAL = 4;
    public static final int MOOD_SOUND_MIN_RANGE = 8;
    public static final int MOOD_SOUND_MAX_RANGE = 16;

    // Volume scale applied to biome ambient sounds while the player is inside
    // (covered ceiling). Reduces outdoor ambience (birds, insects, wind, animal
    // calls) inside houses and caves while keeping a faint sense of the outside.
    private static final float INDOOR_VOLUME_SCALE = 0.15F;

    private final IBiomeLibrary biomeLibrary;
    private final IAudioPlayer audioPlayer;
    private final ITickCount tickCount;
    private final Scanners scanner;

    // Leaf-wind gust in wooded biomes: an independent intermittent sound so it does not
    // share the mood chance with bird calls etc. Scanned every 4 ticks (~0.2s), so a
    // gust every ~3 minutes in daytime and ~1 minute at night:
    //   day   = 1 / (180s / 0.2s) = 1/900  ~ 0.0011
    //   night = 1 / (60s  / 0.2s) = 1/300  ~ 0.0033
    private static final ResourceLocation LEAF_WIND = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.leaf_wind");
    private static final ResourceLocation WIND_GENERIC = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.wind.plains");

    /**
     * The bed asset for open country: pure wind, no leaves.
     *
     * <p>{@link #WIND_GENERIC} is cut from cinetony's "leaves rustle in the wind" - its own
     * {@code ds_credits} says so - so on plains and moors it reads as leaves even though nothing
     * put leaves there. This one is felix.blume's Atacama ridge recording: wind against rock and
     * cactus, nothing that rustles. Resampled to 48 kHz and looped seamlessly by
     * {@code tools/_make_wind_open_loop.py}.</p>
     */
    private static final ResourceLocation WIND_OPEN = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.wind.open");
    // The howl layers loop one clip, and a loop is only as long as its clip: the pool's
    // clips are 8-11 s, which the ear hears as a rhythm rather than as weather. These are
    // 45 s and 50 s - all three clips of the tier, each used twice, the second pass time
    // stretched by a few percent (inaudible on broadband noise, but enough that the ear
    // cannot match the two passes), shuffled and crossfaded. See
    // tools/_make_wind_howl_loops.py.
    private static final ResourceLocation HOWL_MEDIUM = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.wind.howl.medium");
    private static final ResourceLocation HOWL_HEAVY = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.wind.howl.heavy");
    private static final ResourceLocation[] RAIN_TEXTURE_WIND = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind1"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind2"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wind3")};
    // Rain landing on a surface. Seven materials: glass, metal, wood, log,
    // stone, leaves and water. Only four of them actually sound -
    // glass, metal, wood and log - because stone, leaves and water are off in
    // MaterialSurfaceScanner.MATERIAL_ENABLED. Do not list all seven in any
    // player-facing text; check that array first.
    // See the comment on handleRainMaterial(). Extracted from the footstep
    // pools by tools/_make_material_rain_assets.py.
    private static final ResourceLocation RAIN_MATERIAL_GLASS =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass");
    private static final ResourceLocation RAIN_MATERIAL_METAL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal");
    private static final ResourceLocation RAIN_MATERIAL_WATER =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water");

    private static final ResourceLocation[] RAIN_MATERIAL_GLASS_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.glass.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_METAL_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.metal.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_WATER_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.water.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_WOOD_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.wood.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_LOG_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.log.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_STONE_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.stone.heavy"),};
    private static final ResourceLocation[] RAIN_MATERIAL_LEAVES_TIERS = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.light"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.medium"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaves.heavy"),};
    private static final ResourceLocation[][] RAIN_MATERIAL_TIERS = {
            RAIN_MATERIAL_GLASS_TIERS, RAIN_MATERIAL_METAL_TIERS, RAIN_MATERIAL_WATER_TIERS,
            RAIN_MATERIAL_WOOD_TIERS, RAIN_MATERIAL_LOG_TIERS,
            RAIN_MATERIAL_STONE_TIERS, RAIN_MATERIAL_LEAVES_TIERS};
    private static final ResourceLocation[] RAIN_TEXTURE_LEAF = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaf1"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.rain.leaf2")};
    // 暴雨档：Bosk1 的呼啸段（tools/_make_storm_assets_final.py）。它不是"中雨那批放大声"，
    // 而是另一条录音 —— 中/小雨那批来自一条树叶实录，空旷地形也带着沙沙，
    // 这正是"草原还有树叶声"的来处。材质换了，不是音量换了。
    private static final ResourceLocation[] STORM_WIND_HEAVY = {
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.storm.wind.heavy1"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.storm.wind.heavy2"),
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.storm.wind.heavy3")};
    // 档位阈值：0=小雨 1=中雨 2=暴雨。降档要比升档多退 HYSTERESIS，否则强度在阈值附近
    // 微动就会来回换素材，听上去是风无缘无故变了音色。
    private static final float GUST_TIER_MEDIUM = 0.35F;
    private static final float GUST_TIER_HEAVY = 0.65F;
    private static final float GUST_TIER_HYSTERESIS = 0.06F;
    // 每档自己的音量。暴雨档压在中雨档的天花板上不再往上走 —— 旧式 (0.35+0.65t)x雷暴1.3
    // 会把暴雨推到 1.3，那是"大雨太吵"的来源；但第一版压到 0.30/0.45/0.50 又压过头了，
    // 中雨丢了三分之一、暴雨丢了一半，听上去就是"底噪风声没了"。
    // 现在 tiers 之间拉开：小雨最轻、中雨居中、暴雨最响且用新素材（换素材也要换
    // 音量，否则中雨和暴雨一样响，"换了新呼啸怎么没变"）。这一组是绝对值，风力
    // 调制见 GUST_WIND_LEVEL_*。
    private static final float[] GUST_TIER_VOLUME = {0.26F, 0.38F, 0.47F};

    // ---- 2026-10-10 维护者实机反馈：呼啸声偏吵、起伏的"伏"太大、起得太频繁 ----
    // 按每条抱怨各动一个语义对应的旋钮，不做整体压死（本文件上面记着一次过度压制的教训：
    // 中雨丢三分之一、暴雨丢一半，听起来变成"底噪风声没了"）：
    //   伏太大 -> GUST_WIND_LEVEL_FLOOR 0.70→0.50（阵风包络的安静端；顺带把峰也压到 0.95）
    //           RAIN_WIND_SWELL_FLOOR 0.42→0.30（雨风床的起伏底部）
    //           HOWL_FLOOR 0.30→0.22（呼啸自身的安静端）
    //   太吵   -> HOWL_LEVEL_MEDIUM 0.45→0.36、HOWL_LEVEL_HEAVY 0.80→0.62（最响的一层）
    //           GUST_TIER_VOLUME {0.30,0.44,0.55}→{0.26,0.38,0.47}（三档各降约 13%）
    //   起太频 -> SWELL_PERIODS_S 13.7/23.3/37.1/59.9→19.1/31.7/45.3/67.9（拉长约 40%，仍互不通约）
    // 未动：档位阈值（换音色的滞回）、RAIN_WIND_BED_GAIN（那次"底噪没了"就是压它压过头）、
    //       CANOPY_LEVEL、RAIN_GUST_STORM_GAIN、叶片阵风节奏。
    // ---- WIND：让听得见的风与看得见的风同源 --------------------------------
    // 以前风声层的大小只吃降水强度，而雨幕的倾斜吃 WindField（强度 + 阵风幅度 +
    // 群系 + 新的季节系数），两边各有一套大小：密林里雨丝几乎不斜而风声照旧走满，
    // 风袭/山地里雨丝斜得厉害、风声却一点没变响。现在风床和阵风都改问
    // WindField.speedAt()，再由下面这几个数把格/秒折回 0..1。
    //   折回锚点与风速量程是同一份常量，住在 WindField（WIND_LEVEL_MIN_BPS /
    //   WIND_LEVEL_SPAN_BPS）。P1 之前这里自己维护着 0.8/7.2，与 WindField 的
    //   0.15 地板对不上，bps <= 0.8 整整一段被夹成 0；P2 把"唯一的风等级"搬进
    //   WindField 之后这里不再有第二份定义，也就没有第二次对不上的机会。
    //   现在风声的大小直接问 WindField 的风等级（预设驱动），本文件不再自己折算。
    /** 阵风音量受风力调制的深度：静风保留 FLOOR，风拉满补到 FLOOR + SPAN。
     *  FLOOR 抬高 = 所有阵风一起变响；SPAN 加大 = 风大风小的差别更明显。 */
    private static final float GUST_WIND_LEVEL_FLOOR = 0.50F;
    private static final float GUST_WIND_LEVEL_SPAN = 0.45F;
    /** 空旷地形阵风的发声点放在上风侧多远，格。太远会听成"从某个点吹来"，而且
     *  LINEAR 衰减会顺带吃掉音量（约 -2 dB / 4 格）；太近又退回没有方向。 */
    private static final float GUST_SOURCE_RANGE_BLOCKS = 4F;
    /** 发声点的抬升高度，格：贴在脚面上会被雨打材质那一层埋掉。 */
    private static final float GUST_SOURCE_HEIGHT_M = 2F;
    /**
     * Loudness lift for the rain wind bed. The assets are quiet field recordings
     * (wind.ogg sits at -34.8 dB RMS); as one-shot acoustics that was fine, as a
     * continuous bed it read as "barely there". All beds share this gain, and the
     * per-asset trims keep their relative matching on top of it.
     */
    private static final float RAIN_WIND_BED_GAIN = 2.5F;

    // ---- rain wind layer (A-line) ------------------------------------------
    // Sunny behavior is untouched: everything below only runs while the graded
    // precipitation system owns the weather, and it fades out with the storm.

    /** Intensity below which a windless biome gets no rain wind bed at all: it
     *  keeps drizzle from howling across the plains and leaves the sound source
     *  free. Biomes with their own wind asset skip this gate - their bed is a
     *  pure proportion of the intensity and dies on its own at the low end.
     *  0.3 -> 0.15: the bed is what a gust is meant to be riding on. At 0.3 it
     *  was still silent through most of a medium rain, so every gust arrived
     *  out of nowhere and read as a loud noise rather than as weather. */
    private static final float RAIN_WIND_MIN_INTENSITY = 0.15F;
    /** Leaf gust pacing: frequency rises with intensity, divided by the event's
     *  rolled gust amplitude (convective profiles gust more often than steady
     *  rain - GustResponse semantics, not a second curve). */
    private static final float RAIN_LEAF_MIN_INTENSITY = 0.25F;
    // A gust every 25-60 s before the amplitude divisor, against the 5-16 s the
    // first pass used. At the old rate the canopy never stopped rustling, which
    // reads as noise rather than weather - what is wanted is the occasional big
    // gust that comes through and takes the leaves with it.
    // Widened: 70 s at the low end down to 18 s at full intensity (was 60/25),
    // before the amplitude divisor. The maintainer could not tell that the
    // pacing was coupled to intensity at all, so make the span obvious.
    private static final float RAIN_LEAF_INTERVAL_MAX_S = 70F;
    private static final float RAIN_LEAF_INTERVAL_MIN_S = 18F;
    /**
    /**
     * How far the wind bed may fall below, and how far it may rise above, the
     * level the intensity asks for. A bed pinned to one level is a loop with a
     * volume knob, and the maintainer asked for the swells to bite harder.
     * The ceiling is separate from the floor because the complaint was about
     * the crest specifically - lowering the shared bed gain instead would have
     * dragged the trough down with it, and the trough is already at the level
     * that stopped reading as "barely there".
     */
    private static final float RAIN_WIND_SWELL_FLOOR = 0.30F;
    private static final float RAIN_WIND_SWELL_PEAK = 0.8F;
    /**
     * Four swell periods that share no common multiple, so the sum does not
     * repeat inside any stretch a player listens to, and phases drawn once per
     * JVM run so no two sessions breathe alike.
     */
    private static final double[] SWELL_PERIODS_S = {19.1, 31.7, 45.3, 67.9};
    private static final double[] SWELL_PHASES = swellPhases();

    private static double[] swellPhases() {
        final java.util.Random r = new java.util.Random();
        final double[] p = new double[SWELL_PERIODS_S.length];
        for (int i = 0; i < p.length; i++)
            p[i] = r.nextDouble() * 2D * Math.PI;
        return p;
    }

    /** How loud the sustained layers sit, before the gust envelope is applied. */
    private static final float HOWL_LEVEL_MEDIUM = 0.36F;
    private static final float HOWL_LEVEL_HEAVY = 0.62F;
    /**
     * The quiet end of the shaped gust envelope.
     *
     * <p>{@code WindField.envelopeAt} bottoms out at 0.15 because a visual may stand still for a
     * moment. Put that straight on a gain and the wind <em>leaves</em> once every minute or two -
     * the slow layer of the envelope is exactly that long, and "the wind died" is a defect, not a
     * feature. The shape is what was asked for (loud for a stretch, quiet the rest of the time);
     * only its floor is raised here, so it asks for soft instead of asking for absent.</p>
     */
    private static final float HOWL_FLOOR = 0.22F;
    /** The canopy layer only ever answers the howl, it never leads it. */
    private static final float CANOPY_LEVEL = 0.38F;

    // ---- 统一风系统：层的门控与权重（设计 §3.10.2） -------------------------
    /** 叶声层风等级门限的<b>兜底值</b>，滞回 {@link #LEAF_GATE_HYSTERESIS}。低于门限
     *  <b>整层不存在</b> —— 不是"很小"。旧口径是 (0.40 + 0.60 x bandNorm)，静风时仍留
     *  0.40，层永远到不了 0，与"大部分时间安静"直接冲突。
     *  真正的门限按预设走（{@code WindProfile.leafWindMin}，密林取更低的值），
     *  这里只在没有预设时用。 */
    private static final float LEAF_WIND_MIN = 0.25F;
    private static final float LEAF_GATE_HYSTERESIS = 0.05F;
    /** 呼啸层的最小风等级，滞回 {@link #HOWL_GATE_HYSTERESIS}。 */
    private static final float HOWL_WIND_MIN = 0.35F;
    private static final float HOWL_GATE_HYSTERESIS = 0.06F;

    // ---- WIND P3①：床档位族与强风换持续呼啸（设计 §3.10.4 / §3.10.5 / 附录 D） ----
    /**
     * 默认床的档位族：<b>三档 L/M/S × 两条互质循环 A/B</b>，全部 static ——
     * 静态池 247 放得下（24 条只占 9.7 %），而流式池只有 8 条、维护者实测峰值已 7/8，
     * 所以床、叶层、呼啸这三族<b>一条都不能是 stream</b>。
     *
     * <p>粗档的响度由素材自带（−42 / −40 / −38 LUFS-I，附录 D.1），运行期不再按档给
     * 不同的增益，只给等增益的交叉权重 —— 三档因此是同一条曲线上的三段，而不是三条
     * 各自标定的曲线（§3.10.6）。</p>
     *
     * <p>两张床分开：有树群系用 {@code wood}（中性偏林地），无树用 {@code open}（开阔）。
     * <b>树 / 无树的差别由"上面叠叶层还是叠呼啸层"表达，不由床表达</b> —— 两张床都是
     * 纯风，一点叶声都没有，所以 {@link #bedHasLeaves} 对它们是 false，叶层照叠
     * （§3.10.1 规则表 A 优先级 2 / 3）。</p>
     */
    private static final ResourceLocation[][] BED_TIERS_OPEN = windTierPair("biome.wind.bed.open");
    private static final ResourceLocation[][] BED_TIERS_WOOD = windTierPair("biome.wind.bed.wood");
    /** 叶声层档位族（{@code L-leaf}，附录 D.2）。 */
    private static final ResourceLocation[][] LEAF_TIERS = windTierPair("biome.wind.leaf");
    /**
     * 呼啸档位族（{@code H-howl}）：<b>呼啸层与强风时的持续呼啸床共用这一族</b>，
     * 所以交接只是把同一条信号从一个槽搬到另一个槽（§3.10.2）。
     */
    private static final ResourceLocation[][] HOWL_TIERS = windTierPair("biome.wind.howl");

    /**
     * 后缀表（档 / 环）的持有者。
     *
     * <p><b>为什么单独用一个内部类</b>：类初始化按<b>文本顺序</b>执行。上面那四个档位族
     * 字段在 {@code <clinit>} 里就调用 {@code windTierPair}，而它要读后缀表。后缀表原来
     * 是本类的静态字段、声明在调用点<b>之后</b>，读到时还是 {@code null}：2026-10-11
     * 客户端启动崩溃 —— {@code java.lang.NullPointerException: Cannot read the array
     * length because ...BiomeSoundHandler.TIER_ID is null}（{@code windTierPair(:,299)}
     * ← {@code <clinit>(:,283)}），类初始化失败、DI 造不出 Handlers、
     * {@code Client.onComplete} 把客户端直接带崩。</p>
     *
     * <p>放进独立持有者类以后，后缀表有<b>自己的</b> {@code <clinit>}：第一次被读时才
     * 做两个字面量数组，不读本类任何东西。档位族的构造因此与本类静态字段的书写顺序无关 ——
     * 顺序坑在这一层被消掉，而不是靠"记得往前挪一行"。</p>
     */
    private static final class Suffixes {
        /** 档位后缀，索引 = 档（0 = L / 1 = M / 2 = S）。 */
        static final String[] TIER_ID = { "l", "m", "s" };
        /** 互质循环后缀，索引 = 环（0 = A / 1 = B）。 */
        static final String[] RING_ID = { "a", "b" };
    }

    private static ResourceLocation[][] windTierPair(final String prefix) {
        final String[] tierId = Suffixes.TIER_ID;
        final String[] ringId = Suffixes.RING_ID;
        final ResourceLocation[][] out = new ResourceLocation[tierId.length][ringId.length];
        for (int t = 0; t < tierId.length; t++)
            for (int r = 0; r < ringId.length; r++)
                out[t][r] = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                        prefix + "." + tierId[t] + "." + ringId[r]);
        return out;
    }

    /** L 完全区的上界（§3.10.5）。 */
    private static final float TIER_LM_LO = 0.30F;
    /** L→M 交叉区的上界，交叉区宽 0.20。 */
    private static final float TIER_LM_HI = 0.50F;
    /** M 完全区的上界。 */
    private static final float TIER_MS_LO = 0.65F;
    /** M→S 交叉区的上界，<b>同时是"床换持续呼啸"的门限</b>。 */
    private static final float TIER_MS_HI = 0.85F;
    /**
     * 默认床档位族的运行期增益。素材已按 LUFS-I 归一到 −42 / −40 / −38，粗档在素材里，
     * 所以这里不再是旧口径的 {@code RAIN_WIND_BED_GAIN = 2.5}（§4.1 的 {@code BED_GAIN}）。
     */
    private static final float BED_TIER_GAIN = 1.0F;
    /**
     * 呼啸族按风档取电平。三档素材自带 −38 / −36 / −34 LUFS-I 的档差（附录 D.2），
     * 运行期沿用 P2 的两级电平：L / M 用中档值，S 用强档值。两个常量名一个都没改 ——
     * {@code _wind_unified/verify_wind_parity.py} 读的就是它们。
     */
    private static final float[] HOWL_TIER_LEVEL = { HOWL_LEVEL_MEDIUM, HOWL_LEVEL_MEDIUM, HOWL_LEVEL_HEAVY };

    /** 床换持续呼啸的风等级门限，滞回 {@link #HOWL_BED_HYSTERESIS}（§3.10.2）。 */
    private static final float HOWL_BED_MIN_W = 0.85F;
    private static final float HOWL_BED_HYSTERESIS = 0.06F;
    /** 呼啸拥有权交接的时长，秒。与 §3.10.5 的交叉时长、T3 试听件同值。 */
    private static final float HOWL_HANDOFF_S = 3.0F;
    /** 床素材的谱质心超过这个值就判为"自带叶声"（附录 D.1 的实测：本包床档位
     *  194~359 Hz vs cinetony 树叶录音 4105 Hz，两类相差 >= 2.7 倍，现有素材没有
     *  中间样本）。判为真时<b>不再叠叶声层</b>，见 §3.10.2 要点 5。 */
    private static final float ASSET_LEAF_CENTROID_HZ = 1500F;
    /**
     * Extra level for a gust in a thunderstorm. A thunderstorm has to outshout
     * its own rain; at full intensity the gusts were being buried by it.
     */
    private static final float RAIN_GUST_STORM_GAIN = 1.3F;
    /**
     * Rain landing on a material surface: a bounded number of continuous loops
     * spread over whatever surfaces are in reach.
     *
     * <p>Not one per material. One per material stopped different materials
     * competing and started the identical bug one level down - a whole glass
     * roof produced exactly one sound, and every other block of glass was
     * silent, because the slot had claimed the material. Rain on a surface is a
     * field: a large expanse should carry several loops spread across it, and
     * they get louder together simply because there are more of them.</p>
     *
     * <p>This is also the performance answer. The cap is on the total, so a
     * field of glass costs what a single pane costs, many materials in reach
     * can neither stutter nor fall silent, and adding more materials later does
     * not change the budget.</p>
     */
    private static final int RAIN_MATERIAL_MAX_LOOPS = 8;
    /**
     * Shortest gap between two preemptions, in ticks. Three seconds: long
     * enough that a run of unrelated neighbour updates cannot keep cycling
     * the layer, short enough that a block just placed is never far from
     * being heard.
     */
    private static final long RAIN_MATERIAL_PREEMPT_COOLDOWN = 60L;
    /**
     * The number of simultaneous loops this layer's level was tuned at. More
     * voices than this are normalised down to it, so a scene growing does not
     * mean the layer getting louder - which is the whole of the ninth
     * complaint. Two, not four: eight loops at the old anchor still arrived
     * six decibels above one pane, because ten times the log of eight is nine
     * and four only gave three of it back. At two, a plank floor, a plank
     * floor with logs in it and a whole village all land within a decibel of
     * each other, and the extra slots buy coverage instead of level.
     */
    private static final int RAIN_MATERIAL_TUNED_VOICES = 2;
    /**
     * How many loops one group of materials may hold at once, and which group
     * each material belongs to. Both are indexed the way the scanner's
     * MATERIAL_ENABLED is: glass, metal, water, wood, log, stone, leaves.
     *
     * <p>Planks and logs are one group on purpose. They are one SoundType to
     * the game and one material to the ear, split only by the vanilla log tag,
     * and a wooden wall is both at once. Counting them separately let a
     * village hold four wooden loops where a greenhouse held two glass ones -
     * twice the drops and twice the level, which is exactly the tenth report,
     * wood in quantity reading as hail.</p>
     *
     * <p>The cap itself is the half of this that is harshness rather than
     * level. A second loop buys spread - a field instead of a point. A third
     * buys level and a busy, comb-filtered quality, because it is the same
     * drops at the same spectrum a fraction of a second apart, and no amount
     * of trimming makes that read as rain.</p>
     */
    private static final int RAIN_MATERIAL_MAX_PER_GROUP = 2;
    private static final int[] RAIN_MATERIAL_GROUP = {0, 1, 2, 3, 3, 4, 5};

    /**
     * How far apart two loops of the same group must be, in blocks squared.
     *
     * <p>Twelfth log, measured rather than guessed: of the seventy picks that
     * had another loop already sounding, twenty-one landed within four blocks
     * of it and sixteen landed on the block right next to it. Two loops of
     * one clip one block apart are not a surface, they are one surface twice
     * - three decibels of level plus a comb filter wherever the two copies
     * disagree, which is the layer reading as loud and busy instead of wide.
     * That is the "competition" still left in the eleventh log: not two
     * materials fighting over a slot, but two copies of one material sharing
     * a spot.</p>
     *
     * <p>Four blocks, so a surface has to be about five wide before it earns
     * a second anchor. A small wooden platform now gets one loop, which is
     * also the honest answer - rain on three planks is quieter than rain on
     * a roof, and the level dropping in exactly those scenes is the point.</p>
     */
    private static final long RAIN_MATERIAL_MIN_SPREAD_SQ = 16L;
    /**
     * How much a material nothing is playing yet is worth, in blocks squared.
     *
     * <p>It was 10000 - larger than any squared distance this box can express,
     * which makes it not a preference but a first round: every material with a
     * loop already sounding was rejected outright, and only among what was
     * left did distance get a say. The thirteenth log is what that costs.
     * Both slots were holding wood and log; one was given back, and the pick
     * walked past planks three blocks away to reach a log ten blocks off and
     * anchored there. Some forty of these in two hundred seconds, each one a
     * fade out and a fade in somewhere new.</p>
     *
     * <p>The size it had existed to stop one expanse taking every slot. The
     * per-group cap above does that job now and does it better: a group cannot
     * take a third loop, so the slots that are left have nowhere to go but
     * somewhere else. What remains for this dial is preferring variety at
     * roughly equal distance, and sixty squared blocks - call it eight blocks
     * - is the size of that.</p>
     */
    private static final double RAIN_MATERIAL_NEW_MATERIAL_BIAS = 60.0;
    /**
     * Where being away from another loop stops being worth anything, squared.
     *
     * <p>The score was {@code nearest - 0.5 * toPlayer} with {@code nearest}
     * unbounded, so "spread the loops over the surface" was really "put the
     * second one as far from the first as the box allows" - the rim, six to
     * ten blocks out, which is a loop being quiet rather than wide. Past some
     * separation the two already read as two places, and buying more buys
     * nothing; past six blocks it stops counting and distance to the player
     * decides on its own. The hard floor below - MIN_SPREAD_SQ - still keeps
     * them from landing on each other, so this cap cannot collapse them back
     * into one by removing the reason to move apart.</p>
     */
    private static final double RAIN_MATERIAL_SPREAD_CAP_SQ = 36.0;
    /**
     * Only for the diagnostic line, and for the intensity curve below: under
     * this the layer sits at its floor rather than going silent, so a drizzle
     * still has drops in it.
     */
    private static final float RAIN_MATERIAL_MIN_INTENSITY = 0.2F;
    /**
     * Global trim, applied to every loop of this layer before the per-material
     * trim below. One, and it should stay one: the array below is where the
     * levels live now, and two dials that both mean "quieter" is how a number
     * ends up meaning nothing.
     */
    private static final float RAIN_MATERIAL_VOLUME = 1.0F;
    /**
     * Level per material, indexed the way the scanner's MATERIAL_ENABLED is:
     * glass, metal, water, wood, log, stone, leaves.
     *
     * <p>They are not all the same number because the eleventh report says
     * they should not be: glass and metal had come out "a bit quiet", planks
     * and logs "about right", out of one global trim that had been cut twice.
     * Cutting everything together was the error. He called glass and metal
     * perfect long before any of this and never once said they were loud -
     * wood was the material he called loud, twice, and in quantity. So glass
     * and metal come back up and wood stays where the tenth round put it.</p>
     */
    private static final float[] RAIN_MATERIAL_TRIM =
            {0.7F, 0.7F, 1.0F, 0.5F, 0.5F, 1.0F, 1.0F};
    /** Lowest scale the intensity curve reaches - a drizzle still has some. */
    private static final float RAIN_MATERIAL_VOLUME_FLOOR = 0.55F;
    private static final float RAIN_MATERIAL_TIER_MEDIUM = 0.4F;
    private static final float RAIN_MATERIAL_TIER_HEAVY = 0.7F;
    /**
     * How far past a threshold the intensity has to go the other way before
     * the tier moves back. The eleventh log has the intensity sitting at 0.50
     * and at 1.00 - either side of the heavy threshold - and every crossing
     * used to fade the whole layer and pick it again.
     */
    private static final float RAIN_MATERIAL_TIER_HYSTERESIS = 0.06F;
    /**
     * How many ticks a spot has to keep looking wrong before its loop is
     * dropped. One bad read used to cost a loop, and the beat after it picked
     * somewhere else; that is the churn in the log. A quarter of a second is
     * short enough that a block he breaks still goes quiet at once, and long
     * enough that a read which disagrees for three ticks does not move the
     * sound. Zero gives back the old instant-drop behaviour.
     */
    private static final int RAIN_MATERIAL_INVALID_GRACE_TICKS = 5;
    /**
     * How long a candidate set stays usable, in ticks. The volume is walked
     * again every beat even when the player has not moved a block and nothing
     * in the world has changed, which is most beats of most sessions. A second
     * of staleness is inaudible next to a set that cannot have changed; a
     * move or a block update walks it again at once.
     */
    private static final long RAIN_MATERIAL_SCAN_CACHE_TICKS = 20L;
    /**
     * How often the biome gate is re-asked, in ticks. The answer is a biome
     * lookup and a temperature read, and it does not change while he stands
     * in the same place.
     */
    private static final long RAIN_MATERIAL_BIOME_CHECK_TICKS = 20L;
    /**
     * Cadence for re-filling the anchored surfaces. Half a second, not one:
     * the set is now walked synchronously on this beat instead of assembled
     * over the twenty ticks before it, so a shorter beat costs real work -
     * roughly six thousand block reads a second while it rains, which is the
     * price the maintainer asked to pay later rather than now. In exchange,
     * walking under a glass roof is heard inside half a second and every
     * candidate handed to the allocator came from the box the player is in.
     */
    private static final int RAIN_MATERIAL_SCAN_TICKS = 10;
    /**
     * Shortest wait before a block update may pull the allocation beat early.
     * One beat shorter than the cadence above: laying a floor is dozens of
     * updates, and each one would otherwise buy a scan of its own.
     */
    private static final int RAIN_MATERIAL_REACT_TICKS = 5;
    /**
     * Squared distance past which a loop gives its slot back.
     *
     * <p>Deliberately larger than the scan box. Releasing the instant a spot
     * leaves the box would make every step through a village buy a fade and a
     * re-pick - the churn that was the whole complaint two rounds ago - and a
     * surface the allocator has stopped being shown is still making real
     * sound. Twelve blocks is past where this layer can be heard, and still
     * tight enough that walking out of a scene frees its slot at once.</p>
     *
     * <p>The sixth log needed this to be a hard rule, not a preference: it
     * ended with seven of eight loops playing a village twenty-five to thirty
     * blocks behind him, every one of them still a valid block of wood with
     * the sky above it.</p>
     */
    private static final long RAIN_MATERIAL_RELEASE_DIST_SQ = 144L;
    /**
     * What being near the player is worth against being away from the loops
     * already playing, in squared blocks per squared block. Farthest-point
     * sampling alone parks the loops on the rim of the box: maximally spread,
     * and maximally quiet for it. Half is a compromise - clustered enough to
     * be heard from where he stands, spread enough to read as a field rather
     * than a single source. Zero gives back the old rim-only behaviour.
     */
    private static final double RAIN_MATERIAL_PROXIMITY_WEIGHT = 0.5;

    // Search volume around the player. Not a single ground sample: surfaces can
    // be overhead (a metal roof) or off to the side (a glass wall) as easily as
    // underfoot. Stride 1 - every column. The first pass used 2 to halve the
    // column count and it cost far more than it saved: a lone pane or a single
    // block of metal simply fell between the sampled columns and was not found,
    // which reads as the whole feature being broken.
    // Radius is 6, not 8: reach is the cheap thing to give up and the beat is
    // ten times faster, so a narrower box is how the cost stays flat.
    private static final int RAIN_MATERIAL_RADIUS = 6;
    private static final int RAIN_MATERIAL_UP = 8;
    // Deep enough to reach water you are standing above. A lake or a pool is
    // usually below the player rather than level with them, and that is the
    // case the shallow window kept missing - water's special case is reach
    // downward, not a different way of finding it.
    private static final int RAIN_MATERIAL_DOWN = 12;
    private static final int RAIN_MATERIAL_STRIDE = 1;

    /**
     * Per-source trim for the three-source canopy surround. Perceived level
     * goes with volume * sqrt(voices), so three sources at full volume land
     * 4.8 dB above one - enough to bury the wind they are meant to ride with.
     * Applied at the rain call site and not inside {@code playLeafSurround},
     * because that method is shared with the sunny gust and sunny behaviour is
     * not ours to change.
     */
    private static final float RAIN_LEAF_SOURCE_COMP = 0.58F;
    // No delay constants any more. The wind->leaves lag is in the clips
    // themselves - they were recorded that way - and scheduling a second track
    // a few ticks later only doubled the wind.

    private BackgroundSoundLoop rainWindLoop;
    private Object rainWindAsset;

    /**
     * WIND P3①：三族档位槽，{@code [档][环]} 各一条 static 循环 ——
     * 默认床、叶声层、呼啸（层兼强风时的持续呼啸床）。
     */
    private final TierBank bedTiers = new TierBank(3, 2);
    private final TierBank leafTiers = new TierBank(3, 2);
    private final TierBank howlTiers = new TierBank(3, 2);
    /** 档位权重的复用数组：每 tick 至多几次调用，不在热路径上分配。 */
    private final float[] tierWeightScratch = new float[3];
    /** 呼啸拥有权（§3.10.2 要点 3）：g_howl_bed + g_howl_layer ≡ 1.0。 */
    private float howlBedGain;
    private boolean howlBedOwned;

    // The sustained layers above the bed - see handleWindLayers.
    // P3① 起它们不是"一条循环一个槽"，而是三族档位槽（bedTiers / leafTiers / howlTiers）。
    /** 门控状态（带滞回），见 handleWindLayers。 */
    private boolean howlGateOpen = false;
    private boolean leafGateOpen = false;
    private long rainGustNextTick;
    private int rainGustTier = -1;
    private int rainGustLastIndex = -1;
    private long rainMaterialNextTick;
    // DIAGNOSTIC: shape of the last candidate set - how many surfaces and how
    // many of each material, not which container they arrived in. Comparing by
    // identity always said "new", because the allocator hands back a fresh
    // container on every beat, and the result was a scan line every half
    // second: 449 of them in one log, burying the 37 picks underneath.
    private long rainMatLastFingerprint = -1L;
    /** Shape of the last refresh line, so a steady scene stops repeating it. */
    private long rainMatLastRefreshShape = -1L;
    private int rainMatScanPass = 0;
    private long rainMatScanPassLogTick = -1000L;
    private long rainMatRefreshLogTick = -1000L;
    // Set by a block update, cleared once the allocator has acted on it.
    private boolean rainMatReevaluate = false;
    private long rainMatLastPreemptTick = -RAIN_MATERIAL_PREEMPT_COOLDOWN;
    private long rainMatLastRefreshTick = 0L;
    /** A slot was given back this tick because its scene was left behind. */
    private boolean rainMatSlotFreed = false;
    private final BackgroundSoundLoop[] rainMaterialLoops =
            new BackgroundSoundLoop[RAIN_MATERIAL_MAX_LOOPS];
    private final Object[] rainMaterialAssets = new Object[RAIN_MATERIAL_MAX_LOOPS];
    private final net.minecraft.core.BlockPos[] rainMaterialSpots =
            new net.minecraft.core.BlockPos[RAIN_MATERIAL_MAX_LOOPS];
    private final int[] rainMaterialMaterials = new int[RAIN_MATERIAL_MAX_LOOPS];
    private final int[] rainMaterialTiers = new int[RAIN_MATERIAL_MAX_LOOPS];
    /** Ticks this spot has looked invalid for - see the grace constant. */
    private final int[] rainMaterialInvalidTicks = new int[RAIN_MATERIAL_MAX_LOOPS];
    /**
     * What this layer costs, counted rather than argued about. The maintainer
     * asked how many milliseconds a tick it runs and whether it can be felt in
     * the low frames, and the only honest answer to either is the one the game
     * prints: two nanoTime reads around the layer each tick, two more around
     * each scan, no sampling and no estimates anywhere in it.
     */
    private static final long RAIN_MATERIAL_PERF_LOG_TICKS = 200L;
    /** Minimum gap between "scan pass" lines. The candidate set changes
     *  every beat he is walking, so profiling (below) is what keeps the
     *  shape readable; this only keeps it from being written every beat.
     *  0 restores one line per changed set. */
    private static final long RAIN_MATERIAL_SCAN_LOG_MIN_TICKS = 40L;
    /** How often a beat that picked nothing still prints. Never is too
     *  little - a scene that stays silent has to be visible - but every
     *  beat is four lines a second about nothing happening. */
    private static final long RAIN_MATERIAL_REFRESH_LOG_TICKS = 100L;
    /** Resolved once per tick. When it is false none of the timers below run
     *  at all - the maintainer asked for the diagnostics to be a switch he can
     *  leave off, and a switch that still paid for the measurement would not
     *  be one: round 15 measured the logging as the most expensive thing in
     *  the allocator. Two boolean reads per tick is the entire cost of off. */
    private boolean rainMatTrace = false;
    private long rainMatPerfTicks = 0L;
    private long rainMatPerfNanos = 0L;
    private long rainMatPerfMaxNanos = 0L;
    private long rainMatPerfLogTick = -1000L;
    private int rainMatScanCount = 0;
    private long rainMatScanNanos = 0L;
    private long rainMatScanMaxNanos = 0L;
    private long rainMatValidNanos = 0L;
    private long rainMatValidMaxNanos = 0L;
    private int rainMatRefreshCount = 0;
    private long rainMatRefreshNanos = 0L;
    private long rainMatRefreshMaxNanos = 0L;
    private long rainMatScaleNanos = 0L;
    private long rainMatScaleMaxNanos = 0L;
    private long rainMatRestMaxNanos = 0L;
    /** The tier the layer is playing, for the hysteresis in the allocator. */
    private int rainMaterialTier = -1;
    private long rainMatBiomeCheckTick = -1000L;
    private boolean rainMatBiomeAllows = true;
    private long rainMatBiomeLogTick = -1000L;
    private Object rainMatScanCache = null;
    private Object rainMatScanAt = null;
    private long rainMatScanTick = -1000L;
    private static final float LEAF_WIND_DAY_CHANCE = 0.0011F;
    private static final float LEAF_WIND_NIGHT_CHANCE = 0.0033F;

    // Intermittent sculk clicking in the Deep Dark, reminiscent of sculk sensors.
    // Independent of the shared mood chance. Scanned every 4 ticks (~0.2s), so a
    // burst every ~2 minutes: 1 / (120s / 0.2s) = 1/600 ~ 0.0017
    private static final ResourceLocation SCULK_CLICK = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.sculk_click");
    private static final float SCULK_CLICK_CHANCE = 0.0017F;
    private static final ResourceLocation DEEP_DARK_BIOME = ResourceLocation.fromNamespaceAndPath("minecraft", "deep_dark");
    private static final ResourceLocation DEEP_DARK_DRONE = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark");
    private static final ResourceLocation DEEP_DARK_HEARTBEAT = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "biome.deep_dark_heartbeat");
    // The drone loop is an inherently loud low rumble; scale it down a touch so it
    // sits under the heartbeat instead of dominating the deep-dark ambience.
    private static final float DEEP_DARK_DRONE_VOLUME_SCALE = 0.8F;

    // Scratch map used for calculating what sounds need to be playing
    private final Object2FloatOpenHashMap<ISoundFactory> workMap = new Object2FloatOpenHashMap<>(8, Hash.DEFAULT_LOAD_FACTOR);
    // List of emitters that are managing the currently playing biome-related sounds
    private final ObjectArray<BiomeSoundEmitter> emitters = new ObjectArray<>(8);

    public BiomeSoundHandler(IBiomeLibrary biomeLibrary, IAudioPlayer audioPlayer, ITickCount tickCount, Scanners scanner, Configuration config, IModLog logger) {
        super("Biome Sounds", config, logger);
        this.audioPlayer = audioPlayer;
        this.biomeLibrary = biomeLibrary;
        this.tickCount = tickCount;
        this.scanner = scanner;
        this.workMap.defaultReturnValue(0F);
        this.dimensionInformation = org.orecruncher.dsurround.lib.di.ContainerManager
                .resolve(org.orecruncher.dsurround.config.libraries.IDimensionInformation.class);
        // Block updates reach every other cuboid-driven system through this
        // hook. The material layer never subscribed, so a block placed during
        // rain was invisible until the next full pass walked over it.
        org.orecruncher.dsurround.eventing.ClientEventHooks.BLOCK_UPDATE
                .register(this::onMaterialBlockUpdates);
    }

    /**
     * Client-side block updates, applied to the material candidate set at once.
     *
     * <p>The scanner is only created once rain starts, so a dry world has
     * nothing to update and pays nothing.</p>
     */
    private void onMaterialBlockUpdates(final java.util.Collection<net.minecraft.core.BlockPos> positions) {
        // A dry world still costs nothing: the flag is only read by the rain
        // layer, and nothing else reacts to it.
        if (positions.isEmpty())
            return;
        // Nothing to patch any more. The candidate set is walked from the
        // live world at the moment it is read, so a change only has to be
        // noticed - it cannot be missed by a set published earlier.
        this.rainMatReevaluate = true;
    }

    private final org.orecruncher.dsurround.config.libraries.IDimensionInformation dimensionInformation;

    /**
     * Whether biome ambience should play at all: the global sound option AND the dimension's own
     * setting.
     *
     * <p>dimensions.json has carried playBiomeSounds all along, and DimensionInfo parsed and stored it,
     * but there was no accessor and no reader - so a pack that set it to false still got biome ambience.
     * Checking it here covers every caller, because this is the single gate they all pass through.
     */
    private boolean doBiomeSounds() {
        return this.config.soundOptions.enableBiomeSounds && this.dimensionInformation.playBiomeSounds();
    }

    private void generateBiomeSounds() {
        // Get the biomes that have been scanned in the area along with the amount of area
        // each occupies. For each of these biomes, obtain the sounds for that biome into the
        // work array. The volume of each sound will be scaled by the amount of area being
        // occupied. This will result in the sounds for the most dominant biomes being louder
        // than the others.
        final float area = this.scanner.getBiomeArea();
        final boolean inside = this.scanner.isInside();
        // Indoor ambient sounds are heavily attenuated so outdoor ambience does
        // not carry through walls and ceilings.
        for (var kvp : this.scanner.getBiomes().reference2IntEntrySet()) {
            var acoustics = kvp.getKey().findBiomeSoundMatches();
            final float areaScale = 0.05F + 0.95F * (kvp.getIntValue() / area);
            for (var acoustic : acoustics) {
                // The rain bed already has this asset running as its own loop.
                // Letting both hold it opens the same ogg twice - two starting points, two
                // gains - and that is where the "sometimes the bed is very loud" came from:
                // measured at -21.5 dB on cold biomes, louder than a heavy gust.
                if (isWindFactory(acoustic) && PrecipitationIntensity.rainingNow())
                    continue;
                // The Deep Dark is a naturally underground biome; its own ambience loops
                // must not be attenuated as if they were outdoor sound leaking inside.
                final float scale = (inside && !isDeepDarkAmbience(acoustic)) ? INDOOR_VOLUME_SCALE : 1.0F;
                // The drone additionally gets its own (reduced) volume scale so it does
                // not dominate the heartbeat and other ambience.
                final float droneScale = DEEP_DARK_DRONE.equals(acoustic.getLocation()) ? DEEP_DARK_DRONE_VOLUME_SCALE : 1.0F;
                this.workMap.addTo(acoustic, scale * droneScale * areaScale * dsBiomeVolume());
            }
        }
    }

    @Override
    public void process(final Player player) {
        // PHASE-0 DIAG (see SoundDiag): nanoTime the whole per-tick audio path and, once a
        // second, snapshot the six layers. Both are dead branches while the gate is off.
        final long dsrDiagStamp = SoundDiag.enabled() ? System.nanoTime() : 0L;
        this.emitters.forEach(BiomeSoundEmitter::tick);
        // This layer runs every tick, not on the biome scan beat. It used to
        // ride SCAN_INTERVAL, which gave its scanner a fifth of the slices and
        // stretched one pass over seconds; a loop anchored from a pass that
        // old was checking against a world several seconds newer than itself.
        // Perceived latency - a block placed in the rain staying silent - came
        // from there. It pays for that by being the only thing here timed.
        if (player.isAlive()) {
            // Timed here rather than inside the method: that method has six
            // ways out, and wrapping each one is how one gets missed.
            // Everything in here that ought to cost something is timed where
            // it runs. What does not land in one of those buckets is reported
            // as "rest" instead of being assumed free - see rainMaterialPerf.
            this.rainMatTrace = this.logger.isTracing(Configuration.Flags.RAIN_MATERIAL);
            if (this.rainMatTrace) {
                final long beforeScan = this.rainMatScanNanos;
                final long beforeValid = this.rainMatValidNanos;
                final long beforeRefresh = this.rainMatRefreshNanos;
                final long beforeScale = this.rainMatScaleNanos;
                final long stamp = System.nanoTime();
                handleRainMaterial(player);
                final long took = System.nanoTime() - stamp;
                final long rest = took
                        - (this.rainMatScanNanos - beforeScan)
                        - (this.rainMatValidNanos - beforeValid)
                        - (this.rainMatRefreshNanos - beforeRefresh)
                        - (this.rainMatScaleNanos - beforeScale);
                rainMaterialPerf(took, rest);
            } else {
                handleRainMaterial(player);
            }
        }
        if ((this.tickCount.getTickCount() % SCAN_INTERVAL) == 0) {
            handleBiomeSounds(player);
        }
        if (dsrDiagStamp != 0L) {
            SoundDiag.mainThreadProcess(System.nanoTime() - dsrDiagStamp);
            if (SoundDiag.flushPending()) {
                soundDiagSample(player);
                SoundDiag.flush();
            }
        }
    }

    /**
     * 第 0 期取证（见 {@link SoundDiag}）：把六层的运行期音量与增益写进快照。
     *
     * <p><b>只读</b>：不改任何增益、周期、素材或判定。数值取自各层自己的 loop 对象
     * （{@code getBaseVolume()/getScale()/getScaleTarget()}）与纯函数
     * （{@code gustSwell/windSwell/windLevel/WindField.envelopeAt}），所以与耳朵听到的是同一组数。
     * 每秒调用一次（由 {@code process()} 在窗口到期时触发）。
     */
    private void soundDiagSample(final Player player) {
        try {
            final long tick = this.tickCount.getTickCount();
            final boolean inside = this.scanner.isInside();
            final float ambient = PrecipitationIntensity.ambient();
            final float gustAmp = PrecipitationIntensity.gustAmplitude();
            final float bedLevel = windLevel();
            final float bedSwell = windSwell(tick);
            final float gustValue = gustSwell(player);
            final float env = WindField.envelopeAt(player.level().getGameTime(), 0F);
            final float speedBps = WindField.speedOf(bedLevel);
            final boolean thundering = player.level().isThundering();

            // 床 BED：handleRainWindBed 的 target = bedLevel × (inside ? 0.15 : 1) × windSwell
            // × 等功率退让（P3①）。默认床报的是权重最大的那一档的素材与循环。
            final float[] bedW = new float[3];
            tierWeights(bedLevel, bedW);
            final int bedTier = argMax(bedW);
            final BackgroundSoundLoop bed = this.rainWindLoop != null ? this.rainWindLoop
                    : this.bedTiers.loops[bedTier][0];
            SoundDiag.layer(SoundDiag.LAYER_BED,
                    "asset=" + assetName(this.rainWindAsset != null ? this.rainWindAsset
                            : this.bedTiers.assets[bedTier][0])
                            + " bedLevel=" + f3(bedLevel)
                            + " tier=" + Suffixes.TIER_ID[bedTier]
                            + " w=" + f3(bedW[0]) + "/" + f3(bedW[1]) + "/" + f3(bedW[2])
                            + " gHowlBed=" + f3(this.howlBedGain)
                            + " gHowlLayer=" + f3(this.howlLayerGain())
                            + " owners=" + f3(this.howlBedGain + this.howlLayerGain())
                            + " bedDuck=" + f3(bedOwnMaterialGain())
                            + " windSwell=" + f3(bedSwell)
                            + " indoor=" + (inside ? f3(INDOOR_VOLUME_SCALE) : "1.000")
                            + " gate=" + (ambient <= RAIN_WIND_MIN_INTENSITY ? "closed" : "open")
                            + " base=" + f3(loopBase(bed))
                            + " scale=" + f3(loopScale(bed))
                            + " target=" + f3(loopTarget(bed))
                            + " vol=" + f3(loopVol(bed)));

            // 呼啸 HOWL：一个拥有者（P3①）。base = HOWL_TIER_LEVEL[t] × gustSwell × storm
            // × W_howl × windVolumeScale × (g_howl_bed + g_howl_layer)；和恒等于 1。
            final BackgroundSoundLoop howl = this.howlTiers.loops[bedTier][0];
            SoundDiag.layer(SoundDiag.LAYER_HOWL,
                    "asset=" + assetName(this.howlTiers.assets[bedTier][0])
                            + " tier=" + Suffixes.TIER_ID[bedTier]
                            + " gustSwell=" + f3(gustValue)
                            + " storm=" + f3(thundering ? RAIN_GUST_STORM_GAIN : 1F)
                            + " level=" + f3(HOWL_TIER_LEVEL[bedTier])
                            + " gHowlBed=" + f3(this.howlBedGain)
                            + " gHowlLayer=" + f3(this.howlLayerGain())
                            + " owners=" + f3(this.howlBedGain + this.howlLayerGain())
                            + " base=" + f3(loopBase(howl))
                            + " scale=" + f3(loopScale(howl))
                            + " target=" + f3(loopTarget(howl))
                            + " vol=" + f3(loopVol(howl)));

            // 树冠 CANOPY：base = CANOPY_LEVEL × gustSwell × W_leaf × windVolumeScale，
            // 素材是 L-leaf 档位族（P3①，每条两环）。只有 wooded 群系挂这一层。
            final BackgroundSoundLoop canopy = this.leafTiers.loops[bedTier][0];
            SoundDiag.layer(SoundDiag.LAYER_CANOPY,
                    "asset=" + assetName(this.leafTiers.assets[bedTier][0])
                            + " tier=" + Suffixes.TIER_ID[bedTier]
                            + " level=" + f3(CANOPY_LEVEL)
                            + " gustSwell=" + f3(gustValue)
                            + " base=" + f3(loopBase(canopy))
                            + " scale=" + f3(loopScale(canopy))
                            + " target=" + f3(loopTarget(canopy))
                            + " vol=" + f3(loopVol(canopy)));

            // 材质雨 MATERIAL：base = 1.0 × RAIN_MATERIAL_TRIM[mat]，target = vol × voiceComp
            final StringBuilder mats = new StringBuilder();
            final StringBuilder dists = new StringBuilder();
            final StringBuilder bases = new StringBuilder();
            final StringBuilder scales = new StringBuilder();
            final StringBuilder targets = new StringBuilder();
            final StringBuilder vols = new StringBuilder();
            int voices = 0;
            for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
                final BackgroundSoundLoop loop = this.rainMaterialLoops[i];
                if (loop == null)
                    continue;
                voices++;
                mats.append(voices == 1 ? "" : ",").append(this.rainMaterialMaterials[i]);
                bases.append(voices == 1 ? "" : ",").append(f3(loop.getBaseVolume()));
                scales.append(voices == 1 ? "" : ",").append(f3(loop.getScale()));
                targets.append(voices == 1 ? "" : ",").append(f3(loop.getScaleTarget()));
                vols.append(voices == 1 ? "" : ",").append(f3(loopVol(loop)));
                final var anchor = this.rainMaterialSpots[i];
                if (anchor == null)
                    dists.append(voices == 1 ? "" : ",").append("-");
                else
                    dists.append(voices == 1 ? "" : ",")
                            .append(f1(Math.sqrt((double) distanceSq(player.blockPosition(), anchor))));
            }
            SoundDiag.layer(SoundDiag.LAYER_MATERIAL,
                    "tier=" + this.rainMaterialTier
                            + " voices=" + voices + "/" + RAIN_MATERIAL_MAX_LOOPS
                            + " mats=[" + mats + "] dist=[" + dists + "] base=[" + bases
                            + "] scale=[" + scales + "] target=[" + targets + "] vol=[" + vols + "]");

            // 阵风包络 GUST：howl/canopy 用 gustSwell，床用 windSwell
            SoundDiag.layer(SoundDiag.LAYER_GUST,
                    "env=" + f3(env)
                            + " gustSwell=" + f3(gustValue)
                            + " howlFloor=" + f3(HOWL_FLOOR)
                            + " gustAmp=" + f3(gustAmp)
                            + " speedBps=" + f3(speedBps)
                            + " windLevel=" + f3(bedLevel)
                            + " windSwell=" + f3(bedSwell)
                            + " rainAmbient=" + f3(ambient));

            // 室内系数 INDOOR：INDOOR_VOLUME_SCALE 只在三个点乘上去（见 appliedAt）
            SoundDiag.layer(SoundDiag.LAYER_INDOOR,
                    "inside=" + inside
                            + " indoorScale=" + f3(INDOOR_VOLUME_SCALE)
                            + " appliedAt=[biomeAmbience,addOnMood,rainWindBed]"
                            + " rainAmbient=" + f3(ambient)
                            + " grading=" + PrecipitationIntensity.grading()
                            + " rainingNow=" + PrecipitationIntensity.rainingNow()
                            + " windSounds=" + this.config.weatherOptions.enableWindSounds
                            + " biomeSounds=" + this.config.soundOptions.enableBiomeSounds
                            + " auxSends=" + AudioUtilities.getMaxAuxSends());
        } catch (final Throwable t) {
            // 诊断绝不允许影响声音：采样炸了就报一次，然后继续跑。
            SoundDiag.sampleFailed(String.valueOf(t));
        }
    }

    /** DIAG ONLY: asset 字段在这个类里是 {@code Object}；{@code null} 打成 "-"，免得日志里出现 "null"。 */
    private static String assetName(final Object asset) {
        return asset == null ? "-" : asset.toString();
    }

    private static float loopBase(final BackgroundSoundLoop loop) {
        return loop == null ? 0F : loop.getBaseVolume();
    }

    private static float loopScale(final BackgroundSoundLoop loop) {
        return loop == null ? 0F : loop.getScale();
    }

    private static float loopTarget(final BackgroundSoundLoop loop) {
        return loop == null ? 0F : loop.getScaleTarget();
    }

    /** MC 实际会交给 OpenAL 的音量：base × scale × 类别音量 × 逐声音配置。 */
    private static float loopVol(final BackgroundSoundLoop loop) {
        if (loop == null || loop.getSound() == null)
            return 0F;
        return SoundVolumeEvaluator.getAdjustedVolume(loop);
    }

    private static String f3(final float value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String f1(final double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    @Override
    public void onConnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        clearTierBanks();
        this.rainGustNextTick = 0L;
        // 档位带迟滞 = 有状态，换世界必须清掉，否则上一局在暴雨里退出，
        // 新世界一开局就还挂在暴雨档（要降档得比阈值再低 0.06）。
        this.rainGustTier = -1;
        this.rainGustLastIndex = -1;
    }

    @Override
    public void onDisconnect() {
        clearSounds();
        this.rainWindLoop = null;
        this.rainWindAsset = null;
        clearTierBanks();
        this.rainGustNextTick = 0L;
        this.rainGustTier = -1;
        this.rainGustLastIndex = -1;
    }

    /**
     * WIND P3①：换世界时把所有档位槽与拥有权状态清掉 —— 交接进度、门控迟滞都是有状态的，
     * 上一局在暴雨里退出，新世界一开局不该还挂在满风档上。
     */
    private void clearTierBanks() {
        clearTierBank(this.bedTiers);
        clearTierBank(this.leafTiers);
        clearTierBank(this.howlTiers);
        this.howlBedGain = 0F;
        this.howlBedOwned = false;
        this.howlGateOpen = false;
        this.leafGateOpen = false;
    }

    private void handleBiomeSounds(final Player player) {
        this.workMap.clear();

        // Only gather data if the player is alive. If the player is dead, the biome sounds will cease playing.
        if (player.isAlive()) {

            final boolean biomeSounds = doBiomeSounds();

            if (biomeSounds)
                generateBiomeSounds();

            // The following will look at the PLAYER and VILLAGE biomes, two artificial biomes
            // that are used to configure effects.
            final ObjectArray<ISoundFactory> playerSounds = new ObjectArray<>();
            final BiomeInfo internalPlayerBiomeInfo = this.biomeLibrary.getBiomeInfo(SyntheticBiome.PLAYER);
            final BiomeInfo internalVillageBiomeInfo = this.biomeLibrary.getBiomeInfo(SyntheticBiome.VILLAGE);
            // The synthetic biomes may not be initialized yet (e.g. while on the main menu
            // or before the first world connect), so guard against null.
            if (internalPlayerBiomeInfo != null)
                playerSounds.addAll(internalPlayerBiomeInfo.findBiomeSoundMatches());
            if (internalVillageBiomeInfo != null)
                playerSounds.addAll(internalVillageBiomeInfo.findBiomeSoundMatches());
            playerSounds.forEach(fx -> this.workMap.put(fx, 1.0F));

            // This will cause extra spot sounds to play, like birds chirping, wolves growling, etc.
            if (biomeSounds) {
                BiomeInfo playerBiome = this.scanner.playerLogicBiomeInfo();
                handleAddOnSounds(player, playerBiome);
                if (internalPlayerBiomeInfo != null)
                    handleAddOnSounds(player, internalPlayerBiomeInfo);
                if (internalVillageBiomeInfo != null)
                    handleAddOnSounds(player, internalVillageBiomeInfo);
                handleLeafWindGust(player);
                handleRainWindBed(player);
                handleWindLayers(player);
                handleRainTexture(player);
                // handleRainMaterial is called from process() every tick.
                handleSculkClick(player);
            }
        }

        // At this point, we trigger the examination of the existing emitters list, comparing it to the
        // generated work map. Adjustments will be made accordingly.
        queueAmbientSounds();
    }

    private void handleAddOnSounds(Player player, BiomeInfo info) {
        if (info == null)
            return;
        final float indoorScale = this.scanner.isInside() ? INDOOR_VOLUME_SCALE : 1.0F;
        info.getExtraSound(SoundEventType.MOOD, RANDOM).ifPresent(s -> {
            var instance = createMoodInstance(player, s, indoorScale);
            this.audioPlayer.play(instance);
        });

        info.getExtraSound(SoundEventType.ADDITION, RANDOM).ifPresent(s -> {
            var instance = s.createAsAdditional();
            this.audioPlayer.play(instance);
        });
    }

    /**
     * Intermittent gust of wind rustling the leaves in any wooded biome. Independent of the
     * mood chance (which is shared across all mood sounds in a biome), so it never inflates
     * the frequency of bird calls etc. More likely at night. Fires once per scan interval
     * (4 ticks) with its own probability; plays a short gust from three sources spread
     * around the player at canopy height, so the wind sweeps through the treetops.
     */
    private void handleLeafWindGust(Player player) {
        if (doBiomeSounds() && !this.scanner.isInside()) {
            var biome = this.scanner.playerLogicBiomeInfo();
            if (biome != null && isWooded(biome)) {
                // Rain and snow cover the sound; skip then.
                var level = player.level();
                if (level.isRaining())
                    return;

                float chance = DayCycle.isNighttime(level) ? LEAF_WIND_NIGHT_CHANCE : LEAF_WIND_DAY_CHANCE;
                if (RANDOM.nextDouble() < chance) {
                    var factory = ContainerManager.resolve(ISoundLibrary.class)
                            .getSoundFactoryOrDefault(LEAF_WIND);
                    // Surround gust: three sources spread around the player (120 deg apart
                    // with jitter, mixed 8-16 block distances) floating at canopy height
                    // (~6-8 blocks above the ground) so the wind sweeps through the treetops.
                    playLeafSurround(player, factory, 1.0F);
                }
            }
        }
    }

    /**
     * Intermittent sculk clicking in the Deep Dark, reminiscent of sculk sensors.
     * Independent of the shared mood chance, so it never inflates other mood sounds.
     * Plays a short burst of clicks at a random spot near the player.
     */
    /**
     * A raindrop landing on a surface near the player.
     *
     * <p>Three materials, not the sixty the footstep system knows: glass, metal
     * and water. They are the ones that read instantly - a greenhouse roof, a
     * corrugated shelter, a lake - and the maintainer asked for the extraction
     * to be proven on three before it is generalised.</p>
     *
     * <p>The surface is found by looking, not by assuming. Rain lands on whatever
     * is open to the sky: the ground underfoot, but equally a metal roof
     * overhead or a pane of glass off to one side. So the search collects every
     * surface in a volume around the player that is (a) one of our materials and
     * (b) open above, then picks one of them and plays the drop there. Nothing
     * about the player's own position is assumed - which also means it keeps
     * working while flying, where "the block under the player" is air.</p>
     *
     * <p>Only materials with a clip behind them are wired up; everything else
     * returns no match. Stone, wood and leaves are all waiting on their own
     * clips, deliberately - three first.</p>
     */
    private void handleRainMaterial(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds()) {
            fadeRainMaterial();
            return;
        }
        // Gate on the envelope this layer is scaled by, not on vanilla's rain
        // ramp. rainingNow() reads Level.getRainLevel(), and the graded system
        // never writes that level - by decision - so it stays 0 for the whole of
        // a storm we drive ourselves. Measured: 48 samples over five minutes of
        // DSR-driven torrential rain, ramp=0.000 every time, rainingNow() false
        // every time, and so this layer returned at its first gate every tick
        // while PrecipitationRenderer - which gates on takeoverReady() - was
        // drawing torrential rain beside it. ambient() is what the volume is
        // computed from two lines below: if it is zero the layer is silent
        // anyway, so this gate costs nothing and now agrees with the renderer.
        if (PrecipitationIntensity.ambient() <= 0F) {
            // Rate-limited: a closed gate must be VISIBLE. The one below is
            // debug level and never reaches the log, which is why this looked
            // like "no surfaces found" for a whole session.
            if (this.tickCount.getTickCount() % 200 == 0)
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] gate closed: ambient=%.3f rainingNow=%s",
                        PrecipitationIntensity.ambient(),
                        PrecipitationIntensity.rainingNow());
            fadeRainMaterial();
            return;
        }
        // Only where rain is the thing that falls. See rainMaterialAllowedHere().
        if (!rainMaterialAllowedHere(player)) {
            fadeRainMaterial();
            return;
        }
        // Nothing to pump. The candidate set is built by the allocator, on its
        // own beat and always against the world as it is right now.
        final float intensity = PrecipitationIntensity.ambient();
        final long now = this.tickCount.getTickCount();
        // Two things make an anchored loop give its slot back, both checked
        // every tick: the block is no longer what it was, or it is no longer
        // where the listener is. Two block reads and one subtraction per slot,
        // which is nothing next to eight loops playing a scene he has left.
        // Walking the volume is the expensive part, and that stays on its own
        // beat below.
        var level = player.level();
        final var here = player.blockPosition();
        final long validStamp = this.rainMatTrace ? System.nanoTime() : 0L;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
            if (this.rainMaterialSpots[i] == null)
                continue;
            final var anchored = this.rainMaterialSpots[i];
            final long gap = distanceSq(here, anchored);
            final boolean changed = !spotStillValid(level, anchored,
                    this.rainMaterialMaterials[i]);
            final boolean left = gap > RAIN_MATERIAL_RELEASE_DIST_SQ;
            if (!changed && !left) {
                this.rainMaterialInvalidTicks[i] = 0;
                continue;
            }
            // A spot that looks wrong is given a few ticks to keep looking
            // wrong before its loop is dropped. The eleventh log has picks
            // replacing a pick from eleven ticks earlier, which is not a scene
            // changing, it is a decision changing its mind.
            final int grace = ++this.rainMaterialInvalidTicks[i];
            if (grace < RAIN_MATERIAL_INVALID_GRACE_TICKS)
                continue;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d INVALID tick=%d "
                            + "pos=(%d,%d,%d) mat=%d nowMat=%d gapSq=%d reason=%s grace=%d",
                    i, now, anchored.getX(), anchored.getY(), anchored.getZ(),
                    this.rainMaterialMaterials[i],
                    materialSound(level.getBlockState(anchored)), gap,
                    changed ? "block changed" : "left behind", grace);
            fadeMaterialLoop(i);
            // Refill at the react gap rather than waiting out the beat. This
            // slot did not come free because something went wrong; it came
            // free because the player walked somewhere else, and half a second
            // of silence where he now stands is what reads as unreliable.
            this.rainMatSlotFreed = true;
        }
        if (this.rainMatTrace) {
            final long validTook = System.nanoTime() - validStamp;
            this.rainMatValidNanos += validTook;
            if (validTook > this.rainMatValidMaxNanos)
                this.rainMatValidMaxNanos = validTook;
        }
        // Two ways into the allocator: the standing beat, and the world
        // changing underneath us. The second is what makes a block placed in
        // the rain audible in a few ticks instead of waiting out the beat;
        // it is rate-limited because building is not one update, it is forty.
        if (now - this.rainMatLastRefreshTick >= RAIN_MATERIAL_SCAN_TICKS
                || ((this.rainMatReevaluate || this.rainMatSlotFreed)
                    && now - this.rainMatLastRefreshTick >= RAIN_MATERIAL_REACT_TICKS)) {
            final long refreshStamp = this.rainMatTrace ? System.nanoTime() : 0L;
            refreshMaterialLoops(player, intensity);
            if (this.rainMatTrace) {
                final long refreshTook = System.nanoTime() - refreshStamp;
                this.rainMatRefreshCount++;
                this.rainMatRefreshNanos += refreshTook;
                if (refreshTook > this.rainMatRefreshMaxNanos)
                    this.rainMatRefreshMaxNanos = refreshTook;
            }
        }
        float t = (intensity - RAIN_MATERIAL_MIN_INTENSITY) / (1F - RAIN_MATERIAL_MIN_INTENSITY);
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        // Starting on tick one means ambient is still near zero, so bring the
        // level up over the first MIN_INTENSITY of the envelope. Without this the
        // layer arrives at FLOOR scale immediately; the loop player eases, but
        // easing towards a target that is already high is not a fade in.
        float fadeIn = intensity / RAIN_MATERIAL_MIN_INTENSITY;
        fadeIn = fadeIn < 0F ? 0F : (fadeIn > 1F ? 1F : fadeIn);
        // Round 12 note: an indoor multiplier was tried here and removed - rain on a
        // surface is NOT ambience, it is a thing happening at a place, and the layer
        // already anchors every loop to a real block face. Muffling behind a wall is the
        // occlusion system's job (see WIND-SOUNDS.md / the occlusion TODO), not a flat
        // volume cut: a flat cut would also mute rain on a window the player is standing
        // right next to, which is the one case where it should be loudest.
        final float vol = fadeIn
                * (RAIN_MATERIAL_VOLUME_FLOOR + (1F - RAIN_MATERIAL_VOLUME_FLOOR) * t);
        // Perceived level goes with the square root of the number of voices,
        // so eight loops at this volume would arrive nearly 3 dB above the four
        // this layer was tuned with. Hold the total instead: scenes of four or
        // fewer are untouched, and everything past that spends its voices on
        // being somewhere else rather than on being louder.
        int voices = 0;
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            if (this.rainMaterialLoops[k] != null)
                voices++;
        final float voiceComp = voices > RAIN_MATERIAL_TUNED_VOICES
                ? (float) Math.sqrt((double) RAIN_MATERIAL_TUNED_VOICES / (double) voices)
                : 1.0F;
        final long scaleStamp = this.rainMatTrace ? System.nanoTime() : 0L;
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            if (this.rainMaterialLoops[k] != null)
                this.rainMaterialLoops[k].setScaleTarget(vol * voiceComp);
        if (this.rainMatTrace) {
            final long scaleTook = System.nanoTime() - scaleStamp;
            this.rainMatScaleNanos += scaleTook;
            if (scaleTook > this.rainMatScaleMaxNanos)
                this.rainMatScaleMaxNanos = scaleTook;
        }
    }

    /**
     * Spread a bounded number of loops over the material surfaces in reach.
     *
     * <p>Two jobs on the same beat. First drop anything that is no longer what
     * it was: the block was broken, something was built on top of it, or the
     * tier moved. Then fill the free slots by farthest-point sampling seeded
     * with the spots already playing, so a new slot lands away from the ones
     * already sounding rather than next to them.</p>
     */
    private void refreshMaterialLoops(final Player player, final float intensity) {
        final int tier = rainMaterialTierFor(intensity, this.rainMaterialTier);
        this.rainMaterialTier = tier;

        // Validity is checked every tick by the caller; this is only the tier.
        // One loop per beat, not all of them: dropping the whole layer on a
        // tier change is a silence half a second long and eight new picks, and
        // the intensity crosses the threshold both ways whenever a storm
        // breathes. One at a time the layer changes density over a few seconds
        // instead of blinking.
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
            if (this.rainMaterialSpots[i] != null && this.rainMaterialTiers[i] != tier) {
                fadeMaterialLoop(i);
                break;
            }

        boolean free = false;
        int occupied = 0;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
            if (this.rainMaterialLoops[i] == null)
                free = true;
            else
                occupied++;
        final long diagTick = this.tickCount.getTickCount();
        this.rainMatLastRefreshTick = diagTick;
        // Consumed here rather than left for whoever reaches it next. A block
        // update arriving while a slot happens to be free never hits the
        // preemption branch, so the flag would sit set and pull every later
        // refresh in at the react gap instead of at its own beat.
        final boolean worldChanged = this.rainMatReevaluate;
        this.rainMatReevaluate = false;
        this.rainMatSlotFreed = false;
        // Read the candidate set before deciding whether to give a slot up.
        // The bail-out used to come first, so it could not tell a preemption
        // worth making from one that only costs a fade-out. It also meant
        // the scanner was never ticked while every slot was held, so the
        // world could change underneath the layer without it noticing.
        ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces =
                null;
        if (!free) {
            if (!worldChanged) {
                // DIAGNOSTIC: the allocator bails out here without ever reading
                // the candidate set, so anything that appears while all slots
                // are held stays silent for as long as they stay held.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] LOCKED tick=%d occupied=%d/%d "
                                + "- no slot is free, candidates not even read",
                        diagTick, occupied, RAIN_MATERIAL_MAX_LOOPS);
                return;
            }
            // The world changed: a block was placed or broken. Give up the
            // loop furthest from the player so the new surface can be heard -
            // a block just placed should sound, and a distant loop is the
            // cheapest to lose. Gated on a real block update, never on the
            // periodic beat, or every refresh would reshuffle the layer and
            // churn - which is the instability being fixed here.
            if (diagTick - this.rainMatLastPreemptTick < RAIN_MATERIAL_PREEMPT_COOLDOWN) {
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT-SKIP tick=%d "
                                + "- cooldown, layer left alone", diagTick);
                return;
            }
            if (surfaces == null)
                surfaces = materialSurfaces(player, worldChanged);
            if (!hasUnheardMaterial(surfaces)) {
                // Every material in reach already sounds. Dropping one would
                // trade silence in one spot for the same sound in another,
                // which reads as the layer stuttering for no reason.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT-SKIP tick=%d "
                                + "- nothing in reach that is not already playing",
                        diagTick);
                return;
            }
            this.rainMatLastPreemptTick = diagTick;
            final var origin = player.blockPosition();
            int victim = -1;
            long victimDist = -1L;
            for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
                final var sp = this.rainMaterialSpots[i];
                if (sp == null)
                    continue;
                final long ddx = sp.getX() - (long) origin.getX();
                final long ddy = sp.getY() - (long) origin.getY();
                final long ddz = sp.getZ() - (long) origin.getZ();
                final long d = ddx * ddx + ddy * ddy + ddz * ddz;
                if (d > victimDist) {
                    victimDist = d;
                    victim = i;
                }
            }
            if (victim < 0)
                return;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] PREEMPT tick=%d slot=%d dist=%d "
                            + "- world changed, furthest loop dropped",
                    diagTick, victim, victimDist);
            fadeMaterialLoop(victim);
        }

        if (surfaces == null)
            surfaces = materialSurfaces(player, worldChanged);
        final int[] perMat = new int[7];
        if (surfaces != null)
            for (int q = 0; q < surfaces.size(); q++) {
                final int m = surfaces.get(q).material();
                if (m >= 0 && m < 7)
                    perMat[m]++;
            }
        // Shape, not identity: how many surfaces, and how many of each
        // material. The set is a fresh container every beat, so comparing by
        // reference logged a scan every half second and buried the picks.
        long fingerprint = surfaces == null ? -1L : surfaces.size();
        for (int q = 0; q < 7; q++)
            fingerprint = fingerprint * 8L + perMat[q];
        if (fingerprint != this.rainMatLastFingerprint
                && diagTick - this.rainMatScanPassLogTick >= RAIN_MATERIAL_SCAN_LOG_MIN_TICKS) {
            this.rainMatLastFingerprint = fingerprint;
            this.rainMatScanPassLogTick = diagTick;
            this.rainMatScanPass++;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] scan pass=%d tick=%d surfaces=%d "
                            + "mats=[glass=%d metal=%d wood=%d log=%d stone=%d leaves=%d] "
                            + "center=(%d,%d,%d)",
                    this.rainMatScanPass, diagTick,
                    surfaces == null ? 0 : surfaces.size(),
                    perMat[0], perMat[1], perMat[3], perMat[4], perMat[5], perMat[6],
                    player.blockPosition().getX(), player.blockPosition().getY(),
                    player.blockPosition().getZ());
        }
        // Only when the shape of the allocation changes. A steady scene used to
        // write this line twice a second for as long as it rained.
        final long shape = ((long) tier * 100L + occupied) * 100000L
                + (surfaces == null ? 0 : surfaces.size());
        this.rainMatLastRefreshShape = shape;
        if (surfaces == null || surfaces.size() == 0) {
            // Nothing suitable in reach. Say so, because silence is
            // indistinguishable from "broken" without this line.
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] no material surface in reach (intensity %.2f)",
                    intensity);
            return;
        }
        final var originPos = player.blockPosition();
        int picksThisBeat = 0;
        for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++) {
            if (this.rainMaterialLoops[i] != null)
                continue;
            final var spot = pickSpread(surfaces, i, originPos);
            if (spot == null)
                break;
            final var sound = RAIN_MATERIAL_TIERS[spot.material()][tier];
            var factory = ContainerManager.resolve(ISoundLibrary.class)
                    .getSoundFactoryOrDefault(sound);
            var loop = factory.createBackgroundSoundLoopAt(spot.pos());
            loop.setVolume(RAIN_MATERIAL_VOLUME * RAIN_MATERIAL_TRIM[spot.material()]);
            loop.setScaleTarget(0F);
            this.rainMaterialLoops[i] = loop;
            this.rainMaterialAssets[i] = sound;
            this.rainMaterialSpots[i] = spot.pos();
            this.rainMaterialMaterials[i] = spot.material();
            this.rainMaterialTiers[i] = tier;
            this.rainMaterialInvalidTicks[i] = 0;
            this.audioPlayer.play(loop);
            picksThisBeat++;
            // dy is how far below the player's feet the chosen face is. A
            // surface at dy=-7 is underground, which is how a hidden cave
            // ceiling used to be heard as rain on stone in open grass.
            // near is how far away the loop will be playing from. It is the
            // number to read when the layer sounds distant or absent: it was
            // 5-7 for every pick before this round.
            // spread is how far the new anchor sits from the nearest loop
            // already sounding. Within one group it can no longer be less
            // than RAIN_MATERIAL_MIN_SPREAD_SQ, so what it reports is the
            // cross-material case and how much room the rule actually left.
            final long spreadSq = nearestAnchorDistSq(i);
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d PICK tick=%d spots=%d -> %s "
                            + "at (%d,%d,%d) mat=%d dy=%d near=%d spread=%d tier=%d intensity=%.2f",
                    i, diagTick, surfaces.size(), sound.getPath(), spot.pos().getX(),
                    spot.pos().getY(), spot.pos().getZ(), spot.material(),
                    spot.pos().getY() - originPos.getY(),
                    (int) Math.sqrt(distanceSq(originPos, spot.pos())),
                    spreadSq < 0L ? -1 : (int) Math.sqrt((double) spreadSq), tier,
                    intensity);
        }
        // Printed here, after the beat, rather than before it: most beats pick
        // nothing, and a line about nothing was four sixths of this layer's log
        // traffic and a measurable slice of its cost. A beat that did pick is
        // always printed; one that did not is rate limited, never dropped, so a
        // scene standing silently in front of him still says so.
        if (picksThisBeat > 0
                || diagTick - this.rainMatRefreshLogTick >= RAIN_MATERIAL_REFRESH_LOG_TICKS) {
            if (picksThisBeat == 0)
                this.rainMatRefreshLogTick = diagTick;
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] refresh tick=%d tier=%d occupied=%d/%d "
                            + "candidates=%d intensity=%.2f picked=%d",
                    diagTick, tier, occupied, RAIN_MATERIAL_MAX_LOOPS,
                    surfaces == null ? 0 : surfaces.size(), intensity, picksThisBeat);
        }
    }

    /**
     * Whether any material in reach is not currently sounding.
     *
     * <p>Four slots against six materials. Without this check the layer spends
     * its whole budget on whichever expanse the scan reached first, and stepping
     * from stone onto planks changes nothing you can hear.</p>
     */
    private boolean hasUnheardMaterial(final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces) {
        if (surfaces == null)
            return false;
        for (int k = 0; k < surfaces.size(); k++) {
            final int m = surfaces.get(k).material();
            boolean sounding = false;
            for (int i = 0; i < RAIN_MATERIAL_MAX_LOOPS; i++)
                if (this.rainMaterialLoops[i] != null
                        && this.rainMaterialMaterials[i] == m) {
                    sounding = true;
                    break;
                }
            if (!sounding)
                return true;
        }
        return false;
    }

    /** Squared distance between two blocks, in blocks squared. */
    private static long distanceSq(final net.minecraft.core.BlockPos a,
                                   final net.minecraft.core.BlockPos b) {
        final long dx = a.getX() - (long) b.getX();
        final long dy = a.getY() - (long) b.getY();
        final long dz = a.getZ() - (long) b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Bookkeeping for the cost line, and the line itself.
     *
     * <p>Reported every {@code RAIN_MATERIAL_PERF_LOG_TICKS} ticks. {@code per
     * tick} is this layer alone, averaged over the ticks that actually ran it,
     * and the worst tick in the window sits beside it - an average hides
     * exactly the shape that turns up in the low frames. {@code cpu} is the
     * share of the wall clock those ticks consumed: a tick is 50 ms at twenty
     * ticks a second, so it reads directly as what this layer asks of the
     * render thread.</p>
     */
    private void rainMaterialPerf(final long nanos, final long restNanos) {
        this.rainMatPerfTicks++;
        this.rainMatPerfNanos += nanos;
        if (nanos > this.rainMatPerfMaxNanos)
            this.rainMatPerfMaxNanos = nanos;
        if (restNanos > this.rainMatRestMaxNanos)
            this.rainMatRestMaxNanos = restNanos;
        final long now = this.tickCount.getTickCount();
        if (now - this.rainMatPerfLogTick < RAIN_MATERIAL_PERF_LOG_TICKS
                || this.rainMatPerfTicks == 0L)
            return;
        final double budget = (double) this.rainMatPerfTicks * 50000000.0;
        this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] perf ticks=%d  per tick avg=%.3f ms worst=%.3f ms"
                        + "  scan n=%d avg=%.3f worst=%.3f"
                        + "  refresh n=%d avg=%.3f worst=%.3f"
                        + "  valid worst=%.3f  scale worst=%.3f  rest worst=%.3f ms"
                        + "  cpu=%.3f%% of wall",
                this.rainMatPerfTicks,
                this.rainMatPerfNanos / 1000000.0 / (double) this.rainMatPerfTicks,
                this.rainMatPerfMaxNanos / 1000000.0,
                this.rainMatScanCount,
                this.rainMatScanCount == 0 ? 0.0
                        : this.rainMatScanNanos / 1000000.0 / (double) this.rainMatScanCount,
                this.rainMatScanMaxNanos / 1000000.0,
                this.rainMatRefreshCount,
                this.rainMatRefreshCount == 0 ? 0.0
                        : this.rainMatRefreshNanos / 1000000.0 / (double) this.rainMatRefreshCount,
                this.rainMatRefreshMaxNanos / 1000000.0,
                this.rainMatValidMaxNanos / 1000000.0,
                this.rainMatScaleMaxNanos / 1000000.0,
                this.rainMatRestMaxNanos / 1000000.0,
                this.rainMatPerfNanos / budget * 100.0);
        this.rainMatPerfLogTick = now;
        this.rainMatPerfTicks = 0L;
        this.rainMatPerfNanos = 0L;
        this.rainMatPerfMaxNanos = 0L;
        this.rainMatScanCount = 0;
        this.rainMatScanNanos = 0L;
        this.rainMatScanMaxNanos = 0L;
        this.rainMatValidNanos = 0L;
        this.rainMatValidMaxNanos = 0L;
        this.rainMatRefreshCount = 0;
        this.rainMatRefreshNanos = 0L;
        this.rainMatRefreshMaxNanos = 0L;
        this.rainMatScaleNanos = 0L;
        this.rainMatScaleMaxNanos = 0L;
        this.rainMatRestMaxNanos = 0L;
    }

    /**
     * Squared distance from one anchor to the nearest other one, or -1 when
     * it is the only loop sounding. Logged with every pick so the next round
     * can be measured the way this one was.
     */
    private long nearestAnchorDistSq(final int slot) {
        final var pos = this.rainMaterialSpots[slot];
        if (pos == null)
            return -1L;
        long best = -1L;
        for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
            if (j == slot || this.rainMaterialSpots[j] == null)
                continue;
            final long d = distanceSq(pos, this.rainMaterialSpots[j]);
            if (best < 0L || d < best)
                best = d;
        }
        return best;
    }

    /** Still the same material, and still open to the sky? */
    private static boolean spotStillValid(final net.minecraft.world.level.Level level,
                                          final net.minecraft.core.BlockPos pos,
                                          final int material) {
        // Ask exactly what the scanner asked. Two tests that can disagree are
        // how a loop gets anchored, killed on the next tick, anchored again - 
        // the cycle that ran six times on one coordinate in the third log.
        return org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                .rainReaches(level, pos)
                && materialSound(level.getBlockState(pos)) == material;
    }

    /**
     * Where to put the next loop: away from the loops already playing, and not
     * far from the player.
     *
     * <p>Farthest-point sampling alone spreads the loops over whatever the scan
     * reached, which is what stops a large expanse from being one source - but
     * a loop maximally far from the others sits on the rim of the box, six
     * blocks off and correspondingly quiet. Sum of many: the sixth log had all
     * eight picks between five and seven blocks out and none of them under his
     * feet, which is what "not sensitive" sounds like.</p>
     */
    private org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface
            pickSpread(final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> surfaces, final int slot,
                       final net.minecraft.core.BlockPos origin) {
        org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface best = null;
        // Not -1.0. Both terms can be negative now, and a candidate scoring
        // below the seed would otherwise be rejected as "no surfaces in reach"
        // and quietly leave the slot empty.
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < surfaces.size(); k++) {
            final var s = surfaces.get(k);
            boolean clash = false;
            double nearest = -1.0;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
                if (j == slot || this.rainMaterialSpots[j] == null)
                    continue;
                final var other = this.rainMaterialSpots[j];
                if (other.equals(s.pos())) {
                    clash = true;
                    break;
                }
                final int dx = other.getX() - s.pos().getX();
                final int dy = other.getY() - s.pos().getY();
                final int dz = other.getZ() - s.pos().getZ();
                final double d = dx * dx + dy * dy + dz * dz;
                if (nearest < 0.0 || d < nearest)
                    nearest = d;
            }
            if (clash)
                continue;
            // Two loops of one group have to be RAIN_MATERIAL_MIN_SPREAD_SQ
            // apart. The cap below already stops a third and a fourth copy;
            // this stops the second copy from landing on top of the first,
            // which is what the cap could not see. See the constant.
            final int group = RAIN_MATERIAL_GROUP[s.material()];
            boolean tooClose = false;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++) {
                if (j == slot || this.rainMaterialSpots[j] == null)
                    continue;
                if (RAIN_MATERIAL_GROUP[this.rainMaterialMaterials[j]] != group)
                    continue;
                if (distanceSq(this.rainMaterialSpots[j], s.pos())
                        < RAIN_MATERIAL_MIN_SPREAD_SQ) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose)
                continue;
            // One group is allowed RAIN_MATERIAL_MAX_PER_GROUP loops and no
            // more, and planks and logs are one group - see the constant.
            // This is what keeps a large expanse from being several copies of
            // one clip stacked on each other.
            int sameGroup = 0;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS; j++)
                if (j != slot && this.rainMaterialLoops[j] != null
                        && RAIN_MATERIAL_GROUP[this.rainMaterialMaterials[j]] == group)
                    sameGroup++;
            if (sameGroup >= RAIN_MATERIAL_MAX_PER_GROUP)
                continue;
            // Material before distance: something no loop is playing yet is
            // lifted past any distance this box can express, which makes the
            // one loop behave like two rounds - fresh materials first, then
            // distance to order whatever is left. Without it four slots land
            // four times on the largest expanse and the planks ten blocks
            // away never get to sound.
            boolean fresh = true;
            for (int j = 0; j < RAIN_MATERIAL_MAX_LOOPS && fresh; j++)
                if (this.rainMaterialLoops[j] != null
                        && this.rainMaterialMaterials[j] == s.material())
                    fresh = false;
            // Trade being away from the loops already playing against being
            // next to the player. Nothing anchored yet: -toPlayer makes the
            // first loop land on the surface he is standing at, which used to
            // be a coin toss over every candidate in the box.
            final long pdx = s.pos().getX() - (long) origin.getX();
            final long pdy = s.pos().getY() - (long) origin.getY();
            final long pdz = s.pos().getZ() - (long) origin.getZ();
            final double toPlayer = pdx * pdx + pdy * pdy + pdz * pdz;
            // Saturating, not unbounded - see RAIN_MATERIAL_SPREAD_CAP_SQ.
            final double spread = nearest < 0.0 ? 0.0
                    : Math.min(nearest, RAIN_MATERIAL_SPREAD_CAP_SQ);
            final double base = nearest < 0.0
                    ? -toPlayer
                    : spread - RAIN_MATERIAL_PROXIMITY_WEIGHT * toPlayer;
            final double score = fresh ? base + RAIN_MATERIAL_NEW_MATERIAL_BIAS : base;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void fadeMaterialLoop(final int k) {
        if (this.rainMaterialLoops[k] != null) {
            final var gone = this.rainMaterialSpots[k];
            this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] slot=%d FADE tick=%d pos=%s mat=%d",
                    k, this.tickCount.getTickCount(),
                    gone == null ? "none" : gone.toShortString(),
                    this.rainMaterialMaterials[k]);
            this.rainMaterialLoops[k].setScaleTarget(0F);
            this.rainMaterialLoops[k] = null;
            this.rainMaterialAssets[k] = null;
            this.rainMaterialSpots[k] = null;
        }
    }

    private void fadeRainMaterial() {
        for (int k = 0; k < RAIN_MATERIAL_MAX_LOOPS; k++)
            fadeMaterialLoop(k);
    }

    /**
     * Whether rain landing on a surface is a thing that can happen here.
     *
     * <p>Eleventh report: savanna, desert, badlands and every snowy biome were
     * playing this layer, and none of them should. One question covers all
     * four - ask what falls in this biome, and play only where the answer is
     * rain. Desert, badlands and savanna carry {@code has_precipitation:
     * false} in the game's own biome data (read out of
     * {@code data/minecraft/worldgen/biome/*.json} to be sure, rather than
     * from memory), so they answer NONE; a freezing biome answers SNOW.
     * Neither of those is rain on a roof.</p>
     *
     * <p>Asked of the seasonal handler and not of the biome directly, the way
     * the rest of the mod asks it, so a season that makes a temperate biome
     * cold is heard as snow and not as rain. Cached, because it is asked every
     * tick and the answer is a biome lookup plus a temperature read.</p>
     */
    private boolean rainMaterialAllowedHere(final Player player) {
        if (!this.config.weatherOptions.enableMaterialRainSounds)
            return false;
        final long now = this.tickCount.getTickCount();
        if (now - this.rainMatBiomeCheckTick >= RAIN_MATERIAL_BIOME_CHECK_TICKS) {
            this.rainMatBiomeCheckTick = now;
            final var phase = org.orecruncher.dsurround.lib.seasons.SeasonManager.HANDLER
                    .getPrecipitationAt(player.blockPosition());
            this.rainMatBiomeAllows =
                    phase == net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
            if (!this.rainMatBiomeAllows && now - this.rainMatBiomeLogTick >= 200L) {
                this.rainMatBiomeLogTick = now;
                final var at = player.blockPosition();
                // Rate-limited, but it must be visible: a gate that closes
                // silently is indistinguishable from a broken feature.
                this.logger.debug(Configuration.Flags.RAIN_MATERIAL, "[RAIN-MAT] biome gate closed: precipitation=%s "
                                + "at (%d,%d,%d) - this biome does not get rain",
                        phase == null ? "?" : phase.name(),
                        at.getX(), at.getY(), at.getZ());
            }
        }
        return this.rainMatBiomeAllows;
    }

    /**
     * Which density tier the intensity asks for, remembering which one is
     * playing so a threshold can be crossed back without a fight.
     *
     * <p>Without this the tier follows the intensity exactly, and the
     * eleventh log has the intensity sitting at 0.50 and at 1.00 with the
     * heavy threshold at 0.70 between them.</p>
     */
    private static int rainMaterialTierFor(final float intensity, final int current) {
        final float h = RAIN_MATERIAL_TIER_HYSTERESIS;
        if (current < 1)
            return intensity >= RAIN_MATERIAL_TIER_HEAVY ? 2
                    : (intensity >= RAIN_MATERIAL_TIER_MEDIUM ? 1 : 0);
        if (current == 1) {
            if (intensity >= RAIN_MATERIAL_TIER_HEAVY)
                return 2;
            return intensity < RAIN_MATERIAL_TIER_MEDIUM - h ? 0 : 1;
        }
        if (intensity >= RAIN_MATERIAL_TIER_HEAVY - h)
            return 2;
        return intensity >= RAIN_MATERIAL_TIER_MEDIUM ? 1 : 0;
    }

    /**
     * The material surfaces in reach, walked now.
     *
     * <p>Not a scan in flight: this is every candidate inside the box around
     * the player's current position, and nothing carries it from one tick to
     * the next. It costs a scan on the allocated beat and nothing anywhere
     * else - and it only costs that while it is raining, because the layer
     * stops calling it the moment the rain does.</p>
     */
    private ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface>
            materialSurfaces(final Player player, final boolean worldChanged) {
        final long now = this.tickCount.getTickCount();
        final var pos = player.blockPosition();
        // Walking the volume is the one real cost this layer has, and it was
        // being paid every beat even when the player had not moved and nothing
        // in the world had changed. A set cannot change while both of those are
        // true, so it is kept for a second. A move or a block update walks it
        // again immediately.
        if (!worldChanged && this.rainMatScanCache != null
                && pos.equals(this.rainMatScanAt)
                && now - this.rainMatScanTick < RAIN_MATERIAL_SCAN_CACHE_TICKS)
            return (ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface>) this.rainMatScanCache;
        final long stamp = this.rainMatTrace ? System.nanoTime() : 0L;
        final ObjectArray<org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner.Surface> result =
                org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                        .scan(player.level(), pos);
        if (this.rainMatTrace) {
            final long took = System.nanoTime() - stamp;
            this.rainMatScanCount++;
            this.rainMatScanNanos += took;
            if (took > this.rainMatScanMaxNanos)
                this.rainMatScanMaxNanos = took;
        }
        this.rainMatScanCache = result;
        this.rainMatScanAt = pos;
        this.rainMatScanTick = now;
        return result;
    }

    /**
     * Which material this block is, or -1 if it is none of them or that one is
     * switched off - the scanner's answer, not a copy of it.
     */
    private static int materialSound(final net.minecraft.world.level.block.state.BlockState state) {
        // Deliberately not a second copy of the classification. The scanner
        // decides what a block is and whether that material is switched on;
        // this per-tick check used to carry its own answer, and two answers
        // to one question is how the third log got a spot that was picked,
        // killed on the next tick and picked again, six times on the same
        // coordinate. A material cut in MATERIAL_ENABLED now reads -1 here
        // too, so a loop of it already sounding fails this check and fades.
        return org.orecruncher.dsurround.processing.scanner.MaterialSurfaceScanner
                .classify(state);
    }

    private void handleSculkClick(Player player) {
        if (doBiomeSounds()) {
            var biome = this.scanner.playerLogicBiomeInfo();
            if (biome != null && DEEP_DARK_BIOME.equals(biome.getBiomeId())
                    && RANDOM.nextDouble() < SCULK_CLICK_CHANCE) {
                var factory = ContainerManager.resolve(ISoundLibrary.class)
                        .getSoundFactoryOrDefault(SCULK_CLICK);
                var offset = MathStuff.randomPoint(MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
                var instance = factory.createAtLocation(player.getEyePosition().add(offset), dsBiomeVolume());
                this.audioPlayer.play(instance);
            }
        }
    }

/**
 * WIND P3①：一个档位族的槽位表 —— {@code [档][环]} 各一条 <b>static</b> 循环。
 *
 * <p>槽是懒建的：只有权重 &gt; 0 的档才有循环，所以跨档时从 2 条涨到 4 条、交叉区一过
 * 又落回 2 条。素材本身记住了"这条槽现在是谁"，换素材时旧循环只被叫停、由它自己走完
 * 淡出，所以换族（开阔 ↔ 林地）不会把一条波形从中切断。</p>
 *
 * <p>权重写进 {@code setVolume}（基础音量）而不是 {@code setScaleTarget}：
 * {@code BackgroundSoundLoop.scale} 每 tick 只走 0.033，拿它做交叉会让 g₁+g₂ 在 3 s 的
 * 窗里偏离 1 好几分贝；基础音量当 tick 生效。</p>
 */
private static final class TierBank {
    final BackgroundSoundLoop[][] loops;
    final Object[][] assets;

    TierBank(final int tiers, final int rings) {
        this.loops = new BackgroundSoundLoop[tiers][rings];
        this.assets = new Object[tiers][rings];
    }
}

/**
 * WIND P3①（§3.10.5）：档位权重，<b>等增益</b>，和恒等于 1。
 *
 * <pre>
 *   L   w ∈ [0.00, 0.30]   完全          1     0     0
 *   L→M w ∈ (0.30, 0.50)   等增益交叉    1−u   u     0     u = (w − 0.30) / 0.20
 *   M   w ∈ [0.50, 0.65]   完全          0     1     0
 *   M→S w ∈ (0.65, 0.85)   等增益交叉    0     1−u   u     u = (w − 0.65) / 0.20
 *   S   w ∈ [0.85, 1.00]   完全          0     0     1   ← 同时是"床换持续呼啸"的门限
 * </pre>
 *
 * <p><b>同族用等增益、异素材用等功率</b>（§3.10.5 的两行表格，不能混用）：同族档位来自
 * 同一条母带、同一个窗，只差零相位滤波与增益 ⇒ <b>相位锁定</b>，等增益给出恒定的合成
 * 幅度；等功率在中点的凸起实测 +2.24 ~ +3.01 dB（附录 D.3），那就是"打嗝"。床素材 →
 * 持续呼啸是两份互不相关的录音（实测 xcorr 0.010 ~ 0.146），那里走 {@code cos/sin}
 * （见 {@link #bedOwnMaterialGain()}）。</p>
 */
private static void tierWeights(final float w, final float[] out) {
    out[0] = 0F;
    out[1] = 0F;
    out[2] = 0F;
    if (w < TIER_LM_LO) {
        out[0] = 1F;
        return;
    }
    if (w < TIER_LM_HI) {
        final float u = (w - TIER_LM_LO) / (TIER_LM_HI - TIER_LM_LO);
        out[0] = 1F - u;
        out[1] = u;
        return;
    }
    if (w < TIER_MS_LO) {
        out[1] = 1F;
        return;
    }
    if (w < TIER_MS_HI) {
        final float u = (w - TIER_MS_LO) / (TIER_MS_HI - TIER_MS_LO);
        out[1] = 1F - u;
        out[2] = u;
        return;
    }
    out[2] = 1F;
}

/** 权重最大的那一档。只用于诊断报"现在是哪一档"（交叉区报正在变响的那一档）。 */
private static int argMax(final float[] weights) {
    int best = 0;
    for (int i = 1; i < weights.length; i++)
        if (weights[i] > weights[best])
            best = i;
    return best;
}

/**
 * 把一族档位按权重铺进槽位。
 *
 * @param tierLevel 逐档的额外电平倍率；{@code null} = 三档共用一个电平
 *                  （床与叶层的档差在素材里，呼啸族的电平表见 {@link #HOWL_TIER_LEVEL}）
 */
private void renderTierBank(final TierBank bank, final ResourceLocation[][] family, final float w,
                            final float gain, final float[] tierLevel) {
    tierWeights(w, this.tierWeightScratch);
    for (int t = 0; t < this.tierWeightScratch.length; t++) {
        final float level = (tierLevel == null ? 1F : tierLevel[t]) * gain * this.tierWeightScratch[t];
        for (int r = 0; r < Suffixes.RING_ID.length; r++)
            renderTier(bank, t, r, family[t][r], level);
    }
}

/** 挂上（或换掉）一条档位循环，并把增益写进基础音量。 */
private void renderTier(final TierBank bank, final int tier, final int ring, final ResourceLocation asset,
                        final float gain) {
    BackgroundSoundLoop loop = bank.loops[tier][ring];
    if (loop != null && !asset.equals(bank.assets[tier][ring])) {
        loop.setScaleTarget(0F);
        loop = null;
    }
    if (gain <= 0.0005F) {
        if (loop != null) {
            loop.setScaleTarget(0F);
            bank.loops[tier][ring] = null;
            bank.assets[tier][ring] = null;
        }
        return;
    }
    if (loop == null) {
        loop = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactoryOrDefault(asset)
                .createBackgroundSoundLoop();
        // scale 只负责这一条自己的淡入，全部权重都在基础音量里。
        loop.setScaleTarget(1F);
        bank.loops[tier][ring] = loop;
        bank.assets[tier][ring] = asset;
        this.audioPlayer.play(loop);
    }
    loop.setVolume(gain);
}

/** 让一族档位整族淡出：循环自己走到 0 后停止。 */
private void fadeTierBank(final TierBank bank) {
    for (final BackgroundSoundLoop[] row : bank.loops)
        for (int r = 0; r < row.length; r++)
            if (row[r] != null) {
                row[r].setScaleTarget(0F);
                row[r] = null;
            }
    for (final Object[] row : bank.assets)
        java.util.Arrays.fill(row, null);
}

/** 换世界时清空一族档位槽。 */
private void clearTierBank(final TierBank bank) {
    for (final BackgroundSoundLoop[] row : bank.loops)
        java.util.Arrays.fill(row, null);
    for (final Object[] row : bank.assets)
        java.util.Arrays.fill(row, null);
}

/**
 * WIND P3①（§3.10.2 要点 3）：呼啸的拥有权分配器。
 *
 * <pre>
 *   g_howl_bed + g_howl_layer ≡ 1.0     ← 等增益再分配，不是两条各自淡入淡出
 * </pre>
 *
 * <p>只有<b>一条</b>持续呼啸信号（{@link #HOWL_TIERS} 那一族），两个增益乘在它上面。
 * 和恒等于 1 就是"任何时刻只有一个呼啸拥有者"的可判定形式：代码里不存在第二条呼啸
 * 路径，也就不可能出现 T3b 那种 max 1.4142 = +3.01 dB 的双呼啸。交接窗内两条的合计
 * 增益因此是常数 1.0000（T3 实测 min = max = 1.0000）。</p>
 *
 * <p>判据（§3.10.1 规则表 A 第 4 列）：只有<b>没有群系自带风素材</b>的预设才换 ——
 * ALPINE_PEAK / 沙漠这类有自带素材的，任何风级下床都是它自己的素材，呼啸只出现在层上。</p>
 */
private void updateHowlOwnership(final boolean active, final float level, final boolean hasOwnAsset) {
    final boolean want = active && !hasOwnAsset
            && gate(this.howlBedOwned, level, HOWL_BED_MIN_W, HOWL_BED_HYSTERESIS);
    this.howlBedOwned = want;
    final float goal = want ? 1F : 0F;
    // SCAN_INTERVAL tick 走一步，所以交接恰好铺满 HOWL_HANDOFF_S 秒。
    final float step = HOWL_HANDOFF_S <= 0F ? 1F : (SCAN_INTERVAL / 20F) / HOWL_HANDOFF_S;
    if (this.howlBedGain < goal)
        this.howlBedGain = Math.min(goal, this.howlBedGain + step);
    else if (this.howlBedGain > goal)
        this.howlBedGain = Math.max(goal, this.howlBedGain - step);
}

/** g_howl_layer = 1 − g_howl_bed，见 {@link #updateHowlOwnership}。和恒等于 1。 */
private float howlLayerGain() {
    return 1F - this.howlBedGain;
}

/**
 * 床自己的素材对持续呼啸的退让：<b>等功率 {@code cos}</b>。
 *
 * <p>床素材与呼啸是两份互不相关的录音，等增益会在中点掉 3 dB（§3.10.5 中段），所以走
 * {@code g_bed² + g_howl_bed² = 1}。它与 {@code g_howl_bed + g_howl_layer = 1} 是两条
 * 不同的曲线：前者管"床的素材 vs 呼啸"，后者管"呼啸归谁"，不能混用。</p>
 */
private float bedOwnMaterialGain() {
    return (float) Math.cos(this.howlBedGain * Math.PI * 0.5D);
}

/**
 * The gust envelope, compressed for a gain rather than for an angle.
 *
 * <p>This is the same {@code WindField} envelope that leans the rain into the wind, read on the
 * same game clock. That is the whole point: until now the ear heard gusts on one schedule (a
 * one-shot clip every 18-70 seconds) and the eye saw them on another (this envelope), so neither
 * agreed with anything else going on. One source, two outlets.</p>
 */
private static float gustSwell(final Player player) {
    final float env = WindField.envelopeAt(player.level().getGameTime(), 0F);
    final float e = env < 0F ? 0F : (env > 1F ? 1F : env);
    return HOWL_FLOOR + (1F - HOWL_FLOOR) * e;
}

/**
 * The sustained layers above the bed: the howl, and the canopy answering it.
 *
 * <p>Both are loops whose volume is the gust envelope, so a storm is continuous and its size
 * changes, instead of quiet-quiet-LOUD-quiet at a fixed interval. The howl starts at medium
 * rain by the same tiering the one-shot path uses; below that the bed alone is the wind, because
 * a running howl under drizzle reads as weather that is not there.</p>
 *
 * <p>Only wooded ground gets the canopy layer, and the decision is the same trait set
 * {@code isWooded} already answers - wind is not leaves where nothing is rustling.</p>
 */
    private void handleWindLayers(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds()
                || !this.config.weatherOptions.enableWindSounds || this.scanner.isInside()) {
            fadeTierBank(this.howlTiers);
            fadeTierBank(this.leafTiers);
            return;
        }
        final var info = this.scanner.playerLogicBiomeInfo();
        final WindProfile profile = info == null ? null : WindProfile.resolve(info.getTraits());
        final boolean wooded = info != null && isWooded(info);
        // 覆盖度：群系特征给的那一级（1/0）。设计 §9 决策 8 的 (b) 本地树冠扫描是后续项。
        // 注意后果：ALPINE_PEAK 的 canopyResponse = 0.35 在"非有树群系"里乘上 0 之后
        // 恒为 0，也就是它现在没有叶层 —— 本地扫描落地后那 0.35 才会生效。
        // 这不是 bug，是 coverage 还只有二值那一级的直接结果。
        final float coverage = wooded ? 1F : 0F;
        final float canopyResponse = profile == null ? 0F : profile.canopyResponse();
        // 一个权重把二值规则和 canopyResponse 统一起来：有树叶 -> 叶声层，无树叶 -> 呼啸层。
        // （ALPINE_PEAK 的 0.35 要等本地树冠扫描，见上面 coverage 那一行。）
        final float wLeaf = canopyResponse * coverage;
        final float wHowl = 1F - wLeaf;
        final float level = windLevel();
        final float swell = gustSwell(player);
        final float storm = player.level().isThundering() ? RAIN_GUST_STORM_GAIN : 1F;
        final float volumeScale = windVolumeScale();

        // 呼啸：门控挂在【风】上，静风时整层到 0（可以真的不存在）。强风时拥有权搬到
        // 床槽（§3.10.2 要点 3），所以这里乘的是 g_howl_bed + g_howl_layer —— 写出来
        // 是为了让"只有一个呼啸拥有者"在代码里可读、可审、可被 -Ddsr.sound.diag 诊断。
        this.howlGateOpen = gate(this.howlGateOpen, level, HOWL_WIND_MIN, HOWL_GATE_HYSTERESIS);
        if (this.howlGateOpen && wHowl > 0F) {
            final float owners = this.howlBedGain + this.howlLayerGain();
            renderTierBank(this.howlTiers, HOWL_TIERS, level,
                    swell * storm * wHowl * volumeScale * owners, HOWL_TIER_LEVEL);
        } else {
            fadeTierBank(this.howlTiers);
        }

        // 叶声层：有覆盖度、床素材不自带叶声、风过门限，三条同时成立才存在。
        // 门控同样挂在风上（旧口径挂在降水 tier 上，"小雨 / 雨后一阵大风带树叶响"
        // 因此永远不可能发生）。P3① 起用的是 L-leaf 档位族（每条两环，互质同时播）。
        final float leafMin = profile == null ? LEAF_WIND_MIN : profile.leafWindMin();
        this.leafGateOpen = gate(this.leafGateOpen, level, leafMin, LEAF_GATE_HYSTERESIS);
        if (coverage > 0F && !this.bedHasLeaves(profile) && this.leafGateOpen) {
            renderTierBank(this.leafTiers, LEAF_TIERS, level,
                    CANOPY_LEVEL * swell * wLeaf * volumeScale, null);
        } else {
            fadeTierBank(this.leafTiers);
        }
    }

    /**
     * 带滞回的门控：开了要掉到 {@code min - hysteresis} 以下才关。
     */
    private static boolean gate(final boolean open, final float level, final float min, final float hysteresis) {
        return open ? level >= min - hysteresis : level >= min;
    }

    /**
     * 当前（或即将使用的）床素材是不是自带叶声。
     *
     * <p>两个来源，配置优先：{@code WindProfile.assetHasLeaves} 是显式声明；没有声明时
     * 用谱质心兜底（&gt; {@link #ASSET_LEAF_CENTROID_HZ}）。这一条在本包里很实在：有树群系的
     * 默认床素材就是 cinetony 的树叶录音（{@code biome.wind.plains}，谱质心 4105 Hz），不跳过叶层就是
     * <b>同一份树叶叠两遍</b>（T2b 对 T2c）。</p>
     */
    private boolean bedHasLeaves(final WindProfile profile) {
        if (profile != null && profile.assetHasLeaves())
            return true;
        final var info = this.scanner.playerLogicBiomeInfo();
        // P3① 起，没有自带素材的群系用的是档位族床（BED_TIERS_*，纯风，质心 0），
        // 不再是 WIND_GENERIC（cinetony 的树叶录音，质心 4105 Hz）。所以有树群系的默认床
        // 不再被判为"自带叶声"，叶层照叠 —— 叶子从叶层来，不从床来（§3.10.1 优先级 2）。
        final Object bedAsset = this.rainWindAsset != null ? this.rainWindAsset
                : (info != null && isWooded(info) ? BED_TIERS_WOOD[0][0] : BED_TIERS_OPEN[0][0]);
        return windAssetCentroid(bedAsset) > ASSET_LEAF_CENTROID_HZ;
    }

    /**
     * 已知风素材的谱质心，Hz。实测值见设计附录 D.1（本包床档位 194~359 Hz、
     * {@code wind_open} 444 Hz、cinetony 树叶录音 {@code wind_plains} 4105 Hz）。表外的素材按 0 处理 ——
     * “不知道”不等于“有叶子”：误判成有叶会把叶层整层删掉，而漏判的代价只是最多 +1.6 dB 的重复。
     */
    private static float windAssetCentroid(final Object asset) {
        final String name = assetName(asset);
        if (name.endsWith("biome.wind.plains"))
            return 4105F;
        if (name.endsWith("biome.wind.open"))
            return 444F;
        if (name.endsWith("biome.wind"))
            return 250F;
        return 0F;
    }

    /**
     * The rain wind bed: a continuous, non-attenuated loop whose volume tracks
     * the graded intensity. A biome with its own wind acoustic uses that asset;
     * everywhere else uses the generic wind, gated by a minimum intensity (a
     * drizzle on the plains should not howl, and the source stays free). The
     * bed follows the ungated intensity - wind is an attribute of the storm,
     * not of where the drops land, so it keeps blowing under a roof, muffled.
     */
    private void handleRainWindBed(final Player player) {
        final boolean active = PrecipitationIntensity.grading() && doBiomeSounds()
                && this.config.weatherOptions.enableWindSounds;
        final float intensity = PrecipitationIntensity.ambient();
        ResourceLocation wind = null;
        float trim = 1F;
        var info = this.scanner.playerLogicBiomeInfo();
        if (info != null) {
            for (var factory : info.findBiomeSoundMatches()) {
                if (isWindFactory(factory)) {
                    wind = factory.getLocation();
                    trim = windTrim(factory);
                    break;
                }
            }
        }
        // WIND：床的大小改由 WindField 的风速决定，不再直接用降水强度 —— 风速里
        // 已经折进了强度、本场阵风幅度、群系和季节，所以耳朵听到的风与眼睛看到的
        // 雨幕倾斜是同一样东西的两个出口。swell 继续在这个 level 上起呼吸作用。
        final float bedLevel = windLevel();
        // 拥有权先算：同一个 tick 里 handleWindLayers 要读 g_howl_layer（调用顺序见
        // handleBiomeSounds：handleRainWindBed 在 handleWindLayers 之前）。
        updateHowlOwnership(active, bedLevel, wind != null);
        if (!active) {
            fadeRainWind();
            fadeTierBank(this.bedTiers);
            return;
        }
        if (wind != null) {
            // §3.10.1 规则表 A 优先级 1：群系自带素材优先，档位只作用于默认床。
            fadeTierBank(this.bedTiers);
            ownAssetBed(wind, trim, bedLevel, intensity);
            return;
        }
        fadeRainWind();
        defaultBed(info, bedLevel, intensity);
    }

    /**
     * 群系自带风素材的床（§3.10.1 规则表 A 优先级 1）：P2 的原样保留 —— 一条流式循环、
     * 按素材自己的响度做 {@link #windTrim}、按风等级与慢起伏呼吸。
     *
     * <p>The gate applies to both paths now. It used to guard only this fallback branch, so the
     * biomes that carry their own asset were already blowing while the storm was still
     * climbing out of zero - the complaint was "it starts before the rain does".
     * (They also double up against generateBiomeSounds; see the skip there.)</p>
     */
    private void ownAssetBed(final ResourceLocation wind, final float trim, final float bedLevel,
                             final float intensity) {
        float target = intensity <= RAIN_WIND_MIN_INTENSITY ? 0F : bedLevel;
        if (this.scanner.isInside())
            target *= INDOOR_VOLUME_SCALE;
        target *= windSwell(this.tickCount.getTickCount()) * windVolumeScale();
        if (target <= 0.005F) {
            fadeRainWind();
            return;
        }
        if (this.rainWindLoop == null || !wind.equals(this.rainWindAsset)) {
            // Crossfade on an asset change: the old loop keeps playing until its
            // scale reaches zero and stops itself, the new one eases in.
            fadeRainWind();
            var factory = ContainerManager.resolve(ISoundLibrary.class)
                    .getSoundFactoryOrDefault(wind);
            this.rainWindLoop = factory.createBackgroundSoundLoop();
            this.rainWindLoop.setVolume(trim * RAIN_WIND_BED_GAIN);
            this.rainWindAsset = wind;
            this.rainWindLoop.setScaleTarget(target);
            this.audioPlayer.play(this.rainWindLoop);
        } else {
            this.rainWindLoop.setScaleTarget(target);
        }
    }

    /**
     * WIND P3①：默认床 = 档位族（§3.10.4）。选族看有没有树冠，选档看 {@code windLevel}。
     *
     * <p>强风时床换成持续呼啸（{@code g_howl_bed → 1}），所以档位族在这一段按
     * <b>等功率</b>退让 —— 见 {@link #bedOwnMaterialGain()}。到 {@code g_howl_bed = 1}
     * 时档位族整族消失，床上只剩呼啸：这就是"床换成持续呼啸声"。</p>
     *
     * <p>WASTELAND 不需要分支：它的 {@code floorLevel = peakCeiling = 0}，所以
     * {@code bedLevel ≡ 0} ⇒ {@code gain = 0} ⇒ 整族不存在（§3.10.1 优先级 0）。</p>
     */
    private void defaultBed(final BiomeInfo info, final float bedLevel, final float intensity) {
        float target = intensity <= RAIN_WIND_MIN_INTENSITY ? 0F : bedLevel;
        if (this.scanner.isInside())
            target *= INDOOR_VOLUME_SCALE;
        target *= windSwell(this.tickCount.getTickCount()) * windVolumeScale();
        final float gain = BED_TIER_GAIN * target * bedOwnMaterialGain();
        if (gain <= 0.0005F) {
            fadeTierBank(this.bedTiers);
            return;
        }
        // §3.10.1 优先级 2 / 3：两张默认床都是纯风（一点叶声都没有），差别只在 EQ 倾向，
        // 树 / 无树由上面叠叶层还是叠呼啸层表达。
        renderTierBank(this.bedTiers,
                (info != null && isWooded(info)) ? BED_TIERS_WOOD : BED_TIERS_OPEN,
                bedLevel, gain, null);
    }

    private void fadeRainWind() {
        if (this.rainWindLoop != null) {
            this.rainWindLoop.setScaleTarget(0F); // eases down, stops itself at zero
            this.rainWindLoop = null;
            this.rainWindAsset = null;
        }
    }

    /**
     * Slow swell for the wind bed, 0.42-1.0. Two sines at 14 s and 23 s: their
     * beat period is over five minutes, so the shape does not repeat inside any
     * stretch a player is likely to listen to, and the troughs land at a
     * different point every time. The loop player eases towards whatever this
     * returns, so following it costs nothing and adds no seams.
     */
    private static float windSwell(final long tick) {
        final double t = tick / 20D;
        double sum = 0D;
        for (int i = 0; i < SWELL_PERIODS_S.length; i++)
            sum += Math.sin(2D * Math.PI * t / SWELL_PERIODS_S[i] + SWELL_PHASES[i]);
        // Four sines rarely line up, so the mean is small; stretch it back out
        // or the swell would sit in the middle of its own range and do nothing.
        double u = sum / SWELL_PERIODS_S.length * 2D;
        if (u > 1D)
            u = 1D;
        else if (u < -1D)
            u = -1D;
        final float s = 0.5F + 0.5F * (float) u;
        return RAIN_WIND_SWELL_FLOOR + (RAIN_WIND_SWELL_PEAK - RAIN_WIND_SWELL_FLOOR) * s;
    }

    /**
     * WIND：当前风等级 0..1。风床、阵风、呼啸与叶声层全部读这一个数，于是
     * 耳朵听到的风与眼睛看到的雨幕倾斜是同一个量。
     *
     * <p>P2 起不再由"降水强度 + 阵风幅度 + 群系倍率 + 季节倍率"折出来：那是标量
     * 耦合，均值与方差被同一个倍率缩放，维护者的三个性格用例在数学上无解
     * （18522 组网格搜索可行解 = 0）。现在它由群系的 {@code WindProfile} 给形状、
     * 由 {@code WindField} 由预设 + 每场事件的形状算出来，走纯函数，不读缓存 ——
     * {@code update()} 只在降水渲染里被调用，晴天缓存不更新，读 {@code speed()}
     * 拿到的是上一场风暴的残留值。</p>
     */
    private float windLevel() {
        // 总开关关掉时退回"降水强度就是风等级"的旧口径：维护者手里要有一个
        // 一句话能回到 P2 之前的旋钮，而不是只能回退整个 jar。
        if (!this.config.windOptions.enableUnifiedWind)
            return PrecipitationIntensity.ambient();
        return PrecipitationIntensity.windLevel();
    }

    /** 统一风系统：玩家配置的整体音量缩放，乘在风床与各风层上（100% = 1.0）。 */
    private float windVolumeScale() {
        return this.config.windOptions.windVolumeScaling / 100F;
    }

    /**
     * Round 12: what the canopy sources used to lose to distance falloff. They sit 8-16
     * blocks out against Minecraft's 16 block attenuation distance, so on average they
     * arrived at about a quarter of their nominal volume. Now that the falloff is gone
     * (see playLeafSurround) this factor puts the loudness back where it was measured,
     * while the distance no longer decides whether the canopy is audible at all.
     */
    private static final float LEAF_SURROUND_NOATTEN_TRIM = 0.30F;

    /**
     * WIND：上风侧的发声点，{@code player - windDir x blocks}，再抬高 height 格。
     *
     * <p>Round 12：这个点是<em>播放那一刻</em>的玩家位置算出来的世界坐标，之后不会动。
     * 以前带距离衰减，玩家走开几步阵风就没了 —— 风是跟着人的天气，不是地上一个喇叭。
     * 现在走无衰减（见调用处），音量不再随距离掉，方位 pan 仍然由上风侧决定。</p>
     *
     * <p>Minecraft 的 SoundInstance 三版都没有声道 pan 参数，唯一能让玩家听出
     * 风从哪来的办法就是把声源放到风来的那一侧。WindField 约定 {@code (cos a,
     * sin a)} 是风<em>吹去</em>的方向，所以这里取反号。</p>
     */
    private static Vec3 upwindPosition(final Player player, final float blocks, final float height) {
        final float a = WindField.angleAt(player.level().getGameTime(), 0F);
        return new Vec3(player.getX() - Math.cos(a) * blocks,
                player.getY() + height,
                player.getZ() - Math.sin(a) * blocks);
    }

    /** Whether this factory is one of the wind assets biomes.json can assign. */
    private static boolean isWindFactory(final ISoundFactory factory) {
        final String loc = factory.getLocation().toString();
        return loc.endsWith("biome.wind")
                || loc.endsWith("biome.wind.hills")
                || loc.endsWith("biome.wind.mountains")
                || loc.endsWith("biome.wind.arctic")
                || loc.endsWith("biome.wind.desert");
    }

    /**
     * Per-asset loudness trim, referenced to wind.ogg (-34.8 dB RMS): the wind
     * assets span ~15 dB and arctic_wind is stereo besides, so one shared curve
     * would make the arctic blast. trim = 10^((ref - asset) / 20).
     */
    private static float windTrim(final ISoundFactory factory) {
        final String loc = factory.getLocation().toString();
        if (loc.endsWith("biome.wind.arctic"))
            return 0.33F; // -25.1 dB, and stereo
        if (loc.endsWith("biome.wind.desert"))
            return 1.86F; // -40.2 dB
        return 1.0F; // wind.ogg is the reference (hills/mountains resolve to it)
    }

    /**
     * 阵风档位：0=小雨 1=中雨 2=暴雨。升档到阈值就升，降档要多退
     * {@link #GUST_TIER_HYSTERESIS} —— 否则强度在阈值附近微动就会来回换素材。
     */
    private static int gustTierFor(final float intensity, final int current) {
        final float h = GUST_TIER_HYSTERESIS;
        final int t = current < 0 ? 0 : current;
        if (intensity >= GUST_TIER_HEAVY)
            return 2;
        if (intensity >= GUST_TIER_MEDIUM && t < 1)
            return 1;
        if (t == 2 && intensity < GUST_TIER_HEAVY - h)
            return intensity < GUST_TIER_MEDIUM - h ? 0 : 1;
        if (t == 1 && intensity < GUST_TIER_MEDIUM - h)
            return 0;
        return t;
    }

    /**
     * The gust layer, riding on top of the wind bed. Sparse - tens of seconds
     * apart - and one clip per gust.
     *
     * <p>Wooded biomes get a clip that carries its own wind and then its
     * leaves, played from three points at canopy height. Open country gets a
     * clip with wind and no leaves, played unpositioned. The pass before this
     * one played a wind clip <em>and</em> a leaf clip together; every leaf clip
     * in this recording already has wind in it - it is leaves <em>in</em> wind
     * - so that doubled the wind and buried the leaves. One clip, and the
     * wind-then-leaves ordering is the recording's own.</p>
     *
     * <p>Every clip in both pools, and the bed underneath them, comes out of
     * the one 106 s cinetony field recording, so a gust reads as a single
     * event. Bed and gust differ in spectrum - the bed sits near 1.2 kHz, a
     * gust sweeps past 2.7 kHz - and in level. The bed is the weather; the
     * gust is the moment.</p>
     *
     * <p>What separates a gust from rain is the swell, not the spectrum: leaves
     * and rain both rustle somewhere in 2-8 kHz, and a plateau of rustle is
     * indistinguishable from rain. So the clips start before the gust arrives
     * and keep the arrival - see {@code gust_fades} in
     * {@code tools/_make_rain_wind_assets_v2.py}. A 1.5 s fade-in on a clip
     * whose wind peaks two seconds in flattened exactly the thing that made it
     * a gust.</p>
     */
    private void handleRainTexture(final Player player) {
        if (!PrecipitationIntensity.grading() || !doBiomeSounds() || this.scanner.isInside()) {
            this.rainGustNextTick = 0L;
            return;
        }
        final long now = this.tickCount.getTickCount();
        if (now < this.rainGustNextTick)
            return;
        final float intensity = PrecipitationIntensity.ambient();
        var info = this.scanner.playerLogicBiomeInfo();
        final boolean wooded = info != null && isWooded(info);
        final float min = wooded ? RAIN_LEAF_MIN_INTENSITY : RAIN_WIND_MIN_INTENSITY;
        if (intensity < min) {
            this.rainGustNextTick = now + 20L; // re-check in a second
            return;
        }
        final int tier = gustTierFor(intensity, this.rainGustTier);
        this.rainGustTier = tier;
        // Tier 2 is the sustained howl now: firing a one-shot clip of the same
        // recordings on top of a layer already playing them is two copies of one
        // asset, which is the doubling we just removed from the biome path.
        if (tier >= 2)
            return;
        float t = (intensity - min) / (1F - min);
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        float amp = PrecipitationIntensity.gustAmplitude();
        amp = amp < 0F ? 0F : (amp > 2F ? 2F : amp);
        // 间隔随强度收紧，但每次排期再摇一次随机数：旧版强度给定后间隔是定值，
        // 那就是"播放太规律"的根因。
        final float seconds =
                (RAIN_LEAF_INTERVAL_MAX_S + (RAIN_LEAF_INTERVAL_MIN_S - RAIN_LEAF_INTERVAL_MAX_S) * t)
                        / (0.6F + 0.8F * amp)
                        * (0.65F + RANDOM.nextFloat() * 0.7F);
        // WIND：档位只负责挑素材和给基准，真正的大小交给统一风系统的风等级 ——
        // 与雨幕倾斜、风床、呼啸、叶声层读的是同一个数。风力还会在 interval 上
        // 体现（见上），但那是疏密，这里是大小。
        final float gustLevel = windLevel();
        final float gustScale = GUST_WIND_LEVEL_FLOOR + GUST_WIND_LEVEL_SPAN * gustLevel;
        // Louder still when it thunders: a storm has to be heard over its own
        // rain, and that was exactly where the gusts were being buried.
        final float vol = GUST_TIER_VOLUME[tier]
                * gustScale
                * (0.85F + RANDOM.nextFloat() * 0.3F)
                * (player.level().isThundering() ? RAIN_GUST_STORM_GAIN : 1F)
                * windVolumeScale();
        // var, not the resource-location type: it is Identifier on 26.1 and
        // ResourceLocation on the other two.
        final var pool = tier >= 2 ? STORM_WIND_HEAVY
                : (wooded ? RAIN_TEXTURE_LEAF : RAIN_TEXTURE_WIND);
        // 不连抽同一条：三条的小池子里连着抽到同一条，听感就是"同一声反复"。
        int idx = RANDOM.nextInt(pool.length);
        if (pool.length > 1 && idx == this.rainGustLastIndex)
            idx = (idx + 1 + RANDOM.nextInt(pool.length - 1)) % pool.length;
        this.rainGustLastIndex = idx;
        var factory = ContainerManager.resolve(ISoundLibrary.class)
                .getSoundFactoryOrDefault(pool[idx]);
        if (wooded)
            // Canopy surround. The trim keeps three sources from landing 4.8 dB
            // over one; it lives here and not in playLeafSurround because that
            // method is shared with the sunny gust, which is not ours to change.
            playLeafSurround(player, factory, vol * RAIN_LEAF_SOURCE_COMP);
        else
            // 没有树冠可以回应，但也不该因此就没有方向：把声源放到上风侧，玩家
            // 听到的就是"风从那边吹过来"。SoundInstance 三版都没有 pan 参数，位置
            // 是唯一能写的东西 —— 走 createAtLocation 而不是无位置的 additional。
            // Round 12: no attenuation, for the same reason as the canopy - the anchor is a
            // fixed world position, so a gust fired while the player was standing still died
            // as soon as he moved off. The position still drives the pan, which is the only
            // way Minecraft can say "the wind is coming from over there", so the direction
            // survives; only the distance term goes. Four blocks is under a third of the
            // attenuation distance, so the loudness barely moves.
            this.audioPlayer.play(factory.createAtLocationNoAttenuation(
                    upwindPosition(player, GUST_SOURCE_RANGE_BLOCKS, GUST_SOURCE_HEIGHT_M), vol));
        this.rainGustNextTick = now + (long) (seconds * 20F);
        // One line per gust, so tens of seconds apart: is the wooded test
        // answering what we think it is? The trait set is what decided it.
        this.logger.info("[RAIN-GUST] wooded=%s traits=%s intensity=%.2f tier=%d vol=%.2f next=%.0fs",
                wooded, info == null ? "null" : info.getTraits().toString(),
                intensity, tier, vol, seconds);
    }

    /**
     * The three-source canopy surround: 120 deg apart with jitter, 8-16 blocks
     * out, floating at tree-crown height so the wind sweeps through the
     * treetops. Shared by the sunny gust chance and the rain layer.
     */
    private void playLeafSurround(final Player player, final ISoundFactory factory, final float volScale) {
        final int sources = 3;
        // WIND：随机方位听起来是"四面八方的树冠"，不携带风向。现在把中间那一路
        // 对准上风侧（= 风向角 + PI），三源还是 120 度散开加抖动，但整体有了来处。
        // 晴天那次 call 也走这里，所以取风向必须用 WindField 的纯函数：它的缓存
        // 只在降水渲染里更新，晴天读 dirX()/dirZ() 拿到的是上一场风暴的残留。
        final double baseAngle = WindField.angleAt(player.level().getGameTime(), 0F) + Math.PI;
        for (int i = 0; i < sources; i++) {
            final double angle = baseAngle + i * (Math.PI * 2D / sources)
                    + (RANDOM.nextDouble() - 0.5D) * 0.8D;
            final double dist = MOOD_SOUND_MIN_RANGE
                    + RANDOM.nextDouble() * (MOOD_SOUND_MAX_RANGE - MOOD_SOUND_MIN_RANGE);
            final double y = player.getY() + 6.0D + RANDOM.nextDouble() * 2.0D;
            var pos = new Vec3(player.getX() + Math.cos(angle) * dist, y,
                    player.getZ() + Math.sin(angle) * dist);
            float vol = dsBiomeVolume() * volScale * (0.75F + RANDOM.nextFloat() * 0.25F);
            // Round 12: no attenuation. These sources sit 8-16 blocks out by design, and
            // with distance falloff they were dropped to roughly a quarter of their volume
            // before the player heard them - then faded to nothing the moment he walked,
            // because the anchor is a fixed world position, not something that travels with
            // him. Attenuation.NONE keeps the position (so the pan, and with it the upwind
            // cue, still works) and drops only the distance term. The trim stands in for the
            // falloff that used to be applied, so the canopy does not suddenly arrive three
            // times as loud.
            var instance = factory.createAtLocationNoAttenuation(pos, vol * LEAF_SURROUND_NOATTEN_TRIM);
            this.audioPlayer.play(instance);
        }
    }

    /** True if this acoustic is one of the Deep Dark's own ambience loops (drone or heartbeat). */
    private static boolean isDeepDarkAmbience(ISoundFactory acoustic) {
        var loc = acoustic.getLocation();
        return DEEP_DARK_DRONE.equals(loc) || DEEP_DARK_HEARTBEAT.equals(loc);
    }

    /** True if the biome is wooded (any tree-bearing biome). */
    private static boolean isWooded(BiomeInfo biome) {
        var traits = biome.getTraits();
        return traits.contains(BiomeTrait.FOREST)
                || traits.contains(BiomeTrait.CONIFEROUS)
                || traits.contains(BiomeTrait.DECIDUOUS)
                || traits.contains(BiomeTrait.JUNGLE);
    }

    private SimpleSoundInstance createMoodInstance(Player player, ISoundFactory factory, float volumeScale) {
        var scale = volumeScale * dsBiomeVolume();
        if (scale == 1.0F)
            return factory.createAsMood(player, MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
        // createAsMood() has no volume control, so rebuild the same random-offset
        // instance manually with an attenuated volume when inside.
        var offset = MathStuff.randomPoint(MOOD_SOUND_MIN_RANGE, MOOD_SOUND_MAX_RANGE);
        return factory.createAtLocation(player.getEyePosition().add(offset), scale);
    }

    // Config-driven volume multiplier applied to biome ambience (sound-options slider).
    private float dsBiomeVolume() { return (float) this.config.soundOptions.biomeVolume; }

    private void queueAmbientSounds() {
        // Iterate through the existing emitters:
        // * If done, remove
        // * If not in the incoming list, fade out
        // * If it does exist, update volume throttle and fade in if needed
        this.emitters.removeIf(entry -> {
            if (entry.isDone()) {
                return true;
            }
            final float volume = this.workMap.getFloat(entry.getSoundEvent());
            if (volume > 0) {
                entry.setVolumeScale(volume);
                if (entry.isFading())
                    entry.fadeIn();
                this.workMap.removeFloat(entry.getSoundEvent());
            } else if (!entry.isFading()) {
                entry.fadeOut();
            }
            return false;
        });

        // Any sounds left in the list are new and need an emitter created.
        this.workMap.forEach((fx, volume) -> {
            final BiomeSoundEmitter e = new BiomeSoundEmitter(this.logger, this.audioPlayer, fx);
            e.setVolumeScale(volume);
            this.emitters.add(e);
        });
    }

    public void clearSounds() {
        this.emitters.forEach(BiomeSoundEmitter::stop);
        this.emitters.clear();
        this.workMap.clear();
    }

    @Override
    protected void gatherDiagnostics(CollectDiagnosticsEvent event) {
        var panelText = event.getSectionText(CollectDiagnosticsEvent.Section.Emitters);
        this.emitters.forEach(backgroundAcousticEmitter -> panelText.add(Component.literal(backgroundAcousticEmitter.toString())));
    }
}
