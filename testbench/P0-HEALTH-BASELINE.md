# P0：血量基线观测（HealthTrace）

> **P0 只观测，不拦截。** 这一阶段不挂 HealthGuard、不改任何 `setHealth`、不取消任何事件。
> 目的是先把"同一套 L2 管线下，正常傀儡长什么样"量出来，作为后面 P1 影子账本 / P2 局部 ENFORCE 的对照组。
>
> 这份文档和代码都在 `testbench/` 里（已 gitignore）；只有 `gradlew build -Ptestbench` 才会进 jar。

## 1. 它记什么

| 观测点 | 来源 | 关键字段 |
|---|---|---|
| `LivingAttackEvent` / `LivingHurtEvent` / `LivingDamageEvent` / `LivingHealEvent` | Forge 事件（LOWEST，含被取消） | amount、canceled、source、target 血量/吸收/无敌帧 |
| `LivingDeathEvent` | Forge 事件 | 死前血量、伤害源 |
| `setHealth` | testbench mixin（vanilla `LivingEntity`） | arg、before、after、clamp、调用者栈、是否嵌套 |
| `setHealth` / `getHealth` / `setGuardedDataImpl` | testbench mixin（本家 `GuardedEntity`） | 同上；`setGuardedDataImpl` 是 L2/本家真正写 guardedData 的路径 |
| `getHealth` | testbench mixin（两边都挂） | 采样记录值 + 调用者；默认每 256 次记 1 条，只记 caller 不写 10 帧栈（否则 3 个傀儡 1 分钟能写 10MB） |
| `setRemoved` | testbench mixin（vanilla `Entity`） | RemovalReason、移除前血量、是否已经 removed |
| 客户端移除包 | testbench mixin（`ClientboundRemoveEntitiesPacket` 构造器） | 包里的实体 id、其中哪些是被追踪的、服务端是否已 removed |
| L2 管线终值 | `AttackListener`（priority 9500，只读） | `onAttack` / `postAttack` / `onHurt` / `postHurt` / `onDamage` / `finalized` 的 raw / base / fin、事件 amount、取消状态 |
| L2 → 写血配对 | 上述两者 | `l2_applied`：`applied = before - after`、`ratio = applied / fin` |
| tick 血量差 | 每 tick 采样被追踪实体 | before / after / delta；这是"伤害之外"的唯一 ground truth |
| tick 最大生命差 | 每 tick 采样被追踪实体 | before / after / delta；抓 HEALTH_SEAL / trim_max_health 这类只改 max 的手段 |

所有记录写到：

```
config/golems_arsenal/health/p0-<时间戳>-<label>.jsonl
config/golems_arsenal/health/p0-<时间戳>-<label>-summary.json
config/golems_arsenal/health/p0-<时间戳>-<label>-summary.txt
```

## 2. 怎么构建 / 放进去

```bat
gradlew build -Ptestbench
```

产物是 `build/libs/golems_arsenal-<版本>-testbench.jar`，丢进整合包即可。普通 `gradlew build` 的 jar 里
没有 `testbench/`、没有 `/ga health`、没有这组 mixin；`mods.toml` 里也不会引用
`golems_arsenal.testbench.mixins.json`。

> 本仓库的开发环境（`runClient`/`runServer`）历史上没有真正跑通过，所以 P0 的验证路径按老规矩：
> 打 `-testbench` jar → 丢整合包 → 进游戏用命令测。见 `docs/DEV-RUN.md`。

## 3. 命令

