# Golem Sanctuary（2026-11-06，激进改动）

> 构建：**1,062,879 B / SHA256 `D656F47A11FEC3ED3444C642DFACE099D0740FBF38D5AD6ED044E1D651D37B70`**，已安装。
> 回滚：`mods-backup\golems_arsenal-0.3-testbench-1040595-20261106-2042.jar`（= 上一版 1,040,595 / `E5AD4448…`）。
> 单测 30/30；`build -Ptestbench` 通过。**没有启动游戏验证过**（第 6 节就是验证清单）。

## 0. 一句话

把两层判定都换掉了：

- 写血：**“调用栈里出现了白名单类名” → “这次写发生在哪个作用域里”**；
- 攻击：**“这个物品/这个 mod 危不危险” → “这一下是谁打的”**。

## 1. 新模型

### 1.1 伤害判定（目标侧，`LivingAttackEvent` HIGHEST）

`Sanctuary.classify()` 只回答“谁打的”：

| 分类 | 判定 | 默认 |
|---|---|---|
| `INTERNAL` | 没有 attacker/direct entity，且伤害类型注册名在 `golems_arsenal` 命名空间（`admission_rehurt` 就是这种） | 放行 |
| `OPERATION` | 伤害类型带 `minecraft:bypasses_invulnerability`。**不看攻击者**：生物、别的玩家、脚本一视同仁 | 拒绝（`deny_operation`） |
| `ENVIRONMENT` | fire / explosion / fall / drowning / freezing / lightning 标签，加上 `outOfWorld`、`outsideBorder`、`badRespawnPoint`；有 direct entity 但没有 attacker 的也归这类 | 放行 |
| `ENEMY` | 有活体 attacker，且不是主人/同主傀儡 | 放行 |
| `OWNER` | attacker 的 UUID == 傀儡 owner | 拒绝 |
| `OWNER_PROJECTILE` | direct entity 的 owner == 傀儡 owner | 拒绝 |
| `ALLY` | attacker 是同主傀儡 | 拒绝 |
| `UNATTRIBUTED` | 没有 attacker、也没有 direct entity、又不是环境类型 | 拒绝 |

`generic_kill`（`kill()` / `/kill`）默认**不**算环境：归 `UNATTRIBUTED`，否则任何“秒杀物品”都能直接抹掉傀儡。
配置 `sanctuary.allow_generic_kill = true` 可放回环境（`/kill` 就能用）。

判定表是纯函数（`SanctuaryRules`），单测直接打它。

### 1.2 写入口（`LivingEntity#setHealth` / `SynchedEntityData#set(DATA_HEALTH_ID)`）

只拦“受保护傀儡 + 生命值下降”。放行条件改成作用域，任何一条成立就放行：

| 作用域 | 谁开的 |
|---|---|
| `hurt` 窗口 + 已被 Sanctuary 准入 | `LivingEntity#hurt` mixin 开/关；`Sanctuary` 放行时标记 |
| `death` | `GuardedEntity#die` mixin |
| `load` | `LivingEntity#readAdditionalSaveData` mixin |
| `internal` | 本模组显式 `SanctuaryScopes.runInternal(...)` |
| 宿主模组包内写 | 兜底：栈里有 `dev.xkmc.modulargolems.*` |

除此之外的下降写一律拒（并记 `[DamageAdmission] rejected`）。**不再维护类名白名单，也不再每次写血都走 StackWalker**（只有被拒时才走一次栈，用来认宿主模组）。

### 1.3 移除守卫（`Entity#setRemoved` mixin）

| reason | 行为 |
|---|---|
| `DISCARDED` / `UNLOADED_TO_CHUNK` / `UNLOADED_WITH_PLAYER` / `CHANGED_DIMENSION` | 一律放行（回收、倒地、卸载、传送） |
| `KILLED` 且已死（`isDeadOrDying()` / 血 ≤ 0 / `death` 作用域） | 放行 |
| `KILLED` 且还活着 | SHADOW 记 `would_reject`；ENFORCE 取消 `setRemoved` |

`HealthLedger` 同步改了：`DISCARDED` 不再记 `REMOVED_WITHOUT_DEATH`，只有 `KILLED` 才记。

## 2. 代码地图

