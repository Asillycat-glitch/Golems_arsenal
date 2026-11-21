# 当前状态记录：2026-11-21

## 一句话结论

**单开线程不够，后置 section/读侧修复也不够。**
我们仍然没有阻止“改写”本身；现在只是把被改写后的状态反复修回来。
toaru 的 Gungnir 已经证明：对手会直接写 raw 字段/私有终态，绕过我们的读侧回答和容器门。
AV 的“看起来被挡住”也只是写侧/事件侧挡住了，不能当成完整防住。

## 这轮实际测到的现象

- `SanctuaryGuardThread` 已从 100ms 降到 1000ms，并延后到首个玩家登录；加载卡死缓解，但**没有解决阻挡**。
- A 层 `PersistentSectionRemovalGuardMixin` 能打出：
  - `SECTION-DENIED uuid=... reason=KILLED id=...`
  - 但同一 UUID 的 dump 仍是：`raw=0.0 guarded=666 removed=true added=false pickable=false`。
- B 层 `ClientCustomPayloadGuardMixin` 能打出：
  - `CUSTOM_PAYLOAD_BLOCKED channel=toaru:magic uuid=... bytes=19`
  - 但客户端仍可能没有血量/不能回收/模型半清。
- `SECTION-REPAIRED` 修复尝试清 raw reason、`isAddedToWorld=true`、reindex、teleport；还需要实测是否真的恢复 `added=true pickable=true removed=false`。
- `anchor_kept_on_leave`、`watchdog readded`、`INDEX-READDED`、`HEALTH_TAMPER_REPAIRED` 仍在高频出现，说明 toaru 在持续重写，不是一次性攻击。
- `PersistentEntitySectionManager` 之前有 `destroying due to KILLED`；A 层后应变为 `SECTION-DENIED`，但 raw 状态仍被写坏。

## 为什么“单开线程”不够

独立线程只能：

- 以固定频率把对账任务压回服务器线程；
- 在 server thread 上重挂 lookup/section；
- 修 raw 字段/血量/位置。

它不能：

- 阻止另一个 mod 在自己的调用路径里直接 `setRemoved` / 写 raw 字段 / 写私有终态；
- 阻止 native/JVMTI/Unsafe 写；
- 在 toaru 每 250ms 重写一次的情况下避免“修回—再写坏”的循环；
- 恢复客户端私有 tombstone（`GungnirDeathVisuals.CONFIRMED` 这类）。

所以现在的架构是**事后修复**，不是**写入前拒绝**。

## 必须更进一步的方向

1. **写入前拦截，而不是写后修**
   - JVMTI `SetFieldModificationWatch`：`removalReason`、`isAddedToWorld`、health、levelCallback、position；
   - Java agent 改写所有 `/mods/` 类的 `Unsafe` / `MethodHandle` / `Field.set*` / `VarHandle` 调用点；
   - 不要再用旧的全局 JNI 函数表 hook（已确认崩服）。

2. **权威状态移出 Entity，最好移出进程**
   - Entity/DataItem 只当 projection；
   - Soul/Health/Index journal 是 authority；
   - 更强：独立 authority 进程，游戏 JVM 只同步状态；
   - supervisor 只负责进程存活/journal，不解决同进程 native 改写。

3. **客户端投影要成为独立 authority**
   - 不能依赖 toaru 的 `ClientLevel.addEntity` / `GungnirDeathVisuals` 私有状态；
   - 需要自己的 client render state/store，或在网络层拒绝受保护 UUID 的死亡/擦除语义包；
   - 目前 B 是 UUID 字节过滤，仍是半通用，且会误伤同 UUID 的其他自定义包。

4. **A 层之后还要修 raw 状态**
   - `SECTION-DENIED` 只 cancel callback 不够；
   - 必须保证 `removalReason=null`、`isAddedToWorld=true`、section/lookup/tick 一致、pickable/health/data 正常；
   - 否则就是“section 保住了，实体仍是 removed=true”的幽灵。

