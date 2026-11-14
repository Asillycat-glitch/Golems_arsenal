# 伤害准入与伤害 tag（2026-11-13）

> 结论先说：**没有一个单独的“伤害准入 tag”**。准入是 `Sanctuary` 的
> `SourceKind` 分类 + `DamageAdmission` 的写入口判定；tag 只参与分类。
> `bypasses_armor` 和“增伤/减伤事件”本身不会自动被拦。

## 1. 分层

```
LivingEntity#hurt
  → LivingAttackEvent
      → Sanctuary(HIGHEST):
          1. INTERNAL（本模组无攻击者的伤害）
          2. outOfWorld / outsideBorder / badRespawnPoint
          3. genericKill（/kill，可配置）
          4. isOperation = minecraft:bypasses_invulnerability
          5. OWNER / OWNER_PROJECTILE / ALLY / ENEMY / ENVIRONMENT / UNATTRIBUTED
      → 允许时 SanctuaryScopes.admit(golem)
  → LivingHurtEvent / LivingDamageEvent
      → 其他模组的增伤/减伤照常计算
      → HealthLedger(LOWEST) 记录最终 amount 为 expected
  → actuallyHurt
  → setHealth / SynchedEntityData.set
      → DamageAdmission：只放行 admitted/死亡/读档/内部/host 写入
```

`SanctuaryRules.DEFAULT`（战斗态测试期间）：

```
INTERNAL        allow
ENEMY           allow
ENVIRONMENT     allow
OPERATION       allow（deny_operation=false）
OWNER           deny
OWNER_PROJECTILE deny
ALLY            deny
UNATTRIBUTED    deny
```

> 2026-11-13 调整：`bypasses_invulnerability` 现在**默认放行**，因为大多数后门环境都把它当普通伤害 tag 用。
> 这会重新打开“操作级伤害能打伤傀儡”的口子；`DamageAdmission` / `RemovalGuard` /
> `HealthLedger` 仍在，但不再是“操作级一律拒绝”。需要恢复旧行为时把
> `deny_operation` 设回 `true`。

## 2. 我们自己的伤害类型 tag

数据文件在 `src/main/resources/data/.../tags/damage_type/`：

| tag | 我们的条目 | 准入含义 |
|---|---|---|
| `minecraft:bypasses_armor` | `stance_magic`, `orbital_strike` | **只影响护甲计算**，不参与 `SourceKind` |
| `minecraft:bypasses_effects` | `orbital_strike` | 只影响效果，不参与准入 |
| `minecraft:bypasses_enchantments` | `orbital_strike` | 只影响附魔，不参与准入 |
| `minecraft:is_magic` | `flame_magic`, `stance_magic`, `key_sword_magic` | 不参与 `isEnvironment`；仍按攻击者身份分类 |
| `forge:is_magic` | 同上 | 给 L2/其他模组看的，不参与 `SourceKind` |
| `minecraft:bypasses_invulnerability` | 空 | **这条才是 OPERATION** |
| `minecraft:bypasses_resistance` | 空 | 只影响抗性计算 |
| `minecraft:is_fire` | 空 | `isEnvironment` 才看 fire/explosion/fall/... |

`isEnvironment` 认的是：

```
minecraft:is_fire
minecraft:is_explosion
minecraft:is_fall
minecraft:is_drowning
minecraft:is_freezing
minecraft:is_lightning
+ msgId: outsideBorder / badRespawnPoint
```

`outOfWorld` 不再单列：虚空按普通 damage type 的 tag 分类（原版它带
`bypasses_invulnerability`，所以是 `OPERATION`）。

## 3. 自定义伤害类型写到“虚空/破甲”里会怎样

### 写进 `bypasses_invulnerability`

`Sanctuary.isOperation` 只看这条 tag，**不看命名空间**：

- 有攻击者 → `OPERATION`；
- 无攻击者、且类型命名空间不是 `golems_arsenal` → `OPERATION`；
- 无攻击者、且命名空间是 `golems_arsenal` → 会先命中 `INTERNAL`。

`OPERATION` 现在默认放行（`deny_operation=false`），所以**不会仅因为这条 tag 被拦**。
要恢复“操作级一律拒绝”，把 `deny_operation` 设回 `true`。

`generic_kill`（`/kill`、`Entity#kill`）单独走 `UNATTRIBUTED`，不跟随 operation 放行；
只有 `allow_generic_kill=true` 才回到环境。

最后一条命名空间规则仍是当前实现的一个口子：`isInternalDamage` 只检查“无攻击者 +
`golems_arsenal` 命名空间”，没有检查具体 damage type id。以后如果要收紧，应该改成内部类型白名单，
而不是整个命名空间。

### 写进 `bypasses_armor`

`bypasses_armor` **不是准入 tag**。它只让这次伤害跳过护甲计算，`SourceKind` 仍然按：

- 有攻击者：ENEMY / OWNER / ALLY；
- 无攻击者：本模组命名空间 → INTERNAL；否则看环境 tag；都不是 → UNATTRIBUTED。

所以“破甲 + 自己的名字”不会自动被拦，也不会自动被放行；它只是伤害计算不同。

### 写进“虚空”

原版没有 `is_void` tag；虚空是 `DamageTypes.OUT_OF_WORLD`，它本身带
`bypasses_invulnerability`。

**2026-11-13 已去掉 `outOfWorld` 的环境白名单**：

- 不再因为 msgId 是 `outOfWorld` 就直接按 ENVIRONMENT 放行；
- 它现在按普通 tag 分类，即 `OPERATION`；
- `deny_operation=false` 时仍然放行，`deny_operation=true` 时会被拒。

