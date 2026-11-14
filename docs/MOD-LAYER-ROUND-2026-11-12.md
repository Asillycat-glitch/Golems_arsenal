# mod 层补强轮（2026-11-12）：交接文档对照 + 实际补上的门

> 起因：上一份交接文档说"mod 层是满的"，但先读代码再对照，发现这个结论不成立。
> 这一轮只做 mod 层：不 attach agent、不从 `sanctuary-agent` 抄任何实现，也不把
> "拆掉自己的高风险实现"算成"防御被削弱"。

---

## 1. 交接文档 vs 源码（先读再动手的结果）

| 交接文档的说法 | 源码实际 | 后果 |
|---|---|---|
| `RemovalGuard` 已覆盖移除 | `allow()` 只拦 `KILLED`，`DISCARDED` 一律放行 | toaru `finishRemoval`、AV `discard()`、Capsule 清区全部能走 |
| `mod 层满的，伤害门覆盖傀儡` | `GuardedEntity#setHealth` 是 **final 覆写**，不调用 `super.setHealth`；`GuardedEntity#actuallyHurt` 也是 final 覆写 | 挂在 `LivingEntity` 上的 `setHealth` / `actuallyHurt` mixin 对傀儡**根本不执行**；真正执行的是 `GuardedEntity` 的覆写 |
| `kill` 有门 | `LivingEntityKillGuardMixin` 源码存在，但 **没有注册** 到 `golems_arsenal.mixins.json`；而且 `GuardedEntity#kill` 也是覆写 | `kill()` 对傀儡没有任何 mod 层门 |
| 掉落/回调有门 | refmap 里有 `DropLootGuardMixin` / `EntityLevelCallbackGuardMixin`，源码里 **没有这两个类** | `dropAllDeathLoot`、`setLevelCallback(NULL)` 没有门 |
| `actuallyHurt` 未做 | 确实没有 | AV 的 `EntityKillerHelp` / iss_csw 的直伤路径没有 mod 层入口 |
| 拆索引未做 | 确实没有 `ClassInstanceMultiMap` / `EntityLookup` 门 | AV 的 `MainRemove` / jzyy 的 `superKillEntity` 可以直接摘索引 |
| `hard_lock` 是硬锁层 | `Config.SANCTUARY_HARD_LOCK` 存在但 **没有任何代码读取**；`Aegis` 类不存在（只在 javadoc/注释里被提到）；`RemovalGuard.hardRepair` 是死代码 | "每 tick 不变量"没有真正跑；`Watchdog` 固定 10 tick，且只清 `removalReason`，不清 `dead` / `deathTime` |
| 身份轮换 mod 层也能做 | 源码里没有 `IdentityGuard` / `FAKE_DEAD`；只有 agent 侧有 | mod 层没有"旧 UUID 假死 + 生成时洗 UUID" |
| `reindex_failed` 是攻击方赢了 | `Anchor`/`Watchdog` 走 `level.addFreshEntity`，会被 `knownUuids` 拒绝 | 实际上可以绕过 `addFreshEntity` 直接 `EntityLookup#add` + section add |
| `LivingEntityMaxHealthHideMixin` 是门 | 源码存在但没注册，且方法体第一句是 `if (true) { return; }`（读侧掩护已被关掉） | 不要把它算成"现有防御"；本轮没有重新打开 1e9 掩护值 |
| `ReadShield` 掩护值 | `GuardedEntityHealthReadMixin` 里是 `if (false)`，`LivingEntityMaxHealthHideMixin` 里是 `if (true)` | 读侧掩护处于关闭状态；本轮没有把它打开（避免再次把 1e9 灌进账本） |

另外两个测试环境事实：

- 测试实例里是 `modulargolems-2.7.5+3.jar`，而 `build.gradle` 编译依赖是 `2.7.3`；
  本轮没有改依赖版本，但测试时要留意 `GuardedEntity` / `AbstractGolemEntity` 的方法名是否变化。
- `runServer` 的 dev 环境本身缺 MixinExtras，且 `GolemTrackingMechanicalBowItem` 在
  registry 阶段读配置（`Cannot get config value before config is loaded`）。这是**既有**的
  dev 环境问题，不是本轮新增门造成的；真实结论要用测试实例的 jar 跑。

---

## 2. 本轮实际补上的门

### 2.1 移除策略（`RemovalGuard`）

现在的 reason 判定：

| reason | 行为 |
|---|---|
| `KILLED` | 活体一律拦；真死（死亡栈 / 账本见过死亡 / 账本归零）放行 |
| `DISCARDED` | 默认拦；只有玩家回收窗口或宿主 `RetrievalWandItem → AbstractGolemEntity.toItem` 栈放行 |
| `UNLOADED_TO_CHUNK` | 区块真的不在 → 放行；区块还开着（冒充卸载）→ 拦 |
| `UNLOADED_WITH_PLAYER` | 放行（carryon 这类合法搬运） |
| `CHANGED_DIMENSION` | 放行（跨维度必须先放行，否则自己卡传送） |

玩家回收的两条证据：

