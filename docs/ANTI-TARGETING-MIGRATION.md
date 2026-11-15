# 去针对化迁移（2026-11-15）

> 目标：把“按对手身份选择对抗策略”的部分，换成“按可复现技术条件统一处理”。
> 这是独立分支 `defense/generic-t1`，从已上传快照 `9e91919` 开始，不直接改测试 jar。

## 已完成的第一刀

- `CombatProfiles` 移除了 `com.erchien.arcanevortex.` 的包名分支；
- 现在只按行为判定：`SwordItem`/`AxeItem`/...、是否覆写 `onLeftClickEntity`/`hurtEnemy`/
  `onEntitySwing`、是否有自定义 `use`/`inventoryTick`；
- 不再因为“这个物品来自某个包”而额外标 `CUSTOM_HOOK`。

## 已完成的第二刀（C2a）

`DamageAdmission.isHostModWrite` 不再只看宿主帧的“下一帧”：

- 只要宿主 API 的写栈里出现任何非宿主 / 非 vanilla / 非框架的模组帧，就拒绝；
- 不检查具体 mod 名，也不检查 AV/toaru 类名；
- 反射写（`setHealth` 等）也会因为调用栈里有外部 mod 帧而被拒。

这是通用技术条件：**宿主 API 只能由宿主/框架链驱动**。

## 已完成的第三刀（C2b）

agent 侧：

- 移除 `HOSTILE` 包名列表；
- 移除 14 组逐类 `rules.put`；
- 移除 `NEUTRALIZE` 的 AV 具体类；
- 移除 `WATCH` / `retransformWatch` / `restoreKiller`；
- 初始 retransform 改为“所有 `/mods/` 类 + 常量池预筛”；
- 只保留通用字段门、方法调用门、`MODIFIED_CLASSES` 和预筛缓存。

仍未完成：

- `ToaruProbe` 仍是测试命令；
- Tinkers 兼容 mixin 仍以特定事件类为目标（属于兼容，不是攻击策略）。

## C3：通用墓碑扫描

`Scrubber` 已移除 `SCRUB_SCOPE`：

- 扫描所有 `code source` 位于 `/mods/` 的类；
- 每轮最多发现 100 个新类，发现阶段分摊到多轮；
- 已命中类只保留 `Map`/`Collection` 的 `Field[]`，之后每轮只扫这些字段；
- 仍排除原版/Forge/ASM/渲染栈/宿主/L2 自身，避免把框架类初始化到 Scrubber 线程。

这不是按对手包名限定，而是按“类是否来自 mods、是否有静态集合字段”的技术条件。

## 当前测试包验证点

用当前测试包（不是匠战妖域）：

1. 启动日志出现 `retransformed mod classes=... modified=...`；
2. 不再出现 `restore killer` / `neutralize retransformed`；
3. Shift+左键后不再出现 `DamageAdmission allowed setHealth ... scope=host`；
4. 观察启动耗时和 Scrubber 是否仍有明显卡顿；
5. Tinkers 大傀儡、AV/toaru 普通战斗不崩。

## 仍需迁移的针对性部分

| 位置 | 身份依赖 | 目标替代 |
|---|---|---|
| `SanctuaryTransformer.HOSTILE` | 对手包名列表 | 所有 mod 类的技术形态；只按字段/方法调用/反射行为匹配 |
| `SanctuaryTransformer.rules` | AV / toaru 具体类名和方法名 | 通用 `setHealth`/`setRemoved`/`kill`/`die`/字段写/反射调用守卫 |
| `SanctuaryTransformer.NEUTRALIZE` | AV `ClassRestoreManager` 等具体类 | 不再按类名 neutralize；用通用“恢复/重定义”技术条件或交给上游 |
| `SanctuaryAgent.WATCH` | AV 具体类 | 删除；改为通用 transformer 索引 |
| `Scrubber.SCRUB_SCOPE` | AV / toaru 等包名 | 全 mod 静态 Map/Collection 的通用 UUID 墓碑扫描 |
| `AgentBootstrap` 硬编码类 | AV/toaru 类名 | 只保留测试探针或移到 testbench |
| `CombatProfiles.arcaneVortex` | 已移除 | 已改为行为判定 |
| `DamageAdmission.handled_mods/items` | 已知 mod 白名单 | 能力/行为 adapter；身份只用于日志 |
| `ToaruProbe` | toaru 专用测试 | 标注为 test-only，移出生产路径 |

## T1 对手的准备

本测试实例目前没有 pig2 / 超级史蒂夫 jar。需要：

1. 在“匠战妖域”整合包/测试实例中确认两个对手已加载；
2. 先跑 **不实现身份分支** 的通用测试：
   - pig2 雪球：普通 projectile damage 是否被来源准入正确处理；
   - 超级史蒂夫杀務光环 / PLZLiZi 模式 / 5 秒尾杀：是否走 direct health/removal 写；
3. 只把“可复现的技术路径”写进防御：reflect `setHealth`、`setRemoved`、`die`、拆索引、字段直写；
4. 攻击面（从 say/forge entity list 移除对手）单独评估，不要和防御补丁混在一起。

## 检查点

- **C1**：`CombatProfiles` 去身份（本次）。
- **C2**：agent `HOSTILE`/`rules`/`NEUTRALIZE` 去身份，通用方法门。
- **C3**：`Scrubber` 全 mod 通用墓碑扫描 + 字段索引。
- **C4**：T1 实机回归（pig2 / 超级史蒂夫）。
- 每个检查点单独提交、单独构建；失败可回退，不把 C2/C3 混进 C1。

## 风险

- C2/C3 会降低对已知 AV/toaru 路径的定向拦截；这正是“去针对化”的代价。
- 通用反射守卫可能误伤正常模组，需要先用 SHADOW/日志确认覆盖率。
- 没有 pig2 / 超级史蒂夫 jar 时无法验证 T1，不能声称通过。
