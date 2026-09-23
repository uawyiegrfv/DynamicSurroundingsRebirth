# Configuration & Data Files Reference · 配置与数据文件参考

Dynamic Surroundings Rebirth **1.3.2** — Minecraft 1.20.1 (Forge) · 1.21.1 (NeoForge) · 26.1 (NeoForge)

[English](#part-i--english) · [中文说明](#part-ii--中文说明)

> **Looking for "how do I change X"?** → [`docs/CUSTOMISATION-GUIDE.md`](docs/CUSTOMISATION-GUIDE.md)
> is the task-oriented companion to this reference: where data files may live, how copies merge,
> and a recipe per feature (footsteps, armour accents, swing sounds, fog, biome ambience).
> **想知道"X 要怎么改"？** → [`docs/CUSTOMISATION-GUIDE.md`](docs/CUSTOMISATION-GUIDE.md)
> 是本参考的任务导向版：数据文件能放哪里、多份如何合并，以及每个功能（脚步、盔甲重音、
> 挥舞音、雾、群系氛围）的具体改法。本文档是**逐字段参考**。

---

## Part I — English

### 1. Overview

Dynamic Surroundings Rebirth is **fully client-side**. Every setting lives on **each player's own game installation** — there is no server-side enforcement. A modpack author sets the defaults by shipping the files below inside the pack; every player who installs the pack starts with those settings.

Three customization layers exist:

| Layer | Location | Who edits it |
| --- | --- | --- |
| Config files | `config/dsurround/*.json` (game directory) | Modpack authors (ship presets), players |
| In-game GUI | Mod Options screen (needs Cloth Config) | Players |
| Data files | `assets/dsurround/...` in the jar, overridable by **resource packs**, and **extendable by any mod** shipping its own `assets/<its id>/dsconfigs/...` (see §4.0) | Mod authors, modpack authors, resource-pack makers |

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
| enableChannelReaper | bool | false | — | **Experimental.** Tries to free sound channels that have become stuck. Off by default because it can crash the game |
| enableOcclusionProcessing | bool | true | — | Muffle sounds behind blocks |
| reverbRays | int | 32 | 16–64 | ★ Rays projected per sound to compute reverb |
| reverbBounces | int | 4 | 4–8 | ★ Reflections per ray |
| reverbRayTraceDistance | int | 256 | 64–512 | ★ Total ray distance (blocks) |
| reverbIntensity | double | 1.0 | 0–2 | Reverb/echo strength (1.0 = default, 0 = off) |
| enableWaterSoundDamping | bool | true | — | Dampen sounds whose path passes through water |
| waterSoundDamping | double | 0.95 | 0.1–1 | Volume fraction surviving each water block (lower = quieter) |
| waterSoundMuffle | double | 0.7 | 0.1–1 | High-frequency cut per water block (lower = more muffled) |

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
| footstepVolume | double | 1.0 | 0–2 | Footstep volume multiplier |
| biomeVolume | double | 1.0 | 0–2 | Biome ambient volume multiplier |
| playerEffectVolume | double | 1.0 | 0–2 | Player effect volume (jump, heartbeat, hunger, crafting, hotbar) |

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
| waterfallMaxVolume | double | 0.5 | 0–1 | Loudest a waterfall is allowed to get (0.5 = default) |
| furnaceIgniteEnabled | bool | true | — | Crackle and ignite sound when a furnace starts burning |
| enableEnchantTableSounds | bool | true | — | Page-turn sounds at an enchanting table |
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
| enableProjectileBreakSounds | bool | true | — | Impact sound when an arrow, egg, snowball or pearl breaks |
| enableBrushStepEffect | bool | true | — | ☆ Walking through dense brush |
| enablePlayerHeartbeatSound | bool | true | — | ☆ Heartbeat when health is low |
| playerHurtThreshold | double | 0.25 | 0–1 | Health fraction below which heartbeat plays (0 = off) |
| enablePlayerHungerSound | bool | true | — | ☆ Stomach growl when hungry |
| playerHungerThreshold | int | 8 | 0–20 | Food level at/below which growl plays (0 = off) |
| enablePlayerJumpSound | bool | true | — | ☆ Jump sound |
| enablePlayerLandSound | bool | true | — | ☆ Landing sound from a fall |
| enableFootstepSounds | bool | true | — | ☆ Footstep sound system (walk/run materials) |
| enableCreatureFootstepSounds | bool | true | — | Creature footsteps (per-creature material, cadence, landing and stop sounds) |
| inferFootstepMaterial | bool | true | — | Guess the footstep sound for blocks from other mods from their name. Blocks with an explicit rule are never changed |
| enableStopScuffSound | bool | true | — | Short scuff played when you stop or turn around suddenly |
| enableCraftingSound | bool | true | — | ☆ Crafting sound |
| enableFootprints | bool | true | — | ☆ Player footprints while walking |
| footprintStyle | enum | LOWRES_SQUARE | SHOE, SQUARE, HORSESHOE, BIRD, PAW, SQUARE_SOLID, LOWRES_SQUARE | Footprint style |
| showCritWords | bool | true | — | ☆ Comic power word on critical hits |
| showDamageNumbers | bool | true | — | ☆ Damage/healing numbers above entities |

#### 2.7 `footstepAccents`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableAccents | bool | true | Footstep accents globally |
| enableArmorAccents | bool | true | Armor accents |
| inferArmorClass | bool | true | Guess an armor's weight class so armor from other mods gets an accent too. Armor with an explicit rule is never changed |
| enableWetSurfaceAccents | bool | true | Accents when raining / waterlogged |
| enableFloorSqueaks | bool | true | Squeaky-block accents |

#### 2.8 `particleTweaks`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| suppressProjectileParticleTrails | bool | false | Hide projectile particle trails |
| suppressPotionParticles | bool | false | — | ☆ Hide the player's potion particles |

#### 2.9 `compassAndClockOptions`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableClock | bool | true | — | Clock display when holding a clock |
| enableCompass | bool | true | — | Compass display when holding a compass |
| enableTreasureDistance | bool | true | — | Distance to the treasure target on explorer maps |
| compassStyle | enum | TRANSPARENT_WITH_INDICATOR | OPAQUE, TRANSPARENT, OPAQUE_WITH_INDICATOR, TRANSPARENT_WITH_INDICATOR | Compass rendering style |
| scale | double | 1.0 | 0.5–4 | Display scale |
| enableLowDurabilityHighlight | bool | true | — | Highlight a hotbar item that is about to break |
| lowDurabilityThreshold | int | 10 | 1–50 | Remaining durability at which that highlight appears |

#### 2.10 `weatherOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableDesertSandstorm | bool | true | Desert sandstorm dust + yellow tint |
| enableNetherDust | bool | false | Nether dust rain effect |
| enableBiomeFogColor | bool | true | Biome fog color tint (biomes.json fogColor) |

#### 2.11 `fogOptions`
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
| morningFogDensity | double | 1.0 | 0–1 | How early in the morning window the mist reaches full strength (1.0 = only at the peak, lower = ramps in more gradually; 0 = off). The near plane cannot be pulled closer than the type's reserve, so the range stops at the point where it saturates |
| biomeFogDensity | double | 1.0 | 0–2 | Biome fog density (0 = off) |
| weatherFogDensity | double | 1.0 | 0.25–4 | Weather fog density |

#### 2.12 `speechBubbles`
| Option | Type | Default | Range | Notes |
| --- | --- | --- | --- | --- |
| enableSpeechBubbles | bool | false | — | ☆ Chat bubbles above player heads |
| enableEntityChat | bool | false | — | ☆ Chat bubbles above villagers/mobs |
| speechBubbleDuration | double | 7.0 | 5–15 | Seconds a bubble stays |
| speechBubbleRange | int | 16 | 16–32 | Blocks a bubble is visible from |

#### 2.13 `auroraOptions`
| Option | Type | Default | Notes |
| --- | --- | --- | --- |
| enableAurora | bool | true | Aurora (northern lights) rendering |

#### 2.14 `otherOptions`
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

All of these live in `assets/dsurround/` inside the jar.

**Two ways to write the same thing - pick by scope.** Every section below can be written either as
its own file or as a section of one aggregate `dsurround.json`; both are decoded with the *same*
codec, so an entry means exactly the same in either place.

| You want to... | Write | Why |
| --- | --- | --- |
| patch one or two things (a biome's fog, one block's sound) | the per-type file, e.g. `biomes.json` with just your rules | smallest thing to read and review |
| describe a whole mod (many blocks + biomes + sounds) | one `dsurround.json` with a section per type | the 1.12.2 workflow - one mod, one file - instead of spreading one mod's entries over half a dozen files |
| keep it out of the mod jar entirely | either, under the disk config folder | see 4.0 |

Nothing else in this section depends on which you choose.

**Another pack's copy is MERGED with ours, not substituted for it.** The loader reads every copy it can
see, so a resource pack adds to the shipped data:

| File type | How copies combine |
| --- | --- |
| `tags/**` | pure union - entries merge, `replace` is ignored |
| `sound_factories.json`, `variators.json` | same key later in the load order wins |
| `sound_mappings.json` | rules merge, and a specific rule is inserted ahead of the catch-all default |
| `biomes.json`, `blocks.json`, `dimensions.json` | appended; later entries win per scalar field, lists accumulate |

The **disk config folder is read after the jar and after resource packs**, which makes it the reliable
place to override a shipped value (see 4.0). No pack can *remove* a shipped entry - the way to silence
one is a more specific rule, e.g. pointing a block at `dsurround:footsteps.none`.

After changing anything, run `/dsreload` to reload without restarting.

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

#### 4.0 Adding your own mod's blocks — **any mod can ship these files**

The data files are **not** private to Dynamic Surroundings. The loader scans the `dsconfigs/`
path across **every loaded namespace**, so a mod can describe its own blocks without touching DS:

```
<yourmod>.jar
└── assets/<yourmodid>/dsconfigs/
    ├── sound_mappings.json     ← append rules for <yourmodid>:* blocks
    ├── blocks.json             ← (optional) per-block particles/ambient sounds
    ├── biomes.json             ← (optional) your biomes' ambience
    └── tags/…                  ← (optional) vanilla-format tags in your namespace
```

Rules for the **same** sound event are **merged**, not replaced: your block matchers are
inserted **before** DS's catch-all default, so a specific rule always wins. You do not need
to (and should not) copy DS's whole file.

There are two other places a pack or a player can put the same files:

| Location | Who ships it |
| --- | --- |
| `assets/<namespace>/dsconfigs/<file>.json` inside **any** mod jar or resource pack | Mod authors, modpack authors |
| `<game dir>/config/dsurround/configs/<namespace>/<file>.json` | Modpack authors, players (namespace must be a **loaded** mod, otherwise it is ignored) |

**What happens without any entry.** A block whose step sound has no mapping is *not* silenced —
DS falls back to playing the block's own vanilla step sound through its footstep pipeline
(cadence, volume, accents). The result stays audible, it just does not get a DS material
(so a brass block sounds like whatever the mod made it sound like, rather than `hardmetal`).
To opt out of a footstep entirely, point the rule at the reserved silent factory
`dsurround:footsteps.none` (the modern equivalent of the original mod's `NOT_EMITTER`).

Minimal example — give your marble blocks DS's marble footstep while everything else keeps
the vanilla-stone default:

```json
[
  {
    "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["yourmodid:marble", "yourmodid:marble_bricks"], "factory": "dsurround:footsteps.marble" }
    ]
  }
]
```

`blocks` entries accept a block id, a block state (`yourmodid:block[prop=value]`) or a
`#namespace:tag` reference — prefer tags, they keep working when you add blocks later.

**Name-based material inference.** When no rule matches a block and only the catch-all
default would apply, DS looks at the block's registry name before settling for the generic
material: a modded `anything_sandstone` resolves to `concrete`, `*_limestone` / `*_jasper` /
`*_shale` / `*_permafrost` / `*_marble` to `marble`, `*_copper` and `*_raw_copper` to
`copper`, and so on. This exists because rules match on explicit block ids, so a modded
copy of a vanilla block would otherwise take the wrong material.

- Precedence is **explicit rule → name inference → generic default**. Anything written in
  the files above always wins, so inference can never override deliberate data.
- Set `entityEffects.inferFootstepMaterial` to `false` to switch the whole step off.
- Matching is on **word boundaries**, not substrings: `deepslate` is not `slate`.
- Names that are decorative rather than material (`*_sapling`, `*_leaves`, `potted_*`,
  `*_flower`, `*_blossom`, …) are skipped, so `snowblossom_leaves` is not treated as snow.
- The keyword list is short, lives in `MaterialInference`, and is **not** data-driven. If a
  material you care about is not inferred, write an explicit rule (§4.1) - that works today
  and always takes precedence.
- The inferred material brings its **accent layers** with it: a modded sandstone gets the
  same sand sub-sound vanilla sandstone has. The accent belongs to the material, not to the
  block list that happens to name it.
- Inferred assignments are logged under the `RESOURCE_LOADING` debug flag, once per block.

#### 4.1 `sound_factories.json` (array)
| Field | Type | Meaning |
| --- | --- | --- |
| location | string | Factory key code requests (e.g. `dsurround:toolbar.tool.equip`) |
| soundEvent | string | Actual SoundEvent played (must exist in `sounds.json`) |
| category | string | MC sound category (AMBIENT, PLAYER, …) — routes volume sliders |
| volume | number | Base volume multiplier |
| pitch | object | Optional `{"min":0.8,"max":1.2}` random pitch range |
| land | object | Optional landing composition (see below) |
| wander | string | Optional stop-scuff recording override |
| jump | string | Optional take-off recording override |

> `location` ≠ `soundEvent` by design: the factory name is stable, the sound it plays can be re-targeted.

**`land` — how a material lands.** A landing is not one sound: it is a primary layer, an optional
quieter layer, and an optional echo a couple of ticks later, played once per foot so the layers
sum in the mixer. That summation is the only way a landing can read heavier than a footstep,
because a single voice's gain is clamped.

```jsonc
"land": {
  "primary":   "dsurround:footsteps.concrete_run",   // required for the block to do anything
  "secondary": "dsurround:footsteps.stone",          // optional, default scale 0.5
  "echo":      "dsurround:footsteps.stone_run",      // optional
  "secondaryScale": 0.5,                             // optional
  "echoVolume": 1.0,                                 // optional
  "echoDelayMinTicks": 1, "echoDelayMaxTicks": 2     // optional
}
```

A material with no `land` block falls back to its own land/run thud per foot plus a delayed
echo — 1.12.2 `playMultifoot` without a configured composition. The primary is often a
*different* material's run recording rather than the material's own: stone lands with
`concrete_run`, marble with its own `_run`.

**`wander` and `jump` — the cross-references.** In the original data several materials scuff or
take off with a *different* material's recording: metal box and metal bar scuff with a marble
scrape, wood / sand / glass / quicksand / leaf litter with dirt, rugs and grass paths with
grass. A material's own `_wander` recording is used only when no override is given. `jump`
falls back to `wander`, which is what 1.12.2's `EventType.JUMP(WANDER)` declaration meant, so
`jump` is only needed where the two differ.

All three are read from the *material's* factory entry, and the material is whatever
`sound_mappings.json` resolved for the block — so retuning a landing means editing one entry.

#### 4.2 `sound_mappings.json` (array)
Remaps an incoming vanilla sound event to a DS factory:
```json
{ "soundEvent": "minecraft:block.anvil.step",
  "rules": [ { "factory": "dsurround:footsteps.metalbox" } ] }
```

#### 4.3 `biomes.json` (array)
| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| biomeSelector | string | - | Expression over biome tags (see below) |
| _comment | string | - | Optional label |
| priority | int | 0 | Rules apply in ascending order, so later rules win per field |
| clearTraits | bool | false | Drop every auto-detected trait before applying `traits` |
| traits | array | `[]` | Trait names to add, e.g. `["FOREST","COLD"]` |
| clearSounds | bool | false | Drop the accumulated loop / mood / additional sounds |
| resetFogColor | bool | false | Clear a fog color set by an earlier rule |
| fogColor | color | - | Biome fog tint (see `weatherOptions.enableBiomeFogColor`) |
| dustColor | color | - | Tint for the dust effect |
| fogDensity | enum | - | `none` / `light` / `normal` / `medium` / `heavy` |
| additionalSoundChance | string | 0.008 | Per-tick chance of an `addition` sound |
| moodSoundChance | string | 0.008 | Per-tick chance of a `mood` sound |
| acoustics | array | `[]` | `{"factory":"dsurround:biome.wind", "conditions":"weather.isRaining()", "weight":10, "type":"loop"}` |

`clearTraits` and `resetFogColor` exist so a pack can **correct** rather than only add. The tag and
name analyzers run before any rule, so without `clearTraits` their output can only be extended; and
without `resetFogColor` a fog color set by one rule can only be swapped for another color, never
handed back to vanilla. Give the rule a `priority` above ours (our highest is 100) to make either
one stick. `resetFogColor` clears DS's own fog color only - a data pack's biome fog color is
untouched - and does not affect `dustColor`.

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

**Where a tag file may live.** `assets/<namespace>/dsconfigs/tags/**` works from a mod jar *and* from a
resource pack. A real data pack path, `data/<namespace>/tags/**`, is only read from **loaded mod jars** -
`ServerResourceFinder` walks the mod list, not the world's data packs, so a resource pack cannot supply it.

#### 4.8 `chat/<lang>.lang` — entity speech bubbles
Format (documented in the file header): `chat.<entity>.<index>=weight,text`. `villager.flee` is a special flee-line table; `$MINECRAFT$` plays a random vanilla splash text. The file name follows the client language (`en_us.lang`, `zh_cn.lang`).

#### 4.8.1 `popoffNumbers` — damage, healing and critical-hit text

Controls the text that pops off an entity when it takes damage, is healed, or takes a critical hit.
All options are sliders, so the animation can be tuned in game.

| Option | Default | Meaning |
| --- | --- | --- |
| `sizePercent` | 73 | **Starting** size, relative to the 1.12.2 original. The text grows to about 1.9x this at the peak, so this is what makes the change in size read |
| `growFactor` | 114 | Growth per tick, in percent |
| `peakTickTicks` | 5 | Which tick the text reaches its largest |
| `gravityPercent` | 80 | Fall speed, as a percentage of the original mod's gravity |
| `lifetimeTicks` | 17 | How long the text lives (20 ticks = 1 second), so 17 ticks = 0.85 s |
| `driftPercent` | 60 | Horizontal travel, as a percentage of the original mod's launch. Positive drifts away from the attacker, negative toward it, 0 rises straight up |

**What appears in the settings screen.** Only `sizePercent` does. The other six values are
`@Hidden`: they stay in `dsurround.json`, can be edited by hand, and are read on `/dsreload`, but they
are deliberately kept out of the GUI. The defaults were tuned against the original 1.12.2 animation,
and exposing growth rates and gravity invites misconfiguration more than it helps — the 1.12.2 mod
itself offered only two on/off switches for this feature.

**Your own numbers in first person.** A word belonging to the local player is never drawn while
the camera is in first person — the 1.12.2 original refused to create them there, and this port
also refuses to *draw* them, so pressing F5 right after taking a hit no longer leaves your own
number floating across your view. Third person (including the front view) shows them normally.
Everything else — mobs, other players — is unaffected.

**The shape.** The size is a function of the animation's age, not an accumulated value, so the first
and last frames are **exactly equal by construction**:

```
size(age) = grow ^ age                                   while age <= peakTick
          = grow ^ peakTick * shrink ^ (age - peakTick)  after that

shrink    = grow ^ (-peakTick / (lifetime - 1 - peakTick))   = 1.14 ^ (-5/11) = 0.9413 at the defaults
```

At the defaults the whole animation is (17 ticks, 0.85 s):

```
tick    0    1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16
size  1.00 1.14 1.30 1.48 1.69 1.93 1.81 1.71 1.61 1.52 1.43 1.35 1.27 1.20 1.13 1.06 1.00
```

It rises for the first five ticks to 1.93x, then comes back down over the remaining eleven to exactly
the size it started at, fading out over the second half. Total 0.85 s, with almost no sideways travel.

> Equal start and end sizes are a consequence of the formula: change `lifetimeTicks` or
> `peakTickTicks` and the shrink rate is recomputed to keep it true. Earlier builds accumulated the
> size once per tick instead, which left the first frame at `grow` and the last one well below 1.0 -
> so the text visibly ended smaller than it began.

**Two knobs interact.** `lifetimeTicks` and `gravityPercent` together decide how far the text falls:
with the gravity left alone, a longer life always falls further. The defaults are solved for
"0.85 s and about 1.8 blocks of fall". Raising the lifetime without also raising the gravity makes the
number drop well past the target - 20 ticks at 90% fell 3.4 blocks, nearly out of view - while
lowering the gravity too far makes it hang instead of falling at all. Both are `@Hidden`, and the
self-check reports it when the config file and the shipped default disagree, because an existing
config file always wins.

**Why there is no shrink slider.** A hand-picked shrink rate cannot satisfy both "grows quickly" and
"ends at the size it started"; it would either cut the text off early or leave it larger than it
began. `peakTickTicks` shapes the curve instead.

#### 4.9 One file per mod - the aggregate `dsurround.json`

The files above are split by **type**, which is convenient for the mod's own data but awkward when
you adapt a third-party mod: one mod's entries end up spread over half a dozen files. For that case
you can put **every section in a single file**, exactly like the original 1.12.2 mod did with its
`data/<modid>.json`:

```
config/dsurround/configs/<namespace>/dsurround.json
assets/<namespace>/dsconfigs/dsurround.json          (same thing from inside a mod jar)
```

The section names are the names of the dedicated files, and each section is decoded with **the same
codec**, so a rule means exactly the same thing in either place:

```json
{
  "sound_mappings": [ { "soundEvent": "minecraft:block.stone.step",
                        "rules": [ { "blocks": ["yourmodid:marble"], "factory": "dsurround:footsteps.marble" } ] } ],
  "blocks":         [ { "blocks": ["yourmodid:ember"], "effects": [ { "effect": "fire_jet", "spawnChance": "0.005" } ] } ],
  "biomes":         [ { "biomeSelector": "biome.id == 'yourmodid:ashen_waste'", "acoustics": [ { "factory": "biome.wind" } ] } ]
}
```

Sections you can use: `sound_mappings`, `blocks`, `biomes`, `dimensions`, `sound_factories`,
`variators`, `entity_variators`, `critwords`. Anything else in the file is ignored.

**Precedence.** Both sources contribute. A dedicated `<section>.json` and the section inside the
aggregate file are merged, and within a mapping the more specific rule wins (a rule that names blocks
is inserted before the catch-all default). A mod's built-in data is therefore not overridden by
accident, and an aggregate file can still add to it.

**A typo never breaks the load.** The file is read as raw JSON and only the section of interest is
handed to a codec. A section that is missing, null or of the wrong shape contributes nothing; the
other sections in the same file still apply. Unknown keys are ignored silently.

#### 4.9.1 Testing it

1. Create the file (the namespace folder must match a **loaded** mod id, otherwise it is ignored):

```json
{
  "sound_mappings": [
    { "soundEvent": "minecraft:block.stone.step",
      "rules": [ { "blocks": ["yourmodid:some_stone_block"], "factory": "dsurround:footsteps.wood" } ] }
  ],
  "biomes": "not an array - this section is meant to fail",
  "notASection": { "unknown keys are ignored": true }
}
```

2. Run `/dsreload` in game. Walk on `yourmodid:some_stone_block`: its footstep must now sound like
   **wood** instead of stone. That is the section being read from the aggregate file. If it does not,
   `/dsdump steps` prints the whole surface-resolution chain for where you are standing.
3. Confirm the loader read the file and that the bad section is reported but harmless. The data
   self-check names it: the report is written to `logs/latest.log` when you join a world, and
   `/dsdump validate` prints the same report to chat. Expect an entry naming `dsurround.json` and the
   reason the `biomes` section failed - while `sound_mappings` keeps working, which is the point of
   reading each section independently.
4. Remove the file (or rename it to `dsurround.json.disabled`) and `/dsreload` again: the block goes
   back to stone, which proves the effect came from your file and nothing else.

#### 4.10 `critwords.json` — the comic words on a critical hit

A plain JSON array of strings. The word appears above the entity that took the critical hit, with an
exclamation mark appended, thrown up and away from the attacker while growing.

```json
["BONK", "WHACK", "ZOK", "SPLAT"]
```

**Adding your own.** Ship a file with the same name and it is **merged with** the built-in list — you
do not replace the defaults. Put it in any of the locations listed in §4.0:

- inside a mod: `assets/<yourmodid>/dsconfigs/critwords.json`
- on disk: `config/dsurround/configs/<anyname>/critwords.json` (loaded after the jar, so a mod pack
  can add words both for itself and for another mod)

Sources are concatenated in load order, so more files means a larger pool. Duplicates are allowed and
simply make a word more likely. Entries are trimmed, empty strings are ignored, and the list is capped
at 4096 entries. It can also be supplied as the `"critwords"` section of an aggregate file (§4.9).

**Language.** The built-in list is the original 1.12.2 onomatopoeia (AIEEE, BONK, KAPOW, ZZZZWAP, …)
and is **not translated**. Because the list is data, replacing it with your own words is how you
localise it — see §4.10 of the Chinese part for a Chinese example.

> For anyone comparing against the original: the text size, growth rate and launch arc are taken from
> 1.12.2's `ParticleTextPopOff` — a text height of `0.024` world units per font pixel, `×1.08` per
> tick, initial velocity normalised to a total magnitude of `0.12`, and gravity `0.8`. The three
> editions render through different pipelines (a 3D particle pass in 1.12.2, a projected 2D GUI
> overlay here) but use the same numbers, so the on-screen result matches.

#### 4.11 `item_sounds.json` — per-item swing and equip sounds

The hotbar-select ("equip") and swing sounds are chosen in four steps, and this
file is consulted first:

| # | Step | Granularity |
| --- | --- | --- |
| 1 | **this file** — item id or tag to a factory | one item, or one tag |
| 2 | override `dsurround:toolbar.<class>.swing` in `sound_factories.json` | a whole class |
| 3 | add the item to `tags/item/effects/<class>.json` | join an existing class |
| 4 | a brand-new class | needs a code change — the class list is a Java enum |

Step 1 is why an item that no tag covers can still have its own sound without
touching the mod. It is an array of rules; **first match wins**, `items` is
required, and every id must be fully qualified — a bare name without `:` is
rejected.

```jsonc
// assets/<your-namespace>/dsconfigs/item_sounds.json
[
  { "items": ["mymod:katana"],   "swing": "mypack:katana.swing" },
  { "items": ["#mymod:katanas"], "equip": "mypack:katana.equip" }
]
```

`swing` and `equip` are both optional; omit one to leave that action alone.

**The factory has to exist first.** Unlike `biomes.json` and `blocks.json`, this
file does **not** fall back to "treat the location as a sound event" — a `swing`
naming an undefined factory is treated as absent and the item silently keeps its
class default. So define it in `sound_factories.json`:

```jsonc
// assets/<your-namespace>/dsconfigs/sound_factories.json
[
  { "location": "mypack:katana.swing", "soundEvent": "mypack:item.katana.swing",
    "category": "PLAYER", "volume": 0.5, "pitch": { "min": 0.8, "max": 1.2 } }
]
```

and register the event itself in the usual vanilla place, `sounds.json`:

```json
{ "item.katana.swing": { "sounds": ["mypack:katana_swing"] } }
```

Start the event name with `item.` if you want it to follow the in-game player
sound volume slider — that check is made on the event path prefix (`item.`,
`toolbar.`, `player.`), not on the sound category.

**Worked example — a modded katana.** Put those three files in one resource pack,
run `/dsreload`, and the katana swings with your sound while every other sword
keeps the stock one. Nothing in the mod changes.

### 5. Commands

| Command | Side | Notes |
| --- | --- | --- |
| `/dsreload` | Client | Reloads the data files (§4) without restarting. Registration can be disabled via `logging.registerCommands`. |
| `/bubble <text>` | Server | Shows `<text>` in a speech bubble above the sender for 30 s, to every DS client within 30 blocks (sender included). The message **never appears in chat**. Requires DS on the server; vanilla clients see nothing. |

### 6. Version differences

The three editions are functionally equivalent; the differences are internal:

- **Sound pruning** (`soundSystem.enableSoundPruning`): active on all three versions. On 1.20.1 this config key existed but was inert in earlier builds — the pruning implementation has now been ported there as well. **No config file change is required**: the key was already present in `dsurround.json` with the same default (`true`); it is simply honored now.
- Fog rendering hooks: native Forge `ViewportEvent.RenderFog` event (1.20.1), a mixin into `FogRenderer` (1.21.1), the native fog pipeline (26.1).
- Minor internals (mono audio conversion via reflection vs accessor mixins, tag sync via packet listener vs tag collector) — no user-visible effect.

---

## Part II — 中文说明

### 1. 概述

Dynamic Surroundings Rebirth 是**纯客户端**模组。所有设置都在**每个玩家自己的游戏目录**里，没有服务端强制。整合包作者把下面这些文件打进包里，玩家安装后即带你的默认设置。

三个定制层：

| 层 | 位置 | 谁改 |
| --- | --- | --- |
| 配置文件 | 游戏目录下 `config/dsurround/*.json` | 整合包作者（发预设）、玩家 |
| 游戏内 GUI | 模组选项界面（需 Cloth Config） | 玩家 |
| 数据文件 | jar 内 `assets/dsurround/...`，可被**资源包**覆盖，且**任何模组**都能自带 `assets/<自己的id>/dsconfigs/...` 扩展（见 §4.0） | 模组作者、整合包作者、资源包作者 |

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
| enableChannelReaper | 布尔 | false | — | **实验性**。尝试释放卡住的音效通道。可能会让游戏崩溃，因此默认关闭 |
| enableOcclusionProcessing | 布尔 | true | — | 方块后声音变闷 |
| reverbRays | 整数 | 32 | 16–64 | ★ 每个声音投射的射线数 |
| reverbBounces | 整数 | 4 | 2–8 | ★ 每条射线反射次数 |
| reverbRayTraceDistance | 整数 | 256 | 64–512 | ★ 射线总距离（格） |
| reverbIntensity | 双精度 | 1.0 | 0–2 | 混响强度（1.0 默认，0 关闭） |
| enableWaterSoundDamping | 布尔 | true | — | 穿过水的声音衰减 |
| waterSoundDamping | 双精度 | 0.95 | 0.1–1 | 每格水剩余音量比例（越低越轻） |
| waterSoundMuffle | 双精度 | 0.7 | 0.1–1 | 每格水高频削减（越低越闷） |

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
| footstepVolume | 双精度 | 1.0 | 0–2 | 脚步音量倍率 |
| biomeVolume | 双精度 | 1.0 | 0–2 | 群系环境音量倍率 |
| playerEffectVolume | 双精度 | 1.0 | 0–2 | 玩家效果音量（跳跃/心跳/饥饿/制作/快捷栏） |

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
| waterfallMaxVolume | 双精度 | 0.5 | 0–1 | 瀑布音量的上限（0.5 = 默认） |
| furnaceIgniteEnabled | 布尔 | true | — | 熔炉点火时的噼啪与点燃声 |
| enableEnchantTableSounds | 布尔 | true | — | 附魔台翻书声 |
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
| enableProjectileBreakSounds | 布尔 | true | — | 箭、鸡蛋、雪球、末影珍珠破碎时的撞击声 |
| enableBrushStepEffect | 布尔 | true | — | ☆ 穿过茂密灌木声 |
| enablePlayerHeartbeatSound | 布尔 | true | — | ☆ 低血量心跳 |
| playerHurtThreshold | 双精度 | 0.25 | 0–1 | 低于该血量比例触发心跳（0 关闭） |
| enablePlayerHungerSound | 布尔 | true | — | ☆ 饥饿时肚子叫 |
| playerHungerThreshold | 整数 | 8 | 0–20 | 低于该饥饿值触发（0 关闭） |
| enablePlayerJumpSound | 布尔 | true | — | ☆ 起跳声 |
| enablePlayerLandSound | 布尔 | true | — | ☆ 落地声 |
| enableFootstepSounds | 布尔 | true | — | ☆ 脚步系统（材质行走/奔跑） |
| enableCreatureFootstepSounds | 布尔 | true | — | 生物脚步声（按生物材质，含步频、落地与停步声） |
| inferFootstepMaterial | 布尔 | true | — | 按名称推断其它模组方块的脚步声。已显式指定规则的方块不受影响 |
| enableStopScuffSound | 布尔 | true | — | 急停或急转弯时播放的短促擦地声 |
| enableCraftingSound | 布尔 | true | — | ☆ 合成声 |
| enableFootprints | 布尔 | true | — | ☆ 行走脚印 |
| footprintStyle | 枚举 | LOWRES_SQUARE | SHOE, SQUARE, HORSESHOE, BIRD, PAW, SQUARE_SOLID, LOWRES_SQUARE | 脚印样式 |
| showCritWords | 布尔 | true | — | ☆ 暴击漫画字 |
| showDamageNumbers | 布尔 | true | — | ☆ 伤害/治疗飘字 |

#### 2.7 `footstepAccents`（脚步点缀）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableAccents | 布尔 | true | 脚步点缀总开关 |
| enableArmorAccents | 布尔 | true | 盔甲点缀 |
| inferArmorClass | 布尔 | true | 按盔甲材质推断档位，让其它模组的盔甲也有踏步副音。已显式指定规则的盔甲不受影响 |
| enableWetSurfaceAccents | 布尔 | true | 雨天/含水方块点缀 |
| enableFloorSqueaks | 布尔 | true | 吱呀方块点缀 |

#### 2.8 `particleTweaks`（粒子微调）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| suppressProjectileParticleTrails | 布尔 | false | 隐藏弹射物粒子尾迹 |
| suppressPotionParticles | 布尔 | false | — | ☆ 隐藏玩家药水粒子 |

#### 2.9 `compassAndClockOptions`（指南针与时钟）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableClock | 布尔 | true | — | 手持时钟显示时间 |
| enableCompass | 布尔 | true | — | 手持指南针显示方位 |
| enableTreasureDistance | 布尔 | true | — | 藏宝图到宝藏目标的距离 |
| compassStyle | 枚举 | TRANSPARENT_WITH_INDICATOR | OPAQUE, TRANSPARENT, OPAQUE_WITH_INDICATOR, TRANSPARENT_WITH_INDICATOR | 指南针渲染样式 |
| scale | 双精度 | 1.0 | 0.5–4 | 显示缩放 |
| enableLowDurabilityHighlight | 布尔 | true | — | 高亮即将损坏的快捷栏物品 |
| lowDurabilityThreshold | 整数 | 10 | 1–50 | 触发高亮的剩余耐久值 |

#### 2.10 `weatherOptions`（天气效果）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableDesertSandstorm | 布尔 | true | 沙漠沙尘暴 + 黄幕 |
| enableNetherDust | 布尔 | false | 下界尘埃雨 |
| enableBiomeFogColor | 布尔 | true | 群系雾色着色（biomes.json 的 fogColor） |

#### 2.11 `fogOptions`（雾效）
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
| morningFogDensity | 双精度 | 1.0 | 0–1 | 晨雾在清晨窗口内达到满强度的早晚（1.0 = 仅在峰值时刻达到，越小则雾越平缓地增强；0 = 关闭）。近平面无法比该类型的 reserve 更近，因此范围止于饱和点 |
| biomeFogDensity | 双精度 | 1.0 | 0–2 | 群系雾密度（0 关闭） |
| weatherFogDensity | 双精度 | 1.0 | 0.25–4 | 天气雾密度 |

#### 2.12 `speechBubbles`（聊天气泡）
| 选项 | 类型 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| enableSpeechBubbles | 布尔 | false | — | ☆ 玩家头顶聊天气泡 |
| enableEntityChat | 布尔 | false | — | ☆ 村民/生物头顶气泡 |
| speechBubbleDuration | 双精度 | 7.0 | 5–15 | 气泡显示秒数 |
| speechBubbleRange | 整数 | 16 | 16–32 | 气泡可见距离（格） |

#### 2.13 `auroraOptions`（极光）
| 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enableAurora | 布尔 | true | 极光渲染 |

#### 2.14 `otherOptions`（其它）
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

这些都在 jar 内 `assets/dsurround/` 下。

**同一件事有两种写法 —— 按范围选。** 下面每一个段都可以写成独立文件，也可以写成聚合文件 `dsurround.json` 的一个段；两者用**同一个 codec** 解码，所以同一条内容在两处的含义完全相同。

| 你想做的事 | 写成 | 为什么 |
| --- | --- | --- |
| 只改一两处（某群系的雾、某方块的音） | 按类型的文件，如 `biomes.json` 里只写你那几条 | 要读、要审的东西最少 |
| 描述整个模组（大量方块 + 群系 + 声音） | 一份 `dsurround.json`，每类一段 | 即 1.12.2 的做法：一个模组一个文件，而不是把同一个模组的条目撞散到十几个文件里 |
| 完全不放进 mod 的 jar | 两种都行，放到磁盘配置目录 | 见 §4.0 |

本节其余内容与你选哪种无关。

**别的包提供同名文件时，与我们的数据是「合并」而不是「替换」** —— 加载器会把能看到的每一份都读进来：

| 文件类型 | 合并方式 |
| --- | --- |
| `tags/**` | **纯并集**，`replace` 字段被忽略 |
| `sound_factories.json`、`variators.json` | 同 key 后读到的覆盖先读到的 |
| `sound_mappings.json` | 规则级合并，具体规则被插到兜底规则之前 |
| `biomes.json`、`blocks.json`、`dimensions.json` | 追加；标量字段后者覆盖，列表累加 |

**磁盘配置目录在 jar 与资源包之后读取**，所以它才是覆盖出厂值的可靠位置（见 §4.0）。
**任何包都无法删除我们自带的条目** —— 让某个方块静音的办法是写一条更具体的规则，
把它指向 `dsurround:footsteps.none`。

改完游戏内 `/dsreload` 热重载。

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

#### 4.0 为自己的模组添加方块 —— **任何模组都能自带这些文件**

这些数据文件**不是 DS 私有的**。加载器会跨**所有已加载命名空间**扫描 `dsconfigs/` 路径，所以模组可以自行描述自己的方块，完全不用改 DS：

```
<你的模组>.jar
└── assets/<你的模组id>/dsconfigs/
    ├── sound_mappings.json     ← 为自己的 <你的模组id>:* 方块追加规则
    ├── blocks.json             ← （可选）每个方块的粒子/环境音
    ├── biomes.json             ← （可选）自己群系的环境音
    └── tags/…                  ← （可选）自己命名空间下的原版格式 tag
```

**同一个**声音事件下的规则是**合并**而不是替换：你的方块匹配项会被插到 DS 的兜底默认规则**之前**，所以具体规则一定优先。你不需要（也不应该）整份复制 DS 的文件。

另外还有两处可以放同样的文件：

| 位置 | 谁提供 |
| --- | --- |
| **任何**模组 jar 或资源包内的 `assets/<命名空间>/dsconfigs/<文件>.json` | 模组作者、整合包作者 |
| `<游戏目录>/config/dsurround/configs/<命名空间>/<文件>.json` | 整合包作者、玩家（命名空间必须是**已加载**的模组，否则忽略） |

**一个条目都不写会怎样**：脚步音效没有映射的方块**不会被静音** —— DS 会回退到把方块**自己的**原版脚步声放进 DS 的脚步管线（步频、音量、附加音）。结果依然听得见，只是拿不到 DS 材质（黄铜方块听起来还是模组自己给它的声音，而不是 `hardmetal`）。要彻底不发声，把规则指向保留的静音工厂 `dsurround:footsteps.none`（等价于原版模组的 `NOT_EMITTER`）。

最小示例 —— 让你的大理石方块用 DS 的大理石脚步，其余保持原版石头兜底：

```json
[
  {
    "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["你的模组id:marble", "你的模组id:marble_bricks"], "factory": "dsurround:footsteps.marble" }
    ]
  }
]
```

`blocks` 支持方块 ID、方块状态（`你的模组id:block[prop=value]`）或 `#命名空间:tag` 引用 —— **优先用 tag**，以后加方块不用改配置。

**按方块名推断材质。** 当没有任何规则命中、只剩兜底默认规则时，DS 会先看一眼方块的注册名，
再决定是否接受泛化材质：模组的 `xxx_sandstone` 解析成 `concrete`，`*_limestone` / `*_jasper` /
`*_shale` / `*_permafrost` / `*_marble` 解析成 `marble`，`*_copper` 与 `*_raw_copper` 解析成
`copper`，等等。之所以需要它，是因为规则按显式方块 ID 匹配，模组做的"原版方块翻版"否则会拿到错的材质。

- 优先级为 **显式规则 → 名字推断 → 兜底默认**。上面文件里写的东西永远优先，因此推断**不可能覆盖**
  有意写下的数据。
- 把 `entityEffects.inferFootstepMaterial` 设为 `false` 可以整体关掉这一步。
- 匹配按**词元边界**，不是子串：`deepslate` 不等于 `slate`。
- 名字更像装饰而非材质的会被跳过（`*_sapling`、`*_leaves`、`potted_*`、`*_flower`、
  `*_blossom` 等），所以 `snowblossom_leaves` 不会被当成雪。
- 关键词表很短，写在 `MaterialInference` 里，**不是**数据驱动的。若你在意的材质没有被推断，
  直接写显式规则（§4.1）——今天就生效，而且永远优先。
- 推断出的材质会**连带它自己的副音层**：模组砂岩会拿到和原版砂岩一样的沙地副音——
  因为副音属于**材质**，不属于恰好列出该材质的方块清单。
- 被推断的方块会在 `RESOURCE_LOADING` 调试标记下打日志，每个方块只报一次。

#### 4.1 `sound_factories.json`（数组）
| 字段 | 类型 | 含义 |
| --- | --- | --- |
| location | 字符串 | 代码请求的工厂 key（如 `dsurround:toolbar.tool.equip`） |
| soundEvent | 字符串 | 实际播放的声音事件（必须存在于 `sounds.json`） |
| category | 字符串 | MC 声音类别（AMBIENT、PLAYER…），决定走哪个音量滑块 |
| volume | 数值 | 基础音量倍率 |
| pitch | 对象 | 可选 `{"min":0.8,"max":1.2}` 随机音调区间 |
| land | 对象 | 可选的落地合成（见下） |
| wander | 字符串 | 可选的急停擦地音覆盖 |
| jump | 字符串 | 可选的起跳音覆盖 |

> 设计上 `location` ≠ `soundEvent`：工厂名稳定，实际播放的声音可重定向。

**`land` —— 一个材质怎么落地。** 落地不是一个声音：它是主层 + 可选的轻一点的副层
+ 可选的延迟回声，每只脚各播一次，在混音器里相加。**这种相加是落地能比脚步「厚重」的唯一途径**，
因为单个声音的增益是有上限的。

```jsonc
"land": {
  "primary":   "dsurround:footsteps.concrete_run",   // 必填，否则整块无效
  "secondary": "dsurround:footsteps.stone",          // 可选，默认倍率 0.5
  "echo":      "dsurround:footsteps.stone_run",      // 可选
  "secondaryScale": 0.5,                             // 可选
  "echoVolume": 1.0,                                 // 可选
  "echoDelayMinTicks": 1, "echoDelayMaxTicks": 2     // 可选
}
```

没有 `land` 块的材质回退到：它自己的 land/run 声每只脚一次 + 延迟回声（即
1.12.2 的 `playMultifoot` 而无配置合成）。主层往往是**另一个材质**的 run 录音而不是自己的：
石头落地用 `concrete_run`，大理石用它自己的 `_run`。

**`wander` 与 `jump` —— 跨材质引用。** 原始数据里有好几个材质的急停/起跳用的是**另一个材质**的录音：
金属箱与金属条擦出大理石刮擦声，木/沙/玻璃/流沙/落叶用泥土，地毯与草径用草地。
只有没有覆盖时才用材质自己的 `_wander`。`jump` 回退到 `wander`，这正是 1.12.2
`EventType.JUMP(WANDER)` 的含义 —— 所以只有两者不同时才需要写 `jump`。

三者都读取**材质自己的**工厂条目，而材质就是 `sound_mappings.json` 为那个方块解析出的东西 ——
所以重调一个材质的落地只需改那一条。

#### 4.2 `sound_mappings.json`（数组）
把传入的原版声音事件重映射到 DS 工厂：
```json
{ "soundEvent": "minecraft:block.anvil.step",
  "rules": [ { "factory": "dsurround:footsteps.metalbox" } ] }
```

#### 4.3 `biomes.json`（数组）
| 字段 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| biomeSelector | 字符串 | — | 基于群系 tag 的表达式（见下） |
| _comment | 字符串 | — | 可选标签 |
| priority | 整数 | 0 | 规则按升序应用，所以靠后的规则逐字段覆盖前面的 |
| clearTraits | 布尔 | false | 先丢弃全部自动识别出的 trait，再应用 `traits` |
| traits | 数组 | `[]` | 要添加的 trait 名，如 `["FOREST","COLD"]` |
| clearSounds | 布尔 | false | 清空已累加的 loop / mood / addition 声音 |
| resetFogColor | 布尔 | false | 清除更早规则设过的雾色 |
| fogColor | 颜色 | — | 群系雾色着色（见 `weatherOptions.enableBiomeFogColor`） |
| dustColor | 颜色 | — | 尘埃效果的着色 |
| fogDensity | 枚举 | — | `none` / `light` / `normal` / `medium` / `heavy` |
| additionalSoundChance | 字符串 | 0.008 | `addition` 类声音的每刻概率 |
| moodSoundChance | 字符串 | 0.008 | `mood` 类声音的每刻概率 |
| acoustics | 数组 | `[]` | `{"factory":"dsurround:biome.wind","conditions":"weather.isRaining()","weight":10,"type":"loop"}` |

`clearTraits` 与 `resetFogColor` 存在的意义是让整合包能**纠正**而不只是追加：
tag 与名称分析器在所有规则之前运行，没有 `clearTraits` 就只能往它们的输出上加；
而没有 `resetFogColor`，一旦雾色被某条规则设过，就只能换成另一种颜色，**永远回不到原版**。
给规则一个比我们更高的 `priority`（我们最高用到 100）就能确保生效。
`resetFogColor` **只清除 DS 自己的雾色** —— 数据包给群系设的雾色不受影响 —— 也不动 `dustColor`。

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

**tag 文件可以放哪里。** `assets/<命名空间>/dsconfigs/tags/**` 模组 jar 与资源包都行；
而真正的数据包路径 `data/<命名空间>/tags/**` **只从已加载的模组 jar 读取** ——
`ServerResourceFinder` 遍历的是模组列表，不是世界的数据包，资源包无法提供它。

#### 4.8 `chat/<lang>.lang` —— 生物气泡台词
格式（文件头已注明）：`chat.<实体>.<序号>=权重,文本`。`villager.flee` 是特殊的逃跑台词表；`$MINECRAFT$` 播放随机原版闪烁标语。文件名跟随客户端语言（`en_us.lang`、`zh_cn.lang`）。

#### 4.8.1 `popoffNumbers` —— 伤害/治疗/暴击文字

控制实体受伤、被治疗、被暴击时弹出来的文字。全部是滑条，可以直接在游戏里调。

| 选项 | 默认 | 含义 |
| --- | --- | --- |
| `sizePercent` | 73 | **起始**大小，相对 1.12.2 原版的百分比。文字会在峰值放大到约 1.9 倍，所以这一项决定"变化幅度"看起来明不明显 |
| `growFactor` | 114 | 每刻放大比例（%） |
| `peakTickTicks` | 5 | 在第几刻达到最大 |
| `gravityPercent` | 80 | 下落速度，原版重力的百分比 |
| `lifetimeTicks` | 17 | 存在时长（刻），20 刻 = 1 秒，所以 17 刻 = 0.85 秒 |
| `driftPercent` | 60 | 水平抛出距离，原版抛出量的百分比。正值朝远离攻击者方向，负值朝攻击者方向，0 表示垂直上升 |

**设置界面里能看到什么。** 只有「文字大小」一项。其余六项都标记为 `@Hidden`：它们仍写在
`dsurround.json` 里，可以手工修改，`/dsreload` 也会读取，但**刻意不放进界面**。这些默认值是照着
1.12.2 原版动画调出来的，把放大率和重力暴露出来更容易被误调而不是带来帮助 —— 而且 1.12.2 原版这个
功能本来也只有两个开关。

**第一人称下不显示自己的字幕。** 属于本地玩家的文字，在第一人称视角下**永远不绘制** ——
1.12.2 原版的做法是在创建时就不生成，移植版另外在绘制时再挡一道，所以挨了一下之后按 F5 切换视角，
自己的数字也不会残留在视野里横着。第三人称（含前置视角）照常显示。怪物和其它玩家的字幕不受影响。

**变化形状。** 尺寸是**由"年龄"直接算出来的函数**，不再逐刻累乘，所以首帧和末帧**在构造上严格相等**：

```
尺寸(age) = 放大率 ^ age                                    当 age <= 峰值刻
          = 放大率 ^ 峰值刻 × 缩小率 ^ (age - 峰值刻)         之后

缩小率    = 放大率 ^ (-峰值刻 / (时长 - 1 - 峰值刻))   默认值即 1.14 ^ (-5/11) = 0.9413
```

默认值下整段动画为（17 刻，0.85 秒）：

```
刻     0    1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16
尺寸 1.00 1.14 1.30 1.48 1.69 1.93 1.81 1.71 1.61 1.52 1.43 1.35 1.27 1.20 1.13 1.06 1.00
```

前 5 刻升到 1.93 倍，随后 11 刻一边下落一边缩回**与起始完全相同**的大小，后半段同时淡出。
总时长 0.85 秒，水平位移几乎为零。

> 首末等大是这个公式的**必然结果**：改 `lifetimeTicks` 或 `peakTickTicks` 之后，缩小率会自动
> 重算以维持这一点。早期版本是逐刻累乘的，导致首帧相当于 `放大率`、末帧远小于 1.0，
> 于是文字结束时明显比出现时小。

**两个滑条会互相影响。** `lifetimeTicks` 与 `gravityPercent` 共同决定下落距离：重力不变时时长越长
一定落得越远。默认值是按"0.85 秒、下落约 1.8 格"解出来的 —— **只加时长而不动重力，文字会掉得
远低于目标**（曾经落到 3.4 格，几乎掉出视野）；重力调得太小则会飘着不落。两者都是 `@Hidden`，
改完只影响配置文件；自检会在"配置文件里的值与仓库默认值不一致"时报告，因为**已存在的配置文件
永远优先于代码默认值**。

**为什么没有"缩小比例"滑条。** 手调的缩小率无法同时满足"放大要快"和"结束时回到起始大小"：要么
文字还没缩回就消失了，要么结束时比开始时更大。控制形状的旋钮是「最大尺寸所在刻」。

#### 4.9 一个模组一个文件 —— 聚合 `dsurround.json`

上面的数据文件是**按类型**拆分的：对模组自带的内部数据最方便，但给第三方模组写适配时就很别扭
—— 一个模组的规则要散落到五六个文件里。为此提供**一个文件承载全部段落**的写法，形态与
1.12.2 当年的 `data/<modid>.json` 一致：

```
config/dsurround/configs/<namespace>/dsurround.json
assets/<namespace>/dsconfigs/dsurround.json          （模组 jar 内的等价位置）
```

**段落名就是专用文件的名字**，每个段落用**相同的 codec** 解码，所以同一条规则放在哪边含义完全一样：

```json
{
  "sound_mappings": [ { "soundEvent": "minecraft:block.stone.step",
                        "rules": [ { "blocks": ["yourmodid:marble"], "factory": "dsurround:footsteps.marble" } ] } ],
  "blocks":         [ { "blocks": ["yourmodid:ember"], "effects": [ { "effect": "fire_jet", "spawnChance": "0.005" } ] } ],
  "biomes":         [ { "biomeSelector": "biome.id == 'yourmodid:ashen_waste'", "acoustics": [ { "factory": "biome.wind" } ] } ]
}
```

可用的段落：`sound_mappings`、`blocks`、`biomes`、`dimensions`、`sound_factories`、`variators`、
`entity_variators`、`critwords`。文件里的其它键会被忽略。

**优先级**：两边的内容**都会生效**。专用 `<段落名>.json` 与聚合文件里的同段内容会被合并；
在同一个映射里，**更具体的规则胜出**（带方块匹配的规则会插到兜底默认之前）。所以内建数据不会
被误覆盖，同时聚合文件依然能给它追加内容。

**写错不会拖垮数据**：文件按原始 JSON 读取，只有需要的那个段落会交给 codec。段落缺失、写成 null、
或者类型不对时该段不生效，**同文件里的其它段落照常生效**；未知键安静忽略。

#### 4.9.1 怎么测试

1. 建好文件（`<namespace>` 目录名必须是**已加载模组**的 id，否则整个目录会被忽略）：

```json
{
  "sound_mappings": [
    { "soundEvent": "minecraft:block.stone.step",
      "rules": [ { "blocks": ["yourmodid:some_stone_block"], "factory": "dsurround:footsteps.wood" } ] }
  ],
  "biomes": "这里故意写错类型，用来验证容错",
  "notASection": { "未知键会被忽略": true }
}
```

2. 游戏里执行 `/dsreload`，然后走到 `yourmodid:some_stone_block` 上：脚步声应当变成**木料声**
   （原本是石头声）。这就是聚合文件的段落生效了。如果没变，用 `/dsdump steps` 打出你所站位置的
   完整取面判定链来定位。
3. 确认加载器确实读了这个文件，而且坏段落只报不废。数据自检会列出它：进入世界时写入
   `logs/latest.log`，`/dsdump validate` 也会把同一份报告打到聊天栏。预期看到一条写明
   `dsurround.json` 与 `biomes` 段落失败原因的条目 —— 同时 `sound_mappings` 仍然生效，
   这正是"分段独立读取"的意义。
4. 把文件删掉（或改名成 `dsurround.json.disabled`）再 `/dsreload`：该方块恢复石头声，
   证明之前的效果确实来自你的文件。

#### 4.10 `critwords.json` —— 暴击时弹出的拟声词

一个纯 JSON 字符串数组。词会显示在被暴击的实体上方（末尾自动加感叹号），朝远离攻击者的方向
抛起并逐渐变大。

```json
["哐", "砰", "咚", "啪"]
```

**怎么加自己的词。** 放一个同名文件即可，它会与内置词表**合并**（不会替换默认值）。文件可放在
§4.0 列出的任一位置：

- 模组内：`assets/<你的模组id>/dsconfigs/critwords.json`
- 磁盘上：`config/dsurround/configs/<任意名字>/critwords.json`（在 jar 之后加载，所以整合包既能
  给自己的词，也能给别的模组的词表追加）

多个来源按加载顺序**拼接**，文件越多、词池越大。允许重复（重复只会让该词出现得更频繁）；条目
会去掉首尾空白、忽略空串，并限制在 4096 条以内。也可以作为聚合文件（§4.9）的 `"critwords"`
段落提供。

**关于语言。** 内置词表沿用 1.12.2 原始的英文拟声词（AIEEE、BONK、KAPOW、ZZZZWAP…），**没有做
翻译**。由于词表是数据，想本地化就直接换成你自己的词 —— 上面那个例子就是中文拟声词。

> 行为说明（供与原版对照）：字号、变大速率、抛起曲线都取自 1.12.2 的 `ParticleTextPopOff` ——
> 文本高度 `0.024` 世界单位/字体像素、每 tick `×1.08`、初速度归一化到总长 `0.12`、重力 `0.8`。
> 三个版本的渲染管线不同（1.12.2 是三维粒子通道，这里是投影到二维 GUI 层），但**用的是同一组
> 数值**，所以屏幕上的效果是一致的。

#### 4.11 `item_sounds.json` —— 逐物品的挥动与切换音

快捷栏切换（equip）与挥动（swing）音按四步选，**本文件最先被查**：

| # | 步骤 | 粒度 |
| --- | --- | --- |
| 1 | **本文件** —— 物品 id 或 tag → 一个工厂 | 单件物品，或一个 tag |
| 2 | 覆写 `sound_factories.json` 里的 `dsurround:toolbar.<类>.swing` | 整个类别 |
| 3 | 把物品加进 `tags/item/effects/<类>.json` | 归入已有类别 |
| 4 | 新建类别 | 需改代码 —— 类别表是 Java 枚举 |

第 1 步就是"没被任何 tag 覆盖的物品也能有自己的音色"的原因，**不需要改模组**。
它是规则数组，**先匹配先赢**；`items` 必填，且 id 必须全限定 —— 不含 `:` 的裸名会直接报错。

```jsonc
// assets/<你的命名空间>/dsconfigs/item_sounds.json
[
  { "items": ["mymod:katana"],   "swing": "mypack:katana.swing" },
  { "items": ["#mymod:katanas"], "equip": "mypack:katana.equip" }
]
```

`swing` 与 `equip` 都可选，省略即不覆盖该动作。

**工厂必须先存在。** 与 `biomes.json` / `blocks.json` 不同，本文件**不会**回退成
"把 location 当声音事件用" —— 指向未定义工厂的 `swing` 会被当成"没写"，
物品**静默**保留类别默认音。所以先在 `sound_factories.json` 里定义它：

```jsonc
// assets/<你的命名空间>/dsconfigs/sound_factories.json
[
  { "location": "mypack:katana.swing", "soundEvent": "mypack:item.katana.swing",
    "category": "PLAYER", "volume": 0.5, "pitch": { "min": 0.8, "max": 1.2 } }
]
```

再把事件本身注册到原版位置 `sounds.json`：

```json
{ "item.katana.swing": { "sounds": ["mypack:katana_swing"] } }
```

事件名**建议以 `item.` 开头**，这样会跟随游戏内"玩家音效"音量滑块 ——
该判断看的是事件路径前缀（`item.` / `toolbar.` / `player.`），不是声音类别。

**完整例子 —— 给模组武士刀配专属挥动音。** 把上面三个文件放进同一个资源包，
执行 `/dsreload`，武士刀就用你的音色，其它剑不变。**模组本身一行都不用改。**

### 5. 指令

| 指令 | 端 | 说明 |
| --- | --- | --- |
| `/dsreload` | 客户端 | 无需重启重载数据文件（§4）。可通过 `logging.registerCommands` 关闭注册。 |
| `/bubble <文本>` | 服务端 | 在发送者头顶显示 `<文本>` 气泡 30 秒，30 格内所有装了 DS 的客户端可见（含自己）。消息**不进聊天栏**。需服务器装 DS；原版客户端看不到。 |

### 6. 跨版本差异

三个版本功能等价，差异都在内部实现：

- **声音剪枝**（`soundSystem.enableSoundPruning`）：三版均生效。1.20.1 在早期构建里该键存在但不生效（剪枝逻辑未移植），现已移植补齐。**无需改动配置文件**：键本来就存在于 `dsurround.json`，默认值相同（`true`），现在只是真正生效了。
- 雾渲染挂载点：1.20.1 用 Forge 原生 `ViewportEvent.RenderFog` 事件、1.21.1 用 mixin、26.1 走原生雾管线。
- 其余内部差异（单声道转换用反射还是访问器 mixin、tag 同步走包监听还是收集器）对用户无感知。
