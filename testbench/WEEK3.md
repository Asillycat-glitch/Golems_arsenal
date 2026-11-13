# Week 3：HealthLedger + TickAudit（只读）

> 构建：**1,036,114 B / SHA `EA0BA7DF…7488855F` / 2026-11-04 23:40**，已安装。
> 单测：**23/23 通过**（`.\gradlew unitTest`）。

## 1. 做什么

只读影子账本，不写血、不拦截、不修数据：

- 按 `LivingDamageEvent` / `LivingHealEvent` 维护 `expected`；
- 每 20 tick 读一次真实 `DATA_HEALTH_ID`（raw）和 `getHealth()`（actual）；
- `raw != expected` → `UNEXPLAINED_HEALTH`；
- `EntityLeaveLevelEvent` / 实体消失 + `!died` → `REMOVED_WITHOUT_DEATH`；
- `entity.getClass().getName()` 变化 → `CLASS_CHANGED`；
- 事件回调不在服务器线程 → `OFF_THREAD`。

`expected` 只是审计值；`raw` 才是事实。

## 2. 代码

| 类 | 作用 |
|---|---|
| `guard/HealthLedger` | 事件、TickAudit、计数、`/ga guard audit` |
| `guard/HealthLedgerData` | `SavedData`：UUID → expected/raw/class/dimension |
| `guard/HealthAuditMath` | 纯函数：吸收后损失、unexplained 判定 |

配置：

```toml
[guard]
audit_enabled = true
```

`SavedData` 文件：`world/data/golems_arsenal_health_ledger.dat`。

## 3. 命令

```text
/ga guard audit status
/ga guard audit start
/ga guard audit stop
/ga guard audit clear
```

`status` 输出：

```text
[guard-audit] enabled=true tracked=1 unexplained=0 removedWithoutDeath=0
  classChanged=0 offThread=0 nonFinite=0 nbtWindowChanged=0
  windowAnomaly=0 restored=0
```

（实际是一行，这里为了可读折行。）

## 3.1 扩展 finding（本轮新增）

| finding | 触发 |
|---|---|
| `NON_FINITE_HEALTH` | raw 或 actual 是 NaN/Infinity；不再静默 `return` |
| `OFF_THREAD` | `setHealth` / `DATA_HEALTH_ID` 的 mixin 入口在非服务器线程；以及伤害/治疗回调 |
| `NBT_WINDOW_CHANGED` | `PillarCd` / `PillarUntil` 被外部改，且没有经过 `GolemsArsenalSetHandler` 的 `noteTimerWrite` |
| `WINDOW_ANOMALY` | 使用后 `PillarCd/PillarUntil` 从非 0 变 0，或 `PillarUntil == Long.MAX_VALUE` |

说明：

- `PillarUntil=MAX` 仍会被 `TimeWindow` 在读取时夹到 `now+MAX_WINDOW`；`WINDOW_ANOMALY` 只是记录“这个值被写进来了”。
- `PillarCd=0` 仍会被当作“就绪”；`WINDOW_ANOMALY` 能记录外部把它从非 0 改回 0，但正常首次使用（本来就是 0）不报。
- `NBT_WINDOW_CHANGED` 只覆盖这两个计时 key，不是通用 `/data` guard。
- `NON_FINITE` 目前只记录；`default_deny` 开启时 `DamageAdmission` 已经会拒绝 NaN 的 `setHealth`/DataItem。


## 4. 测试

先让正式准入不挡测试刺激：

```toml
[damage_admission]
default_deny = false
```

再用 P2 测试命令做样本：

```text
/ga guard audit status
/ga health start p3-audit
/ga health poke 50          # 直接写血：应出现 UNEXPLAINED_HEALTH
/ga guard audit status
/ga health stop
```

期望：

```text
[GuardAudit] UNEXPLAINED_HEALTH uuid=... expected=100.0 raw=50.0 actual=50.0 delta=-50.0 lastEvent=-
```

移除样本（P2 testbench 的 discard/setRemoved；具体命令按当时测试卡）：

```text
# 期望：REMOVED_WITHOUT_DEATH，除非先触发 LivingDeathEvent
```

正常伤害/回血：

```text
# 期望：UNEXPLAINED_HEALTH 计数不增加；expected 随事件走
```

## 5. 边界

- `REMOVED_WITHOUT_DEATH` 只在这个区块已加载时判定；区块卸载不报，避免误伤。
- 玩家收回傀儡（returnToInventory）也会被记为 `REMOVED_WITHOUT_DEATH`；这是“没有死亡事件就离开”的事实，不一定是攻击。
- 吸收后的 `LivingDamageEvent.amount` 按 `max(0, amount - absorption)` 估算；如果某个 mod 在事件后改 absorption，会有一条误报。
- `OFF_THREAD` 目前只覆盖伤害/治疗回调；tick audit 本身在服务器线程。
- 审计只读；它不会阻止任何事情，也不会修复 raw/expected 的差异。

## 6. 单测

`HealthAuditMathTest`：

- 吸收 4 点，10 点伤害 → 实际损失 6；
- 吸收大于伤害 → 0；
- NaN/负数 → 0；
- expected/raw 一致 → 不报；
- 差超过 EPS → 报；
- NaN → 不报。

## 7. 实测记录（2026-11-03 13:43）

移除样本已成立：

```text
[GA-Health] UNEXPLAINED removed_alive role=test expected=100.0 actual=100.0
[GuardAudit] REMOVED_WITHOUT_DEATH uuid=bbd18d72-... reason=DISCARDED raw=100.0
[guard-audit] tracked=0 unexplained=0 removedWithoutDeath=1
```

`/ga health reflect test 50` 没有产生 `UNEXPLAINED_HEALTH`，因为正式准入还在挡：

```text
[GA-Health] 反射真血未生效：... error=health data write blocked (stored=100.0)
```

live 配置是 `[damage_admission] default_deny=true`，`SynchedEntityData` 写入口被正式准入先拒绝；
raw 没变，审计当然不报。要验证 `UNEXPLAINED_HEALTH`，临时设：

```toml
[damage_admission]
default_deny = false
```

重启后再 `reflect`；否则 `REMOVED_WITHOUT_DEATH` 已经是这一轮的通过项。

