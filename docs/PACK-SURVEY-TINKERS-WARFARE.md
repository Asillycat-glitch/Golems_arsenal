# 「Tinkers' Warfare Demon Realm」包扫描：斗蛐蛐生态的机制地图

> 对象：`D:\我自己的包\.minecraft\versions\Tinkers' Warfare Demon Realm\mods\`（219 个 jar）。
> 方法：只读扫描 `META-INF/mods.toml`、`data/*/damage_type/*.json`、`data/*/tags/damage_type/*.json`、
> `assets/*/lang/{zh_cn,en_us}.json`、`config/*.toml|json`、`kubejs/data/**`。
> **没有逐个反编译**；"确认"= 读到了 tag/lang/config 原文，"推断"= 从名字/关键词判断。文末列了限制。
>
> 结论先行：**这个包不是"数值高"，是"每一层都有人拆"**——
> tag 层有人穿 `bypasses_invulnerability`，iframe 层有人清帧，execute 层有人按最大生命处决，
> 门槛层有人免疫 200/100/99%，属性层有人把上限拉到 `Double.MAX_VALUE`。
> 这正是前几轮说的"层战争"的实景，只不过战场从 MUGEN 的 RoundState 换成了 Tinkers 的词条。

---

## 0. 包画像（一屏）

| 项 | 观察 |
|---|---|
| 规模 | 219 个 jar；Tinkers' Construct + 大量附属 + Boss 模组 + 优化/工具 |
| 核心 | `tconstruct` + `tinkers_advanced` + `tinkersinnovation` + `momotinker` + `miztinker` + `Tinkers Domain` + `ticex` + `etstlib` + `TinkersLevellingAddon` |
| 战斗外围 | `goety` + `goety_revelation`、`irons_spellbooks`、`iceandfire`、`vampirism`、`slashblade` + `SJAP`、`Re-Avaritia`、`ProjectE`、`jerotes` + `jerotesvillage` |
| Boss 内容 | `eeeabsmobs`、`block_factorys_bosses`、`kaiju_calamity`、`legendarymonsters`、`the_cosmic_spokespersons`、`ultris`、`trek`、`jzyy`(221MB)、`prinegorerouse`、`noixmodapi`、`oni_miko`、`witherreincarnated` 等 |
| 测试工具 | `dummmmmmy`（试验假人，带 `dummmmmmy:true` 伤害类型）、`mobbattle`、`mobbottle`、`ScalingHealth` |
| 属性层 | `AttributeFix`（把 `max_health` / `attack_damage` / `armor` 上限拉到 2.1e18 / 2.1e14 / 2.1e13 量级，护甲韧性 1,000,000）、`MaxHealthFix`、`NaNny`（NaN 守卫） |
| 数据层 | 巨型 `kubejs/data`：Tinkers 材质/词条定义、`jzyy`/`ticex`/`tcondiadema` 的终局词条 |
| **缺失** | **没有 `modulargolems`、`l2library`、`l2damagetracker`、`mob_weapon_api`、`l2serial`** |

> 最后一行很重要：**本仓库的 `golems_arsenal` 在这个包里跑不起来**。
> 但 `config/attributefix.json` 里还留着 `l2damagetracker:fire_damage` / `crit_damage` / `explosion_damage`
> 的条目——说明这个包曾经装过 l2damagetracker，后来被移除了。配置是旧的，jar 不在了。

---

## 1. T0：谁能穿 `bypasses_invulnerability`

这是全包最硬的一层。1.20.1 的 `bypasses_invulnerability` 只有 `out_of_world` / `generic_kill`，
任何 mod 只要把自己的伤害类型加进去，就能穿 `setInvulnerable(true)`、`Invulnerable:1b`、以及所有检查这个 tag 的减伤。

**扫描结果（确认）：**

| Mod | 伤害类型 | 同时拥有的 tag |
|---|---|---|
| `Re-Avaritia` | `avaritia:infinity` | `bypasses_armor`、`bypasses_cooldown`、`bypasses_effects`、`bypasses_enchantments`、**`bypasses_invulnerability`**、`bypasses_resistance`、`bypasses_shield`、`always_hurts_ender_dragons` |
| `[Jerotes仓库] jerotes` | `jerotes:magic_effect` | `bypasses_armor`、`bypasses_cooldown`、`bypasses_effects`、`bypasses_enchantments`、**`bypasses_invulnerability`**、`bypasses_resistance`、`bypasses_shield`、`no_knockback`、`panic_causes` |
| `yellowbrossextras` | `yellowbrossextras:vilvgaver` | `bypasses_armor`、`bypasses_effects`、`bypasses_enchantments`、**`bypasses_invulnerability`**、`bypasses_resistance` |

**读法**：这三家是"tag 级"的绝对穿透。任何"我 `isInvulnerableTo` 返回 true / 我 `Invulnerable:1b` / 我检查 `BYPASSES_INVULNERABILITY`"的防御，对它们都无效。
Avaritia 的 `infinity` 是三者里最全的（8 个 tag 全齐）；Jerotes 的 `magic_effect` 次之；yellowbross 的 `vilvgaver` 少了 `bypasses_cooldown`/`bypasses_shield`。

**没有**穿 invulnerability、但穿 armor/effects/enchantments/resistance/shield 的（确认）：

| Mod | 覆盖 |
|---|---|
| `eeeabsmobs` | `bypasses_armor`、`bypasses_cooldown`、`bypasses_resistance`、`bypasses_shield`；自定义 `ignore_armor_attack`、`immortal_magic` 等 |
| `tinkersinnovation` | `bypasses_armor`、`bypasses_cooldown`、`bypasses_effects`、`bypasses_enchantments`、`bypasses_resistance`、`bypasses_shield`；自定义 `void` |
| `TConstruct` | `bypasses_armor`、`bypasses_effects`、`bypasses_enchantments`、`bypasses_shield`；自定义 `piercing`、`bleeding`、`self_destruct` |
| `goety` | `bypasses_armor`、`bypasses_effects`、`bypasses_enchantments`、`bypasses_resistance`、`bypasses_shield`；自定义 `doom`、`death`、`life_leech` |
| `Vampirism` | `bypasses_armor`、`bypasses_effects`；自定义 `stake`、`dbno`、`mother`、`sun_damage` |
| `MutantMonsters` | `bypasses_armor`、`bypasses_effects`；自定义 `armor_bypassing_*`、`effects_bypassing_*` |
| `Dodos Mobs` | `bypasses_armor`、`bypasses_cooldown`、`bypasses_shield` |
| `ProjectE` | `bypasses_armor`（`projecte:player_attack`） |
| `legendarymonsters` | `bypasses_armor` |
| `irons_spellbooks` | 自定义 `bypass_evasion`（穿闪避，不穿 invulnerability） |

---

## 2. T1：代码级 bypass（tag 表里看不到，但更狠）

这一层不在 `data/` 里，在 Java 里。以下全部来自 lang 原文（确认），机制是代码实现（推断）。

| Mod / 词条 | lang 原文 | 它绕过了什么 |
|---|---|---|
| `etstlib:reality_breaker` | "破坏'目标无敌'的现实" / "Make your attack bypasses most of vanilla defense" | 无敌 / 原版防御 |
| `etstlib:executioner` | "你的攻击无视敌人的无敌帧" | i-frame |
| `etstlib:short_circuit` | "攻击时移除敌人无敌帧" | 写 `invulnerableTime` |
| `tinkers_advanced:annihilate` | "你的攻击无视大部分原版防御手段" | 原版防御 |
| `tinkers_advanced:electron_tuner.rapier` | "伤害无视护甲和无敌帧" | 护甲 + i-frame |
| `momotinker:degenerate` | "无视护甲和药水保护且不造成无敌帧" | 护甲 + 药水保护 + i-frame |
| `momotinker:origin` | "攻击无视防御并且会削减目标 20%*等级 的最大生命值……最大生命若低于自身会直接归于虚无" | 防御 + 最大生命 + 即死 |
| `momotinker:compassionateeverything` | "攻击和受到的攻击都将无视防御……目标最大生命值小于持有者的暂时生命则会直接击杀" | 防御 + 即死 |
| `jzyy:pulverize` | "近战时处决生命值低于你最大生命值的敌人" | 处决（阈值 = 你的最大生命） |
| `jzyy:color2` | "红: 处决生命值低于 15% 的生物……紫: 处决生命值低于 7.5% 的生物" | 处决 |
| `jzyy:ancient_ocean` | "自身血量高于 88% 时目标无视 100% 护甲" | 护甲 |
| `jzyy:guixu` | "使自己的无敌帧更长" | 自身 i-frame |
| `ticex:omnipotence` | "工具获得秒杀与瞬间挖掘能力" | 即死 |
| `miztinker:death_note` | "下蹲右键秒杀全图和你对应工具'名字'相同的生物" | **按名字全图即死** |
| `miztinker:blood_bat` | "处于蝙蝠状态时,你可以无视所有攻击,除非对面比你强大太多" | 近乎无敌（数值门） |
| `tinkersinnovation:farseeing` | "额外造成百分比真实伤害……最高为最大生命值的 10%" | 真实伤害 |
| `momotinker:hadal` | "攻击会格外造成对方最大生命 5% 的格外伤害，该伤害无视护甲" | 最大生命百分比 + 护甲 |

**读法**：这一层是"写字段/写血/直接 `kill()`"，不是 tag。
`etstlib:short_circuit` 和 `executioner` 就是本仓库 `GolemComboHandler:211` 的同类写法——`target.invulnerableTime = 0`；
`jzyy:pulverize` / `momotinker:compassionateeverything` 是"阈值处决"，和 MUGEN 的「即死」同构；
`miztinker:death_note` 是字面意义上的"指名道姓"，比 MUGEN 的 専用対策 还直接——它按**名字**杀全图。

---

## 3. T2：门槛 / 免疫 / 属性

这一层不追求"打穿"，追求"你打不动我"。

| Mod / 词条 | lang 原文 | 类型 |
|---|---|---|
| `miztinker:dynamax_armor` | "使你免疫 200 点以下伤害" | 固定值免疫 |
| `miztinker:bloodshield` | "免疫 30 点及以下的伤害……每 1 点伤害消耗 0.005 血液值" | 护盾 + 固定值免疫 |
| `miztinker:void_immunity` | "完全免疫虚空伤害" | 类型免疫 |
| `jzyy:to_embrace_the_one_and_guard_the_origin` | "免疫 100 点以下的伤害，获得 99% 的伤害减免" | 固定值 + 百分比 |
| `jzyy:imagine_breaker_armor` | "免疫大于 7 的伤害"（原文如此，疑似笔误/反向门槛） | 门槛（待实测） |
| `jzyy:he_who_conquers_himself_is_strong` | "免疫击退并提升 100% 最大生命值" | 属性 |
| `tinkersinnovation:environment_reject` | "穿戴者免疫无来源伤害" | `DamageSource` 无 entity |
| `tinkersinnovation:wither_protection` | "免疫凋零伤害，半血以下免疫弹射物伤害" | 类型 + 半血门 |
| `momotinker:unstained` | "免疫一切中性与负面效果（除七罪系列效果）" | 效果免疫 |
| `momotinker:lethe` | "免疫除'引渡人'的一切效果" | 效果免疫 |
| `AttributeFix` | `generic.max_health` 上限 `2147483647000000000`（≈2.1e18）、`generic.attack_damage` `214748364700000`（≈2.1e14）、`generic.armor` `21474836470000`（≈2.1e13）；未列出的属性走 `default` = `Double.MAX_VALUE`；护甲韧性上限 `1,000,000` | 属性上限 |
| `MaxHealthFix` | 修复 MC-17876（>20 血重进游戏被重置） | 血量持久化 |
| `NaNny` | 在 `LivingAttack/Hurt/Damage/Heal/Death` 里检测 NaN 并 cancel/rectify | NaN 守卫 |
| `ScalingHealth` | 怪物 HP/伤害随难度增长；玩家伤害缩放；blights | 数值缩放 |

**关键观察**：

- `AttributeFix` 不是"防御"，是**把上限拆掉**。`generic.max_health`、`generic.attack_damage`、`generic.armor_toughness` 等都能到天文数字。
  本仓库 `BYPASS-DESIGN.md` 里"血量地板 / 每击上限"的前提是"上限是个有限值"；在这个包里，上限本身可以被拉到 `Double.MAX_VALUE`。
- `NaNny` 是**事件级**守卫：它只抓 `LivingAttack/Hurt/Damage/Heal/Death` 里的 NaN。
  `setHealth(Float.NaN)` 如果不经过这些事件（例如直接调用），它抓不到——这正是 §10.2 的"写血"路径。
- `jzyy:to_embrace…` 的 "免疫 100 以下 + 99% 减伤" 和 `miztinker:dynamax_armor` 的 "免疫 200 以下" 是**外层钳制**的典型：
  本仓库 `BYPASS-DESIGN.md` §2 第 6 层说的"管线内无解"，在这里是常态。

---

## 4. T3：Boss / 内容层（不逐条展开）

这些是"斗蛐蛐"的靶子，不是机制核心，但决定了"谁能打谁"：

`eeeabsmobs`（27.6MB）、`block_factorys_bosses`（20.6MB）、`kaiju_calamity`（26.6MB）、
`legendarymonsters`（13.3MB）、`the_cosmic_spokespersons`（27.8MB）、`ultris`（14MB）、
`trek`（31.5MB）、`jzyy`（221MB）、`prinegorerouse`（41.8MB）、`noixmodapi`（6.4MB）、
`oni_miko`（6.6MB）、`witherreincarnated`（12.2MB）、`witherzilla`、`too_much_bosses`、
`sigmabosses`、`awakened_bosses`、`binah`、`blighted_beasts`、`mechapillagerboss`、
`pigzilla`、`the_doppelganger`、`the_ravenous`、`the_starved_stalker`、`FADGhosts`、
`fromtheshadows`、`hadean_breathe`、`HyperRemaster`、`masquerader_mod`、`monsterexpansion`、
`omnimobs`、`salmonsgenesisreincarnation`、`thelegendofthebraveii`、`uranus`、`vzling`、
`yellowbrossextras`、`endertrigon`、`Dodos Mobs`、`dungeonsmobs`、`mutantmonsters`、
`crimsonsteves_mutant_mobs`、`illageandspillage`、`creeperoverhaul`、`invade`、`invasioncodered`。

其中几个的自我描述很能说明定位：

- `omnimobs`："Adds some powerful and overpowered mobs"（明说 overpowered）。
- `HyperRemaster`："Ends the discussion of 'Whats the strongest mob?'"（斗蛐蛐专用）。
- `witherreincarnated`："The Wither is a lacklustre boss, this mod aims to fix that"。
- `jzyy`：描述只有 "csmod"；221MB，KubeJS 里它的词条是终局（`coup_de_grace`、`boss_killer`、`six_trillion_years`、`the_millennium_snow`、`fuck_memory_till_die`、`zeno`）。

---

## 5. KubeJS 数据层：真正的"终局装备"

`kubejs/data` 里有大量 Tinkers 材质/词条定义。几个代表性的（确认）：

| 文件 | 内容 |
|---|---|
| `kubejs/data/ticex/tinkering/materials/traits/infinity.json` | `ticex:omnipotence`（秒杀 + 瞬间挖掘）、`cosmic_luck`、`cosmic_unbreakable`、`bedrock_breaker`；护甲侧还有 `transcendental`、`zeno`、`celestial` |
| `kubejs/data/tinkersinnovation/tinkering/materials/traits/void_crystal.json` | 近战/远程 `tinkersinnovation:void`；护甲 `miztinker:void_immunity` |
| `kubejs/data/tcondiadema/recipes/modifier/kill_aura.json` | `tcondiadema:kill_aura`，需要 Avaritia 水晶剑 + 冰火幽灵剑 + 疯狂觉醒终极剑 + 下界合金剑 + Avaritia 烈焰剑 |
| `kubejs/data/kubejs/recipes/modifiers/coup_de_grace.json` | `jzyy:coup_de_grace`（处决/最后一击），3 级 |
| `kubejs/data/kubejs/recipes/modifiers/boss_killer.json` | `jzyy:boss_killer`（对 Boss 特攻） |
| `kubejs/data/kubejs/recipes/modifiers/six_trillion_years.json` | `jzyy:six_trillion_years`（时间系） |
| `kubejs/data/kubejs/recipes/modifiers/the_millennium_snow.json` | `jzyy:the_millennium_snow`（冰系） |
| `kubejs/data/kubejs/recipes/modifiers/fuck_memory_till_die.json` | `jzyy:fuck_memory_till_die`，需要 64×4 `jzyy:overflowed_memory` + `dimensional_administrator` |

`kubejs/server_scripts/tag.js` 只加物品 tag（宝石/刀/大剑/牙齿等），**没有动 damage_type tag**。
`remove.js` 删了一些"免死"类配方（`supersteve:ssp_sign`、`avaritia:infinity_catalyst`、`final_sword:final_sword_god`、
`the_cosmic_spokespersons:outer_universe_entangled_sword`），这是包作者的平衡层——**但删配方不等于删机制**：
已经做出来的物品/词条仍然生效。

---

## 6. 斗蛐蛐的"元游戏"：门 vs 破门

把这个包按"它拦在哪一层"重排，会得到一张和 MUGEN 論外 同构的表：

| 层 | 这个包里的代表 | MUGEN 对应 |
|---|---|---|
| 数据 tag | Avaritia `infinity`、Jerotes `magic_effect`、yellowbross `vilvgaver` 穿 `bypasses_invulnerability` | `bypasses_invulnerability` 类 = 直死/超即死 |
| 代码写字段 | `etstlib:short_circuit` / `executioner` 清/无视 i-frame | 写 `invulnerableTime` / 破帧 |
| 阈值处决 | `jzyy:pulverize`、`momotinker:compassionateeverything`、`ticex:omnipotence` | 即死 / 处决 |
| 真实伤害 | `tinkersinnovation:farseeing`、`momotinker:hadal`、`jzyy:color2` | `TargetLifeAdd` 固定值 |
| 固定值免疫 | `miztinker:dynamax_armor`(200)、`jzyy:to_embrace`(100+99%) | Hyper Armor / 血量地板 |
| 属性上限 | `AttributeFix` → `Double.MAX_VALUE` | 改属性 / 无限血 |
| 点名 | `miztinker:death_note`（按工具名杀全图） | 専用対策（按 `name`） |

**结论**：这个包的"强弱"不是伤害数字，是**门和破门的层数差**。
- 你有 `bypasses_invulnerability`，就能穿别人的"无敌"；
- 你有 `short_circuit`，就能穿别人的 i-frame；
- 你有 `pulverize`（阈值 = 你的最大生命），就能处决所有血量低于你的目标；
- 你有 `dynamax_armor`（免疫 200 以下），就能让所有小伤害失效；
- 你有 `AttributeFix`，就能让"血量上限"这个前提本身失效。

这和 MUGEN 的「当身 vs 直死」「NoKO vs 超即死」「Hyper Armor vs 混線」是同一个结构：
**每一层防御都被下一层的攻击绕过，最后比的是"谁的门更靠下"。**

---

## 7. 对本仓库 `golems_arsenal` 的意义

### 7.1 依赖缺口（硬事实）

这个包**没有** `modulargolems`、`l2library`、`l2damagetracker`、`mob_weapon_api`、`l2serial`。
所以 `golems_arsenal` 在这个包里**不能加载**（`mods.toml` 的依赖检查会直接拒绝）。
`config/attributefix.json` 里还留着 `l2damagetracker:fire_damage` 等条目，说明它曾经在，后来被移除。

### 7.2 如果把它加进去，会发生什么

| 本仓库的防线 | 这个包里的对应攻击 | 结果 |
|---|---|---|
| `GolemsArsenalSetHandler:149` 的 `LivingAttackEvent` + `BYPASSES_INVULNERABILITY` 门 | Avaritia `infinity` / Jerotes `magic_effect` / yellowbross `vilvgaver` | **直接穿**（tag 级） |
| `GolemGrazeModifier:90` 写 `invulnerableTime` | `etstlib:short_circuit`（清帧）、`executioner`（无视帧） | **被清/被无视** |
| `GolemComboHandler:211` 写 `target.invulnerableTime = 0` | 同上 | 对拼帧；对方可能也有"自身帧更长"（`jzyy:guixu`） |
| `DamageRestitutionListener` 的 `nonlinearFinal(9000)` | **没有 l2damagetracker** | **根本不跑**（l2 管线不存在） |
| `setHealth` / 血量地板 | `jzyy:pulverize`（阈值 = 攻击者最大生命）、`momotinker:compassionateeverything`（目标最大生命 < 临时生命 → 直接击杀） | 阈值处决，绕过血量 |
| `AttributeFix` 的天文上限 | `MAX_HEALTH` 可到 ≈2.1e18、`ATTACK_DAMAGE` ≈2.1e14 | 本仓库的 clamp/上限假设失效 |
| `NaNny` | 事件级 NaN | 抓不到 `setHealth(NaN)` 的直写（§10.2） |

**一句话**：这个包是 `BYPASS-DESIGN.md` 里"第 6 层外层钳制"和"第 1b 层 tag 门"的**活体标本**。
你的 `true_edge` 测试台如果搬进来，能测出"哪些是 tag 级、哪些是固定值、哪些是阈值处决"——
但前提是先补齐 `modulargolems` + `l2library` + `l2damagetracker`。

### 7.3 反过来：这个包能教你什么

- **`bypasses_invulnerability` 不是"核选项"，是"标配"**：三家 mod 都在用。本仓库 §6 第 10 条"不给正式内容加"的立场，在这个包里会显得"打不动"。
- **"免疫 200 以下"比"99% 减伤"更难缠**：固定值免疫对小伤害是归零，对高伤害才有效；这正是 `BYPASS-DESIGN.md` §3.1 里 `damage_absorption` 的放大版。
- **阈值处决（按最大生命）是"写血"的上位替代**：它不需要改你的血，只需要在 `LivingHurtEvent` 里判 `target.getHealth() < attacker.getMaxHealth()` 然后 `setHealth(0)`/`kill()`。
- **点名（`death_note`）已经不是隐喻**：它按工具名字杀全图。任何"按名字/类型/注册名"的防御，都会被同样按名字的攻击反制。

---

## 8. 测试建议（如果你想在这个包里验 `golems_arsenal`）

1. **单独开一个实例**，把 `modulargolems`、`l2library`、`l2damagetracker`、`mob_weapon_api`、`l2serial` 补进 `mods/`；
   注意版本：本仓库 `build.gradle` 用的是 `modulargolems 2.7.3`、`l2library 2.5.3-slim`、`l2damagetracker 0.3.7`。
2. **先跑 `/ga debug sweep start 20`**，目标用 `dummmmmmy:target_dummy`（包里有）。
   但注意：`dummmmmmy` 自己的 `dummmmmmy:true` 伤害类型可能干扰读数；`BYPASS-DESIGN.md` §3.5 已经记过"试验假人不可作为靶子"。
3. **按层测**：
   - `plain` → `armor` → `true_edge`：看护甲层和 `damage_absorption` 层；
   - 对 Avaritia/Jerotes/yellowbross 的伤害类型：看 `bypasses_invulnerability` 是否直接穿；
   - 对 `miztinker:dynamax_armor` / `jzyy:to_embrace`：看"固定值免疫"和"99% 减伤"的差别（改伤害量，看 `kept` 是否恒定）。
4. **开 `NaNny` 的日志**：它会打 "A hurt event tried to deal NaN damage to …"；如果看不到这条，但你仍然掉血异常，说明是 `setHealth` 直写。
5. **不要用 `/kill` 做对照**：`generic_kill` 本身就在 `bypasses_invulnerability` 里，会污染 tag 测试。

---

## 9. 限制

- 只读了 `mods.toml`、`data/` tag、`lang`、`config`、`kubejs/data`；**没有反编译**每个 mod 的 Java。
  所以"代码级"那一列是 lang 原文 + 命名推断，不是字节码确认。
- `lang` 里的描述可能夸大/过时（例如 `jzyy:imagine_breaker_armor` 的"免疫大于 7 的伤害"读起来像笔误）。
- 没有实际进游戏测试；`bypasses_*` tag 的成员是静态读取，运行时可能被其它 mod 的 datapack 追加。
- 包里有 219 个 jar，部分可能是客户端/优化/库，不参与战斗；我没有逐个区分 side。
- 这个包没有 `modulargolems`/`l2damagetracker`，所以本仓库的很多结论**不能直接迁移**；需要先补依赖再测。

---

## 参考

- 本仓库：`docs/BYPASS-DESIGN.md`、`docs/THREAT-MODEL-MUGEN.md`、`docs/CROSSMOD-HOOKS.md`
- 扫描产物（临时，未进版本库）：`build/tmp-mods.tsv`、`build/tmp-scan.tsv`
- 1.20.1 damage type tag 语义：`docs/THREAT-MODEL-MUGEN.md` 附录 A.7