```
/ga health nearby 32            # 先看附近有什么（类型/名字/距离/UUID）
/ga health track control nearest modulargolems:metal_golem
/ga health track test nearest modulargolems:metal_golem
/ga health track control nearest # 不带 type：48 格内最近且未追踪的生物
/ga health track control         # 也可以看向傀儡执行（32 格内）
/ga health track test self       # 玩家侧测试：追踪自己
/ga health track control @e[type=modulargolems:metal_golem,limit=1,sort=nearest]
/ga health untrack               # 取消看向的实体
/ga health untrack all           # 清空追踪表
/ga health list                  # 看追踪表 + pair=OK/WARN/BAD
/ga health start p0-baseline     # 开始记录
/ga health status                # 记录中？追踪了谁？还有几条 L2 待配对？
/ga health stop                  # 停止并写 summary
/ga health report                # 记录中也可以打印当前汇总 / 落摘要
/ga health all true|false        # 观察所有实体（默认关；量很大）
/ga health sample 256            # getHealth 采样率（默认 256）
/ga health gethealth true|false  # 关掉 getHealth 记录
/ga health reset                 # 清追踪表 + 本地统计（记录中不能执行）
```

> `track <role> nearest <type>` 会**跳过已追踪实体**。所以站在 A 旁边登记 control 后，
> 再站到 B 旁边执行同一条命令，会自动选 B，而不是把 A 改成 test。
> 如果两只同类型傀儡站在一起，`nearest` 仍可能选错；看它打印的"选中"距离，或改用 `@e` 选择器。

## 4. P0 的实验步骤

### 4.1 准备两个"同类型、同配置"的傀儡

- 同一个实体类型（例如 `modulargolems:metal_golem` / `iron_golem`，按你的整合包）；
- 同样的升级、护甲、最大生命、抗性；
- **两边都不挂 HealthGuard**（P0 还没有 Guard）；
- 给它们不同的名字/染色，方便区分。

### 4.2 开始记录

```
/ga health nearby 32             # 先确认 A/B 都在列表里，记下它们的 type
/ga health track control nearest modulargolems:metal_golem   # 站到 A 旁边
/ga health track test nearest modulargolems:metal_golem      # 站到 B 旁边
/ga health list                  # 必须看到 tracked=2 且 pair=OK！
/ga health start p0-baseline
```

`status` 里应看到 `tracked=2`、`pendingL2=0`。

> **重要**：`start` 时会做配对检查。
> - `tracked=0` 且 `all=false` → 直接拒绝（空 trace 没有意义）；
> - control/test 都有但**类型不一致** → 直接拒绝（例如 control 是狗傀儡、test 是金属傀儡）；
> - 只有一边，或两边数量不同 → 允许但 `pair=WARN`，`compare` 会缺一组或按均值比。
>
> 如果 `track` 报"没看向生物"，用 `nearest modulargolems:metal_golem` 或实体选择器，见第 9 节。
> 不允许把已登记为 control 的实体直接改成 test——会先把 control 弄丢；要改先 `untrack all`。

### 4.3 打同一个伤害源

用同一把 L2 武器、同样的蓄力/攻击方式，**交替**打 A 和 B。建议：

- 每组 20 下，A/B 各一组；
- 中间不要换武器、不要换目标类型、不要让 A/B 的护甲/抗性变化；
- 如果武器有随机暴击/概率 proc，记录条件里写清楚，并多打几组。

### 4.4 停止并看报告

```
/ga health stop
/ga health report
```

`summary.txt` 里重点看：

1. `role=control` 和 `role=test` 两段：同 `source`、同 `target` 的 `n` / `avg` / `cancelled` 是否一致；
2. 最后的 `compare` 段：每行 `OK` / `DIFF`，以及 L2 的 `finalized` / `finAvg` / `applied`；
3. `writes=... unpairedDamage=... healWrites=... rejected=...`：
   - 正常 L2 命中，`unpairedDamage` 应为 0；`healWrites` 是回血/反治疗，不算未配对；
   - 傀儡的 `guarded.setHealth` 在 `tickCount > 5` 后会拒绝"变大"的写入，这种会出现在 `rejected`；
4. `L2 ... appliedAvg` 与 `finAvg` 的差：差多少就是"算出来的伤害"和"血实际掉了多少"的差
   （傀儡的 `dynamic_reduction` 血量地板会在这里显形）。

### 4.5 仪器自检（很重要）

P0 的观测代码本身必须无副作用。做一次 A/B：

