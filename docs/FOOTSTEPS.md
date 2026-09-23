# Footstep System Reference — 脚步声系统参考

> 本文档由 `_gen_footsteps_doc.py` 从数据文件与源码常量生成材质表，其余为手工维护。
> 相关文档：[CONFIGURATION.md](../CONFIGURATION.md)（配置与数据文件总览）、
> `dsdump steps`（运行期取面判定链）。
> Shared by all three ports (1.20.1 / 1.21.1 / 26.1). Written against the 1.21.1 port;
> version differences are marked inline. If a statement does not match this port, the **code is
> authoritative** - fix the document rather than trusting it.
> 三版共用（1.20.1 / 1.21.1 / 26.1）。以 1.21.1 移植版为基准撰写，版本差异在正文中就地标注；
> 若发现某处与本移植版不符，**以代码为准并修正文档**。

---

## Part I — English

### 1. Overview

A footstep in this mod is the result of **two independent stages**. Confusing them is the single
biggest source of "my rule does nothing" reports, so they are kept strictly apart:

```
STAGE 1  surface resolution    "which block is the player actually standing on?"
   FootstepGenerator.resolveSurfaceBlock(entity, level, pos)      [code]

STAGE 2  material resolution   "which recording does that block sound like?"
   block.getSoundType().getStepSound()  ->  sound_mappings.json  ->  sound_factories.json
```

Stage 2 is pure data. Stage 1 is pure code and cannot be configured — it answers the vanilla
question "what is underfoot", including blocks vanilla's own collision lookup is blind to
(no-collision shapes like rails, and blocks the player stands *in* rather than *on*).

### 2. Stage 2 — the material system

#### 2.1 Vocabulary

| Term | Meaning | Example |
| --- | --- | --- |
| **sound event** | The vanilla event a block's `SoundType` reports | `minecraft:block.stone.step` |
| **material / factory** | A `dsurround:` entry in `sound_factories.json` naming a recording and its pitch/volume | `dsurround:footsteps.marble` |
| **rule** | One `{blocks, factory, accent?}` inside an event's entry in `sound_mappings.json` | see below |
| **accent** | An extra factory layered on top of a step **at low volume** | `dsurround:footstep_accent/sand` |
| **variant** | A separate factory differing only in pitch/volume/recording: `_run`, `_land`, `_wander` | `footsteps.marble_run` |

There are **185 factories** in total: 102 `footsteps.*` materials,
3 `footsteps/*` specials, 10 accents, and the rest are
biome/player/effect sounds. **111 sound events carry mapping rules**, of which
109 are `.step` events.

#### 2.2 Resolution order inside one event

`SoundMapping.findMatch(state)` walks the rules **in order** and returns on the **first** match.
A rule with an empty `blocks` list is the **catch-all default**; every event that has one must keep
it **last**, otherwise everything after it is unreachable. Currently **110** events
have a catch-all.

When the catch-all matches, the material is not taken from it directly: the block's registry name
is consulted first (`MaterialInference`) so a modded `anything_sandstone` resolves to `concrete`
instead of generic `stone`. Precedence is therefore:

```
explicit rule  →  name inference  →  catch-all default
```

#### 2.2.1 Data invariants

These hold in the shipped data and are worth re-checking after any edit:

* **exactly one catch-all per entry, and it is last** — 110 entries comply, 0 violate (verified by
  `_verify_catchall_invariant.py`);
* an event may be described by **several entries** (13 supplementary entries exist, e.g. the
  built-in Quark adaptation). They carry no catch-all, so all their rules are reachable; merging
  inserts a user rule whose factory is new **before the first catch-all of the merged list**;
* a block id absent from the running version becomes `MatchOnNothing`, which **warns** and never
  matches — such a rule is inert, not fatal.

#### 2.3 Materials

`pitch`/`volume` are the factory's randomization range; `plays` is the sound event the factory
resolves to (for many variant materials it is deliberately the *base* recording — the variant is
only a pitch/volume change).

