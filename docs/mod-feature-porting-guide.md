# Dynamic Surroundings Rebirth — 功能实现与移植指南

> **定位**：按系统梳理模组全部功能的实现逻辑、具体表现、踩坑与注意事项，作为向其它高版本
> （26.2+ / 27.x）整体移植的操作指导。事实来源：MIGRATION_STATUS.md（迁移全史）、
> docs/26.1-mod-dev-guide.md（API 速查）、docs/aurora-shader-rewrite-notes.md（极光专章）
> 以及源码本身。每个功能统一按【实现】【表现】【坑】三段书写。
>
> 最后更新：2026-08-16（对应提交 56de777）。

---

## 〇、架构总览（移植前必读）

**客户端纯净性**：纯客户端 mod（`@Mod(dist = Dist.CLIENT)`），不上服务端、可连原版服务器。
所有"世界状态"都从客户端本地推断（群系扫描、天花板扫描、声音位置等）。

**依赖注入**：自研 `ContainerManager`/`DependencyContainer`，`@Cacheable` 类注册为单例。
`Client.construct()` 阶段注册全部服务（日志、库、播放器、扫描器、Handlers）；
`Handlers.init()` 按序实例化各 Handler（`Scanners` 必须最先）。构造注入为主，
少量历史代码用 `ContainerManager.resolve` 服务定位（含 synchronized 查找，
**不要在每帧渲染路径里 resolve**——见 2.30 审查 E4 的教训）。

**事件体系**（三层，移植时注意区分）：
1. `NeoForge.EVENT_BUS`：游戏事件（RenderLevelStageEvent、LivingDamageEvent、ItemCraftedEvent、ViewportEvent.RenderFog、TagsUpdatedEvent 等）
2. 自研 `ClientState`：优先级化相位事件 `STARTED/STOPPING/TICK_START/TICK_END/ON_CONNECT/ON_DISCONNECT/TAG_SYNC/RESOURCE_RELOAD`；
   内置 `connectionDetector` 保证 ON_DISCONNECT 必发
3. `ClientEventHooks`：mixin 转发的原生钩子（声音播放/方块更新/落块落地等）

**启动时序**：`NeoForgeMod` 构造器内**全量初始化**（client.construct + initializeClient + GUI 层注册 + shader 管线注册）。
- 坑①：`RegisterGuiLayersEvent` 早于 `FMLClientSetupEvent`，任何"延迟初始化"都会让 GUI 层 resolve 崩溃、
  SoundManager 工厂注册过晚导致**声音全失效**（2.23 实测回退过，勿再尝试）。
- 坑②：`RegisterRenderPipelinesEvent` 是 **IModBusEvent**，必须在 modBus 注册（`modBus.addListener`）。
- 坑③：`@OnlyIn(Dist.CLIENT)` 注解会被 OnlyInWarningsHandler 打成启动 ERROR——26.1 不要用。
- 坑④：注册 mixin 用 addListener 的方法**不要再加 @SubscribeEvent 注解**（死注解，多次清理过）。

**配置**：Cloth Config 界面 + `Configuration.java`（14 分组 78 字段）。
- **铁律**：新增任何配置字段必须同步 en_us + zh_cn 翻译键（`dsurround.config.<分组>.<字段>` + `.tooltip`，
  分组名首字母小写），否则界面显示原始键路径。
- `run/config/dsurround/dsurround.json` 有 `enableDebugLogging`/`traceMask` 调试开关。

**数据驱动**（`assets/dsurround/dsconfigs/`，全部 JSON、游戏内 `/dsreload` 热重载）：
| 文件 | 内容 |
|---|---|
| `sound_factories.json` | 声音工厂（location、soundEvent、音量/音高/category） |
| `sound_mappings.json` | 原版声音事件→DS 工厂替换规则（含方块/标签匹配、accent 叠加） |
| `blocks.json` | 方块条目（声学、效果、spawnChance） |
| `biomes.json` | 群系条目（音效集、mood、wind） |
| `tags/` | 自定义方块/物品/实体 tag |
| `variators.json`、`dimensions.json` | 脚步参数、维度信息 |

**日志**：`ModLog.create(modId)`，debug 级默认关。**调试日志必须限频**——雨滴粒子/每帧雾的
日志能让日志文件涨到 GB 级卡死游戏（2.9.5 血泪教训）。