1. 不开 `/ga health start`，用测试台现有 `[GA-Probe]` / 血量差打 20 下，记下 `fin` 和实际掉血；
2. 开 `/ga health start`，同样条件再打 20 下；
3. 两次的 `LivingDamageEvent` amount、实际 delta 应一致。

如果开记录后伤害变了，优先怀疑：

- `getHealth` mixin 与别的 mod 的 `getHealth` 注入顺序；
- 事件监听器顺序（我们用了 `LOWEST`，只读，但会参与事件分发）；
- 你打的不是同一个目标/同一套装备。

## 5. 判定标准（P0）

| 指标 | 通过条件 |
|---|---|
| L2 事件流 | control 与 test 的 `living_attack` / `living_hurt` / `living_damage` 次数、amount 均值、cancelled 一致（同 source、同 target）；均值判定要**绝对差 ≤0.01 且相对差 ≤5%** |
| L2 终值 | 两边 `finalized` 次数一致，`fin` / `applied` 均值同样按绝对+相对双阈值 |
| 实际掉血 | 两边 `tick_health` / `l2_applied` 的 delta 均值同样按绝对+相对双阈值 |
| 写血路径 | 正常 L2 命中下，最外层**伤害**写入的 `unpairedDamage = 0`（每次 `setHealth`/`guarded.data` 都能配到一条 finalize）；治疗造成的写入单独记 `healWrites`，不应算未配对 |
| 死亡/移除 | `remove` 的 `reason` 合理；`target.health <= 0` 之后才 `KILLED`；没有"服务端已移除但客户端移除包没发"的 tracked 实体 |
| 仪器副作用 | 4.5 的 A/B 中，开/关 trace 的 damage amount 与 delta 一致 |
| 误报 | 正常 L2 命中不产生 `l2_unmatched`；若出现，日志里有 raw/base/fin/healthBefore 可查 |

> P0 **不**判定"外部写血有没有被挡"——那要等 P1 影子账本和 P2 ENFORCE。
> P0 只负责把外部写血**看见**：`write.l2=false` + `unpairedDamage` + `caller` 栈。
> `l2_applied` 只配**最外层**写入；傀儡的 `guarded.data` 内部再调 `super.setHealth` 是嵌套同步，
> 不会抢走这次配对（旧版本会，导致 `guarded.data` 被误报 unpaired）。
> `tick_health` 的净掉血会被回血抵掉一部分；逐击真实落地看 `l2_applied.applied`。
> **注意**：旧 summary 用 0.01 绝对容差，`fin=0.030 vs 0.024` 会被误判成 OK；现在必须同时满足
> 相对差 ≤5%。`kept≈0.002` 的高减伤目标尤其要看相对差。

## 6. JSONL 速查

每行一个 JSON 对象，公共字段：`kind`、`seq`、`ms`、`tick`。

| kind | 含义 |
|---|---|
| `meta` | start/stop 元信息、被追踪实体、采样配置 |
| `event` | Forge 伤害/治疗/死亡事件 |
| `write` | 一次 setHealth / guarded.setHealth / guarded.data；`nested` 表示是不是嵌套在另一层写入里 |
| `read` | 采样到的 getHealth 调用（值 + caller，不写栈） |
| `remove` | `Entity.setRemoved` |
| `client_remove` | 发给客户端的移除包 |
| `l2` | l2 的 6 个阶段 |
| `l2_applied` | L2 finalize 后第一次匹配上的写血：`applied`、`ratio` |
| `l2_unmatched` | finalize 后 1~2 tick 内没等到匹配的写血（被取消/没落地/目标已死） |
| `tick_health` | 每 tick 血量变化（ground truth） |
| `tick_max_health` | 每 tick 最大生命变化（HEALTH_SEAL / trim_max_health） |

## 7. 已知限制

