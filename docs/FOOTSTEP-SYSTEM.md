# 脚步/落地声系统完整参考（1.20.1 Forge + 26.1 NeoForge）

> 2026-08-29 定稿。两版代码逐行同构（仅 `ResourceLocation` vs `Identifier` 命名差异）。
> 本文是脚步声系统的唯一权威文档；调参前先读 §7 调参手册。历史分析见
> `HANDOFF-footsteps-landing-analysis.md`（§8-§10）、PORTING-DEBT §二十九/三十/三十一。

## 1. 总览：驱动链

```
客户端 tick
  └─ FootstepGenerator.process(player)            [processing/FootstepGenerator.java]
       ├─ 冲刷到期回声队列（pendingEchoes）
       ├─ 起跳/落地状态机（§3）
       ├─ 步距累加 → playStep（§4）
       └─ playJump / playLand（§5、§6）
             └─ IAudioPlayer.play(SimpleSoundInstance)
                  └─ SoundEngine（MixinSoundEngine 注入）
                       ├─ 拦截：blocked / culled（SoundInstanceHandler）
                       └─ 音量：SoundVolumeEvaluator.getAdjustedVolume → clamp(0, 2)  ← 关键
```

玩家原版脚步由 `MixinEntity`/`MixinPlayer` 取消（避免双响），accent 系统
（armor clank、brush 擦草等）经 `ENTITY_STEP_EVENT` 重发。

## 2. 数据文件（全部在 `assets/dsurround/dsconfigs/`，双版本同构）

| 文件 | 作用 |
|---|---|
| `sound_factories.json` | 工厂注册表：location → 声音事件 + category + **volume/pitch 随机区间**。缺条目的 location 走默认工厂（**音量音调恒定 1.0**） |
| `sound_mappings.json` | 原版 step 声事件 → dsurround 材质工厂的逐条重映射（含 accents 字段） |
| `variators.json` | 玩家参数：stride 0.9、landHardDistanceMin 1.5、volumeScale 0.9、playJump true |
| `sounds.json`（assets 根） | 声音事件 → ogg 池（每次播放随机选一个，池内录制天然带长短/音色差异） |

## 3. 状态机与触发条件（数值全部有出处）

- **步距累加**：水平距离（梯子加垂直分量）× **0.6**（1.12.2 `updateWalkedOnStep` 同系数）。
- **步幅**：walk = variator.stride(0.9)，run = ×1.06，ladder = strideLadder。跑步判定 = `player.isSprinting()`。
- **下落一格补步**（steppedDown）：y 下降 > 0.4 立即 playStep —— 楼梯/边缘可靠出声。
- **起跳**：离地且 `deltaMovement.y > 0` → didJump；`enablePlayerJumpSound && playJump && !潜行` → playJump。
- **落地**：
  - 跳跃落地：`fallDistance > JUMP_LAND_DISTANCE_MIN(0.9)` → **重落地 playLand**（平地跳 fallDistance≈1.25，命中）。
  - 非跳跃坠落：`fallDistance > landHardDistanceMin(1.5，variator)` → playLand。
  - 其余小落差：playStep（走/跑音）——1.12.2 此处播 CLIMB 事件（转移链后仍走 walk 音），语义近似。
- **水中**不产生脚步（只 onGround/onLadder 累步）。

## 4. playStep（脚步声）

```
vanilla step 声事件 → getRemappedSound（sound_mappings.json）
  → 跑步时尝试后缀 _run 变体（isSoundRegistered 校验，失败回落 walk 工厂）
  → factory.createAtLocation(脚下方块中心, 0.45 × 滑块)     ← 音量链见 §8
  → 同时叠加 accents（brush 擦草等，来自映射规则 accents 字段，scale = 滑块）
```

- 材质覆盖：90 个原版方块步声映射 87 个（缺 3 种悬挂告示牌，回落原版声）。
- 石头族全部映射到 footsteps.stone（pitch 0.65-0.70，1.12.2 基准，用户拍板）。

## 5. playJump（跳跃"呼"声）

