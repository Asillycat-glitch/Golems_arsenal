# 摘要：从「論外」到 Minecraft

> 这是 [THREAT-MODEL-MUGEN.md](./THREAT-MODEL-MUGEN.md) 的压缩版。
> 完整版含：1.20.1 源码逐条出处、19 条 MUGEN→MC 映射、四种攻击的代码骨架、DEF 路径防守细节、术语表。

## 一句话

**MUGEN 的「論外」不是数值更高，是“把战斗搬到引擎规则层之下”；MC 的对应物不是某个 bug，是 mod 加载器本身。**
在 MUGEN 里运行任意代码是漏洞利用，在 MC 里运行任意代码是发行方式。
所以同进程内没有绝对防御；能拿到的是**服务端权威、分层冗余、不变量看门狗、归因日志、优雅降级**。

## 两个轴

| 轴 | 问什么 | 档位 |
|---|---|---|
| 时间（启动技术） | 多早能安装？ | R1 → R0 → 选人前 |
| 深度（L0–L5） | 能碰到多底层？ | 数据 → Mod API → 字节码 → JVM → Agent → 进程 |

- 一个技术的上限 = **(最早阶段, 最深层次)**，是偏序，不是标量。
- **铁律**：防御在 L(n)，只有 L(n+1) 及更深能稳定破它；同层只能抢先手。
- **强度是区间**：启动技术定上限（客观），应用技术定下限（主观）；“谁赢”比的是实际工作点。

## MUGEN 的阶梯

| 档位 | 战场 | 代表 |
|---|---|---|
| 狂 / 凶 | CNS 内 | 10 割、当身、即死技 |
| 神 | CNS 边缘 | 常时无敌、无喰らい判定、开幕全屏即死 |
| 論外（内部） | 引擎 bug | 超即死/直死（写 Alive）、混線、変数弄り、親捏造 |
| 論外（外部） | 进程 / OS | `.exe`/`.bat`、`%n`/`%f` → shellcode |

**七夜式三件套** = 防守方也搬到 OS 层：再起動（状态重置）、多スレッド（帧外监视）、HW BP+SEH（指令级拦截）。
共同点：**都比引擎的规则层更低**。

**终局**：隔離（把“不跟你在同一层玩”的技术分档）+ 専用対策の公開（指名可以，但必须公开）。

## 启动技术

| 档 | 上限 | 代表 | 泛用性 |
|---|---|---|---|
| 慢 | R1 | 512 超越 | 高 |
| 快 | R0 | DTC / ATC | 高 |
| 极快 | 选人前 | Statedef 溢出 | 中-高 |
| 更快 | roster 扫描（阶段 2） | DEF 溢出、ZIP 溢出 | 低（XP / 文件依赖 / 顺序） |

- **角色级最早可执行点 = 阶段 2（人物列表扫描）**；再往前只能改系统文件/外部程序。
- **“更快 + 更泛用”不存在**：阶段 2 的解析器最老、最依赖平台；S 溢出是“最晚的早期”。
- **2P 的极限是顺序**：roster order（谁先被解析）vs match order（P1 先加载）。2P 只能赢前者。

## MC 的六层

| 层 | 手段 | 能稳定破 |
|---|---|---|
| L0 数据 | datapack tag、`/data merge`、`/attribute`、`/kill` | 只读数据的防御 |
| L1 Mod API | 事件、`setHealth`、`discard`、`changeDimension` | L0 |
| L2 字节码 | Mixin / ASM / `@Overwrite` | L1 |
| L3 JVM | 反射、Unsafe、`retransformClasses` | L2 |
| L4 Agent | `-javaagent`、JVMTI 断点 | L3 |
| L5 进程/OS | 杀进程、改存档、注入 DLL | L4 |

**MC 与 MUGEN 最大的不同**：MUGEN 里跑代码要漏洞；MC 里跑代码是装一个 mod。信任边界是 **mod 列表**，不是引擎。

## 映射（压缩）