**打包**：Nashorn（配置条件脚本求值）走 NeoForge Jar-in-Jar（`META-INF/jarjar/nashorn-core-15.4.jar`）。
- 坑：Nashorn **不能扁平化**进主 jar（模块化冲突：ASM 版本争抢、包名争抢，三连崩见 2.16.1）；
  dev 环境 runClient 能跑 ≠ 发布 jar 能跑，**发布前必须启动器实测**。

---

## 一、声音系统

### 1.1 播放管线与重映射

【实现】`MixinSoundEngine` 注入 `SoundEngine.play()`：
①`SoundInstanceHandler.shouldBlockSoundPlay`（个体配置屏蔽/cull/距离裁剪）→
②`SoundLibrary.remapSound`（按声音触发位置的**下方方块**匹配 `sound_mappings.json` 规则，
替换为 DS 声音工厂）。穿草时对 `block.grass.step` 替换为静音事件让 brush accent 独占。
【表现】原版脚步→材质脚步（草/石/雪…106 条规则全覆盖）、雷声→DS 低沉雷声、穿草沙沙声替换原版草声。
【坑】
- `getSound()` 对未注册声音返回 **MISSING 占位音而非 null**——判断注册要用 `isSoundRegistered()`（2.12 翻车）。
- 工厂 `location ≠ soundEvent` 时必须用 `getSoundFactoryOrDefault(location)` 查（按事件名查会播 MISSING 静音，2.16.2 草径/梯子六个月没声的根因）。
- 爬梯豁免：声音源位置在 `BlockTags.CLIMBABLE` 上时 remap 直接跳过（否则原版梯子声又被映射回 DS 材质）。
- 生物边缘：脚下空气/流体时 mob step 映射返回 null（MC 空气默认 STONE 声，边缘悬空会误播石头声）。
- 已知语义待定：被 block 的声音仍走 remap（屏蔽原版声但替换声照播）。

### 1.2 声音工厂、个体控制与音量滑块

【实现】`sound_factories.json` 定义工厂（可叠加 accent 列表）；`SoundLibrary` 管理注册表；
个体声音配置界面（`gui/sound/*`，15 个文件，26.1 全部按 GuiGraphicsExtractor 重写）持久化到
`soundconfig.json`；声音设置页注入两个滑块（`MixinSoundOptionsScreen` + 自定义 `DsVolumeSlider`，
0-200%）。
【表现】音效设置页多"Configure Sounds"按钮（或按 `[`），逐声音调音量/屏蔽/裁剪/试听；
Footsteps/Biomes 两个总滑块。
【坑】
- `footstepVolume=0 → 恢复原版脚步`需要**三处配合**：FootstepGenerator 提前返回 + remapSound 不映射
  + mixin 不取消原版玩家脚步——少一处则"玩家没脚步、生物有"或双重脚步。
- 26.1 GUI：`OptionInstance.SliderableValueSet` 包私有不能实现，滑块必须继承 `AbstractSliderButton`。

### 1.3 增强音效处理——线程模型

【实现】`SoundEngine` 播放回调建 `SourceContext`（每个 AL 声源一个）；`SoundFXProcessor`
起独立 Worker 线程（50ms 一轮）+ 自建**守护**线程池（默认 2 线程）；每轮取"到期"声源
（每源每 10 tick 一欠，`UPDATE_FREQUENCY_TICKS`）按距离排序**每轮最多处理 32 个**
（`MAX_SOURCES_PER_PASS`，防矿洞怪海打满 CPU）；结果经 `Effects.applyReverb` 在
**声音引擎线程**（`SourceContext.tick`）上传 OpenAL。
【坑】
- **OpenAL 调用只能在声音引擎线程**（context 只在那儿 current）——每源 filter 的创建/删除
  都挂在 tick/stop 路径上，别在客户端线程碰 AL。
- `sources/worldContext` 等跨线程字段要 volatile；WorldContext 做成不可变快照每 tick 重建。
- 后台任务异常要留 debug 痕迹——曾经吞异常把"配置越界→混响整体失效"掩盖了数月（2.30-A3/A4）。
- 出入水用 volatile 标记 + 下轮全员立即重算 + snap，避免水面浮动卡顿。

### 1.4 空间音效四件套（核心算法）

