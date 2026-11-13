# agent 层静态审计（2026-11-12）：实际生效的门、死代码、以及和 mod 层身份轮换的冲突

> 本轮只读代码 / 读日志，不启动游戏、不改 Scrubber、不改 agent 字节码。
> 触发：mod 层补完后，客户端 Alt+Y 打开 YSM GUI 崩溃；栈顶是
> `sanctuary.agent.Scrubber.scrubClass(Scrubber.java:131)`，不是 mod 层门。
> 但审计过程中发现了一个比崩溃更关键的**跨层身份不同步**问题，放在 P0。

---

## 0. 一句话结论

agent **不是**"只保留了低风险部分"：attach 时它 `retransform` 了 **1129 个 mod 类**，
字段门/方法调用门铺得很广；但 `VANILLA_GATES` 是空的，`blockRemoval` / `blockIndexRemoval` /
`blockHurt` / `guardDataItem` / `scrubSpawnTag` 这些运行时门**没有调用点**。
同时 agent 自己的身份轮换 `rotateIdentity` **从未被调用**，而 mod 层这一轮新加的
`IdentityGuard.rotate` 换了 UUID 后**没有通知 agent**——agent 的 `PROTECTED` 仍记旧 UUID，
`isProtectedEntity` 对新 UUID 恒 false，`call*` / 字段门会突然不再认这只傀儡。

---

## 1. 日志证据（2026-11-12 11:15–11:55 这次启动）

| 事实 | 日志 |
|---|---|
| agent 已 attach | `[Sanctuary] agent=ATTACHED`，`SanctuaryAgent: agent self-attach ok` |
| mod 层 16 个 hook 都在 | `[Sanctuary] guard canary: all 16 hooks present` |
| mod 层实际拦下 | `TICKLIST-DENIED`、`INDEX-DENIED`、`HURT-DENIED`、`IDENTITY-ROTATED`、`blocked site=Entity.onRemovedFromWorld` |
| 身份轮换确实发生 | `IDENTITY-ROTATED 721e6786... -> 3bd07316... site=removal_KILLED` 等 3 只 |
| agent 把**全 mods** 都重打了 | `retransformed hostile classes=1129`（每次启动/重载） |
| 数据驱动目标 | `data-driven targets added=83 total=83`，`armTargets retransformed=49/50` |
| 反恢复 | `patched .../ClassRestoreManager methods=9`，`patched .../TailGuard methods=3`，`restore targets cleared, remaining=0` |
| 崩溃 | `SanctuaryAgent-Scrubber` → `Scrubber.scrubClass:131` → `Field.get(null)` → 初始化 YSM 类 → `AbstractClientPlayer` 在错误线程 NPE |

mod 层这轮是有效的；这次崩溃不是 mod 层门的回归。

---

## 2. 当前 agent 架构（按实际执行顺序）

```
SanctuaryAgent.agentmain
 ├─ appendBootstrapStub(inst)                 # GuardRuntime 放进 bootstrap
 ├─ GuardRuntime.init(props)                  # 策略 / 日志 / violation
 ├─ Scrubber(inst, 500ms).start()             # 后台全 JVM 扫 static Map/Collection
 ├─ SanctuaryTransformer.addTransformer(...)
 ├─ retransformHostile(inst)                  # attach 前已加载的 mod 类补一次
 │    └─ isHostile = HOSTILE 前缀 || GuardRuntime.TARGETS（数据驱动）
 │       实际日志：1129 个类被重打
 ├─ GuardRuntime.setWatchStarter / setTargetListener
 ├─ NativeLoader.installQuietly()             # 只金库 + JVMTI 探针，不 install JNI hook
 └─ ServerStarted → armWatch()
      ├─ startRetransformWatch / startFastRegate / startVanillaRegate 全部注释
      └─ startRestoreKiller()                 # 每 3s clear()/clearCache()
```

`SanctuaryTransformer` 的实际门：

1. **Rule 方法入口**（`headPatch` → `GuardRuntime.block`）：
   只对 `rules` 里 9 个类生效；实际日志只看到
   `HardErase`、`NativeAgentHealthMethod`、`GungnirExecution`、`GungnirArcaneCompat`，
   以及 `VanSh`/`VanShRadiantSun` 的 `remove`（日志里未单独出现，按规则应生效）。
