# Golems Arsenal（傀儡军械库）

给「傀儡装配」（Modular Golems）加武器、升级与相关战斗效果。

> 注册 id 是 `golems_arsenal`，显示名 **Golems Arsenal**。
> 龙形傀儡已经拆出去单独维护（`dragon_golems`），本仓库不再包含任何龙的内容。

## 这个 mod 有什么

按需要的依赖分成三层，逐文件清单见 [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md)。

| 层 | 内容 | 前置 |
|---|---|---|
| `base/` | 武器（能量武士刀 / 能源锤 / Z 光剑 / 追踪机械弓 / 神通棍 / 键刃）、升级（主武器 / 异类 / 远程 / 盾类 / 全装猛攻 / 枪骑 / 黑猴架势 / 擦弹 / 开车 / E 罐 / W 罐 / 减震 / 节约能源 / 光柱护体）、特技升级（键刃回旋 / 狮子斩 / 剑雨 / 冲撞 / 凤凰天驱）、全装猛攻附魔、特技互斥 | **必装**：`modulargolems` ≥ 2.7.3、`l2library` ≥ 2.5.3、`l2damagetracker`、`mob_weapon_api` |
| `tech/` | FE 能量能力（Forge Energy）、能量/科技升级、武器升级数据 | 自带；`mekanism` 可选（零件粉碎 / 锯切配方） |
| `compat/` | 卷轴升级（记录 / 施放法术）、古遗物套装联动、JEI 说明页 | 可选：`golemmagicka` + `irons_spellbooks`、`l2artifacts`、`jei` |

可选层全部由 `CompatDispatch` 按模组存在性门控，mixin 另由 `GolemMagickaMixinPlugin` 门控 ——
未安装时相关类完全不加载。

## 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 | |
| Forge | 47.x（开发用 47.4.20） | |
| 傀儡装配 Modular Golems | ≥ 2.7.3 | **必装** |
| l2library | ≥ 2.5.3 | **必装** |

运行时 `l2serial` / `mob_weapon_api` / `l2damagetracker` 由本家 jarjar 提供；
编译期必须能看见，所以 `build.gradle` 里列全了。

## 构建

```
gradlew build
```

产物在 `build/libs/golems_arsenal-<版本>.jar`（当前 `gradle.properties` 里是 `0.3`）。

`libs/` 下的那批编译期 jar **不进版本库**，新克隆一份仓库后需要自己补齐才能编译；
其中 `golemmagicka` / `irons_spellbooks` / `l2artifacts` / `jei` 不在 Maven 上，
换机器时要手动放回 `libs/`。

## 开发环境

**`runClient` 目前没有跑通过**，本仓库的验证方式是「打 jar 进整合包」。
已知障碍、`run/mods` 需要放哪三个 jar、以及本仓库 mixin refmap 的手工维护规则，
全部写在 [`docs/DEV-RUN.md`](docs/DEV-RUN.md)。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