**混响（4 zone）**：从声源投 32 条射线×最多 4 次反弹，按反射延迟把能量分配到
small-room→cavern 四个 EAX 混响区；`sharedAirspace`（反弹点直视玩家）放宽高频截止。
- 每源 4+1 个**独占** low-pass filter（OpenAL filter 是共享参数块，全局 filter 会互相覆盖——
  2.30-A1 串扰 bug，单声源测试测不出来）；zone→send 固定绑定。
- 设备适配：26.1 捆绑 OpenAL Soft 1.25.1 默认只给 2 aux sends；mixin `@Redirect alcCreateContext`
  强制请求 4（失败回退）。发送数不足时按上限截断。
- `REVERB_RAY_BOUNCES` 等常量在类加载时快照 CONFIG——`Math.max(4, …)` 防越界；改配置需重启。

**遮挡**：声源→玩家中心射线 + 锥形扇射线平均，穿墙按方块 `soundOcclusion` 系数累积，
时间平滑（EMA ~0.8s）成"闷"感；衍射补偿见下。
- 坑：**空气别算遮挡**——方块库对 air 返回 DEFAULT(0.5)，不特判 `isAir()→0` 的话
  远墙场景光空气就能累积 12+，补偿永远失效（2.28 头号 bug）。
- 坑：实心声源（唱片机 isSolid）自遮挡——射线起点先 `stepOutOfSolid` 走出声源自身方块。
- 坑：`ReusableRaycastIterator.next()` 续射起点必须 `hit.getLocation().add(normal)`
  （实际命中点），写成 `getBlockPos()` 整数角会**方向性遮挡**（对称场景绕圈忽闷忽亮，
  2.24 排查极苦，只有 diff 原版源码才定位）。

**绕射**：中心线被挡时，在遮挡点垂直平面摆自适应半径（1.5→3→5→8）waypoint 环，
找最短绕行增量 ΔL：`diffraction = EDGE_LOSS / (1 + FALLOFF·ΔL)`，配立方门控
（声源封闭度³/玩家开放度³，只惩罚真围死）+ 时间平滑；同时以 0.5 系数抬 reverb。
直射恢复**统一由衍射负责**，reverb 由反射负责——职责分明，不要再加补偿因子（2.27 的
五因子乘积路线已废弃，越补越乱）。

**水下声学**：声源原始位置→玩家眼全程 0.5 格采样穿水长度；`muffle=0.6^len`（低通）、
`gain=max(0.15, sqrt(0.9^len))`（音量，开方+下限防远声消失）；两者解耦独立配置；
出入水瞬时 snap。坑：声源位置偏移逻辑用 `!=AIR` 判断会把水当实心——水路径采样必须用
**原始位置**绕开偏移点（现状即如此，勿"顺手统一"）。

### 1.5 脚步声系统（A2 全家，工程量最大的子系统）

【实现核心】`FootstepGenerator`（单例 handler）状态机：位移累计过步距触发
（walk 0.7 / run 0.95，×0.6 系数）；跑步用 **`isSprinting()`** 判定（速度阈值会把快走误判）；
`MixinEntity` 取消原版玩家脚步防双声；每步：主声（材质工厂）+ accent 叠加 + 音高随机 0.8-1.2。
【分层】
- **材质映射**：`sound_mappings.json` 106 条按方块/标签区分（石英→marble、黑曜石→lino、
  锅→metalbox…）；7 个补齐材质（quicksand/weakice/glass/marble/concrete/lino/squeakywood）。
- **组合音 accent**：草=grass+brush@0.3、冰=stone+muffledice@0.4 等六组 simultaneous。
- **run/walk 分变体**：`*_run` 独立事件（80 个音频）；**落地声**：分材质 `_run`/`_land`
  三层叠加（主 1.0 + 副 0.5 + 2 tick 延迟回声 0.85，落地 ≈ 走路 4 倍响）+ 音高固定 0.75。
- **触发判定**：主动起跳+落差>0.9 重落地；未起跳掉落>1.5 重落地；跳上一格/走台阶正常脚步
  （显式 `yPosition - pos.y > 0.4` 检测下台阶）。
- **wander 跳跃**：起跳人声 + 脚下方块材质 `_wander`（12 材质 62 音频）。
- **盔甲 accents**：按脚/腿/胸甲槽播装备声；**FloorSqueak**：木板 1/10 概率吱呀；
  **穿草 brush**：高草/蕨（位置去重 messyPos、生物用水平速度判定——`xxa/zza` 对 AI 生物恒 0）；
  **涉水/雨坑**声。
