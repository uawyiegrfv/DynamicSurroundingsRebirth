package org.orecruncher.dsurround.processing.weather;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.CustomEnvironmentEffectsRendererManager;
import net.neoforged.neoforge.client.CustomWeatherEffectRenderer;
import net.neoforged.neoforge.client.event.RegisterCustomEnvironmentEffectRendererEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;

import java.util.Random;

/**
 * Graded rain and snow rendering (N4) - the 1.12.2 StormRenderer ported.
 *
 * <h2>Why we take over instead of layering</h2>
 *
 * <p>1.12.2 replaced the dimension's weather renderer outright
 * ({@code RenderWeather extends IRenderHandler}, installed through
 * {@code WorldProvider.setWeatherRenderer()}). We do the same thing in spirit:
 * vanilla rain and snow are suppressed and ours are drawn in their place. Vanilla
 * always draws the <em>same number</em> of rain columns and only scales alpha by
 * the rain level, so layering on top of it cannot express intensity - it would
 * just draw two rains.</p>
 *
 * <h2>How the takeover is wired</h2>
 *
 * <p>The suppression hook ({@code renderSnowAndRain}) only answers "vanilla, don't
 * bother" and draws nothing. The geometry is drawn from the
 * {@code AfterWeather} level stage instead - the same path the sandstorm veil
 * already uses. {@code LevelRenderer} posts that stage event unconditionally,
 * right after {@code weatherEffectRenderer.render(...)}, so suppressing the
 * vanilla pass cannot take our draw down with it. It also keeps one drawing
 * implementation across all three versions instead of three.</p>
 *
 * <p>{@code tickRain} is suppressed - we return false, which drops vanilla's
 * ground splash particles <em>and</em> its rain sound. Neither has a replacement
 * yet, so both are currently <em>absent</em>, not vanilla. Do not read this as
 * "vanilla handles it".</p>
 *
 * <h2>The 1.12.2 algorithm</h2>
 *
 * <p>Per-block-column in a +/-range grid around the player, one vertical quad
 * whose cross-section is the unit vector perpendicular to the player->column
 * radius scaled to {@link #STRIP_HALF_WIDTH} - the columns together read as
 * slanted curtains sweeping around the player. Rain and snow are
 * <em>different animations</em>, which is easy to get wrong by copying one to the
 * other:</p>
 *
 * <pre>
 *   RAIN: d5  = (((ticks + seed) &amp; 31) + partialTick) / 32.0 * (3.0 + random.nextDouble())
 *         v   = y * 0.25 + d5                 // fast fall, per-column rate
 *         alpha = ((1 - f3^2) * 0.5 + 0.5) * alphaRatio
 *         light = combinedLight               // used as-is
 *
 *   SNOW: d8  = ((ticks &amp; 511) + partialTick) / 512.0      // slow 512-tick scroll
 *         d9  = random.nextDouble() + f1 * 0.01 * random.nextGaussian()   // U shear
 *         d10 = random.nextDouble() + f1 * random.nextGaussian() * 0.001  // V shear
 *         v   = y * 0.25 + d8 + d10
 *         alpha = ((1 - f3^2) * 0.3 + 0.5) * alphaRatio
 *         light = combinedLight               // vanilla: no brightening
 *                (1.12.2 brightened snow here; modern vanilla does not)
 * </pre>
 *
 * <p>Note the snow drift factor is {@code 0.01}, not the {@code 0.2} the dust
 * veil uses - dust was widened on purpose, snow must not be.</p>
 *
 * <h2>Where intensity enters</h2>
 *
 * <ul>
 *   <li><b>Texture</b> - {@code Weather.Properties.mapRainStrength(intensity)},
 *       evaluated on the <em>current ramping</em> value (1.12.2
 *       {@code setCurrentIntensity}), so a storm visibly escalates calm ->
 *       light -> ... as it builds and de-escalates as it stops.</li>
 *   <li><b>Alpha</b> - {@code alphaRatio = intensity / peak}, 1.12.2's
 *       {@code rainStrength / getMaxIntensityLevel()}.</li>
 * </ul>
 *
 * <p>The per-column phase (rain vs snow vs nothing) comes from
 * {@code PrecipitationIntensity.phase}, which delegates to Serene Seasons, so
 * snow on a peak next to rain in the valley - and no rain under a roof - come for
 * free. Phases are cached per column for {@link #CACHE_TTL} ticks because the
 * query does a biome lookup and a heightmap read.</p>
 */
public final class PrecipitationRenderer {

    private static final String MOD_ID = "dsurround";

    /**
     * Half-width of a column's strip in blocks. 1.12.2 uses 0.5, which makes
     * neighbouring strips exactly touch - a continuous curtain. This is the density
     * knob; the dust veil runs at 0.9 because the sparse dust specks read as too
     * thin at 0.5, but rain streaks tile fine at the 1.12.2 value.
     */
    private static final float STRIP_HALF_WIDTH = 0.5F;

    /** Intensity band a curtain fades in over. Without it a curtain pops in at full
     * alpha the instant the intensity passes its rank, which reads as flicker.
     * Wide enough now that a curtain is a visible fade rather than a blink, which
     * is what lets the density channel carry the intensity on its own. */
    private static final float COLUMN_FADE_BAND = 0.12F;

    /** Curtain half-width at zero and at full intensity, as a multiple of the 1.12.2
     * 0.5 - thin threads for drizzle, wide overlapping curtains for a downpour. */
    private static final float STRIP_MIN_SCALE = 0.7F;
    private static final float STRIP_RANGE = 0.6F;

    /**
     * Intensity window over which a column grows a second, independently phased
     * streak - literally more streaks inside the same curtain.
     *
     * <p>This used to be a single threshold at 0.72, which doubled the amount of
     * geometry in one frame: the field went from one streak per column to two
     * with nothing in between, so climbing through that intensity read as a step
     * rather than as the rain getting heavier, and it stepped back down just as
     * abruptly. The extra streak now fades in over a band, so the curtain
     * density is continuous in intensity.</p>
     */
    private static final float SECOND_LAYER_FROM = 0.52F;
    private static final float SECOND_LAYER_TO = 0.88F;

    /**
     * How the opacity follows {@code intensity / peak}. Below 1 the rain becomes
     * visible early and then firms up, instead of staying invisible for the first
     * stretch of every storm: the low end is already carried by curtain count,
     * width, streaks and texture band, so a linear opacity on top pressed it
     * twice, and with the alpha cull that meant the opening seconds drew nothing
     * at all. A power curve rather than a floor, because a floor pops when the
     * intensity reaches zero.
     */

    // ---- WIND：风倾斜的可调参数（实测后只动这一段） ----
    /** tan(倾角) = 增益 x 切向风 / 下落速度，再夹到 +/-MAX_TILT_TAN。
     *  增益是艺术系数：物理值是 1.0，但那会让暴雨斜到 24 度以上，
     *  先给一个保守的 0.5（满强度约 12 度）。 */
    private static final float RAIN_TILT_GAIN = 0.5F;
    /** 雪的倾斜增益。雪的下落只有 1/128 格/tick，按物理值算会直接斜成
     *  水平，所以给一个远小于 1 的增益，上限交给 MAX_TILT_TAN 兜住。 */
    private static final float SNOW_TILT_GAIN = 0.02F;
    /** tan(倾角) 的硬上限，0.36 约 20 度。 */
    private static final float MAX_TILT_TAN = 0.36F;
    /** 每幕倾角的小幅随机扰动幅度（比例，0.25 = 各幕在 +/-25% 之间错开）。
     *  雨的倾角原本完全由几何和风向决定，各幕非常规整（雪和沙尘都有随机扰动，
     *  雨没有）；这个值用来打破那种机械感。调大 = 各幕倾角差异更明显，
     *  超过 0.5 会看到个别幕几乎直立；调回 0 就恢复成规整的样子。 */
    private static final float RAIN_TILT_JITTER = 0.25F;
    /** 雨的下落速度（格/tick）= (2.0 + 2.5 x intensity) / 8，与 d5 的滚动
     *  速率一致；随机项取均值，不再多抽一次随机数，以免改动 1.12.2 的
     *  随机序列。 */
    private static final float RAIN_FALL_SPEED_MIN_BPT = 0.25F;
    private static final float RAIN_FALL_SPEED_RANGE_BPT = 0.3125F;
    /** 雪的下落速度（格/tick）：d8 每 tick 走 1/512 个 v，1 v = 4 格。 */
    private static final float SNOW_FALL_SPEED_BPT = 1F / 128F;

