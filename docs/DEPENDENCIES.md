# 依赖分级与模块说明

代码按“需要哪些 mod”分成三层，配合注册中心与 mixin 门控，避免可选模组缺失时加载相关类。

## 1. base/ — 基础层

**前置（必装）**：`modulargolems`（≥2.7.3，编译/运行基线 2.7.3）、`l2library`、`l2damagetracker`、`mob_weapon_api`

| 内容 | 位置 |
|---|---|
| 主类 / 异类 / 远程 / 盾类 / 全装猛攻 / 黑猴架势 / 特技升级（键刃回旋、狮子斩、剑雨、冲撞）、科技扩充模板 | `base/upgrade/` |
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

- `golems_arsenal.mixins.json`（`required: true`）：两个 mixin —— `AbstractGolemEnergyMixin`（傀儡 FE 能量的读写持久化，注入 vanilla `addAdditionalSaveData`/`readAdditionalSaveData`）与 `GolemUpgradeHandlerMixin`（特技升级互斥，注入本家 `GolemUpgradeItemHandler.appendUpgrade`）
- `golems_arsenal.golemmagicka.mixins.json`（`required: false`）：施法集成（法术池、施法目标、可用法术/魔力日志），由 `GolemMagickaMixinPlugin` 按模组存在性门控
- `AbstractGolemRendererFlipMixin` 在第一个 config 的 `client` 段里（狮子斩/凤凰的前后空翻）

### ⚠️ refmap 是手工维护的

`src/main/resources/golems_arsenal.refmap.json` **不是构建产物** —— Mixin 的注解处理器在本项目里不生成它
（`build/generated/sources/annotationProcessor/` 是空的，实测用 `--rerun-tasks` 强制重编也仍然为空），
它是手写并随源码提交的。

它目前只覆盖 `AbstractGolemEnergyMixin`，**因为只有它注入了 vanilla 成员**：

| mixin | 注入目标 | 需要 refmap？ |
|---|---|---|
| `AbstractGolemEnergyMixin` | vanilla `addAdditionalSaveData` → `m_7380_` / `readAdditionalSaveData` → `m_7378_` | **需要**（已写） |
| `GolemUpgradeHandlerMixin` | 本家 `GolemUpgradeItemHandler.appendUpgrade` + 影子字段 `upgrades` | 不需要（mod 代码不重映射） |
| `AbstractGolemRendererFlipMixin` | 本家 `AbstractGolemRenderer.setupRotations` | 不需要（同上） |

**规则：凡是新增/修改注入 vanilla 成员的 mixin，必须手工往这个 refmap 里补对应条目**，
否则会表现为"开发环境一切正常、打出来的 jar 在生产里静默不生效"。

## 功能 → 依赖速查表

| 功能 | 依赖 | 位置 |
|---|---|---|
| 主类 / 异类 / 远程 / 盾类 / 猛攻 / 架势 / 特技升级 / 扩充 | 基础层四件套 | `base/` |
| 能量武器 / 能量 / 科技升级 | 自带 FE；Mekanism 可选 | `tech/`、`compat/mekanism/` |
| 卷轴升级 | 奥法魔像 + 铁魔法 | `compat/golemmagicka/`、`mixin/golemmagicka/` |
| TACZ 枪械增伤（猛攻） | tacz（软引用，仅 tag 判断） | `base/event/` |

武器触发清单（主类 / 锻锤 / 火焰剑 / 矛 / 镰 / 弓 / 炮）已数据包化：默认值写在
`data/modulargolems/tags/items/weapon_*.json`（沿用傀儡装配自己的命名空间写法，
mod 缺失对应物品时条目自动跳过），数据包可通过追加 `values` 扩展触发武器。
