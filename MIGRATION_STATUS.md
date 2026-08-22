# Dynamic Surroundings — Fabric → NeoForge 26.1 迁移状态文档

> 更新时间：2026-08-16
> 项目目录：`D:\claude code\dsurround-neoforge-26.1`（2026-08-16 起由 E 盘迁至 D 盘，历史条目中的 E 盘路径为当时记录）
> 原始源码：`E:\下载\DynamicSurroundingsFabric-main.zip`（解压于 `E:\claude code\dynamic-surroundings-migration`）
> 1.12.2 参考源码：`D:\claude code\dsurround-1.12.2-src\DynamicSurroundings-1.12.2`
>
> **文档结构**：一~七章为迁移总览（概述/修复历程/构建/功能状态/配置/已知问题/Git）；
> `2.19` 之后的 `## 2.x` 是按时间追加的开发日志（插在第七章之后）；
> **代码审查记录统一收录在「八、代码审查记录」**，不再散落在时间线里。
> **功能实现与移植指导**见 `docs/mod-feature-porting-guide.md`（按系统梳理全部功能的
> 实现/表现/坑，含向更高版本移植的操作清单）。

---

## 一、项目概述

Dynamic Surroundings（简称 DS）是一个氛围增强类客户端模组，为 Minecraft 添加声音和视觉特效。本项目将其从 **Fabric 加载器迁移到 NeoForge 加载器**，目标平台为 **Minecraft 26.1.2 / NeoForge 26.1.2.93**，使用 Java 25 工具链。

迁移过程中，项目因**编译始终出现 200+ 错误**而搁置。本次工作完成了全部修复，使模组**可以正常编译、启动、加载和运行**。

---

## 二、修复历程

修复分四个层面推进，从编译到运行时逐层击破。

### 2.1 编译层（200+ 错误 → 0）

**根因分析**：此前修复脚本（`fix_all.py`/`fix_comprehensive.py` 等）采用大范围正则替换，产生大量错误替换：

| 错误类别 | 示例 | 正确做法 |
|---|---|---|
| `ResourceKey.location()` 被改反 | 26.1 中 `ResourceKey` 只有 `identifier()` | `.location()` → `.identifier()` |
| `SoundEvent.getLocation()` 误改为 `getIdentifier()` | `SoundEvent` 实际是 `location()` | 按类区分：`SoundEvent`/`TagKey` 用 `location()`，`SoundInstance` 用 `getIdentifier()`，`BlockHitResult` 用 `getLocation()` |
| `BlockHitResult.location()` 错改为 `getIdentifier()` | 该方法是获取命中位置 | → `getLocation()` |
| 禁用文件残留引用 | 被 `.disabled` 的类仍被 14 个活跃文件引用 | 逐个清理或正确注册 |

**修复方式**：不再猜测 API，而是用 `javap` 直接反编译 26.1 的 Minecraft jar（`~/.gradle/caches/neoformruntime/artifacts/minecraft_26.1_client.jar`），**逐条核对真实方法签名**后精确替换。所有替换记录在 `fix_26.1_api.py` 中。

**已验证的 26.1 API 变化**（均有 javap 佐证）：
- `ResourceKey`：只有 `identifier()`，无 `location()`
- `Music` 改为 record：`sound()` / `minDelay()` / `maxDelay()`
- `Biome.getPrecipitationAt(BlockPos, int)` 需要两个参数；`Level.precipitationAt(BlockPos)` 为便捷方法
- `Registry.get(int|Identifier)` 替代 `getHolder()`；`listElements()` 替代 `holders()`；`getTagOrEmpty()` 替代 `getTag()`
- `Direction.getUnitVec3i()` 替代 `getNormal()`；`HitResult.getLocation()` 返回命中位置
- `Equipable`、`BiomeSpecialEffects.getBackgroundMusic()`、`Player.bob/oBob`、`Level.getTimeOfDay/getMoonBrightness` 均被移除
- `SharedConstants.getCurrentVersion()` 返回顶层 `WorldVersion`（`name()` 访问器）

### 2.2 依赖层（Architectury 不兼容 → 升级 NeoForge）

**问题**：启动时 Architectury 加载失败 —— `NoClassDefFoundError: net/neoforged/neoforge/event/level/block/BreakBlockEvent`。该事件类在模组原用的 NeoForge `26.1.0.19-beta` 中被移除，且**所有** Architectury 版本都引用它。

**解决方案**：升级 NeoForge 到 `BreakBlockEvent` 已恢复的版本线：

| 配置项 | 原值 | 新值 |
|---|---|---|
| Minecraft | 26.1 | 26.1.2 |
| NeoForge | 26.1.0.19-beta | 26.1.2.93 |
| Architectury | 20.0.4 | 20.0.12 |
| YACL | 3.9.5+26.1-neoforge | 3.9.6+26.1-neoforge |

同时注册了 **Java 21 工具链**（`D:\minecraft\jdk-21.0.11+10`），因为 moddev 的 `downloadAssets` 任务要求 Java 21（在 `gradle.properties` 的 `org.gradle.java.installations.paths` 中配置）。

### 2.3 mixin 适配（26.1 类/方法重构）

启动过程中多个 mixin 注入目标在 26.1 发生变化，逐一修复：

| Mixin | 变更 | 修复 |
|---|---|---|
| `MixinWorld` | `Level.onBlockStateChange` 移除 | 改注入 `ClientLevel.setBlocksDirty` |
| `MixinSoundEngine` | `SoundEngine.play()` 返回类型改为 `PlayResult` | 全部注入 descriptor 更新；可取消注入改为返回 `PlayResult.NOT_STARTED` |
| `MixinSoundEngine.dsurround_init` | `Library.init` 增加 `DeviceList` 参数 | 更新 target descriptor |
| `MixinSoundLibrary` | `Library.init` 重写（属性缓冲移到 `createAttributes`） | 精简为最小 `ISoundEngine` 实现，禁用失效的增强注入 |
| `MixinBiome` | `getFogColor`/`getBackgroundMusic`/`getTemperature` 移除 | 重写：温度改用 `ClimateSettings.temperature()`，其余移除 |
| `MixinClientWorld` | `ClientLevel` 构造器移除 `Supplier` 参数 | 更新 `<init>` 注入签名 |
| `MixinSoundEvent` | `range`/`newSystem` 字段 → `fixedRange` | 更新 @Shadow 字段 |
| `MixinParticleManager` | `spriteSets` 字段移除 | 移除对应 @Accessor |

### 2.4 数据加载层（脚步声音全失效的元凶）

运行时发现**脚步声、工具栏声音等全部失效**，追查后发现两个关键问题：

1. **ReloadListener 未注册**：`Client.java` 中 `ReloadListenerRegistry.register(...)` 被迁移时注释掉，导致 `AssetLibraryEvent.RELOAD`（RESOURCES 范围）**永不触发** → SoundLibrary 从不加载音效工厂和声音映射 → 脚步/工具栏声音全部找不到。

   修复：恢复注册（Architectury 26.1 的 `ReloadListenerRegistry.register(PackType, PreparableReloadListener, Identifier)`）。

2. **ClientResourceFinder 找不到 `sounds.json`**：`listResourceStacks("sounds.json")` 只返回该路径**目录**下的资源，找不到文件本身（26.1 行为差异）。修复为对 `.json` 文件路径回退用 `getResourceStack()`。

   修复效果：声音事件从 1903（纯原版）→ 2003（含模组 100 个）。

### 2.5 稳定性层（运行时 NPE）

游戏运行后逐个修复的空指针/异常：

| 位置 | 原因 | 修复 |
|---|---|---|
| `BiomeSoundHandler` | synthetic biome 库未初始化时返回 null | 加空指针防护 |
| `EntityEffectLibrary` | `defaultInfo` 在 TAGS 范围 reload 后为 null | 构造函数中初始化 |
| `ItemClassType.getToolBarSound` | 音效工厂未加载时 `orElseThrow` 崩溃 | 改为 `orElse(null)` |
| `BlockParticleEffectProducer` | FIREFLY 粒子类禁用后产生 null 粒子 → ParticleEngine NPE | `addParticle` 加空值防护 + 禁用 FIREFLY 效果 |
| `IConfigScreenFactoryProvider` | 注册被注释，模组列表配置按钮报错 | 恢复 `ClothAPIFactoryProvider` 注册 |

### 2.6 增强音效处理（混响）恢复

`SoundEngine.play()` 局部变量表在 26.1 变化，原 `LocalCapture` 注入不可靠被禁用（导致混响失效）。改用 **RETURN 注入 + `instanceToChannel` 访问器** 安全恢复，避免 LocalCapture。

**2026-07-31 混响失效根因与修复（用户实测反馈"混响不生效"）**：

**根因一（发送溢出）**：`SourceContext.tick()` 硬编码使用 **4 个 OpenAL 辅助发送（send 0-3）**。对比原版 1.21.1 日志发现：原版跑在 OpenAL Soft **1.23.1**（`AuxSends: 4`，且原版用 mixin 在 `Library.init` 强制请求 4 发送），26.1 捆绑 **1.25.1** 默认只给 **2 个发送**且迁移时删掉了强制 mixin → send 2/3 全部报 `AL_INVALID_VALUE`，洞窟长混响区（zone2/3）失效 → 听感"没有混响"（只剩 zone0-1 的极短混响）。

**修复**：
1. **`MixinSoundLibrary` 移植原版的发送请求**：26.1 的 `Library.init` 已重构（属性缓冲移到 `createAttributes()`），用 `@Redirect` 重写 `alcCreateContext`，在属性里追加 `ALC_MAX_AUXILIARY_SENDS=4`（失败回退原属性 → 与自适应逻辑配合，双保险）。→ `AuxSends` 从 2 变 **4**，四个混响区全部生效。
2. **`AudioUtilities`/`Effects`/`SourceContext` 自适应**（对任意发送数都稳健）：`getMaxAuxSends()` 存设备上限；`Effects.initialize()` 只初始化可用发送；新增 `Effects.applyReverb(SourceContext)` 把 4 混响区按能量排序映射到可用发送；`tick()` 走 `applyReverb`。
3. **增益调优**：`GLOBAL_REVERB_MULTIPLIER` 0.7 → 1.5 → **1.0**（用户实测 4 发送 + 1.5 太强，1.0 适中）。

**验证**：`AuxSends: 4`，OpenAL 错误清零，四个混响区全部应用，**用户确认混响正常、与原版相当**。诊断日志（debug 级 `REVERB src=...`，`enableDebugLogging` 开启可见）可查看每个声音的混响增益/区域映射。

### 2.7 声音控制界面恢复（2026-07-31，重写 15 个被禁文件）

个体声音控制界面（`gui/sound/*` + `lib/gui/*` + `gui/keyboard/KeyBindings`，共 15 个 `.disabled` 文件）全部恢复。**根因：26.1 渲染管线重构，`GuiGraphics` 类已删除**，全部 GUI 代码需按新管线改写。参考了 NeoForge 官方迁移手册（ChampionAsh5357《Minecraft 1.21.11 -> 26.1 Mod Migration Primer》，`C:\Users\PCL\Desktop\新建 文本文档 (7).txt`）。

**26.1 GUI 核心变化（均有 javap/源码验证）**：

| 旧 (1.21.x) | 新 (26.1) |
|---|---|
| `GuiGraphics` | `GuiGraphicsExtractor`（类已删除） |
| `Screen.render()` / `Widget.render()` | `extractRenderState(GuiGraphicsExtractor, x, y, partialTicks)` |
| `AbstractWidget.renderWidget()` | `extractWidgetRenderState()`（`AbstractButton` 上为 final，可覆盖钩子是 `extractContents()`） |
| `renderTransparentBackground/renderMenuBackground` 由屏幕自调 | 由框架 `extractRenderStateWithTooltipAndSubtitles` 自动调 `extractBackground`，屏幕无需/不应再画背景 |
| 列表项 `render(GuiGraphics, index, rowTop, rowLeft, rowWidth, rowHeight, ...)` | `extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, partialTicks)`，位置改从 `LayoutElement` 的 `getX/getY/getWidth/getHeight` 取 |
| `drawString/drawCenteredString/renderTooltip` | `text()/centeredText()/setTooltipForNextFrame()` |
| `blitSprite(Identifier, x, y, w, h)` | `blitSprite(RenderPipelines.GUI_TEXTURED, Identifier, x, y, w, h)`（9 参版参数顺序为 spriteWidth,spriteHeight,textureX,textureY,x,y,w,h） |
| `keyPressed(int,int,int)` / `charTyped(char,int)` | `keyPressed(KeyEvent)` / `charTyped(CharacterEvent)`（事件对象化；`MouseButtonEvent` 同理） |
| `KeyMapping(String, int, String category)` | `KeyMapping(String, int, KeyMapping.Category)`；自定义分类用 `KeyMapping.Category.register(Identifier)`，label 取 `key.category.<ns>.<path>` |
| `ToastComponent` | `ToastManager`（`Minecraft.getToasts()` → `getToastManager()`） |
| `Toast.render(GuiGraphics, ToastComponent, long)` | `Toast.extractRenderState(GuiGraphicsExtractor, Font, long)` + `update(ToastManager, long)` |
| `AbstractSelectionList.getScrollbarPosition()` | `scrollBarX()` |
| `AbstractSelectionList.ensureVisible()` | `scrollToEntry()` |
| `Music.getEvent()` | `Music.sound()`（record） |

**其他恢复点**：`MixinSoundOptionsScreen`（在音效设置页加"Configure Sounds"按钮）、`MixinMusicManager.startPlaying`（播放音乐 toast）、`KeyBindings.register()`、`SoundLibrary.remapSound`/`SoundInstanceHandler.shouldBlockSoundPlay`/`SoundVolumeEvaluator` 中 `ConfigSoundInstance` 豁免、`key.category.dsurround.keybind` 翻译键。

**验证**：`./gradlew build` 通过；`runClient` 启动无 mixin 错误、游戏进入世界正常运行。声音配置界面本身需进游戏点击验证（音效设置 → Configure Sounds，或默认按键"["打开——旧版为 `=` 键，ModMenu 存在时禁用）。

---

### 2.8 调试 HUD/指南针/时钟恢复（2026-07-31，9 个被禁文件）

`gui/overlay/*`（AbstractOverlay、OverlayManager、CompassOverlay、ClockOverlay、DiagnosticsOverlay + 4 个插件）全部恢复，套用 2.7 的 26.1 GUI API 表。

**关键变化**：
- **GUI 层注册**：`NeoForgeMod` 恢复 `RegisterGuiLayersEvent`，`event.registerBelowAll(id, dsurround_overlayManager::render)`。26.1 的 `GuiLayer` 函数接口签名是 `render(GuiGraphicsExtractor, DeltaTracker)`。
- **CompassOverlay 重写**：旧 Blaze3D 立即模式（`RenderSystem.setShaderTexture`/`setShader`/`Tesselator`/`BufferUploader.drawWithShader`）**在 26.1 已删除**。改用 `graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, texW, texH)`（10 参，src=dest 区域），缩放通过 `graphics.pose()`（`Matrix3x2fStack`）`pushMatrix/scale(x,y)/popMatrix`。
- **ClockOverlay**：`world.getTimeOfDay(1F)` 已删 → 用 `world.getOverworldClockTime() % 24000` 算日相位；`TooltipRenderUtil.renderTooltipBackground` → `extractTooltipBackground(graphics, x, y, w, h, null)`。
- **诊断面板**：`graphics.fill`/`graphics.text`（替代 drawString）。
- **插件 API 修正**：`BlockState.getBlockHolder()`/`FluidState.holder()`/`ItemStack.getItemHolder()` → 统一 `typeHolder()`；`ResourceKey.location()` → `identifier()`；`SoundManager.getDebugString()` → `getChannelDebugString()`；`SoundInstance.getLocation()` → `getIdentifier()`。
- **按键**：`KeyBindings` 中诊断 HUD 键的 `DiagnosticsOverlay.toggleCollection()` 恢复（`Client.java` 的 `registerSingleton(OverlayManager.class)` 也已恢复）。

**验证**：`./gradlew build` 通过；`runClient` 启动无错误，OverlayManager 及其全部依赖（含 4 插件）DI 解析成功，GUI 层注册完成。指南针/时钟需手持物品显示、诊断 HUD 需按键（默认未绑定）——需进游戏实测。

---

### 2.9 粒子系统 + 雾系统 + 其余功能恢复（2026-07-31，全部 .disabled 清零）

本轮将 `src/main/java` 下**全部 47 个 `.disabled` 文件清零**：22 个功能性文件恢复，5 个因 26.1 渲染模型彻底改写而被删除（其职责被原生机制取代）。所有 26.1 API 均以 javap/反编译源码核实。

**2.9.1 粒子系统重写（26.1 渲染模型变更）**

26.1 粒子管线完全重构，以下类被重写：

| 旧 (1.21.x) | 新 (26.1) |
|---|---|
| `TextureSheetParticle`（基类） | 已删除 → 改用 `SingleQuadParticle`（构造器收 `TextureAtlasSprite`） |
| `Particle.getRenderType()` | `getGroup()`（返回 record `ParticleRenderType`） |
| `Particle.render(VertexConsumer, Camera, float)` | `SingleQuadParticle.extract(QuadParticleRenderState, Camera, float)`，quad 经 `extractRotatedQuad` 提交 |
| `ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT` 等 | `SingleQuadParticle.Layer`（`OPAQUE`/`TRANSLUCENT`，绑定纹理图集 + RenderPipeline） |
| `Particle.getLightColor(float)` | `getLightCoords(float)`；辉光用 `LightCoordsUtil.addSmoothBlockEmission` |
| `ParticleEngine.spriteSets` | 移入 `ParticleResources.spriteSets`（新增 `MixinParticleResources` @Accessor + `MixinParticleManager` 暴露 `resourceManager`） |
| 自定义 `ParticleRenderType`（DsurroundParticleRenderType） | 已删除——`ParticleRenderType` 成为 record，自定义渲染改为自定义 `Layer` |
| `ParticleRenderCollection`（自定义 GL 状态批量渲染） | 已删除——粒子各自经 `extract()` 渲染，Layer/pipeline 统一管理混合与纹理 |

