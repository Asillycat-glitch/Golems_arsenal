# 防御方案 V2：先修 agent 包，再谈 Soul

## 0. 现状核查

- mod jar 里**有** `sanctuary-agent.jar`（约 246 KB）。
- 但 `sanctuary-agent.jar` 里**没有**：
  - `sanctuary/agent/GuardRuntime.class`
  - `sanctuary-bootstrap.jar`
- `SanctuaryAgent.appendBootstrapStub()` 通过
  `getResourceAsStream("/sanctuary-bootstrap.jar")` 加载；资源不存在时异常被吞。
- `SanctuaryTransformer` / `SanctuaryAgent` 引用 `sanctuary.agent.GuardRuntime`；
  这个类只在 `sanctuary-bootstrap.jar` 里。
- 因此 agent 可能 attach 成功，但启动/字段门实际没有工作。

结论：

```text
agent 进包了
但 agent 的 bootstrap stub 没进包
字段门代码在 transformer 里存在，运行时缺少 GuardRuntime
```

## 1. 参考模组给的经验

| 模组 | 做法 | 对我们的意义 |
|---|---|---|
| NaNny | 只处理 NaN/非法值，不设硬上限 | 不限制 L2 合法大伤害 |
| MaxHealthFix | 血量 > 上限时延迟到属性加载 | 不把大血量当成攻击 |
| miztinker | `LivingEntitySetHealthMixin`、`EntityRemoveUtil`、`ForceHurtUtil` | 方法/DataItem/索引都要门 |
| momotinker | `PenetratingDamage` 直接写 `DATA_HEALTH_ID` | DataItem 字段门必须启用 |
| supersteve | raw `removalReason`、`EntityLookup`、自定义 remove 包 | 字段 + 索引 + 客户端 |
| witherzilla | 一个 `safeEntity` 谓词挂在所有实体结构上 | 谓词统一，不按类名 |
| pigzilla | `discard()` | 终态门 |
| MaidSpell | 启动时扫描所有 `hurt` override | 类加载期建操作索引 |
| ArcaneVortex | `DataItem.f_135391_` 直写、`f_146795_`/`f_146801_`、native | 字段级 agent 是主战场 |
| toaru | health cell / lookup / tick index / world removal | 状态和索引都要门 |

## 2. 分层方案

### L0：Soul（权威状态）

```text
soulId
owner
health / maxHealth
trustedNbt
inventoryDigest
upgradesDigest
projectionGeneration
```

职责：

- 给门提供 O(1) “是否受保护”；
- 不把 Soul 当成“先被清再恢复”的主路径；
- 只保存不可逆状态，防丢。

### L1：Java 终态门（兜底）

```text
Entity.setRemoved / onRemovedFromWorld / setLevelCallback
EntityLookup / EntitySection / EntityTickList
ClientLevel.removeEntity
setHealth / SynchedEntityData
```

只做同步 allow/deny，**不每 tick readd**。

### L2：agent 字段门（主修复）

必须启用：

```text
DataItem.f_135391_        (DATA_HEALTH value)
Entity.f_146795_          (removalReason)
Entity.f_146801_          (levelCallback)
Entity.isAddedToWorld
Entity.canUpdate
Entity.x/y/z
```

`SanctuaryTransformer.visitFieldInsn` 已存在；问题是 bootstrap stub 不在包内。

### L3：native JNI hook（可选）

```text
sanctuary_jvmhook.c 的 install()
SetObjectField / SetFloatField / SetDoubleField
```

只在隔离测试实例启用；失败必须回退 L1/L2。

### L4：独立进程（长期）

```text
Game JVM = projection
Authority JVM = Soul + journal
IPC = query/commit/projection_lost
```

目前只有同 JVM `SoulAuthority` 线程，不是进程。

## 3. 立刻要做的包修复

1. 在 `sanctuary-agent` 构建时把 `sanctuary-bootstrap.jar` 放进 agent jar 根：

```text
sanctuary-agent.jar
  META-INF/MANIFEST.MF
  sanctuary/agent/*.class
  sanctuary-bootstrap.jar
```

2. 确认 `sanctuary-bootstrap.jar` 只含 `sanctuary/agent/GuardRuntime.class`。
3. 在 `SanctuaryAgent` 启动日志里打：

```text
bootstrap stub present=true/false
GuardRuntime loaded=true/false
transformer installed=true/false
field rules=N
```

4. 资源缺失时必须**大声失败**，不能 `catch ignored`。

## 4. 验收

攻击后：

```text
1. Godot/Jade: 实体仍在
2. /ga dump: 实体仍在
3. EntityLookup 有
4. health 未被写成 0
5. 日志出现 FIELD-DENIED site=DataItem.f_135391_
6. agent log: field rules active
```

先修包，再谈原生/进程。