| 文件 | 作用 |
|---|---|
| `guard/sanctuary/Sanctuary` | 分类 + 事件裁决 + 计数/日志 + 运行时模式 |
| `guard/sanctuary/SanctuaryRules` | 判定表（纯函数，可单测） |
| `guard/sanctuary/SanctuaryScopes` | hurt 窗口 / death / load / internal 作用域 |
| `guard/sanctuary/RemovalGuard` | 移除原因口径 |
| `guard/sanctuary/SourceKind`、`SanctuaryMode` | 枚举 |
| `mixin/LivingEntityHurtScopeMixin` | `hurt` HEAD/RETURN 开关窗口 |
| `mixin/LivingEntityLoadScopeMixin` | `readAdditionalSaveData` 开关读档作用域 |
| `mixin/GuardedEntityDeathScopeMixin` | `GuardedEntity#die` 开关死亡作用域 |
| `mixin/EntityRemovalGuardMixin` | `setRemoved` 取消点 |
| `compat/artifact/DamageAdmission` | 写入口判定（已改成作用域模型） |
| `guard/combat/CombatAdmission` | **只剩** `AttackEntityEvent` + `LivingAttackEvent`；use/inventoryTick/swing 全部删掉 |
| `mixin/ItemStackCombatAdmissionMixin`、`LivingEntitySwingCombatAdmissionMixin` | 已停用（空壳，不在 mixin 配置里） |

`golems_arsenal.refmap.json` 新增/删除了四个条目，均为手工维护：

```text
LivingEntity#hurt                 -> m_6469_
LivingEntity#readAdditionalSaveData -> m_7378_
GuardedEntity#die                 -> m_6667_
Entity#setRemoved                 -> m_142467_
```

（`m_6074_` = `GuardedEntity#kill` 已从 refmap 移除：kill 不再算死亡作用域。）

## 3. 配置

```toml
[sanctuary]
mode = "SHADOW"            # OFF / SHADOW / ENFORCE
deny_owner = true
deny_ally = true
deny_unattributed = true
allow_environment = true
allow_generic_kill = false
deny_operation = true
removal_guard = "SHADOW"   # OFF / SHADOW / ENFORCE
```

## 4. 命令

```text
/ga guard sanctuary                  # 状态（模式 + 规则 + 计数 + 作用域深度）
/ga guard sanctuary shadow|enforce|off|config
/ga guard sanctuary clear            # 清计数
/ga guard removal                    # 移除守卫状态
/ga guard removal shadow|enforce|off # 运行时切（不改配置文件）
/ga guard probe <伤害类型> [伤害量]   # 测试刺激：对 16 格内最近的玩家傀儡打一发
```

`probe` 的用法：伤害源**不带攻击者**，所以 `toaru:gungnir` 会落 `OPERATION`、
普通类型落 `UNATTRIBUTED`。它会打印 `verdict=` 和 `raw A -> B`，用来 A/B 验证
SHADOW（掉血）和 ENFORCE（不掉血）。

## 5. 日志长什么样

```text
[Sanctuary] would_reject kind=OWNER target=... amount=12.0 source=player_attack attacker=...
[Sanctuary] rejected kind=OPERATION target=... amount=3.4E38 source=toaru.gungnir attacker=...
[Sanctuary] rejected kind=UNATTRIBUTED ... source=genericKill attacker=none
[Sanctuary] removal would_reject KILLED target=... reason=KILLED health=42.0 raw=42.0 dead=false
[DamageAdmission] rejected setHealth amount=0.0 raw=42.0 caller=<第一帧>
```

## 6. 进游戏验证清单（按顺序）

1. **启动不崩**：四个新 mixin 都是 `require=1`，目标名错会直接崩在启动阶段——先看能不能进主菜单。
2. `/ga guard sanctuary shadow`，左键自己的傀儡：应出现 `would_reject kind=OWNER`，**伤害仍然生效**（影子模式必须准入，否则 SHADOW 会变成事实 ENFORCE——这点已修过一次）。
3. 让敌人打傀儡：不应出现 `would_reject`，伤害正常。
4. `/ga guard sanctuary enforce`，左键自己的傀儡：不掉血；再让敌人打：正常掉血。
5. `/kill` 傀儡（需要 op）：应被拒（`kind=UNATTRIBUTED source=genericKill`）；把 `allow_generic_kill` 改 true 后可放行。
6. 回收/倒地（`DISCARDED`）：不崩、不被拦、`HealthLedger` 不再报 `REMOVED_WITHOUT_DEATH`。
7. 退出重进（读档）：血量正常，日志里不应有 `rejected`。
8. 梦终 true damage（SHADOW 下）：`applied=true` 应该还在。
9. 正常死亡（敌人打死）：`die` 里不再出现 `rejected setHealth`。

## 7. 对 toaru-runes 的核查（第二轮）

对象：`toaru-runes-0.30.5-1.20.1.jar`（`dev.othinus.toaru.*`，321 个类，mod id `toaru`）。

