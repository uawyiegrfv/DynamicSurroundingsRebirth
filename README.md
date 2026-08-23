# Dynamic Surroundings Rebirth

A **NeoForge port** of *Dynamic Surroundings* for Minecraft **26.1**, bringing the
classic ambient experience to modern Minecraft: biome ambience, material
footsteps, aurora, fog effects, weather and more.

This is an independent port by **deepsleep114** (with development assistance from
Anthropic's Claude Code). It is **not** affiliated with the original author,
OreCruncher. All credit for the original design and code goes to OreCruncher.

> **AI Disclosure:** This port is developed and maintained with the assistance of
> AI tools (Anthropic's Claude Code). AI-assisted code is provided "as is" and may
> contain bugs or unexpected behaviour; please report issues on the GitHub tracker
> and use at your own risk.

* Original: [Dynamic Surroundings (OreCruncher)](https://github.com/OreCruncher/DynamicSurroundingsFabric)
* License: MIT (see below)

---

## Features

* **Biome ambience** — forests, plains, jungles, oceans and more, with smooth
  transitions as you explore. Works with biome mods (Biomes O' Plenty, Terralith, …)
  via convention tags.
* **Material footsteps** — surface-specific step sounds for every material,
  including jump, landing and walking-through-brush sounds.
* **Aurora** — the northern lights appear over cold biomes around midnight.
  Shader-rendered curtain with vertical ray structure, parallax depth and
  spectrum colours (green body, violet/red edges), brightness tuned so the
  night sky read is natural rather than glaring.
* **Fog** — morning, biome, weather, bedrock and elevation fog. Bedrock fog now
  hugs the world floor (minY..minY+10, -64..-54 in the overworld) so deep
  biomes like the Deep Dark stay clear; the Deep Dark keeps a light
  atmospheric fog.
* **Enhanced sound** — reverb and occlusion in a background thread. Reverb and
  occlusion are time-smoothed, and diffraction compensation keeps sounds
  audible behind walls and large obstacles without over-brightening.
* **Particles** — fireflies, frost breath, water ripples, footprints and more.
* **Crit words & damage numbers**, background thunder, waterfall effects,
  falling-block dust, held-torch crackle, and much of the classic Dynamic
  Surroundings feature set.
* **Deep Dark ambience** — a low drone, a faint persistent heartbeat and
  intermittent Sculk clicks in the Deep Dark.
* **Leaf-wind gusts** — intermittent leaf rustling in wooded biomes, more
  frequent at night.
* **Leaf-litter footsteps** — six landing crunch variants (denoised, uniform
  length and loudness) plus varied walking steps, so landing on dead leaves
  sounds different every time.
* **Treasure distance** — the horizontal distance to the target is shown on
  explorer maps.
* **Sound-options sliders** — Footsteps and Biomes volume in the vanilla Sound
  Options menu (0% restores the vanilla footstep sounds). Underwater paths
  dampen volume (default 0.95/block) and muffle treble (default 0.7/block).
* **Quick sound volume overlay** — hold Ctrl+backtick to list and adjust
  recently played sounds without opening a menu; text wraps to the panel and
  the overlay renders above the screen-corner vignette so it stays readable at
  night.
* **Compass & clock HUD** — shown while holding the corresponding item.
* **Sand & gravel dust** — dust clouds kick up when falling blocks land.
* **Individual sound configuration** — fine-tune every sound from the config
  screen. Closing (Done/Cancel/ESC) returns to the Sound Options screen with
  the cursor where you left it and the standard UI click sound.

## Install

* Requires **Minecraft 26.1** + **NeoForge 26.1.2.78**
* 100% client-side — works on vanilla servers.

## Building

```bash
./gradlew build
```

Output jar: `build/libs/DynamicSurroundingsRebirth-26.1.2-<version>.jar`

## Configuration

Open the mod config from the Mods screen (Cloth Config), or edit
`config/dsurround/dsurround.json`. Options cover footsteps, biome sounds,
aurora, fog, weather, particles, crit words and more.

---

> # License
The MIT License (MIT)

Copyright (c) 2023-2025 OreCruncher
Copyright (c) 2026 deepsleep114 (NeoForge port)

Port to Minecraft 26.1 / NeoForge by deepsleep114, with development assistance
from Anthropic's Claude Code.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
