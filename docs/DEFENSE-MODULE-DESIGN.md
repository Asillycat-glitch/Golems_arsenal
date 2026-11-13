# 防御模块设计：从 Omni-Mobs 的 "L2 防线" 到可测试的 P0–P4

> 这份文档回答三件事：
> 1. 你引的那段 "能承诺什么 / P0–P4" 哪些地方**不够准确**，在这个包面前要怎么改；
> 2. `Omni-Mobs` 里已经有一份**可参考的 L2 防线实现**，它做了什么、缺什么；
> 3. 一份**干净、可测试、可清除**的防御模块骨架（不写 agent / native / Unsafe）。
>
> 结论先行：**你引的承诺范围太窄。** 这个包里的攻击不止 `setHealth`/`remove`/`discard`，
> 还有 `forceHurt`（直写 `DataItem`）、`forceSetHealth`（写所有血字段）、`forceRemove`（绕 `remove` 清实体索引）、
> `setRemoved`、`klassPtr` 换类、裸线程、Java agent、JNA DLL。
> 所以承诺要分三档：**能拦（intercept）**、**能记（detect/log）**、**拦不住（out of scope）**。

---

## 0. 把"能承诺什么"改准确

你引的版本：

> 对"受保护实体"，直接写血（setHealth/真血反射/HEALTH_SEAL）和直接移除（remove/discard）会被拦截或记录；
> L2 伤害管线保持不变；bypasses_invulnerability 的 hurt 类伤害和同进程 agent/改 jar 不在防护范围内。

**问题**：
- "直接写血"只提了 `setHealth`/反射/`HEALTH_SEAL`，但这个包最狠的是 **`forceHurt` 直写 `SynchedEntityData.DataItem`**
  和 **`forceSetHealth` 写所有血字段 + 所有 `setHealth` 方法**；
- "直接移除"只提了 `remove`/`discard`，但这个包有 **`forceRemove` 直接清 `EntityLookup`/`PersistentEntitySectionManager`/`ServerLevel` 索引**，
  以及 **`setRemoved`** 绕过 `remove`；
- "L2 伤害管线保持不变"——如果别的 mod 用 `priority = Integer.MAX_VALUE` 的全局 `LivingEntity` mixin，
  你的 L2 观测本身就会被动过；所以要说"**我们不改 L2，但不能保证别人不改**"；
- "agent/改 jar 不在范围内"——对，但还要加 **`klassPtr` 换类、隐藏类、JNA DLL、裸线程** 也不在范围内（或只能检测）。

**建议的承诺（三档）**：

| 档 | 范围 | 承诺 |
|---|---|---|
| **A. 能拦/能改** | 对"受保护实体"的 `setHealth`（含反射调 `setHealth`）、`remove`/`discard`、`/data merge|modify|remove` | SHADOW 记录；ENFORCE 按策略拒绝/夹紧；每次都有审计日志 |
| **B. 能检测/能告警** | `forceHurt` 直写 `DataItem`、`forceSetHealth` 写所有字段、`setRemoved`、`forceRemove`/`killEntity`、`klassPtr` 换类、裸线程、`getHealth` 被子类/mixin 改写 | 每 tick 对账 + `EntityLeaveLevelEvent` + 类名/线程检查；**只记录，不保证能拦** |
| **C. 拦不住** | `bypasses_invulnerability` 的 hurt、同进程 Java agent（`jzyyfix`）、native DLL（`BlueScreen`/`renderentity`）、改 jar、OS 级 | 明确写在文档里；不承诺 |

**一句话版本**：
> 对受保护实体，`setHealth`/`remove`/`discard`/`/data` 会被拦截或记录；
> `forceHurt`/`forceSetHealth`/`setRemoved`/`forceRemove`/换类/裸线程会被检测并告警；
> agent/native/改 jar/`bypasses_invulnerability` 不在防护范围内。

这个版本比原来准确，而且每一档都能写测试。

---

## 1. Omni-Mobs：一份现成的 L2 防线

`omnimobs-0.2.6.jar` 表面是"强力生物"，实际上带了一套 **anti-cheat / 实体保护**：

