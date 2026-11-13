# 战斗能力准入（Combat Admission）

> **已收窄（2026-11-06）**：本文描述的是“源级门”的完整版本（含 `RightClickItem` / `UseItem.Start` /
> `inventoryTick` / `swing` 取消）。那一版已经被 **attack-only** 取代——现在只剩
> `AttackEntityEvent` + `LivingAttackEvent`，两个 mixin 已删除，判定交给目标侧的
> [SANCTUARY-2026-11-06.md](SANCTUARY-2026-11-06.md)。本文留作历史/设计参考。

> 构建：**1,036,114 B / SHA `EA0BA7DF…7488855F` / 2026-11-04 23:40**，已安装。
> 单测：**23/23 通过**（`.\gradlew unitTest`）。

## 0. 先纠正一点

自动分类器**只能**从类/覆写推断出：

```text
NORMAL_MELEE / NORMAL_RANGED / CUSTOM_HOOK
```

它**不能**从 `getMethod("onLeftClickEntity")` 推断 `DIRECT_HEALTH_WRITE` / `FORCED_REMOVAL` / `HEALTH_LOCK`。
那些需要 ASM/字节码扫描或显式适配器。VanSh 这轮的实际 profile 是：

```text
{NORMAL_MELEE, CUSTOM_HOOK} → supported=false
```

## 0.1 单位已提升到 CombatAction

`ItemProfile` 只是 contributor。当前聚合：

```java
CombatAction.forItem(stack, directEntity, causingEntity)
  contributors = [ItemProfile]
  capabilities = union(contributors)
  handled      = ALLOWED + CombatAdapters.handledBy(item.getClass())
  supported    = handled.containsAll(capabilities)
```

未来 `ProjectileProfile` / `SpellProfile` / `HandlerProfile` 也可以进 contributors；
能力并集里只要有一个没被 handled，整个 action 不支持。

新增能力枚举：

```text
CONTINUOUS_HOOK      // inventoryTick / curioTick / equippedTick
GLOBAL_EVENT_HOOK    // LivingHurtEvent / TickEvent 注册者
PROJECTILE_OR_SPELL  // 攻击实际来源不是手持物品
```

自动分类目前只会给 `CONTINUOUS_HOOK`（类覆写了 `inventoryTick`）；后两个需要专门的 contributor，
当前没有，所以不要假装覆盖。

## 0.2 Adapter 是正向声明

```java
interface CapabilityAdapter {
    boolean supports(Class<?> clazz);
    Set<CombatCapability> declaredCapabilities(Class<?> clazz);
    void handle(CombatAction action);
}
```

`CombatAdapters.register(...)` 是白名单；适配器必须一次性声明它能处理的所有能力。
没有适配器的 `CUSTOM_HOOK` / `CONTINUOUS_HOOK` 默认不支持。

拒绝它不需要知道具体写血 API；但“它具体调了 setHealth/forceModify”这个事实不是分类器给的。

## 1. 默认 SHADOW，不 ENFORCE

```toml
[damage_admission]
combat_mode = "SHADOW"   # OFF / SHADOW / ENFORCE
combat_radius = 16
```

```text
/ga guard combat
/ga guard combat off
/ga guard combat shadow
/ga guard combat enforce
/ga guard combat config
```

- SHADOW：只打 `[CombatAdmission] would_reject ...`，不取消；
- ENFORCE：`rejected ...` 并取消；
- 先跑一局收集 false-positive 名单，再决定哪些类进 AdapterRegistry。

## 2. 能力模型

| 能力 | 来源 |
|---|---|
| `NORMAL_MELEE` | 剑/斧/镐/锹/锄/TieredItem/三叉戟 |
| `NORMAL_RANGED` | 弓/弩/三叉戟 |
| `CUSTOM_HOOK` | 模组类自己声明了攻击/使用钩子；纯远程的 use/inventoryTick 不算（避免弓/弩误报） |

复合物品是并集，all-or-nothing：

```text
{NORMAL_MELEE} → supported
{NORMAL_MELEE, CUSTOM_HOOK} → unsupported
```

继承自原版基类、自己不覆写的模组子类不会误报；原版类不检查自定义钩子。

**实测误报（2026-11-04 22:16）**：

```text
[CombatAdmission] would_reject inventory_tick
  item=item.l2archery.void_bow
  contributors=[dev.xkmc.l2archery.content.item.GenericBowItem]
  caps=[CUSTOM_HOOK, NORMAL_RANGED]
```

`GenericBowItem` 覆写了弓的 `use`/`inventoryTick`，被旧规则误判。新规则把**纯远程**的 use/inventoryTick/攻击钩子
都视为正常远程行为，因此该 bow 现在应为 `{NORMAL_RANGED}` supported；近战 `VanSh` 仍是
`{NORMAL_MELEE, CUSTOM_HOOK}` unsupported。代价是纯远程的自定义 hook 交给 backstop，不靠源级门。

## 3. 源级入口（含上一轮漏掉的）

