# Configuration & Data Files Reference · 配置与数据文件参考

Dynamic Surroundings Rebirth **1.2.0** — Minecraft 1.20.1 (Forge) · 1.21.1 (NeoForge) · 26.1 (NeoForge)

[English](#part-i--english) · [中文说明](#part-ii--中文说明)

---

## Part I — English

### 1. Overview

Dynamic Surroundings Rebirth is **fully client-side**. Every setting lives on **each player's own game installation** — there is no server-side enforcement. A modpack author sets the defaults by shipping the files below inside the pack; every player who installs the pack starts with those settings.

Three customization layers exist:

| Layer | Location | Who edits it |
| --- | --- | --- |
| Config files | `config/dsurround/*.json` (game directory) | Modpack authors (ship presets), players |
| In-game GUI | Mod Options screen (needs Cloth Config) | Players |
| Data files | `assets/dsurround/...` in the jar, overridable by **resource packs** | Modpack authors, resource-pack makers |

**File locations** (relative to the game directory):

```
config/dsurround/dsurround.json      ← main config: all feature toggles and sliders
config/dsurround/soundconfig.json    ← per-sound-event overrides (block / cull / volume)
```

**How the main config loads** (important for modpack authors):

- **Missing keys keep their defaults.** You may ship a file that only contains the options you want to change.
- After loading, the file is **rewritten with the complete set**, so options added by newer versions appear automatically.
- If the JSON is malformed, the mod logs an error and **restores defaults** (and rewrites the file). Validate your JSON before shipping.
- Minimal preset example:

```json
{ "speechBubbles": { "enableSpeechBubbles": true },
  "soundOptions":  { "footstepVolume": 1.5 } }
```

The in-game GUI (Mod Options → Dynamic Surroundings Rebirth, requires Cloth Config) edits the same file. In the tables below:

- **★** = needs a full game restart
- **☆** = applies on the next world load
- (no mark) = applies immediately

### 2. Main config — `config/dsurround/dsurround.json`

#### 2.1 `logging`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableDebugLogging | bool | false | Debug logging of the mod |
| traceMask | int | 0 | Bitmask for toggling debug traces |
| enableModUpdateChatMessage | bool | true | Chat notification when an update is available |
| filteredTagView | bool | true | Filter tag display in the diagnostics overlay |
| registerCommands | bool | true | ★ Register client-side commands (`/dsreload`) |

#### 2.2 `soundSystem`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| cullInterval | int | 20 | 0–200 (slider) | Ticks between culled sound events (0 disables culling) |
| enableSoundPruning | bool | true | — | Cancel sounds a player will not hear |

#### 2.3 `enhancedSounds`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableEnhancedSounds | bool | true | — | ★ Enhanced sound processing (reverb, occlusion) |
| backgroundThreadWorkers | int | 0 | 0–8 (slider) | ★ Background threads for sound processing (0 = internal default) |
| enableMonoConversion | bool | true | — | Convert stereo to mono on the fly |
| enableOcclusionProcessing | bool | false | — | Muffle sounds behind blocks |
| reverbRays | int | 32 | 16–64 | ★ Rays projected per sound to compute reverb |
| reverbBounces | int | 4 | 2–8 | ★ Reflections per ray |
| reverbRayTraceDistance | int | 256 | 64–512 | ★ Total ray distance (blocks) |
| reverbIntensity | double | 1.0 | 0–2 (slider) | Reverb/echo strength (1.0 = default, 0 = off) |
| enableWaterSoundDamping | bool | true | — | Dampen sounds whose path passes through water |
| waterSoundDamping | double | 0.95 | 0.1–1 (slider) | Volume fraction surviving each water block (lower = quieter) |
| waterSoundMuffle | double | 0.7 | 0.1–1 (slider) | High-frequency cut per water block (lower = more muffled) |

#### 2.4 `soundOptions`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| ambientVolumeScaling | int | 100 | 0–400 (slider) | % — multiplier for ambient sounds played by the mod |
| replaceThunderSounds | bool | true | — | Use DS's thunder sounds |
| allowScarySounds | bool | true | — | Allow sounds considered scary |
| playBiomeMusicWhileCreative | bool | false | — | Biome background music in creative |
| displayToastMessagesForMusic | bool | true | — | Toast messages for credited music |
| remapSounds | bool | true | — | Sound remapping when sounds play |
| enableBackgroundThunder | bool | true | — | ☆ Distant thunder rumbling during storms |
| enableBiomeSounds | bool | true | — | Ambient biome and village sounds |
| footstepVolume | double | 1.0 | 0–2 (slider) | Footstep volume multiplier |
| biomeVolume | double | 1.0 | 0–2 (slider) | Biome ambient volume multiplier |
| playerEffectVolume | double | 1.0 | 0–2 (slider) | Player effect volume (jump, heartbeat, hunger, crafting, hotbar) |

#### 2.5 `blockEffects`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| blockEffectRange | int | 32 | 16–64 (slider) | Blocks scanned for block effects |
| steamColumnEnabled | bool | true | — | Steam columns near lava/magma |
| flameJetEnabled | bool | true | — | Flame jets over lava |
| bubbleColumnEnabled | bool | true | — | Underwater bubble columns |
| firefliesEnabled | bool | true | — | Fireflies |
| dustJetEnabled | bool | true | — | Dust falling from floating blocks |
| fallingBlockDustEnabled | bool | true | — | Dust clouds when sand/gravel lands |
| waterfallsEnabled | bool | true | — | Waterfall effect from flowing water |
| enableWaterfallSounds | bool | true | — | Waterfall sounds |
| enableWaterfallParticles | bool | true | — | Waterfall particles |
| waterRippleStyle | enum | PIXELATED_CIRCLE | NONE, PIXELATED_CIRCLE | Ripple style when a drop hits fluid |
| enableMagmaSteam | bool | true | — | ☆ Steam/smoke when rain hits magma |

#### 2.6 `entityEffects`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| entityEffectRange | int | 24 | 16–64 (slider) | Max range (blocks) for entity effects |
| enableBowPull | bool | true | — | ☆ Bow pull sound |
| enableBreathEffect | bool | true | — | ☆ Breath in cold biomes / underwater |
| enablePlayerToolbarEffect | bool | true | — | ☆ Player hotbar sound effects |
| enableToolbarBlockSounds | bool | false | — | ☆ Sounds for blocks on the hotbar |
| enableSwingEffect | bool | true | — | ☆ Item swing sounds (players and mobs) |
| enableBrushStepEffect | bool | true | — | ☆ Walking through dense brush |
| enablePlayerHeartbeatSound | bool | true | — | ☆ Heartbeat when health is low |
| playerHurtThreshold | double | 0.25 | 0–1 | Health fraction below which heartbeat plays (0 = off) |
| enablePlayerHungerSound | bool | true | — | ☆ Stomach growl when hungry |
| playerHungerThreshold | int | 8 | 0–20 | Food level at/below which growl plays (0 = off) |
| enablePlayerJumpSound | bool | true | — | ☆ Jump sound |
| enablePlayerLandSound | bool | true | — | ☆ Landing sound from a fall |
| enableFootstepSounds | bool | true | — | ☆ Footstep sound system (walk/run materials) |
| enableCraftingSound | bool | true | — | ☆ Crafting sound |
| suppressPotionParticles | bool | false | — | ☆ Hide the player's potion particles |
| enableFootprints | bool | true | — | ☆ Player footprints while walking |
| footprintStyle | enum | LOWRES_SQUARE | SHOE, SQUARE, HORSESHOE, BIRD, PAW, SQUARE_SOLID, LOWRES_SQUARE | Footprint style |
| showCritWords | bool | true | — | ☆ Comic power word on critical hits |
| showDamageNumbers | bool | true | — | ☆ Damage/healing numbers above entities |

#### 2.7 `footstepAccents`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableAccents | bool | true | Footstep accents globally |
| enableArmorAccents | bool | true | Armor accents |
| enableWetSurfaceAccents | bool | true | Accents when raining / waterlogged |
| enableFloorSqueaks | bool | true | Squeaky-block accents |

#### 2.8 `particleTweaks`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| suppressProjectileParticleTrails | bool | false | Hide projectile particle trails |

#### 2.9 `compassAndClockOptions`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableClock | bool | true | — | Clock display when holding a clock |
| enableCompass | bool | true | — | Compass display when holding a compass |
| compassStyle | enum | TRANSPARENT_WITH_INDICATOR | OPAQUE, TRANSPARENT, OPAQUE_WITH_INDICATOR, TRANSPARENT_WITH_INDICATOR | Compass rendering style |
| scale | double | 1.0 | 0.5–4 | Display scale |

#### 2.10 `mapOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableTreasureDistance | bool | true | Distance to treasure target on explorer maps |

#### 2.11 `weatherOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableDesertSandstorm | bool | true | Desert sandstorm dust + yellow tint |
| enableNetherDust | bool | false | Nether dust rain effect |
| enableBiomeFogColor | bool | true | Biome fog color tint (biomes.json fogColor) |

#### 2.12 `fogOptions`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableFogEffects | bool | true | — | Fog effects master switch |
| enableMorningFog | bool | true | — | Morning fog |
| enableBiomeFog | bool | true | — | Biome fog |
| enableWeatherFog | bool | true | — | Weather fog |
| enableBedrockFog | bool | true | — | More fog at bedrock layers |
| enableElevationHaze | bool | true | — | More haze at high elevation |
| morningFogStartHour | double | 5.0 | 0–24 | Morning fog start (hours of day) |
| morningFogPeakHour | double | 6.0 | 0–24 | Morning fog peak (hours of day) |
| morningFogEndHour | double | 8.0 | 0–24 | Morning fog end (hours of day) |
| morningFogDensity | double | 1.0 | 0.25–4 (slider) | Morning fog density |
| biomeFogDensity | double | 1.0 | 0–2 (slider) | Biome fog density (0 = off) |
| weatherFogDensity | double | 1.0 | 0.25–4 (slider) | Weather fog density |

#### 2.13 `speechBubbles`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableSpeechBubbles | bool | false | — | ☆ Chat bubbles above player heads |
| enableEntityChat | bool | false | — | ☆ Chat bubbles above villagers/mobs |
| speechBubbleDuration | double | 7.0 | 5–15 | Seconds a bubble stays |
| speechBubbleRange | int | 16 | 16–32 | Blocks a bubble is visible from |

#### 2.14 `auroraOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableAurora | bool | true | Aurora (northern lights) rendering |

#### 2.15 `otherOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| playRandomSoundOnStartup | bool | true | Random sound when loading finishes to main screen |

### 3. Per-sound config — `config/dsurround/soundconfig.json`

An array of per-sound-event entries (edited via the in-game sound config screen, but editable by hand):

```json
[
  { "soundEventId": "minecraft:entity.wither.death", "cull": true, "volumeScale": 10 },
  { "soundEventId": "minecraft:ambient.underwater.exit", "startup": true }
]
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| soundEventId | string | — | Sound event ID the entry applies to |
| volumeScale | int | 100 | 0–400 (%) — volume multiplier for that sound |
| block | bool | false | The sound never plays |
| cull | bool | false | The sound may be culled when the system trims inaudible sounds |
| startup | bool | false | Marker used by the sound diagnostics screen (not set by hand) |

### 4. Data files — `assets/dsurround/...` (resource-pack overridable)

All of these live in `assets/dsurround/` inside the jar. **A resource pack can replace any of them** by providing the same path (e.g. `assets/dsurround/dsconfigs/sound_factories.json`). Vanilla-format tag files follow standard tag merge semantics; the other JSON files are replaced whole. After changing them in-game, run `/dsreload` to reload without restarting.

| Path | Purpose |
| --- | --- |
| `dsconfigs/sound_factories.json` | Sound factories: what code requests → what actually plays |
| `dsconfigs/sound_mappings.json` | Remap vanilla sound events to DS footsteps/sounds |
| `dsconfigs/biomes.json` | Biome acoustics, selectors and fog colors |
| `dsconfigs/blocks.json` | Per-block effects (fire, bubbles, dust…) and ambient sounds |
| `dsconfigs/dimensions.json` | Per-dimension rendering parameters |
| `dsconfigs/variators.json` | Gait/footstep parameters per entity profile |
| `dsconfigs/tags/**` | Vanilla-format tag lists (blocks, items, fluids, biomes…) |
| `chat/<lang>.lang` | Entity speech-bubble lines |
| `sounds.json` | Sound-event → .ogg registrations (use with a sound resource pack) |

#### 4.1 `sound_factories.json` (array)
| Field | Type | Meaning |
| --- | --- | --- |
| location | string | Factory key code requests (e.g. `dsurround:toolbar.tool.equip`) |
| soundEvent | string | Actual SoundEvent played (must exist in `sounds.json`) |
| category | string | MC sound category (AMBIENT, PLAYER, …) — routes volume sliders |
| volume | number | Base volume multiplier |
| pitch | object | Optional `{"min":0.8,"max":1.2}` random pitch range |

> `location` ≠ `soundEvent` by design: the factory name is stable, the sound it plays can be re-targeted.

#### 4.2 `sound_mappings.json` (array)
Remaps an incoming vanilla sound event to a DS factory:
```json
{ "soundEvent": "minecraft:block.anvil.step",
  "rules": [ { "factory": "dsurround:footsteps.metalbox" } ] }
```

#### 4.3 `biomes.json` (array)
| Field | Type | Meaning |
| --- | --- | --- |
| biomeSelector | string | Expression over biome tags (see below) |
| _comment | string | Optional label |
| acoustics | array | `{"factory":"dsurround:biome.wind", "conditions":"weather.isRaining()"}` |
| fogColor | color | Optional biome fog tint (see `weatherOptions.enableBiomeFogColor`) |

The selector combines **biome tag names** from `dsconfigs/tags/worldgen/biome/*.json` with `&&` `||` `!` and parentheses, e.g. `(DESERT || BADLANDS) && !(WINDSWEPT || MOUNTAIN || LUSH)`.

#### 4.4 `blocks.json` (array)
| Field | Type | Meaning |
| --- | --- | --- |
| blocks | array | Block IDs / states / tags, e.g. `"minecraft:lava"`, `"minecraft:nether_wart[age=3]"`, `"#minecraft:ice"` |
| effects | array | `{"effect":"fire_jet"|"bubble_column"|"firefly"|"dust", "spawnChance":"0.005"}` — chance is per tick (string) |
| soundChance | string | Per-tick chance of a random ambient sound |
| acoustics | array | Ambient sounds to pick from (factory) |

#### 4.5 `dimensions.json` (array)
`{"dimId":"minecraft:the_nether","seaLevel":0,"cloudHeight":128,"alwaysOutside":true}` — per-dimension sky/cloud/outside parameters.

#### 4.6 `variators.json` (object keyed by profile)
Named gait profiles (default, player, playerSlow, child, quadruped, quadrupedSlow, skeleton, …). Fields: `stride`, `strideStair`, `speedToRun`, `landHardDistanceMin`, `playJump`, `quadruped`, `quadrupedMultiplier`, `footprintScale`, `volumeScale`, `distanceToCenter`.

#### 4.7 `tags/**` — vanilla tag JSON
Standard tag format (`replace`, `values` with `#`-refs and `required`). Namespaces used:
- `block/effects/*` — which blocks get which effects (fireflies, floor squeaks, footprints, steam, brush/straw step, watery step, leaves step, heat producers)
- `block/occlusion/*`, `block/reflectance/*` — sound occlusion / reverb material grades
- `entity_type/effects/*` — entities per effect (bow pull, brush step, frost breath, item swing, light steps, toolbar)
- `fluid/effects/*` — waterfall sources / ripples
- `item/effects/*`, `item/*` — item classes (axes, tools, bows, shields…) and bucket types
- `worldgen/biome/*` — biome tag lists used by `biomes.json` selectors

#### 4.8 `chat/<lang>.lang` — entity speech bubbles
Format (documented in the file header): `chat.<entity>.<index>=weight,text`. `villager.flee` is a special flee-line table; `$MINECRAFT$` plays a random vanilla splash text. The file name follows the client language (`en_us.lang`, `zh_cn.lang`).

### 5. Commands

| Command | Side | Notes |
| --- | --- | --- |
| `/dsreload` | Client | Reloads the data files (§4) without restarting. Registration can be disabled via `logging.registerCommands`. |
| `/bubble <text>` | Server | Shows `<text>` in a speech bubble above the sender for 30 s, to every DS client within 30 blocks (sender included). The message **never appears in chat**. Requires DS on the server; vanilla clients see nothing. |

---

## Part II — 中文说明

### 1. 概述

Dynamic Surroundings Rebirth 是**纯客户端**模组。所有设置都在**每个玩家自己的游戏目录**里，没有服务端强制。整合包作者把下面这些文件打进包里，玩家安装后即带你的默认设置。

三个定制层：

| 层 | 位置 | 谁改 |
| --- | --- | --- |
| 配置文件 | 游戏目录下 `config/dsurround/*.json` | 整合包作者（发预设）、玩家 |
| 游戏内 GUI | 模组选项界面（需 Cloth Config） | 玩家 |
| 数据文件 | jar 内 `assets/dsurround/...`，可被**资源包**覆盖 | 整合包作者、资源包作者 |

**文件位置**（相对游戏目录）：

```
config/dsurround/dsurround.json      主配置：所有功能开关和滑块
config/dsurround/soundconfig.json    单个声音事件的覆盖（屏蔽/剔除/音量）
```

**主配置加载机制**（整合包作者务必了解）：

- **缺键保持默认值**——你可以只放想改的项。
- 加载后会**回写完整文件**，新版新增的项自动补上。
- JSON 写错时模组会记错误日志并**回退默认值**（并重写文件）。发布前务必校验 JSON。
- 最小预设示例：

```json
{ "speechBubbles": { "enableSpeechBubbles": true },
  "soundOptions":  { "footstepVolume": 1.5 } }
```

游戏内 GUI（模组选项 → Dynamic Surroundings Rebirth，需 Cloth Config）改的是同一个文件。下面表格中：

- **★** = 需完全重启游戏
- **☆** = 下次进世界时生效
- （无标记）= 立即生效

### 2. 主配置 — `config/dsurround/dsurround.json`

#### 2.1 `logging`（日志）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableDebugLogging | 布尔 | false | 模组调试日志 |
| traceMask | 整数 | 0 | 调试跟踪位掩码 |
| enableModUpdateChatMessage | 布尔 | true | 有更新时聊天栏提示 |
| filteredTagView | 布尔 | true | 诊断界面过滤标签显示 |
| registerCommands | 布尔 | true | ★ 注册客户端指令（`/dsreload`） |

#### 2.2 `soundSystem`（声音系统）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| cullInterval | 整数 | 20 | 0–200（滑块） | 剔除扫描间隔（刻），0 关闭剔除 |
| enableSoundPruning | 布尔 | true | — | 取消玩家听不到的声音 |

#### 2.3 `enhancedSounds`（增强音效）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableEnhancedSounds | 布尔 | true | — | ★ 增强音效处理（混响、遮挡） |
| backgroundThreadWorkers | 整数 | 0 | 0–8（滑块） | ★ 后台线程数（0 = 内部默认） |
| enableMonoConversion | 布尔 | true | — | 立体声实时转单声道 |
| enableOcclusionProcessing | 布尔 | false | — | 方块后声音变闷 |
| reverbRays | 整数 | 32 | 16–64 | ★ 每个声音投射的射线数 |
| reverbBounces | 整数 | 4 | 2–8 | ★ 每条射线反射次数 |
| reverbRayTraceDistance | 整数 | 256 | 64–512 | ★ 射线总距离（格） |
| reverbIntensity | 双精度 | 1.0 | 0–2（滑块） | 混响强度（1.0 默认，0 关闭） |
| enableWaterSoundDamping | 布尔 | true | — | 穿过水的声音衰减 |
| waterSoundDamping | 双精度 | 0.95 | 0.1–1（滑块） | 每格水剩余音量比例（越低越轻） |
| waterSoundMuffle | 双精度 | 0.7 | 0.1–1（滑块） | 每格水高频削减（越低越闷） |

#### 2.4 `soundOptions`（声音选项）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| ambientVolumeScaling | 整数 | 100 | 0–400（滑块） | 环境音量百分比倍率 |
| replaceThunderSounds | 布尔 | true | — | 使用 DS 的雷声 |
| allowScarySounds | 布尔 | true | — | 允许播放"恐怖"声音 |
| playBiomeMusicWhileCreative | 布尔 | false | — | 创造模式下播放群系背景音乐 |
| displayToastMessagesForMusic | 布尔 | true | — | 音乐致谢弹窗 |
| remapSounds | 布尔 | true | — | 播放时声音重映射 |
| enableBackgroundThunder | 布尔 | true | — | ☆ 暴风雨时远处雷声 |
| enableBiomeSounds | 布尔 | true | — | 群系与村庄环境音 |
| footstepVolume | 双精度 | 1.0 | 0–2（滑块） | 脚步音量倍率 |
| biomeVolume | 双精度 | 1.0 | 0–2（滑块） | 群系环境音量倍率 |
| playerEffectVolume | 双精度 | 1.0 | 0–2（滑块） | 玩家效果音量（跳跃/心跳/饥饿/制作/快捷栏） |

#### 2.5 `blockEffects`（方块效果）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| blockEffectRange | 整数 | 32 | 16–64（滑块） | 扫描方块效果的半径（格） |
| steamColumnEnabled | 布尔 | true | — | 岩浆旁蒸汽柱 |
| flameJetEnabled | 布尔 | true | — | 岩浆上方火焰喷射 |
| bubbleColumnEnabled | 布尔 | true | — | 水下气泡柱 |
| firefliesEnabled | 布尔 | true | — | 萤火虫 |
| dustJetEnabled | 布尔 | true | — | 悬空方块落尘 |
| fallingBlockDustEnabled | 布尔 | true | — | 沙/砾落地扬尘 |
| waterfallsEnabled | 布尔 | true | — | 流水瀑布效果 |
| enableWaterfallSounds | 布尔 | true | — | 瀑布声 |
| enableWaterfallParticles | 布尔 | true | — | 瀑布粒子 |
| waterRippleStyle | 枚举 | PIXELATED_CIRCLE | NONE, PIXELATED_CIRCLE | 水滴落水面涟漪样式 |
| enableMagmaSteam | 布尔 | true | — | ☆ 雨打岩浆/地狱岩蒸汽 |

#### 2.6 `entityEffects`（实体效果）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| entityEffectRange | 整数 | 24 | 16–64（滑块） | 实体效果最大半径（格） |
| enableBowPull | 布尔 | true | — | ☆ 拉弓声 |
| enableBreathEffect | 布尔 | true | — | ☆ 寒冷群系/水下哈气 |
| enablePlayerToolbarEffect | 布尔 | true | — | ☆ 玩家快捷栏音效 |
| enableToolbarBlockSounds | 布尔 | false | — | ☆ 快捷栏方块音效 |
| enableSwingEffect | 布尔 | true | — | ☆ 物品挥舞声（玩家与生物） |
| enableBrushStepEffect | 布尔 | true | — | ☆ 穿过茂密灌木声 |
| enablePlayerHeartbeatSound | 布尔 | true | — | ☆ 低血量心跳 |
| playerHurtThreshold | 双精度 | 0.25 | 0–1 | 低于该血量比例触发心跳（0 关闭） |
| enablePlayerHungerSound | 布尔 | true | — | ☆ 饥饿时肚子叫 |
| playerHungerThreshold | 整数 | 8 | 0–20 | 低于该饥饿值触发（0 关闭） |
| enablePlayerJumpSound | 布尔 | true | — | ☆ 起跳声 |
| enablePlayerLandSound | 布尔 | true | — | ☆ 落地声 |
| enableFootstepSounds | 布尔 | true | — | ☆ 脚步系统（材质行走/奔跑） |
| enableCraftingSound | 布尔 | true | — | ☆ 合成声 |
| suppressPotionParticles | 布尔 | false | — | ☆ 隐藏玩家药水粒子 |
| enableFootprints | 布尔 | true | — | ☆ 行走脚印 |
| footprintStyle | 枚举 | LOWRES_SQUARE | SHOE, SQUARE, HORSESHOE, BIRD, PAW, SQUARE_SOLID, LOWRES_SQUARE | 脚印样式 |
| showCritWords | 布尔 | true | — | ☆ 暴击漫画字 |
| showDamageNumbers | 布尔 | true | — | ☆ 伤害/治疗飘字 |

#### 2.7 `footstepAccents`（脚步点缀）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableAccents | 布尔 | true | 脚步点缀总开关 |
| enableArmorAccents | 布尔 | true | 盔甲点缀 |
| enableWetSurfaceAccents | 布尔 | true | 雨天/含水方块点缀 |
| enableFloorSqueaks | 布尔 | true | 吱呀方块点缀 |

#### 2.8 `particleTweaks`（粒子微调）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| suppressProjectileParticleTrails | 布尔 | false | 隐藏弹射物粒子尾迹 |

#### 2.9 `compassAndClockOptions`（指南针与时钟）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableClock | 布尔 | true | — | 手持时钟显示时间 |
| enableCompass | 布尔 | true | — | 手持指南针显示方位 |
| compassStyle | 枚举 | TRANSPARENT_WITH_INDICATOR | OPAQUE, TRANSPARENT, OPAQUE_WITH_INDICATOR, TRANSPARENT_WITH_INDICATOR | 指南针渲染样式 |
| scale | 双精度 | 1.0 | 0.5–4 | 显示缩放 |

#### 2.10 `mapOptions`（地图）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableTreasureDistance | 布尔 | true | 藏宝图显示目标距离 |

#### 2.11 `weatherOptions`（天气效果）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableDesertSandstorm | 布尔 | true | 沙漠沙尘暴 + 黄幕 |
| enableNetherDust | 布尔 | false | 下界尘埃雨 |
| enableBiomeFogColor | 布尔 | true | 群系雾色着色（biomes.json 的 fogColor） |

#### 2.12 `fogOptions`（雾效）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableFogEffects | 布尔 | true | — | 雾效总开关 |
| enableMorningFog | 布尔 | true | — | 晨雾 |
| enableBiomeFog | 布尔 | true | — | 群系雾 |
| enableWeatherFog | 布尔 | true | — | 天气雾 |
| enableBedrockFog | 布尔 | true | — | 基岩层加重雾 |
| enableElevationHaze | 布尔 | true | — | 高海拔加重霾 |
| morningFogStartHour | 双精度 | 5.0 | 0–24 | 晨雾开始时刻（小时） |
| morningFogPeakHour | 双精度 | 6.0 | 0–24 | 晨雾峰值时刻 |
| morningFogEndHour | 双精度 | 8.0 | 0–24 | 晨雾结束时刻 |
| morningFogDensity | 双精度 | 1.0 | 0.25–4（滑块） | 晨雾密度 |
| biomeFogDensity | 双精度 | 1.0 | 0–2（滑块） | 群系雾密度（0 关闭） |
| weatherFogDensity | 双精度 | 1.0 | 0.25–4（滑块） | 天气雾密度 |

#### 2.13 `speechBubbles`（聊天气泡）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableSpeechBubbles | 布尔 | false | — | ☆ 玩家头顶聊天气泡 |
| enableEntityChat | 布尔 | false | — | ☆ 村民/生物头顶气泡 |
| speechBubbleDuration | 双精度 | 7.0 | 5–15 | 气泡显示秒数 |
| speechBubbleRange | 整数 | 16 | 16–32 | 气泡可见距离（格） |

#### 2.14 `auroraOptions`（极光）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableAurora | 布尔 | true | 极光渲染 |

#### 2.15 `otherOptions`（其它）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| playRandomSoundOnStartup | 布尔 | true | 加载完成进入主界面时播放随机音 |

### 3. 单音效配置 — `config/dsurround/soundconfig.json`

按声音事件的数组（游戏内声音配置界面可改，也可手改）：

```json
[
  { "soundEventId": "minecraft:entity.wither.death", "cull": true, "volumeScale": 10 },
  { "soundEventId": "minecraft:ambient.underwater.exit", "startup": true }
]
```

| 字段 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| soundEventId | 字符串 | — | 应用到的声音事件 ID |
| volumeScale | 整数 | 100 | 0–400（%）音量倍率 |
| block | 布尔 | false | 该声音永不播放 |
| cull | 布尔 | false | 系统剔除听不到的声音时，该声音可被剔除 |
| startup | 布尔 | false | 声音诊断界面使用的标记（无需手改） |

### 4. 数据文件 — `assets/dsurround/...`（可被资源包覆盖）

这些都在 jar 内 `assets/dsurround/` 下。**资源包提供相同路径即可覆盖**（例如 `assets/dsurround/dsconfigs/sound_factories.json`）。原版 tag 格式文件遵循标准 tag 合并语义，其它 JSON 整体替换。改完游戏内 `/dsreload` 热重载。

| 路径 | 用途 |
| --- | --- |
| `dsconfigs/sound_factories.json` | 声音工厂：代码请求的 key → 实际播放的声音 |
| `dsconfigs/sound_mappings.json` | 把原版声音事件重映射到 DS 脚步/音效 |
| `dsconfigs/biomes.json` | 群系声学、选择器和雾色 |
| `dsconfigs/blocks.json` | 每个方块的效果（火、气泡、尘…）和环境音 |
| `dsconfigs/dimensions.json` | 每个维度的渲染参数 |
| `dsconfigs/variators.json` | 各实体档案的步态/脚步参数 |
| `dsconfigs/tags/**` | 原版格式 tag 列表（方块、物品、流体、群系…） |
| `chat/<lang>.lang` | 生物气泡台词 |
| `sounds.json` | 声音事件 → .ogg 注册（配合声音资源包使用） |

#### 4.1 `sound_factories.json`（数组）
| 字段 | 类型 | 含义 |
| --- | --- | --- |
| location | 字符串 | 代码请求的工厂 key（如 `dsurround:toolbar.tool.equip`） |
| soundEvent | 字符串 | 实际播放的声音事件（必须存在于 `sounds.json`） |
| category | 字符串 | MC 声音类别（AMBIENT、PLAYER…），决定走哪个音量滑块 |
| volume | 数值 | 基础音量倍率 |
| pitch | 对象 | 可选 `{"min":0.8,"max":1.2}` 随机音调区间 |

> 设计上 `location` ≠ `soundEvent`：工厂名稳定，实际播放的声音可重定向。

#### 4.2 `sound_mappings.json`（数组）
把传入的原版声音事件重映射到 DS 工厂：
```json
{ "soundEvent": "minecraft:block.anvil.step",
  "rules": [ { "factory": "dsurround:footsteps.metalbox" } ] }
```

#### 4.3 `biomes.json`（数组）
| 字段 | 类型 | 含义 |
| --- | --- | --- |
| biomeSelector | 字符串 | 基于群系 tag 的表达式（见下） |
| _comment | 字符串 | 可选标签 |
| acoustics | 数组 | `{"factory":"dsurround:biome.wind","conditions":"weather.isRaining()"}` |
| fogColor | 颜色 | 可选群系雾色着色（见 `weatherOptions.enableBiomeFogColor`） |

选择器用 `dsconfigs/tags/worldgen/biome/*.json` 里的**群系 tag 名**，配合 `&&` `||` `!` 和括号，例如 `(DESERT || BADLANDS) && !(WINDSWEPT || MOUNTAIN || LUSH)`。

#### 4.4 `blocks.json`（数组）
| 字段 | 类型 | 含义 |
| --- | --- | --- |
| blocks | 数组 | 方块 ID / 状态 / tag，如 `"minecraft:lava"`、`"minecraft:nether_wart[age=3]"`、`"#minecraft:ice"` |
| effects | 数组 | `{"effect":"fire_jet"|"bubble_column"|"firefly"|"dust","spawnChance":"0.005"}`——概率为每刻（字符串） |
| soundChance | 字符串 | 每刻随机环境音概率 |
| acoustics | 数组 | 待选环境音（factory） |

#### 4.5 `dimensions.json`（数组）
`{"dimId":"minecraft:the_nether","seaLevel":0,"cloudHeight":128,"alwaysOutside":true}` —— 每个维度的天空/云/室外参数。

#### 4.6 `variators.json`（按档案键的对象）
命名步态档案（default、player、playerSlow、child、quadruped、quadrupedSlow、skeleton…）。字段：`stride`、`strideStair`、`speedToRun`、`landHardDistanceMin`、`playJump`、`quadruped`、`quadrupedMultiplier`、`footprintScale`、`volumeScale`、`distanceToCenter`。

#### 4.7 `tags/**` —— 原版 tag JSON
标准 tag 格式（`replace`、`values` 带 `#` 引用和 `required`）。用到的命名空间：
- `block/effects/*` —— 哪些方块有哪种效果（萤火虫、地板吱呀、脚印、蒸汽、灌木/稻草步、水步、落叶步、产热方块）
- `block/occlusion/*`、`block/reflectance/*` —— 声音遮挡/混响材质等级
- `entity_type/effects/*` —— 每种效果的实体（拉弓、灌木步、冰霜哈气、挥物、轻步、快捷栏）
- `fluid/effects/*` —— 瀑布源/涟漪
- `item/effects/*`、`item/*` —— 物品类别（斧、工具、弓、盾…）与桶类型
- `worldgen/biome/*` —— `biomes.json` 选择器使用的群系 tag 列表

#### 4.8 `chat/<lang>.lang` —— 生物气泡台词
格式（文件头已注明）：`chat.<实体>.<序号>=权重,文本`。`villager.flee` 是特殊的逃跑台词表；`$MINECRAFT$` 播放随机原版闪烁标语。文件名跟随客户端语言（`en_us.lang`、`zh_cn.lang`）。

### 5. 指令

| 指令 | 端 | 说明 |
| --- | --- | --- |
| `/dsreload` | 客户端 | 无需重启重载数据文件（§4）。可通过 `logging.registerCommands` 关闭注册。 |
| `/bubble <文本>` | 服务端 | 在发送者头顶显示 `<文本>` 气泡 30 秒，30 格内所有装了 DS 的客户端可见（含自己）。消息**不进聊天栏**。需服务器装 DS；原版客户端看不到。 |
