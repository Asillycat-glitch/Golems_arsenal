# 攻防面总账（2026-11-08）

> 这份文件是"**先读后改**"的读账。改法见 `build/attack2/DEFENSE-V2.md`（改之前不落地）。
>
> 材料：
> - 匠战妖域 219 jar 常量池全扫（61763 class）→ `build/attack2/refs-jzyy.tsv` / `summary-jzyy.csv`
> - 47 jar Vineflower 反编译 → `build/attack2/src-jzyy/`，逐方法读 → `build/attack2/primitives2.tsv`
> - 三份分报告：`build/attack2/reports/GROUP-TINKERS.md`（已出）、`GROUP-JZYY.md`、`GROUP-BOSSES.md`、
>   `GROUP-UTILITY.md`
> - 测试包（47.4.20）沿用 `build/attack-survey/reports/*.md`（那份是逐方法 `javap -c` 核过的）
> - 本仓库防御层：`guard/sanctuary/*`、`compat/artifact/DamageAdmission`、`guard/HealthLedger`、`mixin/*`

---

## 一、结论（三句话）

1. **匠战妖域里真正能杀傀儡的，不是"高伤害"，是 4 份互相抄来的清除代码**
   （`MsUtil` / `EntityRemoveUtil` / `PenetratingDamage` / `Tinkers_Domain.method.Test`），
   他们的写法是：**直写 `DATA_HEALTH` → 不看结果 → 直接 `setRemovalReason(KILLED)` + `onRemovedFromWorld` + `setPos(NaN)`**，
   全程**不发任何 Forge 事件**。
2. 所以我们现在的防线（事件层 + 方法门 + 事后对账）在这套面前是**结构性不足**，
   不是"漏了某个 mod"——`Aegis` 事后修得再快，也追不上"同一帧里血被写 0、实体被摘出索引"。
3. 唯一能赢的姿态是：**把"傀儡还活着"变成我们自己保存的、不依赖实体字段的事实**，
   并且**实体被删掉之后能按快照重建**（NBT 快照，含 upgrades/装备/UUID/主人）。

---

## 二、按写入深度分层的原语表（匠战妖域）

| 深度 | 原语 | 本包里的实现 | 我们现在的样子 |
|---|---|---|---|
| D0 | `hurt()` | 绝大多数 Boss（goety/冰火/jerotesvillage/eeeabsmobs/…） | `Sanctuary` 看得见 ✅ |
| D1 | `setHealth` | `ticex.ModifierOmnipotence:130`、`tcondiadema`（5 处）、`jzyy` 各处、`BossEntity` 覆写 | 门在 `LivingEntity#setHealth` ⚠️ **但 `GuardedEntity#setHealth` 是 final 覆写**，走不到我们的 mixin；靠 modulargolems 自己"只许加血"挡 |
| D2 | `SynchedEntityData.set(DATA_HEALTH)` | `LivingEntityUtil.reflectionSeverance`、`PenetratingDamage.reflectionPenetratingDamage`、`tcondiadema.Test` | 门在 `SynchedEntityData#set` ✅ |
| D3 | `DataItem.setValue` 的 `putfield f_135391_` | `ForceHurtUtil.forceHurt`、`tryModifyHealth`、`forceSetHealthByAccessor`、`ticex IEntityDataAccessor.setValue` | ❌ **无门**（jzyy 里有 `DataItemMixin` 做同类事，但没注册进 `jzyy.mixins.json`） |
| D4 | `AttributeInstance` | `setBaseValue`（多处）、`AttributeInstance.cachedValue` 直写（momotinker 的 accessor） | `AttributeInstanceGuardMixin` 只在"腰斩"时拦；`cachedValue` 直写 ❌ |
| D5 | 反射/Unsafe/换对象 | `ForceHurtUtil.makeNoHealable`（**改 klass 指针换 `SynchedEntityData` 子类**）、`tryModifyHealth`（枚举字段） | ❌ 只能事后 |
| R1 | `setRemoved`(final) | 普遍 | `EntityRemovalGuardMixin` ✅（`kill`/`discard`/`setRemovalReason` 最终都到这里） |
| R2 | `f_146795_` 直写 | `MsUtil.KillEntity:...`、`Tinkers_Domain.Test:21` | 事后 ✅/⚠️ |
| R3 | `onRemovedFromWorld` / `setLevelCallback(NULL)` / `canUpdate(false)` / `isAddedToWorld=false` | `MsUtil`、`EntityRemoveUtil`、`Tinkers_Domain.Test` | `EntityLevelCallbackGuardMixin`（**已编译、未注册**）；`onRemovedFromWorld` ❌ |
| R4 | 拆索引：换 `EntityLookup` / 清 `EntityTickList` / 换 `ServerLevel.f_143243_` / 甚至**新建一个 ServerLevel 接管命令** | `MsUtil.superKillEntity`、`Tinkers_Domain.Test` | ❌ 只能 60 tick 后掉成物品（等于守不住） |
| L | `dropAllDeathLoot` / `captureDrops` | `ForceHurtUtil.forceHurt`（吞掉落）、`SuperbiaDiadema:38` | `DropLootGuardMixin`（**未注册**）+ 事件层 |
| P | `setPos(NaN)` / `setPos(1e9)` | `MsUtil`、`EntityRemoveUtil`、`ForceHurtUtil.executeall`、`Origin` | ❌ 无门，且 `Aegis` 会因为 `isLoaded(pos)==false` 主动放弃监视 |
| S | 别人的 SavedData 账本 | toaru `GungnirLedger`、jerotes | 只对 toaru 写了 `GungnirRecovery` |

