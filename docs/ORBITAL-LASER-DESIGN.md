# 天基激光炮（MEK 联动）设计依据

> 本文是**立项依据 + 数值定稿**，配合 `docs/MEK-ENERGY-RADIATION-DESIGN.md`（电量链 / 合金能源 / 辐射）阅读。
> 结论先行：
> 1. **不需要真正的升空阶段**。火箭只在可视高度内播一段短特效，入轨状态是一条 `SavedData` 记录，
>    不加载区块、不持久化实体。
> 2. **伤害用自建的 `orbital_strike` 伤害类型**（不是 MEK 的激光类型），
>    这样 `DamageSource` 才能带上引导者、击杀有归属；穿甲语义由伤害类型标签提供。
>    （早期版本借过 MEK 的激光类型，代价是无归属，2026-10-21 已改掉。）
> 3. **主光束自建渲染，不用 MEK 粒子**——MEK 的 `LaserParticle` 构造器是 private，颜色/透明度/粗细都改不了。
>    MEK 的粒子只用在落点冲击，保证"这就是 MEK 的激光"的观感。
> 4. **清污即发电**（详见另一份文档的辐射章）：本设计只依赖"傀儡身上有辐射剂量"这一个读数。

---

## 0. 2026-10-21 接手后的定稿（以此节为准）

1. **引导升级改成单目标、单发激光炮**，调用 `tech/orbit/OrbitalStrike#cast`，和 `/ga full` 共用
   “六束瞄准 → 弹体 → 落点冲击 + 不可见伤害光束”的完整时序。等级只影响单发伤害/FE，不再增加锁定数。
2. **激光发射台已实现**（`LaserConsoleBlock` / `LaserConsoleBlockEntity` / `LaserConsoleMenu` /
   `LaserConsoleScreen`），模型由 `激光.bbmodel` 转成方块模型。它是**必需的地面控制端**：
   同一维度里至少有一台「启用 + 已绑定在轨平台」的发射台，傀儡的引导升级才会开火。
3. 发射台自动绑定放置者的第一颗在轨平台；先放发射台、后发射火箭也会在 2 秒内自动接上。
   全局记录存在 `golems_arsenal_laser_consoles.dat`，区块卸载后仍然生效。
4. GUI：总开关、友军保护、安全区（0/8/16/32，默认 **8**）、能量档（经济/标准/超载）、
   冷却档（快速/标准/蓄能）、平台切换。落在安全区内时给傀儡主人发 action bar 提示。
5. FE 仍从傀儡自己的池里扣；发射台只定档、不储电。同一维度里最近的启用+已绑定发射台决定档位。
6. 配置键、档位倍率与实机验证步骤见 §8/§9；`impact_damage` 默认 40，满 14 级 +21；
   `cost_base` 20k + 14×5k = 90k FE 每发。
7. **地底/室内目标不再白扣 FE**：`OrbitalImpact.canHitFromOrbit` 比较目标包围盒顶面与落点高度；
   目标整体在落点（头顶遮挡物的顶面）之下时判为不可标定，既不标定也不扣 FE；若目标是在标定后
   才躲进去的，会清目标并给主人发 action bar 提示（`orbital_covered`，10 秒最多一条）。

---

## 1. 已核实的技术事实

