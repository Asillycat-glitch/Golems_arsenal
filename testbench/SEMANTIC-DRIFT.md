# 语义差异清单（Semantic Drift）

> 对照构建：**1,037,300 B / SHA `5FA3026B…E0A3D36` / 2026-11-05 13:02**
> 目的：列出“代码现在实际怎么做”和“文档/旧版本/当前设计以为它怎么做”之间的差异。
> 不是 bug 列表；是读代码时的地图。

## 0. 先看结论

当前最需要先读的不是 `CombatAdmission`，而是：

1. `compat/fantasy/FantasyTrueDamageAdapter.java`
2. `compat/fantasy/FantasyTrueHealthCompat.java`
3. `compat/artifact/DamageAdmission.java`（尤其 `normalSource` / `isHandled`）
4. `guard/combat/CombatAction.java` + `CombatProfiles.java` + `CombatAdmission.java`
5. `guard/HealthLedger.java`

前三个决定“true damage / split-brain”的语义；后两个决定“源级门”的语义。

## 1. P0：真实语义冲突

### 1.1 `FantasyTrueDamageAdapter`

**现在实际：**

```text
CatchActuallyHurt0Event.Pre（只注册 Pre，不注册 PrePre）
  -> cancel + amount=0
  -> handled=false: rejected true_damage，直接不造成伤害
  -> handled=true: min(amount, current) -> entity.hurt(admission_rehurt)
```

**和设计不一致：**

- 文档说“不是硬格挡”，但 `handled=false` 实际就是硬格挡（`anvil` 被免疫）。
- true damage 被转成 `admission_rehurt`（普通伤害语义），不再是 true damage。
- `cappedAmount` 只是 `min(amount, current)`，不是“抗性后的值”（旧名 `resistedAmount`）。
- `applied=false` 时日志仍写 `handled`，看起来像成功，实际没伤害。
- 友伤/无敌都会让 `entity.hurt` 返回 false，日志没有区分。

**2026-11-05 12:11 已修/已加：**

- 不再同时注册 `PrePre` + `Pre`，只处理 `Pre`，避免同一 `actuallyHurt` 双重 cancel/re-hurt；
- 新增 `fantasy_pre` 诊断：`invulnerable / invulnerableTime / deadOrDying / health / invulnerableTo / guardedExtra`；
- `handled` 日志新增 `postHealth / postInvulnerableTime`；
- **没有改** true damage 策略（handled/rejected 仍在），也**没有加任何 tag**。

**2026-11-05 13:02 根因定位：**

- `fantasy_pre` 全部 `invulnerable=false / invulnerableTo=false / guardedExtra=false / deadOrDying=false`；
- 但 `invulnerableTime=20`，说明 `admission_rehurt` 在 `LivingEntity.hurt` 的 i-frame 预检查就被吃掉了；
- 因此 `fantasy_attack` / `fantasy_hurt` 一条都没有（hurt 在 fire 事件之前就 return false）；
- 12:40 那条 `postInvulnerableTime=0` 是旧构建日志，不是 12:11 之后；
- 新构建在 re-hurt 前临时 `invulnerableTime=0`，失败时恢复旧值；日志多 `iframeReset=旧值->0`。**没有加 tag。**

**要读的相关代码：**

- `DamageAdmission.normalSource`：现在返回 `golems_arsenal:admission_rehurt`，不是 `generic`。
- `Config.DAMAGE_ADMISSION_HANDLED_MODS` 默认 `fantasy_ending`；
  `handled=false` 的 `anvil` 就是被这个分支挡的。

**语义选择：**

- 想保留 true damage：新增 `admission_true`（bypass armor/resistance，不 bypass invulnerability），
  所有 `CatchActuallyHurt0Event` 都转 `admission_true`，不再 `rejected`；
- 想保留当前“只放行 handled”：就是硬格挡，文档必须改。

### 1.2 `FantasyTrueHealthCompat`

**现在实际：**

```text
raw > 0
raw < guarded
max > raw
guarded > raw
持续 5 tick
  -> guardedEntity.setGuardedDataImpl(raw, true, false)
```

**和当前 split-brain 语义冲突：**

- `raw=0.1 / guarded=558` 是 VanSh 的 split-brain；如果这个 compat 运行，
  它会在 5 tick 后把 guarded **降到 0.1**，把“显示错误”变成“真的 0.1 血”。
