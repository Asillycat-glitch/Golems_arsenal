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
| **军械手套**（十格储物手套 + 九格效果 + 黑手套渲染） | `base/glove/`、`menu/`、`client/glove/` |

### 军械手套（`golems_arsenal:glove`）

玩家可用的储物手套，放在主手。十个格子里格 0 是手套固有槽（不可放东西），格 1..9 各放一把武器，
每格自带一个固定的战斗效果。右键开界面；按住 Alt 滚轮切换格位（**切换只有滚轮一个入口**，
数字键留给原版热键栏）。

| 依赖 | 用途 | 缺失时 |
|---|---|---|
| `l2itemselector` | 切换选择器的骨架（`IItemSelector`）、屏幕左侧那列格位侧栏 | **硬依赖**（由 l2library / modulargolems 以 jarjar 提供，必然在场） |
| `l2library` 的 `BaseContainerMenu` / `SpriteManager` / `MenuLayoutConfig` | 界面骨架，槽位坐标与背景图由数据包驱动 | **硬依赖** |
| `l2damagetracker:crit_rate` / `crit_damage` | 第 1 格的暴击增益 | 软引用：查不到属性就不挂修饰符，效果静默失效 |
| `l2damagetracker:damage_reduction` | 第 7 格的魔法易伤 | 同上 |
| `golems_arsenal:end_restitution` | 第 8 格的 END 层保底 | 本模组自有属性，见 `ModAttributes` |

数据包文件（都在本仓库里）：

| 文件 | 作用 |
|---|---|
| `data/golems_arsenal/l2library_config/menu_layout/glove.json` | 界面布局：槽位坐标 + 背景贴图尺寸 |
| `assets/golems_arsenal/textures/gui/container/glove.png` | 界面背景（占位图，可替换） |
| `assets/golems_arsenal/textures/item/glove.png` | 物品图标 |
| `assets/golems_arsenal/textures/entity/glove_hand.png` | 手部渲染贴图（占位图，带 UV 参考网格） |
| `data/l2itemselector/tags/items/selectable.json` | **必须**：手套要进这个 tag，选择器才认得它 |
| `data/golems_arsenal/damage_type/glove_magic.json` | 第 5 格的魔法伤害类型 |
| `data/golems_arsenal/damage_type/glove_extra.json` | 第 9 格的追击伤害类型 |
| `data/minecraft/tags/damage_type/bypasses_resistance.json` | **新增**：让 `glove_magic` 无视法术抗性 |

九格效果一览（数值全在 `Config` 的 `glove` 段）：
| 格 | 效果 | 实现位置 |
|---|---|---|
| 1 | 暴击率 + 暴击伤害 | `GloveEffects.applyAttributeGains`（每 10 tick 对齐的属性修饰符） |
| 2 | 命中削目标攻击力 | `GolemEffects.ATTACK_WEAKNESS` |
| 3 | 武器伤害提升（乘算） | `GloveEffects.onHurt`，优先级 `HIGH`（排在暴击之后） |
| 4 | 破盾：挂破甲 + 该击无视护甲 | `GolemEffects.ARMOR_SHRED` + `bypasses_armor` 标签 |
| 5 | 追加魔法伤害，无视护甲与法抗 | `GloveDamageTypes.magic` |
| 6 | 物理易伤（削护甲韧性） | `GolemEffects.PHYSICAL_VULNERABILITY` |
| 7 | 魔法易伤 | `GolemEffects.MAGIC_VULNERABILITY` |
| 8 | END 层保底（固定值） | `end_restitution` 属性 + `DamageRestitutionListener` |
| 9 | 延迟 1 tick 的追击，无视无敌帧 | `GloveDamageTypes.extra` + `bypasses_cooldown` 标签 |

> ⚠️ 第 8 格需要 `DamageRestitutionListener` 对玩家生效。原来那个类里写死了
> `APPLY_TO_PLAYERS = false`（只给傀儡），现在改由配置 `glove.slot8_allow_for_players`
> 控制，默认打开。想恢复「只给傀儡」把它关掉即可。

#### 手写几何体：顶点格式必须逐项对满

`GloveHandMesh` 直接写顶点（第一人称拿不到现成的 `ModelPart`）。它走
`RenderType.entitySolid`，该管线用的格式是 `DefaultVertexFormat.NEW_ENTITY`：

```
Position, Color, UV0, UV1(overlay), UV2(lightmap), Normal, Padding
```