| 事实 | 出处 / 验证方式 |
|---|---|
| `MekanismDamageTypes.LASER` 是公开静态字段，`MekanismDamageType` 是 record | jar 内 `MekanismDamageTypes$MekanismDamageType` 反编译 |
| 它**只有** `source(Level)` / `source(RegistryAccess)`，**没有**接受 Entity 的重载 | 同上；`DamageSource` 里无 attacker，击杀无归属 |
| MEK 激光的实际伤害管线 | `TileEntityBasicLaser`：`Level.getEntitiesOfClass` 沿射线扫 → `Entity.hurt(LASER.source(level), f)`，另含举盾减伤与护甲耐久消耗 |
| 激光**方块**无法远程开火 | `TileEntityLaserAmplifier.shouldFire()` 是 private，公开接口只有 `setDelay/nextMode/setMinThreshold…` |
| `LaserParticleData` 只有 3 个字段 | `(Direction direction, double distance, float energyScale)` —— 无颜色、无粗细 |
| `LaserParticle` 不可外部实例化 | 构造器 `private LaserParticle(ClientLevel, Vec3, Vec3, Direction, float)` |
| MEK 的激光粒子贴图 | `assets/mekanism/textures/particle/laser.png`（只有这一张） |
| 辐射源可写、可扣减 | `IRadiationManager.radiate(Coord4D, double)` 为累加；`RadiationSource.decay()` = `magnitude *= 0.9995; return magnitude < 1.0e-5`（扣到 1e-5 以下 Mekanism 自动清除） |
| `Coord4D` 可从方块/实体构造 | `new Coord4D(Vec3i, Level)` / `new Coord4D(BlockEntity)` / `new Coord4D(Entity)` |
| Ad Astra 的火箭形态（仅作手感参照，本模组不依赖它） | 物品 + 发射台方块 + 实体升空；**倒计时 10 秒**；**`y = 600` 为入轨判定**；玩家中途离开则到 600 后火箭消失；被方块挡住在原地喷火 |

---

## 2. 阶段状态机

> **设计已改（发射台方案作废）**：原先设计的"发射台方块 + 载荷物品"被**火箭方案**取代。
> 理由：Ad Astra 里火箭本来就能直接发射，发射台没有明确作用。现在由**火箭自己**承担一切。

```
阶段0 待发   火箭物品（可预装燃料）→ 放置 → 生成火箭实体（占地 5×5）
阶段1 加注   右键火箭实体开 UI：储罐槽加氢气 + 「发射」按钮
阶段2 点火   按发射 → 倒计时 200 tick（10 秒）
阶段3 升空   火箭实体上升；**上升段撞到任何东西 → 爆炸**
阶段4 在轨   到达高度后实体消失，只写一条 SavedData 记录
             （没有实体、没有方块、不加载区块）
阶段5 打击   引导者存在时才现算：射线 → 光束实体 → 伤害
```

### 图纸分工（已定）

| 东西 | 角色 | UI |
|---|---|---|
**激光方块**（BB：`激光.bbmodel`，0.92×1.16×0.92 格） | **地面端**：控制激光（开关、禁区…） | **暂时不做**，那些功能列为长远优化 |
**火箭物品** | 可放置、**可预装燃料** | — |
**火箭实体**（BB：`火箭实体模型.bbmodel`，4.44×14.94×4.50 格，512 贴图） | 加燃料 → 发射 → 升空 → **自己就是平台（也是卫星）** | ✅ 储罐槽 + 燃料显示 + 发射按钮 |
**绑定** | 不必须（只是仪式感） | 不做 |

### 已定的数值

| 项 | 值 | 出处 |
|---|---|---|
倒计时 | **200 tick（10 秒）** | 用户指定 |
占地 | **至少 5×5** | 用户指定；与模型 4.44×4.50 格吻合 |
燃料 | **氢气**（MEK 气体） | Gas-Burning Generator 只烧氢气与乙烯，氢气最易得 |
上升段碰撞 | **撞到东西就爆炸** | 用户指定 |
发射触发 | **UI 上的「发射」按钮**（不是自动） | 用户指定 |

### 玩家进度的功能（长远优化，已登记）

- 激光方块的 UI：激光开关、**禁区/安全区**（不触发激光攻击的区域）
- 绑定流程的"更多功能"

### 为什么砍掉"真升空到 y=600"

- 轨道平台在 `y=600` 以上，**那里没有区块加载**：火箭实体飞过去会立刻进入未加载区块
- 要么一路 chunkload（代价高、还有 MEK `Anchor` 的前车之鉴），要么接受实体半途消失——
  而 **Ad Astra 自己就是后者**（玩家中途下船，火箭到 600 后直接消失），说明它也只在意可视高度内的那段
