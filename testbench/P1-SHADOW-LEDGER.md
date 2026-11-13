# P1：影子账本（Shadow Ledger）

> **P1 只读。** 不拦 `setHealth`、不改血量、不取消事件。
> 它回答的是：**“这一次血量变化，能不能被合法的 Damage/Heal 事件解释？”**
> 不能解释的变化才叫 `UNEXPLAINED_HEALTH`，那是 P2 局部 ENFORCE 的输入。

## 1. 和 P0 的关系

- P0 已经量出：正常 L2 命中时 `l2_applied.applied == fin`、ratio≈1.0，观测层没有改 L2。
- P1 在同一套事件上再加一个**按实体**的 expected 账本：
  - `LivingDamageEvent` → `expected -= amount`（clamp 到 [0, max]）
  - `LivingHealEvent` → `expected += amount`（clamp 到 [0, max]）
  - `LivingDeathEvent` → `expected` 应为 0；>0 记 `unexpected_death`
  - `Entity.setRemoved(KILLED/DISCARDED)` → `expected` 应为 0；>0 记 `removed_alive`
  - 每 tick 末尾：`actual = entity.getHealth()`，`abs(actual - expected) > 1e-3` 记 `unexplained`
- **expected 只在合法事件里更新**；`setHealth` 不参与更新，只用于 P0 的 write 日志/归因。
- 因此正常 L2 应该 `unexplained = 0`；外部 `setHealth(0)` 会立刻出现 `expected=旧值, actual=0`。

P1 **不需要** control/test 先做成完全相同的 build 才能验证：
ledger 是按实体算的，cf899 的 feed/regen 只要走 `LivingHealEvent` 就会被记账。
P0 的 A/B 目标差异是另一件事。

## 2. 实现

| 类 | 作用 |
|---|---|
| `HealthLedger` | 按 UUID 的 expected 账本；`onDamage` / `onHeal` / `onDeath` / `onRemoved` / `check`；只读 |
| `HealthTrace` | 在事件入口更新 ledger，在 tick 末尾比较；`LAST_WRITE` 归因；写 `unexplained` JSONL |
| `HealthTraceStats` | 每角色 `unexplained` 计数，进 summary / compare |
| `HealthTraceData.ledgerEnabled` | 存档级开关，默认 true |
| `HealthTraceCommand` | `ledger` / `unexplained` / `poke` |

关键约束：

- **不写血**：`HealthLedger` 没有任何 `setHealth` 调用；`poke` 是单独的测试刺激命令，不属于 Guard。
- **不拦事件**：没有 `setCanceled`、没有 `setAmount`。
- **比较在 tick 末尾**：Damage/Heal 事件先发生，`setHealth` 在事件之后；tick 末读 actual 才是落地值。
- **只对已追踪实体**：未追踪实体不建 ledger，不产生每 tick 开销。
- **只在 actual 变化时写 JSONL**：同一个外部写不会每 tick 刷屏；`entry.unexplainedTicks` 仍逐 tick 累计。
- **`removed_alive` 只认 KILLED/DISCARDED**：区块卸载、跨维度不是外部 erase，不误报。

## 3. 命令

```
/ga health ledger true|false    # 开关影子账本；默认 true；开启时给当前已追踪实体挂上
/ga health unexplained         # 打印每角色 unexplained / writes / unpairedDamage / healWrites
/ga health poke test 0         # 测试刺激：对 test 调 setHealth(0)；P1 只记录，不阻止
/ga health poke test 20        # 尝试恢复（对傀儡增加血量可能被 GuardedEntity 拒绝）
/ga health report              # 完整 summary，包含 unexplained 行
```

`poke` 是**故意写血**的测试命令，只用于验证 ledger 能不能抓到“事件之外的写”；
它不是 P2 的 Guard，正式 jar 里也没有 `/ga health`。

## 4. P1 实验步骤

### 4.1 正常 L2（防误报）

先按 P0 的方式追踪 control/test，`/ga health ledger true`，`/ga health start p1-normal`。
用同一把 L2 武器各打 20 下，然后：

