# 1.21.1 移植 · 阶段一完成报告与阶段二施工图（2026-08-30）

> 阶段一（骨架 + 编译基线）已完成。本文件是阶段二的唯一施工依据。
> 基座：dsurround-neoforge-26.1（397 java / 928 资源）→ 目标：NeoForge 21.1.84 / MC 1.21.1 / Java 21。

## 0. 阶段一踩坑记录（必读，避免重复踩坑）

1. **moddev 坏缓存坑**：首次 compileJava 报 1128 个 "程序包net.minecraft.*不存在"。
   根因：createMinecraftArtifacts 的 neoform-runtime 中间产物损坏（patch/applyNeoforgePatches 显示 DONE
   但输出没落盘），任务随后 UP-TO-DATE 却不产出。
   **修复**：gradlew createMinecraftArtifacts --rerun-tasks --info 强制重跑即恢复，
   产物出现在 build/moddev/artifacts/neoforge-21.1.84.jar(22MB)+merged+sources。
   注意：**21.1 的产物名是 neoforge-21.1.84.jar，不是 26.1 的 minecraft-patched-*.jar**，别拿错参照。
2. **诊断时临时关闭了** org.gradle.parallel/caching/configuration-cache（现为 false）。管线验证通过后可改回 true。
3. 官方 MDK（NeoForgeMDKs/MDK-1.21.1-ModDevGradle）证实：moddev 2.0.144 + Gradle 9.2.1 + NeoForge 21.1 是官方支持组合，插件版本无需降级。
4. SereneSeasons 1.21.1 jar 已下载到 libs/SereneSeasons-neoforge-1.21.1-10.1.0.1.jar（Modrinth b2S4BcbP）。
5. mixins.json compatibilityLevel 已改 JAVA_21。

## 1. 编译基线统计（javac maxerrs=2000，实报 1473 错误）

| 类别 | 数量 | 说明 |
|---|---|---|
| 找不到符号 | 1299 | 绝大多数是 Identifier 及其连带 |
| 程序包不存在 | 66 | 全部是 26.1 新包路径回退（见 §2.3） |
| 不兼容的类型 | 34 | 渲染/事件签名连锁 |

符号频次 Top（"符号: 类/变量/方法 X"）：
Identifier 766（类556+变量210）· FogData 74 · GuiGraphicsExtractor 64 · identifier() 38 ·
getIdentifier() 30 · location() 26 · sprite 24 · contains(String) 22 · RenderPipelines 30 ·
Layer 32 · Util 12 · getOverworldClockTime() 10 · PlayResult 26 · Villager 8 · ParticleResources 8 ·
ARGB 8 · typeHolder() 8 · LeafLitterBlock 6 · OpenUrl 6 · getSelectedSlot() 6 · WeightedList 6 ·
RegisterRenderPipelinesEvent 6 · BlendFunction 6 · DepthStencilState 6 · QuadParticleRenderState 4 ·
Category 4 · ActiveTextCollector 4 · precipitationAt 4 · setSpriteFromAge 4 · getDeltaTracker 4 ·
getViewRotationProjectionMatrix 4

## 2. 阶段二施工清单（按性价比排序）

### 2.1 机械重命名（先做，预计消掉 ~60% 错误）
- net.minecraft.resources.Identifier → net.minecraft.resources.ResourceLocation（336 处/93 文件）
  - 工厂方法：Identifier.fromNamespaceAndPath(ns,path) → new ResourceLocation(ns,path)
    （1.21.1 构造器 public，官方源码大量 new ResourceLocation(...)）
  - 26.1 Identifier 的方法 .location() 若接收者是 ResourceKey，则 1.21.1 同名保留；
    若接收者是 Identifier 本身（取路径），对应 .getPath()/.getNamespace()
  - getIdentifier()/identifier() 是我们自己的工具方法名，统一改名 location()/rl() 即可（类内引用）
- inventory.getSelectedSlot() → inventory.selected 字段（官方 ToolbarEffect.java:28 原样写法，6 处）
- getOverworldClockTime() → getDayTime()（官方 MinecraftClock.java:33，10 处）
- ARGB.* → FastColor.ARGB32.*（官方 ColorPalette.java，8 处）
- KeyMapping.Category.register(...) → 直接字符串 category 传 new KeyMapping(key, code, category)（4 处）