- 两层：`dsurround:player.jump`（"呼"grunt，`createAsAdditional`，跟随玩家）+ 材质 `_wander` 层（位置播放，走 JSON 工厂）。
- grunt 工厂配置（1.12.2 `_JUMP` 基线 mcp.json acoustics._JUMP）：**pitch 0.8-1.2、volume 0.7-0.8**（vol 从 1.12.2 的 0.9-1.0 应用户要求下调）。
- 与 1.12.2 的差异：原版起跳是 JUMP 事件（材质 jump/wander 声学；静止跳 multifoot/跑跳单脚 + 0.4 垂直探测偏移），无独立 grunt 层；我们的 grunt 是移植期新增，音调随机对齐 1.12.2 `_JUMP`。

## 6. playLand（重落地，multifoot 机制）

**1.12.2 `Generator.playMultifoot`（行 342-356）忠实移植：落地组合每层左右脚各播一次，双声道同时发声在混音器线性叠加** —— 这是落地比脚步"厚重"的唯一机制（单声道增益被钳制在 2.0，见 §8）。

- 播放位置：脚下方块中心沿朝向垂直方向 ±0.2（`FOOT_LATERAL_OFFSET`，1.12.2 findAssociation DISTANCE_TO_CENTER）。
- 组合表 `LAND_COMPOSITIONS`（主层 ×2 + walk@50% 层 ×2 + 延迟回声 ×2）：

| 材质 | 主层 | @50% 层 | 回声 | 1.12.2 对照（mcp.json land） |
|---|---|---|---|---|
| grass / organic_dry / dirt_path | grass_run | — | grass_run | [grass_run + delayed(50) grass_run]，**无 walk 层** |
| stone / muffledice / lino | concrete_run | stone | stone_run | [concrete_run + stone_walk@50 + delayed(50) stone_run] |
| dirt / leaves_through | dirt_land | dirt | dirt_run | 同构 |
| gravel | gravel_run | gravel | gravel_run | [run + land + walk@50 + delayed] |
| sand | sand_run | sand | sand_run | [run@60 + walk@60 + delayed(50) run@60] |
| snow | snow_run | snow | snow_run | 同构 |
| wood / log | wood | — | wood | [wood_walk + delayed(30) wood_walk] |
| marble / concrete | *_run | 基材 | *_run | 同构 |
| metalbar / metalbox / rug / mud / weakice / squeakywood / bluntwood / glass / quicksand | 见代码 | — | — | metalbar=[walk+delayed(30)] 等 |
| leaves_crunch | leaves_crunch_land | — | — | 专属落地录音，无回声 |

- **回声延迟**：每次落地随机采样 **1-2 tick（50-100ms）**，同一次落地的双脚+装甲回声共享同一样本。
  依据：1.12.2 数据为定值 50ms（`"delay":50` → min=max），但 `SoundPlayer.think` 每 tick 才检查毫秒到期时间，
  实际回放 50-100ms 随帧相位浮动、卡顿丢弃（isLate）。我们复刻浮动但不复刻丢弃。
- **装甲落地**：有效护甲（脚→腿→胸第一个非空）walk accent 即时 + run accent 延迟回声（scale = 滑块）。

## 7. 调参手册（改哪里）

| 想调什么 | 改哪里 | 注意 |
|---|---|---|
| 落地整体更重/更轻 | **声道数**：playLand 各层 ×N（×2 是 1.12.2 基准）；或直接调 sound_factories.json 的 volume | 单实例总增益被钳制在 **2.0**（§8）；落地默认约 1.4，仍有约 40% 余量 |
| 落地音调/音量随机幅度 | sound_factories.json 对应 `_run`/`_land` 条目的 pitch/volume 区间 | 缺条目 = 恒定值 |
| 跳跃"呼"声 | `dsurround:player.jump` 的 pitch/volume（现 0.8-1.2 / 0.7-0.8） | 1.12.2 基线 pitch 0.8-1.2 |
| 回声间隔 | `LAND_ECHO_DELAY_MIN/MAX_TICKS`（现 1-2 tick） | 50ms 融合为一声，100ms 开始分离 |
| 材质音调基线 | sound_factories.json 基材条目；1.12.2 全表：stone 65-70、log 55-60、bedrock 55-60、stonemachine 110-120、metalcompressed 80-85、ladder 130-140@vol50、rug@70、sand@60、brush@65、fire 70-80、rails@20、armor 80-105 | 石头已拍板 0.65-0.70 |
| 脚步/落地音量总开关 | variators.json player.volumeScale（0.9）+ 游戏内滑块 | 两者相乘 |
| 步幅/触发距离 | variators.json（stride 0.9 / landHardDistanceMin 1.5）、代码 `JUMP_LAND_DISTANCE_MIN` 0.9 | |

