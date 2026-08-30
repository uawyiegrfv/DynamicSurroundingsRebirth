# 1.21.1 移植 · 阶段二完成报告与行为一致性验证清单（2026-08-30）

> **编译 0 错误，完整 build 成功**：build/libs/DynamicSurroundingsRebirth-1.21.1-1.1.0.jar（19.2MB）
> 本文 = 阶段四回归验收的行为对照依据。每条记录：移植动作 → 行为等价论证 → 实机验证点。

## 0. 构建状态
- 编译：BUILD SUCCESSFUL（compileJava 0 错误，从基线 1473 → 0）
- jar：DynamicSurroundingsRebirth-1.21.1-1.1.0.jar（19.2MB）+ dev-shadow（19.4MB）
- gradle parallel/caching/config-cache 已恢复 true
- 依赖：NeoForge 21.1.84 / architectury 13.0.8 / cloth 15.0.140 / nashorn 15.4(jarJar) / SereneSeasons 1.21.1(compileOnly)

## 1. 移植动作总账（26.1基 → 1.21.1）

### 1.1 机械重命名（行为零影响）
| 26.1 | 1.21.1 | 处数 |
|---|---|---|
Identifier → ResourceLocation（含工厂方法形态） | 同名/parse/fromNamespaceAndPath | ~460 |
inventory.getSelectedSlot() → inventory.selected 字段 | 官方 ToolbarEffect 同款 | 3 |
getOverworldClockTime() → getDayTime() | 两版均为累积 day*24000+time，调用点已做 %24000 取相 | 6 |
ARGB → FastColor.ARGB32 | 官方 ColorPalette 同款 | 5 |
KeyMapping.Category record → 字符串 category | lang key "key.category.dsurround.keybind" 不变，label 显示一致 | KeyBindings |
SoundEvent.location() → getLocation()；Music record sound() → getEvent()；Registry.getValue → get | 官方源码逐条对照 | ~30 |

### 1.2 结构性回退（需实机验证行为）
1. **沙尘暴 WeatherStormHandler**：26.1 的 GPU 管线（RenderPipeline+GpuBuffer+WEATHER_TARGET）→ 1.20.1 的
   Tesselator 路线（AFTER_WEATHER 阶段直绘）。行为等价论证：
   - 绘制时机同为原版天气 pass 之后（26.1 写入 WEATHER_TARGET、1.20.1/1.21.1 在 AFTER_WEATHER 阶段主帧直绘）
   - 深度写均由 Minecraft.useShaderTransparency() 决定（26.1 选 WEATHER_DEPTH_WRITE 管线 / 21.1 depthMask）
   - 光照均经 lightmap（26.1 bindTexture Sampler2 / 21.1 turnOnLightLayer + PARTICLE 格式 uv2）
   - 列几何/种子/UV 滚动/距离 alpha 公式逐行同 1.20.1 已验收版
   - Gui 层滤色 registerBelowAll（Xaero 不遮挡）签名 renderGui(GuiGraphics, DeltaTracker) 同形
2. **极光 Aurora 全套**：1.20.1 版复刻（ShaderInstance + RegisterShadersEvent + RenderType）。
   1.21.1 仍支持 core shader（1.21.2+ 才移除）。aspect 双 shader 文件方案原样。
   VertexConsumer 调用已适配 addVertex/setUv/setColor 命名。
3. **粒子系统 9 文件**：1.20.1 版复刻（TextureSheetParticle 模型）。
   尺寸语义：1.20.1 的 quadSize 已含 1.20.1 的 1.8x 全局补偿（0.9=0.5x1.8、0.11≈0.06x1.8、0.54=0.3x1.8），
   1.21.1 无补偿、与 1.20.1 语义一致 → 视觉与 26.1 一致。
   FireflyParticle：26.1 原生 firefly 贴图回移为 dsurround 自带 firefly.png（1.21.1 无原生萤火虫），
   flicker 的 packed-light 映射用 1.20.1 实现（LightTexture.pack）。
4. **雾系统**：恢复官方 1.21.1 方案 —— MixinFogRenderer(setupFog RETURN 拦截) + ClientEventHooks.FOG_RENDER_EVENT
   + FogHandler（26.1 曾改用 NeoForge ViewportEvent.RenderFog+FogData，1.21.1 无此 API）。
   fog 计算器 8 文件回退为 FogRenderer.FogData 内部类形态（官方每帧 new，删除 26.1 的 reusableResult 复用，
   字段 renderDistanceStart/End → start/end）。AT 恢复 FogRenderer$FogData 公开。
