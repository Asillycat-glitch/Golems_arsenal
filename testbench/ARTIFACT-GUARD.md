# 伤害准入（Damage Admission）

> 构建：**1,036,114 B / SHA `EA0BA7DF…7488855F` / 2026-11-04 23:40**，已安装。
> 回滚：`mods-backup\golems_arsenal-0.3-testbench-978700-20261103-1242.jar` 等。
> 这是“白名单 + 默认拒绝 + 已处理来源归一化”的防御层，不是按 mod 黑名单硬 cancel。

## 1. 设计

| 层 | 行为 |
|---|---|
| 普通 Forge 伤害 | `LivingAttackEvent → LivingHurt → LivingDamage → 写血` 一律放行，走现有护甲/减伤/L2 |
| 已处理（handled）的绕过伤害 | 取消 true damage，用**无持有者的 generic** 正常伤害源重结算，让抗性生效 |
| 未处理（unhandled）的绕过伤害 | 直接 `setHealth` / `DATA_HEALTH_ID` 写血 → 默认拒绝，记 `[DamageAdmission] rejected` |

白名单是“我们知道怎么处理”的兼容列表，不是敌人列表。默认只有 `fantasy_ending`；
以后给新来源写适配器后再加。

## 2. 配置

```toml
[damage_admission]
enabled = true
owned_only = true
default_deny = true
handled_mods = ["fantasy_ending"]
handled_damage_types = ["fantasy_ending.fe_power", "fantasy_ending.ds_power"]
handled_items = ["fantasy_ending:fantasy_ending_sword"]
```

- `enabled`：总开关；关掉后完全回到原版行为。
- `owned_only`：只保护有 owner UUID 的玩家傀儡。
- `default_deny`：对“受保护傀儡 + 生命值下降 + 非正常伤害链/内部写”的默认拒绝。
  **P2 测试台的 `poke` / `reflect` 需要设为 false**，否则会先被正式层拦掉。
- `handled_*`：已处理来源的分类；默认 fantasy。**加名字不会自动长出一个适配器**，
  新来源需要像 `FantasyTrueDamageAdapter` 一样写入口。

旧配置段 `[artifact_guard]` 已废弃，会被忽略。

## 3. 代码

| 类 | 作用 |
|---|---|
| `compat/artifact/DamageAdmission` | 白名单判定、正常伤害源构造、写入口判定、日志 |
| `mixins/LivingEntityHealthAdmissionMixin` | `LivingEntity#setHealth` HEAD；受保护傀儡的下降写默认拒绝 |
| `mixins/SynchedEntityDataHealthAdmissionMixin` | `SynchedEntityData#set(DATA_HEALTH_ID)` HEAD；覆盖直接 DataItem 写 |
| `compat/fantasy/FantasyTrueDamageAdapter` | 只在 fantasy 加载时注册；`CatchActuallyHurt0Event.Pre/PrePre` 归一化 |

### 正常伤害链放行

栈检查允许：

- `LivingEntity#hurt/m_6469_`、`actuallyHurt/m_6475_`、`heal/m_5634_`；
- `GuardedEntity#hurtFinal/hurtFinalImpl/m_6475_`；
- `ForgeHooks#onLivingHurt/onLivingDamage`；
- `l2damagetracker` 的 `AttackEventHandler`。

内部写允许：读档 `readAdditionalSaveData/m_7378_`、`validateGuardedData`、`die/baseTick`、
`GolemUpgradeMenu` / `GolemPart` / `GolemAssemble` / `GolemHolder` / `AbstractGolemEntity`、
本模组的 `GolemPhoenixHandler` / `FantasyTrueHealthCompat`。

### fantasy 归一化

`CatchActuallyHurt0Event.Pre/PrePre` 命中受保护傀儡时：

1. `cancel + amount=0`，原始 true damage 不落 `DATA_HEALTH_ID`；
2. `EntityASMUtil.setHealthDelta(entity, 0)` 清 FE delta；
3. 取 `min(amount, getHealth())`，用 **`generic`**（没有玩家/生物持有者）重新 `entity.hurt(...)`；
4. 正常管线里的护甲、`damage_reduction`、`damage_cap`、L2 全部生效。

> 之前用 `playerAttack` 会触发玩家傀儡的 `PlayerImmuneModifier` 友伤/玩家免疫，
> `entity.hurt` 返回 false，表现就是“梦终左键打不动”。改用 `generic` 后不再带玩家实体，
> 但仍然走正常抗性。

## 4. 覆盖边界

**覆盖：** 普通 Forge 伤害（放行）；已处理的 fantasy true damage（抗性归一化）；
受保护傀儡的 `setHealth` / `DATA_HEALTH_ID` 直接下降写（默认拒绝）。

**不覆盖：** `setRemoved`/`discard`/索引移除；反射/VarHandle 改 `float` 字段；
agent/native/改 jar；正常 Forge 事件内部的写（按 scope 放行）。

## 5. 测试

```text
/ga health guard test off
/give @s modulargolems:metal_golem 1
# 普通武器打：应正常掉血，无 [DamageAdmission] rejected

/ga health start p21-damage-admission
# 梦终剑左键：应出现
# [DamageAdmission] handled ... normalSource=generic applied=true
# 血量按抗性下降，不负血

# P2 直接写测试：
# [damage_admission] default_deny = false
```

如果梦终仍然 `applied=false`，先确认目标是否有 `PlayerImmuneModifier`/`IMMUNITY`；
`generic` 不带玩家实体，应该绕过玩家免疫，但仍可能被 `IMMUNITY` 拦。