```
/ga health unexplained
/ga health stop
```

期望：

- control/test 的 `unexplained` 都是 **0**；
- `unpairedDamage` 都是 0；
- `l2_applied` 仍然 11/11 或 20/20，ratio≈1.0；
- 如果 cf899 的 feed 走 `LivingHealEvent`，`healWrites` 有数字但 `unexplained` 仍为 0；
  如果 feed 绕过了事件直接写血，`unexplained` 会出现——这是 P1 抓到的真实路径，不是观测误报。

### 4.2 外部写：poke 与 poke_guarded

#### 4.2.1 对普通实体/玩家：poke

```
/ga health poke test 0.01
/ga health unexplained
```

> **必须 `start` 之后再 `poke`，并且 `poke` 后保持记录到下一 tick 再看 `unexplained`。**
> `stop` 会清空 ledger；如果在 stop 之后 poke，血量会变，但 P1 不会记录（日志里
> `entries=0`、`unexplained=0` 就是这种假阴性）。新版 `poke` 会直接拒绝未开始记录的情况。

> **`poke` 会报 `accepted/rejected/dead/unchanged`。** 对 **GuardedEntity**，它在 tickCount>5 后
> **只允许增加血量**（见 4.2.3），向下的 `setHealth(0.01)` 会被目标自己拒绝；这时
> `accepted=0 rejected>0`，`unexplained=0` 是**正确**的，不是 P1 漏报。`0` 血也不能靠 `setHealth` 复活。

期望（仅当 `accepted>0`）：

- 下一 tick 出现 `UNEXPLAINED_HEALTH`（JSONL `kind=unexplained`, `reason=health_mismatch`）；
- `expected` 是 poke 前血量，`actual` 是写入值，`delta` 为负；
- 该行带 `writeOp=guarded.setHealth`/`living.setHealth`、`writeCaller=...HealthTraceCommand...`；
- **血量真的变了**（P1 没有拦截）；
- control 的 `unexplained` 仍为 0（poke 只作用于 test）。

#### 4.2.2 对傀儡：poke_guarded（绕过 setHealth）

```
/ga health poke_guarded test 150
/ga health unexplained
```

`poke_guarded` 直接调用 `GuardedEntity.setGuardedDataImpl(150, ..., false)`，**绕过**
`GuardedEntity.setHealth` 的“只允许增加”保护，模拟外部 mod/反射直接写 guardedData。
这是 P1 真正要抓的 backdoor；P2 才会在 Test 实体上拦它。

期望：

- 命令返回 `accepted=1`；300/300 的傀儡变成 150；
- 下一 tick 出现 `unexplained`，`expected≈300`、`actual=150`、`delta=-150`；
- `writeOp=guarded.data`（不是 `guarded.setHealth`），`writeCaller=...HealthTraceCommand...`；
- control 不受影响。

> 如果 `poke_guarded` 也 `accepted=0`：目标不是 `GuardedEntity`、已经 0 血，或它自己的逻辑拒绝；
> 换一个活着的傀儡。`poke_guarded` 是测试刺激，不是 Guard。

#### 4.2.3 关键发现：GuardedEntity.setHealth 在 tickCount>5 后拒绝“向下写”

modulargolems 2.7.5 的 `GuardedEntity.setHealth(float)` 字节码是：

```text
before = getGuardedDataImpl();
if (tickCount > 5 && amount <= before) return;   // 拒绝减少/相等
setGuardedDataImpl(amount, amount > before, false);
```

也就是说，**过了前 5 tick，外部 `setHealth(0)`/`setHealth(150)` 对 300/300 的傀儡全部被目标自己拒绝**，
只有增加（且不超过 max）会生效。19:12 的 `p1-poke2` 正好是这个结果：

- 300/300 的 test；
- `setHealth(150/100/30/0.01/0.005)` 全部 `rejected=1`；`setHealth(300)` 是 `unchanged=1`；
- `writes=6` 全是 `guarded.setHealth`，`accepted=0`；没有 `guarded.data`；
- `unexplained=0`，因为**血量根本没变**。