### 2.2 渲染管线回退（最大结构性工作，~200 处 / 9 文件）
26.1 的 RenderPipeline/GpuBuffer/ByteBufferBuilder/BlendFunction/DepthStencilState/GpuTextureView/
RenderSetup/FilterMode/QuadParticleRenderState/RegisterRenderPipelinesEvent 全部不存在于 1.21.1。
**策略：从 1.20.1 版抄实现**（dsurround-forge-1.20.1 有全套 Tesselator/RenderSystem 写法）：
- WeatherStormHandler（沙尘暴列渲染）→ 1.20.1 版 WeatherStormHandler.java:258 Tesselator 路线
- AuroraRenderPipelines/AuroraShader/AuroraClassic → 1.20.1 版同名文件
- GuiGraphicsExtractor（Matrix3x2fStack+text()，64 处）→ 1.21.1 GuiGraphics
  （pose()/drawString/fill；官方 render(GuiGraphics context, ...) 签名，气泡/crit/overlay 全适用）
- FogData 74 处：**26.1 移除的 FogRenderer$FogData 在 1.21.1 又回来了** ——
  把 accesstransformer.cfg 里被注释的 FogRenderer$FogData 一行恢复，雾逻辑可回退到 1.20.1 同款（好消息）
- sprite/setSpriteFromAge/sound()（粒子，24+ 处）：SingleQuadParticle → TextureSheetParticle（1.21.1 同 1.20.1）

### 2.3 包路径回退（26.1 新包 → 1.21.1 旧包，66 处）
| 26.1 包 | 1.21.1 正确包 |
|---|---|
| world.entity.vehicle.boat | world.entity.vehicle（Boat 直接在 vehicle） |
| world.entity.projectile.arrow | world.entity.projectile（AbstractArrow） |
| world.entity.npc.villager | world.entity.npc（Villager） |
| client.renderer.state / state.level | 不存在；对应状态类按 1.21.1 各自原位（对照官方 common 源码） |
| client.input | client（MouseHandler/KeyMapping 原位） |
| client.renderer.rendertype | client.renderer（RenderType） |
| client.renderer.fog | client.renderer（FogRenderer） |

### 2.4 26.1 独有类的回退（需逐个决策）
- PlayResult（26 处）：26.1 SoundEngine.play 返回对象；1.21.1 返回 void（官方 IAudioPlayer.play void）。
  我们的 mixin SoundEngine 逻辑改为不依赖返回值 / 用 cancel 型注入。
- ParticleResources（8 处）：26.1 类；1.21.1 纹理走 ParticleEngine/SpriteSet，mixin 重写。
- LeafLitterBlock（6 处）：**1.21.2+ 方块，1.21.1 根本没有**。相关脚步/落地音配置降级为软引用（按方块 ID 匹配字符串，不 import 类）。
- WeightedList、Util、OpenUrl、ActiveTextCollector、contains(String)、typeHolder()、
  precipitationAt、first()、secondary()：逐个对照官方 common 同功能类写法（官方源码在
  DynamicSurroundingsFabric-main/common，同架构可直接抄）。

## 3. 修复顺序（严格串行，每步后全量编译）
1. §2.1 机械重命名（脚本化：sed/正则 + 手工核对工厂方法）→ 编译，预期错误降到 ~500
2. §2.3 包路径回退 → 编译
3. §2.2 渲染管线（按文件：WeatherStormHandler → Aurora* → Gui 层 → 粒子）→ 编译
4. §2.4 逐类决策 → 编译到 0 错误
5. AT 恢复 FogRenderer$FogData 后跑 runClient 做阶段四回归清单（计划文档 §阶段四）

## 4. 参照物索引
- 官方 1.21.1 源码（同架构，权威 API 参考）：D:\claude code\DynamicSurroundingsFabric-main\common
- 1.20.1 渲染实现（Tesselator/RenderSystem 可抄）：D:\claude code\dsurround-forge-1.20.1\src\main\java
- 官方 MDK（构建样板）：github NeoForgeMDKs/MDK-1.21.1-ModDevGradle（moddev 2.0.144 / Gradle 9.2.1 / NeoForge 21.1.248）
- 本项目日志：build_compile_baseline.txt（错误全量）、build_createArtifacts_info.txt（管线详情）
