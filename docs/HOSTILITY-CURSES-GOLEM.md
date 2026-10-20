# 莱特兰-恶意（l2hostility）诅咒饰品 × 傀儡可用性调研

> 对象：Minecraft 1.20.1 Forge。全部结论来自**已反编译的第三方源码**（只读），**未修改任何源码**，本文件为唯一新增产物。
>
> 路径别名（下文一律用别名 + 行号）：
> - `⟨l2h⟩` = `l2h-full-src/dev/xkmc/l2hostility/`
> - `⟨l2hd⟩` = `l2h-full-src/data/l2hostility/`
> - `⟨mg⟩` = `dev/mg-src/dev/xkmc/modulargolems/`
> - `⟨mgd⟩` = `dev/mg-src/data/`
> - `⟨mgdp⟩` = `mgdp-src/src/toi_et_moi/mgdp/`
> - 中文名来源：`l2h-full-src/assets/l2hostility/lang/zh_cn.json`（按字节只读）
> - 傀儡 = `dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity` 的实例（metal / humanoid / dog golem）
>
> 判定口径（本报告统一使用）：
> - ✅ **能装上且至少一个效果真实生效**
> - ⚠️ **能装上，钩子会被调用，但收益恒为 0 / 条件恒不成立**（"理论可用但触发不了"）
> - ⛔ **能装上或压根装不上，但完全依赖玩家专属属性 / 事件 / 操作**，傀儡侧无任何路径
>
> 未确认项集中列在 §7。

---

## 0. 一句话回答：傀儡到底有没有 Curio 槽？

**有，而且 l2hostility 的槽一个不缺。** Curios 槽位由数据包 `curios/entities/*.json` 按实体类型分配，**不是**玩家专属：

| 事实 | 证据 |
|---|---|
| modulargolems 自己给三种傀儡分配了 24 个 Curios 槽，**含 `hostility_curse`** | `⟨mgd⟩curios/entities/golem_curios.json:3-6`（metal/humanoid/dog golem）、`:26`（`hostility_curse`）、`:17`（`charm`）、`:19`（`hands`）、`:21`（`ring`）、`:12`（`back`）、`:18`（`head`） |
| l2hostility 只给自己的 5 个槽定义了尺寸，实体分配文件里**只有 `minecraft:player`** | `⟨l2hd⟩curios/slots/hostility_curse.json`（size 1, order -1300）；`⟨l2hd⟩curios/entities/l2hostility_entity.json:4-11`（`"entities": ["minecraft:player"]`） |
| 但槽位分配是**多文件叠加**的，modulargolems 那份文件补上了傀儡 | 同上两条；两文件路径不同（`data/l2hostility/...` vs `data/modulargolems/...`），互不覆盖 |
| 傀儡有完整的 Curios 菜单 + 标签页 + 打开入口 | `⟨mg⟩compat/curio/CurioCompatRegistry.java:127-141`（`create` / `tryOpen`，`getSlots()!=0` 才开）、`:144`（注册 `golem_curios` 菜单）、`:148`（`GolemCurioTab`） |
| 玩家怎么把饰品放进傀儡：命令杖 Shift 右键 | `dev/mg-src/assets/modulargolems/patchouli_books/golem_guide/en_us/entries/golems/c2_command_wand.json:10`（"…configure curios"） |
| modulargolems 自己就在用傀儡的 curio 槽（`golem_route` / `golem_skin`） | `⟨mg⟩content/capability/PathConfig.java:33`、`⟨mg⟩compat/curio/CurioCompatRegistry.java:156-163` |

补充：`charm`(1) / `ring`(2) / `hands`(1) / `head`(1) / `back`(1) 是 **Curios 自带**的默认槽（不在 l2hostility 的 data 里），傀儡同样从 `golem_curios.json` 拿到；具体容量未在本次材料中确认（Curios jar 未反编译，见 §7）。**容量可以用 `infinity_glove` / `oddeyes_glasses` / `triple_strip_cape` 撑大**（见 §3.D）。

---

## 1. 恶意读取饰品的通道（决定"能不能触发"的关键）

| 通道 | 行为 | 证据 |
|---|---|---|
| `CurioCompat.getItems(le, pred)` | 扫**全部 `EquipmentSlot`**（只认实现了 `EquipCurioItem` 的）+ 扫 **Curios 全槽**。**对任意 `LivingEntity` 生效，无玩家判断** | `⟨l2h⟩compat/curios/CurioCompat.java:52-67`、`:133-145` |
| `CurioCompat.hasItemInCurio(le,item)` | 纯 Curios 查询，无玩家判断 | `:48-50`、`:111-131` |
| `CurioCompat.hasItemInCurioOrSlot(le,item)` | 先扫 `EquipmentSlot`，再查 Curios | `:34-42` |
| `hasItemInCurioChecked(le,item)` | 多一层过滤：`enableCurioCheckFilter=true`（默认）时，`Enemy`/`Animal` 直接返回 false | `:44-46`；`⟨l2h⟩init/data/LHConfig.java:331-332` |
| `TraitEffectCache` | 缓存目标的饰品列表，提供 `reflectTrait()` 与 16 格 AoE `getTargets()` | `⟨l2h⟩content/logic/TraitEffectCache.java:26-42`、`:44-60` |