2. **字段写门**（`FieldWriteVisitor`）：
   对 `isHostile` 为 true 的每个方法生效；`HOSTILE` 前缀 + `isModClass(domain)` 让
   **所有 mods 类**（1129 个）都进入字段门。字段表：
   `DataItem.f_135391_`、`Entity.f_19825_/f_19826_/f_185933_`、
   `Entity.f_146795_`、`Entity.f_146801_`、`isAddedToWorld`、`canUpdate`。
3. **方法调用替身**：
   把 hostile 类里的 `setHealth` / `setRemoved` / `remove` / `kill` / `die` 调用改成
   `GuardRuntime.call*`；`call*` 只按 `isProtectedEntity` + caller 白名单判。
4. **读侧替身**：
   hostile 类里的 `getHealth`/`getMaxHealth` → `readFloat`（返回 `1e9`）；
   `DataItem.f_135391_` 的 `GETFIELD` → `readObject`（返回 `Float.MIN_VALUE`）。
5. **NEUTRALIZE**（`alwaysReturn`）：
   `ClassRestoreManager`（9 个方法）、`TailGuard`（3 个）、`MemoryDllLoader`、
   `OriginalClassProvider`。
6. **Scrubber**：每 500ms 对 `inst.getAllLoadedClasses()` 的每个类的每个 static
   `Map`/`Collection` 做 `field.get(null)`，读里面的 key/value，命中受保护 UUID 就删。

---

## 3. 实际生效 vs 死代码（审计重点）

| 组件 | 实际状态 | 证据 / 原因 |
|---|---|---|
| `Rule` 方法入口 | **部分生效** | 日志 `patched HardErase methods=2`、`GungnirExecution methods=7`；但 `HardRemove` 规则类名写错（见 P1-1） |
| 字段写门 | **生效但过宽** | 1129 个 mod 类被重打；`f_135391_` 一律拒（不看目标） |
| `setHealth`/`setRemoved`/`remove`/`kill`/`die` 调用替身 | **生效** | hostile 类里的调用被替换；但依赖 `PROTECTED`，身份轮换后会失效 |
| `blockRemoval` | **死代码** | 只有 `vanillaGate("removal")` 调用；`VANILLA_GATES` 为空 |
| `blockIndexRemoval` | **死代码** | 只有 `vanillaGate("index")` 调用 |
| `blockHurt` | **死代码** | 只有 `vanillaGate("hurt")` 调用 |
| `guardDataItem` | **死代码** | 只有 `vanillaGate("data")` 调用 |
| `scrubSpawnTag` | **死代码** | 只有 `vanillaGate("tag")` 调用 |
| agent `rotateIdentity` | **死代码** | 全文件无调用者 |
| `classRestoreManager` neutralization | **生效** | 日志 `patched ... methods=9`；`restore targets cleared, remaining=0` |
| `TailGuard` neutralization | **部分生效** | 日志 `methods=3`；实际类有 `start/runLoop/assertTail/burst/kick` 5 个匹配名 |
| `MemoryDllLoader` neutralization | **名不对** | jar 里只有 `MemoryDllLoader$K32`，没有 `MemoryDllLoader.class` |
| `OriginalClassProvider` neutralization | **名不对** | 实际方法 `initDefaults/addSearchDirectory/getOriginalBytes/clearCache`，没有 `register/provide/restore` |
| Scrubber | **生效但危险** | 500ms 扫全 JVM；这次客户端崩溃就是它 |
| DLL | 只金库/探针 | `NativeLoader` 不调 `install` |

---

## 4. Findings（按优先级）

### P0-1  Scrubber 会在错误线程初始化任意类，客户端直接崩

栈：

```
SanctuaryAgent-Scrubber
  Scrubber.scrubClass:131   field.get(null)
  ysm...O0o0oOO0OOO0O0OooOooOo0o.<clinit>
  AbstractClientPlayer.<init>
  ExceptionInInitializerError / NPE
```

`field.get(null)` 会触发类初始化；Scrubber 遍历的是**全 JVM 已加载类**，包括客户端
YSM 的类。把客户端类初始化到 daemon 线程 → 后续 GUI 打开时
`NoClassDefFoundError: Could not initialize class ...`。

同时它会对**任意 static Map/Collection** 调 `field.get` / `map.remove`，日志里已删掉
数百条“tombstone”，很多不是对手的表，兼容性风险很大。

### P0-2  mod 层身份轮换后，agent 不再认这只傀儡

