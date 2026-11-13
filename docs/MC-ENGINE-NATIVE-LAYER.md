# MC 引擎的"原生层"：DLL / JNI / Java Agent / Coremod，以及它们和"代码冲突"的关系

> 缘起：有制作经验的人说"部分 mod 会用 DLL 注入来防止代码冲突导致的崩溃"。
> 我把这个包（219 个 jar）里的 native 文件、`Premain-Class`/`Agent-Class`、`FMLCorePlugin`、
> MixinConfigPlugin 全扫了一遍，并把 `jzyyfix` 的 agent 反编译了。
>
> **结论先行**：这句话"结果对、名字错"。
> 这个包里确实有 mod 在用**运行时类重定义**来修冲突/崩溃，但机制是
> **Java Instrumentation agent（自 attach + `Instrumentation` + Javassist + `retransform/redefine`）**，
> 不是经典意义上的"外部 DLL 注入"。真正的 DLL 注入解决不了 Mixin 冲突，反而更容易把游戏搞崩。

---

## 0. 先分清六层

| 层 | 机制 | 入口 | 能改什么 | 能"防冲突"吗 |
|---|---|---|---|---|
| L0 | 数据包 / 配置 | `data/`、`config/` | 配方、tag、战利品、部分数值 | 只能避开"数据层面"的冲突 |
| L1 | Mod API | 事件、注册表、`setHealth` | 游戏状态 | 不能改别人的类，只能"让着别人" |
| L2 | Mixin / ASM / Coremod | `@Mixin`、`IClassTransformer`、`FMLCorePlugin` | 别人的字节码 | **可以**，但要用 Mixin 插件/`require=0`/priority |
| L3 | JVM 内部 | 反射、`Unsafe`、`MethodHandles`、隐藏类 | 字段、类指针、私有方法 | 可以"绕过"冲突，不是"解决" |
| L4 | Java Agent / JVMTI | `-javaagent`、`VirtualMachine.attach`、`Instrumentation` | 任意类的字节码、重定义/重转换 | **可以**，但很危险 |
| L5 | JNI / JNA | `System.load`、`Native.load`、native 方法 | 进程内任意内存/系统调用 | 不能解决 Java 层冲突 |
| L6 | 外部 DLL 注入 | `CreateRemoteThread` + `LoadLibrary` | 整个进程 | 更不能；这是外挂/反作弊/DRM 的领域 |

**关键区别**：
- L4 的 `Instrumentation` 是 **JVM 官方提供的类重定义 API**；它知道类名、方法名、字节码。
- L6 的 DLL 注入是 **操作系统层面的"把代码塞进进程"**；它不知道 Mixin、不知道 refmap、不知道优先级。
- 两者都能"改代码"，但只有 L4 能相对安全地做"Java 层兼容补丁"。

---

## 1. 这个包里的实证

### 1.1 native 文件扫描（只列非 profiler 的）

| Jar | native | 用途（按代码/命名） |
|---|---|---|
| `jzyy-1.1078fix.jar` | `bluescreen-win-jna.dll` | JNA 接口 `BlueScreen.triggerBlueScreen(boolean)`——**蓝屏** |
| `jzyyfix-0.0.5.6ex.jar` | `plz/lizi/objhandles/instimpl-x64.dll`、`instimpl-x86.dll` | 自定义 `Instrumentation` 的 native 实现（`redefineClass0`/`retransformClass0`/`getAllLoadedClasses0`） |
| `super-steve-1.7.1.3shit.jar` | `plz/lizi/supersteve/api/renderentity-x64.dll`、`x86.dll` | 渲染实体用的 JNI（`SSModEntityRenderers` 里 `System.load`） |
| `spark-1.10.53-forge.jar` | `libasyncProfiler.so`、`attach_hotspot_windows.dll` | 性能分析器（正常） |

其余 jar 只命中 `MixinConfigs`（正常），没有 native 文件。

### 1.2 `jzyyfix` 的 manifest：它是一个 Java Agent

```
Premain-Class: plz.lizi.jzyyfix.JzyyTestAgent
Agent-Class: plz.lizi.objhandles.AgentCallback
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

`Premain-Class` 意味着它可以被 `-javaagent:jzyyfix.jar` 启动；
`Agent-Class` 意味着它可以被 **attach** 到已经运行的 JVM。

### 1.3 自 attach：`HandleBase.init()`

```java
public static void init() {
    Field f = Class.forName("sun.tools.attach.HotSpotVirtualMachine")
                   .getDeclaredField("ALLOW_ATTACH_SELF");
    PLZBase.UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), true);
    VirtualMachine vm = VirtualMachine.attach(String.valueOf(ProcessHandle.current().pid()));
    vm.loadAgent(PLZBase.getJarPath());   // 把自己的 jar 作为 agent 加载
    vm.detach();
}
```

**这是"运行中给自己挂 agent"**：不需要启动参数，mod 在构造器里就能拿到 `Instrumentation`。
拿到之后：

```java
HandleBase.INST.retransformClasses(clazz);                       // 重新转换
HandleBase.INST.redefineClasses(new ClassDefinition(clazz, ...)); // 直接重定义
```

`JzyyFixConfig` 里的 transformer 会按类名/方法名匹配，然后用 Javassist 改方法体：

- `Block.canHarvestBlock(...)`——修 MCR 方块挖掘；
- `tickBlock(BlockPos)`——限制加速火把；
- `execute(Event, Entity)`——修"勇者传说"的雷霆方块直接击杀实体；
- `net.mcreator.organizers.procedures.QuakeOnEffectActiveTickProcedure#setAngles(float,float,float)`——`enableOrganEffFix` 的 organizer 效果修复；
- `AttributeInstance`——**"实体血量暴增修复"**（`enableHealthBoostFix`）。