('| material (`dsurround:` prefix omitted) | pitch | volume | plays (sound event) | variants of this material |\n| --- | --- | --- | --- | --- |\n| `footsteps.bamboo` | - | 1.0 | `dsurround:footsteps.bamboo` | land |\n| `footsteps.bedrock` | 0.55–0.6 | 0.9–1.0 | `dsurround:footsteps.stone` | run |\n| `footsteps.bluntwood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.bluntwood` | wander |\n| `footsteps.bone` | - | 1.0 | `dsurround:footsteps.bone` | land |\n| `footsteps.bone_dry` | 0.8–0.9 | - | `dsurround:footsteps.bone` | — |\n| `footsteps.brush_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.brush_through` | — |\n| `footsteps.calcite_soft` | 0.9–1.0 | 0.8–0.9 | `dsurround:footsteps.marble` | — |\n| `footsteps.concrete` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.concrete` | run, wander |\n| `footsteps.copper` | - | 1.0 | `dsurround:footsteps.copper` | land |\n| `footsteps.copper_dull` | 0.8–0.9 | 0.8–0.9 | `dsurround:footsteps.copper` | — |\n| `footsteps.copper_hollow` | 1.15–1.25 | 0.85–0.95 | `dsurround:footsteps.copper` | — |\n| `footsteps.dirt` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.dirt` | land, run, wander |\n| `footsteps.dripstone` | 0.6–0.7 | 0.8–0.9 | `dsurround:footsteps.stone` | — |\n| `footsteps.froglight` | - | 1.0 | `dsurround:footsteps.froglight` | land |\n| `footsteps.froglight_soft` | 0.85–0.95 | 0.8–0.9 | `dsurround:footsteps.froglight` | — |\n| `footsteps.glass` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.glass` | — |\n| `footsteps.grass` | 0.8–1.2 | 0.5 | `dsurround:footsteps.grass` | run, wander |\n| `footsteps.gravel` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.gravel` | run, wander |\n| `footsteps.honey` | - | 1.0 | `dsurround:footsteps.honey` | land |\n| `footsteps.leaves_crunch` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.leaves_crunch` | land |\n| `footsteps.leaves_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.leaves_through` | — |\n| `footsteps.lino` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.lino` | — |\n| `footsteps.log` | 0.55–0.65 | 0.9–1.0 | `dsurround:footsteps.log` | — |\n| `footsteps.magma_soft` | 0.55–0.65 | 0.8–0.9 | `dsurround:footsteps.concrete` | — |\n| `footsteps.mangrove_roots` | - | 1.0 | `minecraft:block.mangrove_roots.step` | land |\n| `footsteps.marble` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.marble` | run, wander |\n| `footsteps.metalbar` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.metalbar` | run, wander |\n| `footsteps.metalbar_thin` | 1.12–1.22 | 0.55–0.65 | `dsurround:footsteps.metalbar` | — |\n| `footsteps.metalbox` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.metalbox` | run, wander |\n| `footsteps.moss` | - | 1.0 | `dsurround:footsteps.moss` | land |\n| `footsteps.moss_dry` | 0.85–0.95 | - | `dsurround:footsteps.moss` | — |\n| `footsteps.moss_soft` | 1.05–1.15 | - | `dsurround:footsteps.moss` | — |\n| `footsteps.mud` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.mud` | wander |\n| `footsteps.muffledice` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.muffledice` | — |\n| `footsteps.organic` | 0.8–1.2 | 0.4–0.45 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_dry` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_shell` | 0.9–1.0 | 0.9–1.0 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_spongy` | 1.08–1.18 | 0.8–0.9 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_waxy` | 1.15–1.25 | 0.55–0.65 | `dsurround:footsteps.organic` | — |\n| `footsteps.quartz_powdery` | 0.95–1.05 | 0.85–0.95 | `dsurround:footsteps.marble` | — |\n| `footsteps.quicksand` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.quicksand` | — |\n| `footsteps.rails_land` | - | 0.2 | `dsurround:footsteps.metalbar_run` | — |\n| `footsteps.rug` | 0.8–1.2 | 0.7 | `dsurround:footsteps.rug` | — |\n| `footsteps.sand` | 0.8–1.2 | 0.5 | `dsurround:footsteps.sand` | run |\n| `footsteps.sculk` | - | 1.0 | `dsurround:footsteps.sculk` | land |\n| `footsteps.sculk_vein` | - | 1.0 | `dsurround:footsteps.sculk_vein` | land |\n| `footsteps.shroomlight` | - | 1.0 | `dsurround:footsteps.shroomlight` | land |\n| `footsteps.slime` | - | 1.0 | `dsurround:footsteps.slime` | land |\n| `footsteps.snow` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.snow` | run, wander |\n| `footsteps.squeakywood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.squeakywood` | wander |\n| `footsteps.stone` | 0.65–0.7 | 0.9–1.0 | `dsurround:footsteps.stone` | run, wander |\n| `footsteps.tuff` | - | 1.0 | `dsurround:footsteps.tuff` | land |\n| `footsteps.tuff_soft` | 0.8–0.9 | 0.85–0.95 | `dsurround:footsteps.tuff` | — |\n| `footsteps.tuff_thin` | 0.9–1.0 | 0.8–0.9 | `dsurround:footsteps.tuff` | — |\n| `footsteps.water_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.water_through` | — |\n| `footsteps.wax_dry` | 0.95–1.05 | 0.5–0.55 | `dsurround:footsteps.organic` | — |\n| `footsteps.weakice` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.weakice` | — |\n| `footsteps.wood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_click` | 1.35–1.5 | 0.45–0.55 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_door` | 1.1–1.2 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_fence` | 1.12–1.22 | 0.6–0.7 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_shelf` | 1.05–1.15 | 0.75–0.85 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_sign` | 1.18–1.28 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_sticky` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_thin` | 1.08–1.18 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps/dirt_path` | 1.0–1.2 | 0.9–1.0 | `dsurround:footsteps.grass` | — |\n| `footsteps/ladder` | 0.95–1.05 | 1.0 | `minecraft:block.ladder.step` | land |', 38)

**Composite names.** `footsteps.brickstone/concrete` and `footsteps.obsidian/stone` name two
recordings; the slash means "primary/secondary" and the player mixes them (see §3.4).

**The silence sentinel.** `dsurround:footsteps.none` is a real value meaning *play nothing*
(the modern `NOT_EMITTER`). Buttons use it. Note the consequence for surface resolution: a cell
whose block is mapped to `none` is **not** treated as an explicit material — otherwise a silent
proxy would win over the block actually stood on.

#### 2.4 Accents

An accent is layered on top of the primary step so one recording can carry a second texture
(e.g. `concrete` + a faint sand hiss on sandstone).

| accent (`dsurround:` prefix omitted) | pitch | volume | plays | used by |
| --- | --- | --- | --- | --- |
| `footstep_accent/amethyst` | 0.9–1.1 | 0.3 | `minecraft:block.amethyst_block.break` | 2 event(s) |
| `footstep_accent/brush` | 0.8–1.2 | 0.3 | `dsurround:footsteps.brush_through` | 1 event(s) |
| `footstep_accent/chain_rattle` | - | 0.28 | `dsurround:armor.medium_walk` | 1 event(s) |
| `footstep_accent/glass` | 0.95–1.05 | 0.9–1.0 | `dsurround:footsteps.glass` | 3 event(s) |
| `footstep_accent/mud` | - | 0.8 | `dsurround:footsteps.mud` | **UNUSED** |
| `footstep_accent/mud_quiet` | - | 0.2 | `dsurround:footsteps.mud` | 6 event(s) |
| `footstep_accent/muffledice` | - | 0.4 | `dsurround:footsteps.muffledice` | 1 event(s) |
| `footstep_accent/rail_plank` | - | 0.45 | `dsurround:footsteps.wood` | 1 event(s) |
| `footstep_accent/sand` | - | 0.1 | `dsurround:footsteps.sand` | 5 event(s) |
| `footstep_accent/weakice` | - | 0.1 | `dsurround:footsteps.weakice` | 1 event(s) |