- mod 侧 `IdentityGuard.rotate` 换了 `Entity.uuid`，迁移了 mod 自己的表；
- agent 侧 `GuardRuntime.PROTECTED` 仍是旧 UUID；
- agent 的 `isProtectedEntity` 读 `f_19820_/uuid` 查 `PROTECTED` → new UUID 查不到；
- 于是 `allowCall`（`callSetHealth` / `callSetRemoved` / `callRemove` / `callKill` /
  `callDie`）和 `denyField`（坐标、removalReason、levelCallback）对新 UUID 全部放行；
- `Scrubber` 的 `protectedIds` 也来自 `GuardRuntime.protectedIds()`，同样只剩旧 UUID；
- `AgentBridge` 目前只有 `onProtected(UUID)`，没有“轮换”入口；`Sanctuary.onJoinLevel`
  只在 join 时推，live rotate 不会触发 join。

这是本轮发现的**最严重的跨层缺口**。mod 层仍然挡得住，但 agent 测试会看到
“换名前拦、换名后放行”的假象。

### P0-3  `VANILLA_GATES` 为空，四个运行时门是死代码

`SanctuaryTransformer.VANILLA_GATES` / `VANILLA_UNUSED` 都是空 Map（第 313–327 行）。
`SanctuaryAgent` 里 `startVanillaRegate` 也没有启用。结果是：

- `guardDataItem`（SynchedEntityData#set 入口）没有调用点；
- `blockIndexRemoval`（ClassInstanceMultiMap.remove）没有调用点；
- `blockHurt`（actuallyHurt/hurt 直伤）没有调用点；
- `blockRemoval`（setRemoved 基类）没有调用点；
- `scrubSpawnTag`（NBT 生成洗 UUID）没有调用点。

交接文档说“写入方字段门 / 方法调用包装 / 读侧替换”在 agent 层，但**方法入口的
原版门已经撤空**；现在 agent 真正生效的是 Rule 类入口 + hostile 类的字段/调用替身。

### P1-1  Rule 表里有写错的类名 / 漏掉的处决入口

- `rules` 里 `.../EntityRemover/Purge/HardRemove` 实际在
  `.../EntityRemover/RemoveHelp/HardRemove`（jar 里没有 `Purge/HardRemove.class`）→ 该规则死。
- `MainRemove`（`RemoveHelp/MainRemove`）没有 Rule，只有字段门；它的方法入口没有
  `block` 判断。
- `HardErase` 只匹配了 `stripEntity` / `purgeSections`（`methods=2`）；公开的
  `erase(...)` 三个重载、`purgeChunkMap`、`broadcastRemoval` 没有入口门。
- toaru 的 `GungnirExecution` 7 个方法已打，但 `finishRemoval` / `finishCollateralRemoval`
  等后续清理链是否在 `GungnirExecution` 内，需要按实际字节码再核。

### P1-2  字段门 `f_135391_` 无条件拒绝，过宽

`GuardRuntime.denyField` 第 935 行：只要 `site.endsWith(".f_135391_")` 就拒绝，
不看目标是不是受保护傀儡。也就是说任何 mod 类写任何实体的 `DataItem.value` 都会被吞。
这会影响非傀儡实体和正常模组；字段门应该先用 `entityOfData(target)` 反查实体，再
`isExempt(entity)`。

### P1-3  读侧替身仍开着，且方法查找忽略 SRG

- `readFloat` / `readObject` 仍返回 `1e9` / `Float.MIN_VALUE`（交接文档说读侧掩护已回退）。
- `GuardRuntime.method(...)`（第 1100 行）拿到 `srg` 却没用，只按
  `m.getName().equals(name)` 找方法；生产环境方法名是 `m_21223_`，所以 trusted 分支
  找不到 `getHealth`，返回 `0.0F`。
- `readObject` 把 `f_135391_` 当方法名去 `getDeclaredMethods()` 找，必然找不到；
  trusted 分支返回 `null`。
- 结果：非 trusted 的 hostile 类读罩值是对的，但 trusted 的 hostile 类读到 0/null；
  这两条要么修对，要么整体关掉。

### P1-4  NEUTRALIZE 有一半是空名

- `ClassRestoreManager` 9 个方法已打（`register*` / `retransformAll` / `primeCache`）；
- `TailGuard` 只打了 3 个（实际匹配名有 5 个）；
- `MemoryDllLoader`：jar 中只有 `MemoryDllLoader$K32`，NEUTRALIZE 的类名不存在；
- `OriginalClassProvider`：实际没有 `register/provide/restore`，只有
  `initDefaults/addSearchDirectory/getOriginalBytes/clearCache`。