- 当前配置 `fantasy_true_health_sync=true`，但 latest.log 没有 `[Compat]` 行；
  说明它这次没有生效（反射/注册/条件未满足），不是“安全”。
- 它只跳过 `raw <= 0`，不跳过 `0 < raw << guarded` 的可疑状态。

**要读的相关代码：**

- `Config.FANTASY_TRUE_HEALTH_SYNC` 默认 true；
- `HealthLedger` 的 `raw/actual` 对账；
- `COMBAT-ADMISSION.md` 的 display split-brain 段。

**语义选择：**

- 如果 true damage 改成 `admission_true` 走 hurt，guarded 本来就该同步，compat 可以收窄/关闭；
- 如果保留 sync，应只处理“raw 是合理真实血量”的情况，不处理 `raw=0.1, guarded=558`。

### 1.3 `DamageAdmission.normalSource`

**现在实际：**

```java
new DamageSource(holder)  // admission_rehurt, 无 attacker
```

**旧注释/文档仍写：**

```text
用无持有者的 generic
normalSource=generic
```

**语义差异：**

- 不是 `generic`，是自定义 `admission_rehurt`；
- 自定义类型没有 `bypasses_*` tag，所以护甲/抗性会生效；
- 它没有 attacker，避免 `PlayerImmuneModifier` 友伤，但也丢失归因；
- 如果目标 `isInvulnerable`，`GuardedEntity.isInvulnerableToExtra` 会拒绝，
  所以 `applied=false` 不一定是 bug。

### 1.4 `CombatAction` / adapter 语义

**现在实际：**

```text
CombatAction.forItem(stack, direct, causing)
  contributors = [ItemProfile]          // 只有物品
  capabilities = union(contributors)
  handled = ALLOWED + CombatAdapters.handledBy(class)
  supported = handled.containsAll(capabilities)
```

**和文档/设计不一致：**

- `CapabilityAdapter.handle(CombatAction)` 定义了，但**没有任何地方调用**；
- `ProjectileProfile` / `SpellProfile` / `HandlerProfile` 还没有；
- `GLOBAL_EVENT_HOOK` / `PROJECTILE_OR_SPELL` 只是枚举，没有 contributor；
- `CombatAdmission.onLivingAttack` 只从攻击者的主手取物品；
  投射物/法术可能被错误归因到“射手手上的物品”，而不是投射物/法术本身。

### 1.5 `ItemProfile.supported` vs `CombatAction.supported`

**现在实际有两套 supported：**

```text
ItemProfile.supported  = ALLOWED.containsAll(caps)     // 测试/单贡献者
CombatAction.supported = handled.containsAll(caps)    // 实际 gate，含 adapter
```

两者在没有 adapter 时一致；有 adapter 时会分歧。  
`CombatProfilesTest` 测的是 `ItemProfile`，不是实际 `CombatAction`。读代码时不要把它们当成同一个语义。

## 2. P1：规则语义

### 2.1 `CombatProfiles`

**现在实际：**

- `SwordItem/AxeItem/PickaxeItem/ShovelItem/HoeItem/TieredItem/TridentItem` → `NORMAL_MELEE`；
- `BowItem/CrossbowItem/TridentItem` → `NORMAL_RANGED`；
- `pureRanged`（ranged 且非 melee）**完全忽略**自定义 use/inventoryTick/攻击钩子；
- 近战自定义 use/inventoryTick → `CUSTOM_HOOK`；
- 覆写 `inventoryTick` 的近战 → `CONTINUOUS_HOOK`；
- `net.minecraft.*` 不查自定义 hook。

**语义代价：**

- 纯远程自定义 bow 现在被完全信任，L2 archery `void_bow` 不再误报；
- 但一个恶意纯远程 bow 如果直接写血，源级门不会拦，只能靠 backstop；
- `TieredItem` 包含镐/锹/锄，所有工具都算 melee；
- `CUSTOM_HOOK` 只说明“有自定义行为”，不说明它写血。

### 2.2 `CombatAdmission`

**现在实际：**

- `OFF / SHADOW / ENFORCE`；
- SHADOW：`would_reject`，不取消；
- ENFORCE：`rejected`，取消；
- 覆盖 `AttackEntityEvent`、`LivingAttackEvent`、`RightClickItem/Block/EntityInteract`、
  `UseItem.Start`、`ItemStack.inventoryTick` mixin、`LivingEntity.swing` mixin；
