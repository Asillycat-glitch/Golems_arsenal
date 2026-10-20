# 潘多拉之咒（curseofpandora 2.4.26）诅咒 / 负面效果体系调研

> 对象：Minecraft 1.20.1 Forge，第三方 mod **反编译产物**，仅用于学习设计思路，**未向任何源码文件搬运其代码**。
>
> 材料与路径别名（下文一律用别名，替换即可定位）：
> - `⟨cop⟩` = `cop-src/dev/xkmc/curseofpandora/`
> - `⟨l2dt⟩` = `dev/l2dt-src/dev/xkmc/l2damagetracker/`
> - `⟨mc⟩` = `dev/mcinv/out/`（本项目内已有的原版方法反编译片段，用于确认 SRG 名语义）
> - 中文名来源：`curseofpandora-2.4.26.jar!/assets/curseofpandora/lang/zh_cn.json`（从 jar 内按字节取出后只读，未做文本往返）
> - 标签常量语义来源：`dev/mctags/out/DamageTypeTags.java`
>
> 未确认项集中列在 §5。

---

## 0. 先看骨架：这套体系是怎么跑起来的

三层结构，诅咒与"强力效果"共用同一套地基：

| 层 | 角色 | 关键文件:行 |
|---|---|---|
| 载体 | Curio 物品，tick 时向玩家身上的 `ConditionalData` 申请/刷新一个 token | `⟨cop⟩content/complex/ITokenProviderItem.java:49-53`（`curioTick` → `tick`）、`:34-36` |
| 生命周期 | token 用 `life` 做"1~2 tick 短租约"：每 tick 刷新为 2，停止刷新后 2 tick 内自动 `removeImpl` | `⟨cop⟩content/complex/BaseTickingToken.java:13-29` |
| 回调 | token 实现 `IAttackListenerToken`，由 `PandoraAttackListener` 在伤害管线各阶段遍历调用 | `⟨cop⟩event/PandoraAttackListener.java`、`⟨cop⟩content/complex/IAttackListenerToken.java:7-34` |
| 属性写回 | `AttrAdder`（直接加修饰符）/ `AttributeLimiter`（先抵消同类再加反向量） | `⟨cop⟩content/complex/AttrAdder.java:30-52`、`⟨cop⟩content/reality/AttributeLimiter.java:29-46` |

伤害管线派发点（全部在 `PandoraAttackListener`，`NO_SCALE` 标签是总闸）：

| 阶段 | 回调 | 文件:行 |
|---|---|---|
| `CreateSourceEvent` | `onCreateSource` | `:25-33` |
| `LivingAttackEvent` | `onPlayerAttacked`（目标是自己，可整体取消，取消即 return）、`onPlayerAttackTarget` | `:69-94`（早退 `:78-80`） |
| `LivingHurtEvent` | `onPlayerHurtTarget`、`onPlayerHurt` | `:96-118` |
| `LivingDamageEvent` | `onPlayerDamageTarget`、`onPlayerDamaged`（另有"带虚影标记者伤害 −20%"全局规则 `:125-130`） | `:120-149` |
| 结算后 | `onPlayerDamageTargetFinal`、`onPlayerDamagedFinal` | `:45-67` |
| 击杀 | `LivingDeathEvent` / `LivingExperienceDropEvent` | `⟨cop⟩event/PandoraEvents.java:52-58`、`:60-69` |

**要点**：同一件事写在哪个阶段，效果完全不同。`onPlayerAttackTarget` 取消事件 = 直接免疫；`multTotal`（HURT/DAMAGE 层）= 乘算但吃抗性；`nonlinearFinal`（END 层）= 最后一名改写者说了算。

---

## 1. 诅咒 → 效果对照表

### 1.1 现实七诅咒（`CurseOf*Item`）

