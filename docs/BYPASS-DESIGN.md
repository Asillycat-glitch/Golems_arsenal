# 「绕过减伤」设计依据（实测 + 源码对照）

> 本文是**立项依据**，不是实现文档。它把私有测试台（`testbench/`，不进版本库）跑出来的数据、
> 以及各 mod 的源码出处整理在一起，供决定"正式版加什么、按什么数值加"时对照。
>
> 结论先行：**光靠"提高伤害数值"过不了整合包里的高级目标**；能过的是四类**位置正确**的手段
> —— ①伤害类型带对 tag；②抢在别人前面写数值；③把"每击上限"换成多段/破帧；④管线外写血。
> 本文按"它拦在哪一层"分类，逐层给出判据、破法、实测数字和边界。

---

## 1. 结论摘要（一屏）

| 防御类型 | 实测代表 | 唯一/最优破法 | 我们做不做 |
|---|---|---|---|
| **整击免疫**（事件取消 + 无敌标记） | `modulargolems:immunity`（`l2complements:eternium` 材质给） | **只有 `bypasses_invulnerability`**；反取消**无效** | ✅ 做成「湮灭」特技 |
| **减伤属性 / 血量地板** | `l2damagetracker:damage_reduction` | **`bypasses_invulnerability`**（实测 ×9.3） | ✅ 同上 |
| **护甲** | 41 护甲 / 30 韧性 | `bypasses_armor`（实测 ×5.1） | ✅ 同上 |
| **保护附魔 / 抗性 / 效果** | 精英 `enchantLevel:30`、Resistance | `bypasses_enchantments / resistance / effects` | ✅ 同上（对无附魔目标 ×1.0，纯保险） |
| **每击上限** | `damage_cap`、使徒限伤 | **多段**（+破无敌帧） | ✅ 「破帧」效果 |
| **固定值减法** | `modulargolems:coating`（−1/级）、`damage_absorption` | **单次更大**（多段反而更亏） | ⛔ 不加，改数值时避免多段依赖 |
| **外层钳制（无 tag 口）** | GoetyRevelation 的 `Math.min(onLivingDamage, 20)` | **管线外写血** | ⚠️ 只做「处决档」 |
| **速率门 / 无敌帧** | 使徒 hitCooldown、`invulnerableTime` | 写血 / 写无敌帧字段 | ✅ 「破帧」+ 处决档 |
| **被减免后的兜底** | —— | **END 层返还**（生态里独有；作用范围仅限 **l2 管线内**，见 §5 注） | ✅ 扩成升级线 |

---

## 2. 防御分类学：七层拦截点

按时间顺序，每一层都有明确的源码位置。**"能不能绕"取决于它拦在哪一层，而不是它叫什么名字。**

| # | 拦截点 | 判据（怎么认出它） | 破法 |
|---|---|---|---|
| 1 | `LivingAttackEvent` 取消 | "被取消"计数上涨、管线 0 条 | 选对 tag（`forge:is_magic` / `is_fire` / `is_projectile` 决定会不会被取消）；`postAttack` 里 `setCanceled(false)` 可反取消 |
| 1b | **事件外第二层**（`isInvulnerableToExtra`） | 事件取消后 `hurt()` 立刻 return false、`actuallyHurt` **根本不跑**；只有反取消**成功之后**才轮到它拦人 → 同样是"管线 0 + 掉血 0" | **只能靠 tag**：`bypasses_invulnerability`。反取消在这里无用（实测 28/28 全灭） |
| 2 | 护甲 / 韧性 | `CombatRules` 上限：armor ≥ 20 点即 −80% | `bypasses_armor` |
| 3 | 附魔 / 抗性 / 效果 | Protection、Resistance | `bypasses_enchantments` / `resistance` / `effects` |
| 4 | l2 dealt 累加器 | 减伤属性、`meror_defense` | `bypasses_invulnerability`（这些实现都检查它）；或 END 层返还 |
| 5 | `LivingDamageEvent`（默认优先级） | 每击上限、`damage_cap` | 多段（每段各吃一次上限）；`bypasses_invulnerability` 若实现检查了它 |
| 6 | **包住整个伤害事件的外层钳制** | `fin` 很大但 `掉血` 卡在常数（如 ≈20） | **管线内无解** → 写血 / 改属性 / 不打 |
| 7 | 速率门 / 无敌帧 | "重发"远大于"管线"、`未落地` 计数上涨 | 写 `invulnerableTime = 0`；`bypasses_cooldown`；写血 |

### 关键源码出处

