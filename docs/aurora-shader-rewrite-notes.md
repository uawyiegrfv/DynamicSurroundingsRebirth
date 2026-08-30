# 极光 Shader 重写备忘（完整版）

> 本文记录 26.1 极光 shader 从移植、重写、回退到经典版的完整经验、技术结论、
> 参数-视觉映射和用户视觉需求，供下一次 clean-room 重写时直接参考。
> 最后更新：2026-08-16。

---

## 1. 当前状态（截至 2026-08-16）

- **D 盘是唯一工作区**：`D:\claude code\dsurround-neoforge-26.1`
- git HEAD：`c3c4371 保存极光 shader 重制前的当前状态`
- 极光功能已回退到 **经典版**：`AuroraFactory.produce()` 只返回 `AuroraClassic`
- 仓库中**没有** shader 文件（`AuroraShader.java`、`AuroraRenderPipelines.java`、
  `assets/dsurround/shaders/` 均已移除）
- 已验证编译产物：`build/classes` 和 jar 内只有经典版类，无 shader 残留
- 经典版行为说明：`bandCount = random.nextInt(3) + 1`，即经典版会随机生成
  **1–3 条极光带**，每条带在 Z 轴上有 20–40 格偏移。这是原版行为，不是 bug。

### 1.1 构建 / 测试标准操作

```powershell
# 在 D 盘项目根目录执行
.\gradlew.bat build -x test --no-configuration-cache
.\gradlew.bat runClient --no-configuration-cache
```