- **FireflyParticle**：改用**原版 26.1 的萤火虫贴图与实现**（`minecraft:firefly` 闪烁光点 + 光/alpha 随生命周期脉动淡入淡出 + 随机漂移，移植自 vanilla `FireflyParticle`），DS 保留触发条件（夜晚 + `#minecraft:flowers` + 3.5% 几率）。去掉了原版"方块内移除"逻辑——DS 生成在花自身位置，会立即误移除。
- **FrostBreathParticle**：改继承 `SingleQuadParticle`，`getLayer()` → `TRANSLUCENT`，`getSize` → `getQuadSize`。
- **WaterRippleParticle**：改继承 `SingleQuadParticle`，重写 `extract()` 用 `rotationX(HALF_PI)` 把 billboard 转为**水平铺在水面**；涟漪纹理 `pixel_ripples.png` 复制到 `assets/dsurround/textures/particle/`（单数目录），自动缝合进原版粒子图集，`getU0/U1/V0/V1` 把 7 帧条带 UV 重映射进 sprite 图集区间。
- **WaterRippleHandler**：不再经由 ParticleRenderCollection，直接 `particleEngine.add(ripple)`。
- **MixinRainSplashParticle** 恢复：`WaterDropParticle.Provider#createParticle` 签名新增尾部 `RandomSource` 参数，descriptor 已更新。

**2.9.2 雾系统（26.1 移除 FogRenderer，改用原生事件）**

| 旧 (1.21.x) | 新 (26.1) |
|---|---|
| `FogRenderer`（类） | 移入 `net.minecraft.client.renderer.fog`，`setupFog` 返回 `FogData` 存入 `CameraRenderState` |
| `FogData.start/end/shape/mode` | `FogData.environmentalStart/End`、`renderDistanceStart/End`、`skyEnd`、`cloudEnd`、`color`（UBO 提交） |
| `MixinFogRenderer` + `ClientEventHooks.FOG_RENDER_EVENT` | **原生 `ViewportEvent.RenderFog`**（可变 `FogData`，`MixinFogRenderer` 删除） |
| `RenderSystem.setShaderFogStart/End/Shape` | 直接改写事件 `fogData.environmentalStart/End` |
| `DimensionType.natural()` | 已删除 → `hasSkyLight()` 代理 |
| `Level.getTimeOfDay(1F)` | 已删除 → `getOverworldClockTime() % 24000 / 24000F * 360F` |
| `GameRenderer.getRenderDistance()` | 已删除 → `options.getEffectiveRenderDistance()` |
| `SimpleWeightedRandomList`（清晨雾密度随机表） | 已删除 → 自实现小型加权表 |

- `FogHandler` 改为监听 `ViewportEvent.RenderFog`（`FogType.ATMOSPHERIC` 才处理），写回事件 `FogData`。
- 六个雾计算器（Holistic/Biome/Morning/Weather/Vanilla/IFogRange）全部恢复并适配上述 API。
- `Client.java` / `Handlers.java` 恢复 `HolisticFogRangeCalculator` / `FogHandler` 注册。

**2.9.3 其余恢复**

- **VillageScanner**：`Villager` 移入 `npc.villager` 子包；`Level#getEntitiesOfClass` → `getEntities(EntityTypeTest, AABB, Predicate)`；`BlockPos#closerToCenterThan` → `Vec3#closerThan`。`Scanners.java`/`Handlers.java` 恢复注册。
- **SereneSeasons 季节兼容**：SereneSeasons 已有 26.1.2 NeoForge 构建（`26.1.2.0.4`，从 Modrinth 下载放入 `libs/`，compileOnly 依赖，`libs/` 已加 .gitignore）。`SeasonHooks.getPrecipitationAtSeasonal`/`getBiomeTemperature` 新增第 4 个 `seaLevel` 参数（镜像 `Biome.getPrecipitationAt(pos, seaLevel)`）。`SeasonManager` 恢复 `isModLoaded` 分支。
- **MixinEntityArrow**：`AbstractArrow` 移入 `projectile.arrow` 子包，target 更新。
- **MixinTagCollector**：`TagCollector` 并入 `RegistryDataCollector$TagCollector`（API 全变），mixin 删除，改用**原生 `TagsUpdatedEvent.ClientPacketReceived`**（NeoForgeMod 中触发 `ClientState.TAG_SYNC`）。

**验证**：`./gradlew build` 通过，产物 `build/libs/dsurround-neoforge-26.1.2-0.5.0.jar`。所有恢复类已确认打包。粒子/雾/村庄/季节需进游戏实测视觉效果与季节显示。

**2.9.4 昼夜判断 bug 修复（runClient 实测发现）**

实测时用户反馈"白天有蟋蟀/猫头鹰声，睡一觉后正常"。排查发现：`Level.getOverworldClockTime()` 在 26.1 返回**跨天累计总刻度**（`ClockManager.getTotalTicks`，含天数），而 `DayCycle.getCycle()` 和 `DiurnalVariables` 的 `celestialAngle` 直接用它算日相位**没有 `% 24000` 取模**：

- 世界**第一天**（累计 < 24000）：昼夜判断正确 → 夜晚 owl/蟋蟀响、白天静是正常行为
- **第二天及以后**（累计 ≥ 24000）：`角度 = 累计/24000*360 ≥ 360°` 恒大于阈值 → `DayCycle` **恒判 DAYTIME** → `diurnal.isNight()` 恒 false → **夜间声从此永久消失**（用户"睡一觉后正常"恰是此 bug 的表现）

**修复（2026-07-31 雾排查时补全）**：
1. 取模：`DayCycle.getCycle()` / `DiurnalVariables.update()` 对 `getOverworldClockTime()` 补 `% 24000L`（`getTotalTicks` 跨天累计）。
2. **-6 小时偏移**：实测发现 26.1 的 `getOverworldClockTime()` tick 0 = **日出 6AM**（`/time set 18000` 是夜晚），而 DS 的 `DayCycle` 阈值和 `FogDensity` 范围按 **0=正午** 约定设计 → 缺 `+18000 mod 24000`（-6h）偏移。漏掉偏移会导致：白天误判夜晚（第一天白天 owl/蟋蟀响）、晨雾 270-317° 实际落在午夜-凌晨3点。**修复**：`DayCycle`/`DiurnalVariables`/`MorningFogRangeCalculator` 的角度统一 `((ticks % 24000) + 18000) % 24000 / 24000 * 360`。

**2.9.6 雾系统完整修复（2026-07-31 实测确认）**

三层问题叠加导致雾"看不到/距离远"：

1. **基准选错**：1.21 的 `FogData.start/end` 对应 26.1 的 `renderDistanceStart/End`（主世界 `environmentalStart` 恒 0 会让计算归零）。改用 renderDistance 基准后雨雾/晨雾生效。
2. **角度基准缺偏移**（见 2.9.4）：晨雾 270-317° 在 0=6AM 下落在午夜-凌晨，用户 `/time set 0` 不触发。补 -6h 偏移后 `/time set 0`（=270° 日出）正确触发晨雾。
3. **雾距离远**：DS 雾基于 renderDistance（16 chunks=256），雨雾 end 153.6 / 晨雾 end 230，比原版（12 chunks≈115）远。**修复**：雨雾 end 上限 `96`（`MAX_RAIN_FOG_END`）、晨雾 end 上限 `128`（`MAX_MORNING_FOG_END`）。

**验证**：雨雾 `rdist=23.04/96`、晨雾 `/time set 0` 触发、白天无 owl（昼夜判断修复）、用户确认雾距离合适。

**2.9.5 水波涟漪不可见修复（三轮实测定位，核心是 Mth.lerp 参数顺序）**

实测中涟漪被创建、被引擎渲染（extract 调用、参数全正常），但**不可见**。排查出三层根因：

1. **纹理没进粒子图集**（sprite 返回 missingno）：复制到 `textures/particle/` 后 vanilla 目录扫描未生效。**修复**：新增 `assets/minecraft/atlases/particles.json` 覆盖文件，用 `single` sprite source 显式把 `dsurround:particles/pixel_ripples` 注册进图集（sprite 名 `dsurround:particle/pixel_ripples`）。
2. **`Mth.lerp` 参数顺序写反**（真正的杀手）：`Mth.lerp(delta, start, end)` 第一个参数是插值系数。写成 `Mth.lerp(sprite.getU0(), sprite.getU1(), u)` → UV 完全算错，采到纹理透明区。而 26.1 `particle.fsh` 有 `if (color.a < 0.1) discard;` → **整个 quad 被丢弃**。正确：`Mth.lerp(u, sprite.getU0(), sprite.getU1())`。
3. **帧 0 太小被 discard**：纹理帧 0 只有 8 个不透明像素，quad 几乎全透明 → discard。**修复**：从帧 1 开始动画（`START_FRAME_INDEX=2`）。

**其他必须注意的 26.1 粒子渲染要点**（全部实测验证）：
- **粒子管线默认剔除背面**：`RenderPipeline.Builder#cull` 默认 `true`。水平铺水面的 quad 必须用 `rotationX(-Mth.HALF_PI)`（法线朝上）；`+90°` 法线朝下从上方被剔除。这也是 vanilla `WEATHER_SNIPPET` 显式 `.withCull(false)` 的原因。
- **`getQuadSize` 不能从 0 增长**（涟漪初始尺寸 0 不可见）→ 返回固定 `scaledWidth`，环在 quad 内靠帧动画扩张。
- **涟漪 y 需略浮出水面**（`+0.05`）避免被水深度遮挡。
- 排查方法：CPU 侧看不到 discard（发生在 GPU），只能靠**对照实验**（完整 UV vs 帧重映射）定位。临时日志会**严重刷屏**（雨滴粒子海量 + 每帧 fog 日志可让日志文件涨到 GB 级卡死游戏）——调试日志必须限频或及时移除。

---

### 2.10 1.12.2 功能移植（2026-08-01，用户逐项实测确认）

本轮把原版 1.12.2 的玩家状态声、脚印、暴击词、雾、天气等按难度逐项移植，每项均进游戏实测：

| 功能 | 实现 | 实测 |
|---|---|---|
| 心跳声（血量<25%）/ 饥饿声（饥饿<8） | `PlayerEffectHandler`：每 0.8s 心跳 / 每 15s 饥饿 | ✅ 节奏对齐原版 |
| 跳跃声 / 落地声 | `PlayerEffectHandler`（落地用空中缓存 fallDistance，>0.9 触发） | ✅ |
| 制作声 | `CraftingSoundEffectHandler`（`ItemCraftedEvent`，30tick 节流） | ✅ |
| 弓箭/盾/弩 + 快捷栏切换 | `BowUseEffect`（弓/盾 use 声）、`ToolbarEffect`（主/副手物品变化） | ✅ |
| 药水粒子抑制 | `PotionParticleHandler`（`EffectParticleModificationEvent`，抑制所有 Player） | ✅ |
| 脚印 | `FootprintHandler` + `FootprintParticle`（材质白名单：泥土/草/沙/雪/冰/粘土+扩展；落地双脚；左右交替） | ✅ |
| 暴击词 + 伤害/治疗数字 | `CritWordHandler`（3D 飞出 + 距离缩放 + partialTicks 插值；红伤害/绿治疗/金暴击） | ✅ |
| 背景雷声 | `ThunderHandler`（雷暴时 20-40s 远处雷声） | ✅ |
| 基岩/沙漠/海拔雾 | `BedrockFogRangeCalculator` / `HazeFogRangeCalculator`（云层+高海拔 280-320 双雾带）/ biomes.json 沙漠 fogDensity | ✅ |
| 岩浆冒烟 | `MagmaSplashHandler`（雨落岩浆/下界岩冒烟） | ✅ |
| 气泡呼吸 | `BreathBubbleParticle`（准心处透明小气泡，安全 spriteProvider+move 模式；先前的 getSprite+super.tick 版触发 GPU 崩溃已规避） | ✅ |

**26.1 API 修正要点**（本轮）：
- 物品 tag：26.1 common tags 改**单数**（`c:tools/bow` 非 `bows`），DS tag 全修正；`#minecraft:bookshelf_books` 等替代失效 `c:` 标签
- `fallDistance` 落地瞬间重置 → 空中缓存（落地声/脚印同）
- `ObjectArray` 无 `remove(int)`，`remove(i)` 装箱成 `remove(Object)` 永不移除 → 用 `removeIf`（暴击词泄漏卡死根因）
- 渲染：`drawInBatch` 立即模式在 26.1 renderLevel **不生效**（走 SubmitNodeCollector）→ 暴击词用 GUI 投影 + 距离缩放
- `LightCoordsUtil.pack`（net.minecraft.util）替代 LightTexture
- 透明粒子崩溃（getSprite + super.tick 版）→ 用 FrostBreathParticle 安全模式（spriteProvider + move）
- GPU 崩溃（`Vertex buffer slot out of range`、OpenGL id=1001）→ 用户显卡驱动不稳定，与 DS 逻辑无关

**清理**：删除 3 处注释残留（SoundInstanceHandler 重复 import、Handlers 失效 import、ClientEventHooks FOG_RENDER_EVENT）；实现 BreathEffect 气泡呼吸（原空方法烂尾）。

---

### 2.11 A2-2 方块声学映射（2026-08-01）

把原版 1.12.2 mcp.json `footsteps` 表的**方块→材质声音映射**移植到 26.1 数据体系，实现"每方块类型声音映射"（walk/land/jump）。

**实现方式**：利用既有 `sound_mappings.json` remap 机制（`SoundEngine.play` → `remapSound` → 按声音位置下方方块匹配规则 → 替换为 DS 材质音效工厂）。原版是 DS 自建 BlockMap/AcousticResolver 逐帧解析，26.1 用 remap + 每方块状态匹配实现同等效果，零新 Java 解析代码。

**1. 材质音频补全**（`sounds/footsteps/` 183 → **295**）——从 1.12.2 复制 7 个缺失材质：

| 材质 | 声音事件 | 对应方块 |
|---|---|---|
| quicksand | `footsteps.quicksand` | 灵魂沙、灵魂土 |
| weakice | `footsteps.weakice` | 薄冰、荧石、海晶灯、蘑菇灯 |
| glass | `footsteps.glass` | 玻璃/染色玻璃/玻璃板、蜡烛、饰纹陶罐、紫水晶簇 |
| marble | `footsteps.marble` | 方解石、石英块/柱/砖/平滑石英 |
| concrete | `footsteps.concrete` | 混凝土（所有色）、砖类、深板岩砖/瓦、下界砖、凝灰岩砖、骨块 |
| lino | `footsteps.lino` | 黑曜石/哭泣黑曜石/重生锚/附魔台/末影箱 |
| squeakywood | `footsteps.squeakywood` | 箱子/陷阱箱 |

**2. sound_mappings.json 全面扩充**（12 条 → **106 条**）：覆盖 26.1 全部 `block.*.step` 声音事件（用 javap 从 `SoundEvents` 提取 100+ 个 step 事件 ID 逐一核对）。共享 SoundType 的声音事件（如 `block.stone.step`）用每方块/标签规则区分材质（石英→marble、黑曜石→lino、锅→metalbox、默认 stone）。

**3. FootstepGenerator 增强**：
- **跳跃声**：离地且 `deltaMovement.y>0` 播放 `player.jump`（受 `entityEffects.enablePlayerJumpSound` 开关，默认 true）
- **落地声开关**：受 `entityEffects.enablePlayerLandSound` 控制
- **声音位置修正**：脚步声音改在玩家脚部方块播放（原在表面方块）——这样 remap 取"声音下方方块"能正确命中表面方块（此前靠水平扫描兜底，多一层方块时会失配）

**26.1 方块 ID 修正**：`minecraft:chain` 已改名 → `iron_chain`/`copper_chain`（去掉该规则，链子走 `block.chain.step`→metalbar 已覆盖）。

**验证**：`./gradlew build` 通过；runClient 启动进入世界无错误；`SoundEvents cached: 2012`（+7 新事件）、`sound remappings cached: 106`（全部加载）。材质音效需进游戏逐块实测。

---

### 2.12 A2-3/A2-5 脚步声系统深化（2026-08-01，用户逐项实测确认）

在 A2-2 材质映射基础上继续还原原版脚步声的**组合音**、**run/walk 变体**和**落地声**。用户与 1.12.2 参考游戏并排对比调试。

**1. 声学组合器（simultaneous 组合音）**
- `sound_mappings.json` 规则新增 `accent` 字段（叠加声工厂列表），`FootstepGenerator` 播放主声后同时播放 accent
- 配置的组合：草=grass+brush@0.3、树叶=dirt+leaves_through、冰=stone+muffledice@0.4、玻璃=wood+glass、砂岩=concrete+sand@0.1、荧石=concrete+weakice@0.1
- 新增 accent 音量工厂（`footstep_accent/*`）

**2. run/walk 分变体 + 音高随机**
- 复制 1.12.2 run 音频（stone/dirt/grass/gravel/sand/snow/metalbox/metalbar 80 个），新增 `footsteps.<材质>_run` 事件
- 跑步时播 `*_run` 声（`isSprinting()` 判定——**关键**：速度阈值 0.18 低于走路速度 0.216，会把快走误判成跑步导致忽重忽轻，改用冲刺状态彻底解决）
- 全部材质工厂加 pitch 0.8-1.2（每步自然音高变化）

**3. 落地声（原版 delayed 组合）**
- **分材质落地声**：`resolveLandSound()` 取脚下方块材质，用 `*_run`（重）或基础声
- **延迟回声**：落地后 2 tick（100ms）再播一次（`LAND_ECHO_VOLUME=0.85`）
- **多层叠加**：主声@1.0 + 副层 walk@0.5 + 回声 —— **关键**：Minecraft 把单声音量钳制到 1.0（`SoundEngine Mth.clamp`），单声无法更响，只能靠叠加；同时脚步基础音量降到 0.55（`FOOTSTEP_VOLUME`），落地声 ≈ 走路 4 倍响度
- **触发判定**（用户逐项确认）：
  - 主动起跳（`didJump` 标记）+ 实际落差 >0.9 → 重落地
  - 从高处掉落（未起跳）>1.5 → 重落地
  - **跳上一格**（落差仅 ~0.25）→ 正常脚步
  - 走/跑过小台阶（≤1.5 未起跳）→ 正常脚步
- 落地音高固定 0.75（原版不随机落地音高）

**4. 关键 bug 修复**：
- **木头/玻璃脚步声"只响一次"**：`getSound()` 对未注册声音返回 `MISSING` 占位音（非 null），`!=null` 检查误判 → 播放静音占位音。新增 `isSoundRegistered()` 正确判断
- **落地声双份**：`PlayerEffectHandler` 与 `FootstepGenerator` 重复播落地/跳跃 → 移除 PlayerEffectHandler 的 jump/land（保留心跳/饥饿）
- **方块边缘落地回退旧泥地声**：`resolveSurfaceBlock()` 脚下空气时横向扫描最近实体方块（同 remap 边缘处理）
- **下台阶不出声**：原版 `updateWalkedOnStep` 含垂直位移 → 显式检测 `yPosition - pos.y > 0.4` 强制出步
- **灵魂沙笑声只在下界触发**：去掉 blocks.json 的维度条件（对齐 1.12.2）