| 类名（文件:行） | 中文名 | 效果 | 代价 / 副作用 | 触发时机 |
|---|---|---|---|---|
| `CurseOfInertiaItem` `⟨cop⟩content/reality/CurseOfInertiaItem.java:24` | 现实第一诅咒：囚笼 | 抹除**其他所有**攻击速度加成；若最终攻速 ≤ 0.5，额外 +80% 攻速 | 攻速被硬顶在 3.0（`CoPConfig:316-318`）；武器自带的专属 UUID 修饰符也在被抹范围 | Curio tick 持续重算（`:58-60` → `AttributeLimiter.java:29-46`） |
| `CurseOfProximityItem` `:30` | 现实第二诅咒：制限 | 抹除其他所有攻击距离加成，上限 6.0；若 ≤ 2.0 额外 +50% 距离 | 需要从主手物品反查 reach 修饰符 UUID 并单独放行（`:68-71`） | 同上 |
| `CurseOfFleshItem` `:24` | 现实第三诅咒：血肉 | 饱食度 ≥14 维持满 2 分钟后，+100% 生命上限 | 不满足时 `BonusMult(1.0)`，且**把当前血量硬压回上限**（`:66-68`） | tick 累加 `maintain`（`:88-100`） |
| `CurseOfMetabolismItem` `:32` | 现实第四诅咒：羸弱 | 饱食度每高于 16 一点 → +5% 攻击/移速；满饱食度再 +20% | 低于 16 每点 → **−5% 攻击、移速、攻速**；满饱食度会把所有移速减益一并抵消（`:111-118`） | tick 重算 `MULTIPLY_BASE`（`:84-103`） |
| `CurseOfTensionItem` `:39` | 现实第五诅咒：恐惧 | 命中给目标放"恐惧标记"，成熟后 10 秒内对其伤害 +20%/层，最多 5 层 | 单次受击 ≥ 自身最大血 10% → 3 秒内**完全无法对该生物造成伤害**（事件取消，`:168-178`），并清空你给它的标记（`:156`） | `onPlayerHurtTarget`（叠层）+ `onPlayerDamagedFinal`（惩罚）+ `onPlayerAttackTarget`（锁死） |
| `CurseOfPrudenceItem` `:42` | 现实第六诅咒：碎魂 | —（纯惩罚项） | 对同一目标连击 → 伤害 ×0.5^层数（上限 20 层，`:131`）；**对血量高于你的目标，单次伤害不超过其最大血 20%**（`:139-140`） | `onPlayerDamageTarget` |
| `CurseOfSpellItem` `:31` | 现实第七诅咒：咒缚 | 按附魔"等级权重 `Σ2^(lv-1)` / (魔力耐性+附魔价值)"算魔力负荷 | 负荷 >0 时：每 20 tick 自伤 1 点（`:88-90`）、受击伤害 ×(1+负荷)（`:94-106`）、造成伤害 ÷(1+负荷)（`:109-115`） | tick 节流 + 双向伤害阶段 |

### 1.2 其他 `*Curse` / `Cursed*` / 含诅咒语义的类

| 类名（文件:行） | 中文名 | 效果 | 代价 | 触发时机 |
|---|---|---|---|---|
| `EvilSpiritCurse` `⟨cop⟩content/sets/evil/EvilSpiritCurse.java:26` | 邪祟咒灭 | 对**血量 <20% 上限**的目标，魔法伤害 +100% | 仅魔法伤害、仅低血目标（条件极窄） | `onPlayerHurtTarget`（`:71-80`） |
| `CursedSoulCrystal` `⟨cop⟩content/sets/hell/CursedSoulCrystal.java:33` | 死灵魂核 | 被非亡灵攻击时，把这一击**原样转移**给周围 8 格内随机一只亡灵，自己免疫该击 | 8 格内必须存在亡灵（否则不生效）；200 tick 冷却（`:86-94`） | `onPlayerAttacked`（取消事件 + 延迟一 tick 转伤） |
| `CursedKarma` `⟨cop⟩content/weapon/CursedKarma.java:41` | 罪业 | 空挥发射诅咒燃魂爆炸剑气 | 武器，非饰品 | 左键空挥（`EmptyClickListener`） |
| `BaseCurseCurio` `⟨cop⟩content/complex/BaseCurseCurio.java:7-11` | — | **注意**：它只是一个 11 行的 record（`Item`+`ItemStack` 实现 `ICurio`），**不是诅咒类基类**；真正的基类是 `ITokenProviderItem` / `ISlotAdderItem` | — | — |

### 1.3 ★ 强力复活（3 项，全部走 `L2Totem` 免死总线）

| 类名（文件:行） | 中文名 | 复活后给什么 | 触发门槛 | 冷却 |
|---|---|---|---|---|
| **`AngelicProtection`** `⟨cop⟩content/sets/angle/AngelicProtection.java:36-52` | 天使加护 | **回满血** + 清空全部药水效果（含 debuff） | 现实指数 ≥6，且（戴了神罚之印 **或** 露天） | 600 tick = 30 秒 |
| **`AbyssalWill`** `⟨cop⟩content/sets/abyss/AbyssalWill.java:96-112` | 深渊意志 | **保留 1 血** + 清空全部药水效果 + 200 tick `abyssal_protection`（该窗口内免疫一切"穿透附魔"的伤害，`:125-135`） | y≤0 时通吃；y>0 时只对"穿透附魔"的伤害生效（`:108`） | 600 tick |
| **`EvilSpiritWalk`** `⟨cop⟩content/sets/evil/EvilSpiritWalk.java:88-98` | 祟神附体 | **回满血** + 清空效果 + 库默认"图腾三件套"药水（复刻 `⟨l2dt⟩contents/curios/L2Totem.java:29-31`）+ **退出附体状态** | 必须处于附体状态（有 token），现实指数 ≥7 | 600 tick |

### 1.4 ★ 强力效果（精选，非全部）

