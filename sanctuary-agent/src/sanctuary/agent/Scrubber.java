package sanctuary.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用"墓碑清扫"：把受保护实体的 UUID / 实体对象从所有外来静态集合里摘掉。
 *
 * <p>为什么需要它：清除类攻击通常会在**自己的静态表**里给目标登记一个墓碑
 * （{@code Map<UUID,Boolean> DEAD}、{@code Set<UUID> ERASED}、{@code List<Entity> PENDING}），
 * 之后所有"这个实体死了吗""这个实体能加回来吗"的判断都读这张表。它们不发事件、不调 vanilla 方法，
 * 方法级 hook 永远看不见。</p>
 *
 * <p>本类<b>不认任何 mod id / 类名</b>，只认两件事：</p>
 * <ol>
 *   <li>这个 static 字段是不是 {@code Map}/{@code Collection}；</li>
 *   <li>里面的 key/value 是不是"我们保护的 UUID"，或者其 {@code getUUID()} 命中。</li>
 * </ol>
 * 命中就删。删的是"记录"，不是"实体"；实体本身由 mod 层的 Anchor 负责重建/重注册。</p>
 *
 * <p>运行在 bootstrap 层，<b>不能 import 任何 Minecraft 类型</b>，全部走反射。</p>
 */
public final class Scrubber implements Runnable {

    /**
     * 明确放过的类前缀。
     *
     * <p>YSM（是，史蒂夫模型）的类在静态初始化里会碰客户端状态：Scrubber 是 daemon
     * 线程，`field.get(null)` 触发它的类初始化时，会把 `AbstractClientPlayer` 初始化到
     * 错误线程并 NPE，随后整个客户端在 Alt+Y 打开 GUI 时崩。这里只做“放过”，不做通用
     * 客户端类过滤；要加别的模组时继续往这张白名单里加。</p>
     */
    private static final String[] SKIP_PREFIXES = {
            "com.elfmcys.yesstevemodel.",
            "com.elfmcys.yes_steve_model.",
            // Embeddium/Sodium + Oculus/Iris：这些类带 mixin post-apply 插件，
            // getDeclaredFields() 触发的类加载在 Scrubber 线程上会拿到 null context loader，
            // 把 PostprocessorMixin 打成 FATAL。Scrubber 不碰渲染栈。
            "me.jellysquid.mods.sodium.",
            "org.embeddedt.embeddium.",
            "net.irisshaders.",
            "net.coderbot.iris.",
            // L2 框架 / Modular Golems：宿主与能力系统自身不是墓碑来源；
            // 跳过它们可以避免在 Scrubber 线程上触发大量能力类的字段加载。
            "dev.xkmc.l2",
            "dev.xkmc.modulargolems."
    };

    private final Instrumentation inst;
    private final long periodMillis;
    private final AtomicLong removed = new AtomicLong();
    private final AtomicLong sweeps = new AtomicLong();
    private volatile boolean running = true;
    private volatile Set<UUID> protectedIds = Set.of();
    private volatile boolean verbose = true;

    public Scrubber(Instrumentation inst, int periodMillis) {
        this.inst = inst;
        this.periodMillis = Math.max(50, periodMillis);
    }

    public void setProtectedIds(Set<UUID> ids) {
        this.protectedIds = ids == null ? Set.of() : Set.copyOf(ids);
    }

    public void setVerbose(boolean value) {
        this.verbose = value;
    }