```
// 1) 整击免疫的两层墙（决定了"反取消"在本家身上无效）
modulargolems ImmunityModifier:18            if (!source.is(BYPASSES_INVULNERABILITY)) event.setCanceled(true);
AbstractGolemEntity:1217                     public boolean isInvulnerable() { return hasFlag(GolemFlags.IMMUNITY); }
GuardedEntity:51                             isInvulnerableToExtra = isInvulnerable() && !source.is(BYPASSES_INVULNERABILITY)

// 1a 与 1b 的先后（这道顺序决定了"反取消"能走多远）
LivingEntity.hurt                           if (isInvulnerableTo(source)) return false;
                                            else if (!ForgeHooks.onLivingAttack(...)) return false;   ← 事件取消就在这里 return
GuardedEntity:55-57                         protected final void actuallyHurt(source, amount) {
                                                if (!this.isInvulnerableToExtra(source)) { ... }       ← 反取消成功后才轮到它
                                            }
注意：Entity.isInvulnerableTo 读的是**裸字段** f_19840_，而 AbstractGolemEntity:1217 覆写的是**方法**，
      两者不同 → 事件确实会发出来（取消计数可见），不是"事件都没发"。
      另外已核实：dev/ 下没有任何代码调用 setInvulnerable 给傀儡设裸字段。

// 2) 减伤属性 / 血量地板（都检查 bypasses_invulnerability）
l2damagetracker GeneralAttackListener:109-120    if (!source.is(DamageTypeTags.f_268738_/*BYPASSES_INVULNERABILITY*/)) {
                                                     addDealtModifier(multAttr(damage_reduction));      // :113
                                                     addDealtModifier(add(-damage_absorption));          // :119
                                                 }
jerotes_village_golems MerorDefenseModifier:30   if (!source.is(f_268738_)) { addDealtModifier(multTotal(0.8^level)) }   // :35
modulargolems DamageCapModifier:21               if (!source.is(f_268738_)) { 每击上限钳到 (2−0.2lv)×0.1×maxHP }
（同上：l2hostility 的「适应」AdaptingTrait 也用 f_268738_/f_268437_ 两个 tag 一起把关）

// 3) 血量地板（动态减伤）与"force"的关系 —— 写血要 force=true
GuardedEntity:343-350        set(): ans = rate > 0 && !force ? max(amount, baseline) : amount;
GuardedEntity:337-341        start(): baseline = amount − maxHealth × dynamicReductionCap(0.2)
GuardedEntity:255-259        public setGuardedDataImpl(float, boolean force, boolean repair)  ← 公开 API

// 4) 无敌帧（i-frame）语义
LivingEntity.hurt:1101-1115  if (invulnerableTime > 10 && !BYPASSES_COOLDOWN) { if (amount <= lastHurt) return false; actuallyHurt(amount - lastHurt); }
SRG 映射：f_19802_ = invulnerableTime（已用 srg_to_official 表核实）

// 5) l2 自带的官方 tag 组合（我们照抄，不发明）
l2damagetracker L2DamageTypes:41-43          BYPASS_INVUL = { bypasses_armor, bypasses_enchantments,
                                                              bypasses_resistance, bypasses_invulnerability, bypasses_effects }
                                             （相邻还有 BYPASS_MAGIC = 后三项）

// 6) 管线顺序（决定"我们的 END 返还跑在谁之后"）
l2damagetracker AttackEventHandler:186-188   onDamagePost(LivingDamageEvent) 注册在 EventPriority.LOWEST
l2damagetracker AttackCache:76-83            pushDamagePre 最后一句 event.setAmount(damage)
modulargolems ModifierEventListeners:98-101  onDamaged(LivingDamageEvent) 默认优先级（早于 LOWEST）
DamageAccumulator:81-82                      冻结后 addHurtModifier/addDealtModifier 抛 "modify damage only on event."
```

---

## 3. 实测数据

测试台口径：`档位@杠杆@目标`，每档 20 击；`通过率 = 真实掉血 / 打出去的伤害量`（不依赖 l2 是否上报），
`kept = fin / base`（管线视角）。测试武器为 `golems_arsenal:example_weapon`（+7 / +20%，玩家与傀儡都可用）。

### 3.1 tag 档位的边际收益（目标：普通 `metal_golem`，满蓄力，量 9.60/击）

| 档位 | 管线 | fin | 通过率 | 相邻档增益 |
|---|---|---|---|---|
| `plain` | 20 | 0.21 | **2.1%** | — |
| `armor` | 20 | 1.03 | **10.7%** | **×5.1**（护甲那层，正好 80% 上限） |
| `armor_ench` | 20 | 1.03 | 10.7% | ×1.00（该目标无保护附魔） |
| `pierce4` | 20 | 1.03 | 10.7% | ×1.00（该目标无抗性/效果层） |
| `true_edge` | 20 | **9.60** | **100%** | **×9.3**（＝`damage_reduction` + 血量地板那一层） |
| `true_edge_cd` | 20 | 9.60 | 100% | ×1.00（单段测不出冷却） |

**要点**：挡在面前的其实只有两层——**护甲**与**"护甲之后、`true_edge` 之前"的那一层**；
`bypasses_enchantments/resistance/effects` 在无附魔目标上完全无效（对穿甲玩家、对 `enchantLevel:30` 的精英才值钱）。

**×9.3 的归因（重要，且不可迁移）**：那一层的候选有三个，**不能笼统写成"`damage_reduction` + 血量地板"**——