- 本模组**不需要玩家乘坐**，所以 Ad Astra 那套"高度 UI / A-D 旋转 / Shift 下船"一个都不需要

**"不可乘坐"因此自动成立**：不给实体实现 `PlayerRideable`、`interact` 直接 `return PASS` 即可。

### 打断规则

| 情形 | 处理 |
|---|---|
| 倒计时期间火箭实体被破坏 | 取消发射，**退还火箭物品**（带原来的燃料） |
| 升空段爆炸 | 火箭损毁（物品不退） |
| 升空特效播到一半被打断 | **照常入轨**（记录本来就要写，不做中途拦截判定） |
| 在轨期间**原来的地面端**被拆 | 平台记录失效（下次打击时校验，失效则清除并通知）—— *待定：新方案里地面端是激光方块，这条要重定* |

---

## 3. 在轨状态的存储形态

**用 `SavedData`，不用玩家 NBT。**

理由：卫星是"世界尺度"的设施，不是玩家物品的一部分。挂在玩家 `persistentData` 上会带来一串麻烦：
玩家死了/退服/换维度时状态归属模糊、多人服无法转交、也无法支持"一个玩家多个平台"。

```
OrbitalPlatformData extends SavedData          // 世界级，dimension 无关（记录里带维度）
  Map<UUID /*platformId*/, Platform>
    Platform {
      UUID owner;                  // 发起者
      ResourceKey<Level> dimension;
      BlockPos launchPos;          // 发射台坐标（用于校验平台是否还在）
      int tier;                    // 载荷等级 → 决定可用引导上限
      long launchedAt;
    }
```

配套一件**锚点物品**（`orbital_anchor`，不可合成、由载荷转化）：
- 归属与转交靠它（丢在地上 = 平台可被夺走）
- 客户端/服务端同步只需同步锚点的存在与 tier，不必同步世界记录
- 玩家阵亡掉落锚点，天然 PvP 玩法

> **一个坑要提前记住**：玩家自己用 MEK 激光时，`TileEntityBasicLaser` 是用 `Level.getEntitiesOfClass`
> 直接取实体的，**不需要区块加载**；但我们的天基炮是从 `y=600` 往下打，**发射端所在区块必须是加载的**。
> 所以打击时用 `ServerLevel.getChunk` + 强制加载一小块（发射台或落点），**打击结束立刻释放**。
> 不能常驻加载——那正是"不要为了卫星挂一个永久 chunkloader"的原因。

---

## 4. 引导与开火（已实现）

### 引导路径

| 引导者 | 载体 | 能量来源 |
|---|---|---|
| **傀儡** | 引导升级（合金链，见 §5） | **傀儡自己的 FE 池**（与合金能源链咬合） |
| 玩家 | 目标指示器物品 | 未实现（第 3 刀之后再评估） |

### 四条游戏约束（都已在代码里生效）

| 约束 | 配置键 / 判定 | 为什么 |
|---|---|---|
| **贴身目标引导不了** | `orbital_guidance.min_range`（默认 5 格） | 防"变成额外近战伤害"；同时给了对手一个明确的反制手段——贴脸 |
| **被方块挡住就打不到** | 固定行为（`canSee` 做 `ClipContext` 视线检测） | 掩体有意义，也避免穿墙击杀的观感问题 |
| **头顶有遮挡也打不到** | 固定行为（`OrbitalImpact.canHitFromOrbit`） | 地底/室内目标不再被标定、不扣 FE；光柱只从落点往上打，不会隔天花板命中 |
| **每发扣傀儡的 FE** | `cost_base` + `cost_per_level` | 让合金能源链决定你打得起几发；打不起就**静默停火**（代价是"打得起"，不是冷却） |

