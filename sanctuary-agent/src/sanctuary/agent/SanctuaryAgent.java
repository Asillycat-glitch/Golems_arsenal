package sanctuary.agent;

import java.lang.instrument.Instrumentation;
import java.util.Properties;

/**
 * Java agent entry point.
 *
 * <p>The agent is deliberately independent from the Forge mod: it runs in {@code premain}, gets
 * {@link Instrumentation} before modlauncher, and rewrites the "hard clear" entry points of
 * ArcaneVortex / toaru at class load. It does not use Mixin and does not touch the game classpath
 * at bootstrap time.</p>
 *
 * <p>Usage: {@code -javaagent:sanctuary-agent.jar}</p>
 */
public final class SanctuaryAgent {

    private static Instrumentation instrumentation;
    private static volatile boolean started;
    private static volatile Scrubber scrubber;

    public static Scrubber scrubber() {
        return scrubber;
    }

    private SanctuaryAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        try { appendBootstrapStub(inst); } catch (Throwable ignored) { }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-entered.txt"), "entered " + java.time.LocalDateTime.now() + " pid=" + ProcessHandle.current().pid()); } catch (Throwable ignored) { }
        start(args, inst);
    }

    private static void start(String args, Instrumentation inst) {
        try {
            startUnsafe(args, inst);
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            GuardRuntime.log("[SanctuaryAgent] START-FAILED " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                GuardRuntime.log("[SanctuaryAgent]   at " + e);
            }
        }
    }

    private static void startUnsafe(String args, Instrumentation inst) {
        
        if (!GuardRuntime.beginStart()) { GuardRuntime.log("[SanctuaryAgent] already started, skip"); return; }
instrumentation = inst;
        appendBootstrapStub(inst);
        Properties props = Config.load(args);
        GuardRuntime.init(props);
        scrubber = new Scrubber(inst, 2000);
        scrubber.start();
        GuardRuntime.log("[SanctuaryAgent] scrubber started");
        boolean patchObjects = "true".equalsIgnoreCase(
                props.getProperty("patch_object_returns", "false"));
        inst.addTransformer(new SanctuaryTransformer(patchObjects), true);
        // attach 之前已经加载的外来类不会经过 transformer，必须补一次 retransform：
        // 顺带把那些类上已经应用的第三方 mixin 剥掉（mixin 是 launcher plugin，不是 transformer）。
        retransformHostile(inst);
        // 把"可以开始周期重打了"的入口注册到 bootstrap 层的 GuardRuntime 上 —— mod 侧
        // 只能看到 GuardRuntime（bootstrap），看不到 SanctuaryAgent（系统类加载器）。
        GuardRuntime.setWatchStarter(SanctuaryAgent::armWatch);
        GuardRuntime.setTargetListener(SanctuaryAgent::armTargets);
        NativeLoader.installQuietly();
        // 2026-11-09：周期重打【不在这里】启动。加载期 retransform 会和类加载抢锁，
        // 而且 Entity/LivingEntity/DataItem 是 Mixin 大户，重定义会把所有 mod 的 mixin 一起剥掉。
        // 改成：只盯 AV 自己的 5 个类，并且由 mod 在 ServerStartedEvent 时调 armWatch() 启动。
        GuardRuntime.log("[SanctuaryAgent] installed (retransform="
                + inst.isRetransformClassesSupported() + ", patchObjectReturns=" + patchObjects + ")");
    }

    /**
     * 需要反复重打的关键类（点分名）。
     *
     * <p><b>只放 AV 自己的类</b>：这些类上的 mixin 是 AV 自己的，剥掉是目的。
     * 绝不能放 {@code Entity}/{@code LivingEntity}/{@code SynchedEntityData$DataItem}——
     * 重定义会把**所有** mod 在这三个类上的 mixin 一起剥掉（2026-11-09 实测：卡死在
     * "Mod Loading working"）。</p>
     */
    private static final java.util.Set<String> WATCH = java.util.Set.of(
            "com.luoee.arcanevortex.Help.HealthModifyHelp.HealthModify",
            "com.luoee.arcanevortex.Help.EntityKillerHelp",
            "com.luoee.arcanevortex.Content.Item.EntityRemover.RemoveHelp.MainRemove",
            "com.luoee.arcanevortex.Content.Item.VanSh.VanShCrusher",
            "com.luoee.arcanevortex.Content.Item.VanSh.VanShTool.VanShRadiantSun",
            "com.luoee.arcanevortex.Content.Item.VanSh.VanShTool.VanSh"
    );

    private static volatile boolean watchArmed;

    /** 调 AV 自己的 nativeClearRestoreCache（如果它的桥已经加载）。 */
    private static void wipeRestoreCache(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!"com.luoee.arcanevortex.NativeAgent.NativeAgentBridge".equals(c.getName())) {
                continue;
            }
            try {
                c.getMethod("nativeClearRestoreCache").invoke(null);
                GuardRuntime.log("[SanctuaryAgent] AV restore cache cleared");
            } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
                GuardRuntime.log("[SanctuaryAgent] clear restore cache failed: " + t);
            }
            return;
        }
    }

    /** mod 扫完 jar 推来新目标之后：把已加载的那些立刻 retransform 一遍。 */
    private static void armTargets() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            return;
        }
        try {
            java.util.List<Class<?>> batch = new java.util.ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (GuardRuntime.isTarget(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) {
                    batch.add(c);
                }
            }
            for (int i = 0; i < batch.size(); i += 40) {
                inst.retransformClasses(batch.subList(i, Math.min(i + 40, batch.size()))
                        .toArray(new Class<?>[0]));
            }
            // 废掉 AV 的 native 恢复缓存，并把"注册/重放"那两类也重打一遍。
            wipeRestoreCache(inst);
            java.util.Set<String> neutral = SanctuaryTransformer.neutralizeNames();
            int neutralized = 0;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (neutral.contains(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) {
                    try {
                        inst.retransformClasses(c);
                        neutralized++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            GuardRuntime.log("[SanctuaryAgent] neutralize retransformed=" + neutralized);
            GuardRuntime.log("[SanctuaryAgent] armTargets retransformed=" + batch.size()
                    + " totalTargets=" + GuardRuntime.targetCount());
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            GuardRuntime.log("[SanctuaryAgent] armTargets failed: " + t);
        }
    }

    /**
     * 拒绝恢复流程：用 AV 自己的 API 把恢复目标清空。
     *
     * <p>为什么不用 NEUTRALIZE：native 方法没有字节码，把 `nativeRetransformRestoreTargets`
     * 改成 `return` 是无效的；而 `registerClass` 的登记发生在我们的 attach 之前。
     * 正确的做法是调它自己的 `clear()` / `clearCache()`。</p>
     */
    private static void startRestoreKiller() {
        Thread thread = new Thread(() -> {
            boolean reported = false;
            while (true) {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Class<?> mgr = findLoaded("com.luoee.arcanevortex.NativeAgent.ClassRestoreManager");
                    if (mgr == null) {
                        continue;
                    }
                    mgr.getMethod("clear").invoke(null);
                    mgr.getMethod("clearCache").invoke(null);
                    int left = (int) mgr.getMethod("getRegisteredClassCount").invoke(null);
                    if (!reported || left > 0) {
                        GuardRuntime.log("[SanctuaryAgent] restore targets cleared, remaining=" + left);
                        reported = true;
                    }
                } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
                    if (!reported) {
                        GuardRuntime.log("[SanctuaryAgent] restore killer failed: " + t);
                        reported = true;
                    }
                }
            }
        }, "SanctuaryRestoreKiller");
        thread.setDaemon(true);
        thread.start();
        GuardRuntime.log("[SanctuaryAgent] restore killer armed (3s)");
    }

    private static Class<?> findLoaded(String dotted) {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            return null;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(dotted)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 按行为重打：不认对手名字，只认"我们的门类"。
     *
     * <p>小类 200ms 一轮、大类 1s 一轮 —— 目的是把"被恢复后的空窗"压到对手来不及杀人。
     * 这是对"换名字的恢复机制"的通用答案：我们不需要认识它，只需要比它快。</p>
     */
    private static void startFastRegate(Instrumentation inst) {
        Thread thread = new Thread(() -> {
            java.util.List<Class<?>> small = new java.util.ArrayList<>();
            java.util.List<Class<?>> big = new java.util.ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName().replace('.', '/');
                if (!inst.isModifiableClass(c)) {
                    continue;
                }
                if ("net/minecraft/util/ClassInstanceMultiMap".equals(n)
                        || "net/minecraft/world/level/entity/EntitySection".equals(n)) {
                    small.add(c);
                } else if ("net/minecraft/world/entity/Entity".equals(n)
                        || "net/minecraft/world/entity/LivingEntity".equals(n)) {
                    big.add(c);
                }
            }
            try { Thread.sleep(90000L); } catch (InterruptedException e) { return; }
            GuardRuntime.log("[SanctuaryAgent] fast regate armed small=" + small.size() + " big=" + big.size());
            long tick = 0;
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                tick++;
                try {
                    if (!small.isEmpty()) {
                        inst.retransformClasses(small.toArray(new Class<?>[0]));
                    }
                    if (tick % 10 == 0 && !big.isEmpty()) {
                        inst.retransformClasses(big.toArray(new Class<?>[0]));
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "SanctuaryFastRegate");
        thread.setDaemon(true);
        thread.start();
    }

    /** 原版关卡类单独一条慢线程：它们太大，1 秒重打会卡。 */
    private static void startVanillaRegate(Instrumentation inst) {
        try {
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(20000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        for (Class<?> c : inst.getAllLoadedClasses()) {
                            String n = c.getName().replace('.', '/');
                            if (SanctuaryTransformer.isVanillaGateClass(n) && inst.isModifiableClass(c)) {
                                inst.retransformClasses(c);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "SanctuaryVanillaRegate");
            thread.setDaemon(true);
            thread.start();
            GuardRuntime.log("[SanctuaryAgent] vanilla regate armed (20s)");
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            GuardRuntime.log("[SanctuaryAgent] vanilla regate failed: " + t);
        }
    }

    /** 由 mod 层在服务端启动完成后调用（反射入口），避免加载期重定义。 */
    public static void armWatch() {
        if (watchArmed) {
            return;
        }
        watchArmed = true;
        Instrumentation inst = instrumentation;
        if (inst != null) {
            // startRetransformWatch(inst);  // 打架：它 150ms 就 burst，我们追不上，先关
            // startFastRegate(inst);       // 打架：同上
            // startVanillaRegate(inst);    // 打架：同上
            // startFastRegate(inst);       // 打架：同上
            startRestoreKiller();
        } else {
            GuardRuntime.log("[SanctuaryAgent] armWatch without instrumentation");
        }
    }

    private static void startRetransformWatch(Instrumentation inst) {
        try {
            java.util.List<Class<?>> watched = new java.util.ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (WATCH.contains(c.getName()) && inst.isModifiableClass(c)) {
                    watched.add(c);
                }
            }
            GuardRuntime.log("[SanctuaryAgent] retransform watch armed classes=" + watched.size());
            if (watched.isEmpty()) {
                return;
            }
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        inst.retransformClasses(watched.toArray(new Class<?>[0]));
                    } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
                        GuardRuntime.log("[SanctuaryAgent] retransform watch failed: " + t);
                    }
                }
            }, "SanctuaryRetransformWatch");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            GuardRuntime.log("[SanctuaryAgent] retransform watch arming failed: " + t);
        }
    }

    private static void retransformHostile(Instrumentation inst) {
        try {
            java.util.List<Class<?>> batch = new java.util.ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String name = c.getName().replace('.', '/');
                if (SanctuaryTransformer.isHostile(name) && inst.isModifiableClass(c)) {
                    batch.add(c);
                }
            }
            for (int i = 0; i < batch.size(); i += 40) {
                inst.retransformClasses(batch.subList(i, Math.min(i + 40, batch.size()))
                        .toArray(new Class<?>[0]));
            }
            GuardRuntime.log("[SanctuaryAgent] retransformed hostile classes=" + batch.size());
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            GuardRuntime.log("[SanctuaryAgent] retransform failed: " + t);
        }
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /**
     * 把 GuardRuntime 单独塞进 bootstrap classpath。
     *
     * <p>补丁代码运行在 modlauncher 的 TransformingClassLoader 里，它看不到系统类加载器，
     * 直接引用 agent 里的 GuardRuntime 会 NoClassDefFoundError（2026-11-07 20:45 的服务端崩溃）。
     * bootstrap 是唯一所有加载器都可见的层；但整个 agent 含 shade 过的 ASM，塞进 bootstrap
     * 会和 Forge/ModLauncher 的 ASM 冲突，所以只放一个只含 GuardRuntime 的小 stub。</p>
     */
    private static void appendBootstrapStub(Instrumentation inst) {
        try (java.io.InputStream in = SanctuaryAgent.class.getResourceAsStream("/sanctuary-bootstrap.jar")) {
            if (in == null) {
                System.err.println("[SanctuaryAgent] bootstrap stub missing");
                return;
            }
            java.nio.file.Path stub = java.nio.file.Files.createTempFile("sanctuary-bootstrap-", ".jar");
            java.nio.file.Files.copy(in, stub, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            stub.toFile().deleteOnExit();
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(stub.toFile()));
            System.err.println("[SanctuaryAgent] bootstrap stub appended: " + stub);
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of("sanctuary-agent-fail.txt"), java.time.LocalDateTime.now() + "\n" + sw);
            } catch (Throwable ignored) { }
            System.err.println("[SanctuaryAgent] bootstrap stub failed: " + t);
        }
    }
}