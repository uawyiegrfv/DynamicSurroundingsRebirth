# Customisation Guide — 自定义指南

> **What this is / 这是什么** — a task-oriented "I want X, where do I edit it" guide.
> 任务导向的"我想改 X，该改哪里"指南。
>
> **What this is not / 这不是什么** — not a field-by-field reference. Every file and every field is listed in
> [`CONFIGURATION.md`](../CONFIGURATION.md). This guide links there instead of repeating it.
> 不是逐字段参考。所有文件和字段都在 [`CONFIGURATION.md`](../CONFIGURATION.md) 里，本指南只指路，不重复。
>
> 1.20.1 (Forge) · 1.21.1 (NeoForge) · 26.1 (NeoForge) — one guide, three ports. Version-only
> differences are flagged **`[1.20.1]`** / **`[26.1]`**; everything unmarked works identically on all three.
> 三版通用。仅版本差异处标注 **`[1.20.1]`** / **`[26.1]`**；未标注的写法三版完全一致。

---

## Part I — English

### 0. Decide the *form* first

Every customisation in this mod is one of four forms. Picking the form is the whole decision; after
that it is just editing a JSON file.

| Form | Use it for | Applies |
| --- | --- | --- |
| **A. Data file** | anything about *content*: which block sounds like what, a biome's fog, an item's swing sound | `/dsreload`, instantly |
| **B. Config file / GUI** | anything about *behaviour and balance*: on/off switches, volume sliders, fog multipliers, reverb quality | immediately, or next world load |
| **C. Tag file** | *classifying* things: "treat these items as swords", "these blocks leave footprints" | `/dsreload` |
| **D. Java** | adding a **new** category that does not exist (a new item class, a new effect type) | rebuild |

**Rule of thumb: if a switch for it already exists in the config GUI, that is form B. If it exists but
the *content* is wrong, that is form A.** Only form D requires source code — and it is needed far less
often than people assume: nearly every "I want a new sound" request is A or C.

#### A note on the two forms of a data file

The same content can be written as **its own file** (`biomes.json`) or as a **section of one aggregate
file** (`dsurround.json`). Both are decoded by the same codec, so an entry means exactly the same in
either place.

| You want to… | Write |
| --- | --- |
| patch one or two things | the per-type file — smallest thing to review |
| describe a whole mod (blocks + biomes + sounds) | one `dsurround.json` with a section per type |
| keep it out of any jar | either, under the disk config folder |

#### Where a data file can live (all five places)

| # | Location | Who ships it |
| --- | --- | --- |
| 1 | `<mod>.jar` → `assets/<any namespace>/dsconfigs/<file>.json` | mod authors |
| 2 | **resource pack** → `assets/<any namespace>/dsconfigs/<file>.json` | modpack / resource-pack authors |
| 3 | **disk** → `config/dsurround/configs/<namespace>/<file>.json` | players, modpack authors |
| 4 | aggregate `dsurround.json` in any of the above, as a section | modpack authors |
| 5 | real datapack `data/<namespace>/tags/**` | **loaded mod jars only** — a resource pack cannot supply it |

- ⚠️ **In form 3 the directory name must be a loaded mod id.** `<namespace>` is the only part of the
  path checked, and a folder that is not a mod id is **silently ignored** — no warning, no log line.
  This is the single most common reason a disk override "does nothing".
- Tags also have a pack-friendly path: `assets/<namespace>/dsconfigs/tags/<registry>/<tag>.json`
  (works from a resource pack, unlike form 5).
- **Load order**: jars and resource packs first, the disk folder **last** — so disk is the reliable
  place to override a shipped value.

#### Merge rules — and the one thing you cannot do

| File | How copies combine |
| --- | --- |
| `tags/**` | **pure union** — `replace` is read but never used |
| `sound_factories.json`, `variators.json` | same key: **later wins** (disk is last, so disk wins) |
| `sound_mappings.json` | rules merge; a new specific rule is inserted **ahead of** the catch-all |
| `biomes.json`, `blocks.json`, `dimensions.json` | appended; later wins per scalar field, lists accumulate; `biomes.json` also sorts by `priority` |
| `critwords.json` | concatenated |
| `item_sounds.json` | **first match wins** |

> ⚠️ **No pack can remove a shipped entry.** To silence something, write a *more specific* rule —
> e.g. point a block at the reserved silent factory `dsurround:footsteps.none`.

**Prefer tags over block/item id lists.** A tag keeps working when a mod adds blocks later; a list of
ids does not.

---

### 1. Footsteps

#### 1.1 Which sound a block makes — `sound_mappings.json`

This file groups rules by the **vanilla step sound event** the block reports, then maps blocks to a DS
material. One entry per event; rules are walked in order and **the first match wins**.

```jsonc
// assets/<your-ns>/dsconfigs/sound_mappings.json
[
  { "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["yourmod:marble", "#yourmod:marble_blocks"],
        "factory": "dsurround:footsteps.marble",
        "accent":  ["dsurround:footstep_accent/sand"] },
      { "factory": "dsurround:footsteps.stone" }        // catch-all, MUST stay last
    ] }
]
```