| 候选 | 源码 | 乘数是否可迁移 |
|---|---|---|
| `l2damagetracker:damage_reduction` | `GeneralAttackListener:113` `multAttr` | 是（乘区）。但三件 `meror_defense` 护甲最多也只到 ×1.95，**撑不起 ×9.3** |
| **`l2damagetracker:damage_absorption`（最吻合）** | `GeneralAttackListener:119` `add(-val)` + `:120 nonlinearMiddle(943, e -> max(0,e))` | **否**。固定值减法：1.03 这种小伤害直接归零，`true_edge` 一开 tag 就全恢复 → 正好是 ×9.3 的样子 |
| **血量地板（`dynamic_reduction` 的 baseline 硬钳）** | `GuardedEntity:106-108` `takeDamage` → `setGuardedDataImpl(..., force=false)` → `GuardedEntity:343-350` `ans = rate > 0 && !force ? max(amount, baseline) : amount` | **否**。硬钳制不是比值：**任何单次伤害都推不过 baseline**，用 ×N 描述不了 |

**判决性实验**（同档位、只改伤害量）：`kept` 从 0.70 抬到 1.00 ⇒ 固定值减法；恒定 0.70 ⇒ 乘区；
卡住不动且数值等于 `baseline / maxHP` ⇒ 血量地板。（测试台可用"傀儡持武器打"或 `restitution` 把量推大来做。）

**这条区分直接影响正式版**：若真是固定值减法，那"×9.3"只在**小伤害**上成立；我们会打出的高伤害本来就能碾压它，
不需要为它专门加 tag。

### 3.2 整击免疫实测（目标：带 `modulargolems:immunity` 的 `humanoid_golem`）

| 档位 | 重发 | 管线 | 取消 | 通过率 |
|---|---|---|---|---|
| `plain` / `armor` / `armor_ench` / `pierce4` | 20 | **0** | 0~3 | **0.000** |
| `true_edge` / `true_edge_cd` | 20 | 20 | 0 | **0.700** |

- 前四档：**伤害 0**，但"取消"计数只有 0~3 —— 因为多数命中死在**第 1b 层**（不发事件），
  事件层的计数器和 l2 管线都看不到；**只有"通过率"抓到了它**。
- `true_edge` 能进去但仍被吃掉 30%（`drop == fin`，所以**不是血量地板**，而是管线内一层不吃 tag 的减伤）。
  **未定案**，嫌疑：`modulargolems:coating`（`CoatingModifier:26`，固定值 −1/级）、`damage_absorption`、某个 ×0.7 乘区。

### 3.3 使徒：限伤与减半（`goety:apostle`）

| 现象 | 数据 | 对应源码 |
|---|---|---|
| 后置减伤全被 `true_edge` 穿过 | `kept = 1.0000` | `Apostle.actuallyHurt:953-956` 的 `ApostleDamageCap` **有** tag 检查，被跳过 |
| **限伤卡在 ≈20** | `fin 149.4 → 掉血 18.9` | 附属（GoetyRevelation）`LivingEntityMixin`：`Math.min(ForgeHooks.onLivingDamage(...), APOLLYON_HURT_LIMIT)`，**无 tag 检查** |
| 写血（普通版）有效 | 管线 2.4/击 → 写血 8% 得 **28.0**、40% 得 **94.64**，`writeApplied` 全成功 | `GuardedEntity`/`LivingEntity.setHealth` 路径 |
| 写血（附属加强版）被拒 | `writeApplied 1 / writeRejected 50` | 附属自己的防御代码（**不深究**，按"这类 boss 会拒"处理） |
| **傀儡伤害减半** | —— | `Apostle.hurt:928-933`：附近有非创造玩家且**攻击者不是玩家** → `trueAmount /= 2` |
| 打够次数会传送走 | 6 段/挥时 157 重发只有 27 进管线 | `Apostle.hurt:920-922` → `teleport()` |

### 3.4 多段 vs 速率门

| 模式 | 目标 | 重发 | 管线 | 结论 |
|---|---|---|---|---|
| 6 段/挥（`extraHits=5`） | 使徒 | 157 | 27（**17%**） | 多段在**自带速率门**的目标上大幅浪费 |
| 单段（`extraHits=0`） | 使徒 | 51 | **51（100%）** | 单段稳定进管线 |
| 6 段/挥 | metal / humanoid / dog golem | 60 / 40 / 13 | 全部 | 普通目标上多段正常落地 |

### 3.5 其它已记录现象（未定案，留档）

- `dog_golem` 的 `kept` 恒为 **1.5000**（×1.5 放大）；试验假人也出现过 ×1.5。
- 部分目标 `kept > 1`（metal golem 1.457、apostle 1.367）：说明有**放大层**存在。
- `avgAmount` 与 `avgBase` 数量级不一致（17.3 vs 141.7）：**大概率是同 tick 内 l2 伤害缓存被其它攻击者共用**，
  属测试口径短板；看到这种行就别用它的 `base/fin`。