5. **渲染桥接**
   - 保留真实 `MetalGolemEntity` 和 Modular Golems renderer；
   - 补 equipment/DataItem/upgrade/pose 同步；
   - 用 `RenderLivingEvent.Post` 挂我们的 render skill，所有绘制在 Render thread；
   - 不能从 guard thread 直接碰 `PoseStack`/renderer。

## 当前构建

```
D:\我自己的包\.minecraft\versions\1.20.1-Forge_47.4.20\mods\golems_arsenal-c2c3-forceregister.jar
1,369,270 B   2026-11-20 23:44:28
```

（之后没有重新构建；本文只记录状态，不代表已修复。）

## 规则

- 任何可能**削弱现有防御**的改动，先询问，不擅自做。
- 不把“日志里出现了 DENIED/BLOCKED”当成“已经防住”；以 dump/实际回收/血量/模型为准。
- 不按 mod 名/类名做针对性策略；按技术操作 + 受保护实体状态判定。
- 旧文档 `DEFENSE-LAYERS-2026-11-11.md` 不作为有效依据。

## 下一轮优先级

1. 验证 `SECTION-REPAIRED` 后 dump 是否真的 `added=true pickable=true removed=false`；
2. 若仍失败：JVMTI 字段 watch + agent 写调用点门（写入前拒绝）；
3. 客户端投影 authority/渲染桥接；
4. 最后再谈独立进程 authority。

## 2026-11-21 11:33 更新：agent 写调用点门已落地

- `SanctuaryTransformer` 新增：
  - `sun.misc.Unsafe` / `jdk.internal.misc.Unsafe` 的 `put*`、`put*Volatile`、
    `compareAndSwap*` 调用点 → `GuardRuntime.unsafePut*/unsafeCompareAndSwap*`；
  - `VarHandle.set/setVolatile/setOpaque/setRelease/compareAndSet/getAndSet*`
    → `GuardRuntime.varHandle*`；
  - 目标受保护时在**写入前**丢弃，不再等 section/lookup 被写坏。
- `INTEREST_NEEDLES` 加入 `Unsafe`/`VarHandle`，确保只含这些调用的 mod 类也会进 ASM。
- agent jar：253,808 B；mod jar：1,372,312 B（2026-11-21 11:33:23）。
- 这层是 **Java agent 调用点门**，不是 JVMTI：
  - 能挡 mod 自己的 `Unsafe`/`VarHandle` 调用；
  - 挡不住 JDK/vanilla 已编译代码的写、native 直接写、`MethodHandle` 多态调用；
  - JVMTI 仍需 native 编译环境（当前机器没有 MSVC/MinGW）。

## 2026-11-21 12:33 更新：移除独立守卫线程

参考 `super-steve-1.7.1.3shit.jar` 的做法后确认：

- Super Steve 没有额外守卫线程；状态由实体自己的 `m_8119_` tick 推进，
  血量放在**加密字符串 DataAccessor**（`DATA_SS_HEALTH_ID` + `FloatObfuscator`），
  `getHealth` 覆写读它，`setHealth` 空实现。
- 渲染是它自己实体的 `HumanoidMobRenderer` + 自定义 layer，不接管别的实体。

我们只采用“**不单开线程、由服务器 tick 驱动对账**”这一条：

- 删除 `SanctuaryGuardThread.java`；
- 删除 `Anchor.threadPulse`；
- 对账统一走 `Anchor.onServerTick`（`ServerTickEvent.END`，每 10 tick）；
- 不再有 `server.execute` 后台队列。

没有照搬 Super Steve 的加密血量 DataAccessor：那需要给 Modular Golems 实体加
新的 `EntityDataAccessor` 并改构造/同步，属于会动宿主兼容面的改动，先不擅自做。

当前构建：`golems_arsenal-0.3.jar` 1,369,870 B，2026-11-21 12:33:42。