- `RestoreKiller` 每 3s 调 `clear()`/`clearCache()`，日志 `remaining=0`，这条是有效的；
  但它只清 ClassRestoreManager，不等于 TailGuard/native 恢复链被完全停掉。

### P1-5  attach 时重打 1129 个 mod 类

`retransformHostile` 用 `isHostile` 判，`isHostile` 对 `isModClass(domain)` 为 true 的
**所有 mod 类**返回 true，于是 attach 时 `retransformed hostile classes=1129`。
这包括客户端类、框架类、和防御无关的模组。代价是启动慢、mixin/类初始化竞争面大；
收益只是字段门覆盖所有 mod。应该改成“数据驱动 targets + HOSTILE 前缀”，
不要用 `isModClass` 兜底全量。

### P2 其他

- `Scrubber` 500ms 全量扫描 + `verbose=true`，日志被 tombstone 刷屏；应先降频/关闭 verbose。
- `isExcluded` 排除了 `dev.xkmc.modulargolems.`，但没排除 `dev.xkmc.l2*`；字段门会改
  l2 框架类。
- `block` 只按 `isOwnedGolem` 判，不看 `EXEMPT`；Rule 方法入口和字段/调用门的判据不统一。
- `VANILLA_UNUSED` 是死 Map；`canUpdate` 字段在 1.20.1 不存在，对应字段规则永远不命中。
- `GuardRuntime.rotateIdentity` / `FAKE_DEAD` / `scrubSpawnTag` 在 agent 侧完整但未接线；
  mod 侧已有 `IdentityGuard`，两边重复且未同步。

---

## 5. 下一轮设计（建议顺序）

### 阶段 A：让 agent 测试可运行（不改业务语义）

1. **Scrubber 安全模式**
   - 加 `scrubber_enabled`，默认 `false`；先让其它门独立测试。
   - 开启时只扫 `GuardRuntime.TARGETS` + `HOSTILE` 前缀的类，不扫全 JVM。
   - 不调用 `field.get(null)` 触发初始化：用
     `sun.misc.Unsafe.staticFieldBase(field)` + `staticFieldOffset(field)` +
     `getObject(base, offset)` 读静态字段；未初始化类读到 `null` 就跳过。
   - 只处理“值类型是 Map/Collection 且类来自 `mods/`”的字段；客户端类/`net.minecraft.client.*`
     直接跳过。
   - 先只记录命中，不删除；确认无副作用后再开删除。
2. **身份桥**
   - `AgentBridge` 增加 `onIdentityRotated(old, fresh)`：
     `GuardRuntime.adoptIdentity(old, fresh)`（新方法）：
     `PROTECTED.remove(old); PROTECTED.add(fresh);`
     `EXEMPT.remove(old); EXEMPT.add(fresh);`
     `FAKE_DEAD.add(old);`
     `Scrubber` 下次 sweep 自动拿到新 `protectedIds`。
   - `IdentityGuard.migrate` 里调用 `AgentBridge.onIdentityRotated(old, fresh)`；
     `Anchor.recreate` 已经走 `migrate`，自动覆盖。
   - `forget(old)` 不要删 fresh；`onJoin` 仍可重复 `protect(fresh)`（幂等）。
3. **状态可见**
   - agent 日志加 `IDENTITY-BRIDGED old -> fresh protected=N`；
   - `/ga guard identity` 里显示 agent `protected` / `fakeDead` 计数（反射读）。

### 阶段 B：把 agent 的门收敛到“mod 层管不到的野路子”

4. **字段门按目标判**
   - `denyField` 对 `f_135391_` 先 `entityOfData(target)`，只有 `isExempt(entity)` 才拒；
     非傀儡/非豁免实体放行。
   - 对坐标/removalReason/levelCallback 保留 `isProtected(target)`，并确保 `PROTECTED`
     已被身份桥更新。
5. **Rule 表修正**
   - `HardRemove` 改为 `.../RemoveHelp/HardRemove`；
   - 加 `MainRemove`（方法名按 javap 实际：`remove`/`removeEntity` 等）；
   - 加 `HardErase.erase` 三个重载；`GungnirExecution` 补齐 `finishRemoval` 链；
   - `VanSh`/`VanShRadiantSun` 规则保留。
