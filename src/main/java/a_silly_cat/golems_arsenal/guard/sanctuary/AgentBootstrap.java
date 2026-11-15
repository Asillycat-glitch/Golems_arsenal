package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Self-attaching bootstrap for {@code sanctuary-agent.jar}.
 *
 * <p>ArcaneVortex 0.7.10 的 {@code NativeAgent} 不走 {@code -javaagent}：它在 mod 启动时
 * 自己 attach 到当前 JVM 再 {@code loadAgent}。这里用同样的机制，好处是不需要改 PCL 的
 * JVM 参数——mod 自带 agent。</p>
 *
 * <p>步骤：把打进 mod jar 的 {@code /sanctuary-agent.jar} 释放到临时文件 →
 * {@code jdk.attach.allowAttachSelf=true} → {@code VirtualMachine.attach(pid)} →
 * {@code loadAgent(temp)} → {@code detach}。随后通过系统类加载器拿到 agent 里的
 * {@link Instrumentation}，对**已经加载**的目标类做一次 retransform（晚 attach 也能补上）。</p>
 *
 * <h2>2026-11-08 修：读配置的时机</h2>
 *
 * <p>旧实现在 mod 构造函数里直接 {@code Config.SANCTUARY_AGENT.get()}。但是
 * {@code registerConfig} 在构造函数<b>末尾</b>才调用，Forge 真正加载文件还要更晚，而
 * {@code ForgeConfigSpec.ConfigValue#get()} 在 {@code spec.childConfig == null} 时
 * <b>直接返回默认值</b>（{@code if (spec.childConfig == null) return defaultSupplier.get();}）。
 * 于是：文件里 {@code agent_enabled = true}，日志却打印
 * {@code agent disabled by config (agent_enabled=false)}——读到的是默认值 {@code false}，
 * 而且 {@code done = true} 已经置上，之后永远不会再试。</p>
 *
 * <p>现在改成：</p>
 * <ul>
 *   <li>构造函数里的调用只是"<b>提前试一次</b>"，配置没加载就只记一行 defer，<b>不置任何终态</b>；</li>
 *   <li>真正的安装在 {@link ModConfigEvent}（config 已 loaded）里做；</li>
 *   <li>日志把 {@code spec.isLoaded()} / 读到的值 / jar 是否找到都打出来，避免再出现
 *       "看着是关的其实是没加载"的误判；</li>
 *   <li>{@code disabled} 不锁死：之后 reload 把开关改成 true 仍会尝试 attach。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AgentBootstrap {

    /** attach 成功之后才置 true；失败/关闭都不置，允许后续 config reload 再试。 */
    private static boolean installed;
    private static boolean loggedDefer;
    private static boolean loggedDisabled;

    private AgentBootstrap() {
    }

    /**
     * config 事件入口。{@code ModConfigEvent} 只在<b>文件真的加载/重载之后</b>才发，
     * 所以这是第一次能读到文件值的时机。
     */
    @SubscribeEvent
    public static void onModConfig(ModConfigEvent event) {
        try {
            if (event.getConfig().getModId().equals(Golems_arsenal.MODID)
                    && event.getConfig().getType() == ModConfig.Type.COMMON) {
                install();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 尝试安装自带的 agent。
     *
     * <p>可以在构造函数里当"早鸟"调用（此时只 defer），也可以在 config 加载后调用。</p>
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        boolean loaded;
        try {
            loaded = Config.SPEC.isLoaded();
        } catch (Throwable t) {
            loaded = false;
        }
        if (!loaded) {
            if (!loggedDefer) {
                loggedDefer = true;
                Golems_arsenal.LOGGER.info(
                        "[Sanctuary] agent: config not loaded yet, deferring to ModConfigEvent (early call ignored)");
            }
            return;
        }
        boolean enabled;
        try {
            enabled = Config.SANCTUARY_AGENT.get();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] agent: reading agent_enabled failed: {}: {}",
                    t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
            return;
        }
        if (!enabled) {
            if (!loggedDisabled) {
                loggedDisabled = true;
                // 这里打印的是"文件里读到的值"，不再是 spec 默认值。
                Golems_arsenal.LOGGER.info(
                        "[Sanctuary] agent disabled by config (loaded=true, file value agent_enabled=false)");
            }
            return;
        }
        Golems_arsenal.LOGGER.info(
                "[Sanctuary] agent enabled (loaded=true, file value agent_enabled=true), attaching...");
        try {
            Path jar = extract();
            if (jar == null) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] agent jar missing from mod resources; skipping self-attach");
                return;
            }
            boolean ok = trySelfAttach(jar);
            if (!ok) {
                // jdk.attach.allowAttachSelf 是 JVM 启动时读进 saved properties 的，
                // 运行期 System.setProperty 不生效；外部进程 attach 不需要任何参数。
                Golems_arsenal.LOGGER.info(
                        "[Sanctuary] agent self-attach unavailable (allowAttachSelf is startup-only), trying external attach");
                ok = tryExternalAttach(jar);
            }
            if (!ok) {
                Golems_arsenal.LOGGER.warn(
                        "[Sanctuary] agent NOT attached. Add JVM arg -Djdk.attach.allowAttachSelf=true, "
                                + "or check the external attach log line above");
                return;
            }
            installed = true;
            Golems_arsenal.LOGGER.info("[Sanctuary] agent attached from {}", jar);
            retransformLoaded();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] agent self-attach failed: {}: {}",
                    t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
        }
    }

    /** 供 {@code /ga guard status} 之类读：agent 到底装上了没有。 */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * 服务端启动完成后再让 agent 开始"周期重打"。
     *
     * <p>为什么不在 attach 时就开始：加载期 retransform 会和类加载抢锁（实测卡死在
     * "Mod Loading working"），而且我们的 watch 列表里现在只有 AV 自己的类，
     * 服务端起来之后再打完全够用。</p>
     */
    public static void armWatch() {
        try {
            // 只能走 GuardRuntime：它在 bootstrap 层，mod 的类加载器看得见。
            // SanctuaryAgent 在系统类加载器里，直接 Class.forName 会 ClassNotFoundException。
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            runtime.getMethod("requestArmWatch").invoke(null);
            Golems_arsenal.LOGGER.info("[Sanctuary] agent retransform watch requested");
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] armWatch failed: {}", t.toString());
        }
    }

    /**
     * 把"扫 jar 得到的可疑类"推给 agent（数据驱动名单）。
     *
     * <p>这些类只按"引用了哪些原语"选出来，不看是哪个 mod —— 所以不存在"针对某个模组"。</p>
     */
    private static volatile boolean targetsPushed;

    /** 手动豁免：把 UUID 推给 agent 的统一豁免表。 */
    public static void setExempt(java.util.UUID uuid, boolean exempt) {
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            runtime.getMethod("setExempt", java.util.UUID.class, boolean.class)
                    .invoke(null, uuid, exempt);
            Golems_arsenal.LOGGER.info("[Sanctuary] exempt {} = {}", uuid, exempt);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] setExempt failed: {}", t.toString());
        }
    }

    public static void pushTargets(java.util.Collection<String> dotNames) {
        if (targetsPushed) { return; }
        targetsPushed = true;
        if (dotNames == null || dotNames.isEmpty()) {
            return;
        }
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            runtime.getMethod("addTargets", String[].class)
                    .invoke(null, (Object) dotNames.toArray(new String[0]));
            Golems_arsenal.LOGGER.info("[Sanctuary] pushed {} data-driven target class(es)", dotNames.size());
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] pushTargets failed: {}", t.toString());
        }
    }

    private static Path extract() {
        try (InputStream in = AgentBootstrap.class.getResourceAsStream("/sanctuary-agent.jar")) {
            if (in == null) {
                return null;
            }
            Path tmp = Files.createTempFile("sanctuary-agent-", ".jar");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 自 attach：只有在启动参数里给了 {@code -Djdk.attach.allowAttachSelf=true} 才会成功。 */
    private static boolean trySelfAttach(Path jar) {
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class)
                    .invoke(null, Long.toString(ProcessHandle.current().pid()));
            try {
                vmClass.getMethod("loadAgent", String.class, String.class)
                        .invoke(vm, jar.toAbsolutePath().toString(), "");
            } finally {
                try {
                    vmClass.getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                }
            }
            Golems_arsenal.LOGGER.info("[Sanctuary] agent self-attach ok");
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Golems_arsenal.LOGGER.info("[Sanctuary] agent self-attach failed: {}: {}",
                    cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()));
            return false;
        }
    }

    /**
     * 外部 attach：把 {@link ExternalAttacher} 的 class 从 mod jar 释放到临时目录，用同一个 java
     * 起一个子进程 attach 本进程。实测不需要任何 JVM 参数。
     */
    private static boolean tryExternalAttach(Path agentJar) {
        try {
            String javaExe = ProcessHandle.current().info().command().orElse(null);
            if (javaExe == null || javaExe.isBlank()) {
                boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
                javaExe = Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString();
            }
            Path dir = Files.createTempDirectory("ga-attacher-");
            Path cls = dir.resolve("a_silly_cat/golems_arsenal/guard/sanctuary/ExternalAttacher.class");
            Files.createDirectories(cls.getParent());
            try (InputStream in = AgentBootstrap.class.getResourceAsStream(
                    "/a_silly_cat/golems_arsenal/guard/sanctuary/ExternalAttacher.class")) {
                if (in == null) {
                    Golems_arsenal.LOGGER.warn("[Sanctuary] agent external attach: attacher class missing from jar");
                    return false;
                }
                Files.copy(in, cls, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe,
                    "-cp", dir.toString(),
                    ExternalAttacher.class.getName(),
                    Long.toString(ProcessHandle.current().pid()),
                    agentJar.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            Golems_arsenal.LOGGER.info("[Sanctuary] agent external attach output: {}", out.isEmpty() ? "<empty>" : out);
            return finished && out.contains("EXTERNAL_ATTACH_OK");
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] agent external attach failed: {}: {}",
                    t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
            return false;
        }
    }

    /** C2b：不再按硬编码目标类补 retransform；agent 自己按 mod 类预筛处理。 */
    private static void retransformLoaded() {
        // no-op：agent 的 retransformHostile 已经遍历所有 mod 类。
    }
}
