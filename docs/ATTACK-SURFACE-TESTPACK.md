# 测试包攻击面盘点（2026-11-08）

> 对象：`D:\我自己的包\.minecraft\versions\1.20.1-Forge_47.4.20`（82 jar）。
> 方法：ForgeFlower 反编译 + `javap -c` 逐方法核对 + 常量池扫描（`build/attack-survey/`），
> 逐条落到"写的是哪个字段 / 走不走方法边界 / 我们哪一层能看见"。
> 四份原始取证报告：
> [ARCANEVORTEX-ATTACK](build/attack-survey/reports/ARCANEVORTEX-ATTACK.md)、
> [TOARU-CHAIN](build/attack-survey/reports/TOARU-CHAIN.md)、
> [FANTASY-AND-GOLEM-UPGRADES](build/attack-survey/reports/FANTASY-AND-GOLEM-UPGRADES.md)、
> [REST-OF-PACK](build/attack-survey/reports/REST-OF-PACK.md)。

---

## 0. 一句话结论

这个包的击杀手段分成**四种写入深度**，只有最后一种需要"每 tick 对账"：

| 深度 | 写在哪 | 代表 | 方法边界能拦？ |
|---|---|---|---|
| D0 | 走 `hurt()` / Forge 事件 | goety `death`、l2 `killer_aura`、绝大多数生物 AI | ✅ `LivingAttackEvent` |
| D1 | 调 `LivingEntity#setHealth` | fantasy 真血、Cataclysm 复活、iss_csw 第一层 | ✅ `setHealth` mixin |
| D2 | 写 `SynchedEntityData#set(DATA_HEALTH)` | iss_csw `EntityUtil`、fantasy `setDataItemValue` | ✅ `SynchedEntityData#set` mixin |
| D3 | `putfield DataItem.value` / `Unsafe` / 侧表 | toaru `forceZeroHealth`、AV `HealthModify`、iss_csw `ASMHealthMethodHelper` | ❌ **只能每 tick 回写** |

移除侧同理：`setRemoved`（可拦）→ 反射写 `removalReason`（可读侧回滚）→ 直接拆
`EntityLookup`/`ChunkMap`/`EntityTickList` 并 `setLevelCallback(NULL)`（只能守望 + 拒 NULL callback）。

**所以这一轮的防御不再"逐个 mod 找入口"，而是把"受保护傀儡还活着"当成每 tick 强制成立的不变量**
（`guard/sanctuary/Aegis`）。逐个 mod 的 hook 仍然保留，但降级为"切得干净"的优化，不再是保证。

---

## 1. toaru-runes（`dev.othinus.toaru`，Gungnir）

**唯一语义开关**：`GungnirDamageApi$Authority.state(Entity)`；返回 `INVULNERABLE` 时，
strike 前置门、30+ 处 `GungnirProtection.active`、`blocksRemoval`、`isRemoved/getRemovalReason`
回滚、`hurt/die/setRemoved` 取消全部生效。非玩家没有数据级免疫路径（`hasSpear` 只认 `ServerPlayer`）。

| # | 入口 | 写什么 | 本仓库拦截 |
|---|---|---|---|
| 1 | `Authority#isProtected` | 判定 | `GungnirAuthorityGuardMixin` 恒 true |
| 2 | `GungnirExecution#performExecution` | 封印 max、`hurt(MAX)`、`setHealth(0)`、`forceZeroHealth` | `setHealth`/`DataItem` 层 + Aegis 回写 |
| 3 | `forceZeroHealth`（写 `DataItem.f_135391_`） | D3 字段写 | **Aegis 每 tick** |
| 4 | `GungnirExecution#finishRemoval` | `setRemoved(KILLED)` + `finishWorldRemoval` + `removeResident` | `GungnirExecutionGuardMixin` HEAD cancel |
| 5 | `GungnirLedger#shouldBlock/record` | SavedData「已抹除」 | `GungnirLedgerGuardMixin` 一票否决 |
| 6 | `GungnirReaper` 每 5 tick 追杀 | 再 `setRemoved` + 拆索引 | 被 #5 一票否决 |
| 7 | `GungnirLootMixin#finishNativeLoot` | 绕过 die 强制 `dropAllDeathLoot` | `DropLootGuardMixin` + `LivingDropsEvent` |
| 8 | `PersistentEntitySectionManager#removeResident` | 拆 section/lookup/tickList | 索引层无 mixin；靠 #5 + `setLevelCallback(NULL)` 拦截 + Aegis |
| 9 | `ServantEvents`（goety）清掉落 | `event.getDrops().clear()` | `LivingDropsEvent` HIGHEST 先于它？见 §6 注 |