**傀儡的过滤结果**：`AbstractGolemEntity extends GuardedEntity extends AbstractGolem implements NeutralMob, OwnableEntity, PowerableMob` —— **既不是 `Enemy` 也不是 `Animal`**，所以 `hasItemInCurioChecked` 的过滤对傀儡**放行**。

- `⟨mg⟩content/entity/common/AbstractGolemEntity.java:143-148`
- `⟨mg⟩content/entity/common/GuardedEntity.java:30`
- `dev/mg-src`、`mgdp-src` 全树搜索 `implements …Enemy` / `Enemy`：**无任何傀儡实体实现 `Enemy`**

这是本报告里"✅ 占多数"的根本原因：**l2hostility 的诅咒饰品回调几乎全部写在 `LivingEntity` 上，而不是 `Player` 上**。真正把结论压到 ⛔/⚠️ 的，是下面两类：

**A. 玩家专属属性 `l2hostility:add_level`（`LHMiscs.ADD_LEVEL`）只注册给玩家**

```java
// ⟨l2h⟩init/L2Hostility.java:121-125
public static void modifyAttributes(EntityAttributeModificationEvent event) {
   event.add(EntityType.f_20532_ /* PLAYER */, (Attribute)LHMiscs.ADD_LEVEL.get());
   event.add(EntityType.f_20532_, (Attribute)LHMiscs.ADD_SCALE.get());
}
```

所有 `CurseCurioItem.getExtraLevel() > 0` 的饰品（嫉妒/贪婪/色欲/暴怒/扭曲之魂/尼德霍格之欲）靠 `getAttributeModifiers` 往 `ADD_LEVEL` 上挂修饰符（`⟨l2h⟩content/item/curio/core/CurseCurioItem.java:51-67`）。傀儡的属性表里**没有这个属性**，修饰符无处落地。

**B. `DifficultyLevel.ofAny()` 对傀儡恒返回 0**

```java
// ⟨l2h⟩content/logic/DifficultyLevel.java:27-33
public static int ofAny(LivingEntity entity) {
   if (entity instanceof Player player) { return ...PlayerDifficulty...getLevel(); }
   else { return MobTraitCap.HOLDER.isProper(entity) ? ...getLevel() : 0; }
}
```

而 `MobTraitCap` 的适用谓词是

```java
// ⟨l2h⟩content/capability/mob/MobTraitCap.java:60-67
e -> e.m_6095_().m_204039_(LHTAG.WHITELIST) || e instanceof Enemy && !e.m_6095_().m_204039_(LHTAG.BLACKLIST)
```

`⟨l2hd⟩tags/entity_types/whitelist.json` 里**没有任何傀儡**，傀儡也不是 `Enemy` ⇒ `isProper(golem) == false` ⇒ **`ofAny(golem) == 0`**。反之，被傀儡打的**恶意怪有 `MobTraitCap`，`ofAny(怪) > 0`** —— 这一条差异直接造就了"暴怒诅咒在傀儡身上异常好用"。

**C. 掉落修正器需要 `KILLER_PLAYER`**

`EnvyLootModifier` / `GluttonyLootModifier` / `TraitLootModifier` 里都是同一段：先 `context.hasParam(KILLER_PLAYER)`，再 `(Player) context.getParameter(KILLER_PLAYER)` 并 `PlayerDifficulty.HOLDER.get(player)`。傀儡击杀没有 `KILLER_PLAYER`（`loot_envy` / `loot_gluttony` 的生成条件本身就是 `LootTableTemplate.byPlayer()`）。

- `⟨l2h⟩init/loot/EnvyLootModifier.java:40-46`
- `⟨l2h⟩init/loot/GluttonyLootModifier.java:40-46`
- `⟨l2h⟩init/loot/TraitLootModifier.java:80-87`
- `⟨l2h⟩init/loot/TraitGLMProvider.java:57-60`
- `⟨l2h⟩init/loot/PlayerHasItemCondition.java:34-38`

**D. 但"击杀归属"是傀儡** —— 这是 ✅ 的关键

```java
// ⟨l2h⟩events/CapabilityEvents.java:103-109
LivingEntity killer = event.getEntity().m_21232_();   // Mob#getKillCredit
Player player = null;
if (killer instanceof Player pl) { player = pl; }
else if (killer instanceof OwnableEntity own && own.m_269323_() instanceof Player pl) { player = pl; }
```