    // ---- 风致拖影（让 1~3px 的雪/沙尘也能读出风向） ----

    /**
     * 拖影长度上限，贴图像素。判据是"剪切能让特征顶部让开 tan x 高度"：
     * 满风 tan=0.36 时 3px 的雪花只让开 1.08px，读不出方向；而 16px 的雨丝
     * 能让开 5.8px 所以雨一直是对的。6px 是"读得出方向"和"雪变雨丝"的分界。
     */
    /**
     * 拖影是否加在围栏幕上。**关着** —— 离线实测（{@code _trail_spread_check.py}）：
     * 拖影的方向 = &plusmn;该幕自己的横轴，而围栏的横轴围成一圈，{@code sign(p)} 只能
     * 把它折到以风向为中心的半圆里，于是划痕方向铺满 180&deg;（标准差 39.6&deg;，
     * 只有 35% 的方向能量落在 &plusmn;30&deg; 内）。加权平均虽然指向风向（0&deg;），
     * 但眼睛看到的不是平均、是散布 —— 表现就是"看不出方向，还看得眼花"。
     *
     * <p>拖影只能放在"所有幕朝向一致"的流幕上：流幕的横轴就是风向，实测标准差
     * 0&deg;、100% 能量在 &plusmn;15&deg; 内。流幕上线后这里改成"只有流幕片开拖影"。
     */
    // ---- 流幕（与风向平行的一层，叠在围栏上） ----

    /**
     * 流幕的强度系数（相对围栏那一层）。**0 = 这一层关掉。**
     *
     * <p>实测三轮（2026-10-06），反馈依次是"看不出方向、看得眼花缭乱"
     * &rarr; "加了和没加差不多、半透明、太快像在闪" &rarr; "还是很快很虚，
     * 围栏也没变"。<b>结论：不是参数没调好，是这条路走不通。</b></p>
     *
     * <ol>
     * <li>围栏的每一片幕只能在自己面内表达，而幕绕成一圈 ⇒ 任何"沿幕"的方向
     *     表达（倾角、拖影、漂移）在每片幕上方向都不同 —— 实测划痕方向铺满
     *     180&deg;、标准差 39.6&deg;。</li>
     * <li>于是只剩"叠一层统一朝向的幕"。但严格平行时顺风看完全 edge-on、覆盖率
     *     归零，只能当附加层 ⇒ 天生是少数派，改不动"以围栏为主的观感"；改扇形
     *     （&plusmn;30&deg;）不归零了，可它是半透明、还在动，看着虚。</li>
     * <li>最根本的一条：<b>雪在贴图上只有 1~3px。</b>任何"把点拉长成划痕"的做法
     *     本质上都是把雪变成雨丝/雾 —— 方向有了，雪不像雪了。这是美术尺度决定
     *     的，代码绕不过去。</li>
     * </ol>
     *
     * <p>要真让雪有风向，只有一条彻底的路：把降水改成<b>世界空间的粒子系统</b>
     * （每颗雪是一颗能朝任意方向运动的粒子），而不是"幕"。那是重写，不是调参。
     * 参数留在这里是为了将来真要重写时有个对照。</p>
     */
    private static final float STREAMER_GAIN = 0F;

    /**
     * 扇形的半角，度。0 = 严格平行（顺风看完全 edge-on，覆盖率归零，只能当附加层）；
     * 30 = 轴在风向 &plusmn;30&deg; 内展开，顺风看仍有 38% 的覆盖率，可以当主层。
     *
     * <p>复现器实测（{@code _fan_layout_check.py}，强度 1.0，覆盖相对现状围栏）：</p>
     * <pre>
     *   半角  顺风   侧风   一圈平均  方向标准差
     *   15&deg;   19%   154%    100%      8.1&deg;
     *   25&deg;   32%   151%    100%     13.2&deg;
     *   35&deg;   44%   146%    100%     18.3&deg;
     *   45&deg;   56%   141%    100%     23.4&deg;
     * </pre>
     * 取 30&deg;：标准差 ~15&deg;（读得出方向），顺风不塌。
     */
    private static final float STREAMER_FAN_DEG = 30F;

    /**
     * 满风风速，格/tick —— 把风速归一化到 0..1 用。
     * 7.35 格/秒 = (0.15 + 4.2 + 3.0) / 20。
     */
    private static final float STREAMER_FULL_WIND_BPT = 0.3675F;

    /**
     * 流幕的横向漂移，u/tick（满风时），折合每秒 1.0 个纹理周期。
     *
     * <p>必须以"每秒几个纹理周期"定标并封顶：v1 的跟风漂移就是没定标，
     * 实测每秒滚 8 个纹理周期 —— 那不叫风向，那叫闪烁。</p>
     *
     * <p>0.02 = 满风每秒 0.4 个纹理周期 &asymp; 0.52 格/秒。第一版给的是 0.05
     * （1.0 周期/秒），他实测"强度 1.0 时移动太快，根本看不清，像在闪" ——
     * 先让他看清静态划痕的方向，再谈运动。</p>
     */
    private static final float STREAMER_DRIFT_UPT = 0.02F;

    /** 漂移方向的符号。1F = 雪朝风去的那一侧飘。实机若发现反了，这里改 -1F。 */
    private static final float STREAMER_DRIFT_SIGN = 1F;

    private static final boolean TRAIL_ON_FENCE = false;

    private static final float TRAIL_MAX_PX = 6F;

    /** 快门时间：拖影代表多少 tick 的位移。 */
    private static final float TRAIL_TICKS = 0.5F;

    /** 采样间距上限，px。1.5px 在屏幕上通常只有 1~2 个像素，看着是连续的。 */
    private static final float TRAIL_GAP_PX = 1.5F;

    /** 采样数上限，也就是成本上限。 */
    private static final int TRAIL_MAX_SAMPLES = 4;

    /** 雪花自身的尺寸，px —— 拖影拉到这么长时亮度减半。 */
    private static final float TRAIL_ALPHA_FEATURE_PX = 3F;

    /** 1px 以下的拖影既看不见也不该扣亮度，保证静歇期精确退化成今天的样子。 */
    private static final float TRAIL_ALPHA_DEAD_PX = 1F;

    /**
     * 亮度下限。纯物理（划痕拉长几倍就暗几分之一）会淡到"半透明、看不出存在"，
     * 他反馈过一次，所以兜到 0.5。
     */
    private static final float TRAIL_ALPHA_FLOOR = 0.5F;

    /** 贴图宽度，px = 1 个 u 周期。拖影按"贴图像素"定标，与幕宽无关。 */
    private static final float TRAIL_TEX_PX = 64F;

    /** 度 -> 弧度。 */
    private static final float DEG2RAD = (float) (Math.PI / 180.0);
    /** 雪的横向抖动幅度（u/tick），1.12.2 的 0.01F。风给出的是均值，
     *  这个高斯项保留各幕之间的差异（1.12.2 的招牌观感）。 */
    private static final float SNOW_DRIFT_JITTER = 0.01F;
    private static final float RAIN_ALPHA_GAMMA = 0.4F;

    /** Hard cap on layers. Also the vertex buffer multiplier on 26.1. */
    private static final int MAX_LAYERS = 2;