1. `MobInteractGuardMixin` 在玩家手持 `RetrievalWandItem` 右键傀儡时记
   `player_recovery_window`（默认 10 tick，`Config.SANCTUARY_RECOVERY_TICKS`）；
2. `RemovalGuard.isHostRetrieval()` 检查调用栈里是否**同时**有
   `RetrievalWandItem` 和 `AbstractGolemEntity.toItem`——只认宿主回收链，
   普通 `discard()` 不会出现这两个帧。

`SEALED` 下两条都关掉；`Projection.onRightClickItem` 还会取消回收手杖的 shift-use，
避免出现"discard 被拦下、物品仍然发到背包"的复制漏洞。

### 2.2 索引 / 回调 / 直伤（`IndexGuard` + 新 mixin）

| 入口 | 文件 | 行为 |
|---|---|---|
| `ClassInstanceMultiMap#remove` | `ClassInstanceMultiMapGuardMixin` | 受保护活体被摘 section → 返回 false |
| `EntityLookup#remove` | `EntityLookupGuardMixin` | 受保护活体被摘查询索引 → 取消 |
| `EntityTickList#remove` | `EntityTickListGuardMixin` | 受保护活体被从 tick 列表摘掉 → 取消 |
| `Entity#setLevelCallback(NULL)` | `EntityLevelCallbackGuardMixin` | 正常生命周期外被置 NULL → 取消 |
| `LivingEntity#actuallyHurt` | `ActuallyHurtGuardMixin` | 不在 Sanctuary 已准入 hurt 窗口里的直伤 → 取消 |
| `GuardedEntity#actuallyHurt` | `GuardedEntityActuallyHurtGuardMixin` | 同上（覆盖 final 覆写） |
| `GuardedEntity#setHealth` | `GuardedEntitySetHealthGuardMixin` | 未准入的下降写 → 取消，且 `guardedData` 不会被改小 |
| `GuardedEntity#kill` | `GuardedEntityKillGuardMixin` | 账本说活着 → 取消 |
| `LivingEntity#kill` | `LivingEntityKillGuardMixin`（本轮注册） | 基类兜底 |
| `LivingEntity#dropAllDeathLoot` | `DropLootGuardMixin` | 活体被直接掉包 → 取消 |

### 2.3 身份轮换（`IdentityGuard`）

- 门拦下移除时：`setUUID(fresh)` → 更新 `EntityLookup.byUuid` 和
  `PersistentEntitySectionManager.knownUuids` → 迁移我们自己的所有 UUID 表
  （`HealthLedger` / `Anchor` / `DataGuard` / `Projection` / `RemovalGuard` /
  `Sanctuary` / `Watchdog` / `AttributeGuard` / `DamageAdmission` / `IndexGuard`）
  → 旧 UUID 进 `FAKE_DEAD`；
- `Entity#load` HEAD：NBT 里的 UUID 在 `FAKE_DEAD` 里 → 换新 UUID 再读
  （物品放出来还是同一只傀儡，但旧名单打不中）；
- `Anchor` 快照重建：同上洗 UUID，再迁移内部表；
- 宿主 `GolemTracker.data` 按 UUID 存的条目也一起迁移；换名失败只记 `failed`，
  不影响移除守卫本身的拒绝。

### 2.4 硬锁 / 守望（`Watchdog`）

- `SANCTUARY_HARD_LOCK` 现在真正生效；间隔读 `SANCTUARY_LOCK_INTERVAL`（默认 1 tick）；
- 每轮先 `Anchor.repairPosition`（NaN/世界外坐标会让 `isLoaded` 失真）；
- `removalReason` 用 `RemovalGuard.hardRepair` 修，正常卸载/跨维度/玩家回收不硬顶；
- 账本说活着、但 `dead` / `deathTime` 被写死 → 回写（`HARDLOCK dead=false` /
  `HARDLOCK deathTime=0`）；
- 原始 `byUuid` 索引缺失 → `IndexGuard.reindex` 直接 `EntityLookup#add` + section add
  + `knownUuids.add`，不再只依赖会被 `knownUuids` 拒绝的 `addFreshEntity`；
- `ServerLevel.entityTickList` 里没有它 → `IndexGuard.ensureTicking` 补回
  （`TICKLIST-READDED`）。

### 2.5 账本/监视表的生命周期修正

- `HealthLedger#onLeave`：只有"正常生命周期"或真死才删账本；被拦下的
  `KILLED` / `DISCARDED` / 冒充卸载保留孤儿账本；
- `Anchor#onLeave` / `Watchdog#onLeave`：被拦下的移除继续盯，不能被
  `EntityLeaveLevelEvent` 骗走；
- `Anchor#check`：账本归零（宿主 `postHurt` 回收路径没有 `LivingDeathEvent`）
  也算真死，不再重建。

---

## 3. 新增/修改文件

新增：

- `guard/sanctuary/IdentityGuard.java`
- `guard/sanctuary/IndexGuard.java`
- `mixin/ActuallyHurtGuardMixin.java`
- `mixin/GuardedEntityActuallyHurtGuardMixin.java`
- `mixin/GuardedEntitySetHealthGuardMixin.java`
- `mixin/GuardedEntityKillGuardMixin.java`
- `mixin/DropLootGuardMixin.java`
- `mixin/EntityLevelCallbackGuardMixin.java`
- `mixin/ClassInstanceMultiMapGuardMixin.java`
- `mixin/EntityLookupGuardMixin.java`
- `mixin/EntityTickListGuardMixin.java`

