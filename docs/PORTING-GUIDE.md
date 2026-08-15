# Dynamic Surroundings 高版本移植指导手册

> **目的**：把 Dynamic Surroundings（DS）从一个 Minecraft 版本移植到另一个高版本时，按功能模块系统地还原每个效果，知道"做什么、怎么做、会踩什么坑"。
> **来源**：本文档从 DS Fabric→NeoForge **26.1.2** 迁移实战中提炼（MIGRATION_STATUS.md 2.10~2.16 节 + docs/26.1-mod-dev-guide.md 的 API 速查）。所有坑都实测踩过。
> **适用**：对 MC 版本演进不熟悉、需要把 DS 功能搬到新版本的开发者。

---

## 〇、移植策略总论

### 先回答：整体改接口，还是一项一项慢慢实现？

**推荐：按"模块依赖深度"分四批逐项移植，不要一次性整体改。**

原因：
1. DS 是一个**松耦合的模块集合**（脚步/雾/粒子/声音各自独立），各模块只依赖"一个全局 hook 点 + 数据文件"。整体改接口=同时改 40+ 类，一处错全崩，难定位。
2. 模块之间**没有强依赖**（脚步不依赖雾，雾不依赖粒子），可以一个模块验证通过再下一个。
3. 但有一个**前置决策**：移植前先花一天把新版 MC 的 API 变化摸清（下文"零、新版本 API 摸底"），否则每个模块都会在同样的 API 上反复踩坑。

### 移植批次划分

| 批次 | 模块 | 依赖 | 为什么这个顺序 |
|---|---|---|---|
| **第 0 批：地基** | DI 容器、数据加载（json）、事件总线接入、mixin 配置 | 无 | 一切的前提 |
| **第 1 批：声音系统** | 脚步声、环境声、声音重映射、增强音效 | 地基 | 最多逻辑、最核心，先打通 |
| **第 2 批：视觉** | 粒子（脚印/萤火虫/水波/落灰）、雾 | 地基 | 粒子/雾是新版重构最狠的地方，独立验证 |
| **第 3 批：杂项** | 玩家状态声、暴击词、村庄、季节、GUI | 地基+声音 | 依赖前面成果 |

### 关键原则

1. **javap 是唯一权威**：改任何 API 前，先反编译新版本 MC jar 确认签名。**绝不凭记忆或正则猜测替换**（本项目的 `fix_all.py` 曾因猜测把 `ResourceKey.location()` 错改成 `getIdentifier()`，200+ 编译错误）。
2. **runClient 能跑 ≠ 发布能跑**：开发环境（Gradle run）会把依赖直接上 classpath，而发布 jar 需自包含。打包类依赖（如 Nashorn）必须走新版本支持的机制（见"声音系统-脚本引擎"）。
3. **一个功能一个 commit**：每完成一个模块就构建+实测+提交，方便回滚。
4. **日志会刷屏**：雨滴粒子/每帧 fog 的 debug 日志能让日志文件到 GB 级卡死游戏，调试日志必须限频或及时移除。

---

## 零、新版本 API 摸底（移植前必做）

移植前，先把新 MC 版本里这些**全局重构**摸清，否则每个模块都会重复踩：

| 重构方向 | 旧版本 | 新版本（以 26.1 为例） |
|---|---|---|
| 渲染管线 | `GuiGraphics` 类 + Blaze3D 立即模式 | `GuiGraphicsExtractor`，`extractRenderState` 模型，**立即模式删除** |
| 粒子 | `TextureSheetParticle` + `ParticleRenderType` | `SingleQuadParticle` + `Layer`（record） |
| 雾 | `FogRenderer` + 自定义事件 | `ViewportEvent.RenderFog` + `FogData` record |
| 时间 | `Level.getTimeOfDay` | `getOverworldClockTime()`（跨天累计，需 `%24000`） |
| 方块注册 | `getBlockHolder()` | `typeHolder()` |
| tag 同步 | `MixinTagCollector` | 原生 `TagsUpdatedEvent.ClientPacketReceived` |
| 声音播放 | `SoundEngine.play()` 返回 void | 返回 `PlayResult` |

**摸底方法**：解包新 MC 的 `mergeWithSources_*_output.jar` 拿源码，grep 你要改的类的完整签名。25~26.1 的 API 变化速查表见 `docs/26.1-mod-dev-guide.md` 第 6 节。

---

## 一、地基：DI 容器 + 数据加载 + 事件

### 效果
DS 用自研 DI 容器（`ContainerManager`）管理所有单例（声音库、tag 库、各 handler），数据文件（json）在 reload 时加载进库。