- **梯子**：`onClimbable()` + 步距加 `|dy|`（水平步距爬梯永远不触发）；材质直接用攀爬方块。
【坑】
- **`fallDistance` 落地瞬间重置为 0**——必须在空中缓存（落地声、脚印同源坑）。
- **MC 单声音量钳制 1.0**——"更响"只能靠多声音叠加，不能调 volume；素材响度差异必须
  在 ogg 层面 normalize（stone_run 曾只有 grass_run 一半响度）。
- `ObjectArray` 无 `remove(int)`——`remove(i)` 装箱匹配 `remove(Object)` 永不移除，
  必须用 `removeIf`（暴击词泄漏卡死的根因）。
- 雪层表面高度用 **`getShape`（视觉形状）**而非 `getCollisionShape`（雪层碰撞盒矮一层，
  脚印会印错层）；薄雪踩雪声取脚所在层的可见形状。
- 灵魂沙笑声不要加维度条件（1.12.2 对齐）；`minecraft:chain` 已改名 `iron_chain/copper_chain`。
- 换世界必须复位状态（lastPos/isFlying/fallDistance…否则幻影脚步，2.30-B1）。

### 1.6 群系/环境/玩家状态声

【实现】`BiomeSoundHandler`：`BiomeScanner` 每 4 tick 扫 18×18×16 群系占比 →
按占比加权生成 loop 音量；mood 声按群系概率；**室内 ×0.15**（CeilingScanner 头顶遮挡率>63%）。
【特色点】
- **leaf_wind 风吹树叶**：`moodSoundChance` 是**群系级共享**的，独立触发必须绕开 mood 集合
  在 handler 里硬编码（ wooded trait → 白天 3min/晚上 1min 一次）。
- **深暗之域三层氛围**：drone loop + 心跳 loop（0.02 音量）+ sculk 咔嗒；**室内衰减豁免**
  （地下必被判室内，不豁免 drone 全被吞）。
- 玩家状态：心跳（血量<25%、0.8s）、饥饿咕噜（<8、15s）。
- **背景雷**：雷暴时 20-40s 远处低沉雷声（`ThunderHandler`）。
- **火把燃烧**：固定火把走 `RandomBlockEffectSystem`（blocks.json spawnChance 0.01）；
  手持火把独立 handler 独立工厂（音量解耦）。
【坑】
- **概率都是每 tick 的**：0.03/tick 累积 60%/秒——素材 1.8s 长时 0.012 才不连成一片（2.26 教训）。
- 方块效果随机采样下 spawnChance 要远高于直觉（采样率 ~1.9%/tick/方块）。
- 无缝循环素材**切勿再叠加首尾淡入淡出**（每圈"断一下"）；响度用 rms 对齐。

### 1.7 音乐管理与命令

【实现】`MixinMusicManager`：`startPlaying` RETURN 注入播 toast（曲名/作者，credits 空列表要判空）；
`/dsmm` 命令族 pause/unpause/whatsplaying；`dsbiome/dsdump/dsreload/dsscript` 调试命令。
【坑】音乐 toast 依赖 sounds.json 的 title/credits 元数据（`ds_credits` 署名字段，CC0 素材合规）。

---

## 二、视觉系统

### 2.1 极光（Aurora）

【实现】双渲染器：`AuroraShader`（默认）+ `AuroraClassic`（回退）。
- **管线**：`RegisterRenderPipelinesEvent`（modBus）注册 2 个 pipeline（ASPECT 4.5/9 双变体，
  `withShaderDefine` 烘焙静态参数）；`RenderType.create(name, RenderSetup.builder(pipeline)...)`；
  QUADS + `POSITION_TEX_COLOR`；SRC_ALPHA/SRC_ALPHA 加色混合、只测深不写深、无剔除。
- **渲染**：`RenderLevelStageEvent.AfterSky` + `Minecraft.renderBuffers().bufferSource()` 缓冲路径
  （**不能在 AfterSky 里 immediate draw / 自定义 UBO 上传**——framegraph 崩）；QUADS 不能用
  TRIANGLE_STRIP（不合并批当场 draw 同样崩）。
- **shader**：GLSL 330 + `#moj_import`；动态时间用内置 `GameTime`（globals.glsl），
  淡入淡出走顶点色 alpha，**标准 RenderType 路径绑不了 per-frame 自定义 uniform**。