---

## 三、必须点名的 12 个"处决/清除"入口（匠战妖域）

| # | 模组 | 入口 | 判据 / 动作 | 证据 |
|---|---|---|---|---|
| 1 | momotinker | `AttackUtil.executeall`（由 `ModEvent` 的 `LivingTickEvent` 自动触发） | 半径 200 内所有 LivingEntity：写 `DATA_HEALTH` → `onRemovedFromWorld` → `setRemovalReason(KILLED)` → `setPos(NaN)` | `util/AttackUtil.java:426-440`、`event/ModEvent.java:95-99` |
| 2 | momotinker | `Origin`（`onInventoryTick`，每 tick） | `target.getMaxHealth() < player.getMaxHealth()` → 穿透伤害 = maxHealth + KILLED + `setPos(NaN)` | `Modifiers/modifiers/Origin.java:96-111` |
| 3 | momotinker | `PenetratingDamage.tryModifyHealth` | 反射枚举所有字段 + 所有 DataItem，试写 `health-1` 找出真血字段再写新值 | `util/PenetratingDamage.java:61-130` |
| 4 | miztinker | `ForceHurtUtil.forceHurt` / `makeNoHealable` | 手写 actuallyHurt（`DataItem.setValue`）+ `Unsafe` 换 `SynchedEntityData` 子类 | `modifier/modifiers/base/ForceHurtUtil.java` |
| 5 | miztinker | `EntityRemoveUtil.removeEntity/forceRemoveEntity/removeAll` | `setRemovalReason(DISCARDED)` + `setPos(1e9)` + 全图逐个清 | `.../base/EntityRemoveUtil.java:19,28,47` |
| 6 | jzyy | `MsUtil.KillEntity` / `superKillEntity` | 见 R2/R3/R4 全套 | `ms/util/MsUtil.java` |
| 7 | Tinkers Domain | `method.Test.KillEntity` + diadema（`Superbia` / `TimeRain` / `Luxuria` / `PhysicalInjury` / `WonderOfYou`） | 掉战利品 + `setHealth(0)`；每 tick 绝对覆盖 `maxHealth*5e-4`；`setHealth(1.0)`；`kill()` | `method/Test.java:12-43`、`diadema/*` |
| 8 | tinkersinnovation | `PetrifiedModifier` | 非玩家目标直接 `setRemovalReason(KILLED)`（不 hurt、不掉落） | `library/modifiers/PetrifiedModifier.java:29-54,56-85` |
| 9 | ticex | `ModifierOmnipotence` / `ModifierDeflection` | `setHealth(0)` + `kill()`；`setHealth(getHealth()-damage)` 绝对扣血 | `modifier/ModifierOmnipotence.java:130,141`、`ModifierDeflection.java:60-68` |
| 10 | etstlib | `LegacyDamageSource#m_269533_` | 伪造 `bypasses_invulnerability` 等 tag 成员 | `entity/specialDamageSources/LegacyDamageSource.java:83-123,193-201` |
| 11 | jzyy | `AbsoluteSeveranceHealthMixin` / `SynchedEntityDataMixin`(priority=1) / `LivingEntityMixin`(priority=MAX_VALUE) | 侧表抢读、抢写、封回血 | `mixins/*.java` |
| 12 | 测试包 | toaru `GungnirExecution` / fantasy_ending `EntityActuallyHurt` / AV `HealthModify` / `HardErase` | 见 `build/attack-survey/reports/*.md` | — |

---

## 四、两个"可以直接用"的战术结论

1. **MAX_HEALTH 是这批处决的公共判据**：`Origin:103`、`SuperbiaDiadema:37`、`Significance:105` 都在比
   "目标 max/cur 血 < 攻击者 max 血"。把这些分支做成"不成立"最省事（但这是数值改动，本轮不做）。
2. **不能依赖 `LivingHurtEvent` / `LivingDeathEvent`**：`LoveTrainDiadema:42-57`、`MartyrDiadema:34-44`、
   `EnderDragonDiadema:105-126`、`etstlib ToolEvents:57-82`、`ticex TicEXAvaritiaEvent:32-44`、
   `tinkersinnovation UndyingTraitModifier:24-32` 都会在事件里改伤/转嫁/取消/复活。
   我们的事件层只当"准入"用，不能当"事实"。

