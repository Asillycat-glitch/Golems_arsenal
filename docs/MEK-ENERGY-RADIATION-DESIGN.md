# MEK 联动：电量链 / 合金能源 / 辐射发电 设计依据

> ⚠️ **变更记录：辐射屏蔽整条线已删除。**
> 原设计的"辐射屏蔽（3 级，占 1 槽，与发电共用 step 曲线互为代价）"已被移除。
> 理由是设计意图变了：**"净污发电"本身就替代了屏蔽**——傀儡不是挡住辐射，而是把周围那点污染
> 消耗掉换电。所以本文档里所有关于"屏蔽值 / shield_denominator / 屏蔽与发电互为代价"的内容
> **只作为历史推导保留**，实现中已不存在（清理清单见 §11）。
>
> 本文是**立项依据 + 数值定稿**，不是实现文档。所有数值都给出推导过程、源码出处与实测校准步骤，
> 供决定"正式版按什么步长发布"时对照。
>
> **状态：已实现并编译通过**（`gradlew build` 成功）。实现出来的文件与本文档的差异，
> 都已在 §4.2 的"实现期修正"里标注，改动过的数值（发电倍率、config 键名）也同步更新。
> 仍待实测的一项见 §4.5（用盖革计数器校准 `fe_per_severity`）。
>
> 结论先行：
> 1. **合金能源不占额外槽位**，也不需要互斥——四个升级喂的是**同一个** `golem_energy` 修饰符，
>    等级自动累加，乘数只是一个纯函数。装配侧**一行新代码都不用写**。
> 2. **承载力与带宽必须同源一起涨**。只涨容量会出现"8.7M 的电池、500 FE/t 的管子"这种荒唐状态。
> 3. **辐射不做源**（多人服不可逆污染），改做**辐射发电**：只在辐射区工作，干净环境严格 0。
> 4. 配置走**步长制**：只存一个 `step`，各档系数是 2 的幂次常量。改一个数整体缩放，
>    且"高档 > 低档"在任何步长下恒成立。

---

## 0. 已实现的文件清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 修饰符 | `base/upgrade/GolemUpgrades.java` | 新增 `radiation_generator` / `radiation_shielding` 两个注册；`golem_energy` 沿用 |
| | `tech/upgrade/GolemEnergyModifier.java` | `MAX_LEVEL` 5→15、`totalLevel` / `levelOfUpgrades` / `capacityForLevel`（转调 `GolemEnergyTiers`） |
| | `tech/energy/GolemEnergyTiers.java` | 等级 → `GolemEnergyTier` 的解析入口；无 MEK 时走 fallback |
| | `compat/mekanism/MekanismEnergyTiers.java` | 把 `EnergyCubeTier` 映射成傀儡 tier（容量 ÷2、输出全额） |
| | `tech/radiation/GolemRadiationGeneratorModifier.java` | 辐射发电（只读环境剂量） |
| | `tech/radiation/GolemRadiationShieldingModifier.java` | 辐射屏蔽（占 1 槽） |
| 物品 | `tech/upgrade/AlloyEnergyUpgradeItem.java` | 合金链物品：不占槽 + 前置校验 + 同档去重 |
| | `compat/mekanism/MekanismCompat.java` | 5 件物品的条件注册（3 合金 + 3 发电 + 3 屏蔽，共 9 件） |
| 能力 | `tech/radiation/GolemRadiationAttachHandler.java` | 用 `AttachCapabilitiesEvent` 给傀儡挂 `IRadiationEntity` |
| | `tech/radiation/RadiationEntityProvider.java` | 该能力的 Provider（复用 Mekanism 的 `DefaultRadiationEntity`） |
| 改造 | `base/event/WeaponEventHandler.java` | 带宽取 tier 的总 FE/t，主手 + 护甲共享预算 |
| | `tech/energy/GolemEnergyItemProvider.java` | 收纳态容量走同一个 `capacityForLevel` |
| | `base/recipe/GolemRepeatableSmithAddSlotRecipe.java` | 锻造台路径的前置校验 |
| | `mixin/GolemUpgradeHandlerMixin.java` | 升级界面路径的前置校验 + 同档去重 |
| 数据 | 2 个标签、9 个配方、9 个模型、2 个语言文件 | 见 §7 |


---

## 1. 结论摘要（一屏）