    /**
     * <p>Which columns exist at a given intensity is decided by a <em>stratified</em>
     * rank, not by an independent per-column coin flip.</p>
     *
     * <p>The distinction is the whole ballgame. With an independent hash each
     * column is a Bernoulli trial, so the columns that survive at low intensity
     * form a Poisson cloud: they clump, and the gaps between clumps are far
     * larger than the average spacing. At 10% intensity the average spacing is
     * about three blocks but the typical worst gap is eight, which means the
     * player routinely stands in a bald patch with every remaining curtain
     * somewhere off in the distance - the rain looks lopsided rather than thin.
     * Turning the intensity down did not thin the rain evenly, it punched holes
     * in it.</p>
     *
     * <p>Stratifying fixes it. The world is cut into {@link #STRATUM}-sized tiles
     * and inside every tile the columns are ordered by an 8x8 Bayer matrix, so
     * <em>any</em> threshold keeps a share of every tile instead of a random
     * subset of the plane. Gaps are bounded by the tile size and the survivor set
     * has no direction to it.</p>
     *
     * <p>The tile itself is rotated, mirrored and phase-shifted from a hash of the
     * tile coordinates. Without that every tile would keep the same cells and the
     * Bayer ordering would show up as a visible diagonal lattice - a uniform
     * field, but an obviously artificial one.</p>
     */
    private static final int STRATUM = 8;
    private static final int STRATUM_SHIFT = 3;
    private static final int STRATUM_MASK = STRATUM - 1;

    /**
     * The rank is anchored to <b>world</b> coordinates, never to the player.
     *
     * <p>That is deliberate and it is the one thing that cannot be traded away.
     * A player-centred rank would put the pattern exactly where it is wanted,
     * but the player moves: cross one block boundary and every column's offset
     * from the player changes, so every column's rank changes and the whole
     * field re-rolls. With a fade band that reads as the rain shimmering while
     * you walk, which is far worse than an occasional bald patch. Anchoring to
     * the world keeps a column's rank fixed for as long as that column exists,
     * so columns only ever appear and disappear at the edge of the grid.</p>
     *
     * <p>The cost of that anchoring is that the player can land in a gap of the
     * pattern, which is what {@link #CENTER_BIAS} pays back.</p>
     */
    private static final float CENTER_BIAS = 0.18F;
    /** Radius, in blocks, over which the centre weighting falls off. */
    private static final float CENTER_RADIUS = 7F;
    /**
     * Intensity at which the centre weighting is fully applied. It is scaled down
     * towards zero intensity so that a storm building from nothing does not
     * appear as a small disc of rain hugging the player - at drizzle the field
     * has to arrive everywhere at once, and only once there is a field does it
     * make sense to favour the middle of it.
     */
    private static final float CENTER_BIAS_FULL_AT = 0.15F;

    /**
     * Floor on the intensity the <em>geometry</em> is thinned with. Thinning is a
     * sampling problem: below roughly twenty curtains in a 21x21 grid no scheme
     * reads as even, it reads as a few clumps in two or three directions. So below
     * this the field holds, and low intensity is carried by the continuous
     * channels (streaks per curtain, width, alpha, texture band) instead. Applied
     * to the geometry only - fog, sky and sound are scalars and go lower freely.
     */
    private static final float MIN_GEOMETRY_INTENSITY = 0.12F;

    /** 8x8 ordered-dither matrix: a spatially hierarchical permutation of 0..63. */
    private static final int[] BAYER8 = {
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
    };

    /**
     * The stratified rank of a world column, 0..1. Lower means "this column is
     * among the first to appear as the intensity rises".
     *
     * <p>Pure and dependant only on world coordinates, so a column keeps its rank
     * for as long as it is inside the grid.</p>
     */
    /** As the rain's density channel, shared with the dust veil. Public
     *  because WeatherStormHandler grades the sandstorm with the same
     *  stratified rank - see the comment there. */
    public static float columnRank(int gridX, int gridZ) {
        final int tileX = gridX >> STRATUM_SHIFT;
        final int tileZ = gridZ >> STRATUM_SHIFT;
        int localX = gridX & STRATUM_MASK;
        int localZ = gridZ & STRATUM_MASK;

        // Per-tile orientation and phase. Cheap integer hash - this runs for every
        // column of the grid every frame.
        final int tileHash = (tileX * 73856093) ^ (tileZ * 19349663);
        final int rotation = tileHash & 3;
        final int mirrored = (tileHash >>> 2) & 1;
        final int phase = (tileHash >>> 3) & 63;

        for (int i = 0; i < rotation; i++) {
            final int swap = localX;
            localX = localZ;
            localZ = STRATUM_MASK - swap;
        }
        if (mirrored == 1)
            localX = STRATUM_MASK - localX;

        return (float) ((BAYER8[localZ * STRATUM + localX] + phase) & 63) / 64F;
    }

    /**
     * Per-column cross-section table, 1.12.2 {@code StormRenderer.RAIN_X_COORDS} /
     * {@code RAIN_Y_COORDS}. Indexed {@code (dz + 16) * 32 + (dx + 16)}. The centre
     * column is a 0/0 division in 1.12.2 and would be NaN, so it falls back to a
     * fixed half-width - otherwise the column under the player never draws.
     */
    private static final float[] STRIP_X = new float[1024];
    private static final float[] STRIP_Y = new float[1024];

    static {
        for (int i = 0; i < 32; ++i) {
            for (int j = 0; j < 32; ++j) {
                final float dx = j - 16F;
                final float dz = i - 16F;
                final float r = (float) Math.sqrt(dx * dx + dz * dz);
                final int idx = i << 5 | j;
                if (r < 0.0001F) {
                    STRIP_X[idx] = STRIP_HALF_WIDTH;
                    STRIP_Y[idx] = 0F;
                } else {
                    STRIP_X[idx] = -dz / r * STRIP_HALF_WIDTH;
                    STRIP_Y[idx] = dx / r * STRIP_HALF_WIDTH;
                }
            }
        }
    }

    /** 1.12.2 {@code Weather.Properties} levels, in ascending order. */
    private static final float[] LEVELS = { 0.125F, 0.25F, 0.365F, 0.5F, 0.625F, 0.75F, 0.875F, 1.0F };
    private static final String[] LEVEL_NAMES = {
            "calm", "light", "gentle", "moderate", "heavy", "strong", "intense", "torrential"
    };

    private static final Identifier[] RAIN_TEXTURES = build("rain");
    private static final Identifier[] SNOW_TEXTURES = build("snow");

    private static Identifier[] build(String kind) {
        var result = new Identifier[LEVEL_NAMES.length];
        for (int i = 0; i < LEVEL_NAMES.length; i++)
            result[i] = Identifier.fromNamespaceAndPath(MOD_ID,
                    "textures/environment/" + kind + "_" + LEVEL_NAMES[i] + ".png");
        return result;
    }

    /**
     * Grid radius in blocks. 1.12.2 ties this to fancy graphics (10 fast / 5
     * fancy); the dust veil already runs a fixed 10 on 1.21.1 and 26.1, so the
     * precipitation grid matches it instead of diverging per version.
     */
    private static final int RANGE = 10;

    private static final long CACHE_TTL = 40L;

    private static final Random columnRandom = new Random();
    private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> surfaceCache =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap phaseCache =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightCache =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static long cacheTick = Long.MIN_VALUE;
    private static net.minecraft.resources.ResourceKey<Level> cacheDimension;

    private static volatile boolean broken = false;

    /**
     * Clears session state when a world is left. The caches are keyed by
     * dimension and game time, which go stale across worlds that share the key
     * (a new world's clock can read lower than the old one's, freezing the TTL
     * forever), and a one-off render exception must not stay broken across
     * sessions.
     */
    public static void onWorldLeft() {
        broken = false;
        effectsOwnershipChecked = false;
        phaseCache.clear();
        surfaceCache.clear();
        lightCache.clear();
        cacheDimension = null;
    }

    private PrecipitationRenderer() {
    }

    /** Registers the game-bus listener. Called from the mod constructor. */
    public static void bootstrap() {
        NeoForge.EVENT_BUS.addListener(PrecipitationRenderer::onAfterWeather);
    }

