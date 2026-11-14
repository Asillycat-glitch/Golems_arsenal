# 全面敌对环境下的防御清单（2026-11-10）

> **前提（本文档的唯一公理）**
>
> 1. 整合包里**每一个** mod 都可能是敌人；
> 2. 每一个 jar、每一份存档、每一段配置都可能是伪造的；
> 3. 进程内存里有敌人的代码在跑，且它可能是 **native**（DLL）甚至**内核**（驱动）级；
> 4. 敌人知道我们的实现（jar 公开可分發），会针对性地绕；
> 5. 敌人可以在任意时刻让游戏崩溃、让电脑蓝屏（参考匠战妖域的 DLL）；
> 6. 我们**不能**要求用户改启动参数、装驱动、或用管理员权限。
>
> 只有在这个前提下设计出来的东西，才配叫"防御"。任何"假设对方只用 Java"的方案一律作废。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 能不能保证"生物绝对不受影响"？ | **不能**。同进程 + native/内核对手，没有任何用户态方案能保证。 |
| 那能保证什么？ | **保证可恢复**：状态有权威副本（账本 + 外置日志），任何篡改在下一次 tick / 下一次启动被拉回；**保证可观测**：每次攻击留下指纹。 |
| 最坏情况（蓝屏/杀进程）怎么办？ | 外置日志 + 原子写 + 启动对账，保证"死机也不丢账"。 |
| 防御的极限在哪？ | 用户态内：native hook（JNI 表/inline hook）+ 数据层权威副本。跨出用户态（内核驱动）后，只剩 OS 隔离（独立用户/VM/驱动白名单）。 |

---

## 1. 攻击面全表（敌人能做什么）

### 1.1 磁盘层

| 攻击 | 具体手段 | 我们能做的 | 代价 |
|---|---|---|---|
| 改 jar | 直接覆盖、换 mtime、替换同名类 | 清单（size+mtime+头 1MB SHA-1）+ `dos:readonly` + WatchService + `/ga guard disk verify` | 低 |
| 删 jar | 删除、改名 | 清单里标 missing，启动报警 | 低 |
| 改存档 | 改 `.dat`、改 region、删 level.dat | 外置账本 + MAC + 启动对账 | 中 |
| 伪造配置 | 改 `config/*.toml` | 配置项校验 + 关键项写进账本 | 低 |
| 塞 DLL | 往 mods 里放带 native 的 jar | 扫 jar 内的 `.dll/.so/.arcane` 并告警 | 低 |
| 锁文件 | 独占打开，让我们写不进 | `FileChannel.tryLock` 失败 → 记日志、走备用路径 | 低 |

**边界**：`dos:readonly` 对管理员/驱动无效；WatchService 有延迟；Windows 没有 Java 层的"拒绝写入"钩子（没有 SecurityManager）。

### 1.2 加载层

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 换启动器 | 用改过的 PCL/HMCL 注入参数 | 无解（用户环境） | — |
| 改 ModLauncher | 换 securejarhandler / bootstraplauncher | 启动后校验关键类 hash | 中 |
| JarJar 嵌套 jar | 把恶意类塞进别人的 jar | 扫 jar 内嵌 jar | 中 |
| 提前 attach | 在 premain 阶段就装自己的 agent | 我们只能"后发制人"（外部 attach 更晚） | — |

### 1.3 类层

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| Mixin | 改任意方法 | 启动 canary 校验关键 handler 是否还在 | 低 |
| ClassFileTransformer | 注册自己的 transformer | 只能 detect（retransform 计数） | 低 |
| redefine/retransform | 运行期换类 | 周期重打 + 抢尾巴（native） | 高 |
| 隐藏类 | `Lookup.defineHiddenClass` / `Unsafe.defineAnonymousClass` | 只能扫 `getAllLoadedClasses` 里的隐藏类 | 中 |
| 类恢复 | 缓存原始字节反复恢复（AV 的 ClassRestoreManager） | 废掉注册入口（NEUTRALIZE）+ 抢尾巴 | 高 |
| 自校验 | 对方的 jar 校验自身完整性 | 无法阻止，只能让它校验失败后行为可控 | — |

### 1.4 进程 / JVM 层

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 自 attach | `VirtualMachine.attach` + `loadAgent` | 我们能做的只有"也 attach"；无法禁止别人 attach | — |
| JVMTI | `ClassFileLoadHook`、`SetFieldModificationWatch` | 我们也能装（需 native） | 高 |
| Instrumentation | 别人拿到 `Instrumentation` 实例 | 无法撤销 | — |
| 反射改字段 | `Field.setAccessible` + `set` | 调用点门 + 读侧掩护 | 中 |
| Unsafe | `Unsafe.putFloat/putObject` | 调用点门（拦 `Unsafe.put*` 的调用方） | 中 |
| 线程 | 起守护线程持续恢复 | 废掉它的线程方法（NEUTRALIZE） | 中 |
| 关进程 | `System.exit` / `Runtime.halt` | 无法阻止；只能保证外置账本已落盘 | — |