**验证**：用户实测确认脚步声/落地声效果满意；`./gradlew build` 通过。

---

### 2.13 A2-8 落灰（2026-08-01）

浮空方块（下方是空气）底部飘落尘土，移植自原版 `DustJetEffect`/`ParticleDust`。**至此 A2 方块效果全部完成**。

- **`DustParticle`**：26.1 `SingleQuadParticle` 重写，用原版 ASH 贴图 + 棕色染色（0.62,0.5,0.32），重力下落 + 摩擦 + 寿命后期渐隐 + 落地实体方块即消失
- **`DustJetProducer`**：每次触发播 3 个粒子（原版 ParticleDustJet 的 burst），仅当方块下方是空气
- **`BlockEffectType.DUST_JET("dust")`** + `enableDustJets` 配置项
- **blocks.json**：`minecraft:dirt` → dust，spawnChance **0.01**

**26.1 关键机制**：方块效果是**随机采样**触发（`RandomBlockEffectSystem` 每 tick 采样 667 个位置，近程 16 格），特定方块被采样率约 1.9%/tick。因此 spawnChance 必须远高于直觉（用户实测 0.5 太频繁 → 0.1 → **0.01** 合适）。

**验证**：`./gradlew build` 通过；runClient 启动无错误。浮空泥土边沿偶有尘土飘落（用户确认频率合适）。

---

### 2.14 A17 天气系统（简化粒子版，2026-08-01）

完整移植原版 StormRenderer（8 级强度雨/雪/尘纹理替换原版天气）工程量大、26.1 天气渲染已重构为 `WeatherEffectRenderer`，**暂缓**。先做用户最关心的**沙漠沙尘 + 下界尘雨**粒子版：

- **`StormDustParticle`**：沙尘粒子（强风向 + 重力 + 渐隐），沙漠沙尘用大粒子（0.8×）、下界尘雨用小粒子（0.4×）
- **`WeatherStormHandler`**：每 tick 检测生物群系/维度
  - 沙漠（`#is_desert`/`#is_badlands` tag）→ 黄色滤镜（强度 0.12）+ 少量沙粒
  - **沙漠下雨 = 沙尘暴** → 滤镜强度 0.7 + 12 粒子/帧（浓黄雾霾，接近原版）
  - 下界 → 暗色尘雨（强度 0.1）
- **沙漠黄色滤镜**：`registerBelowAll` GUI 层，屏幕叠加黄棕（0xD8B266）半透明填充，不透明度系数 0.7
- **配置**：`weatherOptions.enableDesertSandstorm` / `enableNetherDust`

**26.1 关键坑**：handler 需同时注册 DI（`registerSingleton`）**和**每 tick 列表（`Handlers.init()` 的 `this.register()`），只注册前者 `process()` 永不调用（本次实测定位）。

**验证**：`./gradlew build` 通过。沙漠黄色雾霾 + 沙尘暴 + 下界尘雨实测有效（用户确认简化版先这样）。完整版（雨/雪纹理替换）留待以后评估。

---

### 2.15 穿草音效 + 材质跳跃 + 脚印/雪层修复 + 代码审查（2026-08-02）

本轮集中修复穿草音效、补全材质跳跃音效、修正脚印与雪层相关 bug，并对反复缝补的代码做了一次系统审查（simplify 四角度）。

#### 2.15.1 穿草音效（brush step）

- **音量对齐 1.12.2**：`brush_step/brush` 0.3→**0.65**（高草/蕨）、`brush_step/straw` 0.3→**1.0**（枯灌木/藤蔓，leaves_through）、草地 accent 改走独立 `footstep_accent/brush`（**0.4**）——三个场景音量分离，不再共用工厂。
- **位置去重（1.12.2 messyPos）**：`StepThroughBrushEffect` 记录 `lastBrushPos`，同一格草丛只触发一次，离开再进入才重新触发——避免重复。
- **生物移动判定**：`shouldProcess` 改用**水平 `getDeltaMovement()` + 0.01 阈值**（原 `xxa/zza` 对 AI 导航生物恒为 0，生物穿草永不触发）；静止生物不再循环播放。
- **穿草抑制**：`SoundLibrary.remapSound` 对 `block.grass.step` 且脚在 `BRUSH_STEP` 方块时替换为 `ambient/silence`（静音原版穿草声，让 brush accent 独占），entity_type tag 扩展到玩家+25 生物。

#### 2.15.2 材质跳跃音效（wander 体系，1.12.2 两层跳跃）

- **复制 62 个 `*_wander` 音频**（12 材质：bluntwood/concrete/dirt/grass/gravel/marble/metalbar/metalbox/mud/snow/squeakywood/stone）+ 注册 12 个声音事件 + 补 9 个字幕键。
- `FootstepGenerator.playJump` 对齐 1.12.2：**起跳人声"呃"（`player.jump`）+ 按脚下方块材质的 `_wander` 音效**（雪→snow_wander、石→stone_wander 等），无 wander 的材质自动跳过（`isSoundRegistered` 守卫）。
- 抽 `materialVariant(material, suffix)` helper 复用 `_run`/`_land`/`_wander` 后缀查找（三处重复收敛）。

#### 2.15.3 脚印与雪层修复

- **冰上无脚印**：`FOOTPRINT_BLOCKS` 移除 4 个冰方块（用户要求，偏离原版）。
- **雪层脚印高度**：改用**视觉形状 `getShape`** 而非 `getCollisionShape`——26.1 雪层碰撞盒用 `SHAPES[LAYERS-1]`（1 层雪顶=0、2 层只算 1 层），脚印会印在错误（低一层）表面/埋进雪里。`getShape` 给出真实可见雪面。
- **脚印悬空剔除**：`spawnPrint` 探测**偏移后的脚印位置**（非玩家脚部），表面离脚底 >±0.5 格则拒绝（方块边缘不悬空、不高一格）。
- **薄雪层踩雪声**：`resolveSurfaceBlock` 优先检查**玩家脚所在层**（`pos.above()`）的可见形状（非植物）——1 层雪碰撞盒近空但视觉有形状，之前 fallback 到雪层下方草方块播草声；排除 `VegetationBlock`（高草有形状但可穿过）。

#### 2.15.4 代码审查（simplify 四角度）

4 个并行 agent 审查了本轮全部 Java 改动（Reuse/Simplification/Efficiency/Altitude），应用的安全修复（已提交 d84c3b2）：

| 发现 | 修复 |
|---|---|
| `StepThroughBrushEffect.getVolumeScaling` 缩成常量 stub + `volumeScale` 死参数 | 删除，音量由 factory JSON 配置驱动 |
| `feetPos` 冗余变量（对整型 BlockPos `containing(+0.25)` 恒等） | 删除 |
| `SoundLibrary` `ambient/silence` 每帧内联构造 | 提为静态常量 `SILENCE` |
| `SoundLibrary.isMobStep` 冗余（mob step remap 必然 `.step` 结尾，蕴含 `isStepSound`） | 删除 |
| `FootprintHandler` 8 层向下扫描过度（±0.5 窗口只可能命中 2 层，降序首个即最高） | 折叠为 2 层早退 + `MutableBlockPos`，删 `printBlock`/`bestTop` 记账 |
| `_run`/`_land`/`_wander` 后缀查找重复 3 处 | 抽 `materialVariant()` helper |

**审查判定跳过**（重构非 bug，功能正确，风险大于收益，留作后续）：
- "实体站在哪个表面"概念在 4 处独立实现（FootstepGenerator/FootprintHandler/SoundLibrary/StepThroughBrushEffect）——理想抽共享 surface resolver。
- 穿草抑制用魔法字符串 `block.grass.step` 而非数据驱动映射。
- `resolveSurfaceBlock` 4 层回退是补丁堆积（但每层有实测依据）。

#### 2.15.5 发布标准评估

**已达标**：编译零错误零警告、构建产物完整（145 声音事件源=jar 一致）、`.disabled` 清零、无新 TODO/FIXME、每功能独立 git 提交可回滚、本轮功能用户逐项实测确认。

**发布前须知**：A17 完整天气（雨/雪纹理替换）仍是**简化粒子版**（2.14 暂缓）；A2-4 变奏器可选未做；建议公开发布前跑一次**长时间稳定性测试**并声明 A17 限制。

**验证**：`./gradlew build` 通过；runClient 启动正常，穿草音效/材质跳跃/雪层脚印逐项实测确认。

---

### 2.16 启动器崩溃修复 + 草径/梯子/基岩雾（2026-08-02）

模组在 **启动器（PCL）发布环境**下启动崩溃，与 dev 环境（runClient）不同，暴露了打包/运行差异。另修复草径无声与基岩雾坐标。

#### 2.16.1 Nashorn 打包问题（三连修）

`ExecutionContext`（配置条件求值、dsscript）硬性 `orElseThrow` 需要 JavaScript 引擎（Nashorn）。runClient dev 环境用 `implementation` 依赖直接上 classpath，主 jar 发布则需自带。三次打包尝试定位出 Nashorn 的特殊要求：

1. **主 jar 缺 Nashorn → 崩溃** `Unable to load a JavaScript engine!`（RegisterGuiLayersEvent / OverlayManager 初始化）
2. **扁平化打包 Nashorn+ASM → LinkageError** `loader constraint violation: org.objectweb.asm`——Nashorn 传递依赖 ASM 7.3.1 与 Minecraft 自带 ASM 9.9.1 冲突
3. **排除 ASM 后扁平化 → ResolutionException** `Module org.openjdk.nashorn.structures contains package org.openjdk.nashorn.internal.scripts, module dsurround exports ...`——Nashorn 运行时构建 `org.openjdk.nashorn.*` Java 模块，扁平化使其与 dsurround 模块争抢包

**最终方案：NeoForge Jar-in-Jar**。在 `jarJar` 配置组声明 `nashorn-core` 依赖，moddev 的 `JarJarPlugin` 自动把它**原样**（完整模块结构）复制进 `META-INF/jarjar/` 并生成 metadata.json，加载器解压为独立 jar 保留模块边界。已验证：主 jar 含 `META-INF/jarjar/nashorn-core-15.4.jar` + metadata、0 扁平类；dev-shadow 扁平化（dev 环境用）。本地用 javap/Java 测试确认 **Nashorn 15.4 + Minecraft 完整 ASM 9.9.1 组件集兼容**（JS 编译/执行正常）。

**发布教训**：Nashorn 不能扁平化进 mod jar（模块化）；`runClient` 能跑 ≠ 发布 jar 能跑，发布前必须用启动器实测。

#### 2.16.2 草径/梯子无声（工厂 location ≠ 事件名）

**根因**：`FootstepGenerator.playStep` 用 `SoundFactoryBuilder.create(soundLoc)` 把工厂 location 当**声音事件**查。绝大多数材质工厂 location=事件名（`footsteps.grass`），能工作；但 **6 个专用工厂 location≠事件名**会播放 MISSING 静音：
- `footsteps/dirt_path`（→`footsteps.gravel`，且错选 gravel/0.25 音量/无 category）
- `footsteps/ladder`（→`footsteps.bluntwood`）
- `footstep_accent/brush|muffledice|sand|weakice`（accent 叠加，本就走 `getSoundFactoryOrDefault` 正确方法，无影响）

**修复**：`playStep` 主声音改用 `SOUND_LIBRARY.getSoundFactoryOrDefault(soundLoc)`（按 location 查工厂，正确处理 location→event 映射）——一次性覆盖 dirt_path + ladder。`footsteps/dirt_path` 音效从 gravel 改 **grass**（对齐 1.12.2 `grass_path→grass`）、补 `category: PLAYER`、音量 1.0。

**排查方法**：遍历 sound_factories.json 找出所有 `location != soundEvent` 的工厂，对照映射引用确认影响面。

#### 2.16.3 基岩雾改为现代 min Y

**根因**：`BedrockFogRangeCalculator` 的 `BASE_Y=32F` 按 1.12.2 基岩层在 Y=0 设计（32 格是"安全高度"）；现代世界（1.18+）最低层 Y=-64，基岩在 Y=-64。

**修复**：改用 `player.level().getMinY() + 32` 动态获取参考高度——基岩雾梯度跟随真实基岩层，支持任何版本/维度。1.12.2 语义 `factor=(y+4)/32` 等效为 `factor=(y+4-minY)/32`。

**验证**：`./gradlew build` 通过；草径有 grass 脚步声（音量 1.0）、梯子 bluntwood 高音调、基岩雾随 Y=-64 生效——用户逐项实测确认。

#### 2.16.4 完整 mod 环境实测 + 声音/雾警告清理（2026-08-02）

用户装了完整 mod 包（Sodium/Iris 光影、Xaero、Mekanism、IE、Lithium、Biomes O' Plenty、更多工艺等）实测，确认 DS 与光影等大量 mod 兼容正常、稳定进游戏。清理了 debug.log 中 DS 的 4 个实质警告：

| 警告 | 根因 | 修复 |
|---|---|---|
| `Unable to locate sound 'footsteps.concrete_run'` | `LAND_COMPOSITIONS` 混凝土落地用 `concrete_run`，但 sounds.json **未注册该事件**（音频文件已有，缺事件定义）→ 落地 MISSING 静音 | 注册 `footsteps.concrete_run`（11 声） |
| `Unable to locate sound 'footsteps.marble_run'` | 同上，大理石落地 | 注册 `footsteps.marble_run`（11 声） |
| `Unable to locate sound 'ambient/silence'` | `SILENCE` 常量误用**音频路径** `ambient/silence`，实际声音事件 ID 是 `silence` → 穿草静音抑制失效 | 改 `SILENCE = dsurround:silence` |
| `Fog calculator 'Weather' invalid fog range (start 171 > end 96)` ×194/帧 | 光影（Iris）改变雾参数使 `renderDistanceStart` 变大；Weather fog 的 start 未随 end 一起 cap 到 96，start > end → Holistic 组合器拒绝并刷屏 | start 钳制 `min(start*scale, end)` |

**排查方法**：写脚本遍历 `LAND_COMPOSITIONS`/`sound_factories.json` 引用的所有声音事件，与 sounds.json 对比找出未定义事件（找到 3 个：concrete_run/marble_run 是真缺，block.fire.ambient 是 vanilla 事件误报）。

**无害忽略**（无需修复）：
- `Unknown block 'biomesoplenty:glowshroom_block'`（BOP mod 方块，DS 映射里 BOP 条目版本差异）
- `No root paths defined for ResourceLookupHelper`（已知无害）
- Iris refmap 警告、Sodium mixin 协调禁用（mod 间正常协作，非错误）

**验证**：`./gradlew build` 通过（声音事件 145→147）；最新会话 latest.log **0 个 DS 错误/警告**，玩家 `OpticMango29239` 光影环境下稳定进游戏。

---

#### 2.17 极光（Aurora）移植（2026-08-02）

从 1.12.2 源码包 `DynamicSurroundings-1.12.2.zip`（用户提供）移植极光功能。

**关键发现**：1.12.2 极光有**两条渲染路线**（`AuroraFactory` 按 `auroraUseShader` 配置选择）：
- `AuroraShaderBand`：GLSL shader（aurora.vert/frag，取自 Mattenii "Aurora Lights" CC BY-NC-SA 3.0）
- `AuroraClassic`：**立即模式 Tessellator，纯 POSITION_COLOR 三角形色带，无纹理无 shader**（shader 不可用时默认降级）

**26.1 移植决策**：只移植 **Classic 版**（shader 版跳过——26.1 需自定义 RenderPipeline + shader 文件，且 Iris 光影会接管天空渲染覆盖极光，性价比为负）。

**渲染管线改写**（最大改动）：
- 26.1 移除 `Tessellator`/`BufferRenderer`（立即模式全部移除），渲染走 FrameGraph
- 改用电 NeoForge 原生 `RenderLevelStageEvent.AfterSky` 事件 + `Minecraft.renderBuffers().bufferSource()` + `VertexConsumer`
- RenderType 选 `RenderTypes.debugQuads()`：反编译确认其管线 = `DEBUG_FILLED_SNIPPET` = **POSITION_COLOR + QUADS + BlendFunction.TRANSLUCENT + depthWrite off**，正是极光需要的"无纹理半透明填充四边形"
- AfterSky 事件的 poseStack 原点 = 相机位置（neoforge 官方 `BlockEntityRenderBoundsDebugRenderer` 反编译确认），世界坐标顶点用 `translate(worldPos - cameraPos)` 对齐；原 `getTranslationX/Z` 算的正是 `playerWorldPos - cameraPos`，逻辑直接复用
- 1.12.2 的 `GlStateManager.disableLighting/enableBlend/depthMask(false)` 状态设置由 RenderPipeline 内置混合/深度状态替代

**数学工具替换**：1.12.2 的 `MathStuff`（独立 lib）→ 26.1 的 `Mth`（`cos/sin` 弧度制、`abs/clamp/PI/DEG_TO_RAD`）。

**颜色替换**：1.12.2 的 `org.orecruncher.lib.Color`（不可得源码）→ 自建 `ColorF`（float 分量 0-1，`luminance` 乘法调亮）。

**触发条件**（沿用 1.12.2 逻辑）：
- 群系：`BiomeInfo.getTraits().contains(SNOWY) || contains(ICY)`（雪原/冰刺/冰冻洋等；26.1 用已有 trait，无需新增 hasAurora 标记）
- 时间：`DayCycle.SUNSET/NIGHTTIME`（黄昏/夜晚）
- 渲染距离 ≥ 6
- 种子：原版 `GMTDaySeed + day`（每晚同一天极光相同）→ 26.1 用 `level.getOverworldClockTime() / 24000`（游戏天数），同天一致、跨天变化

**踩过的坑**：
- `Level.getSeed()` 不存在 → 用游戏天数做种子
- `ResourceKey.location()` 不存在 → 26.1 用 `identifier()`（返回 `Identifier`）
- `AbstractClientHandler` 是**包私有 abstract 类**（构造器包私有）→ `AuroraEffectHandler` 必须在 `processing` 包而非 `aurora` 子包，否则无法调用 super()
- `IAurora` 接口要求 `render(float)`，但 26.1 渲染需 PoseStack → AuroraClassic 提供 `render(PoseStack, float)` 主方法 + `render(float)` 空兼容桩

**验证**：`./gradlew build` 通过；实测日志 `New aurora [<CLASSIC> bands: 1, off: 28.0, len: 64, base: [0,255,0], fade: [255,0,0]]`，雪原夜晚可见，fade/unfade 生命周期正常，零渲染错误。用户确认"可以"。

#### 2.17.1 shader 版极光尝试（2026-08-03，已回退放弃）

用户希望还原 1.12.2 的 shader 版绚丽效果（彩色动态幕帘）。调研并实现了 26.1 自定义 shader 全套机制，但**多次迭代视觉效果均未达预期，最终回退到经典版**。