| MUGEN | MC |
|---|---|
| HitDef 巨大伤害 | `hurt(src, MAX_VALUE)` |
| `Lifeset`/`LifeAdd` | `setHealth` / `heal` |
| 当身 | `LivingAttackEvent` 取消 + 延迟反击 |
| 超即死 / 直死 | `setHealth(0)` / `discard()`（不发 `LivingDeathEvent`） |
| 変数弄り | `getPersistentData()` / `SavedData` / scoreboard |
| 親捏造 | `FakePlayer` / `DamageSource` 伪造 |
| 時間停止 | 服务器线程卡死 / `/tick freeze`（1.20.5+） |
| 隔離 | `changeDimension` / `teleportTo` / remove+add |
| 再起動 | 崩服/重启、`/reload`、维度切换 |
| 多スレッド | 异步线程写世界（MC 非线程安全） |
| HW BP+SEH | JVMTI / Agent / Mixin at exact call site |
| 専用対策 | `ModList.isLoaded` / entity type / NBT key / 源码 |

## 你仓库里的四个发现

1. **`GolemsArsenalSetPillarUntil` 是可写 NBT long**：归零 → 防御消失；设 MAX → 永久免疫；`PillarCd` 归零 → 每次受击 24 根光柱 → 卡服。这是「変数弄り」。
2. **`FakePlayer` + `instabuild`** 能让 `setInvulnerable(true)` 失效（`isInvulnerableTo` 的 `!isCreativePlayer()` 分支）。这是「親捏造」。
3. **防线绝大多数在 L0–L1**：tag 门、`nonlinearFinal(9000)`、`invulnerableTime`、`PersistentData`。破它们不需要指名道姓，一个 datapack 或普通 mod 就够。
4. **模块化生物的组合是数据**：`getUpgrades()` / `getModifiers()` 返回 live 集合；`getUpgrades().clear()` 一行禁用 `isActive`；`getModifiers().put(malicious, 1)` 注入钩子；`updateAttributes` 是 public，清空后调用连 `IMMUNITY` flag 一起消失。
   → 防御必须放在组合**之外**（`SavedData` / 实体类型 / 服务端记录），或在组合**之上**（每次 `updateAttributes` 后 re-assert），不能只写一个 `GolemModifier`。

## 四种常见攻击

| 攻击 | 层 | 能防吗 | 能检测吗 | 核心动作 |
|---|---|---|---|---|
| 写血 | L1/L0 | 不能阻止 | 能 | delta 看门狗 + clamp + 影子账本 |
| 改 jar | L5 | 不能 | 能 | 发布 SHA-256 + 启动打印 + 启动器 manifest |
| 反射 | L3 | 不能 | 部分 | 冗余/派生状态 + canary + 栈检查 |
| 杀主类 | L5/L2 | 不能 | 能 | fail soft + 清晰报错 + guard mod |

- **DEF 指向改**：canonicalize + prefix check；拒绝绝对路径/`..`/符号链接/UNC/盘符/ADS/设备名；扩展名白名单；`select.def` 不信任顺序；内存指针重定向只能在引擎侧修。
- **“不用 .bat / 绝对路径”不是边界**：相对路径的角色包照样能触发解析器 bug、`..` 逃逸、roster 扫描执行。
- **F1/F5** 是调试键，不是角色能力；角色要按它们必须先有代码执行。

## 参考实现：Arcane Vortex（L4/L5 活样本）

CoreMod 注入 `Minecraft.<init>` → `AgentBootstrap` 自 attach（`Unsafe` 绕过 `allowAttachSelf`）→ 失败则 `.bat` + `-javaagent` 重启；内嵌 `arcanevortex-agent.jar`。