5. **crit 词 / 聊天气泡（投影）**：1.20.1 版复刻 —— 投影矩阵在 RenderLevelStageEvent AFTER_PARTICLES 阶段捕获
   (getProjectionMatrix × getPoseStack)，1.21.1 同 API。Gui 层签名 renderGui(GuiGraphics, DeltaTracker)。
   crit 词伤害值：LivingDamageEvent → LivingDamageEvent.Post.getNewDamage()（同为最终伤害语义）。
6. **藏宝图距离 MixinMapRenderer**：26.1 的 MapItem.getMapId/MapDecoration.getX → 1.21.1 的
   DataComponents.MAP_ID 组件 + MapId.key() + MapDecoration record x()/y()；
   decorations 字段经新增 MixinMapItemSavedData accessor 暴露。
7. **tag sync**：恢复官方 MixinTagCollector（26.1 曾改 NeoForge TagsUpdatedEvent，1.21.1 无该事件形态）。
8. **实体/注册包路径**：vehicle.boat→vehicle、projectile.arrow→projectile、npc.villager→npc、
   monster.illager/zombie→monster（mixin target 字符串同步）。ParticleStatus server.level→client。
   UseAnim 26.1 改名 ItemUseAnimation → 回退 UseAnim。
9. **SoundEngine mixin**：26.1 play() 返回 PlayResult → 1.21.1 void（官方 MixinSoundEngine 复刻）。
   MixinParticleResources 删除（1.21.1 sprite 表在 ParticleEngine 上，MixinParticleManager accessor 同官方）。
10. **SereneSeasons**：官方 1.21.1 compat 文件复刻（SeasonHelper.getSeasonState 同源 API）。

## 2. 行为一致性验证清单（阶段四实机逐项过）
- [ ] 脚步全套：材质脚步/multifoot 落地/land+jump 随机性/stone 音调（数据层 dsconfigs 原样带过，代码层 getDayTime 等价）
- [ ] 沙尘暴：雨天帷幕+尘柱渲染/晴天远景黄调（fog color）/Nether 尘雨；Xaero 小地图不被遮挡
- [ ] 沙尘暴尘柱与原版雨雪的混合深度（AFTER_WEATHER 直绘 vs 26.1 WEATHER_TARGET，观察半透明排序）
- [ ] 极光：shader 注册成功、六种 aspect 变体、与 1.20.1 视觉一致
- [ ] 雾：晨雾季节权重/群系雾/-bedrock 雾计算（getMinBuildHeight 语义）；水下/岩浆/细雪不被修改
- [ ] 粒子：脚步脚印纹理窗、水波纹扩散、呼吸气泡/雪霜呼吸、沙尘粒子、萤火虫（闪烁+夜晚发光）
- [ ] crit 词：伤害/治疗数字、暴击样式、投影跟随与遮挡剔除（射线检测）；1.21.1 Post 事件伤害值正确性
- [ ] 聊天气泡：玩家+村民聊天、世界→屏幕投影、字体缩放
- [ ] 藏宝图：手持地图右上角距离显示（MAP_ID 组件读取、单人服务器数据源）
- [ ] 音频诊断 HUD：channel 计数、按 SoundInstance 分组统计（getLocation）
- [ ] 配置屏：Cloth Config 15.0.140 打开、按键绑定（字符串 category）、独立音量控制屏
- [ ] runClient 全量回归：计划文档 §阶段四 全项

## 3. 遗留说明
- LeafLitterBlock/VegetationBlock（1.21.2+ 方块）以恒 false 的兼容 helper 保留检查结构（1.21.1 世界无此方块，行为等价；升 1.21.2+ 只需改两个 helper）
- mixins.json 最终 33 项 client mixins（含恢复的 MixinFogRenderer/MixinTagCollector/MixinMapItemSavedData，删除的 MixinParticleResources）
- 诊断日志 build_compile_step2*.txt 留档于项目根（gitignored）
## 4. 脚步系统专项复刻验证（2026-08-30，对照 docs/FOOTSTEP-SYSTEM.md 逐项）

> 触发：用户质询"确定完整复刻了吗"——逐文件/逐数值/逐数据条目核验结果如下。

### 4.1 代码层核验（1.20.1 ↔ 1.21.1 逐文件 diff）