- 试验假人（`dummmmmmy:target_dummy`）会出现"伤害算出来了但半点血不掉"，**不可作为靶子**。

---

## 4. 生态对照：别人怎么做的

| 机制 | 我们的杠杆 | 生态里的对应物 | 实现方式 | 差异 |
|---|---|---|---|---|
| 破护甲/破魔防 | tag 档位 | mgdp `end_void`（**仅末地**）/`dementor`/`end_of_beginning` | l2 的 `CreateSourceEvent.enable(BYPASS_ARMOR / BYPASS_MAGIC)` | mgdp **全程不用** `bypasses_invulnerability` → 打不动免疫族 |
| 多段落地 | `true_edge_cd`（tag） | mgdp `quick_strike`；JVG 莫厄武器 | **写 `target.invulnerableTime = 0`**（`QuickStrikeModifier:24`、`RefineMerorWeaponItem:30`）+ 攻速间隔 mixin | 写字段路线更强：不挑类型、对后续所有攻击者有效 |
| END 层保底 | `DamageRestitutionListener` | **无** | mgdp 全 247 类零处 `DamageModifier/nonlinear*` | 我们独有 |
| 反取消 | `postAttack` 里 `setCanceled(false)` | **无** | mgdp `ProfaneStealHandler` 只在 HIGHEST/LOWEST 快照比对"偷增益" | 我们独有；但被第 1b 层克制 |
| 摘保护 | 摘附魔 NBT | mgdp `disarm`（等级=每次卸下件数，含 Curios 反射） | `setItemSlot(EMPTY)` + 掉落在傀儡身旁 | mgdp 更狠（整件扒）；恶意附属味道最重 |
| 绝对量伤害 | —— | mgdp `executioner`（+100% 目标生命上限）/`wither_extinction`（100% 上限，1 分钟 CD）/`the_apocalypse`（+2000%） | `setAmount(amount + targetMax)` / 以 `getMaxHealth()` 为伤害量 | 强度高但有条件，且仍走正常管线（会被免疫/tag 层挡） |
| 写血 | `write_health` | ArcaneVortex `TrueDamageHelper`（多路写血 + Agent）、`HealthLockManager` | 反射/Agent/篡改 SynchedEntityData | 傀儡侧有 **public** `setGuardedDataImpl`，不需要反射 |
| 防御侧限伤 | —— | mgdp `void_echo`（`max(20, maxHP×0.2)`）/`focused_defense`/`last_line` | —— | 可作我们"传奇对策"的对标 |

### 4.1 潘多拉之咒（`curseofpandora-2.4.26`，xkmc 出品，`l2hostility` 为可选依赖）

它验证了一件对我们很关键的事：**`END_NONLINEAR`（`nonlinearFinal`）这一层是"谁都能改"的公用写回点，靠 priority 分先后。**

| 类/行 | 写法 | 含义 |
|---|---|---|
| `RealityTrait:59` | `cache.addDealtModifier(DamageModifier.nonlinearFinal(12346, e -> 0.0F))` | **把伤害直接归零**（无任何 tag 检查） |
| `CurseOfPrudenceItem:140` | `nonlinearFinal(9000, e -> Math.min(e, maxDamage))` | **每击上限**，实现方式就是 END 层的 `min` |
| `CurseOfPrudenceItem:131` | `multTotal(pow(damageFactor, count))` | 按层数叠乘 |
| `AbyssalCrown:68` | `event.enable(DefaultDamageState.BYPASS_MAGIC)` | 走 l2 state 的破魔（和 mgdp 一个路子） |
| 全局 | `bypasses = 0` 命中 | **整个 mod 不用 `bypasses_invulnerability`**（与 mgdp 一致） |

**推论（直接影响我们的保底设计）**：

- 我们的测试台用 `nonlinearFinal(9400)`：**低于** `RealityTrait` 的 12346 → 它能把我们的返还**再清零**；
  **高于** `CurseOfPrudence` 的 9000 → 我们能**盖掉它的每击上限**。
- 也就是说：**"保底"和"限伤"本质是同一个写回点的优先级之争**。正式版若要让保底"算数"，
  必须选一个足够高的 priority（并接受这可能与别的 mod 打架）；
  同时要意识到：**别人也能用同一个点把我们的伤害归零，且不看任何 tag** —— 这是第 6 层之外的第四种"关不上的门"。
- 反过来说，`RealityTrait` 那种"END 层归零"是**管线内唯一能无视一切 tag 的防御写法**，
  它比 `Math.min(..., 20)` 更彻底（归零 vs 限伤），代价是必须靠 priority 抢。
- **我们正式版的现状**：`DamageRestitutionListener:115` 的 `ORDER_PRIORITY = 9000`
  → 与 `CurseOfPrudence` 的 9000 **打平**（同 priority 谁先谁后取决于注册顺序，等于不确定），
  且输给 `RealityTrait` 的 12346。建议改成 **9500**：确定性盖过"每击上限"类，
  但**不越过 12346**——即"我们抢'限伤'这一层，不抢'绝对归零'那一层"（与 §6 第 7 条拒绝 invulnerability 的立场一致）。