### 实现逻辑
- `ContainerManager` + `@registerSingleton` 注册所有依赖（如 `ISoundLibrary`→`SoundLibrary`）。
- `ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, listener, Identifier...)` 注册 reload 监听。
- 数据 json（`sound_factories.json`/`sound_mappings.json`/`dsconfigs/*`）经 codec 反序列化进库。

### ⚠️ 坑
1. **ReloadListener 必须注册**：`Client.java` 里 `ReloadListenerRegistry.register(...)` 被注释掉 → `AssetLibraryEvent.RELOAD`（RESOURCES 范围）**永不触发** → 音效工厂/声音映射全部不加载 → 脚步/工具栏声音全失效。**这是最容易漏的静默 bug**。
2. **DI 只注册不 tick**：handler 需同时 `registerSingleton` **和**每 tick 列表（`Handlers.init()` 的 `this.register()`）。只注册前者，`process()` 永不调用。

### 移植要点
1. 确认新版本的 `ReloadListenerRegistry` / reload 事件 API（版本间可能改名）。
2. 移植 DI 容器时逐类核对构造参数能否解析（新版本可能移除某些依赖类）。

---

## 二、脚步声系统（核心）

### 2.1 DS 脚步生成器（FootstepGenerator）

**效果**：玩家走/跑/跳/落地/爬梯，按脚下的方块材质播放对应的 DS 脚步声（替代原版单调脚步）。

**实现逻辑**：
- 每 tick `process(player)` 用 `player.position()` 累计水平位移，按 stride（走 0.7 / 跑 0.95）触发一步。
- 状态机：`onGround`/`onLadder`/`inWater` 切换，`didJump` 标记主动起跳，`fallDistance` 空中缓存判落地。
- 落地分"重落地"（起跳+落差>0.9，或未起跳>1.5）与"正常脚步"（小台阶）。
- `resolveSurfaceBlock` 取脚下材质 → `getRemappedSound(stepSound, state)` 拿材质音效工厂 → 播放。

**⚠️ 坑**：
1. **跑步判定用 `player.isSprinting()`**，不用速度阈值——速度 0.18 < 走路速度 0.216（4.317m/s÷20），阈值会把快走误判成跑步，忽重忽轻。
2. **`fallDistance` 落地瞬间重置** → 必须空中缓存，否则落地声/脚印丢失。
3. **Minecraft 单声音量钳制到 1.0**（`Mth.clamp`）→ 想更响只能**多层叠加**（同时播多个 SoundInstance）。落地声 = 主声@1.0 + 副层@0.5 + 回声，才比走路响。
4. **方块边缘**：脚下空气时 `resolveSurfaceBlock()` 横向扫描最近实体方块（同 remapSound 边缘处理），否则回退旧 `player.land`。
5. **雪层碰撞盒特殊**：26.1 雪层 `getCollisionShape` 用 `SHAPES[LAYERS-1]`（1 层雪顶=0、2 层只算 1 层），比**视觉表面**低一层。判定/取表面高度要用 `getShape`（视觉形状），否则薄雪层踩雪声丢失、脚印埋雪里。

### 2.2 方块→材质声学映射（sound_mappings.json）

**效果**：106 条规则，把原版 `block.*.step` 事件映射到 DS 材质音效（草/泥/石/沙/雪/木/金属/玻璃/石英/混凝土等），特殊方块有专属音（灵魂沙/玻璃/冰/箱子）。

**实现逻辑**：
- `sound_mappings.json` 每条：`soundEvent`（原版 step 事件）→ `rules[]`（按 `blocks` 条件匹配 → `factory` + `accent`）。
- `SoundMapping.findMatch(blockState)` 遍历规则，首个匹配生效，默认规则兜底。
- `remapSound(soundInstance)` 在 `SoundEngine.play` 时拦截：按声音位置取下方方块 → 匹配映射 → 替换成 DS 音效。

**⚠️ 坑**：
1. **获取新版 MC 声音事件 ID**：解包源码 jar 的 `net/minecraft/sounds/SoundEvents.java`，grep `register("block.<name>.step")`。javap 出不来字符串时用源码。
2. **方块 ID 会改名**：如 `minecraft:chain` → `iron_chain`/`copper_chain`。手写方块 ID 后必须核对 `Blocks.java`/`BlockIds.java`。
3. **工厂 location ≠ 声音事件名的映射必须用 `getSoundFactoryOrDefault(location)` 播放**（按 location 查工厂），而不是 `SoundFactoryBuilder.create(location)`（按事件查）。后者对 `footsteps/dirt_path`（location≠事件名）会播放 MISSING 静音。**这是草径/梯子无声的根因**。
4. **声音事件缺失检查**：写脚本遍历 `LAND_COMPOSITIONS`/`sound_factories.json` 引用的所有事件，与 `sounds.json` 对比，找出未定义事件（如 `concrete_run`/`marble_run` 音频有但事件没注册 → 落地静音 + `Unable to locate sound` 警告）。