| 文件 | 结果 |
|---|---|
| SoundVolumeEvaluator / SoundInstanceHandler / IAudioPlayer / FootprintHandler / SoundEventType / IRandomizer | **字节级 IDENTICAL** |
| FootstepGenerator（609→621 行） | 28 行差异全部为：isLeafLitter/isVegetationBlock 兼容 helper + 注释。状态机/常量/组合表逐行同构 |
| FootstepAccents（39=39 行） | 仅 PlatformCompat→architectury Platform（isModLoaded 语义相同） |
| MixinEntity（脚步取消+accent 重发） | 结构同构；注入点 26.1/1.21.1 同为 walkingStepSound（已从 1.21.1 sources jar 验证该方法存在，1.21.1 新增的内部分发，HEAD cancel 行为等价且连带水晶步声分支） |
| MixinLivingEntity | 功能同构（effectInfo + jumping shadow）；1.20.1 多出的 7 行是无用 import |
| MixinSoundEngine | 采用官方 1.21.1 版：音量缩放（SoundVolumeEvaluator）同款、onSoundPlay 空间音频改用 locals 捕获（官方对 1.21.1 权威注入点）、额外获得声音距离修剪 |
| AudioPlayer / AudioPlayerDebug | **发现并修复回归**：26.1 基为构造器注入 SoundManager，1.20.1 曾因早实例化 null 改为懒解析——已恢复懒解析（AudioPlayerDebug 构造器同步改为仅收 logger） |

### 4.2 常量核验（与文档 §3/§6/§7 数值逐一对表）

JUMP_LAND_DISTANCE_MIN=0.9 ✓ LAND_ECHO_DELAY_MIN/MAX=1/2 tick ✓ LAND_ECHO_VOLUME=1.0 ✓
FOOT_LATERAL_OFFSET=0.2 ✓ CLIMB_VOLUME_BOOST=1.0 ✓ stride walk/run×1.06/ladder ✓ 步距 ×0.6 ✓
LAND_COMPOSITIONS 组合表逐行同构（Compare-Object 除 helper 差异外零变化）✓

### 4.3 数据层核验（assets/dsurround）

| 文件 | 结果 |
|---|---|
| sound_mappings.json（重映射+accents） | 字节 IDENTICAL |
| variators.json（stride 0.9/landHard 1.5/volumeScale 0.45/playJump） | 字节 IDENTICAL |
| biomes.json / blocks.json / dimensions.json | 字节 IDENTICAL |
| sounds.json（ogg 池） | 163 事件两侧一致 |
| sound_factories.json（97 条工厂） | **96 条一致，1 条既有漂移**：brush accent volume 1.20.1=0.3 / 26.1基=0.4（文档 §10 记载的"0.3/0.4"即此）——1.21.1 跟随基座取 0.4，待用户拍板是否回同步 1.20.1 |

### 4.4 修复动作（本轮验证产出）
1. FootstepGenerator.isVegetationBlock：恒 false → `instanceof BushBlock`（对齐 1.20.1 的支撑面排除语义——1.21.1 仍有 BushBlock，草丛不做脚步支撑面）；
2. AudioPlayer/AudioPlayerDebug：恢复 1.20.1 的懒解析（防 DI 早实例化 null）；
3. 修复后 build 成功：DynamicSurroundingsRebirth-1.21.1-1.1.0.jar（13:32:10）。
## 5. 阶段三完成记录：数据与素材核对（2026-08-30）

| 项 | 结果 |
|---|---|
| dsconfigs 6 个 json | sound_mappings/variators/biomes/dimensions 与 26.1 字节一致；sound_factories 97 条中 96 条一致，brush accent volume 经用户拍板**三版统一 0.3**（26.1+1.21.1 已改） |
| blocks.json | **修复 2 个 1.21.1 方块改名**：waterlily→lily_pad、deadbush→dead_bush（26.1 的改名回退；蛙声/响尾蛇声学条件不变） |
| sound_mappings 声音事件 | 90 条映射中 9 条引用 26.1 独有声事件（creaking_heart/dried_ghast/leaf_litter/resin/resin_bricks/shelf 等新方块步声 + dirt/iron/spawner 独立步声）——1.21.1 下全部无害回落到 SoundType 级映射（grass/metal），与 1.20.1 行为一致，条目保留待升级自动生效 |
| tags（114 个 json） | 结构与 26.1 一致；NeoForge optional tag 引用（{"id":"#c:...","required":false}）为 26.1 引入的模组兼容标记，1.21.1 NeoForge 同样支持，缺失优雅跳过 |
| 模组兼容数据 | biomesoplenty/natures_spirit/profundis/promenade/sereneseasons 共 **11 文件，三版 MD5 一致**（assets/<modid>/dsconfigs/ 模组命名空间，随源树拷贝自动带上） |
| 资源树 | 26.1 与 1.21.1 均为 **928 文件**，内容差异仅 3 个（mixins.json/AT/ blocks.json——全部为有意修改）；chat 台词表/shaders/sounds/textures 全量在位 |
| lang | en_us/pl_pl/zh_cn 三文件三版**字节一致**（401 keys）；1.21.1 无新增 UI 文案 |
| 脚本选择器 | LibraryFunctions.oneof 在 1.21.1 存在（模组兼容数据的条件规则依赖） |
| CREDITS.md | build.gradle processResources 已拷入 jar |