**26.1 自定义 shader 机制（已跑通，供未来参考）**：
- pipeline 注册：`RegisterRenderPipelinesEvent`（MOD_BUS，neoforge 的 `NeoForgeRenderPipelines.java` 是官方范例）
- `RenderPipeline.builder()` + `withLocation/withVertexShader/withFragmentShader` + `withUniform(name, UNIFORM_BUFFER)` 声明 DynamicTransforms/Projection/Globals + `withColorTargetState` + `withVertexFormat(POSITION_TEX, QUADS)`
- shader 资源放 `assets/<ns>/shaders/core/*.vsh/fsh`，GLSL 330 + `#moj_import`
- **`GameTime` 全局 uniform**（来自 `globals.glsl`，`#moj_import <minecraft:globals.glsl>`）每帧自动更新，极光动画无需自定义 uniform
- `bindDefaultUniforms` 自动绑定 Projection/Globals/DynamicTransforms
- `RenderType.create(name, RenderSetup.builder(pipeline).createRenderSetup())`（neoforge AT 已把 create 变 public）
- 渲染：`AfterSky` + bufferSource + VertexConsumer，POSITION_TEX

**踩过的坑**：
- **shader 编译失败是最大坑**：`vec3 out = ...` 用了 GLSL 保留字 `out` → `unexpected OUT` 语法错误，且**编译失败无直观报错**（只在 `[mojang/GlDevice] Couldn't compile` ERROR 日志）。**必须每次改动后查编译日志**，否则看到的是"渲染错误"效果而非真实 shader 效果
- `RegisterRenderPipelinesEvent` 是 `IModBusEvent`，须在 MOD_BUS 注册（`modBus.addListener` 或注解扫描）
- 效果调优难点：3D 幕帘在 quad 上映射的视觉（条纹密度/颜色渐变/动画速度）需反复对照，本地截图+8B 模型无法判断动态效果

**结论**：26.1 shader 机制已跑通，但视觉调优工作量大且效果不佳，**回退到经典版**（`useShader` 默认关）。shader 代码已从仓库删除（git 未提交，删除即彻底回退）。如需再试，从 `RegisterRenderPipelinesEvent` + GameTime uniform 这套已验证机制重新出发。

#### 2.17.2 极光触发时间限定（2026-08-03，用户实测确认）

原触发条件 `DayCycle.SUNSET || NIGHTTIME` 覆盖**整个夜晚**（日落~黎明），用户指出极光应只在**午夜前后**出现。

**修复**：改用角度窗口判断（沿用 DayCycle 约定：正午=0°、午夜=180°，`angle = ((clock%24000 + 18000)%24000)/24000*360`），限定 `150° ≤ angle ≤ 210°` = 晚上 10 点 ~ 凌晨 2 点（午夜 ± 2 小时）。

- 午夜 `tick 18000` → 180° ✓；晚 10 点 → 150°（下界）；凌晨 2 点 → 210°（上界）
- 日出/日落/白天不再出现极光
- 移除对 DayCycle 的依赖（改用裸角度计算）
- 用户实测 `/time set 18000` 可见极光，黄昏/黎明无极光，确认 ok

---

#### 2.18 发布准备（2026-08-03）

**目标**：将模组发布到 GitHub（`uawyiegrfv/DynamicSurroundingsRebirth`），命名/署名/协议/调试全部整理干净。

**命名与署名**：
- mod 显示名改为 **Dynamic Surroundings Rebirth**（原版是 "Dynamic Surroundings"）
- 作者：`deepsleep114, OreCruncher`（用户 GitHub 名 uawyiegrfv，git 署名 deepsleep114）
- mod_id 保持 `dsurround`（兼容存档/配置）
- mod_version 保持 `0.5.0`

**协议（MIT）**：
- 保留 OreCruncher 2023-2025 版权
- 追加 deepsleep114 2026 NeoForge port 版权 + **Claude Code 致谢**（LICENSE/README/mods.toml description 均标注）

**清理项**：
1. `No root paths defined for ResourceLookupHelper` 刷屏警告（212次/次）→ WARN 降为 DEBUG（单机客户端无 SERVER_DATA 根路径是正常的）
2. 磁盘残留：`backup*/mdk-ref`（36M）+ 7 个旧修复脚本已删除；保留 git 跟踪的 `fix_26.1_api.py` 作修复记录（后也已从 git 移除）
3. 开发文档（CLAUDE.md/MIGRATION_STATUS.md/docs/CHANGELOG/fix_26.1_api.py）**从 git 移除**（留在磁盘开发用，不公开）
4. 会话导出 txt 加入 .gitignore

**Git 历史压缩**：
- 96 个开发提交（含大量中文/文档提交）→ squash 成 **1 个英文初始提交** `0086685`
- 用 `git checkout --orphan squashed` + 重新暂存 + `git rm --cached` 开发文档后 commit
- 推送需用户手动（浏览器认证），尚未完成

**构建**：`./gradlew clean build` 通过，jar `dsurround-neoforge-26.1.2-0.5.0.jar`（Rebirth 元数据，16:16 构建）

**发布待办**：
- `git push -f origin master`（覆盖 GitHub 旧历史，用户手动执行）
- 创建 GitHub Release v0.5.0 + 上传 jar
- packs/（Seasons/Extended 音乐包）暂不移植，可单独分发（pack_format 已从 34 改为 64 适配 26.1）

---

## 三、构建与运行

### 环境要求
- **JDK 21**（moddev `downloadAssets` 任务需要）：`D:\minecraft\jdk-21.0.11+10`
- **JDK 25**（编译工具链）：已注册于 gradle.properties
- **Gradle 9.2.1** wrapper（已缓存；下载时注意 wrapper 默认 10s 网络超时过短，已调至 300s）

### 构建命令
```bash
./gradlew build          # 编译 + 打包
./gradlew shadowJar      # 打包 Nashorn 脚本引擎（dev-shadow jar）
./gradlew runClient      # 启动游戏客户端
```

### 产物
- `build/libs/dsurround-neoforge-26.1-0.5.0.jar`（主模组）
- `build/libs/dsurround-neoforge-26.1-0.5.0-dev-shadow.jar`（含 Nashorn 1085 个类）

---

## 四、功能实现状态

### ✅ 已实现 / 可正常使用

| 功能 | 说明 | 验证状态 |
|---|---|---|
| 生物群系声 | 基于所在生物群系播放氛围声，随移动平滑过渡 | ✅ 用户确认 |
| 脚步声（表面声 + 涉水声） | 通过声音重映射替换原版脚步，特殊表面附加声 | ✅ 用户确认 |
| 脚步声材质映射（A2-2） | 方块→材质声音映射 106 条，覆盖全原版方块；灵魂沙/玻璃/冰/石英/混凝土/黑曜石/箱子等 7 个新材质音效 | ✅ 已实测（见 2.11/2.16.4） |
| 跳跃声（材质跳跃） | 起跳"呃"声 + 按脚下方块材质 `_wander` 音效（雪/石/泥/草等 12 材质，见 2.15.2） | ✅ 已实测（见 2.15.2） |
| 落地声 | 按材质 `_run`/`_land` 分变体 + 延迟回声（混凝土/大理石等已补全，见 2.16.4） | ✅ 已实测 |
| 穿草音效（brush） | 高草/低草/蕨→`brush_through`、枯灌木→`leaves_through`，位置去重 + 生物移动判定（见 2.15.1） | ✅ 已实测 |
| 脚印（雪层/冰/边缘） | 材质白名单（冰无印）、雪层 `getShape` 表面高度、边缘悬空剔除（见 2.15.3） | ✅ 已实测 |
| 草径/梯子脚步声 | 草径→grass、梯子→bluntwood 高音调（工厂 location≠事件 统一修复，见 2.16.2） | ✅ 已实测 |
| 基岩雾 | 随世界真实 `minY`（Y=-64）渐变，支持任意版本/维度（见 2.16.3） | ✅ 已实测 |
| 环境声 / 天气声 | 森林、平原、洞穴等环境氛围 | ✅ 用户确认 |
| 武器切换声（工具栏） | 物品栏切换音效 | ✅ 用户确认 |
| 热块效果 | 熔岩火焰喷射、蒸汽、气泡柱（使用原版粒子） | ✅ 代码完整 |
| 雷声替换 | `minecraft:entity.lightning_bolt.thunder` → `dsurround:thunder` | ✅ 映射已加载 |
| 增强音效处理（混响/遮挡） | 后台线程计算空间混响效果，4 个混响区 + 遮挡滤镜 + 空气吸收 | ✅ 用户确认混响正常（见 2.6） |
| DS 客户端命令 | `dsbiome` / `dsdump` / `dsreload` / `dsscript` / `dsmm` | ✅ 已注册 |
| 原版服务器兼容 | 纯客户端模组，兼容连接原版服务器 | ✅ 天然支持 |
| 配置系统 | Cloth Config 配置界面（模组列表可打开） | ✅ 已修复 |
| 个体声音控制界面 | 音效设置页新增 "Configure Sounds" 按钮；音量/屏蔽/裁剪/试听逐个声音调整，`soundconfig.json` 持久化 | ✅ 代码完成（待进游戏点开验证） |
| 自定义按键绑定 | 模组配置/声音配置/诊断 HUD 三个按键（"Dynamic Surroundings" 分类） | ✅ 已注册（诊断 HUD 键需进游戏绑定） |
| 音乐播放 toast | 新音乐播放时显示曲名/作者 toast（`SoundToast`/`WarmToast`） | ✅ 已恢复 |
| 调试 HUD（诊断面板） | 手持时钟/指南针显示时间/方位，或按键开关诊断面板（来自 DebugHud 的界面） | ✅ 代码完成（GUI 层已注册，需进游戏实测） |
| 指南针 / 时钟 overlay | 手持对应物品时屏幕上方显示指南针带 / 时钟 | ✅ 代码完成（需进游戏实测） |
| 瀑布声效与视觉效果 | 瀑布水流声（6 档音效）+ 飞溅粒子（原版 SPLASH 粒子） | ✅ 用户确认正常 |
| 萤火虫粒子 | 萤火虫光点围绕灌木丛漂浮（END_ROD 精灵表，26.1 SingleQuadParticle 重写） | ✅ 代码完成（需进游戏实测） |
| 霜息粒子 | 寒冷生物群系生物口鼻呼出的雾气（CLOUD 精灵表，TRANSLUCENT 层） | ✅ 代码完成（需进游戏实测） |
| 水波涟漪粒子 | 雨点滴落水面产生扩散涟漪环（替换原版溅水粒子，水平铺面 + 7 帧动画） | ✅ 代码完成（需进游戏实测） |
| 清晨/生物群系/天气雾系统 | 依据生物群系雾密度、季节随机清晨雾、雨雾调整环境雾距（26.1 原生 `ViewportEvent.RenderFog`） | ✅ 代码完成（需进游戏实测） |
| 村庄探测 | 村民 + 铃铛判定玩家是否在村庄内（供环境状态/诊断使用） | ✅ 已恢复 |
| Serene Seasons 季节兼容 | 安装 Serene Seasons 时显示真实季节、季节性温度/降水（compileOnly 依赖 26.1.2.0.4） | ✅ 代码完成（需装模组实测） |
| 心跳/饥饿/跳跃/落地声（玩家状态） | 血量<25% 心跳（0.8s/次）、饥饿<8 咕噜（15s/次）、起跳声、高处落地声 | ✅ 用户确认（2026-08-01） |
| 制作声 / 药水粒子抑制 | 合成音效（ItemCraftedEvent 节流）/ 玩家药水粒子隐藏 | ✅ 用户确认 |
| 弓箭/盾/弩 + 快捷栏切换 | 弓拉弦、盾举盾、背包换主手物品音效；tag 修正 | ✅ 用户确认 |
| 脚印（材质白名单） | 泥土/草/沙/雪/冰/粘土+扩展方块留印，落地双脚，左右交替 | ✅ 用户确认完美 |
| 暴击词 + 伤害/治疗数字 | 金暴击词、红伤害、绿治疗（真实回血量），3D 飞出 + 距离缩放 + 插值 | ✅ 用户确认 |
| 极光（北极光） | 寒冷群系（雪原/冰刺等）夜晚天空飘动的光带色带，随机几何/配色/动画（`AuroraClassic` + AfterSky，见 2.17） | ✅ 用户确认（2026-08-02） |
| 背景雷声 | 雷暴时 20-40s 远处低沉雷声 | ✅ 用户确认 |
| 基岩/沙漠/海拔雾 | 基岩层加浓、沙漠 medium 雾、云层+高海拔双雾带（280-320）平滑渐变 | ✅ 用户确认 |
| 岩浆冒烟 | 雨落岩浆/下界岩冒烟（SMOKE 粒子） | ✅ 用户确认 |
| 气泡呼吸 | 准心处透明小气泡（安全渲染模式） | ✅ 用户确认 |

### ⚠️ 部分实现

| 功能 | 说明 |
|---|---|
| （无） | |

### ❌ 未实现（当前无 .disabled 文件残留）

> 说明：`src/main/java` 下所有 `.disabled` 文件已在 2.9 轮清零——功能恢复或被 26.1 原生机制取代（如 `MixinTagCollector`→`TagsUpdatedEvent`、`MixinFogRenderer`→`ViewportEvent.RenderFog`、`ParticleRenderCollection`/`DsurroundParticleRenderType`→`SingleQuadParticle.Layer`）。恢复/删除记录见 2.9。

---

## 五、关键文件与配置

| 文件 | 作用 |
|---|---|
| `fix_26.1_api.py` | 26.1 API 精确替换脚本（也是修复记录，勿删） |
| `gradle.properties` | 版本配置（MC 26.1.2 / NeoForge 26.1.2.93 / 工具链路径） |
| `src/main/resources/assets/dsurround/sounds.json` | 模组声音定义（100 个） |
| `src/main/resources/assets/dsurround/dsconfigs/` | DS 数据配置（音效工厂、声音映射、标签等） |
| `run/config/dsurround/dsurround.json` | 运行时配置（含调试开关） |
| `libs/SereneSeasons-neoforge-26.1.2-26.1.2.0.4.jar` | SereneSeasons 26.1 compileOnly 依赖（Modrinth 下载，不入库） |
| `src/main/resources/assets/dsurround/textures/particle/pixel_ripples.png` | 水波涟漪 7 帧条带（在单数 `particle/` 目录，自动缝合进原版粒子图集） |
| `docs/26.1-mod-dev-guide.md` | **26.1 mod 开发参考手册**（渲染/粒子/GUI/声音/雾/时间/重命名/调试，javap 验证的速查表） |

### 调试开关
`run/config/dsurround/dsurround.json`：
- `enableDebugLogging: true` — 开启调试日志
- `traceMask` 位掩码：`AUDIO_PLAYER=1`、`BASIC_SOUND_PLAY=2`、`RESOURCE_LOADING=4`

---

## 六、已知问题与后续计划

### 已知问题
- **代码审查遗留清单（13 项）与待拍板项（3 项）统一见「八、代码审查记录」8.4/8.5**（2026-08-16 全项目审查产出）
- 日志中 `No root paths defined for ResourceLookupHelper` 警告（客户端无服务端数据包磁盘根，**无害**）
- `Unknown block 'biomesoplenty:glowshroom_block'` 警告（用户装了 Biomes O' Plenty，DS 映射里 BOP 条目版本差异，**无害**）
- ~~**增强音效处理（混响）**~~ ✅ 2026-07-31 已修复并**用户确认正常**（根因：OpenAL Soft 新版本默认只给 2 辅助发送，见 2.6）
- 声音配置界面代码完成，需进游戏点开验证（音效设置 → Configure Sounds）
- ~~**A2-2 材质脚步声（2.11）**~~ ✅ 2026-08-02 完整 mod 环境实测通过（含混凝土/大理石落地声，见 2.16.4）
- ✅ **光影兼容**：用户装 Sodium/Iris 光影 + 大量 mod（Xaero/Mekanism/IE/Lithium 等）实测，DS 稳定进游戏、无错误警告（见 2.16.4）

### 后续计划（按优先级）
1. **长时间稳定性测试** — 确认游戏可长时间稳定运行
2. **进游戏实测 2.9 恢复项** — 萤火虫/霜息/水波/雾效/Serene Seasons 季节（需用户装 SereneSeasons 26.1.2.0.4 验证）
3. ~~**恢复声音控制界面**~~ ✅ 已完成（见 2.7）
4. ~~**恢复调试 HUD/指南针**~~ ✅ 已完成（见 2.8）
5. ~~**恢复瀑布效果**~~ ✅ 已完成（用户确认瀑布声+粒子正常，见 2.6 前文）
6. ~~**粒子类特效（萤火虫/霜息/水波）**~~ ✅ 已完成（26.1 SingleQuadParticle 重写，见 2.9.1）
7. ~~**雾系统**~~ ✅ 已完成（原生 ViewportEvent.RenderFog，见 2.9.2）
8. ~~**Serene Seasons 季节兼容**~~ ✅ 已完成（compileOnly 依赖 26.1.2.0.4，见 2.9.3）
9. ~~**A2-2 方块声学映射**~~ ✅ 已完成（见 2.11，待进游戏逐块实测）

### 原版 1.12.2 功能移植计划（按实现难度排序）

> 说明：本项目当前已是 **Fabric 版（1.21 时代）功能的全量恢复**。用户指出原版是 **1.12.2 Forge**（源码 `E:\下载\DynamicSurroundings-1.12.2.zip`，解压 `/tmp/ds1122/DynamicSurroundings-1.12.2`），包含更多功能。以下为原版有而当前 26.1 版本没有的功能，按移植难度从易到难排序。难度评估结合了 26.1 的 API 可行性（渲染管线重构、天气系统重构等）。