- Write your rule wherever the block's **actual** vanilla step event is. Put it under the wrong event
  and it never fires.
- The **catch-all** is the rule with an empty `blocks` list. It must be last; anything after it is
  unreachable.
- **Don't copy DS's whole file** — ship only your rules. They are merged in ahead of the catch-all.
- A block with no rule is **not silenced**: it keeps its own vanilla step sound, processed through DS's
  footstep pipeline. Only `dsurround:footsteps.none` silences it.
- Modded blocks that look like a vanilla material (`*_sandstone`, `*_marble`, `*_copper`…) are guessed
  by **name** when no rule matches. That inference never overrides an explicit rule.

The 102 materials available are listed in [`FOOTSTEPS.md`](FOOTSTEPS.md).

#### 1.2 Landing, stopping and jumping — the `land` / `wander` / `jump` keys

Cadence and surface are separate: **which** recording plays comes from the *material* your block
resolved to, not from the block. So retuning a landing means editing one factory entry in
`sound_factories.json`, in its `land` block:

```jsonc
{ "location": "dsurround:footsteps.concrete",
  "soundEvent": "dsurround:footsteps.concrete",
  "land": {
    "primary":   "dsurround:footsteps.concrete_run",  // required
    "secondary": "dsurround:footsteps.stone",         // optional 2nd layer
    "echo":      "dsurround:footsteps.stone_run",     // optional, a couple of ticks later
    "secondaryScale": 0.5, "echoVolume": 1.0,
    "echoDelayMinTicks": 1, "echoDelayMaxTicks": 2
  },
  "wander": "dsurround:footsteps.marble_wander",      // stop-scuff override
  "jump":   "dsurround:footsteps.dirt_wander" }       // take-off override (falls back to wander)
```

A landing is **layered** (primary + quieter secondary + a delayed echo, summed in the mixer) — that
summation is the only way a landing can read heavier than a footstep, because one voice's gain is
clamped. 30 of the 185 shipped factories carry a `land` block; the rest fall back to the material's own
run/land thud plus an echo.

#### 1.3 Volume, pitch and muting

`volume` and `pitch` live on the **factory**, so they are per-material, not per-block. To mute one
block without touching its material, point that block's rule at the silent factory:

```jsonc
{ "blocks": ["yourmod:silent_tiles"], "factory": "dsurround:footsteps.none" }
```

Global footstep loudness is a config slider (`soundOptions.footstepVolume`, 0–2); **0 % also restores
the vanilla footstep sounds**.

#### 1.4 Footprints

Tag-driven, no factory needed: add a block to `tags/block/effects/footprintable.json`.

```jsonc
// assets/<your-ns>/dsconfigs/tags/block/effects/footprintable.json
{ "values": ["yourmod:snow_bricks"] }
```

Switches live in the config: `entityEffects.enableFootprints` (players),
`entityEffects.enableCreatureFootprints` (mobs), `entityEffects.footprintStyle`. Mob footprint size and
spacing come from the gait profile in `variators.json` / `entity_variators.json`
(`footprintScale`, `stride`, `volumeScale`, `hasFootprint`).

#### 1.5 Walking through grass and crops

Three tags decide it: `tags/block/effects/brush_step.json`, `straw_step.json`, `crop_step.json`.
`crop_step` is age-aware, so immature crops do not rustle the same way. Add your plant to the tag —
again, no factory needed.

#### 1.6 Armour footsteps (the metallic/leathery accents when you walk)

Each armour **material** is a tag listing the four vanilla pieces:

```jsonc
// assets/<your-ns>/dsconfigs/tags/item/effects/armor/iron.json
{ "values": ["yourmod:steel_helmet", "yourmod:steel_chestplate",
             "yourmod:steel_leggings", "yourmod:steel_boots"] }
```

| Material | DS sound |
| --- | --- |
| `leather` | soft leather |
| `chain` | chain rattle |
| `iron` | iron clink |
| `gold` | soft metallic |
| `diamond` | hard metallic |
| `netherite` | heavy metallic |
| **`slimey`** | **shipped empty — your extension point** |

Rules that surprise people:

- The sound comes from the **leggings**; if the entity wears no leggings it falls back to the
  **chestplate**. Boots use a separate accent.
- Sprinting takes a `_run` variant of the same accent.
- `slimey` is a real, empty tag of its own. Put slime-like armour there — do not overload `leather`.
- The accent is per **material tag**, so a modded armour that is "iron-ish" must be added to
  `armor/iron.json`. Alternatively switch on `footstepAccents.inferArmorClass` to let DS guess the
  material from the item's defence values — convenient, but a guess.
- Master switch: `footstepAccents.enableArmorAccents`.
- **`[1.20.1]` / `[1.21.1]` / `[26.1]`** the *guess* uses three different formulas with three
  different thresholds, so the same modded armour can be classified differently on different ports.
  **Tagging it explicitly is the portable answer.**

---

### 2. The held item: hotbar-select and swing sounds

#### 2.1 The four steps (first hit wins)