这样“虚空”不再是一条比其它 operation 更宽的特殊通道。

## 4. 增伤/减伤事件

**允许。** 只要 `Sanctuary` 先放行了这次伤害：

- `LivingHurtEvent` / `LivingDamageEvent` 上其他模组的增伤、减伤、倍率、护甲穿透都正常计算；
- `HealthLedger` 在 `LivingDamageEvent` 的 `LOWEST` 记录最终 amount；
- `DamageAdmission` 不按“原始伤害”校验，它只检查最终 `setHealth` 写入是否在 admitted 窗口内；
- 所以增伤后的最终血量变化可以通过。

### 但不等于“无限放行”

- `Sanctuary` 在事件之前已经拒绝的来源（OWNER / ALLY / OPERATION / UNATTRIBUTED）不会进入增伤事件；
- `DamageAdmission` 会拦截**没有 hurt 窗口的直接写血**；
- `HealthLedger` / `DataGuard` 会把“没有 `LivingDamageEvent` 对账的 raw 下降”当成篡改修回去；
- 在 admitted hurt 窗口里，其他模组如果直接调用 `setHealth(0)`（不走事件），这次写会被 `DamageAdmission` 放行；
  `HealthLedger` 没有收到对应伤害，随后可能由 `DataGuard` 修回。这是“允许正常 hurt”本身的取舍，
  不是增伤事件的特殊通道。

### 事件优先级

- `Sanctuary`：`LivingAttackEvent` HIGHEST，负责准入；
- `WeaponEventHandler`：`LivingHurtEvent` NORMAL，只改数值；
- `HealthLedger`：`LivingDamageEvent` LOWEST，记录最终值；
- 其他模组在 NORMAL/HIGH/LOW 的增伤减伤都会在 `HealthLedger` 之前落定（除非它用更晚的优先级写血）。

## 5. 一百万伤害的剑

没有伤害上限，也不打算加：

- 这把剑如果是普通 `ENEMY` 来源，`Sanctuary` 放行；
- `DamageAdmission` 只检查“这次下降是否在 admitted 窗口里”，不检查数值大小；
- `HealthLedger` 记录最终 amount，expected 归零后按真死处理；
- `RemovalGuard` 只看“是否真死”，不会因为数字大就额外拦截。

所以是的：**一把 1,000,000 伤害的普通剑可以一击秒掉傀儡**，这是当前战斗态的预期行为，
不是漏掉的绕过。只有 `OPERATION` / tag 准入和 agent 的已知清除入口是另外两层。

## 6. L2 古遗物：哪些写法可能绕过普通攻击链（分析，未落地）

> 这一节只做路径分析，**没有**加代码白名单。真要放行，优先做数据包 allowlist，
> 而不是把类名硬编码进 `Sanctuary`。

L2 古遗物大部分效果只是：

- 修改 `AttackCache` / `LivingHurtEvent` / `LivingDamageEvent`（Wrath、PhysicalDamage、
  AttackStrike、AbyssAttack、FrozeBreak...）；
- 修改属性（AttributeSetEffect）；
- 减伤/取消/闪避/治疗（Perfection、Protection、Saint、SlimyBuffer、Thermal、DeadCellDodge...）。

这些走正常事件链，没有额外“绕过”。

真正会**直接调 `LivingEntity#hurt`** 的是少数：

| 效果 | 路径 | 现有准入会怎么判 |
|---|---|---|
| `FungusExplode`（孢子爆破） | `indirectMagic` 打附近感染目标 | source 带玩家身份 → OWNER/ALLY；默认拒绝 |
| `DeadCellParry`（死细胞格挡） | `thorns` 反伤攻击者 | source 带玩家身份 → OWNER/ALLY；默认拒绝 |
| `DamoclesSword` | 自伤 `hurt` | 目标是玩家自己，不经过傀儡准入 |
| `ExecutorSelfHurtEffect` | 击杀后自伤，带 `BYPASS_ARMOR/MAGIC` | 目标是玩家自己，不经过傀儡准入 |
| `ArtifactSynergyHandler`（我们自己的法师反伤） | `thorns` 反伤攻击者 | 同主傀儡目标 → ALLY，可能被拒 |

所以它们的问题不是“数值穿透护甲”，而是**来源身份被 `Sanctuary` 判成 OWNER/ALLY**。

如果以后要放行，建议数据包：

```json
{
  "allow": [
    {"msg_id": "indirectMagic", "stack": "dev.xkmc.l2artifacts.content.effects.v5.FungusExplode"},
    {"msg_id": "thorns", "stack": "dev.xkmc.l2artifacts.content.effects.v5.DeadCellParry"},
    {"msg_id": "thorns", "stack": "a_silly_cat.golems_arsenal.compat.l2artifacts.ArtifactSynergyHandler"}
  ],
  "deny": []
}
```

- 白名单命中 → 直接放行；
- 黑名单命中 → 直接拒绝；
- 两边都命中时，黑名单优先（和 toaru 的“被针对修复”路径分开管理）；
- 不再把具体类名写死在 `Sanctuary` 里。

## 7. 相关配置

```
damage_admission_enabled
damage_admission_owned_only
damage_admission_default_deny
damage_admission_strict_writes
damage_admission_window_enforce
damage_admission_combat_mode
damage_admission_handled_mods
damage_admission_handled_damage_types
damage_admission_handled_items
```

`handled_*` 只用于“绕过普通 hurt 的适配器来源”白名单，不参与普通 Forge 伤害的准入。