### 2.3 穿草音效（brush step）

**效果**：玩家/生物穿过高草/低草/蕨 → `brush_through` 沙沙声；穿枯灌木/藤蔓 → `leaves_through`。

**实现逻辑**：
- `StepThroughBrushEffect`（EntityEffect）每 2 tick 检查脚下/头上方块是否 `BRUSH_STEP`/`STRAW_STEP` tag → 播放对应音效。
- `SoundLibrary.remapSound` 对 `block.grass.step` 且脚在 brush 内 → 替换为 `silence`（抑制原版穿草声，让 brush 独占）。
- entity_type tag 决定哪些生物有穿草音效（玩家+25 生物）。

**⚠️ 坑**：
1. **生物移动判定用水平 `getDeltaMovement()` + 0.01 阈值**：`xxa/zza`（输入轴）对 AI 导航生物恒为 0，用它会永不触发；但静止生物有浮点抖动，`!=0` 会误触发循环播放，所以要阈值。
2. **位置去重**（原版 messyPos）：同一格草丛只触发一次，离开再进入才重触发，否则穿草丛疯狂刷声。
3. **静音事件 ID**：`SILENCE` 常量必须指向**注册的声音事件 ID**（`dsurround:silence`），不是音频路径（`ambient/silence`）。用错会 `Unable to locate sound` + 静音抑制失效。

### 2.4 材质跳跃 / 落地音效

**效果**：起跳 = 人声"呃" + 按脚下方块材质的 `_wander` 音效（雪→snow_wander、石→stone_wander、泥→dirt_wander 等 12 材质）；落地按材质分变体 + 延迟回声。

**实现逻辑**：
- `playJump`：先播通用 `player.jump`（呃声），再 `resolveMaterial` 取脚下方块材质 → `materialVariant(material, "_wander")` 查 `_wander` 事件（未注册则跳过）。
- `playLand`：`LAND_COMPOSITIONS` 表按材质定义 primary/secondary/echo 三层（如 concrete = concrete_run + concrete@0.5 + 延迟 echo）。
- `materialVariant(material, suffix)` helper：拼 `<path>_<suffix>` + `isSoundRegistered` 守卫。

**⚠️ 坑**：
1. **`_wander`/`_run`/`_land` 事件必须注册**：音频文件可能已复制，但 sounds.json 没注册对应事件 → 静音 + 警告（concrete_run/marble_run 实测踩过）。
2. 新版本可能没有 `*_wander` 音频，用 `isSoundRegistered` 守卫优雅降级（跳过材质层，只播呃声）。

---

## 三、环境音效系统

### 3.1 生物群系声 / 环境声

**效果**：基于所在生物群系播放氛围声（森林/平原/洞穴），随移动平滑过渡。

**实现逻辑**：`BiomeSoundHandler` + `BiomeSoundEmitter`，每 tick 检测玩家所在群系，切换/淡入淡出对应的环境声循环。

### 3.2 雷声替换 + 背景雷声

**效果**：闪电雷声替换为 DS 的 `dsurround:thunder`；雷暴时每 20-40s 播放远处低沉雷声。

**实现逻辑**：
- 雷声替换：`sound_mappings` 把 `minecraft:entity.lightning_bolt.thunder` → `dsurround:thunder`。
- `ThunderHandler`：雷暴时按随机间隔播放远处雷声。

### 3.3 增强音效处理（混响/遮挡）

**效果**：后台线程计算空间混响，4 个混响区 + 遮挡滤镜 + 空气吸收，洞窟/封闭空间有明显回声。

**实现逻辑**：
- OpenAL 效果器（EFX）：`Effects.initialize` 初始化混响/低通滤波器，`applyReverb(SourceContext)` 按能量把 4 混响区映射到可用辅助发送。
- `MixinSoundLibrary` @Redirect 重写 `alcCreateContext` 强制 `ALC_MAX_AUXILIARY_SENDS=4`。

