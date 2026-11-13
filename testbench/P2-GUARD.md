# P2：按实体 Guard（SHADOW / ENFORCE）

> **最终目标：直接写血（setHealth / 真血反射 / HEALTH_SEAL）和直接移除（remove/discard）
> 会被拦截或记录。**
>
> P2 只对 **test** 实体生效；control 永远 OFF；不搞全局。所有决定写 `kind=guard` 的 JSONL。

## 1. 三层防线

| 层 | 覆盖 | 方式 |
|---|---|---|
| 写入口 | `setHealth`、`GuardedEntity.setGuardedDataImpl`、`SynchedEntityData.set(DATA_HEALTH_ID)` | mixin HEAD：arg 与 P1 ledger expected 不一致 → SHADOW 记录 / ENFORCE cancel |
| 移除入口 | `Entity.setRemoved(KILLED/DISCARDED)` | expected>0 → SHADOW 记录 / ENFORCE cancel；正常死亡 expected=0 放行 |
| tick 兜底 | 真血反射、HEALTH_SEAL、任何绕过 mixin 的写 | tick 末尾 actual vs expected：SHADOW 记录 / ENFORCE 回滚（先 maxHealth，再当前血） |

**直接真血的关键是 `SynchedEntityData`：** 1.20.1 的 `LivingEntity.health` 存在
`DATA_HEALTH_ID`（SRG `f_20961_`）里。`ArcaneVortex` 的 `VanShAttackEntity` 和
`iss_csw` 的 `EntityUtil` 都是绕过 `setHealth` 直接 `entityData.set(DATA_HEALTH_ID, x)`；
所以 P2 在 `SynchedEntityData.set` 加了 `SynchedEntityDataTraceMixin`，不等 tick。

**为什么不会误拦正常 L2：** P1 的 ledger 在 `LivingDamageEvent`/`LivingHealEvent` 时已经把
`expected` 更新成“这次写应该到达的值”。`Damage/Heal` 之后的 `setHealth`/`guardedData`
arg 与 expected 一致就放行；只有不一致才触发 Guard。L2 的 amount 不被修改。

## 2. 档位

```text
/ga health guard test off       # 默认；不拦
/ga health guard test shadow    # 只记 would_block / would_restore
/ga health guard test enforce   # 真拦 setHealth/guardedData/setRemoved；tick 回滚反射真血
/ga health guard all off        # 应急：全部 OFF
```

- 每实体持久化；**服务器启动强制全部 OFF**，避免上次 ENFORCE 残留。
- `control` 只能 OFF；命令拒绝给 control 设 shadow/enforce。
- 必须 `/ga health start`（ledger 有 expected）Guard 才有依据；stop 后 ledger 清空，Guard 不再拦。

## 3. 写入/移除判定

### 3.1 setHealth / guardedData

- `arg == expected`（±1e-3）→ 放行；这是正常 Damage/Heal、nested `super.setHealth`、死亡写 0。
- `arg != expected`：
  - SHADOW：写 `kind=guard, decision=would_block`，**允许写**；
  - ENFORCE：写 `decision=blocked`，`ci.cancel()`，血量不变。
- **傀儡的向下 setHealth**：mod 自己的 `GuardedEntity.setHealth` 在 tickCount>5 后只允许增加；
  P2 仍会在 HEAD 记录/拦截（ENFORCE 直接 cancel），不会依赖 mod 的拒绝。
- **真正危险的是 `setGuardedDataImpl` 直写**：`poke_guarded` 就是模拟它；ENFORCE 会拦。

### 3.2 setRemoved（清除）

- 只处理 `KILLED` / `DISCARDED`；
- `expected > 0` → 活着被清除：
  - SHADOW：`decision=would_block`，实体仍被移除；P1 同时 `removed_alive`；
  - ENFORCE：`decision=blocked`，`ci.cancel()`，实体保留；
- `expected <= 0`（正常死亡）→ 放行；
- **傀儡收回**：`AbstractGolemEntity#toItem` 会先 `GolemHolder.setEntity(this)` 造同 UUID 持有物，
  再 `discard()`。这个 `DISCARDED` 必须放行，否则会出现“实体没删 + 手里多一个同 ID 傀儡”；
  P2 通过调用者判断 `toItem`/`returnToInventory` 并 allow；
- `UNLOADED_TO_CHUNK` / `CHANGED_DIMENSION` 等非终止原因 → 放行，不误拦。