| # | 功能 | 难度 | 实现要点（26.1） | 原版参考源码 |
|---|---|---|---|---|
| 1 | 心跳声（血量<40%） | ★☆☆☆☆ | 每 tick 检查玩家血量，低于阈值定时播放心跳音（复用 `IAudioPlayer`） | `client/handlers/effects/` |
| 2 | 饿肚子声（饥饿<40%） | ★☆☆☆☆ | 同上，检查饥饿值 | 同上 |
| 3 | 跳跃音效 | ★☆☆☆☆ | 检测 `onGround` false→true + 上升，播放"huh" | 同上 |
| 4 | 制作音效 | ★☆☆☆☆ | NeoForge `PlayerItemCraftedEvent` + 音效 | `CraftingSoundEffect.java` |
| 5 | 弓箭音效 | ★☆☆☆☆ | 检测弓拉满松开 + 音效 | `EntityBowSoundEffect.java` |
| 6 | 药水粒子控制 | ★☆☆☆☆ | 配置项 + mixin 拦截药水粒子 | `client/effects/` |
| 7 | 伤害/治疗数字 | ★★★☆☆ | 文字粒子（`ParticleTextPopOff`）→ 26.1 `SingleQuadParticle` + 数字字模渲染 | `EntityHealthPopoffEffect.java` |
| 8 | 灵魂沙笑声/木板咯吱 | ★★★☆☆ | 行走方块检测 + 现有方块效果系统加声音 | `client/footsteps/`、`registry/acoustics` |
| 9 | 落灰 | ★★★☆☆ | 特定方块上方生成灰尘粒子（参考水波涟漪/萤火虫） | `client/fx/particle/` |
| 10 | 战斗音乐 | ★★★★☆ | 资源包 + 战斗状态检测 + 音乐管理器切换，需评估 26.1 音乐系统 | `packs/battlemusic`、`client/sound/` |
| 11 | 风暴系统（雨滴/雨声/闪电/沙尘暴） | ★★★★★ | 26.1 天气渲染重构（`WeatherEffectRenderer`），旧 `StormRenderer`/rain 纹理不适用，需重写 | `client/weather/`、`client/renderer/weather/` |
| 12 | 极光（极地夜晚） | ★★★★★ | ✅ **已实现**（2026-08-02，见 2.17）。只移植 Classic 版（无 shader，AfterSky + debugQuads），跳过 shader 版 | `processing/aurora/` |

**建议实施顺序**：从 ★☆☆☆☆ 的 6 项音效/控制类入手（相互独立、复用现有音效系统），再按需推进中等的（伤害数字较有价值），最后评估风暴/极光（渲染重写工程量大）。—— 极光已完成（2026-08-02），剩余重点是风暴系统。

### 3.x 从 `dsurround.cfg` 完整盘点补充的缺失功能（2026-08-01）

用户提供了原版 1.12.2 实际运行配置文件 `D:\minecraft\.minecraft\versions\1.12.2-Forge_14.23.5.2864\config\dsurround\dsurround.cfg`（CONFIG_VERSION 3.6.2.1，395 行）。逐项核对后发现下表功能在当前 26.1 版本**缺失**（此前 MIGRATION_STATUS 的功能清单不全面）。**用户实测指出**：原版脚步声不仅是较大，而是声音完全不同；跳跃后有落地声（LAND）。

> **实现状态（截至 2026-08-01）**：✅ 已完成 —— 原 #4 制作声、#5 弓箭（含盾/弩/快捷栏切换）、#6 药水粒子、A1 落地声、A7 脚印（含材质白名单 + 落地双脚 + 伤害数字）、A8 暴击词语（含绿色治疗数字）、A9 背景雷声、A11 三档雾（基岩/沙漠/海拔+高海拔）、A12 岩浆冒烟、气泡呼吸；A3 雨坑声、A13 水花 —— 已实现。❌ 未完成 —— A2 完整脚步声（大项）、A14 喷泉、A17 天气系统。

| # | 功能（cfg 键名） | 难度 | 说明 |
|---|---|---|---|
| A1 | 落地声（footsteps EventType.LAND） | ★☆☆☆☆ | ✅ 已实现。从高处落下（fallDistance 超阈值）播放落地声。原版 `Generator.simulateJumpingLanding`，`LAND_HARD_DISTANCE_MIN` |
| A2 | 完整脚步声系统（footsteps Generator） | ★★★★☆ | 用户实测：原版脚步声不仅更大，声音也完全不同。当前 26.1 只 remap 原版脚步（Minecraft 触发），原版是 DS 自行生成 WALK/RUN/JUMP/LAND/CLIMB + mcp.json 每方块声学。需重建 footsteps Generator |
| A3 | 雨坑声（Rain Puddle Sound） | — | ✅ **用户确认已实现**（雨天移动踩水坑声） |
| A4 | 箭矢暴击尾迹禁用（asm） | ★☆☆☆☆ | 禁用箭矢飞行留下的暴击粒子尾迹（当前 `particleTweaks.suppressProjectileParticleTrails` 是否覆盖需核对） |
| A5 | 物品栏药水图标禁用（asm） | ★☆☆☆☆ | 关闭物品栏中药水效果图标显示 |
| A6 | 水面悬浮粒子禁用（effects） | ★☆☆☆☆ | 关闭水下悬浮粒子效果 |
| A7 | 脚印（effects.Footprints + Footprint Style） | ★★☆☆☆ | ✅ 已实现（材质白名单 + 落地双脚 + 走路/跳跃）。行走留下脚印粒子（6 种样式） |
| A8 | 暴击词语（Show Crit Words） | ★★☆☆☆ | ✅ 已实现（含红色伤害数字，3D 飞出 + 距离缩放 + 插值平滑）。暴击时屏幕显示随机词语 |
| A9 | 背景雷声（Background Thunder） | ★★☆☆☆ | ✅ 已实现。雷暴时每 20-40 秒播远处低沉雷声 |
| A10 | 后台静音（Mute when Background） | ★★☆☆☆ | 游戏切到后台时静音（与"后台时暂停声音"原版选项） |
| A11 | 基岩雾 / 沙漠雾 / 海拔雾（fog） | ★★★☆☆ | ✅ 已实现。基岩层加浓、沙漠 medium 雾密度、云层/高海拔双雾带（280-320 第二层，平滑渐变） |
| A12 | 下界岩浆雨溅射（Netherrack Splash） | ★★★☆☆ | ✅ 已实现（主世界岩浆/下界岩雨滴冒烟 SMOKE）。下界/岩浆块上雨滴溅射粒子 |
| A13 | 水花（Water Splash） | ★★★☆☆ | 水流倾泻产生的水花粒子 |
| A14 | 喷泉（FountainJetEffect） | ★★★☆☆ | 熔岩/水流形成的喷泉喷射粒子 |
| A15 | 语音气泡（speechbubbles） | ★★★★☆ | 玩家/实体头顶语音气泡 + 实体聊天气泡（需服务端配合） |
| A16 | 脚步声系统完整移植（walk/jump/land/climb 全套 + 方块声学） | ★★★★☆ | 原版脚步声由 DS 自行生成（EventType.WALK/RUN/JUMP/LAND/CLIMB + mcp.json 声学），当前 26.1 只 remap 原版脚步。完整移植需重建 footsteps Generator |
| A17 | 自定义天气/雨强度（rain + Weather Control） | ★★★★★ | 见上表 #11 风暴系统 |
| A18 | 战斗音乐（Battle Music） | ★★★★★ | 见上表 #10 |

**与 1.12.2 当前配置文件（用户实际设置）的差异**：用户的 cfg 中 `B:"Jump Sound"=true`（原版默认 false，用户已开启）；`playerHurtThreshold=0.25`、`playerHungerThreshold=8`、`suppressPotionParticles=false`；`effects.Footprints=true`、`"Damage Popoffs"=true`、`"Show Crit Words"=true`、`"Enable DustJetEffect Motes"=true`；`fog.Bedrock/Desert/Elevation` 均 true；`sound.Armor Sound=true`、`"Rain Puddle Sound"=true`、`"Background Thunder"=true`；`speechbubbles.EnableEntityChat=false`（默认关闭）。

**未完成任务按难度重排（2026-08-01 更新，A2 合并为大类）**：

| 顺序 | 任务 | 难度 | 说明 |
|---|---|---|---|
| 1 | **A2 完整脚步声系统**（大类，含子模块 A2-1~A2-8） | ★★★★☆ | 重建 footsteps Generator（区分跑/跳/走）+ 方块声学映射 + 变奏器 + 声学组合器 + 播放集成 + 盔甲脚步 + 脚步强调补全 + 方块效果补充（落灰/灵魂沙笑声）。用户实测：原版脚步声声音完全不同 |
| 2 | **A17 天气系统** | ★★★★★ | 自定义雨/雪/**尘**渲染（`StormRenderer` 1.12.2 立即模式 → 26.1 `WeatherEffectRenderer` 重写），沙漠/下界**滤镜 + 粒子雨**（`dust_*.png` 8 强度）。纹理可复制，渲染核心需重写 |

> 用户 2026-08-01 确认**不实现**：A4 箭矢尾迹、A5 药水图标、A6 水悬浮粒子、A10 后台静音、A14 喷泉（原版仅对 mod 方块生效，无 vanilla 效果）、A15 语音气泡、A18 战斗音乐。A2 子模块分类保留在 3.y 节，作为一个大项推进（内部按 A2-1~A2-8 顺序）。

> **A17 补充说明（2026-08-01 代码调研）**：原版 `Weather.Properties` 按强度映射 `rain_*/snow_*/dust_*.png` 纹理，`StormRenderer` 用 Tessellator/GL11 立即模式绘制替换原版雨雪。26.1 已删除立即模式、天气渲染重构为 `WeatherEffectRenderer`，完整移植需深度重写（本次迁移最大工程）。用户实测关注点：沙漠 **dust 沙尘**滤镜 + 粒子、下界**尘雨**。

### 3.y 脚步声系统分类规划（2026-08-01，A2 分解为子项目）

参考原版功能介绍 + 源码调研（`Generator`/`BlockMap`/`Variator`/`AcousticResolver`/`SoundPlayer`/`accents`），将脚步声系统分解为**可独立推进的子项目**：

| 子项目 | 难度 | 说明 |
|---|---|---|
| A2-1 脚步状态检测（Generator 核心） | ★★★☆ | ✅ 已完成（2026-08-01）：`FootstepGenerator` 重建，玩家走/跑步频自然（步距 walk 0.7/run 0.95，位移累计 ×0.6），落地声（fallDistance>0.9），mixin 屏蔽玩家原版脚步避免双重 |
| A2-2 方块声学映射（mcp.json） | ★★★☆ | ✅ 已完成（2026-08-01）：移植原版 mcp.json footsteps 表 → 26.1 `sound_mappings.json`（106 条规则，覆盖全原版方块）。新增 7 个材质声音（quicksand/weakice/glass/marble/concrete/lino/squeakywood），音频 183→295。详见 2.11 |
| A2-3 声学组合器（Association/DelayedAcoustic） | ★★★☆ | ✅ 已完成（2026-08-01）：sound_mappings 规则 `accent` 字段实现 simultaneous 组合音（草/树叶/冰/玻璃/砂岩/荧石），落地声 delayed 回声。详见 2.12 |
| A2-4 变奏器（Variator） | ★★☆☆ | ⏳ 待做（低优先）：每方块音量/音高/节奏数据驱动参数（当前用音高工厂 + 音量常量近似） |
| A2-5 声音播放集成（SoundPlayer） | ★★☆☆ | ✅ 已完成（2026-08-01）：run/walk 分变体（`isSprinting` 判定 + `*_run` 事件）、脚步音高随机、落地声多层叠加 + 音量对比（0.55 vs 1.0+0.5+0.85）。详见 2.12 |
| A2-6 盔甲脚步声 | ★★☆☆ | ✅ 已实现：ArmorAccents（按脚/腿/胸甲槽播装备声，`enableArmorAccents`） |
| A2-7 脚步强调（accents） | ★☆☆☆ | ✅ 已实现：FloorSqueak（木板咯吱 1/10）、Watery（湿地/雨坑）、Brush/Leaves 通过 remap accent 实现 |
| A2-8 方块效果补充 | ★★☆☆ | ✅ 已完成（2026-08-01）：落灰（`DustParticle` + `DustJetProducer`，浮空泥土下方飘落小阵尘土，`enableDustJets` 开关，spawnChance 0.01）；灵魂沙笑声 ✅、木板咯吱 ✅ |

**方块效果现状（全部已实现）**：✅ 岩浆喷口/火焰喷射（`FlameJetProducer`）、水下气泡（`UnderwaterBubbleProducer`）、蒸汽柱（`SteamEffectSystem`）、萤火虫（花丛，`firefliesEnabled`）、瀑布水声+水花（`WaterfallEffectSystem`）、岩浆冒烟（`MagmaSplashHandler`）、落灰（`DustJetProducer`）。

**建议推进顺序**：~~A2-1~~ ✅ → ~~A2-2~~ ✅ → ~~A2-3~~ ✅ → ~~A2-5~~ ✅ → ~~A2-6~~ ✅ → ~~A2-7~~ ✅ → ~~A2-8 落灰~~ ✅ → **A2-4 变奏器**（可选）→ A17 天气系统。

---

## 七、Git 版本管理

### 仓库结构
- **独立仓库**：本项目在 `dsurround-neoforge-26.1` 内独立 `git init`，与父目录 `E:\claude code`（一个包含多个无关项目且无提交的仓库）完全隔离。
- **GitHub 远程**：`https://github.com/uawyiegrfv/DynamicSurroundingsRebirth`（用户 `uawyiegrfv`，git 身份 `deepsleep114 / abc18355325322@outlook.com`）
- **历史已 squash**（2026-08-03）：开发过程中的 96 个琐碎/中文提交已压成 **1 个英文初始提交** `0086685`（"Dynamic Surroundings Rebirth: NeoForge 26.1 port of Dynamic Surroundings"），便于公开。
- 已提交：1270 文件（仅源码/资源/配置/README/LICENSE/CREDITS，**不含开发文档**）。

### 发布配置（gradle.properties / LICENSE / README）
- mod 名称：**Dynamic Surroundings Rebirth**（不再沿用原版 "Dynamic Surroundings"）
- 作者：`deepsleep114, OreCruncher`
- LICENSE：MIT（保留 OreCruncher 版权 + deepsleep114 port 版权 + **Claude Code 致谢**）
- ModInformation 版本检查 URL：指向 `deepsleep114/DynamicSurroundingsRebirth`
- mod_version 保持 `0.5.0`

### 已排除（.gitignore）
- 构建产物：`build/`、`run/`、`.gradle/`
- **开发文档**（留在磁盘供开发，不提交）：`CLAUDE.md`、`MIGRATION_STATUS.md`、`docs/`、`fix_26.1_api.py`、`CHANGELOG.md`
- 会话导出：`2026-08-02-...migration.txt`（Claude Code 会话记录）
- 可选资源包：`packs/`（Seasons/Extended 音乐包，暂不移植，可单独分发）
- 磁盘已清理：`backup*/mdk-ref` 备份目录（36M）和 7 个旧修复脚本已删除

### 发布状态
- jar 已重新构建：`build/libs/dsurround-neoforge-26.1.2-0.5.0.jar`（含 Rebirth 元数据）
- **待完成**：
  1. `git push -f origin master`（覆盖 GitHub 旧 96 提交历史，需要用户手动推送因浏览器认证）
  2. 创建 GitHub Release v0.5.0 + 上传 jar
- 可选资源包（Seasons/Extended）暂不移植，如发布可单独分发

### 日常操作
```bash
git status            # 查看状态
git add -A            # 暂存全部修改
git commit -m "描述"   # 提交
git log --oneline     # 查看历史
git push origin master # 推送
```

---

## 2.19 第二版 + 性能优化 + 后续修复（2026-08-07）

### 第二版提交（`7a32da1`，已提交）
标题 "Ambient animal sounds, indoor attenuation, and sound fixes"（36 文件，314+/8-）。
内容：
- **氛围动物声**：蛙鸣（睡莲方块触发）、猴子/野牛/鳄鱼/大象/响尾蛇（群系 mood 触发）。从原版 1.12.2 补回 **26 个漏掉的 ogg**（frog/primate/bison/crock/elephant/snake），sounds.json 注册 8 事件（含 hiss），biomes.json +5 群系条目，blocks.json +2 方块条目，中英字幕 +8。原版 bullfrog/coyote 的 CC-BY 授权信息保留为 `ds_credits`。
- **环境音室内衰减**：BiomeSoundHandler 全局室内 ×0.15（loop + mood），室内判定 `scanner.isInside()`（CeilingScanner 头顶遮挡率 >63%）。
- **暴击词/伤害数字穿墙修复**：CritWordHandler 每字 `level.clip(COLLIDER)` 视线检测，被方块遮挡不渲染。
- **生物边缘石头脚步修复**：`remapMobStepSound` 脚下空气/流体时返回 null（MC 空气块默认 soundType=STONE，边缘悬空会误判石头声）。
- **Cloth Config 依赖**：mods.toml 声明 `cloth_config` required（版本 ≥26.1.154）。只装 DS 时配置按钮打不开是因为 Cloth 缺失且未声明依赖，现在明确提示。

### 性能优化（代码完成，**未提交**）
用户实测：平原均帧 1300→1200、1% low 300→160；矿洞大量怪物回声 1000→700、low 大幅下降。
用 **handler 每 10 秒耗时探针**（Handlers.tick 输出 TimerEMA，探针已移除）量化定位：
- 初始：AreaBlockEffects 0.4~1.4ms/tick（最大热点，RandomBlockEffectSystem 每 tick 采样 667 方块位）、Scanners 0.1~0.2ms、其余 <0.1ms。总 **~1.8ms/tick**。
- **回声系统是大头**：每声音源每 7 tick 后台做 32 射线×(1+4反弹×2)≈300 次方块射线，矿洞大量源 → 2 线程打满 → CPU 争用。

已做优化（全部保持效果，用户实测"有一定提升"，handler 总耗时降至 **~0.6ms/tick**）：
1. `SourceContext.UPDATE_FEQUENCY_TICKS` 7→20（每秒 1 次，遮挡/混响是慢变参数，感知无差异）
2. `SoundFXProcessor`：每轮最多处理 32 源（距离优先，`MAX_SOURCES_PER_PASS`）
3. `EntityEffectHandler`：实体扫描 2→4 tick
4. `AreaBlockEffects`：方块采样 NEAR 1→2、FAR 3→4 tick
5. `RandomBlockEffectSystem.ITERATION_COUNT` 667→500
6. `CeilingScanner.SURVEY_INTERVAL` 4→8（矿洞/地下向下扫描尖峰减半）
7. **Nashorn 脚本快路径**（最大收益）：`Script.getConstant()` + `ExecutionContext.eval` —— 纯数字/布尔脚本（如 soundChance "0.05"、默认条件 "true"）直接解析返回，不碰 JS 引擎。消除特效密集区每次命中的解释执行开销。

**多线程/GPU 说明**：回声已是后台线程池；GPU 加速需方块世界上传 GPU + compute shader + 回读，引擎级工作量不现实；MC 客户端 tick 单线程，handler 无法并行化。最现实优化 = 减少总工作量（已做）。

### 日志刷屏修复
根因：`run/config/dsurround/dsurround.json` 里 `enableDebugLogging=true + traceMask=3`（音频 trace 全开，调试残留）→ runClient 每次声音/生物脚步刷 `PLAYING:`/`Mob sound remapping`/`TOO FAR`。
已修复：dev config 改回 `enableDebugLogging=false, traceMask=0`。启动器环境（默认配置）本无刷屏（latest.log 81 条 dsurround 日志，正常）。
这些日志是 debug 级别（ModLog.debug 需 `isDebugging()` 才输出），默认关。