### 目标选择（2026-10-21 改为单目标）

```
可同时标定数 N = 1                                  // 「增强升级」未做，不再随等级增长
实际打击       = 1 发完整激光炮
优先级         = 傀儡当前目标（合法时）→ 最近的合法威胁
```

标定每 `scan_interval`（默认 20 tick）刷新一次；目标以单个 UUID 存 `persistentData`，读档后继续。
开火前会再校验一次：活着、不是自己人、距离在 `[min_range, range]`、线视没有被方块挡住、**头顶没有被遮挡**。
只有 `Enemy`、已经锁定傀儡/主人的 `Mob`，或傀儡自己的当前目标才会被标定。

### 命中判定（实际实现）

1. 落点由 `OrbitalImpact.impactPoint` 求出（目标 x/z 这一列最高的 `MOTION_BLOCKING` 方块上方）；
2. `OrbitalStrike.cast` 先放六束瞄准，约 2 秒后发射弹体，弹体落地生成 `OrbitalImpactEntity`；
3. 同一时刻生成**不可见的** `OrbitalBeamEntity`：落地首 tick 结算一次半径内冲击伤害，
   之后按 `residual_damage` 每 tick 结算余热；
4. 友军判定走 `GolemFriendlyFire`，与引导选敌**共用同一套规则**；发射台的「友军保护」关闭时跳过该判定；
5. 发射台的安全区内的落点直接放弃，不扣 FE，并给主人发 action bar 提示。

> **“头顶有遮挡”怎么判**：光柱的伤害范围是从落点**往上**的柱体，不往下延伸。
> 地底/山洞/室内的目标，`getHeight(MOTION_BLOCKING)` 返回的是它**头顶遮挡物**的顶面，
> 目标包围盒整体在这个平面之下，`canHitFromOrbit` 就返回 false——既不标定也不扣 FE。
> 如果目标正上方有直通天空的竖井，落点会落到竖井底部，目标在落点之上，仍可命中。

#### 落点：取地表，**不需要射线**

第一版用射线（`ClipContext`）从天上扫下来找"第一个方块"，那是把 Mekanism 的机制硬套过来：

> Mekanism 的激光是**水平发射**的——从机器朝某个方向打出去，距离由配置的 `laserRange` 决定，
> 光路上有什么完全未知，所以它**必须**射线才能知道光停在哪。

**而本模组的光束是竖直的，落点是发起方自己挑的**，光该打在哪在生成那一刻就完全已知。
所以落点只做一件事：**取目标脚下那一格的地表**（`getHeight(MOTION_BLOCKING)`）。

> 关于草丛：`MOTION_BLOCKING` 不含草丛/火把这类无碰撞箱方块，所以落点是它们**下面**的地面。

---

### 方块破坏：**我们自己挖**（试过委托 Mekanism，撤回了）

> **署名说明**：这一整段是**我们自己的实现**。不存在任何借用的机制。

#### 走过的弯路：曾经把破坏整套交给 `mekanism:laser`

曾经的做法是在天上放一台真的 `mekanism:laser`（`facing=down`），每 tick 往里灌 FE，
让 MEK 自己的代码跑射线、挖掘进度、`MekFakePlayer` 破坏、掉落表、方块事件、命中火花。
**撤回的原因**是它的破坏受三重硬限制：

| 限制 | 具体 | 后果 |
|---|---|---|
| 射程 | `[general.laser] range`，默认 **64**（上限 1024），且**没有 setter**，运行时改不了 | 平台够不到地面，**必须让玩家改整合包配置** |
| 耗能 | `energyNeededPerHardness × 硬度`，默认 **100000/格** | 和我们的 FE 量级（满配 8.7M）对不上，挖一格土要 50000 FE |
| 占地 | 要在世界上真的放一个激光方块实体 | 世界高度上限会多出一堆方块，还得管清理 |

顺带把 MEK 那边的机制摸清楚了，这些结论留下来当参考：