### 4.2 潘多拉之咒的「触发思路」可借鉴清单

> 逐条调研（7 个诅咒、六大系列、复活机制、未确认项）见 [`CURSEOF-PANDORA-SURVEY.md`](./CURSEOF-PANDORA-SURVEY.md)。

| 手法 | 它怎么做的 | 我们可以怎么用 |
|---|---|---|
| **1~2 tick 短租约 token** | `BaseTickingToken:13-29`：`update()` 把 `life=2`，`tick()` 里 `life--`，≤0 自动 `removeImpl`；由物品每 tick 续租（`ITokenProviderItem:49-53`） | 傀儡"部件/烙印"效果照抄：拆件、换核心、死亡后 **2 tick 内自动清干净**，不用在 N 处写清理代码 |
| **先全量抵消同类属性，再写反向量** | `AttributeLimiter:29-46` + `CursePandoraUtil:47-74`：把 `ADDITION`/`MULTIPLY_BASE` 上的加成全量抹掉再加回目标值；`MULTIPLY_TOTAL` 交给 `ClipMultiplierData:18-29` 做"超上限裁到上限／低于下限给 bonus" | 傀儡"攻速/护甲封印"型改造：**兼容任何第三方加成**，不会和别的 mod 打成一团，且完全可逆 |
| **触发时机分层** | `PandoraAttackListener` 五阶段各司其职（`:25-33`/`:69-94`/`:96-118`/`:120-149`/`:45-67`）：免疫用 `setCanceled`、限伤用 END 层、补下限用中段 | 写新特技时先问"这件事该在哪一层"，而不是一律塞进 `LivingHurtEvent` |
| **三种阈值范式** | 目标低血 `EvilSpiritCurse:77`（<20% 上限）／单次大伤 `CurseOfTension:152`（≥自身最大血 10%）／双方当前血对比 `CurseOfPrudence:138` | 三套现成模板：**处决**（目标低血）、**反制**（挨大伤才启用）、**对等**（只在血比对手多时加成） |
| **免死总线（最值得学）** | `l2dt` 在**原版 `checkTotemDeathProtection` 的 HEAD** 短路（`mixin/LivingEntityMixin:18-28`）→ `TotemHelper:26-54` 遍历双手 + 所有 Curio 槽 → `L2Totem.allow/trigger`；默认 `trigger` 就是"1 血 + `removeAllEffects()` + 三药水"（`L2Totem:24-32`） | **做傀儡免死不需要自己写 mixin**：实现 `L2Totem` 接口即可，自动与不死图腾、别人的图腾**共用同一套排序与短路语义**；复活顺手清 debuff 也是白送的 |
| **一次性保命但不销毁物品** | `getCooldowns().addCooldown(this, 600)`（`AngelicProtection:42`） | "核心碎裂保命"：不打碎核心，只进 30 秒冷却 + 给一段免疫窗口 |
| **延迟结算 / 延迟回血** | `Frame` 记位置+伤害，20 tick 后按 AABB 并集结算（`ShadowConsolidation:137-178`）；回血延后一 tick（`ShadowConvergence:65-71`） | "连击累积 → 1 秒后引爆"或"伤害转护盾"：保住打击感，避开同 tick 递归伤害 |
| **UI 与逻辑解耦** | 层数走真实属性，显示走"仅渲染"的 `FakeRenderEffect`（`CoPEffects:13-28`，注释明写 For render only） | 傀儡"充能层数"：数值走属性、显示走无逻辑 `MobEffect`，省掉自定义同步包 |
| **对价结构写进同一个物品** | `MAGIC_FACTOR −1.0` 换近战/暴击/护甲（`BarbaricInstinct:30-34` 等 5 处） | "重装/钝器"升级件：以"魔法伤害为 0"换物理强度，一句话说清取舍 |
| **属性门（本 mod 的地基）** | 几乎所有进阶物品 gate 在 `REALITY ≥ N`（0~7，`CoPConfig:98-560`）；同属性作为恶意词条时让实体**免疫指数更低者的伤害**（`CoPTraits:20-22` + `RealityTrait:59` 归零） | **用"数值门"替代"tag 门"**：绝对防御但可刷可破、对创造模式无击杀风险 —— 见 §6 第 1 条 |

---

## 5. 四个测试杠杆的最终结论