### 脚印浮空修复（代码完成，**未提交，待用户测试确认**）
现象：支撑方块被挖掘后脚印粒子浮空（原 10 秒生命周期）。
修复：`FootprintParticle.tick()` 检查支撑方块——空气则 `remove()`；否则算 `getShape` 表面高度，粒子浮空 >0.35 格（多层雪被削薄、表面下降）也 `remove()`。正常坐地面差值仅 0.02，不误删。
注意：雪层用 `getShape` 视觉表面（同 spawnPrint 的 2.15.3 约定，勿用 getCollisionShape）。

### 当前待办
- **未提交**：性能优化 8 文件 + 脚印修复 1 文件 + dev config 修改（run/ 被 .gitignore 排除）。可提交为第三版。
- **用户测试确认**：脚印浮空修复（挖方块/多层雪削薄）。
- **发布**：`git push -f origin master`（用户手动，浏览器认证）+ Release v0.5.0。
- **Iris/Sodium 崩溃**：`IncompatibleClassChangeError: ArmorAccents$$Lambda 被当 GlTessellation`，Iris 阴影渲染路径，非 DS 逻辑问题（ArmorAccents 是纯音频类）。用户关光影/隔离优化 mod 可确认。参考：GitHub IrisShaders/Iris#2828。
- **新功能方向**（用户讨论过）：樱花花瓣飘落（cherry_grove）、深暗之域/远古城市氛围、洞穴氛围分层、极光 shader 重做、红树林萤火虫、竹林/原始针叶林映射、试炼密室氛围。

## 2.20 落块扬尘 + 树林风叶 + 枯叶堆素材重做（2026-08-09）

### 沙/砾石落地扬尘（`c818749`）
- **功能**：沙/砾石落地时在落点扬起彩色尘团（沙黄/砾石灰），横向扩散、少上升。
- **落地检测关键技术**（踩过多轮坑，务必记住）：
  - 客户端 FallingBlockEntity 的 `onGround()` **永远为 false**——客户端的 `move()` 是纯插值移动，不跑碰撞检测。
  - 事件方案（EntityJoinLevelEvent/EntityTickEvent.Post）都不可靠：服务端落地瞬间 `setBlock → discard()`，实体同 tick 被移除，Post 事件错过。
  - **可靠方案**：注入 `Entity.onClientRemoval()`（FallingBlockEntity 未 override 它，所以 `@Mixin(Entity.class)` + `instanceof FallingBlockEntity` 过滤）。服务端把落块变回方块时 discard 实体 → 同步到客户端 → 客户端 `ClientLevel.removeEntity` 调 `onClientRemoval()`，位置即落点。
- **实现**：`MixinFallingBlockEntity`（触发 `ClientEventHooks.FALLING_BLOCK_LAND_EVENT`）+ `FallDustCloudHandler`（生成 `DustCloudParticle`）+ 配置 `blockEffects.fallingBlockDustEnabled`（中英标签）。

### 树林风吹树叶（`64860a1` 含）
- **需求**：所有树林群系加"偶尔一阵风吹树叶"。原 `biome.wind` 是纯低频呼啸（68% 低频），非树叶沙沙。
- **素材**：Freesound CC0 `575380 cinetony leaves-rustle-in-the-wind`，截 50.5-60.5s，音量对齐原版（rms ~0.018），cos 渐入 1s/渐出 3s。
- **独立触发**（重要）：`moodSoundChance` 是 **biome 级共享**（同一群系所有 mood 音共享一个概率），无法只让单个声音独立。leaf_wind 因此**不放进 mood 集合**，改在 `BiomeSoundHandler.handleLeafWindGust()` 里硬编码独立触发：`isWooded()`（FOREST/CONIFEROUS/DECIDUOUS/JUNGLE trait）→ 独立概率（白天 0.0011≈3min/次，晚上 0.0033≈1min/次）→ `createAtLocation` 播放。这样**不影响鸟叫等其他 mood**。
- 注意：`sounds.json` 的 `volume` 字段可独立控制 LOOP 音量；`Script` 支持 `lib.iif(diurnal.isNight(), a, b)` 动态脚本。

### 枯叶堆脚步素材重做（`64860a1` 含）
- **问题**：原合成枯叶碎声太刺耳（peak 0.85）。换 BigSoundBank CC0 `FEETHmn 0137`。
- **走路**：从 63.7s 素材切 6 个真实脚步（4 个独立脚步 × 2 温和音高），peak 0.45、0.60s，对齐原版 leaves_through 柔和标准。
- **落地**：专门 `leaves_crunch_land`（15.36s 最强实脚步，peak 0.9），落地组合 `(land, null, null)`——**echo 改 null 需给 playLand 的 echo 加 null 检查**（原代码无条件 create，NPE）。

### 当前提交历史（截至 2026-08-09）
```
64860a1 Forest leaf-wind gusts and reworked leaf-litter footsteps
c818749 Dust clouds when sand or gravel lands
6c75dc6 Leaf litter crunch footstep sound
36a855d Performance optimizations and footprint support fix
7a32da1 Ambient animal sounds, indoor attenuation, and sound fixes
0086685 Dynamic Surroundings Rebirth: NeoForge 26.1 port
```
### 待办
- `git push -f origin master`（用户手动）+ Release v0.5.0
- Iris/Sodium 崩溃确认（关光影）
- 新功能方向（见 2.19）

## 2.21 深暗之域氛围 + NeoForge 版本放宽 + ATM11 验证（2026-08-10）

### 深暗之域（The Deep Dark）专属氛围
三层设计：
1. **drone 常驻 loop**（`biome.deep_dark`，11s 无缝）：素材 = 用户找的 Freesound `691481 bowesy war-droneambience` + `268198 the_odds all-comments`（均 CC0），混合截取稳定段 + crossfade 无缝循环，rms 0.022。
2. **心跳常驻 loop**（`biome.deep_dark_heartbeat`，5.4s 无缝）：原版 DS 1.12.2 素材 `ambient/steve/heartbeat.ogg`（0.81s 单跳 lub-dub、低频主导），按 0.9s 周期（66bpm）循环 6 次，音量 0.02（曾试 0.01 被 drone 埋没，后加大）。
3. **Sculk 咔嗒间歇**（`biome.sculk_click`，2.6s）：合成谐振敲击脉冲（对齐原版 sculk_clicking 特征：18ms 短脉冲、700-1100Hz 共振峰、频谱尖锐度 7.7），`handleSculkClick()` 独立概率触发（0.0017 ≈ 2min/次），不占共享 mood 概率。

### 关键技术（踩坑记录）
- **室内衰减豁免**：深暗之域在地下必被 `isInside()`（天花板检测）判为"室内"，所有 biome loop 被 `INDOOR_VOLUME_SCALE=0.15` 衰减 → drone 几乎无声。`generateBiomeSounds()` 对 deep_dark 专属 factory（`isDeepDarkAmbience` 匹配 `biome.deep_dark`/`biome.deep_dark_heartbeat`）豁免衰减。
- **无缝循环**：crossfade 首尾（0.8-1s）保证循环点无缝；**切勿在无缝循环后再加首尾淡入淡出**（会致每次循环"断一下"）。
- **libsndfile 写 ogg 大数组崩溃**：`sf.write` 一次性写 >~53 万样本的 ogg 会让 python 进程崩溃（无 traceback，shell 报 127），须用 `sf.SoundFile` + 分块 `write()` 流式写入（chunk 96000）。
- 深暗之域匹配：`biome.id == 'minecraft:deep_dark'`（路径含 "dark" 自动带 SPOOKY trait）。通用 `UNDERGROUND` 块排除 deep_dark 避免双 loop。`sculk_click` 是合成音故无 credits；drone 素材已标注 CC0 credits。

### NeoForge 版本放宽
- 原 `[26.1.2.93,)` → `[26.1.2.78,)`。三处改动：`gradle.properties` 新增 `neo_version_range` 变量、`neoforge.mods.toml` 模板 `versionRange="${neo_version_range}"`、`build.gradle` 的 `generateModMetadata` replaceProperties **白名单**注册该变量（模板展开只认注册过的 key，否则 `Missing property` 构建失败）。
- MC 仍精确 `[26.1.2]`。声明放宽 ≠ 保证运行：代码针对 .93 编译，同 MC 版本内构建 API 差异极小，实测确认。

### ATM11 整合包实测（用户确认）
- **完美运行**。脚印部分比 ATM11 自带脚印模组更好；ATM11 自带模组存在我们已踩过的坑（脚印浮空、雪层表面高度等）。

## 2.22 藏宝图距离 GUI（2026-08-10，`47484e9`）

### 功能
- 手持探险者地图（主/副手）时，地图右上角显示**到宝藏目标的水平距离**（纯数字+米，如 `128米`），文字随地图 3D 视角一起转动。普通地图不显示。

### 实现（参考 treasuredistance，CC BY 署名）
- **`MixinMapRenderer`**：注入 `MapRenderer.render(MapRenderState, PoseStack, SubmitNodeCollector, boolean, int)` 的 `@TAIL`，在**地图自身的 PoseStack** 里 `translate(128 - textWidth*scale - 2, 2, -0.025)`（右上角）→ `scale` → `collector.order(1).submitText(...)` 提交 3D 文本。文字因复用地图 pose 而天然跟随视角。
- **距离**：`stack.get(DataComponents.MAP_DECORATIONS)` → 遍历装饰，找 `type().value().showOnItemFrame()`（目标装饰）→ `Entry.x()/z()` 是 **double 世界坐标** → `hypot(player.x-x, player.z-z)`。

### 关键技术/坑（务必记住）
- **客户端地图数据不接收中心**：`MapItemSavedData` 客户端由 `createForClient(scale, locked, dimension)` 创建（**无 center 参数**），且 `ClientboundMapItemDataPacket` 同步包**不含 centerX/centerZ**。故客户端 `MapItemSavedData.centerX/centerZ` **恒为 0**（=世界原点）。**普通地图"到中心距离"纯客户端无法实现**（会显示成到 (0,0) 的距离，如 6000 米）。已按用户决定只做藏宝图。
- 藏宝图目标装饰坐标可靠：`MapDecorations`（物品组件）随物品同步，`Entry.x/z` 是服务端写入的**世界坐标**（double）。
- 地图本身是**第一人称 3D 手持渲染**（`ItemInHandRenderer.renderMap`），非 HUD 贴图；"贴地图"靠复用地图 PoseStack 提交文本。
- 渲染架构 26.1 与 1.21.10 一致（`MapRenderer.render` 签名、`OrderedSubmitNodeCollector.submitText` 相同）——treasuredistance(1.21.10) 的方法可直接移植。
- 配置开关 `mapOptions.enableTreasureDistance`（`Configuration.MapOptions`，中英/pl 翻译）。auroraOptions 配置翻译补齐（原缺失）。

## 2.23 声音滑块 + 脚步完善 + 发布准备（2026-08-10，提交 6c33865/85c411c/ed16683/255e8b1/9d5ba78）

### 声音设置界面滑块（`MixinSoundOptionsScreen`）
- 26.1 的 `OptionInstance.SliderableValueSet` 是**包私有**，不能外部实现 → 改用自定义 `AbstractSliderButton` 子类 `DsVolumeSlider`（需实现抽象方法 `applyValue`/`updateMessage`），`this.list.addSmall(...)` 追加到声音列表。
- 两个滑块：**Footsteps / Biomes**，0-200%（0→2 映射），0% 显示 "关"（`options.off`）。值存 `Configuration.SoundOptions.footstepVolume/biomeVolume`。
- 音量应用：FootstepGenerator 所有播放点 ×`dsFootstepVolume()`；BiomeSoundHandler 的 `generateBiomeSounds`(loop)/`createMoodInstance`/leaf_wind/sculk ×`dsBiomeVolume()`。
- **footstepVolume=0 → 恢复原版脚步**（三处配合）：① FootstepGenerator.process 提前返回；② `SoundLibrary.remapSound` 对 `.step` 且音量≤0 不映射；③ `MixinEntity.dsurround_cancelPlayerStep` 音量≤0 不取消原版玩家脚步（否则玩家无原版脚步、只有生物有）。

### 爬梯子音效（对齐原版：播放原版表面音效）
- 根因 1（不触发）：脚步距离只用**水平位移**（`Math.hypot(dx,dz)`），爬梯子是垂直运动 → 加 `onLadder` 时 `+= abs(dy)`。
- 根因 2（材质解析错）：`resolveSurfaceBlock` 解析不到梯子（无碰撞）→ 爬梯子时直接用 `player.blockPosition()` 的攀爬方块。
- 根因 3（仍播 DS 材质）：`playStep` 跳过 `getRemappedSound` 播原版 `block.ladder.step`，但播放管线 `SoundLibrary.remapSound` 又映射回 DS 材质（字幕显示"木质脚步"）→ remapSound 对 `.step` 且位置在 `BlockTags.CLIMBABLE` 上时 `return Optional.empty()`。
- `onLadder = player.onClimbable()`（CLIMBABLE 标签，覆盖梯子/藤蔓/竹/脚手架/垂泪藤）。`CLIMB_VOLUME_BOOST` 最终 1.0（用户嫌 2.0 太大）。

### 基岩雾与混响
- 基岩雾太浓：`BedrockFogRangeCalculator` 最深视距硬下限 5→20。生效范围=维度 minY 到 minY+32（主世界 -64~-32）。
- 矿洞回声太强：`Effects.GLOBAL_REVERB_MULTIPLIER` 1.0→**0.7**（原版 1.12.2 基线，26.1 曾提 1.0 导致混响过强）。

### 脚步素材响度（用户：草比石头响、石头疾跑单薄）
- 素材文件与原版 1.12.2 **逐字节一致**（stone_walk/grass_run 等）；差异在响度分布：stone_run rms 0.018 vs grass_run 0.040（草是石的 2.2 倍）。
- **log 素材错误**：移植时自造 `log_walk`（8742B），原版 log 复用 `wood_walk`（8469B）→ `footsteps.log` 事件改回 `wood_walk` + 原版低沉 pitch 0.55-0.65。
- **stone_run 响度放大**：11 个素材 normalize 到 rms 0.036（0.018→0.036，peak 保护 <0.98）。
- 注意：MC 单声音量 clamp 1.0，**不能靠 volume 配置补偿**响度，必须在素材层面 normalize。

### 工程清理（AI 审查，用户让参考）
- 删 YACL 死依赖（build.gradle+gradle.properties；`Constants.YACL` 保留无害）、修失效 JDK 25 路径（实际在 `C:\Program Files\Eclipse Adoptium`）。
- **初始化时序重构已回退**：`RegisterGuiLayersEvent` 早于 `FMLClientSetupEvent`，延迟初始化会让 GUI 层 resolve 崩溃 + SoundManager factory 注册太晚导致**声音全失效** → 构造器保持全量初始化（`ensureInitialized` 方案也崩过，最终回退原样）。
- jar 名改 `DynamicSurroundingsRebirth-26.1.2-<ver>.jar`（archivesName）。

### toolbar 切换音效 bug
- `ToolbarEffect` 用 `ItemStack.matches`（比较全部组件含耐久）→ 工具耐久下降误判"换物品"→ 播切换音效。改用 `ItemStack.isSameItem`（只比物品类型）。

### README 更新 + 发布
- README 加 **AI 免责声明**、Features 补新功能（深暗/风叶/藏宝距离/滑块/HUD/扬尘等）、NeoForge 版本 26.1.2.78、jar 名。爬梯子是 bug 不入 Features。
- 版本 **0.5.0**；git push 完成（master 到 `9d5ba78`）；tag `v0.5.0` 本地已建（指向 ed16683，README 提交在后，发布时可考虑挪 tag）；**Modrinth 上传中（新项目审核，可见不可下载属正常）**；CurseForge 待传。

## 2.24 水下声学 + 方向性 bug 根治（2026-08-11）

### 水下声学（新增功能）
- **配置**（EnhancedSounds）：`enableWaterSoundDamping`(开关)、`waterSoundDamping`(音量每格保留率, 默认0.9)、`waterSoundMuffle`(闷感每格截止率, 默认0.6)，两个独立滑块。
- **实现**（SoundFXUtils）：`calculateWaterPathLength` 从声源原始位置到玩家眼**全程 0.5 格采样**（getFluidState 判水，覆盖 waterlogged），累加穿水长度 → `muffle=0.6^len`(低通 gainHF) + `gain=max(0.15, sqrt(0.9^len))`(音量 gain)。音量与闷感解耦，深水稳定闷、远处不消失。
- **时间平滑**（SourceContext.smoothWaterFactor，alpha 0.7）：实体水面浮动/玩家移动时渐变不跳变；`snap` 参数让出入水瞬间直接到位。
- **出入水立即响应**（SoundFXProcessor）：clientTick 检测玩家 isUnderWater 翻转 → 下轮全部源立即重算 + `markImmediate()` → snap，零延迟。
- 坑：`offsetPositionIfSolid` 原版"非空气即偏移"会把**水当实心**推出（水 != AIR）→ 判断改为 `isSolid() && fluidState.isEmpty()`。注意 `BlockBehaviour.Properties.solid` 默认 true、水不清它，**必须显式排除流体**。

### ★★★ 方向性 bug 根因（移植产生的，排查极难）
- **现象**：环境完全对称（超平坦埋唱片机），玩家固定半径绕圈，声音"半边闷半边不闷"（方向性遮挡），忽闷忽亮。
- **根因**：`ReusableRaycastIterator.next()` 移植时被改错——原版 `setStart(hitResult.getLocation().add(normal))`（**实际命中点** + 射线方向），被改成 `new Vec3(hitResult.getBlockPos().getX()+normal.x(), ...)`（**方块整数角坐标** + 方向）。
  - `getBlockPos()` 是命中方块的角(整数)，`+normal`(单位方向)后起点随射线方向在方块角周围偏移 → 下一次 trace 穿过的方块量随方向变化 → occlusion 方向性。
  - 只有 `calculateOcclusion` 用 ReusableRaycastIterator 循环（reverb 射线是单次 trace + 手动反射，用 `lastHitPos`）→ **occlusion 关就稳定**（reverbDC 恒定）。
- **排查教训**（花费极大，务必吸取）：
  1. 误判链：offset 方向 → 水衰减参数 → occlusion 单射线 → Fluid.NONE，越改越乱。
  2. 决定性证据"occlusion 关时稳定"很早出现（C 验证），却没立即锁定"方向性 100% 在 occlusion"。
  3. occlusion 方向性伪装成真实遮挡（坑口透/实心闷），难与"算法敏感"区分；且 9 条平均、多方向都修不掉（bug 在每次 next() 的起点推进）。
  4. **只有对比原版源码才能定位**：diff 两边 lib/math/ReusableRaycastIterator，一眼看出 getBlockPos vs getLocation。