### 3.3 tick 兜底（反射真血 / HEALTH_SEAL）

- **真血反射**：反射直接改 `LivingEntity.health` / `guardedData.amount`，不会走 setter；
  P2 在 tick 末尾用 `actual vs expected` 发现：
  - SHADOW：`op=reconcile, decision=would_restore`；
  - ENFORCE：`restoreToExpected` 回滚（傀儡走 `setGuardedDataImpl`，普通实体走 `setHealth`），
    记 `decision=restored`。
- **HEALTH_SEAL / trim_max_health**：`getMaxHealth` 与 attach 时 `baseMax` 不一致：
  - SHADOW：`op=max_health, decision=would_block`；
  - ENFORCE：尝试 `MAX_HEALTH` 属性 `setBaseValue(baseMax)`，记 `blocked` + `restoredMax`；
    若 seal 是自定义 cap，属性恢复可能不完全，**至少记录**。
  - 当前血被连带裁剪时，第 3.3 条的 reconcile 会再回滚当前血。
- **max 时 heal 是 no-op**：`HealthLedger.onHeal` 在 `actual >= currentMax` 时直接跳过，
  避免 HEALTH_SEAL 把 max 压到 100 后 feed 每 tick 把 expected 从 293 推到 300、刷出大量 gap。
- **`GuardedEntity#validateGuardedData` 的内部同步**放行：它不是外部写，ENFORCE 不应和
  傀儡自己的 validate 对打；P2 通过调用者判断 `validateGuardedData` 并 allow。
- **自家模板的 maxHealth 代价要放行**：`tech_expansion` 每级 -10% maxHealth（最多 5 级），
  走 Modular Golems 的 `GolemStatType.PERCENT` → `setBaseValue`，不是 modifier。P2 在 P1 check
  之前读 `GolemUpgradeLevels.levelOf(golem, "golems_arsenal:tech_expansion")`，等级变化且
  max 与 `1 - 0.1 * level` 的预期一致时记 `max_health accepted_template`，更新 baseMax/expected；
  模板激活期间的 `/attribute max_health` 仍按外部 HEALTH_SEAL 拦。
- 反射是**事后**回滚（tick 末），不是写入瞬间 cancel；这是“拦截或记录”的边界。

## 4. 审计

JSONL `kind=guard`：

```json
{
  "kind": "guard",
  "op": "guarded.data",
  "decision": "blocked",
  "target": {"uuid": "...", "role": "test", "health": 300.0, "maxHealth": 300.0},
  "arg": 150.0,
  "expected": 300.0,
  "actual": 300.0,
  "maxHealth": 300.0,
  "mode": "enforce",
  "caller": "...HealthTraceCommand#pokeGuarded...",
  "notes": "..."
}
```

- `decision`：`would_block` / `blocked` / `would_restore` / `restored`
- summary 每角色：`guard(would=… blocked=… restored=…)`
- 控制台 WARN：`[GA-Guard] op decision role mode arg expected actual`
- P1 的 `unexplained`/`removed_alive` 仍然写；SHADOW 下两者会同时出现（P1 记录 + Guard would-block）。

## 4.1 第一轮实测与修正（21:42 `p2-shadow`）

普通金属傀儡，test 先 `shadow` 后切 `enforce`：

- `poke_guarded 150` → `guarded.data blocked`，血保持 300；
- `discard test` → `setRemoved blocked`，实体保留，`guardBlocked=1`；
- 切 enforce 时 `reconcile restored` 把 180 回滚到 300；
- **问题**：`poke_guarded 150` 后血在 150→300 由 feed 每 tick +2 回，P1 expected 停在 max 300；
  P2 把每个合法 `LivingHealEvent` 写当成 `arg=176 != expected=300`，记了 337 次 `would_block`，
  并且 `reconcile would_restore` 每 tick 刷屏。
- **修正**：
  1. `HealthLedger` 记录最近一次 `LivingHealEvent` 的 `lastHealAmount`；P2 对
     `actual <= arg <= actual + lastHealAmount` 的写直接放行（guarded.data 与内层 setHealth 都覆盖）。
  2. `reconcile` 只在 gap **第一次出现或变大**时记一条；feed 把 gap 缩小的 tick 不刷。
- **结果**：`guard(would)` 从 337 降到个位数；正常 L2/feed 不再产生 would_block；
  `poke_guarded`/`discard` 的 blocked 保留。