- 产物位置：`D:\claude code\dsurround-neoforge-26.1\build\libs\`
- 运行目录：`D:\claude code\dsurround-neoforge-26.1\run\`
- **必须使用 `--no-configuration-cache`**，或先清掉 `.gradle\configuration-cache`。
  历史问题：D 盘克隆自 E 盘时带过来旧 configuration cache，里面嵌有 E 盘绝对路径，
  导致 D 盘 Gradle 构建实际落到 E 盘。

### 1.2 启动日志中可忽略的噪音

- `MouseHandler ... FramerateLimitTracker is null` 的 NPE：NeoForge 早期显示窗口
  收到鼠标事件的常见噪音，游戏会继续正常加载，不影响测试。
- `Unable to fetch version information ... versions.json`：版本检查网络失败，无害。

---

## 2. 视觉需求规格（重写提示词）

> 目标：clean-room 重写极光 shader，达到或超过 Mattenii 版观感，同时可 MIT 分发。
> 本节可以直接作为重写任务的核心提示词使用。

### 2.1 核心画面

一片**连续的大片极光幕帘**，主体由**竖直射线/条纹**构成，像真实极光的
ray structure：无数条沿高度方向拉长的光带，整体又像被风吹动的丝绸，
既有大范围的连续起伏，也有局部“小瓣样”的明暗与形状动态。

### 2.2 结构要求（几何）

1. **竖直射线是主体**：画面必须有清晰的竖直条纹/射线，沿高度方向拉长。
   不是水平横条，不是单纯的一团雾，也不是只有轮廓的空心带。
2. **射线的密度与粗细**：射线数量要够多，覆盖幕帘宽度，但单条射线不能太粗
   （太粗会变成一条条色块）。参考 clean-room v1：`ray = fbm(vec2(p.x * 4.5,
   p.y * 0.35 + t * 0.05))`——x 频率 4.5 左右的射线密度是“还行”的起点。
3. **射线的有机弯曲/倾斜**：竖直线不能像直尺一样死直。射线坐标中要加入
   **很小**的 y 依赖（倾斜）和 curtain 依赖（弯曲）。幅度必须小：
   第一眼仍要觉得是竖直射线，细看才有自然卷曲。
   参考失败值：`0.28 * p.y` 和 `0.65 * curtain` 太大，观感完全变样。
   建议起步值：倾斜 `0.03–0.08 * p.y`，弯曲 `0.10–0.20 * curtain`。
4. **连续大片银幕**：幕帘沿 ribbon 连续，**不能中间断开**、不能变成孤立的
   几段。整体像一整片幕布，而不是几条细线。
5. **上下边界**：下缘相对锐利，上缘柔和消散（这是极光真实形态）。
   参考值：`bottomMask = smoothstep(0.0, 0.14, p.y - bottom)`；
   `topMask = 1.0 - smoothstep(0.0, 0.38, p.y - top)`。
6. **左右边缘**：ribbon 两端要柔和淡出，不要戛然而止。
   参考：`edgeFade = smoothstep(0.0, 0.18, uv.x) * (1.0 - smoothstep(0.82, 1.0, uv.x))`。
7. **多层/深度**：可以用“背层 + 前层”制造视差，但背层必须很淡
   （alpha 0.35 或更低），否则叠层加色后会过亮。
8. **宽度**：整体宽度比 clean-room v1（`SCALE_XZ = 0.5`）略窄，
   目标 `SCALE_XZ = 0.42–0.45`。

### 2.3 动态要求（动画）

1. **时间尺度要分层**：
   - 慢速大幕浪：整片幕帘像被风吹动的布，周期约 10–30 秒。
   - 中速射线摆动：单条射线轻微左右摇摆，周期约 2–5 秒。
   - 快速闪烁：射线亮度有轻微 shimmer，周期 < 1 秒。
2. **局部“小瓣”动态**：要有原版 Mattenii 版那种小瓣状/褶皱状的局部明暗变化，
   让幕帘有布料感；但瓣状必须柔和，**不能变成一坨亮斑**。
   实现上：用低幅度 domain warp 或二次噪声调制，不要用高频大振幅折叠。
3. **禁止的动画问题**：
   - 不要像跑马灯一样只做水平平移。
   - 不要让射线集体同步摆动（要每处相位不同）。
   - 不要有突兀的闪烁或跳变。

### 2.4 颜色要求

1. **保持光谱感**：底部紫/蓝、主体绿色、顶部红/粉。用户对 clean-room v1
   的颜色“还行”，可接受，重写时沿用这个方向。
2. **参考混色**：
   - `violet = vec3(0.40, 0.26, 0.95)`
   - `green = vec3(0.16, 0.82, 0.38)`
   - `red = vec3(0.95, 0.16, 0.28)`
   - 绿：`greenMix = smoothstep(0.02, 0.38, p.y + 0.22 * curtain - 0.08 * ray)`
   - 红：`redMix = smoothstep(0.42, 0.88, p.y + 0.18 * ray - 0.10 * curtain)`
3. **饱和度**：不要荧光感过强；绿色为主，红/紫作上下点缀。
4. **颜色随时间**：可以随 GameTime 缓慢变化，但变化幅度要小；不要彩虹循环。

### 2.5 亮度与透明度

1. **亮度保守**：峰值亮度宁暗勿亮，避免“亮瞎眼”。加色混合下很容易过曝，
   默认参数要从低起步。
2. **参考数值**：
   - clean-room v1 `brightness = 1.55`：偏亮。
   - 下一版建议起步 `brightness = 0.9–1.1`，确认后再逐次 +0.1。
3. **透明度**：淡入淡出走顶点色 alpha（0–255），fragment 读 `vertexColor.a`。
   shader 内 alpha 只做乘数，不要把 alpha 写进 `fragColor.a`。
   `fragColor.a` 恒为 1.0（加色混合由 RGB 决定）。
4. **叠层**：背层 + 前层同时存在时，总亮度按加法叠加，计算总亮度时要
   乘以 `(1 + backLayerAlpha)` 的系数。例如 front=1.0、back=0.35 时，
   总峰值约 1.35。

### 2.6 必须做到（MUST）

1. 必须有竖直射线。
2. 竖直射线要有机变化（轻微弯曲/倾斜/摆动）。
3. 连续大片银幕，不断开。
4. 局部小瓣动态，但柔和、不亮斑。
5. 光谱颜色：底紫、中绿、顶红。
6. 亮度保守。
7. 宽度比 clean-room v1 略窄。
8. 一次只改一个视觉变量，改完进游戏确认。

### 2.7 禁止（MUST NOT）

1. 不要中间断开 / 分段落消失 / 变成孤立的几条。
2. 不要把“瓣状”做成亮斑、团块。
3. 不要过亮。
4. 不要做成完全水平的横条。
5. 不要把竖直射线做成完全笔直的直线（太机械）。
6. 不要把弯曲/倾斜做得太大（`0.28*p.y`、`0.65*curtain` 级别就是失败）。
7. 不要复制 Mattenii 的具体代码（clean-room 要求）。
8. 不要一次改多个视觉变量。

### 2.8 历次反馈的失败记录（务必避开）

| 版本 | 做法 | 结果 |
|---|---|---|
| clean-room v1 | 竖直线 + 亮度 1.55 + 宽度 0.5 | “还行，但不如上一版”；竖直线太笔直，整体只有一条长条 |
| clean-room v2 | 加上射线倾斜/弯曲 + 瓣状褶皱 + presence 分段 | “不行”；瓣状成一坨亮斑，中间断开 |
| clean-room v3 | 去掉分段/瓣状，宽度 0.42，亮度 1.32 | 用户觉得太亮/刺眼 |
| Mattenii + 弯曲 | 在 Mattenii 版上直接加大幅弯曲 | 用户说“跟之前完全不一样”，改动幅度过大 |

**结论**：竖直线结构保留，只加轻微弯曲/倾斜；幕帘保持连续；亮度起步值
用 1.0 左右；宽度 0.42–0.45；任何新结构先想清楚它会在画面上产生什么。

### 2.9 可直接复制的提示词（下一版重写用）

> 请 clean-room 重写极光 fragment shader，不参考 Mattenii 代码。
> 画面要求：连续的大片极光幕帘，主体是竖直射线/条纹，射线有轻微弯曲/倾斜/
> 摆动（第一眼仍是竖直射线），局部有小瓣状柔和动态但绝不出现亮斑。
> 颜色底部紫/蓝、主体绿、顶部红/粉。亮度保守（起步 brightness 约 1.0），
> 宽度比上一版略窄（SCALE_XZ 0.42–0.45）。幕帘下缘锐利、上缘柔和，
> ribbon 两端淡出。一次只实现一个结构变化，先保证连续大片银幕和竖直射线
> 成立，再逐步加入轻微弯曲、小瓣动态。

---

## 3. 26.1 自定义 shader 技术要点（已验证）

### 3.1 机制

- 26.1 无立即模式；自定义 shader = `RenderPipeline` + shader 文件 + `RenderType`
- shader 文件放在 `assets/<ns>/shaders/core/*.vsh` 和 `*.fsh`，GLSL 330 + `#moj_import`
- 在 MOD_BUS 监听 `RegisterRenderPipelinesEvent` 注册 pipeline
- `RenderType.create(name, RenderSetup.builder(pipeline).createRenderSetup())`
  （NeoForge AT 已把 `create` 公开）
- 渲染走 `Minecraft.renderBuffers().bufferSource().getBuffer(renderType)`
  （标准 MultiBufferSource 缓冲路径）

### 3.2 关键坑

1. **不能在 `RenderLevelStageEvent.AfterSky` 里做 immediate draw / 自定义 UBO 上传**
   - `AfterSky` 位于 framegraph 的 sky pass 内
   - 直接调用 `RenderSystem.getDynamicUniforms().writeTransform(...)` 会崩：
     `Close the existing render pass before performing additional commands`
   - 必须走 `MultiBufferSource` 缓冲，由游戏在安全时机统一 flush
2. **用 QUADS，不要用 TRIANGLE_STRIP**
   - `BufferSource.getBuffer` 对 `connectedPrimitives=true` 的模式不会合并批；
     第二次取同一 RenderType 会当场 `type.draw`，同样触发 framegraph 内绘制
3. **标准 RenderType 路径不能绑 per-frame 自定义 uniform**
   - 动态时间 → 用内置 `GameTime`（`#moj_import <minecraft:globals.glsl>`）
   - 静态参数（颜色、aspect）→ 用 `withShaderDefine` 烘焙成 pipeline 变体
   - 透明度/淡入淡出 → 用顶点色 `POSITION_TEX_COLOR`，fragment 读 `vertexColor.a`
4. **混合模式与原版一致**
   - `new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.SRC_ALPHA, SourceFactor.ONE, DestFactor.ZERO)`
   - 深度：`new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)`
   - `withCull(false)`
5. **shader 编译失败没有明显报错**：启动日志只会有 `[mojang/GlDevice] Couldn't compile`
   之类；看到画面异常先查编译日志，避免白调。
6. **GLSL 保留字坑**：`out` 是保留字，不能当变量名。
7. **流水线变体数量**：
   - Mattenii 版：24 调色板 × 2 aspect = 48 个 pipeline
   - clean-room 版：2 个 pipeline（只按 band 长度 64/128 的 aspect 区分）

### 3.3 顶点格式与绘制

- 顶点格式：`DefaultVertexFormat.POSITION_TEX_COLOR`，`VertexFormat.Mode.QUADS`
- 每 quad 4 个顶点，UV：底 `v=0`、顶 `v=1`；`u` 沿 band 从 0 到约 1
- 顶点色：`setColor(255, 255, 255, alphaByte)`，alpha 由 `getAlpha()` 计算
- 每个 band 一个 ribbon；`AuroraShader` 中可画背层+前层两遍

---

## 4. 参数-视觉效果映射表（重要）

> 调参时先看这张表，想清楚再改。`+` 表示增大，`-` 表示减小。

### 4.1 Java 参数

| 参数 | 位置 | 效果 |
|---|---|---|
| `SCALE_XZ` | `AuroraShader` | 控制极光整体水平宽度。增大→更宽；减小→更窄。目标 0.42–0.45 |
| `SCALE_Y` | `AuroraShader` | 控制极光高度。增大→更高；原版 10.0 |
| `BACK_LAYER_ALPHA_FACTOR` | `AuroraShader` | 背层亮度。增大→叠层更亮、更容易过曝；建议 ≤0.35 |
| `BACK_LAYER_SCALE_XZ/Y` | `AuroraShader` | 背层比前层大多少；太大会糊成一团 |
| `BACK_LAYER_Z_BIAS` | `AuroraShader` | 背层与前层的视差距离 |
| `alphaByte` | `AuroraShader` | 顶点 alpha；决定整体淡入淡出，不直接调 |

### 4.2 Fragment shader 参数

| 参数 | 效果 |
|---|---|
| `brightness` | 整体亮度倍率。过 1.2 容易刺眼；建议从 0.9–1.1 起步 |
| 射线 x 频率（如 `p.x * 4.5`） | 射线密度。越大射线越细密；越小越粗疏 |
| 射线 y 频率（如 `p.y * 0.35`） | 越大射线沿高度变化越多，越小越接近笔直竖线 |
| 倾斜项（如 `+ k * p.y` 加在 rayP.x） | 越大射线越斜。k 建议 0.03–0.08，不要 0.28 |
| 弯曲项（如 `+ k * curtain` 加在 rayP.x） | 越大射线越弯。k 建议 0.10–0.20，不要 0.65 |
| `warp * 1.1` 幅度 | domain warp 强度。过大→亮斑/团块；过小→无布料感 |
| `bottomMask` / `topMask` 的 smoothstep 宽度 | 下缘/上缘锐利程度。宽度越大越柔和 |
| `edgeFade` 范围 | ribbon 两端淡出长度。太小会看到硬边 |
| `greenMix` / `redMix` 位置 | 绿色/红色在高度上的分布 |
| `time` 速度系数 | 动画快慢。太大→乱跳；太小→不动 |

---

## 5. 协议经验

- 原版 1.12.2 `aurora.vert/frag` 来自 Mattenii 的 Aurora Lights，
  **CC BY-NC-SA 3.0**，不是 MIT。
- 如果要随 mod 以 MIT 分发，shader 必须 **clean-room 重写**：
  不复制 Mattenii 的 hash 常量、函数结构（`hashConst 12.9898,78.233`、`shash`、
  `fbm1..4`、`lights` 等具体表达）。
- 通用技术（值噪声、FBM、domain warp、光谱色带、aspect 校正 UV、加色混合）
  属于可用的通用想法，但代码要自己写。

---

## 6. 下一版 clean-room 的实现建议

### 6.1 分阶段实施（每阶段只改一个变量）

1. **阶段 A：连续银幕 + 竖直射线**
   - 只实现：值噪声 FBM 幕帘 + 竖直 ray + 上下边界 + edgeFade + 光谱色。
   - 参数：`SCALE_XZ = 0.45`、`brightness = 1.0`、背层 alpha 0.35。
   - 验收：用户确认“大片银幕、竖直射线、亮度合适”。
2. **阶段 B：射线轻微弯曲/倾斜**
   - 在 rayP 坐标加入小幅度倾斜/弯曲（先只加倾斜，确认后再加弯曲）。
   - 验收：用户确认“射线不死直了，但整体没变样”。
3. **阶段 C：小瓣动态**
   - 用低幅度 domain warp 或二次噪声调制射线亮度，制造小瓣明暗。
   - 验收：用户确认“有布料感，没有亮斑”。
4. **阶段 D：颜色/亮度/宽度精修**
   - 最后再调颜色分布、亮度、宽度。每次只动一个。

### 6.2 shader 结构参考

```glsl
// 自写 hash / value noise / FBM（不要用 Mattenii 的结构）
float hash21(vec2 p) { ... }
float vnoise(vec2 p) { ... }
float fbm(vec2 p) { ... }

void main() {
    vec2 uv = texCoord0;
    vec2 p = vec2(uv.x * ASPECT, uv.y);
    float t = time;

    // 1) 连续幕帘（domain warp，幅度小）
    vec2 warp = vec2(fbm(p + vec2(0.0, t * 0.04)),
                     fbm(p + vec2(4.7, 1.9)));
    float curtain = fbm(p * vec2(1.6, 0.7) + warp * 1.1 + vec2(0.0, t * 0.025));

    // 2) 竖直射线（先笔直，阶段 B 再加轻微倾斜/弯曲）
    float ray = fbm(vec2(p.x * 4.5, p.y * 0.35 + t * 0.05));

    // 3) 上下边界
    float bottom = 0.06 + 0.10 * curtain;
    float top = 0.55 + 0.35 * curtain;
    float bottomMask = smoothstep(0.0, 0.14, p.y - bottom);
    float topMask = 1.0 - smoothstep(0.0, 0.38, p.y - top);
    float envelope = bottomMask * topMask;

    // 4) 左右淡出
    float edgeFade = smoothstep(0.0, 0.18, uv.x) * (1.0 - smoothstep(0.82, 1.0, uv.x));

    // 5) 光谱色
    vec3 color = mix(violet, green, greenMix);
    color = mix(color, red, redMix);

    // 6) 亮度
    float rayMask = 0.55 + 0.45 * ray;
    float flickerMask = 0.80 + 0.20 * flicker;
    float brightness = 1.0;
    vec3 lit = color * (vertexColor.a * edgeFade * brightness * envelope * rayMask * flickerMask);
    fragColor = vec4(lit, 1.0);
}
```

### 6.3 Java 结构参考

- `AuroraRenderPipelines`：2 个 pipeline（aspect 64/128），`withShaderDefine("ASPECT", ...)`
- `AuroraShader`：QUADS + POSITION_TEX_COLOR；背层+前层两遍绘制
- `AuroraFactory.produce(seed)`：优先 `new AuroraShader(seed)`，构造异常时
  回退 `new AuroraClassic(seed)`

---

## 7. 测试流程与验收

1. `.\gradlew.bat build -x test --no-configuration-cache` 确认编译。
2. `.\gradlew.bat runClient --no-configuration-cache` 从 D 盘启动。
3. 进入世界 → 寒冷群系（雪原/冰刺）→ `/time set midnight`。
4. 看日志：无 `Couldn't compile`、无 aurora 相关 `Exception`。
5. 用户视觉验收（按阶段）：
   - 阶段 A：是否连续大片？是否有竖直射线？亮度是否合适？
   - 阶段 B：射线是否仍像竖直射线？是否不死直？整体是否没变样？
   - 阶段 C：是否有小瓣动态？是否无亮斑？
   - 阶段 D：颜色/亮度/宽度是否满意？
6. 每次只问一个变化点，避免多个变量混在一起无法定位。

---

## 8. 26.1 clean-room 重写实施记录（2026-08-16 第二轮）

### 8.1 已落地的文件

- `assets/dsurround/shaders/core/aurora.vsh` — POSITION_TEX_COLOR 直通
- `assets/dsurround/shaders/core/aurora.fsh` — 全部视觉逻辑（值噪声 FBM 自写）
- `AuroraRenderPipelines` — 2 个 pipeline（ASPECT=4/8，按 band.length 64/128），
  在 `NeoForgeMod` 构造器经 modBus 注册；SRC_ALPHA/SRC_ALPHA 加色混合、
  只测深度不写深度、无剔除
- `AuroraShader` — QUADS 连续幕帘（顶点 y 0–1，v=0 底/1 顶），
  背层+前层两遍；`AuroraFactory` 异常回退 `AuroraClassic`
- `AuroraEffectHandler.doRender` 同时分发两种渲染器

### 8.2 第一轮测试修掉的问题

- **幕帘只有 ~10 格高**：顶点 y=0–1 时 `SCALE_Y` 就是幕帘总高度，
  10 是错的；改为 120（经典版等效 56–144）。这是"效果不太行"的主因。
- **启动 ERROR 警告**：`@OnlyIn(Dist.CLIENT)` 注解在 26.1 会被
  OnlyInWarningsHandler 打成 ERROR——不要给类加 `@OnlyIn`。

### 8.3 第二轮用户反馈与对策

| 反馈 | 对策（当前值） |
|---|---|
| 层间分隔太大、纵向分层严重 | 背层 `SCALE_XZ 0.58→0.50`、`Z_BIAS 10→6`；带间距乘 `BAND_OFFSET_FACTOR=0.5` |
| 上边缘太锐利 | 根因：`top=0.55+0.35*curtain`（最大 0.9）+ 渐隐 0.38 超出几何边 → 硬切。改 `top=0.45+0.25*curtain`、渐隐 0.42，另加 `skyFade=1-smoothstep(0.80,0.97,p.y)` 保底归零 |
| 竖线不明显 | `rayShaped=smoothstep(0.28,0.72,ray)` 对比度整形，`rayMask=0.35+0.65*rayShaped`；射线 x 频率 4.5→5.0 |
| 想要少数竖线沿幕帘扫过 | `sweepBoost=0.85+0.45*smoothstep(0.45,0.90,fbm(低频漂移))`，局部竖线增亮并缓慢平移 |
| 蒜瓣明暗几乎看不到 | `lobeMask=0.80+0.35*lobe`（中频慢漂移）直接调制亮度，幅度保守防亮斑 |
| （附加）顶部暖白化 | `mix(color, color*vec3(1.06,0.96,0.90), 0.35*smoothstep(0.60,1.0,p.y))` |

### 8.4 第三轮：回调到中间态（用户定性"改过头"）

| 反馈 | 调整（第二轮 → 第三轮） |
|---|---|
| 竖线太过明显、幕帘像断成几截 | `rayShaped` smoothstep 0.28–0.72 → **0.24–0.76**；`rayMask` 0.35+0.65 → **0.45+0.55**（暗缝不再太暗，保证连续性优先） |
| 扫过的竖线太宽 | sweep x 频率 0.8 → **1.6**（亮区宽度减半）；boost 0.85+0.45 → **0.85+0.35** |
| 整体太短太暗 | `top` 0.45+0.25 → **0.50+0.30\*curtain**；skyFade 0.80–0.97 → **0.82–0.98**；`BRIGHTNESS` 1.0 → **1.1** |

教训：射线对比度是"连续性 vs 结构感"的直接权衡，smoothstep 区间宽度比阈值更敏感；
改对比度后必须连同 rayMask 下限一起抬，否则暗缝变"断口"。

### 8.5 第四轮：几何宽度/长度独立缩放

反馈：宽度削一些、整体长度加一些、暗线最低亮度提高。

- `AuroraShader` 拆分 `SCALE_X`（沿幕帘长度）/`SCALE_Z`（侧向宽度）：
  X 0.45→**0.55**（更长），Z 0.45→**0.26**（更窄，第五轮又从 0.32 削减）；
  背层 0.60/0.30
- `ASPECT` define 4/8→**5/10**：补偿长度 +22%，同时单条射线物理宽度变窄
- `rayMask` 下限 0.45→**0.52**（暗线更亮，连续性更好）

教训：长度与宽度必须拆 X/Z 缩放单独调；改 X 长度后 ASPECT 要同比放大，
否则射线会被水平拉宽。

### 8.6 第五轮：真实感三件套（2026-08-16）

- **底部射线增强**（初版翻车后修正）：`rim` 必须宽过渡 + 沿幕帘断续分布——
  最终 `rim = (1-smoothstep(0, 0.28, p.y-bottom)) * (0.55+0.45*curtain)`，
  `rimBoost = 1+0.22*rim*rayShaped`。
  **教训**：① 射线 x 频率随高度调制（`5.0+3.0*rim`）会让噪声域竖直压缩，
  产生"木星纹"式水平条纹——频率对高度必须恒定；② 窄 smoothstep（0.02–0.17）
  + 全宽乘 rayShaped 会形成一条又锐又通长的底边横亮带，很突兀。
- **整体高度呼吸**：`breath = 0.5+0.5*sin(t*0.126)`（周期约 25 秒），
  `bottom += 0.03*breath`，`top += 0.02*breath`
- **每带色相偏移**：`AuroraShader.bandTints`——随机相位色相旋转，每带 RGB 字节
  = 128±14，经顶点色传入；fsh 中 `color *= vertexColor.rgb * 2.0` 重新居中
  （128=无色调制），无需额外管线变体。同一带的背层同色调。

### 8.7 第六轮：微调 + 性能护栏（2026-08-16）

- 亮度 `BRIGHTNESS` 1.1→**1.0**；长度 `SCALE_X` 0.55→**0.50**（背层 0.55），
  `ASPECT` 5/10→**4.5/9** 同步补偿（射线宽度不变）
- **性能早退**：main 开头先算 `edgeFade/skyFade/vertexColor.a`，乘积 < 0.004
  直接输出黑并 return——天空渐隐区、幕帘两端、淡出期全部跳过 7 组 FBM，
  视觉完全等价（这些区域本来就是精确零）
- 开销账：每可见像素 7 组 FBM ≈ 112 次 hash；最坏 3 带×2 层 ≈ 670 次/像素，
  但屏幕覆盖率低（一条水平带），独显无感，核显最坏情况可能掉几帧。
  后续如需再省：flicker/sweep 降为单层 vnoise（−22% 噪声量）、warp 减一个
  八度、背层跳过 flicker（需 define 变体）。当前无需再做。

### 8.8 雪地反光（未实施，备忘）

效果：雪原/冰面朝极光方向呈现微弱的绿/紫渐变照明，极光正下方最亮，
似月光但带色。实现路径二选一：
1. 地形光照注入——把极光当作一个动态彩色光源参与 block light 混合，
   需要改地形管线或自定义 LightTexture，侵入大；
2. 屏幕空间后处理——AfterSky 后对已渲染场景按极光方位加色，实现简单但
   会错误点亮遮挡物背光面。建议放最后，先验证观感收益。

### 8.9 亮度预算提醒

峰值系数 ≈ lobeMask(≤1.15) × rayMask(≤1.0) × sweepBoost(≤1.30) × flicker(≤1.0)
× BRIGHTNESS(1.0) ≈ 1.5，但各最大值几乎不同时出现，典型均值 ≈ 0.6–0.8。
若嫌暗，先加 `BRIGHTNESS` +0.1 再动别的。

### 8.10 第七轮后：亮度提升（2026-08-23）

用户反馈整体偏暗，要求提亮。按 §8.9 只动 `BRIGHTNESS`：1.0 → **1.2**（aurora.fsh）。
历史警戒线：v3 时代 1.32 配更暗的射线暗缝（rayMask 0.35+0.65）被评"刺眼"；
当前 rayMask 下限 0.52 整体均值更高，1.2 是安全带内的明显可感步长。
若仍嫌暗下一步 +0.1；若局部过曝优先压 sweepBoost/rimBoost 而非回退全局亮度。