- **修复**：`next()` 改回 `getLocation().add(normal)`。水下/地下方向性问题全部随之消失，固定 occl 等绕弯子改动全部移除。
- **辅助（保留）**：`soundPos` 对称（solid 内声源保持原始位置，reverb 从对称中心算）；occlusion 9 条射线扇形平均（OCCLUSION_FAN=0.15, 3×3 网格）。对边缘场景有益，但**根因是 ReusableRaycastIterator**。

### 基岩雾超平坦修复（2.24 续）
- **现象**：超平坦地面（y=0~3）白天起基岩雾。
- **根因（对照 1.12.2 原版）**：原版 `BedrockFogRangeCalculator` 用 `d0 = skyLight/16 + factor`（**亮度+深度**），`d0>=1` 不触发——亮的地表（白天 skylight 满）不触发，只有暗的深处触发。移植时**丢了 skyLight 项**，只剩 y 深度 → 超平坦地表（y 在梯度内）也触发。
- **修复**：恢复 `getRawBrightness(pos,0)/16` 亮度项（`level.getRawBrightness(player.blockPosition(),0)`）。超平坦白天 skylight 满 → d0>1 无雾；主世界暗矿洞 → d0<1 有雾。**注意**：超平坦 `getMinY()` 通常 -64，`baseY=-32`，地表 y=0~3 高于它 → 本就不触发，亮度项主要防 minY=0 配置/深挖场景。
- 教训：**移植功能丢子项（如 skylight）是隐蔽 bug，务必 diff 原版全文**。Fabric 版（当前 DS 原版）没有基岩雾，1.12.2 才有。

## 2.25 调试日志清理 + 脚印方向 + 极光睡醒残留（2026-08-12）

### 调试日志清理（遮挡功能已确认，全部移除）
- **SoundFXUtils**：删 `WATER sound=` 详细版、`OCCLTRACE` 逐段、`SNDNBH` 51×51×51 邻域 ASCII 图、`dumpSoundNeighborhood` 方法、`diagnosticCounter` 计数、`LOGGER`/`IModLog` 字段与 import（`Level`/`Blocks`/`BLOCK_LIBRARY` 在别处仍用，不留孤儿 import）。
- **BlockInfo**：删 `WOOL occl tags` 材质诊断。
- **run/config/dsurround/dsurround.json**：`enableDebugLogging` true→false。

### 脚印方向偏移（用户实测，方向性固定偏差）
- **现象**：直线行走时**某些世界方向**脚印有固定朝向偏差，另一些方向正常（非转身/绕圈引起）。
- **根因（移植 bug）**：26.1 粒子 quad 建在 **XY 平面**（`QuadParticleRenderState.renderRotatedQuad`，corner=(nx,ny,0)，纹理顶部 v0=+Y）。`rotationY(yaw)`+`rotationX(-90°)` 折叠平放后，**纹理顶部（脚趾/脚掌端）映射到世界 (-sinθ, -cosθ)**——沿南北轴的镜像：东西走向刚好正确、南北走向印子反 180°、斜向偏 90°。原版 1.12.2 `MoteFootprint` 用 `-rotation + 180` 补偿，移植改成直接 `rotationY(yaw)` 丢了这步。
- **修复**：`FootprintParticle.extract` 改 `rotationY((float)(Math.PI - this.yaw))`（即原版的 `-rotation+180`），脚趾端所有方向都指向行进前向 (-sinθ, +cosθ)。**生成逻辑不动**（`FootprintHandler` 沿用原面向 `getYRot()` 方案）。
- 定位方法：读 26.1 客户端反编译源码 `QuadParticleRenderState`+`particle.vsh` 推导 quad 基础朝向；ASCII 渲染脚印贴图确认脚掌/脚跟端。

### 极光睡醒残留
- **现象**：睡一觉后早上极光还"慢慢消失"（太阳已升起）。
- **根因**：非移植 bug，原版行为——离开可见窗口后 `AuroraLifeTracker` 以 `ageDelta=1/tick` 淡出，峰龄 512 tick ≈ **25.6 秒**。自然日出时天空渐亮，这个渐变观感自然；但**睡觉时间跳变**到清晨，太阳瞬间升起，极光却在白天继续淡出 25 秒。
- **修复**：`AuroraEffectHandler.process` 检测**时间跳变**——`DayCycle.getCelestialAngleDegrees` 每 tick 更新，自然推进仅 ~0.015°/tick，睡眠/`/time` 命令会瞬间前进 >30°（`TIME_SKIP_THRESHOLD=30`）。检测到即 `current=null` 立刻消失（醒来黑幕淡入遮住弹出）。自然日出仍走慢淡出（观感正常）。正午 359°→0° 换向是负增量，不会误触发。

### 遮挡系统增强（2.25 续，用户实测确认）
针对用户反馈的两个"简陋"现象（声源近处一个方块遮挡就闷、挖进地里头上有开口仍闷）做的两处增强，均在 `SoundFXUtils`：

- **吸收系数恢复 Fabric 原版 3.0**（曾临时抬到 4.0，用户要求还原）。
- **反射系统（复用 reverb 多弹跳）**：reverb 循环里 `sharedAirspace` 检测（反射点→玩家射线 MISS=路径可达）命中时，累加 `reflectedDirectCount += blockReflectivity`（材质反射率加权）。循环后 `directCutoff = max(reflectedDirectCount / REFLECTED_DIRECT_DIVISOR(40), directCutoff)`——墙/坑挡直射但反射路径能到玩家时，直接声道被部分补回。Fabric 原版已有弱版（`sqrt(avgSharedAirspace)*0.2`），这是强化版并带材质加权。
- **致密射线（锥形扇）**：`calculateOcclusion` 从单射线改为 **5 条锥形束平均**（中心 + 围绕主射线方向的两个正交轴 ±0.6 偏移，轴由 `dir.cross(seed)` 构造，seed 按主射线是否垂直选 Y/X）。偏移围绕**声源→玩家主射线**展开而非世界坐标水平/垂直，所以玩家在声源任意方位（侧/上/下）都有全向采样；玩家在声源正下方时主射线竖直、扇在水平面展开，不退化单射线。**防切地面**：偏移量小（0.6）+ 5 条平均 + EMA 平滑（不再靠"不放向下射线"）。
- **性能**：遮挡 5→20 raycast/源/更新，对比 reverb 128 次只多 ~11%；每源每 10 tick（`UPDATE_FEQUENCY_TICKS`）更新，`MAX_SOURCES_PER_PASS` 限源数。

用户实测：效果很好。

## 2.26 火把燃烧声 + 脚步修复 + 配置翻译（2026-08-12）

### 火把燃烧声（新功能）
- **固定火把**：blocks.json 给 `torch`/`wall_torch`/`soul_torch`/`soul_wall_torch` 配 `soundChance: 0.01` + acoustics（`dsurround:block.torch_burn`）。走 `RandomBlockEffectSystem`（每 tick 从玩家周围随机采样 500 方块，采到火把才按概率触发一声短噼啪，MC 原生距离衰减，非 loop）。
- **素材**：Freesound **CC0**（LilMati 714566 "Fire crackling cozy campfire fireplace SFX"），截 3 个 1.8s 噼啪变体 `crackle1-3.ogg` → `sounds/ambient/torch_burn/`；sounds.json 注册 `block.torch_burn`（volume 0.55 + `ds_credits` 署名）。
- **手持火把**：新 `HeldTorchBurnHandler`（processing 包，`ClientState.TICK_END` 检测主/副手 torch/soul_torch，`TRIGGER_CHANCE 0.012`≈4s 一声，`factory.attachToEntity(player)` 随玩家移动）。**独立工厂** `dsurround:held_torch_burn`（soundEvent=block.torch_burn, category AMBIENT, volume 0.85）——音量与固定火把解耦（手持贴脸更响）。
- 参数调整：固定火把 0.0003→0.01（3 分钟→约 1 分钟一声）、音量 0.35→0.55；手持 0.006→0.03（太密，1.8s 素材几乎连续）→0.012。**教训**：概率是每 tick 的，0.03 累积到 60%/秒=1.7s 一声，叠加 1.8s 素材近乎连续。

### 脚步系统修复
- **水下脚步**：`FootstepGenerator` 的 `if (onGround || inWater || onLadder)` → `if (onGround || onLadder)`——水下悬空（脚离河床 1 格内但不接触）不再播脚步，只有接触地面或爬梯才响。
- **爬梯节奏**：`Variator` DEFAULT `strideLadder 0.5 → 1.0`（先试 1.2 用户定 1.0）。原版 1.12.2 也是 0.5，但**现代 MC 爬梯更快**导致 0.5 太密（每秒约 5 声）。
- blocks.json `netherbrick.breathing` 补 `dsurround:` 前缀（其余 factory 都带；`IdentityUtils.CODEC` 对无前缀默认补 dsurround 命名空间，风格统一）。

### 配置翻译补齐
- **问题**：缺翻译键的配置项在 Cloth Config 界面显示**原始键名路径**（如 `dsurround.config.soundOptions.enableBiomeSounds`）。
- en_us **+4**：`soundOptions.enableBiomeSounds/footstepVolume/biomeVolume`、`entityEffects.enableFootstepSounds`（+`.tooltip`）。
- zh_cn **+7**：上述 4 个 + `enhancedSounds` 水遮挡 3 项（`enableWaterSoundDamping`/`waterSoundDamping`/`waterSoundMuffle`）。
- **约定**（已记入 CLAUDE.md）：`Configuration.java` 新增任何配置项必须**同步加 en_us + zh_cn 翻译键**（`dsurround.config.<分组>.<字段>` + `.tooltip`，分组名首字母小写）；校验脚本对比 Configuration.java 字段与 lang 键。
- 注：biomes.json 的 61 处**无前缀 factory**（biome.wind/wolf 等）是**合法设计**（`IdentityUtils.resolveIdentifier` 对无 `:` 字符串默认补 `dsurround:`），非格式错误，勿改。

## 2.27 遮挡系统重构：衍射补偿（2026-08-12）

### 问题链（用户逐轮反馈驱动）
- **同种方块近处弱/远处强、紧贴 1×2 墙也闷、埋进 6×6×6 羊毛"特定方位才正常遮挡"、地上清晰听见矿洞里的僵尸、室内外切换突兀**。
- 逐层挖出根因，每层都对应一次真实的误判：
  1. **反射补偿（reflectedDirect）以声源为原点**：reverb 射线从声源辐射，远处/贴墙的遮挡物张角太小或反弹点"直线可见玩家"被墙自身挡住 → 补偿失效 → 远处/贴墙闷死。
  2. **无条件 diffraction floor 过强**：把埋进羊毛（本该全闷）也抬到 0.48。
  3. **enc 探针从声源中心发**：实心方块声源（**唱片机 isSolid**）6 向全命中自己身体 → enc=1 → 补偿永远关闭 → 贴墙必闷、离开墙遮挡归零又正常。
  4. **补偿只看声源周围封闭度，不看路径穿墙厚度**：矿洞里僵尸周围是开阔矿洞（enc 低），但玩家与它之间隔着几格岩石——补偿全额拉回 → 地面清晰听见洞穴声音。
  5. **补偿无时间平滑**：跨门槛时 openness/enclosure 一步跳变 → 室内外切换"咯噔"。

### 最终模型（SoundFXUtils.calculate 补偿块）
```
compensation = DIFFRACTION_BASE × openness × sealedFactor × pathFactor × distBoost
```
- **openness**（玩家 6 向世界轴短射线开放比例，`calculatePlayerOpenness`）：贴墙但周围开阔 → 衍射可达；密闭房间/玩家被围 → 保持闷。
- **enclosure**（声源 6 向封闭比例，`calculateSourceEnclosure`）：**探针先沿方向步进走出声源自身方块**再测（否则唱片机这类实心方块声源误判全封闭）；埋羊毛 6 向全命中 → 方位对称地闷。
- **sealedFactor = (1−enc)×(1−SEALED_LEAK)+SEALED_LEAK**：全封闭保留基础漏声（关门/隔墙隐约可闻），不瞬间静音。
- **pathFactor = clamp1(2 − 中心射线穿墙累积)**：**关键**——不用整条遮挡扇的平均 occ（开阔地会刮到地形累积到 5-10 误杀开放空间），改用**中心射线的穿墙厚度**（薄墙 ~0.8，地面隔矿洞 ~3+）。这同时解决"羊毛全闷"和"矿洞僵尸闷"，且不破坏"墙后阴影区可闻"。
- **distBoost = 1 + clamp1(D/24)×0.6**：声源越远直射束在墙处越窄、墙影越大，叠加引擎距离衰减 → 远处补偿需抬升保持"闷但可闻"。
- **时间平滑** `SourceContext.smoothDiffraction`（复用 WATER_SMOOTH_ALPHA 0.85，~0.8s settle）：跨门槛/门开关/遮挡移开时**渐隐渐显**而非跳变；无遮挡帧平滑回 0。
- 补偿同时抬 **direct + 4 路 reverb** 的 gainHF（reverb 同样受"反弹点直线可见"失效困扰，只抬 direct 贴墙仍闷）。
- **常量**：`PROBE_RAY_DISTANCE 12`（4 格测不到房间墙，关门误判开放）、`DIFFRACTION_BASE 0.75`、`SEALED_LEAK 0.12`、pathFactor 阈值 2、distBoost 参数（24/0.6）。

### 开关
- 配置"**遮挡处理**"（`enableOcclusionProcessing`）就是**总开关**：`skipOcclusion()` 关闭时 `calculateOcclusion` 直接返回 0、`lastOccluderPos=null`，衍射补偿块不执行（含两个探针）——遮挡和补偿一起停。MASTER/MUSIC 类别恒跳过。

### 性能
- 新增 12 条**短射线**（声源 6 + 玩家 6，每条 12 格），**只在有遮挡时跑**（`occluderPos != null`），开阔地零成本；量级约为 reverb（32×4 长射线）的 5%。每次 `[OCCLCOMP]` 诊断日志已清理。
- 文件：`SoundFXUtils.java`（补偿块 + 两个探针方法 + lastCenterOcclusion/lastOccluderPos 字段）、`SourceContext.java`（smoothDiffraction）。

## 2.28 遮挡系统二次重构：几何绕射（2026-08-13）

上一版（2.27）的补偿是 5 个手调因子乘积（`DIFFRACTION_BASE × openness × sealedFactor × pathFactor × distBoost`），每修一个玩家反馈就加一个因子，越补越乱。这一版收敛成**一个物理量**：最短绕射路径的绕行增量 ΔL。用户最终确认"效果完美"。

### 模型
```
中心射线被挡 (lastCenterOcclusion > 0)：
    diffraction = EDGE_LOSS / (1 + DIFFRACTION_FALLOFF · ΔL)   // ΔL = 绕行增量，无自由边时 = 0
    enclGate    = 1 − enclosure³                                // 立方门控：enc 0.5 → 0.875，enc 1 → 0
    openGate    = 1 − (1 − openness)³                           // 立方门控：open 0.67 → 0.96，open 0 → 0
    compensation = clamp1( diffraction × openGate × enclGate )
    direct = max(direct, smoothedComp)                          // 平滑后
    reverb_i = max(reverb_i, smoothedComp × 0.5)                // reverb 少抬一半
```
- **ΔL**（`calculateDiffraction`）：在遮挡物首命中点、垂直于声源→玩家方向的平面内，摆一圈 waypoint 环，逐一判 `声源→点`、`点→玩家` 两段是否畅通；取 ΔL 最短的有效路径。**半径自适应**（1.5→3→5→8），薄墙小半径即通、宽墙要大半径、矿洞顶/埋死则任何半径都找不到自由边 → diffraction=0 → 基础遮挡保持闷。
- **立方门控**（而非线性 `(1−encl)` / 原始 `openness`）：线性会把"靠墙/贴墙/踩地"这类**部分遮挡**（enc≈0.5、open≈0.67）也当封闭狠砍；立方让中间值几乎不惩罚，只有真正四面围死（enc→1 / open→0）才关闭。绕射探针本身也能通过 `trace(waypoint, player)` 命中等方式捕捉"围死"，所以门控只是兜底。
- **删除**：`distBoost`、`pathFactor`、`DIFFRACTION_BASE`、`SEALED_LEAK`，以及两套反射 direct 恢复（`sqrt(averageSharedAirspace)×0.2` 与 `reflectedDirectCount/40`）。direct 恢复**统一由衍射**负责；reverb 仍由反射射线（`sharedAirspace` → `sendCutoff`）负责。职责分明：**direct=衍射，reverb=反射**。
- **闸门修正**：补偿闸门从 `lastOccluderPos != null`（扇内任意一条刮到就触发）改为 `lastCenterOcclusion > 0`（仅中心线被挡才触发）；`lastOccluderPos` 只记中心射线首命中（旁射线传 null 不污染）。`skipOcclusion` 分支同时复位 `lastCenterOcclusion=0`。
- **平滑常数拆分**：`SourceContext` 新增 `DIFFRACTION_SMOOTH_ALPHA`（0.85），不再复用 `WATER_SMOOTH_ALPHA`。

### 四个关键 bug（调试链，每个都是"修不好"的元凶）
1. **★★★ 空气被算成遮挡**：`getBlockInfoWeak(air)` 返回方块库 `DEFAULT`（`soundOcclusion=Occlusion.DEFAULT=0.5`），于是 `traceOcclusion` 把"声源到墙之间的空气"按 `0.5×距离` 累积——声源离墙 25 格时，光空气就贡献 12.76，`centerOcc` 虚高到 13.26。**2.27 的 `pathFactor=clamp1(2−centerOcc)` 因此恒为 0，补偿从来没生效过**——这就是"贴墙闷/远墙闷"反复修不好的真正根因。修复：`getOcclusion` 对 `state.isAir()` 直接返回 0。
2. **firstOccluder 中点算错**：`MathStuff.addScaled(lastHit, result.getLocation(), 0.5)` 语义是 `base+addened×scale` = `lastHit+0.5×hit`，不是中点；正确中点 = `lastHit + 0.5×(hit−lastHit)`。导致 `lastOccluderPos` 错位（z 落在整数边界），绕射环中心跑偏。2.27 只把 `lastOccluderPos` 当布尔用所以没暴露。
3. **实心声源（唱片机）自遮挡**：唱片机 `isSolid` 且 `canOcclude()` → occlusion=0.5，center ray 从唱片机中心出发第一段就命中自己。修复：`calculateOcclusion` 里 rayOrigin 先 `stepOutOfSolid` 走出声源自身方块；`calculateDiffraction` 的声源端 trace 同样步进。
4. **线性门控过度惩罚**：`openness × (1−encl)` 把"靠墙/贴墙"当封闭砍半。改立方（见模型）。