- MEK 的射线用 `ClipContext.Block.OUTLINE`——**连草丛、火把都会挡住它**；
- 破坏会走 `MekFakePlayer.withFakePlayer(...)`，掉落表与方块事件依赖那个上下文；
- 玩家侧的防御是白拿的（`module_laser_dissipation_unit`、盾牌、护甲耗损）；
- 有一条必须记住的坑：**"顶在一个方块底下"和"能开火"是物理冲突的**——
  正下方那格方块正是射线第一个撞到的东西，会把光堵在出发处。

#### 现在的做法：下端逐格下移

光的下端就是"当前正在挖的那一格"，从落点开始：

```
每 tick：progress += 伤害 / (block_hardness_cost × 硬度)
progress ≥ 1.0  →  destroyBlock(tip)，然后 tip = tip.below()，progress = 0
progress < 1.0  →  在方块顶面喷火花（表示"正在烧"）
硬度 < 0（基岩那类）或 > max_block_hardness  →  光就停在那一格，不动
```

"破了才照到下一格"是它的自然结果——`tip = tip.below()` 就是下一格。
下端同步给客户端（`DATA_TIP_Y`），所以光柱在视觉上会**沉进地面**。

#### 破坏是**负面代价**，不是挖矿奖励

| 限制 | 配置键 | 默认 | 说明 |
|---|---|---|---|
| 硬度上限 | `max_block_hardness` | 2.0 | 草 0、土/沙 0.5~0.6、**石头 1.5**、木头 2.0、黑曜石 50 → 默认吃土/植物/木头，**啃不动石头** |
| 挖掘速度 | `block_hardness_cost` | 8.0 | 每 tick 进度 = `伤害 / (cost × 硬度)`；默认值下草（硬度 0）瞬间穿过、土（0.5）2 tick、木头（2.0）8 tick |
| 时间上限 | `life` | **40 tick** | 一发光束大约挖穿 5 格土或 2 格木头 |
| 掉落物 | `drop_blocks` | **false** | 平台是武器不是采石场，啃出来的方块不掉落，避免它变成刷资源手段 |
| 整体开关 | `break_blocks` | true | 关掉就是纯武器 |
| 基岩那类 | 固定行为 | — | 负硬度**永不破坏** |

#### 只在战斗中引导

`combat_only`（默认开）+ `combat_memory`（默认 200 tick = 10 秒）。
"在战斗"的判据取三种**真实威胁**信号，任一成立就刷新时间戳：

1. 傀儡自己有攻击目标（`getTarget()`）；
2. 它最近打过人或挨过打（`getLastHurtMob()` / `getLastHurtByMob()`）；
3. 附近有怪**正盯着它或它的主人**——这是唯一能在"还没开打"时就成立的条件，
   也是"前沿观察员"该有的嗅觉。

**停火时冷却一并冻结**，这样重新进入战斗不会立刻白送一发。
时间戳存 `persistentData`，读档后仍然有效。

#### 打击节奏（本次调过）

| 参数 | 旧值 | 新值 | 理由 |
|---|---|---|---|
| `life`（光束持续） | 60 tick | **20 tick** | 读起来是"打击"而不是"持续区域"；同时压制破坏深度 |
| `cooldown`（引导间隔） | 40 tick | **120 tick** | 从"循环炮台"变成"呼叫一次打击"；也让同一片地形不会被反复犁 |

---

## 5. 引导升级：**三档，没有核心件**（已实现）

这条链是 **MEK 的附属联动**（没有 Mekanism 就不注册这几件物品），玩法上只是"傀儡标定 + 平台打击"
的附加线路，不该再多占一个槽。所以**最低档灌注就是入口**，不需要核心件：