| 类 | 作用 |
|---|---|
| `entities/anticheat/HealthManager` | 独立的血量账本 `HashMap<BossEntity, Float>`；`setHealth` 必须过 `AccessBlocker.checkAccess()` |
| `entities/anticheat/AccessBlocker` | `Thread.currentThread().getStackTrace()[3]` 必须是 `flashfur.omnimobs.entities` 或 `S2CSyncBossHealth`，否则拒绝并打印 |
| `entities/base/IInvulnerableEntity` | 标记接口：`getHealth` 永远满血、`isAlive` 永远 true、`isDeadOrDying` 永远 false |
| `entities/base/BossEntity` | `getHealth`/`setHealth`/`isAlive`/`isDeadOrDying` 全部重定向到 `HealthManager`；`setHealth` 带减伤/每击上限/i-frame；`remove`/`setRemoved` 空实现 |
| `mixin/DataCommandsMixin` | `priority = Integer.MAX_VALUE`；取消对 `Flashfur` 的 `/data merge`、`/data modify`、`/data remove` |
| `mixin/EntityLookupMixin` | `priority = Integer.MAX_VALUE`；取消 `EntityLookup.remove`（保护 `FlashfurMetapotent`） |
| `mixin/PersistentEntitySectionManagerMixin` | `priority = Integer.MAX_VALUE`；取消 `stopTicking`（保护 `FlashfurMetapotent`） |
| `mixin/LivingEntityMixin` | 加 `DATA_MODIFY_GET_HEALTH_DELTA`；`getHealth` 返回 `DATA_HEALTH + delta` |
| `util/EntityUtil` | `forceSetHealth`（`setHealth` → `DATA_HEALTH` → 所有同步血字段 → 所有血字段 → 所有 `setHealth` 方法）；`forceRemove`（清 `EntityLookup`/`PersistentEntitySectionManager`/`ServerLevel`/`ClientLevel` 索引）；`forceSetPos`（1e9） |
| `items/EntityRemover` | 左键/右键强制移除所有非玩家实体，`setPos(1e9)`，再调 `die` |

**它值得学的四个点**：
1. **血量不在 `DATA_HEALTH` 里**：`HealthManager` 是账本，`getHealth` 读账本；`setHealth` 必须过 caller check。
2. **`/data` 命令是 L0 攻击面**：`DataCommandsMixin` 直接取消，别让 op 命令改 NBT。
3. **世界索引是移除的必经之路**：`EntityLookup.remove`/`stopTicking` 被拦，`discard` 就没那么容易生效。
4. **`forceSetHealth` 是"写血"的完整清单**：`setHealth`、`DATA_HEALTH`、所有同步 Float、所有血字段、所有 `setHealth` 方法——你的检测要覆盖这些路径。

**它的不足（也是你要补的）**：
- `AccessBlocker` 只看 `stacktrace[3]`，反射/agent/`MethodHandle` 一绕就过；它只是**减速带**，不是安全边界。
- `HealthManager` 是 `static HashMap`，**不是线程安全、不持久化**；实体重建/重启就丢。
- `BossEntity.m_142687_`/`m_142467_` 是空实现——能挡 `remove`/`setRemoved`，但 `EntityUtil.forceRemove` 直接清索引，绕过去了。
- `DataCommandsMixin`/`EntityLookupMixin` 只保护 `Flashfur`/`FlashfurMetapotent`，不是通用保护。
- `LivingEntityMixin` 的 `delta` 是**显示偏移**，不是账本；`getHealth` 仍可能被别的 mixin 改。
- 没有换类检测、没有裸线程检测、没有 agent/native 检测。

---

## 2. 干净模块骨架（建议放 `a_silly_cat.golems_arsenal.defense`）

> 目标：可测试、可清除、可打包成 OFF/SHADOW/ENFORCE；**不写 agent / native / Unsafe**；
> **不用 `priority = Integer.MAX_VALUE` 的全局 mixin**（那会和这个包里一堆 mod 打架）。

### 2.1 包结构

```
defense/
├── DefenseMode.java          // OFF / SHADOW / ENFORCE
├── ProtectionRegistry.java   // UUID -> ProtectionRecord；SavedData + 内存
├── HealthLedger.java         // 原始 DATA_HEALTH + 期望值 + 每次伤害/治疗
├── TickAudit.java            // 服务端线程，每 5 tick 对账
├── AuditLog.java             // JSONL：谁、什么类型、什么伤害源、tick、位置
├── DefenseCommand.java       // /ga defense mode|status|clear|audit
├── DefenseBootstrap.java     // 注册事件；无 mixin（或只有一个窄 mixin）
└── mixin/
    └── SetHealthGuardMixin.java  // 可选；只对受保护实体生效；gated by MixinConfigPlugin
```

### 2.2 关键设计

#### `ProtectionRegistry`

```java
public record ProtectionRecord(
    UUID id, ResourceKey<Level> dim,
    float lastRawHealth, long lastTick,
    String expectedClass, boolean enforce) {}

public final class ProtectionRegistry {
    private static final Map<UUID, ProtectionRecord> MEM = new ConcurrentHashMap<>();
    // SavedData: 只存 UUID/维度/expectedClass/lastKnownHealth，不存"安全状态"的布尔
    public static boolean isProtected(LivingEntity e) { ... }
    public static void protect(LivingEntity e, boolean enforce) { ... }
    public static void clear(UUID id) { MEM.remove(id); AuditLog.write("DEFENSE_CLEAR", id); }
}
```

#### `HealthLedger`

