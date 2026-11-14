# 防御分层与"削弱"的准确表述（2026-11-11 修订）

> **2026-11-12 补丁**：§1 说"mod 层防御已经做满"不准确；实际缺口（DISCARDED、
> 索引、actuallyHurt、dropAllDeathLoot、levelCallback、GuardedEntity 覆写）与补强结果
> 见 `docs/MOD-LAYER-ROUND-2026-11-12.md`。

> 这份是给后续窗口的口径修正。之前几次汇报把"我们拆掉了自己的实现手法"说成了"防御被削弱"，
> 导致读文档的人以为 **mod 层** 也不行了。事实不是这样。

---

## 1. 顺序：先满 mod 层，才被迫上阶层

```
第一阶段（已完成）：mod 层防御做满
    - DamageAdmission        伤害准入（按 damage tag 判，不看攻击者是谁）
    - RemovalGuard           setRemoved 拦截（KILLED）
    - EntityOnRemovedGuardMixin / EntityIsRemovedGuardMixin   移除回调与读侧伪装
    - HealthDataItemSealMixin / SynchedEntityDataHealthAdmissionMixin   数据写入准入
    - Anchor                 快照 / 重建 / 重索引
    - DataGuard              每 tick 收敛
    - HealthLedger           账本与修复
    - Projection(SEALED)     交互/伤害封锁
    - GuardCanary            启动自检
    - DiskShield             磁盘层（清单/哈希/镜像）

第二阶段（mod 层满了还挡不住，才上升）：agent 层
    - 写入方字段门（PUTFIELD f_135391_ / f_146795_ / f_146801_ …）
    - 方法调用包装（setHealth / setRemoved / remove / kill / die）
    - 读侧替换（readFloat）
    - 反恢复（NEUTRALIZE + RestoreKiller）

第三阶段：DLL 层（JVMTI / JNI / 金库）—— 仍在验证，未稳定
```

**mod 层是满的。** 上阶层不是"因为 mod 层不行"，而是"mod 层管不到绕过 API 的野路子"。

---

## 2. agent 的门是全 mods，不是只针对 AV

判定依据是"这个类是不是从 `mods/*.jar` 加载的"：

```java
boolean modClass = isModClass(domain) && !GuardRuntime.isExcludedDotted(...);
boolean hostile  = isHostile(className) || modClass;
```

`isHostile` 的前缀表只是兜底；**真正生效的是 `isModClass`** —— 任何 mod、任何包名、任何新出现的模组，只要它在写入方，就会被改写。

只有两类东西仍然**点名 AV**：

| 东西 | 为什么点名 | 影响 |
|---|---|---|
| `rules` 里的 `VanSh*` / `HealthModify` / `GungnirExecution` | 这些是"具体武器的具体方法"，是加速项 | 不影响通用覆盖 |
| `NEUTRALIZE` / `RestoreKiller` 里的 `ClassRestoreManager` / `TailGuard` | 这是**反制对手的恢复机制**，天然是对手特定的 | 换对手就要重新找它的恢复入口 |

所以准确说法是：**防御路径不针对任何模组；只有"反反制"部分是对手特定的。**

---

## 3. 最近的"削弱"拆的是什么

拆掉的都是**我们自己的实现手法**，不是 mod 层能力：

| 拆掉的 | 为什么拆 | 它原来补的是什么 |
|---|---|---|
| 原版巨型类的门（`Entity` / `SynchedEntityData` / `EntitySection` / `ClassInstanceMultiMap` / `LivingEntity` / `EntityType`） | `COMPUTE_FRAMES` 与 AV 的 retransform 循环 + G1 GC 并发 → JVM 崩 | 绕过 API 的直写 / 拆索引 / 丢弃 |
| 周期重打（1s / 20s / 200ms） | 每次重打都触发 AV 的 `TailGuard` burst → 补丁风暴 → 服务端线程饿死 | 补丁被恢复后的自动重装 |
| JNI 表 hook（DLL `install`） | 原地改共享 JNI 表 → `defineClass` 崩溃 | native 写字段 |
| 读侧掩护值（1e9） | 把 1e9 灌进了我们自己的账本 | 骗"按血量阈值处决" |

**这些是"实现手法"，不是"防御层"。** mod 层一条没动。

---

## 4. 因此当前的真实缺口（都是 mod 层之外）

| 缺口 | 谁能补 | 状态 |
|---|---|---|
| `discard()` / `DISCARDED` 移除 | mod 层 `RemovalGuard` 扩一条（带玩家回收豁免） | 未做 |
| 拆索引（`ClassInstanceMultiMap` / `EntitySection`） | agent 或 mod mixin | 未做（原版门被拆） |
| `actuallyHurt` 直伤 | agent 写入方门 或 mod mixin | 未做（原版门被拆） |
| 生成时洗 UUID / 身份轮换 | mod 层也能做（纯反射） | 未做 |
| 补丁被恢复后的重装 | DLL 代理 env（JVMTI）或低频主动重写 | 未做 |

**这些缺口不影响"mod 层是满的"这个结论** —— 它们本来就是 mod 层管不到的野路子。

---

## 5. 一句话口径

> mod 层防御已经做满；上阶层是为了覆盖"绕过 API 的野路子"，属于必要升级。
> 最近的回退拆的是**我们自己的高风险实现**（巨型类改字节码、周期重打、改 JNI 表），
> **不是拆防御层**。缺口都在"野路子"那一侧，按上表逐条补即可。