### 7.1 为什么它“不打友军”

`CombatMagic.canTarget(caster, target)`：

```text
target == caster                     -> false
target.isSpectator()                 -> false
target.isAlliedTo(caster)            -> false
target is OwnableEntity
    && caster.getUUID() == ownerUUID -> false      // 就是这一条把玩家自己的傀儡挡掉了
target is Player                     -> server.isPvpAllowed() && caster.canHarmPlayer(target)
```

傀儡实现了 `OwnableEntity`，owner 正是施法玩家 → 直接被排除。**这是模组自己的目标过滤，
不是我们的 Guard 生效**；想让玩家用 toaru 打自己的傀儡，只能改它（或换一个非主人的施法者）。

### 7.2 它的“执行”是怎么落地的

`GungnirExecution.performExecution` 的字节码顺序：

```text
1. 压防御：HEALTH_SEAL 属性修饰符（MULTIPLY_TOTAL -1）、invulnerable=false、
           invulnerableTime=0、setAbsorptionAmount(0)
2. target.hurt(gungnir, Float.MAX_VALUE)        <- LivingAttackEvent，我们能看见
3. target 的 toaru$beginTermination(caster)     <- 它自己的 ledger 标记“已终止”
4. target.setHealth(0)                          <- 我们能看见
5. toaru$forceZeroHealth()                      <- 走 GungnirHealthStorage.toaru$writeHealth
                                                   -> GungnirHealthCellMixin.toaru$writeHealthValue
                                                   -> putfield DataItem.value（字段写，没有方法可挂钩）
6. removeNonPlayer / setRemoved                 <- RemovalGuard 能看见
```

对应的拦截结果（当前构建，ENFORCE）：

| 步 | 我们的行为 |
|---|---|
| 2 | `kind=OPERATION`（`toaru:gungnir` 在 `bypasses_invulnerability` 里）→ 取消 |
| 4 | 没有死亡作用域 → 写入口拒绝 |
| 5 | **拦不到**（`toaru$writeHealth` 直接改 DataItem，不走 `SynchedEntityData.set`） |
| 6 | 若 `isDeadOrDying()` 为假 → `RemovalGuard` 取消；SHADOW 下不拦 |

### 7.3 结论 / 下一步

- 攻击判定（第 2 步）已经能挡住：**不看攻击者**，生物/别的玩家/脚本用 toaru 都一样。
- 第 5 步是**字段写**，不是方法调用：`GungnirHealthCellMixin.toaru$writeHealthValue`
  里就是 `putfield f_135391_`（DataItem 的 value 字段）。Mixin 不能注入字段写，
  所以任何“在方法边界上把关”的白名单都看不见它；`DataItem#setValue` 也拦不到。
- 因此对 toaru 这类 health cell，只有三种白名单式做法：
  1. **账本即白名单**：每 tick 以 ledger 为准，raw 值不是“被批准的写”产生的就回滚 ——
     不列任何 mod/武器，只承认“经过准入的值”；
  2. 在它拿到实体之前拦（物品/施法入口）——这就是“盯着武器”，不推荐；
  3. 只检测不回写：`HealthLedger` 已经会报 `UNEXPLAINED_HEALTH`，只是不动手。
- 即使拦下第 4/5 步，第 3 步已经把它自己的 ledger 标成“终止”，它的
  `getHealth()/isAlive()` 注入会跟着变。**toaru 的终止状态是它自己的**，我们无法通用地清掉。

## 7.4 2026-11-06 22:5x：ENFORCE 实测后的四处修正

这一轮终于让配置真正生效（启动行 `[Sanctuary] mode=ENFORCE ... removal=ENFORCE`），日志显示：

- `SynchedEntityData.set` 路径的 `0.0 / 0.1 / -Infinity` 写全部 `rejected`；
- 无来源 `magic 1.0` 变成 `rejected kind=UNATTRIBUTED`；
- `host_api_foreign` 0 次（说明这次没走宿主 API）。

但仍有三处问题，已修：

1. **移除守卫把 `raw<=0` 当成“已死”** → 攻击方先把 raw 写成 0，再发 `KILLED` 就能过。
   现在只看 `死亡栈 / isDeadOrDying / guarded 血量`，不看 raw。
2. **`fantasy_true_health_sync=true` 是放大器**：raw 被写成 0.1 后，它 5 tick 把
   `guardedData` 从 666 同步到 0.1，等于把“篡改”变成“真的死”。已在实例配置里关掉。