**永远读原始 `DATA_HEALTH`，不要读 `getHealth()`**：

```java
public static float rawHealth(LivingEntity e) {
    return e.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
}
```

因为 `getHealth` 可能被 `BossEntity`、`AbsoluteSeveranceHealthMixin`、`LivingEntityMixin` 改写；
`rawHealth` 才是"数据层真实值"。

每 tick 对账：

```java
float expected = rec.lastRawHealth() + healThisTick - damageThisTick;
float actual   = rawHealth(e);
if (!Float.isFinite(actual) || Math.abs(actual - expected) > EPS) {
    AuditLog.write("UNEXPLAINED_HEALTH", e, rec, actual, expected);
}
```

`damageThisTick`/`healThisTick` 由 `LivingDamageEvent`/`LivingHealEvent` 累加；
`forceHurt` 直写 `DataItem` 不走事件，所以 `expected` 不变、`actual` 变了——**正好被抓到**。

#### `SetHealthGuard`

两条路，二选一：

**A. 纯事件（推荐，零 mixin）**
- `LivingAttackEvent` + `LivingHurtEvent` + `LivingDamageEvent` 只做记录；
- `setHealth` 直写用 tick 对账抓（因为 `setHealth` 不发事件）。

**B. 窄 mixin（可选，必须 gate）**
```java
@Mixin(value = LivingEntity.class, priority = 1000)
public abstract class SetHealthGuardMixin {
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ga$guard(float value, CallbackInfo ci) {
        if (!ProtectionRegistry.isProtected((LivingEntity)(Object)this)) return;
        if (DefenseMode.current() == DefenseMode.SHADOW) { AuditLog.write("SET_HEALTH_SHADOW", ...); return; }
        if (DefenseMode.current() == DefenseMode.ENFORCE) { ... ci.cancel(); }
    }
}
```
- 用 `MixinConfigPlugin` 包起来，只在 `golems_arsenal` 自己的实体上应用；
- **不要** `priority = Integer.MAX_VALUE`；
- **不要**全局改 `addEffect`/`getHealth`。

#### `RemovalGuard`

不要只拦 `remove`/`discard`，因为 `forceRemove` 直接清索引。三层：

1. `EntityLeaveLevelEvent`：实体离开世界时记录原因；
2. tick 对账：`e.isRemoved()` 但本 tick 没有 `LivingDeathEvent` → `UNEXPLAINED_REMOVAL`；
3. 可选窄 mixin：`Entity.setRemoved` HEAD，记录 `RemovalReason`/调用者（stack-walk），不改行为。

#### `TickAudit`

服务端线程，每 5 tick 对受保护实体：

```java
if (!server.isSameThread()) { AuditLog.write("OFF_THREAD_AUDIT"); return; }
if (e.isRemoved() && !deathSeen) AuditLog.write("REMOVED_WITHOUT_DEATH");
if (!e.getClass().getName().equals(rec.expectedClass())) AuditLog.write("CLASS_CHANGED");
if (e.getHealth() != rawHealth(e)) AuditLog.write("GET_HEALTH_OVERRIDE");
if (e.getPersistentData().getLong(KEY) != rec.token()) AuditLog.write("STATE_TAMPERED");
```

#### `AuditLog`

JSONL，字段：

```json
{"tick":123,"event":"UNEXPLAINED_HEALTH","uuid":"...","entity":"...",
 "raw":40.0,"expected":40.0,"getHealth":12.0,"source":"attackmod:true_damage",
 "attacker":"...","class":"...","thread":"Server thread","pos":[...]}
```

#### `DefenseCommand`

```
/ga defense mode <off|shadow|enforce>
/ga defense status
/ga defense clear <uuid>        // 只清账本，不动实体；记 DEFENSE_CLEAR
/ga defense audit <uuid>        // 打印最近 N 条
```

#### `clear()`（如果你说的是"清除/复位防御状态"）

只清自己的账本/注册表，**绝不**清实体的血/状态：

```java
public static void clear(UUID id) {
    ProtectionRegistry.MEM.remove(id);
    HealthLedger.MEM.remove(id);
    AuditLog.write("DEFENSE_CLEAR", id);
}
```
- 只允许命令/测试调用；
- 每次 clear 都记日志；
- 不要把 `clear` 暴露成 NBT/配置/网络包（那就是后门）。

---

## 3. P0–P4：在这个包面前要加什么

