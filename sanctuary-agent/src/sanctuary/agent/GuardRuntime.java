package sanctuary.agent;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Bootstrap-safe policy runtime for the Sanctuary agent.
 *
 * <p>Deliberately free of Minecraft imports: the agent is loaded before modlauncher and cannot
 * resolve game classes at transform time. Everything about a target is discovered reflectively.</p>
 */
public final class GuardRuntime {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static boolean protectOwned = true;
    private static boolean globalBan = false;
    private static boolean verbose = true;
    private static PrintWriter out;
    private static long denied;

    private static PrintWriter logOut;

    /** 版本号：用来确认打补丁的类链接到的到底是不是这一份 GuardRuntime。 */
    private static final int VERSION = 2;

    static {
        System.out.println("[SanctuaryAgent] GuardRuntime v" + VERSION + " initialized");
    }

    private GuardRuntime() {
    }

    /** Agent-side console + file logging. The game's latest.log does not capture System.out. */
    public static void log(String message) {
        System.out.println(message);
        if (logOut != null) {
            logOut.println(message);
        }
    }

    public static void init(Properties props) {
        protectOwned = !"false".equalsIgnoreCase(props.getProperty("protect_owned_golems", "true"));
        globalBan = "true".equalsIgnoreCase(props.getProperty("global_ban", "false"));
        verbose = !"false".equalsIgnoreCase(props.getProperty("verbose", "true"));
        String agentLog = props.getProperty("agent_log", "sanctuary-agent.log");
        try {
            logOut = new PrintWriter(new FileWriter(Paths.get(agentLog).toAbsolutePath().toFile(), true), true);
        } catch (IOException e) {
            System.out.println("[SanctuaryAgent] cannot open agent log: " + e);
        }
        String log = props.getProperty("violations", "sanctuary-violations.jsonl");
        try {
            Path p = Paths.get(log).toAbsolutePath();
            if (p.getParent() != null && !Files.exists(p.getParent())) {
                Files.createDirectories(p.getParent());
            }
            out = new PrintWriter(new FileWriter(p.toFile(), true), true);
            System.out.println("[SanctuaryAgent] violations -> " + p);
        } catch (IOException e) {
            System.out.println("[SanctuaryAgent] cannot open violation log: " + e);
        }
        log("[SanctuaryAgent] policy protectOwned=" + protectOwned
                + " globalBan=" + globalBan);
    }