**所以"防止代码冲突导致的崩溃"的真相是**：
不是 DLL 注入，而是 **Java agent + Instrumentation + Javassist 的运行时补丁**。
它针对的是"某个 mod 的方法在某个环境下会炸/会暴增"的具体问题，不是通用的"Mixin 冲突解决器"。

### 1.4 `InstrumentationImpl`：自己实现 `java.lang.instrument.Instrumentation`

`jzyyfix` 还带了一个 350 行的 `InstrumentationImpl implements Instrumentation`：

```java
private static void loadLibraryFromJar(String dllName) throws IOException {
    Path tempFile = Files.createTempFile(..., ".dll");
    Files.copy(..., tempFile, REPLACE_EXISTING);
    Thread.sleep(200L);
    System.load(tempFile.toAbsolutePath().toString());   // 加载 instimpl-x64.dll
}

public void retransformClasses(Class<?>... classes) {
    for (Class<?> klass : classes) retransformClass0(klass);   // native
}

public void redefineClasses(ClassDefinition... defs) {
    for (ClassDefinition d : defs) redefineClass0(d.getDefinitionClass(), d.getDefinitionClassFile()); // native
}
```

也就是说：**它把 JVMTI 的类重定义能力用 native DLL 自己实现了一遍**。
这不是"注入一个 DLL 去防冲突"，而是"用 native 实现 Instrumentation 的底层调用"。
`HandleBase.init()` 优先用自 attach 拿到的"真" `Instrumentation`；拿不到才退回这个 native 实现。

### 1.5 `BlueScreen`：这不是防冲突，这是蓝屏

```java
public interface BlueScreen extends Library {
    BlueScreen INSTANCE = (BlueScreen) Native.load("bluescreen-win-jna.dll", BlueScreen.class);
    void triggerBlueScreen(boolean var1);
    int triggerBlueScreenWithStatus(boolean var1);
}
```

`jzyy` 里有一个 `com.csdy.jzyy.ms.BlueScreen`，用 JNA 加载 `bluescreen-win-jna.dll`，
暴露 `triggerBlueScreen` / `triggerBlueScreenWithStatus`。
我在已反编译的 `jzyy` 子集里没找到调用点（可能在未反编译的类里，也可能是休眠/彩蛋），
但**一个游戏 mod 携带"触发 Windows 蓝屏"的 native 接口，本身就是恶意软件级的红旗**。

### 1.6 `super-steve` 的 `renderentity`：普通 JNI

```java
String libName = "renderentity-x" + (is64 ? "64" : "86") + ".dll";
Path tempFile = Files.createTempFile(...);
Files.copy(inputStream, tempFile, REPLACE_EXISTING);
System.load(tempFile.toAbsolutePath().toString());
```

这是渲染用的 JNI，不是注入，也不是防冲突。

---

## 2. 为什么"DLL 注入"防不了 Mixin 冲突

Mixin 冲突的本质是 **Java 层的"同一个注入点被多个 transformer 改"**：

- 两个 `@Inject(at = @At("HEAD"))` 抢同一个方法；
- `@Redirect` 和 `@Inject` 抢同一个调用；
- `@Overwrite` 和别的 `@Inject` 冲突；
- `refmap`/`remap` 在开发/生产环境对不上；
- `require`/`expect` 数量不匹配 → `Mixin apply failed`；
- 类加载顺序/`priority` 导致 `NoSuchMethodError`。

这些冲突需要：
1. **知道目标类/方法/描述符**；
2. **知道其他 transformer 的注入点和优先级**；
3. **能在 Mixin 的框架内调整顺序**（`priority`、`MixinConfigPlugin`、`require=0`）。

DLL 注入只知道"内存地址/字节"，不知道这些语义。它可以：
- 在类加载前把字节码整体替换掉（但你怎么保证不覆盖别人的 Mixin？）；
- 在运行时改内存（但 Java 的类元数据、JIT、去优化不是随便改的）；
- hook JVM 的 `DefineClass`（这已经是 JVMTI/agent 的领域，不是"注入 DLL"）。

**结论**：DLL 注入不是"防冲突"，它是"绕过 Java 层直接改"。它可能让某个崩溃"看起来没了"，
但下一个冲突会更难查，而且它是版本/OS/arch 锁死的——换个 JVM 小版本就可能崩。

---

## 3. 真正防冲突的工具（按推荐顺序）