| # | Step | Granularity |
| --- | --- | --- |
| 1 | **`item_sounds.json`** — item id or tag → factory | one item, or one tag |
| 2 | override `dsurround:toolbar.<class>.swing` / `.equip` in `sound_factories.json` | a whole class |
| 3 | add the item to `tags/item/effects/<class>.json` | join an existing class |
| 4 | a brand-new class | **needs Java** — the class list is an enum |

**"My weapon's swing sound is wrong" almost always means step 1, and step 1 exists precisely so you
never have to touch the mod.** Nothing in the item's tags needs to change.

#### 2.2 Per-item override — `item_sounds.json`

```jsonc
// assets/<your-ns>/dsconfigs/item_sounds.json
[
  { "items": ["mymod:katana"],   "swing": "mypack:katana.swing" },
  { "items": ["#mymod:katanas"], "equip": "mypack:katana.equip" }
]
```

`items` is required; every id must be **fully qualified** (a bare name with no `:` is rejected).
`swing` and `equip` are independently optional. First match wins.

> ⚠️ **The factory must already exist.** Unlike `biomes.json`/`blocks.json`, this file does **not**
> fall back to "treat the location as a sound event". A `swing` naming an undefined factory is treated
> as absent and the item **silently** keeps its class default. Define it in `sound_factories.json`
> first, and register the event in `sounds.json`.

Start the event path with `item.` if you want it to follow the in-game player-volume slider — that
check is on the event **path prefix** (`item.`, `toolbar.`, `player.`), not on the sound category.

#### 2.3 Reuse a whole class instead (simplest)

To make an item sound "like a sword", just join the class — no factories, no sounds.json:

```jsonc
// assets/<your-ns>/dsconfigs/tags/item/effects/swords.json
{ "values": ["mypack:katana"] }
```

Existing classes: `swords` `axes` `tools` `bows` `crossbows` `shields` `potions` `books`, plus the
armour material tags above.

---

### 3. Fog

Fog is deliberately split: **the colour and the per-biome density are data, the global strength and
timing are config.**

#### 3.1 Colour and density per biome — `biomes.json`

```jsonc
[
  { "biomeSelector": "SWAMP && !FOREST", "fogColor": "#406040", "fogDensity": "medium" },
  { "biomeSelector": "biome.id == 'minecraft:dark_forest'", "fogDensity": "heavy", "priority": 150 },
  { "biomeSelector": "biome.id == 'minecraft:plains'", "fogDensity": "none" }
]
```

- `fogDensity`: `none` / `light` / `normal` / `medium` / `heavy`. `none` turns it off.
- `fogColor`: `#RRGGBB`. 5 biomes ship with a colour.
- `biomeSelector` is a small expression language over **biome tag names** (`SWAMP`, `FOREST`,
  `COLD`, `SNOWY`, `LUSH`, … — full list in `tags/worldgen/biome/*`) combined with `&&` `||` `!` and
  parentheses. It also reads `biome.id`, `biome.temperature`, `biome.getRainfall()`.
- Rules apply in ascending `priority`; later wins per field. DS's own highest priority is **100**, so
  use a higher number to make your rule stick.
- `dustColor` tints the dust effect (independent of fog).

**Going back to vanilla fog** needs the two reset flags, because a rule can otherwise only *add*:

| Flag | Effect |
| --- | --- |
| `resetFogColor: true` | clears a colour set by an earlier rule |
| `clearTraits: true` | drops every auto-detected trait first, so `traits` is the whole answer |
| `clearSounds: true` | drops accumulated loop / mood / additional sounds |

#### 3.2 Global fog — config `fogOptions`

`enableBiomeFog`, `biomeFogDensity` (0–2 multiplier), `morningFog*` (start/peak/end hour, density),
`enableWeatherFog`, `weatherFogDensity`, and `weatherOptions.enableBiomeFogColor` to use the
per-biome colour at all. Full table in [`CONFIGURATION.md`](../CONFIGURATION.md) §2.

---

### 4. Biome ambience

`biomes.json` again — each rule can attach ambient sounds, change the ambient *chances*, and set
traits.

```jsonc
{ "biomeSelector": "JUNGLE",
  "acoustics": [
    { "factory": "dsurround:biome.jungle.day", "weight": 10, "type": "loop" },
    { "factory": "dsurround:biome.bird",       "weight": 4,  "type": "mood",
      "conditions": "!weather.isRaining()" }
  ],
  "additionalSoundChance": "0.02",
  "moodSoundChance": "0.01" }
```

| `type` | Behaviour |
| --- | --- |
| `loop` | **default** — the background bed, picked by `weight` |
| `mood` | occasional, individual calls |
| `addition` | extra layered ambience |
| `music` | biome music |

- `conditions` is a script expression (`weather.isRaining()`, `biome.temperature`…). Omit it and the
  sound is always eligible.
- `traits` (the plain list, not `acoustics`) attaches DS's own trait vocabulary —
  `FOREST` `SWAMP` `COLD` `SNOWY` `DESERT` `OCEAN` `MOUNTAIN` `LUSH` `SPOOKY` `MAGICAL` `DEAD`
  `NETHER` `UNDERGROUND` … This is what the rest of the mod keys off; if your modded biome is
  misdetected, `clearTraits: true` plus an explicit list is the fix.