- **几何**：连续竖直 quad 条带沿 AuroraBand 路径（顶点 y 0-1，SCALE_Y 即幕帘总高度）；
  前层 + 背层（背层 alpha ≤0.35 防过曝）；X（长度）/Z（宽度）缩放分离独立调参。
- **触发**：雪原/冰刺群系 + 22:00–03:00（天顶角 150°–225°）+ 渲染距离≥6 + 按游戏日做种子。
【表现】连续大幕帘、竖直射线、底紫中绿顶红、扫掠亮线、25s 高度呼吸、每带微色相偏移、
底缘射线增亮。
【坑】（七轮实测调优的精华，详见 aurora-shader-rewrite-notes.md 第 8 节）
- 亮度宁暗勿亮（加色混合过曝快），起步 1.0 每次 +0.1；峰值系数预算表见文档 8.9。
- **射线 x 频率对高度必须恒定**——随高度变会让噪声域竖直压缩出"木星纹"。
- 顶部渐隐必须保证 top+fade < 几何边，否则 quad 硬切边。
- 对比度整形（smoothstep）必须连同 rayMask 下限一起调，否则暗缝变"断口"。
- 改 X 长度要同步 ASPECT define，否则射线被水平拉宽。
- **一次只改一个视觉变量**，改完进游戏确认。
- 睡觉时间跳变检测：>30°/tick 即杀（自然日出仍走 25s 慢淡出）。

### 2.2 粒子系统（26.1 SingleQuadParticle 模型，通用坑先列）

**通用**（全部实测，详见 dev-guide §2）：
1. 纹理必须显式进粒子图集——覆盖 `assets/minecraft/atlases/particles.json` 用
   directory + single source（放 `textures/particle/` 单数目录不保证生效）。
2. `Mth.lerp(delta, start, end)` 第一参是插值系数，写反静默错 UV。
3. 粒子管线默认背面剔除——水平铺地用 `rotationX(-HALF_PI)`（法线朝上）。
4. `particle.fsh` 对 alpha<0.1 discard——UV 采到透明区=整粒消失，CPU 日志看不出，
   只能对照实验二分。
5. `getQuadSize` 别从 0 增长；涟漪固定 quad 尺寸靠帧动画扩张。
6. SpriteSet 在 `ParticleResources.spriteSets`（mixin @Accessor）。

**逐粒子**：
| 粒子 | 实现 | 坑 |
|---|---|---|
| 脚印 Footprint | 材质白名单方块留印；落地双脚左右交替；`getShape` 表面高度；旋转 `rotationY(PI - yaw)` 补偿 XY 平面 quad 的镜像 | 方向公式必须 π−yaw（否则南北反向）；挖掉支撑块/雪层削薄后浮空→tick 检查支撑面 remove；边缘悬空剔除 ±0.5 |
| 水波涟漪 WaterRipple | mixin 原版雨滴粒子→水平 quad + 7 帧条带动画（帧 UV 重映射进 sprite 区间）；y+0.05 浮出水面 | 从帧 2 开始动画（帧 0 太透明被 discard） |
| 落块扬尘 DustCloud | `Entity.onClientRemoval` mixin（FallingBlockEntity 未 override）——客户端 onGround 恒 false、服务端 discard 同 tick 事件全错过，onClientRemoval 是唯一可靠钩子 | 见 2.20，这是最重要的移植知识点之一 |
| 沙尘 StormDust | 沙漠/下界粒子雨 + 黄屏滤镜（GUI 层 `registerBelowAll`）；isInside 矿洞豁免 | 12 粒/tick 要考虑粒子预算（MINIMAL/DECREASED/ALL 分档缩放） |
| 萤火虫 Firefly | 原版 26.1 firefly 贴图 + 闪烁 alpha 脉动；夜晚花丛 3.5% | 去掉原版"方块内移除"（DS 生成在花位置会立即误删） |
| 霜息 FrostBreath | 寒冷群系生物口鼻白雾，TRANSLUCENT 层 | getSize→getQuadSize 改名 |
| 呼吸气泡 Breath | 准心处透明小气泡 | getSprite+super.tick 版触发 GPU 崩溃——用 spriteProvider+move 安全模式 |
| 暴击词 CritWord | 伤害/治疗/暴击词 GUI 投影 + 3D 飞出 + 距离缩放；`level.clip` 视线遮挡剔除；先深度剔除再 raycast | `drawInBatch` 立即模式在 26.1 renderLevel 不生效（SubmitNodeCollector），改 GUI 投影；ObjectArray removeIf |