6. **原版门做减法**
   - 不要恢复 `Entity`/`LivingEntity`/`SynchedEntityData` 的 `COMPUTE_FRAMES` 巨型门；
   - 只在**小类**上重新开：`ClassInstanceMultiMap.remove`、`EntitySection.remove`、
     `EntityLookup.remove`、`EntityTickList.remove`，用 `COMPUTE_MAXS`/固定栈；
   - 与 mod 层 mixin 二选一，避免双门重复。
7. **读侧**
   - 要么把 `readFloat`/`readObject` 默认关闭（`read_shield=false`），要么修
     `method()` 使用 SRG 名 + 描述符；`readObject` 改成读字段而不是找方法。
   - 修好之前不要把“读侧掩护”算成有效层。
8. **NEUTRALIZE**
   - 删掉 `MemoryDllLoader`、`OriginalClassProvider` 的空名条目；
   - `TailGuard` 补 `start`/`kick`（确认 `methods=5`）；
   - 保留 `RestoreKiller`，但它只算 ClassRestoreManager 的补丁，不算 native/TailGuard 的。
9. **性能**
   - attach 时 `retransformHostile` 只打 `TARGETS + HOSTILE`，不打 1129 个全 mod 类；
   - Scrubber 5s 一次、verbose 默认 false；日志按命中类别聚合。

### 阶段 C：验收（仍不启动游戏）

10. 静态/字节码验收：
    - `agent` 日志出现 `IDENTITY-BRIDGED`；
    - 轮换后 `call*` / 字段门仍命中新 UUID（用临时测试类或单测模拟 `PROTECTED`）；
    - `retransformed hostile` 数量从 1129 降到“targets + HOSTILE”；
    - `VANILLA_GATES` 只含 4 个小类，且每个 `@Method` 存在（javap 对照）；
    - `Scrubber` 默认关闭；开启时只扫 target 类。
11. 运行时验收（下一轮）：
    - 客户端 Alt+Y 不再触发 Scrubber 类初始化；
    - `IDENTITY-ROTATED` 后 `/ga guard watch` 仍显示 `damageBlocked` / `REMOVE-DENIED` 增加；
    - toaru / AV 处决链在轮换后仍被 agent 门拦下（不是只靠 mod 层）。

---

## 6. 本轮已实现（2026-11-12 12:18，只做 1–3）

按"先做 1–3"落地，Scrubber / VANILLA_GATES / 字段门收窄仍后议：

1. **身份桥**
   - `sanctuary-agent/.../GuardRuntime.java` 新增 `adoptIdentity(old, fresh)`：
     `PROTECTED` / `EXEMPT` 搬 key，`FAKE_DEAD.add(old)`，日志
     `[SanctuaryAgent] IDENTITY-BRIDGED old -> fresh protected=N exempt=N fakeDead=N`。
   - 新增 `AgentBridge.onIdentityRotated(old, fresh)`（mod → agent 反射调用）。
   - `IdentityGuard.migrate` 已接上：`rotate` 和 `Anchor.recreate` 都会同步 agent。
   - `/ga guard identity` 追加 `AgentBridge.agentStatusLine()`，显示
     `agentProtected=` / `agentFakeDead=`；agent 没挂时显示 `agent=off`。
   - `Scrubber` 每轮 `GuardRuntime.protectedIds()`，桥之后自动拿到新 UUID。
2. **Rule 表修正**
   - `HardErase`：`Rule` 增加 `objectReturn`，`erase`/`eraseLegacy` 现在会被入口门拦；
     原有 `stripEntity` / `purgeSections` / `purgeKnownUuids` 保留。
   - `HardRemove`：类名改为实际包 `.../EntityRemover/RemoveHelp/HardRemove`。
   - `MainRemove`：新增规则，覆盖 `remove` / `removeEntityFromSection` /
     `forceRemove*` / `clearEntity*` / `chunkMapRemove*` / `deleteFrom*`。
3. **产物**
   - agent jar 重建并嵌入 `src/main/resources/sanctuary-agent.jar`；
   - `gradlew build -Ptestbench` 通过；新 jar `build/libs/golems_arsenal-0.3-testbench.jar`
     （1,423,581 B），内嵌 agent 已用 `javap` 确认含 `adoptIdentity(UUID,UUID)`。
   - 未改 Scrubber 的扫描逻辑，也未改 `VANILLA_GATES`。

**仍待做**：P0-1 的通用安全模式（目前只做了 YSM 例外，见 §8）、P0-3 原版门（4 个小类）、
P1-2 字段门按目标收窄、P1-3 读侧 SRG/字段修正、P1-4 NEUTRALIZE 空名清理、
P1-5 attach 重打范围收窄。

