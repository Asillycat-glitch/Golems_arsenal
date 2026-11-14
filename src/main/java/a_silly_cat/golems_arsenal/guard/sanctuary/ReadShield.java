package a_silly_cat.golems_arsenal.guard.sanctuary;

import java.util.function.Supplier;

/**
 * 读侧掩护（"抓不到"）——线程级的读写隔离。
 *
 * <p>思路变了：之前一直在"拦写入"，实测证明拦不住（补丁打上了、方法没执行、值照样变）。
 * 换个方向：<b>不拦写，改成让对方读不到真实值</b>。</p>
 *
 * <p>实现是线程局部的：只有"进了我们作用域"的代码看得到真实数据
 * （{@link SanctuaryScopes} 的 internal / load / death 三个作用域，或者显式
 * {@link #runTrusted}）。其余所有代码——包括原版、包括别的模组、包括攻击者——
 * 通过 {@code getHealth()} / {@code getMaxHealth()} 拿到的都是 {@link #DECOY}。</p>
 *
 * <p>为什么这有用：这一类处决武器（{@code pulverize} / {@code Origin} /
 * {@code compassionateeverything} / 各种"目标血量低于我最大生命就秒杀"）判定的前提就是
 * <b>能读到目标血量</b>。读到 1e9 时，这些阈值分支全部不成立。</p>
 *
 * <p>为什么是线程级：作用域是 {@link ThreadLocal}，攻击方在别的线程补刀
 * （{@code AsynchronousRemove} 那种）天然拿不到真实值；同线程的写入仍然靠
 * {@link DataGuard} 每 tick 收敛兜底。</p>
 */
public final class ReadShield {

    /** 掩护值：大到任何"低于我"的阈值判定都不成立，又不会变成 Infinity 引起 NaN。 */
    public static final float DECOY = 1.0E9F;

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private static long hiddenReads;
    private static long trustedReads;

    private ReadShield() {
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /**
     * 当前是不是"可信读者"。
     *
     * <p>三个来源：显式作用域、读档/死亡栈（这两个是原版自己在算，必须看到真值）、
     * 以及本模组内部写（{@code runInternal}）。</p>
     */
    public static boolean trusted() {
        return DEPTH.get()[0] > 0
                || SanctuaryScopes.inInternal()
                || SanctuaryScopes.inLoad()
                || SanctuaryScopes.inDeath();
    }

    public static void runTrusted(Runnable body) {
        enter();
        agentEnter();
        try {
            body.run();
        } finally {
            agentExit();
            exit();
        }
    }

    public static <T> T callTrusted(Supplier<T> body) {
        enter();
        agentEnter();
        try {
            return body.get();
        } finally {
            agentExit();
            exit();
        }
    }

    // ---- agent 侧线程标志 ----
    //
    // agent 的 readFloat/readObject 用的是它自己的 ThreadLocal；mod 这边进入可信作用域时
    // 必须同步告诉它，否则我们自己读血也会被掩护成 1e9。
    private static java.lang.reflect.Method agentEnterMethod;
    private static java.lang.reflect.Method agentExitMethod;
    private static boolean agentLookupDone;

    private static void agentEnter() {
        try {
            java.lang.reflect.Method m = agentEnterMethod();
            if (m != null) {
                m.invoke(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void agentExit() {
        try {
            java.lang.reflect.Method m = agentExitMethod();
            if (m != null) {
                m.invoke(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Method agentEnterMethod() {
        lookupAgent();
        return agentEnterMethod;
    }

    private static java.lang.reflect.Method agentExitMethod() {
        lookupAgent();
        return agentExitMethod;
    }

    private static void lookupAgent() {
        if (agentLookupDone) {
            return;
        }
        agentLookupDone = true;
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            agentEnterMethod = runtime.getMethod("trustEnter");
            agentExitMethod = runtime.getMethod("trustExit");
        } catch (Throwable ignored) {
            // agent 没挂：只有 mod 侧的 trusted() 生效
        }
    }

    public static void noteHidden() {
        hiddenReads++;
    }

    public static void noteTrusted() {
        trustedReads++;
    }

    public static String statusLine() {
        return "shield=" + (hiddenReads + trustedReads == 0 ? "idle"
                : "hidden=" + hiddenReads + " trusted=" + trustedReads)
                + " decoy=" + DECOY;
    }

    public static void reset() {
        hiddenReads = 0;
        trustedReads = 0;
    }
}