1. **`MixinConfigPlugin`**：按 `ModList.get().isLoaded(...)` 决定要不要应用这组 mixin。
   这个包里的正面例子：
   - `ticex` 有 7 个插件：`ticex.apotheosis.mixins.json`、`ticex.create.mixins.json`、
     `ticex.curios.mixins.json`、`ticex.draconicevolution.mixins.json`、`ticex.mekanism.mixins.json`、
     `ticex.projecte.mixins.json`、`ticex.slashblade.mixins.json`；
   - `Tinkers Domain` 的 `csdy.mixins.json`；
   - 本仓库的 `GolemMagickaMixinPlugin` 是同一个思路。
2. **`require = 0` / `expect = 0`**：目标方法不存在时静默跳过，而不是崩。
3. **`@Pseudo`**：目标类不存在时跳过。
4. **`priority`**：让自己的注入排在其他 mod 之前/之后，而不是抢默认值。
5. **`ModList.get().isLoaded` + `IExtensionPoint`**：可选依赖门控。
6. **`@Mod` 依赖声明**：`mandatory=false`、`ordering=AFTER`、`versionRange`。
7. **JarJar / SecureJar**：把依赖隔离到独立 module，减少类冲突。
8. **`RuntimeDistCleaner` / `Dist`**：别把客户端类带到服务端。

**不要用**：
- DLL 注入；
- `priority = Integer.MAX_VALUE` 的全局 `setHealth`/`addEffect` mixin（这个包里就有）；
- 运行时 `redefineClasses` 去改 vanilla 的核心方法（除非你非常清楚后果）。

---

## 4. 检测与防守

### 4.1 扫描清单（服务端/整合包作者）

对每个 jar 检查：

| 检查项 | 怎么查 | 风险 |
|---|---|---|
| `Premain-Class` / `Agent-Class` | 读 `META-INF/MANIFEST.MF` | 可在启动/运行时挂 agent |
| `Can-Redefine-Classes` / `Can-Retransform-Classes` | 同上 | 可运行时改类 |
| `FMLCorePlugin` / `ForceLoadAsMod` | 读 `META-INF/mods.toml` | 启动期 coremod |
| `.dll` / `.so` / `.dylib` / `.exe` | 列 jar entries | native 代码 |
| `MixinConfigs` + `"plugin"` | 读 `mixins.*.json` | 正常，但要知道它门控了什么 |
| `VirtualMachine.attach` / `loadAgent` | 反编译/字符串扫描 | 自 attach |
| `System.load` / `Native.load` | 反编译/字符串扫描 | JNI/JNA |
| `Unsafe` / `defineHiddenClass` / `klassPtr` | 反编译/字符串扫描 | JVM 内部操作 |

这个包里命中的：
- `jzyyfix`：`Premain`/`Agent` + `Can-Redefine`/`Can-Retransform` + `instimpl` DLL + `VirtualMachine.attach`；
- `jzyy`：`bluescreen-win-jna.dll` + JNA `BlueScreen`；
- `super-steve`：`renderentity` JNI + `Unsafe` + `defineHiddenClass`；
- `spark`：asyncProfiler（正常）；
- `ticex` / `Tinkers Domain`：MixinConfigPlugin（正常）。

### 4.2 防守

- **进程内防不住**：一个能自 attach agent / 加载 native DLL 的 mod，在同一 JVM 里可以为所欲为。
- **服务端权威**：客户端装什么，服务端管不了；服务端只信自己的逻辑。
- **沙箱/VM**：真要测这些包，放虚拟机、快照、断网。
- **不要装**：这是最终答案。
- **对自己的 mod**：用 Mixin 插件 + `require=0` + `priority`，不要 native，不要 `Unsafe`。

---

## 5. 一句话总结

- "DLL 注入防代码冲突"——**结果是真的，名字是错的**：
  这个包里真正做"防冲突/防崩"的是 **Java Agent + Instrumentation + Javassist 的运行时重定义**（`jzyyfix`），
  不是外部 DLL 注入。
- DLL 注入解决不了 Mixin 冲突；它只会把 Java 层的冲突变成 native 层的崩溃。
- 这个包真正的红旗不是"防冲突"，而是 **`bluescreen-win-jna.dll`**：
  一个游戏 mod 携带"触发 Windows 蓝屏"的 native 接口，这已经超出"游戏机制"的范畴。
- 如果你要防的是"mod 冲突导致的崩溃"，正确工具是 **MixinConfigPlugin + `require=0` + priority + 可选依赖门控**，
  不是 DLL 注入，也不是 `priority = Integer.MAX_VALUE` 的全局 mixin。

---

## 参考

- 本仓库：`docs/THREAT-MODEL-MUGEN.md`、`docs/CODE-STUDY-POWER-MOBS.md`、`docs/PACK-SURVEY-TINKERS-WARFARE.md`
- 反编译产物：`build/decomp/jzyyfix/`、`build/decomp/jzyy/`、`build/decomp/supersteve/`
- 扫描产物：`build/tmp-mods.tsv`、`build/tmp-scan.tsv`