| 入口 | 覆盖 |
|---|---|
| `AttackEntityEvent` | 玩家左键攻击 |
| `PlayerInteractEvent.RightClickItem/Block/EntityInteract` | 右键使用 |
| `LivingEntityUseItemEvent.Start` | 开始使用 |
| `LivingEntityUseItemEvent` / `LivingEntityUseItemEvent.Stop`? | 未单独覆盖连续 use tick；按 Start 源级拒绝 |
| `ItemStack.inventoryTick` mixin | **连续 inventoryTick**（VanSh 真正的 remove 在这里） |
| `LivingEntity.swing` mixin | 独立 swing 包，不经过 AttackEntityEvent |
| `LivingAttackEvent` | 普通 hurt 路径的 backstop |

VanSh 的 `remove` 实际调用点在 `inventoryTick`（bytecode 偏移 438/491），所以只取消右键是不够的；
本轮加了 `ItemStackCombatAdmissionMixin` 和 `LivingEntitySwingCombatAdmissionMixin`。

## 4. VanSh 预测

`VanSh` 自己声明 `onLeftClickEntity` / `use` / `inventoryTick`，profile：

```text
{NORMAL_MELEE, CUSTOM_HOOK} → unsupported
```

SHADOW 下每次交互/每 20 tick 最多一条：

```text
[CombatAdmission] would_reject inventory_tick item=... class=... caps=[NORMAL_MELEE, CUSTOM_HOOK] combat=true supported=false
```

ENFORCE 下取消整个 `inventoryTick`，所以不会进入：

```text
setHealth(0.1) → hurt(MAX) → entityDataModify(0) → forceModify → setRemoved
```

但这是**预测**；未进游戏实打 VanSh。先 SHADOW 看日志，确认 profile 与误报，再切 enforce。

### VanSh 的实际能力（游戏 tooltip / 字节码）

tooltip 原文：

```text
[左键] 强力的改血（仅改血）
[左键]+[Shift] 更强的左键击杀目标
[右键] 击杀以你为中心半径 [攻击范围] 格内的实体
[右键] 蓄力 1s 击杀半径内实体
[右键]+[Shift] 击杀 [攻击范围] 内实体
[右键]+[Shift] 蓄力 1s 击杀
[ALT]+[左键] 发射魔法弹幕
[ALT]+[Q] 丢弃该物品
```

覆盖情况：

| 路径 | 源级门 |
|---|---|
| 左键改血/击杀 | `AttackEntityEvent` + `LivingAttackEvent` |
| 右键击杀/蓄力 | `RightClickItem` / `RightClickBlock` / `UseItem.Start` |
| 持续 inventoryTick remove | `ItemStack.inventoryTick` mixin |
| ALT+左键 魔法弹幕 | **不完整**：投射物 contributor 还没有；只有 `LivingAttackEvent` 的 backstop |
| ALT+Q 丢弃 | 非战斗，不处理 |

### 显示 split-brain

VanSh 打到一半时会出现：

```text
raw=0.1
guarded=558.0
actual=getHealth()=558.0
```

HUD 用 guarded，所以显示 558/558；`raw` 0.1 只影响内部/某些血条读取。收回/放出傀儡后
`guardedData` 重算，raw/guarded 恢复一致。当前没有自动 reconcile；这是**显示/内部状态分裂**，
不是实际死亡。


## 5. 这层不能替代 backstop

- `NORMAL_MELEE` 不代表数值安全：只改 `getAttributeModifiers` 的剑仍是 `{NORMAL_MELEE}`，可以一击 1e9；数值/减伤由 BYPASS/DEFENSE 负责；
- `CUSTOM_HOOK` 只说明“有自定义行为”，不说明它一定写血；反之，**没有**自定义 hook 的物品也可能通过全局事件/投射物/法术写血；
- 投射物、法术、curio/armor tick、Dispenser 的 `DispenseItemBehavior` 不经过这些物品 hook；
- `DataItem.setValue` 直写：目前没有 mixin 覆盖，只能靠 `HealthLedger` 的 tick 对账；明确为 **audit-only**，不承诺拦截；
- `normalSource` 已从 `generic` 改为自定义 `golems_arsenal:admission_rehurt`（无 `bypasses_*` tag、无 attacker，避免友伤），护甲/抗性/L2 重新生效；
- `allowHealthChange` 在 `default_deny=false` 时仍然是 fail-open；这是配置语义，不是源级门能替代的；
- `absorption` 的重复扣减由 `HealthLedger` 的 expected 估算处理，仍可能误报，不能当成安全边界。

## 6. 误报成本

默认拒绝所有 `CUSTOM_HOOK` 会误伤：

- SlashBlade / Tinkers / Cataclysm / jzyy / fantasy_ending 的武器；
- 只覆写 `use`/`onLeftClickEntity` 做粒子/音效/动画的物品。

所以：

1. 先用 `combat_mode=SHADOW` 跑一局；
2. 收集 `would_reject` 的 class/能力列表；
3. 为确认安全的类/接口写 Adapter；
4. 只对未确认类 `ENFORCE`，不要全局一刀切。

## 7. 当前边界

- 类/覆写检查是启发式，不是安全边界；反射/混淆/运行期生成类可绕过；
- `combat_radius` 只保护傀儡附近/作为目标的交互；远处使用仍可能影响别的实体；
- 移除/血量写 backstop 仍然只做现有策略；
- 默认 SHADOW，不会因为这次改动直接禁掉你的武器。
