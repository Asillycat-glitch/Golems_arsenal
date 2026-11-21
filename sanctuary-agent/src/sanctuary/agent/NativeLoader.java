package sanctuary.agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 从 agent jar 里取出自带的原生 hook（{@code /sanctuary-win-x86_64.arcane}）并装进进程。
 *
 * <p>和 AV 的 {@code jvmHook-*.arcane} 是同一种做法：原生库跟着 jar 走，运行时落到临时文件
 * 再 {@code System.load}，不要求用户装任何东西。加载后由 {@code Agent_OnAttach}/install
 * 把 JNI 函数表里的 {@code SetObjectField/SetFloatField/SetDoubleField} 换成我们的桩。</p>
 *
 * <p>原生库只依赖 jvm.dll，且所有失败都吞掉 —— 装不上时 Java 侧的防线照常工作。</p>
 */
public final class NativeLoader {

    private static final String[] CANDIDATES = {
            "/sanctuary-win-x86_64.arcane",
            "/native/sanctuary-win-x86_64.arcane"
    };

    private static boolean loaded;
    private static boolean installed;

    private NativeLoader() {
    }

    /** 由 SanctuaryAgent.start() 调用。 */
    public static synchronized void installQuietly() {
        if (loaded) {
            return;
        }
        try {
            if (!isWindows()) {
                GuardRuntime.log("[SanctuaryAgent] native hook not available on this OS");
                return;
            }
            Path dll = extract();
            if (dll == null) {
                GuardRuntime.log("[SanctuaryAgent] native payload missing from agent jar");
                return;
            }
            System.load(dll.toAbsolutePath().toString());
            loaded = true;
            // JNI 绑定的是本类的 native 方法；装表在 DLL 里做。
            int code = -1;  // 不调用 install()：不碰 JNI 表
            installed = false;  // 不装 JNI 表；只启用金库（内存防火墙）
            GuardRuntime.log("[SanctuaryAgent] native install code=" + code);
            if (code == 1) {
                int vault = vaultInit();
                int selftest = vault == 1 ? vaultSelfTest() : -1;
                GuardRuntime.log("[SanctuaryAgent] memory vault init=" + vault + " selftest=" + selftest);
                int caps = jvmtiProbe();
                GuardRuntime.log("[SanctuaryAgent] jvmti caps=0x" + Integer.toHexString(caps) + " redefine=" + ((caps & 1) != 0) + " retransform=" + ((caps & 2) != 0) + " fieldWatch=" + ((caps & 4) != 0));
            }
            GuardRuntime.log("[SanctuaryAgent] native hook loaded=" + loaded + " installed=" + installed);
        } catch (Throwable t) {
            GuardRuntime.log("[SanctuaryAgent] native hook failed: " + t);
        }
    }

    /** DLL 导出：Java_sanctuary_agent_NativeLoader_install。 */
    private static native int install();

    // ---- 内存防火墙（金库） ----
    private static native int vaultInit();
    private static native int vaultSelfTest();
    private static native int vaultTamper();

    /** JVMTI 能力探针：bit0=redefine, bit1=retransform, bit2=field watch. */
    private static native int jvmtiProbe();

    public static int vaultTamperCount() { try { return installed ? vaultTamper() : -1; } catch (Throwable t) { return -1; } }

    /** DLL 导出：被丢弃的 native 字段写次数。 */
    private static native int blocked();

    public static int blockedCount() {
        try { return installed ? blocked() : 0; } catch (Throwable t) { return 0; }
    }

    public static boolean isInstalled() {
        return installed;
    }

    private static Path extract() {
        for (String name : CANDIDATES) {
            try (InputStream in = NativeLoader.class.getResourceAsStream(name)) {
                if (in == null) {
                    continue;
                }
                Path tmp = Files.createTempFile("sanctuary-jvmhook-", ".dll");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