- **锁血**：`HealthLockMixin` 在 `setHealth` HEAD cancel、`getHealth` HEAD 返回锁定值、`tick` TAIL 重锁；`HealthModify` 五路写（EntityData / 反射 / agent / NBT / 属性）；`protection.LivingEntityMixin` hook 整个伤害/死亡生命周期。
- **改别人**：`JarPatcher` / `MixinPatcher` 扫 `mods/*.jar` 改 class（`.jar.backup` / `.mixin.backup`），`AgentDllBlocker` 拦 DLL，`SecurityClassProtector` 防反制；mixin 优先级 `2147483647`。
- **检测**：`%TEMP%/arcanevortex/restart_*.bat`、`%TEMP%/ArcaneVortex/.lock`、`*.jar.backup`、`-javaagent:`、`arcanevortex.*` 系统属性、线程 `ArcaneVortex-Monitor`、`HealthLockManager.isEntityLocked`、`getHealth` 与 raw data / NBT / attribute 四路不一致。
- **结论**：一个正常 mod（L1）不可能让它“彻底失效”；只能检测 + 归因 + 外部处置（移除、校验 jar、只读 mods、启动器 manifest、沙箱）。

## 启动器视角：能不能保护自己？

能，但只是“风险降低”，不是“免疫”。**信任锚必须在游戏进程之外**——启动器自己要被签名/自动更新保护，否则换公钥就完事。

- **校验**：签名 manifest + 逐文件哈希；拒绝未知/不匹配。
- **加固**：`-XX:+DisableAttachMechanism`（断自 attach）、禁 `-javaagent`/`-Xbootclasspath/a`/`-agentlib`、最小 `--add-opens`、专用低权用户。
- **隔离**：mods 只读/copy-on-write、默认断网、独立用户/容器/VM、快照回滚。
- **监控**：进程树（`cmd`/`wmic`/子 `javaw`）、文件写入（`.jar.backup`/`.bat`）、jar 哈希、网络。
- **处置**：**quarantine（不删）** + 哈希拉黑 + 报告。

保护不了：能改启动器/JRE/OS 的人、管理员权限、0-day/高度混淆。“病毒处理”是 AV/EDR 的活，Java 启动器只做编排和隔离。

## 加密影子血量（分离 getHealth + 自验证 + 加密字符串）

把 `float health` 换成“认证加密的密文 + 自定义 `getHealth`/`setHealth`”。

- **挡住**：generic 字段扫描（`Traversal` 找不到数值字段）、离线改档（tag 不对）、旁路写 NBT/字段。
- **挡不住**：API 写（调你的 `setHealth(0)`，你会生成合法 tag）、读 jar/内存拿密钥、Mixin `Integer.MAX_VALUE`、agent retransform、`JarPatcher`。
- **关键盲点**：MAC 不区分调用者；它只保护静态数据，不保护内存中的 `float`。
- **要用就**：AES-GCM/HMAC、密钥不进存档、缓存明文+版本号、`getHealth`/`setHealth` 成对虚拟化、失败返回 last-known-good。
- **定位**：tamper-evident，不是 security boundary；反 Arcane Vortex 不如把预算放到启动器。

## 可以借的四样

1. **不变量看门狗**：每 tick 对账 `expected = lastHealth + heal - damage`，不一致先 log，不要立刻回血。
2. **重启韧性**：安全状态放 `SavedData` + UUID，读回 clamp、校验单调；状态缺失是信号。
3. **三点冗余**：`LivingAttackEvent` → `LivingHurtEvent`/`LivingDamageEvent` → `finalizeHurtTarget`/tick。
4. **归因日志**：damage type、causing/direct entity、mod id、tick、位置；这是唯一能事后指出“是谁”的东西。

## 加固清单

**P0**：NBT 时间窗口读时 clamp；数值卫生（NaN/Inf）；套装免疫加第二道检查；归因日志。
**P1**：安全状态迁 `SavedData`；维度/传送校验；`/ga audit` + JSONL；授权包限频。
**P2**：启动自检（`-javaagent`/native lib）；`FakePlayer` 策略；公开文档。

## 反模式

绝对免疫 flag；只用 tag 判定；单点检查；安全靠隐藏；每 tick 写血；把 `invulnerableTime` 当安全信号；防御路径抛异常；安全状态和玩法状态共用 NBT 命名空间。

## 结语

MUGEN 的终局不是“无敌防守”，是 **隔離 + 専用対策の公開**。
MC 的同构是 **服务端权威 + 整合包筛选 + 公开文档 + 归因日志**。
**怕是对的，但答案不是造一堵更高的墙，是把信任边界画清楚**——破了之后，你还在。