### 1.5 native 层（DLL）

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 内存加载 DLL | JNA + `kernel32` 手动映射（AV 的 `MemoryDllLoader`） | 拦它的 Java 入口（`MemoryDllLoader.*` 置空） | 低 |
| JNI 写字段 | `JNIEnv->SetObjectField/SetFloatField` | **我们自己的 DLL 原地覆盖 JNI 表** | 高 |
| inline hook | 改 jvm.dll 的 `jni_SetObjectField` 机器码 | 只能检测（代码段 hash） | 高 |
| 线程劫持 | `CreateRemoteThread` / APC | 无解 | — |
| 直接内存写 | 拿到对象地址后 `WriteProcessMemory` | 只能靠数据层对账 | — |

### 1.6 内核层（驱动 / 蓝屏）

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 蓝屏 | 驱动里 `KeBugCheckEx` / 非法内存访问（匠战妖域的 DLL 传闻） | **无解**。只能：外置日志保证不丢账、启动对账恢复 | — |
| 结束进程 | `ObRegisterCallbacks` 保护/结束 | 无解 | — |
| 改内存 | 驱动直接改用户态内存 | 无解 | — |
| 文件保护 | Minifilter 拦我们的写 | 无解 | — |

**结论**：一旦对手有内核组件，用户态的一切防御都只是"提高成本"。唯一可行的路是 **OS 隔离**：独立 Windows 用户、虚拟机、VBS/HVCI、驱动签名白名单、不给管理员。

### 1.7 数据层（游戏内）

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 改血量 | `DataItem.value` 直写 / `setHealth` / `hurt` | 账本 + 每 tick 收敛 + 读侧掩护 | 低 |
| 改最大生命 | attribute / `getMaxHealth` | 账本 + 恢复 | 低 |
| 移除实体 | `setRemoved` / 拆索引 / `levelCallback` | 移除门 + 看门狗 + 重建 | 中 |
| 改位置 | `setPosRaw` / `moveTo` | 位置快照 + 收敛 | 低 |
| 改 NBT | `saveWithoutId` / 直接改 tag | 外置账本优先 | 中 |
| 改 AI/目标 | brain / target | 未覆盖 | — |

### 1.8 网络层

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 伪造包 | 自建 channel 发假同步 | 客户端信任服务端；单机无解 | — |
| 抢注册 | 抢先注册同 id 的 channel | 记录冲突 | 低 |

### 1.9 人 / 环境层

| 攻击 | 手段 | 我们 | 代价 |
|---|---|---|---|
| 改配置 | 关掉我们的防御 | 关键项写进账本，改了要显式确认 | 低 |
| 删日志 | 清 `logs/` | 关键事件双写（`config/golems_arsenal/vault/journal`） | 低 |
| 换时间 | 改系统时间绕过 TTL | 用 `gameTime` 而不是 wall clock | 低 |

---

## 2. 防御手段总表（按可行性分层）

### A. 纯 Java（可随 jar 分发，任何包都能用）

1. **权威账本**：血量/最大生命/位置/数据快照，存在 `SavedData` + 外置目录；
2. **每 tick 收敛**：`DataGuard` 比对 raw 与账本，偏差即写回；
3. **读侧掩护**：`getHealth/getMaxHealth` 对非可信读者返回掩护值；
4. **写侧门**：字段 `PUTFIELD` / 方法调用点替换，带调用方身份；
5. **移除门 + 看门狗**：`setRemoved/remove/die/kill` 拦截；实体索引被替换则重建；
6. **数据驱动目标扫描**：扫 jar 常量池，按"能力"而不是"名字"选目标；
7. **磁盘层**：jar 清单 + 只读 + WatchService + 存档镜像 + 启动恢复；
8. **canary**：启动校验关键 mixin handler 是否被剥；
9. **熔断开关**：`agent.properties` 可关闭任一层，避免崩循环。

### B. 需要 native（自带 DLL，随 jar 分发）

10. **JNI 表覆盖**：`SetObjectField/SetFloatField/SetDoubleField` → 我们的桩；
11. **JVMTI `ClassFileLoadHook`**：注册在链尾，恢复后立刻重打；
12. **JVMTI `SetFieldModificationWatch`**：对关键字段下修改断点（需实测 JNI 写是否触发）；
13. **inline hook**：改 `jvm.dll` 的 `jni_*` 函数体（需 pattern scan + 内存保护切换）；
14. **DLL 加载拦截**：hook `LoadLibraryExW`，拒绝未知来源的映射。

