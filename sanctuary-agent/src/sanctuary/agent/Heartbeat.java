package sanctuary.agent;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/**
 * 心跳自愈：每隔 N 秒把已经被我们改过的类再 retransform 一次。
 *
 * <p>为什么需要：ArcaneVortex 自带 {@code ClassRestoreManager} / {@code PristineSealer} /
 * {@code TailGuard}，会在后台把类还原成"原始字节码"，把我们的补丁剥掉。JVM 的
 * {@link Instrumentation} 没有"删除某个 transformer"的公开需求（即使能拿到实例），
 * 但只要我们还在 transformer 列表里，一次 retransform 就能把补丁重新贴回去。</p>
 *
 * <p>这不是"绝对防剥"，只是把"剥掉之后永久失效"变成"最多失效一个心跳周期"。
 * 真正的权威状态仍然在 mod 层的不变量（每 tick 对账）里——那一层不依赖注入存活。</p>
 */
public final class Heartbeat implements Runnable {

    private final Instrumentation inst;
    private final SanctuaryTransformer transformer;
    private final long periodMillis;
    private volatile boolean running = true;

    public Heartbeat(Instrumentation inst, SanctuaryTransformer transformer, int seconds) {
        this.inst = inst;
        this.transformer = transformer;
        this.periodMillis = Math.max(1, seconds) * 1000L;
    }

    public void start() {
        Thread t = new Thread(this, "SanctuaryAgent-Heartbeat");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(periodMillis);
                repatch();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                GuardRuntime.log("[SanctuaryAgent] heartbeat error: " + t);
            }
        }
    }

    private void repatch() {
        if (!inst.isRetransformClassesSupported()) {
            return;
        }
        Set<String> names = new HashSet<>(transformer.patchedClassNames());
        if (names.isEmpty()) {
            return;
        }
        Class<?>[] targets = new Class<?>[0];
        java.util.ArrayList<Class<?>> list = new java.util.ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (names.contains(c.getName()) && inst.isModifiableClass(c)) {
                list.add(c);
            }
        }
        targets = list.toArray(targets);
        if (targets.length == 0) {
            return;
        }
        try {
            inst.retransformClasses(targets);
        } catch (Throwable t) {
            GuardRuntime.log("[SanctuaryAgent] heartbeat retransform failed: " + t);
        }
    }

    public void stop() {
        running = false;
    }
}