**⚠️ 坑**：
1. **OpenAL Soft 版本差异**：新版捆绑 1.25.x 默认只给 **2 个辅助发送**（旧版 1.23.1 是 4）。发送不足 → `AL_INVALID_VALUE` → 长混响区失效 → 听感"没混响"。
2. **强制 4 发送**：`alcCreateContext` 第一参是 **long 设备句柄**（不是对象）。
3. **自适应**：`getMaxAuxSends()` 存设备上限，发送数不足时降级，别硬编码。

### 3.4 脚本引擎（Nashorn）

**效果**：配置条件求值（`ConditionEvaluator`）和 `dsscript` 命令需要 JavaScript 引擎。

**⚠️ 坑（发布级）**：
1. **Nashorn 不能扁平化打包进 mod jar**：它是模块化的（运行时建 `org.openjdk.nashorn.*` 模块），扁平化会让 mod 模块和 nashorn 模块争抢包 → `ResolutionException`。
2. **Nashorn 的 ASM 依赖会冲突**：nashorn-core 带 ASM 7.3.1，与 MC 自带 ASM 9.x 冲突 → `LinkageError`。ASM 必须排除，用 MC 的（兼容）。
3. **正确打包**：用 NeoForge **Jar-in-Jar**（`jarJar` 配置组 → `META-INF/jarjar/nashorn-core-x.jar`），加载器解压为独立模块 jar。
4. **发布前必须启动器实测**：runClient dev 环境用 `implementation` 依赖，发布 jar 需自包含，两者行为不同。

---

## 四、粒子视觉系统

> 新版 MC 粒子管线完全重构。**`TextureSheetParticle` 类删除**，改用 `SingleQuadParticle`。详细 API 变化见 `docs/26.1-mod-dev-guide.md` 第 2 节。

### 4.1 脚印粒子

**效果**：玩家在泥土/草/沙/雪/粘土上行走留脚印，落地双脚、左右交替；冰上无印；雪层表面高度正确；方块边缘悬空脚印剔除。

**实现逻辑**：
- `FootprintHandler` 每 tick 累计位移，每约 0.9 格交替左右脚，`spawnPrint` 按玩家朝向偏移生成 `FootprintParticle`。
- 脚印 y 用**偏移后位置**的方块视觉形状顶面（`getShape`）。
- 材质白名单 `FOOTPRINT_BLOCKS`（冰明确排除）。

**⚠️ 坑**：
1. **用 `getShape`（视觉形状）取表面**：雪层 `getCollisionShape` 低一层（见 2.1 坑 5）。
2. **偏移位置探测**：脚印左右偏移后，须检查偏移位置（非玩家脚部）下方是否有可印方块，否则方块边缘脚印悬空。
3. **雪层表面高度**：1 层雪碰撞盒近空（`isSolid`=false），判定可印用**非空形状**而非 `isSolid`。

### 4.2 萤火虫 / 霜息 / 水波涟漪 / 气泡 / 落灰 / 沙尘

**效果**：夜晚花丛萤火虫、寒冷群系生物口鼻霜息、雨滴水面涟漪环、准心气泡、浮空泥土落灰、沙漠/下界沙尘。

**实现逻辑**：全部继承 `SingleQuadParticle`，`extract()` 里用 `extractRotatedQuad` + `Quaternionf` 旋转提交；`getGroup()` 返回 `Layer`（OPAQUE/TRANSLUCENT）；`getLightCoords` 处理辉光。

**⚠️ 坑**（全部实测，详见 dev-guide 2.2）：
1. **纹理必须显式进粒子图集**：覆盖 `assets/minecraft/atlases/particles.json` 用 `single` source 注册，否则 sprite 返回 missingno。
2. **`Mth.lerp(delta, start, end)` 参数顺序**：第一参是系数！写反会静默采到透明区。
3. **粒子管线默认背面剔除**：水平铺地 quad 用 `rotationX(-HALF_PI)`（法线朝上）；`+90°` 从上方看被剔除。
4. **`particle.fsh` 会 `discard` 透明像素**（`alpha<0.1`）：UV 采到透明区 → 整个粒子不可见，CPU 日志看不出。排查只能对照实验。
5. **`getQuadSize` 别从 0 增长**：初始 0 的粒子头几帧不可见。
6. **SpriteSet 获取**：`ParticleEngine.spriteSets` 移到 `ParticleResources`，需要 mixin `@Accessor`。

---

## 五、雾系统

**效果**：清晨/生物群系/天气（雨雾）/基岩/沙漠/海拔 六种雾计算器，组合输出环境雾距。

