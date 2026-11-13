# P2.1：真值读取 / 读档收窄 / 容器与索引检查

> 构建：`build/libs/golems_arsenal-0.3-testbench.jar` = **958,698 B / 2026-11-02 19:29**
> SHA256 `33AA0025EB224C99738AD02CD99F1F50015E071E02FDAC0F3E07CC50D4618441`
>
> **2026-11-02 22:09 更新**：已安装 **960,127**（`B38369B9…8D6`），在 959021 的
> 1 血修正之上又加了事件链增伤/吸收处理；见 [`P2.1a-FINDINGS.md`](P2.1a-FINDINGS.md)
> 与 [`P2.1b-EVENT-DAMAGE.md`](P2.1b-EVENT-DAMAGE.md)。
>
> **未安装**：mods 里仍是 946,543（复测目标）。本文件描述的是下一版要测的内容。

## 1. 目标

P2 原来的判断全部依赖 `getHealth()`。jzyy 会 coremod `getHealth`，super-steve 会换
`SynchedEntityData` 子类或删索引，omnimobs 会绕过 `setRemoved`。只靠 getter 和 `setRemoved`
mixin 抓不到这些路径，所以这一版把“真值”和“索引”单独读出来：

- **5.1 真值读取**：`Entity.f_19804_` → `SynchedEntityData.f_135345_`（itemsById）→
  `DataItem.f_135391_`（value）；傀儡再取 `guardedData.amount()`，有效真血 = `max(vanilla, guardedData)`。
- **5.2 读档收窄**：`readAdditionalSaveData` 不再无条件放行；必须顺着栈确认是
  `Entity#load` / `EntityType#load` / `GolemHolder#summon`，且没有反射 / `LivingEntityUtil` /
  `EntityActuallyHurt` / `AbsoluteSeverance`。
- **5.3 容器类检查**：`SynchedEntityData` 运行时类不是原版类就记 `entityData.container`。
- **5.4 索引一致性**：`isRemoved` / `removalReason` / `isAddedToWorld` / `canUpdate` /
  `level.getEntity(id)` / `ServerLevel.getEntity(uuid)` 对不上，持续 5 tick 记 `index detected`。

## 2. 代码

| 文件 | 改动 |
|---|---|
| `HealthTruth.java`（新） | 真值快照；`read(entity, withReported)`；所有反射失败回退 `null` |
| `HealthGuard.java` | 写入/移除/reconcile 改用真值；新增 `checkTruth` / `checkIndex`；`isHealthAccessor` 改成按 id 比较 |
| `HealthTrace.java` | `isLegitSaveLoad()`；`isInternalGuardedSync` 收窄；`readTruthQuiet`；`truthLines`；`reflect`/`poke`/`pokeGuarded` 用真值 |
| `HealthLedger.java` | `attach` 的初始 expected 用真值 |
| `HealthTraceCommand.java` | 新增 `/ga health truth` |

### 2.1 真值读取的细节

- 反射字段：`Entity.f_19804_`、`SynchedEntityData.f_135345_`、
  `SynchedEntityData$DataItem.f_135391_`、`LivingEntity.f_20961_`、`GuardedEntity.guardedData`。
- 1.20.1 的 `DataItem` 取值字段是 **`f_135391_`**；`f_135403_` 是 getter `m_135403_` 的 SRG 名，
  代码里两个都试，但正常走 `f_135391_`。开发环境再兜底 official 名 `value`。
- 傀儡 `getHealth()` 在 2.7.5 里是 `max(AbstractGolem.getHealth(), guardedData.amount())`，
  所以 `effective = max(synced, guarded)`；非傀儡 `effective = synced`。
- 读不到 `guardedData` 时不调用 `getGuardedDataImpl()`，避免它的懒初始化副作用。
- 真值整体不可用（字段初始化失败）时 `read` 返回 `null`，Guard 回退原来的 `getHealth` 路径。

### 2.2 读档收窄的细节

合法栈必须同时包含：

- `LivingEntity#readAdditionalSaveData` / `m_7378_`；
- `Entity#load` / `m_20258_`，或 `EntityType` 的 `create` / `loadEntityRecursive` /
  `m_20642_` / `m_20645_` / `m_20669_` / `m_185991_` / `m_185988_`，或
  `GolemHolder#summon` / `#createDummy`。

出现以下任一帧就拒绝：