| 类名（文件:行） | 中文名 | 强在哪 | 关键行 |
|---|---|---|---|
| `AngelicPunishment` `⟨cop⟩content/sets/angle/AngelicPunishment.java:30` | 神罚之印 | **给伤害设下限**：不足对方当前血 5% 就补到 5%（POST_NONLINEAR 层，priority 71），20 tick 一次 | `:97`、`:102-111` |
| `HellfireSkull` `:31` | 燃魂颅骨 | 目标魂火剩余 ≥10 秒时，命中把魂火等级 +1（上限 = 现实指数−1） | `:75-81` |
| `EyeOfCursedSoul` `:33` | 咒怨之眼 | 命中后让目标 8 格内所有敌对怪**丢失目标**并各吃 1 点 soul_curse | `:89-102` |
| `CrownOfDemon` `:32` | 巫妖之证 | 支配 24 格内基础护甲 ≤50 的亡灵替你打人，且它们不会攻击你 | `:109-116`、`⟨cop⟩mixin/LivingEntityMixin.java:21-30` |
| `ShadowConsolidation` `⟨cop⟩content/sets/shadow/ShadowConsolidation.java:40` | 虚影化形 | 命中按伤害记帧，**1 秒后**在记录位置并集范围内对敌对怪结算范围伤害（可升级为虚空伤害） | `:137-178` |
| `CurseRedirection` `⟨cop⟩content/sets/elemental/CurseRedirection.java:26` | 逆魔符 | 把"诅咒附魔"变成强度：每件甲 1 条诅咒附魔 +1 魔力耐性，每条 +50% 魔法伤害 | `:35-72` |
| `PrimalForce` `⟨cop⟩content/sets/barbaric/PrimalForce.java:29` | 原始之力 | 自己 +100% 最大血护甲；**被你打的怪也 +100% 自己最大血护甲** | `:48-50`、`:96-100` |

---

## 2. 触发思路分类（报告重点）

### 2.1 触发时机

| # | 模式 | 代码证据 |
|---|---|---|
| A1 | **Curio tick 短租约**（每 tick 调用 `tickImpl`，脱装 2 tick 内自动 `removeImpl`） | `BaseTickingToken.java:13-29`、`ITokenProviderItem.java:34-36,49-53`、`ListTickingToken.java:16-23` |
| A2 | **每秒节流** `tickCount % 20 == 0` | `CurseOfSpellItem.java:88`（自伤 1）、`AbyssalWatcher.java:59`（按深度回血）、`CrownOfDemon.java:83,102`（刷新臣民表，`active` 倒计时 100 tick） |
| A3 | **命中目标时**（`onPlayerHurtTarget`，LivingHurtEvent） | `ShadowCore.java:78-85`、`HellfireSkull.java:72-83`、`EyeOfCursedSoul.java:84-105`、`HellfireReformation.java:81-87`、`AngelicDescent.java:69-74`、`EvilSpiritCurse.java:71-80`、`CurseOfTensionItem.java:181-205` |
| A4 | **造成伤害时**（`onPlayerDamageTarget` / `onCreateSource`） | `CurseOfPrudenceItem.java:117-145`、`CurseOfSpellItem.java:109-115`、`AngelicPunishment.java:92-100`、`AbyssalCrown.java:65-70`（改伤害**状态**而非数值：`event.enable(BYPASS_MAGIC)`） |
| A5 | **自己受伤时**（`onPlayerDamaged`：改"我受到的伤害"） | `CurseOfSpellItem.java:94-106`、`ShadowReformation.java:93-105` |
| A6 | **被攻击时整击取消**（`onPlayerAttacked` + `LivingAttackEvent.setCanceled`） | `CursedSoulCrystal.java:78-101`、`HellfireReformation.java:70-78`、`AbyssalWill.java:125-135`、`CurseOfTensionItem.java:168-178`、`AngelicWing.java:67-81`（取消两种伤害类型）、`BarbaricInstinct.java:90-107`（真在 Attack 阶段回血） |
| A7 | **结算后读真实伤害**（`onPlayer*Final`） | `ShadowConvergence.java:74-79`（按 `cache.getDamageDealt()` 攒回血）、`ShadowConsolidation.java:112-131`、`CurseOfTensionItem.java:147-161` |
| A8 | **死亡 / 掉落**（Forge 事件） | `PandoraEvents.java:52-58`（击杀加经验）、`:60-69`（`LivingDeathEvent` → `EvilSpiritAwakening.trigger`） |
| A9 | **免死**（注入原版 `checkTotemDeathProtection`） | `⟨l2dt⟩mixin/LivingEntityMixin.java:18-28` → `TotemHelper.process` → `L2Totem.allow/trigger`（详见 §3） |
| A10 | **条件不满足就主动拆除属性**（tick 里 `removeImpl`） | `EarthCrush.java:66-73`（攻速≤1.0 才给暴击伤害）、`WindThrust.java:79-93`（疾跑才有加成，否则移除）、`WavingSpell.java:61-69`（水/雨）、`AbyssalShell.java:70-79`、`AbyssalTreasure.java:59-66`（深度 0 就卸掉） |
| A11 | **左键空挥 / 交互** | `⟨cop⟩event/ItemClickListener.java:16-32`（空挥、左键方块、左键实体三路分发） |