    /**
     * Called at the head of every patched method.
     *
     * @param target the entity (or arbitrary object) the method is about; may be null
     * @param site   "Class.method" for the violation record
     * @return true when the call must be aborted with a default return value
     */
    public static boolean block(Object target, String site) {
        try {
            if (globalBan) {
                deny(target, site + " (global)");
                return true;
            }
            if (!protectOwned || target == null) {
                return false;
            }
            // 2026-11-12：生产环境里 getOwnerUUID 的反射可能失败（SRG/Forge 名差异），
            // 导致 isOwnedGolem 返回 false、Rule 入口全放行。PROTECTED/EXEMPT 是按 UUID
            // 字段判的，不受方法名影响，作为第二判据。
            if (!isOwnedGolem(target) && !isExempt(target)) {
                return false;
            }
            deny(target, site);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void deny(Object target, String site) {
        denied++;
        String uuid = String.valueOf(invoke(target, "getUUID"));
        String name = String.valueOf(invoke(target, "getName"));
        String owner = String.valueOf(invoke(target, "getOwnerUUID"));
        if (verbose) {
            log("[SanctuaryAgent] DENIED " + site
                    + " target=" + name + " uuid=" + uuid + " owner=" + owner);
        }
        if (out != null) {
            out.println("{\"time\":\"" + LocalDateTime.now().format(TS) + "\""
                    + ",\"site\":\"" + escape(site) + "\""
                    + ",\"target\":\"" + escape(name) + "\""
                    + ",\"uuid\":\"" + uuid + "\""
                    + ",\"owner\":\"" + owner + "\""
                    + ",\"targetClass\":\"" + target.getClass().getName() + "\""
                    + ",\"denied\":" + denied + "}");
        }
        // 把拒绝事件交回 mod 层：它负责给相关玩家那张命名纸。
        try {
            Class<?> bridge = Class.forName("a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge",
                    true, target.getClass().getClassLoader());
            bridge.getMethod("onDenied", Object.class, String.class).invoke(null, target, site);
        } catch (Throwable ignored) {
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Walk the hierarchy looking for the Modular Golems package, then require an owner. */
    private static boolean isOwnedGolem(Object target) {
        Class<?> c = target.getClass();
        boolean golem = false;
        for (int i = 0; i < 16 && c != null; i++, c = c.getSuperclass()) {
            if (c.getName().startsWith("dev.xkmc.modulargolems.")) {
                golem = true;
                break;
            }
        }
        if (!golem) {
            return false;
        }
        Object owner = invoke(target, "getOwnerUUID");
        if (owner == null) {
            owner = fieldOf(target, new String[]{"owner"});
        }
        return owner != null;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static long deniedCount() {
        return denied;
    }

    /** 受保护实体的 UUID（由 mod 层通过 AgentBridge 推进来）。bootstrap 层只存 UUID，不碰游戏类。 */
    private static final java.util.Set<java.util.UUID> PROTECTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void protect(java.util.UUID id) {
        if (id != null && PROTECTED.add(id)) {
            log("[SanctuaryAgent] protecting uuid=" + id + " (total=" + PROTECTED.size() + ")");
        }
    }

    public static void forget(java.util.UUID id) {
        if (id != null) {
            PROTECTED.remove(id);
        }
    }

    /**
     * 2026-11-12：mod 层 {@code IdentityGuard} 把活体 UUID 换掉之后，agent 的
     * {@code PROTECTED} / {@code EXEMPT} 必须跟着换。否则 {@code isProtectedEntity}
     * 对新 UUID 恒 false，字段门和 {@code call*} 会突然放行——表现像"轮换后 agent 失效"。
     */
    public static void adoptIdentity(java.util.UUID old, java.util.UUID fresh) {
        if (old == null || fresh == null || old.equals(fresh)) {
            return;
        }
        PROTECTED.remove(old);
        // 即使旧 UUID 没在表里（例如 attach 前轮换），新 UUID 也必须进表：
        // 这个调用只由 mod 的 IdentityGuard 对受保护傀儡发起。
        PROTECTED.add(fresh);
        EXEMPT.remove(old);
        EXEMPT.add(fresh);
        // 旧名字进入假死名单：即使以后某个生成路径还带着旧 UUID，也不会被当成新实体。
        FAKE_DEAD.add(old);
        LAST_ROTATE.put(fresh, System.currentTimeMillis());
        log("[SanctuaryAgent] IDENTITY-BRIDGED " + old + " -> " + fresh
                + " protected=" + PROTECTED.size()
                + " exempt=" + EXEMPT.size()
                + " fakeDead=" + FAKE_DEAD.size());
    }

    public static java.util.Set<java.util.UUID> protectedIds() {
        return PROTECTED;
    }

    // ------------------------------------------------------------------ 跨加载器入口
    //
    // 为什么需要它：agent 是外部 attach 进来的， SanctuaryAgent 只存在于系统类加载器；
    // mod 侧 Class.forName("sanctuary.agent.SanctuaryAgent") 会 ClassNotFoundException（实测）。
    // 但 GuardRuntime 在 bootstrap 层，所有加载器都看得见 —— 所以把"服务端起来了，可以开始
    // 周期重打了"这个信号挂在这里。

    private static volatile Runnable watchStarter;
    private static volatile boolean armPending;

    // ---- 数据驱动目标集（mod 启动时扫 jar 推过来） ----
    //
    // 不再靠类名前缀猜：mod 扫描 mods/*.jar 的每个 class，凡是引用
    // f_146795_ / f_135391_ / f_146801_ / setRemovalReason / removeServerLevelEntity
    // 这些原语的类，都作为 target 推进来。transform 判定只看这张表。

    private static final java.util.Set<String> TARGETS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Runnable targetListener;

    public static void setTargetListener(Runnable listener) {
        targetListener = listener;
        if (listener != null && !TARGETS.isEmpty()) {
            try {
                listener.run();
            } catch (Throwable t) {
                log("[SanctuaryAgent] target listener failed: " + t);
            }
        }
    }

    /** @param dotted 点分类名（mod 侧扫 jar 得到的） */
    public static void addTargets(String[] dotted) {
        if (dotted == null) {
            return;
        }
        int added = 0;
        for (String name : dotted) {
            if (name != null && !name.isEmpty() && !isExcluded(name) && TARGETS.add(name.replace('.', '/'))) {
                added++;
            }
        }
        log("[SanctuaryAgent] data-driven targets added=" + added + " total=" + TARGETS.size());
        Runnable listener = targetListener;
        if (listener != null && added > 0) {
            try {
                listener.run();
            } catch (Throwable t) {
                log("[SanctuaryAgent] target listener failed: " + t);
            }
        }
    }

    // ---- 原生 hook 的回调（JNI 表桩 → 这里判） ----
    //
    // 返回 true = 允许写；false = 丢弃。DLL 侧的 SetObjectField/SetFloatField 会先问这里。
    private static long nativeBlocked;

    public static boolean nativeFieldWrite(Object target, String site) {
        try {
            if (target == null) {
                return true;
            }
            if (trusted() || !isExempt(target)) {
                return true;
            }
            nativeBlocked++;
            if (verbose && nativeBlocked <= 40) {
                log("[SanctuaryAgent] NATIVE-DENIED " + site + " uuid=" + invoke(target, "getUUID"));
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static long nativeBlocked() {
        return nativeBlocked;
    }

    // ---- 原版关卡门（agent 直接改原版类，不依赖 mixin） ----

    private static long deniedData;
    private static long gateCalls;

    public static long gateCalls() {
        return gateCalls;
    }

    private static long deniedRemoval;

    /** SynchedEntityData#set 的入口门：受保护实体的任何数据写都拒。 */
    public static boolean guardDataItem(Object data, Object accessor, Object value, String site) {
        
        if (PROTECTED.isEmpty()) { return true; }gateCalls++;
        try {
            Object entity = entityOfData(data);
            if (gateCalls <= 5) {
                log("[SanctuaryAgent] gate#" + gateCalls + " entity=" + entity
                        + " protected=" + PROTECTED.size() + " trusted=" + trusted());
            }
            if (entity == null) {
                return true;
            }
            if (trusted() || !isExempt(entity)) {
                return true;
            }
            deniedData++;
            if (verbose && deniedData <= 40) {
                log("[SanctuaryAgent] DATA-DENIED " + site + " uuid=" + invoke(entity, "getUUID"));
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Entity#setRemoved / #remove 的入口门。 */
    public static boolean blockRemoval(Object entity, String site) {
        try {
            if (entity == null || trusted() || !isExempt(entity)) {
                return false;
            }
            deniedRemoval++;
            if (verbose && deniedRemoval <= 40) {
                log("[SanctuaryAgent] REMOVE-DENIED " + site + " uuid=" + invoke(entity, "getUUID"));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long deniedData() { return deniedData; }
    /** ClassInstanceMultiMap.remove(entity)：受保护实体从索引里摘除 → 假装成功。 */
    public static boolean blockIndexRemoval(Object entity, String site) {
        try {
            if (entity == null || trusted() || !isExempt(entity)) {
                return false;
            }
            deniedRemoval++;
            if (verbose && deniedRemoval <= 40) {
                log("[SanctuaryAgent] INDEX-DENIED " + site + " uuid=" + invoke(entity, "getUUID"));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** actuallyHurt / hurt 的直接伤害门：豁免实体上的致命/荒谬伤害直接丢弃。 */
    public static boolean blockHurt(Object entity, float amount, String site) {
        try {
            if (entity == null || trusted() || !isExempt(entity)) {
                return false;
            }
            if (amount < 1000.0F) {
                return false;   // 普通伤害放行；只挡"处决级"数值
            }
            deniedRemoval++;
            if (verbose && deniedRemoval <= 40) {
                log("[SanctuaryAgent] HURT-DENIED " + site + " amount=" + amount
                        + " uuid=" + invoke(entity, "getUUID"));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long deniedRemoval() { return deniedRemoval; }

    private static final java.util.Map<Class<?>, java.lang.reflect.Field> ENTITY_OF_DATA =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object entityOfData(Object data) {
        if (data == null) {
            return null;
        }
        java.lang.reflect.Field f = ENTITY_OF_DATA.computeIfAbsent(data.getClass(), c -> {
            for (String name : new String[]{"f_135344_", "entity"}) {
                try {
                    java.lang.reflect.Field fld = c.getDeclaredField(name);
                    fld.setAccessible(true);
                    return fld;
                } catch (Throwable ignored) {
                }
            }
            return null;
        });
        if (f == null) {
            return null;
        }
        try {
            return f.get(data);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自家 / 原版 / 框架永远不是目标。 */
    public static boolean isExcludedDotted(String dotted) {
        return isExcluded(dotted);
    }

    private static boolean isExcluded(String dotted) {
        return dotted.startsWith("a_silly_cat.") || dotted.startsWith("sanctuary.")
                || dotted.startsWith("net.minecraft.") || dotted.startsWith("net.minecraftforge.")
                || dotted.startsWith("com.mojang.") || dotted.startsWith("java.")
                || dotted.startsWith("javax.") || dotted.startsWith("jdk.") || dotted.startsWith("sun.")
                || dotted.startsWith("com.sun.") || dotted.startsWith("org.spongepowered.")
                || dotted.startsWith("org.objectweb.asm.") || dotted.startsWith("dev.xkmc.modulargolems.");
    }

    // ---- 身份轮换（"假死"装甲） ----
    //
    // 对方的死亡名单/墓碑（ClientPurge）、knownUuids、entityMap 全按 UUID 记。
    // 实体被拦下的那一刻，把它的 UUID 换掉：名单里留下的旧 UUID 指向一个不存在的实体，
    // 傀儡以新身份继续存在 —— 不需要复制 NBT、不需要重建实体。

    private static final java.util.Set<java.util.UUID> FAKE_DEAD =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<java.util.UUID, Long> LAST_ROTATE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 实体从 NBT 生成时的洗身份：如果 NBT 里的 UUID 在"假死名单"里，换成新的。
     *
     * <p>这就是"玩家手里的傀儡放出来还是原来那只、但不再被秒清"的实现点：
     * 物品里存的是旧 UUID（对方名单里正好有），生成前洗掉即可。</p>
     */
    public static void scrubSpawnTag(Object tag) {
        try {
            if (tag == null || FAKE_DEAD.isEmpty()) {
                return;
            }
            Object old = callWith(tag, new String[]{"m_25975_", "getUUID"}, new Class<?>[]{String.class}, new Object[]{"UUID"});
            if (!(old instanceof java.util.UUID uuid) || !FAKE_DEAD.contains(uuid)) {
                return;
            }
            java.util.UUID fresh = java.util.UUID.randomUUID();
            callWith(tag, new String[]{"m_128362_", "putUUID"},
                    new Class<?>[]{String.class, java.util.UUID.class}, new Object[]{"UUID", fresh});
            FAKE_DEAD.add(fresh);
            log("[SanctuaryAgent] SPAWN-UUID-WASHED " + uuid + " -> " + fresh);
        } catch (Throwable ignored) {
        }
    }

    private static Object callWith(Object target, String[] names, Class<?>[] types, Object[] args) {
        Object m = methodAny(target.getClass(), names, types);
        if (m instanceof java.lang.reflect.Method mm) {
            try {
                return mm.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ---- 统一豁免（所有门的唯一判据来源） ----
    //
    // 受保护名单 + 手动豁免 + 未来的标签/会话令牌，全部从这里进。
    // 门只问一句 isExempt(entity)，不各自判断 —— 避免"有的门认、有的门不认"。

    private static final java.util.Set<java.util.UUID> EXEMPT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void setExempt(java.util.UUID uuid, boolean exempt) {
        if (uuid == null) {
            return;
        }
        if (exempt) {
            EXEMPT.add(uuid);
        } else {
            EXEMPT.remove(uuid);
        }
    }

    public static java.util.Set<java.util.UUID> exemptIds() {
        return EXEMPT;
    }

    public static int exemptCount() {
        return EXEMPT.size();
    }

    /** 所有门共用的唯一判据。 */
    public static boolean isExempt(Object entity) {
        if (entity == null) {
            return false;
        }
        try {
            if (isProtectedEntity(entity)) {
                return true;
            }
            Object id = invoke(entity, "getUUID");
            return id instanceof java.util.UUID uuid && EXEMPT.contains(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isFakeDead(java.util.UUID uuid) {
        return uuid != null && FAKE_DEAD.contains(uuid);
    }

    public static int fakeDeadCount() {
        return FAKE_DEAD.size();
    }

    /** 把一个实体的身份换掉；返回 true 表示已轮换。 */
    public static boolean rotateIdentity(Object entity) {
        try {
            Object idObj = call(entity, new String[]{"m_20148_", "getUUID"});
            if (!(idObj instanceof java.util.UUID old)) {
                return false;
            }
            long now = System.currentTimeMillis();
            Long last = LAST_ROTATE.get(old);
            if (last != null && now - last < 5000L) {
                return false;
            }
            java.util.UUID fresh = java.util.UUID.randomUUID();
            if (!applyUuid(entity, fresh)) {
                return false;
            }
            FAKE_DEAD.add(old);
            LAST_ROTATE.put(fresh, now);
            updateKnownUuids(entity, old, fresh);
            log("[SanctuaryAgent] IDENTITY-ROTATED " + old + " -> " + fresh
                    + " fakeDead=" + FAKE_DEAD.size());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean applyUuid(Object entity, java.util.UUID fresh) {
        Object setter = methodAny(entity.getClass(), new String[]{"m_20084_", "setUUID"},
                java.util.UUID.class);
        if (setter instanceof java.lang.reflect.Method m) {
            try {
                m.invoke(entity, fresh);
                return true;
            } catch (Throwable ignored) {
            }
        }
        for (String field : new String[]{"f_19820_", "uuid"}) {
            try {
                java.lang.reflect.Field f = entity.getClass().getSuperclass() != null
                        ? findField(entity.getClass(), field) : null;
                if (f != null) {
                    f.set(entity, fresh);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static java.lang.reflect.Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 让关卡侧的 knownUuids 跟着换，避免下次保存时又用旧 UUID。 */
    private static void updateKnownUuids(Object entity, java.util.UUID old, java.util.UUID fresh) {
        try {
            Object level = call(entity, new String[]{"m_9236_", "level"});
            if (level == null) {
                return;
            }
            Object manager = fieldOf(level, new String[]{"f_143244_", "entityManager"});
            if (manager == null) {
                return;
            }
            Object known = fieldOf(manager, new String[]{"f_157491_", "knownUuids"});
            if (known instanceof java.util.Set<?> set) {
                @SuppressWarnings("unchecked")
                java.util.Set<Object> raw = (java.util.Set<Object>) set;
                raw.remove(old);
                raw.add(fresh);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object fieldOf(Object target, String[] names) {
        for (String name : names) {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            if (f != null) {
                try {
                    return f.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object call(Object target, String[] names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Object found = methodAny(target.getClass(), new String[]{name});
                if (found instanceof java.lang.reflect.Method mm) {
                    return mm.invoke(target);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object methodAny(Class<?> start, String[] names, Class<?>... types) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    // ---- 全 JVM 唯一的一次性开关（bootstrap 层，多次 attach 也只有一个实例） ----
    private static volatile boolean startedOnce;

    public static synchronized boolean beginStart() {
        if (startedOnce) {
            return false;
        }
        startedOnce = true;
        return true;
    }

    public static boolean isTarget(String internalName) {
        return internalName != null && TARGETS.contains(internalName);
    }

    public static int targetCount() {
        return TARGETS.size();
    }

    public static void setWatchStarter(Runnable starter) {
        watchStarter = starter;
        // agent 的 attach 可能晚于 ServerStartedEvent：那次 request 先记下来，这里补发。
        if (armPending && starter != null) {
            armPending = false;
            try {
                starter.run();
            } catch (Throwable t) {
                log("[SanctuaryAgent] deferred armWatch failed: " + t);
            }
        }
    }

    /** mod 侧在 ServerStartedEvent 调用。 */
    public static void requestArmWatch() {
        Runnable starter = watchStarter;
        if (starter == null) {
            armPending = true;
            log("[SanctuaryAgent] armWatch requested before attach; will fire on registration");
            return;
        }
        try {
            starter.run();
        } catch (Throwable t) {
            log("[SanctuaryAgent] requestArmWatch failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 字段写门（引擎层）
    //
    // SanctuaryTransformer 把目标字段的 PUTFIELD 换成这些静态调用：
    //   [obj, value] → LDC site → INVOKESTATIC putX(Object, T, String)V
    // LDC 只压一个常量、静态调用消费三个槽，栈是"中立"的，不需要 DUP 技巧。
    // 允许时用反射把值写回去；受保护实体直接丢弃。

    private static final java.util.Map<String, java.lang.reflect.Field> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static long fieldDenied;
    private static boolean blockForeignDataItem = true;

    public static long fieldDeniedCount() {
        return fieldDenied;
    }

    public static void putObject(Object target, Object value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).set(target, value);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    public static void putInt(Object target, int value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).setInt(target, value);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    public static void putBoolean(Object target, int value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).setBoolean(target, value != 0);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    public static void putLong(Object target, long value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).setLong(target, value);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    public static void putFloat(Object target, float value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).setFloat(target, value);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    public static void putDouble(Object target, double value, String site) {
        probe(site);
        if (denyField(target, site)) {
            return;
        }
        try {
            field(site, target.getClass()).setDouble(target, value);
        } catch (Throwable t) {
            fail(site, t);
        }
    }

    /**
     * 统一入口门：按<b>调用方身份</b>判，不按类名清单。
     *
     * <p>调用方是 transformer 在改调用点时静态写进去的常量（不跑 StackWalker，零成本）。
     * 策略：</p>
     * <ul>
     *   <li>目标不是受保护实体 → 放行；</li>
     *   <li>调用方在白名单（原版 / Forge / 宿主 / 我们自己）→ 放行；</li>
     *   <li>其余 → 拒绝并打 {@code CALLER} 记录（谁在调什么、目标是谁）。</li>
     * </ul>
     */
    private static boolean allowCall(Object target, String caller, String callerMethod, String site) {
        if (target == null) {
            return true;
        }
        if (!isProtectedEntity(target)) {
            return true;
        }
        if (trusted() || allowedCaller(caller)) {
            return true;
        }
        deniedCalls++;
        if (verbose && deniedCalls <= 40) {
            log("[SanctuaryAgent] CALLER-DENIED " + caller + "#" + callerMethod
                    + " -> " + site + " uuid=" + invoke(target, "getUUID"));
        }
        return false;
    }

    private static long deniedCalls;

    public static long deniedCalls() {
        return deniedCalls;
    }

    private static boolean allowedCaller(String caller) {
        if (caller == null) {
            return false;
        }
        for (String prefix : CALLER_WHITELIST) {
            if (caller.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] CALLER_WHITELIST = {
            "net/minecraft/", "net/minecraftforge/", "com/mojang/", "java/", "javax/",
            "sun/", "jdk/", "org/spongepowered/", "org/objectweb/asm/",
            "dev/xkmc/modulargolems/", "dev/xkmc/l2", "a_silly_cat/golems_arsenal/"
    };

    // ---- 敏感方法的替身（栈中立：只多压三个常量） ----

    public static void callSetHealth(Object target, float amount,
                                     String caller, String callerMethod, String site) {
        if (!allowCall(target, caller, callerMethod, site)) {
            return;
        }
        invokeVoid(target, site, new Class<?>[]{float.class}, new Object[]{amount});
    }

    public static void callSetRemoved(Object target, Object reason,
                                      String caller, String callerMethod, String site) {
        if (!allowCall(target, caller, callerMethod, site)) {
            return;
        }
        invokeVoid(target, site, new Class<?>[]{reasonClass(reason)}, new Object[]{reason});
    }

    public static void callRemove(Object target, Object reason,
                                  String caller, String callerMethod, String site) {
        if (!allowCall(target, caller, callerMethod, site)) {
            return;
        }
        invokeVoid(target, site, new Class<?>[]{reasonClass(reason)}, new Object[]{reason});
    }

    public static void callKill(Object target, String caller, String callerMethod, String site) {
        if (!allowCall(target, caller, callerMethod, site)) {
            return;
        }
        invokeVoid(target, site, new Class<?>[0], new Object[0]);
    }

    public static void callDie(Object target, Object source,
                               String caller, String callerMethod, String site) {
        if (!allowCall(target, caller, callerMethod, site)) {
            return;
        }
        invokeVoid(target, site, new Class<?>[]{paramClass(source)}, new Object[]{source});
    }

    private static Class<?> reasonClass(Object reason) {
        return reason == null
                ? netMinecraftRemovalReason()
                : reason.getClass();
    }

    private static Class<?> paramClass(Object value) {
        return value == null ? Object.class : value.getClass();
    }

    private static Class<?> netMinecraftRemovalReason() {
        return Object.class;
    }

    private static void invokeVoid(Object target, String site, Class<?>[] types, Object[] args) {
        try {
            methodExact(target.getClass(), site, types).invoke(target, args);
        } catch (Throwable t) {
            if (verbose && deniedCalls <= 40) {
                log("[SanctuaryAgent] call forward failed " + site + ": " + t);
            }
        }
    }

    private static java.lang.reflect.Method methodExact(Class<?> start, String site, Class<?>[] types)
            throws NoSuchMethodException {
        String name = site.substring(site.lastIndexOf('.') + 1);
        String srg = SRG.get(name);
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(site);
    }

    /**
     * 一次字段写要不要丢。
     *
     * <p>{@code DataItem.f_135391_} 没有反向指针（拿不到它属于谁），所以走"外来一律拒"：
     * 白名单（原版 / 宿主 / 我们自己）根本不会被 transformer 改写，能走到这里的都是外来模组。
     * 其余字段（坐标 / removalReason / canUpdate / isAddedToWorld）按 UUID 判。</p>
     */
    /**
     * 探针：确认补丁代码到底有没有进来。
     *
     * <p>前面一次实测里 {@code patched ... fields=3} 出现了、{@code FIELD-DENIED} 却是 0 ——
     * 说明补丁要么没执行，要么执行时抛了被对方 {@code catch (Throwable)} 吞掉的
     * {@code NoSuchMethodError}。这条日志把两种情况分开。</p>
     */
    private static void probe(String site) {
        try {
            probeCount++;
            if (probeCount <= 40 || probeCount % 200 == 0) {
                log("[SanctuaryAgent] FIELD-CALL #" + probeCount + " " + site);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long probeCount;

    /** 方法入口探针：每个 site 只打一次，确认"这个方法到底跑没跑"。 */
    private static final java.util.Set<String> TRACED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void trace(String site) {
        try {
            if (TRACED.add(site)) {
                log("[SanctuaryAgent] ENTER " + site);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean denyField(Object target, String site) {
        try {
            
            if (trusted()) { return false; }if (target == null) {
                return false;
            }
            if (site.endsWith(".f_135391_")) {
                if (!blockForeignDataItem) {
                    return false;
                }
                fieldDenied++;
                if (verbose) {
                    log("[SanctuaryAgent] FIELD-DENIED " + site + " class=" + target.getClass().getName());
                }
                return true;
            }
            if (!isProtected(target)) {
                return false;
            }
            fieldDenied++;
            if (verbose) {
                log("[SanctuaryAgent] FIELD-DENIED " + site + " uuid=" + invoke(target, "getUUID"));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isProtected(Object target) {
        Object id = invoke(target, "getUUID");
        return id instanceof java.util.UUID uuid && PROTECTED.contains(uuid);
    }

    private static java.lang.reflect.Field field(String site, Class<?> start) throws Exception {
        String key = site + "@" + start.getName();
        java.lang.reflect.Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String name = site.substring(site.lastIndexOf('.') + 1);
        String srg = SRG.get(name);
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(site);
    }

    private static void fail(String site, Throwable t) {
        if (verbose) {
            log("[SanctuaryAgent] field write failed " + site + ": " + t);
        }
    }

    // ------------------------------------------------------------------ 读侧掩护（线程级）
    //
    // 由 SanctuaryTransformer 把敌对类里的
    //   getHealth()F / getMaxHealth()F          → GuardRuntime.readFloat(Object, String)F
    //   DataItem.f_135391_ 的 GETFIELD          → GuardRuntime.readObject(Object, String)Object
    // 换成这些静态调用。栈仍然是中立的（只多压一个 site 常量）。
    //
    // 判据是"线程级"的：只有进了 TRUST 作用域的线程拿得到真值，其余线程（原版、别的模组、
    // 攻击方在异步线程里的补刀）一律拿到掩护值。这也是"抓不到血量"的实现方式。

    public static final float DECOY = 1.0E9F;

    private static final java.lang.ThreadLocal<int[]> TRUST =
            java.lang.ThreadLocal.withInitial(() -> new int[1]);
    private static final java.util.Map<String, java.lang.reflect.Method> METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static long hiddenReads;

    public static void trustEnter() {
        TRUST.get()[0]++;
    }

    public static void trustExit() {
        int[] depth = TRUST.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static boolean trusted() {
        return TRUST.get()[0] > 0;
    }

    public static long hiddenReads() {
        return hiddenReads;
    }

    /** getHealth / getMaxHealth 的替身。 */
    public static float readFloat(Object target, String site) {
        if (target == null) {
            return 0.0F;
        }
        if (!trusted() && isProtectedEntity(target)) {
            hiddenReads++;
            if (verbose && hiddenReads <= 20) {
                log("[SanctuaryAgent] HIDDEN " + site + " uuid=" + invoke(target, "getUUID") + " -> " + DECOY);
            }
            return DECOY;
        }
        try {
            java.lang.reflect.Method m = method(site, target.getClass(), 0);
            Object value = m.invoke(target);
            return value instanceof Number n ? n.floatValue() : 0.0F;
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /**
     * {@code DataItem.f_135391_} 的 GETFIELD 替身。
     *
     * <p>DataItem 没有反向指针，拿不到它属于谁，所以对敌对类一律返回掩护值。</p>
     *
     * <p><b>这里必须是"永远不等于血量"的值</b>：AV 的 {@code entityDataModify} 判据是
     * {@code item.value == entity.getHealth()}，两边如果都返回同一个掩护值（比如都 1e9），
     * 相等反而成立，它就会照写不误。用 {@code Float.MIN_VALUE}：
     * 它仍是 Float（不会让对方的 {@code instanceof Float} 分支落空），但数值上不可能等于
     * {@link #DECOY} 或真实血量，所以那个 if 永远进不去。</p>
     */
    public static Object readObject(Object target, String site) {
        if (target == null) {
            return null;
        }
        if (!trusted()) {
            hiddenReads++;
            if (verbose && hiddenReads <= 20) {
                log("[SanctuaryAgent] HIDDEN " + site + " -> " + DATA_DECOY);
            }
            return DATA_DECOY;
        }
        try {
            java.lang.reflect.Method m = method(site, target.getClass(), 0);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** DataItem 读掩护值：类型是 Float，但数值永远碰不上真实血量/血量掩护值。 */
    private static final Float DATA_DECOY = Float.MIN_VALUE;

    /** 简单名 → SRG 别名（运行时是 SRG，简单名找不到就等于永远判 false）。 */
    private static final java.util.Map<String, String> SRG = java.util.Map.of(
            "getUUID", "m_20148_",
            "isRemoved", "m_213877_",
            "getHealth", "m_21223_",
            "getMaxHealth", "m_21233_",
            "level", "m_9236_",
            "discard", "m_6074_",
            "isAlive", "m_6084_",
            "getRemovalReason", "m_214078_");

    private static boolean isProtectedEntity(Object target) {
        try {
            Object id = fieldOf(target, new String[]{"f_19820_", "uuid"});
            return id instanceof java.util.UUID uuid && PROTECTED.contains(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ generic terminal gates
    // No mod names here.  Deny only when the target/key is a protected entity (or an
    // index container that still holds one) and we are not in a rebuild scope.

    public static void reflectSet(java.lang.reflect.Field field, Object target, Object value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.set(target, value);
        } catch (Throwable t) {
            fail("Field.set", t);
        }
    }

    public static void reflectSetBoolean(java.lang.reflect.Field field, Object target, boolean value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.setBoolean(target, value);
        } catch (Throwable t) {
            fail("Field.setBoolean", t);
        }
    }

    public static void reflectSetInt(java.lang.reflect.Field field, Object target, int value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.setInt(target, value);
        } catch (Throwable t) {
            fail("Field.setInt", t);
        }
    }

    public static void reflectSetFloat(java.lang.reflect.Field field, Object target, float value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.setFloat(target, value);
        } catch (Throwable t) {
            fail("Field.setFloat", t);
        }
    }

    public static void reflectSetDouble(java.lang.reflect.Field field, Object target, double value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.setDouble(target, value);
        } catch (Throwable t) {
            fail("Field.setDouble", t);
        }
    }

    public static void reflectSetLong(java.lang.reflect.Field field, Object target, long value) {
        if (denyReflect(field, target, value)) {
            return;
        }
        try {
            field.setLong(target, value);
        } catch (Throwable t) {
            fail("Field.setLong", t);
        }
    }

    // ------------------------------------------------------------------ unsafe / varhandle write gates
    // 2026-11-21: 直接改写字段/内存的入口。transformer 把这些调用点换成下面的静态方法；
    // 目标受保护时整次写被丢弃，而不是等 section/lookup 被写坏后再修。
    private static final java.util.Map<String, java.lang.reflect.Method> UNSAFE_METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> UNSAFE_FAILED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static java.lang.reflect.Method unsafeMethod(Object unsafe, String name,
                                                          Class<?>... params) throws Exception {
        String key = unsafe.getClass().getName() + "#" + name + java.util.Arrays.toString(params);
        java.lang.reflect.Method cached = UNSAFE_METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        java.lang.reflect.Method method = unsafe.getClass().getMethod(name, params);
        method.setAccessible(true);
        UNSAFE_METHODS.put(key, method);
        return method;
    }

    private static boolean denyUnsafe(Object target, String site) {
        if (target == null || trusted()) {
            return false;
        }
        try {
            if (isProtected(target) || isProtectedDataItem(target) || containsProtected(target)) {
                fieldDenied++;
                if (verbose && fieldDenied <= 80) {
                    log("[SanctuaryAgent] UNSAFE-DENIED " + site + " target="
                            + target.getClass().getName() + " uuid=" + invoke(target, "getUUID"));
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void unsafeFail(String site, Throwable t) {
        if (UNSAFE_FAILED.add(site)) {
            log("[SanctuaryAgent] UNSAFE-REFLECT-FAILED " + site + ": " + t);
        }
    }

    public static void unsafePutObject(Object unsafe, Object target, long offset, Object value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, Object.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutInt(Object unsafe, Object target, long offset, int value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, int.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutLong(Object unsafe, Object target, long offset, long value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, long.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutFloat(Object unsafe, Object target, long offset, float value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, float.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutDouble(Object unsafe, Object target, long offset, double value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, double.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutBoolean(Object unsafe, Object target, long offset, boolean value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, boolean.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutByte(Object unsafe, Object target, long offset, byte value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, byte.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutShort(Object unsafe, Object target, long offset, short value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, short.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static void unsafePutChar(Object unsafe, Object target, long offset, char value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return;
        try {
            unsafeMethod(unsafe, method, Object.class, long.class, char.class)
                    .invoke(unsafe, target, offset, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
        }
    }

    public static boolean unsafeCompareAndSwapObject(Object unsafe, Object target, long offset,
                                                     Object expected, Object value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return false;
        try {
            return (boolean) unsafeMethod(unsafe, method, Object.class, long.class, Object.class, Object.class)
                    .invoke(unsafe, target, offset, expected, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
            return false;
        }
    }

    public static boolean unsafeCompareAndSwapInt(Object unsafe, Object target, long offset,
                                                  int expected, int value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return false;
        try {
            return (boolean) unsafeMethod(unsafe, method, Object.class, long.class, int.class, int.class)
                    .invoke(unsafe, target, offset, expected, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
            return false;
        }
    }

    public static boolean unsafeCompareAndSwapLong(Object unsafe, Object target, long offset,
                                                   long expected, long value, String method) {
        if (denyUnsafe(target, "Unsafe." + method)) return false;
        try {
            return (boolean) unsafeMethod(unsafe, method, Object.class, long.class, long.class, long.class)
                    .invoke(unsafe, target, offset, expected, value);
        } catch (Throwable t) {
            unsafeFail("Unsafe." + method, t);
            return false;
        }
    }

    private static boolean denyVarHandle(Object[] args, String site) {
        if (args == null || args.length == 0 || trusted()) {
            return false;
        }
        Object target = args[0];
        if (target == null) {
            return false;
        }
        try {
            if (isProtected(target) || isProtectedDataItem(target) || containsProtected(target)) {
                fieldDenied++;
                if (verbose && fieldDenied <= 80) {
                    log("[SanctuaryAgent] VARHANDLE-DENIED " + site + " target="
                            + target.getClass().getName());
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void varHandleSet(Object handle, Object[] args, String method) {
        if (denyVarHandle(args, "VarHandle." + method)) return;
        try {
            java.lang.reflect.Method m = handle.getClass().getMethod(method, Object[].class);
            m.invoke(handle, (Object) args);
        } catch (Throwable t) {
            unsafeFail("VarHandle." + method, t);
        }
    }

    public static boolean varHandleCompareAndSet(Object handle, Object[] args, String method) {
        if (denyVarHandle(args, "VarHandle." + method)) return false;
        try {
            java.lang.reflect.Method m = handle.getClass().getMethod(method, Object[].class);
            return (boolean) m.invoke(handle, (Object) args);
        } catch (Throwable t) {
            unsafeFail("VarHandle." + method, t);
            return false;
        }
    }

    public static Object varHandleGetAndSet(Object handle, Object[] args, String method) {
        if (denyVarHandle(args, "VarHandle." + method)) return null;
        try {
            java.lang.reflect.Method m = handle.getClass().getMethod(method, Object[].class);
            return m.invoke(handle, (Object) args);
        } catch (Throwable t) {
            unsafeFail("VarHandle." + method, t);
            return null;
        }
    }

    private static boolean denyReflect(java.lang.reflect.Field field, Object target, Object value) {
        if (field == null || target == null || trusted()) {
            return false;
        }
        String name = field.getName();
        if (isContainerField(name)) {
            if (containsProtected(target)) {
                fieldDenied++;
                if (verbose && fieldDenied <= 40) {
                    log("[SanctuaryAgent] FIELD-DENIED " + name + " containerReplace target="
                            + target.getClass().getName());
                }
                return true;
            }
            return false;
        }
        if (!isCriticalField(name)) {
            return false;
        }
        if ("f_135391_".equals(name) && isProtectedDataItem(target)) {
            fieldDenied++;
            if (verbose && fieldDenied <= 40) {
                log("[SanctuaryAgent] FIELD-DENIED f_135391_ dataItem value=" + value);
            }
            return true;
        }
        Object entity = "f_135391_".equals(name) ? entityOfData(target) : target;
        if (entity == null || !isExempt(entity)) {
            return false;
        }
        fieldDenied++;
        if (verbose && fieldDenied <= 40) {
            log("[SanctuaryAgent] FIELD-DENIED " + name + " target=" + invoke(entity, "getUUID")
                    + " value=" + value);
        }
        return true;
    }

    private static boolean isCriticalField(String name) {
        return "f_146795_".equals(name) || "f_146801_".equals(name)
                || "isAddedToWorld".equals(name) || "f_135391_".equals(name)
                || "f_19820_".equals(name) || "f_19825_".equals(name)
                || "f_19826_".equals(name) || "f_185933_".equals(name)
                || "canUpdate".equals(name);
    }

    /** DataItem 没有反向指针；由 mod 在注册/对账时把受保护傀儡的 DATA_HEALTH 项推过来。 */
    private static final java.util.Set<Object> PROTECTED_DATA_ITEMS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void protectDataItem(Object item) {
        if (item != null) {
            PROTECTED_DATA_ITEMS.add(item);
        }
    }

    private static boolean isProtectedDataItem(Object item) {
        return item != null && PROTECTED_DATA_ITEMS.contains(item);
    }

    private static boolean isContainerField(String name) {
        return "f_156807_".equals(name) || "f_156808_".equals(name)
                || "f_157491_".equals(name) || "f_156852_".equals(name)
                || "f_13527_".equals(name) || "f_13529_".equals(name)
                || "f_156903_".equals(name) || "f_156904_".equals(name)
                || "f_156905_".equals(name) || "f_140150_".equals(name);
    }

    public static Object mapRemove(Object map, Object key) {
        if (map == null) {
            return null;
        }
        if (!trusted() && protectedKey(key)) {
            fieldDenied++;
            if (verbose && fieldDenied <= 40) {
                log("[SanctuaryAgent] MAP-DENIED " + map.getClass().getName() + " key=" + key);
            }
            return null;
        }
        try {
            return ((java.util.Map<?, ?>) map).remove(key);
        } catch (Throwable t) {
            fail("Map.remove", t);
            return null;
        }
    }

    public static Object mapRemoveInt(Object map, int key) {
        if (map == null) {
            return null;
        }
        if (!trusted() && protectedKey(key)) {
            fieldDenied++;
            if (verbose && fieldDenied <= 40) {
                log("[SanctuaryAgent] MAP-DENIED " + map.getClass().getName() + " key=" + key);
            }
            return null;
        }
        try {
            return ((java.util.Map<?, ?>) map).remove(key);
        } catch (Throwable t) {
            fail("Map.remove(int)", t);
            return null;
        }
    }

    public static boolean mapRemoveIntBool(Object map, int key, Object value) {
        if (map == null) {
            return false;
        }
        if (!trusted() && protectedKey(key)) {
            fieldDenied++;
            return false;
        }
        try {
            return ((java.util.Map<?, ?>) map).remove(key, value);
        } catch (Throwable t) {
            fail("Map.remove(int,value)", t);
            return false;
        }
    }

    public static boolean collectionRemove(Object collection, Object value) {
        if (collection == null) {
            return false;
        }
        if (!trusted() && protectedKey(value)) {
            fieldDenied++;
            if (verbose && fieldDenied <= 40) {
                log("[SanctuaryAgent] COLLECTION-DENIED " + collection.getClass().getName() + " value=" + value);
            }
            return false;
        }
        try {
            return ((java.util.Collection<?>) collection).remove(value);
        } catch (Throwable t) {
            fail("Collection.remove", t);
            return false;
        }
    }

    public static void iteratorRemove(Object iterator) {
        if (iterator == null) {
            return;
        }
        if (!trusted()) {
            Object owner = iteratorOwner(iterator);
            if (owner != null && containsProtected(owner)) {
                fieldDenied++;
                if (verbose && fieldDenied <= 40) {
                    log("[SanctuaryAgent] ITERATOR-DENIED " + owner.getClass().getName());
                }
                return;
            }
        }
        try {
            ((java.util.Iterator<?>) iterator).remove();
        } catch (Throwable t) {
            fail("Iterator.remove", t);
        }
    }

    private static boolean protectedKey(Object key) {
        if (key == null) {
            return false;
        }
        if (key instanceof java.util.UUID uuid) {
            return PROTECTED.contains(uuid);
        }
        if (key instanceof Integer id) {
            return isProtectedId(id);
        }
        return isExempt(key);
    }

    private static boolean isProtectedId(int id) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> bridge = Class.forName("a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge", false, loader);
            Object value = bridge.getMethod("isProtectedId", int.class).invoke(null, id);
            return value instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object iteratorOwner(Object iterator) {
        try {
            for (java.lang.reflect.Field f : iterator.getClass().getDeclaredFields()) {
                if (java.util.Collection.class.isAssignableFrom(f.getType())
                        || java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object value = f.get(iterator);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean containsProtected(Object container) {
        try {
            if (container instanceof java.util.Map<?, ?> map) {
                for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                    if (protectedKey(e.getKey()) || protectedKey(e.getValue())) {
                        return true;
                    }
                }
            } else if (container instanceof java.util.Collection<?> collection) {
                for (Object value : collection) {
                    if (protectedKey(value)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static java.lang.reflect.Method method(String site, Class<?> start, int argCount) throws Exception {
        String name = site.substring(site.lastIndexOf('.') + 1);
        String srg = SRG.get(name);
        String key = name + "#" + argCount + "@" + start.getName();
        java.lang.reflect.Method cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                    m.setAccessible(true);
                    METHOD_CACHE.put(key, m);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(site);
    }
}