```
灌注合金引导    +2 级，不占额外槽位，【无前置】
精英合金引导    +4 级，不占额外槽位，需已装灌注
终极合金引导    +8 级，不占额外槽位，需已装精英
   → 总等级 L = 2 + 4 + 8 = 14
   → 合金加成 = L ÷ 2 = 1 / 3 / 7（只灌注 / 加精英 / 三件齐）
   → 可同时引导数 = 1 + (合金加成 − 1) × step      step=1 时满配 15 个目标
```

`step` 是唯一旋钮；档位系数是代码常量，因此"高档 > 低档"在任何步长下恒成立
（与 `docs/MEK-ENERGY-RADIATION-DESIGN.md` §2 同一套证明）。

### 与能源链的对照

| | 能源链 | 引导链 |
|---|---|---|
| 入口档 | 灌注，**要求**基础电量升级 | 灌注，**无前置** |
| 逐级前置 | 灌注 → 精英 → 终极 | 灌注 → 精英 → 终极 |
| 槽位成本 | 核心 1 槽 + 合金各 0 槽 | 合金各 0 槽（共 0 槽） |
| 满配等级 | 15（含核心的 +1） | 14 |

两条链**不互相前置**，但引导打击要扣傀儡 FE，所以实际玩起来会互相需要——这是刻意的咬合。

### 前置校验的实现要点

Modular Golems 的槽位机制**表达不了"需要一个前置"**：不占槽的物品在
`GolemHolder.getRemaining` 里完全不参与计算。所以校验由本模组自己拦，两个装配路径共用同一个入口
（`AlloyChains.rejectionForAny`）：

- 升级界面 → `GolemUpgradeHandlerMixin`（注入 `appendUpgrade` HEAD）
- 可重复锻造配方 → `GolemRepeatableSmithAddSlotRecipe.matches()`

顺带做**同档去重**：等级是"档位系数之和"，装两件灌注就是 2+2=4，会冒充精英档。

---

## 6. 伤害与击杀归属

### 现状：我们**借**了 MEK 的伤害类型，代价是击杀无归属

> **署名说明**：对敌人的伤害是**我们自己的逻辑**（我们每 tick 调 `target.hurt(...)`、伤害值来自
> `damage` / `damage_per_level`），但我们用的 **`DamageSource` 是 Mekanism 的**
> （`MekanismDamageTypes.LASER`）。所以"无归属"这个后果**来自 MEK 的伤害类型**，不是我们的设计决定。

`MekanismDamageTypes.LASER` 只有 `source(Level)` / `source(RegistryAccess)` 两个重载
（已用 javap 核实 `MekanismDamageType` 这个 record 的全部方法），造出的 `DamageSource`
**不带攻击者**。后果：

- 击杀不计入引导者 → 玩家拿不到经验、掉落不归功；
- 死亡讯息用的是 MEK 自己的 `death.attack.mekanism.laser`（"%1$s was incinerated."）；
- 玩家能做 `module_laser_dissipation_unit` 减伤、盾牌能挡——**这些是 MEK 的机制，我们白拿的，好事**。

**这是"借"的代价，不是设计立场。** 要不要改由你定，两条路：

| 想要的效果 | 做法 |
|---|---|
| 保持现状（MEK 语义 + 无归属） | 什么都不用改 |
| 击杀归功于引导者 | 用 `MekanismDamageTypes.LASER.key()` 取伤害类型 key，自造带 `causingEntity` 的 `DamageSource`（`new DamageSource(holder, directEntity, causingEntity)` 或 `level.damageSources().source(key, direct, causing)`）。签名需在 1.20.1 对照验证一次 |
| 完全自建伤害类型 | 注册我们自己的 `DamageType` + 死亡讯息，彻底不碰 MEK。**但那就失去上面那两条"白拿的机制"** |

### 实现：反射取 MEK 的伤害源

```java
// 反射取，避免把 mekanism.* 写进无条件注册类的常量池（见 MekanismLaserDamage）
DamageSource src = MekanismLaserDamage.source(level);   // = MekanismDamageTypes.LASER.source(level)
if (src == null) src = level.damageSources().generic(); // MEK 不在场时的退化路径
```