### 2.2 条件判据

| 判据 | 证据 |
|---|---|
| 血量百分比阈值 | `EvilSpiritCurse.java:77`（目标 <20% 上限）、`CurseOfTensionItem.java:152`（单次 ≥ 自己最大血 10%）、`CurseOfPrudenceItem.java:138`（目标当前血 > 自己当前血） |
| 层数 / 计数累积 | `CurseOfTensionItem.java:186-199`（成熟标记数，上限 5 → `multTotal(1+0.2n)`）、`CurseOfPrudenceItem.java:122-131`（`pow(0.5, n)`，上限 20）、`EvilSpiritAwakening.java:106-127`（保留最近 5 次击杀，属性按 `list.size()`） |
| 状态效果有无 | 虚影标记一族：`ShadowCore.java:80-84`、`ShadowConvergence.java:76`、`ShadowConsolidation.java:118`、`ShadowReformation.java:81,94`；魂火：`HellfireSkull.java:74-81` |
| 随机概率 | `AbyssalCrown.java:67`（深度层数×5% 概率获得 BYPASS_MAGIC）、`CursedSoulCrystal.java:114`（随机选一只亡灵）、`WindBladeEntity.java:109`、`CursedKarma.java:74` |
| 玩家行为 | 饱食度：`CurseOfFleshItem.java:90`、`CurseOfMetabolismItem.java:88`；附魔数量：`CurseRedirection.java:38-48`、`CurseOfSpellItem.java:41-48`、`SpellSingularity.java:54-58`；疾跑：`WindThrust.java:81`；水中：`WavingSpell.java:63`；手持武器+攻击强度：`BarbaricInstinct.java:96-103` |
| 周围实体数量 / 种类 | `CrownOfDemon.java:109-116`（24 格内基础护甲≤50 的亡灵）、`CursedSoulCrystal.java:104-115`、`EyeOfCursedSoul.java:89-102`、`ShadowConsolidation.java:165-175` |
| 维度 / 坐标 / 时间 | 深度分层 `AbyssalWill.getStep` `AbyssalWill.java:45-49`（`-y / 12`，戴深渊意志时步长 8）；游戏刻做过期判据 `CurseOfTensionItem.java:110-137`、`EvilSpiritAwakening.java:106-113` |
| 自定义属性门槛 | 几乎所有 set 物品的 `tick()` 都 gate 在 `REALITY ≥ indexReq`：`AngelicBless.java:52-57`、`AbyssalCrown.java:47-52`、`ShadowCore.java:60-65`…（默认门槛 3~7，`CoPConfig`:98-560） |

### 2.3 数值结构（含 `DamageModifier` 分层与优先级）

`⟨l2dt⟩contents/attack/DamageModifier.java:6-36` 的工厂方法 ↔ 层级（`:46-61`）：

| 工厂方法 | Order | 语义 |
|---|---|---|
| `nonlinearPre(priority, f)` | `PRE_NONLINEAR` | 曲线层，最早 |
| `multAttr(v)` | `PRE_MULTIPLICATIVE` | 属性乘算 |
| `add(v)` | `PRE_ADDITIVE` | 属性加算 |
| `multBase(v)` | `POST_MULT_BASE` | 基础乘区 |
| `multTotal(v)` | `POST_MULTIPLICATIVE` | 总乘区（无 priority） |
| `nonlinearMiddle(priority, f)` | `POST_NONLINEAR` | 曲线层，中段 |
| `addExtra(v)` | `POST_ADDITIVE` | 额外加算 |
| `nonlinearFinal(priority, f)` | `END_NONLINEAR` | **最后一层，priority 决定谁最后写** |

本 mod 的选用规律与真实取值：

- **倍率一律 `multTotal`**：`CurseOfPrudenceItem.java:131`、`CurseOfTensionItem.java:198`、`CurseOfSpellItem.java:103,113`、`ShadowReformation.java:87,102`。
- **上限 / 下限归到非线性层**：
  - `CurseOfPrudenceItem.java:140` → `nonlinearFinal(9000, e -> min(e, 目标最大血×20%))`
  - `RealityTrait.java:59` → `nonlinearFinal(12346, e -> 0.0F)`（**归零**，priority 更高 → 排在 9000 之后，能覆盖它）
  - `AngelicPunishment.java:97` → `nonlinearMiddle(71, mapVal)`（补**下限**，中段）——注意冷却是在写回函数内部消费的（`:104-105`），属"lambda 里做副作用"的写法。