    /**
     * Mod-bus: installs the weather renderer whose only job is to tell vanilla not
     * to draw rain.
     *
     * <p>The registration key is <em>not</em> the dimension id, and it also may
     * <em>not</em> be the default value.
     * {@code CustomEnvironmentEffectsRendererManager.getCustomWeatherEffectRenderer(Level, Vec3)}
     * reads the {@code CUSTOM_WEATHER_EFFECTS} environment attribute and uses that
     * identifier to look the renderer up. Registering under the dimension id
     * silently yields null; registering under {@code minecraft:default} throws
     * {@code IllegalArgumentException: You cannot register a renderer for the default
     * weather effects} and kills startup (observed on NeoForge 26.1.2.93). So we
     * register under our own id.</p>
     *
     * <p>That attribute is a {@code syncable} {@code EnvironmentAttribute} whose value
     * comes from the <em>dimension type</em>
     * ({@code EnvironmentAttributeSystem.addDimensionLayer} reads
     * {@code DimensionType.attributes()}), so it is world data, not something a
     * client-only mod can set. On a vanilla overworld it stays
     * {@code minecraft:default}, our renderer is never consulted, {@code hookCalls}
     * stays 0 and {@code active()} stays false - which degrades to vanilla rendering
     * instead of drawing a second rain on top of it. See {@link #active()}.</p>
     */
    /**
     * The instance handed to NeoForge, kept so the 26.1 lookup mixin can return
     * the same one. Null until the registration event fires.
     */
    private static CustomWeatherEffectRenderer registeredWeatherRenderer = null;

    /**
     * The key we register the weather renderer under. One home, because the
     * ownership re-check below has to ask the manager for exactly this key.
     */
    private static final Identifier WEATHER_RENDERER_KEY =
            Identifier.fromNamespaceAndPath("dsurround", "graded_precipitation");

    public static CustomWeatherEffectRenderer registeredWeatherRenderer() {
        return registeredWeatherRenderer;
    }

    public static void onRegisterWeatherEffectRenderer(RegisterCustomEnvironmentEffectRendererEvent event) {
        Identifier key = WEATHER_RENDERER_KEY;
        event.registerWeatherEffectRenderer(key, new GradedPrecipitation());
        // Remembered so the 26.1 lookup mixin can hand the same instance back -
        // see MixinCustomEnvironmentEffectsRendererManager for why the lookup
        // has to be patched at all.
        registeredWeatherRenderer = new GradedPrecipitation();
        note("registered weather renderer under key=%s", key);
    }

    /**
     * Whether we draw precipitation this frame. Also the takeover condition: when
     * this is false vanilla keeps drawing, so any failure mode degrades to vanilla
     * behaviour rather than to a world with no rain at all.
     */
    private static volatile boolean lastActive = false;
    /** How many times the suppression hook has been consulted. Stays 0 if the hook
     * was never installed (wrong registration key, or another mod won the slot). */
    private static volatile int hookCalls = 0;
    /** How many times our own draw stage fired. See {@link #renderCalls()}. */
    private static volatile int renderCalls = 0;
    /** Frames spent in the "takeover wanted, but vanilla never asked us" state - see
     * the warn inside {@link #active()}. Frames, not ticks: the hook is a render path. */
    private static int unhookedTakeoverFrames = 0;
    /** Set once the warn has fired for the current bad stretch, so a silent failure
     * surfaces once per incident instead of once per frame. */
    private static boolean warnedUnhookedTakeover = false;

    /**
     * How many times vanilla asked {@code tickRain} - the ground splash particle
     * and rain sound pass.
     *
     * <p>This exists to settle one specific disagreement. We return false from
     * {@code tickRain}, which by the bytecode should suppress vanilla's splash
     * particles <em>and</em> its rain sound outright. Maintainer testing reported
     * both still present and unvarying. Three explanations fit, and this counter
     * separates them:</p>
     *
     * <ul>
     * <li>{@code tick=0} while {@code hook>0} - our effects object never reaches
     *     this path at all, so the suppression was never applied.</li>
     * <li>{@code tick=0} and {@code hook=0} - another mod owns the effects slot.</li>
     * <li>{@code tick>0} - we are being asked and are declining, so anything on
     *     screen is coming from somewhere else (or the report was about the
     *     curtains, not the splashes).</li>
     * </ul>
     */
    private static volatile int tickHooks = 0;

    private static boolean active() {
        boolean now = false;
        if (!broken) {
            checkEffectsOwnership();
            var config = Client.Config;
            final boolean locked = PrecipitationIntensity.forced() >= 0F;
            final boolean enabled = locked
                    || (config != null && config.weatherOptions.enableGradedPrecipitation);
            // The takeover answer is `enabled && takeoverReady() && hookCalls > 0`,
            // and hookCalls only grows when vanilla actually consults our renderer -
            // which on this version rides on a require = 0 mixin
            // (MixinCustomEnvironmentEffectsRendererManager). If a NeoForge update
            // shifts the target signature, that mixin stops applying SILENTLY,
            // takeoverReady() stays true, hookCalls stays 0, and the world falls
            // back to vanilla rain with no error anywhere. Count the frames that sit
            // in exactly that state and surface it once - the condition below is the
            // half of the takeover condition that can fail without anyone logging it.
            final boolean unhooked = enabled
                    && PrecipitationIntensity.takeoverReady()
                    && hookCalls == 0;
            if (unhooked) {
                if (!warnedUnhookedTakeover && ++unhookedTakeoverFrames >= 100) {
                    warnedUnhookedTakeover = true;
                    noteWarn("graded precipitation wants to take over but vanilla never called our renderer "
                            + "(takeoverReady=true, hookCalls=0 for %d frames) - the "
                            + "CustomEnvironmentEffectsRenderer hook has likely stopped applying; "
                            + "rain will fall with vanilla's look", unhookedTakeoverFrames);
                }
            } else {
                unhookedTakeoverFrames = 0;
                warnedUnhookedTakeover = false;
            }
            // ---- hand-off: Particle Rain owns the rain curtain ----
            //
            // PR cancels vanilla's rain pass at HEAD, which means our suppression hook is never
            // consulted and our own curtain (drawn from AFTER_WEATHER) would land on top of PR's
            // rain: two rains, one of them blind to the other's settings. Standing down leaves
            // one rain, drawn by the mod that was installed for it.
            //
            // Two exceptions, both deliberate: a diagnostics lock (/dsprecip lock) always draws,
            // because the lock exists to show OUR layer whatever else is installed; and the
            // config option lets a pack compare the two on purpose.
            // Detection is behavioural, not a mod list: RouteFuse watches whether vanilla has asked
            // us at all while rain is falling, which catches Particle Rain and Simple Clouds (both
            // cancel the vanilla rain pass before it reaches us) and anything else that ever does
            // the same. Presence is only used for the message.
            final boolean someoneElseOwnsRain = !locked
                    && RouteFuse.rainRouteForeign()
                    && !(config != null && config.weatherOptions.gradedRainOverParticleRain);
            now = enabled
                    && !someoneElseOwnsRain
                    && PrecipitationIntensity.takeoverReady()
                    // The dimension must actually point at us (CUSTOM_WEATHER_EFFECTS
                    // = dsurround:graded_precipitation). When it does not, vanilla never
                    // consults our renderer and we must not draw either, or the world
                    // shows two rains. hookCalls is incremented before this runs, so the
                    // first frame the hook fires is already a takeover frame.
                    && hookCalls > 0;
        }
        if (now != lastActive) {
            lastActive = now;
            note("takeover=%s intensity=%.3f peak=%.3f hookCalls=%d tickHooks=%d renderCalls=%d config=%s locked=%s rain-owner=%s broken=%s",
                    now,
                    PrecipitationIntensity.intensity(),
                    PrecipitationIntensity.peak(),
                    hookCalls,
                    tickHooks,
                    renderCalls,
                    Client.Config == null ? "null" : String.valueOf(Client.Config.weatherOptions.enableGradedPrecipitation),
                    PrecipitationIntensity.forced() >= 0F,
                    RouteFuse.rainRouteForeign() ? "another mod" : "dsr",
                    broken);
        }
        return now;
    }

    /**
     * Set once the weather-renderer slot has been inspected in this world.
     *
     * <p>Reset when the world is left, because the next server may load a different set of mods'
     * registrations - a check that only ever runs once per game session would miss exactly the
     * pack that added a cloud mod between sessions.</p>
     */
    private static volatile boolean effectsOwnershipChecked = false;

