# 私有测试台（PRIVATE TESTBENCH）—— 禁止提交 / 禁止发布

> **这批东西是私活测试，不是 mod 内容。**
> 目录 `testbench/` 已在 `.gitignore` 里；**不要 `git add -f`，不要打包进发布 jar，不要上传 GitHub。**

## 它是什么

逐项量化"绕过减伤"的手段到底有多强。每个手段都能在局内单独开关，配合 `[GA-Probe]`
日志对比出**边际收益**，而不是靠感觉。

要测的杠杆（分组见对话里的设计稿）：

| 组 | 内容 |
|---|---|
| 1 | tag 档位：`plain` / `armor` / `armor_ench` / `pierce4` / `true_edge` / `true_edge_cd` / `magic` / `fire`（tag 是注册表级，必须预注册成档位来切） |
| 2 | 阶段杠杆：`uncancel`（反取消）/ `floor_attack` / `floor_hurt` / `floor_dealt` / `end_restitution`（我们已有的 END 层返还）/ `strength_gate` / `proc_chance` |
| 3 | 多段：`extra_hits` / `distinct_ids`（破适应）/ `same_tick` / 多段用哪档 tag |
| 4 | 状态干预：`strip_enchants`（摘附魔 NBT）/ `dispel_state`（BYPASS_MAGIC）/ `trim_max_health` / `write_health`（含"被拒绝"计数） |
| 5 | 诊断：命中携带"本次生效杠杆集合"、被钳制识别、被适应识别、单次命中全链路 dump、`/ga debug report` 落盘 |

## 载体

测试武器＝现有的 `golems_arsenal:example_weapon`（示例傀儡武器，`ExampleWeapon`）。
`allow_player` 默认**开**：傀儡手持和你自己手持都会触发这些杠杆，方便直接手测。
写血那一档也在测试范围内。

## P0 血量基线（HealthTrace）

`/ga health ...` 是后来加的**只读**观测层，用来回答"L2 正常长什么样 / 谁在写血 / 谁被移除"：

- `/ga health track control|test` → `/ga health start` → 打同一个伤害源 → `/ga health stop`；
- 记录 Forge 四个伤害/治疗事件、`setHealth`/`getHealth`（含调用者栈）、傀儡的
  `setGuardedDataImpl`、`Entity.setRemoved`、客户端移除包、L2 六个阶段的 raw/base/fin，
  以及 finalize 后第一次写血的 `applied = before - after`；
- 输出 `config/golems_arsenal/health/p0-*.jsonl` + `-summary.json` + `-summary.txt`；
- **P0 不拦任何 setHealth、不改任何伤害**。P1 才做影子账本，P2 才做按实体挂载的 ENFORCE。

完整协议、命令、判定标准见 [`P0-HEALTH-BASELINE.md`](P0-HEALTH-BASELINE.md)。

## P1 影子账本（HealthLedger）

P1 在 P0 的事件流上再加一个**按实体**的 expected 账本，只回答“这次血量变化能不能被
Damage/Heal 事件解释”：

- `LivingDamageEvent` / `LivingHealEvent` 更新 expected；每 tick 末尾读 `getHealth` 比较；
- 不一致写 `kind=unexplained` 的 JSONL（含 expected/actual/delta、最近一次合法事件、最近一次
  P0 write 的 op/caller），并在 summary 里按角色计 `unexplained`；
- **不写血、不拦截、不取消事件**；`/ga health ledger true|false` 可一键关掉；
- `/ga health poke test 0` 是**故意写血**的测试刺激，用来验证 ledger 能抓到外部 `setHealth`；
- `/ga health unexplained` 看计数；`/ga health report` 看完整 summary。

完整协议、矩阵、判定标准见 [`P1-SHADOW-LEDGER.md`](P1-SHADOW-LEDGER.md)。

## P2 按实体 Guard（HealthGuard）

P2 只对 **test** 生效，control 永远 OFF；每实体 `off`/`shadow`/`enforce`：

- `shadow`：记录 `kind=guard` 的 would_block/would_restore，不拦；
- `enforce`：拦 `setHealth`/`setGuardedDataImpl`/`SynchedEntityData(DATA_HEALTH_ID)`/`setRemoved(KILLED/DISCARDED)`，
  反射真血/HEALTH_SEAL 在 tick 末尾回滚；
- 正常 L2 的 Damage/Heal 写通过 P1 ledger expected 放行，不改 L2 amount；
- `/ga health guard test shadow|enforce`；`/ga health guard all off` 应急；
- 服务器启动强制全部 OFF。

完整设计、命令、测试矩阵、边界见 [`P2-GUARD.md`](P2-GUARD.md)。

它需要 5 个 testbench-only mixin（`LivingEntityTraceMixin` / `GuardedEntityTraceMixin` /
`EntityTraceMixin` / `SynchedEntityDataTraceMixin` / `ClientboundRemoveEntitiesPacketTraceMixin`）。
这些类只在 `-Ptestbench` 构建里存在；普通 jar 的 `mods.toml` 不会引用
`golems_arsenal.testbench.mixins.json`。refmap 是手工维护的，改注入目标时要同步改
`resources/golems_arsenal.testbench.refmap.json`。

P1 不需要 P0 的 control/test 先完全同 build：ledger 是按实体算的，feed/regen 只要走
`LivingHealEvent` 就会被记账。P0 的 A/B 目标差异是另一件事。

它需要 4 个 testbench-only mixin（`LivingEntityTraceMixin` / `GuardedEntityTraceMixin` /
`EntityTraceMixin` / `ClientboundRemoveEntitiesPacketTraceMixin`）。这些类只在
`-Ptestbench` 构建里存在；普通 jar 的 `mods.toml` 不会引用
`golems_arsenal.testbench.mixins.json`。refmap 是手工维护的，改注入目标时要同步改
`resources/golems_arsenal.testbench.refmap.json`。

## 它为什么默认不存在

`build.gradle` 里这套是**条件源集**：

```
gradlew build                 -> build/libs/golems_arsenal-<版本>.jar          干净发布产物，一行测试代码都没有
gradlew build -Ptestbench     -> build/libs/golems_arsenal-<版本>-testbench.jar  只在本机自用
```

- 默认构建：`testbench/` 连编译都不参与，`testbench/resources` 里的数据包（档位伤害类型与 tag）也不会进 jar。
- 只有 `-Ptestbench` 才会并入，并且分类器改成 `-testbench`，日志里还会打一条醒目横幅。
- 运行期再叠一道保险：所有杠杆默认全关，必须 `/ga debug enable` 才生效，**每次服务器启动强制复位成全关**。

## 一条纪律

**不要把这里的任何一段代码原样搬进 `src/main`。** 测出结论后，只把"结论"（数值、tag 选择、
挂哪一层）搬进正式内容，实现要重新按正式代码的标准写一遍——debug 代码的写法（反射、写血、
摘 NBT）不适合出现在发布物里。