---

## 四·补：分报告里另外 8 条必须知道的（引用见各报告）

1. **jzyy 有 coremod**：`coremod/CsdyLaunchPluginService.java:55-83` 在**类加载期改写
   `LivingEntity#getHealth` 的方法体**，并把**任意类**里的 `m_21223_`/`m_21224_`/`f_20919_`
   访问点改写成 `CoreMethod.*`（`:85-134`）。这意味着**我们自己的读血调用也会被改道**，
   而且它在 mixin 之前、事件之外。
2. **jzyy 的"零写血一击必杀"**：`modifier/modifier/csdy/CsdyAttack.java:34,65` 命中即
   `CoreMsUtil.setCategory(target, csdykill)`（只写实体上的 `@Unique` 字段，不写血、不发事件）。
   之后全服读它 = 0 血/已死，而且**没有清除路径**。
3. `AbsoluteSeverance.setEntityDead`（`modifier/modifier/Severance/AbsoluteSeverance.java:108-115`）
   反射写 `f_20890_`(dead)=true，**不调 `die()`**；配 UUID→血量侧表每 tick 强写。
   侧表只在 `isRemoved()` 时清——我们越阻止移除，侧表活得越久。
4. `ms/util/Helper.java:375-438` `killMAX`：把 13 个 `RangedAttribute` 的 min/max 改成 ±`Double.MAX`，
   "夹上限"这类防线的前提直接失效。
5. **jzyyfix 是 JVMTI 级插桩**：自 attach + 自带 `instimpl-x64.dll`（`redefineClass0`/`retransformClass0`），
   能在运行期重定义我们已装好的防护类；`reloadClass` 的 fallback 用 jar 原始字节 `redefineClasses`，
   **不重跑 transformer ⇒ 该类上的 mixin 全部消失**。配置加载即 `retransformClasses(AttributeInstance)`。
   另一条活路径：`JzyyFixHooks.onLTBlock`（`:168-172`）对非 Player 的 LivingEntity 直接
   `setRemovalReason(DISCARDED)`，由"勇者传说"的 Lavathunder 过程触发。
6. **sweeper_maid 每 360 秒清场**：所有 `ItemEntity` 收进 dustbin（无事件），并按名单 `kill()`
   （`SweeperMaid.java:140-162`）；名单里已有 `infinitygolem:test_one`、`fireball`。
   傀儡死亡掉落/装备被吞，就是这里。
7. **carryon 能搬走别人的傀儡**：`useWhitelistEntities=false`、主人校验只对 `TamableAnimal`，
   `tryPickupEntity` 用 `setRemovalReason(UNLOADED_WITH_PLAYER)` 存 NBT，放下时按 NBT 重建新实例
   （`PickupHandler.java:113-181`）。这是"实体被换成一个新实例"的合法路径——所有按 UUID 记的账都会断。
8. **Capsule 的结构保存会 `discard()` 区域内全部实体**（`StructureSaver.java:135-141`，无 LivingEntity 过滤）。

> 修正一条分报告里的推断：`GROUP-JZYY.md` 说 jzyy 的 `DataItemMixin` 会吞掉外部血量写。
> **它没有注册**——`jzyy.mixins.json` 的 `mixins` 列表里没有 `DataItemMixin`（读了原文件确认）。
> 所以 D3 写入目前是"没人拦"而不是"被 jzyy 拦"。但这恰好说明：**D3 门必须自己写，且最好用
> `DataItem.f_135391_` 直写 + 手工发包，绕开别人可能加的 `set` 重定向。**

## 五、现状（读完才知道的坏消息）

`gradlew compileJava` **当前是坏的**：
`Sanctuary.java` 引用的 `Aegis`、以及 `Aegis` 依赖的 `GolemKit` / `GolemSalvage` / `GungnirRecovery`
**源码不在仓库里**（只剩 `build/tmp/.../stash-dir/*.class` 和 `build/libs/` 里的旧 jar）。
另外 `mixins.json` 漏注册 4 个已编译的 mixin（`DropLootGuardMixin` / `EntityLevelCallbackGuardMixin` /
`GuardedEntityDataFloorMixin` / `LivingEntityGetHealthGuardMixin`）。

证据：
- `git log --all -- '*Aegis*'` 无记录（从未提交）；
- `build/classes/.../Aegis.class` 在本次 `compileJava` 失败后被清掉，字节码只在
  `build/tmp/compileJava/compileTransaction/stash-dir/` 里；
- 已从字节码反编译恢复到 `build/recover-aegis/src/`（`Aegis` / `GolemKit` / `GolemSalvage` /
  `GungnirRecovery` / 4 个 mixin），**没有写回 `src/`**。

所以"改"的第一步不是加新东西，而是**决定这些旧件是恢复还是被 v2 取代**。