- `java.lang.reflect.*` / `jdk.internal.reflect.*` / `sun.reflect.*` / `java.lang.invoke.*`；
- `LivingEntityUtil` / `EntityActuallyHurt` / `EntityASMUtil` / `AbsoluteSeverance`。

`GolemHolder` 的整类放行也收窄到 `#summon` / `#setEntity`。

### 2.3 容器 / 索引的判定

`entityData.container`：`data.getClass() != SynchedEntityData.class`，每个类名只记一次。

`entityData.index`：`DATA_HEALTH_ID` 在 `itemsById` 里找不到（`synced=NaN`），每个实体只记一次；
这种情况下 reconcile **不写回**，避免往坏掉的容器里 set 抛异常。

`index`（持续 5 tick 才记一次）：

- `removed=true` 但 `removalReason=null`（forceRemove 绕过 setRemoved）；
- `removed=false` 但 `removalReason!=null`；
- `removed=false`、`byUuid=true`、`byId=false`（针对性删 id 索引）；
- `removed=false`、`addedToWorld=true`、`byId=false`（从 level 索引消失）；
- `removed=false`、`byId=true`、`canUpdate=false`（tick 列表脱节）。

以上只在 `expected > 0`（活着）时判定；正常死亡/收回的 `expected` 已经到 0，不会刷。
`removed && byId` 故意不判：`setRemoved` 后 lookup 可能还要 1–2 tick 才清。

## 3. 新命令

```text
/ga health truth
```

逐只打印：

```text
role=test modulargolems:metal_golem uuid=... guard=enforce
  synced=300.000 guarded=300.000 reported=300.000 class=SynchedEntityData
  removed=false reason=null added=true canUpdate=true byId=true byUuid=true
```

用来确认：真值字段读对了、容器是原版、索引一致、`getHealth` 与真值一致。

## 4. 日志

新增 `kind=guard` 的 `decision=detected`：

```json
{"kind":"guard","op":"getHealth","decision":"detected",
 "arg":999.0,"expected":300.0,"actual":300.0,
 "notes":"synced=300.000 guarded=300.000 reported=999.000 class=SynchedEntityData ..."}
```

`detected` **不计入** summary 的 `guard(would/blocked/restored)`；正常 L2 的 A/A 基线仍然是 0/0/0。

## 5. 测试顺序（装新版后）

1. 备份 946543，装 958698，启动。
2. `/ga health track test nearest modulargolems:metal_golem` + `/ga health start p21-truth`。
3. `/ga health truth`：`synced/guarded/reported` 三者一致，`class=SynchedEntityData`，
   `removed=false reason=null added=true canUpdate=true byId=true byUuid=true`。
4. 正常 L2 20 下：`finAvg=1.740`、`guard(0/0/0)`、`unexplained=0`，JSONL 里**没有** `detected`。
5. 收回：`setRemoved` 不 blocked，`recall:true`，无 `index detected`。
6. 重进世界 / 收回再召唤：傀儡血量正常（验证 `readAdditionalSaveData` 收窄没误伤读档）。
7. `reflect 150` / `poke_guarded 150` / `discard`：与之前一致；`reflect` 的
   `stored` 现在读的是 `DataItem.value`，不再走 `data.get`。
8. 换部件/升级、装备面板：仍然 `accepted_build` / 无 Guard 日志。

jzyy / super-steve / omnimobs 不装进这个实例；它们的攻击路径只能靠以后单独的隔离实例验证。
这里先用 `/ga health truth` 和正常路径确认“读得对、不误报”。

## 6. 边界

- **getHealth 不能 cancel**：jzyy 重定向 getter 时，我们只能在 tick 末记 `getHealth detected`，
  再用真值做 reconcile；不能阻止别的 mod 读到假值。
- **索引被删**：只记录 + 停止回滚，不能安全修复。
- **SynchedEntityData 子类 override set**：如果它完全不走 super，mixin 拦不到；
  容器检查会在 tick 末记录，但写入已经发生。
- **反射字段名**：`f_135391_` / `f_19804_` / `f_135345_` 都是 1.20.1 SRG；如果换映射版本，
  先跑 `/ga health truth` 看 `synced=NaN` 还是正常值。
- **5.5 SavedData / 5.6 innate guard 未做**：这一版仍然只对 `/ga health` 的 test 实体生效，
  `GuardPolicy` 仍没有 consumer。