l2hostility 明写"击杀者不是玩家时，再向上找 `OwnableEntity` 的主人"—— 傀儡正是 `OwnableEntity`。这说明 **`getKillCredit()` 在傀儡击杀时返回的是傀儡本身**，于是 `MobEvents` 里那些 `hasItemInCurio(killer, …)` 的判断对傀儡成立。

---

## 2. 清单总览

共 **31 条 Curios 饰品**（另有 2 条非饰品的同级诅咒物件，见 §3.E）：**✅ 17 条 / ⚠️ 6 条 / ⛔ 8 条**。

| 分组 | 槽位 | 条数 | ✅ | ⚠️ | ⛔ |
|---|---|---|---|---|---|
| A 七宗罪 | `charm` + `hostility_curse` | 7 | 2 | 3 | 2 |
| B 诅咒槽物品 | `hostility_curse`(+`charm`) | 9 | 2 | 2 | 5 |
| C 戒指 | `ring` | 7 | 6 | 1 | 0 |
| D 手/头/背/护符 | `hands`/`head`/`back`/`charm` | 8 | 7 | 0 | 1 |
| **合计** | | **31** | **17** | **6** | **8** |

标签与槽位映射来源：`⟨l2h⟩init/registrate/LHItems.java:196-229`；`⟨l2hd⟩curios/tags/items/{charm,hands,ring,head,back,hostility_curse}.json`。

---

## 3. 分类表（核心）

### A. 七宗罪（`CurseCurioItem` 子类，全部可装进傀儡的 `charm` 或 `hostility_curse`）

| 物品 id | 中文名 | 效果 / 代价 | 装备位 | 判定 | 证据 |
|---|---|---|---|---|---|
| `curse_of_lust` | 色欲诅咒 | 击杀怪物时让怪物**掉落全部装备** / 代价为 +50 玩家难度（对傀儡无效） | `charm` `hostility_curse` | ✅ | 生效点 `⟨l2h⟩events/MobEvents.java:78-86`（`:81` `hasItemInCurio(credit, CURSE_LUST)`；`credit = mob.m_21232_()` 即击杀归属，傀儡击杀时=傀儡，见 §1.D） |
| `curse_of_wrath` | 暴怒诅咒 | 攻击**等级高于自己**的怪时，每级差 +2% 伤害；另 +50 难度等级 | `charm` `hostility_curse` | ✅ | 钩子 `⟨l2h⟩events/LHAttackListener.java:132-136`（任意攻击者，无 `Player` 判断）；公式 `⟨l2h⟩content/item/curio/curse/CurseOfWrath.java:48-54`（`:49` `ofAny(target) - ofAny(user)`）→ 傀儡侧 `ofAny(user)==0`，**等级差 = 怪物等级**，收益全额生效 |
| `curse_of_pride` | 傲慢诅咒 | 按难度每级 +0.5% 生命上限 / +0.5% 伤害；怪物词条成本 ×0.5 | `charm` `hostility_curse` | ⚠️ | 钩子确实会跑（`LHAttackListener.java:133`），但 `CurseOfPride.java:37` / `:61` 两次取 `DifficultyLevel.ofAny(wearer)`＝0 ⇒ 加伤、加血**恒为 0**；词条折扣在 `⟨l2h⟩content/capability/player/PlayerDifficulty.java:111-114`，纯玩家 |
| `curse_of_envy` | 嫉妒诅咒 | 击杀怪物按其词条阶数掉落词条物品；+50 难度等级 | `charm` `hostility_curse` | ⛔ | `getExtraLevel` 走 `ADD_LEVEL`（玩家专属，§1.A；`CurseOfEnvy.java:21-23`）；`getLootFactor` 只在 `EnvyLootModifier.java:40-46` 被调用，需 `KILLER_PLAYER`；该 GLM 注册条件为 `byPlayer()`（`TraitGLMProvider.java:57`）⇒ 傀儡击杀**根本不进这个 modifier** |
| `curse_of_gluttony` | 暴食诅咒 | 击杀怪物掉落"瓶装恶意"，每级 2% 概率 | `charm` `hostility_curse` | ⛔ | 唯一实现是 `getLootFactor`（`CurseOfGluttony.java` 无其它方法）→ `GluttonyLootModifier.java:40-46`，同 `KILLER_PLAYER` 限制；注册条件 `byPlayer()`（`TraitGLMProvider.java:58-60`） |
| `curse_of_greed` | 贪婪诅咒 | 词条掉落概率 ×2；+50 难度等级 | `charm` `hostility_curse` | ⛔ | `getExtraLevel`→`ADD_LEVEL`（`CurseOfGreed.java:22-25`）；`getLootFactor`（`:27-30`）只由 `TraitLootModifier.java:84`/`EnvyLootModifier` 调用，同 `KILLER_PLAYER` 限制 |
| `curse_of_sloth` | 怠惰诅咒 | 击杀不再提升难度 / 自身无掉落增长 | `charm` `hostility_curse` | ⚠️ | `CurseOfSloth.java:21-29` 把 `getLootFactor`/`getGrowFactor` 都写死 0.0；两者都只在玩家路径被消费（`PlayerDifficulty.java:129-139`）⇒ 傀儡装上后**既无收益也无代价**，纯占槽 |