对应调用是 `vertex() → color() → uv() → overlayCoords() → uv2() → normal() → endVertex()`
（`Padding` 由 `endVertex()` 自己补）。

**少填任何一项，`endVertex()` 会抛 `IllegalStateException: Not filled all elements of the vertex`，
而且是在渲染线程上抛 —— 表现为「一拿起手套就崩」。**

这个类踩过两次，方向正好相反：
1. 一开始走 `armorCutoutNoCull`（同样是 `NEW_ENTITY`），带着 `normal()`，是对的；
2. 后来换成 `entitySolid` 时误以为「纯色几何不需要法线」把 `normal()` 删了 → 崩溃。

**规则：换渲染类型时必须重新核对那条管线的顶点格式，不能凭印象增删调用。**
`RenderType` 里每个工厂方法都写明了它用的 `VertexFormat`，去 `DefaultVertexFormat` 查元素表即可。



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

- `golems_arsenal.mixins.json`（`required: true`）：
  - `AbstractGolemEnergyMixin`（傀儡 FE 能量的读写持久化，注入 vanilla `addAdditionalSaveData`/`readAdditionalSaveData`）
  - `GolemUpgradeHandlerMixin`（特技升级互斥，注入本家 `GolemUpgradeItemHandler.appendUpgrade`）
  - `GloveScrollMixin`（**client**，手套的 Alt+滚轮切换，注入 vanilla `MouseHandler#onScroll`）- `golems_arsenal.golemmagicka.mixins.json`（`required: false`）：施法集成（法术池、施法目标、可用法术/魔力日志），由 `GolemMagickaMixinPlugin` 按模组存在性门控
- `AbstractGolemRendererFlipMixin` 在第一个 config 的 `client` 段里（狮子斩/凤凰的前后空翻）

### ⚠️ refmap 是手工维护的

`src/main/resources/golems_arsenal.refmap.json` **不是构建产物** —— Mixin 的注解处理器在本项目里不生成它
（`build/generated/sources/annotationProcessor/` 是空的，实测用 `--rerun-tasks` 强制重编也仍然为空），
它是手写并随源码提交的。

| mixin | 注入目标 | 需要 refmap？ |
|---|---|---|
| `AbstractGolemEnergyMixin` | vanilla `addAdditionalSaveData` → `m_7380_` / `readAdditionalSaveData` → `m_7378_`（实际是**本家** `AbstractGolemEntity` 上的覆写，所以走 mod 侧描述符） | **需要**（已写） |
| `GolemUpgradeHandlerMixin` | 本家 `GolemUpgradeItemHandler.appendUpgrade` + 影子字段 `upgrades` | 不需要（mod 代码不重映射） |
| `AbstractGolemRendererFlipMixin` | 本家 `AbstractGolemRenderer.setupRotations` | 不需要（同上） |
| `GloveScrollMixin` | vanilla `MouseHandler#onScroll` → **`m_91526_`** | **需要**（已写） |

> ⚠️ **不要用 `@Inject(locals = LocalCapture.CAPTURE_FAILHARD)`**。手套滚轮第一版就是那么写的，
> 结果启动即崩：`LVT in ...MouseHandler::m_91526_(JDD)V has incompatible changes /
> Expected: [D, D, I, LocalPlayer;] / Found: [D, D, I, D]`。
> 局部变量表由编译器决定，和反编译源码看起来的样子经常不一样，而且上游一重编译就可能再崩一次。
> 现在改成 `@Inject(at = @At("HEAD"))` 自己维护状态，注入点不依赖 LVT。
> **能用 `HEAD` / `RETURN` 解决的就不要碰 `LocalCapture`。**

**规则：凡是新增/修改注入 vanilla 成员的 mixin，必须手工往这个 refmap 里补对应条目**，
否则会表现为"开发环境一切正常、打出来的 jar 在生产里静默不生效"。

**SRG 号从哪查**：`build/extractSrg/output.srg`（TSRG2 格式，ForgeGradle 在 `compileJava` 时生成）。
它是「混淆名 → 官方名」的表，先拿 `minecraft_repo/versions/1.20.1/client_mappings.txt` 里的
官方名（例如 `void handleKeybinds() -> bu`）找到混淆名，再在 `output.srg` 对应类块里按
**描述符**匹配，就能拿到 SRG 名（`bu ()V m_91279_`）。**不要凭记忆猜** —— 本仓库曾经把
`Minecraft#handleKeybinds` 写成 `m_91787_`，而那个号根本不属于 `Minecraft` 类。

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