| 升级 | 槽位 | 等级 | 容量 / 带宽 | 材料 | 作用域 |
|---|---|---|---|---|---|
| 基础电量 | **占 1**（链的根） | max 1 | `EnergyCubeTier.BASIC`（容量 ÷2，输出全额） | 基础件 | 全局 |
| 灌注合金能源 | 不占（附属） | +2 级 | `EnergyCubeTier.ADVANCED`（容量 ÷2，输出全额） | `mekanism:alloy_infused` | 全局 |
| 精英合金能源 | 不占（附属） | +4 级 | `EnergyCubeTier.ELITE`（容量 ÷2，输出全额） | `mekanism:alloy_reinforced` | 全局 |
| 终极合金能源 | 不占（附属） | +8 级 | `EnergyCubeTier.ULTIMATE`（容量 ÷2，输出全额） | `mekanism:alloy_atomic` | 全局 |
| 辐射发电 | **占 1** | +1/+2/+4 级 | `1 + n×step_rad` | 钋球 | 只在辐射区 |
| 辐射屏蔽 | 待定 | +1/+2/+4 级 | `1 + n×step_rad` | 钋球 + 铅 | 只在辐射区 |

满配（基础 + 三合金 + 辐射发电）：**3 槽**，金属傀儡默认 4 槽，剩 1 槽给武器/特技。

---

## 2. 数值来源：能量立方映射 + 辐射步长

**电量链不再有 step。** 基础档与三件合金直接映射到 Mekanism 的四个能量立方档位；
容量取立方存储的**一半**，带宽取立方的**完整输出**（解释为“全部装备合计的 FE/t”）。
这样 MEK 那边的 `EnergyCubeTier` 配置一改，傀儡这边自动跟随；我们只保留无 MEK 时的 fallback。

```
基础       → BASIC       4,000,000 J → 容量 800,000 FE，带宽 1,600 FE/t
+灌注      → ADVANCED   16,000,000 J → 容量 3,200,000 FE，带宽 6,400 FE/t
+精英      → ELITE      64,000,000 J → 容量 12,800,000 FE，带宽 25,600 FE/t
+终极      → ULTIMATE  256,000,000 J → 容量 51,200,000 FE，带宽 102,400 FE/t
```

J→FE 按 Mekanism 的默认 1 FE = 2.5 J 换算；`EnergyCubeTier.getMaxEnergy()` / `getOutput()`
本身就是 config-backed 的，所以不需要在傀儡的配置里重复写容量与带宽。

**辐射发电 / 屏蔽仍然是步长制**：档位系数是常量（发电 1/2/4，屏蔽 1/3/7），
`step_rad` 是唯一旋钮。两条系统互不依赖。

**配置键总数**：电量链只剩 2 个无 MEK fallback（`fallback_capacity`、`fallback_transfer`）；
辐射保留 `fe_per_severity`、`step`、`shield_denominator`、`min_dose`、`max_severity`。
原先的 `step`、`base_capacity`、`capacity_per_level`、`equipment_transfer_rate` 全部删除。

---

## 3. 电量链：等级即倍率

### 3.1 数据流（全部复用现有机制，无新逻辑）

```java
// 物品等级：GolemWeaponUpgradeItem 的构造签名本来就带等级参数
new GolemWeaponUpgradeItem(props, 1, GolemUpgrades::modifier)  // 基础电量   → +1
new GolemWeaponUpgradeItem(props, 2, GolemUpgrades::modifier)  // 灌注合金   → +2
new GolemWeaponUpgradeItem(props, 4, GolemUpgrades::modifier)  // 精英合金   → +4
new GolemWeaponUpgradeItem(props, 8, GolemUpgrades::modifier)  // 终极合金   → +8

// 等级累加：GolemMaterial.collectModifiers 已经做了求和 + 封顶
//   dev/mg-src/.../config/GolemMaterial.java:129
//   values.compute(e.mod(), (a, old) -> Math.min(a.maxLevel, (old==null?0:old) + e.level()));
// 所以 GolemEnergyModifier 只需把 MAX_LEVEL 从 5 改成 15（= 1+2+4+8）。
```

于是**同一个等级变量**同时承载"有没有基础电量"和"装了几个合金"：

```
总等级 L = 1（基础） + 2（灌注） + 4（精英） + 8（终极）      满配 L = 15
档位     = 1 → BASIC，3 → ADVANCED，7 → ELITE，15 → ULTIMATE
```

### 3.2 容量（`GolemEnergyTiers.forLevel`）

```java
public static int capacityForLevel(int totalLevel) {
    return GolemEnergyTiers.forLevel(totalLevel).capacityFe();
}
```

| 组合 | L | MEK tier | cube max（J） | 容量（÷2 → FE） |
|---|---|---|---|---|
| 只有基础电量 | 1 | `BASIC` | 4,000,000 | 800,000 |
| +灌注合金 | 3 | `ADVANCED` | 16,000,000 | 3,200,000 |
| +精英合金 | 7 | `ELITE` | 64,000,000 | 12,800,000 |
| +终极合金（满） | 15 | `ULTIMATE` | 256,000,000 | **51,200,000** |

