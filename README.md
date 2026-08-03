# Dynamic Surroundings Rebirth

A **NeoForge port** of *Dynamic Surroundings* for Minecraft **26.1**, bringing the
classic ambient experience to modern Minecraft: biome ambience, material
footsteps, aurora, fog effects, weather and more.

This is an independent port by **deepsleep114** (with development assistance from
Anthropic's Claude Code). It is **not** affiliated with the original author,
OreCruncher. All credit for the original design and code goes to OreCruncher.

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
* **Fog** — morning, biome, weather, bedrock and elevation fog.
* **Enhanced sound** — reverb and occlusion in a background thread.
* **Particles** — fireflies, frost breath, water ripples, footprints and more.
* **Crit words & damage numbers**, background thunder, waterfall effects, and
  much of the classic Dynamic Surroundings feature set.

## Install

* Requires **Minecraft 26.1** + **NeoForge 26.1.2.93**
* 100% client-side — works on vanilla servers.

## Building

```bash
./gradlew build
```

Output jar: `build/libs/dsurround-neoforge-26.1.2-<version>.jar`

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