`TerminalAuthority.sealed` 对 mob 是**永久**的（`release` 只在玩家路径被调）；
`GungnirRecovery.repair` 在硬锁触发时反射调 `TerminalAuthority.release` +
`GungnirLedger.forget` + 清 `toaru_birth_origin_v1`，否则重新召唤的同 NBT 傀儡会被
`GungnirLineage.erasedBirth` 立刻再杀。

---

## 2. ArcaneVortex（`com.luoee.arcanevortex`，秘法涡流 0.7.10）

| 原语 | 行为 | 拦截 |
|---|---|---|
| `HealthModify.entityDataModify(Entity,float)` | 遍历 `SynchedEntityData` 里**值等于当前 getHealth** 的 `DataItem`，`putfield f_135391_` | `ArcaneVortexHealthModifyGuardMixin` HEAD；漏了由 Aegis |
| `NativeAgent.HEALTH/MAX_HEALTH` 侧表 | 血量存在 `SyncStore.DATA`（实体上没字段），`HealthMixin` 覆盖 `getHealth/getMaxHealth` | 读侧：`GuardedEntityHealthReadMixin` 取 max(raw, 账本) |
| `HealthMixin#setHealth` | 侧表有值时**取消向上的 setHealth**（回血被单吃掉） | Aegis 走 `setGuardedDataImpl` + `writeRawHealth`（DataItem），绕开它 |
| `EntityKillerHelp.killEntity/kill/tickDeath/AsynchronousRemove` | `kill()/die()/setHealth(0)/discard`，再直写 `removalReason/dead/deathTime/canUpdate` | `ArcaneVortexKillerGuardMixin` 全拦 |
| `EntityKillerHelp.setRemoved/remove/deleteEntity/removeServerLevelEntity` | 清索引 + `invalidateCaps` | 同上 |
| `setEntityPos`（1e9） | 折跃到世界外 | 同上 + Aegis |
| `HardErase.erase/stripEntity/purgeSections/purgeKnownUuids` | 世界索引级清除 | `HardEraseGuardMixin` |
| `VanillaBypass` | 把 `hurt/die/dropAllDeathLoot/spawnAtLocation` 转发到**原始字节码副本**，绕过别人的 mixin | 因此本仓库对 AV 的守卫都放在**它自己的类**上，不放在原版方法上 |
| native JVM hook DLL + `ClassRestoreManager` | 可能剥掉注入 | 不变量层（Aegis）不依赖注入存活 |

---

## 3. fantasy_ending（`com.mega.uom`，梦终）

- `EntityActuallyHurt.actuallyHurt` **整段替换**了原版 actuallyHurt：不发
  `LivingAttack/Hurt/Damage`，只发自己的 `CatchActuallyHurt0Event.PrePre/Pre` 与 `ShieldBlockEvent`。
- `catchSetTrueHealth` → `setDataItemValue`：用 VarHandle 写 `DataItem.value`（D3）。
- `SoftGetHealthZeroCommand`：置 `isDead=true` + `feGetHealth=-∞`（NBT 持久化），
  配合 coremod 对所有 `LivingEntity` 子类改写 `getHealth/isAlive/isDeadOrDying`。
- `DeleteCommand`：`setRemoved(UNLOADED_TO_CHUNK)`（`shouldDestroy=false`，不进 `onRemove`、
  不触发回收）+ `setLevelCallback(NULL)` + `entityTickList.remove` → "永远不再 tick 的雕像"。
- `ForceKillCommand`/`ForceSetHealthCommand`：命令级。

拦截：`EntityLevelCallbackGuardMixin`（拒 NULL callback）、`RemovalGuard` 对
"UNLOADED 但区块还开着" 的删除做 `hardRepair`、Aegis 回写 `dead/deathTime`。
**注意**：因为 ledger 只在被准入的伤害事件里下降，梦终的真伤现在会被 Aegis 每 tick 顶回去——
即"梦终打不动受保护傀儡"。这是有意的（先全拦，再谈数值）。

---

## 4. 其余包内攻击面