> 容量是“半个能量立方”。这比旧的 300k / 1.5M / 3.9M / 8.7M 大约 2.7×–5.9×，
> 但充满时间从 6 秒变成 25 秒：**更大的油箱，更慢的加油**，这是刻意换的手感。

### 3.3 带宽（`WeaponEventHandler.chargeEquipment`）

带宽同样来自 `EnergyCubeTier.getOutput()`，但**不再按“每件装备”解释**：它是**全部装备合计**的
FE/t。`chargeEquipment` 每 5 tick 取一次总预算，按主手 → 护甲的先后顺序花，某个目标满了就把
剩余预算留给下一个，因此空槽/满槽都不会浪费。

```java
GolemEnergyTier tier = GolemEnergyTiers.forLevel(GolemEnergyModifier.totalLevel(golem));
long perPass = (long) tier.transferFePerTick() * ENERGY_CHARGE_INTERVAL; // 5 tick
int remaining = ...; // 主手 + 护甲共享
```

| 组合 | MEK tier | cube output（J/t） | 总带宽（FE/t） | 充满整个池 |
|---|---|---|---|---|
| 只有基础电量 | `BASIC` | 4,000 | 1,600 | 500 tick = 25 秒 |
| +灌注 | `ADVANCED` | 16,000 | 6,400 | 500 tick = 25 秒 |
| +精英 | `ELITE` | 64,000 | 25,600 | 500 tick = 25 秒 |
| +终极（满） | `ULTIMATE` | 256,000 | 102,400 | 500 tick = 25 秒 |

> 对比旧值：容量 300k / 1.5M / 3.9M / 8.7M，总带宽 2.5k / 12.5k / 32.5k / 72.5k，全部 6 秒充满。
> 新曲线的基础/中档带宽更低、终极更高，但所有档位的“容量 ÷ 带宽”统一为 25 秒——
> 这是把“对折存储 + 原样输出”直接落到能量立方曲线上的结果。

**无 MEK 时**：只有基础档，走 `fallback_capacity` / `fallback_transfer`（默认 300,000 FE /
2,500 FE/t 总带宽），行为和旧版基础档一致。

### 3.4 前置校验：基础电量必须先装

**好消息：槽位机制替我们兜住了一半。** `GolemRepeatableSmithAddSlotRecipe.matches()` 已经有一段
"模拟装上去看看剩余槽位够不够"的检查（`src/main/java/.../base/recipe/GolemRepeatableSmithAddSlotRecipe.java:64-68`），
它调的就是 `GolemHolder.getRemaining`。而后者（`dev/mg-src/.../content/item/golem/GolemHolder.java:693-709`）是：

```java
int base = type.getUpgradeSlots();                                    // 金属傀儡默认 4
for (IUpgradeItem e : upgrades) if (e.consumesSlot()) base--;         // 占槽的才扣
for (modifier : modifiers) base += modifier.addSlot(upgrades, level); // 扩充升级加回来
```

只要合金三件**覆写 `consumesSlot() = false`**（照 `AddSlotTemplate` / 你 `SpecialMoveUpgradeItem`
的写法），`getRemaining` 就**算不出**它们的存在——它无法表达"需要一个前置"。所以前置校验要自己写：

| 装配路径 | 位置 | 是否需要新代码 |
|---|---|---|
| 升级界面 | `GolemUpgradeHandlerMixin`（已注入 `appendUpgrade` HEAD，现用于特技互斥） | 扩展即可（**mod 类，不需要 refmap**） |
| 可重复锻造配方 | `GolemRepeatableSmithAddSlotRecipe.matches()`（本项目自己的类） | 在现有模拟块后加一句 |
| 本家的 `golem_add_slot` 锻造配方 | `dev/mg-src/.../recipe/GolemSmithAddSlotRecipe.java:43-49` | 不改；只做本模组数据包配方也行 |

判定逻辑（三条路径共用）：

```java
// 合金链的任一档，都要求傀儡身上已有"基础电量升级"这件物品
static boolean alloyChainAllowed(ArrayList<IUpgradeItem> installed) {
    return installed.stream().anyMatch(i -> i == ModItems.GOLEM_ENERGY_UPGRADE.get());
}
```

**注意不要用修饰符等级代替物品检查**：`golem_energy` 的等级在"只装了合金没装基础"时是 3、7、15，
看上去也有等级——所以必须查**物品本身**（这和 `GolemEnergyTechModifier.hasTechUpgrade` 刻意避开
标签、改查具体物品是同一个思路，见该文件 124-130 行的注释）。

---

## 4. 辐射链：只发电，不做源

### 4.1 为什么砍掉"辐射源"