An accent marked **UNUSED** is registered but referenced by no rule and no landing composition.
`footstep_accent/mud` is in that state: `footstep_accent/mud_quiet` (volume 0.2) replaced it when the
sticky-piston-head rule was retuned, and the louder original was left behind. It is harmless (an
unreferenced factory is never played) but it should be deleted or reused.

> `footsteps.mud` appearing as a *land-composition secondary* is a material factory, not an accent —
> do not confuse the two namespaces.

#### 2.5 Armour accents

Armour does not use `sound_mappings`; it is resolved from the worn item through DS item tags and
then read as a **sound event**, not a factory:

| event | note |
| --- | --- |
| `armor.crystal_foot` | `dsurround:footsteps/armor/crystal_footN` | 4 take(s) |
| `armor.crystal_run` | `dsurround:footsteps/armor/crystal_runN` | 6 take(s) |
| `armor.crystal_walk` | `dsurround:footsteps/armor/crystal_walkN` | 6 take(s) |
| `armor.heavy_foot` | `dsurround:footsteps/armor/heavy_footN` | 3 take(s) |
| `armor.heavy_run` | `dsurround:footsteps/armor/heavy_runN` | 9 take(s) |
| `armor.heavy_walk` | `dsurround:footsteps/armor/heavy_walkN` | 6 take(s) |
| `armor.light_run` | `dsurround:footsteps/armor/light_N` | 3 take(s) |
| `armor.light_walk` | `dsurround:footsteps/armor/light_N` | 3 take(s) |
| `armor.medium_foot` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.medium_run` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.medium_walk` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.slimey_run` | `dsurround:footsteps/armor/slime_runN` | 6 take(s) |
| `armor.slimey_walk` | `dsurround:footsteps/armor/slime_walkN` | 6 take(s) |

Base classes: `armor.light` (leather), `armor.medium` (chain), `armor.heavy` (iron/gold/netherite),
`armor.crystal` (diamond), `armor.slimey`. Variants are `_walk`, `_run` and — where a dedicated
recording exists — `_foot`; `leather` has no `_foot` and falls back to `_walk`.
`ArmorAccents` picks the running variant while sprinting and reads feet → legs → chest, so a single
piece of armour is enough.

#### 2.6 Adding a material

```json
[
  {
    "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["yourmodid:marble", "#yourmodid:polished_stone"],
         "factory": "dsurround:footsteps.marble",
         "accent": ["dsurround:footstep_accent/sand"] }
    ]
  }
]
```

Rules for a **new** event go in their own file; rules for an **existing** event are merged into it
and are inserted **before the catch-all default**, so a specific rule always wins. Put this file in
any of the three locations listed in CONFIGURATION.md §4.0.

### 3. Stage 1 — the footstep generator

#### 3.1 What runs when

| Player action | Handler | Sound |
| --- | --- | --- |
| Walking/running | `FootstepGenerator.playStep` | material (+ `_run` variant when sprinting), plus accents |
| Leaving the ground | `playJump` | `player.jump` grunt + the material's `_wander` (or a `JUMP_OVERRIDES` entry) |
| Landing | `playLand` | `LAND_COMPOSITIONS` or the `_land`/`_run`/base fallback, two feet + echo, plus armour |
| Climbing a ladder | `playStep` while `onClimbable` | the ladder material (vanilla `block.ladder.step` boosted) |
| Stopping/turning sharply | stop-scuff | the material's `_wander` |
| Any managed creature | `CreatureFootstepGenerator` | the creature's own variator + the surface material |
| Walking through foliage | `StepThroughBrushEffect` | one of `crop_step` / `straw_step` / `brush_step` |

#### 3.1.1 Brush-through sounds (brush / straw / crop)

`StepThroughBrushEffect` samples every 2 ticks and only fires **when the feet cell changes**
(1.12.2's `messyPos` dedup), so standing still inside one bush stays silent. The order is
**crop → straw → brush**, and each tag probes both the feet cell and the head cell.

| Tag | Sound | Contents |
| --- | --- | --- |
| `crop_step` | by growth stage | crops: normalised age `< 0.25` silent, `< 0.75` brush, `>= 0.75` straw (the `0.75..1.0` band also layers brush) |
| `straw_step` | `brush_step/straw` (the `leaves_through` recording) | vines, sugar cane, dead bush, big dripleaf stem, hay block |
| `brush_step` | `brush_step/brush` (the `brush_through` recording) | grass, ferns, tall grass, tall flowers, sweet berry bush, azalea, dripleaf, spore blossom, hanging roots, crop stems, nether sprouts, fungi roots, mangrove roots, seagrass, kelp, torchflower |

The crop row is the port of 1.12.2's `#wheat` / `#crop` / `#beets` BlockMap macros, which varied
by `age`; **in 1.12.2 an unripe crop was completely silent**, so a young crop must report
"matched but silent" instead of falling through to a generic brush rustle. A mod can add its own
crops to `#dsurround:effects/crop_step` and get the same semantics (it needs an int `age`).

> **Block ids differ between the three versions, so check each one when editing these tags.**
> Short grass is `minecraft:grass` in 1.20.1 and was renamed `short_grass` in 1.21+;
> `#minecraft:tall_flowers` no longer exists in 26.1 (the flowers are listed by name there).
> Writing an id from the wrong version **does not error** - the entry simply never applies, which
> is how 1.20.1 lost its short-grass brush sound. The "witness block" check in the self-check
> (`DataValidator.checkEffectTags`) exists to catch that class of mistake, and `/dsdump brush`
> prints the live decision chain at the player's position.

#### 3.2 Surface resolution (the five steps)

`resolveSurfaceBlock(entity, level, pos)` with `pos = blockPosition().below()`:

1. **a snow layer the feet are in** — checked with `isStandingOn`, because vanilla's
   collision-derived support block cannot see it. (1.20.1 and 1.21.1 have no leaf-litter block, so
   this probe is the snow layer only; 26.1 also tests `LeafLitterBlock` here and in the AABB scan
   below. The same AABB scan over the cells the feet overlap runs after step 2, for the
   standing-on-the-edge case.)
