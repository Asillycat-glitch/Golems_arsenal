# 从「論外」到 Minecraft：攻击面分层、七夜式三件套的对应物、以及本仓库的防守边界

> 压缩版：[THREAT-MODEL-MUGEN-SUMMARY.md](./THREAT-MODEL-MUGEN-SUMMARY.md)（约 130 行；本文是完整版）。
>
> 用途：**威胁建模与防守设计**，不是攻击教程。文中不提供可直接使用的绕过实现。
> 所有对 1.20.1 / Forge 47.4.20 的行为描述，均以本机 `forge-1.20.1-47.4.20_mapped_official_1.20.1-sources.jar`
> 里的实际源码为准（附录 A 列了逐条出处）；MUGEN 侧以 atwiki / 千寻 / 公开分析文章为准，推断处会标「推断」。

---

## 0. 一屏结论

1. **MUGEN 的「論外」不是数值更高，是"把战斗搬到引擎规则层之下"。** 神级还在 CNS 里打（当身、即死、时停、无喰らい判定），
   論外内部开始写引擎状态（Alive flag、Target/Helper 归属、变量），論外外部直接进进程/OS（`.exe`/`.bat`、`%n`/`%f` shellcode）。
2. **七夜式三件套（重启 / 多线程 / 硬件断点+SEH）是防守方也搬到 OS 层。** 它的"可靠性绝对有保证"不是因为它更强，
   而是因为它比 MUGEN 的规则层更低：进程可以重建、线程不受帧调度约束、断点精确到"某条指令执行前"。
3. **MC 的对应物不是某个 bug，而是 mod 加载器本身。** 在 MUGEN 里运行任意代码是漏洞利用；在 MC 里运行任意代码是发行方式。
   一个普通 mod 天然拥有 L1，一行 Mixin 就到 L2，`Unsafe`/反射到 L3，`-javaagent` 到 L4，JNI 到 L5。
4. **一条铁律：防御在 L(n)，只有 L(n+1) 及更深的攻击能稳定破它；同层只能"抢先手"，不是保证。**
   —— 这条比任何具体技巧都重要，后文所有表格都是它的展开。
5. **同进程内没有"绝对防御"。** 能真正拿到的是四样东西：**服务端权威、分层冗余、不变量看门狗、归因日志**；
   外加一条设计原则：**优雅降级**（防线被破时不崩、不软锁、可回滚）。
6. **「指名道姓」在 MC 里是伪命题的"隐藏"，不是伪命题的"攻击"。** 你的 mod id、实体类型、NBT key、伤害类型 id、甚至源码
   （MIT + GitHub）都是公开的；对手不需要猜。唯一能做的是让"识别"不等于"生效"。
7. **「强度」是区间，不是标量。** 启动技术定**上限**（客观：最早能在哪个阶段执行），
   应用技术定**下限**（主观：拿它干什么）。「谁赢」比较的是**实际工作点**，不是上限——见 §1.6。

---

## 1. MUGEN 那边的战争是怎么打的

### 1.1 四档，四种战场

| 档位 | 战场 | 代表写法 | 防守方的应对 |
|---|---|---|---|
| 狂 / 凶 | **CNS 内**（状态机、HitDef、变量） | 10 割连段、当身、即死技、回血 | 连段优化、当身、根性 |
| 神 | **CNS 的边缘**（系统判定、演出） | 常时无敌、无喰らい判定、开幕全屏即死、1 コンボ 10 割、血量 0 不死、计时器停 | Hyper Armor、ステート抜け、NoKO |
| 論外（内部） | **引擎 bug** | 超即死 / 直死（写 Alive flag）、混線（偷 Target/Helper）、変数弄り、親捏造 | 専用対策、Helper 占有、复活 |
| 論外（外部） | **进程 / OS** | `.exe`/`.bat`、`%n`/`%f` → 任意写 → GOT 劫持 → shellcode、外部程序 | 基本无解；只能隔离 |