原设计的"傀儡变成辐射源"有一个不可接受的缺陷：**Mekanism 的辐射源每秒只衰减 ×0.9995
（`sourceDecayRate = 0.9995`，你的 `config/Mekanism/general.toml:99`）**，一处千 Sv/h 级污染
要十小时以上才降到安全线，而 Mekanism **没有清除剂**（已吸收剂量只能靠 `targetDecayRate` 自行消退）。
傀儡是会跟着玩家满地图走的实体——它走过的每条路都会留下长期污染，多人服里后续玩家会在不知情的情况下
吸收过量剂量暴毙。**这是不可逆的破坏，不适合作为默认内容。**

改做**辐射发电**：不写世界、只读世界，因此不可能污染任何地方。

### 4.2 数值基础：傀儡不吃辐射，这恰好是支点

已核实：**`DefaultRadiationEntity.Provider` 在整个 Mekanism jar 里只被 `CommonPlayerTracker`
一个类引用**——原版只给玩家挂 `IRadiationEntity` 能力，普通生物与傀儡根本不会累积剂量。
所以傀儡站在辐射区里除了被辐照外没有负面效果，而这个"缺口"正好可以当机制用：

```
每 20 tick 结算（与 W 罐/备用能源同一节拍）：
  E        = IRadiationManager.INSTANCE.getRadiationLevel(golem)   // Sv/h，只读
  屏蔽值    = 辐射屏蔽升级提供（默认 0）
  有效剂量  = E × (1 − 屏蔽值)
  severity = clamp((log10(有效剂量) + 6) / 7, 0, max_severity)      // 原版公式
  发电FE/t = severity × Config.RADIATION_FE_PER_SEVERITY × (1 + 档位系数 × step_rad)
```

**实现期的两处修正**（写代码时实测出来的，与最初设想不同）：

1. **屏蔽必须由我们自己乘，不能指望 Mekanism 采信。** 反编译 Mekanism 10.4.x 的
   `RadiationManager.getRadiationResistance` 发现它是
   `for (slot : EnumUtils.ARMOR_SLOTS) getItemBySlot(slot).getCapability(Capabilities.RADIATION_SHIELDING)`
   ——**只遍历四个盔甲槽的"物品"能力**，实体自身挂 `IRadiationShielding` 它根本不看。
   所以正确做法是：我方读环境值 → 自己乘 `(1 − 屏蔽值)` → 把结果灌进傀儡。
2. **为了让屏蔽真正有意义，必须给傀儡挂 `IRadiationEntity`。** 实测该能力的 Provider
   在整个 Mekanism jar 里**只被 `CommonPlayerTracker` 引用**，也就是原版只给玩家累积剂量；
   不挂的话傀儡永远不吃辐射伤害，屏蔽升级就退化成"只压低发电机出力"的纯负收益升级。
   实现走 `AttachCapabilitiesEvent`（与 Mekanism 挂玩家的做法同一个事件），
   并复用 Mekanism 自己的 `DefaultRadiationEntity.Provider` 等价物，掉血规则完全交给它。

**推论：屏蔽值与发电量成反比。** 这不是缺陷，而是**把两档升级咬合起来的油门**——
"免疫"的代价就是停机：

| 屏蔽值 | 傀儡受照 | 发电量 |
|---|---|---|
| 0（无防护） | 满剂量 | **100%（满出力）** |
| 27%（屏蔽 I） | ×0.73 | 73% |
| 53%（屏蔽 II） | ×0.47 | 47% |
| 73%（屏蔽 III） | ×0.27 | 27% |

（这组数字按推荐默认 `step_rad = 3` 得出，推导见下表。）

屏蔽值本身也走同一个步长制：

```
加权 = 1 + 档位系数 × step_rad      // 档位系数是常量：0（无）/ 1 / 3 / 7
屏蔽值 = clamp(加权 / shield_denominator, 0, 1)
```

> **实现时简化了这层**：档位系数直接取 `0 / 1 / 3 / 7`（即 `加权 = 1 + 系数 × step_rad`），
> 而不是初稿里的"系数−1"。两种写法在 `step_rad=3` 下给出的三档屏蔽值完全一样
> （0.27 / 0.53 / 0.73），但前者的常数表更直观。默认 `shield_denominator = 15`。

| 档位 | 贡献（×step_rad） | `n_rad` | 加权（step=3） | 屏蔽值 | 傀儡受照 | 发电出力 |
|---|---|---|---|---|---|---|
| 只有基础档 | — | 0 | 1 | 0.07 | ×0.93 | 93% |
| 屏蔽 I | ×1 | 1 | 4 | **0.27** | ×0.73 | 73% |
| 屏蔽 II | ×3 | 3 | 10 | **0.53** | ×0.47 | 47% |
| 屏蔽 III | ×7 | 7 | 22 | **0.73** | ×0.27 | 27% |