- **固定值**：`AttrAdder.of(name, attr, op, 常量)`（`AttrAdder.java:17-19`）。
- **按层数线性**：`AbyssalShell.java:34,38`（`step × 20%` 的 `MULTIPLY_BASE`）、`AbyssalTreasure.java:30`（`step` 点幸运）、`AbyssalWatcher.java:62`（`step × 1% × 最大血`）、`PrimalForce.java:49`（`最大血 × 100%` 护甲）、`EvilSpiritAwakening.java:79-85`（`size` 线性，一个 ADDITION 一个负 `MULTIPLY_TOTAL`）。
- **幂次叠加**：`CurseOfPrudenceItem.java:131` `pow(0.5, count)`。
- **属性抹除的数值学**（`CursePandoraUtil.java:47-74`）：遍历指定 `Operation` 上的所有修饰符累加，再写一个"反向量"修饰符把净结果拉回目标值。
  - `ADDITION` / `MULTIPLY_BASE` 传 `new Add()` → **全量抵消**（`AttributeLimiter.java:33-38`）；
  - `MULTIPLY_TOTAL` 传子类策略 → 可以选择"抵消"或"截断"（`:42`）。
  - `ClipMultiplierData.java:18-29`：`fv > cap` 时把总量裁到 cap；`fv ≤ min` 时整体 ×(1+bonus)。这就是"囚笼/制限"两诅咒的完整数学。
  - `CursePandoraUtil.Mult/BonusMult/Add`（`:76-125`）分别实现 `reverse()`：乘法族 `1/val - 1`，加成族 `factor/val - 1`。

### 2.4 代价与反制

**对价结构（给你好处，同时明确在别处扣你）**：

| 结构 | 好处 | 代价 | 证据 |
|---|---|---|---|
| 蛮荒系（本能/凶器/恐吓/暴戾/原始之力） | 近战强度、命中回血、暴击、护甲 | **`MAGIC_FACTOR −1.0`，等于全部魔法伤害为 0** | `BarbaricInstinct.java:30-34`、`BarbaricBlade.java:26,30-32`、`BarbaricRoar.java:26-31`、`BarbaricWrath.java:26-31`、`PrimalForce.java:30-34` |
| 原始之力（额外一层） | 自己获得最大血护甲 | **被你打的怪获得它自己最大血的护甲** | `PrimalForce.java:96-100` |
| 咒缚 | 附魔越多越强（间接） | 自伤 + 增伤 + 减伤，三重扣 | `CurseOfSpellItem.java:88-114` |
| 逆魔符 | 诅咒附魔 → 魔法伤害 | 需要真的穿诅咒附魔（占附魔位） | `CurseRedirection.java:38-72` |
| 羸弱 | 高饱食度加伤加移速 | 低饱食度扣伤/速/攻速 | `CurseOfMetabolismItem.java:88-99`、`:111-118` |
| 恐惧 | 叠标记加伤 | 被大伤后 3 秒完全无法还手 | `CurseOfTensionItem.java:152-178` |

**能否被解除 / 净化**：

- 现实七诅咒是**物品本身**（Curio 词条），不是 `MobEffect`，**牛奶无效**；卸载路径只有 token 生命周期（`BaseTickingToken.java:18-19`）与 `removeImpl` 链（`AttributeLimiter.java:48-58` 会删掉抵消修饰符，`CurseOfTensionItem.java:97-101` 会清 `terror` 表）。
- 你在 HUD 上看到的"恐惧标记 / 碎魂指示物 / 现实压制"是**纯渲染用** `FakeRenderEffect`（`CoPEffects.java:13-28`，注册注释明写 "For render only"），被清掉也没有任何数值后果——这是个很干净的做法：**UI 与逻辑解耦**。
- 真正当"标记"用的效果：`ShadowEffect` 同时实现 `InherentEffect` + `ForceEffect`（`⟨cop⟩content/effect/ShadowEffect.java:11`），属库级"固有/强制"语义（`l2library` 源码不在本产物内 → 具体是否免疫净化 **未确认**）。
- 主动清除：`EvilSpiritWalk.java:109`、`EvilSpiritAwakening.java:91,100,122` 用 `removeEffect` 自己收尾；`HellfireReformation` 实现 `EffectValidItem` 只对 `FLAME` 返回 true（`:48-50`）——同一接口在 `EffectRefreshItem.java:31-33` 用来"只刷新指定 ID 的效果"。
- **兼容层的"净化"是另一条路**：`EnigmaticLegacyCompat.java:8-14` 用"玩家身上有没有某个 token"来抹除七咒之戒的诅咒，代价是 −1 现实指数（`AggrevateRelief.java:38`）。这里没有出现任何 `DispellTrait` 引用（§5）。

### 2.5 冷却与限频（四种手法，各有适用面）