### B. `hostility_curse` 槽物品（`⟨l2hd⟩curios/tags/items/hostility_curse.json`）

| 物品 id | 中文名 | 效果 / 代价 | 装备位 | 判定 | 证据 |
|---|---|---|---|---|---|
| `abrahadabra` | 扭曲之魂 | 怪物词条想给**你**上效果时，改为施加给周围所有敌对生物 | `hostility_curse` | ✅ | `Abrahadabra.java:28-31`（`reflectTrait` 恒 true）；消费点 `⟨l2h⟩content/logic/TraitEffectCache.java:26-34` ← `⟨l2h⟩content/traits/base/MobTrait.java:100-108`；另有 7 处 `isOn()` 直读：`AuraEffectTrait:30`、`GravityTrait:20`、`ReflectTrait:30`、`CorrosionTrait:23`、`ErosionTrait:23`、`KillerAuraTrait:49`、`PushPullTrait:51`。`isOn` 走 `hasItemInCurioChecked`，傀儡非 Enemy/Animal ⇒ 放行（`Abrahadabra.java:37-39`） |
| `greed_of_nidhoggur` | 尼德霍格之欲 | 击杀掉落物按怪物等级每级 +0.5%；+100 难度等级 | `hostility_curse` | ✅ | 生效点 `⟨l2h⟩events/MobEvents.java:96-123`（`:104-105` `killer = getKillCredit()` → `hasItemInCurio(killer, NIDHOGGUR)`，傀儡击杀时成立）；`getExtraLevel`（`GreedOfNidhoggur.java:22-25`）与 `getLootFactor`（`:27-30`）在傀儡侧无效 |
| `divinity_light` | 神圣之光 | 玩家动态难度维持 0 | `hostility_curse` `charm` | ⚠️ | `DivinityLight.java:29-34`：`curioTick` 里 `if (slotContext.entity() instanceof Player player && …)` —— **写死 Player**，傀儡 tick 直接跳过；`getGrowFactor`＝0 也属玩家路径（`:24-27`） |
| `divinity_cross` | 神圣十字 | 净化效果不会清除 1 级正面效果 | `hostility_curse` `charm` | ⚠️ 未确认 | `DivinityCross.java:17-24` 实现 `dev.xkmc.l2complements…EffectValidItem`，接口签名是 `(MobEffectInstance, ItemStack, LivingEntity)`（**实体通用**），但 **l2hostility 内没有任何消费者**；`l2c-src` 只有 4 个 java 文件且无 `EffectValidItem` 命中 ⇒ 无法确认 l2complements 是否对非玩家调用它。**缺 l2c 反编译即可定论** |
| `charm_of_looting_1..4` | 粗糙/魔能/混沌/奇迹抢夺宝珠 | 解锁部分恶意词条掉落（JEI 查看） | `hostility_curse` `charm` | ⛔ ×4 | `LootingCharm.java:14-21` **只有 tooltip，没有任何效果方法**；真实作用是给 `TraitLootModifier` 当条件：`PlayerHasItemCondition.java:34-38` 先要求 `KILLER_PLAYER`，再 `hasItemInCurio(player, item)` ⇒ **只有玩家装才生效** |
| `abyssal_thorn` | 深渊王座 | 怪物获得该等级全部词条，但佩戴嫉妒诅咒时必定掉落 | `hostility_curse` | ⛔ | 唯一消费者 `⟨l2h⟩content/capability/player/PlayerDifficulty.java:116-120`（`this.player`）；`AbyssalThorn.java:14-21` 自身无逻辑 |

### C. 戒指（`curios:ring`）