    public void start() {
        Thread t = new Thread(this, "SanctuaryAgent-Scrubber");
        t.setDaemon(true);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context == null) {
            context = Scrubber.class.getClassLoader();
        }
        if (context != null) {
            t.setContextClassLoader(context);
        }
        t.start();
    }

    public void stop() {
        running = false;
    }

    public long removedCount() {
        return removed.get();
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(periodMillis);
                sweep();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                GuardRuntime.log("[SanctuaryAgent] scrubber error: " + t);
            }
        }
    }

    /** 跑一遍全量清扫；返回删掉的条目数。 */
    public int sweep() {
        Set<UUID> ids = GuardRuntime.protectedIds();
        if (ids.isEmpty()) {
            ids = protectedIds;
        }
        if (ids.isEmpty()) {
            return 0;
        }
        sweeps.incrementAndGet();
        int total = 0;
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            if (cls == null || cls.getClassLoader() == null) {
                continue;
            }
            String name = cls.getName();
            if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("sanctuary.agent")) {
                continue;
            }
            if (isSkipped(name)) {
                continue;
            }
            if (isClientClass(cls)) {
                continue;
            }
            try {
                total += scrubClass(cls, ids);
            } catch (Throwable ignored) {
            }
        }
        if (total > 0) {
            removed.addAndGet(total);
            if (verbose) {
                GuardRuntime.log("[SanctuaryAgent] scrubber removed " + total
                        + " tombstone entr(ies), sweeps=" + sweeps.get() + ", total=" + removed.get());
            }
        }
        return total;
    }

    private static boolean isSkipped(String dotted) {
        for (String prefix : SKIP_PREFIXES) {
            if (dotted.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 客户端类链不扫。像 DeadKingAmbienceSoundInstance 这种类名不在 client 包，
     * 但父类是 net.minecraft.client.*；在 Scrubber 线程上 getDeclaredFields()
     * 会触发父类加载，服务端/错误 classloader 下直接报 ClassNotFoundException。
     */
    private static boolean isClientClass(Class<?> cls) {
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                String name = c.getName();
                if (name.startsWith("net.minecraft.client.")
                        || name.startsWith("com.mojang.blaze3d.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int scrubClass(Class<?> cls, Set<UUID> ids) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader loader = cls.getClassLoader();
        if (loader != null && loader != previous) {
            thread.setContextClassLoader(loader);
        }
        try {
            int removedHere = 0;
            for (Field field : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                boolean isMap = Map.class.isAssignableFrom(type);
                boolean isCollection = Collection.class.isAssignableFrom(type);
                if (!isMap && !isCollection) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value == null) {
                        continue;
                    }
                    removedHere += isMap ? scrubMap((Map<?, ?>) value, ids) : scrubCollection((Collection<?>) value, ids);
                } catch (Throwable ignored) {
                }
            }
            return removedHere;
        } finally {
            if (loader != null && loader != previous) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private int scrubMap(Map<?, ?> map, Set<UUID> ids) {
        List<Object> doomed = new ArrayList<>();
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (matches(entry.getKey(), ids) || matches(entry.getValue(), ids)) {
                    doomed.add(entry.getKey());
                }
            }
        } catch (Throwable ignored) {
            return 0;
        }
        int n = 0;
        for (Object key : doomed) {
            try {
                map.remove(key);
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private int scrubCollection(Collection<?> collection, Set<UUID> ids) {
        List<Object> doomed = new ArrayList<>();
        try {
            for (Object element : collection) {
                if (matches(element, ids)) {
                    doomed.add(element);
                }
            }
        } catch (Throwable ignored) {
            return 0;
        }
        int n = 0;
        for (Object element : doomed) {
            try {
                collection.remove(element);
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 这个 key/value 是不是"我们保护的实体"。只看 UUID 与 getUUID()，不认类型名。 */
    private boolean matches(Object candidate, Set<UUID> ids) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof UUID id) {
            return ids.contains(id);
        }
        if (candidate instanceof String text) {
            // 有些实现把 UUID 存成 toString()（带/不带连字符）
            for (UUID id : ids) {
                if (text.equals(id.toString()) || text.equals(id.toString().replace("-", ""))) {
                    return true;
                }
            }
            return false;
        }
        try {
            Object got = candidate.getClass().getMethod("getUUID").invoke(candidate);
            return got instanceof UUID id && ids.contains(id);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