| 手法 | 写法 | 证据 |
|---|---|---|
| **字段倒计时** | `int cooldown` 在 `tickImpl` 里递减，触发时置为配置值 | `AngelicPunishment.java:78,86-88,104-105`、`BarbaricInstinct.java:72,83-84,103`、`EyeOfCursedSoul.java:70,78-80,87`、`ShadowConsolidation.java:90,106-108,120-125` |
| **物品冷却槽**（把 Curio 当图腾用） | `player.getCooldowns().addCooldown(item, ticks)` + `isOnCooldown(item)` 做 `allow` 判定 | `AngelicProtection.java:42,48`、`AbyssalWill.java:102,108`、`EvilSpiritWalk.java:73,96`、`CursedSoulCrystal.java:86,94`、`EvilSpiritEvoke.java:119-120` |
| **时间戳表 + 过期裁剪** | 自己存 `HashMap<UUID, Long>` / `ArrayList<Long>`，tick 里用 `getGameTime()` 剔除 | `CurseOfTensionItem.java:110-137`、`CurseOfPrudenceItem.java:100-107`、`EvilSpiritAwakening.java:106-113` |
| **一次性 / 脏标记** | `boolean updated`（只在层数变化时重建药水）、`boolean sync`（本 tick 是否要发包） | `EvilSpiritAwakening.java:77,120,129,136`、`CurseOfTensionItem.java:90,107-140`、`CurseOfPrudenceItem.java:80,97-110` |
| **窗口聚合对象** | `Frame` 记录"位置+伤害"，倒计时结束后一次性结算 | `ShadowConsolidation.java:88,98-104,137-144` |

---

## 3. 强力复活 / 特殊效果的实现机制

### 3.1 免死总线：注入原版图腾判定（最关键的一条）

```
⟨l2dt⟩mixin/LivingEntityMixin.java:18-28
  @Inject(at = HEAD, cancellable = true, method = "checkTotemDeathProtection")
  → TotemHelper.process(self, source) 返回 true 则 cir.setReturnValue(true)
```

原版 `checkTotemDeathProtection` 的调用条件已确认：`⟨mc⟩LivingEntity.java:1157-1165`——**只有在 `isDeadOrDying()`（血量已 ≤0）时才调用**，返回 false 就播死亡音并 `die()`。原版实现体在 `:1199-1230`：查双手是否有不死图腾 → `setHealth(1.0F)` + `removeAllEffects()` + 三个药水 + 广播事件 35。

l2dt 在 HEAD 短路，因此：
1. **不需要消耗物品**（是否消耗由各 `trigger` 自己决定，这 3 个都只挂冷却，不销毁物品）；
2. `TotemHelper.process`（`⟨l2dt⟩contents/curios/TotemHelper.java:26-54`）会遍历**双手 + 所有 Curio 槽**，按 `isValid` → `allow` 顺序筛选，命中第一个就 `trigger` 并结束；
3. `L2Totem` 的默认 `trigger`（`L2Totem.java:24-32`）就是"1 血 + `removeAllEffects()` + 三药水"——所以**子类覆写 `trigger` 时若忘了 `removeAllEffects`，就会留下 debuff**。

### 3.2 `AngelicProtection`：最强的一条

`AngelicProtection.java:36-52`
- 改血量：`self.setHealth(self.getMaxHealth())` → **直接回满**（`:38`；
- 清 debuff：`self.m_21219_()` = `removeAllEffects()`，已由 `⟨mc⟩LivingEntity.java:899-913` 确认语义（`:39`）；
- 限频：`player.getCooldowns().addCooldown(this, 600)`（`:42`）；
- `allow`（`:46-52`）：`AngelicPunishment.check(player, 6) && !isOnCooldown(this)`。
  - `AngelicPunishment.check`（`AngelicPunishment.java:35-39`）：**身上有"神罚之印"token 就直接 true**（= 常时可用），否则要求 `canSeeSky` + 现实指数 ≥ 门槛。这就是语言档"所有天使系潘多拉饰品不需要在天空下也能生效"的实现方式。

### 3.3 `AbyssalWill`：1 血 + 定向免疫窗口

`AbyssalWill.java:96-112`
- 改血量：`self.setHealth(1.0F)` → **保留 1 血**（`:98`）；
- 清 debuff：`removeAllEffects()`（`:99`）；
- 加效果：`EffectUtil.addEffect(self, ABYSSAL_PROTECTION, 200 tick)`（`:100`）；
- 免疫窗口的判定不在 `trigger`，而在 Data 的 `onPlayerAttacked`（`:124-135`）：窗口期内，**凡带 `bypasses_enchantments` 标签的伤害一律取消事件**。这是"护甲式免伤"做不到的事（护甲/抗性对 bypass 无效），必须用事件取消。
- `allow`（`:106-112`）反着写：`y > 0 && !bypasses_enchantments ? false : !onCooldown` —— 即**地面上只挡穿透伤害**，地下通吃。注意 `allow` 里**没有**现实指数检查，只有被动 Data 的 `tick`（`:89-94`）查 —— 免死本体比被动效果更容易拿到。

### 3.4 `EvilSpiritWalk`：复活 = 退出变身

`EvilSpiritWalk.java:88-98`
- 回满血 + `removeAllEffects()` + 复刻库默认三药水；
- `allow`（`:80-86`）：**必须有 token**（即处于附体状态）且不在冷却 → 附体状态本身是"复活次数"的载体；`Data.removeImpl`（`:106-110`）会移除 `SPIRIT_WALK` 标记效果，`tick`（`:71-78`）在冷却中或现实指数不足时干脆不刷新 token → token 自然过期消失。**"用 token 存活与否当状态机"**。

