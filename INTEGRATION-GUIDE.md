# Dynamic Surroundings Rebirth — 整合包 / 数据驱动指南

本 mod 继承原版 Dynamic Surroundings 的**数据驱动**设计：生物群系、方块、物品、实体、维度、
声音映射都可以通过 JSON 配置，无需改代码。本文档面向整合包作者。

## 1. 配置文件放在哪里（三处加载来源，优先级从低到高）

1. **模组 jar 内**：`assets/<命名空间>/dsconfigs/*.json`（mod 自带默认）。
2. **磁盘配置目录**（推荐整合包用）：`.minecraft/dsurround/configs/<命名空间>/*.json`
   —— 直接丢文件即可，不用改 jar，会**叠加/覆盖** jar 内同名配置。
3. **资源包**：tags 定义（`data/<命名空间>/tags/**`）与资源（`assets/<命名空间>/...`）。

> 命名空间用 `minecraft` 或你自己的 mod id。磁盘目录示例：
> `.minecraft/dsurround/configs/mypack/biomes.json`

## 2. 数据驱动文件一览

| 文件 | 作用 |
|------|------|
| `biomes.json` | 群系声音 + **雾颜色/浓度** |
| `blocks.json` | 方块的遮挡/反射/音效/效果规则 |
| `sound_mappings.json` | 原版脚步声 → 材质脚步重映射 |
| `sound_factories.json` | 音效工厂（location → 声音事件 + 音量/音调） |
| `item_sounds.json` | **物品/tag → 切换/挥舞音效覆盖**（本 port 新增） |
| `variators.json` | 声音变调器 |
| `dimensions.json` | 维度信息 |
| `tags/item/effects/*` | 物品分类（剑/斧/工具/弓/弩/盾/药水/书/盔甲材质） |
| `tags/block/effects|occlusion|reflectance/*` | 方块效果/遮挡/反射 |
| `tags/entity_type/effects/*` | 实体效果（挥动/弓/霜息/工具栏等） |
| `tags/fluid/effects/*` | 流体效果（涟漪/瀑布） |
| `tags/worldgen/biome/*` | 群系标签（供 biomes.json 选择器用） |

## 3. 常见整合包需求示例

### 3.1 逐群系雾浓度（0 = 关）

在 `biomes.json` 加一条规则（`fogDensity` 取值 `none/light/normal/medium/heavy`，
`none` = 关；`fogColor` 是十六进制颜色）：

```json
[
  { "biomeSelector": "SWAMP && !FOREST", "fogColor": "#406040", "fogDensity": "medium" },
  { "biomeSelector": "biome.id == 'minecraft:dark_forest'", "fogDensity": "heavy" },
  { "biomeSelector": "biome.id == 'minecraft:plains'", "fogDensity": "none" }
]
```

`biomeSelector` 支持脚本表达式，可用标签（`SWAMP`/`FOREST`/`JUNGLE`/`COLD`/`SNOWY` 等，
见 `tags/worldgen/biome/*`）和 `biome.id`/`biome.temperature`/`biome.getRainfall()` 等。

### 3.2 给自定义武器加专属切换/挥舞音效

需要三步（前两步是 MC 自带机制，第三步是 mod 的数据驱动覆盖）：

**第 1 步**：在资源包 `assets/<命名空间>/sounds.json` 注册声音事件：
```json
{ "my_hammer_swing": { "sounds": ["mypack:hammer_swing"] }, "my_hammer_equip": { "sounds": ["mypack:hammer_equip"] } }
```

**第 2 步**：在 `sound_factories.json` 定义音效工厂（location → 声音事件 + 音量/音调）：
```json
[
  { "location": "mypack:hammer.equip", "soundEvent": "mypack:my_hammer_equip", "category": "PLAYER", "volume": 0.5, "pitch": { "min": 0.8, "max": 1.2 } },
  { "location": "mypack:hammer.swing", "soundEvent": "mypack:my_hammer_swing", "category": "PLAYER", "volume": 0.5, "pitch": { "min": 0.8, "max": 1.2 } }
]
```

**第 3 步**：在 `item_sounds.json` 把物品（或 tag）映射到上面的工厂 location：
```json
[
  { "items": ["mypack:steel_hammer"], "equip": "mypack:hammer.equip", "swing": "mypack:hammer.swing" },
  { "items": ["#minecraft:axes"], "equip": "mypack:axe.equip" }
]
```

`items` 支持物品 ID（`mypack:steel_hammer`）或物品 tag（`#minecraft:axes`）。
`equip`/`swing` 可省略其一（只覆盖其中一个动作）。规则按顺序，第一条命中生效。

> 简单场景：只想让某物品"像剑一样响"，直接把它加进 `tags/item/effects/swords.json` 即可
> （见 §3.3），无需 item_sounds.json。

### 3.3 把物品归类为已有类别（复用现有切换/挥舞音）

在磁盘 `tags/item/effects/` 下放同名 tag 文件（会与 jar 内合并）。例如让 `mypack:katana` 像剑一样响：
```json
// .minecraft/dsurround/configs/mypack/tags/item/effects/swords.json
{ "values": ["mypack:katana", "#minecraft:swords"] }
```
类别有：`swords/axes/tools/bows/crossbows/shields/potions/books`，以及盔甲材质
`armor/leather|chain|iron|gold|diamond|netherite`（盔甲切换音 = 材质 accent）。

### 3.4 自定义脚步材质音效

在 `sound_mappings.json` 里把原版方块脚步声映射到 `sound_factories.json` 里的工厂：
```json
[
  { "soundEvent": "minecraft:block.amethyst_block.step", "rules": [ { "blocks": ["mypack:my_block"], "factory": "dsurround:footsteps.marble" } ] }
]
```

## 4. 雾的可调参数（config，非数据驱动）

在 `.minecraft/config/dsurround/dsurround.json`（或模组配置界面）的 `fogOptions`：
- `morningFogStartHour` / `morningFogPeakHour` / `morningFogEndHour`（晨雾时间，小时制 0-24）
- `morningFogDensity`（晨雾浓度，1.0=默认，越高越浓）
- `biomeFogDensity`（群系雾浓度倍率，0=关）
- `weatherFogDensity`（雨雾浓度）
- `enableMorningFog` / `enableBiomeFog` / `enableWeatherFog` 等开关

## 5. 注意

- 所有 `items`/`blocks`/`biomeSelector` 里的 ID 建议用**全限定名**（`namespace:path`）。
- tag 用 `#namespace:path` 前缀。
- 磁盘配置目录的文件名和结构必须与 jar 内一致（如 `biomes.json`、`tags/item/effects/swords.json`）。
