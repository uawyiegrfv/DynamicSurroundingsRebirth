# 极光 Shader 重写备忘

> 本文记录 26.1 极光 shader 从移植、重写、回退到经典版的完整经验和用户视觉要求，
> 供下一次 clean-room 重写时直接参考。最后更新：2026-08-16。

---

## 1. 当前状态（截至 2026-08-16）

- **D 盘是唯一工作区**：`D:\claude code\dsurround-neoforge-26.1`
- git HEAD：`c3c4371 保存极光 shader 重制前的当前状态`
- 极光功能已回退到 **经典版**：`AuroraFactory.produce()` 只返回 `AuroraClassic`
- 仓库中**没有** shader 文件（`AuroraShader.java`、`AuroraRenderPipelines.java`、`assets/dsurround/shaders/` 均已移除）
- 已验证编译产物：`build/classes` 和 jar 内只有经典版类，无 shader 残留

### 构建 / 测试标准操作

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

---

## 2. 视觉需求规格（重写提示词）

> 目标：clean-room 重写极光 shader，达到或超过 Mattenii 版观感，同时可 MIT 分发。
> 下面这段可直接作为重写任务的核心提示词使用。

### 2.1 核心画面

一片**连续的大片极光幕帘**，主体由**竖直射线/条纹**构成，像真实极光的
ray structure：无数条沿高度方向拉长的光带，整体又像被风吹动的丝绸，
既有大范围的连续起伏，也有局部“小瓣样”的明暗与形状动态。

### 2.2 必须做到（MUST）

1. **必须有竖直射线**：画面主体是清晰的竖直条纹/射线，沿高度方向拉长，
   不是水平横条，也不是单纯的一团雾。
2. **竖直射线要有机变化**：竖直线不能像直尺一样死直。射线需要有**轻微**
   的弯曲、倾斜、摆动；幅度要小，让人第一眼仍觉得是竖直射线，细看有
   自然卷曲。
3. **连续大片银幕**：幕帘沿 ribbon 连续，**不能中间断开**、不能变成孤立的
   几段。整体要像一整片幕布，而不是几条细线。
4. **局部“小瓣”动态**：要有原版 Mattenii 版那种小瓣状/褶皱状的局部明暗
   变化，让幕帘有布料感；但瓣状必须柔和，**不能变成一坨亮斑**。
5. **颜色**：保持光谱感——底部紫/蓝、主体绿色、顶部红/粉。用户对现有
   颜色满意，重写时沿用这个颜色方向。
6. **亮度保守**：峰值亮度宁暗勿亮，避免“亮瞎眼”。加色混合下很容易过曝，
   默认参数要从低起步。
7. **宽度适中**：整体宽度比 clean-room v1（`SCALE_XZ = 0.5`）略窄。
8. **一次只改一个视觉变量**：弯曲/倾斜、亮度、宽度、颜色、分段感等
   分开调，每次改完进游戏确认。

### 2.3 禁止（MUST NOT）

1. 不要中间断开 / 分段落消失 / 变成孤立的几条。
2. 不要把“瓣状”做成亮斑、团块。
3. 不要过亮。
4. 不要做成完全水平的横条。
5. 不要把竖直射线做成完全笔直的直线（太机械）。
6. 不要把弯曲/倾斜做得太大，否则整体观感会完全变样。
7. 不要复制 Mattenii 的具体代码（clean-room 要求）。

### 2.4 历次反馈的失败记录（务必避开）

| 版本 | 做法 | 结果 |
|---|---|---|
| clean-room v1 | 竖直线 + 亮度 1.55 + 宽度 0.5 | “还行，但不如上一版”；竖直线太笔直，整体只有一条长条 |
| clean-room v2 | 加上射线倾斜/弯曲 + 瓣状褶皱 + presence 分段 | “不行”；瓣状成一坨亮斑，中间断开 |
| clean-room v3 | 去掉分段/瓣状，宽度 0.42，亮度 1.32 | 用户觉得太亮/刺眼 |
| Mattenii + 弯曲 | 在 Mattenii 版上直接加大幅弯曲 | 用户说“跟之前完全不一样”，改动幅度过大 |

**结论**：竖直线结构保留，只加轻微弯曲/倾斜；幕帘保持连续；亮度起步值
用 1.0 左右；宽度 0.42–0.45；任何新结构先想清楚它会在画面上产生什么。

### 早期 clean-room 版参数（用户觉得“还行”的起点）

- Java 层：`SCALE_XZ = 0.5F`、`SCALE_Y = 10.0F`
- 背层：`BACK_LAYER_ALPHA_FACTOR = 0.45F`、`BACK_LAYER_SCALE_XZ = 0.62F`、`BACK_LAYER_SCALE_Y = 11.0F`、`BACK_LAYER_Z_BIAS = 6.0F`
- shader 内 `brightness = 1.55`

### 后来确认的调整方向

- 宽度：`SCALE_XZ` 从 0.5 降到 0.42–0.45 左右
- 亮度：`brightness` 从 1.55 降到 1.0–1.2 左右
- 背层 alpha：从 0.45 降到 0.35 或更低，避免叠层过亮
- “弯曲/倾斜”如果要做，**幅度必须非常小**，否则整体观感会完全变样

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

---

## 4. 协议经验

- 原版 1.12.2 `aurora.vert/frag` 来自 Mattenii 的 Aurora Lights，
  **CC BY-NC-SA 3.0**，不是 MIT。
- 如果要随 mod 以 MIT 分发，shader 必须 **clean-room 重写**：
  不复制 Mattenii 的 hash 常量、函数结构（`hashConst 12.9898,78.233`、`shash`、
  `fbm1..4`、`lights` 等具体表达）。
- 通用技术（值噪声、FBM、domain warp、光谱色带、aspect 校正 UV、加色混合）
  属于可用的通用想法，但代码要自己写。

---

## 5. 下一版 clean-room 的实现建议

### 5.1 shader 结构

- 自写 hash / value noise / FBM（integer hash 或自选常量的 sin hash）
- domain warp 用于幕帘褶皱（注意幅度，过大变成亮斑）
- 竖直射线：坐标里加入 **很小的** y 依赖（倾斜）和 curtain 依赖（弯曲），
  幅度要小（参考失败经验：`0.28*p.y`、`0.65*curtain` 太大了）
- 不要用 `presence`/`patch` 把幕帘沿 x 断开；保持连续大片
- 颜色：物理向海拔渐变（紫底 / 绿主 / 红顶），用户对“颜色还行”可接受

### 5.2 Java 层

- `AuroraShader` 使用 QUADS + POSITION_TEX_COLOR，alpha 走顶点色
- 可保留“背层 + 前层”的视差设计，但背层 alpha 要低
- 宽度 `SCALE_XZ` 目标 0.42–0.45
- 亮度目标保守起步，例如 `brightness = 1.0`

### 5.3 测试流程

1. 先 `build` 确认编译和 jar 产物
2. `runClient --no-configuration-cache` 从 D 盘启动
3. 进入世界 → 寒冷群系（雪原/冰刺）→ `/time set midnight`
4. 一次只调一个参数，用户确认后再改下一个
