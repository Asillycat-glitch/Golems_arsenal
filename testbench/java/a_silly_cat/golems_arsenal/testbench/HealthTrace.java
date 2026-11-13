package a_silly_cat.golems_arsenal.testbench;

import a_silly_cat.golems_arsenal.testbench.HealthTraceData.Tracked;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * P0：血量基线观测（PRIVATE TESTBENCH）。
 *
 * <h2>这个类做什么</h2>
 * <ul>
 *   <li>记录 Forge 的四个伤害/治疗事件与死亡事件的次数、amount、取消状态；</li>
 *   <li>记录被追踪实体上的 {@code setHealth} / {@code getHealth} 调用（testbench mixin，只读）；</li>
 *   <li>记录傀儡的 {@code GuardedEntity.setHealth} / {@code setGuardedDataImpl}（L2/本家真正的写血路径）；</li>
 *   <li>记录 {@code setRemoved} 与发给客户端的移除包；</li>
 *   <li>注册一个 l2 {@link AttackListener}，在 finalize 时读 raw/base/fin，并把紧随其后的写血配对成
 *       {@code l2_applied}（applied = before - after），用来量"伤害算出来了，血到底掉了多少"。</li>
 * </ul>
 *
 * <h2>这个类不做什么</h2>
 * <p><b>不改任何伤害、不取消事件、不写血、不拦截 setHealth。</b>所有入口先检查
 * {@link #active}；未开启时只剩一次 volatile 读。任何观测异常都被吞掉并只打一条错误日志，
 * 不会把异常抛回游戏线程。</p>
 *
 * <h2>为什么 P0 也需要 mixin</h2>
 * <p>Forge 没有 setHealth/getHealth 事件，也没有"实体被移除"事件（只有 LivingDeathEvent）。
 * 要回答"谁在写血"，只能在这些方法上挂只读回调。mixin 只在 {@code -Ptestbench} 构建里存在，
 * 正式 jar 的 {@code mods.toml} 不会引用它。</p>
 */
public final class HealthTrace {

    /** 事件/写入量很大，每多少行 flush 一次；tick 末也会 flush。 */
    private static final int FLUSH_EVERY = 16;

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Object IO_LOCK = new Object();
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** 跟踪 getHealth 采样：uuid -> 本会话调用次数。 */
    private static final Map<UUID, Integer> GET_COUNTS = new ConcurrentHashMap<>();
    /** 每个被追踪实体的上一次采样血量。 */
    private static final Map<UUID, Float> LAST_HEALTH = new HashMap<>();
    /** 每个被追踪实体的上一次采样最大生命；用来抓 HEALTH_SEAL / trim_max_health。 */
    private static final Map<UUID, Float> LAST_MAX_HEALTH = new HashMap<>();
    /** 已经 finalize、等待紧随其后的写血来配对。 */
    private static final Map<Integer, PendingL2> PENDING_L2 = new HashMap<>();
    /** P1 影子账本：按 UUID 挂到已追踪实体，只读，不写血。 */
    private static final HealthLedger LEDGER = new HealthLedger();
    /** 最近一次写血，用于 UNEXPLAINED 归因。 */
    private static final Map<UUID, LastWrite> LAST_WRITE = new HashMap<>();
    /** 最近几次写血：feed/heal 会覆盖 LAST_WRITE，归因时需要看到 poke 那一次。 */
    private static final Map<UUID, Deque<LastWrite>> RECENT_WRITES = new HashMap<>();
    private static final int RECENT_WRITE_LIMIT = 4;

    /** 读血量时置 true，防止我们自己的 getHealth 调用被记录（也会防止递归）。 */
    private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 写血嵌套深度：GuardedEntity.setHealth → guardedData → super.setHealth 会叠三层。 */
    private static final ThreadLocal<Integer> WRITE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<WriteCtx> LIVING_WRITE = new ThreadLocal<>();
    private static final ThreadLocal<WriteCtx> GUARDED_WRITE = new ThreadLocal<>();
    private static final ThreadLocal<WriteCtx> GUARDED_DATA_WRITE = new ThreadLocal<>();

    private static volatile boolean active;
    private static volatile HealthTraceData data;
    private static HealthTraceStats stats = new HealthTraceStats();

    private static MinecraftServer server;
    private static BufferedWriter writer;
    private static Path file;
    private static long startedAt;
    private static long seq;
    private static int tick;
    private static int tickCounter;
    private static boolean failureLogged;

    private HealthTrace() {
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean active() {
        return active;
    }

    public static Path file() {
        return file;
    }

    public static void onServerStarted(MinecraftServer srv) {
        data = HealthTraceData.get(srv);
        if (data != null) {
            data.active = false;
        }
        active = false;
        writer = null;
        file = null;
        stats = new HealthTraceStats();
        LAST_HEALTH.clear();
        LAST_MAX_HEALTH.clear();
        PENDING_L2.clear();
        GET_COUNTS.clear();
        LEDGER.clear();
        LAST_WRITE.clear();
        RECENT_WRITES.clear();
        HealthGuard.clear();
        int guardCleared = data == null ? 0 : data.guardAllOff();
        if (guardCleared > 0) {
            PrivateTestbench.LOGGER.info("[GA-Health] server start：强制把 {} 个实体的 guard 复位到 OFF", guardCleared);
        }
        PrivateTestbench.LOGGER.info("[GA-Health] P0 probe ready. tracked={}；用 /ga health start 开始记录",
                data == null ? 0 : data.size());
    }

    public static void onServerStopping(MinecraftServer srv) {
        if (active) {
            String msg = stop();
            PrivateTestbench.LOGGER.info("[GA-Health] server stopping；{}", msg);
        }
        if (data != null) {
            data.active = false;
            data.setDirty();
        }
    }

    /** 开始一轮记录；返回给命令显示的消息。 */
    public static synchronized String start(MinecraftServer srv, String label) {
        if (active) {
            return "已经在记录：" + file;
        }
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return "拿不到存档级追踪数据（只能在服务器上开始）";
        }
        // tracked=0 的空 trace 只会写一行 meta，打多少下都不会有数据；直接拒绝，避免白测一轮。
        if (!d.trackAll && d.size() == 0) {
            return "还没有追踪任何实体：先 /ga health nearby 32 + /ga health track control|test ...，"
                    + "或用 /ga health all true；空 trace 不会记录任何事件";
        }
        // A/B 的硬前提：如果 control/test 都有，类型必须一致；否则 compare 永远对不上。
        String pairMismatch = d.pairMismatch();
        if (pairMismatch != null) {
            return pairMismatch;
        }
        String pairWarning = d.pairWarning();
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("golems_arsenal").resolve("health");
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            String safe = sanitizeLabel(label);
            Path out = dir.resolve("p0-" + stamp + (safe.isEmpty() ? "" : "-" + safe) + ".jsonl");
            BufferedWriter bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            data = d;
            server = srv;
            file = out;
            writer = bw;
            startedAt = System.currentTimeMillis();
            seq = 0L;
            tick = (int) srv.overworld().getGameTime();
            tickCounter = 0;
            failureLogged = false;
            stats = new HealthTraceStats();
            LAST_HEALTH.clear();
            LAST_MAX_HEALTH.clear();
            PENDING_L2.clear();
            GET_COUNTS.clear();
            LEDGER.clear();
            LAST_WRITE.clear();
        RECENT_WRITES.clear();
        HealthGuard.clear();

            for (HealthTraceData.Tracked t : d.tracked()) {
                LivingEntity entity = d.resolve(srv, t);
                if (entity != null) {
                    t.entityId = entity.getId();
                    LAST_HEALTH.put(t.uuid, readHealthQuiet(entity));
                    LAST_MAX_HEALTH.put(t.uuid, entity.getMaxHealth());
                    if (d.ledgerEnabled) {
                        LEDGER.attach(entity);
                        HealthGuard.noteBuild(entity);
                    }
                }
            }

            active = true;
            d.active = true;
            writeMeta("start");
            String msg = "开始记录：" + out + "（tracked=" + d.size()
                    + "，trackAll=" + d.trackAll + "，sampleEvery=" + d.sampleEvery
                    + "，ledger=" + d.ledgerEnabled + "）";
            if (pairWarning != null) {
                msg += "；注意：" + pairWarning;
            }
            PrivateTestbench.LOGGER.info("[GA-Health] {}", msg);
            return msg;
        } catch (Throwable t) {
            active = false;
            writer = null;
            file = null;
            PrivateTestbench.LOGGER.error("[GA-Health] 开始记录失败", t);
            return "开始记录失败：" + t;
        }
    }

    /** 停止当前记录并写摘要；返回给命令显示的消息。 */
    public static synchronized String stop() {
        if (!active) {
            return "当前没有在记录";
        }
        String traceName = file == null ? "?" : file.getFileName().toString();
        try {
            writeMeta("stop");
        } catch (Throwable t) {
            PrivateTestbench.LOGGER.warn("[GA-Health] 写 stop 元信息失败", t);
        }
        active = false;
        flush();
        synchronized (IO_LOCK) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    PrivateTestbench.LOGGER.warn("[GA-Health] 关闭 trace 失败", e);
                }
                writer = null;
            }
        }
        if (data != null) {
            data.active = false;
        }
        Path summary = writeSummary();
        LEDGER.clear();
        LAST_WRITE.clear();
        RECENT_WRITES.clear();
        HealthGuard.clear();
        String msg = "已停止：" + traceName + "（" + stats.lines() + " 行）"
                + (summary == null ? "；摘要落盘失败，见日志" : "；摘要：" + summary);
        PrivateTestbench.LOGGER.info("[GA-Health] {}", msg);
        return msg;
    }

    public static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("active=" + active + "  file=" + (file == null ? "-" : file));
        out.add("lines=" + stats.lines() + "  tick=" + tick + "  pendingL2=" + PENDING_L2.size()
                + "  since=" + (startedAt == 0L ? "-" : ((System.currentTimeMillis() - startedAt) / 1000L) + "s"));
        HealthTraceData d = data;
        if (d != null) {
            out.add("trackAll=" + d.trackAll + "  sampleEvery=" + d.sampleEvery
                    + "  logGetHealth=" + d.logGetHealth + "  ledger=" + d.ledgerEnabled
                    + "  ledgerEntries=" + LEDGER.size() + "  tracked=" + d.size());
            for (HealthTraceData.Tracked t : d.tracked()) {
                out.add("  " + HealthTraceData.describe(t));
            }
        } else {
            out.add("（没有存档级追踪数据）");
        }
        return out;
    }

    public static List<String> summaryLines() {
        return stats.summaryLines(sessionInfo());
    }

    public static void printReport(CommandSourceStack src) {
        List<String> lines = summaryLines();
        for (String line : lines) {
            PrivateTestbench.LOGGER.info(line);
            if (src != null) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
    }

    /** 手动落一份摘要（记录中也可以，摘要反映当前已 flush 的内容）。 */
    public static Path writeSummaryNow() {
        if (active) {
            flush();
        }
        return writeSummary();
    }

    /** 只允许在没有记录时清本地统计；追踪表在 {@link HealthTraceData} 里，命令负责清。 */
    public static void clearStats() {
        if (!active) {
            stats = new HealthTraceStats();
            file = null;
            startedAt = 0L;
        }
    }

    /** P1：开启/关闭影子账本；开启时给当前所有已追踪实体挂上。 */
    public static int setLedgerEnabled(MinecraftServer srv, boolean enabled) {
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return -1;
        }
        d.ledgerEnabled = enabled;
        d.setDirty();
        LEDGER.clear();
        LAST_WRITE.clear();
        RECENT_WRITES.clear();
        HealthGuard.clear();
        if (enabled) {
            for (HealthTraceData.Tracked t : d.tracked()) {
                LivingEntity entity = d.resolve(srv, t);
                if (entity != null) {
                    LEDGER.attach(entity);
                }
            }
        }
        return LEDGER.size();
    }

    /** poke 的结果：只有 accepted 会真正改血；guardBlocked 是 P2 ENFORCE 拦下的数量。 */
    public record PokeResult(int total, int accepted, int rejected, int dead, int unchanged,
                             int guardBlocked) {
    }

    /**
     * P1 测试刺激：把某个角色的所有已追踪实体 setHealth 到指定值。
     *
     * <p><b>这是故意写血</b>，只用于验证影子账本能抓到“事件之外的写”。它不属于观测层，
     * 也不是 Guard；P2 才会在 Test 实体上真正拦截。只建议对 test 用。</p>
     *
     * <p>返回 accepted/rejected/dead/unchanged：GuardedEntity 会拒绝“增加血量”、低于自身
     * floor 的写入，或者实体已经 0 血/死亡；这些情况不会产生 P1 unexplained，命令必须说清楚。</p>
     */
    public static PokeResult poke(MinecraftServer srv, HealthTraceData.Role role, float health) {
        // stop 会清空 ledger；没在记录时 poke 只会改血而不会产生 P1 数据，必须拒绝。
        if (!active) {
            return new PokeResult(0, 0, 0, 0, 0, 0);
        }
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return new PokeResult(0, 0, 0, 0, 0, 0);
        }
        int total = 0;
        int accepted = 0;
        int rejected = 0;
        int dead = 0;
        int unchanged = 0;
        int guardBlocked = 0;
        for (HealthTraceData.Tracked t : d.tracked()) {
            if (t.role != role) {
                continue;
            }
            LivingEntity entity = d.resolve(srv, t);
            if (entity == null) {
                continue;
            }
            total++;
            float before = readTruthQuiet(entity);
            if (before <= 0.0F) {
                // setHealth 不能复活；0.005 对 0 血是“增加”，会被 GuardedEntity 直接拒绝。
                dead++;
                continue;
            }
            float expected = LEDGER.expected(t.uuid);
            boolean guardWillBlock = t.guardMode == HealthTraceData.GuardMode.ENFORCE
                    && Float.isFinite(expected) && Math.abs(health - expected) > 1.0E-3F;
            entity.setHealth(health);
            float after = readTruthQuiet(entity);
            if (Math.abs(after - before) <= 1.0E-3F) {
                if (Math.abs(health - before) <= 1.0E-3F) {
                    unchanged++;
                } else {
                    rejected++;
                    if (guardWillBlock) {
                        guardBlocked++;
                    }
                }
            } else if (Math.abs(after - health) <= 1.0E-3F) {
                accepted++;
            } else {
                rejected++;
                if (guardWillBlock) {
                    guardBlocked++;
                }
            }
        }
        return new PokeResult(total, accepted, rejected, dead, unchanged, guardBlocked);
    }

    /**
     * P1 测试刺激（直写 guardedData）：绕过 {@link GuardedEntity#setHealth(float)} 的
     * “tickCount>5 后只允许增加”保护，模拟外部 mod/反射直接调用 setGuardedDataImpl。
     *
     * <p>这是 P1 真正要抓的 backdoor 路径；不是 Guard，P2 才会拦截。只建议对 test 用。</p>
     */
    public static PokeResult pokeGuarded(MinecraftServer srv, HealthTraceData.Role role, float health) {
        if (!active) {
            return new PokeResult(0, 0, 0, 0, 0, 0);
        }
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return new PokeResult(0, 0, 0, 0, 0, 0);
        }
        int total = 0;
        int accepted = 0;
        int rejected = 0;
        int dead = 0;
        int unchanged = 0;
        int guardBlocked = 0;
        for (HealthTraceData.Tracked t : d.tracked()) {
            if (t.role != role) {
                continue;
            }
            LivingEntity entity = d.resolve(srv, t);
            if (entity == null) {
                continue;
            }
            total++;
            if (!(entity instanceof GuardedEntity guarded)) {
                rejected++;
                continue;
            }
            float before = readTruthQuiet(entity);
            if (before <= 0.0F) {
                dead++;
                continue;
            }
            float expected = LEDGER.expected(t.uuid);
            boolean guardWillBlock = t.guardMode == HealthTraceData.GuardMode.ENFORCE
                    && Float.isFinite(expected) && Math.abs(health - expected) > 1.0E-3F;
            guarded.setGuardedDataImpl(health, health > before, false);
            float after = readTruthQuiet(entity);
            if (Math.abs(after - before) <= 1.0E-3F) {
                if (Math.abs(health - before) <= 1.0E-3F) {
                    unchanged++;
                } else {
                    rejected++;
                    if (guardWillBlock) {
                        guardBlocked++;
                    }
                }
            } else if (Math.abs(after - health) <= 1.0E-3F) {
                accepted++;
            } else {
                rejected++;
                if (guardWillBlock) {
                    guardBlocked++;
                }
            }
        }
        return new PokeResult(total, accepted, rejected, dead, unchanged, guardBlocked);
    }

    public record DiscardResult(int total, int removed, int blocked, int guardBlocked) {
    }

    /**
     * P1 清除测试：对某个角色的已追踪实体调用 {@code discard()}（RemovalReason.DISCARDED）。
     *
     * <p>这是模拟外部 erase/清除，不是 Guard；P1 应记录 remove + removed_alive + client_remove。
     * P2 ENFORCE 会 cancel setRemoved；但有些目标（如 iss_csw:nameless_wizard）自己重写了
     * discard/remove，根本不进 setRemoved —— 这时 {@code guardBlocked=0}，只记录 attempt。</p>
     */
    public static DiscardResult discard(MinecraftServer srv, HealthTraceData.Role role) {
        if (!active) {
            return new DiscardResult(0, 0, 0, 0);
        }
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return new DiscardResult(0, 0, 0, 0);
        }
        int total = 0;
        int removed = 0;
        int blocked = 0;
        int guardBlocked = 0;
        for (HealthTraceData.Tracked t : d.tracked()) {
            if (t.role != role) {
                continue;
            }
            LivingEntity entity = d.resolve(srv, t);
            if (entity == null) {
                continue;
            }
            total++;
            float expected = LEDGER.expected(t.uuid);
            boolean mightBeGuard = t.guardMode == HealthTraceData.GuardMode.ENFORCE
                    && Float.isFinite(expected) && expected > 1.0E-3F;
            entity.discard();
            boolean didRemove = entity.isRemoved();
            if (didRemove) {
                removed++;
            } else {
                blocked++;
                if (mightBeGuard) {
                    guardBlocked++;
                }
            }
            logDiscardAttempt(t, entity, didRemove, mightBeGuard && !didRemove, expected);
        }
        return new DiscardResult(total, removed, blocked, guardBlocked);
    }

    private static void logDiscardAttempt(HealthTraceData.Tracked t, LivingEntity entity,
                                          boolean removed, boolean guardBlocked, float expected) {
        if (!active) {
            return;
        }
        JsonObject o = base("discard_attempt");
        o.add("target", entityJson(entity, t));
        o.addProperty("removed", removed);
        o.addProperty("guardBlocked", guardBlocked);
        o.addProperty("guardMode", t.guardMode.id());
        HealthTraceStats.putNum(o, "expected", expected);
        HealthTraceStats.putNum(o, "actual", readHealthQuiet(entity));
        write(o);
    }

    public record ReflectResult(int total, int changed, int failed, String error) {
    }

    /**
     * P2 测试刺激：绕过所有 setter，直接用反射改真血。
     *
     * <p>对普通实体只改 {@code LivingEntity.health}；对傀儡同时把 {@code guardedData}
     * 换成一个新的 record（amount=health），否则 {@code getHealth()} 会取 max(super, guardedData)
     * 而看不到变化。P1 的影子账本应在下一 tick 报 unexplained；P2 SHADOW 记 would_restore，
     * ENFORCE 记 restored 并回滚。</p>
     */
    public static ReflectResult reflect(MinecraftServer srv, HealthTraceData.Role role, float health) {
        if (!active) {
            return new ReflectResult(0, 0, 0, "not recording");
        }
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return new ReflectResult(0, 0, 0, "no trace data");
        }
        int total = 0;
        int changed = 0;
        int failed = 0;
        String error = "";
        try {
            // 1.20.1 的 LivingEntity.health 不是普通字段，而是 SynchedEntityData 里的
            // DATA_HEALTH_ID；反射必须走 accessor，否则会像 f_20930_ 那样撞到 long 字段。
            Field dataField = ObfuscationReflectionHelper.findField(Entity.class, "f_19804_");
            dataField.setAccessible(true);
            Field accessorField = ObfuscationReflectionHelper.findField(LivingEntity.class, "f_20961_");
            accessorField.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> healthAccessor =
                    (EntityDataAccessor<Float>) accessorField.get(null);
            Field guardField = GuardedEntity.class.getDeclaredField("guardedData");
            guardField.setAccessible(true);
            for (HealthTraceData.Tracked t : d.tracked()) {
                if (t.role != role) {
                    continue;
                }
                LivingEntity entity = d.resolve(srv, t);
                if (entity == null) {
                    continue;
                }
                total++;
                try {
                    SynchedEntityData data = (SynchedEntityData) dataField.get(entity);
                    data.set(healthAccessor, health);
                    // 不用 data.get：jzyy 这类 coremod 会把 get 重定向；直接读 DataItem.value。
                    HealthTruth.Snapshot truth = HealthTruth.read(entity, false);
                    float stored = truth == null ? Float.NaN : truth.synced();
                    if (!Float.isFinite(stored) || Math.abs(stored - health) > 1.0E-3F) {
                        // P2 ENFORCE 在 SynchedEntityData.set 层拦下了；不要再去改 guardedData。
                        failed++;
                        error = "health data write blocked (stored=" + stored + ")";
                        continue;
                    }
                    if (entity instanceof GuardedEntity guarded) {
                        Object old = guardField.get(guarded);
                        float baseline = old instanceof GuardedEntity.GuardedData dataBefore
                                ? dataBefore.baseline() : health;
                        guardField.set(guarded, new GuardedEntity.GuardedData(health, baseline));
                    }
                    changed++;
                    JsonObject o = base("reflect");
                    o.add("target", entityJson(entity, t));
                    HealthTraceStats.putNum(o, "health", health);
                    o.addProperty("guardMode", t.guardMode.id());
                    write(o);
                } catch (Throwable ex) {
                    failed++;
                    error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                }
            }
        } catch (Throwable ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        return new ReflectResult(total, changed, failed, error);
    }

    /**
     * P2：设置某个角色的 Guard 档位。control 只能 OFF；返回 -2 表示拒绝。
     */
    public static int setGuardMode(MinecraftServer srv, HealthTraceData.Role role,
                                   HealthTraceData.GuardMode mode) {
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return -1;
        }
        if (role == HealthTraceData.Role.CONTROL && mode != HealthTraceData.GuardMode.OFF) {
            return -2;
        }
        int n = d.setGuardMode(role, mode);
        HealthGuard.clear();
        return n;
    }

    public static int guardAllOff(MinecraftServer srv) {
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            return -1;
        }
        int n = d.guardAllOff();
        HealthGuard.clear();
        return n;
    }

    public static List<String> unexplainedLines() {
        List<String> out = new ArrayList<>();
        if (!active) {
            out.add("当前没有在记录；stop 会清空 ledger，下面的 unexplained 只是上一轮统计。"
                    + "要测 poke，先 /ga health start。");
        }
        out.add("ledger=" + (data != null && data.ledgerEnabled) + "  entries=" + LEDGER.size());
        out.addAll(stats.unexplainedLines());
        return out;
    }

    /**
     * P2 诊断：逐只打印真值快照（SynchedEntityData 原值 / guardedData / getHealth / 容器类 / 索引）。
     * 用来验证 5.1 真值读取、5.3 容器类检查、5.4 索引一致性，不依赖外部攻击 mod。
     */
    public static List<String> truthLines(MinecraftServer srv) {
        List<String> out = new ArrayList<>();
        HealthTraceData d = HealthTraceData.get(srv);
        if (d == null) {
            out.add("拿不到存档级追踪数据。");
            return out;
        }
        out.add("truth: active=" + active + " ledger=" + d.ledgerEnabled
                + " tracked=" + d.size());
        for (Tracked t : d.tracked()) {
            LivingEntity entity = d.resolve(srv, t);
            if (entity == null) {
                out.add("  " + t.role.id() + " " + t.uuid + " 未加载/已移除");
                continue;
            }
            HealthTruth.Snapshot truth = HealthTruth.read(entity, true);
            out.add("  " + t.role.id() + " " + HealthTraceData.typeId(entity)
                    + " uuid=" + t.uuid + " guard=" + t.guardMode.id()
                    + (truth == null ? " truth=unavailable" : " " + truth.notes()));
        }
        return out;
    }

    // ------------------------------------------------------------------ P2 Guard 入口

    static HealthTraceData.Tracked trackedFor(LivingEntity entity) {
        return tracked(entity);
    }

    static float ledgerExpected(UUID uuid) {
        return LEDGER.expected(uuid);
    }

    static float ledgerBaseMax(UUID uuid) {
        return LEDGER.baseMax(uuid);
    }

    static int currentTick() {
        return tick;
    }

    /** P2：最近一次 LivingHealEvent 允许的回血量；用于放行 feed 在低血时的回血写。 */
    static float ledgerHealAllowance(UUID uuid) {
        return LEDGER.healAllowance(uuid, tick);
    }

    static void acceptMax(UUID uuid, float max) {
        LEDGER.acceptMax(uuid, max);
    }

    static void acceptBuildMax(UUID uuid, float max, float expected) {
        LEDGER.acceptBuild(uuid, max, expected);
    }

    /** 当前写入是否来自 readAdditionalSaveData（只认 caller，不判断是不是合法读档）。 */
    static boolean isReadAdditionalCaller() {
        try {
            String caller = caller();
            return caller.contains("readAdditionalSaveData") || caller.contains("m_7378_");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前写入是否来自 fantasy 的 true-damage 路径（EntityActuallyHurt / CatchActuallyHurt0）。 */
    static boolean isFantasyHurtCaller() {
        try {
            String caller = caller();
            return caller.contains("com.mega.uom.util.entity.EntityActuallyHurt")
                    || caller.contains("CatchActuallyHurt0Event")
                    || caller.contains("EntityASMUtil");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** fantasy true damage：把 expected 接受为这次写入的健康值（允许负数），不夹取。 */
    static void acceptFantasyWrite(UUID uuid, float amount) {
        LEDGER.acceptFantasyWrite(uuid, amount);
    }

    /**
     * 当前写入是否来自真正的“换部件/改升级/装配”路径。
     * 只有这些路径才允许把 max 变化当成 accepted_build；伤害事件、tick、validate 里
     * 的写即使 build hash 变了，也只更新签名、不改 baseMax。
     */
    static boolean isBuildChangeCaller() {
        try {
            String caller = caller();
            return caller.contains("GolemUpgradeMenu")
                    || caller.contains("GolemDisintegrateMenu")
                    || caller.contains("GolemDisassembleMenu")
                    || caller.contains("GolemAssemble")
                    || caller.contains("GolemPart")
                    || caller.contains("AbstractGolemEntity#updateAttributes")
                    || caller.contains("GolemHolder#summon")
                    || caller.contains("GolemHolder#setEntity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 当前写是否发生在伤害/治疗链里：hurt / actuallyHurt / hurtFinal / heal / ForgeHooks 的
     * LivingHurt/LivingDamage 派发 / L2 的 AttackEventHandler。
     *
     * <p>用途：这些调用栈里的最终写是事件链的一部分，不应该被当成“事件结束后直接写血”。
     * 事件链之外的 setHealth（例如 tick handler、反射写）仍然走 Guard。</p>
     */
    static boolean isDamageChainWrite() {
        try {
            List<String> frames = WALKER.walk(s -> s
                    .filter(f -> !isNoiseFrame(f.getClassName()))
                    .filter(f -> !isInjectedFrame(f))
                    .limit(24)
                    .map(f -> f.getClassName() + "#" + f.getMethodName())
                    .collect(Collectors.toList()));
            for (String frame : frames) {
                int hash = frame.indexOf('#');
                String cls = hash < 0 ? frame : frame.substring(0, hash);
                String method = hash < 0 ? "" : frame.substring(hash + 1);
                if (isDamageChainFrame(cls, method)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDamageChainFrame(String cls, String method) {
        if (cls.equals("net.minecraft.world.entity.LivingEntity")) {
            return switch (method) {
                case "hurt", "m_6469_", "actuallyHurt", "m_6475_", "heal", "m_5634_" -> true;
                default -> false;
            };
        }
        if (cls.equals("dev.xkmc.modulargolems.content.entity.common.GuardedEntity")) {
            return switch (method) {
                case "actuallyHurt", "m_6475_", "hurtFinal", "hurtFinalImpl", "heal", "m_5634_" -> true;
                default -> false;
            };
        }
        if (cls.equals("net.minecraftforge.common.ForgeHooks")) {
            return method.equals("onLivingHurt") || method.equals("onLivingDamage");
        }
        if (cls.equals("dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler")) {
            return switch (method) {
                case "onActuallyHurtPre", "onActuallyHurtPost", "onDamagePost" -> true;
                default -> false;
            };
        }
        return false;
    }

    /** 事件链内的最终写：把 ledger expected 对齐到实际写入值，避免增伤被 reconcile 回滚。 */
    static void acceptDamageWrite(UUID uuid, float amount) {
        LEDGER.acceptDamageWrite(uuid, amount);
    }

    /** 傀儡自己的 guardedData 校验/同步不算外部写；P2 放行，避免和 validateGuardedData 对打。 */
    static boolean isInternalGuardedSync() {
        try {
            String caller = caller();
            // 读档：readAdditionalSaveData 会把 NBT 里的 Health 写回实体，这是合法加载。
            // 但它也是 jzyy 的写血入口，所以不能只看 caller 方法名；必须顺着栈确认
            // 这次调用真的来自 Entity#load / EntityType#load / GolemHolder#summon。
            if (caller.contains("readAdditionalSaveData") || caller.contains("m_7378_")) {
                return isLegitSaveLoad();
            }
            return caller.contains("GuardedEntity#validateGuardedData")
                    // 装配/改升级/换部件/换装备：合法的 build/attribute 重算。
                    || caller.contains("GolemUpgradeMenu")
                    || caller.contains("GolemDisintegrateMenu")
                    || caller.contains("GolemAssemble")
                    || caller.contains("GolemPart")
                    || caller.contains("AbstractGolemEntity#updateAttributes")
                    // GolemHolder 原来整类放行太宽；只留召唤/收回持有物。
                    || caller.contains("GolemHolder#summon")
                    || caller.contains("GolemHolder#setEntity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 只有真正的实体 NBT 读档/召唤才允许借 {@code readAdditionalSaveData} 写血。
     *
     * <p>合法栈里必须同时出现 {@code readAdditionalSaveData} 和 {@code Entity#load}
     * （或 EntityType 的 load/create、GolemHolder#summon）；出现反射/MethodHandle、
     * {@code LivingEntityUtil}、{@code EntityActuallyHurt}、{@code AbsoluteSeverance}
     * 就判定为借读档接口的外部写，返回 false。</p>
     */
    static boolean isLegitSaveLoad() {
        try {
            List<String> frames = WALKER.walk(s -> s
                    .filter(f -> !isNoiseFrame(f.getClassName()))
                    .filter(f -> !isInjectedFrame(f))
                    .limit(24)
                    .map(f -> f.getClassName() + "#" + f.getMethodName())
                    .collect(Collectors.toList()));
            boolean readAdditional = false;
            boolean load = false;
            for (String frame : frames) {
                int hash = frame.indexOf('#');
                String cls = hash < 0 ? frame : frame.substring(0, hash);
                String method = hash < 0 ? "" : frame.substring(hash + 1);
                if (isForbiddenSaveFrame(cls, method)) {
                    return false;
                }
                if (method.equals("readAdditionalSaveData") || method.equals("m_7378_")) {
                    readAdditional = true;
                }
                if (isEntityLoadFrame(cls, method)) {
                    load = true;
                }
            }
            return readAdditional && load;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isForbiddenSaveFrame(String cls, String method) {
        if (cls.contains("LivingEntityUtil") || cls.contains("EntityActuallyHurt")
                || cls.contains("EntityASMUtil") || cls.contains("AbsoluteSeverance")) {
            return true;
        }
        // Method.invoke / MethodHandle.invoke 的类已经是这些包；不要再按方法名 invoke 一刀切，
        // 否则 Forge 事件总线（ASMEventHandler#invoke）下的合法召唤会被误判。
        return cls.startsWith("java.lang.reflect.") || cls.startsWith("jdk.internal.reflect.")
                || cls.startsWith("sun.reflect.") || cls.startsWith("java.lang.invoke.")
                || method.equals("invokeExact") || method.equals("invokeBasic");
    }

    private static boolean isEntityLoadFrame(String cls, String method) {
        if (cls.equals("net.minecraft.world.entity.Entity")) {
            return method.equals("load") || method.equals("m_20258_");
        }
        if (cls.equals("net.minecraft.world.entity.EntityType")) {
            return switch (method) {
                case "load", "create", "loadEntityRecursive",
                     "m_20642_", "m_20645_", "m_20669_", "m_185991_", "m_185988_",
                     "m_262455_", "m_262451_" -> true;
                default -> false;
            };
        }
        if (cls.startsWith("dev.xkmc.modulargolems.") && cls.endsWith("GolemHolder")) {
            return method.equals("summon") || method.equals("createDummy");
        }
        return false;
    }

    /**
     * 傀儡收回：AbstractGolemEntity#toItem 会先 GolemHolder.setEntity(this) 造同 UUID 持有物，
     * 再 discard() 实体。P2 不能拦这个 DISCARDED，否则实体还在、手里多一个同 ID 傀儡。
     */
    static boolean isGolemRecall() {
        try {
            String caller = caller();
            return caller.contains("AbstractGolemEntity#toItem")
                    || caller.contains("AbstractGolemEntity#returnToInventory");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** P2：把 Test 实体回滚到 expected；傀儡走 guardedData，普通实体走 setHealth。 */
    static void restoreToExpected(LivingEntity entity, float expected) {
        if (entity instanceof GuardedEntity guarded) {
            // 不走 setHealth（会拒绝向下写）；直接设置 guardedData，和 P0 的测试刺激同一条路。
            guarded.setGuardedDataImpl(expected, expected > readTruthQuiet(entity), false);
        } else {
            entity.setHealth(expected);
        }
    }

    /** 自家模板（tech_expansion）的 maxHealth 变化：接受并更新 ledger，不算 HEALTH_SEAL。 */
    static void logTemplateMax(LivingEntity entity, HealthTraceData.Tracked t,
                               float oldMax, float newMax, int level) {
        if (!active || entity == null || t == null) {
            return;
        }
        JsonObject o = base("guard");
        o.add("target", entityJson(entity, t));
        o.addProperty("op", "max_health");
        o.addProperty("decision", "accepted_template");
        HealthTraceStats.putNum(o, "arg", newMax);
        HealthTraceStats.putNum(o, "expected", oldMax);
        HealthTraceStats.putNum(o, "actual", readHealthQuiet(entity));
        HealthTraceStats.putNum(o, "maxHealth", entity.getMaxHealth());
        o.addProperty("mode", t.guardMode.id());
        o.addProperty("notes", "tech_expansion=" + level);
        o.addProperty("caller", caller());
        write(o);
        PrivateTestbench.LOGGER.info(
                "[GA-Guard] max_health accepted_template role={} level={} oldMax={} newMax={}",
                t.role.id(), level, oldMax, newMax);
    }

    /** 换部件/改升级列表导致的 maxHealth 重算：接受，不算 HEALTH_SEAL。 */
    static void logBuildMax(LivingEntity entity, HealthTraceData.Tracked t,
                            float oldMax, float newMax) {
        if (!active || entity == null || t == null) {
            return;
        }
        JsonObject o = base("guard");
        o.add("target", entityJson(entity, t));
        o.addProperty("op", "max_health");
        o.addProperty("decision", "accepted_build");
        HealthTraceStats.putNum(o, "arg", newMax);
        HealthTraceStats.putNum(o, "expected", oldMax);
        HealthTraceStats.putNum(o, "actual", readHealthQuiet(entity));
        HealthTraceStats.putNum(o, "maxHealth", entity.getMaxHealth());
        o.addProperty("mode", t.guardMode.id());
        o.addProperty("notes", "materials/upgrades changed");
        o.addProperty("caller", caller());
        write(o);
        PrivateTestbench.LOGGER.info(
                "[GA-Guard] max_health accepted_build role={} oldMax={} newMax={}",
                t.role.id(), oldMax, newMax);
    }

    static void guardLog(LivingEntity entity, HealthTraceData.Tracked t, String op, String decision,
                         float arg, float expected, float actual, String notes) {
        if (!active || entity == null || t == null) {
            return;
        }
        JsonObject o = base("guard");
        o.add("target", entityJson(entity, t));
        o.addProperty("op", op);
        o.addProperty("decision", decision);
        HealthTraceStats.putNum(o, "arg", arg);
        HealthTraceStats.putNum(o, "expected", expected);
        HealthTraceStats.putNum(o, "actual", actual);
        HealthTraceStats.putNum(o, "maxHealth", entity.getMaxHealth());
        o.addProperty("mode", t.guardMode.id());
        if (notes != null && !notes.isEmpty()) {
            o.addProperty("notes", notes);
        }
        o.addProperty("caller", caller());
        write(o);
        stats.guard(t.role.id(), decision);
        PrivateTestbench.LOGGER.warn(
                "[GA-Guard] {} {} role={} mode={} arg={} expected={} actual={} note={}",
                op, decision, t.role.id(), t.guardMode.id(), arg, expected, actual, notes);
    }

    /** 取消单个追踪时清掉采样状态，避免重新追踪时把旧血量当成一次 delta。 */
    public static void onUntrack(UUID uuid) {
        if (uuid != null) {
            LAST_HEALTH.remove(uuid);
            LAST_MAX_HEALTH.remove(uuid);
            GET_COUNTS.remove(uuid);
            LEDGER.detach(uuid);
            LAST_WRITE.remove(uuid);
            RECENT_WRITES.remove(uuid);
        }
    }

    /** 新追踪一个实体时立刻采一次血量，避免第一次 tick 只做初始化、不记录。 */
    public static void onTrack(LivingEntity entity) {
        if (entity != null) {
            LAST_HEALTH.put(entity.getUUID(), readHealthQuiet(entity));
            LAST_MAX_HEALTH.put(entity.getUUID(), entity.getMaxHealth());
            if (data != null && data.ledgerEnabled) {
                LEDGER.attach(entity);
            }
        }
    }

    public static void onClearTracked() {
        LAST_HEALTH.clear();
        LAST_MAX_HEALTH.clear();
        GET_COUNTS.clear();
        PENDING_L2.clear();
        LEDGER.clear();
        LAST_WRITE.clear();
        RECENT_WRITES.clear();
        HealthGuard.clear();
    }

    // ------------------------------------------------------------------ 事件入口

    public static void onLivingAttack(LivingAttackEvent event) {
        if (!active) {
            return;
        }
        try {
            event("living_attack", event.getEntity(), event.getAmount(), event.isCanceled(), event.getSource());
        } catch (Throwable t) {
            fail("LivingAttackEvent", t);
        }
    }

    public static void onLivingHurt(LivingHurtEvent event) {
        if (!active) {
            return;
        }
        try {
            event("living_hurt", event.getEntity(), event.getAmount(), event.isCanceled(), event.getSource());
        } catch (Throwable t) {
            fail("LivingHurtEvent", t);
        }
    }

    public static void onLivingDamage(LivingDamageEvent event) {
        if (!active) {
            return;
        }
        try {
            event("living_damage", event.getEntity(), event.getAmount(), event.isCanceled(), event.getSource());
            if (data != null && data.ledgerEnabled && !event.isCanceled()) {
                LEDGER.onDamage(event.getEntity(), event.getAmount(), tick);
            }
        } catch (Throwable t) {
            fail("LivingDamageEvent", t);
        }
    }

    public static void onLivingHeal(LivingHealEvent event) {
        if (!active) {
            return;
        }
        try {
            event("living_heal", event.getEntity(), event.getAmount(), event.isCanceled(), null);
            if (data != null && data.ledgerEnabled && !event.isCanceled()) {
                LEDGER.onHeal(event.getEntity(), event.getAmount(), tick);
            }
        } catch (Throwable t) {
            fail("LivingHealEvent", t);
        }
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!active) {
            return;
        }
        try {
            LivingEntity entity = event.getEntity();
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            event("living_death", entity, 0.0F, false, event.getSource());
            stats.death(role);
            if (data != null && data.ledgerEnabled && t != null) {
                HealthLedger.Finding finding = LEDGER.onDeath(entity, tick);
                if (finding != null) {
                    stats.unexplained(role);
                    logUnexplained(finding, t);
                }
            }
        } catch (Throwable t) {
            fail("LivingDeathEvent", t);
        }
    }

    private static void event(String name, LivingEntity target, float amount, boolean canceled,
                              DamageSource source) {
        if (target == null || target.level().isClientSide) {
            return;
        }
        Tracked t = tracked(target);
        String role = roleOf(t);
        if (role == null) {
            return;
        }
        JsonObject o = base("event");
        o.addProperty("event", name);
        o.add("target", entityJson(target, t));
        HealthTraceStats.putNum(o, "amount", amount);
        o.addProperty("canceled", canceled);
        if (source != null) {
            addSource(o, source);
        }
        write(o);
        stats.event(role, name, sourceId(source), HealthTraceData.typeId(target), amount, canceled);
    }

    // ------------------------------------------------------------------ setHealth / getHealth（mixin 入口）

    public static void onLivingSetHealthHead(LivingEntity entity, float amount) {
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float before = readHealthQuiet(entity);
            float max = entity.getMaxHealth();
            int depth = WRITE_DEPTH.get();
            WRITE_DEPTH.set(depth + 1);
            LIVING_WRITE.set(new WriteCtx("living.setHealth", amount, before, max,
                    "", depth > 0, true, caller(), stack()));
        } catch (Throwable e) {
            fail("living.setHealth HEAD", e);
        }
    }

    public static void onLivingSetHealthReturn(LivingEntity entity, float amount) {
        WriteCtx ctx = LIVING_WRITE.get();
        if (ctx == null) {
            return;
        }
        LIVING_WRITE.remove();
        WRITE_DEPTH.set(Math.max(0, WRITE_DEPTH.get() - 1));
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float after = readHealthQuiet(entity);
            writeLine(entity, t, role, ctx, after, false);
        } catch (Throwable e) {
            fail("living.setHealth RETURN", e);
        }
    }

    public static void onGuardedSetHealthHead(GuardedEntity entity, float amount, boolean dataPresent) {
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float before = dataPresent ? entity.getGuardedDataImpl() : Float.NaN;
            float max = entity.getMaxHealth();
            int depth = WRITE_DEPTH.get();
            WRITE_DEPTH.set(depth + 1);
            GUARDED_WRITE.set(new WriteCtx("guarded.setHealth", amount, before, max,
                    "", depth > 0, dataPresent, caller(), stack()));
        } catch (Throwable e) {
            fail("guarded.setHealth HEAD", e);
        }
    }

    public static void onGuardedSetHealthReturn(GuardedEntity entity, float amount, boolean dataPresentNow) {
        WriteCtx ctx = GUARDED_WRITE.get();
        if (ctx == null) {
            return;
        }
        GUARDED_WRITE.remove();
        WRITE_DEPTH.set(Math.max(0, WRITE_DEPTH.get() - 1));
        // 如果方法前后 guardedData 都还是 null，说明它没有真正写；这时读它会触发懒初始化。
        if (!dataPresentNow || !active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float after = entity.getGuardedDataImpl();
            boolean rejected = ctx.beforeKnown()
                    && Math.abs(after - ctx.before()) < 1.0E-4F
                    && Math.abs(amount - ctx.before()) > 1.0E-4F;
            writeLine(entity, t, role, ctx, after, rejected);
        } catch (Throwable e) {
            fail("guarded.setHealth RETURN", e);
        }
    }

    public static void onGuardedDataHead(GuardedEntity entity, float amount, boolean sync,
                                         boolean force, boolean dataPresent) {
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float before = dataPresent ? entity.getGuardedDataImpl() : Float.NaN;
            float max = entity.getMaxHealth();
            int depth = WRITE_DEPTH.get();
            WRITE_DEPTH.set(depth + 1);
            GUARDED_DATA_WRITE.set(new WriteCtx("guarded.data", amount, before, max,
                    "sync=" + sync + ", force=" + force, depth > 0, dataPresent, caller(), stack()));
        } catch (Throwable e) {
            fail("guarded.data HEAD", e);
        }
    }

    public static void onGuardedDataReturn(GuardedEntity entity, float amount, boolean sync,
                                           boolean force, boolean dataPresentNow) {
        WriteCtx ctx = GUARDED_DATA_WRITE.get();
        if (ctx == null) {
            return;
        }
        GUARDED_DATA_WRITE.remove();
        WRITE_DEPTH.set(Math.max(0, WRITE_DEPTH.get() - 1));
        if (!dataPresentNow || !active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            float after = entity.getGuardedDataImpl();
            writeLine(entity, t, role, ctx, after, false);
        } catch (Throwable e) {
            fail("guarded.data RETURN", e);
        }
    }

    public static void onLivingGetHealth(LivingEntity entity, float value) {
        getHealth(entity, "living.getHealth", value);
    }

    public static void onGuardedGetHealth(GuardedEntity entity, float value) {
        getHealth(entity, "guarded.getHealth", value);
    }

    private static void getHealth(LivingEntity entity, String op, float value) {
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            Tracked t = tracked(entity);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            HealthTraceData d = data;
            if (d == null || !d.logGetHealth) {
                return;
            }
            int count = GET_COUNTS.merge(entity.getUUID(), 1, Integer::sum);
            int every = Math.max(1, d.sampleEvery);
            boolean sampled = count == 1 || (count % every == 0);
            String caller = sampled ? caller() : null;
            stats.read(role, caller, sampled);
            if (!sampled) {
                return;
            }
            JsonObject o = base("read");
            o.addProperty("op", op);
            o.add("target", entityJson(entity, t));
            HealthTraceStats.putNum(o, "value", value);
            o.addProperty("caller", caller);
            // getHealth 是高频路径：只记一个 caller，不写 10 帧栈。
            // 之前 3 个傀儡 1 分钟能写 10MB，99% 是这里的 stack 数组。
            write(o);
        } catch (Throwable e) {
            fail(op, e);
        }
    }

    private static void writeLine(LivingEntity entity, Tracked t, String role, WriteCtx ctx,
                                  float after, boolean rejected) {
        JsonObject o = base("write");
        o.addProperty("op", ctx.op());
        o.add("target", entityJson(entity, t));
        HealthTraceStats.putNum(o, "arg", ctx.arg());
        HealthTraceStats.putNum(o, "before", ctx.before());
        HealthTraceStats.putNum(o, "after", after);
        HealthTraceStats.putNum(o, "delta", ctx.before() - after);
        HealthTraceStats.putNum(o, "max", ctx.max());
        o.addProperty("beforeKnown", ctx.beforeKnown());
        o.addProperty("argFinite", Float.isFinite(ctx.arg()));
        o.addProperty("clamped", ctx.arg() < 0.0F || ctx.arg() > ctx.max());
        o.addProperty("rejected", rejected);
        o.addProperty("nested", ctx.nested());
        o.addProperty("caller", ctx.caller());
        o.add("stack", stackJson(ctx.stack()));
        if (ctx.notes() != null && !ctx.notes().isEmpty()) {
            o.addProperty("notes", ctx.notes());
        }
        L2Applied applied = takeL2(entity, ctx, after);
        if (applied != null) {
            o.addProperty("l2", true);
            o.addProperty("l2Seq", applied.pending().seq());
            HealthTraceStats.putNum(o, "l2Fin", applied.pending().fin());
            HealthTraceStats.putNum(o, "l2Applied", applied.applied());
            HealthTraceStats.putNum(o, "l2Ratio", applied.ratio());
        } else {
            o.addProperty("l2", false);
        }
        write(o);
        LAST_WRITE.put(entity.getUUID(),
                new LastWrite(ctx.op(), ctx.caller(), ctx.before(), after, seq, tick));
        Deque<LastWrite> recent = RECENT_WRITES.computeIfAbsent(entity.getUUID(),
                k -> new ArrayDeque<>());
        recent.addLast(new LastWrite(ctx.op(), ctx.caller(), ctx.before(), after, seq, tick));
        while (recent.size() > RECENT_WRITE_LIMIT) {
            recent.removeFirst();
        }
        boolean damageWrite = Float.isFinite(ctx.before()) && Float.isFinite(after)
                && after < ctx.before() - 1.0E-4F;
        boolean healWrite = Float.isFinite(ctx.before()) && Float.isFinite(after)
                && after > ctx.before() + 1.0E-4F;
        stats.write(role, ctx.op(), ctx.caller(), rejected, applied != null, ctx.nested(),
                damageWrite, healWrite);
        if (applied != null) {
            JsonObject a = base("l2_applied");
            a.add("target", entityJson(entity, t));
            a.addProperty("op", ctx.op());
            a.addProperty("pendingSeq", applied.pending().seq());
            HealthTraceStats.putNum(a, "raw", applied.pending().raw());
            HealthTraceStats.putNum(a, "base", applied.pending().base());
            HealthTraceStats.putNum(a, "fin", applied.pending().fin());
            HealthTraceStats.putNum(a, "before", ctx.before());
            HealthTraceStats.putNum(a, "after", after);
            HealthTraceStats.putNum(a, "applied", applied.applied());
            HealthTraceStats.putNum(a, "ratio", applied.ratio());
            a.addProperty("source", applied.pending().source());
            write(a);
            stats.l2Applied(role, applied.applied(), applied.pending().fin(), applied.ratio());
        }
    }

    // ------------------------------------------------------------------ 移除 / 客户端移除包

    public static void onSetRemoved(Entity entity, Entity.RemovalReason reason) {
        if (!active || isQuiet()) {
            return;
        }
        try {
            if (offThread() || entity.level().isClientSide) {
                return;
            }
            if (!(entity instanceof LivingEntity living)) {
                return;
            }
            Tracked t = tracked(living);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            JsonObject o = base("remove");
            o.addProperty("reason", reason == null ? "null" : reason.name());
            o.add("target", entityJson(living, t));
            o.addProperty("alreadyRemoved", entity.isRemoved());
            // 收回也是 DISCARDED：toItem 先造持有物再 discard，不是“活着被外部清除”。
            boolean recall = reason == Entity.RemovalReason.DISCARDED && isGolemRecall();
            o.addProperty("recall", recall);
            write(o);
            stats.removal(role);
            // 只有 KILLED/DISCARDED 才是“生命终止”意义上的移除；UNLOADED/CHANGED_DIMENSION
            // 不是外部 erase，不能误报 removed_alive。收回的 DISCARDED 也不算。
            boolean terminal = (reason == Entity.RemovalReason.KILLED
                    || reason == Entity.RemovalReason.DISCARDED) && !recall;
            if (terminal && data != null && data.ledgerEnabled && t != null) {
                HealthLedger.Finding finding = LEDGER.onRemoved(living, tick);
                if (finding != null) {
                    stats.unexplained(role);
                    logUnexplained(finding, t);
                }
            }
            LAST_HEALTH.remove(entity.getUUID());
            LAST_MAX_HEALTH.remove(entity.getUUID());
            LAST_WRITE.remove(entity.getUUID());
            RECENT_WRITES.remove(entity.getUUID());
            PENDING_L2.remove(entity.getId());
        } catch (Throwable e) {
            fail("Entity.setRemoved", e);
        }
    }

    public static void onClientRemove(int[] ids) {
        if (!active || ids == null || ids.length == 0) {
            return;
        }
        try {
            if (server == null || offThread()) {
                return;
            }
            HealthTraceData d = data;
            if (d == null) {
                return;
            }
            JsonArray all = new JsonArray();
            JsonArray trackedArr = new JsonArray();
            for (int id : ids) {
                all.add(id);
                HealthTraceData.Tracked t = d.byEntityId(id);
                if (t == null) {
                    continue;
                }
                JsonObject o = trackedJson(t);
                LivingEntity entity = d.resolve(server, t);
                o.addProperty("serverRemoved", entity != null && entity.isRemoved());
                o.addProperty("serverAlive", entity != null && !entity.isRemoved()
                        && readHealthQuiet(entity) > 0.0F);
                trackedArr.add(o);
                stats.clientRemove(t.role.id());
            }
            if (trackedArr.isEmpty()) {
                return;
            }
            JsonObject o = base("client_remove");
            o.add("ids", all);
            o.add("tracked", trackedArr);
            write(o);
        } catch (Throwable e) {
            fail("ClientboundRemoveEntitiesPacket", e);
        }
    }

    // ------------------------------------------------------------------ l2 AttackListener

    public static void registerL2() {
        try {
            AttackEventHandler.register(9500, new L2Probe());
            PrivateTestbench.LOGGER.info("[GA-Health] l2 observer registered at priority 9500（只读）");
        } catch (Throwable t) {
            PrivateTestbench.LOGGER.warn("[GA-Health] l2 observer registration failed: {}", t.toString());
        }
    }

    private static final class L2Probe implements AttackListener {
        @Override
        public void onAttack(AttackCache cache, ItemStack weapon) {
            l2("onAttack", cache, weapon);
        }

        @Override
        public void postAttack(AttackCache cache, LivingAttackEvent event, ItemStack weapon) {
            l2("postAttack", cache, weapon);
        }

        @Override
        public void onHurt(AttackCache cache, ItemStack weapon) {
            l2("onHurt", cache, weapon);
        }

        @Override
        public void postHurt(AttackCache cache, LivingHurtEvent event, ItemStack weapon) {
            l2("postHurt", cache, weapon);
        }

        @Override
        public void onDamage(AttackCache cache, ItemStack weapon) {
            l2("onDamage", cache, weapon);
        }

        @Override
        public void onDamageFinalized(AttackCache cache, ItemStack weapon) {
            l2("finalized", cache, weapon);
        }
    }

    private static void l2(String stage, AttackCache cache, ItemStack weapon) {
        if (!active) {
            return;
        }
        try {
            if (offThread()) {
                return;
            }
            LivingEntity target = cache.getAttackTarget();
            if (target == null || target.level().isClientSide) {
                return;
            }
            Tracked t = tracked(target);
            String role = roleOf(t);
            if (role == null) {
                return;
            }
            boolean finalized = "finalized".equals(stage);
            float raw = Float.NaN;
            float base = Float.NaN;
            float fin = Float.NaN;
            // 只在 finalized 读累加器。l2 的 getPreDamage/getDamageDealt 在更早的阶段会
            // 打 "damage not calculated yet" ERROR（虽然异常被我们吞了，但日志会刷屏）。
            if (finalized) {
                try {
                    raw = cache.getPreDamageOriginal();
                } catch (Throwable ignored) {
                }
                try {
                    base = cache.getPreDamage();
                } catch (Throwable ignored) {
                }
                try {
                    fin = cache.getDamageDealt();
                } catch (Throwable ignored) {
                }
            }
            float health = readHealthQuiet(target);
            DamageSource source = damageSource(cache);

            JsonObject o = base("l2");
            o.addProperty("stage", stage);
            o.add("target", entityJson(target, t));
            if (finalized) {
                HealthTraceStats.putNum(o, "raw", raw);
                HealthTraceStats.putNum(o, "base", base);
                HealthTraceStats.putNum(o, "fin", fin);
            }
            HealthTraceStats.putNum(o, "health", health);
            o.addProperty("source", sourceId(source));
            o.addProperty("weapon", itemId(weapon));
            addAttacker(o, cache);
            addEventSnapshot(o, cache);
            write(o);

            if (finalized && fin > 0.0F && health > 0.0F) {
                PendingL2 p = new PendingL2(seq, tick, raw, base, fin, health, sourceId(source),
                        role, target.getUUID().toString(), HealthTraceData.typeId(target), target.getId());
                PendingL2 old = PENDING_L2.put(target.getId(), p);
                if (old != null) {
                    writeUnmatched(old, "replaced");
                }
                stats.l2Finalized(role, raw, base, fin);
            }
        } catch (Throwable t) {
            fail("l2 " + stage, t);
        }
    }

    private static void addEventSnapshot(JsonObject o, AttackCache cache) {
        try {
            LivingAttackEvent attack = cache.getLivingAttackEvent();
            if (attack != null) {
                HealthTraceStats.putNum(o, "attackAmount", attack.getAmount());
                o.addProperty("attackCanceled", attack.isCanceled());
            }
        } catch (Throwable ignored) {
        }
        try {
            LivingHurtEvent hurt = cache.getLivingHurtEvent();
            if (hurt != null) {
                HealthTraceStats.putNum(o, "hurtAmount", hurt.getAmount());
                o.addProperty("hurtCanceled", hurt.isCanceled());
            }
        } catch (Throwable ignored) {
        }
        try {
            LivingDamageEvent damage = cache.getLivingDamageEvent();
            if (damage != null) {
                HealthTraceStats.putNum(o, "damageAmount", damage.getAmount());
                o.addProperty("damageCanceled", damage.isCanceled());
            }
        } catch (Throwable ignored) {
        }
    }

    private static DamageSource damageSource(AttackCache cache) {
        try {
            if (cache.getLivingDamageEvent() != null) {
                return cache.getLivingDamageEvent().getSource();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cache.getLivingHurtEvent() != null) {
                return cache.getLivingHurtEvent().getSource();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cache.getLivingAttackEvent() != null) {
                return cache.getLivingAttackEvent().getSource();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void addAttacker(JsonObject o, AttackCache cache) {
        try {
            LivingEntity attacker = cache.getAttacker();
            if (attacker != null) {
                o.addProperty("attackerType", HealthTraceData.typeId(attacker));
                o.addProperty("attackerUuid", attacker.getUUID().toString());
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ l2 配对

    private static L2Applied takeL2(LivingEntity entity, WriteCtx ctx, float after) {
        // 嵌套写入（GuardedEntity.setGuardedDataImpl 内部的 super.setHealth）不能消费 pending：
        // 否则真正的 guarded.data 会变成 unpaired，而 applied 被错误归到同步写。
        if (ctx.nested()) {
            return null;
        }
        if (!ctx.beforeKnown() || !Float.isFinite(after)) {
            return null;
        }
        PendingL2 pending = PENDING_L2.get(entity.getId());
        if (pending == null) {
            return null;
        }
        if (tick > pending.tick() + 1) {
            PENDING_L2.remove(entity.getId());
            writeUnmatched(pending, "expired");
            return null;
        }
        // 容差必须很小：傀儡的 nested super.setHealth 发生在 guardedData 已经更新之后，
        // 它的 before 是"新血量"。用 0.05 会让 <=0.05 的伤害把 nested 写入误配成 L2 落地。
        if (Math.abs(pending.healthBefore() - ctx.before()) > 1.0E-3F) {
            return null;
        }
        float applied = ctx.before() - after;
        if (applied < -1.0E-3F) {
            return null;
        }
        PENDING_L2.remove(entity.getId());
        float ratio = pending.fin() <= 0.0F ? 0.0F : applied / pending.fin();
        return new L2Applied(pending, applied, ratio);
    }

    private static void expirePending() {
        if (PENDING_L2.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, PendingL2>> it = PENDING_L2.entrySet().iterator();
        while (it.hasNext()) {
            PendingL2 p = it.next().getValue();
            if (tick > p.tick() + 2) {
                it.remove();
                writeUnmatched(p, "expired");
            }
        }
    }

    private static void writeUnmatched(PendingL2 p, String reason) {
        JsonObject o = base("l2_unmatched");
        o.addProperty("reason", reason);
        o.addProperty("pendingSeq", p.seq());
        o.addProperty("uuid", p.uuid());
        o.addProperty("type", p.type());
        o.addProperty("role", p.role());
        o.addProperty("eid", p.entityId());
        HealthTraceStats.putNum(o, "raw", p.raw());
        HealthTraceStats.putNum(o, "base", p.base());
        HealthTraceStats.putNum(o, "fin", p.fin());
        HealthTraceStats.putNum(o, "healthBefore", p.healthBefore());
        o.addProperty("source", p.source());
        write(o);
    }

    // ------------------------------------------------------------------ P1 影子账本

    /** P1：把账本的不一致写成 JSONL，并打一条 WARN（只在 actual 变化时触发，不刷屏）。 */
    private static void logUnexplained(HealthLedger.Finding f, Tracked t) {
        if (f == null || !active) {
            return;
        }
        JsonObject o = base("unexplained");
        o.addProperty("reason", f.reason);
        o.add("target", entityJson(f.entity, t));
        HealthTraceStats.putNum(o, "expected", f.expected);
        HealthTraceStats.putNum(o, "actual", f.actual);
        HealthTraceStats.putNum(o, "delta", f.delta());
        HealthTraceStats.putNum(o, "maxHealth", f.entity.getMaxHealth());
        o.addProperty("role", t == null ? "all" : t.role.id());
        o.addProperty("lastEvent", f.entry.lastEvent);
        o.addProperty("lastEventAge", f.entry.lastEventTick == Long.MIN_VALUE
                ? -1L : (long) tick - f.entry.lastEventTick);
        o.addProperty("unexplainedTicks", f.entry.unexplainedTicks);
        HealthTrace.LastWrite lw = f.lastWrite;
        if (lw != null) {
            o.addProperty("writeAge", tick - lw.tick());
            o.addProperty("writeOp", lw.op());
            o.addProperty("writeCaller", lw.caller());
            HealthTraceStats.putNum(o, "writeBefore", lw.before());
            HealthTraceStats.putNum(o, "writeAfter", lw.after());
            o.addProperty("writeSeq", lw.seq());
        }
        Deque<LastWrite> recent = RECENT_WRITES.get(f.entity.getUUID());
        if (recent != null && !recent.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (LastWrite w : recent) {
                JsonObject wo = new JsonObject();
                wo.addProperty("op", w.op());
                wo.addProperty("caller", w.caller());
                HealthTraceStats.putNum(wo, "before", w.before());
                HealthTraceStats.putNum(wo, "after", w.after());
                wo.addProperty("seq", w.seq());
                wo.addProperty("age", tick - w.tick());
                arr.add(wo);
            }
            o.add("recentWrites", arr);
        }
        write(o);
        PrivateTestbench.LOGGER.warn(
                "[GA-Health] UNEXPLAINED {} role={} expected={} actual={} delta={} lastEvent={} write={} caller={}",
                f.reason, t == null ? "all" : t.role.id(), f.expected, f.actual, f.delta(),
                f.entry.lastEvent, lw == null ? "-" : lw.op(), lw == null ? "-" : lw.caller());
    }

    // ------------------------------------------------------------------ tick 采样

    public static void onServerTick(MinecraftServer srv) {
        if (!active) {
            return;
        }
        try {
            if (srv == null) {
                return;
            }
            server = srv;
            tick = (int) srv.overworld().getGameTime();
            HealthTraceData d = data;
            if (d == null) {
                return;
            }
            if (++tickCounter % 20 == 0) {
                d.refresh(srv);
            }
            for (HealthTraceData.Tracked t : d.tracked()) {
                LivingEntity entity = d.resolve(srv, t);
                if (entity == null) {
                    // 区块卸载/玩家下线期间拿不到真实血量；清掉采样，避免重新加载时
                    // 把"这段时间的变化"误记成一次 delta。
                    LAST_HEALTH.remove(t.uuid);
                    LAST_MAX_HEALTH.remove(t.uuid);
                    continue;
                }
                // 追踪时实体可能还没加载（eid=-1）；第一次 resolve 到它时补挂 ledger。
                if (d.ledgerEnabled && LEDGER.entry(t.uuid) == null) {
                    LEDGER.attach(entity);
                    HealthGuard.noteBuild(entity);
                }
                // 自家模板（tech_expansion）和换部件/改升级都会合法改 maxHealth；
                // 先接受，再进 P1 check；否则会被当成 HEALTH_SEAL。
                if (d.ledgerEnabled) {
                    HealthGuard.acceptBuildMax(t, entity, tick);
                    HealthGuard.acceptTemplateMax(t, entity, tick);
                }
                float now = readHealthQuiet(entity);
                float max = entity.getMaxHealth();
                Float prev = LAST_HEALTH.put(t.uuid, now);
                if (prev != null && Math.abs(now - prev) >= 1.0E-4F) {
                    JsonObject o = base("tick_health");
                    o.add("target", entityJson(entity, t));
                    HealthTraceStats.putNum(o, "before", prev);
                    HealthTraceStats.putNum(o, "after", now);
                    HealthTraceStats.putNum(o, "delta", now - prev);
                    o.addProperty("direction", now < prev ? "drop" : "heal");
                    write(o);
                    stats.tick(t.role.id(), prev, now);
                }
                // 最大生命单独采样：toaru HEALTH_SEAL / trim_max_health 可能只改 max，
                // 当前血量在下一 tick 才被连带裁剪，不能把它和 tick_health 绑在一起。
                Float prevMax = LAST_MAX_HEALTH.put(t.uuid, max);
                if (prevMax != null && Math.abs(max - prevMax) >= 1.0E-4F) {
                    JsonObject o = base("tick_max_health");
                    o.add("target", entityJson(entity, t));
                    HealthTraceStats.putNum(o, "before", prevMax);
                    HealthTraceStats.putNum(o, "after", max);
                    HealthTraceStats.putNum(o, "delta", max - prevMax);
                    write(o);
                    stats.maxHealthChange(t.role.id());
                }
                // P1：合法事件更新 expected 后，tick 末尾读 actual；不一致就是事件之外的写血。
                if (d.ledgerEnabled) {
                    HealthLedger.Finding finding = LEDGER.check(entity, tick, LAST_WRITE.get(t.uuid));
                    if (finding != null) {
                        stats.unexplained(t.role.id());
                        logUnexplained(finding, t);
                    }
                    // P2 兜底：反射真血/HEALTH_SEAL/漏网写；只对 test 且 mode != OFF 生效。
                    HealthGuard.reconcile(t, entity, tick);
                }
            }
            expirePending();
            if ((tick & 31) == 0) {
                flush();
            }
        } catch (Throwable t) {
            fail("ServerTickEvent", t);
        }
    }

    // ------------------------------------------------------------------ 输出

    private static JsonObject base(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        return o;
    }

    private static void write(JsonObject o) {
        if (!active || writer == null) {
            return;
        }
        o.addProperty("seq", ++seq);
        o.addProperty("ms", System.currentTimeMillis());
        o.addProperty("tick", tick);
        String line;
        try {
            line = GSON.toJson(o);
        } catch (Throwable t) {
            fail("json", t);
            return;
        }
        synchronized (IO_LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.newLine();
                stats.line();
                if ((seq % FLUSH_EVERY) == 0L) {
                    writer.flush();
                }
            } catch (IOException e) {
                ioFailure("write trace", e);
            }
        }
    }

    private static void flush() {
        synchronized (IO_LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.flush();
            } catch (IOException e) {
                ioFailure("flush trace", e);
            }
        }
    }

    private static void ioFailure(String where, IOException e) {
        PrivateTestbench.LOGGER.error("[GA-Health] {} failed; trace stopped", where, e);
        active = false;
        if (data != null) {
            data.active = false;
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        writer = null;
    }

    private static void writeMeta(String event) {
        JsonObject o = base("meta");
        o.addProperty("event", event);
        o.addProperty("session", file == null ? "?" : file.getFileName().toString());
        HealthTraceData d = data;
        o.addProperty("trackAll", d != null && d.trackAll);
        o.addProperty("sampleEvery", d == null ? 0 : d.sampleEvery);
        o.addProperty("logGetHealth", d == null || d.logGetHealth);
        o.addProperty("ledger", d != null && d.ledgerEnabled);
        o.addProperty("startedAt", startedAt);
        o.addProperty("world", server == null ? "?" : server.getWorldData().getLevelName());
        o.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        o.addProperty("modVersion", net.minecraftforge.fml.ModList.get()
                .getModContainerById("golems_arsenal")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?"));
        JsonArray arr = new JsonArray();
        if (d != null) {
            for (HealthTraceData.Tracked t : d.tracked()) {
                arr.add(trackedJson(t));
            }
        }
        o.add("tracked", arr);
        write(o);
    }

    private static Path writeSummary() {
        if (file == null || stats == null) {
            return null;
        }
        try {
            HealthTraceStats.SessionInfo info = sessionInfo();
            String baseName = file.getFileName().toString().replaceAll("\\.jsonl$", "");
            Path dir = file.getParent();
            Path json = dir.resolve(baseName + "-summary.json");
            Path txt = dir.resolve(baseName + "-summary.txt");
            Files.writeString(json, PRETTY.toJson(stats.toJson(info)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(txt, stats.summaryLines(info), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return json;
        } catch (Throwable t) {
            PrivateTestbench.LOGGER.error("[GA-Health] 写摘要失败", t);
            return null;
        }
    }

    private static HealthTraceStats.SessionInfo sessionInfo() {
        HealthTraceData d = data;
        return new HealthTraceStats.SessionInfo(
                file == null ? "-" : file.getFileName().toString(),
                stats.lines(), startedAt, System.currentTimeMillis(),
                d != null && d.trackAll, d == null ? 0 : d.sampleEvery,
                d == null ? List.of() : new ArrayList<>(d.tracked()));
    }

    // ------------------------------------------------------------------ JSON 片段

    private static JsonObject trackedJson(HealthTraceData.Tracked t) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", t.uuid.toString());
        o.addProperty("role", t.role.id());
        o.addProperty("type", t.type);
        o.addProperty("name", t.name);
        o.addProperty("dim", t.dimension);
        o.addProperty("eid", t.entityId);
        return o;
    }

    private static JsonObject entityJson(Entity entity, Tracked t) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", entity.getUUID().toString());
        o.addProperty("eid", entity.getId());
        o.addProperty("type", HealthTraceData.typeId(entity));
        o.addProperty("name", entity.getName().getString());
        o.addProperty("dim", entity.level().dimension().location().toString());
        o.addProperty("role", t != null ? t.role.id() : "all");
        float health = Float.NaN;
        if (entity instanceof LivingEntity living) {
            health = readHealthQuiet(living);
            HealthTraceStats.putNum(o, "health", health);
            HealthTraceStats.putNum(o, "maxHealth", living.getMaxHealth());
            HealthTraceStats.putNum(o, "absorption", living.getAbsorptionAmount());
            o.addProperty("invulnerableTime", living.invulnerableTime);
        }
        o.addProperty("dead", Float.isFinite(health) && health <= 0.0F);
        o.addProperty("removed", entity.isRemoved());
        // 不要调 LivingEntity#isAlive：它内部会再读一次 getHealth，制造一条多余 read 日志。
        o.addProperty("alive", !entity.isRemoved() && (!Float.isFinite(health) || health > 0.0F));
        return o;
    }

    private static void addSource(JsonObject o, DamageSource source) {
        o.addProperty("source", source.getMsgId());
        try {
            source.typeHolder().unwrapKey().ifPresent(
                    key -> o.addProperty("damageType", key.location().toString()));
        } catch (Throwable ignored) {
        }
        Entity attacker = source.getEntity();
        if (attacker != null) {
            o.addProperty("attackerType", HealthTraceData.typeId(attacker));
            o.addProperty("attackerUuid", attacker.getUUID().toString());
        }
        Entity direct = source.getDirectEntity();
        if (direct != null) {
            o.addProperty("directType", HealthTraceData.typeId(direct));
            if (direct != attacker) {
                o.addProperty("directUuid", direct.getUUID().toString());
            }
        }
        try {
            o.addProperty("bypassInvul", source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
            o.addProperty("bypassArmor", source.is(DamageTypeTags.BYPASSES_ARMOR));
        } catch (Throwable ignored) {
        }
    }

    private static JsonArray stackJson(String[] stack) {
        JsonArray arr = new JsonArray();
        if (stack != null) {
            for (String s : stack) {
                arr.add(s);
            }
        }
        return arr;
    }

    // ------------------------------------------------------------------ 呼叫者/栈

    private static String caller() {
        try {
            return WALKER.walk(frames -> frames
                    .filter(f -> !isNoiseFrame(f.getClassName()))
                    .filter(f -> !isInjectedFrame(f))
                    .findFirst()
                    .map(HealthTrace::frameText)
                    .orElse("?"));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String[] stack() {
        try {
            List<String> frames = WALKER.walk(s -> s
                    .filter(f -> !isNoiseFrame(f.getClassName()))
                    .filter(f -> !isInjectedFrame(f))
                    .limit(10)
                    .map(HealthTrace::frameText)
                    .collect(Collectors.toList()));
            return frames.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static boolean isNoiseFrame(String className) {
        return className.startsWith("a_silly_cat.golems_arsenal.testbench")
                || className.startsWith("org.spongepowered.asm")
                || className.startsWith("java.lang.Thread")
                || className.startsWith("jdk.internal");
    }

    private static boolean isInjectedFrame(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        String method = frame.getMethodName();
        // Mixin 生成的 handler 方法（handler$ehc000$ga$setHealthHead 之类）也在目标类里；
        // 不滤掉的话 caller 永远是自己的 handler，而不是真正写血的人。
        if (method.startsWith("handler$") || method.contains("$ga$")) {
            return true;
        }
        if (className.equals("net.minecraft.world.entity.LivingEntity")) {
            return isHealthMethod(method)
                    || method.equals("discard") || method.equals("m_142687_");
        }
        if (className.equals("dev.xkmc.modulargolems.content.entity.common.GuardedEntity")) {
            return isHealthMethod(method) || method.equals("setGuardedDataImpl")
                    || method.equals("discard") || method.equals("m_142687_");
        }
        if (className.equals("net.minecraft.world.entity.Entity")) {
            return method.equals("setRemoved") || method.equals("m_142467_")
                    || method.equals("discard") || method.equals("m_142687_")
                    || method.equals("remove") || method.equals("m_146870_");
        }
        // SynchedEntityData 是 P2 自己的注入目标；跳过它才能看到真正的写血调用者。
        if (className.equals("net.minecraft.network.syncher.SynchedEntityData")) {
            return method.equals("set") || method.equals("m_135381_")
                    || method.equals("m_276349_");
        }
        return false;
    }

    /** 开发名 + 生产 SRG 名都算“目标方法本身”，不是 caller。 */
    private static boolean isHealthMethod(String method) {
        return method.equals("setHealth") || method.equals("m_21153_")
                || method.equals("getHealth") || method.equals("m_21223_");
    }

    private static String frameText(StackWalker.StackFrame frame) {
        String method = frame.getMethodName();
        method = switch (method) {
            case "m_21153_" -> "setHealth";
            case "m_21223_" -> "getHealth";
            case "m_21233_" -> "getMaxHealth";
            case "m_5634_" -> "heal";
            case "m_6469_" -> "hurt";
            case "m_6667_" -> "die";
            case "m_142467_" -> "setRemoved";
            default -> method;
        };
        return frame.getClassName() + "#" + method + ":" + frame.getLineNumber();
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean offThread() {
        MinecraftServer srv = server;
        return srv != null && !srv.isSameThread();
    }

    private static boolean isQuiet() {
        return Boolean.TRUE.equals(QUIET.get());
    }

    /** 读血量但不触发我们的 getHealth 记录；也会被 getHealth mixin 用来防递归。 */
    public static float readHealthQuiet(LivingEntity entity) {
        Boolean old = QUIET.get();
        QUIET.set(Boolean.TRUE);
        try {
            return entity.getHealth();
        } finally {
            QUIET.set(old);
        }
    }

    /**
     * P2 测试刺激/回滚用：优先读 DATA_HEALTH_ID 真值，读不到才回退 getHealth。
     * guardedData 只在 synced 缺失时兜底；不要用 max，否则 maxHealth 被压时会误判满血。
     */
    static float readTruthQuiet(LivingEntity entity) {
        HealthTruth.Snapshot truth = HealthTruth.read(entity, false);
        if (truth != null && Float.isFinite(truth.effective())) {
            return truth.effective();
        }
        return readHealthQuiet(entity);
    }

    private static Tracked tracked(Entity entity) {
        HealthTraceData d = data;
        return d == null ? null : d.get(entity.getUUID());
    }

    private static String roleOf(Tracked t) {
        if (t != null) {
            return t.role.id();
        }
        HealthTraceData d = data;
        return d != null && d.trackAll ? "all" : null;
    }

    private static String sourceId(DamageSource source) {
        return source == null ? "none" : source.getMsgId();
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? stack.getItem().getDescriptionId() : id.toString();
    }

    private static String sanitizeLabel(String label) {
        if (label == null) {
            return "";
        }
        String safe = label.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }

    private static void fail(String where, Throwable t) {
        if (failureLogged) {
            PrivateTestbench.LOGGER.debug("[GA-Health] probe error in {}: {}", where, t.toString());
            return;
        }
        failureLogged = true;
        PrivateTestbench.LOGGER.error(
                "[GA-Health] probe error in {}（这条只打一次；trace 继续，但个别数据可能缺失）", where, t);
    }

    private record WriteCtx(String op, float arg, float before, float max, String notes,
                            boolean nested, boolean beforeKnown, String caller, String[] stack) {
    }

    /** P1 归因用：最近一次写血的参数与调用者。 */
    record LastWrite(String op, String caller, float before, float after, long seq, int tick) {
    }

    private record PendingL2(long seq, int tick, float raw, float base, float fin, float healthBefore,
                             String source, String role, String uuid, String type, int entityId) {
    }

    private record L2Applied(PendingL2 pending, float applied, float ratio) {
    }
}