### C. 需要 OS/内核（只能建议，不能强制）

15. **独立 Windows 用户**运行游戏（敌人写不到用户目录）；
16. **虚拟机/沙箱**（蓝屏不影响主机）；
17. **VBS/HVCI + 驱动白名单**（挡内核组件）；
18. **不给管理员**（挡驱动安装）。

### D. 只能检测、不能阻止

19. retransform/redefine 计数异常；
20. 类字节 hash 变化；
21. 未知 DLL 加载（枚举模块）；
22. 存档 MAC 不匹配；
23. 崩溃转储里的"问题模块"。

---

## 3. 我们的现状对照表

| 手段 | 状态 | 文件 |
|---|---|---|
| 权威账本 | ✅ | `guard/HealthLedger.java` |
| 每 tick 收敛 | ✅ | `guard/sanctuary/DataGuard.java` |
| 读侧掩护 | ✅ | `mixin/GuardedEntityHealthReadMixin.java`、`LivingEntityMaxHealthHideMixin.java` |
| 写侧门（字段） | ✅ | `SanctuaryTransformer` + `GuardRuntime.putX` |
| 写侧门（方法） | ✅ | `callSetHealth/callSetRemoved/callRemove/callKill/callDie` |
| 移除门 + 看门狗 | ✅ | `RemovalGuard`、`Watchdog`、`Anchor` |
| 数据驱动扫描 | ✅ | `DiskShield.scanTargets` |
| 磁盘层 | ✅ | `DiskShield` |
| canary | ✅ | `GuardCanary` |
| 熔断开关 | ❌ 待做 | — |
| JNI 表覆盖 | ⚠️ 已编译，`install` 失败（-码 待测） | `native/sanctuary_jvmhook.c` |
| JVMTI ClassFileLoadHook | ❌ 未做 | — |
| 字段修改断点 | ❌ 未做 | — |
| inline hook | ❌ 未做 | — |
| DLL 加载拦截 | ❌ 未做 | — |
| 外置日志（HMAC） | ❌ 未做 | — |

---

## 4. 检测矩阵（每次攻击都要留痕）

| 事件 | 记录点 | 字段 |
|---|---|---|
| 字段被外写 | `GuardRuntime` | 站点、调用方、UUID、值、tick |
| 方法被拦截 | `GuardRuntime.block` | 站点、UUID |
| native 写被拒 | DLL → `nativeFieldWrite` | 站点、UUID、次数 |
| 类被恢复重打 | transformer | 类名、次数、间隔 |
| 存档被改 | 启动对账 | 文件、MAC、差异 |
| 未知 DLL | 启动枚举 | 路径、大小、hash |
| 崩溃 | hs_err | 问题模块、栈 |

---

## 5. 响应策略

1. **检测到即恢复**（收敛、重建、重读账本）；
2. **连续失败即熔断**（关掉会崩的层，保游戏能开）；
3. **熔断后只保留只读保护**（账本 + 外置日志 + 告警）；
4. **重启后对账**（外置日志 vs 存档，冲突以日志为准）；
5. **把证据写进 `vault/journal`**（即使 `logs/` 被清）。

---

## 6. 测试计划（每条都要能复现）

| # | 测试 | 期望 |
|---|---|---|
| 1 | 五把武器各打一次 | 傀儡不死、读侧不显示 0.1、日志有拦截记录 |
| 2 | 手动改 jar mtime | `/ga guard disk verify` 报 changed=1 |
| 3 | 删存档 `.dat` | 启动时从 vault 恢复 |
| 4 | 杀进程（任务管理器） | 重启后账本一致 |
| 5 | 断网 | 不影响本地防御 |
| 6 | 蓝屏（如果敢测） | 重启后账本一致、日志有最后 tick |
| 7 | 换类名重打包攻击 mod | 数据驱动扫描仍命中（按能力） |

---

## 7. 硬边界（写给自己看）

- 同进程 + native：**竞态**，我们能赢窗口，赢不了永远；
- 同进程 + 内核：**无解**，只能隔离；
- 蓝屏：**无解**，只能保证不丢账；
- 任何"绝对防御"的说法都是假的，能承诺的只有：**可恢复、可检测、可熔断**。

---

## 8. 下一步（按性价比排序）

1. 修 `native install`（错误码已加，等下一轮日志）；
2. **L1 数据层**：`DataItem.value` 常态掩护 + 客户端同步强制账本值（不依赖 native 输赢）；
3. 熔断开关 `agent.properties`；
4. 外置账本 + HMAC（防改存档）；
5. JVMTI `ClassFileLoadHook`（抢链尾）；
6. 未知 DLL 枚举 + 告警。