### 2.3 雾系统

【实现】`FogHandler` 监听**原生** `ViewportEvent.RenderFog`（ATM/`FogType.ATMOSPHERIC`），
写回事件的可变 `FogData`；`HolisticFogRangeCalculator` 组合 5 个子计算器取 min：
Biome（群系 fogDensity 缩放）、Morning（清晨随机雾，270-317° 窗口）、Weather（雨雾）、
Bedrock（基岩层渐变，动态 minY+32，含 skyLight 亮度项——白天地表不触发）、
Haze（云层+高海拔 280-320 双雾带）。
【坑】
- **基准选 renderDistanceStart/End**——主世界 environmentalStart 恒 0，用错全归零（2.9.6 头号坑）。
- **角度要 -6h 偏移**：`((ticks%24000)+18000)%24000 /24000*360`（tick0=6AM，DS 约定 0=正午）；
  漏偏移白天误判夜晚、晨雾落午夜（2.9.4）。
- 距离 cap：雨雾 96、晨雾 128（DS 基于 renderDistance 会推到 150-230 太远）。
- `start>end` 会被组合器拒收刷屏——start 必须随 end 一起钳制（Iris 光影会改大 start）。
- 26.1 无 per-dimension sky height/cloud height API——用维度类型/`IDimensionInformation` 近似。

### 2.4 天气视觉与方块效果

- **沙漠沙尘暴**：沙漠下雨 = 滤镜 0.7 + 12 粒/tick（平时 0.12 淡黄雾）；下界恒定暗尘雨；
  均受粒子档位缩放、矿洞豁免。
- **岩浆冒烟**：雨落岩浆/下界岩冒烟（SMOKE 粒子）。
- **方块效果**（`AreaBlockEffects`/`RandomBlockEffectSystem`，每 tick 采样 500 方块）：
  瀑布声+溅花（6 档音效）、熔岩喷口/火焰喷射、水下气泡、蒸汽柱、萤火虫、落灰（浮空方块底部，
  spawnChance 0.01）。
- 坑：**handler 必须 DI 注册 + tick 列表双注册**，只注册 DI 则 process() 永不调用（2.14 实测）；
  spawnChance 是被随机采样下的条件概率，远高于直觉（0.5 太频繁→0.01 合适）。

### 2.5 GUI Overlay

- **指南针/时钟**：手持对应物品时屏幕上方横带（`CompassOverlay`/`ClockOverlay`，
  `registerBelowAll` GUI 层）；spinRandomly 每 tick 复位。
- **诊断 HUD**：按键开关，`CollectDiagnosticsEvent` 收集各系统状态。
- **藏宝图距离**：`MixinMapRenderer` @TAIL 注入，复用地图自身 PoseStack 提交 3D 文本
  （右上角距离数字随视角转动）。
  坑：**客户端地图数据不含中心坐标**（createForClient 无 center、同步包不含 centerX/Z，
  恒 0）——"普通地图到中心距离"纯客户端不可能，只做藏宝图（装饰组件是世界坐标 double）。
- 26.1 GUI API 全家（GuiGraphicsExtractor/text()/KeyEvent/ToastManager/KeyMapping.Category）
  见 dev-guide §1。

---

## 三、世界扫描

- **BiomeScanner**：每 4 tick、玩家跨格才扫 18×18×16=5184 采样（vanilla 噪声群系量化+缓存，
  实测可控）；产出群系占比表供声音/雾/沙尘使用。
- **CeilingScanner**：头顶遮挡率 → `isInside()`（>63%），驱动室内声音衰减/沙尘豁免；
  SURVEY_INTERVAL 8 tick（矿洞向下扫描尖峰减半）。
- **VillageScanner**：村民+铃铛判定在村庄（诊断/环境状态）。
- 坑：扫描器是众多功能的状态源——移植顺序上**先于**所有消费者初始化（Handlers 里 Scanners 第一）。

---

## 四、外部兼容

- **Serene Seasons**：compileOnly 依赖 + `SeasonHooks`（温度/降水镜像 `getPrecipitationAt` 的
  seaLevel 参数）；运行时按 mod 加载分支。