### 3.5 其他"复活 / 转伤 / 清 debuff / 免疫"手段一览

| 手段 | 实现方式 | 证据 |
|---|---|---|
| 转伤给第三方 | 取消原事件，`GeneralEventHandler.schedule` 下一 tick 把**同一个 DamageSource 与 amount** 打到随机亡灵身上 | `CursedSoulCrystal.java:90-98` |
| 转伤窗口免疫 | 窗口内取消 `bypasses_enchantments` 伤害 | `AbyssalWill.java:130-134` |
| 免疫整类伤害 | `LivingAttackEvent.setCanceled`，配 `DamageTypeTags` 白名单（不用抗性） | `HellfireReformation.java:70-78`（SOUL_FLAME）、`AngelicWing.java:67-81`（两种伤害类型，语言档写"摔落/鞘翅动能"）、`CurseOfTensionItem.java:168-178` |
| 死亡免疫（非图腾） | 反向：**取消攻击者的攻击事件** | `CurseOfTensionItem.java:170-176` |
| 清 debuff | 只有 `removeAllEffects()`（3 个图腾）与自家标记的 `removeEffect` | `AngelicProtection.java:39`、`AbyssalWill.java:99`、`EvilSpiritWalk.java:92`、`EvilSpiritAwakening.java:91` |
| 换实体（不只是打一下） | 把攻击你的恼鬼 `convertTo` 成己方 `EvilSpirit`，复制全套装备、设置限时生命与主人、并把它的攻击目标改成它原来的主人 | `EvilSpiritEvoke.java:85-113` |
| 自己造召唤物 | 受击即召唤限时 400 tick 的 `EvilSpirit`，60 tick 冷却 | `EvilSpiritEvoke.java:117-128` |
| 清敌方目标 | 遍历敌对怪，`setTarget(null)` + 清 `NearestAttackableTargetGoal`，再打 1 点伤害 | `EyeOfCursedSoul.java:89-102` |
| 延迟范围结算 | 记帧 → 延迟 20 tick → AABB 并集 → 逐怪累加范围内记录的伤害 × factor | `ShadowConsolidation.java:112-131,137-178` |
| 延迟回血 | `onPlayerDamageTargetFinal` 只累加 `heal`，下一 tick 的 `tickImpl` 才真正 `heal()`（**避免在同 tick 递归进伤害管线**） | `ShadowConvergence.java:58,74-79,65-71` |
| 属性归零式免伤 | END 层 `nonlinearFinal(12346, e -> 0.0F)` | `RealityTrait.java:59` |

---

## 4. 可借鉴清单（面向傀儡 / 魔像 mod）