**注意两点：**

1. **发电与屏蔽共用一条曲线是刻意的**——屏蔽档位越高，发电机出力越低，两档升级互为代价，
   配置里也只有 `step_rad` 一个数要调。
2. **`step_rad` 决定这条曲线有多陡**。表里是我推荐的默认值 **3**（三档屏蔽 27%/53%/73%，
   发电 73%/47%/27%，连续、无废档）。若调到 7，屏蔽 II 的加权就达到 22/15 → 直接封顶，
   屏蔽 III 变成废档；若调到 7 又想要三档都有意义，得同时把分母 15 改大。
   **结论：默认 3，且分母固定 15。**

所以早先"要不要给傀儡自免疫"的纠结自动消解了：**不是不给免疫，而是免疫就是关掉发电机**。

**实现补充：傀儡会受到自己所在辐射场的辐照，但被压到 1%。**
`GolemRadiationGeneratorModifier.ABSORBED_FRACTION = 0.01` 决定这一条：
每 20 tick 把 `环境剂量 × (1 − 屏蔽值) × 0.01` 灌进傀儡的 `IRadiationEntity`。

- 为什么**不是 0**：屏蔽若只影响发电、完全不影响傀儡自身，它就是个纯发电油门，
  "屏蔽"这个词就名不副实了。
- 为什么**是 0.01 而不是 1**：傀儡会跟着玩家满地图走，若按原样吸收剂量，它会站在
  自家废料库旁边慢慢掉血——那不是设计，那是麻烦。1% 让熔毁级辐射场里的剂量累积慢到几乎无害，
  同时保留"屏蔽确实在挡东西"的语义。
- 屏蔽值因此有**双重作用**：削弱灌入傀儡的剂量，同时按比例压低发电量。

### 4.3 severity ↔ 剂量换算表（所有辐射数值的地基）

```
severity = clamp((log10(dose) + 6) / 7, 0, 1)        dose 单位 Sv/h
你的配置 negativeEffectsMinSeverity = 0.1  ← 低于此不加负面效果
反解 0.1：log10(dose) = 0.1×7 − 6 = −5.3  →  dose = 5.0e-5 Sv/h
```

**这条算出来的线正好等于社区实测的"50 µSv 开始掉血"**，两个独立来源互相印证，可以放心当基准：

| severity | 剂量 (Sv/h) | 症状 |
|---|---|---|
| 0.00 | 1.0e-6 | HUD 下限 |
| **0.10** | **5.0e-5** | **开始掉血** |
| 0.20 | 2.5e-4 | 视野变蓝 |
| 0.40 | 2.5e-3 | |
| 0.70 | 1.0e-1 | |
| 1.00 | 1.0e2 | 满 |

**累积速度**（算"多久出事"必需）：`实体剂量累积 = 环境剂量 ÷ 3600`（每秒）。
即环境剂量 1.0 → 1 小时到掉血线、约 100 小时致死。**"环境 1.0"看着大，其实是慢性的。**

### 4.4 发电数值（基础值 20 FE/t per severity）

辐射三档的档位系数是常量 **1 / 2 / 4**（与合金链同一套 2 的幂次），走**加法叠加**，
实际倍率 = `1 + 系数 × step_rad`。**已实现值**（`step_rad = 3`，故倍率 4 / 7 / 13）：

| 环境 | severity | I 档（×4） | II 档（×7） | III 档（×13） |
|---|---|---|---|---|
| **干净环境（绝大多数战场）** | 0 | **0 FE/t** | **0 FE/t** | **0 FE/t** |
| 刚过掉血线（弱废料堆） | 0.1 | 8 FE/t | 14 FE/t | 26 FE/t |
| 中等辐射（正常废料库） | 0.4 | 32 FE/t | 56 FE/t | 104 FE/t |
| 裂变堆内 | 0.7 | 56 FE/t | 98 FE/t | 182 FE/t |
| 熔毁现场 | 1.0 | 80 FE/t | 140 FE/t | **260 FE/t** |

- **干净环境严格 0**，靠两道闸：① `RADIATION_MIN_DOSE`（默认 `1.0e-4`）直接把低于阈值的剂量
  判成 0——实测干净环境的基线是 `1.0e-7`（Mekanism `DefaultRadiationEntity` 里的常量），
  比阈值低三个数量级；② severity 公式本身在低剂量下趋近 0。
- III 档满辐射 260 FE/t ≈ **每 8 秒攒够一次武士刀全力爆发**。是额外补给，不是替代充能站。
- **不做发热**：傀儡没有 Mekanism 的热容能力（`IHeatHandler` 可挂），而热在你的系统里没有下游用途
  （不驱动任何东西）。只发电。