- Ambient **volume** is a config slider: `soundOptions.biomeVolume` (0–2) and `ambientVolumeScaling`.
- Master switches: `soundOptions.enableBiomeSounds`, `playBiomeMusicWhileCreative`,
  `allowScarySounds`. Per-dimension there is `playBiomeSounds` in `dimensions.json`.

---

### 5. Everything else, in one table

| I want to… | Form | Edit |
| --- | --- | --- |
| Change what a block's effects/particles are | A | `blocks.json` — `blocks`, `effects[]`, `soundChance`, `acoustics[]`, `clearSounds` |
| Give a block ambient sound | A | `blocks.json` — `acoustics[]` + `soundChance` |
| Change a dimension's sea level / biome sounds | A | `dimensions.json` |
| Change a mob's gait, stride or footprint spacing | A | `variators.json`, `entity_variators.json` |
| Change the comic words on a critical hit | A | `critwords.json` — see the shape warning below |
| Change the lines mobs say in speech bubbles | A | `assets/dsurround/chat/<lang>.lang` — `chat.<entity>.<index>=weight,text` |
| Change damage/heal/crit number text | B | `popoffNumbers` (only `sizePercent` is in the GUI; the rest are hand-edit only) |
| Turn a whole feature on or off | B | the matching config section |
| Retune reverb / occlusion / diffraction | B | `enhancedSounds`; `/dstune` overrides live for testing only, it does not save |
| Change which blocks are "leaves", "ice", "heat sources"… | C | the matching tag under `tags/block/effects/` |
| Add a new item class (e.g. "wands") | **D** | Java — `ItemClassType` is a closed enum |

> ⚠️ **File shapes are not uniform — match the file's own shape.**
> `critwords.json` and `item_sounds.json` are **bare arrays** (`[ … ]`). `sound_mappings.json` is an
> array of `{soundEvent, rules}`. `variators.json` is an **object** keyed by profile, not an array.
> A wrong top-level shape is rejected by the codec — the whole file fails to load.

---

### 6. "I changed it and nothing happened"

Work down this list — these are ordered by how often they are the cause.

| # | Cause | How to confirm |
| --- | --- | --- |
| 1 | Disk folder name is **not a loaded mod id** → whole folder ignored | rename it to a real mod id |
| 2 | Rule added under the **wrong vanilla step event** | `/dsdump blocks` shows each block's actual event |
| 3 | Rule sits **after the catch-all**, or after a broader rule that already matches | `/dsdump steps` prints the full decision chain, branch by branch |
| 4 | Block/item id wrong, or **does not exist in this MC version** → rule becomes inert (warn only) | `/dsdump validate` |
| 5 | `#c:*` / `#forge:*` namespace **not present on this version** — `required:false` makes the empty set pass **silently** | `/dsdump tags` |
| 6 | Checked the wrong declaration — one event can have **2–4 top-level entries** | `/dsdump blocks` |
| 7 | `item_sounds.json` points at a **factory that does not exist** → silent fallback to the class default | `/dsdump items` |
| 8 | Forgot `/dsreload` after editing the disk folder | — |

Tools: **`/dsreload`** (hot reload), **`/dsdump blocks`** (per-block step event + material DS resolved),
**`/dsdump steps`** (the whole footprint decision chain at your position, every branch),
`/dsdump items`, `/dsdump tags`, `/dsdump biomes`, `/dsdump brush`, `/dsdump sounds`,
`/dsdump validate` (self-check report). All of them are described in
[`FOOTSTEPS.md`](FOOTSTEPS.md) §4.

Realistic loop: **edit → `/dsreload` → `/dsdump steps` standing on the block → read which branch won.**

---

### 7. What does not carry across versions

| Area | Difference |
| --- | --- |
| Biome tags | `#forge:*` on 1.20.1 vs `#c:*` on the NeoForge ports. **Never copy a selector tag between ports** — use `biome.id` or a tag you define yourself |
| Armour classification (the guess) | three different algorithms and thresholds → same modded armour, different accent per port. **Tag explicitly** |
| Bucket-type detection | **`[1.20.1]`** instance-based, so the `*_buckets.json` tags do nothing there; tag-based on the other two |
| Vanilla step events | a handful of vanilla blocks changed `SoundType` between versions; a rule written against the old event will not fire |
| `grass` / `tall_flowers` / `suspicious_gravel` | block ids renamed or split between versions |

Everything else — file names, fields, codecs, merge behaviour — is identical across the three ports.

---

## Part II — 中文说明

### 0. 先决定"改的形式"

本模组的自定义只有四种形式。**选形式就是全部决策**，选完就只是编辑一个 JSON。