| 杠杆 | 强度（实测） | 唯一弱点 | 结论 |
|---|---|---|---|
| **tag 档位（真伤五件套）** | 护甲 ×5.1；护甲之后那一层 ×9.3（很可能是 `damage_absorption` 固定值减法，见 §3.1）；免疫族唯一解 | tag 是注册表级、要预注册；对"无 tag 口的外层钳制"无效；`bypasses_invulnerability` **我们不采用**（见 §6 第 7 条） | ✅ 只做"无 invulnerability"的子集 |
| **END 层保底** | 能把被削掉的部分按比例还回来；对 `damage_cap`/`meror_defense`/`damage_reduction` 都有效 | **作用范围只是"l2 管线内的上限"**（见下注）：上限是 `base`；管线之后还有 baseline 硬钳与第 6 层，它救不回来 | ✅ 立项 |
| **反取消** | 能救回"纯事件取消"的命中 | 对本家 `immunity` 的**第 1b 层无效**（实测 28/28 仍全灭） | ⚠️ 保留为保险 |
| **写血** | 管线 2.4 → 28.0 / 94.64（×11.7 / ×39） | 附属加强版 boss 会拒；对玩家用是恶性工具 | ⚠️ 只做「处决档」 |

**注（"END 层保底"的准确边界）**：`nonlinearFinal = Order.END_NONLINEAR` 是 **l2 `DamageAccumulator` 内的最后一层**，
不是"伤害流程的最后一层"。实测顺序是：

```
GuardedEntity.actuallyHurt
  → ForgeHooks.onLivingHurt         (hurt 累加器)
  → getDamageAfterArmorAbsorb       (护甲/韧性)
  → getDamageAfterMagicAbsorb
  → ForgeHooks.onLivingDamage       (dealt 累加器 ← END_NONLINEAR 在这里)
  → hurtFinal → hurtFinalImpl → takeDamage → setGuardedDataImpl(..., force=false)   ← baseline 硬钳在 END 之后
```

所以：**END 层能反转"护甲之后、写回血量之前"的所有削减（`damage_reduction` / `damage_absorption` / `meror_defense` / `damage_cap`），
但改不动 ①血量地板的硬钳（第 6 层之一）②外层钳制 ③写血路径。** 文档里一律不要写成"伤害的最后一层"。

---

## 6. 正式内容立项建议

按"到哪里都好使"排序：

1. ~~**「湮灭」特技 + 新伤害类型 `true_edge`（P0）**~~
   **⛔ 已按"压制强度、不破坏环境"的定位取消**（2026-10 决定）。理由：这套 tag 组合（尤其
   `bypasses_invulnerability`）会让"减伤"这一整个设计维度失效，与本整合包刻意压强的方向相反。
   测试台里的 `true_edge` 档位保留，只用于量数据，不进正式内容。
   **替代路线：数值门**（照潘多拉之咒 `REALITY` 的做法，可刷可破、创造模式零风险）——见 §4.2。
1b. **END 层返还改成"一个属性 + 数据包"（P0，已实现）**
   - 新增属性 **`golems_arsenal:end_restitution`（0 ~ 0.8）**，挂在三种傀儡实体上：
     所有"多个 END 层返还来源"（升级件 / 材质 / 其它 mod）都写进这一个属性，
     由监听器统一夹到 **0.8** 上限 —— 不可能再出现"两件装备各 0.5 = 无视所有减伤"。
   - 武器侧改为**数据包驱动**：`data/<ns>/golems_arsenal_restitution/*.json`
     （`{"values":[{"item":"...","value":0.15,"max":0.30}]}`）。`max` 可选，填了就按键刃的
     处决进度插值。**任何 mod 的武器都能被数据包授予返还，不需要改代码**。
   - 出厂值**整体调低**：键刃 0.30→0.60 改为 **0.15→0.30**，能量武士刀 0.20 改为 **0.10**。
   - 代码位置：`init/ModAttributes`、`compat/l2damagetracker/RestitutionData`、
     `compat/l2damagetracker/DamageRestitutionListener`（`END_RESTITUTION_CAP = 0.8`）。
1c. **武器的「莫厄伤害」标记（P0，已实现，纯数据包）**
   查证结论：`jerotes`（Jerotes 实用仓库）的"莫厄伤害"判据有两条，**都不是伤害类型 tag**：
   - 近战武器在 **物品 tag `jerotes:meror`** 里（`EntityAndItemFind.isMerorWeapon`，
     `EntityAboutEvent:153`）；
   - 攻击者实体在**实体类型 tag `jerotes:meror_damage`** 里（`isMerorAttackEntity`）。
   而 JVG 的「莫厄防护」（`MerorDefenseModifier:30-36`）就是"非莫厄伤害 → ×0.8^level"。
   → 所以"让武器添加莫厄伤害"= **把武器写进 `jerotes:meror` 物品 tag**，已提供一个桥接：
   我们自己的 tag `golems_arsenal:meror_weapons` + `data/jerotes/tags/items/meror.json`
   里 `#golems_arsenal:meror_weapons` 一条引用。以后要授予哪把武器，改我们自己的 tag 即可
   （没装 jerotes 时那个桥接文件完全惰性）。
2. **「破帧」效果（P0，可与 1 合并）**
   命中后写 `target.invulnerableTime = 0`（mgdp/JVG 同款）。好处是不挑伤害类型，且多傀儡组队互相收益。
   护栏：只对敌对目标生效（避免 PvP 里变成拆对手无敌帧的工具）。