修改：

- `RemovalGuard` / `HealthLedger` / `HealthLedgerData` / `Anchor` / `DataGuard` /
  `Projection` / `Sanctuary` / `Watchdog` / `AttributeGuard` / `DamageAdmission`
- `EntityLoadScopeMixin`（生成时洗 UUID）
- `MobInteractGuardMixin`（回收窗口）
- `Config`（`player_recovery_window`、removal 注释）
- `golems_arsenal.mixins.json` / `golems_arsenal.refmap.json`
- `GuardCanary`（新 handler 自检）
- `GuardCommand`（`/ga guard identity`，`watch` 带 index/identity）

---

## 4. 构建与验证

- `.\gradlew.bat compileJava`：通过。
- `.\gradlew.bat build -Ptestbench`：通过；产物
  `build/libs/golems_arsenal-0.3-testbench.jar`（约 1,422,468 B）。
- 产物 manifest 有 `MixinConfigs: golems_arsenal.mixins.json,...`；
  `golems_arsenal.mixins.json` 已包含 9 个新 mixin，并补注册了原本漏掉的
  `LivingEntityKillGuardMixin`；refmap 已包含
  `ActuallyHurt` / `GuardedEntity*` / `DropLoot` / `LevelCallback` / `EntityLookup` /
  `EntityTickList` / `ClassInstanceMultiMap` 条目。
- dev `runServer` 的两次失败都不是本轮 mixin 报错：
  1. 第一次缺 MixinExtras（`l2library` 里 jarjar 的库；已临时放到 `run/mods` 再试）；
  2. 第二次在 registry 阶段被既有的 `GolemTrackingMechanicalBowItem` 配置读取问题挡住。
  因此**本轮没有完成真实启动验证**；结论以测试实例加载 jar 为准。

---

## 5. 下一轮测试清单（先 mod 层，再 agent）

启动看：

```
[Sanctuary] agent=NOT ATTACHED        # 本轮不测 agent，先确认 mod 层单独成立
[Sanctuary] mode=ENFORCE rules=... removal=ENFORCE ...
[Sanctuary] guard canary: all 15 hooks present
```

进世界后：

1. `/ga guard dump 32` 记录基线；
2. 主人左键傀儡 → 应看到 `removal rejected ...` 或 `HURT-DENIED`，`dump` 数量/raw 不变；
3. `NORMAL` 下用回收手杖右键/潜行右键 → 应正常回收（`recoveryWindows` 增加）；
4. `SEALED` 下用回收手杖 → 应被 `Projection.onRightClickItem` 拒绝，且不产生复制物品；
5. `/ga guard toaru` / AV 的 VanSh → 看 `REMOVE-DENIED`（`removal rejected`）、
   `INDEX-DENIED`、`IDENTITY-ROTATED`、`SPAWN-UUID-WASHED`；
6. `/kill`、`Capsule` 清区、区块卸载/跨维度 → 前者应被拦，后两者应正常；
7. 真死（账本归零）→ 应放行并正常掉落/回收；
8. `/ga guard watch` / `/ga guard identity` 看
   `indexBlocked` / `lookupBlocked` / `tickBlocked` / `callbackBlocked` / `damageBlocked` /
   `reasonCleared` / `revivedFields` / `readded` / `tickingRestored` / `rotated` / `fakeDead`。

---

## 6. 已知风险（别当成"已经验证"）

1. **活体 UUID 轮换**：迁移的是本模组自己的表和宿主 `GolemTracker`；如果别的模组
   在别处缓存了旧 UUID，仍可能打空/打错。轮换失败只记 `failed`，不影响拒绝。
2. **`ClassInstanceMultiMap` / `EntityLookup` 的 null-reason 分支**：正常 chunk
   visibility 变化也可能走 `EntityLookup#remove`，所以 null-reason 只在
   `recentTamper` 或 `inHurt` 时拦；一次"不碰血、直接摘索引"的攻击仍可能先漏，
   随后由 `Watchdog` 的 `IndexGuard.reindex` 补回。
3. **`UNLOADED_TO_CHUNK` 的假卸载判定**用 `level.isLoaded(pos)`；区块卸载的边界帧
   有竞态，测试时要专门看区块卸载是否被误拦。
4. **硬锁每 tick**：`hard_lock_interval=1` 对大量傀儡有 CPU 成本；如果服务端 TPS
   掉，先把它调到 2–5，而不是删门。
5. **`GuardedEntity.setHealth` 门依赖 `damage_admission.enabled`**：这是既有的总开关；
   如果为了排查把它关掉，这一层也会跟着关。
6. **给下一个人的一句话**：这一轮补的是"门"和"索引/身份/硬锁"，不是"证明"。
   测试实例里先确认 `modulargolems` 是 2.7.5 还是 2.7.3，再逐条跑上面的清单。