> **注意**：默认参数下满档 260 FE/t 比初稿估算的 140 更肥——因为 `step_rad = 3` 同时服务于
> 屏蔽与发电两条曲线。若实测觉得过强，把 `fe_per_severity` 从 20 降到 10 即可（整张表减半），
> 不需要动步长（动步长会连带改屏蔽值）。

### 4.5 ⚠️ 上线前必做：用盖革计数器校准

上表的 severity 是按 Mekanism 公式**推算**的，**没有实测过你整合包里的真实剂量**。
`RADIATION_FE_PER_SEVERITY` 是目前全设计里唯一**凭推算**设定的数。接线后第一步：

1. 手持 `mekanism:geiger_counter`，分别站在 **① 裂变堆旁 ② 废料桶阵列旁 ③ 旧熔毁点** 各记一个读数；
2. 用 `severity = clamp((log10(读数) + 6) / 7, 0, 1)` 换算成 severity；
3. 若实测 severity 远低于上表（很可能，因为多数废料库辐射量不大），**按比例调高
   `RADIATION_FE_PER_SEVERITY`**，让"站对地方"仍有正收益。

**这一步不做，辐射发电的数值就是猜的。**

### 4.6 不做的：辐射源（备注留档）

若将来仍想做"傀儡作为辐射源"：**必须默认关闭**（配置开关，`radiation.emit.enabled = false`），
且文档里标明"不推荐用于多人服"。原因见 §4.1，不展开。

---

## 5. 槽位经济

金属傀儡默认 4 槽（`MGConfig.COMMON.largeGolemSlot = 4`，人形 3、狗 2）：

```
基础电量(1) + 科技升级(1) + 合金能源 ×3(0，附属) + 辐射发电(1) = 3 槽，剩 1 槽
再敲科技扩充（+1 槽 / −10% 最大生命，最多 5 级）可打开武器与特技配置
```

**为什么合金链不占槽**：三个升级是同一件东西的三档，喂同一个修饰符，语义上是"基础电量的扩展包"。
**为什么辐射发电占槽**：它和合金同源、共用材料，但性质上是一个独立设备；而且"不占槽的免费升级"
会挤掉其它设计空间——玩家没有理由不装它，它就从"选择"变成"默认"，而"选择"才是升级系统的意义。
它在家里（核电站/废料库旁）随时可用，只是战斗中不发电——这是**场景限定**，不是"不起作用"。

---

## 6. Config 键清单（电量链接 MEK；辐射仍为步长制）

```toml
[golem_energy_upgrade]
    # 仅在没装 Mekanism 时使用：基础档的容量与总带宽。
    # 装了 Mekanism 时，基础/灌注/精英/终极分别镜像 BASIC/ADVANCED/ELITE/ULTIMATE，
    # 容量取立方存储的一半，带宽取立方输出的全额（全部装备合计）。
    fallback_capacity = 300000
    fallback_transfer = 2500
    lightning_charge = 50000

[radiation]
    # severity = 1.0（熔毁现场级）时，基础档（非最高档）的发电量：
    # 实际发电 = severity × 这个数 × (1 + 系数(1/2/4) × step)
    fe_per_severity = 20
    # 【步长】辐射发电 / 屏蔽每档的加成 = 档位系数 × step
    # 发电系数 1/2/4 → 倍率 4/7/13；屏蔽系数 1/3/7 → 加权 4/10/22
    step = 3
    # 屏蔽值 = 加权 ÷ 这个分母（封顶 1.0）。默认 15 → 0.27 / 0.53 / 0.73
    shield_denominator = 15.0
    # 低于这个剂量（Sv/h）完全不发电。干净环境基线是 1.0e-7
    min_dose = 1.0e-4
    # severity 上限（防止极端场收益爆掉）
    max_severity = 1.0
```

> 屏蔽的等级上限（1 / 3 / 7）是 `GolemRadiationShieldingModifier.MAX_LEVEL` 里的**编译期常量**，
> 没有做成配置键：`GolemModifier` 的 `maxLevel` 必须在 `super()` 之前确定，而静态初始化阶段读
> `Config` 会踩到"配置规格还没构建"的时序。开一个改不动的键只会误导人。

**辐射预设对照**（电量链已无 step，不在此表）：

| `step_rad` | 屏蔽 I/II/III | 发电倍率 I/II/III |
|---|---|---|
| **3（默认）** | 27% / 53% / 73% | ×4 / ×7 / ×13 |
| 5 | 40% / 100% / 100%（后两档封顶，成废档） | ×6 / ×11 / ×21 |

---

## 7. 数据 / 资源文件清单