这条要写进 P2 的设计：`GuardedEntity.setHealth` 已经挡住了向下的外部写，
**P2 真正要防的是 `setGuardedDataImpl`/反射/直接字段写**；只测 `setHealth(0)` 会误以为“已经安全”。

#### 4.2.4 实测：poke_guarded 通过

19:30 的 `p1-poke3`（test `a44c`，300/300）：

- `poke_guarded test 150` → `accepted=1`，实际血量 300→150，随后 feed 每 tick +2；
- summary：`writes=48`，`unpairedDamage=1`（就是这次直写），`unexplained=23`；
- 日志：`UNEXPLAINED health_mismatch expected=300 actual=152 delta=-148 write=guarded.data`，
  随后 154/156/... 直到 feed 把血拉回 300；
- **P1 外部直写检测：通过**。

`unexplained=23` 是旧逻辑“每 tick actual 变化就记一条”造成的（feed 回血也变 actual）。
新 build 改为**只在 gap 变大时记一条**，所以这次直写只会出 1 条 `unexplained`；
`entry.unexplainedTicks` 仍逐 tick 累计。

旧 build 的 `writeCaller` 显示的是 feed 的 `GuardedEntity#heal`，因为 feed 覆盖了 `LAST_WRITE`；
新 build 的 `unexplained` 行增加 `recentWrites` 数组，能看到：

```json
"recentWrites": [
  {"op":"guarded.data","caller":"...HealthTraceCommand#pokeGuarded...","before":300.0,"after":150.0},
  {"op":"guarded.data","caller":"...GuardedEntity#heal...","before":150.0,"after":152.0}
]
```

### 4.3 清除测试：discard / erase

P1 已经埋了三条清除观测：

- 服务端 `Entity.setRemoved` → JSONL `kind=remove`（reason=KILLED/DISCARDED/...）；
- `expected>0` 时 → `kind=unexplained, reason=removed_alive`；
- 客户端 `ClientboundRemoveEntitiesPacket` → `kind=client_remove`。

新增测试刺激：

```text
/ga health start p1-clear
/ga health discard test
/ga health unexplained
/ga health stop
```

`discard test` 会对 test 调 `entity.discard()`（`RemovalReason.DISCARDED`），这是**真的清除**，
实体会消失；control 不受影响。期望：

- `remove` 行 reason=`DISCARDED`；
- `unexplained` 行 reason=`removed_alive`，`expected` 是清除前血量（>0），`actual` 是当时血量；
- `client_remove` 行包含该 eid；
- summary：`removals=1`、`clientRemoves=1`、`unexplained>=1`；
- **正常死亡**（`KILLED` 且 expected 已到 0）不应产生 `removed_alive`；
- **区块卸载/跨维度**（`UNLOADED_TO_CHUNK`/`CHANGED_DIMENSION`）不应产生 `removed_alive`。

如果 `toaru erase` 走 `DISCARDED`/`KILLED`，也会走同一套；如果它只清客户端、不调 `setRemoved`，
`client_remove` 会有、`removed_alive` 可能没有——先看 `remove` 的 reason。

### 4.3.1 对照：poke control

如果 control 也是普通实体/玩家，`poke control 0.01` 应让 control 也出现 `unexplained`，
证明 ledger 按实体挂载；对傀儡 control 同样要用 `poke_guarded`。

### 4.4 其他矩阵（P1 只记录，不判定拦截）

| 刺激 | 期望 |
|---|---|
| `toaru HEALTH_SEAL` / `trim_max_health` | `tick_max_health` 出现；若血量被连带裁剪，`unexplained` 出现（expected 仍按旧 max 记账） |
| `/forceSetHealth`（梦终） | 若走 `setHealth`：对傀儡可能被目标拒绝；反射直写字段：`unexplained` 但没有新 write |
| `toaru erase` | `removed_alive`（reason=DISCARDED/KILLED），expected>0 |
| `ArcaneVortex HealthLock` | `actual` 被锁成假值 → `unexplained`；ledger 的 expected 仍是真值 |
| CSW remove/removeEffect | 不测；用 hurt 代替 |