| 阶段 | 你引的建议 | 这个包要求你加的东西 |
|---|---|---|
| **P0 基线** | 记录 L2 事件/血量/`setHealth` 调用者 | 加：**原始 `DATA_HEALTH`**、`DataItem` 直写、`setRemoved`/`remove` 原因、`EntityLeaveLevelEvent`、类名/线程、`DamageSource` 身份、`ModList` 快照、JVM args/agent 扫描 |
| **P1 影子账本** | 只读，不拦截 | 加：`rawHealth` vs `getHealth` 对比、每 UUID 的 `SavedData`、重启/重建后重挂、`forceHurt` 的"事件没涨、数据涨了"检测 |
| **P2 局部 ENFORCE** | 一个测试实体，A/B | 加：对 `forceHurt`/`forceSetHealth`/`forceRemove`/`setRemoved`/`/data merge`/`killEntity`/换类 各跑一遍；加 kill switch；**不要** 用 `priority=MAX` |
| **P3 玩家** | 先 SHADOW 再 ENFORCE | 加：`ServerPlayer` 断线/回滚；不写玩家血；检测 `abilities.instabuild/mayfly`；`DataItem` 写保护 |
| **P4 打包** | OFF/SHADOW，无后门 | 加：扫 `Premain-Class`/`Agent-Class`/`FMLCorePlugin`/`.dll`；正式 jar 不许有 agent/native；模式默认 OFF；哈希/签名 |

**P0 的"正常长什么样"要包含**：
- 正常 `hurt` 一次：`LivingAttackEvent` → `LivingHurtEvent` → `LivingDamageEvent` → `rawHealth` 下降；
- 正常 `heal` 一次：`LivingHealEvent` → `rawHealth` 上升；
- 正常 `remove`/`discard`：`EntityLeaveLevelEvent` + `isRemoved`；
- 正常 `kill`：`LivingDeathEvent` + `rawHealth == 0`。

**这些"正常"基线不成立，后面所有判断都是错的。**

---

## 4. 测试矩阵

| 攻击 | 工具 | 期望 |
|---|---|---|
| `setHealth(0)` | 测试命令 | SHADOW 记录；ENFORCE 拒绝/夹紧 |
| `forceHurt`（直写 `DataItem`） | `Omni-Mobs` `EntityUtil.forceHurt` 同款 | tick 对账抓到 `UNEXPLAINED_HEALTH` |
| `forceSetHealth`（写所有字段） | `Omni-Mobs` `EntityUtil.forceSetHealth` 同款 | 抓 `raw` 变化 + 类字段变化 |
| `forceRemove`/`killEntity` | `EntityUtil.forceRemove` 同款 | `EntityLeaveLevelEvent` + `REMOVED_WITHOUT_DEATH` |
| `setRemoved(DISCARDED)` | 反射/命令 | `REMOVED_WITHOUT_DEATH` |
| `/data merge` | op 命令 | `DataCommands` 拦截或审计 |
| `klassPtr` 换类 | `PLZBase.klassPtr` 同款 | `CLASS_CHANGED` |
| 裸线程改血 | `new Thread` | `OFF_THREAD_AUDIT` |
| Java agent | `jzyyfix` 同款 | JVM args/agent 扫描（只能检测） |
| JNA DLL | `BlueScreen` 同款 | jar native 扫描（只能检测/不装） |

---

## 5. 不要做

- **不要** `priority = Integer.MAX_VALUE` 的全局 `LivingEntity` mixin；
- **不要**全局 `getHealth`/`addEffect` mixin；
- **不要** `Unsafe`/`klassPtr`/`defineHiddenClass`；
- **不要** Java agent / native DLL；
- **不要**每 tick 写血（和 legit 伤害打架、橡皮筋）；
- **不要**在 P2 数据出来前对外说"挡住了"；
- **不要**把 `clear()` 暴露成命令以外的入口（NBT/配置/网络包 = 后门）。

---

## 6. 一句话总结

- 你引的承诺**方向对，范围窄**：`setHealth`/`remove` 只是这个包攻击面的一小半；
  `forceHurt`/`forceSetHealth`/`forceRemove`/`setRemoved`/换类/裸线程/agent/native 都要单独列。
- `Omni-Mobs` 是一份**可参考的 L2 防线**：独立账本 + caller check + `/data` 拦截 + 世界索引拦截 + `forceSetHealth`/`forceRemove` 的"完整攻击清单"。
- 你要补的是：**原始 `DATA_HEALTH`**、**DataItem 直写检测**、**移除原因/索引检测**、**类名/线程检查**、**持久化**、**agent/native 扫描**、**A/B 测试矩阵**。
- 先做 P0/P1，再对一个测试实体 P2；P2 数据出来之前，只说"能拦/能记/拦不住"三档，不说"挡住了"。

---

## 参考

- `Omni-Mobs`：`HealthManager`、`AccessBlocker`、`BossEntity`、`DataCommandsMixin`、`EntityLookupMixin`、`PersistentEntitySectionManagerMixin`、`EntityUtil`、`EntityRemover`
- 本仓库：`docs/THREAT-MODEL-MUGEN.md`、`docs/CODE-STUDY-POWER-MOBS.md`、`docs/MC-ENGINE-NATIVE-LAYER.md`