| 形式 | 用于 | 生效方式 |
| --- | --- | --- |
| **A. 数据文件** | 一切**内容**问题：哪个方块听起来像什么、某群系的雾、某物品的挥舞音 | `/dsreload`，立即 |
| **B. 配置文件 / GUI** | 一切**行为与平衡**问题：开关、音量滑块、雾的倍率、混响质量 | 立即，或下次进世界 |
| **C. 标签文件** | **归类**问题："把这些物品当剑"、"这些方块留脚印" | `/dsreload` |
| **D. Java** | 新建一个**不存在**的类别（新的物品类、新的效果类型） | 重新编译 |

**判断口诀：如果配置界面里已经有这个开关，就是 B；开关有但"内容"不对，就是 A。**
只有 D 需要改源码 —— 而且比大家以为的少得多：几乎所有"我想要一个新音效"都是 A 或 C。

#### 数据文件其实有"两种写法"

同样的内容，既能写成**独立文件**（`biomes.json`），也能写成**聚合文件的一段**
（`dsurround.json`）。两者用**同一个 codec** 解码，所以同一条目在哪儿写都一样。

| 你要做的事 | 建议写法 |
| --- | --- |
| 只补一两处 | 独立文件 —— 最小、最好审阅 |
| 描述一整个模组（方块 + 群系 + 声音） | 一个 `dsurround.json`，每种类型一段 |
| 完全不想进 jar | 都行，放到磁盘配置目录 |

#### 数据文件能放的五处

| # | 位置 | 谁发布 |
| --- | --- | --- |
| 1 | `<模组>.jar` → `assets/<任意命名空间>/dsconfigs/<文件>.json` | 模组作者 |
| 2 | **资源包** → `assets/<任意命名空间>/dsconfigs/<文件>.json` | 整合包 / 资源包作者 |
| 3 | **磁盘** → `config/dsurround/configs/<命名空间>/<文件>.json` | 玩家、整合包作者 |
| 4 | 以上任一位置的聚合 `dsurround.json` 的对应段 | 整合包作者 |
| 5 | 真数据包 `data/<命名空间>/tags/**` | **仅已加载的模组 jar** —— 资源包无效 |

- ⚠️ **第 3 种的目录名必须是已加载模组的 id。** 路径里被检查的只有这一段，**不是模组 id 的
  文件夹会被静默忽略** —— 没有警告、没有日志。这是"磁盘覆盖不生效"最常见的原因，没有之一。
- tag 另有一条资源包可用的路径：`assets/<命名空间>/dsconfigs/tags/<registry>/<tag>.json`。
- **加载顺序**：jar 与资源包在前，磁盘目录**最后** —— 所以磁盘是覆盖出厂值最可靠的地方。

#### 合并规则 —— 以及唯一做不到的事

| 文件 | 多份如何合并 |
| --- | --- |
| `tags/**` | **纯并集** —— `replace` 被读取但从不使用 |
| `sound_factories.json`、`variators.json` | 同 key **后者覆盖**（磁盘最后读，所以磁盘赢） |
| `sound_mappings.json` | 规则合并；新写的具体规则被插到兜底规则**之前** |
| `biomes.json`、`blocks.json`、`dimensions.json` | 追加；标量字段后者覆盖，列表累加；`biomes.json` 另按 `priority` 升序排序 |
| `critwords.json` | 拼接 |
| `item_sounds.json` | **先匹配先赢** |

> ⚠️ **任何包都删不掉出厂条目。** 想让某个东西静音，要写一条**更具体**的规则 ——
> 例如把方块指向保留的静音工厂 `dsurround:footsteps.none`。

**优先用 tag，而不是方块/物品 id 列表。** 模组以后新增方块时 tag 自动跟上，id 列表不会。

---

### 1. 脚步声

#### 1.1 方块发什么声 —— `sound_mappings.json`

本文件按**原版脚步事件**分组，再把方块映射到 DS 的材质。一个事件一条顶层条目；规则按顺序
走，**第一条命中生效**。

```jsonc
// assets/<你的命名空间>/dsconfigs/sound_mappings.json
[
  { "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["yourmod:marble", "#yourmod:marble_blocks"],
        "factory": "dsurround:footsteps.marble",
        "accent":  ["dsurround:footstep_accent/sand"] },
      { "factory": "dsurround:footsteps.stone" }        // 兜底规则，必须最后
    ] }
]
```

- 要写在方块**真正的**原版脚步事件下。挂错事件，永不生效。
- **兜底规则** = `blocks` 为空的规则。它必须最后，排在它后面的规则永远到不了。
- **不要照抄 DS 的整个文件** —— 只发你自己的规则，合并时会插到兜底之前。
- 没写规则的方块**不会静音**：它保留自己的原版脚步音，只是走 DS 的脚步管线（步频、音量、重音）。
  只有 `dsurround:footsteps.none` 会静音它。
- 没匹配到规则的模组方块，会按**名字**猜材质（`*_sandstone`、`*_marble`、`*_copper`…）。
  猜测**永远不能**盖过显式规则。

现有 102 个材质见 [`FOOTSTEPS.md`](FOOTSTEPS.md)。

#### 1.2 落地、急停、起跳 —— `land` / `wander` / `jump`

