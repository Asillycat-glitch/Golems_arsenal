# 傀儡地牢「试炼」机制速查（1.20.1 / golemdungeons-1.0.7）

本文是把 `golemdungeons-1.0.7.jar` 反编译后整理出的试炼机制要点，用于平衡分析与写兼容代码时的对照。
原始反编译产物（第三方，不入库）在仓库根目录 `gd-jar*/`，英文完整版报告见根目录 `golem-trial-mechanics.md`。

## 1. 流程

| 项 | 值 | 出处 |
|---|---|---|
| 存档里的试炼 id | `golem_spawner` 方块实体的 NBT（结构文件带 `state=idle`），也可用 `hostile_summon_wand` 右键改写 | `HostileSummonWand.java:107-121` |
| 触发条件 | 试炼 id 非空 + 无正在跟踪的玩家 + 冷却结束 + 有非旁观玩家进入区域内 | `GolemTrialBlockEntity.tick():172-210` |
| 区域 | **方盒**：`|dx| <= triggerRange && |dz| <= triggerRange && minY < dy < maxY`（Y 是严格不等） | `TrialConfig.java:65-72` |
| 单体刷新间隔 | 10 tick（0.5 s）一只 | `TrialData.java:27-29` |
| 波次间隔 | 场上 `trialGolems` 清空后 40 tick 开下一波 | 同上 |
| 胜利 | 走完 `trial.list` 所有波次且场上无试炼傀儡 | `TrialData.java:75-83` |
| 失败 | **没有失败条件、没有限时** | 全文 |
| 全部玩家离开跟踪 | `stop()`：丢弃所有试炼傀儡、回到 CHARGING，下次从**第 1 波**重来 | `TrialData.java:171-183` |
| 跟踪丢失 | 距刷怪点 > 48 格（2304 格²）或换维度 → 该实体/玩家被移出跟踪名单 | `GolemTrialBlockEntity:388-395` |
| `spawnCost` | 不是每只的价钱，是**充能点数**：每次刷新 `addCost(cost)` 累加到 `lastCost`，`lastCost` 每 tick 自动 -1，用来卡"下一只/下一轮" | `GolemTrialBlockEntity.addCost():262-274` |
| 奖励 | 走 `config.reward` 战利品表，参数集为空（不吃幸运/击杀者）；`generateChest=false` 时直接掉在刷怪点上方 | `GolemTrialBlockEntity.complete():295-334` |

**可利用点**：傀儡被拉开 48 格就会被移出跟踪名单，所以把敌人引远可以直接"清波"（人离远了则会整体重置到第 1 波）。
**不能改的**：40/10/60 tick 计时、48/24 格半径都是硬编码；难度、波次大小、玩家数量缩放一概没有，全部由数据包 JSON 决定。

## 2. 敌人是怎么造出来的

`SpawnConfig.summon/createGolem`：按 `spawn/*.json` 的 `types/materials/upgrades/equipments` 造**真正的傀儡装配傀儡**，
材质属性直接读 `modulargolems_config/materials/*.json`，因此敌人的血量/攻击就是材质值 × 部件数（金属 4 部件，`MetalGolemPartType = RIGHT/BODY/LEFT/LEG`），
所有者是敌对阵营 uuid（`isHostile()==true`），模式固定 GUARD（锚定刷怪点）。
`upgradeChance` 逐项掷骰、**一次失败就停止**（默认 `{1.0,0.5,0.5,0.5}`）。
装备里的 `enchantLevel` 会走 `EnchantmentHelper.enchantItem`，所以 `enchantLevel: 30` 的敌人是**带满级附魔**的（保护 IV / 锋利 V）。
玩家傀儡与试炼傀儡**互相都能打**，地牢不禁止也不保护（`AbstractGolemEntity.canAttack:748-751` 的敌对性捷径）。

## 3. 「传奇之躯」（refine_meror）试炼的敌人档位

- 材质 `jerotes_village_golems:refine_meror`：`attack 30 / max_health 400 / sweep 1`，
  修改器 `fire_affinity 1, legendary 1, meror_attack 1, meror_defense 2, add_slot 1, armor_penetration 2, magic_immune 1`。
  属性与修改器都**按部件累加**（`GolemMaterial.collectModifiers:120-131`，封顶到各修改器 maxLevel）。
- 护甲组 `meror_large_armor_refine`：`refine_meror_helmet/chestplate/shinguard` = 护甲 13+18+10=41、韧性 30；
  每件还给 `meror_defense +1`，并挂 `l2damagetracker:damage_reduction` **-0.2 MULTIPLY_TOTAL**（3 件 → ×0.512）。
- 武器 `meror_large_weapon_refine`：`refine_meror_golem_axe/sword/spear`（14 基础 + 4~5% 攻击比例），
  带 `RefineMerorWeaponItem.onDamage`：**只要有传奇状态**，伤害取 `max(x, sqrt(x * 攻击力))` —— 这就是"真伤"。
- 波次 4~6 的 `*_charged` 变体额外带 `meror_charge`（超能矩阵在 ARROW 槽），
  `LegendaryModifier.onAiStep` 每 20 tick 续 600 tick 的传奇效果 → **波次 4~6 全程传奇，波次 1~3 没有**。
- `jerotes_village_golems:fire_affinity`：受火焰伤害时**取消该次伤害**，换来 1 秒内 `等级×0.5` 的自然回复与 `等级×10%` 的增伤
  （`FireAffinityModifier.java:21-41`）→ 打火焰等于给敌人回血加攻。
- `modulargolems:magic_immune` 取消 `L2DamageTypes.MAGIC = forge:is_magic` 的伤害（`javap` 已确认该 tag 名）。
- `modulargolems:armor_penetration` 等级 × `armorBypassChance(0.2)` = 每击启用 `BYPASS_ARMOR` 的概率（等级 2~4 → 40%~80%）；
  它只跳过护甲**与韧性**（`getDamageAfterArmorAbsorb` 整个不进），**不跳过盾牌**（那要 `bypasses_shield`），
  抗性提升、保护附魔、吸收仍然生效。
- 注意：三种傀儡的基础护甲/韧性都是 **0**（`GolemTypes` 只加了 MAX_HEALTH/ATTACK_DAMAGE/MOVEMENT_SPEED 等），
  所以精英的 41/30 全部来自那三件护甲；`modulargolems:damage_cap` 满级把每击压到最大生命的 10%（lv1~5 = 18/16/14/12/10%），
  是唯一能正面摁住传奇真伤地板的减伤。
- 傀儡近战节奏 = `ceil(20 / min(1, ATTACK_SPEED))` = 20 tick = **1 击/秒**；金属傀儡基础 `golem_sweep=0`
  （要斧/剑 +2.0 或下界合金材质 +2.0 才有横扫），人类傀儡基础 2.0 一直有小范围横扫。

## 4. 实测（整合包日志 `dev/logs/latest*.utf8.log` 里的 `[GA-Probe]`）

- 我方傀儡打试炼精英：`base 均值 89~142 → fin 均值 7.7~11.9`（`kept ≈ 0.08~0.12`）。
- 试炼精英打我方可动：`base 58~70 → fin 100~152`（`kept ≈ 1.5~2.3`，被"真伤 + 破甲"反向放大）。
- 精英身上实测：`jerotes_village_golems.meror_defense=4`、`armor_penetration=2~4`、`magic_immune=1`、`legendary=2~4`、`fire_affinity=2~4`；
  目标面板：护甲 41、韧性 30、`damage_reduction` 0.512。