- **P0 不拦任何东西**。`unpairedDamage` 只是"没配到 L2 的顶层伤害写入"的信号，不是"外部写血"的定论；P1 会用 expected/actual 影子账本坐实。治疗写入计入 `healWrites`，嵌套的 `super.setHealth` 同步不计入。
- 旧版本（12:24 及更早）会把 `GuardedEntity.setGuardedDataImpl` 内部的 `super.setHealth` 当成 L2 落地，导致真正的 `guarded.data` 被误报 unpaired；现在 `takeL2` 跳过 `nested` 写入，`l2_applied.op` 应为 `guarded.data`（傀儡）或 `living.setHealth`（非傀儡）。
- `getHealth` 是热路径。`active=false` 时只有一次 volatile 读；`active=true` 时每个 `getHealth` 会做一次 map 查找 + 计数，采样才走 StackWalker。默认 `sampleEvery=256`，`read` 只记 caller，不再写栈。长时间 `trackAll=true` 不建议。
- 傀儡的 `guardedData` 是懒初始化：观测代码在 `guardedData == null` 时**不读** `getGuardedDataImpl()`，避免让它提前创建。代价是"实体刚生成、第一下就命中"的极少数写入会 `beforeKnown=false`，不参与 L2 配对。
- 本测试台按 `modulargolems 2.7.3` 编译，并已在 **2.7.5 的 reobf jar** 上核对：`GuardedEntity.setHealth/getHealth` 作为 vanilla 覆写，生产环境里是 `m_21153_` / `m_21223_`，**必须走 refmap**；`setGuardedDataImpl(FZZ)V` 与 `guardedData` 是本家自有名字，用 `remap = false`。换大版本后如果这些名字变了，`required: true` 会在启动时报出来；不要静默放过。
- 服务器崩溃时 `summary.json/.txt` 可能没有（正常 `/ga health stop` 或停服会写）；`.jsonl` 还在，最多丢最后 16 行。
- 玩家侧 P0 只做观测（`track ... self`）。图腾/重生/吸收/创造模式的完整判定留到 P3。

## 8. 下一步

1. 用 P0 打出 control/test 基线，确认两边一致；
2. P1：给 test 实体挂只读 `HealthLedger`（只在 `LivingDamageEvent` / `LivingHealEvent` 更新），比较 `expected` 与 `entity.getHealth()`，出现 `UNEXPLAINED_HEALTH` 才算外部写；
3. P2：只在 test 实体上开 `SHADOW` → `ENFORCE`，A/B 比较 L2 事件流是否仍然一致；
4. P3：玩家侧 SHADOW → 局部 ENFORCE，最后才碰 `abilities.invulnerable` / 图腾 / 重生。

## 9. 故障排查（已知踩坑）

### trace 里 `sampleEvery=16` / `caller` 是 `handler$...` / 1 分钟 10MB

说明跑的是旧 jar。先看 `mods\golems_arsenal-0.3-testbench.jar` 的时间戳/大小，和
`build\libs\golems_arsenal-0.3-testbench.jar` 对一下。2026-10-28 14:02 之后的版本：

- 默认 `sampleEvery=256`，`read` 只记 caller、不写栈；
- `caller` 会跳过 Mixin 生成的 `handler$...$ga$...` 帧，显示真正的调用者；
- `l2` 只在 `finalized` 读 raw/base/fin，不再刷 `damage not calculated yet`；
- `l2_applied` 只配最外层写入，傀儡会显示 `op=guarded.data`。

### `caller` 显示 `m_21153_` / `m_21223_`，或 `living.setHealth` 的 caller 是目标自己

生产环境里 vanilla 覆写叫 `m_21153_`/`m_21223_`；旧版只过滤开发名 `setHealth`/`getHealth`，
所以 caller 会停在被注入的目标方法上。新版同时过滤 SRG 名，并把常见 SRG 映射回可读名
（`m_21153_→setHealth`、`m_5634_→heal` 等）。如果 summary 里还看到 `m_21153_`，说明 jar 旧。

### control 有大量 `living_heal`、test 为 0；`fin` 只有 0.025/0.032