| 物品 id | 中文名 | 效果 / 代价 | 判定 | 证据 |
|---|---|---|---|---|
| `ring_of_life` | 屹立不倒之戒 | 单次受伤不超过最大生命的 90% | ✅ | 钩子 `⟨l2h⟩events/MobEvents.java:68-75`（对任意 `LivingEntity` 遍历其 `CurseCurioItem`，无玩家判断）；实现 `RingOfLife.java:28-38` |
| `ring_of_healing` | 再生之戒 | 每秒回复 5% 最大生命 | ✅ | `RingOfHealing.java:26-35`（`curioTick` → `wearer.heal(...)`，无玩家判断） |
| `ring_of_divinity` | 圣光守护之戒 | 免疫魔法伤害 + 持续净化 | ✅ | 事件侧 `⟨l2h⟩events/MobEvents.java:50-51`（`hasItemInCurio(event.getEntity(), RING_DIVINITY)` 直接取消，实体通用）；tick 侧 `RingOfDivinity.java:30-35` |
| `ring_of_reflection` | 诅咒扭曲之戒 | 药水类词条改为施放给周围 16 格敌对生物 | ✅ | `RingOfReflection.java:23-26`（`reflectTrait` 限 `LHTraits.POTION`）+ `:32-34`（`isOn`）；消费点 `⟨l2h⟩content/traits/base/TargetEffectTrait.java:34-45`；AoE 收集 `TraitEffectCache.java:44-60`（只收 `Mob`） |
| `ring_of_corrosion` | 丑恶诅咒之戒 | 打人：扣目标全部装备各 20% 总耐久；被打：扣自己全部装备各 10% | ✅ | `RingOfCorrosion.java:30-37`（经 `LHAttackListener.java:133`）与 `:39-44`（经 `MobEvents.java:70`）；傀儡有装备槽（`⟨mg⟩compat/materials/cataclysm/CataDispatch.java:63-71` 直接读 `golem.getItemBySlot(HEAD/CHEST/LEGS)`） |
| `ring_of_ocean` | 深海加护之戒 | 永远处于"下雨"状态 | ✅ | Mixin 打在 `Entity.isInRain` 上：`⟨l2h⟩mixin/EntityMixin.java:39-49`（`self instanceof LivingEntity` 即生效）；`RingOfOcean.java:25-27` |
| `ring_of_incarceration` | 禁锢诅咒之戒 | **潜行时**对你与攻击范围内所有生物施加禁锢 | ⚠️ | `RingOfIncarceration.java:35-60`，关键条件 `:38` `wearer.m_6144_()`（`isShiftKeyDown`）。`dev/mg-src` + `mgdp-src` 全树搜 `ShiftKeyDown`/`setCrouching`/`setShiftKeyDown` 对傀儡**零命中**（命中项全是 `player.m_6144_()`）⇒ 傀儡从不潜行，条件恒假 |

### D. 手 / 头 / 背 / 护符槽

| 物品 id | 中文名 | 效果 / 代价 | 装备位 | 判定 | 证据 |
|---|---|---|---|---|---|
| `imagine_breaker` | 破咒遗骨 | 近战伤害穿透魔法防御（自身掉落归零） | `hands` | ✅ | `⟨l2h⟩events/LHAttackListener.java:159-165`（`hasItemInCurioOrSlot(mob, IMAGINE_BREAKER)` → `event.enable(BYPASS_MAGIC)`，`mob` = 攻击者，无玩家判断）；`ImagineBreaker.java:17` 实现 `EquipCurioItem`（额外可从装备槽被读到，`CurioCompat.java:55-60`） |
| `platinum_star` | 破风拳套 | 近战伤害无视受击冷却 | `hands` `charm` | ✅ | `⟨l2h⟩events/LHAttackListener.java:167-169`（`event.enable(BYPASS_COOLDOWN)`）；同为实现 `EquipCurioItem`（`PlatinumStar.java:15`） |
| `flaming_thorn` | 魔焰荆棘 | 命中时按目标护甲格数施加 5 秒/级的魂火 | `hands` | ✅ | `FlamingThorn.java:28-36`（经 `LHAttackListener.java:133`）；`FlamingThorn.java:23` 实现 `EquipCurioItem` |
| `infinity_glove` | 宝石手套 | **+5 个 `ring` 槽、+1 个 `charm` 槽** | `hands` | ✅ | `InfinityGlove.java:28-33`（`CuriosApi.addSlotModifier(map,"ring",uuid,5,ADDITION)` / `"charm",1`）。这是傀儡**扩容饰品槽**的关键道具 |
| `oddeyes_glasses` | 异色眼镜 | +2 个 `head` 槽 | `head` | ✅ | `OddeyesGlasses.java:28-32`。无其它效果，实质是"扩槽工具" |
| `triple_strip_cape` | 三色披风 | +3 个 `back` 槽 | `back` | ✅ | `TripleStripCape.java:28-32`。同上 |
| `pocket_of_restoration` | 解咒口袋 | 自动收纳被封印物品，到期后放回原槽位 | `charm` | ✅ | `PocketOfRestoration.java:43-78`；`:47` 用 `CurioCompat.getItemAccess(le)`（实体通用），`:54` 用 `CurioCompat.decode(...)`（通用），只有 `:58` 的兜底分支写死 `Player`（找不回槽位时塞进玩家背包）—— 对傀儡只是退化为"放不回就留在口袋里" |
| `detector_glasses` | 侦测眼镜 | 显示恶意区块/词条信息 | `head` | ⛔ | 三处消费者全是玩家侧：`⟨l2h⟩events/ClientEvents.java:132`（渲染）、`⟨l2h⟩events/ClientGlowingHandler.java:42`（客户端缓存）、`⟨l2h⟩content/item/consumable/HostilityOrb.java:34`（玩家使用道具）⇒ 傀儡侧无任何路径，且不属于"诅咒"标签 |