| # | 它怎么做的 | 我们可以怎么用 |
|---|---|---|
| 1 | **1~2 tick 短租约 token**：`update()` 把 `life=2`，`tick()` 里 `life--`，≤0 就 `removeImpl`（`BaseTickingToken.java:13-29`） | 傀儡"部件/烙印"效果用同一模式：每 tick 由持有者续租，拆件/死亡/换核心后 2 tick 内自动清干净，不需要在 N 个地方写清理代码。 |
| 2 | **先全量抵消同类属性，再写反向量**（`AttributeLimiter.java:29-46` + `CursePandoraUtil.java:47-74`） | 傀儡的"攻速/护甲封印"型改造：不硬改基础值，而是每 tick 抵消所有 `ADDITION`/`MULTIPLY_BASE` 修饰符再加回目标值 → 天然兼容任何第三方加成，且不会和别的 mod 打成一团。 |
| 3 | **阈值触发分三类**：目标低血（`EvilSpiritCurse.java:77`）、单次大伤（`CurseOfTensionItem.java:152`）、比较双方当前血（`CurseOfPrudenceItem.java:138`） | 傀儡三套已成型的设计模板：处决（目标 <20%）、反制（单次伤害 ≥ 阈值就启用某状态）、对等（只在血量高于对手时给加成）。 |
| 4 | **END 层改写 + priority 排队**：`nonlinearFinal(9000, min)` / `nonlinearFinal(12346, 0)` / `nonlinearMiddle(71, 补下限)`（`CurseOfPrudenceItem.java:140`、`RealityTrait.java:59`、`AngelicPunishment.java:97`） | 我们已有 `DamageRestitutionListener.java:176` 用 `nonlinearFinal(ORDER_PRIORITY)`。**编号必须大于 12346** 才能保证"保底"不被归零覆盖（`docs/BYPASS-DESIGN.md:194-196` 已记录这个教训）；"限伤"用 END 层、**"提高下限/斩杀"用 `nonlinearMiddle`**，两者不打架。 |
| 5 | **一次性强力保命但不销毁物品**：挂 `getCooldowns().addCooldown(this, 600)`（`AngelicProtection.java:42`） | 傀儡"核心碎裂保命"：不真的打碎核心，只进 30 秒冷却 + 给 10 秒免疫窗口，玩家体验更好、也更好平衡。 |
| 6 | **免疫走事件取消 + tag 白名单，不走抗性**（`AbyssalWill.java:130-134`、`HellfireReformation.java:70-78`） | 傀儡的"元素/穿透免疫"必须用 `LivingAttackEvent.setCanceled`，并显式列出 `DamageTypeTags`/`L2DamageTypes`；否则会被 `bypasses_*` 打穿。 |
| 7 | **延迟结算窗口**：`Frame` 记位置+伤害，20 tick 后按 AABB 并集结算（`ShadowConsolidation.java:137-178`）；回血也延后一 tick（`ShadowConvergence.java:65-71`） | 傀儡"连击累积 → 1 秒后范围引爆"或"伤害转护盾"：既做出打击感，又避开同 tick 递归伤害与无限循环。 |
| 8 | **层数给属性 + 用空药水当 HUD 计数器**：属性按 `list.size()` 线性，`EmptyEffect` 只负责显示等级（`EvilSpiritAwakening.java:79-85,117-127`） | 傀儡"充能层数"：数值走属性、层数显示走一个无逻辑 `MobEffect`，省掉自定义同步包。 |
| 9 | **对价结构写进同一个物品**：`MAGIC_FACTOR −1.0` 换近战/暴击/护甲（`BarbaricInstinct.java:30-34` 等 5 处） | 傀儡"重装型/钝器型"升级件：明确以"魔法易伤 100%"为代价换物理强度，一句话就能让玩家理解取舍。 |
| 10 | **给敌人加 buff 作为惩罚**（`PrimalForce.java:96-100`） | 傀儡的"反噬/嘲讽"机制：被你打的怪获得护甲或回血，逼玩家换手段/换武器，比单纯削玩家数值更有趣。 |
| 11 | **用 `MobEffect` 当跨 mod 可见的标记**，命中即刷新时长（`ShadowCore.java:78-85`），并实现 `ForceEffect` 防止被随手清（`ShadowEffect.java:11`） | 傀儡的"标记/锁定"若做成效果，其他 mod（含药物、净化、HUD）都能观测；接受"会被牛奶清掉"这一副作用，或者显式实现库的强制接口。 |
| 12 | **免死挂在 `checkTotemDeathProtection`**（`⟨l2dt⟩mixin/LivingEntityMixin.java:18-28`） | 我们做傀儡免死要走同一挂点（或 `L2Totem` 接口），才能和原版不死图腾、其他 mod 的图腾**共用同一套排序与短路语义**；自己另开挂点会造成"两个不死同时触发"或"互相覆盖"。 |

---

## 5. 未确认与需要留意的点

- `l2library` / `l2complements` / `l2hostility` 的源码**不在本反编译产物内**，因此以下语义未确认：`ConditionalData` token 的实际 tick 频率与 `NetworkSensitiveToken.sync` 的发包时机；`ForceEffect` / `InherentEffect` 是否等价于"免疫净化"；`EffectValidItem.isEffectValid` 返回 true 究竟表示"受保护"还是"可被移除"；`EffectUtil.AddReason`（SELF/SKILL/FORCE）的差异。
- `⟨mc⟩Player.java:499` 确认 `FoodData.m_38721_()` 是 `needsFood()`，故 `CurseOfMetabolismItem.java:91` 的 `!m_38721_()` = **满饱食度**（与语言档一致），但该 SRG 名的正式映射名未在产物中直接给出。
- `MobEffects.f_19605_/f_19617_/f_19607_` 与 `DamageTypes.f_268576_/f_268671_` 的常量身份未确认（后者只能依语言档"摔落和鞘翅动能"反推）。
- `⟨cop⟩content/sets/mutation/` 下**只有 `package-info.java`**（无任何实现），但 `CoPConfig` 与 `CoPLangData` 中仍保留 Mutation 系列条目（`CoPConfig.java:428-517`）→ 该系列疑似被裁剪或未完成。
- `BaseCurseCurio`（`⟨cop⟩content/complex/BaseCurseCurio.java:7-11`）不是诅咒基类，只是 11 行的 `ICurio` 记录；本 mod 里没有 `*Curse` 的共同基类，共同点是"实现 `IAttackListenerToken` 的 token + `ITokenProviderItem` 物品"。
- 在整个 `cop-src` 与工作区其他反编译产物中**均未找到 `DispellTrait` 的任何引用** → 本 mod 的诅咒不通过 l2hostility 的词条净化路径解除。
- 显示用效果 `FakeRenderEffect` 是真实注册的 `MobEffect`（`MobEffectCategory.NEUTRAL`，`FakeRenderEffect.java:11-21`），仅注释说明 "For render only"；`CurseOfTensionItem` / `CurseOfPrudenceItem` 是通过 `ClientEffectCap` 直接往客户端玩家的效果表里塞条目（`CurseOfTensionItem.java:240-280`），服务端不存在这些效果。