步频和表面是两件事：**放哪个录音**由方块解析出的**材质**决定，不由方块决定。所以调落地音 =
改 `sound_factories.json` 里那一条工厂条目，在它的 `land` 块里：

```jsonc
{ "location": "dsurround:footsteps.concrete",
  "soundEvent": "dsurround:footsteps.concrete",
  "land": {
    "primary":   "dsurround:footsteps.concrete_run",  // 必需
    "secondary": "dsurround:footsteps.stone",         // 可选，第二层
    "echo":      "dsurround:footsteps.stone_run",     // 可选，延迟几 tick
    "secondaryScale": 0.5, "echoVolume": 1.0,
    "echoDelayMinTicks": 1, "echoDelayMaxTicks": 2
  },
  "wander": "dsurround:footsteps.marble_wander",      // 急停拖擦音覆盖
  "jump":   "dsurround:footsteps.dirt_wander" }       // 起跳音覆盖（缺省回退到 wander）
```

落地是**分层叠加**的（主层 + 更轻的第二层 + 延迟回声，在混音器里相加）—— 这是落地能听出比
普通脚步"更重"的唯一办法，因为单条声道的增益是有上限的。出厂 185 个工厂里有 30 个带 `land`；
其余的按"材质自己的 run/land + 回声"兜底。

#### 1.3 音量、音高、静音

`volume` / `pitch` 在**工厂**上，所以是**按材质**，不是按方块。要只静音一个方块而不动它的材质，
把那个方块的规则指向静音工厂：

```jsonc
{ "blocks": ["yourmod:silent_tiles"], "factory": "dsurround:footsteps.none" }
```

脚步的全局音量是配置滑块 `soundOptions.footstepVolume`（0–2）；**调到 0% 同时也会恢复原版脚步声**。

#### 1.4 脚印

tag 驱动，不需要工厂：把方块加进 `tags/block/effects/footprintable.json`。

```jsonc
// assets/<你的命名空间>/dsconfigs/tags/block/effects/footprintable.json
{ "values": ["yourmod:snow_bricks"] }
```

开关在配置里：`entityEffects.enableFootprints`（玩家）、`entityEffects.enableCreatureFootprints`
（生物）、`entityEffects.footprintStyle`。生物脚印的大小与间距来自 `variators.json` /
`entity_variators.json` 里的步态档案（`footprintScale`、`stride`、`volumeScale`、`hasFootprint`）。

#### 1.5 穿草丛与作物

三个 tag 决定：`tags/block/effects/brush_step.json`、`straw_step.json`、`crop_step.json`。
`crop_step` 带 **age 语义**，所以未成熟的作物不会发出成熟作物那样的大动静。把植物加进 tag 即可，
同样不需要工厂。

#### 1.6 盔甲踏步音效（走路的金属声 / 皮革声）

每种盔甲**材质**是一个 tag，列出四件原版装备：

```jsonc
// assets/<你的命名空间>/dsconfigs/tags/item/effects/armor/iron.json
{ "values": ["yourmod:steel_helmet", "yourmod:steel_chestplate",
             "yourmod:steel_leggings", "yourmod:steel_boots"] }
```

| 材质 | DS 音色 |
| --- | --- |
| `leather` | 柔软皮革 |
| `chain` | 锁甲摩擦 |
| `iron` | 铁器轻碰 |
| `gold` | 柔和金属 |
| `diamond` | 硬质金属 |
| `netherite` | 厚重金属 |
| **`slimey`** | **出厂为空 —— 这是留给你的扩展位** |

几个容易踩的点：

- 音色取自**护腿**；没穿护腿则回退到**胸甲**。靴子是另一套重音。
- 疾跑时取同一重音的 `_run` 变体。
- `slimey` 是一个真实存在但为空的 tag。粘液类盔甲放这里，**不要硬塞进 `leather`**。
- 重音是**按材质 tag** 走的，所以"铁质手感"的模组盔甲必须自己加进 `armor/iron.json`。
  另一条路是打开 `footstepAccents.inferArmorClass`，让 DS 按防御值猜材质 —— 方便，但那是猜。
- 总开关：`footstepAccents.enableArmorAccents`。
- **`[1.20.1]` / `[1.21.1]` / `[26.1]`** 这套"猜"用的是**三套不同公式、三个不同阈值**，
  所以同一件模组盔甲在三版可能被分到不同档位。**显式打 tag 才是可移植的答案。**

---

### 2. 手持物品：切换声与挥舞声

#### 2.1 四步（先命中先赢）

| # | 步骤 | 粒度 |
| --- | --- | --- |
| 1 | **`item_sounds.json`** —— 物品 id 或 tag → 工厂 | 单件物品，或一个 tag |
| 2 | 覆写 `sound_factories.json` 里的 `dsurround:toolbar.<类>.swing` / `.equip` | 整个类别 |
| 3 | 把物品加进 `tags/item/effects/<类>.json` | 归入已有类别 |
| 4 | 新建类别 | **需改 Java** —— 类别表是枚举 |

**"我的武器挥舞音不对"几乎都属于第 1 步，而第 1 步存在的意义就是让你完全不用碰模组。**
不需要改这个物品的任何 tag。