### E. 非饰品、但同属"恶意诅咒"体系的物件（仅供对照，傀儡用不了）

| 物品 id | 中文名 | 为什么不可能 | 证据 |
|---|---|---|---|
| `bottle_of_curse` | 瓶装恶意 | 需玩家饮用，逻辑签名就是 `doServerLogic(ServerPlayer)` | `⟨l2h⟩content/item/consumable/BottleOfCurse.java:22-28` |
| `witch_wand` | 混乱魔药法杖 | 非 Curio（`chaos_equipment` 手持），效果是右键投掷 | `⟨l2h⟩init/registrate/LHItems.java:191-195`、`⟨l2h⟩init/data/LangData.java:81` |

## 4. 触发路径速查（哪一行决定了 ✅ / ⚠️ / ⛔）

| 阶段 | 派发点 | 傀儡是否进入 | 受影响饰品 |
|---|---|---|---|
| `CreateSourceEvent` | `LHAttackListener.java:153-172`（`:163`/`:167`） | ✅ 攻击者通用 | imagine_breaker、platinum_star |
| `LivingHurtEvent`（`onHurt`） | `LHAttackListener.java:79-139`；`:132-136` 是**唯一**的诅咒加伤入口 | ✅（`:99` 的词条块进不去，`:132` 的诅咒块能进去） | curse_of_wrath、curse_of_pride、ring_of_corrosion |
| 词条反弹 / `LivingAttackEvent` / `LivingDamageEvent` / 死亡掉落 | `MobTrait.java:100-108` → `TraitEffectCache.java:26-34`；`MobEvents.java:45-57`(`:50`)、`:68-75`、`:77-91`(`:81`)、`:93-124`(`:105`) | ✅ 除 `curioTick` 外全部按 `LivingEntity` 派发 | abrahadabra、ring_of_reflection、ring_of_ocean、ring_of_divinity、ring_of_life、curse_of_lust、greed_of_nidhoggur |
| `curioTick` | Curios 侧驱动（Curios 对持有该能力的实体调用） | ⚠️ 见 §7 | ring_of_healing、ring_of_divinity、ring_of_incarceration、divinity_light、pocket_of_restoration、curse_of_pride |
| 掉落修正器（`KILLER_PLAYER`） | `EnvyLootModifier.java:40-46`、`GluttonyLootModifier.java:40-46`、`TraitLootModifier.java:80-87` | ❌ 无 `KILLER_PLAYER` | curse_of_envy、curse_of_gluttony、curse_of_greed、curse_of_sloth、charm_of_looting_* |
| `ADD_LEVEL` 属性 / `PlayerDifficulty` | `L2Hostility.java:121-125`（只给 PLAYER）；`PlayerDifficulty.java:107-123`、`:129-139`、`:163-167` | ❌ 属性不存在 / 玩家专属能力 | envy、greed、lust、wrath、abrahadabra、nidhoggur 的"额外等级"；curse_of_pride 词条折扣、abyssal_thorn、curse_of_sloth、divinity_light |

## 5. 给我们的用处

### 5.1 最值得做成傀儡升级的 3 条机制