## 7. 2026-11-12 12:45 测试结果 + 帧修复

这轮测试确认了身份桥：

```
[SanctuaryAgent] IDENTITY-BRIDGED f7721e90... -> da4281d3... protected=3 exempt=1 fakeDead=1
[SanctuaryAgent] IDENTITY-BRIDGED cf10a94e... -> e2f7aa7f... protected=3 exempt=2 fakeDead=2
...
[SanctuaryAgent] IDENTITY-BRIDGED 6df4d2c2... -> 76099996... protected=9 exempt=6 fakeDead=6
```

轮换后的新 UUID 上，mod 层继续命中 `INDEX-DENIED` / `TICKLIST-DENIED`，
说明身份桥和 mod 侧迁移都生效。

但这轮暴露了一个 agent 字节码 bug：

```
[toaru] 失败: VerifyError: Bad type on operand stack
  Location: dev/othinus/toaru/GungnirArcaneCompat.finishNativeHealth @181: invokestatic
  Reason: Type 'java/lang/Object' is not assignable to 'java/lang/Throwable'
```

根因不是 `GungnirArcaneCompat` 本身，而是 `SanctuaryTransformer` 的
`ClassWriter(COMPUTE_FRAMES)` 覆写 `getCommonSuperClass` 恒返回 `java/lang/Object`；
遇到 `catch (any)` 的异常处理器时，栈上的 `Throwable` 被算成 `Object`，运行期 VerifyError。

**已修**：`SanctuaryTransformer.commonSuperClass(a, b, loader)` 用目标类自己的
ClassLoader 解析继承链，解析不到才退回 Object。用 ASM `CheckClassAdapter` 对
真实的 `GungnirArcaneCompat` 做了离线验证：

```
input=5066 output=5346
CheckClassAdapter.verify -> 无报错
```

agent 已重新构建并嵌入 mod jar；这一项需要下一轮客户端用 toaru 再验一次。
当前产物：`build/libs/golems_arsenal-0.3-testbench.jar`，1,424,057 B。

同一次测试里 Scrubber 崩溃仍在（12:45:45，YSM `AbstractClientPlayer`），
按约定没有在本次改 Scrubber。

## 8. 2026-11-12 12:56 Scrubber 白名单：放过 YSM

按"只写白名单，放过 YSM"做的**最小改动**，没有实现通用安全模式：

- `Scrubber.SKIP_PREFIXES = { "com.elfmcys.yesstevemodel.", "com.elfmcys.yes_steve_model." }`；
- `sweep()` 在 `scrubClass` 之前跳过这些前缀；
- 这样 YSM 的类不会再被 `field.get(null)` 触发初始化，Alt+Y 那条崩溃路径先绕开；
- 其它客户端类仍可能被 `field.get` 初始化，P0-1 的通用安全模式仍未做。

重新构建后：`sanctuary-agent.jar` 244,891 B，已嵌入 mod jar；
`build/libs/golems_arsenal-0.3-testbench.jar` 1,424,290 B。

## 9. 2026-11-12 13:18 测试：toaru 探针绕过了 execute 门

这轮 `/ga guard toaru` 的结果：

```
[toaru] mode=EXECUTION result=Result[status=APPLIED, removedFromWorld=true] raw 666.0 -> 0.0
[toaru] mode=EXECUTION result=Result[status=INCOMPLETE, removedFromWorld=false] raw 100.0 -> 0.0
[toaru] mode=EXECUTION result=Result[status=INCOMPLETE, removedFromWorld=false] raw 0.0 -> 0.0
```

原因不是整层失效，而是**测试刺激走了一条比 agent 规则更低的入口**：

- `ToaruProbe` 调的是 `GungnirDamageApi.service().strike(caster, target, mode)`；
- agent 的 Rule 之前只覆盖 `GungnirExecution.execute / erase / performExecution / removeNonPlayer`；
- `strike` 的实现是 `dev.othinus.toaru.GungnirDamageApi$Authority.strike`，它没有经过 `GungnirExecution.execute` 的入口门；
- mod 层的 `Sanctuary` 仍然拒绝了 `toaru.gungnir` 的 `LivingAttackEvent`（日志有 `rejected kind=OPERATION`），但 toaru 的 `strike` 在更低层写血/移除，第一只傀儡因此 `APPLIED`；后两只已被后续门拦成 `INCOMPLETE`。