**实现逻辑**：
- `FogHandler` 监听原生 `ViewportEvent.RenderFog`（`FogType.ATMOSPHERIC`），各 `IFogRangeCalculator` 计算后写回 `fogData.renderDistanceStart/End`。
- `HolisticFogRangeCalculator` 组合多个计算器结果。

**⚠️ 坑**（详见 dev-guide 第 4 节）：
1. **用 `renderDistanceStart/End` 基准**：主世界 `environmentalStart` 恒为 0（overworld 未配 FOG_START_DISTANCE），用它做乘法会归零。
2. **start > end 判非法**：雾计算器必须保证 `start ≤ end`，否则 Holistic 组合器拒绝并每帧刷屏警告（`Fog calculator 'Weather' reporting invalid fog range`）。
3. **距离 cap**：雾基于 renderDistance（16 chunks=256）会推到 150-230 块，需手动 cap（雨雾 96、晨雾 128）。
4. **基岩雾参考高度**：新版世界最低层 Y=-64（旧版 Y=0），用 `level.getMinY() + 32` 动态获取，勿硬编码。

---

## 六、玩家状态声 / 暴击词 / 方块效果

### 6.1 玩家状态声

**效果**：血量<25% 心跳、饥饿<8 咕噜、跳跃"呃"、高处落地、制作声、弓箭/盾/弩、快捷栏切换、药水粒子抑制。

**实现逻辑**：
- `PlayerEffectHandler`：每 tick 检查血量/饥饿/跳跃/落地状态播声。
- `CraftingSoundEffectHandler`（`ItemCraftedEvent` 节流 30tick）、`BowUseEffect`、`ToolbarEffect`（主/副手物品变化）、`PotionParticleHandler`（`EffectParticleModificationEvent` 抑制）。

**⚠️ 坑**：落地/跳跃声容易与脚步系统**双份**（PlayerEffectHandler + FootstepGenerator 都播）→ 保留一方，移除重复。

### 6.2 暴击词 + 伤害/治疗数字

**效果**：暴击时屏幕显示随机词语（THWACK/UGGH 等），伤害红数字、治疗绿数字（真实回血量），3D 飞出 + 距离缩放 + 插值。

**⚠️ 坑**：`drawInBatch` 立即模式在新版 renderLevel **不生效**（走 SubmitNodeCollector）→ 暴击词改用 GUI 投影 + 距离缩放。

### 6.3 方块效果

**效果**：岩浆喷口/火焰喷射、水下气泡、蒸汽柱、萤火虫、瀑布水声+水花、岩浆冒烟、落灰、灵魂沙笑声、木板咯吱。

**实现逻辑**：`BlockEffectType` 枚举注册各 producer，`RandomBlockEffectSystem` 每 tick 随机采样 667 个位置（近程 16 格）触发。

**⚠️ 坑**：方块效果是**随机采样**触发（特定方块被采样率约 1.9%/tick），spawnChance 必须远高于直觉（落灰实测 0.5 太频繁 → 0.1 → 0.01 合适）。

---

## 七、系统功能

| 功能 | 实现 | 移植要点 |
|---|---|---|
| 配置系统 | Cloth Config 界面 + 自定义按键绑定 | 新版本 GUI API（extractRenderState）重写 |
| 客户端命令 | `dsbiome`/`dsdump`/`dsreload`/`dsscript`/`dsmm` | 命令注册 API 可能变 |
| 调试 HUD/指南针/时钟 | GUI 层 + overlay | 新版本 `RegisterGuiLayersEvent` |
| 村庄探测 | 村民+铃铛判定 | `Villager` 类可能移子包 |
| Serene Seasons 季节 | 季节显示 + 季节性温度/降水 | 需对应新版本的季节 mod 版本 |
| 音乐 toast | 新音乐播放显示曲名/作者 | `ToastManager`（新）替代 `ToastComponent` |
| 原版服务器兼容 | 纯客户端 | 天然兼容，无需处理 |

---

## 附：移植检查清单

移植完一个模块，对照打勾：

- [ ] `./gradlew build` 零错误零警告
- [ ] `runClient` 启动无 mixin 错误，能进世界
- [ ] 用启动器（PCL/官方）实测发布 jar（不是 runClient）
- [ ] 逐项进游戏实测该模块效果（用户确认）
- [ ] git 提交存档
- [ ] 更新 MIGRATION_STATUS 记录移植细节

**模块级检查**：
- 声音：脚步/落地/跳跃/穿草/生物/环境/混响逐项出声
- 粒子：每个粒子可见（防 discard 静默）
- 雾：雾距 start≤end、距离合适、各雾场景触发
- 时间：昼夜判断正确（防 tick0=6AM 偏移）