3. **保底线扩成升级（P0）**
   把 `DamageRestitutionListener` 的 p 值从"两把武器硬编码"扩成一档升级（建议 25%~40%）。
   保持"数值写在代码里、不做配置项"的既有哲学。
   **注意边界**：它是 l2 管线内的上限，改不动血量地板硬钳与外层钳制（§5 注）；
   另外 l2 的 `onDamageFinalized` / 本家 `GolemAttackListener` 在累加器算完之后还会动数值，
   所以**要把"最终写回"这一步做在 `finalizeHurtTarget` 之后**（已列入 §7 待验证）。
4. **摘附魔升级（P1）**
   照 `EnchantmentDisabler` 的做法摘目标装备的 `Enchantments` NBT，到期还原（**不需要 mixin**）。
   护栏：限件数、只对非玩家、附魔缓存可还原；不要学 `disarm` 直接扒装备（那是 grief 边界）。
5. **处决档写血（P2）**
   仅在 `目标生命 < X%` 时执行；对傀儡走 public `setGuardedDataImpl(next, true, false)`，
   对普通生物走 `setHealth`；记录"被拒"次数作为遥测。
6. **免疫族怎么打（P2，替代方案）**
   既然不加 `bypasses_invulnerability`，`modulargolems:immunity` 的傀儡在管线内就是**打不动**。
   可行路线只剩：①处决档写血（管线外）②改属性（`trim_max_health` 一类，需谨慎）③不打（换个目标）。
   建议：把它明确写进内容说明（"免疫目标需要特殊手段"），别偷偷留后门。
7. **免死 / 复活走 l2 的图腾总线（P1）**
   实现 `L2Totem` 接口挂到物品上即可 —— l2damagetracker 已在**原版 `checkTotemDeathProtection` 的 HEAD**
   注入短路（`mixin/LivingEntityMixin:18-28` → `TotemHelper:26-54` 遍历双手 + Curio 槽）。
   **好处**：不必写 mixin、与不死图腾及别人的图腾共用同一套排序语义、默认 `trigger` 自带
   "1 血 + `removeAllEffects()` + 三药水"（复活顺带清 debuff）；用 `getCooldowns().addCooldown` 限频，
   **不销毁物品**（`AngelicProtection:36-52` 就是范例）。
8. **END 层 priority 的立场（P0，一行改动）**
   `DamageRestitutionListener:115` 现在是 9000 → 与潘多拉 `CurseOfPrudence` 的 9000 **打平（不确定）**。
   建议改 **9500**：确定性盖过"每击上限"类，但**不越过 12346**——我们抢"限伤"这一层，
   不抢"绝对归零"那一层（与第 7 条拒绝 `bypasses_invulnerability` 的立场一致）。
   "提高下限 / 斩杀"另走 `nonlinearMiddle`（潘多拉的用法：`AngelicPunishment:97` 的 `71`），与 END 层限伤不冲突。
9. **组织方式借鉴（P2）**
   ①"短租约 token"（`BaseTickingToken:13-29`）：拆件/换核心后 2 tick 内自动清理；
   ②"先全量抵消同类属性、再写反向量"（`AttributeLimiter:29-46`）做封印型改造；
   ③触发按五阶段分层（`PandoraAttackListener`），别一律塞 `LivingHurtEvent`。
10. **明确不做**
   - **不给正式内容加 `bypasses_invulnerability`**：它同时会跳过 `Player.hurt` 里创造/旁观的无敌门
     （`abilities.invulnerable && !source.is(BYPASSES_INVULNERABILITY)`），**有击杀创造模式实体的风险**；
     而且它把"所有检查这个 tag 的减伤"一次性清空，强度不可控。测试台里保留它只是为了量数据。
   - 不追求突破"无 tag 口的外层钳制"（GoetyRevelation 那种 `Math.min(..., 20)`）——那是设计者刻意关的门；
   - 不对玩家摘装备/写血；
   - 不引入反射/Java Agent 那套（傀儡侧有合法 API，没必要）。

---

## 7. 待定问题（下一轮可选）

1. **×9.3 那一层是固定值减法还是血量地板？** 判决性实验见 §3.1（只改伤害量，看 `kept` 走向）。
2. **`END` 层是不是"最后写回"？** 已有反例：l2 的 `onDamageFinalized`（`AttackCache:81` 的回调）
   与本家 `GolemAttackListener`（`dev/mg-src/.../events/GolemAttackListener.java`）都在累加器之后动数值；
   潘多拉之咒更直接——`RealityTrait:59` 用 `nonlinearFinal(12346, e -> 0)` 把伤害归零（**priority 高于我们的 9400**）。
   → 保底实现要么抬高 priority，要么改到 `finalizeHurtTarget` 之后；两条都要先做实验验证覆盖关系。