#### 2.2 逐物品覆盖 —— `item_sounds.json`

```jsonc
// assets/<你的命名空间>/dsconfigs/item_sounds.json
[
  { "items": ["mymod:katana"],   "swing": "mypack:katana.swing" },
  { "items": ["#mymod:katanas"], "equip": "mypack:katana.equip" }
]
```

`items` 必填；id 必须**全限定**（不含 `:` 的裸名直接报错）。`swing` 与 `equip` 互相独立、都可省略。
先匹配先赢。

> ⚠️ **工厂必须先存在。** 与 `biomes.json` / `blocks.json` 不同，本文件**不会**回退成
> "把 location 当声音事件用"。指向未定义工厂的 `swing` 会被当成"没写"，物品**静默**保留类别默认音。
> 所以先在 `sound_factories.json` 里定义它，并在 `sounds.json` 里注册事件。

事件路径以 `item.` 开头，才会跟随游戏内的"玩家音量"滑块 —— 这个判断看的是**事件路径前缀**
（`item.`、`toolbar.`、`player.`），不是声音分类。

#### 2.3 直接复用整个类别（最省事）

想让某物品"像剑一样响"，直接归类即可 —— 不需要工厂，也不需要 sounds.json：

```jsonc
// assets/<你的命名空间>/dsconfigs/tags/item/effects/swords.json
{ "values": ["mypack:katana"] }
```

现有类别：`swords` `axes` `tools` `bows` `crossbows` `shields` `potions` `books`，
以及上面那套盔甲材质 tag。

---

### 3. 雾

雾是**故意拆开**的：**颜色与逐群系浓度是数据，全局强度与时间是配置。**

#### 3.1 逐群系颜色与浓度 —— `biomes.json`

```jsonc
[
  { "biomeSelector": "SWAMP && !FOREST", "fogColor": "#406040", "fogDensity": "medium" },
  { "biomeSelector": "biome.id == 'minecraft:dark_forest'", "fogDensity": "heavy", "priority": 150 },
  { "biomeSelector": "biome.id == 'minecraft:plains'", "fogDensity": "none" }
]
```

- `fogDensity`：`none` / `light` / `normal` / `medium` / `heavy`。`none` 即关闭。
- `fogColor`：`#RRGGBB`。出厂有 5 个群系带颜色。
- `biomeSelector` 是一门小表达式语言，操作的是**群系 tag 名**（`SWAMP`、`FOREST`、`COLD`、
  `SNOWY`、`LUSH`…，完整表在 `tags/worldgen/biome/*`），可用 `&&` `||` `!` 和括号组合；
  也能读 `biome.id`、`biome.temperature`、`biome.getRainfall()`。
- 规则按 `priority` 升序应用，同字段后者覆盖。DS 自己的最高优先级是 **100**，
  要让自己这条压过它就用更大的数。
- `dustColor` 给沙尘效果染色（与雾无关）。

**想退回原版雾**需要用两个重置标志 —— 否则规则只能"加"，不能"减"：

| 标志 | 作用 |
| --- | --- |
| `resetFogColor: true` | 清除前面某条规则设的颜色 |
| `clearTraits: true` | 先丢掉全部自动识别的特征，让 `traits` 成为唯一答案 |
| `clearSounds: true` | 丢掉已累加的 loop / mood / addition 音效 |

#### 3.2 全局雾 —— 配置 `fogOptions`

`enableBiomeFog`、`biomeFogDensity`（0–2 倍率）、`morningFog*`（起始/峰值/结束小时、浓度）、
`enableWeatherFog`、`weatherFogDensity`，以及 `weatherOptions.enableBiomeFogColor`
（是否启用逐群系颜色，总开关）。完整表格见 [`CONFIGURATION.md`](../CONFIGURATION.md) §2。

---

### 4. 群系环境氛围

还是 `biomes.json` —— 每条规则都能挂环境音、改环境音的**概率**、设置特征。

```jsonc
{ "biomeSelector": "JUNGLE",
  "acoustics": [
    { "factory": "dsurround:biome.jungle.day", "weight": 10, "type": "loop" },
    { "factory": "dsurround:biome.bird",       "weight": 4,  "type": "mood",
      "conditions": "!weather.isRaining()" }
  ],
  "additionalSoundChance": "0.02",
  "moodSoundChance": "0.01" }
```

| `type` | 行为 |
| --- | --- |
| `loop` | **默认** —— 背景底噪，按 `weight` 挑选 |
| `mood` | 偶发的单声（鸟叫、狼嚎之类） |
| `addition` | 额外叠加的氛围层 |
| `music` | 群系音乐 |

- `conditions` 是脚本表达式（`weather.isRaining()`、`biome.temperature`…）。不写就是永远可播。
- `traits`（普通列表，不是 `acoustics`）挂的是 DS 自己的特征词汇：`FOREST` `SWAMP` `COLD`
  `SNOWY` `DESERT` `OCEAN` `MOUNTAIN` `LUSH` `SPOOKY` `MAGICAL` `DEAD` `NETHER` `UNDERGROUND`…
  模组其他地方都看这套特征；**模组群系被识别错了，解法就是 `clearTraits: true` + 显式列表**。