    /**
     * One-shot check that the {@code dsurround:graded_precipitation} registration is still ours.
     *
     * <p>This is the 26.1 shape of the 1.20.1/1.21.1 overworld-effects re-check. There the silent
     * loss was Forge's registry keeping the <em>last</em> {@code overworld} registration; here it is
     * {@code RegisterCustomEnvironmentEffectRendererEvent}, which is a plain
     * {@code Map.put} - a second registration under our key does not conflict, does not log and
     * does not crash, it simply becomes the renderer vanilla asks, and every counter we own stops
     * moving. From the player's side "the rain mod stopped working"; from the log's side nothing
     * happened.</p>
     *
     * <p>Deliberately limited to the overworld, exactly as on the other versions: the nether and
     * the end are not where the graded rain lives, and reporting them would be noise. Called from
     * {@link #active()}, which runs every frame, so the answer is noticed in the frame the world
     * finishes loading rather than at the next storm.</p>
     *
     * <p>Fail-closed: a lookup we cannot read, or a null answer, is not a verdict. Only a renderer
     * that is <em>present and not ours</em> is reported.</p>
     */
    private static void checkEffectsOwnership() {
        if (effectsOwnershipChecked)
            return;
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(Level.OVERWORLD))
            return;
        // The registration event has not run yet: no verdict to give.
        if (registeredWeatherRenderer == null)
            return;
        effectsOwnershipChecked = true;
        final CustomWeatherEffectRenderer resolved;
        try {
            resolved = CustomEnvironmentEffectsRendererManager.getCustomWeatherEffectRenderer(WEATHER_RENDERER_KEY);
        } catch (Throwable t) {
            // Unreadable is not "lost" - see the class comment on failing closed.
            return;
        }
        if (resolved == null || resolved instanceof GradedPrecipitation)
            return;
        RouteFuse.noteCapabilityLost(RouteFuse.Capability.RAIN,
                "another mod registered a custom weather renderer under dsurround:graded_precipitation"
                        + " after us, so the rain pass is no longer ours to suppress");
        RouteFuse.noteCapabilityLost(RouteFuse.Capability.CLOUD,
                "the same pass carries the sky event's cloud suppression");
    }

    /** Diagnostics only. Logging must never be able to break rendering. */
    private static void note(String fmt, Object... args) {
        try {
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .info("Precipitation: " + fmt, args);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Same contract as {@link #note}, at warn level - for the one failure mode
     * that degrades silently to vanilla and must be visible in a normal log. */
    private static void noteWarn(String fmt, Object... args) {
        try {
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .warn("Precipitation: " + fmt, args);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static final class GradedPrecipitation implements CustomWeatherEffectRenderer {
        @Override
        public boolean renderSnowAndRain(
                net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState,
                net.minecraft.client.renderer.state.level.WeatherRenderState weatherRenderState,
                net.minecraft.client.renderer.MultiBufferSource bufferSource,
                Vec3 cameraPos) {
            hookCalls++;
            RouteFuse.feed(RouteFuse.Route.RAIN_RENDER);
            // The witness for "someone else is drawing the rain" - see noteRainRenderHook. tickRain
            // below deliberately does NOT feed it: nothing cancels tickRain, so it would keep the
            // detector asleep while a rain mod cancels this very call.
            RouteFuse.noteRainRenderHook();
            return active();
        }

        @Override
        public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
            // Counted before the answer, and the answer is unconditional, so the
            // counter reads "vanilla asked" rather than "we took it over". That is
            // the whole point - see the field comment on tickHooks.
            tickHooks++;
            RouteFuse.feed(RouteFuse.Route.RAIN_RENDER);
            // The caller is `if (renderer != null && renderer.tickRain(...))
            // return;` - true means vanilla stops, false means vanilla carries
            // on. We want it to carry on: tickRainParticles is the only place
            // the ground splash positions and the two rain one-shots come from,
            // and N7 grades both from the inside (see
            // MixinWeatherEffectRenderer). Offsets: ifeq 29 at 25, return at 28.
            return false;
        }
    }

    private static void onAfterWeather(RenderLevelStageEvent.AfterWeather event) {
        // Counted before the active() gate so the diagnostics can tell "the stage never
        // fires" (stays 0) apart from "the stage fires but we decline to draw".
        renderCalls++;
        if (!active())
            return;
        try {
            render();
        } catch (Throwable t) {
            // Never let a render failure take the frame down; the next frame falls
            // back to vanilla because active() drives the takeover too.
            broken = true;
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .error(t, "PrecipitationRenderer threw while rendering precipitation");
        }
    }

    private static void render() {
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null)
            return;

        final float intensity = PrecipitationIntensity.intensity();
        final float peak = PrecipitationIntensity.peak();
        final float alphaRatio = peak > 0.0001F
                ? (float) Math.pow(Mth.clamp(intensity / peak, 0F, 1F), RAIN_ALPHA_GAMMA)
                : 0F;
        if (alphaRatio <= 0F)
            return;

        final int rainLevel = levelIndex(intensity);
        final int range = RANGE;
        final int ticks = (int) level.getGameTime();
        final float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        final int playerX = Mth.floor(player.getX());
        final int playerY = Mth.floor(player.getY());
        final int playerZ = Mth.floor(player.getZ());

        refreshCaches(level, ticks);
        // WIND：风向/风速只依赖游戏时钟，每帧调一次即可，不需要 tick 钩子。
        // 强度里已经折进了离散阵风，gustAmplitude 是本场事件摇出的阵风幅度。
        // 最后一个参数是群系风力系数：多风的地方风更大、密林里风更小。
        // 它在 PrecipitationIntensity 里每 tick 更新一次，这里只是取用 ——
        // 千万别改成在这里查 BiomeInfo，更不要放进 per-column 循环。
        WindField.update(ticks, partialTick, intensity, PrecipitationIntensity.gustAmplitude(),
                PrecipitationIntensity.windScale());


        if (PrecipitationIntensity.forced() >= 0F) {
            // Diagnostics lock: draw the locked level as whatever this place
            // actually precipitates, not as rain everywhere. The lock used to
            // force RAIN because the per-column phase gate needs
            // level.isRaining() to be true - see potentialPhase(), which asks
            // the same question without that test.
            drawPhase(mc, level, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.RAIN, RAIN_TEXTURES[rainLevel], 1F, false, true, intensity);
            drawPhase(mc, level, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.SNOW, SNOW_TEXTURES[rainLevel], 1F, true, true, intensity);
        } else {
            drawPhase(mc, level, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.RAIN, RAIN_TEXTURES[rainLevel], alphaRatio, false, false, intensity);
            drawPhase(mc, level, cam, partialTick, ticks, range, playerX, playerY, playerZ,
                    Biome.Precipitation.SNOW, SNOW_TEXTURES[rainLevel], alphaRatio, true, false, intensity);
        }
    }

    private static void drawPhase(Minecraft mc, ClientLevel level, Vec3 cam, float partialTick,
                                  int ticks, int range, int playerX, int playerY, int playerZ,
                                  Biome.Precipitation want, Identifier texture,
                                  float alphaRatio, boolean snow, boolean ignorePhase,
                                  float intensity) {
        var dustTexture = mc.getTextureManager().getTexture(texture);
        final int maxQuads = (range * 2 + 1) * (range * 2 + 1) * MAX_LAYERS;

        try (ByteBufferBuilder byteBufferBuilder =
                     ByteBufferBuilder.exactlySized(maxQuads * DefaultVertexFormat.PARTICLE.getVertexSize() * 4)) {
            BufferBuilder bufferBuilder =
                    new BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

            int quads = buildColumns(level, cam, bufferBuilder, partialTick, ticks, range,
                    playerX, playerY, playerZ, want, alphaRatio, snow, ignorePhase, intensity);
            if (quads == 0)
                return;

            GpuBuffer vertexBuffer;
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            try (MeshData mesh = bufferBuilder.buildOrThrow()) {
                vertexBuffer = RenderPipelines.WEATHER_NO_DEPTH_WRITE.getVertexFormat()
                        .uploadImmediateVertexBuffer(mesh.vertexBuffer());
                var autoIndices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                indexBuffer = autoIndices.getBuffer(mesh.drawState().indexCount());
                indexType = autoIndices.type();
            }

            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrix(),
                            new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());

            var weatherTarget = OutputTarget.WEATHER_TARGET.getRenderTarget();
            GpuTextureView colorTexture = weatherTarget.getColorTextureView();
            GpuTextureView depthTexture = weatherTarget.getDepthTextureView();
            var renderPipeline = Minecraft.useShaderTransparency()
                    ? RenderPipelines.WEATHER_DEPTH_WRITE
                    : RenderPipelines.WEATHER_NO_DEPTH_WRITE;

            try (var renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "DSurround Precipitation", colorTexture,
                            OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
                renderPass.setPipeline(renderPipeline);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.bindTexture("Sampler2", mc.gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.setVertexBuffer(0, vertexBuffer);
                renderPass.bindTexture("Sampler0", dustTexture.getTextureView(),
                        RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
                renderPass.drawIndexed(0, 0, quads * 6, 1);
            }
        }
    }

    private static int buildColumns(ClientLevel level, Vec3 cam, BufferBuilder buffer, float partialTick,
                                    int ticks, int range, int playerX, int playerY, int playerZ,
                                    Biome.Precipitation want, float alphaRatio, boolean snow,
                                    boolean ignorePhase, float intensity) {
        final int wanted = want.ordinal();
        final double animClock = (double) ticks + partialTick;
        int quads = 0;

        for (int gridZ = playerZ - range; gridZ <= playerZ + range; gridZ++) {
            for (int gridX = playerX - range; gridX <= playerX + range; gridX++) {
                long colKey = BlockPos.asLong(gridX, 0, gridZ);
                long[] col = surfaceCache.get(colKey);
                if (col == null) {
                    int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gridX, gridZ);
                    col = new long[] { surface };
                    surfaceCache.put(colKey, col);
                }
                final int surface = (int) col[0];
                final int k2 = Math.max(playerY - range, surface);
                final int l2 = Math.max(playerY + range, surface);
                if (k2 >= l2)
                    continue;

                long phaseKey = BlockPos.asLong(gridX, k2, gridZ);
                if (ignorePhase) {
                    // Locked: phase() would answer NONE everywhere because the
                    // world is not raining, so ask what this column would drop
                    // instead. Not cached - a lock is a diagnostics tool and the
                    // lookup is per column per frame only while one is held.
                    cursor.set(gridX, k2, gridZ);
                    if (PrecipitationIntensity.potentialPhase(cursor).ordinal() != wanted)
                        continue;
                } else {
                    if (!phaseCache.containsKey(phaseKey)) {
                        cursor.set(gridX, k2, gridZ);
                        phaseCache.put(phaseKey, PrecipitationIntensity.phase(cursor).ordinal());
                    }
                    if (phaseCache.get(phaseKey) != wanted)
                        continue;
                }

                final int seed = (gridZ << 16) ^ gridX;

                // Density channel one: which columns exist at all. STRATIFIED, not
                // random - see columnRank(). An independent per-column coin flip
                // clumps, and at low intensity the survivors ended up lopsided
                // around the player: a few curtains out at the edge of the grid and
                // none overhead. Stratifying bounds the gaps, so turning the rain
                // down thins it evenly across the plane instead of punching holes
                // in it.
                final float density = Math.max(intensity, MIN_GEOMETRY_INTENSITY);
                final float columnHash = columnRank(gridX, gridZ);

                // The rank has to be world-anchored or the field shimmers as the
                // player walks, which leaves the player free to stand in one of its
                // gaps. This lifts the threshold for the columns around them. It is
                // a smooth function of the camera position so walking cannot pop
                // it, and it is scaled down towards zero intensity so a storm
                // building from nothing does not arrive as a disc hugging the
                // player.
                final double centreX = gridX + 0.5 - cam.x;
                final double centreZ = gridZ + 0.5 - cam.z;
                final float centreDist2 = (float) (centreX * centreX + centreZ * centreZ)
                        / (CENTER_RADIUS * CENTER_RADIUS);
                final float centreWeight = centreDist2 >= 1F
                        ? 0F
                        : CENTER_BIAS * (1F - centreDist2) * (1F - centreDist2)
                        * Mth.clamp(intensity / CENTER_BIAS_FULL_AT, 0F, 1F);
                final float threshold = density + centreWeight;

                final float columnFade = (threshold - columnHash) / COLUMN_FADE_BAND;
                if (columnFade <= 0F)
                    continue;
                final float columnAlpha = columnFade > 1F ? 1F : columnFade;

                // Density channel two: how wide each curtain is.
                final float widthScale = STRIP_MIN_SCALE + STRIP_RANGE * density;

                // Density channel three: streaks per curtain. Faded in over a band
                // rather than switched on at a threshold - see SECOND_LAYER_FROM.
                final float secondWeight = Mth.clamp(
                        (density - SECOND_LAYER_FROM) / (SECOND_LAYER_TO - SECOND_LAYER_FROM),
                        0F, 1F);
                final int layers = secondWeight > 0F ? MAX_LAYERS : 1;

                final int idx = (gridZ - playerZ + 16) * 32 + gridX - playerX + 16;
                final float stripX = STRIP_X[idx] * widthScale;
                final float stripY = STRIP_Y[idx] * widthScale;
                // ---- WIND：这一幕的倾斜量 Δu（加在顶顶点上） ----
                // ★ 风向必须投影到**几何真正用的那条横轴**上，也就是 (stripX, stripY)。
                // 之前投影用的是"相对相机的连续切向"，与查表来的横轴差最多 90 度：
                // 远处两者几乎重合看不出来，近处的幕（尤其脚下那一幕，查表 fallback
                // 是固定 +X，连续切向却是任意方向）符号直接翻 —— 这就是"贴脸的雨
                // 朝反方向斜"和"抬头看四周像旋风"。
                final float axisLen = Mth.sqrt(stripX * stripX + stripY * stripY);
                final float axisX = axisLen > 0.0001F ? stripX / axisLen : 1F;
                final float axisZ = axisLen > 0.0001F ? stripY / axisLen : 0F;
                final float windT = WindField.tangentialAlong(axisX, axisZ);
                final float fallBPT = snow
                        ? SNOW_FALL_SPEED_BPT
                        : RAIN_FALL_SPEED_MIN_BPT + RAIN_FALL_SPEED_RANGE_BPT * intensity;
                // 条带实际宽度（格）= 2 x |(stripX, stripY)|。雨/雪正好等于
                // widthScale，直接算出来就不用再抄一遍半宽常量。
                final float stripWidth = 2F * Mth.sqrt(stripX * stripX + stripY * stripY);
                final float tiltTan = stripWidth > 0.0001F
                        ? Mth.clamp((snow ? SNOW_TILT_GAIN : RAIN_TILT_GAIN) * windT / fallBPT,
                                -MAX_TILT_TAN, MAX_TILT_TAN) * WindField.SLANT_SIGN
                        : 0F;
                // Δu = 幕高(格) x tan(倾角) / 条带宽(格)。
                // 这一个量同时给出两件事：竖条在几何体上变斜（形状倾斜），
                // 以及竖条沿倾斜方向运动（运动倾斜）—— v 里已经含滚动量，
                // 剪切把滚动量自动耦合进了 U，不需要额外的时间累加器。
                // 每幕的小幅扰动：原来所有幕的倾角完全由几何和风向决定，太规整。
                // 用世界锚定的 columnHash（0..1）映射成 -1..1 —— 必须世界锚定，
                // 用逐帧随机数会让幕在玩家走动/每帧重算时 shimmer。
                final float tiltJitter = 1F + RAIN_TILT_JITTER * ((columnHash - 0.5F) * 2F);
                final float slantU = stripWidth > 0.0001F
                        ? (l2 - k2) * tiltTan * tiltJitter / stripWidth
                        : 0F;

                // ---- WIND：风致拖影（trailUV） ----
                // 剪切只是把整片幕的顶点沿 u 剪斜，它在贴图上"让开"的量是
                // tan x 特征高度 —— 对 16px 的雨丝有 5.8px 所以雨看着对，
                // 对 1~3px 的雪/沙尘只有 0.36~1.08px，等于没有。拖影是把这一帧
                // 沿风的投影方向多画几份、每份 u 错开一点，合成出一条够长的划痕。
                //
                // 只有雪走这条：雨丝本身就是长线，剪切已经够用，再叠拖影会变雨幕。
                // 沙尘在 WeatherStormHandler，形态同样是 1px 的点，下一步照搬。
                //
                // 风速 0 => 拖影 0 => samples 1 => 与今天逐顶点完全相同，
                // 不改任何贴图 —— "无风"的表现照旧存在。
                final float trailPx = snow && TRAIL_ON_FENCE && stripWidth > 0.0001F
                        ? Math.min(TRAIL_MAX_PX,
                                Math.abs(windT) * TRAIL_TICKS / stripWidth * TRAIL_TEX_PX)
                        : 0F;
                final int samples = trailPx > 0.0001F
                        ? Mth.clamp((int) Math.ceil(trailPx / TRAIL_GAP_PX), 1, TRAIL_MAX_SAMPLES)
                        : 1;
                // u 增加的方向就是幕的横轴，所以偏移量直接加在 u 上，符号取风在该
                // 轴上的投影符号 —— 和 slantU 用的是同一个 windT，方向必然一致。
                final float trailU = trailPx / TRAIL_TEX_PX * (windT >= 0F ? 1F : -1F);
                // 亮度按快门时间内的占空比给：划痕拉长几倍，每处就只亮几分之一。
                // 只由拖影长度决定、不由 samples 决定，所以 samples 从 2 跳到 3
                // 时亮度不会跟着跳。静歇期 trailPx < 1px => fade = 1，精确退化。
                final float trailFade = samples > 1
                        ? Math.max(TRAIL_ALPHA_FLOOR, 1F / (1F
                                + Math.max(0F, trailPx - TRAIL_ALPHA_DEAD_PX) / TRAIL_ALPHA_FEATURE_PX))
                        : 1F;

                // 流幕的强度 ∝ 风速：无风时 0 ⇒ 这一层根本不画，
                // 于是"无风"的表现仍然是围栏那一层原封不动的样子。
                final float windNorm = Mth.clamp(
                        Math.abs(WindField.speedPerTick()) / STREAMER_FULL_WIND_BPT, 0F, 1F);
                final float streamerWeight = snow ? STREAMER_GAIN * windNorm : 0F;

                // Measured from the camera rather than from the player's block, so
                // the edge falloff is centred on where the view actually is instead
                // of being off by up to a block.
                final double d6 = centreX;
                final double d7 = centreZ;
                final float f3 = Mth.sqrt((float) (d6 * d6 + d7 * d7)) / range;
                final float alpha = (snow
                        ? ((1.0F - f3 * f3) * 0.3F + 0.5F)
                        : ((1.0F - f3 * f3) * 0.5F + 0.5F)) * alphaRatio * columnAlpha;
                if (alpha <= 0.002F)
                    continue;

                int light;
                if (lightCache.containsKey(phaseKey)) {
                    light = lightCache.get(phaseKey);
                } else {
                    int raw = LevelRenderer.getLightCoords(level, cursor.set(gridX, k2, gridZ));
                    // Round 12: a snowdrift a block deep buries this sample point inside
                    // solid snow, and the light inside a solid block is the light of its
                    // own interior - zero - so the whole curtain went black exactly when
                    // the drift reached a full block. The snow units on the plants never
                    // did: they are lit from their own anchor, which is never inside a
                    // block. Only a totally black reading is questioned, and only upwards:
                    // a normally lit column is left exactly as it was.
                    if (raw == 0) {
                        for (int step = 1; step <= 3; step++) {
                            final int up = k2 + step;
                            if (level.isOutsideBuildHeight(up))
                                break;
                            final int lit = LevelRenderer.getLightCoords(level, cursor.set(gridX, up, gridZ));
                            if (lit != 0) {
                                raw = lit;
                                break;
                            }
                        }
                    }

                    // Rain, snow and dust all take the light where they actually
                    // are, so all three dim after dark.
                    //
                    // This used to brighten snow towards full light -
                    // (combinedLight * 3 + LightTexture.FULL_BRIGHT) / 4 - so
                    // flakes stayed visible after dark. That is a 1.12.2
                    // behaviour and dropping it was right, but it was never what
                    // kept the curtains bright at night: that was the uv2
                    // argument order at the vertex - see the note there.
                    //
                    // Vanilla's snow branch does keep a smaller floor of its own
                    // ((x * 3 + 240) / 4 per axis, 1.20.1
                    // LevelRenderer.renderSnowAndRain); rain keeps none. We do not
                    // copy it - switching it on is a look decision, not a fix.
                    light = raw;
                    lightCache.put(phaseKey, light);
                }

                for (int layer = 0; layer < layers; layer++) {
                    // The extra streak fades in instead of appearing at full
                    // strength, so curtain density is continuous in intensity.
                    final float layerAlpha = layer == 0 ? 1F : secondWeight;
                    columnRandom.setSeed(seed + layer * 0x9E3779B9);
                    float u0, u1, v0, v1;
                    if (snow) {
                        final double d8 = ((ticks & 511) + partialTick) / 512.0;
                        final double d9 = columnRandom.nextDouble()
                                + animClock * SNOW_DRIFT_JITTER * (float) columnRandom.nextGaussian();
                        final double d10 = columnRandom.nextDouble()
                                + animClock * (float) columnRandom.nextGaussian() * 0.001D;
                        u0 = (float) d9;
                        u1 = 1F + (float) d9;
                        v0 = k2 * 0.25F + (float) d8 + (float) d10;
                        v1 = l2 * 0.25F + (float) d8 + (float) d10;
                    } else {
                        // 1.12.2: ((renderCount + seed) & 31) - Java binds + tighter than &,
                        // so this is ((ticks + seed) & 31), not ticks + (seed & 31).
                        final double d5 = (((ticks + seed) & 31) + partialTick) / 32.0
                                * (1.5D + 2.5D * intensity + columnRandom.nextDouble());
                        u0 = 0F;
                        u1 = 1F;
                        v0 = k2 * 0.25F + (float) d5;
                        v1 = l2 * 0.25F + (float) d5;
                    }

                    final float x0 = gridX - stripX + 0.5F - (float) cam.x;
                    final float z0 = gridZ - stripY + 0.5F - (float) cam.z;
                    final float x1 = gridX + stripX + 0.5F - (float) cam.x;
                    final float z1 = gridZ + stripY + 0.5F - (float) cam.z;
                    final float y0 = k2 - (float) cam.y;
                    final float y1 = l2 - (float) cam.y;
                    final int alphaInt = (int) (alpha * layerAlpha * trailFade * 255F);
                    // White for both: 1.12.2 tints only DUST (biome dustColor), rain and
                    // snow use Color.WHITE.
                    // setLight(light) is setUv2(light & 0xFFFF, light >> 16), i.e.
                    // (block, sky) - the lightmap's own axis order, and exactly
                    // what vanilla rain passes. Do not swap it.
                    final int argb = (alphaInt << 24) | 0x00FFFFFF;

                    for (int smp = 0; smp < samples; smp++) {
                        // 采样沿拖影方向均匀分布：只画一份时 off=0，与改动前逐顶点相同。
                        final float off = samples > 1
                                ? trailU * ((float) smp / (float) (samples - 1))
                                : 0F;
                        buffer.addVertex(x0, y0, z0).setUv(u0 + off, v0).setColor(argb).setLight(light);
                        buffer.addVertex(x1, y0, z1).setUv(u1 + off, v0).setColor(argb).setLight(light);
                        buffer.addVertex(x1, y1, z1).setUv(u1 + off + slantU, v1).setColor(argb).setLight(light);
                        buffer.addVertex(x0, y1, z0).setUv(u0 + off + slantU, v1).setColor(argb).setLight(light);
                        quads++;
                    }
                }
                // ==================== WIND：流幕（与风向平行的一层） ====================
                // 围栏的横轴围成一圈，所以任何"沿横轴"的表达在每片幕上方向都不同 ——
                // 拖影在围栏上实测划痕方向铺满 180°、标准差 39.6°，看着就是乱。
                // 流幕把横轴换成风向本身，于是拖影在每一片流幕上都朝同一个方向
                // （实测标准差 0°、100% 的方向能量落在 ±15° 内）；而且因为所有流幕
                // 横轴相同，横向漂移也第一次能用 —— 全体朝同一个方向走，不再是
                // 围栏上那种"绕圈/横扫"。
                if (streamerWeight > 0F) {
                    final float sh = STRIP_HALF_WIDTH * widthScale;
                    // 扇形：轴 = 风向 &plusmn; STREAMER_FAN_DEG，偏移由世界锚定的
                    // columnHash 决定（逐帧随机会让幕 shimmer）。严格平行（0&deg;）
                    // 顺风看会完全 edge-on、覆盖率归零，所以只能当附加层、永远是
                    // 少数派；扇形不归零，于是它可以当主层。
                    final float fanA = WindField.angleRad()
                            + (columnHash * 2F - 1F) * STREAMER_FAN_DEG * DEG2RAD;
                    final float ssx = Mth.cos(fanA) * sh;
                    final float ssz = Mth.sin(fanA) * sh;
                    final float sWidth = 2F * sh;
                    // 横轴就是风向 ⇒ 风在它上面的投影 = 满风速（不用再点乘一遍）
                    final float sSpeed = Math.abs(WindField.speedPerTick());
                    final float sTrailPx = Math.min(TRAIL_MAX_PX,
                            sSpeed * TRAIL_TICKS / sWidth * TRAIL_TEX_PX);
                    final int sSamples = Mth.clamp((int) Math.ceil(sTrailPx / TRAIL_GAP_PX),
                            1, TRAIL_MAX_SAMPLES);
                    final float sTrailU = sTrailPx / TRAIL_TEX_PX * WindField.SLANT_SIGN;
                    final float sFade = sSamples > 1
                            ? Math.max(TRAIL_ALPHA_FLOOR, 1F / (1F
                                    + Math.max(0F, sTrailPx - TRAIL_ALPHA_DEAD_PX) / TRAIL_ALPHA_FEATURE_PX))
                            : 1F;
                    final float sTan = Mth.clamp((snow ? SNOW_TILT_GAIN : RAIN_TILT_GAIN)
                            * sSpeed / fallBPT, -MAX_TILT_TAN, MAX_TILT_TAN) * WindField.SLANT_SIGN;
                    final float sSlant = (l2 - k2) * sTan / sWidth;
                    // 漂移：采样窗口沿 -u 走，纹理内容就朝 +横轴（= 风向）走。
                    // % 1.0：把偏移永远限制在一个纹理周期内。不这么做它随游戏时长
                    // 线性增长（一小时就能到几千），float 精度会掉；取模 1.0 则回绕时
                    // 正好差一个完整周期、纹理是 REPEAT，跳变无缝。
                    final float driftU = (float) -(animClock * STREAMER_DRIFT_UPT
                            * windNorm * STREAMER_DRIFT_SIGN % 1.0);
                    columnRandom.setSeed(seed ^ 0x5bf03635);
                    final double e8 = ((ticks & 511) + partialTick) / 512.0;
                    final double e9 = columnRandom.nextDouble()
                            + animClock * SNOW_DRIFT_JITTER * (float) columnRandom.nextGaussian();
                    final double e10 = columnRandom.nextDouble()
                            + animClock * (float) columnRandom.nextGaussian() * 0.001D;
                    final float su0 = (float) e9 + driftU;
                    final float su1 = su0 + 1F;
                    final float sv0 = k2 * 0.25F + (float) e8 + (float) e10;
                    final float sv1 = l2 * 0.25F + (float) e8 + (float) e10;
                    final float sx0 = gridX - ssx + 0.5F - (float) cam.x;
                    final float sz0 = gridZ - ssz + 0.5F - (float) cam.z;
                    final float sx1 = gridX + ssx + 0.5F - (float) cam.x;
                    final float sz1 = gridZ + ssz + 0.5F - (float) cam.z;
                    final float sy0 = k2 - (float) cam.y;
                    final float sy1 = l2 - (float) cam.y;
                    final int sAlphaInt = (int) (alpha * streamerWeight * sFade * 255F);
                    if (sAlphaInt > 0) {
                        for (int smp = 0; smp < sSamples; smp++) {
                            // 尾巴留在上风侧：采样从当前位置往后退
                            final float soff = sSamples > 1
                                    ? -sTrailU * ((float) smp / (float) (sSamples - 1))
                                    : 0F;
                            buffer.addVertex(sx0, sy0, sz0).setUv(su0 + soff, sv0).setColor((sAlphaInt << 24) | 0x00FFFFFF).setLight(light);
                            buffer.addVertex(sx1, sy0, sz1).setUv(su1 + soff, sv0).setColor((sAlphaInt << 24) | 0x00FFFFFF).setLight(light);
                            buffer.addVertex(sx1, sy1, sz1).setUv(su1 + soff + sSlant, sv1).setColor((sAlphaInt << 24) | 0x00FFFFFF).setLight(light);
                            buffer.addVertex(sx0, sy1, sz0).setUv(su0 + soff + sSlant, sv1).setColor((sAlphaInt << 24) | 0x00FFFFFF).setLight(light);
                            quads++;
                        }
                    }
                }
            }
        }
        return quads;
    }

    private static void refreshCaches(ClientLevel level, int ticks) {
        var dim = level.dimension();
        if (dim != cacheDimension) {
            cacheDimension = dim;
            clearCaches(ticks);
        } else if (ticks - cacheTick >= CACHE_TTL) {
            clearCaches(ticks);
        }
    }

    private static void clearCaches(int ticks) {
        surfaceCache.clear();
        phaseCache.clear();
        lightCache.clear();
        cacheTick = ticks;
    }

    /**
     * 1.12.2 {@code Weather.Properties.mapRainStrength}: first level at or above the
     * intensity. Index into {@link #LEVELS}.
     */
    /**
     * How far past a boundary intensity must travel before the texture level
     * switches. Intensity is continuous but the levels are not, so without this a
     * value hovering on a boundary flips the texture back and forth every few
     * frames - the single most visible way graded rain can look broken.
     */
    private static final float LEVEL_HYSTERESIS = 0.02F;
    private static int lastLevel = -1;

    /** Unhysteretic mapping - which band an intensity falls in. Pure. */
    private static int rawLevel(float intensity) {
        for (int i = 0; i < LEVELS.length; i++)
            if (intensity <= LEVELS[i])
                return i;
        return LEVELS.length - 1;
    }

    /**
     * 1.12.2 {@code Weather.Properties.mapRainStrength}: first level at or above the
     * intensity. Index into {@link #LEVELS}.
     *
     * <p>Carries hysteresis so the level only changes once intensity has clearly
     * committed to the next band. Reset when a lock is applied, because a lock is a
     * deliberate jump to a level and must land immediately.</p>
     */
    private static int levelIndex(float intensity) {
        final int raw = rawLevel(intensity);
        final int prev = lastLevel;
        if (prev < 0 || raw == prev)
            return lastLevel = raw;
        if (raw > prev && intensity > LEVELS[prev] + LEVEL_HYSTERESIS)
            return lastLevel = raw;
        if (raw < prev && intensity < (prev > 0 ? LEVELS[prev - 1] : 0F) - LEVEL_HYSTERESIS)
            return lastLevel = raw;
        return prev;
    }

    /** Name of the texture level an intensity maps to - diagnostics only. */
    public static String levelName(float intensity) {
        return LEVEL_NAMES[rawLevel(intensity)];
    }

    /** Called when a lock is applied so the next frame lands on the locked level. */
    public static void resetLevelHysteresis() {
        lastLevel = -1;
    }

    /**
     * Diagnostics only: how many times our draw stage fired. Stays 0 when that stage
     * never fires (a rewritten render pipeline, most often Sodium or Embeddium),
     * which is the one failure mode {@link #hookCalls()} cannot see - the suppression
     * hook can be consulted all day while our own draw pass never runs.
     */
    public static int renderCalls() {
        return renderCalls;
    }

    /** Diagnostics only: how many times vanilla asked whether it should draw rain. */
    public static int hookCalls() {
        return hookCalls;
    }

    /**
     * Diagnostics only: how many times vanilla asked {@code tickRain}, the pass
     * that spawns ground splash particles and plays the rain sound. See the field
     * comment on {@code tickHooks} for what the value tells you.
     */
    public static int tickHooks() {
        return tickHooks;
    }

    /** Diagnostics only: true after a render failure permanently disabled the pass. */
    public static boolean isBroken() {
        return broken;
    }
}
