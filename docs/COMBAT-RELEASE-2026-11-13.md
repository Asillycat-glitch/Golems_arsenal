# 战斗态释放：SEALED → NORMAL（2026-11-13）

## 原则

只释放 `Projection`（实体模拟层），**不释放** `Sanctuary`、`DamageAdmission`、
`RemovalGuard`、`IdentityGuard`、`HealthLedger`、`AttributeGuard`。

```
projection_mode = NORMAL
sanctuary.mode = ENFORCE
removal_guard = ENFORCE
damage_admission.enabled = true
hard_lock = true
```

NORMAL 下正常 hurt 仍要走：

```
hurt → LivingAttackEvent → Sanctuary(ENFORCE) 准入 → actuallyHurt → setHealth
```

`DamageAdmission` 看到 `SanctuaryScopes` 的准入窗口才放行；这不是拆防御。

## 已加命令

```
/ga guard projection              # 看当前 projection / sanctuary / removal
/ga guard projection normal       # 进入战斗态
/ga guard projection sealed       # 回到测试冻结态
/ga guard projection config       # 取消 runtime override，回配置
```

## 关键修复：NORMAL 必须解除封印

旧版 `Projection` 从 SEALED 切到 NORMAL 只 `FROZEN.clear()`，但 `seal()` 设置的
`invulnerable` / `noAi` 不会还原；结果是“解冻了但永远无敌、永远不动”。

现在：

- `seal()` 第一次封印时记录原先的 `invulnerable` / `noAi` 到 `SEALED_STATE`；
- 在傀儡 persistent data 写 `GolemsArsenalProjectionSealed=1`；
- `NORMAL` 每 tick 对存活傀儡调 `unseal()`；
- `unseal()` 还原原状态；旧存档没有 state/flag 时，把“invulnerable + noAi”视为遗留封印；
- `Sanctuary` 被关掉时 `releaseAll()`，不会留下永久无敌/无 AI。

## 测试顺序

1. 进战斗态后确认傀儡能移动、能攻击、能右键。
2. 普通僵尸/骷髅打傀儡：确认掉血、`expected` 下降、没有被 `DataGuard` 修回。
3. 致命伤：确认能死、掉落、回收；`RemovalGuard` 只拦活体清除。
4. modded 敌人：若 `Sanctuary` 判 `UNATTRIBUTED`/`OPERATION`，只加该 source 的准入规则。
5. 主人/友军：仍应被 `Sanctuary` 拒绝；训练模式不要写成永久豁免。

## Tinkers Golem 1.0.2 兼容（2026-11-13 20:33 崩溃）

战斗态第一次实际伤害触发了一个第三方 bug：

```
java.util.NoSuchElementException: No value present
  at GeneralCapabilityHolder.get
  at GolemOverslimeEvents.getOverslime
  at GolemOverslimeEvents.onGolemHurt
  at TGAttackListener.lambda$onDamage$0
```

`GolemOverslimeEvents` 只判断 `entity instanceof AbstractGolemEntity`，然后直接
`OverslimeCap.HOLDER.get(entity)`；`GeneralCapabilityHolder.get` 内部是
`Optional.get()`。大型傀儡**可以**获得粘液镀层，所以不能按“非 SlimeGolem”一刀切；
问题是**没有实际 capability 的傀儡也会走到这里**，例如还没镀层的大型傀儡。

已加 `TinkersGolemOverslimeCompatMixin`（可选配置，只有装了 `tinkers_golem` 才应用）：

- 先查 `entity.getCapability(OverslimeCap.CAPABILITY)` 是否真的存在；
- 有镀层的傀儡（包括大型傀儡）完全走原逻辑；
- 没有 capability 的才：`getOverslime` 返回 `0`，`set/addOverslime` 取消，
  `onGolemHurt` 原样返回伤害。

这不是“只保护史莱姆傀儡”，也不是防御层改动；是防止没有镀层的 Modular Golem
触发 `Optional.get()`。

> 2026-11-13 21:48：为了排查“进世界后 server tick 卡死”，已**暂时撤掉**这个
> Tinkers 兼容 mixin（连同配置和 build.gradle 里的 `golems_arsenal.tinkers.mixins.json`）。
> 如果撤掉后不再卡死，说明问题在兼容层/类初始化；如果仍然卡死，则用 `jstack` 抓
> Server thread，不要继续猜。Tinkers 的 overslime 空值 bug 仍然需要上游修。
>
> 2026-11-13 22:18：撤掉后没有再复现 40 秒卡死；但“收傀儡”时又复现了原来的
> `NoSuchElementException`，说明卡死和 Tinkers 空值崩溃是两个问题。现在已把
> 兼容 mixin **以延迟解析 capability 的方式**加回：
> - Mixin 类加载阶段不再碰 `OverslimeCap` 的 static init；
> - 第一次真正判断时才解析 capability；
> - 有镀层的傀儡（包括大型傀儡）继续走原逻辑，没有 capability 的返回 0/原伤害。
>
> 同时修了 `RemovalGuard` 对 `UNLOADED_TO_CHUNK` 的处理：如果调用栈是回收手杖
> `toItem` / `returnToInventory`，区块仍 loaded 也按正常回收放行，不再误判成
> 敌对拆索引、也不再无意义地 rotate UUID。

## 回滚

```
/ga guard projection sealed
```

或配置 `projection_mode = "SEALED"`。测试实例配置已备份为
`golems_arsenal-common.toml.bak-before-combat-20261113`。