**已修**：

- 新增规则 `dev/othinus/toaru/GungnirDamageApi$Authority`，前缀 `strike`，`objectReturn=true`
  （阻断时返回 null；`ToaruProbe` 会显示 `result=null`，不会 NPE）；
- `GungnirExecution` 规则补 `finishRemoval` / `finishCollateralRemoval` / `settleAndRemove`。
- 离线验证：`patched GungnirDamageApi$Authority methods=1`，`strike` 入口已插入
  `GuardRuntime.block`（`aload_2` 是 target），`CheckClassAdapter` 无报错。

下一轮应看到 `/ga guard toaru` 的 `APPLIED` 消失、`raw 666 -> 0` 不再发生。
真实玩家的 `GungnirExecution.execute` 之前有门；这次补的是探针/低层 API 这条。
当前产物：`build/libs/golems_arsenal-0.3-testbench.jar`，1,424,390 B。

## 10. 2026-11-12 13:46 测试：strike 门加载了，但 block 没认主人

这轮 agent 日志里已经出现：

```
patched dev/othinus/toaru/GungnirDamageApi$Authority methods=1
```

但 `/ga guard toaru` 仍然：

```
[toaru] APPLIED raw 666.0 -> 0.0          (13:46:01, uuid=62e69692...)
[toaru] INCOMPLETE raw 0.0 -> 0.0         (13:46:04 / 11 / 15)
```

说明 `Authority.strike` 的 `block` 被调用了，但返回 false，没有 deny。根因在
`GuardRuntime.block`：

- 它只按 `isOwnedGolem(target)` 判；
- `isOwnedGolem` 用 `invoke(target,"getOwnerUUID")`，而 `invoke` 只按**简单名**找方法；
  生产环境里 `getUUID` 是 `m_20148_`，日志中 `REMOVE-DENIED ... uuid=null` 就是这个
  反射失败的证据；`getOwnerUUID` 同样可能拿不到 → owner null → block=false。
- 因此 `GungnirExecution.execute` 和 `Authority.strike` 的入口门在生产环境实际是放行的。

**已修**：

1. `block` 改为 `if (!isOwnedGolem(target) && !isExempt(target)) return false;`
   `isExempt` 走 `PROTECTED`/`EXEMPT` + UUID 字段，不受方法名影响；
2. `isOwnedGolem` 在 `getOwnerUUID` 失败时回退读 `owner` 字段；
3. 重新构建/嵌入：`sanctuary-agent.jar` 245,016 B，
   `build/libs/golems_arsenal-0.3-testbench.jar` 1,424,425 B。

关于“第一次能打、后面超级适应、VanSh 后刷新”：toaru 的 `GungnirLedger` 本身就会
`record` 目标（`erasedInstance` + `GungnirIdentity.uuid/currentUuid` + `GungnirLineage.recordBirth`），
后续 `shouldBlock` 命中就返回 `PRESERVED/INCOMPLETE`。我们的 `IdentityGuard` 每次拦下移除
会换 UUID；如果新 UUID 还没进 toaru 的 lineage，就会得到“一次新目标”。VanSh 命中不同
傀儡/触发换名后，toaru 下一次选到的可能是未记录的新身份，看起来像“刷新”。
13:46 这轮里第一次 `APPLIED` 的 `62e69692...` 就是这种未记录目标；其它已记录目标都是
`INCOMPLETE`。

下一轮预期：agent 日志出现 `DENIED dev/othinus/toaru/GungnirDamageApi$Authority.strike`
或 `DENIED ...GungnirExecution.execute`，`/ga guard toaru` 不再 `APPLIED`。

## 11. 2026-11-12 15:38 验证：strike 门生效

最新日志里三次 `/ga guard toaru`：

```
[toaru] result=null raw 666.0 -> 666.0 max 666.0 -> 666.0
[toaru] result=null raw 666.0 -> 666.0 max 666.0 -> 666.0
[toaru] result=null raw 666.0 -> 666.0 max 666.0 -> 666.0
```

没有一次 `APPLIED`，raw 也没再被写成 0。说明上一轮 `block` 的
`isExempt` + owner 字段回退生效，`GungnirDamageApi$Authority.strike` 入口
返回 null，`ToaruProbe` 正常显示 `result=null`。

同一段日志里：