### 常量（全在 SoundFXUtils）
`EDGE_LOSS=0.9`、`DIFFRACTION_FALLOFF=0.12`、`DIFFRACTION_REVERB_SCALE=0.5`、`DETOUR_RADII={1.5,3,5,8}`、`DETOUR_SAMPLES=8`。

### 实测（用户确认"效果完美"）
- 贴墙/薄墙：`centerOcc=0.5`（真实墙厚）、`diff≈0.82`、`comp≈0.69` → 闷但可闻，不再堵耳。
- 远墙与近墙一致（`distBoost` 已删，ΔL 几何自含距离）。
- 埋羊毛：enc→1 → enclGate→0 → 全闷。
- 矿洞/厚地形：找不到自由边 → diffraction=0 → 基础遮挡保持闷。

### 性能
绕射探针仅在中心线被挡时跑，最多 4 半径 × 8 点 × 2 射线 = 64 次短 raycast（薄墙首半径即停，~16 次），与 2.27 的 12 探针 + 5 扇射线同量级。诊断日志（`[DIFFDBG]`/`[TRACEDBG]`）已全部清理。

## 2.29 极光 shader clean-room 重写（2026-08-16）

2.17.1 的结论被推翻：用 26.1 原生管线机制重新实现了 shader 版极光并通过七轮用户实测调优。**与 2.17.1 不同，这次没有回退**。

- **文件**：`assets/dsurround/shaders/core/aurora.vsh/.fsh`（自写值噪声 FBM，MIT 可分发，无 Mattenii 代码）、`AuroraRenderPipelines`（ASPECT 4.5/9 双管线，modBus 注册）、`AuroraShader`（QUADS 连续幕帘 + 前/背双层 + X/Z 独立缩放）、`AuroraFactory` 异常回退 Classic、`AuroraEffectHandler` 双渲染器分发。可见窗口延至晚 10 点—凌晨 3 点（225°）。
- **七轮调优的关键参数**（完整记录见 `docs/aurora-shader-rewrite-notes.md` 第 8 节）：亮度 1.0；SCALE_X 0.50 / SCALE_Z 0.26 / SCALE_Y 120；射线 smoothstep(0.24,0.76) + rayMask 0.52+0.48；顶部渐隐必须保证 top+fade < 几何边（否则硬切）；sweep x 频率 1.6；底部 rim 宽过渡 × curtain 调制（频率对高度恒定，否则"木星纹"）；高度呼吸 sin(t*0.126)≈25s；每带色相 128±14 经顶点色 ×2 居中。
- **坑**：`@OnlyIn` 注解在 26.1 会被 OnlyInWarningsHandler 打成启动 ERROR——不要用；顶点 y=0–1 时 SCALE_Y 即幕帘总高度。

## 2.30 全项目代码审查修复（2026-08-16）

本轮审查的完整记录（发现清单、改动明细、遗留项、素材研究、待拍板项）集中收录在
**「八、代码审查记录」**章节，对应提交 `52ddaf8`。

---

## 2.31 火光明暗闪烁尝试（2026-08-22，已回退放弃）

为火把/火/营火做"噼啪声响起时光晕明暗跳动"的伪光效（不加真实光照，加色 quad 叠层）。多轮迭代（billboard 单卡 → 三平面十字 → 双层卡片 → 宽扁椭圆+透镜层 → 光点云）观感均不达标，最终**整体回退**（代码未提交，直接清理）。若未来重试，先读这里的结论：

**技术结论（已验证，有复用价值）**：
- 触发/发现机制可行：`AudioUtilities.onSoundPlay` 钩子抓声源 + 周期扫描（5s/16 格）发现光源 + 按方块光发射判定存活，这些都没问题
- **buffered RenderType 在后期阶段必须显式 flush**：`RenderType.create` 的批留在共享 bufferSource 里会拖到帧末 endBatch 才画，那时 ModelView 已不是视图旋转 → 光晕钉在准心下随视角滑动/旋转/时隐时现。**提交完顶点立刻 `endBatch(type)`** 才正确。极光没踩坑纯因后续管线切 buffer 时把它提前冲刷了
- AfterSky/AfterTranslucentParticles 的 event poseStack 均为 identity，视图旋转由 flush 时 ModelView 提供；诊断日志打印三个矩阵（viewSnap/stagePose/flushMv）是定位手段
- 近相机逐顶点淡出（1.2–3 格）是防大色块扫屏的必要手段

**观感结论（为什么放弃）**：
- billboard 大卡 = "面对玩家的贴图"感无法消除；卡片插进地面被深度裁出直线边
- 三平面十字 = 平面对着相机扫过的光带
- 光点云（12 个小光点）= "萤火虫"，离散感强
- 宽扁椭圆贴地 + 火焰高度透镜层：最接近可用，但仍显"圆锥向上"的形态感
- 根因：加色叠层只能"增亮"，无法产生真实光照的**面接收明暗**（墙面/地面被照亮的方向性），任何卡片/点排布都在模拟体积而非真实漫射。这类效果的正解是光影包/光照引擎层面，不是叠层

---

## 八、代码审查记录

> 代码/工程审查的统一归档章节。时间线日志（2.x）只留一句话指引，细节都在这里，
> 避免审查发现散落在各处难以追溯。

### 8.1 历次审查索引

| 日期 | 审查 | 结果去处 |
|---|---|---|
| 2026-08-02 | 穿草/脚印轮 simplify 四角度审查（4 并行 agent） | 2.15.4（历史条目，保留原地） |
| 2026-08-10 | 发布前工程清理（AI 审查） | 2.23 工程清理小节（历史条目，保留原地） |
| 2026-08-16 | 全项目审查（本轮，3 并行只读 agent + 1 素材研究 agent + 人工复核高严重度发现） | **本章 8.2 起**，提交 `52ddaf8` |

### 8.2 审查方法与范围（2026-08-16）

- **声音系统**：runtime/audio 全部、mixins/audio、FootstepGenerator、Variator、SoundLibrary
- **处理器/渲染**：processing 包全部 Handler、粒子、雾计算器、GUI overlay、Client/DI 初始化
- **资源与配置一致性**：sounds.json 引用完整性、Configuration↔lang 键对齐、配置项死字段、mods.toml/gradle 一致性、纹理引用
- **素材溯源**：无引用 ogg/纹理 对照 1.12.2 源码（`D:\claude code\dsurround-1.12.2-src`）逐一验证用途，含 MD5 对比
- 高严重度发现（Effects 共享滤波器、credits 越界）经人工读源码复核后才动手修

### 8.3 已修复（提交 52ddaf8）

#### A. 声音系统缺陷（行为修正类，修后需实测多声源场景）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| A1 | `runtime/audio/effects/Effects.java` | **共享滤波器串扰（本轮最重要）**：`filter0-3`/`direct` 是全局唯一 OpenAL filter 对象，OpenAL filter 是共享参数块，多声源并发时只有"最后写入者"的遮挡/混响参数对所有人生效；`direct` 共享意味着一个被遮挡声源会把**所有**声音一起闷掉。单声源测试（埋唱片机）发现不了 | 滤波器改**每源持有**（`SourceContext.zoneFilters[4]/directFilter`，声音引擎线程 `ensureFilters()` 懒初始化、`stop()` 释放）；zone→send 改**固定绑定**（send i 恒载 zone i），删除按源排序重绑（同时消掉了每 tick 的 Integer[] 装箱排序）。注意：2-send 设备上长混响 zone2/3 不再被动态提升到 send0/1——mixin 请求 4 发送后实际设备都有 4，影响面极小 |
| A2 | `effects/Slot.java` 及子类 | `deinitialize()` 只置空句柄不删对象，设备重建（资源重载/切输出设备）泄漏 4 aux slot + 4 effect + 5 filter | `deinitialize()` 现在调用 alDeleteAuxiliaryEffectSlots/alDeleteEffects/alDeleteFilters 真正删除 |
| A3 | `runtime/audio/SoundFXUtils.java` | `REVERB_RAY_BOUNCES = CONFIG.reverbBounces` 可被配置改小，`bounceRatio[0..3]` 硬编码索引越界 → 异常被 updateImpl 吞掉 → **整个混响静默失效** | 钳制 `max(4, config)` 并注释原因 |
| A4 | `SourceContext.updateImpl` | 吞掉**所有** Throwable 且零日志——A3 这类 bug 被它掩盖数月 | 改 debug 级留痕（`IModLog` 新增 `debug(Throwable,...)` 重载，默认关不刷屏） |
| A5 | `mixins/audio/MixinMusicManager` | `credits.get(0)` 无空判——sounds.json 配了 title 没配 credits 时 `/dsmm whatsplaying` 越界崩命令 | 空列表回退 |
| A6 | `sound/SoundInstanceHandler.inRange` | `getSound()==null` 时异步 resolve 后**立即**解引用 `getSound().getAttenuationDistance()` → NPE | 放行并等下轮复查 |
| A7 | `SoundFXProcessor` | 自建线程池非守护线程（JVM 退出可被挂起）；`sources/worldContext/diagnosticString` 跨线程无 volatile | 守护线程 + 命名工厂；三字段 volatile；错误消息里过时的 "ForkJoinPool" 字样修正 |

#### B. 换世界/重连状态残留

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| B1 | `FootstepGenerator.onDisconnect` | 单例 handler 未复位 lastPos/isFlying/fallDistance/didJump/distanceWalked/dmwBase/yPosition——换世界第一 tick 用旧世界坐标算出巨大位移 → 幻影脚步/假落地声 | 补齐全量复位 |
| B2 | `WeatherStormHandler` | `dustIntensity` 未在 connect/disconnect 归零，沙漠/下界退出后黄雾残留 ~2 秒 | 覆写两个钩子归零 |
| B3 | `CompassOverlay.tick` | `spinRandomly` 不随 `showCompass` 一起复位，主副手切换时副手罗盘被陈旧状态错误乱转 | 每 tick 复位 |

#### C. 行为调整（用户批准的需确认项）

| # | 位置 | 改动 |
|---|---|---|
| C1 | `WeatherStormHandler` 沙尘暴分支 | 加 `scanners.isInside()` 矿洞判定：沙漠下洞穴不再灌沙粒/黄雾（下界尘雨不受影响——下界本就是洞穴维度） |

#### D. 死代码/混乱清理（零功能影响）

| # | 内容 |
|---|---|
| D1 | `Client.java` 的 HolisticFogRangeCalculator 死 DI 单例（FogHandler 自建实例才是活的，两套计算器树并存且注释误导） |
| D2 | 死 `@SubscribeEvent` 注解×6：AuroraEffectHandler、CritWordHandler×2、CraftingSoundEffectHandler、PotionParticleHandler、FogHandler（实际都走 addListener，注解纯误导） |
| D3 | 死字段/方法：FootstepGenerator.wasOnGround、FootprintHandler.logger、DiagnosticsOverlay.serverBranding、WeatherStormHandler.getDustIntensity、HeldTorchBurnHandler.config/logger、AuroraShader.renderType()+@Nullable、AuroraBase 四个未用 helper（getMiddleColor/getBandCount/getBandOffset/clamp）、NeoForgeMod.onInitializeClient 空监听、WaterRippleStyle.resource/getTexture |
| D4 | `UPDATE_FEQUENCY_TICKS` 拼写 → `UPDATE_FREQUENCY_TICKS` |
| D5 | `SourceContext` 三个 smooth* 三份复制收敛为 `ease()` 辅助；`smoothOcclusion` 误用 WATER_SMOOTH_ALPHA → 独立 OCCLUSION_SMOOTH_ALPHA（值不变） |
| D6 | `SoundFXUtils.calculate()` 277 行拆为 traceReverb / applyDiffraction / finalizeSendGains / uploadSettings 四方法（表达式逐行保留，行为等价） |

#### E. 性能优化（观感不变）

| # | 位置 | 改动 |
|---|---|---|
| E1 | `CritWordHandler.renderGui` | 先做投影深度剔除（镜头背后/超距直接跳过）再做遮挡 raycast——此前每个词条每帧白做一次完整体素射线 + 分配 |
| E2 | `FootprintParticle` / `WaterRippleParticle` | 恒定旋转四元数缓存（字段 / static final），消除每帧每粒子 1-2 个 Quaternionf 分配 |
| E3 | fog 六个计算器 | 复用成员 FogData 实例（render() 返回前必然覆写两个字段，复用安全） |
| E4 | `HazeFogRangeCalculator` | 每帧走 synchronized `ContainerManager.resolve` 改构造注入（经 HolisticFogRangeCalculator 传入） |

### 8.4 已知未修（遗留清单，按建议优先级）

| # | 位置 | 问题 | 备注 |
|---|---|---|---|
| L1 | `SoundLibrary.postProcess` | F3+T 资源重载时 `startupSounds` 无界翻倍累积、`culledSounds` 残留（用户取消屏蔽后仍被屏蔽） | 修法：postProcess 开头清三个集合 |
| L2 | `zh_cn.json` | 缺 8 个运行时键（dsmm pause/unpause/failure/success、musicmanager nothing/playing）——中文环境命令显示原始键名 | en_us 已有，照抄翻译即可 |
| L3 | `neoforge.mods.toml` | architectury 版本范围硬编码 `[13.0.8,)`（旧时代版本号），与 gradle 的 20.0.12 脱节 | 改用 `${architectury_api_version}` 并注册 replaceProperties |
| L4 | 版本策略 | `minecraft_version_range=[26.1.2]` 精确锁定，26.1.3 补丁版直接拒载 | 是否放宽待定 |
| L5 | `packs/Seasons sound_factories.json` | 坏 JSON（3 个冬季音乐条目缺 `},`），整个文件解析失败 | packs 不入库；单独分发前必须修 |
| L6 | `HeldTorchBurnHandler` | 无配置开关（手持火把噼啪声关不掉） | 需新增配置项+翻译 |
| L7 | `config/Variator` | 16 字段仅 5 个有消费者（四足脚步/wander/脚印缩放等未移植） | 建议类注释标明哪些当前无效果 |
| L8 | `BiomeScanner.surveyedDimension` | 初始 null，靠求值顺序巧合避免 NPE | 建议哨兵值初始化 |
| L9 | `BiomeFogRangeCalculator` | `withinManhattan(6,6,6)` 实际遍历整个 13³ 盒 ≈2197 采样/跨格 | 原 1.12.2 为稀疏采样；受量化缓存缓冲，实测可控 |
| L10 | `SoundFXProcessor.onSoundPlay` | `sources[id-1]` 无上界检查（驱动分配超 MAX_SOUNDS 时 AIOOBE 被吞） | 防御性补丁 |
| L11 | `AudioUtilities` | 初始化 catch 只打 getMessage()（常为 null），丢堆栈 | 排查信息不足 |
| L12 | `ThunderHandler` | 背景雷声用 info 级日志 | 降 debug |
| L13 | `AreaBlockEffects.blockUpdateCount` | 每 tick 清零导致诊断面板大多显示 0 | 显示时序小瑕疵 |

### 8.5 待用户拍板项（含效果影响说明）

**P1 `SoundFXUtils.offsetPositionIfSolid` 用 `!=AIR` 判断**（水/花草都会把声源朝玩家偏移 0.876 格）
- 现状来源：2.24 修成 `isSolid()&&fluidEmpty`，后按"Fabric 原版语义"回退
- 实际影响：**极小**——偏移不到一格，只影响遮挡/混响射线起点，音色差异人耳难辨；水的部分已用 rawSourcePos 独立采样绕开
- 建议：维持现状（保留原版行为）

**P2 `MixinSoundEngine` 被 block/cull 的声音仍播放 remap 替换声**
- 实际影响：在个体声音配置里**屏蔽**某原版声音、且该声音有替换映射（DS 材质脚步/远处雷声）时，替换声**照常播放**——"屏蔽原版劣质声、保留 DS 增强声"
- 若期望"屏蔽=完全无声"则脚步类关不干净；修复只需在 setReturnValue 后补 return
- 待定语义选择

**P3 无引用素材删除**（详见 8.6 研究结论）：45 个纯冗余可删，6 个未移植功能素材建议保留

### 8.6 无引用素材研究结论（对照 1.12.2 源码 + MD5 验证，2026-08-16）

**可安全删除（纯冗余副本或本就无引用，共 45 个）**：

| 组 | 文件 | 结论 |
|---|---|---|
| A | `sounds/ambient/items/` 27 个（blunt/bow/sword/tool/utility/potion/swoosh 系列） | 1.12.2 物品音效原始路径；26.1 的 `sounds.json` 已全部改用 `sounds/items/` 下**字节级相同副本**（MD5 全量对比一致），旧路径零引用 |
| B | `sounds/footsteps/wood/log_walk1-11` | **1.12.2 里不存在**（原木一直复用 wood_walk，见 mcp.json log acoustic）；疑似移植时误加 |
| C | `sounds/ambient/miscblocks/floorsqueak1-3` | 26.1 用 `footsteps/floor_squeak/` 副本（MD5 相同）；同目录 breathing/hiss **仍在用勿删** |
| D | `sounds/items/pageflip1-3` | 1.12.2 就在 `ambient/book/`（26.1 同路径在用）；`items/` 副本无引用 |
| E | `ambient/droplets/drop4`、`footsteps/armor/heavy_foot4`、`textures/particles/none.png` | 1.12.2 本来就是零引用孤儿（waterdrips 事件重复列 drop3 两次、armor.heavy_foot 只列 1-3） |

**建议保留（未移植功能的原始素材，共 6 个）**：

| 文件 | 对应功能 |
|---|---|
| `ambient/insects/gnatt1`、`grasshopper1` | 1.12.2 **insectbuzz 昆虫嗡嗡群系音效**（普通陆地群系、非结冰不下雨时随机播放）——26.1 未移植 |
| `textures/particles/rainsplash.png` | 雨滴砸水面**水花粒子**（MoteWaterSpray/MoteRainSplash）——26.1 只做了涟漪没做溅射 |
| `textures/particles/ripple.png`、`ripple1.png`、`ripple2.png` | 原版 3 种涟漪风格（ORIGINAL/CIRCLE/SQUARE）——26.1 只移植了像素圆一种 |

（另：`textures/particles/` 下的 footprint.png、pixel_ripples.png 也是 `textures/particle/` 的未用副本，删冗余时可一并处理。）

### 8.7 验证

`./gradlew build` 通过；runClient 启动、进世界运行无 DS 错误/警告（仅既有的版本检查 SSL 噪音）；
多声源音频场景、换世界状态复位、沙尘暴矿洞判定待用户日常游玩确认。