来源：[即死攻撃 - ニコニコMUGENwiki](https://w.atwiki.jp/niconicomugen/pages/1002.html)、
[神キャラ - 同 wiki](https://w.atwiki.jp/niconicomugen/pages/18.html)。

### 1.2 「即死」的两条路线：走管线 vs 写状态

这是整个 MUGEN 凶恶史的主轴，也是和 MC 伤害管线最像的地方。

**A. 走正常处理（防守方还有机会）**

| 写法 | 机制 | MC 里的同构 |
|---|---|---|
| HitDef damage / `TargetLifeAdd` 巨大值 | 走正常伤害结算，只是数值超模 | `hurt(src, Float.MAX_VALUE)` |
| 自己改对方生命（`P2StateNo` + `Lifeset`/`LifeAdd`） | 把对手拉进自己的状态，然后用"改生命"指令 | 拿到对方实体引用后 `setHealth` |
| 落下即死（`Fall.damage` + 共通状态 5100/5110） | 利用共通状态的固定掉血 | 延迟 DoT / 固定伤害效果 |

**B. 写引擎状态（防守方的事件根本看不到）**

| 写法 | 机制（wiki 口径） | MC 里的同构 |
|---|---|---|
| 超即死 | HitPause 中让 `IgnoreHitPause` 附近发生缓冲区溢出，改写 Alive flag；Alive > 1 时 MUGEN 强制归 0 | `setHealth(0)` / `setRemoved` / `discard()`，**不触发 `LivingDeathEvent`** |
| 直死 | 从超即死派生，约 55 万个 state controller（或循环展开）直接写 Alive，不依赖对手状态 | 反射写 `health` / `dead` / `removalReason` |
| 混線バグ | 永続 Target + "空 Target" + Helper，把对手的 Helper 变成自己的 Target | UUID / owner 混淆、`Projectile#setOwner`、乘客链 |
| 変数弄り | 偷 Target 后用 `ParentVarSet` 改对手变量 | 写 `getPersistentData()` / capability / scoreboard |
| 親捏造 | 伪造 Helper 的父关系，让对手的系统认错人 | `FakePlayer`、`DamageSource` 的 causingEntity 伪造 |
| 死の宣告 | `RoundState=3` 之后 `TargetLifeAdd`，回合结束瞬间判负 | 回合结束/死亡事件取消 + `setHealth` |

**关键区别**：A 类还能被"事件 + 优先级 + tag"挡；B 类**根本不发事件**。MC 的 `setHealth` 就是 B 类：
`LivingEntity.setHealth` 只写 `DATA_HEALTH_ID`，不发 `LivingHurtEvent`、不发 `LivingDamageEvent`、不发 `LivingDeathEvent`。
所以"在事件里做防御"这件事，对 B 类天生无效。

### 1.3 防守的阶梯：每一层都被下一层破

wiki 的「対策」列表本身就是一部分层战争史：

```
Hyper Armor               → 被 当身 / ステート奪取 破
Helper の Hyper Armor      → 被 混線 / OTH キラー 破
ステート抜け（GameTime 式） → 被 "p2stateno=-1 で time がリセットされない" 破（2016）
ステート抜け（オニワルド式）→ 基本破られないが記述量が膨大
Lifeset / LifeAdd 常时回血  → 被 変数弄り / 直死 破
NoKO                      → 被 超即死 破（直写 Alive，不走 life=0 判定）
HelperType Player         → 被 親捏造 / 本体ステート固定 破
本体ステート固定           → 被 直死 / 混線 破
時止め無効化               → 被 更强的时停 破
ラウンド終了時ガードステート → 被 八百長 / 強制死の宣告 破
蘇生                      → 被 判定負け / 死の宣告 破
Helper 占有               → 被 親変更 / 全領域親変更 破
```

**读法**：不是"哪一招最强"，而是"这一招在哪一层，下一层是什么"。
这和本仓库 `BYPASS-DESIGN.md` 的七层拦截点表是同一个思维模型——只不过 MUGEN 的层比 MC 多两层（引擎内存、OS）。

### 1.4 七夜式三件套到底在干什么

按你引的那段描述，加上 論外 生态的通行做法，三件套的分工大致是（**推断**，非官方文档）：

| 技术 | 表面动作 | 实际作用 | 类比 |
|---|---|---|---|
| **再起動** | 让 MUGEN 进程重启 / 状态重置 | 夺回"初始条件"：把对手的永久 Target、已写坏的内存、一次性防御全部清掉；外部程序的状态还在 | 崩溃恢复 + 状态机重置 |
| **多スレッド** | 在 MUGEN 进程里开额外线程 | 不受 MUGEN 帧调度约束，持续监视/修复；不依赖 `-2`/`-3` 的触发时机 | 独立看门狗 |
| **硬件断点 + SEH** | 在"某个功能执行时"下 DR0-3 断点，触发 `#DB`，SEH 接管，执行七夜式代码，再恢复 | 把拦截点精确到"某条指令执行前"；可以停在 `%n` 真正写内存之前，然后反杀 | 指令级 hook |

共同点只有一句：**它们都比 MUGEN 的规则层更低。**
CNS 触发器再快，也快不过 CPU 的断点异常；`-2`/`-3` 再常时，也管不到另一个线程。
"可靠性绝对有保证"的来源不是代码写得好，而是**它站在了引擎下面**。

这也解释了为什么它"麻烦，麻烦，以及太麻烦"：你要自己管理进程、线程、异常、恢复；
而改源码（甚至直接写内存）在同一个进程里更省事——但省事的那条路，**可靠性就回到了引擎层**。

### 1.5 终局：隔離 + 専用対策

- 直死出现后，神ランク把这类技术**隔離**出去：不是"更强"，是"不跟你在同一套规则里玩"。
  （"隔離"一词后来也指用这些技术做**非杀伤**的用途，比如全領域親変更把 20–30MB 的记述压成模板。）
- **専用対策**：看对手的 `name`，只在对上特定角色时启用特定破法。神ランク默许，狂以下御法度。
- 换句话说：**技术上无解之后，社区用"分层 + 公开"来管理**。
  这条对 MC 的启示比任何具体技巧都大——见 §9。

### 1.6 启动技术：用"最早可行动时刻"替代"强度"

「强度」这个词在 論外 圈里已经过剩：它比的是"谁赢"，而"谁赢"受对局、应用选择、専用対策影响，
**不是技术的属性**。更客观的度量是**启动技术**——**这套技术最早能在哪个 RoundState 开始执行**。

| 档 | 速度上限 | 最早可行动点 | 代表技术 | 为什么只能到这里 |
|---|---|---|---|---|
| 慢 | R1 | R0 之后（开打后） | 512 超越启动 | 按这个口径，要等战斗开始（命中 / HitPause）才有输入 |
| 快 | R0 | R0 中（黑屏期） | DTC / ATC 启动 | 选完人、进入对战界面、还没开打就能接管 |
| 极快 | 选人 | R0 前（角色加载 / 选人） | Statedef 溢出启动 | 在状态表加载时就把表写坏，代码比 round 更早存在 |

> R0 = 选完人进入对战界面之后、Round 1 开始之前的黑屏阶段。
> DTC / ATC 的具体全称我没有可靠出处，这里只按"R0 期可启动"这一档来用。

**为什么这个度量更客观**：它是技术的属性，不是作者的意愿。

- 一个 S 溢出启动的角色，可以只做"每 P 不同头像"，完全不杀人；它的启动上限仍然是**选人期**。
- 一个 512 超越启动 + 直死的角色再凶，启动上限仍然只有 **R1**。
- **前者技术含量更高，后者只是更想赢。**

形式化一点：

```
强度 ∈ [应用下限, 启动上限]
        ↑ 作者选择      ↑ 技术决定
```

- **启动技术定上限**（客观）：你能在多早的阶段安装自己的逻辑。
- **应用技术定下限**（主观）：你装上之后拿它干什么（杀人、防守、换头像）。
- 「谁赢」比较的是**实际工作点**，不是上限。S 溢出角色如果选择在 R0 装一个针对 512 的反制，
  它就能赢；但它选择换头像，所以输了。**上限是潜力，工作点是选择。**

**"启动档位"更准确的定义是：这套技术的前置条件最早在哪个阶段成立。**
512 的前置条件是"有一次命中 / HitPause"，所以最早 R1；
DTC / ATC 的前置条件是"round 对象已经存在"，所以 R0；
S 溢出的前置条件是"状态表正在加载"，所以选人期。
**不是它想多早，是它最早能多早。**

一个必要的补充：**"技术含量"不等于"启动档位"。**
启动档位是客观的，但"技术含量"是"启动档位 + 应用复杂度"的混合。
一个 512 档的角色如果把应用写得很精妙（精确的状态夺取、专用对策、资源管理），
它的总技术含量可能高于一个只会换头像的 S 溢出角色。
上面那个例子的说服力，来自"换头像"这个应用**故意做得极简**，所以总技术含量被启动档位主导。
换句话说：**启动档位是客观的，但"技术含量"仍然是一个带主观成分的合成量；
真正完全客观的只有"最早可行动阶段"这一个投影。**

这也是为什么"强度榜"会失真：它把"上限"和"工作点"混成了一个标量。
两个角色可能上限相同、工作点不同；也可能上限更高但工作点更低，于是输给上限更低的对手。
**这不是"谁更强"的问题，是"你在比哪一个量"的问题。**

### 1.7 启动速度 vs 泛用性：越早越脆

把 §1.6 的三档再往下拆，会看到一条硬约束：**越早的启动点，越依赖具体实现，越不泛用。**

| 阶段 | 引擎在做什么 | 角色能插手的点 | 代表 | 泛用性 | 依赖 |
|---|---|---|---|---|---|
| 0 进程启动 | OS loader / CRT | 无（还没读你的文件） | `.bat` / 外部程序 | — | 被排除 |
| 1 引擎初始化 | `mugen.cfg`、`system.def`、字体/声音 | 无（系统文件，不是角色） | 改系统文件 | — | pack 级 |
| 2 人物列表扫描 | 读 `select.def`，按顺序解析每个角色的 DEF/ZIP | **DEF 解析 / ZIP 读取** | DEF 溢出、ZIP 溢出 | 低 | OS / 解析器 / 文件顺序 |
| 3 选人画面 | 加载立绘 / SFF / 音效 | **SFF/PCX/SND 解析** | 资源解析溢出 | 低-中 | 资源格式 / 加载时机 |
| 4 选人确认 | 加载 P1 的 CNS/CMD/SFF/AIR，然后 P2 | **状态表加载** | S 溢出 | 中-高 | 状态表格式 |
| 5 RoundState 0 | 入场/黑屏，`-2`/`-3` 跑 | 状态机 | DTC / ATC | 高 | 引擎状态机 |
| 6 RoundState 1 | 开打 | 状态机 / 命中 | 512 超越、直死 | 高 | 引擎状态机 |

**角色级的最早可执行点是阶段 2（人物列表扫描）。**
阶段 0/1 不是角色能碰的（除非改系统文件或外部程序，这两条被"不用 .bat / 不用绝对路径"排除了）。
阶段 2 之后的所有技术都更晚。

所以"比 S 溢出更快"的答案不是一个新技巧，而是**整个阶段 2 的解析器类**：
DEF、ZIP，以及任何在阶段 2 被解析的格式（SFF/PCX/SND 如果在选人画面就被读，算阶段 3）。
它们都比 S 溢出快，但都比 S 溢出脆——这正是"DEF 只能 XP、ZIP 需要文件依赖"的原因。

**"更快 + 更泛用"的角色级技术不存在**：不是没找到，是结构上没有。
阶段 2 的解析器是引擎最老、最依赖平台的部分；S 溢出之所以是"泛用最快"，
正因为它站在"最晚的早期"——状态表加载是所有 MUGEN 构建都必须走的路。
**要更快，就必须接受更脆；要更泛用，就必须接受更晚。**

### 1.8 两个顺序：roster order vs match order

2P 的"击破极限"取决于两个不同的顺序：

- **roster order（人物列表顺序）**：引擎按 `select.def` / 文件夹顺序解析 DEF。
  谁在前面，谁的 DEF 先被解析。第一个被解析的角色，可以在别人还没被解析时执行代码。
- **match order（对战加载顺序）**：选人确认后，P1 的 CNS/CMD/SFF/AIR 先加载，然后 P2。
  所以 1P 的 S 溢出可以在 2P 的状态表还没加载时动手。

2P 想赢，只能赢 roster order，赢不了 match order：

- 如果 2P 的角色在 roster 里排在 1P 前面，它可以在 1P 的 DEF 被解析前执行（阶段 2）。
- 如果 1P 在前面，2P 的 DEF 解析已经晚了；match order 又天然偏向 1P，2P 没有窗口。

**实际顺序取决于文件系统**（NTFS 索引顺序），不一定是字母序；所以"第一个"不是完全可控的。
这也是为什么"打开主程序就赢"在别人的机器上不一定复现——它依赖 roster 顺序，
而 roster 顺序不是角色能完全决定的。

这就是"2P 击破极限遥远"的结构原因：**不是 2P 的技术不够快，是加载顺序不站在 2P 这边。**
2P 要打破它，只能攻击"共享资源"（系统文件、字体、声音）或者"更早的解析器"——
而这两条都越过了"角色包"的边界，进入 pack 级或外部程序级。

---

## 2. 对照 Minecraft：六层攻击面

### 2.1 分层表

| 层 | 名称 | 代表手段 | 门槛 | 能稳定破 |
|---|---|---|---|---|
| **L0** | 数据 | datapack 的 damage_type tag、配方、战利品、`/data merge`、`/attribute`、`/kill` | 无代码；op 或整合包作者 | 只读数据的防御（tag 判定、config 判定） |
| **L1** | Mod API | 事件、注册表、`setHealth`、`discard`、`changeDimension`、`setPos`、`AttributeInstance`、`getPersistentData` | 一个 mod | L0 及以下；同层靠优先级抢先手 |
| **L2** | 字节码 | Mixin / ASM / Coremod、`@Inject`、`@Redirect`、`@Overwrite` | mod + 知道你的类名（源码公开） | L1 |
| **L3** | JVM 内部 | 反射、`Unsafe`、`VarHandle`、`MethodHandles`、`Instrumentation.retransformClasses` | mod + 反射权限/Unsafe | L2 |
| **L4** | Agent / JVMTI | `-javaagent`、attach API、native agent、`SetBreakpoint`/`MethodEntry` | 启动参数或 attach 权限 | L3 |
| **L5** | 进程 / OS | 杀进程、改存档、注入 DLL、RCE、断点+SEH | 本机权限 | L4 |

**铁律**：防御在 L(n)，只有 L(n+1) 及更深能**稳定**破它。
同层攻击不是不能破，而是**靠顺序/优先级抢先手**——你能抢，对手也能抢，所以同层永远不是保证。

**L0 的特殊性**：`minecraft:tags/damage_type/*` 是**全局命名空间**，任何 datapack 都能 `replace: false` 追加。
所以 L0 的 tag 防御会被**同层**破——这是唯一一个"同层就能稳定破"的例外，也是本仓库 `true_edge` 测试台能成立的原因。

### 2.2 MC 和 MUGEN 最大的不同

| | MUGEN | Minecraft |
|---|---|---|
| 运行任意代码 | 需要漏洞（`%n`/`%f`/溢出） | **是发行方式**：装一个 mod 即可 |
| "普通角色/mod"的能力上限 | CNS 状态机 | **完整 JVM 权限** |
| 信任边界 | 引擎进程 | **mod 列表本身** |
| 攻击者的默认起点 | 狂 / 神（规则内） | **L1（Mod API）** |
| 到 L2 的成本 | 需要找 bug | 一行 `@Mixin` |
| 到 L5 的成本 | 写外部程序 | 打包一个 `.dll` + `System.load` |

所以：**在 MC 里，"攻击模组"不是罕见的漏洞利用，而是一个带敌意的普通 mod。**
任何"假设对手只有 CNS 级能力"的防守设计，在 MC 里都是错的。

### 2.3 早晚 × 深浅：两个轴，一个格

§2.1 的 L0–L5 是**深度轴**：你能碰到多底层。
§1.6 的启动技术是**时间轴**：你能多早安装。
两者相关，但不重合——一个技术的上限是二元组 **(最早阶段, 最深层次)**，不是单一标量。

| MC 阶段 | 最早可行动点 | 代表 | MUGEN 对应 |
|---|---|---|---|
| 进程启动前 | `-javaagent` premain / native agent | agent / JVMTI | 选人前（S 溢出） |
| 类加载时 | Mixin / coremod / `ClassLoader` | Mixin / ASM | 选人期 |
| mod 构造 | `@Mod` 构造、`FMLConstructModEvent` | mod | R0 前 |
| 注册 / 设置 | `FMLCommonSetupEvent`、registry | mod | R0 |
| 服务器启动 | `ServerAboutToStartEvent` | event | R0 / R1 边界 |
| 世界加载 | `ServerStartedEvent`、`LevelEvent.Load` | event | R1 |
| 首个 tick | `TickEvent`、`LivingHurtEvent` | event | R1（512 档） |

**为什么不能只用一条斜线概括**：

- agent 可以**中途 attach**：晚 + 深（不占"早"，但占"深"）。
- datapack 可以 `/reload`：晚 + 浅。
- mod 可以在构造时安装、但等到 first tick 才行动：早 + 浅。
- 所以 (早, 深) 是一个**偏序**，不是全序：两个技术可能不可比
  （一个更早但更浅，一个更晚但更深）。

**对防守方的意义**：不要问"谁更强"，要问两个有客观答案的问题——

1. **它在哪个阶段安装？**（时间轴）
2. **它能碰到哪一层？**（深度轴）

你的防线如果只占"晚 + 浅"（例如只在 `LivingHurtEvent` 里判），
那么任何"早 + 深"的东西（Mixin / agent）都能在你之前改写你。
这不是强度问题，是**安装顺序**问题——也是为什么 §6.3 要求把同一个不变量在多个时间点各放一次。

---

## 3. 逐条映射：MUGEN 技术 → MC 等价物

| MUGEN 写法 | 它绕过了什么 | MC 等价物 | 层 |
|---|---|---|---|
| HitDef 巨大伤害 / `TargetLifeAdd` | 正常伤害数值 | `hurt(src, Float.MAX_VALUE)` | L1 |
| `Lifeset` / `LifeAdd` 常时 | 掉血 | tick 里 `heal()` / `setHealth(max)` | L1 |
| `Fall.damage` 累积 | 即时结算 | 延迟 DoT、`addEffect` 伤害 | L1 |
| 当身（`ReversalDef`） | 对手的 HitDef | `LivingAttackEvent` 取消 + 记仇 + 延迟反击 | L1 |
| 永続 Target / 混線 | Target 归属 | UUID/owner 混淆、`Projectile#setOwner`、乘客链 | L1 |
| `TargetState` 状態奪取 | 对手状态机 | `changeDimension`、`teleportTo`、`startRiding`、改 AI target | L1 |
| 超即死 / 直死（Alive flag） | 死亡判定 | `setHealth(0)`（不发 `LivingDeathEvent`）、`discard()`、`setRemoved` | L1/L3 |
| 変数弄り | 对手变量 | `getPersistentData()`、capability、`SavedData`、scoreboard | L0/L1 |
| 親捏造 | 亲子关系 | `FakePlayer`、`DamageSource` causingEntity 伪造 | L1 |
| 時間停止 / `SuperPause` | 帧推进 | 服务器线程卡死、`/tick freeze`（1.20.5+）、`Level#tick` 取消 | L1/L2 |
| 死の宣告 | 回合结束判定 | `LivingDeathEvent` 取消 + `setHealth(1)`；BossBar 移除 | L1 |
| 八百長 | 判负 | 同上 + 复活 | L1 |
| 論外外部 `.exe`/`.bat` | 整个进程 | 同 JVM 的 mod / Java Agent / JNI | L4/L5 |
| `%n` / `%f` | 内存写 + GOT 劫持 | 无直接等价；最近的是反序列化、`ScriptEngine`、`Runtime.exec`、JNI | L3/L4 |
| 再起動 | 状态丢失 | 崩服/重启、`/reload`、维度切换、区块卸载 | L1/L5 |
| 多スレッド | 帧调度 | 异步线程写世界（**MC 非线程安全**，这是差异点） | L1 |
| 硬件断点 + SEH | 指令级 | JVMTI `SetBreakpoint`、Java Agent、Mixin `@Inject` at exact call site | L2/L4 |
| 隔離 | 把对手移出战场 | `changeDimension`、`teleportTo`、`remove` + `addFreshEntity` | L1 |
| 専用対策 | 看 `name` | `ModList.isLoaded` / entity type / NBT key / 源码 | L0/L1 |

---

## 4. 本仓库现有防线的层级审计

### 4.1 逐条

| 位置 | 现在做的事 | 层 | 谁能破 | 具体破法（防守视角） |
|---|---|---|---|---|
| `GolemsArsenalSetHandler:149-158` | 光柱期间 `LivingAttackEvent` 取消，除非 `BYPASSES_INVULNERABILITY` | L1 | L0 / L1 / L2 | 自定义伤害类型进 tag；`LOWEST` 监听器 `setCanceled(false)`；Mixin `HEAD` cancel |
| `GolemsArsenalSetHandler:65-70` | `isActive` 由升级等级推导 | L1 | L1 | 移除升级、改 `GolemUpgradeLevels` 的读取 |
| `GolemsArsenalSetHandler:58-63` | 冷却/窗口存在 `PersistentData` | L0/L1 | L0/L1 | `/data merge` 或直接 `putLong` |
| `DamageRestitutionListener:121,182` | `nonlinearFinal(9000)` 保底 | L1 | L1 | 更高 priority 的 `nonlinearFinal`；`LivingDamageEvent` 取消；直接写血 |
| `GolemGrazeModifier:90-91` | 写 `invulnerableTime` | L1 | L1 | 写 0；`BYPASSES_COOLDOWN` |
| `GolemComboHandler:211` | 写 `target.invulnerableTime = 0` | L1 | — | 这是"攻击性"写法；对别人用就是别人眼里的攻击模组 |
| `ServerboundAuthorizePacket:37-45` | 服务端二次校验（手持、距离、冷却） | L1 | L0/L1 | 伪造包（当前校验算合格，缺频率限制） |
| `Config.ENERGY_HAMMER_FLAT_IMMUNITY` | 固定值减法 | L0 | L0 | 改 config |
| `GolemUpgradeHandlerMixin:40` | 注入本家 `appendUpgrade` | L2 | L2/L3 | 更高优先级 mixin；但这是"互斥"，不是安全边界 |

**结论**：你的防线绝大多数在 L0–L1。能破它们的东西**不需要指名道姓**，只需要一个 datapack 或一个普通 mod。
这不是你的实现问题——L1 的防线本来就只能挡 L1 以下。

### 4.2 例 1：`GolemsArsenalSetPillarUntil` —— 「変数弄り」的 MC 版

```java
// GolemsArsenalSetHandler
private static final String PILLAR_UNTIL_KEY = "GolemsArsenalSetPillarUntil";
...
if (golem.level().getGameTime() < golem.getPersistentData().getLong(PILLAR_UNTIL_KEY)
        && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
    event.setCanceled(true);
}
```

这个 long 是**可写、可读、命名可猜**的。三种玩法：

1. **归零** → 无敌窗口消失，防守被拆。
2. **设成 `Long.MAX_VALUE`** → 对非 bypass 伤害永久免疫。防守变成破坏平衡的工具（比"拆防御"更糟）。
3. **把 `PillarCd` 归零** → 每次 `LivingDamageEvent` 都触发 24 根光柱（4 根 × 3 轮）→ 卡服/崩服。
   这是**用你的防御代码打你自己**，而且不需要碰你的 Java。

MUGEN 的「変数弄り」是同一个思路：不碰对手的代码，只碰对手**信任的可写状态**。

**修法**：读的时候 clamp，而不是写的时候相信。

```java
long until = tag.getLong(PILLAR_UNTIL_KEY);
long now = golem.level().getGameTime();
if (until - now > MAX_WINDOW_TICKS) until = now + MAX_WINDOW_TICKS; // 上限
if (until < now - MAX_WINDOW_TICKS) until = 0L;                    // 反常过去值
```

并且把"安全状态"和"玩法状态"分开：冷却/窗口这类东西，最好放**服务端 `SavedData` + UUID**，
或者至少加一个 `lastKnownGood` 快照，读回时校验单调性。

### 4.3 例 2：`FakePlayer` + `instabuild` —— 「親捏造」的 MC 版

1.20.1 的 `Entity.isInvulnerableTo`（附录 A.3）有三个分支：

```java
return this.isRemoved()
    || this.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !source.isCreativePlayer()
    || source.is(DamageTypeTags.IS_FIRE) && this.fireImmune()
    || source.is(DamageTypeTags.IS_FALL) && this.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE);
```

注意 `!source.isCreativePlayer()`：`DamageSource.isCreativePlayer()` 只检查
`causingEntity instanceof Player && player.getAbilities().instabuild`。
一个 `instabuild = true` 的 `FakePlayer` 作为 causingEntity，就能让 `setInvulnerable(true)` 失效——
**不需要任何 tag，也不需要任何 Mixin**。

这是「親捏造」的 MC 版：不改你的防御，改"谁在打"。
修法：在 `LivingAttackEvent` 里校验 `source.getEntity()` 是真实 `ServerPlayer` / 已知实体、同维度、距离合理；
对 `FakePlayer` 单独定策略（放行/拒绝都要显式）。

### 4.4 一个容易踩的坑：字段 vs 方法

`Entity.isInvulnerableTo` 读的是**裸字段** `this.invulnerable`，不是 `isInvulnerable()` 方法。
`AbstractGolemEntity` 覆写的是 `isInvulnerable()`（读 `GolemFlags.IMMUNITY`），
所以 vanilla 的 `isInvulnerableTo` 看不到它；真正拦住的是 `GuardedEntity.isInvulnerableToExtra`。
**任何"我覆写了 `isInvulnerable()` 所以我就无敌了"的假设都是错的**——要看调用方读的是字段还是方法。

---

## 5. 「指名道姓」的攻击模组：画像

### 5.1 它怎么认出你

| 信号 | 成本 | 备注 |
|---|---|---|
| `ModList.get().isLoaded("golems_arsenal")` | 极低 | mod id 是公开的 |
| 实体类型 / `instanceof AbstractGolemEntity` | 极低 | 本家类型也是公开的 |
| 你的伤害类型 id（`golems_arsenal:*`） | 极低 | `DamageSource#getMsgId()` 就能读 |
| 你的 NBT key（`GolemsArsenalSet*`） | 低 | `getPersistentData()` 可读 |
| 你的类名 / Mixin refmap / `mods.toml` | 低 | jar 里都有 |
| 你的源码 | 零 | MIT + GitHub，攻击者可以逐行读你的防御 |

**这就是"指名道姓"的真相**：在开源 mod 生态里，隐藏是不可能的。你能选的只有"识别之后能不能生效"。

### 5.2 它会怎么做（按成本排序）

| 档 | 手段 | 例子 | 层 |
|---|---|---|---|
| T0 | 纯数据 | datapack 把自定义伤害类型加进 `bypasses_invulnerability`；`/data merge` 改 NBT；`/attribute` 改属性；`/kill` | L0 |
| T1 | 普通 mod API | `setHealth(0)`、`discard()`、`changeDimension(void)`、`setPos(NaN)`、`setInvulnerable(false)`、`getAttribute(MAX_HEALTH).setBaseValue(0)`、`LOWEST` 取消你的事件、`setCanceled(false)` 反取消 | L1 |
| T2 | Mixin / ASM | `@Inject(HEAD, cancellable)` 取消你的 `onGolemAttacked`；`@Redirect` 你的 `isInvulnerableTo`；`@Overwrite` 你的 `hurt`；改 `ORDER_PRIORITY` | L2 |
| T3 | 反射 / Unsafe | 写 `invulnerableTime`、`health`、`dead`、`removalReason`；删你的 `AttributeModifier`；调私有 `setGuardedDataImpl` | L3 |
| T4 | Agent / JVMTI | `retransformClasses` 你的类；hook `LivingEntity.setHealth`；断点在你的 guard 上 | L4 |
| T5 | native / OS | 杀 JVM、改存档、注入 DLL、RCE | L5 |

**大多数"攻击模组"停在 T1–T2**，因为 T1 就够了：你的防线在 L1，而 L1 的攻击者能用 `setHealth` 直接绕过所有事件。
T3 以上是"专门做你"的级别，通常只在私人恩怨/小圈子里出现。

### 5.3 它通常不会做什么

- 如果它是**纯客户端** mod：碰不到服务端实体状态，只能伪造包。服务端校验（如 `ServerboundAuthorizePacket`）能挡。
- 如果它**只装在客户端而服务器没有**：`setHealth` 只影响客户端本地，服务端权威仍然有效。
- 如果它**装在服务端**：你输了。同进程内没有任何东西能阻止它。
  这不是"你的防御不够强"，是"信任边界被越过了"。

### 5.4 怎么区分"针对你"和"普通冲突"

| 特征 | 普通 mod 冲突 | 针对性攻击 |
|---|---|---|
| 日志 | 常见异常、`Mixin apply failed`、`ClassCastException` | **干净**，没有异常 |
| 触发面 | 所有同类实体都受影响 | 只有你的实体/套装/特定状态受影响 |
| 数值 | 伤害偏高/偏低、事件顺序乱 | 事件计数正常但血量不符（写血） |
| 来源 | 已知 mod 的伤害类型 | 陌生命名空间的 damage type，或 `null` entity |
| 可复现性 | 换目标也复现 | 换目标就正常 |

**"事件计数正常、血量不符"是最强的信号**：说明有人绕过了事件直接写状态。
这也是 §6.1 看门狗要抓的东西。

### 5.5 所以"防指名道姓"要防什么

不是防"它知道你是谁"（防不住），而是防：

1. **它不需要写代码就能破你**（T0）→ 把 tag/ NBT / config 这些 L0 面收窄、加校验。
2. **它一个单点就能破你**（T1/T2）→ 同一不变量在 2–3 个层各查一次。
3. **它破了之后你没证据**（全档）→ 归因日志。
4. **它破了之后存档烂了**（全档）→ 优雅降级、可回滚。

---

## 6. 可以真正借的四样东西

### 6.1 不变量看门狗（「多スレッド」的合法版）

MUGEN 的额外线程能直接写内存；MC 不行（非线程安全）。所以合法版是：

- **在服务端线程上**，每 tick（或每 N tick）对"套装激活"的傀儡做一次账目核对：
  ```
  expected = lastHealth - damageThisTick + healThisTick
  actual   = golem.getHealth()
  if (!finite(actual) || abs(actual - expected) > EPS) → log("UNEXPLAINED_HEALTH", ...)
  ```
- `damageThisTick` / `healThisTick` 由 `LivingDamageEvent` / `LivingHealEvent` 累加。
- 不一致**先记日志，不要立刻回血**——立刻回血会和别的 mod 打架，也会掩盖证据。
- 想更接近"多线程"：把日志写到文件/JSONL，让**进程外**的脚本去 tail 和告警。
  这才是真正的"引擎外监视"，而且不会碰 MC 的线程模型。

### 6.2 重启韧性（「再起動」的合法版）

- 安全状态（冷却、窗口、lastKnownGood）持久化到 **`SavedData`**，key 用 UUID，不要只存在实体 NBT 里。
- 读回时 **clamp**（§4.2），并校验 `lastSeenGameTime` 单调。
- 实体被 `changeDimension` 重建、被 `remove` + `addFreshEntity` 重建时，按 UUID 重新挂状态。
- "状态缺失"本身是一个信号：正常下线/重启也会缺失，但**频繁缺失**值得记一笔。

### 6.3 精确拦截（「HW BP + SEH」的合法版）

在三个层次各放一次检查，目的不是"更强"，是"**单点 Mixin 不能一次全关**"：

| 检查点 | 时机 | 能挡什么 |
|---|---|---|
| `LivingAttackEvent` | `hurt` 的第一行（附录 A.1） | 所有走 `hurt` 的攻击，包括 `/kill` |
| `LivingHurtEvent` / `LivingDamageEvent` | 护甲前 / 护甲后 | 事件取消、数值篡改 |
| `finalizeHurtTarget` / tick 对账 | 写血之后 | `setHealth`、`discard`、Mixin 写字段 |

但要说清楚：一个 `@Overwrite` 在 `setHealth` 上，就能同时废掉这三个。
**同进程内没有终局**；这三层只是把"一个 mixin 搞定"变成"要写三个 mixin"。

### 6.4 归因（唯一能对付指名道姓的东西）

每次"异常命中"记一条 JSONL：

```json
{"tick":12345,"target":"...","hpBefore":40.0,"hpAfter":12.0,
 "damageType":"attackmod:true_damage","msgId":"true_damage",
 "causingEntity":"attackmod:xxx","directEntity":"minecraft:player",
 "mods":["golems_arsenal","modulargolems","attackmod"],"dim":"minecraft:overworld",
 "pos":[x,y,z],"bypassInvul":true,"bypassArmor":true}
```

这条日志不能阻止攻击，但它是**唯一能让你在事后指出"是谁干的"**的东西。
MUGEN 的 専用対策 之所以要公开，就是因为"指名"必须可被社区看见；MC 里没有社区裁判，只能靠日志。

### 6.5 如果你自己要写"攻击性"内容

参考 `docs/BYPASS-DESIGN.md` §6 的立场，这里只列四条护栏（不是新提案）：

1. **只对 hostile**：`!(target instanceof Player)`、排除友军/同 owner，写进每个 `onHurtTarget` 的第一行。
2. **不用 `bypasses_invulnerability`**：它同时跳过创造/旁观的无敌门，有击杀创造模式实体的风险。
3. **多段走自己的冷却**：伤害类型进 `bypasses_cooldown`，段间节流由自己的计时器负责，
   **不要写 `target.invulnerableTime`**（`GolemComboHandler:211` 是现存例外，已注明取舍）。
4. **不做"绝对免疫"**：免疫窗口要有上限、可被绕过、可被观测；否则就是下一个被指名道姓的目标。

这四条的本质不是"仁慈"，是**别把自家内容做成别人眼里的"必须针对性反制"的东西**——
MUGEN 的 論外 就是被这么一步步逼出来的。

---

## 7. 加固清单

### P0（现在就能做，成本低）

1. **所有 NBT 时间窗口读时 clamp**：`PillarUntil`、`PillarCd`、`GenmuCd`、`GenmuVolley2`、`Refill`。
   上限/下限/单调性，三条都查。
2. **数值卫生**：傀儡 tick 里检查 `health`、`position`、`deltaMovement`、关键属性是否 `finite`；
   非有限值 → 恢复到 `lastKnownGood` + 记日志。这是 MC 版的"溢出防御"。
3. **套装免疫窗口加第二道检查**：`LivingHurtEvent` 或 `LivingDamageEvent` 里再判一次；
   不要只依赖 `LivingAttackEvent` 一个点。
4. **归因日志**：每次对"套装激活"的傀儡使用 `BYPASSES_INVULNERABILITY` 伤害时，
   记录 `DamageSource` 的 type id、causing/direct entity、mod id、tick、位置。

### P1（需要一点设计）

5. **安全状态迁移**：冷却/窗口从 `PersistentData` 迁到 `SavedData`（UUID key），或至少加 `lastKnownGood` 快照。
6. **维度/传送校验**：如果套装傀儡应该是"场地锚定"的，覆写 `canChangeDimensions`，
   监听 `EntityTravelToDimensionEvent`，并在 tick 里检查是否被 `teleportTo` 挪走。
7. **`/ga audit` 命令 + JSONL 落盘**：把 §6.4 的日志做成可查询的。
8. **`ServerboundAuthorizePacket` 加频率限制**：每玩家每 N tick 最多一次；其余直接丢。

### P2（可选，收益递减）

9. **启动自检**：`ManagementFactory.getRuntimeMXBean().getInputArguments()` 里找 `-javaagent`/`-agentlib`；
   扫描 mod jar 里是否有 `.dll`/`.so`/`.exe`/`.bat`；把结果写日志。
   （这只是"提高成本 + 留证据"，不是防御。）
10. **`FakePlayer` 策略**：在 `LivingAttackEvent` 里显式判断 `source.getEntity()` 是否 `FakePlayer`，
    按你的玩法决定放行/拒绝，并记录。
11. **公开文档**：把你的防线层级、每层的counter、已知边界写进 docs（就像本文）。
    这不是"教攻击者"，是让"指名道姓"变成"公开的指名道姓"——MUGEN 的 専用対策 也是这么处理的。

---

## 8. 反模式（不要做）

| 反模式 | 为什么 |
|---|---|
| **"绝对免疫" flag** | 招指名道姓；一旦被破就是软锁；还会被拿来做 grief（§4.2 例 1） |
| **只用 tag 判定** | tag 是 L0 全局命名空间，任何 datapack 能改 |
| **单点检查** | 一个 Mixin 全关 |
| **安全靠隐藏** | 源码 MIT、mod id 公开、NBT key 可读 |
| **每 tick 写血** | 和别的 mod 打架、橡皮筋、掩盖证据 |
| **把 `invulnerableTime` 当安全信号** | 它是 public 字段，谁都能写 |
| **在防御路径里抛异常** | 崩服比被破更糟；永远 clamp + continue |
| **把安全状态和玩法状态放同一个 NBT 命名空间** | 改玩法状态的手顺带就把安全状态改了 |

---

## 9. 结语：MUGEN 的终局对 MC 意味着什么

MUGEN 的凶恶史走到最后，不是"出现了一个无敌的防守"，而是：

1. **隔離**：把"不跟你在同一层玩"的技术单独分档，不参与常规评价。
2. **専用対策の公開**：指名攻击被允许，但必须公开；隐藏的指名是御法度。
3. **社区分层**：神ランク比的是"能杀多少"，不是"能不能被杀"。

MC 没有社区裁判，但有三个同构的东西：

- **服务端权威** = 隔離：客户端再强，碰不到服务端状态。
- **整合包筛选** = 分层：装不装那个 mod，是整合包作者的决定。
- **公开文档 + 归因日志** = 専用対策の公開：你可以被指名，但指名会被看见。

所以，**怕是对的，但"造一堵更高的墙"不是答案**。
答案是：**把信任边界画清楚**（哪些层你能控制、哪些不能），
**把不变量做成多点冗余**（一个 mixin 关不掉），
**把异常做成可归因的日志**（事后能指出是谁），
**把失败做成可降级的**（破了也不软锁）。

启动技术这一轴还补了一句：**防线要尽量往前装**（在 mod 构造 / 类加载期就把不变量注册好），
但**装得再早也早不过 JVM 参数**——所以"早"只能提高成本，不能替代"深"（进程边界）。

这四件事做完，你就已经比 99% 的 MC mod 更能扛"指名道姓"了——
不是因为你不可破，而是因为**破了之后，你还在**。

---

## 10. 四种常见攻击的防守：写血 / 改 jar / 反射 / 杀主类

> 这一节回答的是"整合包里已经有这些手段，怎么防"。
> 先问一句：**攻击在客户端还是服务端？**
> 客户端的一切都能被改；服务端的一切都防不住。
> 只有"客户端攻击 + 服务端权威"这个组合里，防御才有意义。
> 如果攻击 mod 装在服务端，你能做的只有检测、归因、以及不跟它玩。

### 10.1 先分类

| 攻击 | 实际动作 | 层 | 进程内能防吗 | 能检测吗 |
|---|---|---|---|---|
| 写血 | `setHealth` / `setBaseValue` / `/data merge` | L1（或 L0） | 不能阻止调用；能 clamp + 对账 | 能（delta watchdog） |
| 改 jar | 改 class / 换 jar / 删依赖 | L5 | 不能 | 能（外部哈希；自检只是减速带） |
| 反射 | `setAccessible` / `Unsafe` / `VarHandle` | L3 | 不能 | 部分（canary / 冗余 / 栈检查） |
| 杀主类 | 删/换 `@Mod` 主类，或让它加载失败 | L5/L2 | 不能 | 能（启动日志 / guard） |

一句话：**四种里只有"写血"是能在进程内和它周旋的**；另外三种的终局都是信任边界。
这不是悲观，是分层模型的直接推论：写血在 L1，你在 L1 还有话语权；反射在 L3，改 jar 在 L5，你在那一层没有立足点。

### 10.2 写血

**它做了什么**：`LivingEntity#setHealth(float)` 是 public、无事件、只写 `DATA_HEALTH_ID`。
配套的还有 `setAbsorptionAmount`、`AttributeInstance#setBaseValue`（把 `MAX_HEALTH` 设成 0/NaN）、
`getPersistentData().put*`、以及 op 的 `/data merge entity`。
共同点：**不经过 `LivingHurtEvent` / `LivingDamageEvent` / `LivingDeathEvent` 中的任何一个。**

**防不住的部分**：你没法阻止一个同进程的 mod 调用 public 方法。`setHealth` 不是 final，你也不能逐实例覆写它。
所以"阻止写血"这个目标本身不成立；能成立的是"**发现写血 + 限制它的后果 + 留下证据**"。

**能做的**：

1. **Delta 看门狗**（P0，最值钱）
   每 tick（或每 N tick）对"受保护"的实体对账：
   ```
   expected = lastHealth + healThisTick - damageThisTick
   actual   = entity.getHealth()
   if (!finite(actual) || abs(actual - expected) > EPS) → log("UNEXPLAINED_HEALTH", ...)
   ```
   `damageThisTick` / `healThisTick` 由 `LivingDamageEvent` / `LivingHealEvent` 累加。
   这能抓到所有"绕过事件"的写入，包括 `setHealth`、反射、Mixin 写字段。
   **先记日志，不要立刻回血**——立刻回血会和别的 mod 打架，也会掩盖证据。

2. **读时 clamp**（P0）
   `health` 非有限、`< 0`、`> maxHealth` → 修正；
   `maxHealth` 非有限或 `<= 0` → 恢复到基线；
   `absorption` 为负 → 0。
   这条对"NaN 血"（附录 A.4）是唯一有效的处理：NaN 会穿过 `Mth.clamp`，`isDeadOrDying()` 对 NaN 为 false，实体永远不死。

3. **影子账本**（P1）
   在 `SavedData` 里按 UUID 存一份"应然血量"，由 `LivingDamageEvent` / `LivingHealEvent` 更新。
   tick / 登录时比对：实体比账本低、且没有对应的伤害事件 → 写血。
   比"只看当前血量"强，因为你能区分"被打"和"被写"。

4. **Canary**（P1）
   一个随机 `long` 存在私有字段里，`SavedData` 存一份副本，定期比对。
   反射写字段的人通常不会同步更新 `SavedData` 的副本。
   这不是密码学，是"提高成本 + 让 naive 写入暴露"。

5. **属性守卫**（P1）
   每 tick 检查 `MAX_HEALTH` 的 base value 和 modifier 是否有限、是否在合理区间；
   NaN/负数 modifier → 移除 + 记日志。

6. **NBT 读入校验**（P1）
   `readAdditionalSaveData` / `load` 之后，对 `Health`、`Attributes`、自定义冷却做一次范围校验。
   这挡的是 `/data merge` 和"改存档"，不是同进程 mod。

**不要做的**：
- 每 tick 把血写回最大值（橡皮筋、和 legit 伤害打架、掩盖证据）。
- 在 `setHealth` 上 `@Overwrite` 成"拒绝一切外部调用"（会破坏 vanilla/其他 mod 的合法路径）。
- 把"防御状态"存在一个 public/可反射的 boolean 里，然后指望它不被改。

### 10.3 改 jar

**它做了什么**：改你的 class、替换整个 jar、删掉依赖、或者把 `@Mod` 主类换成会抛异常的实现。
这是**文件系统层（L5）**，在 JVM 的信任边界之外。

**防不住的部分**：**在你自己的 jar 里做任何自检，都能被同一个改 jar 的人 patch 掉。**
"哈希比对常量写在 jar 里"等于把钥匙和锁放在同一个盒子里。
所以"进程内防改 jar"这个目标不成立。

**能做的**：

1. **发布 SHA-256**（P0，成本最低）
   每个 release jar 在发布页 / `checksums.txt` 里给出哈希。
   这是唯一不依赖"进程内自检"的验证方式。

2. **启动打印指纹**（P0）
   启动时打印自己的 jar 路径 + SHA-256，让服务器管理员能对照发布页。
   代码骨架：
   ```java
   var src = Golems_arsenal.class.getProtectionDomain().getCodeSource().getLocation();
   // src 可能为 null（特殊 ClassLoader）；开发环境是目录，生产是 jar，两种都要处理
   // 读字节 → MessageDigest.getInstance("SHA-256") → 十六进制
   ```
   它不能防，但能让"改过的 jar"在管理员面前现形。

3. **`/ga integrity`**（P1）
   把上面的指纹做成命令，服务器里随时可查。

4. **分离 guard jar**（P2）
   一个独立的小 mod，启动时校验主 jar 的哈希，不匹配就 log / 拒绝启用内容。
   攻击者要同时 patch 两个 jar。提高成本，仍然可破。

5. **启动器 manifest**（P2，唯一真正"防"的）
   由启动器在**启动前**校验哈希，信任锚在 jar 之外。
   这是唯一不依赖"被改进程自证清白"的方案。

**不要做的**：
- 只做混淆（攻击者读字节码就能找到）。
- "检测到篡改就崩游戏"（先崩的是 legit 用户；而且可 patch）。
- 把公钥/哈希写进同一个 jar 然后自称"签名校验"。

### 10.4 反射

**它做了什么**：`setAccessible(true)` 写私有字段、`Unsafe`/`VarHandle` 写内存、`MethodHandles` 调私有方法。
你的 mod 在 Forge 里大概率是 unnamed module / ModLauncher 的 module layer，
`setAccessible` 对其他 mod 是开放的；`SecurityManager` 在 17 已废弃、后续版本默认收紧，
**不要把它当防线**。`module-info` 的强封装在 Forge 环境里也不实用（Mixin/AT/Forge 自己都要反射你）。

**防不住的部分**：同进程的反射 / Unsafe 写入，**没有进程内的通用拦截点**。
`VarHandle`/`Unsafe` 写字段不经过任何你能 hook 的方法。

**能做的**（提高成本 + 检测）：

1. **冗余 / 派生状态**（P0）
   不要把"无敌"存成一个 boolean。把它派生自：升级列表 + `gameTime` + `SavedData` 记录，
   三处交叉校验。反射改一个字段，不会同时改另外两处。
   这是"不变量多点冗余"在状态层面的版本。

2. **Canary 字段**（P1）
   同 §10.2 第 4 条。随机 nonce + `SavedData` 副本，定期比对。

3. **栈检查**（P2，贵）
   在 `setHealth` 或你的 guard 上 `@Inject`，用 `StackWalker` 看调用者类；
   不是 vanilla / 你的 mod → log。
   它能抓 naive 反射，但 `MethodHandles`/`Unsafe`/native 可以绕过；而且每次 `setHealth` 都 walk 很贵，
   只对"受保护实体"、只采样。

4. **Fail soft**（P0）
   字段被改成非法值 → clamp + log，不要抛异常。
   反射攻击者常常指望你崩，崩了就是他的胜利。

**不要做的**：
- `SecurityManager`（废弃、可绕过、破坏兼容）。
- 自定义 `ClassLoader` 隔离（Forge 的类加载不是你能随便换的）。
- 把字段"加密"（密钥在 jar 里）。
- 把安全寄托在"字段名很怪"（源码公开）。

### 10.5 杀主类

先分清两种：

- **删/换主类文件**：和 §10.3 改 jar 同级（L5），进程内防不住，只能外部校验。
- **让主类加载失败**（`NoClassDefFoundError` / static initializer 抛异常 / 依赖缺失）：
  这是**可用性攻击**，不是完整性攻击。它不偷不改你的状态，它让你的 mod 不工作。

**能做的**：

1. **Fail soft**（P0）
   别让别的 mod 硬依赖你的主类；`mods.toml` 里该 optional 的 optional；
   跨 mod 引用用 `Class.forName` + `try/catch`，不要 static import。
   你的 mod 没了，整合包应该还能跑——这是"破了之后你还在"的最低版本。

2. **清晰报错**（P0）
   在自己的 init 里 `catch (Throwable)`，打出"golems_arsenal 加载失败，原因 X"，
   不要让玩家看到一串 `NoClassDefFoundError` 猜谜。

3. **Guard mod**（P2）
   一个独立 jar，`ModList.isLoaded("golems_arsenal")` 为 false 时 log / 告警。
   它救不回来，但能给管理员证据。

4. **服务端要求**（P1）
   如果服务器需要这个 mod，客户端缺失时给一条干净的 disconnect 消息，而不是崩。

**不要做的**：
- 自定义 ClassLoader "自愈"、agent 复活主类（L4，过度设计、易碎）。
- 把主类写成巨型 God class（一挂全挂）。

### 10.6 总表

| 优先级 | 动作 | 防谁 | 成本 | 性质 |
|---|---|---|---|---|
| P0 | Delta 看门狗 + 归因日志 | 写血 | 低 | 检测 |
| P0 | 读时 clamp（health / attribute / NBT） | 写血、NaN | 低 | 限制后果 |
| P0 | Fail soft + 清晰报错 | 杀主类 | 低 | 降级 |
| P1 | 发布 SHA-256 + 启动打印 + `/ga integrity` | 改 jar | 低 | 取证 |
| P1 | 影子账本 / 冗余派生状态 | 写血、反射 | 中 | 检测 |
| P1 | Canary | 反射 | 低 | 检测 |
| P2 | 分离 guard jar | 改 jar、杀主类 | 中 | 提高成本 |
| P2 | 启动器 manifest 校验 | 改 jar、杀主类 | 高 | **唯一真防** |
| — | 进程内"绝对防反射/防改 jar" | — | — | **不存在** |

**一句话总结**：
写血可以"对账 + clamp + 留证据"；改 jar / 杀主类只能"外部校验 + 降级"；
反射只能"冗余 + canary + 栈检查"提高成本。
**四种里没有一种能在进程内被"彻底防住"**——这不是实现问题，是信任边界问题。

### 10.7 DEF 指向改 / 路径重定向的防守

"DEF 指向改"有三种，防守方式不同：

1. **路径重定向**：把 DEF 的 `sprite/anim/sound/cmd/cns` 指到别处（`..`、绝对路径、UNC、符号链接、ADS）。
2. **`select.def` 重定向**：把角色的 `def` 路径指到另一个 DEF（弱角色 / 恶意文件）。
3. **内存指针重定向**：DEF 解析器溢出，覆盖指针 / 返回地址。

**路径重定向的防守**（引擎侧，最有效）：

- 对每个路径做 `realpath` / `canonicalize`，然后检查结果是否仍在角色文件夹内。
- 拒绝：绝对路径、`..`、符号链接、UNC（`\\`）、盘符（`C:`）、ADS（`:`）、设备名（`CON`/`NUL`）。
- 扩展名白名单：`.sff .air .snd .cmd .cns .def .st .txt`；拒绝 `.exe .dll .bat .zip`。
- 只允许相对路径，且相对路径的每一段都不能是 `..`。
- 读文件前先 `stat`，拒绝非常规文件（目录、设备、管道）。

**`select.def` 重定向的防守**：

- 不信任 `select.def` 的顺序和路径；解析后做同样的 canonicalize + 白名单。
- 对 roster 做确定性排序（不要依赖文件系统顺序）。
- 对每个条目算哈希，和清单比对。

**内存指针重定向的防守**：

- 这是解析器的内存安全 bug，角色侧无解；只能在引擎侧修：
  边界检查、`safe_strcpy`/`snprintf`、现代编译器缓解（ASLR/DEP/CFG）、
  用现代 zip 库替换老解析器。
- 最彻底：换引擎（IKEMEN Go 这类开源、可打补丁的），或者把 MUGEN 放进沙箱 / VM。

**F1/F5 是另一类**：它们是调试键，不是角色能力。
角色要按它们，必须先有代码执行（`keybd_event` 之类），所以防 F1/F5 的终点还是防代码执行。
如果只是玩家手按，那是规则问题：用关掉调试键的构建，或者在比赛规则里禁掉。

### 10.8 "不用 .bat / 不用绝对路径"不等于安全

相对路径的角色包仍然可以：

- 触发 DEF/ZIP/SFF 解析器的内存 bug；
- 用 `..` 跳出角色文件夹读系统文件；
- 用符号链接 / ADS / 设备名绕过路径检查；
- 在 roster 扫描阶段执行代码。

所以"没有 `.bat`、没有绝对路径"只是**降低了可见性**，不是**降低了能力**。
真正的边界只有两个：**不在同一进程里加载不可信包**，或者**在沙箱里加载**。

---

## 11. 模块化生物的攻击面：组合即数据

> 这一节只针对《傀儡装配》（`modulargolems`）这类"由零件 / 材质 / 升级拼出来"的生物。
> 结论先行：**对这种生物，防御不是实体的属性，是"当前组合"的属性；而组合在每一层都是数据，数据是可写的。**
> 攻击者不需要破你的防御，他只需要改组合——把提供防御的升级 / 材质 / 修饰符拿掉，或者塞一个自己的修饰符进去。

> 版本说明：以下行号来自 `libs/modulargolems-2.6.34-sources.jar`（681 个源文件）。
> 运行时是 `libs/modulargolems-2.7.3.jar`；用 `javap` 核对过 `AbstractGolemEntity` / `GolemHolder`
> 的关键 public 签名，**公开面没有变**，但精确行号可能要按 2.7.3 重新对。

### 11.1 组合管线

```
GolemHolder 物品 NBT
  ├─ golem_materials: [{part, material}]
  └─ golem_upgrades: [item id]
        │ summon / updateAttributes
        ▼
GolemPart.parseMaterial(materialId)
  ├─ GolemMaterialConfig.stats[materialId]       ← datapack
  ├─ GolemMaterialConfig.modifiers[materialId]   ← datapack
  ├─ GolemPartConfig.filters[part]               ← datapack
  └─ GolemPartConfig.magnifiers[type]            ← datapack
        ▼
GolemMaterial（record: stats, modifiers）
        │ GolemMaterial.collectModifiers(materials, upgrades)
        ▼
HashMap<GolemModifier, Integer>   ← 实体里的 live map（getModifiers() 直接返回）
        │ ModifierEventListeners 逐个调用
        ▼
GolemModifier.onAttacked / onHurt / onDamaged / modifyDamage / ...
        │ updateAttributes 时重建
        ▼
HashSet<GolemFlags>（IMMUNITY / FIRE_IMMUNE / REVIVE / ...）
        │ hasFlag()
        ▼
AbstractGolemEntity.isInvulnerable() → GuardedEntity.isInvulnerableToExtra
```

### 11.2 攻击面

| 阶段 | 可写的东西 | 层 | 一行攻击 | 防守 |
|---|---|---|---|---|
| Holder NBT | `golem_materials` / `golem_upgrades` | L0/L1 | `GolemHolder.setUpgrades(stack, List.of())` | 召唤时校验 NBT / 哈希清单 |
| 材质配置 | `GolemMaterialConfig` / `GolemPartConfig` | L0 | datapack 改 `modifiers` / `ingredients` | 把配置当 L0 数据；校验 / 哈希 |
| `parseMaterial` | 公开方法；`GolemMaterial` 是 public record | L1 | 自建 `GolemMaterial` 塞进 `materials` | material id 白名单 |
| `collectModifiers` | 公开 static | L1 | 用空 upgrades 重算 | 不把它当安全边界 |
| `getModifiers()` | **live map** | L1 | `put(malicious, 1)` / `remove(IMMUNITY)` / `clear()` | 快照 + 版本号；不要直接信 |
| `getModifiersExtended()` | 装备 `IGolemModifierItem` | L1 | 给傀儡穿一件提供 modifier 的装备 | 装备白名单 / 审计 |
| `getUpgrades()` | **live list** | L1 | `clear()` / `add(...)` | 快照 + 比对 |
| `getMaterials()` | **live list** | L1 | `set(i, ...)` / `clear()` | 快照 + 比对 |
| `updateAttributes()` | 公开；重建 modifiers / flags / attrs | L1 | 清空 upgrades 后调用 | override / mixin 后 re-assert；tick 对账 |
| `golemFlags` | private，只在 `updateAttributes` 重建 | L3 | 反射改 | canary / 派生校验 |
| `ModifierEventListeners` | 事件分发 | L2 | mixin 跳过某个 modifier | 多点冗余 |

**补充：配置对象本身也是 public 可变的。**
`GolemMaterialConfig.stats/modifiers`、`GolemPartConfig.filters/magnifiers` 都是 `public HashMap` 字段，
`get()` 返回的是单例；任何 mod 都能在运行时直接改（L1），不需要 datapack（L0）。
`GolemMaterial` 虽然是 record，但它的 `stats()` / `modifiers()` 返回的是 `HashMap` 本体，也是可变的。
所以"组合即数据"不只是"存档里的 NBT 是数据"，而是**从配置单例到实体字段，整条链都是可写的**。

### 11.3 两个表示：`modifiers` 与 `golemFlags`

组合有两份表示：

- `modifiers` 是**直接表示**：`HashMap<GolemModifier, Integer>`，事件分发读它。
- `golemFlags` 是**派生表示**：`HashSet<GolemFlags>`，`isInvulnerable()` 读它。

它们只在 `updateAttributes` 里同步。这带来一个很关键的缝隙：

| 攻击 | `modifiers` | `golemFlags` | 结果 |
|---|---|---|---|
| `getModifiers().remove(IMMUNITY)` | 没了 | 还在 | 事件层不取消，但 `isInvulnerableToExtra` 仍然拦 |
| `getUpgrades().clear()` | 不变（直到 rebuild） | 不变 | 你的 `isActive` 立刻失效；本家免疫还在 |
| `getUpgrades().clear()` + `updateAttributes` | 重建为空 | 重建为空 | **两层一起消失** |
| 反射改 `golemFlags` | 还在 | 没了 | 事件层还会取消，但 flag 层没了 |

**"两份表示不一致"本身就是有人在改状态的信号。** 对账能抓到，单看一份会被骗。

### 11.4 本仓库的具体暴露

1. **`GolemUpgradeLevels.levelOf` 逐项读 `golem.getUpgrades()`**（[GolemUpgradeLevels.java:38](src/main/java/a_silly_cat/golems_arsenal/base/upgrade/GolemUpgradeLevels.java#L38)）。
   `getUpgrades().clear()` 一行就把 `isActive` 变成 false，Omega 套装（光柱免疫 / 幻梦零 / E 罐）全部失效——
   **不需要 `updateAttributes`**。
2. **`GolemsArsenalSetHandler.isActive` 依赖它**。所以"套装防御"的实际强度 = `getUpgrades()` 的可写性。
3. **你们的 `GolemModifier` 和本家的 `ImmunityModifier` 在同一个 `modifiers` map 里**。
   `getModifiers().remove(yourModifier)` 就禁用它；`put(malicious, 1)` 就插入一个能收到所有钩子的 modifier。
4. **`updateAttributes` 是 public**：清空 upgrades 再调用，本家的 `IMMUNITY` flag 也会消失。
   这是"从组合层拆防御"的最短路径。
5. **`getModifiersExtended()` 会从装备读 `IGolemModifierItem`**：给傀儡穿一件恶意装备，就能注入 modifier，
   不需要改 upgrades。
6. **`GolemMaterialConfig` / `GolemPartConfig` 是 datapack 驱动的**：材质授予哪些 modifier、零件接受哪些 stat，
   都是数据。一个 datapack 可以改这些（至少可以合并 / 覆盖；能否删除取决于 `CollectType` 语义）。
7. **`GolemHolder` 的 NBT 是"蓝图"**：`setUpgrades` / `setMaterial` 是 public static；在召唤前改 NBT，
   召唤出来的傀儡就是另一个组合。

### 11.5 防守清单

1. **组合快照**：在套装完成 / 召唤时算一个 `hash(materials, upgrades, modifiers)`，
   存 `SavedData` + UUID；tick / 事件时比对。
2. **不要只信 live list**：`getUpgrades()` / `getModifiers()` 是 live 的；每次判定前拿快照或版本号。
3. **两份表示交叉校验**：`modifiers` 与 `golemFlags` 不一致 → log。
4. **`updateAttributes` 之后 re-assert**：mixin / override，`super` 之后重新注册你的不变量；或 tick 对账。
5. **装备审计**：对"受保护"的傀儡，记录 / 限制 `IGolemModifierItem` 的来源。
6. **Holder NBT 校验**：召唤时校验 `golem_materials` / `golem_upgrades` 是否是合法组合（白名单 / 哈希）。
7. **配置当 L0**：不要把"安全"建立在 `GolemMaterialConfig` 的 modifier 上；它可被 datapack 改。
8. **Fail soft**：组合被改 → clamp + log，不要崩。

**一句话**：模块化把"防御"变成了"组合的一个部件"，而部件是可拆的。
所以对模块化生物，**防御必须是一个独立的、不可组合掉的不变量**——
要么在组合之外（`SavedData` / 实体类型 / 服务端记录），要么在组合之上（每次重建后 re-assert）。
只写一个 `GolemModifier` 就指望它一直生效，等于把门锁装在一个可拆卸的门上。

---

## 12. 反制：让敌意 mod 的改动"失效"

> 先把话说死：**如果敌意 mod 和你在同一个 JVM 里，没有任何进程内手段能让它的改动"彻底失效"。**
> 它能调 public API、能反射、能 mixin、能换 jar；你写的每一个检查都在它的可达范围内。
> 所以这一节的目标不是"不可破"，是让它的改动：
> **①尽量不生效 ②生效了能被发现 ③发现了能修复 / 归因 ④修不了也不崩。**

### 12.1 两条战线

| 战线 | 它做什么 | 你在哪一层能拦 | 拦不住的部分 |
|---|---|---|---|
| 攻击面 | 无视抗性、自组伤害、写血 | L1 事件 + L1.5 状态 | `setHealth` 调用本身 |
| 篡改面 | mixin、改 jar、改组合 | L2 检测 + L5 外部 | 同进程的字节码 / 文件修改 |

### 12.2 四层防线

| 层 | 做什么 | 能挡 | 代价 |
|---|---|---|---|
| L1 事件层 | default-deny：受保护实体只接受白名单伤害类型/攻击者 | 无视抗性、自组伤害、`hurt` 类攻击 | 会误伤 legit mod；要可配置 |
| L1.5 状态层 | 影子账本 + tick 对账 + 组合快照 | `setHealth`、反射写字段、组合篡改 | 有性能成本；会与 legit 回血打架 |
| L2 检测层 | 组合 hash、两份表示交叉校验、JVM/ModList 审计 | 发现篡改、归因 | 不能阻止 |
| L5 外部层 | 启动器 manifest、只读 mods、独立进程/沙箱 | mixin、改 jar、换进程 | 重；需要基础设施 |

### 12.3 针对每种攻击

| 攻击 | L1 事件层 | L1.5 状态层 | L2 检测层 | L5 外部层 |
|---|---|---|---|---|
| 无视抗性 / 自组伤害 | 只放行白名单 damage type + 攻击者；其余 `setCanceled(true)` + log | 账本记录实际伤害 | 记录未知 damage type / mod id | — |
| 写血 | 看不到（无事件） | 账本不符 → 修复 / log | tick delta、异常时间戳 | — |
| mixin | 多点冗余（event + tick + finalize） | reconciler 放两处 | 审计 JVM args / `MixinConfigs` / 未知 mod | 启动器校验、agent 检测 |
| 改 jar | 不知道 | 自检 hash（可 patch） | 启动打印 + `/ga integrity` | 启动器 manifest / 只读 / 沙箱 |
| 改组合 | 组合快照 | `updateAttributes` 后 re-assert | `modifiers` vs `golemFlags` 不一致 | — |

**几个具体点**：

- **default-deny 不是"取消所有伤害"**：是"受保护实体只接受白名单"。白名单要包含 vanilla 的常规伤害类型
  + 你确认过的 mod 伤害类型 + 玩家直接攻击。未知来源 → cancel + log，而不是 crash。
- **写血只能靠对账**：`setHealth` 不发事件，事件层永远看不到。影子账本 + tick 比对是唯一能在进程内发现它的手段。
- **mixin 只能靠冗余**：一个 `@Overwrite` 能废掉你的一个检查，所以把同一个不变量放在
  `LivingAttackEvent`、`LivingHurtEvent`/`LivingDamageEvent`、`finalizeHurtTarget`/tick 三处。
- **改 jar 只能靠外部**：自检 hash 的常量在 jar 里，改 jar 的人能一起改；只有"启动前校验"才是真防。

### 12.4 "彻底失效"的唯一路径

进程内的所有手段，最终都输给"攻击者能在同一个进程里执行代码"。
要让篡改"彻底失效"，只有把**权威状态搬出这个进程**：

1. **独立进程 / 伴随服务**：游戏服务器只发事件，权威判定在另一个进程；
   那边返回"允许/拒绝/修正值"，游戏侧照做。攻击者改游戏 JVM，改不了另一个进程的状态。
2. **启动器 manifest**：启动前校验 jar 哈希 + JVM 参数；信任锚在 jar 之外。
3. **只读 / 不可变**：mods 目录只读、容器镜像、快照回滚；改 jar 在启动前就被挡。
4. **签名指令**：独立进程用私钥签名修正值，游戏侧用**固定在 jar 之外**的公钥验签。
   （公钥在 jar 里 = 可 patch = 不是签名。）

这四条都有代价：延迟、复杂度、运维。**如果一条都不做，"彻底"就是不存在的**；
你能做到的最好结果是"它的改动被发现、被修复、被归因，且不崩"。

### 12.5 一个"受保护实体"的架构

```
SavedData "ga_protected":
  uuid -> {compositionHash, expectedHealth, lastDamageTick, tamperCount, lastKnownGood}

LivingAttackEvent (HIGHEST):
  if protected && !allowed(source): cancel + log("DENY_SOURCE")

LivingDamageEvent (LOWEST):
  ledger[uuid] -= amount; lastDamageTick = now

LivingTickEvent (每 N tick, 只对 protected):
  expected = ledger[uuid]
  if (!finite(health) || abs(health - expected) > EPS):
      log("UNEXPLAINED_HEALTH"); tamperCount++
      if (config.repair) restore(expected)

ServerTickEvent:
  每 M tick 检查一次 jar hash / JVM args / ModList（只 log，不阻断）

EntityJoinLevelEvent / updateAttributes 之后:
  re-assert composition（hash 不符 → log + 重新注册）

/ga audit:
  dump protected state + 最近 N 条 tamper 日志
```

**关键设计**：

- **只对"受保护"实体开严格模式**：普通实体只 log，不修复；否则会和整个整合包打架。
- **修复要保守**：只在"账本与实体不符、且没有对应的伤害/治疗事件"时修复；有歧义就只 log。
- **tamperCount 是升级信号**：连续 N 次 → 进入 lockdown（禁用该实体的内容 / 通知管理员），
  而不是无限对账——对账循环本身就是给攻击者的一个 DoS 面。
- **日志是主产品**：`damageType`、`causingEntity`、`mod id`、`tick`、`position`、`compositionHash`。
  没有日志，你连"是谁"都不知道。

### 12.6 设计原则

1. **Default deny, not default allow**：受保护实体只接受白名单；未知来源拒绝 + log。
2. **Derive, don't store**：防御状态从组合/服务端记录派生，不存成一个可写 boolean。
3. **Re-assert, don't trust**：`updateAttributes` / 登录 / 维度切换之后重新注册不变量。
4. **Log, don't crash**：崩服是攻击者的胜利；永远 clamp + continue。
5. **Fail soft, not fail open/closed**：防线被破时，内容降级（该实体不再"受保护"），但服务器继续跑。
6. **Don't fight in a loop**：对账有上限；超过阈值就转人工/服务端处置。
7. **False positive is the enemy**：严格模式默认关，按实体/按整合包开；否则第一个被投诉的是你。

**一句话**：进程内你能做到的是"**让它的改动变得昂贵、可见、可归因、可修复**"；
"彻底失效"只存在于"它的代码不在你的进程里"——那不是代码问题，是部署问题。

---

## 13. 参考实现：Arcane Vortex（L4/L5 的活样本）

> 拆的是 `ArcaneVortex [Alpha]-0.6.8-1.20.1-Forge.jar`（`com.erchien.arcanevortex`）。
> 这不是"又一个攻击 mod"，是一个**自安装 agent + CoreMod + 最高优先级 Mixin + jar 补丁器 + 自我保护**的完整框架。
> 它正好证明了 §12 的结论：**在同一个 JVM 里，L1 的防守对 L4/L5 的攻击没有胜算。**
> 这一节只做**架构拆解和检测特征**，不提供复刻步骤。

### 13.1 它是怎么进来的：加载阶梯

```
META-INF/asm/agent_bootstrap.js（Forge CoreMod）
  → 注入 Minecraft.<init>，调用 AgentBootstrap.agentLoad()
  → 检查 -javaagent: 是否已在参数里
  → 尝试 VirtualMachine.attach(pid).loadAgent(agentJar)
      └─ enableSelfAttach(): 用 Unsafe 把 HotSpotVirtualMachine.allowAttachSelf 置 true
  → 失败则 restartWithAgent():
      写 %TEMP%/arcanevortex/restart_*.bat
      用 -javaagent:... -Darcanevortex.restart.done=true 重启
  → 还支持 WMIC / sun.java.command / javawrapper.jar 重建启动命令
```

关键点：

- **它不需要你在启动器里加 `-javaagent`**：能自 attach 就自 attach，不能就写 `.bat` 重启。
- **它比大多数 mod 都早**：CoreMod 注入 `Minecraft.<init>`，在 mod 初始化之前就把 agent 挂上。
- 内嵌 agent：`assets/arcanevortex/agent/arcanevortex-agent.jar`。

### 13.2 它怎么锁血：三层覆盖

| 层 | 类 | 作用 |
|---|---|---|
| Mixin 层 | `HealthLockMixin` | `setHealth` HEAD → `ci.cancel()`；`getHealth` HEAD → `cir.setReturnValue(locked)`；`tick` TAIL → 每 tick 重锁 |
| 状态层 | `HealthLockManager` | `Map<UUID, LockedHealthData>`：originalHealth / lockedHealth / lockTime / healthFields |
| 写血层 | `HealthModify` | 五路：`entityDataModify` / `traversalModify` / `forceModify` / `tagModify` / `attributeModifier` |
| 反射层 | `Traversal` / `TraversalUnstable` | 扫 EntityData、普通字段、属性、嵌套对象、NBT；`setupHealthLockSafely` 生成 `FieldAccessInfo` |
| Agent 层 | `AgentHealthMethodHelper` | `HEALTH_MAP` + `forceSetHealth` / `agentGetHealth` / `agentGetMaxHealth` |
| 生命周期层 | `protection.LivingEntityMixin` | hook `hurt` / `die` / `setHealth` / `kill` / `actuallyHurt` / `tickDeath` / `isAlive` / `isDeadOrDying` / `getDamageAfterArmorAbsorb` / `getDamageAfterMagicAbsorb` / 掉落 / 经验 / 音效 |
| 同步层 | `protection.SynchedEntityDataMixin` | 拦 `SynchedEntityData.set`，阻止别人改血量同步 |
| 真伤层 | `TrueDamageMixin` | `actuallyHurt` HEAD 注入 |

**为什么难防**：这三层是独立的。你写 raw 字段，`getHealth` 仍返回锁定值；你写 `setHealth`，被 cancel；
你写 NBT/attribute，`Traversal` 的 tick 重锁会盖回来；你想 mixin 它，`SecurityClassProtector` 会检测/revert。

### 13.3 它怎么改别人

| 类 | 作用 | 落盘痕迹 |
|---|---|---|
| `JarPatcher` | 扫 `mods/*.jar`，改 class（health 方法、return 方法、entity 方法），写回 jar | `.jar.backup` / `.tmp` / `%TEMP%/ArcaneVortex/.lock` / 线程 `ArcaneVortex-Monitor` |
| `MixinPatcher` | 扫 jar 里的 Mixin 类，改/中和 | `.mixin.backup` / `.mixin.tmp` |
| `AgentDllBlocker` | 拦其他 mod 的 DLL 加载 | `blockedDlls` / `allowedDlls` |
| `SecurityClassProtector` | 保护自己的类：`CLASS_HASHES` / `ORIGINAL_BYTECODE` / `MAX_TRANSFORM_ALLOWED` / `preventRedefinition` | — |
| `HiddenClassManager` | 保护包/类，增加反射难度 | — |

Mixin 配置里 `priority: 2147483647`、`mixinPriority: 2147483647`——**用最大优先级压过其他 mod 的 mixin**。

### 13.4 为什么进程内防守无效

| 你的防守 | 它对应的层 | 为什么无效 |
|---|---|---|
| `LivingAttackEvent` 取消 | `protection.LivingEntityMixin.onLivingHurt` | 它可以取消你的取消，或在更后面改 |
| `setHealth` 对账 | `HealthLockMixin.setHealth` / `getHealth` | 你的写被 cancel，你的读返回锁定值 |
| 写 raw `SynchedEntityData` | `SynchedEntityDataMixin` + `Traversal` | 它拦截/重写 |
| 写 NBT / attribute | `HealthModify.tagModify` / `attributeModifier` + tick 重锁 | 五路写 + 每 tick 盖回 |
| mixin 它的类 | `SecurityClassProtector` | 它检测/revert |
| 自带 DLL 反制 | `AgentDllBlocker` | 它拦 DLL 加载 |
| 改 jar 自检 | `JarPatcher` | 它能改你的 jar |
| 只查 `-javaagent` | `AgentBootstrap` 自 attach / `.bat` 重启 | 它不需要启动参数 |

### 13.5 检测特征（可直接用）

| 类型 | 特征 |
|---|---|
| Mod / 类 | `ModList.isLoaded("arcanevortex")`；`com.erchien.arcanevortex.*` |
| 系统属性 | `arcanevortex.agent.loaded` / `arcanevortex.restart.done` / `arcanevortex.agent.disabled` |
| JVM 参数 | `-javaagent:...arcanevortex-agent.jar`；`-Darcanevortex.restart.done=true` |
| 文件 | `%TEMP%/arcanevortex/restart_*.bat`；`%TEMP%/arcanevortex/arcanevortex-agent.jar`；`%TEMP%/ArcaneVortex/.lock`；`*.jar.backup`；`*.mixin.backup`；`*.tmp` |
| 线程 | `ArcaneVortex-Monitor` |
| 日志 | 控制台 `[ArcaneVortex] ...` |
| 行为 | `HealthLockManager.isEntityLocked(entity)`（可反射调用）；`getHealth` 与 raw data / NBT / attribute **四者不一致** |
| Mixin | `arcanevortex.mixins.json` 的 `priority: 2147483647` |

**最实用的两条**：

1. **四路对账**：`getHealth()` vs `SynchedEntityData` 里的 `DATA_HEALTH_ID` vs NBT `Health` vs `MAX_HEALTH` 属性。
   只要四者不一致，实体就被"锁"了——这是不依赖它 API 的检测。
2. **文件/参数审计**：`%TEMP%/arcanevortex/`、`.jar.backup`、`-javaagent:`、`arcanevortex.*` 系统属性。
   这些是它的"落盘痕迹"，比内存检测稳。

### 13.6 结论

- 它是 **L4/L5 的完整框架**：自 attach、改 jar、改 mixin、锁血、拦 DLL、保护自己。
- 一个正常 mod（L1）**不可能**让它"彻底失效"；这是层级问题，不是实现问题。
- 能做的：**检测 + 归因 + 外部处置**（移除 mod、校验 jar、只读 mods、启动器 manifest、沙箱）。
- 如果必须共存：不要和它在 `getHealth`/`setHealth` 上对账；用独立状态 + 四路交叉校验，
  发现被锁就 log + 降级，不要试图"打过"它——它的 `SecurityClassProtector` 就是为这场仗准备的。

---

## 14. 启动器视角：从 Java 代码上，我们能不能保护自己？

> 这一节把战场从"游戏进程内"移到"启动器 + 进程 + 文件系统"。
> 结论先行：**能，而且比进程内防守强一个数量级；但只能做到"风险降低"，不是"免疫"。**
> 关键不是"写多聪明的 Java 检查"，而是**信任锚放在哪**——如果锚和攻击者在同一个信任域里，它就不是锚。

### 14.1 信任锚：先问"谁的话算数"

| 锚 | 强度 | 问题 |
|---|---|---|
| 哈希常量写在同一个 jar 里 | 无 | 改 jar 的人能一起改 |
| 启动器内置公钥，验签 manifest | 中 | 改启动器的人能换公钥 |
| 启动器代码签名 + 自动更新（HTTPS + 固定公钥） | 中-高 | 依赖 OS 的代码签名信任 |
| OS keystore / TPM 里的私钥 | 高 | 需要硬件/系统支持 |
| 服务端 nonce + 客户端签名 | 高 | 需要服务端配合；无硬件时私钥可被提取 |
| 只读镜像 / 容器 / VM + 快照 | 高 | 运维重；但这是唯一"改不进去"的 |

**规则**：**验证者不能和被测者在同一个信任域里。**
启动器验 jar，锚在启动器；启动器自己被改，锚就没了。所以"启动器保护 jar"之前，先解决"谁保护启动器"。

### 14.2 启动器能做的五层

| 层 | 动作 | 挡住什么 | Java 能做吗 |
|---|---|---|---|
| 1 校验 | 签名 manifest：每个 jar / config / JVM 参数的哈希 | 改 jar、加 mod、换参数 | 能（文件 I/O + Ed25519 验签） |
| 2 加固 | `-XX:+DisableAttachMechanism`、禁 `-javaagent`、禁 `-Xbootclasspath/a`、最小 `--add-opens`、专用低权用户 | 自 attach、动态 agent、bootclasspath 注入 | 能（启动参数） |
| 3 隔离 | mods 只读 / copy-on-write、独立用户 / 容器 / VM、默认断网 | `JarPatcher` 写 jar、外联 | 部分（Java 编排，OS 执行） |
| 4 监控 | 进程树（`cmd`/`wmic`/子 `javaw`）、文件写入、jar 哈希、网络 | `.bat` 重启、运行期改 jar、外联 | 部分（Java 轮询，OS 更稳） |
| 5 处置 | **quarantine（不删）**、按哈希拉黑、回滚、报告 | 已知恶意样本 | 能 |

### 14.3 关键 Java / JVM 机制

| 机制 | 作用 | 注意 |
|---|---|---|
| `-XX:+DisableAttachMechanism` | **关掉 attach API**，`VirtualMachine.attach` 直接失败 | 对 Arcane Vortex 的自 attach 是致命一击；会同时禁掉 jcmd/jstack/profiler |
| `-Djdk.attach.allowAttachSelf=false` | 禁止自 attach | 默认就是 false；它用 `Unsafe` 绕过，所以**不够** |
| `-XX:-EnableDynamicAgentLoading` | JDK 21+ 默认限制动态加载 agent | Java 17 用 `DisableAttachMechanism` |
| 禁 `-javaagent` / `-agentlib` / `-Xrunjdwp` | 不让启动参数带 agent | 启动器自己构建完整命令行，不接受用户追加 |
| 禁 `-Xbootclasspath/a` | 不让改 boot classpath | 同上 |
| 最小 `--add-opens` / `--add-exports` | 减少反射面 | Forge 自身可能需要一些；按白名单给 |
| 专用低权用户 + 只读目录 | 限制写文件 / 提权 | Java 只能编排，执行靠 OS |
| `SecurityManager` | **不要用** | 已废弃，且挡不住 `Unsafe`/native |

**一句话**：`-XX:+DisableAttachMechanism` 是启动器手里最便宜、最有效的一张牌。
它把 Arcane Vortex 从"自 attach"逼回"`.bat` + `-javaagent` 重启"——而后者是进程树/参数监控能抓的。

### 14.4 静态扫描：启动前先看 jar

| 检查 | 目标 |
|---|---|
| `META-INF/MANIFEST.MF` 的 `Agent-Class` / `Premain-Class` / `Launcher-Agent-Class` | 自带 agent 的 jar |
| `META-INF/asm/*.js` | Forge CoreMod（Arcane Vortex 的入口就在这里） |
| `*.mixins.json` 的 `priority` / `mixinPriority` 极端值 | 试图压过别人的 mixin |
| `.dll` / `.so` / `.dylib` / `.exe` / `.bat` | native / 外部程序 |
| 常量池字符串：`java/lang/instrument`、`ClassFileTransformer`、`retransformClasses`、`com/sun/tools/attach`、`sun/misc/Unsafe`、`Runtime.exec`、`ProcessBuilder`、`wmic`、`-javaagent`、`defineClass` | agent / 反射 / 执行 |
| 嵌套 jar、加密/高熵 blob、超大 base64 | 打包/混淆 |

**局限**：这是启发式，混淆能绕过；但它能抓已知样本、把"随手塞一个恶意 jar"变成"要专门做免杀"。
用 ASM/ClassFile API 看字节码，不要只 `strings`。

### 14.5 运行期监控

- **进程树**：子进程里出现 `cmd.exe` / `wmic` / 带 `-javaagent` 的 `javaw` → 高危。
- **文件**：`mods/` 被写、`%TEMP%/` 出现 `.bat` / `*.jar.backup` / `*.mixin.backup` / `.lock` → 高危。
- **哈希**：每 N 秒重算 `mods/*.jar`，和 manifest 比；不一致 → 告警/杀进程。
- **JVM 参数**：`ProcessHandle.Info.arguments()`（Java 9+）读子进程参数；Windows 上可能拿不到，用 OS API 兜底。
- **网络**：默认断网；需要联机时按域名/IP 白名单。

**Java 能做轮询，但抓不住会隐藏的进程；真正的监控是 OS 级（ETW / auditd / eBPF / Windows Defender）。**

### 14.6 隔离：唯一强的边界

- **只读 mods**：启动前校验，启动后挂只读 / copy-on-write；`JarPatcher` 写不进去。
- **独立用户**：游戏进程不能写启动器、manifest、其他实例。
- **容器 / VM**：文件系统、网络、进程空间都隔开；被感染就丢快照。
- **快照 / 回滚**：启动前快照，发现篡改就回滚。
- **默认断网**：单机不需要外联；恶意 mod 的 C2/下载器直接失效。

Java 可以编排这些（`ProcessBuilder`、容器 CLI），但**执行**是 OS 的事。Java 本身不是沙箱。

### 14.7 处置：quarantine，不是 delete

这一点很重要，也正好符合"别给它真删了，我还要拿去参考"：

- **隔离，不删除**：把可疑 jar 移到 `quarantine/`，记录哈希、文件名、检测原因、时间；**不执行**。
- **保留样本**：删除会毁掉证据；保留才能做静态分析、写 YARA、上报。
- **按哈希拉黑/放行**：同一哈希第二次直接拦；白名单同理。
- **回滚**：从干净快照恢复，而不是"删掉可疑文件继续跑"。
- **报告**：给用户看"哪个文件、为什么、建议怎么做"，不要静默处理。

### 14.8 一个具体的启动流程

```
1. Launcher 启动（代码签名 + HTTPS 自动更新 + 固定公钥）
2. 拉取签名 manifest（文件列表 + 哈希 + JVM 参数 + 允许的 agent）
3. 验签（固定公钥）→ 逐文件哈希
4. 静态扫描每个 jar（manifest / coremod / native / agent / 可疑字节码）
5. 不匹配 / 未知：
     → quarantine（不删）+ 展示 diff
     → 默认拒绝启动；用户坚持则进"沙箱模式"（只读 + 断网 + 快照）
6. 构建 JVM 参数：
     -XX:+DisableAttachMechanism
     禁 -javaagent / -Xbootclasspath/a / -agentlib
     最小 --add-opens
     专用低权用户
7. 启动游戏：mods 只读、默认断网、独立进程/容器
8. 监控：进程树、文件写入、jar 哈希、网络
9. 发现篡改：kill + 快照 + quarantine + 报告
```

### 14.9 反模式

| 反模式 | 为什么 |
|---|---|
| 哈希常量写在同一个 jar 里 | 改 jar 的人能一起改 |
| `SecurityManager` | 已废弃；挡不住 `Unsafe`/native |
| 自定义 `ClassLoader` 当沙箱 | 不是安全边界；`Unsafe`/`MethodHandles` 能逃 |
| 删除可疑 jar | 毁证据；应该 quarantine |
| 信任客户端自报 | 服务端要 nonce + 签名，不能信"我没改" |
| 用管理员权限"保护" | 攻击面更大 |
| 只按文件名/大小扫描 | 改名/加壳就绕过 |

### 14.10 结论：能保护到什么程度

**能保护的**：

- 已知的 Arcane Vortex 路径：自 attach（`DisableAttachMechanism`）、改 jar（只读）、`.bat` 重启（进程树）、`-javaagent`（参数控制）。
- 已知恶意样本：manifest 哈希 + 静态扫描 + 哈希拉黑。
- 未知样本：隔离 + 沙箱 + 断网 + 快照，把"中招"限制在一个可丢弃的实例里。

**保护不了的**：

- 能改启动器 / JRE / OS 的攻击者——信任锚被换掉了。
- 以管理员权限运行、且启动器没有 OS 级隔离的情况。
- 0-day / 高度混淆的样本——静态扫描只能提高成本。
- "病毒处理"本身：那是 AV/EDR 的活；Java 启动器能做的是编排扫描器和隔离，不是替代它们。

**一句话**：**启动器能把"同进程内无解"变成"进程外可管理"**——
校验、加固、隔离、监控、quarantine。它不是让恶意 mod 变安全，而是让它在**没有权限、没有网络、没有可写文件、没有可信启动参数**的环境里跑不起来。
信任锚必须在这个环境之外；否则你只是在和攻击者比谁改得快。

---

## 15. 加密影子血量：分离 getHealth + 自验证 + 加密字符串

> 这是一种"把血量从 vanilla 字段里搬出来"的思路：
> 不存 `float health`，存一段**带认证的密文**（AES-GCM / HMAC），`getHealth` 解密并验 tag，
> `setHealth` 重新加密写回；tag 不对 = 被改过。
> 它的定位是 **tamper-evident shadow state（可检测篡改的影子状态）**，不是 **security boundary（安全边界）**。

### 15.1 拆词

| 说法 | 实际含义 |
|---|---|
| **分离 getHealth** | Mixin / redirect `getHealth` / `setHealth`，读写的不是 `DATA_HEALTH_ID`，而是自定义 store |
| **自验证** | 密文带 MAC / GCM tag；解密失败或 tag 不匹配 = 被篡改 |
| **密码加密字符串存血** | 用密钥把 `float` 序列化成密文（Base64 字符串），存 NBT / `SavedData` / capability |

合起来：**血量虚拟化 + 认证加密的影子状态**。

### 15.2 它为什么可能有效

| 攻击 | 为什么被挡 |
|---|---|
| **generic field traversal**（如 Arcane Vortex 的 `Traversal` 扫"接近当前血/最大血的数值字段"） | 密文不是数值，扫不到；`getHealth` 解密出来的 `float` 是栈上的临时值，不是字段 |
| **直接写 NBT / 存档** | tag 不对就解不开；改一个字节就露馅 |
| **naive `setHealth` 锁** | 如果内部逻辑只用自己的 accessor、不调 vanilla `getHealth`，锁不到你的 store |
| **半吊子外挂 / 误改** | tag 不匹配能检测 |

### 15.3 它为什么不是安全边界

| 攻击 | 为什么挡不住 |
|---|---|
| **读 jar / 反编译** | 密钥/算法在 jar 里；能读就能解密/伪造 |
| **内存 dump** | 运行时密钥和明文都在堆里；能读进程就能拿 |
| **Mixin 优先级** | Arcane Vortex 用 `Integer.MAX_VALUE`；你压不过；它可以在 `getHealth` HEAD 覆盖/取消你的返回值 |
| **Agent retransform** | `AgentHealthTransformer` 在类加载后重写 `getHealth`/`setHealth`；mixin 是加载时，agent 是之后 |
| **`JarPatcher`** | 直接改你的 class；你的解密逻辑被替换 |
| **存档编辑器** | 如果密钥存在同一存档里，它不是秘密 |

**最关键的一条**：**MAC 不区分调用者。**
攻击者调你的 `setHealth(0)`，你的方法会正常加密、生成合法 tag、写回——从"数据完整性"角度看，这是一次合法写入。
所以加密只挡**旁路写**（字段/NBT），不挡**走 API 的写**；要挡后者，需要的是**语义校验**（影子账本）或**调用者校验**（栈/capability），不是密码学。

### 15.4 如果要做，怎么做才不坑

1. **用 AEAD，不要自己拼**：AES-GCM 或 HMAC-SHA256；`MessageDigest.isEqual` 做常量时间比较。
2. **密钥分级**：
   - 防离线改档：密钥放 OS keystore / 玩家口令派生（PBKDF2/Argon2），**不要**和密文放同一个存档；
   - 防进程内：没有真正的秘密，只能提高成本。
3. **不要每次 `getHealth` 都解密**：它调用极频繁；缓存明文 + 版本号，写时加密、tick/低频校验。
4. **保留 vanilla 字段做镜像**：别的 mod 会直接读 `DATA_HEALTH_ID` / 反射；不同步会破坏兼容。
5. **`getHealth` 和 `setHealth` 必须成对虚拟化**：只改一个会让 `actuallyHurt` / `isDeadOrDying` / `tickDeath` 不一致。
6. **失败策略**：tag 不对时返回什么？
   - 返回 0 → 攻击者可以"触发死亡"；
   - 返回 max → 攻击者可以"触发无敌"；
   - 返回 last-known-good + log 通常最好；
   - 不要抛异常/崩服。
7. **密钥不要用 `String`**：`byte[]` + 用完清零（只是降低堆 dump 窗口，不是防御）。
8. **性能**：AEAD 解密 + Base64 在每 tick 多次调用下很贵；先 profile。

### 15.5 它到底值不值得做

| 目标 | 值不值得 |
|---|---|
| 反 generic 字段扫描 | **值得**，成本可控，直接打掉 `Traversal` 这类"找 float"的攻击 |
| 反离线改档 | **值得**，前提是密钥不在存档里 |
| 反误改 / 半吊子外挂 | **值得**，tag 不匹配就是信号 |
| 反 Arcane Vortex / agent | **不值得**，挡不住；预算应放到启动器（§14） |
| 反编译级对手 | **不要自欺**：密钥在进程里，永远不是秘密 |

**一句话**：它是"把血量从'一个谁都能写的 float'变成'一段谁都能读、但旁路改了就露馅的密文'"。
**露馅**是它的价值；**挡不住 API 写和进程内攻击**是它的边界。

---

## 附录 A：验证过的 1.20.1 / Forge 47.4.20 源码事实

> 来源：`forge-1.20.1-47.4.20_mapped_official_1.20.1-sources.jar`（本机 Gradle 缓存）。
> 这些是后文所有层级判断的依据；如果你的实际运行版本不同，以实际 jar 为准。

### A.1 `LivingEntity.hurt` 的顺序（重要）

```java
public boolean hurt(DamageSource source, float amount) {
   if (!ForgeHooks.onLivingAttack(this, source, amount)) return false;  // ← 第一行
   if (this.isInvulnerableTo(source)) return false;
   else if (this.level().isClientSide) return false;
   else if (this.isDeadOrDying()) return false;
   else if (source.is(DamageTypeTags.IS_FIRE) && this.hasEffect(MobEffects.FIRE_RESISTANCE)) return false;
   ...
   if (this.invulnerableTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
       if (amount <= this.lastHurt) return false;
       this.actuallyHurt(source, amount - this.lastHurt);
       this.lastHurt = amount;
   } else {
       this.lastHurt = amount;
       this.invulnerableTime = 20;
       this.actuallyHurt(source, amount);
       ...
   }
   ...
   if (this.isDeadOrDying()) {
       if (!this.checkTotemDeathProtection(source)) { ... this.die(source); }
   }
}
```

**注意**：`ForgeHooks.onLivingAttack` 在 `isInvulnerableTo` **之前**。
本机 `47.4.10` / `47.4.20` / `47.4.23` 三个版本的 sources 都是这个顺序；
`docs/BYPASS-DESIGN.md` §2 写的「先 `isInvulnerableTo` 再事件」与源码不符（不影响该文"反取消无效"的结论，
因为反取消之后仍然会撞上 `isInvulnerableTo`）。
这个顺序的直接后果：**`LivingAttackEvent` 是任何 `hurt` 调用的最早公共钩子**，
包括 `/kill`（`kill()` → `hurt(genericKill, MAX_VALUE)`）。

### A.2 `actuallyHurt` 的顺序

```java
protected void actuallyHurt(DamageSource source, float amount) {
   if (!this.isInvulnerableTo(source)) {
      amount = ForgeHooks.onLivingHurt(this, source, amount);
      if (amount <= 0) return;
      amount = this.getDamageAfterArmorAbsorb(source, amount);
      amount = this.getDamageAfterMagicAbsorb(source, amount);
      float f1 = Math.max(amount - this.getAbsorptionAmount(), 0.0F);
      ...
      f1 = ForgeHooks.onLivingDamage(this, source, f1);
      if (f1 != 0.0F) {
         this.getCombatTracker().recordDamage(source, f1);
         this.setHealth(this.getHealth() - f1);
         ...
      }
   }
}
```

### A.3 `Entity.isInvulnerableTo`

```java
public boolean isInvulnerableTo(DamageSource source) {
   return this.isRemoved()
       || this.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !source.isCreativePlayer()
       || source.is(DamageTypeTags.IS_FIRE) && this.fireImmune()
       || source.is(DamageTypeTags.IS_FALL) && this.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE);
}
```

### A.4 `setHealth` 的 clamp 不拦 NaN

```java
public void setHealth(float health) {
   this.entityData.set(DATA_HEALTH_ID, Mth.clamp(health, 0.0F, this.getMaxHealth()));
}

// Mth.clamp(float, float, float)
return value < min ? min : Math.min(value, max);
```

`NaN < min` 为 false，`Math.min(NaN, max)` 为 NaN → **NaN 原样穿过**。
之后 `isDeadOrDying()` 是 `getHealth() <= 0.0F`，对 NaN 为 false → **永远不死**。
这是 MC 里最接近 MUGEN「溢出即死」的反向利用：不是秒杀，是**造出一个杀不死的实体**。

### A.5 `kill()` / `/kill`

```java
// LivingEntity
public void kill() { this.hurt(this.damageSources().genericKill(), Float.MAX_VALUE); }

// KillCommand
entity.kill();
```

`generic_kill` 在 vanilla 的 `bypasses_invulnerability`、`bypasses_armor`、`bypasses_resistance` 里（附录 A.7）。
所以 `/kill` 会穿过 `setInvulnerable(true)`，**但会经过 `LivingAttackEvent`**——
如果事件里不按 tag 排除，理论上可以取消 `/kill`。

### A.6 `die()` 的第一行是可取消事件

```java
public void die(DamageSource source) {
   if (ForgeHooks.onLivingDeath(this, source)) return;   // ← LivingDeathEvent 可取消
   if (!this.isRemoved() && !this.dead) { ... this.dead = true; ... }
}
```

**但 `setHealth(0)` 不会调用 `die()`**：它只写 `DATA_HEALTH_ID`。
对 mob 而言，`tickDeath()` 会在 `baseTick` 里被调用（`deathTime >= 20` 时 `remove(KILLED)`），
所以 `setHealth(0)` 是"20 tick 后静默消失"，**不发 `LivingDeathEvent`、不掉战利品**。
这是"写血"和"走管线"最关键的区别。

### A.7 vanilla damage type tag（1.20.1）

```
bypasses_invulnerability = [out_of_world, generic_kill]
bypasses_armor           = [on_fire, in_wall, cramming, drown, fly_into_wall, generic,
                            wither, dragon_breath, starve, fall, freeze, stalagmite,
                            magic, indirect_magic, out_of_world, generic_kill,
                            sonic_boom, outside_border]
bypasses_resistance      = [out_of_world, generic_kill]
bypasses_effects         = [starve]
bypasses_enchantments    = [sonic_boom]
```

`bypasses_cooldown` 是 Forge 加的字段（`DamageTypeTags.BYPASSES_COOLDOWN`），
数据文件在 Forge 自己的 jar 里，不在 vanilla `client-extra.jar` 里。

### A.8 `changeDimension` 有可取消钩子

```java
public Entity changeDimension(ServerLevel level, ITeleporter teleporter) {
   if (!ForgeHooks.onTravelToDimension(this, level.dimension())) return null;
   ...
}
```

`canChangeDimensions()` 是 `!isPassenger() && !isVehicle()`。
想做"场地锚定"的话，这两个都是合法入口。

### A.9 `setPos` 不做校验

```java
public void setPos(double x, double y, double z) {
   this.setPosRaw(x, y, z);          // position = new Vec3(x, y, z)
   this.setBoundingBox(this.makeBoundingBox());
}
```

`setPosRaw` 里会 `Mth.floor(x)` 算 blockPosition；NaN 经 `(int)Math.floor(NaN)` 变成 0，
于是 `position` 是 NaN、`blockPosition` 是 (0,0,0)，两者不一致。这是"位置污染"的入口。

---

## 附录 B：术语

| 术语 | 含义 |
|---|---|
| **論外** | 超出常规评价体系；早期指"绝对打不赢"，后来分"防御論外""攻击論外（内部/外部）" |
| **準論外** | 理论上能被击破、但需要专用对策的 |
| **神 / 神キャラ** | 以"击杀数"为评价的档位；默认常时无敌、开幕全屏即死 |
| **隔離** | 把"不跟常规同层玩"的技术/角色单独分档；也指用这些技术做非杀伤用途 |
| **専用対策** | 看对手 `name` 的指名攻击；神ランク默许，狂以下御法度 |
| **当身** | 反击/格挡反击（`ReversalDef`） |
| **混線** | 利用空 Target + Helper 夺取对手 Helper/Target 的 bug |
| **直死** | 直接写 Alive flag 的即死，防守方基本无解 |
| **超即死** | 利用 HitPause 中的缓冲区溢出让对手强制 KO |
| **親捏造** | 伪造 Helper 的父子关系，让对手系统认错人 |
| **%n / %f** | MUGEN 的格式化字符串漏洞：任意写 + GOT 劫持 + shellcode |
| **処理落ち** | 掉帧/卡顿；有时被当作战术 |
| **启动技术** | 最早能在哪个 RoundState 开始执行；分 R1 / R0 / 选人三档 |
| **应用技术** | 在启动技术之上，作者选择用它做什么（杀 / 防 / 演出） |
| **RoundState** | MUGEN 的回合阶段：0=黑屏/入场，1=战斗，2=KO，3=回合结束，4=比赛结束 |
| **512 超越** | 用 512+ state controller 的溢出/错误作为启动手段；上限 R1 |
| **DTC / ATC** | R0 黑屏期可启动的控制类技术（全称未确认） |
| **S 溢出** | Statedef 溢出；上限选人确认后的 CNS 加载期 |
| **DEF 溢出** | DEF 文件解析时的内存 bug；上限人物列表扫描期；平台依赖强 |
| **ZIP 溢出** | 引擎读取角色 zip 时的解析 bug；上限人物列表扫描期；依赖文件布局 |
| **roster order** | 人物列表/文件夹顺序；决定谁的 DEF 先被解析 |
| **match order** | 对战加载顺序；P1 的 CNS 先于 P2 |
| **路径重定向** | 把 DEF 的 `[Files]` 路径指到别处；用 canonicalize + 白名单防 |
| **上限 / 下限** | 启动技术决定的上限、应用技术决定的下限；强度是区间 |
| **L0–L5** | 本文的六层攻击面：数据 / Mod API / 字节码 / JVM / Agent / 进程 |

---

## 参考

- [即死攻撃 - ニコニコMUGENwiki](https://w.atwiki.jp/niconicomugen/pages/1002.html)
- [神キャラ - ニコニコMUGENwiki](https://w.atwiki.jp/niconicomugen/pages/18.html)
- [mugen凶恶研究(1)——%n和%f - CTF导航](https://www.ctfiot.com/107348.html)
- [七夜式 - 千寻](https://qxmugen.com/character/14846)
- [Tier 5: Null Tier (論外) - DJB MUGEN Community](https://mugen.doomjoshuaboy.com/pages/tier-5-null/)
- 本仓库：`docs/BYPASS-DESIGN.md`、`docs/CROSSMOD-HOOKS.md`、`docs/CURSEOF-PANDORA-SURVEY.md`