- `IDENTITY-ROTATED` / `INDEX-DENIED` 继续命中；
- `CombatAdmission would_reject` 对 `arcanevortex:van_sh` 生效；
- `DamageAdmission handled ... applied=false` 对 fantasy true-damage 生效；
- 最后正常保存世界，没有 Scrubber/VerifyError 崩溃。

agent 层这条“探针/低层 API”路径算闭环了。下一步应测真实玩家
`GungnirExecution.execute`，以及 `VANILLA_GATES` / Scrubber 通用安全模式。

## 12. 2026-11-12 20:38 OrangeSummer 突破

`arcanevortex:orange_summer` 这轮把三只傀儡都清掉了。日志顺序：

```
host_api_foreign caller=OrangeSummer#remove        (mod 层识别到外部驱动宿主 API)
allowed setHealth ... scope=host amount=0.0        (宿主内部清理写被放行)
ATTRIBUTE_BASE_BLOCKED / ATTRIBUTE_MODIFIER_BLOCKED
HEALTH_TAMPER_REPAIRED raw=0.0 -> 666.0
Golem ... is forcefully removed
removal rejected KILLED ...
INDEX-DENIED ...
dump: 0 golem(s)
```

根因不是 `Sanctuary` 没判，而是 **`OrangeSummer` 走的入口没有方法门**：

- `OrangeSummer.remove(Entity, Player)` 直接做：
  `Entity.f_19840_/f_19802_` 字段写 → `hurt(Infinity)` →
  `SynchedEntityData.set(DATA_HEALTH_ID, 0.1)` → `LivingEntity.setHealth(0.1)` →
  `HealthModify.tagModify/attributeModifier/entityDataModify/forceModify` →
  `DropLootHelp.dropLoot` → `VanShCrusher.remove`。
- agent 之前对这些类只有 `FieldWriteVisitor`（字段门），**没有 Rule 方法入口**；
  `VanShCrusher` 日志也确认过 `methods=0`，只 patch 了字段。
- `DamageAdmission` 对 `OrangeSummer#remove` 打了 `host_api_foreign` 并拒绝了一次
  `setHealth`，但随后宿主自己的 `GuardedEntity` 清理链又调了一次 `setHealth(0)`，
  这次栈里只剩宿主帧，被 `scope=host` 放行。
- `RemovalGuard` 的 `setRemoved` 拒绝发生在 `Golem ... forcefully removed` **之后**；
  `VanShCrusher.remove` 已经先把实体从 section/lookup 上摘掉，`INDEX-DENIED` 只是事后日志。

**已补**：

```java
rules.put(".../Sword/OrangeSummer/OrangeSummer", new Rule(false, "remove"));
rules.put(".../VanSh/VanShCrusher",
        new Rule(false, "remove", "forceRemove", "chunkMapRemove"));
rules.put(".../HealthModifyHelp/HealthModify",
        new Rule(false, "forceModify", "entityDataModify", "tagModify",
                "attributeModifier", "setHealth"));
rules.put(".../DropLootHelp", new Rule(false, "dropLoot"));
```

离线验证 `OrangeSummer`：`patched ... OrangeSummer methods=1`，`remove` 入口已插入
`GuardRuntime.block`。新产物：
`build/libs/golems_arsenal-0.3-testbench.jar` 1,424,574 B。

下一轮预期：agent 日志出现
`DENIED .../OrangeSummer.remove` 或 `.../VanShCrusher.remove`，
不再出现 `Golem ... forcefully removed`，`dump` 仍保留 3 只。

这里还暴露一个通用问题：`DamageAdmission` 的 `scope=host` 会在“外部模组触发宿主
清理链”时放行宿主内部的 `setHealth`。本次用方法入口堵住 `OrangeSummer`；以后遇到
新的“宿主清理 API”调用方，仍应优先在**写入方入口**加 Rule，而不是只靠写入口判栈。

## 13. 给下一位的提醒

- **不要把这次崩溃归因于 mod 层**：栈是 Scrubber；mod 层 16 hooks 和门都已在日志里跑通。
- **不要直接开 Scrubber 做“全 JVM 清墓碑”**：它会在任意线程初始化任意类，客户端必炸；
  先做安全模式，再做删除。
- **身份轮换是跨层的**：mod 换了 UUID，agent 必须同步；否则“mod 层拦下、agent 层放行”
  会被误判成“agent 没用”。
- 本文件的 P0 是下一轮的最小闭环；Scrubber 崩溃的正式修复按用户要求后议，但
  `scrubber_enabled=false` 是让 agent 测试可运行的前置，不算修复崩溃本身。
