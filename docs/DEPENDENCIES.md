# 依赖分级与模块说明

代码按“需要哪些 mod”分成三层，配合注册中心与 mixin 门控，避免可选模组缺失时加载相关类。

## 1. base/ — 基础层

**前置（必装）**：`modulargolems`、`l2library`、`l2damagetracker`、`mob_weapon_api`

| 内容 | 位置 |
|---|---|
| 主类 / 异类 / 远程 / 盾类 / 全装猛攻升级、科技扩充模板 | `base/upgrade/` |
| 全装猛攻附魔 | `base/enchantment/` |
| 示例武器 | `base/item/` |
| 战斗事件中枢（兼管科技武器与 TACZ 软引用） | `base/event/` |
| 可重复锻造配方 | `base/recipe/` |

## 2. tech/ — 科技层

**前置**：本模组内置的 FE 能量能力（Forge Energy）；`mekanism` 可选（零件配方）

| 内容 | 位置 |
|---|---|
| 能量武士刀 / 能源锤 / 追踪机械弓 / 武器升级数据 | `tech/item/` |
| FE 能量能力与存储 | `tech/energy/` |
| 能量升级 / 科技升级 | `tech/upgrade/` |
| Mekanism 零件粉碎 / 锯切兼容 | `compat/mekanism/` |

## 3. compat/golemmagicka/ — 铁魔法层

**前置（可选）**：`golemmagicka`（奥法魔像）+ `irons_spellbooks`（铁魔法）

| 内容 | 位置 |
|---|---|
| 卷轴升级（记录 / 施放法术） | `compat/golemmagicka/`、`mixin/golemmagicka/` |

未安装时：注册由 `CompatDispatch` 门控，mixin 由 `GolemMagickaMixinPlugin` 门控，完全不加载。

## 注册中心（init/）

- `ModItems`：物品注册（含按模组门控的创造栏条目）
- `GolemUpgrades`（`base/upgrade/`）：傀儡修饰符注册（L2Registrate）
- `ModRecipeSerializers`：配方序列化器
- `ModEnchantments` / `ModAttributes` / `GolemEffects` / `ModTags`：附魔、属性、效果、标签

## mixin/

- `golems_arsenal.mixins.json`：能量持久化（`AbstractGolemEnergyMixin`）
- `golems_arsenal.golemmagicka.mixins.json`：施法集成（法术池、施法目标、可用法术/魔力日志），由插件按模组存在性门控

## 功能 → 依赖速查表

| 功能 | 依赖 | 位置 |
|---|---|---|
| 主类 / 异类 / 远程 / 盾类 / 猛攻 / 扩充 | 基础层四件套 | `base/` |
| 能量武器 / 能量 / 科技升级 | 自带 FE；Mekanism 可选 | `tech/`、`compat/mekanism/` |
| 卷轴升级 | 奥法魔像 + 铁魔法 | `compat/golemmagicka/`、`mixin/golemmagicka/` |
| TACZ 枪械增伤（猛攻） | tacz（软引用，仅 tag 判断） | `base/event/` |