**不做的事**：不把激光伤害登记进 `is_magic` 等标签。理由——它是 MEK 的激光，就该保持 MEK 的激光语义；
登记进去会让其它模组的魔法增伤意外放大它，也会让"护甲无效"这个特征被误读成"魔法穿透"。

---

## 7. 渲染（已实现）

### 主光束：自建，因为 MEK 的粒子**改不了**

MEK 的 `LaserParticle` 构造器是 **private**，`LaserParticleData` 只有
`(direction, distance, energyScale)` 三个字段——**颜色、透明度、粗细全部没有入口**。
它那张 `laser.png` 还是**纯实心品红**（R=255, G=0），顶点色相乘时绿通道恒为 0，
**永远染不出橙色**。所以要满足"变色 / 半透明 / 粗细"三项需求，只能自建。

### 最终形态：内外两层四边光柱

```
外柱  宽 0.6 格，alpha 0.45，暖橙 (1.0, 0.72, 0.28)   ← 光晕 / 体积感
内柱  宽 0.24 格（外柱 40%），alpha 0.85，近白 (1.0, 0.95, 0.80)  ← 激光芯
```

| 要点 | 做法 |
|---|---|
| 贴图 | **本模组自己的纯白 16×16**（`textures/entity/orbital_laser.png`）——白色才能染出任意颜色 |
| 亮度 | `RenderType.entityTranslucentEmissive`（原版 `rendertype_entity_translucent_emissive` 着色器 + 加法混合）+ `uv2 = (240,240)` 全亮，与铁魔法烈阳射线的 `magic_glow` 同一套着色器 |
| 顶点色 | 走**浮点重载** `color(float,float,float,float)`：整型重载会夹在 0..1，浮点允许推过 1.0 做饱和（内柱的"白热"靠这个） |
| UV | 相位跟世界坐标走（不是从本地 0 开始），光束变长/相机移动时贴图不会游动；**向下滚**表示能量从天而降 |
| 精度 | 只渲染相机上下各 `RENDER_WINDOW = 96` 格那一段，避免画 300 格 |
| 面 | 每个面**正反各画一遍**，任何角度都可见，不必开 no-cull |

> 走过的弯路（都靠截图发现）：① 四棱柱"管子"→ 出来是一根实心棒子（几何错）；
> ② 薄片 ribbon → 侧看会变窄（3 片时最小可见宽度只有 37%，改 4 片 71%）；
> ③ 用 MEK 品红贴图 → 染不出橙色（贴图错）。

### 落点特效

落点火花目前用原版 `ELECTRIC_SPARK` 粒子（客户端侧生成，不产生额外网络包）。
**MEK 的 `LaserParticleData` 粒子可以后续替换上去**（发包给追踪该区块的玩家），
这样"就是 MEK 的激光"的观感更完整；它不涉及对方的客户端类，只用数据包。

> 注意：**天上那台 MEK 激光自己渲染它自己的光束**（`LaserParticleData`），
> 那一段是 MEK 发的包、MEK 画的，和我们下面这根柱子是两回事——两者会同时出现。
> 我们这条自建光柱的顶端已经跟着平台位置走（`DATA_TOP_Y`），所以视觉上是接着的。

---

## 8. Config 键清单（2026-10-21 实际实现）