| jar | 原语 | 深度 | 拦截 |
|---|---|---|---|
| iss_csw（交错织法） | `EntityUtil.setHealth` 直写 `DATA_HEALTH`，再升级到 ASM+`Unsafe.putFloat`、MethodHandle、反射 setter/Map 扫描 | D2→D3 | `SynchedEntityData#set` + `getHealth` 读侧 + Aegis |
| iss_csw | `actuallyHurt` 里 `Math.max(f1, amount)` 抵消减伤事件，且不调 `hurt()` | D0 旁路 | Aegis（账本不降） |
| iss_csw | `noInvincibilityFramesHurt` 清 `invulnerableTime/hurtTime` | 帧 | 无害（我们不看帧） |
| iss_csw | `EntityMixin#onSetRemoved` 反向保护自家 Boss | — | 不影响 |
| Cataclysm | `tryCursiumPlateRebirth`：`setHealth(5)` + cancel `LivingDeathEvent`（仅穿戴者） | D1 | `setHealth` 门 + 死亡否决 |
| goety | `KillingSpell`：`hurt(deathCurse, target.getHealth())` = 按当前血量秒杀 | D0 | `LivingAttackEvent`（`bypasses_*` 类型 → OPERATION 拒） |
| goety | `ServantEvents#DropEvents`：`getDrops().clear()` | 掉落 | 前置快照 / `LivingDropsEvent` |
| goety | `SoulJar`：`target.discard()`（亡灵） | 移除 | `setRemoved` 门 |
| l2hostility | `KillerAuraTrait`：`hurt` | D0 | 事件 |
| l2hostility | `GrowthTrait/UndyingTrait`：自愈/自弃 | — | 不影响 |
| spore | `Infection#onEntityDeath`：铁傀儡 → `InfestedConstruct` 后 `golem.discard()` | 转换 + 移除 | 死亡否决（死在先）+ `setRemoved` |
| jerotesvillage | `VariantZsieinDiscard`：`hurt(MAX)×2` 后 `setRemoved(CHANGED_DIMENSION)`（跳过 die、无掉落） | 移除 | **CHANGED_DIMENSION 目前放行**（见 §6 空洞） |
| modulargolems | `setRemoved(KILLED)` / `discard()` 在 hp>0 时走 `untrackRemoved` → `DEATH`（**不生成物品、升级全丢**） | 移除 | `setRemoved` 门 + Aegis |
| modulargolems | `the_risen`=`ReviveModifier`：只挂 `RECYCLE|REVIVE` 两个 flag；物品形态由 `returnToInventory` 生成 | — | 不删实体就不需要它 |

**升级件存储（关键）**：升级只存在 `AbstractGolemEntity.upgrades`（`@SerialField` → auto-serial NBT），
只在 `GolemHolder.setEntity` 时被复制到物品的 `golem_upgrades`。`dropGolemLoot` **从不**掉升级。
因此"升级丢失"的充要条件是：**实体被移除时没走 `setEntity`**。`setRemoved(KILLED)` / `remove` /
`discard` 三条都满足这个条件——这就是上一轮"回收升级失败"的机制。

---

## 5. 防御层（本轮）

| 层 | 文件 | 管什么 | 实现方式 |
|---|---|---|---|
| L0 判定 | `guard/sanctuary/Sanctuary` | "这一下是谁打的" | `LivingAttackEvent` HIGHEST |
| L1 写入口 | `compat/artifact/DamageAdmission` | `setHealth` / `SynchedEntityData#set` | 两个窄 mixin，按作用域判 |
| L2 死亡 | `GuardedEntityDeathScopeMixin` + `Sanctuary.preventDeath` | `die` 直接 return | **判据改成账本**（不再看 raw） |
| L3 移除 | `EntityRemovalGuardMixin` / `EntityIsRemovedGuardMixin` / `EntityLevelCallbackGuardMixin` | `setRemoved` / `isRemoved` / NULL callback | 按 RemovalReason + 区块是否还开着 |
| L4 掉落 | `DropLootGuardMixin` + `LivingDropsEvent` | 绕过 die 的强制掉落 | 账本说活着就清空 |
| L5 硬锁 | **`guard/sanctuary/Aegis`（新）** | guardedData / DATA_HEALTH / dead / deathTime / removalReason | 每 tick 对账回写 |
| L6 外围 | `GungnirRecovery` / `GolemKit`（新） | 对面账本（erased/sealed）、装备槽 | 反射清理 + 快照补回 |
| L7 hook | `ArcaneVortex*GuardMixin` / `Gungnir*GuardMixin` / `HardEraseGuardMixin` / `Miztinker*` / `Omnimbos*` / `Witherzilla*` | 已知硬清除入口 | 方法 HEAD cancel（`require=0`） |