3. `true_edge` 在永恒傀儡上剩的那 **30%** → 对目标跑 `/ga debug inspect`（看 `coating` / `damage_absorption`）。
4. 扫档时 `plain/armor/armor_ench/pierce4` 的"取消"计数为何只有 0~3（反取消那次实测是 48/48、28/28，
   说明计数器本身是好的）→ 属测试台口径问题，**判决性指标仍是通过率**。
5. `dog_golem` 恒定的 **×1.5** 从哪来？
6. `kept > 1` 的放大层（metal golem 1.457 / apostle 1.367）来源？
7. `avgAmount` vs `avgBase` 不一致 → 给命中加"金额校验"（同 tick 缓存共用检测）。
8. **潘多拉之咒的覆盖关系**：见 §4.1。要测的是**同一目标、同一档位**下，
   潘多拉的 `RealityTrait`（归零）与 `CurseOfPrudence`（限伤）分别能不能盖掉/被盖掉我们的返还；
   测试台可用 `restitution` 的 priority（当前 9400）做对照。已反编译到 `cop-src/`（142 个文件）。

---

## 附录：命令速查（私有测试台）

```
/ga debug enable                             总开关（默认关，服务器启动自动复位）
/ga debug help                               三步流程 + 注意事项
/ga debug inspect                            看向生物：登记表 / 护甲 / 减伤属性 / 血量地板
/ga debug sweep start 20                     自动扫档：plain→armor→armor_ench→pierce4→true_edge→true_edge_cd
/ga debug tier <档位> / lever <名字> <true|false>
/ga debug extra <0-8> / restitution <0-1> / strip <件数> [秒] / writepct <0-1> / writebelow <0-1> / trim <0-1>
/ga debug report                             打印汇总并落盘 config/golems_arsenal/debug/report-*.json
/ga debug export / clear / reset
```

杠杆清单：`uncancel` `floor_attack` `floor_hurt` `floor_dealt` `distinct_ids` `same_tick`
`strip_enchants` `clear_iframe` `write_health` `trim_max_health` `light_pillar_onhit`

---

## 附录 B：多段伤害的规范写法（L2 作者口径）

**来源**：L2 作者（lcy0x1）在群里的两次判断，以及 `Youkai-Homecoming` 的实际做法（反编译 `AFoP` 包
`youkaishomecoming-2.6.9.jar` 核实）。

### B.1 作者原话

> "你那个根本就不叫独立无敌帧。"<br>
> "我写过独立无敌帧，在弹幕里面。" / "只有自己的伤害能写。"<br>
> "沙暴不是你自己的 → 所以写不了 → 除非 mixin → 但是 mixin 容易炸 → 所以别写了。"<br>
> "**正确的写法是让伤害类型持有 `bypass_cooldown` 标签，然后自己维护一个无敌帧。**"

### B.2 由此得出的规范（后续所有"多段"内容都按这个写）

1. **自己的伤害类型**：每一段都用我们注册的 damage type，并把该类型加入
   `data/minecraft/tags/damage_type/bypasses_cooldown.json`。标签负责"进得去"（原版帧判定对它跳过）。
2. **自己的帧**：段与段之间的节流由**我们自己的计时/排程**负责 —— 存在施放者身上、按
   `目标 UUID + 升级 id` 分桶，或直接由该技能的发射排程承担。**永远不去写 `target.invulnerableTime`。**
3. **不引入 mixin**：作者判断 mixin 易碎；只要伤害"能归属到自己"，纯 API 就够。
   （mixin 只在"伤害无法归属"时才被迫使用 —— 那不是我们的情况。）
4. **不追求"零足迹"**：`bypass_cooldown` 落地的那一次仍会写 `lastHurt`/`invulnerableTime`
   （原版 else 分支的固有行为），这是可接受的上限；真要连 `lastHurt` 都不写只能 mixin，**不做**。

### B.3 现状对照（本仓库）

| 伤害类型 | 在 `bypasses_cooldown` 里 | 清帧 | 结论 |
|---|---|---|---|
| `stance_magic` / `phoenix_fire` / `flame_fist` / `command_extra` / `mystic_blast` | ✅ | ❌ 不需要 | 规范形态 |
| `minecraft:mob_attack`（三连击三段） | ⛔ **不能加**（会变成"全世界近战都无视无敌帧"） | ✅ 仍清 | **唯一例外**：它必须走 `doHurtTarget` 才能吃到附魔/物品侧 on-hit；代码注释已写明取舍 |

### B.4 `Youkai-Homecoming` 的实际做法（佐证）

- 全 mod 只有一个类碰 `invulnerableTime`：`events/YHAttackListener`（l2 的 `AttackListener`，纯 API），
  而且是 **`+= (amplifier+1)*6`（加长目标帧、当限速闸用）**，不是私有帧表，也不是清帧；
- mixin 配置存在，但注入的全是**效果/机制**（`mixin/effect/LivingEntityMixin` 注入 `hasEffect` 与效果施加路径），
  **没有任何一个是无敌帧**；
- 弹幕的"独立帧"体现为**每发弹幕自带命中记录**（"只有自己的伤害才能写"）—— 与 B.2 的第 1、2 条同构。