- **Iris/Sodium**：大面积 mod 实测兼容；已知 Iris 阴影渲染路径 `IncompatibleClassChangeError`
  （ArmorAccents lambda 被当 GlTessellation，非 DS 逻辑问题，关光影可复现/规避）。
- **光影交互**：雾参数会被 Iris 改大（start 钳制已防）；极光走 AfterSky 自定义管线，
  光影包可能整体接管天空——用户侧已知行为。
- **Cloth Config**：依赖已在 mods.toml 声明（≥26.1.154），缺失时配置按钮点不开并有明确提示。
- 无害警告白名单：`No root paths defined for ResourceLookupHelper`（客户端无 SERVER_DATA 根）、
  BOP glowshroom 方块、版本检查 SSL 失败。

---

## 五、资源与素材规范

- 素材授权：CC0/原版 MIT 混合，**每个非原版素材在 sounds.json 用 `ds_credits` 标注来源**；
  Mattenii shader 为 CC BY-NC-SA——极光 shader 必须 clean-room（已做）。
- 响度：MC 单声音量 clamp 1.0，素材间响度差必须 ogg 层面 normalize（对齐 rms，peak<0.98）。
- 无缝循环：crossfade 首尾，**之后不得再加淡入淡出**。
- ogg 生成：libsndfile 写大数组会崩（>~53 万样本），用 `sf.SoundFile` 分块流式写。
- 资源引用三方对齐：sounds.json ↔ sound_factories/mappings ↔ 音频文件；新增事件先查
  `SoundEvents cached` 日志数。配置翻译 en+zh 同步（CLAUDE.md 约定）。
- 未引用素材现状与处置建议见 MIGRATION_STATUS 8.6（45 冗余可删、6 个未移植功能素材保留）。

---

## 六、向更高版本移植的操作清单

1. **环境**：换 MC/NeoForge 版本号（gradle.properties、mods.toml range、JarJar Nashorn 重验）；
   `--no-configuration-cache`（或清 configuration-cache，绝对路径残留会把构建甩到旧盘）。
2. **API 全面复核**：按 docs/26.1-mod-dev-guide.md 的表逐条 javap 新版 jar 确认——
   历史证明**凭记忆正则替换会产生 200+ 编译错**（2.1）。重点复查：
   渲染 extractRenderState 模型、粒子 SingleQuadParticle、FogData 字段、
   SoundEngine PlayResult、`getOverworldClockTime` 语义、Identifier/location 家族改名。
3. **编译层过完跑运行时清单**：mixin 注入点（30 个）逐个验 target 签名；
   GUI 层/管线注册时序；reload 监听（ReloadListenerRegistry + sounds.json 路径行为）。
4. **功能冒烟顺序**（依赖序）：扫描器 → 声音重映射 → 脚步 → 群系声 → 空间音效 →
   粒子 → 雾 → 天气 → 极光 → GUI。
5. **每功能用户实测**再进下一个；声音类问题准备对照实验（开关 occlusion/reverb 分离定位）。
6. **发布**：build 后用启动器（完整 mod 环境）实测——runClient ≠ 发布 jar。
7. **审查**：每轮大改后做一次只读 agent 审查（模式见 MIGRATION_STATUS 8.2），
   发现记入第八章。

---

## 七、调试与排错速查

| 症状 | 先查 |
|---|---|
| 声音全部失效 | ReloadListener 注册、SoundManager 工厂初始化时序（2.4/2.23） |
| 某声音静音 | isSoundRegistered vs MISSING 占位；工厂 location≠事件名（2.16.2） |
| 混响"没有" | AuxSends 数量日志；REVERB_RAY_BOUNCES 越界被吞（开 enableDebugLogging 看 updateImpl 痕迹） |
| 遮挡方向性/忽闷忽亮 | ReusableRaycastIterator 的 getLocation vs getBlockPos；空气算遮挡 |
| 粒子不可见 | 图集注册 → UV/discard 对照实验 → cull 方向 |
| 雾不触发/距离怪 | renderDistance vs environmental 基准；-6h 角度偏移；start>end 拒收日志 |
| 昼夜行为错乱 | getOverworldClockTime 跨天累计（%24000）+ tick0=6AM 偏移 |
| shader 画面异常 | `[mojang/GlDevice] Couldn't compile` 编译日志（没有直观报错） |
| 启动器崩溃 runClient 正常 | Nashorn 打包 / jarJar metadata |
| 换世界一次性怪声/残留 | handler onDisconnect 状态复位清单 |