2. **a block the feet are in that is a foot overlay** — `#dsurround:effects/foot_overlay`: carpets,
   snow, lily pads, pressure plates, the four rails, sculk veins, glow lichen. This is the port of
   1.12.2's carpet/foliage substrate set. Doors and trapdoors are deliberately **not** in it.
   It must pass `isStandingOn` as well, or a block the player stands *in* would win.
3. **vanilla's `mainSupportingBlockPos`** — the collision-derived "what am I standing on".
4. **the block under the feet**, else a horizontal neighbour scan for the edge case.

`isStandingOn` is "cell Y + the collision shape's top is at or below the feet (+0.05)". The cell's own
Y **must** be added: `getCollisionShape` returns cell-local coordinates (0..1, a fence 1.5), so
comparing the raw shape height against the entity's world Y is true for every cell and the guard
silently does nothing (that bug shipped, was found in review, and `/dsdump steps` printed
`isStandingOn = true` unconditionally while it lasted). It exists because a door or an open trapdoor
is a *thin vertical panel* standing on its cell's floor: comparing heights is what tells you whether
the player is on it or merely sharing the cell with it.

> A fifth probe — "the feet-cell block by visible shape, if it is in the overlay tag" — used to sit
> between 3 and 4. It was **unreachable** (probe 2 already returns the feet cell under every
> condition the fifth probe added) and was removed in the 2026-09-17 review.

#### 3.3 Why a rule can silently do nothing

Five distinct failure modes, all observed in this project:

1. the rule is on the **wrong sound event** (check the block's actual event with `/dsdump blocks`);
2. the rule is in the **wrong entry** — an event may have several entries, all merged;
3. the rule sits **after the catch-all** default in its entry;
4. the block id is **misspelled or absent** in that version — it becomes `MatchOnNothing`, which
   only warns;
5. the rule is **shadowed** by an earlier, broader rule in the same entry.

After adding a rule, verify the **effective** value rather than the file: `/dsdump blocks` prints
the material DS resolved for each block, and `/dsdump steps` prints the whole decision chain at the
player's position.

#### 3.4 Landing and echoes

Landing is the only event that plays **two voices per foot** (the 1.12.2 `playMultifoot`): the
engine clamps one voice at 2F gain, so channel summation is the only way a landing reads heavier
than a step. The primary is the composition's first entry, the secondary plays at 50 %, and a
delayed echo fires 1–2 ticks later. Volumes are multiplied by `LAND_GAIN_BOOST = 1.7`.

`LAND_COMPOSITIONS` is keyed by the **material path**; anything not listed uses the naming
fallback `<material>_land` → `<material>_run` → `<material>`, and `player.land` when there is no
remap at all.

#### 3.5 Variators

`variators.json` holds the gait parameters. Player defaults: `stride 0.9`,
`landHardDistanceMin 1.5`, `volumeScale 0.9`. The run stride is `stride * 1.06`,
the ladder stride comes from `strideLadder`. Creature profiles (`quadruped`, `child`, `skeleton`,
`light`, …) are attached per entity through `entity_variators.json`.

#### 3.6 Footprints

`FootprintHandler` drops a print every `STEP_DISTANCE = 0.9` blocks of travel (and a pair on a
landing harder than `LAND_PRINT_DISTANCE = 0.4`), at the strike position, using `FootprintStyle`
and the variator's `footprintScale`. It covers **every living entity**, not just the player: the
player is gated on `enableFootprints`, creatures on `enableCreatureFootprints`.

> **It does NOT share the sound's surface resolution.** `FootprintHandler` runs its own probe
> (`probeState`) and never calls `resolveSurfaceBlock`. The two agree in the common case, but
> "a print and its step always point at the same block" is NOT true - do not assume that changing
> one of them affects the other.

**Which blocks leave prints is DATA, not a hardcoded list.** The handler checks the resolved surface
block against `#dsurround:effects/footprintable`, so a mod can opt its own soft ground in and a
modpack can retune the set from a resource pack:

```jsonc
// assets/<your-namespace>/dsconfigs/tags/block/effects/footprintable.json
{ "values": ["yourmod:soft_mud"] }
```

The shipped contents are the 1.12.2 `FOOTPRINT_MATERIAL` idea (clay, grass, ground, sand, snow)
expressed as vanilla blocks - 17 entries, or 18 in 26.1 where `suspicious_gravel` was added:

```
dirt, coarse_dirt, grass_block, podzol, mycelium, rooted_dirt, dirt_path, farmland, mud,
sand, red_sand, suspicious_sand, soul_sand, gravel, snow, snow_block, clay
```

Ice is deliberately **excluded** (the player slides across it), a documented deviation from 1.12.2.

### 4. Debugging

| Surface | What it answers |
| --- | --- |
| `/dsdump blocks` (file `config/dsurround/dumps/blocks.txt`) | for every block: its step sound event **and the material DS resolved** |
| `/dsdump steps` (chat) | the whole surface-resolution chain at the player's position, branch by branch |
| `/dsdump sounds` | the registered sound events |
| `enableDebugLogging` + `traceMask` | per-step logging, and the merged rule chain for every event |

---

## Part II — 中文说明

### 1. 总览：两个独立阶段

**混淆这两者是"我改了规则却没反应"报告的最大来源**，所以必须分开看：

```
阶段 1  取面   "玩家脚下到底是哪个方块？"
   FootstepGenerator.resolveSurfaceBlock(...)          【代码，不可配置】

阶段 2  材质   "那个方块听起来像什么？"
   方块 getStepSound() -> sound_mappings.json -> sound_factories.json    【纯数据】
```

阶段 2 全是数据；阶段 1 全是代码，回答的是"脚下是什么"这个原版问题 —— 包括原版碰撞查询看不见的
方块（铁轨这类无碰撞箱的，以及玩家**站在其中**而不是**站在其上**的方块）。

### 2. 阶段 2：材质系统

#### 2.1 术语

| 术语 | 含义 | 例子 |
| --- | --- | --- |
| **音效事件** | 方块 `SoundType` 报告的原版事件 | `minecraft:block.stone.step` |
| **材质 / 工厂** | `sound_factories.json` 里一条 `dsurround:` 条目，含录音与音高/音量 | `dsurround:footsteps.marble` |
| **规则** | `sound_mappings.json` 某个事件下的一条 `{blocks, factory, accent?}` | 见下 |
| **副音** | 叠在主音之上的**低音量**额外工厂 | `dsurround:footstep_accent/sand` |
| **变体** | 只改音高/音量/录音的独立工厂：`_run`/`_land`/`_wander` | `footsteps.marble_run` |

当前共 **185** 个工厂：102 个 `footsteps.*` 材质、
3 个 `footsteps/*` 特殊音、10 个副音，其余是群系/玩家/效果音。
**111 个事件带规则**，其中 **109** 个是 `.step` 事件。

#### 2.2 单个事件内的解析顺序

`findMatch(state)` **按顺序**走规则，**第一个匹配就返回**。`blocks` 为空的规则是**兜底默认**；
带兜底的事件必须让它在**最后**，否则它后面的规则永远到不了。目前 **110** 个事件带兜底。

走到兜底时不会直接用兜底材质：先按方块注册名做**名字推断**，所以模组的 `anything_sandstone`
会解析成 `concrete` 而不是泛用 `stone`。优先级：

```
显式规则  →  名字推断  →  兜底默认
```

#### 2.2.1 数据不变量

以下在现有数据中成立，改动后值得复查：

* **每条条目恰好一个兜底规则，且在最后** —— 110 条合规、0 条违反（用 `_verify_catchall_invariant.py` 校验）；
* 一个事件可以由**多条条目**共同描述（现存 13 条这类补充条目，例如内置的 Quark 适配）。
  它们不带兜底，因此其规则全部可达；合并用户规则时，若工厂是新的，会插到**合并后列表的第一个兜底之前**；
* 该版本不存在的方块 id 会变成 `MatchOnNothing`：**只报警告**、永不匹配 —— 这条规则是空转，但不会致命。

#### 2.3 材质总表

`pitch`/`volume` 是该工厂的随机范围；`plays` 是它最终解析到的音效事件（很多变体材质
**故意指向基础录音** —— 变体只改音高/音量）。

('| material (`dsurround:` prefix omitted) | pitch | volume | plays (sound event) | variants of this material |\n| --- | --- | --- | --- | --- |\n| `footsteps.bamboo` | - | 1.0 | `dsurround:footsteps.bamboo` | land |\n| `footsteps.bedrock` | 0.55–0.6 | 0.9–1.0 | `dsurround:footsteps.stone` | run |\n| `footsteps.bluntwood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.bluntwood` | wander |\n| `footsteps.bone` | - | 1.0 | `dsurround:footsteps.bone` | land |\n| `footsteps.bone_dry` | 0.8–0.9 | - | `dsurround:footsteps.bone` | — |\n| `footsteps.brush_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.brush_through` | — |\n| `footsteps.calcite_soft` | 0.9–1.0 | 0.8–0.9 | `dsurround:footsteps.marble` | — |\n| `footsteps.concrete` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.concrete` | run, wander |\n| `footsteps.copper` | - | 1.0 | `dsurround:footsteps.copper` | land |\n| `footsteps.copper_dull` | 0.8–0.9 | 0.8–0.9 | `dsurround:footsteps.copper` | — |\n| `footsteps.copper_hollow` | 1.15–1.25 | 0.85–0.95 | `dsurround:footsteps.copper` | — |\n| `footsteps.dirt` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.dirt` | land, run, wander |\n| `footsteps.dripstone` | 0.6–0.7 | 0.8–0.9 | `dsurround:footsteps.stone` | — |\n| `footsteps.froglight` | - | 1.0 | `dsurround:footsteps.froglight` | land |\n| `footsteps.froglight_soft` | 0.85–0.95 | 0.8–0.9 | `dsurround:footsteps.froglight` | — |\n| `footsteps.glass` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.glass` | — |\n| `footsteps.grass` | 0.8–1.2 | 0.5 | `dsurround:footsteps.grass` | run, wander |\n| `footsteps.gravel` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.gravel` | run, wander |\n| `footsteps.honey` | - | 1.0 | `dsurround:footsteps.honey` | land |\n| `footsteps.leaves_crunch` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.leaves_crunch` | land |\n| `footsteps.leaves_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.leaves_through` | — |\n| `footsteps.lino` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.lino` | — |\n| `footsteps.log` | 0.55–0.65 | 0.9–1.0 | `dsurround:footsteps.log` | — |\n| `footsteps.magma_soft` | 0.55–0.65 | 0.8–0.9 | `dsurround:footsteps.concrete` | — |\n| `footsteps.mangrove_roots` | - | 1.0 | `minecraft:block.mangrove_roots.step` | land |\n| `footsteps.marble` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.marble` | run, wander |\n| `footsteps.metalbar` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.metalbar` | run, wander |\n| `footsteps.metalbar_thin` | 1.12–1.22 | 0.55–0.65 | `dsurround:footsteps.metalbar` | — |\n| `footsteps.metalbox` | 0.8–1.2 | 0.75–0.85 | `dsurround:footsteps.metalbox` | run, wander |\n| `footsteps.moss` | - | 1.0 | `dsurround:footsteps.moss` | land |\n| `footsteps.moss_dry` | 0.85–0.95 | - | `dsurround:footsteps.moss` | — |\n| `footsteps.moss_soft` | 1.05–1.15 | - | `dsurround:footsteps.moss` | — |\n| `footsteps.mud` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.mud` | wander |\n| `footsteps.muffledice` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.muffledice` | — |\n| `footsteps.organic` | 0.8–1.2 | 0.4–0.45 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_dry` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_shell` | 0.9–1.0 | 0.9–1.0 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_spongy` | 1.08–1.18 | 0.8–0.9 | `dsurround:footsteps.organic` | — |\n| `footsteps.organic_waxy` | 1.15–1.25 | 0.55–0.65 | `dsurround:footsteps.organic` | — |\n| `footsteps.quartz_powdery` | 0.95–1.05 | 0.85–0.95 | `dsurround:footsteps.marble` | — |\n| `footsteps.quicksand` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.quicksand` | — |\n| `footsteps.rails_land` | - | 0.2 | `dsurround:footsteps.metalbar_run` | — |\n| `footsteps.rug` | 0.8–1.2 | 0.7 | `dsurround:footsteps.rug` | — |\n| `footsteps.sand` | 0.8–1.2 | 0.5 | `dsurround:footsteps.sand` | run |\n| `footsteps.sculk` | - | 1.0 | `dsurround:footsteps.sculk` | land |\n| `footsteps.sculk_vein` | - | 1.0 | `dsurround:footsteps.sculk_vein` | land |\n| `footsteps.shroomlight` | - | 1.0 | `dsurround:footsteps.shroomlight` | land |\n| `footsteps.slime` | - | 1.0 | `dsurround:footsteps.slime` | land |\n| `footsteps.snow` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.snow` | run, wander |\n| `footsteps.squeakywood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.squeakywood` | wander |\n| `footsteps.stone` | 0.65–0.7 | 0.9–1.0 | `dsurround:footsteps.stone` | run, wander |\n| `footsteps.tuff` | - | 1.0 | `dsurround:footsteps.tuff` | land |\n| `footsteps.tuff_soft` | 0.8–0.9 | 0.85–0.95 | `dsurround:footsteps.tuff` | — |\n| `footsteps.tuff_thin` | 0.9–1.0 | 0.8–0.9 | `dsurround:footsteps.tuff` | — |\n| `footsteps.water_through` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.water_through` | — |\n| `footsteps.wax_dry` | 0.95–1.05 | 0.5–0.55 | `dsurround:footsteps.organic` | — |\n| `footsteps.weakice` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.weakice` | — |\n| `footsteps.wood` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_click` | 1.35–1.5 | 0.45–0.55 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_door` | 1.1–1.2 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_fence` | 1.12–1.22 | 0.6–0.7 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_shelf` | 1.05–1.15 | 0.75–0.85 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_sign` | 1.18–1.28 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_sticky` | 0.8–1.2 | 0.9–1.0 | `dsurround:footsteps.wood` | — |\n| `footsteps.wood_thin` | 1.08–1.18 | 0.7–0.8 | `dsurround:footsteps.wood` | — |\n| `footsteps/dirt_path` | 1.0–1.2 | 0.9–1.0 | `dsurround:footsteps.grass` | — |\n| `footsteps/ladder` | 0.95–1.05 | 1.0 | `minecraft:block.ladder.step` | land |', 38)

**复合名**：`footsteps.brickstone/concrete`、`footsteps.obsidian/stone` 是"主/次"两段录音。

**静音哨兵**：`dsurround:footsteps.none` 是真实取值，表示**不发声**（现代版的 `NOT_EMITTER`），
按钮用它。注意它对取面的影响：指向 `none` 的格子**不算**"有显式材质"，否则静音代理会压过
真正踩着的方块。

#### 2.4 副音总表

副音叠在主音之上，让一段录音多带一层质感（例如砂岩的 `concrete` 上再叠一层极轻的沙沙声）。

| accent (`dsurround:` prefix omitted) | pitch | volume | plays | used by |
| --- | --- | --- | --- | --- |
| `footstep_accent/amethyst` | 0.9–1.1 | 0.3 | `minecraft:block.amethyst_block.break` | 2 event(s) |
| `footstep_accent/brush` | 0.8–1.2 | 0.3 | `dsurround:footsteps.brush_through` | 1 event(s) |
| `footstep_accent/chain_rattle` | - | 0.28 | `dsurround:armor.medium_walk` | 1 event(s) |
| `footstep_accent/glass` | 0.95–1.05 | 0.9–1.0 | `dsurround:footsteps.glass` | 3 event(s) |
| `footstep_accent/mud` | - | 0.8 | `dsurround:footsteps.mud` | **UNUSED** |
| `footstep_accent/mud_quiet` | - | 0.2 | `dsurround:footsteps.mud` | 6 event(s) |
| `footstep_accent/muffledice` | - | 0.4 | `dsurround:footsteps.muffledice` | 1 event(s) |
| `footstep_accent/rail_plank` | - | 0.45 | `dsurround:footsteps.wood` | 1 event(s) |
| `footstep_accent/sand` | - | 0.1 | `dsurround:footsteps.sand` | 5 event(s) |
| `footstep_accent/weakice` | - | 0.1 | `dsurround:footsteps.weakice` | 1 event(s) |

标 **UNUSED** 的副音是"注册了但没有任何规则或落地合成引用"。`footstep_accent/mud` 就是这种状态：
黏性活塞头那条规则重新调音时用 `footstep_accent/mud_quiet`（音量 0.2）取代了它，而这个更响的原版
被留下来了。它无害（没人引用的工厂永远不会播放），但应当删除或复用。

> 注意别把两个命名空间搞混：`footsteps.mud` 作为**落地合成**里的次层出现时，它是**材质**工厂，不是副音。

#### 2.5 盔甲副音

盔甲不走 `sound_mappings`，而是按穿戴物经 DS 物品标签分类，再当作**音效事件**读取：

| 事件 | 说明 |
| --- | --- |
| `armor.crystal_foot` | `dsurround:footsteps/armor/crystal_footN` | 4 take(s) |
| `armor.crystal_run` | `dsurround:footsteps/armor/crystal_runN` | 6 take(s) |
| `armor.crystal_walk` | `dsurround:footsteps/armor/crystal_walkN` | 6 take(s) |
| `armor.heavy_foot` | `dsurround:footsteps/armor/heavy_footN` | 3 take(s) |
| `armor.heavy_run` | `dsurround:footsteps/armor/heavy_runN` | 9 take(s) |
| `armor.heavy_walk` | `dsurround:footsteps/armor/heavy_walkN` | 6 take(s) |
| `armor.light_run` | `dsurround:footsteps/armor/light_N` | 3 take(s) |
| `armor.light_walk` | `dsurround:footsteps/armor/light_N` | 3 take(s) |
| `armor.medium_foot` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.medium_run` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.medium_walk` | `dsurround:footsteps/armor/medium_walkN` | 3 take(s) |
| `armor.slimey_run` | `dsurround:footsteps/armor/slime_runN` | 6 take(s) |
| `armor.slimey_walk` | `dsurround:footsteps/armor/slime_walkN` | 6 take(s) |

基础类：`armor.light`（皮革）、`armor.medium`（锁链）、`armor.heavy`（铁/金/下界合金）、
`armor.crystal`（钻石）、`armor.slimey`。变体为 `_walk`/`_run`，以及有专门录音时的 `_foot`；
皮革没有 `_foot`，回退到 `_walk`。`ArmorAccents` 在疾跑时取 run 变体，并按 脚→腿→胸 顺序取，
所以只穿一件也有效。

#### 2.6 新增材质

```json
[
  {
    "soundEvent": "minecraft:block.stone.step",
    "rules": [
      { "blocks": ["yourmodid:marble", "#yourmodid:polished_stone"],
         "factory": "dsurround:footsteps.marble",
         "accent": ["dsurround:footstep_accent/sand"] }
    ]
  }
]
```

**新事件**的规则写成自己的文件；**已有事件**的规则会被合并，并**插到兜底默认之前**，
所以具体规则总能生效。文件可放 CONFIGURATION.md §4.0 列出的三个位置之一。

### 3. 阶段 1：脚步生成系统

#### 3.1 各动作触发什么

| 玩家动作 | 处理函数 | 播放内容 |
| --- | --- | --- |
| 走/跑 | `FootstepGenerator.playStep` | 材质（疾跑时用 `_run` 变体）+ 副音 |
| 离地 | `playJump` | `player.jump` 闷哼 + 材质的 `_wander`（或 `JUMP_OVERRIDES` 指定） |
| 落地 | `playLand` | `LAND_COMPOSITIONS` 或 `_land`/`_run`/基础回退，双脚 + 回声，外加盔甲 |
| 爬梯子 | 攀爬时的 `playStep` | 梯子材质（沿用原版 `block.ladder.step` 并放大） |
| 急停/急转 | 擦地音 | 材质的 `_wander` |
| 受管生物 | `CreatureFootstepGenerator` | 该生物的 variator + 脚下方块材质 |
| 穿过草丛/灌木 | `StepThroughBrushEffect` | `crop_step` / `straw_step` / `brush_step` 三个标签之一 |

#### 3.1.1 穿越灌木丛音（brush / straw / crop）

`StepThroughBrushEffect` 每 2 刻检查一次，**只在脚下格变化时**响一次（1.12.2 的 `messyPos`
去重）；静止站在同一格里不会再响。判定顺序是 **crop → straw → brush**，每个标签都探测
脚下格与头格。

| 标签 | 音效 | 内容 |
|---|---|---|
| `crop_step` | 按生长阶段 | 作物：归一化 age `< 0.25` 静音、`< 0.75` brush、`>= 0.75` straw（`0.75~1.0` 再叠 brush） |
| `straw_step` | `brush_step/straw`（`leaves_through` 录音） | 藤蔓、甘蔗、枯木丛、大型垂滴叶茎、干草块 |
| `brush_step` | `brush_step/brush`（`brush_through` 录音） | 草、蕨、大型蕨、高草、大型花、甜浆果丛、杜鹃、垂滴叶、孢子花、垂根、作物茎、下界苗、菌根、红树根、海草、海带、火把花 |

作物那一条是 1.12.2 `BlockMap` 的 `#wheat` / `#crop` / `#beets` 宏（按 age 变化）的移植 ——
**1.12.2 里未熟的作物是完全静音的**，所以年轻作物必须"匹配但静音"，不能掉回通用的
brush 沙沙声。任何模组把自己的作物加进 `#dsurround:effects/crop_step` 即可自动获得同样
语义（前提是该方块有整数 `age` 属性）。

> **三个版本的方块 id 不一样，改标签时必须逐版本核对**：短草在 1.20.1 是
> `minecraft:grass`，1.21+ 才改名 `short_grass`；`#minecraft:tall_flowers` 在 26.1 已被拆掉
> （改为逐个列名）。写错版本的 id **不会报错**，只是那个方块永远不生效 ——
> 1.20.1 的短草就是这样丢了穿越音。自检里的"见证方块"检查
> （`DataValidator.checkEffectTags`）就是为了挡住这一类，另外 `/dsdump brush`
> 可以现场打印某个位置的判定链。

#### 3.2 取面（五个步骤）

`resolveSurfaceBlock(entity, level, pos)`，其中 `pos = blockPosition().below()`：

1. **脚部所在格的雪片** —— 用 `isStandingOn` 判定，因为原版靠碰撞箱推导的支撑方块看不到它。
   （1.20.1 与 1.21.1 没有落叶方块，所以这一步只认雪片；26.1 在这里和下面的 AABB 扫描里
   额外测 `LeafLitterBlock`。扫过脚部 AABB 覆盖的所有格子那一步排在第 2 步之后，用于"踩在边缘"。）
2. **脚部所在格、且属于"覆盖层"的方块** —— `#dsurround:effects/foot_overlay`：地毯、雪、睡莲、
   压力板、四种铁轨、幽魂脉络、发光地衣。这是 1.12.2 carpet/foliage 底材集合的移植。
   **门和活板门刻意不在其中**。它还必须过 `isStandingOn`，否则"站在方块里"的格子会赢。
3. **原版的 `mainSupportingBlockPos`** —— 靠碰撞箱推导的"我踩在什么上"。
4. **脚下那一格**，否则做水平邻格扫描（踩在边缘的情况）。

`isStandingOn` 的含义是"**格子 Y** + 碰撞箱顶面在脚底或其下（容差 0.05）"。**必须加格子自身的 Y**：
`getCollisionShape` 返回的是**格内局部坐标**（0..1，栅栏 1.5），拿这个高度直接和实体的世界 Y 比，
对任何格子都恒真 —— 守卫等于没有（这个 bug 曾经存在于三个仓库，2026-09-17 代码审查时发现并修好；
在它失效期间 `/dsdump steps` 打印的 `isStandingOn = true` 是假信息）。它存在的理由是：门和开启的
活板门是**立在格子底部的竖直薄板** —— 只有比较高度才能区分"踩在它上面"和"只是和它同格"。

> 第 3 步与第 4 步之间曾经还有第五个探测 —— "脚部格按可见形状，且属于覆盖层标签"。它**永远不可达**
> （第 2 步已经在第五个探测新增的所有条件下返回了脚部格），2026-09-17 审查时删除。

#### 3.3 规则为什么可能"无声失效"

五种形态，本项目全都踩过：

1. 挂在**错误的事件**上（用 `/dsdump blocks` 查方块的真实事件）；
2. 挂在**错误的条目**里 —— 同一事件可能有多条条目，都会被合并；
3. 排在同条目的**兜底默认之后**；
4. 方块 id **拼错或该版本不存在** —— 变成 `MatchOnNothing`，只报警告；
5. 被同条目里**更早的宽规则**抢先。

新增规则后要校验**实际取值**而不是文件：`/dsdump blocks` 会打印每个方块解析到的材质，
`/dsdump steps` 会打印玩家位置处的完整判定链。

#### 3.4 落地与回声

落地是唯一**每只脚两个声部**的事件（对应 1.12.2 的 `playMultifoot`）：引擎对单个声部的增益
上限为 2F，所以只有多声部相加才能让落地听起来比踏步更重。主音是合成表第一项，副层按 50% 播放，
回声延后 1~2 tick。音量额外乘 `LAND_GAIN_BOOST = 1.7`。

`LAND_COMPOSITIONS` 以**材质路径**为键；表里没有的走命名回退
`<材质>_land` → `<材质>_run` → `<材质>`，完全没有映射时用 `player.land`。

#### 3.5 Variator（步态参数）

`variators.json`：玩家默认 `stride 0.9`、`landHardDistanceMin 1.5`、`volumeScale 0.9`；
跑步跨步为 `stride * 1.06`，梯子跨步取 `strideLadder`。生物档位（`quadruped`/`child`/`skeleton`/`light`…）
通过 `entity_variators.json` 按实体分配。

#### 3.6 脚印

`FootprintHandler` 每走 `STEP_DISTANCE = 0.9` 格落一个脚印（落地距离超过 `LAND_PRINT_DISTANCE = 0.4`
时落一对），位置在落点，样式取 `FootprintStyle`，缩放取 variator 的 `footprintScale`。
覆盖**所有生物**，不只是玩家：玩家由 `enableFootprints` 控制，生物由 `enableCreatureFootprints` 控制。

> **实现上并不共用取面。** `FootprintHandler` 有自己的一套探测（`probeState`），**不调用**
> `resolveSurfaceBlock`。两者在常见情况下结论一致，但“永远指向同一个方块”这句话是不成立的
> —— 读到这段时不要据此推断改动其中一处会影响另一处。

**哪些方块留脚印是数据，不是硬编码集合。** 处理器把解析出的表面方块拿去查
`#dsurround:effects/footprintable`，所以模组可以把自己的软地面加进来，整合包也能用资源包调整：

```jsonc
// assets/<你的命名空间>/dsconfigs/tags/block/effects/footprintable.json
{ "values": ["yourmod:soft_mud"] }
```

出厂内容就是 1.12.2 的 `FOOTPRINT_MATERIAL` 思路（黏土、草、土地、沙、雪），用原版方块表达
—— 17 条，26.1 因新增 `suspicious_gravel` 为 18 条：

```
dirt, coarse_dirt, grass_block, podzol, mycelium, rooted_dirt, dirt_path, farmland, mud,
sand, red_sand, suspicious_sand, soul_sand, gravel, snow, snow_block, clay
```

冰**被刻意排除**（玩家会滑过去），这是相对 1.12.2 的有意偏离。

### 4. 排错工具

| 入口 | 能回答什么 |
| --- | --- |
| `/dsdump blocks`（文件 `config/dsurround/dumps/blocks.txt`） | 每个方块的 step sound 事件 **以及 DS 实际解析到的材质** |
| `/dsdump steps`（聊天栏） | 玩家位置处的完整取面判定链，逐步分支 |
| `/dsdump sounds` | 已注册的音效事件 |
| `enableDebugLogging` + `traceMask` | 每步日志，以及每个事件合并后的完整规则链 |

---

## Appendix — most referenced tags / 最常引用的标签

| tag | used by rules |
| --- | --- |
| `#minecraft:all_signs` | 8 |
| `#c:ladders` | 6 |
| `#minecraft:all_hanging_signs` | 4 |
| `#minecraft:cauldrons` | 3 |
| `#dsurround:effects/wax_blocks` | 2 |
| `#minecraft:coral_blocks` | 2 |
| `#dsurround:effects/dried_mud_blocks` | 2 |
| `#minecraft:stone_bricks` | 2 |
| `#c:glazed_terracottas` | 2 |
| `#minecraft:buttons` | 2 |
| `#minecraft:crystal_sound_blocks` | 1 |
| `#c:clusters` | 1 |
| `#c:buds` | 1 |
| `#minecraft:candles` | 1 |
| `#c:storage_blocks/copper` | 1 |
| `#c:ores` | 1 |
| `#minecraft:ice` | 1 |
| `#c:ice` | 1 |
| `#dsurround:effects/leaves_step` | 1 |
| `#minecraft:terracotta` | 1 |
