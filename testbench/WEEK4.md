# Week 4：/ga guard selftest + 临时测试傀儡

> 构建：**1,036,114 B / SHA `EA0BA7DF…7488855F` / 2026-11-04 23:40**，已安装。
> 单测：**23/23 通过**（`.\gradlew unitTest`）。

## 1. 命令

```text
/ga guard selftest start
/ga guard selftest status
/ga guard selftest stop
```

`start` 会：

- 在玩家旁边生成临时 `modulargolems:metal_golem`，每只只跑一种样本，跑完立即清理；
- 不碰玩家自己的傀儡；
- 不切换 `GuardRuntime` 模式（selftest 输出当前 mode；自动只到 SHADOW）；
- 只做观测，不 ENFORCE。

## 2. 五种样本与三列

| 样本 | 动作 |
|---|---|
| `setHealth(0)` | `setHealth(0)` |
| `forceHurt` | 直接写 `DATA_HEALTH_ID` 到 50 |
| `setRemoved` | `setRemoved(DISCARDED)` |
| `dataMerge` | `saveWithoutId` → `Health=50` → `load`（模拟 `/data merge` 后重载） |
| `forceRemove` | `setRemoved(DISCARDED)`（没有真正的世界索引 forceRemove 可调） |
| `nanHealth` | 直接写 `DATA_HEALTH_ID = NaN` |
| `nbtWindow` | 设置 `PillarUntil = Long.MAX_VALUE`（模拟 `/data merge`） |
| `windowUntil` | 设置 `PillarUntil=MAX` 后直接调 `onGolemAttacked`，验证 `WINDOW_REJECTED` |
| `offThread` | 从新线程调用 `HealthLedger.noteOffThread`（只验证上报路径，不写世界） |

输出：

```text
[selftest] setHealth(0) before=100.0 after=100.0 blocked=yes logged=no missed=no raw 100.0 -> 100.0
[selftest] forceHurt before=100.0 after=50.0 blocked=no logged=yes missed=no raw 100.0 -> 50.0
[selftest] setRemoved before=100.0 after=100.0 blocked=no logged=yes missed=no removed
[selftest] dataMerge before=100.0 after=50.0 blocked=no logged=yes missed=no raw 100.0 -> 50.0
[selftest] forceRemove before=100.0 after=100.0 blocked=no logged=yes missed=no removed
[selftest] nanHealth before=100.0 after=NaN blocked=no logged=yes missed=no raw 100.0 -> NaN
[selftest] nbtWindow before=100.0 after=100.0 blocked=no logged=yes missed=no pillarUntil=9223372036854775807
[selftest] windowUntil before=100.0 after=100.0 blocked=no logged=yes missed=no PillarUntil MAX through onGolemAttacked
[selftest] offThread before=100.0 after=100.0 blocked=no logged=yes missed=no detection path only
```

含义：

- `blocked`：raw 没变；
- `logged`：`HealthLedger` 的 finding 计数增加（`UNEXPLAINED` 或 `REMOVED_WITHOUT_DEATH`）；
- `missed`：raw 变了/实体被移除了，但审计没报。

## 3. 前置配置

`forceHurt` 走 `DATA_HEALTH_ID` 直写；如果正式准入还开着默认拒绝：

```toml
[damage_admission]
default_deny = true
```

会得到 `blocked=yes`，这是**正确**的准入结果，不是审计失败。要观察 `logged`，临时设：

```toml
[damage_admission]
default_deny = false
```

再跑 selftest。

## 4. 预期

在 `default_deny=false`、audit 开启时：

```text
setHealth(0)   blocked=no   logged=yes  missed=no    （raw 100 -> 0，审计记到）
forceHurt      blocked=no   logged=yes  missed=no    （raw 100 -> 50，审计记到）
setRemoved     blocked=no   logged=yes  missed=no    （REMOVED_WITHOUT_DEATH）
dataMerge      blocked=no   logged=yes  missed=no    （UNEXPLAINED_HEALTH）
forceRemove    blocked=no   logged=yes  missed=no    （REMOVED_WITHOUT_DEATH）
```

在 `default_deny=true` 时，前两行应变成 `blocked=yes logged=no`；后三行仍应 `blocked=no logged=yes`。

判断标准：

- `missed=yes`：审计漏了；
- `default_deny=true` 下 `forceHurt` 仍 `blocked=no`：准入没挡住。

## 4.2 第一版 strict enforce（本轮）

新增：

```toml
[damage_admission]
strict_writes = true
```

`strict_writes` 独立于 `default_deny`，只做两件事：

- 拒绝非有限的 health 写（NaN/Infinity）；
- 拒绝非服务器线程的 health 写（setHealth / DATA_HEALTH_ID）。

运行时 kill switch：

```text
/ga guard strict
/ga guard strict on
/ga guard strict off
/ga guard strict config
```

`strict=on(config)` 表示回到配置值。

在 `strict_writes=true` 下重跑 selftest：