注意：21:42 的 A/B `living_damage` 仍然是 1.740 vs 1.253（目标 build 不同），不是 P2 改的；
P2 的 A/B 需要两只升级/效果一致的傀儡。

## 5. 测试矩阵

### 5.1 同实体 A/A 实测（20:35–20:37，已通过）

同一个地牢金属傀儡 `68a26a7b...`，同一套升级，不做双傀儡 A/B：

| 指标 | guard=off 20 下 | guard=enforce 20 下 |
|---|---|---|
| `living_attack` | 20 | 20 |
| `l2Finalized` | 20 | 20 |
| `finAvg` / `appliedAvg` | 1.740 / 1.740 | **1.740 / 1.740** |
| `living_hurt` | 8.700 | **8.700** |
| `unpairedDamage` | 0 | 0 |
| `unexplained` | 0 | **0** |
| `guard` | 0/0/0 | **0/0/0** |

ENFORCE 下 20 下正常 L2 没有任何 `[GA-Guard] blocked`，`finAvg` 与 OFF 完全一致，
feed 回血也没有被拦。**P2 不改变正常 L2。**

同实体外部写/清除（20:37 `p2-own-poke`）：

- `poke_guarded test 150` 两次 → `guarded.data blocked`；
- `discard test` 两次 → `setRemoved blocked`；
- `guard(blocked=4)`，`writes=0`，`unexplained=0`，实体保留。

### 5.2 测试矩阵

| 步骤 | 设置 | 期望 |
|---|---|---|
| 正常 L2 | test shadow | control/test 事件/伤害一致；无 `guard blocked`；L2 不被改 |
| `poke_guarded test 150` | test shadow | `guard would_block`，血 300→150，P1 `unexplained` |
| `poke_guarded test 150` | test enforce | `guard blocked`，血保持 300，P1 无 unexplained；`poke_guarded` 返回 accepted=0 |
| `discard test` | test shadow | `guard would_block`，实体被删，P1 `removed_alive`+`client_remove` |
| `discard test` | test enforce | `guard blocked`，实体保留，无 `removed_alive` |
| 正常死亡（伤害击杀） | test enforce | expected 已到 0，setRemoved 放行；无 `guard blocked` |
| 区块卸载/跨维度 | test enforce | `setRemoved` 非 KILLED/DISCARDED，放行 |
| `reflect test 150` | test shadow | `guard entityData.health would_block`，P1 unexplained，tick `reconcile would_restore` |
| `reflect test 150` | test enforce | `guard entityData.health blocked`，血保持 300，`reflect` 返回 failed=blocked |
| `/attribute` max_health | test shadow/enforce | `guard max_health`；enforce 尝试恢复 max，当前血回滚 |
| control | 任意 | guard 永远 OFF；不受 test 的 poke/discard 影响 |

### 5.3 反射/HEALTH_SEAL

```text
/ga health reflect test 150
/ga health unexplained
```

SHADOW：`kind=reflect` + P1 `unexplained` + P2 `entityData.health would_block` + `reconcile would_restore`；
ENFORCE：`entityData.health blocked`，血保持 300，`reflect` 返回 failed（这是预期；直接真血被拦在写入层）。

HEALTH_SEAL / trim_max_health 用实际 toaru 源或 `/attribute <target> minecraft:generic.max_health base set 100`：

- SHADOW：`guard max_health would_block`；
- ENFORCE：`guard max_health blocked`，尝试恢复 `MAX_HEALTH` base；当前血由 `reconcile` 回滚。

## 6. 边界 / 未覆盖

- **applyData / 反射直接改字段**：只能 tick 后回滚，不能写入瞬间 cancel；SHADOW 记录、ENFORCE 回滚。
- **HEALTH_SEAL 自定义 cap**：属性 `setBaseValue` 不一定能移除自定义 cap；至少 `max_health` 记录。
- **玩家**：重生/图腾/吸收/创造模式留到 P3；P2 的 setHealth 守卫先对 test 生效。
- **L2**：P2 不改 L2 amount、不取消 LivingDamageEvent；正常 L2 的 A/B 不被影响。
- **Guard 只在 trace active 时有效**；stop 后 ledger 清空，Guard 没有 expected，不会拦。
- `discard`/`poke_guarded` 是测试刺激，不是 Guard；会真的改血/删实体。