| 优先级 | 来源 | 机制内核 | 为什么值得 | 与现有 `hostility_*` 的重复度 |
|---|---|---|---|---|
| ★★★ | `curse_of_wrath` | **"敌人等级差 → 伤害"的负面对价轴**：`multBase(level差 × 0.02)`（`CurseOfWrath.java:49-52`） | 公式天然实体通用，且傀儡侧 `ofAny(self)==0` 使收益退化为"按目标恶意等级加伤"。**必须补代价**（原始代价"自己难度 +50"对傀儡不存在），建议做成 `hostility_wrath`：按目标恶意等级加伤 X%，同时傀儡承受 (X/2)% 额外伤害 —— 这才是完整的"对价结构" | **无重复**。mgdp 的 `hostility_drain`（按目标负面效果数加伤）、`hostility_killer_aura`（AoE）、`hostility_reprint`（按附魔点数）都不是等级轴 |
| ★★★ | `ring_of_life` | **"单次受伤上限 = X% 最大生命"**（`RingOfLife.java:29-38`，直接 `event.setAmount`） | 对高血量傀儡是极强的生存保底，且实现只有 6 行、走 `LivingDamageEvent`，拷进 `GolemModifier#onDamaged` 几乎零成本。代价可设计成"上限越低、傀儡攻速/移速越慢" | **无重复**。mgdp `last_line` 是暮色森林群系内的定点免疫（`modifier/defense/LastLineModifier.java:23-40`），`hostility_dementor` 是"非线性减伤"（`MGDPModifiers.java:259`），都不是硬上限 |
| ★★☆ | `ring_of_corrosion` | **双向耐久对价**：打人扣对方总耐久 20%，被打扣自己总耐久 10%（`RingOfCorrosion.java:30-44`） | 傀儡有真实装备槽（cataclysm 兼容读 `getItemBySlot(HEAD/CHEST/LEGS)`），"用装备耐久换输出"是很干净的资源型对价。也可反过来做成"傀儡耐久越低伤害越高" | **无重复** |

**不推荐做成升级的**（已被现有升级覆盖）：

- `platinum_star`（无视受击冷却）≈ mgdp `quick_strike`「君子五艺」（`modifier/combat/QuickStrikeModifier.java:23-25` 直接 `event.getEntity().f_19802_ = 0`）——**完全重复**。
- `greed_of_nidhoggur`（掉落倍增）≈ mgdp `prosperity`「丰收」（`modifier/farming/ProsperityModifier.java:43-56`，每级 +1 倍战利品）——**同轴重复**，且尼德霍格的曲线（0.5%/级）更难平衡。
- `abrahadabra` / `ring_of_reflection`（词条反弹）≈ modulargolems 自带 `hostility_reflect`（`⟨mg⟩compat/materials/l2hostility/ReflectiveModifier.java`，注册名 `hostility_reflect`）——**重复**。
- `ring_of_healing`（每秒回血）≈ `hostility_regen`（`⟨mg⟩…/RegenModifier.java`）——**重复**。
- `imagine_breaker`（穿透魔法防御）≈ `hostility_dispell`「破魔」——**部分重复**（一个穿透、一个驱散）。
- `curse_of_lust` 与 `greed_of_nidhoggur` 的**掉落部分无需做成升级**：它们已经是 ✅，直接给傀儡戴饰品即可。

### 5.2 现成的"对价结构"设计模板

l2hostility 的诅咒统一是 `负面代价 × 强度收益`，可直接照搬到傀儡升级：

| 模板 | l2hostility 原型 | 傀儡化改写 |
|---|---|---|
| 等级对价 | `curse_of_envy/greed/lust/wrath` 的 `+N 难度等级` | 傀儡没有难度等级 ⇒ 换成"傀儡击杀**不再**给主人累积难度"或"傀儡承受 +X% 伤害" |
| 掉落 / 资源对价 | `curse_of_sloth`（掉落因子归零换收益）、`ring_of_corrosion`（耐久消耗） | "傀儡不掉落自身部件，换取伤害"；"用傀儡装备耐久换输出" |
| 目标侧放大 | `curse_of_wrath` / `greed_of_nidhoggur` 按目标等级缩放 | 傀儡版统一用 `MobTraitCap.getLevel()`（即"恶意等级"）当缩放自变量 |

### 5.3 与 mgdp 已有 `hostility_*` 的对照

| 已有升级（id） | 中文名 | 来源 |
|---|---|---|
| `hostility_core` / `hostility_potion` / `hostility_tank` / `hostility_speed` / `hostility_protection` / `hostility_regen` / `hostility_reflect` / `hostility_adaptive` / `hostility_dispell` | 恶意核心/药水/坚韧/迅捷/保护/再生/反射/适应/破魔 | modulargolems 自带：`⟨mg⟩compat/materials/l2hostility/LHCompatRegistry.java:21-61`；标签 `modulargolems:hostility_upgrades`（`:83`），由 `HostilityCoreModifier.java:14-24` 实现"不占升级槽" |
| `hostility_dementor` / `hostility_drain` / `hostility_reprint` / `hostility_undying` / `hostility_grenade` / `hostility_killer_aura` / `hostility_pulling` / `hostility_repelling` | 恶意升级：摄魂/嗜魔/复印/不死/榴弹/杀戮光环/吸引/排斥 | mgdp：`⟨mgdp⟩init/MGDPModifiers.java:259/262/265/325/331/334/340/343`；中文名取自 `mgdp-in/zh_cn.json` |