```text
setHealth(0)  blocked=no* logged=yes   （default_deny=false 时仍放行）
forceHurt     blocked=no* logged=yes   （有限值、服务器线程）
nanHealth     blocked=yes logged=no    （strict 拒绝 NaN）
nbtWindow     blocked=no  logged=yes   （不是 health 写）
offThread     blocked=no  logged=yes   （sample 只验证上报路径）
```

`*` 如果 `default_deny=true`，前两行也会变成 `blocked=yes`。

注意：`offThread` 样本仍然只调用 `HealthLedger.noteOffThread`，不真的从子线程写世界；
真正的 mixin 入口在写血时会先 note、再被 strict 拒绝。

## 4.4 第二版 enforce：光柱计时

新增：

```toml
[damage_admission]
window_enforce = true
```

运行时：

```text
/ga guard window
/ga guard window on
/ga guard window off
/ga guard window config
```

做两件事：

1. `PillarCd` 被外部写成 0 时，用服务端内存里的 `lastTrigger + 600` 补回；
   `onGolemDamaged` 里先看正常 CD，再看 `lastTrigger`，不会对正常冷却刷 `WINDOW_REJECTED`。
2. `PillarUntil` 原始 NBT 大于 `now + bloomDuration + 2` 时，直接清零并忽略这次无敌窗口。

被拒绝时：

```text
[GuardAudit] WINDOW_REJECTED uuid=... PillarCd reset inside cooldown; restored to ...
[GuardAudit] WINDOW_REJECTED uuid=... PillarUntil beyond bloom window: 9223372036854775807
```

注意：selftest 的 `nbtWindow` 只设 `PillarUntil=MAX` 不触发 `LivingAttackEvent`，所以它仍然
只会产生 `NBT_WINDOW_CHANGED`/`WINDOW_ANOMALY`；真正验证 `WINDOW_REJECTED` 需要：
对一个 Omega 套傀儡先挨一下触发光柱，再 `/data merge` 把 `PillarCd` 设为 0，再挨一下；
或把 `PillarUntil` 设为 `Long.MAX` 后挨一下。




## 4.3 实际 A/B（2026-11-03 22:13；strict 之前）

**B：`default_deny=false`（准入关，审计开）**

```text
setHealth(0)  before=100.0 after=0.0  blocked=no logged=yes missed=no
forceHurt     before=100.0 after=50.0 blocked=no logged=yes missed=no
setRemoved    before=100.0 after=100.0 blocked=no logged=yes missed=no removed
dataMerge     before=100.0 after=50.0 blocked=no logged=yes missed=no
forceRemove   before=100.0 after=100.0 blocked=no logged=yes missed=no removed
```

**A：`default_deny=true`（准入开，审计开）**

```text
setHealth(0)  blocked=yes logged=no  （DamageAdmission rejected setHealth）
forceHurt     blocked=yes logged=no  （DamageAdmission rejected data.health）
setRemoved    blocked=no  logged=yes
dataMerge     blocked=no  logged=yes
forceRemove   blocked=no  logged=yes
```

结论：

- 两种配置都是 **0 missed**；
- 准入关：5/5 都发生并被审计记到；
- 准入开：setHealth/forceHurt 被拦，dataMerge/移除仍被审计记到；
- 移除目前没有准入层，`blocked=no` 是预期，不是漏拦。


## 5. 安全边界

- 每只测试傀儡都是新生成的临时实体；`start` 结束后删除；
- `stop` 会 `forget` + `discard` 当前临时傀儡；
- 不调用 ENFORCE，不改玩家傀儡；
- `dataMerge` 用 `saveWithoutId/load` 模拟 `/data merge` 后重载，不是字面执行原版 `/data` 命令；
- `forceRemove` 目前只是 `setRemoved(DISCARDED)`；真正的世界索引 forceRemove 需要更底层的测试工具，不在 jar 本体范围内。

## 5.1 清理为什么没被“阻止清除”拦住

`DamageAdmission` 只拦**生命值下降写**：

```text
LivingEntity#setHealth / SynchedEntityData#set(DATA_HEALTH_ID)
```

它不拦 `setRemoved` / `discard` / 世界索引移除。移除只在 `HealthLedger` 里被**记录**为
`REMOVED_WITHOUT_DEATH`，Week 3/4 是只读审计，不 ENFORCE 移除。

selftest 的清理路径是：

```java
HealthLedger.forget(golem); // 先移出审计，避免清理本身被记成 removed
golem.discard();            // 正常实体移除，不经过 health 写入口
```

所以：

```text
setRemoved    blocked=no logged=yes
forceRemove   blocked=no logged=yes
```

是正确的：移除发生了（blocked=no），审计记到了（logged=yes）。
如果真要“阻止清除”，那是另一个移除准入层，不在当前只读审计范围。


## 6. 后续

如果这五行的 `blocked/logged/missed` 都符合预期，再考虑把 SHADOW 的 finding 接进命令报告/JSONL；ENFORCE 仍然保持手动，不自动开。