**Aegis 的不变量**（只对有主、服务端、未真死的傀儡）：

```
guardedData >= ledger      (宿主血账)
DATA_HEALTH >= ledger      (同步血字段)
removalReason == null      (被 setRemoved / 反射写之后复活)
dead == false, deathTime=0 (清"看起来死了")
装备槽快照补回             (最近 200 tick 内被篡改过才动手)
```

账本只在**被 Sanctuary 准入的伤害事件**（`LivingDamageEvent`）与治疗事件里变化；
`raw/guarded` 向上的变化一律承认。所以"攻击方先写 0 再调 die/remove"这种同 tick 组合也说服不了它。

---

## 6. 已知空洞（不承诺）

1. **`CHANGED_DIMENSION` 的移除仍放行**：jerotesvillage 用它做硬删除（`hurt(MAX)×2` 后
   `setRemoved(CHANGED_DIMENSION)`，跳过 die、无掉落）。放行是因为跨维度传送也走它；
   要区分得看"移除后有没有在新维度重新加入"。当前只靠 `setRemoved` 之前那两发 `hurt` 被事件拦。
2. **世界索引被直接拆掉**（`EntityLookup.byId/byUuid`、`ChunkMap.entityMap`、`EntityTickList`）：
   没有通用 mixin 点；`setLevelCallback(NULL)` 能拦一部分，`GungnirLedger` 能拦 toaru。
   AV 的 `HardRemove` 走 `Unsafe.putObject` 抹底层数组，唯一办法是守望重注册（未实现）。
3. **AV 的 native DLL / `ClassRestoreManager`**：可以剥掉我们的注入。Aegis 是每 tick 的行为，
   不依赖注入存活，但 `setRemoved` 门被剥掉时会退化成"事后复活"。
4. **`fantasy_true_health_sync` 必须是 false**（实例配置已改）：它会把 `guardedData` 向下同步到
   被篡改的 raw，等于把"篡改"变成"真的死"。
5. **梦终/iss_csw 的真伤现在打不动受保护傀儡**：账本不降 → Aegis 顶回。这是"先全拦"的
   直接后果；要恢复"能吃一部分真伤"，需要给它们写适配器（`handled_*` + 重新结算），不是这一轮的事。
6. **`Spore` 的铁傀儡转换**：`LivingDeathEvent` 里 `discard()` + 生成新实体；死亡已被否决，
   但如果将来放开死亡路径，需要在 `LivingDeathEvent` 前置拦转换。
7. **cataclysm 复活甲**（`setHealth(5)` + cancel 死亡）对傀儡不适用（穿戴者限定），但
   如果将来给傀儡穿，会被 `setHealth` 门拦下——需要放行进"外部复活"白名单。

---

## 7. 怎么验（进游戏后按顺序）

1. `/ga guard status`：应打印 `[sanctuary] mode=ENFORCE ... hardlock=ON interval=1 watched=N`。
2. `/ga guard hardlock`：看四类计数；`/ga guard hardlock now` 立刻跑一遍。
3. 空手左键自己的傀儡：`would_reject`/`rejected kind=OWNER`，血不掉（ENFORCE）。
4. toaru 右键 Gungnir（对准傀儡）：应出现
   `blocked site=GungnirDamageApi$Authority.isProtected` 或 `GungnirExecution.finishRemoval`；
   傀儡血不掉、不被移除；`/gungnir records` 里不应出现它的 UUID。
5. ArcaneVortex 的清除物品（EntityRemover / VanSh）：`blocked site=EntityKillerHelp.*`。
6. 梦终剑砍傀儡：血不掉（Aegis 会打 `HEALTH_TAMPER_REPAIRED` 或 `guardedLocks` 计数）。
7. 正常敌人打死一只傀儡：账本归零 → 允许死亡/回收，`the_risen` 照常掉物品。
8. 回收手杖：`DISCARDED` 放行，不出现 `hard_repaired`。