```toml
[orbital_beam]
    altitude = 320                  # 光束视觉上的来源高度（1.20.1 建筑上限）
    residual_damage = 0.5           # 光束存续期间每 tick 的余热（不衰减）
    life = 20                       # 伤害光束存续 tick（20 = 1 秒）
    width = 0.6                     # 渲染外柱宽度（纯视觉）
    shader_effect_brightness = 0.35 # 光影包下的亮度倍率
    # ---- 破坏方块：预留键（当前只结算伤害，不挖方块） ----
    break_blocks = true
    max_block_hardness = 2.0
    block_hardness_cost = 40.0
    drop_blocks = false

[orbital_guidance]
    range = 32.0                    # 标定距离
    min_range = 5.0                 # 此距离内无法标定（防贴身输出）
    scan_interval = 20              # 标定刷新间隔
    cooldown = 120                  # 基础打击间隔；实际再乘发射台冷却档
    impact_damage = 40.0            # 单发冲击基础伤害（再乘发射台能量档）
    impact_damage_per_level = 1.5   # 每级额外冲击伤害（单件满 14 级 → +21）
    strike_radius = 6.0             # 瞄准圈/落点冲击视觉半径（伤害半径 ×1.15）
    cost_base = 20000               # 单发基础 FE（从傀儡自己的池扣）
    cost_per_level = 5000           # 每级额外 FE（满 14 级 → 90000）
    # ---- 只在战斗时引导 ----
    combat_only = true
    combat_memory = 200             # 脱离战斗后仍算"在战斗"的 tick 数
```

**发射台档位（代码常量）**：能量档 经济 ×0.60 成本 / ×0.75 伤害，标准 ×1.0，超载 ×1.60 / ×1.40；
冷却档 快速 ×0.70、标准 ×1.00、蓄能 ×1.50。安全区 0/8/16/32 格，默认 **8**；
落在安全区内时给傀儡主人发 action bar 提示（每 5 秒最多一条）。

---

## 9. 落地顺序

| 刀 | 内容 | 状态 |
|---|---|---|
| **第 1 刀** | `OrbitalBeamEntity` + `OrbitalBeamRenderer` + 伤害 + `/ga full` 调试命令 | ✅ 已完成 |
| **第 2 刀** | 引导升级（合金链收敛为 **1 件**，+14 级）+ 目标标定 + FE 消耗 | ✅ 已完成 |
| **第 3 刀** | 火箭 + `OrbitalPlatforms` 在轨记录 + 倒计时 + 升空 + 入轨 | ✅ 已完成 |
| **第 4 刀** | 激光发射台（方块/方块实体/菜单/GUI/平台绑定/安全区/能量冷却档） | ✅ 2026-10-21 完成 |
| **收尾** | 引导升级改用 `OrbitalStrike` 全流程（单目标、单发） | ✅ 2026-10-21 完成 |

调试验证命令（不依赖发射台/平台）：

```
/ga full 14          # 完整激光炮
/ga shot 14          # 只发射弹
/ga guide 14         # 只看六束瞄准
```

引导升级的端到端验证：火箭入轨 → 放发射台（自动绑定）→ 装
`infused_alloy_guidance_upgrade` + 能量升级 → 让傀儡进入战斗。
**注意**：发射台默认安全区 8 格；如果目标在安全区内会被取消，并给主人发 action bar 提示。

---

## 10. 待办与开放项

| 项 | 状态 |
|---|---|
| 引导链数值实测（满 14 级 → 61 基础伤害 × 能量档；90000 FE × 能量档） | 待实测；`impact_damage` / `cost_base` 可调 |
| 发射台 GUI 实机观感 | 自绘深色面板，无贴图；待看文字/按钮对齐 |
| 平台绑定多人服边界 | 全局覆盖 + 同维度最近发射台；同一玩家多平台可手动切换；跨维度不生效 |
| 发射台配方 | 已加 `laser_console.json`（MEK 材料，`forge:mod_loaded` 门控） |
| 方块破坏（`break_blocks` 等键） | 配置键在，当前只做伤害、不挖方块——**未实现，不是 bug** |
| 玩家侧"目标指示器"物品 | 未做（目前只有傀儡引导） |
| 落点改用 MEK 的 `LaserParticleData` 粒子 | 可选增强 |
| 作废文件清理 | `MekanismLaserDamage`（已无调用方，保留作参考）等 |
| 落点区块的临时加载与释放 | 未做；目标区块本来就加载，暂未处理 |