| 类型 | 路径 | 数量 |
|---|---|---|
| 物品注册 | `init/ModItems.java` | 4 件（基础电量改名沿用 / 三合金 / 辐射发电） |
| 物品标签 | `data/golems_arsenal/tags/items/*` | 新增 `energy_alloy_upgrades`（合金链，供前置校验与 JEI 归类）；**不要**塞进 `tech_upgrades` |
| 配方 | `data/golems_arsenal/recipes/*.json` | 基础电量：无 MEK（红石×4 + 铜锭×4） / 有 MEK（红石×4 + 基础控制电路×2 + 铁锭×2）；合金三档：灌注合金×4+高级电路×2+锇锭×2、强化合金×4+精英电路×2+金锭×2、原子合金×4+终极电路×2+钻石×2；另有辐射件（钋球） |
| 模型 | `assets/golems_arsenal/models/item/*.json` | 4 个（可复用现有贴图，如 `minecraft:item/redstone`） |
| 语言 | `lang/zh_cn.json`、`en_us.json` | 每件 3 条：`item.` / `upgrade.` / `upgrade.<id>.desc`（最后一条强制，`GolemModifier.getDetail` 默认查它） |
| 创造栏 | `init/ModItems.java` displayItems | 4 条 |

**合金链的材料不构成硬依赖**：物品 id 是 `mekanism:alloy_*`，没装 MEK 时配方自然无法完成、
升级自然取不到——基础电量升级照常加载，正是"基础版"的语义。不需要 `mods.toml` 加依赖
（若要让 JEI/提示更友好，可另加 `forge:mod_loaded` 条件，但非必需）。

---

## 8. 实现要点与坑

| # | 事项 | 说明 |
|---|---|---|
| 1 | `GolemEnergyModifier.MAX_LEVEL` 5 → **15** | 等于 `1+2+4+8`。改小了合金装不上（`collectModifiers` 会封顶） |
| 2 | `GolemEnergyModifier.capacityForLevel` | 改成 `GolemEnergyTiers.forLevel(...).capacityFe()`；收纳态 `GolemEnergyItemProvider.capacityOf` 自动跟随，不需要另写公式 |
| 3 | `chargeEquipment` / `chargeStack` | 带宽是**总 FE/t**：每 5 tick 取 `transferFePerTick × 5`，主手 → 护甲共享，`chargeStack` 返回实际接受的量，剩余预算继续往下传 |
| 3b | `MekanismEnergyTiers` 的加载门控 | 放在 `compat/mekanism`，只由 `MekanismCompat.register` 设置进 `GolemEnergyTiers`；无 MEK 时不能把 `EnergyCubeTier` 放进任何 always-loaded 类的常量池 |
| 4 | `consumesSlot() = false` | 三件合金都要覆写；用 `GolemWeaponUpgradeItem` 的子类或 `RepeatableExpansionItem` 的写法 |
| 5 | 前置校验 | §3.4。三条路径中**只有本家的 `golem_add_slot` 配方改不到**（不是我们的类），但那条路径本来也不用于本模组的合金件 |
| 6 | refmap | 本次**不需要**新增条目——注入的是 mod 类（`GolemUpgradeItemHandler`）。**若将来注入 vanilla 成员，必须手工补 `golems_arsenal.refmap.json`**（见 `docs/DEPENDENCIES.md` 的告警） |
| 7 | 辐射只读 | 只调 `getRadiationLevel`，**绝不调 `radiate`**；不要挂 `IRadiationEntity`，不要自己算伤害（否则绕过防化服，制造重叠） |
| 8 | 单位 | `getRadiationLevel` 返回 **Sv/h**，Mekanism 内部累积是 `÷3600`。自己算累积时必须除，否则放大 3600 倍 |
| 9 | 节拍 | 全部挂在现有 `tickCount % 20` 上；`chunkCheckRadius = 5`（你的配置默认）不要自己再加扫描 |
| 10 | Mod 缺失门控 | 合金与辐射的物品注册走"条件注册"路线，照 `scroll_upgrade` 的现成写法：物品本体在 `compat/mekanism/` 下注册（该类只在 `CompatDispatch` 的 `isLoaded("mekanism")` 分支里被加载），创造栏侧在 `ModItems.TAB` 的 `displayItems` 里判一次 `ModList.get().isLoaded("mekanism")`（对比 `ModItems.java:273-275` 的 `golemmagicka` 写法）。`IRadiationManager` 是编译期硬引用（`build.gradle:110` 是 `implementation`），**类加载必须门控**，否则没装 MEK 时 `NoClassDefFoundError` |

---

## 9. 待办与开放项

