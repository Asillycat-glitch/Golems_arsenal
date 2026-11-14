# WeaponEventHandler 拆分：第一批（2026-11-13）

> 目标：把 `WeaponEventHandler` 里混在一起的 listener / 效果 / 属性同步 / 特效调度拆开，
> 同时**不改防御层**。这一批只做两件低风险、但必须先做的事。

## 0. 对评审说法的核对

- `AbstractGolemEntity#updateAttributes` 只在
  `onCreate` / `GolemHolder` 召唤 / `readAdditionalSaveData` / `readSpawnData` 调用；
  活体傀儡的 modifiers 在生命周期内不变 → 不需要新造“升级变更事件”。
- 上游 `updateAttributes` 会把型号所有属性 base 重置回默认，“升级失效”已由上游处理。
- 真正缺的刷新是：**装备槽没变、ItemStack 的 NBT 变了**
  （`GolemEnergyKatanaItem.setUnitLevel/addUnitLevels` 这类原地改 NBT）。
- 1.20.1 `LivingEntity#detectEquipmentUpdates()` SRG `m_21315_` 存在，作为落点。

## 1. 本批已改

### 1.1 LivingHurtEvent 收敛成显式 dispatcher

`WeaponEventHandler#onLivingHurt` 现在是唯一 `LivingHurtEvent` 入口，内部按固定顺序调用：

```
applyGolemAttackHurt      // 攻击方
applyPlayerOnslaughtAttack
applyGildedDamageTaken    // 读当前 amount
applyGolemHurt            // 锤子减伤，最后改 amount
```

四个原 `@SubscribeEvent` 已摘掉，避免拆类后靠方法扫描顺序决定数值。
这个顺序与旧声明顺序一致；以后新增效果加到同一条序列里，不要另开 NORMAL handler。

### 1.2 装备 NBT 变化的补刷

新增 `LivingEntityEquipmentNbtRefreshMixin`：

```java
@Inject(method = "detectEquipmentUpdates", at = @At("TAIL"))
```

调用 `WeaponEventHandler.onEquipmentNbtChanged`：

- 对主手/副手/护甲所有槽位做 ItemStack + NBT 指纹；
- 指纹变化才调用 `refreshEquipmentAttributes`，不是每 tick 轮询；
- 第一次只建基线，之后才补刷；
- `onGolemEquipmentChange` 清指纹，避免换装后重复补刷。

## 2. 第二批（同一轮）：静态科技属性搬进 `getGolemModifiers`

| 武器 | 原来 tick 里做的 | 现在 |
|---|---|---|
| 能量武士刀 | `KATANA_PERCENT_UUID` + tech% 每 tick set/remove | `GolemEnergyKatanaItem#getGolemModifiers` 一次性算 |
| Z 光剑 | `TECH_PERCENT_UUID` + tech% 每 tick | `GolemZeroSwordItem#getGolemModifiers` |
| 追踪机械弓 | `BOW_EXPLOSION_UUID` + explosion factor 每 tick | `GolemTrackingMechanicalBowItem#getGolemModifiers` |

`updateWeaponAttributes` 已从 tick / 换装 / 刷新调用中移除。
NBT 原地变化由 `detectEquipmentUpdates` 的指纹刷新调用
`refreshStaticWeaponAttributes`，只补这三个 UUID，不做六趟全量。

**仍保留 tick 的**：剑触及/扫掠（`updateSwordAttributes`）、箭速、炮魔法、
列车、法杖——这些依赖运行时状态，不能一次性算。

## 3. 本批**没有**做

- 没有把静态属性搬到 `getGolemModifiers`（下一批）；
- 没有拆出 per-weapon effect 类；
- 没有改能量/列车/骑乘/连击的刷新时机；
- 没有改任何 `Sanctuary` / `DamageAdmission` / `AttributeGuard` 逻辑。

## 4. 下一批的边界

| 类型 | 处理 |
|---|---|
| 科技等级、黑猴数量、列车数量（如果确实只随 modifiers 变） | `getGolemModifiers` 一次性算 |
| 骑乘状态 | 上下骑乘事件驱动 |
| 能量、充能窗口、连击段 | `GolemModifier#onAiStep` 或窄口径刷新器 |
| 火焰云/闪电链/长枪 AOE | 每武器 effect 类 + 薄 dispatcher |

## 5. 测试注意

1. **锤子 + 镀金 + 猛攻**同时成立：确认 `onGildedDamageTaken` 读到的仍是减伤前数值，
   `applyGolemHurt` 最后改 amount。
2. 武士刀/光剑原地改 NBT 后，攻击属性是否立即刷新。
3. 换装/卸装/升级变更后，旧 UUID modifier 不残留。
4. `Sanctuary` / `DamageAdmission` / `AttributeGuard` 回归：本批不碰它们，但事件顺序
   变化会影响数值，测试时看 `GuardAudit` 和 `HANDLED` 日志。