- 氛围**音量**是配置滑块：`soundOptions.biomeVolume`（0–2）与 `ambientVolumeScaling`。
- 总开关：`soundOptions.enableBiomeSounds`、`playBiomeMusicWhileCreative`、`allowScarySounds`；
  逐维度还有 `dimensions.json` 里的 `playBiomeSounds`。

---

### 5. 其余一切，一张表

| 我想…… | 形式 | 改哪里 |
| --- | --- | --- |
| 改方块的粒子/效果 | A | `blocks.json` —— `blocks`、`effects[]`、`soundChance`、`acoustics[]`、`clearSounds` |
| 给方块加环境音 | A | `blocks.json` —— `acoustics[]` + `soundChance` |
| 改维度的海平面 / 群系音 | A | `dimensions.json` |
| 改生物的步态、步幅、脚印间距 | A | `variators.json`、`entity_variators.json` |
| 改暴击时的拟声词 | A | `critwords.json` —— 注意下面的形状警告 |
| 改生物气泡台词 | A | `assets/dsurround/chat/<语言>.lang` —— `chat.<实体>.<序号>=权重,文本` |
| 改伤害/治疗/暴击数字的样式 | B | `popoffNumbers`（只有 `sizePercent` 在 GUI 里，其余只能手改文件） |
| 整个功能开关 | B | 对应配置分节 |
| 调混响 / 遮挡 / 衍射 | B | `enhancedSounds`；`/dstune` 只用于临时试听，**不落盘** |
| 改"哪些方块算树叶/冰/热源…" | C | `tags/block/effects/` 下对应 tag |
| 新建物品类别（比如"法杖"） | **D** | 需改 Java —— `ItemClassType` 是封闭枚举 |

> ⚠️ **各文件的顶层形状并不统一 —— 必须按文件自己的形状写。**
> `critwords.json` 和 `item_sounds.json` 是**裸数组**（`[ … ]`）；`sound_mappings.json` 是
> `{soundEvent, rules}` 的数组；`variators.json` 是**按档案名键的对象**，不是数组。
> 顶层形状写错，codec 会拒绝 —— **整个文件加载失败**。

---

### 6. "我改了，但没反应"

按顺序往下查，已按"最常见"排序。

| # | 成因 | 怎么确认 |
| --- | --- | --- |
| 1 | 磁盘目录名**不是已加载模组 id** → 整个目录被忽略 | 改成真实模组 id |
| 2 | 规则挂到了**错误的原版脚步事件**下 | `/dsdump blocks` 会列出每个方块真实的事件 |
| 3 | 规则排在**兜底规则之后**，或排在一条已能命中的宽规则之后 | `/dsdump steps` 会逐分支打印完整判定链 |
| 4 | 方块/物品 id 写错，或**该版本根本没有这个 id** → 规则变惰性（只 warn） | `/dsdump validate` |
| 5 | `#c:*` / `#forge:*` 命名空间**在这个版本不存在** —— `required:false` 让空集**静默通过** | `/dsdump tags` |
| 6 | 检查错了声明处 —— 同一事件可能有 **2–4 条顶层条目** | `/dsdump blocks` |
| 7 | `item_sounds.json` 指向**不存在的工厂** → 静默回退成类别默认音 | `/dsdump items` |
| 8 | 改完磁盘目录忘了 `/dsreload` | — |

工具：**`/dsreload`**（热重载）、**`/dsdump blocks`**（每方块的脚步事件 + DS 解析出的材质）、
**`/dsdump steps`**（你当前位置的完整脚印判定链，逐分支）、`/dsdump items`、`/dsdump tags`、
`/dsdump biomes`、`/dsdump brush`、`/dsdump sounds`、`/dsdump validate`（自检报告）。
全部说明见 [`FOOTSTEPS.md`](FOOTSTEPS.md) §4。

实际循环：**改文件 → `/dsreload` → 站到那块方块上 `/dsdump steps` → 看是哪条分支赢了。**

---

### 7. 哪些写法不能跨版本照抄

| 区域 | 差异 |
| --- | --- |
| 群系 tag | 1.20.1 是 `#forge:*`，两个 NeoForge 版是 `#c:*`。**选择器里的 tag 千万别跨版复制** —— 用 `biome.id`，或自己定义的 tag |
| 盔甲分类（"猜"的那条路） | 三套不同算法、三个不同阈值 → 同一件模组盔甲在不同版得到不同音色。**显式打 tag** |
| 桶类型判定 | **`[1.20.1]`** 是实例判定，所以那些 `*_buckets.json` tag 在 1.20.1 上不起作用；另两版是 tag 判定 |
| 原版脚步事件 | 少数原版方块在这些版本之间改过 `SoundType`；按旧事件写的规则不会触发 |
| `grass` / `tall_flowers` / `suspicious_gravel` | 方块 id 在版本间被改名或拆分 |

除此之外 —— 文件名、字段、codec、合并行为 —— **三版完全一致**。