**结论**：**7 个诅咒的机制与这些升级没有交集**（wrath 的等级差加伤、ring_of_life 的伤害上限、corrosion 的耐久对价、ring_of_ocean 的湿润、ring_of_incarceration 的禁锢、curse_of_lust 的掉装备、curse_of_pride 的词条折扣），其余 5 组已有替代。新增 `hostility_*` 升级时**记得挂 `modulargolems:hostility_upgrades` 标签**，即可被 `hostility_core` 免槽。

## 6. 操作结论（给用户的一句话版）

1. 傀儡**有** `head` / `charm` / `ring` / `hands` / `back` / `hostility_curse` 等全部相关 Curios 槽，用命令杖 Shift 右键打开傀儡的饰品页即可装备。
2. **直接能给傀儡戴且有效的**：`curse_of_lust`、`curse_of_wrath`、`abrahadabra`、`greed_of_nidhoggur`、`ring_of_life`、`ring_of_healing`、`ring_of_divinity`、`ring_of_reflection`、`ring_of_corrosion`、`ring_of_ocean`、`imagine_breaker`、`platinum_star`、`flaming_thorn`、`infinity_glove`、`oddeyes_glasses`、`triple_strip_cape`、`pocket_of_restoration`（17 条）。
3. **建议先铺槽**：傀儡戴 `infinity_glove`（`hands`）+5 `ring` +1 `charm`，再塞戒指，收益最高。
4. **别浪费槽位**：`charm_of_looting_*`(×4)、`abyssal_thorn`、`curse_of_envy`、`curse_of_gluttony`、`curse_of_greed`、`detector_glasses` 在傀儡身上**完全无效**（8 条）；`curse_of_pride`、`curse_of_sloth`、`ring_of_incarceration`、`divinity_light`、`divinity_cross` 是"钩子会跑但收益为 0"（6 条，其中 `divinity_cross` 未确认）。

## 7. 未确认项（缺什么才能定论）

| # | 未确认内容 | 为什么不确定 | 需要什么 |
|---|---|---|---|
| 1 | **Curios 侧三件事**：① 是否对非玩家实体调用 `curioTick`（影响 `ring_of_healing`、`ring_of_divinity` tick、`ring_of_incarceration`、`divinity_light`、`pocket_of_restoration`、`curse_of_pride`）；② 默认槽 `charm`/`ring`/`hands`/`head`/`back` 的初始容量；③ `CuriosApi.addSlotModifier` 对非玩家是否生效（决定 `infinity_glove`/`oddeyes_glasses`/`triple_strip_cape` 能否真正给傀儡扩容） | Curios 的 jar 在本次材料中**不存在**（`libs/` 下只有 l2 系列与 modulargolems，无 curios；全工作区 `.jar` 名匹配 `curio` 零命中）。旁证：modulargolems 为傀儡实现了完整 Curios GUI 与 `golem_skin`/`golem_route` 读取；`⟨l2h⟩compat/curios/CurioCompat.java:158-180` 的 `isSlotAdder` 也对任意实体做槽位检测，说明作者认为该机制实体通用 | Curios 1.20.1 反编译源码，或实机验证（给傀儡戴 `ring_of_healing` 看是否回血、戴 `infinity_glove` 看戒指槽是否变 7） |
| 2 | **`divinity_cross` 的 `EffectValidItem` 消费点** | `l2c-src` 仅 4 个 java 文件，`EffectValidItem` / `isEffectValid` 在该目录与 `l2h-full-src`、`src/` 中零命中 | l2complements 完整反编译（尤其净化效果 `LCEffects.CLEANSE` 的处理逻辑） |
| 3 | **`curse_of_lust` / `greed_of_nidhoggur` 是否 100% 生效** | 依赖 `Mob#getKillCredit()` 在"傀儡击杀"时返回傀儡。本次无法读取原版源码，但 l2hostility 自己的 `CapabilityEvents.java:103-109` 明确为 `OwnableEntity`（傀儡正是）留了向上找主人的分支，反推 `getKillCredit()` 返回的是傀儡而非主人 | 原版 `Mob#getKillCredit` 反编译，或实机验证（傀儡戴 `curse_of_lust` 杀一只穿盔甲的僵尸） |
| 4 | **`EquipCurioItem` 的 `EquipmentSlot` 通道对傀儡是否可用** | 傀儡确实有装备槽（cataclysm 兼容读 `getItemBySlot(HEAD/CHEST/LEGS)`），但**能否把 `flaming_thorn`/`imagine_breaker`/`platinum_star` 这类 Curio 放进傀儡的装备槽**未确认；不影响结论（三者同时有 `hands` Curios 槽，走 Curios 通道即可） | 查看傀儡装备菜单是否校验 `IGolemEquipmentItem`（`mgdp` 里有类似校验：`player.m_6144_() \|\| !itemstack.m_41619_() && !(itemstack.m_41720_() instanceof IGolemEquipmentItem)`） |