| 项 | 状态 |
|---|---|
| 用盖革计数器实测三处辐射读数，反推 `fe_per_severity` | **上线前必做**（§4.5） |
| 辐射屏蔽升级要不要做、占不占槽 | 待定（公式已和发电链共用步长） |
| 电量链映射到 MEK 立方后，51.2M + 102.4k FE/t（25 秒充满）是否过肥 | 打完 jar 进整合包实测 |
| 无 MEK 的 fallback（300k + 2,500 FE/t）是否还要保留可配置 | 实测后决定；当前保留两个键 |
| 辐射源档 | **不做**（§4.6 留档） |

---

## 10. 数值出处

| 结论 | 出处 |
|---|---|
| 升级等级求和 + 封顶 | `dev/mg-src/.../config/GolemMaterial.java:129`（`Math.min(a.maxLevel, old + e.level())`） |
| 剩余槽位算法、`consumesSlot` 才扣槽 | `dev/mg-src/.../content/item/golem/GolemHolder.java:693-709` |
| 默认槽位 4 / 3 / 2 | `dev/mg-src/.../init/data/MGConfig.java:195-197` |
| 本家豁免升级**占槽**（未覆写 `consumesSlot`） | `dev/mg-src/.../init/registrate/GolemItems.java:448-459` → `SimpleUpgradeItem` |
| 唯一不占槽的模板 | `dev/mg-src/.../content/item/upgrade/AddSlotTemplate.java:23-26` |
| 可重复锻造配方已有槽位模拟 | `src/main/java/.../base/recipe/GolemRepeatableSmithAddSlotRecipe.java:64-68` |
| 充能调用点与硬编码带宽 | `src/main/java/.../base/event/WeaponEventHandler.java:1221-1240` |
| 容量同步点（两处） | `WeaponEventHandler:955-966`、`tech/energy/GolemEnergyItemProvider.java:30-37` |
| 只有玩家挂辐射能力 | Mekanism jar：`DefaultRadiationEntity$Provider` 仅被 `mekanism.common.CommonPlayerTracker` 引用 |
| severity 公式 / 0.1 阈值 | Mekanism 10.4.x `RadiationManager`；`config/Mekanism/general.toml:105`（`negativeEffectsMinSeverity = 0.1`） |
| 源/实体衰减 0.9995 | `config/Mekanism/general.toml:99,102` |
| 合金三阶 id | 整合包 FTB 任务：`mekanism:alloy_infused` / `alloy_reinforced` / `alloy_atomic` |
| 环境剂量 → 掉血时间 | 社区实测（10.4.16.80）：50 µSv 开始掉血，与 §4.3 反解值一致 |

---

## 11. 变更记录：辐射屏蔽已删除

**决定**：删掉整条"辐射屏蔽"升级线（原本 3 级、占 1 槽、与发电共用 `step` 曲线互为代价）。
理由——**"净污发电"替代了屏蔽的意图**：傀儡不是挡住辐射，而是把周围那点污染消耗掉换电，
所以屏蔽变成了一条多余且互相纠缠的线。

**已清理**：

| 位置 | 处理 |
|---|---|
| `tech/radiation/GolemRadiationShieldingModifier.java` | 删除文件 |
| `GolemUpgrades` 的 `RADIATION_SHIELDING` 注册 + `radiationShieldingModifier()` | 删除 |
| `MekanismCompat` 的 `RADIATION_SHIELDING_UPGRADE_1/2/3` | 删除 |
| `Config.RADIATION_SHIELD_DENOMINATOR`（`shield_denominator`） | 删除 |
| 语言键 `item./upgrade.golems_arsenal.radiation_shielding*` | 删除 |
| 3 个模型 json + 3 个配方 json | 删除文件 |
| `GolemRadiationGeneratorModifier` 里所有 `(1 − 屏蔽值)` 折损 | 删除（含 `irradiate` 与 `generationRate`） |

**保留的东西**：傀儡身上的 `IRadiationEntity` **没有删**。它的用途不是"让傀儡受伤"，
而是**记录傀儡累积了多少辐射**——将来"持续照射强化傀儡"那条设计要靠它当燃料槽读。
灌入量由 `ABSORBED_FRACTION = 0.01` 压到 1%，实测平衡点约 `5e-6 Sv/h`，
远低于 `5e-5` 的掉血线，所以它平时伤不到自己。

**旧存档兼容**：屏蔽升级物品消失后，傀儡身上残留的 `radiation_shielding` 修饰符等级在
`GolemUpgradeLevels.levelOf` 里查不到就是 0，不报错；物品本身变成"缺失条目"（任意移除模组的常规表现）。

**若将来要做回来**：注意 Mekanism 的 `RadiationManager.getRadiationResistance` 只遍历
**四个盔甲槽的物品**去取 `Capabilities.RADIATION_SHIELDING`，**实体自身挂的能力它不采信**——
所以实体级屏蔽必须由模组自己乘系数（读环境值 → 乘 `(1−屏蔽值)` → 再灌回实体）。