3. **`DataItem.value` 直写**仍然无法在方法边界上拦（rejected/allowed 都没有记录，
   raw 却从 666 变 0.1）。按“账本即白名单”加了兜底回写 `sanctuary.repair_tamper`：
   - 只在 `raw < 账本` 且差距 > 20% 最大生命时触发；
   - 只向上写回 raw（不碰 guardedData），走 `internal` 作用域；
   - 不在 hurt / 死亡栈里修；账本自己漂到 max 以上也不修；
   - 日志：`[GuardAudit] HEALTH_TAMPER_REPAIRED raw=... -> ...`。

`/ga guard audit status` 现在会多一个 `tamperRepaired=N` 计数。

## 7.5 2026-11-06 23:5x：修"看着像死了"的两处

23:50 那轮日志：`repair_tamper` 已经读进去了，但一次 `HEALTH_TAMPER_REPAIRED` 都没有，
而 `UNEXPLAINED raw=0.0 actual=0.0`、`REMOVED_WITHOUT_DEATH reason=KILLED raw=0.0` 照旧。
原因不是兜底没开，是**兜底和移除守卫都在看血量**：

- 兜底里 `golem.isDeadOrDying()` 为真（血被写成 0）→ 直接跳过，不修；
- 移除守卫里 `isDeadOrDying()` 为真 → 当成真死放行。

两处都改成"只看死亡证据，不看血量"：

| 判定 | 现在只认 |
|---|---|
| 真死 | `SanctuaryScopes.inDeath()`（die mixin 开着）或 `HealthLedger.observedDeath()`（真的收到过 `LivingDeathEvent`） |
| 兜底修复 | 只要不是真死、且不在 hurt 栈里，就按账本把 raw 拉回去（哪怕血量读出来是 0） |

另外补了两条：

1. **被拒后立即对账**：`logRejected` 之后马上 `HealthLedger.auditNow(golem)`，
   不再等 20 tick 的常规审计——攻击方常常"写 0 → 同 tick 移除"，等不到下一次审计。
2. **DISCARDED 也看上下文**：正常回收放行；但"最近 100 tick 内发生过血量篡改、
   傀儡还没真死"的 DISCARDED 视为攻击方兜底（VanSh 会 kill/discard 轮着来），
   ENFORCE 下同样拒绝。

## 8. 已知空洞（这一层不承诺）

- `forceHurt` / 直写 `DataItem.value` / `forceRemove`（清实体索引）/ 换类 / 裸线程 / agent / 改 jar / NBT 编辑：**方法边界拦不住**。现在靠 `repair_tamper` 事后把 raw 拉回账本；`forceRemove` 仍然只能靠 `setRemoved` 拦截 + `EntityLeaveLevelEvent` 审计。
- **宿主 API 只放行“宿主自己驱动”的写**：`dev.xkmc.modulargolems.*` / `dev.xkmc.l2*` / 原版调用放行；其它模组调 `setGuardedDataImpl` 会被拒并记 `host_api_foreign caller=...`。
- `kill()`：普通傀儡走 `hurt(genericKill)` 被拦；但 `GuardedEntity#kill` 在 `dynamicReductionRate() > 0` 时直接 `setHealth(0)`，没有死亡作用域 → 被写入口拒，`guardedData` 归零、下一 tick 由 `validateGuardedData` 补回（不崩，但会有一帧“空血”）。
- 作用域是 ThreadLocal：`hurt`/`die`/`readAdditionalSaveData` 抛异常时不会执行退出分支，可能残留一个窗口。正常路径不会；真抛异常时服务器通常已经要崩了。
- `Sanctuary` 对**普通**敌人伤害仍然放行（`ENEMY`）。也就是说“生物”只在**操作级**（`bypasses_invulnerability`）和**直写/移除**这两条线上被防；一只普通僵尸把傀儡打死是允许的。要改成“所有生物伤害都拒”，把 `deny_operation` 旁边再加一个开关即可，但那等于傀儡无敌。

## 9. 构建与安装
```powershell
$env:JAVA_HOME='D:\Java\zulu17.58.21-ca-jdk17.0.15-win_x64'
.\gradlew.bat build -Ptestbench --console=plain
```

> 工作区的 `build\.gradle-home`（旧会话为了绕开只读沙箱建的）已经删掉：它被一次中断的 `clean`
> 弄坏过，现在统一用默认的 `~/.gradle`。`clean` 也因此恢复正常。

产物：`build\libs\golems_arsenal-0.3-testbench.jar` → 安装到
`D:\我自己的包\.minecraft\versions\1.20.1-Forge_47.4.20\mods\golems_arsenal-0.3-testbench.jar`。