- `allowInventoryTick` 不再看 `selected`，所有背包位置都检查；
- `onLivingAttack` 的 `player` 参数是 null，SHADOW 日志不按玩家限流；
- `combat_radius` 只对“附近有傀儡”的 use 生效；攻击目标傀儡时不查半径。

**没覆盖：**

- `DispenserItemBehavior`；
- 全局 `LivingHurtEvent` / `TickEvent` 注册者；
- 投射物/法术自己的 onHit/tick；
- curio/armor tick；
- `DataItem.value` 直写。

### 2.3 `HealthLedger`

**现在实际：**

- `UNEXPLAINED_HEALTH` 是 expected vs raw/actual 的差；
- 正向 delta（raw > expected）也会报，常见于 heal/split-brain；
- `raw=0.1 / actual=558` 会持续 `delta=+0.1`；
- 只审计，不 reconcile；
- `removedWithoutDeath` 只记录 discard/removal，不拦。

**语义差异：**

- 它抓的是“账本不一致”，不是“攻击者写血”；
- 正向 `UNEXPLAINED` 不代表敌人加血，也不代表 VanSh 又打了一次；
- 当前 split-brain 的重复日志是账本噪声，不是新攻击。

## 3. 文档/注释漂移

| 文件 | 旧语义 | 现在 |
|---|---|---|
| `testbench/ARTIFACT-GUARD.md` | “无持有者的 generic” / “handled 才重结算” | `admission_rehurt`；`anvil` 被 rejected |
| `FantasyTrueDamageAdapter` javadoc | “不是针对 fantasy 的硬格挡” | unhandled 实际硬格挡 |
| `DamageAdmission.normalSource` javadoc | “generic” | `admission_rehurt` |
| `testbench/COMBAT-ADMISSION.md` | 已较新 | 但 true damage 段还没写 `admission_true` |
| `P2.1c-FANTASY-TRUE-HEALTH.md` | 旧 build / 旧 fantasy 语义 | 需补 `applied=false` / `admission_rehurt` |
| `WEEK4.md` | 9 样本 | 仍准确；但无 combat/true damage 样本 |

## 4. 配置语义

```toml
[damage_admission]
default_deny = true
strict_writes = true
window_enforce = true
combat_mode = "SHADOW"

handled_mods = ["fantasy_ending"]
handled_damage_types = ["fantasy_ending.fe_power", "fantasy_ending.ds_power"]
handled_items = ["fantasy_ending:fantasy_ending_sword"]

[compat]
fantasy_true_health_sync = true
```

注意：

- `combat_mode` 是新 key；旧 `combat_admission` 已废弃；
- `handled_*` 只影响 `isHandled`，不影响“普通 `hurt` 放行”；
- `fantasy_true_health_sync=true` 与“保留 true damage 走 hurt”是潜在冲突；
- `anvil` 不在 `handled_damage_types`，所以被 rejected。

## 5. 测试语义

- `unitTest`：23 个纯函数测试；`CombatProfilesTest` 只测 `ItemProfile`，不测 MC 物品分类；
- `Selftest`：9 个 health/removal/NBT/thread 样本；没有 true damage、没有 combat、没有 projectile；
- `applied=false` 只在真机日志里，不在单测覆盖范围；
- `void_bow` 误报是 SHADOW 实测发现，不是测试框架发现。

## 6. 建议阅读顺序

1. `FantasyTrueDamageAdapter` → 看 true damage 怎么被 cancel/重结算；
2. `FantasyTrueHealthCompat` → 看 raw/guarded 怎么被 sync；
3. `DamageAdmission` 的 `normalSource` / `isHandled` / `allowHealthChange`；
4. `CombatAction` → `CombatProfiles` → `CombatAdmission`（从聚合到入口）；
5. `HealthLedger` → 看 expected/raw/actual 的账本语义；
6. 最后再看 `ARTIFACT-GUARD.md` / `COMBAT-ADMISSION.md`，把旧注释当历史，不要当现状。

## 7. 当前没有改的东西

- 没有新增 `admission_true`；
- 没有改 `FantasyTrueDamageAdapter` 的 cancel/rejected；
- 没有关 `fantasy_true_health_sync`；
- 没有给 `CombatAdapters` 注册具体 adapter；
- 没有自动 reconcile raw/guarded。