### 4.5 目标太肉时：20 下只出 2 次 damage

160/160、20 个升级槽、夜视/力量/真实视觉的人形傀儡，或 300/300、护甲 74 的大型金属傀儡，
都会把 20 次 attack/hurt 压到只有 2 次 `living_damage`（`fin` 0.010/0.013/0.12）。
P1 的 `unexplained=0` 仍然成立，但**信号很弱**：

- 做 P1 误报验证够用；
- 做 P2 的 ENFORCE A/B 前，建议换一对**低减伤**目标（或暂时卸掉 `damage_reduction`/上限类升级），
  否则 Guard 拦截与 0.01 的血量变化很难区分；
- 两边都必须是同样的升级/效果（力量/夜视/真实视觉如果只有一边有，也会污染 A/B）。
- P1 的 `poke_guarded` 是直接改 guardedData，不需要先打伤害，适合验证 backdoor。

## 5. 判定标准（P1）

| 指标 | 通过条件 |
|---|---|
| 正常 L2 误报 | `SHADOW` 模式下，control/test 的 `unexplained=0`；L2 事件流与 P0 完全一致（19:30 前已通过） |
| 外部写血 | `poke_guarded test 150` 后 test 出现 `unexplained`，带 expected/actual/delta/recentWrites；control 不受影响（19:30 `p1-poke3` 已通过） |
| 不拦截 | poke_guarded 后血量真的变化（300→150）；P1 没有取消/回滚/改回原值 |
| 清除 | `discard test` 后 `remove` reason=DISCARDED + `removed_alive` expected>0 + `client_remove`；正常死亡/区块卸载/跨维度不误报 |
| 归因 | `unexplained` 行里能读到 `lastEvent`、`recentWrites`（含 poke 的 guarded.data 与后续 heal） |
| 回滚 | `/ga health ledger false` 后不再产生新的 unexplained；行为与 P0 一致 |

## 6. JSONL

`kind=unexplained`：

```json
{
  "kind": "unexplained",
  "reason": "health_mismatch",
  "target": {"uuid": "...", "role": "test", "type": "modulargolems:humanoid_golem", "health": 0.0, "maxHealth": 400.0},
  "expected": 0.024,
  "actual": 0.0,
  "delta": -0.024,
  "lastEvent": "damage",
  "lastEventAge": 12,
  "unexplainedTicks": 1,
  "writeOp": "guarded.setHealth",
  "writeCaller": "a_silly_cat.golems_arsenal.testbench.HealthTraceCommand#poke:...",
  "writeBefore": 0.024,
  "writeAfter": 0.0
}
```

- `reason`：`health_mismatch` / `unexpected_death` / `removed_alive`
- `lastEvent`：最近一次合法 Damage/Heal/Death
- `writeOp/writeCaller`：最近 2 tick 内 P0 记录的写血；反射写可能没有新的 write

## 7. 已知限制 / 下一步

- **吸收**：`LivingDamageEvent` 的 amount 是吸收后的值；golem 吸收为 0 时直接成立。玩家吸收留到 P3。
- **feed/regen**：必须走 `LivingHealEvent` 才会被 ledger 记账；绕过事件的回血会被 P1 标为 unexplained（这是发现，不是 bug）。
- **getHealth 被锁**：ledger 的 expected 仍是真值；P1 只记录不一致，不修复。
- **maxHealth 变化**：expected 不会自动跟着 max 裁剪；HEALTH_SEAL 会以 unexplained 呈现，P2 再决定怎么处理。
- **玩家**：重生/图腾/吸收/创造模式留到 P3；P1 的 `poke` 可以用于玩家，但先不要用 ENFORCE。
- **P2**：只在 Test 实体上按 `SHADOW`/`ENFORCE` 拦截“没有被事件解释的 setHealth”；control 始终 OFF；先做 SHADOW，再 A/B。
