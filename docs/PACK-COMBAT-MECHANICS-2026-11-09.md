# 匠战妖域（Tinkers' Warfare Demon Realm）攻防面总账 · 2026-11-09

> 对象：`D:\我自己的包\.minecraft\versions\Tinkers' Warfare Demon Realm\mods\`（**219 个 jar**）
> 方法：①包内全部 `.class` 常量池扫描（本次重跑，产物 `.tmp-golem/scan-primitives.tsv` / `scan-classes.tsv`）；
> ②关键类 `javap -c` 核字节码；③`kubejs/data`、`data/*/tags/damage_type`、`lang`、`config` 原文；
> ④沿用 `docs/PACK-SURVEY-TINKERS-WARFARE.md`、`docs/ATTACK-SURFACE-2026-11-08.md` 的既有取证。
> 标记：**确认** = 读到字节码/原文；**推断** = 由名字/结构判断。

---

## 0. 先纠三件事

1. **这个包里没有 ArcaneVortex（秘法涡流）**。219 个 jar 里没有 `Arcane*`/`秘法`。
   AV 在另一个实例 `1.20.1-Forge_47.4.20`；它的机制见 §7，是另外取证的。
2. **这个包里没有 `modulargolems` / `l2library` / `l2damagetracker`**，所以本仓库的 mod 在这个包里加载不了。
   下面所有"对傀儡"的结论都是**要先把这几个依赖补进来**才成立。
3. **`Entity.canUpdate` 是 Forge 加的，不是没有**（本轮新证据，推翻 11-09 早先的判断）：
   - `MsUtil` 家族调用 `Entity.canUpdate(Z)V`（方法）；
   - `momotinker.Cutter` / `super-steve.SSUtil` 直接 `putfield Entity.canUpdate:Z`（字段）。
   - 同一个名字既有字段又有 setter，且 6 个互不相关的 mod 同时在用 ⇒ 只能是 Forge 补丁加的。
   - **后果**：`canUpdate=false` 是一个**真实可用的"冻结实体"原语**，8 个清除模组全在用。
     我们之前用 `LivingEntityTickFreezeMixin` 做的等价物能用，但防线里也要**把它读回来**（§6.2）。

---

## 1. 包画像

| 项 | 观察 |
|---|---|
| 规模 | 219 jar；Tinkers 全家桶 + Boss 模组 + 优化/工具 |
| 匠魂系 | `tconstruct`、`tinkers_advanced`、`tinkersinnovation`、`momotinker`、`miztinker`、`Tinkers Domain`(tcondiadema)、`ticex`、`etstlib`、`TinkersLevellingAddon`、`vampirismtinker`、`constructs_casting`、`tconbreakthrough` |
| 战斗外围 | `goety` + `Goety·Revelation`、`irons_spellbooks`、`iceandfire`、`vampirism`、`SlashBladeResharped` + `SJAP`、`Re-Avaritia`、`ProjectE`、`jerotes` + `jerotesvillage` |
| Boss | `jzyy`(232MB)、`prinegorerouse`、`trek`、`eeeabsmobs`、`block_factorys_bosses`、`kaiju_calamity`、`legendarymonsters`、`ultris`、`the_cosmic_spokespersons`、`witherzilla`、`witherreincarnated` 等 |
| 属性/数值层 | `AttributeFix`（上限拉到 2.1e18 量级）、`MaxHealthFix`、`NaNny`（NaN 守卫）、`ScalingHealth` |
| 特殊 | `jzyyfix`（JVMTI 插桩，自带 `instimpl-x64.dll`）、`sweeper_maid`（定时清场）、`carryon`（搬实体）、`Capsule`（结构保存） |

---

## 2. 攻击手法（按"写到多深"分层）

| 层 | 原语 | 谁在用（确认/推断） | 是否发 Forge 事件 |
|---|---|---|---|
| **D0** | `hurt()` | 绝大多数 Boss（goety/冰火/eeeabsmobs/jerotes…） | ✅ |
| **D1** | `setHealth()` | `ticex ModifierOmnipotence`、`tcondiadema` 5 处、`jzyy` 多处、`BossEntity` 覆写 | ❌（LivingHeal 事件只在部分路径） |
| **D2** | `SynchedEntityData.set(DATA_HEALTH)` | `LivingEntityUtil.reflectionSeverance`、`PenetratingDamage`、`tcondiadema.Test` | ❌ |
| **D3** | `DataItem.f_135391_` 直写 | `ForceHurtUtil`、`tryModifyHealth`、`forceSetHealthByAccessor`、`ticex IEntityDataAccessor` | ❌ |
| **D4** | 属性：`setBaseValue` / `cachedValue` 直写 | 多处；`momotinker` 有 `cachedValue` accessor | ❌ |
| **D5** | 反射 / `Unsafe` / 换类 | `ForceHurtUtil.makeNoHealable`（**改 klass 指针把 `SynchedEntityData` 换成子类**）、`jzyy.Helper`、`witherzilla.UnsafeUtil`、`miztinker.Helper` | ❌ |
| **R1** | `setRemoved(reason)` | 普遍（最终都到这里） | ❌ |
| **R2** | `f_146795_` 直写 | `MsUtil` 家族（8 个 mod） | ❌ |
| **R3** | `onRemovedFromWorld()` / `f_146801_`(levelCallback) / `isAddedToWorld=false` / `canUpdate=false` | `MsUtil` 家族、`Tinkers Domain Test` | ❌ |
| **R4** | 拆索引：`EntityTickList` 移除、`f_143244_`(chunkMap) 的 `byId/byUuid` 清空、换 `EntityLookup` | `MsUtil.superKillEntity`、`witherzilla.ModUtil`、`DiexvUtil` | ❌ |
| **P** | `setPos(NaN)` / `setPos(1e9)` | `MsUtil`、`EntityRemoveUtil`、`ForceHurtUtil.executeall`、`momotinker.Origin` | ❌ |
| **L** | `dropAllDeathLoot` / `captureDrops` | `ForceHurtUtil.forceHurt`（吞掉落）、`SuperbiaDiadema` | 部分 |
| **S** | 别人的 SavedData / 侧表 | toaru `GungnirLedger`、`AbsoluteSeverance` 的 UUID→血量侧表 | ❌ |
| **K** | `kill()` / `die()` / `setHealth(0)` 组合 | `EntityKillerHelp`(AV)、`tcondiadema`、`ticex` | 部分 |

### 2.1 「清场 7 件套」：同一段代码被抄了 8 份（确认）

`javap -c` 读 `jzyy ms/util/MsUtil.KillEntity`，字节码顺序就是完整拆解：

```
putfield Entity.f_146801_ = NULL           ← levelCallback 换空实现
Entity.m_141960_(callback)                 ← setLevelCallback 同一件事再走一遍
entity.passengers.forEach(递归 KillEntity)  ← 连坐
putfield Entity.f_146795_ = KILLED         ← removalReason 直写（绕过 setRemoved）
Entity.m_142036_()                         ← 触发内部移除钩子
Entity.onRemovedFromWorld()                ← 直接调（跳过所有门）
Entity.m_142687_(KILLED)                   ← remove(reason)
Entity.m_142467_(KILLED)                   ← setRemoved(reason)（唯一会撞方法门的一条）
putfield Entity.isAddedToWorld = false
Entity.canUpdate(false)                    ← 永久冻结 tick
new EntityTickList(); tickList.remove(e)   ← 从 tick 表里摘掉
chunkMap.byId.clear(); byUuid.clear()      ← 清实体索引
Brain.stopAll()                            ← 停 AI
tags/caps 清理
LivingEntityUtil.forceSetAllCandidateHealth(e, …)  ← 最后写血
```

同样的类结构出现在：

| Mod | 类 | 覆盖的原语 |
|---|---|---|
| jzyy | `com.csdy.jzyy.ms.util.MsUtil` | 全套 + `Helper` 用 `sun.misc.Unsafe` |
| miztinker | `com.mizi.miztinker.modifier.modifiers.base.MsUtil` | 全套（**逐字复制**） |
| momotinker | `com.momosensei.momotinker.util.Cutter` | 全套 |
| witherzilla | `com.wzz.witherzilla.util.ModUtil` | 全套 + **`ClientboundRemoveEntitiesPacket`**（唯一会直接踢客户端显示） |
| super-steve | `plz.lizi.supersteve.api.SSUtil` | 全套 |
| Tinkers Domain | `com.csdy.tcondiadema.method.Test` | 全套 |
| DiexvCreeper | `net.diexv.diexvcreeper.util.DiexvUtil` | 全套 |
| omnimobs | `flashfur.omnimobs.util.EntityUtil` | 全套 + `mixin.EntityMixin` 改 `setPosRaw` |

> 为什么这件事重要：**这不是 8 个 mod 的问题，是 1 段代码的 8 个副本**。
> 堵住 `MsUtil` 用到的原语，等于同时堵住 8 家；反之漏一条，8 家一起漏。

### 2.2 定点处决（按"数值门"杀人）

| Mod | 入口 | 判据 |
|---|---|---|
| `jzyy:pulverize` | 近战 | 目标生命 < **攻击者最大生命** → 处决 |
| `jzyy:color2` | 颜色词条 | 红：<15%；紫：<7.5% |
| `momotinker:Origin` | `onInventoryTick` | `target.max < player.max` → 穿透伤害 = max + KILLED + `setPos(NaN)` |
| `momotinker:CompassionateEverything` | 词条 | 目标 max < 持有者**临时生命** → 直接击杀 |
| `ticex:Omnipotence` | 词条 | 秒杀 |
| `miztinker:death_note` | 下蹲右键 | **按工具名杀全图同名生物** |
| `tinkers_advanced:Annihilate` | 词条 | 无视大部分原版防御 |
| `tick` 类：`tcondiadema` 每 tick 覆盖 `maxHealth*5e-4` | `Superbia`/`TimeRain`/`Luxuria`/`PhysicalInjury`/`WonderOfYou` | 持续磨血 |

---

## 3. 修改手法（写"实体状态"而不是写"伤害"）

| 目标 | 手法 | 代表类 |
|---|---|---|
| 血量 | D1–D5 五层，最狠的是 `Unsafe` 换 `SynchedEntityData` 子类，让 `set()` 直接写不进去 | `ForceHurtUtil.makeNoHealable` |
| 死亡标记 | 反射写 `dead`（`f_20890_`）不调 `die()` | `AbsoluteSeverance.setEntityDead` |
| 死亡标记（2） | `f_19864_ = true`（`Entity` 的布尔，18 个 mod 在用） | `MsUtil` 家族、`FoxBlade`、`tcondiadema`、`hadean_breathe` |
| 移除 | R1–R4 四层 | `MsUtil` / `ModUtil` / `DiexvUtil` |
| 索引 | 清 `chunkMap.f_143244_` 的 `byId/byUuid`、换 `EntityTickList`（`f_143243_`） | `MsUtil.superKillEntity` |
| 位置 | `setPos(NaN)` / `setPos(1e9)` / `setPosRaw` | `MsUtil`、`omnimobs.mixin.EntityMixin` |
| 属性 | `setBaseValue`、`cachedValue` 直写、**改 `RangedAttribute` 的 min/max 到 ±`Double.MAX`** | `jzyy Help.killMAX`（13 个属性） |
| NBT | `saveWithoutId` → 改 `Health` → `readAdditionalSaveData` | AV `HealthModify.tagModify` |
| 客户端显示 | `ClientboundRemoveEntitiesPacket` / `ClientboundSetEntityDataPacket` | witherzilla `ModUtil`、AV `EntityKillerHelp` |
| 玩家存档/命令 | jzyyfix 用 JVMTI `redefineClass0`/`retransformClasses` 在运行期**重定义我们已装的防护类** | `jzyyfix` |

---

## 4. 防御手法

| 类型 | 代表 | 机制 |
|---|---|---|
| **tag 级免疫** | `Re-Avaritia:infinity`、`jerotes:magic_effect`、`yellowbross:vilvgaver` | 往 `bypasses_invulnerability` 里加自己的伤害类型 → 穿透一切 `isInvulnerableTo`/`Invulnerable:1b` |
| **固定值免疫** | `miztinker:dynamax_armor`（<200 免）、`jzyy:to_embrace`（<100 免 + 99% 减伤）、`bloodshield`（<30 免 + 耗血） | `LivingHurtEvent` 里按数值归零 |
| **i-frame 操作** | `etstlib:short_circuit`（清帧）、`executioner`（无视帧）、`jzyy:guixu`（自身帧更长） | 写 `invulnerableTime` |
| **效果/类型免疫** | `momotinker:unstained`（免疫一切中/负面效果）、`tinkersinnovation:wither_protection`、`void_immunity` | 效果表 / 伤害类型判断 |
| **NaN 守卫** | `NaNny` | 在 `LivingAttack/Hurt/Damage/Heal/Death` 里检测 NaN → cancel |
| **属性上限** | `AttributeFix` | 把 `max_health` 上限拉到 2.1e18、其它走 `Double.MAX_VALUE` |
| **删除保护（代码）** | AV `ProtectRemovalMixin`（`setRemoved`/`remove`/`discard` 前问 `PlayerProtection.block`）、toaru `GungnirProtection`（读侧把 `isRemoved` 改 false） | mixin |
| **环境/垃圾清理** | `sweeper_maid` 每 360s 收 `ItemEntity` + 按名单 `kill()` | 定时任务（无事件） |
| **结构/搬运** | `carryon`（`setRemovalReason(UNLOADED_WITH_PLAYER)` + NBT 重建）、`Capsule`（`discard()` 区域内全部实体） | 合法路径，但会断 UUID 账 |
| **启动期改写** | `jzyy` coremod 在类加载期改写 `LivingEntity#getHealth` 方法体，并把 `m_21223_`/`m_21224_`/`f_20919_` 访问点改线 | coremod |
| **运行期重定义** | `jzyyfix`（JVMTI）重定义已加载类，**不重跑 transformer ⇒ 该类上的 mixin 全消失** | JVMTI |

---

## 5. 操作接口（玩家怎么触发 / 模组怎么自动触发）

| 接口 | 形态 | 例子 |
|---|---|---|
| 匠魂词条 | 工作台装配 + `kubejs/data/**/materials/traits/*.json` | `jzyy:pulverize`、`ticex:omnipotence`、`jzyy:coup_de_grace` |
| 右键/长按物品 | `Item#use` / `useOn` | `miztinker:death_note`（下蹲右键，按工具名杀全图） |
| 常驻物品 | 背包/装备后每 tick 跑 | `momotinker:Origin`（`onInventoryTick`） |
| 事件驱动 | `LivingTickEvent` 自动触发 | `momotinker.AttackUtil.executeall`（半径 200 全清） |
| Boss 技能 | `Mob#tick` / `Goal` / 动画帧 | `eeeabsmobs`、`block_factorys_bosses`、`kaiju_calamity` |
| 命令 | `/…` | `sweeper_maid` 名单、`mobbattle`/`mobbottle`（测试） |
| 结构/方块 | 放置方块触发 | `Capsule`、`witherzilla` 的 Remover 物品 |
| 网络包 | 服务端直接发 `Clientbound*Packet` | `witherzilla.ModUtil` |
| 启动插件 | `IFMLLoadingPlugin` / agent | `jzyy` coremod、`jzyyfix` JVMTI |
| 数据包 | `data/<mod>/damage_type/*.json` + tag | 三家 `bypasses_invulnerability` |

---

## 6. 原理：为什么这些手法挡不住 / 该怎么挡

### 6.1 层的顺序

```
tag 层（damage_type json）        → 决定 hurt 是否被"无视无敌"
  ↓
事件层（LivingAttack/Hurt/Damage）→ D0 走这里
  ↓
方法层（setHealth / set / setValue / setRemoved）
  ↓
字段层（DataItem.value / removalReason / isAddedToWorld / canUpdate / pos）
  ↓
索引层（EntityTickList / chunkMap）→ 实体还在内存，但世界不再 tick 它
  ↓
包层（ClientboundRemoveEntities）→ 服务端还在，客户端没了
```

**越往下，方法边界越少，事件越早失效。** 这就是为什么"事件 + mixin 方法门"在这套面前结构性不足。

### 6.2 对本仓库的直接影响（本轮新增）

| 新发现 | 影响 |
|---|---|
| `canUpdate` 是 Forge 字段 + setter | 之前说"1.20.1 没有 canUpdate"**错了**；防御侧要每 tick 把它读回来并置 true，否则对方一次 `canUpdate(false)` 就把实体永久冻住（我们现在的 `LivingEntityTickFreezeMixin` 只是等价替代，不能阻止对方写这个字段） |
| `MsUtil` 家族 8 份副本 | 原语清单可以按"这 8 个类"收敛，不用按 219 个 jar 铺 |
| `ClientboundRemoveEntitiesPacket` | witherzilla 会在服务端"还活着"的情况下踢掉客户端显示；`isRemoved` 读侧对此无效，必须拦包或重建后强制重新 spawn |
| `f_19864_`（18 个 mod） | 一个 `Entity` 布尔字段被当作"死亡/清除"标记广泛使用；读侧 `isAlive`/`isDeadOrDying` 要连它一起判 |

### 6.3 唯一有效的姿态（不变）

**把"傀儡还活着"变成我们自己保存的、不依赖实体字段的事实**；实体被删就按 NBT 快照重建。
字段/索引层的攻击，只能靠 agent 的 `visitFieldInsn`（写入门）＋ 读侧撒谎 ＋ 重建来兜。

---

## 7. 秘法涡流（ArcaneVortex）——不在这个包里，单独记

AV 在 `1.20.1-Forge_47.4.20` 实例；反编译源在 `%TEMP%\av-src`（1265 个 `.java`）。四把武器最终都落到**它自己类里的 PUTFIELD**：

| 文件 | 写的是什么 |
|---|---|
| `Help/HealthModifyHelp/HealthModify.java` `entityDataModify` | `var6.f_135391_ = var1`（`DataItem.value` 直写） |
| `Help/EntityKillerHelp.java` `setRemoved` | `f_19864_=true` → `f_146795_=reason` → `valid=false` |
| `Content/Item/EntityRemover/RemoveHelp/MainRemove.java:36,51,240` | `f_146795_` / `isAddedToWorld=false` / `f_146801_=空 callback` |
| `Content/Item/Sword/FoxBlade/FoxBlade.java:131,138` | `f_19864_=true` / `setRemoved(KILLED)` |
| `Content/Item/VanSh/VanShCrusher.java` `remove` | 同上组合 + 拆索引 |

`EntityKillerHelp.remove` 里的 `hurt`/`die`/`setHealth`/`setRemoved` 走反射 `tryInvokeMethod`，会撞方法门；
**上面这些字段写不会**。`HealthDataItemSealMixin` 只 redirect `DataItem.setValue` 内部的 putfield，AV 不调它，所以无效。

AV 自己的防御：`ProtectRemovalMixin` 在 `setRemoved`/`remove`/`discard` 前问 `PlayerProtection.block(entity, 1)`，
非白名单实体直接拒绝移除；`GungnirProtection`（toaru）在**读侧**把 `isRemoved` 改成 false。

---

## 8. 限制

- 只做了常量池扫描 + 关键类字节码；没有逐方法读 219 个 jar（`jzyy` 232MB，全量反编译不现实）。
- `lang` 描述可能夸大（例：`jzyy:imagine_breaker_armor` 的"免疫大于 7 的伤害"像笔误）。
- `bypasses_*` tag 是静态读的，运行时可能被 datapack 追加。
- 没有进游戏实测；"谁能打穿谁"仍需 `golems_arsenal` 补依赖后实测。

---

## 9. 本次重跑的产物

| 文件 | 内容 |
|---|---|
| `.tmp-golem/scan-primitives.tsv` | `mod \t 原语 \t 命中 class 数`（161 行 / 66 个 mod） |
| `.tmp-golem/scan-classes.tsv` | 12 个重点 jar 里**具体哪些类**含哪些原语（43 行） |