## 8. 音量链与增益钳制（历次调参失败的根因，必读）

```
实例音量 = 工厂 volume(随机采样) × createAtLocation 传入的 scale
最终增益 = clamp(实例音量 × MC分类音量 × 单声音覆盖, 0, 2)    [SoundVolumeEvaluator.getAdjustedVolume]
```

- 单声道增益上限 **2.0**（`Mth.clamp(volume, 0, 2F)`）。
- 落地主层 scale = `player.volumeScale(0.9) × soundOptions.footstepVolume(滑块，默认 1.0) × LAND_GAIN_BOOST(1.7)` ≈ **1.53**；
  再乘工厂 volume（`_land`/`_run` 条目通常 0.9-1.0）⇒ 最终约 **1.4**，**离 2.0 还有约 40% 余量**。
- 因此 **JSON 调落地音量是有效的** —— 只有把总和推到 2.0 以上才会被削平。加重落地也可以靠多声道叠加（multifoot ×2 ≈ +6dB）。
- ⚠️ **本节曾写“上限 1.0、落地已顶格、调 JSON 无效”，那是错的**（`volumeScale` 也曾误记为 0.45，实际 0.9）。
  若你据此得出过“落地只能靠声道数”的结论，请重新评估。

## 9. 随机性构成（与 1.12.2 对齐项）

每次脚步/落地声音的随机维度：
1. **ogg 池随机**（sounds.json，如 grass_run1..11——录音攻击/时长天然不同 → "组成/时长/厚重感"变化）；
2. **音量随机**（工厂 volume 区间，1.12.2 默认 0.9-1.0）；
3. **音调随机**（工厂 pitch 区间；`_run` 系取基材值 0.8-1.2，stone 族 0.65-0.70；对 1.12.2 默认 0.95-1.05 属有意偏离——走/跑音色一致 + 变化幅度更大，已记录 PORTING-DEBT §三十一）；
4. **回声延迟随机**（1-2 tick）；
5. 双脚两个独立实例各自采样（1.12.2 multifoot 同样每脚独立采样）。

## 10. 与 1.12.2 的已知差异清单（评估过的取舍）

| 项 | 1.12.2 | 我们 | 取舍理由 |
|---|---|---|---|
| 防双响 | nextStepDistance=MAX | mixin 取消 vanilla 步声 | 1.20.1 无等效字段 |
| 小落差落地 | CLIMB 事件（!stepThisFrame 守卫） | playStep(walk/run 音) | 转移链后听感近似 |
| 跑步判定 | 速度阈值 SPEED_TO_RUN 0.22 | isSprinting() | 阈值会误伤快走（早期实测） |
| 跳跃 grunt | 无（只有材质 JUMP 声学） | 新增 player.jump 层 | 移植期新增，音调对齐 _JUMP |
| 回声丢弃 | isLate 丢弃 | 不丢弃 | 丢弃是网络卡顿副作用，非设计 |
| brush 探测 | 每 100ms 独立探测 | 映射 accents 字段 | 已移植（grass run 带 brush@vol0.3/0.4） |
| 材质 pitch 默认 | 0.95-1.05 | 0.8-1.2（除 stone 族） | 早期现代化取值；变化幅度更大 |
| 事件转移链 | WANDER/RUN→WALK 等 8 态 | walk/run/land/jump 4 态 | 覆盖全部发声场景 |
