# 跨 mod 联动调研（傀儡军械库 · 1.20.1 Forge）

> 调研范围：整合包 `D:\我自己的包\.minecraft\versions\1.20.1-Forge_47.4.20\mods\`（69 个 jar）。
> 方法：`System.IO.Compression` 只读扫描全部 jar 的 `assets/*/lang/zh_cn.json`，取出 295 条 `effect.<modid>.<id>`；
> 再用 `javap` 反编译关键 `MobEffect` / 注册类 / tag 文件核对 id 与语义。
> **所有 id 均来自实际读取**；读不到的写「未确认」，不猜。

---

## 0. 结论速览

- 《傀儡装配》的 `modulargolems:ignis_attack` 和 l2weaponry 的 `l2weaponry.mats.cata.ignitium`（"腾炎"材质）**都已经在做"叠烙印"**。
  所以本模组的空间在**另一侧**：**读取目标身上已有的烙印 / 减益 / 增益，把它换成额外收益**。
- **炎拳升级已经存在**：`base/upgrade/GolemFlameFistModifier.java` + `GolemUpgrades.FLAME_FIST`（id `golems_arsenal:flame_fist`，
  Config 项 `flame_fist_hits_lv1/lv2`、`flame_fist_ratio`、`flame_fist_cooldown`、`flame_fist_brand_refresh`），
  所以**第 3 部分方案 1 写成"现状 + 缺口清单"，不是从零提案**。
- `cataclysm:blazing_brand` 是真实注册的效果，且被列进 `cataclysm:effective_for_bosses` 效果 tag
  （**对 boss 也生效**），所以"目标有烙印"这个判定在 boss 战里也成立。
- **额外多段伤害必须走带 `bypasses_cooldown` 的伤害类型**。本模组的 tag 文件
  `src/main/resources/data/minecraft/tags/damage_type/bypasses_cooldown.json` 当前值为
  `golems_arsenal:stance_magic`、`golems_arsenal:phoenix_fire`、`golems_arsenal:flame_fist`（炎拳自己的类型）。
  → 新的多段伤害**复用 `flame_fist`**（若语义是火系重拳），或新增类型并进同一个 tag。
  注意 `stance_magic` 同时在 `bypasses_armor.json` 里（无视护甲），**不要**滥用。
- ⚠️ `build/resources/main/` 是**旧产物**（其 `bypasses_cooldown.json` 里还没有 `flame_fist`），
  以 `src/main/resources/` 为准；改完资源记得重新构建。
- **无硬依赖的取 id 姿势**（本项目已有先例，见 `base/upgrade/GolemTrainModifier.java:104`）：
  ```java
  ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("cataclysm", "blazing_brand"))
  ```
  取一次缓存进 `static` 字段，`null` 即视为"该 mod 不在/该效果被删"，全部逻辑短路。

---

## 第 1 部分：可联动的「目标身上的状态」清单

筛选标准：**能改变伤害收支**（增伤 / 易伤 / 破甲 / 持续伤害 / 护盾 / 无敌 / 治疗）才收录。纯功能类（砍树、光合作用、幸运）全部剔除。

| 效果 id | 中文名 | mod | 施加来源 | 战斗语义 |
|---|---|---|---|---|
| `cataclysm:blazing_brand` | 炽热烙印 | 灾变 | Ignis、`cataclysm:ignitium_*` 装备、`blazing_grips`（击中几率）、`gauntlet_of_bulwark`（蓄力推+施加） | **减益**：攻速 −20%、移速 −20%（`EffectBlazing_Brand` 构造器实测两条 `MULTIPLY_TOTAL -0.2`）；本身**不加伤**，是"标记"——`cataclysm:immolator` 对带烙印目标**必定暴击** |
| `cataclysm:abyssal_burn` | 深渊灼烧 | 灾变 | 深渊系（利维坦）攻击 | **持续伤害**；自带伤害类型 `cataclysm:abyssal_burn` 在 `cataclysm:bypasses_hurt_time` tag 里（**不吃无敌帧**） |
| `cataclysm:curse_of_desert` | 沙漠诅咒 | 灾变 | 沙漠系（远古沙碑 / 沙暴） | 减益，配合沙漠装备组 |
| `cataclysm:bone_fracture` | 骨裂 | 灾变 | 骸龙系攻击 | 减益（骨骼类），适合做"骨折增伤" |
| `cataclysm:stun` | 眩晕 | 灾变 | 灾变系重击 | **硬控**：`LivingEntityMixin` 里 `canAttack` 被直接返回 false，目标完全不能攻击 |
| `cataclysm:ghost_sickness` | 鬼魅缠身 | 灾变 | 幽灵系 | 减益，在 `effective_for_bosses` 里 |
| `cataclysm:monstrous` | 骇人之恶 | 灾变 | 怪物化机制 | 在 `effective_for_bosses` 里；增/减益待确认 |
| `cataclysm:abyssal_fear` | 深渊恐惧 | 灾变 | 深渊系 | 减益（恐惧），`blessed_amethyst_crab_meat` 可免疫 |
| `cataclysm:lightning_hex` | Lightning Hex | 灾变 | 灾变系闪电 | 减益（**无中文名**，zh_cn 未翻译） |
| `cataclysm:wetness` | 潮湿 | 灾变 | 水/雨相关 | 状态标记，可做"潮湿增伤（雷/冰）" |
| `l2complements:flame` | 魂火 | 莱特兰-扩充 | `soul_flame_blade` 附魔、`soul_flame_thorn` 附魔、`item.l2complements.soul_flame` | **核心持续伤害**：`FlameEffect extends InherentEffect implements ForceEffect`，`ForceEffect` = **不能被奶/常规净化拆掉** |
| `l2complements:bleed` | 流血 | 莱特兰-扩充 | 武器/附魔 | **可叠加**（`StackingEffect`）：降攻速+移速，每 3 秒一跳伤害；`l2complements:bleed` 在 `minecraft:bypasses_effects` tag 里 |
| `l2complements:armor_reduce` | 破甲 | 莱特兰-扩充 | 莱特兰武器 | 真实护甲削减（`ArmorReduceEffect` 带固定 `UUID ID` 属性修改器） |
| `l2complements:frozen` | 寒流 | 莱特兰-扩充 | `ice_thorn`、冰系武器 | 减益/冻结（`IceEffect` 带 `bleed_*.png` 同类 overlay） |
| `l2complements:stone_cage` | 禁锢 | 莱特兰-扩充 | 莱特兰技能 | **硬控**（石笼，`StoneCageEffect`） |
| `l2complements:curse` | 诅咒 | 莱特兰-扩充 | 莱特兰武器 | 减益（`CurseEffect`，未知/深渊系增伤入口） |
| `l2complements:cleanse` | 净化 | 莱特兰-扩充 | 净化类道具 | **驱散**：`CleanseEffect.clearOnEntity` 清空 `l2complements:skill_effect` tag 内全部效果 |
| `goety:sapped` | 侵蚀 | 诡厄巫法 | 术士/女巫法术 | 减益（`summon_down` 会给新召唤物挂侵蚀+虚弱） |
| `goety:wane` | 衰弱 | 诡厄巫法 | 死灵法术 | 减益（削弱） |
| `goety:impaired` | 锢魂 | 诡厄巫法 | 灵魂系法术 | 减益（灵魂禁锢），在 l2 的 `skill_effect` 里 |
| `goety:doom` | 末日 | 诡厄巫法 | `DoomEffect`（有独立伤害类型 `goety:doom`） | **处决/爆裂**：自带 `data/goety/damage_type/doom.json`，是"用烙印引爆"的同构机制 |
| `goety:stunned` | 震撼 | 诡厄巫法 | 打击系 | **硬控**：`StunnedEvents.isStunned(LivingEntity)` 是公开 API，可无依赖语义调用 |
| `goety:wounded` | 神陨 | 诡厄巫法 | 高阶法术 | 减益（重伤），l2 `skill_effect` 成员 |
| `goety:crippled` | 缚骨 | 诡厄巫法 | 骨系法术 | 减益（残废），l2 `skill_effect` 成员 |
| `goety:void_touched` | 虚空之蚀 | 诡厄巫法 | 虚空法术 | 减益（虚空侵蚀） |
| `goety:ender_flux` | 末影溶解 | 诡厄巫法 | 末影法术 | 减益（末影腐蚀） |
| `goety:burn_hex` | 灼烧魔印 | 诡厄巫法 | 下界系法术 | **火系标记**，与烙印同类，可做"双烙印加成" |
| `goety:corrosion` | 腐蚀 | 诡厄巫法 | 荒野/酸液 | 减益（`acid_venom` 同类，破甲向） |
| `goety:soul_hunger` | 灵魂饥饿 | 诡厄巫法 | 灵魂系 | 减益：削移动/挖掘/攻速/近战伤害 |
| `goety:illague` | 灾厄瘟疫 | 诡厄巫法 | 灾厄系 | 传染型减益 |
| `revelationfix:quietus` | 寂灭 | 诡厄巫法：启示录 | `quietus_star` / `wither_quietus_focus` | **处决向**减益（`QuietusBolt` 实体） |
| `irons_spellbooks:heartstop` | 止心术 | Iron 法术书 | 法术 | **无敌 + 延迟结算**："accumulated damage once it wears off"，天然是"别在无敌时打"的反制点 |
| `irons_spellbooks:abyssal_shroud` | 深渊庇佑 | Iron 法术书 | 法术 | **完全闪避**任何攻击（描述原文 "Dodge any attack"） |
| `irons_spellbooks:evasion` | 闪避 | Iron 法术书 | 法术 | **传送闪避**任何攻击 |
| `irons_spellbooks:charged` | 超负荷 | Iron 法术书 | 法术 | **目标身上的增益**：每级 +10% 攻击、+5% 法术强度、+20% 速度 |
| `irons_spellbooks:oakskin` | 橡肤 | Iron 法术书 | 法术 | **减伤 15% + 每级 5%** → 破盾目标 |
| `irons_spellbooks:rend` | 破甲 | Iron 法术书 | 法术 | **每级 −5% 护甲**（与我们已有 `golems_arsenal:armor_shred` 语义重合，注意别叠加爆炸） |
| `irons_spellbooks:fortify` | 神圣守护 | Iron 法术书 | 法术 | **吸收盾**（absorption hearts） |
| `irons_spellbooks:immolate` | 炽焰献祭 | Iron 法术书 | 法术 | **3 层引爆**：满 3 层爆炸并传染附近目标 |
| `irons_spellbooks:blight` | 枯萎 | Iron 法术书 | 法术 | 每级 −5% 输出、−10% 受疗 → **对傀儡是威胁** |
| `jerotesvillage:bitter_cold` | 苦寒 | Jerotes 村庄 | 苦寒巨斧 / 冰系 | 减益：降移速 + 降击退抗性 |
| `jerotes:bleeding` | 流血 | Jerotes 仓库 | Jerotes 系武器 | **持续伤害**（与 l2 流血同名不同 id，要分别判定） |
| `jerotes:paralyzed` | 麻痹 | Jerotes 仓库 | 电/雷系 | **硬控**（麻痹），配套 `anesthetized` 麻醉 / `hold_mob` 定身 |
| `jerotes:corrosive` | 腐蚀 | Jerotes 仓库 | 酸液 | 减益（破甲向） |
| `jerotes:deadly_poison` | 剧毒 | Jerotes 仓库 | 毒素 | **持续伤害** |
| `spore:marker` | 菌群凝视 | 真菌感染：孢子 | 孢子系真菌 | **标记**（被真菌"锁定"），同类还有 `mycelium_ef` 菌丝感染 |
| `spore:corrosion` | 盔甲腐蚀 | 真菌感染：孢子 | 孢子系 | 破甲（对傀儡威胁大） |
| `jerotes_village_golems:meror_energy` | 莫厄充能 | Jerotes 村庄傀儡 | `LegendaryModifier`（消耗资源获得 "legendary status"） | **自身增益**：`MerorEnergy extends MobEffect`；en_us 描述就是 "Legendary Status"，是"传奇之躯"的入口 |
| `jerotes_village_golems:netherite_boost` | 下界合金强化 | Jerotes 村庄傀儡 | 下界合金相关 | **自身增益**：提升傀儡攻击速度 |
| `modulargolems:manyullyn_effect` | 贪婪 | 傀儡装配 | 玛玉灵材质 | **自身增益**：提升傀儡攻击伤害 |
| `modulargolems:maledictus_rage` | 恶咒 | 傀儡装配 | 咒翼灵骸系 | **自身增益**：提升傀儡攻击伤害 |
| `modulargolems:mechanical_force` | 工业长路 | 傀儡装配 | 机械动力火车材质（body buff） | **自身增益**：提升傀儡攻击伤害（本模组已在用） |
| `modulargolems:mechanical_mobility` | 汽鸣铁道 | 傀儡装配 | 机械动力火车材质（hand buff） | **自身增益**：提升傀儡移速（本模组已在用） |
| `l2artifacts:flesh_overgrowth` | 血肉膨胀 | 莱特兰-古遗物 | 古遗物套装 | 自身增益（生命/回复向），本模组已有 `compat/l2artifacts/` |
| `l2artifacts:frost_shield` | 冰结护盾 | 莱特兰-古遗物 | 古遗物套装 | **护盾**（反伤/吸收向） |

**未确认**：`cataclysm:monstrous` / `lightning_hex`、`goety:void_touched` / `ender_flux` / `burn_hex` 的**具体数值**（只确认 id 与所属 mod）。
`goety` 侧**没有名为 "soul flame" 的 MobEffect**：魂火在 `l2complements:flame`，Goety 的 soul fire 是**方块/火焰状态**
（`fire_breath_focus.info` 只说"射出一股灵魂之火，燃烧目标"）。→ 判定用 `target.hasEffect(l2complements:flame)`
或 `target.getRemainingFireTicks()` / `SoulFireBlock`，**不要**去查不存在的 `goety:soul_flame`。

---

## 第 2 部分：可联动的「事件 / 机制型」hook

| 机制名 | mod | 触发时机 | 我们能怎么用 |
|---|---|---|---|
| `cataclysm:effective_for_bosses`（效果 tag） | 灾变 | 施加效果时 | 官方保证 `blazing_brand` / `abyssal_burn` / `ghost_sickness` 对 boss 也生效 → 炎拳的判定在 boss 战可用 |
| `cataclysm:bypasses_hurt_time`（伤害 tag） | 灾变 | 结算伤害时 | 证明"把伤害类型塞进自定义 tag 来绕过无敌帧"是灾变自己也在用的做法；我们的 `bypasses_cooldown` 同构 |
| `cataclysm:immolator` 的"烙印必暴击" | 灾变 | 命中带烙印目标 | 现成的"烙印 → 收益"先例，可引用它做数值锚；**注意本模组的 `flame_fist` 已经在做"吃烙印"了** |
| `golems_arsenal:flame_fist`（自家，已实现） | 本模组 | `onHurtTarget` | 现成的"查 registry → 判定烙印 → 多段投递"模板，见方案 1；新方案应复用它的 `flameFistSource()` / `isFlameFist()` |
| `EffectStun` 的 `canAttack` 拦截 | 灾变 | `LivingEntity.canAttack` | 眩晕期间目标**完全不能反击** → 傀儡的"处决窗口" |
| `jerotes_village_golems:legendary`（`LegendaryModifier`） | Jerotes 村庄傀儡 | `onAiStep` 维持 `meror_energy` 层数 | 傀儡自己有"传奇存在"层数时，伤害可**无视减伤**并**按目标最大生命缩放**（tooltip 原文） |
| `meror_defense` / `meror_attack` | Jerotes 村庄傀儡 | 受击 / 命中 | 官方减伤与增伤入口，`DamageModifier` 窗口内的同行；本模组 `DamageRestitutionListener` 已明确点名它 |
| `jerotes:counterspell` + `spell_reaction_lock` | Jerotes 仓库 | 施法 / 反制 | 目标身上带这两个 = **法术被锁**；傀儡可以顺势打"反制奖励" |
| `jerotes:mirror_image` / `cloaking` / `true_invisible` | Jerotes 仓库 | 目标隐身/幻象 | 判定"目标不可见"来做非视觉索敌奖励 |
| `l2complements:skill_effect`（效果 tag） | 莱特兰-扩充 | 净化 / 施法 | 一张**跨 mod 的"技能状态"白名单**（含 iron's、goety、cataclysm、tconstruct、traveloptics…）→ 一次性覆盖几十个 mod 的"是否是技能态"判定 |
| `l2complements:cleanse` + `CleanseEffect.clearOnEntity` | 莱特兰-扩充 | 命中时 | 公开静态方法，可直接调用来**驱散目标增益**（无反射） |
| `l2complements:bleed` 的 `StackingEffect` | 莱特兰-扩充 | tick | 层数是可读状态 → "层数换算成额外伤害"的天然旋钮 |
| `minecraft:bypasses_effects`（含 `l2complements:bleed`） | 莱特兰-扩充 | 伤害结算 | 说明 bleed 不吃"效果免疫"；反向给我们的多段伤害选型参考 |
| `L2DamageTracker` 属性 `BOW_STRENGTH` 等 | l2damagetracker | 攻击结算 | 本模组已用反射取（`GolemStanceModifier.resolveL2Attribute`）；远程升级沿用 |
| `DamageModifier` 四档（`add/multBase/multTotal/addExtra` + `nonlinearPre/Middle/Final`） | l2damagetracker | collect → finalize | 多段伤害**不要**写在 `onDamageFinalized`（会被 `setAmount` 覆盖），要么 `onDamage` 注册 modifier，要么 `hurt()` 二次命中 |
| `AttackCache.getLivingHurtEvent()` / `getPreDamage()` | l2damagetracker | 全阶段 | 判定"这一击是否真的进了管线"；`getPreDamage()` 未跑过会抛 `IllegalStateException`，要 try/catch |
| `irons_spellbooks` 的 `AttributeRegistry` | Iron 法术书 | 属性 | 公开字段：`SPELL_POWER`、`COOLDOWN_REDUCTION`、`CAST_TIME_REDUCTION`、`SUMMON_DAMAGE`、9 系 `*_SPELL_POWER` / `*_MAGIC_RESIST` → 傀儡可以"吃法术强度" |
| `golemmagicka` 的 `GolemSpellManager` / `GolemManaInfo` | 傀儡魔法 | 法术施放 | 傀儡**自己会施法**：判断"刚放完法术 / 有法力"来做攻击强化（本模组已有 `compat/golemmagicka/`） |
| `modulargolems` 的 `GolemModifier` 全套钩子 | 傀儡装配 | — | `onAttackTarget`(LivingAttackEvent) / `onHurtTarget`(LivingHurtEvent) / `onKillTarget`(LivingDeathEvent) / `onDamaged`(LivingDamageEvent) / `modifyDamage`+`onDamageMax`+`finalizeHurtTarget`(AttackCache) / `onAiStep` —— **做联动升级只需要覆写对应钩子，不需要自己注册事件** |
| `cataclysm:gauntlet_of_bulwark`（壁垒护手） | 灾变 | 蓄力右键 | 官方"推开 + 施加烙印"；可做傀儡的同类 AOE 烙印技 |
| `create:railway` 材质 → `mechanical_force/mobility` | 傀儡装配 + 机械动力 | 装备时 | 本模组已有 `GolemTrainModifier` 读这两个 buff（`ForgeRegistries.MOB_EFFECTS` 查表），是可复制的模板 |
| `goety` 灵魂能量（`item.goety.soul_energy`） | 诡厄巫法 | 击杀 / 吸收 | "击杀获取资源"型机制，可做傀儡击杀回能 |
| `Legacy: tconstruct` `insatiable_melee`（贪婪，近战） | 匠魂 | 近战命中 | **目标/自身身上的层数型增益**，与玛玉灵 `manyullyn_effect` 同名机制，可统一处理 |
| `twilightforest:frosted` | 暮色森林 | 冰系命中 | 霜冻标记，与 `l2complements:frozen` 组"双冰" |
| `goety_revelation:quietus` | 诡厄巫法：启示录 | 命中 / 死亡 | 处决型，可做"低血 + 寂灭 = 斩杀" |
| `composite_material:degeneration`（禁疗） | 复合材料 | 命中 | **禁疗**是硬通货：给傀儡一个"挂禁疗"的升级，压制有自愈的 boss |
| `composite_material:suppression`（抑制） | 复合材料 | 命中 | 抑制型减益，具体作用**未确认** |
| `spore:madness`（疯狂）/ `uneasy`（不安） | 真菌感染：孢子 | 感染扩散 | 傀儡被孢子感染时的负面状态 → 做"净化/反感染"型升级 |

---

## 第 3 部分：联动升级设计方案

统一约定：
- **触发层**用《傀儡装配》的 `GolemModifier` 钩子名标注（`onHurtTarget` = `LivingHurtEvent`，`onAttackTarget` = `LivingAttackEvent`，
  `onKillTarget` = `LivingDeathEvent`，`onDamaged` = `LivingDamageEvent`，`modifyDamage` / `onDamageMax` = l2 `AttackCache`）。
- **多段伤害投递**统一用本模组已验证的姿势：`GeneralEventHandler.schedulePersistent` 延迟 1 tick + `target.hurt(自定义伤害源, 值)`
  （见 `base/upgrade/GolemStanceModifier.java:127`），伤害类型必须带 `bypasses_cooldown`。
- **取 id** 统一用 `ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(mod, id))` + `static` 缓存 + `null` 短路。

---

### 1. 炎拳升级（Flame Fist，`golems_arsenal:flame_fist`）— **已实现**，P0 收尾

> 现状代码：`base/upgrade/GolemFlameFistModifier.java`、注册 `GolemUpgrades.FLAME_FIST`（`GolemUpgrades.java:113`）、
> 物品 `ModItems.java:187`（`flame_fist_upgrade`）、伤害类型 `data/golems_arsenal/damage_type/flame_fist.json`（已登记进 `bypasses_cooldown.json`）。
> 下面是**实际行为**与**待补缺口**，不是新提案。

- **联动对象**：`cataclysm:blazing_brand`（炽热烙印）—— 已是准确 id。
- **触发**：`onHurtTarget`（`LivingHurtEvent`，目标侧）；先排除弹射物命中与自家技能伤害
  （`GolemCritHandler.isOwnSkillDamage`，防炎拳自我触发）；多段在 `schedulePersistent` 里**每段隔 1 tick** 投递。
- **效果（实际值）**：目标带烙印 → 追加 `flame_fist_hits_lv1`（默认 **2**）/ `flame_fist_hits_lv2`（默认 **3**）段，
  每段 = 本次命中伤害 × `flame_fist_ratio`（默认 **0.35**）；冷却 `flame_fist_cooldown`（默认 **20 tick**）；
  2 级在最后一段顺带把烙印续到 `flame_fist_brand_refresh`（默认 **60 tick**）。
- **实现要点（已落地，可直接照抄给别的方案）**：
  - 无硬依赖：`ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("cataclysm","blazing_brand"))` + `static` 缓存 + `null` 短路；
    `blazingBrand()` 为 `null` 时 tooltip 显示 `flame_fist.no_compat`（红字），**不崩、不假装生效**；
  - 伤害类型 `golems_arsenal:flame_fist` 已在 `bypasses_cooldown.json` 里（不被无敌帧吃掉），
    且**不在** `is_fire` / `is_magic` / `bypasses_armor` 里 → 不会被火免/魔免取消，**护甲照常生效**；
  - 段间隔 1 tick 是为了避开 l2 伤害缓存的递归分支；投递前检查 `golem.isAlive()`、`target.isAlive()`、同维度；
  - **不碰 l2 `DamageModifier`**：炎拳是独立二次命中，不改主伤害，所以天然绕过 `DamageRestitutionListener` 等 collect 阶段逻辑。
- **缺口 / 待补护栏（建议下一步）**：
  1. **敌对性判定缺失**：当前只靠 `target.hurt()` 自身规则，没有显式 `!(target instanceof Player)` / 友军排除 —— 建议补上，避免傀儡 PVP 或误伤盟友。
  2. **冷却默认 20 tick 偏短**：`hits=2~3 × 0.35 = 70%~105%` 的额外伤害在 1 秒内可再触发一次，建议实战实测后上调（或按等级给 40/60 tick）。
  3. **每段独立 `hurt()`**：若想整组只结算一次（更可控的数值曲线），可改成"累加后在最后一段一次性投递"，代价是失去手感。
  4. **`build/resources/main/` 是旧产物**（其中的 `bypasses_cooldown.json` 还没 `flame_fist`），改完资源要重新构建再测。
- **强度档位**：**P0（已完成；只剩调数值 + 补敌对判定）**。
- **与已有内容不重复**：`modulargolems:ignis_attack` / l2weaponry 腾炎材质负责**叠烙印**，炎拳负责**吃烙印**；
  2 级的"续烙印"只是延长已存在的那一条（`refreshBrand` 保留原 `amplifier`，不新增、不叠层），
  **不要**把它改成"命中就给烙印"，那会和上面两者正面撞车。

### 2. 霜印碎击（Frost Sigil Crush，`golems_arsenal:frost_sigil_crush`）— P1

- **联动对象**：`l2complements:frozen`（寒流）+ `twilightforest:frosted`（霜冻）
- **触发**：`onHurtTarget`；命中时读目标效果，直接在主伤害上走 `DamageModifier.nonlinearFinal`（不产生新命中）。
- **效果**：目标带任一冰系标记时主伤害 **+25%**；两个都在则 **+40%** 并施加 `minecraft:slowness` II / 60 tick；无冷却（靠标记自然限速）。
- **实现要点**：两个 id 都走 `ForgeRegistries` + 缓存；`DamageModifier.nonlinearFinal(ORDER_PRIORITY, x -> x * mult)`
  注册在 `GolemModifier.modifyDamage(AttackCache, golem, level)` 钩子里（**这是傀儡装配直接给的入口，不需要自己 `AttackListener.register`**）；priority 取 `9500`（在返还之后，读终值）。
- **平衡与护栏**：只对 hostile；增伤乘区独立于姿态/返还，做 clamp `min(mult, 1.6)`；不对玩家生效。
- **强度档位**：**P1**（冰系标记需要其他 mod 先手，组合面窄但上下限清楚）。

### 3. 深渊灼烧·引燃（Abyssal Detonate，`golems_arsenal:abyssal_detonate`）— P2

- **联动对象**：`cataclysm:abyssal_burn`（深渊灼烧）
- **触发**：`onHurtTarget` 判定 + 1 tick 延迟投递。
- **效果**：若目标带深渊灼烧，**消耗该效果**（`target.removeEffect`）并造成 `剩余时长(秒) × 4` 点火焰魔法伤害（上限 40），1 段，冷却 200 tick。
- **实现要点**：读 `MobEffectInstance.getDuration()` 做数值来源；伤害类型用新的 `golems_arsenal:abyss_detonate`（进 `bypasses_cooldown`，并加进 `minecraft:is_fire` tag 以便火焰免疫/吸收逻辑一致）；`forceFireImmunity` 类目标要排除。
- **平衡与护栏**：上限硬编码 40；对 `cataclysm:netherite_monstrosity` 等 boss 需要 `cataclysm:effective_for_bosses` 语义确认（**未确认 boss 是否可被移除**，实现时用 `MobEffectEvent` 的可见性做兜底）。
- **强度档位**：**P2**（要动灾变的 debuff 生命周期，风险高于收益，先做原型）。

### 4. 涤罪重击（Purge Strike，`golems_arsenal:purge_strike`）— P1

- **联动对象**：`l2complements:skill_effect`（效果 tag）+ `l2complements:cleanse`
- **触发**：`onHurtTarget`（主伤害乘算）。
- **效果**：命中时统计目标身上属于 `l2complements:skill_effect` tag 的**增益**数量 `n`：
  主伤害 `×(1 + 0.08n)`（n 上限 6）；`n ≥ 3` 时额外调用 `CleanseEffect.clearOnEntity(target)` 驱散一次；冷却 300 tick。
- **实现要点**：tag 用 `TagKey.create(Registries.MOB_EFFECT, new ResourceLocation("l2complements","skill_effect"))` +
  `target.getActiveEffects()` 过滤 `instance.getEffect().is(tagKey)`；`CleanseEffect.clearOnEntity` 是 `public static`，
  但**它是硬依赖**（类加载即崩），必须放进 `compat/l2complements/` 包并由 `ModList.get().isLoaded("l2complements")` 门控注册，
  或者退化成"自己 `target.removeEffect(effect)` 逐个删"的纯反射/纯 registry 版本（**推荐后者**，更安全）。
- **平衡与护栏**：只驱散 target 侧且只驱散 tag 内成员（不会误删 `minecraft:instant_health` 之类瞬时效果）；冷却 300 tick；不对玩家生效。
- **强度档位**：**P1**（tag 覆盖面大、收益直观，但需要谨慎处理"删别人的 buff"引发的 mod 冲突）。

### 5. 反咒铁拳（Counterspell Knuckle，`golems_arsenal:counterspell_knuckle`）— P1

- **联动对象**：`irons_spellbooks:abyssal_shroud`、`irons_spellbooks:evasion`、`irons_spellbooks:heartstop`、`irons_spellbooks:oakskin`、`irons_spellbooks:fortify`
- **触发**：`onHurtTarget`（部分字段需要在 `LivingAttackEvent` 侧读，因为 `abyssal_shroud` / `evasion` 会让伤害**根本不进管线**）。
- **效果**：目标带"免伤/闪避"类增益时，本次命中**附加一次无视闪避的结算**：把主伤害的 **35%** 用 `golems_arsenal:stance_magic`（`bypasses_armor` + `bypasses_cooldown`）在 1 tick 后投递；
  目标带 `oakskin` / `fortify`（纯减伤/护盾）时改成主伤害 **+20%**；冷却 120 tick。
- **实现要点**：`evasion` / `abyssal_shroud` 会 cancel 事件 → **必须在 `onAttackTarget`（LivingAttackEvent）里就记账**，
  不能只在 `onHurtTarget` 里查；用 `golem.getPersistentData()` 存 `pending_counter` 标记，1 tick 后消费。
  注意 `l2complements:skill_effect` 已包含这几个 iron's 效果，可以直接用 tag 代替逐个 id（更抗版本变动）。
- **平衡与护栏**：只对 hostile；冷却 120 tick；**不对玩家生效**（PVP 里"必定穿闪避"过强）；只能穿"闪避"，不能穿 `bypasses_invulnerability` 那种真免疫。
- **强度档位**：**P1**（反制感强，但需要处理"事件被 cancel"这条路径）。

### 6. 窃法之拳（Spell Siphon，`golems_arsenal:spell_siphon`）— P2

- **联动对象**：`irons_spellbooks:charged`（超负荷）、`golems_arsenal:charge`（自身充能，已有）
- **触发**：`onKillTarget`（`LivingDeathEvent`）+ `onHurtTarget`。
- **效果**：击杀带 `irons_spellbooks:charged` 的目标时，给傀儡自己挂 **`golems_arsenal:charge`**（本模组已有，见 `init/GolemEffects.java`）**60 tick**；
  若傀儡已有 `charge`，则本次攻击（`onHurtTarget`）改为 **+3 段 × 8%**。
- **实现要点**：`golems_arsenal:charge` 是自家效果，直接引用；`irons_spellbooks:charged` 走 registry 查表。
  `onKillTarget(golem, target, LivingDeathEvent, level)` 是 `GolemModifier` 现成钩子。
- **平衡与护栏**：击杀才给、且只给 60 tick，自然限速；不对玩家生效（击杀玩家不给）。
- **强度档位**：**P2**（依赖"敌人身上正好有超负荷"，触发面窄，更适合做套装联动件）。

### 7. 莫厄余烬（Meror Afterglow，`golems_arsenal:meror_afterglow`）— P1

- **联动对象**：`jerotes_village_golems:meror_energy`（莫厄充能 / "Legendary Status"）
- **触发**：`onHurtTarget`，读**傀儡自己**的效果层数 `amplifier`。
- **效果**：傀儡身上 `meror_energy` 每层使本次命中 **+6% 伤害**（上限 5 层 = +30%），
  并且**层数满 5 时**这一击的部分伤害（20%）走 `golems_arsenal:stance_magic` 无视护甲。
- **实现要点**：`ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("jerotes_village_golems","meror_energy"))`；
  层数读 `MobEffectInstance.getAmplifier()`；`meror_energy` 由 `LegendaryModifier.onAiStep` 维持，
  我们**只读不写**，避免和 Jerotes 自己的"消耗资源换层数"逻辑打架。
- **平衡与护栏**：只读不写；上限 5 层；只对 hostile。
- **强度档位**：**P1**（"传奇之躯"本来就有"无视减伤"语义，我们只做小幅加成，不抢它的定位）。

### 8. 缚魂重锤（Soulbind Maul，`golems_arsenal:soulbind_maul`）— P1

- **联动对象**：`goety:impaired`（锢魂）、`goety:wounded`（神陨）、`goety:crippled`（缚骨）、`goety:sapped`（侵蚀）、`goety:wane`（衰弱）
- **触发**：`onHurtTarget`（主伤害乘算 + 1 段追加）。
- **效果**：目标身上的**诡厄巫法减益每有一种**，本次命中 **+7%**（5 种全中 = +35%）；≥3 种时追加 **1 段 20%** 伤害（`bypasses_cooldown`）；冷却 80 tick。
- **实现要点**：用**硬编码 id 列表**（5 个 `ResourceLocation`）+ registry 查表而非 `goety` 的 tag（`goety` 没有合适的减益 tag）；
  计数走 `target.getActiveEffects()`；一段额外伤害走延迟投递。
- **平衡与护栏**：只对 hostile；冷却 80 tick；对玩家不生效；每种减益只计一次（用 `Set<MobEffect>` 去重）。
- **强度档位**：**P1**（诡厄巫法的减益覆盖面很广，实战触发率高，是"稳定收益"位）。

### 9. 驱散铁律（Dispel Mandate，`golems_arsenal:dispel_mandate`）— P2

- **联动对象**：`jerotes:counterspell`（法术反制）、`jerotes:spell_reaction_lock`（法术反应封锁）、`revelationfix:counterspell`
- **触发**：`onHurtTarget`。
- **效果**：目标带"反制/封锁"类效果时，傀儡本次命中 **+15%** 并把目标的这两个效果**剩余时长翻倍**（压制施法者）；冷却 200 tick。
- **实现要点**：`MobEffectInstance` 是**不可变**的，改时长要 `target.removeEffect(effect)` + `target.addEffect(new MobEffectInstance(effect, newDur, amp))`；
  注意 `revealationfix` 与 `jerotes` 的 `counterspell` 是**两个不同 mod 的同名 id**，要分别查。
- **平衡与护栏**：只增时长不叠层（`amplifier` 原样）；冷却 200 tick；不对玩家生效。
- **强度档位**：**P2**（收益偏"战术"而非"数值"，适合做特殊件）。

### 10. 屠戮余响（Slaughter Echo，`golems_arsenal:slaughter_echo`）— P1

- **联动对象**：**环境/事件型** —— 傀儡击杀目标的那一刻（`onKillTarget` / `LivingDeathEvent`）
- **触发**：`onKillTarget`。
- **效果**：击杀 hostile 时，把**这一击的溢出伤害**（`target.getMaxHealth()` 相关不可靠，改用 `LivingDamageEvent` 里记录的最终值 − 目标死亡前生命）
  转成 **3 段 × 溢出 × 25%** 的溅射，只打**同类**目标（`target.getType() == victim.getType()`），半径 4 格，上限每段 20；冷却 60 tick。
- **实现要点**：需要同时用 `onDamaged`（`LivingDamageEvent`，记录最终伤害）和 `onKillTarget`（消费），用 `golem.getPersistentData()` 传值；
  溅射伤害用 `golems_arsenal:lion_slash` 或 `phoenix_impact`（**已有**的 damage_type，见 `data/golems_arsenal/damage_type/`），
  但这两个**是否已进 `bypasses_cooldown` 需要确认**（当前 tag 里只有 `stance_magic` 与 `phoenix_fire`）。
- **平衡与护栏**：只溅射同类且 hostile；半径 4；每段上限 20；不对玩家生效；冷却 60 tick。
- **强度档位**：**P1**（"割草感"强，但需要 `bypasses_cooldown` 的伤害类型准备好）。

### 11. 天候共鸣（Weather Resonance，`golems_arsenal:weather_resonance`）— P2

- **联动对象**：**环境型** —— 维度 + 天气 + `cataclysm:wetness`（潮湿）
- **触发**：`onHurtTarget`（`golem.level()` 读环境）。
- **效果**：按环境给不同增益，**同一时刻只生效一个**（优先级：下界 > 末地 > 主世界下雨 > 其他）：
  下界 → 火焰伤害段 +1（+12%）；末地 → 虚空系增伤 +15%；主世界 `isRaining()` → 目标带 `cataclysm:wetness` 时 +20% 且施加减速。
- **实现要点**：`golem.level().dimension()` 比 `ResourceKey`；`level().isRaining()`；维度 id 用 `Level.NETHER` / `Level.END` 比较；
  炎拳/雷系相关部分与方案 1 共用 `blazing_brand` 判定。
- **平衡与护栏**：只对 hostile；**不做乘算叠乘**（选最高一档）；冷却 0（靠环境自然限速）。
- **强度档位**：**P2**（风味强、数值弱，适合做"环境专精"套装）。

### 12. 烙印收割（Brand Reaper，`golems_arsenal:brand_reaper`）— P2

- **联动对象**：`cataclysm:blazing_brand` + `goety:burn_hex`（灼烧魔印）+ `l2complements:flame`（魂火）+ `l2complements:bleed`（流血）
- **触发**：`onAiStep`（每 20 tick 扫一次附近目标）+ 命中时。
- **效果**：**炎拳的上位套装件**：只要目标身上**同时**存在 ≥2 种"标记/持续伤害"（烙印 / 灼烧魔印 / 魂火 / 流血），
  该目标受到的傀儡伤害 **+18%**，且傀儡对其命中不加冷却地吃炎拳的 1 段（**与方案 1 互斥安装**，`canExistOn` 或运行时检测对方升级）。
- **实现要点**：`onAiStep` 做 4 格内敌人扫描并写 `PersistentData` 标记；互斥检测真实 id 是 `GolemUpgrades.FLAME_FIST.get()`（`GolemUpgrades.java:113`）后短路。
  注意 `l2complements:bleed` 是 `StackingEffect`，层数读 `amplifier`。
- **平衡与护栏**：只对 hostile；+18% 与炎拳的 45% **不叠加**（互斥）；扫描半径 4、每 20 tick。
- **强度档位**：**P2**（做得漂亮但耦合重，适合后期内容）。

---

## 第 4 部分：工程建议

### 4.1 需要「是否存在」容错的 id

| 风险等级 | id | 处理 |
|---|---|---|
| **必须容错**（mod 可能不在包里） | `cataclysm:blazing_brand`、`cataclysm:abyssal_burn`、`cataclysm:stun`、`cataclysm:bone_fracture`、`cataclysm:wetness` | `ForgeRegistries.MOB_EFFECTS.getValue(...)` + `static` 缓存；`null` → 整个升级 inert。**不要** `ModList.isLoaded` 之后直接 `import` cataclysm 类（会 LinkageError） |
| **必须容错** | 全部 `irons_spellbooks:*`、`goety:*`、`l2complements:*`、`jerotes*:*`、`revelationfix:*`、`spore:*`、`composite_material:*`、`tconstruct:*`、`twilightforest:frosted` | 同上传表；tag 形式（`l2complements:skill_effect`）也要 `null` 容错（tag 可能不存在） |
| **可硬依赖**（已在 `libs/`） | `l2damagetracker`、`modulargolems`、`l2library` | 直接 `import`，本项目已在用 |
| **可门控硬依赖**（有 `compat/<mod>/` 先例） | `golemmagicka`、`mekanism` | `CompatDispatch` 里 `ModList.get().isLoaded(...)` 门控 + `compat/<mod>/` 包内直接引用 API（**当前项目的既有模式**） |
| **禁止硬依赖** | `l2complements`（`CleanseEffect.clearOnEntity`） | 要么放 `compat/l2complements/` 并门控，要么退化成"自己遍历 `getActiveEffects()` 逐个 `removeEffect`" |

### 4.2 建议的代码组织

沿用现有习惯，**新增两个层次**：

```
src/main/java/a_silly_cat/golems_arsenal/
├── base/upgrade/
│   ├── GolemUpgrades.java              # 注册新的 GolemModifier（现有）
│   ├── GolemFlameFistModifier.java     # 方案 1（已存在，只调数值/补敌对判定）
│   ├── FrostSigilCrushModifier.java    # 方案 2 …
│   └── ...
├── compat/
│   ├── crossmod/    # ★新增：CrossModEffects（id→MobEffect 缓存查表 + hasEffect/level）、
│   │                #   CrossModTags（TagKey<MobEffect> 懒加载）、CrossModDamage（延迟多段投递统一入口）
│   ├── cataclysm/   # 只有需要硬引用灾变类时才建；否则全靠 CrossModEffects
│   ├── l2complements/  # CleanseEffect 这类必须门控的
│   └── l2damagetracker/  # 现有（DamageRestitutionListener 等）
```

- `CrossModEffects` 建议放一个 `ConcurrentHashMap<ResourceLocation, Optional<MobEffect>>` 缓存，只暴露 `boolean has(LivingEntity, String mod, String id)` 与 `int level(...)`（返回 `amplifier`）；
  `CrossModDamage` 收口"延迟 1 tick + `hurt()`"，并在开发环境用 `source.is(DamageTypeTags.BYPASSES_COOLDOWN)` 断言。
  **不要让每个 Modifier 各自写一遍查表**（`GolemTrainModifier` 已经这么干过一次，别再复制第三遍）。
- **不需要**为每个方案单独注册 `AttackListener`：`GolemModifier` 已经提供 `modifyDamage` / `onDamageMax` / `finalizeHurtTarget` 三个 `AttackCache` 钩子，优先用它们。

### 4.3 与已有内容重复 / 需要避让的地方

| 已有内容 | 重复风险 | 建议 |
|---|---|---|
| `modulargolems:ignis_attack`（叠烙印 + 吸血 + 低血穿甲） | **叠烙印这一侧已被完全覆盖** | 只做"吃烙印"（方案 1、12），**绝不再加一个"命中叠烙印"** |
| l2weaponry 腾炎材质（`l2weaponry.mats.cata.ignitium`） | 同上 | 同上；若傀儡装备腾炎武器，炎拳是**乘法叠加**，靠 `flame_fist_cooldown`（现默认 20 tick）限速 |
| `jerotes_village_golems:legendary` / `ultimate`（传奇状态 → 无视减伤 + 按目标最大生命缩放） | 方案 7 若做太强会**抢定位** | 方案 7 只做 **+6%/层**（上限 30%）并**只读不写** `meror_energy` |
| `jerotes_village_golems:meror_defense`（非莫厄伤害 −X%） | 本模组 `DamageRestitutionListener` 已经明确针对它做"返还" | 任何新增的"无视减伤"都要**显式说明是否穿它**，别默默叠加 |
| `golems_arsenal:armor_shred`（自家破甲）+ `irons_spellbooks:rend` + `l2complements:armor_reduce` + `tconstruct:pierce` | 四个破甲来源可能把护甲打到 0 → 数值崩坏 | 破甲类升级做**互斥**或设下限（`max(armor, 0)` 由 MC 保证，但增伤要 clamp） |
| `golems_arsenal:charge`（自家充能）+ `irons_spellbooks:charged`（超负荷） | 同名不同源，容易在代码里搞混 | 一律写全 id；方案 6 有意把两者连起来，要在注释里写清 |
| `GolemTrainModifier` 读 `modulargolems:mechanical_force/mobility` | 机制上重复（都是"读自身 buff 换收益"） | 复用它的 `handBuff()` / `hasHandBuff()` 接口，不要重写 |
| `GolemStanceModifier.magicSource()` + `golems_arsenal:flame_fist` | 多段伤害投递已有一整套 | **优先复用 `flame_fist`**（已带 `bypasses_cooldown`、**吃护甲**、不吃火免/魔免，且有 `isFlameFist()` 防自我触发）；只有"要无视护甲"时才用 `stance_magic` |
| `compat/l2artifacts/ArtifactSetHelper.java` | 已有一套"读别人 mod 状态"的工具 | 先读它，能复用就别在 `crossmod/` 里另起炉灶 |

### 4.4 落地顺序建议

1. **P0（补完）**：方案 1（炎拳，已存在）—— 补敌对判定 + 上调冷却；并把 `CrossModEffects` + `CrossModDamage` 从 `GolemFlameFistModifier` 里**抽出来**（别让后面 5 个方案各抄一遍查表与投递）。
2. **P1**：方案 2（霜印）、方案 4（涤罪）、方案 7（莫厄余烬）、方案 8（缚魂）、方案 10（屠戮余响）—— 都是"读 tag/效果 → 乘算或加段"，工具类就位后成本极低。
3. **P2**：方案 3、5、6、9、11、12 —— 涉及事件被 cancel、改别人的 debuff 生命周期、或耦合过重，留到有实测数据后再做。