这不是 L2 的问题（`l2_applied` 仍然 15/15、30/30，ratio≈1.0，`unpairedDamage=0`），而是
两只傀儡的目标配置不同。`kept` 只有 0.002~0.003 时，`damage_reduction`/护甲/升级的极小差异
会放大成很大的相对 `fin` 差异。先做完全相同的一对（同材料/同升级/同效果），再打**相同数量的
有效 `living_damage`**（不是相同挥击数）；控制组大量 `living_hurt` 没走到 `living_damage`，
说明有额外的吸收/上限/免疫在生效。用 `/ga debug inspect` 对比护甲/韧性/减伤属性和登记表。

### `/ga health track ...` 总是报"没看向生物"

这说明 `player.pick` 没拿到实体：可能没瞄准、目标在 32 格外、被方块挡住，或者目标
`isPickable` 为 false。不要反复试看向，直接用：

```
/ga health nearby 32
/ga health track control nearest modulargolems:metal_golem   # 站到目标旁边；只选该类型、未追踪的最近者
/ga health track control @e[type=modulargolems:...,limit=1,sort=nearest]
```

- `nearby` 会列出类型、名字、距离、UUID；用 `@e[type=...,limit=1,sort=nearest]` 可以精确选。
- `nearest [type]` 会跳过已追踪实体，并打印它选中了谁；选错就 `untrack all` 后重来。
- `start` 前必须 `/ga health list` 看到 `tracked=2` 且 `pair=OK`。`tracked=0` 的 trace 只有一行 meta，
  打多少下都不会有数据（`p0-20261028-121805.jsonl` 就是这种情况）。

### `pair=BAD` / start 拒绝："control/test 类型不一致"

这说明追踪表里混进了别的傀儡（例如 control 是狗傀儡、test 是金属傀儡），或者两只金属傀儡
都被登记成了 test。P0 只比较同类型实体，所以 start 会硬拒绝。清理后重来：

```
/ga health untrack all
/ga health nearby 32
/ga health track control nearest modulargolems:metal_golem
/ga health track test nearest modulargolems:metal_golem
/ga health list        # pair=OK
/ga health start p0-run3
```

已登记为 control 的实体**不能直接改成 test**（命令会拒绝），否则 control 会静默丢失；
要改角色先 `untrack all`。

### `L2DamageTracker: damage not calculated yet` 刷屏

旧版 P0 在 l2 的每个阶段都读 `getPreDamage/getDamageDealt`，早期阶段 L2 会先打这条 ERROR
再抛异常（异常被我们吞了，但日志刷屏）。14:02 之后只在 `finalized` 读 raw/base/fin；早期阶段
只记事件 amount。若装的是新 jar 还看到这条，说明另有调用点，把当次 `latest.log` 发我。

### `could not find any targets matching 'setHealth' in ...GuardedEntity`（启动 FATAL）

本家 jar 是 reobf 的：`GuardedEntity.setHealth/getHealth` 是 vanilla 覆写，生产环境叫
`m_21153_` / `m_21223_`，不是 `setHealth` / `getHealth`。`GuardedEntityTraceMixin` 的这两个
注入**必须走 refmap**，不能写 `remap = false`：

```json
"a_silly_cat/golems_arsenal/testbench/mixin/GuardedEntityTraceMixin": {
  "setHealth": "Ldev/xkmc/modulargolems/content/entity/common/GuardedEntity;m_21153_(F)V",
  "getHealth": "Ldev/xkmc/modulargolems/content/entity/common/GuardedEntity;m_21223_()F"
}
```

`setGuardedDataImpl(FZZ)V` / `guardedData` 是本家自有名字，保持 `remap = false`。

### `@Shadow field guardedData was not located`

本家换了版本，`guardedData` 字段名没了。先
`javap -p dev.xkmc.modulargolems.content.entity.common.GuardedEntity` 看实际字段；
P0 不能没有这个字段（否则会提前触发懒初始化），所以宁可启动报错也不要静默跳过。

### 其他 mixin 报错

先看 `latest.log` 里第一处 `Mixin apply failed`。通常不是 L2 的问题，而是某个目标类的生产名
（SRG）与开发名不一致。规则：**vanilla 成员走 refmap；mod 自有成员才用 `remap = false`。**
