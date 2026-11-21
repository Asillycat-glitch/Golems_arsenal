package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.SoulRegistry;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 锚点：这只傀儡"应该是什么样"的权威副本。
 *
 * <p>为什么需要它：匠战妖域的清除手段最终都会走到"实体从世界里没了"——
 * {@code jzyy/MsUtil.superKillEntity} 直接换 {@code EntityLookup}/{@code EntityTickList}、
 * {@code miztinker/EntityRemoveUtil} 调 {@code setRemovalReason(DISCARDED)}、
 * {@code tcondiadema/method/Test} 换 {@code levelCallback}。这些一旦成功，
 * 事后修字段没有意义：对象还在，但世界不再 tick 它，玩家也点不到它。</p>
 *
 * <p>所以这里做两件事，<b>都不看是哪个 mod 干的</b>：</p>
 * <ol>
 *   <li>{@link #reindex}：对象还活着、世界索引里却没有它 → 重新 {@code addFreshEntity} 加回去；</li>
 *   <li>{@link #recreate}：对象已经不可达、但锚点里还有 NBT 快照（含 upgrades / 装备 / 主人 / UUID）
 *       → 按快照重建一只并放回原位。</li>
 * </ol>
 *
 * <p>不会被重建的情况：玩家回收/主动放生（走 {@link #release}）、跨维度、区块卸载、
 * 账本已经归零（真死）。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class Anchor extends SavedData {

    private static final String DATA_NAME = "golems_arsenal_anchor";
    private static final String KEY_TYPE = "ga$type";
    private static final int SNAPSHOT_INTERVAL = 40;
    /**
     * 连续多少次"挂回索引失败"之后改用快照重建。
     *
     * <p>2026-11-09 从 100 降到 20 再降到 2：实测重挂会在同一 tick 内被"标记已移除"顶回来，
     * 而重建才是设计里的兜底。20 次是 10 秒，测试窗口里根本等不到；2 次（1 秒）足够排除抖动。</p>
     */
    private static final int MISSING_LIMIT = 2;
    private static final Map<UUID, Long> LAST_CLIENT_SYNC = new HashMap<>();

    /** 活着的受保护傀儡（弱引用：不让 GhostPurge 从静态表里扫到实体对象）。 */
    private static final Map<UUID, java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>>> LIVE = new HashMap<>();

    /** 受保护实体的网络 entity id：用于拦截客户端移除包。 */
    private static final IntSet PROTECTED_ENTITY_IDS =
            IntSets.synchronize(new IntOpenHashSet());
    /** 客户端也要按 UUID 挡 byUuid/knownUuids 的直删。 */
    private static final java.util.Set<UUID> PROTECTED_UUIDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isProtectedEntityId(int id) {
        return PROTECTED_ENTITY_IDS.contains(id);
    }

    public static void protect(int id) {
        PROTECTED_ENTITY_IDS.add(id);
    }

    public static void protect(UUID uuid, int id) {
        if (uuid != null) {
            PROTECTED_UUIDS.add(uuid);
        }
        protect(id);
    }

    /** 受保护投影 UUID：世界索引门用它判断 byUuid / knownUuids 的 key。 */
    public static boolean isProtectedUuid(UUID id) {
        return id != null && (PROTECTED_UUIDS.contains(id) || LIVE.containsKey(id) || SoulRegistry.hasProjection(id));
    }

    /** 客户端对账用：当前受保护 UUID 快照。 */
    public static java.util.List<UUID> protectedUuids() {
        return new ArrayList<>(PROTECTED_UUIDS);
    }

    public static void unprotect(UUID uuid, int id) {
        if (uuid != null) {
            PROTECTED_UUIDS.remove(uuid);
        }
        unprotect(id);
    }

    /** 正常生命周期放行前调用：允许本次服务端移除同步到客户端。 */
    public static void unprotect(int id) {
        PROTECTED_ENTITY_IDS.remove(id);
    }

    /**
     * 当前在监视表里的傀儡（拷贝）。
     *
     * <p>给 {@link Projection} 每 tick 遍历用：只走"入世界时登记过"的这一小撮，
     * 不去每 tick 扫全世界实体。</p>
     */
    public static java.util.List<AbstractGolemEntity<?, ?>> liveSnapshot() {
        java.util.List<AbstractGolemEntity<?, ?>> out = new ArrayList<>(LIVE.size());
        for (java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref : new ArrayList<>(LIVE.values())) {
            AbstractGolemEntity<?, ?> golem = ref == null ? null : ref.get();
            if (golem != null) {
                out.add(golem);
            }
        }
        return out;
    }

    private static void putLive(AbstractGolemEntity<?, ?> golem) {
        if (golem != null) {
            LIVE.put(golem.getUUID(), new java.lang.ref.WeakReference<>(golem));
        }
    }
    /** 玩家/正常生命周期放行的移除，不再重建。 */
    private static final Set<UUID> RELEASED = new HashSet<>();
    /** 最后一次"坐标正常"的位置。攻击方能把 x/y/z 直接写成 NaN，之后所有 isLoaded 判定都会假。 */
    private static final Map<UUID, net.minecraft.core.BlockPos> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> MISSING = new HashMap<>();
    /** 重建失败计数/冷却：避免 addFreshEntity 失败后每 tick 刷日志把服务器拖死。 */
    private static final Map<UUID, Integer> REBUILD_FAILS = new HashMap<>();
    private static final Map<UUID, Long> LAST_REBUILD_AT = new HashMap<>();

    private final Map<UUID, CompoundTag> snapshots = new HashMap<>();
    /**
     * 上次快照的时刻。
     *
     * <p>用 {@code level.getGameTime()} 而不是 {@code golem.tickCount}：攻击方一旦把
     * {@code canUpdate} 写成 false，实体的 {@code tickCount} 就冻住，节流会变成永久，
     * 快照再也不刷（坑 #1）。</p>
     */
    private final Map<UUID, Long> lastSnapshotTick = new HashMap<>();
    private static final Map<String, Long> LAST_SNAP_LOG = new HashMap<>();
    private static long reindexed;
    private static long recreated;
    private static long snapshotsTaken;
    private static long snapshotsForced;

    public Anchor() {
    }

    public static Anchor get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                Anchor::load, Anchor::new, DATA_NAME);
    }

    private static Anchor load(CompoundTag tag) {
        Anchor data = new Anchor();
        ListTag list = tag.getList("snapshots", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("uuid") && entry.contains("data", 10)) {
                data.snapshots.put(entry.getUUID("uuid"), entry.getCompound("data"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, CompoundTag> entry : snapshots.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("uuid", entry.getKey());
            one.put("data", entry.getValue());
            list.add(one);
        }
        tag.put("snapshots", list);
        return tag;
    }

    // ------------------------------------------------------------------ 生命周期

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem
                    && golem.getOwnerUUID() != null) {
                // 客户端只保护本地玩家的傀儡；敌方/其他 profile 的傀儡不能被客户端
                // remove 门保护，否则服务端能打、客户端却挡移除。
                net.minecraft.client.player.LocalPlayer local =
                        net.minecraft.client.Minecraft.getInstance().player;
                if (local != null && local.getUUID().equals(golem.getOwnerUUID())) {
                    PROTECTED_ENTITY_IDS.add(golem.getId());
                    PROTECTED_UUIDS.add(golem.getUUID());
                }
            }
            return;
        }
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !Sanctuary.protectedGolem(golem)) {
            return;
        }
        putLive(golem);
        PROTECTED_ENTITY_IDS.add(golem.getId());
        PROTECTED_UUIDS.add(golem.getUUID());
        VisibilityGuard.repair(golem);
        try {
            AgentBridge.onProtectedDataItem(golem);
        } catch (Throwable ignored) {
        }
        AgentBridge.onProtected(golem.getUUID());
        RELEASED.remove(golem.getUUID());
        MISSING.remove(golem.getUUID());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem) {
                // 攻击方在客户端先 setRemoved/触发 leave，再走“已移除”分支。
                // 只要这个 id 还在保护集里，就不能因为 leave 自己把保护撤掉。
                if (isProtectedEntityId(golem.getId())) {
                    Golems_arsenal.LOGGER.warn(
                            "[Sanctuary] client_protection_kept_on_leave uuid={} id={}",
                            golem.getUUID(), golem.getId());
                    return;
                }
                PROTECTED_ENTITY_IDS.remove(golem.getId());
                PROTECTED_UUIDS.remove(golem.getUUID());
            }
            return;
        }
        if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem) {
            Entity.RemovalReason reason = golem.getRemovalReason();
            if (reason == null) {
                return;
            }
            if (RemovalGuard.isLifecycleAllowedRecently(golem)) {
                release(golem.getUUID());
                return;
            }
            // 真死（账本见过 LivingDeathEvent）是正常结束，直接从监视表放掉。
            if (HealthLedger.observedDeath(golem)) {
                release(golem.getUUID());
                return;
            }
            // 只有"正常生命周期"才从监视表里放掉。被拦下的 KILLED/DISCARDED 即使
            // 触发了离开事件，也必须继续盯——否则对方的直接字段写会把 Anchor 骗走。
            if (!RemovalGuard.isAllowedLifecycle(golem)) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] anchor_kept_on_leave uuid={} reason={} ledger={}",
                        golem.getUUID(), reason, HealthLedger.expectedOf(golem));
                return;
            }
            release(golem.getUUID());
        }
    }

    /** 从监视表移除，并同步撤掉网络 entity id 的移除包保护。 */
    private static void untrack(UUID id) {
        java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref = LIVE.remove(id);
        AbstractGolemEntity<?, ?> removed = ref == null ? null : ref.get();
        if (removed != null) {
            PROTECTED_ENTITY_IDS.remove(removed.getId());
        }
    }

    /** 正常生命周期放行（玩家回收、跨维度、卸载）：不再重建这只。 */
    public static void release(UUID id) {
        if (id == null) {
            return;
        }
        RELEASED.add(id);
        java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref = LIVE.remove(id);
        AbstractGolemEntity<?, ?> removed = ref == null ? null : ref.get();
        if (removed != null) {
            PROTECTED_ENTITY_IDS.remove(removed.getId());
        }
        MISSING.remove(id);
    }

    // ------------------------------------------------------------------ 每 tick 对账

    /**
     * 先修坐标，再做其他判定。
     *
     * <p>攻击方可以绕过 {@code setPosRaw} 直接写 {@code x/y/z} 字段，把傀儡扔到
     * {@code -999999} 或 NaN。等 END 阶段再修，碰撞扫描已经拿着这个位置跑了一整 tick；
     * 所以 START 每 tick 只做一次轻量位置修复。</p>
     */
    /**
     * 2026-11-21：独立守卫线程已移除。对账统一走服务器 tick 的
     * {@link #onServerTick}（每 10 tick），不再有跨线程 server.execute 队列。
     * 这与 Super Steve 的做法一致：状态由实体/世界 tick 驱动，而不是后台线程。
     */

    @SubscribeEvent
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        for (AbstractGolemEntity<?, ?> golem : liveSnapshot()) {
            try {
                clampMovement(golem);
                repairPosition(golem);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 实体 tick 前先夹住 delta 并修位置；防止同一 tick 内碰撞扫描被巨大位移拖死。 */
    public static void guardBeforeTick(AbstractGolemEntity<?, ?> golem) {
        try {
            clampMovement(golem);
            repairPosition(golem);
        } catch (Throwable ignored) {
        }
    }

    /** 位置被打飞后，delta 也可能还是巨大值；碰撞扫描会按它扫穿整个世界。 */
    private static void clampMovement(AbstractGolemEntity<?, ?> golem) {
        net.minecraft.world.phys.Vec3 delta = golem.getDeltaMovement();
        double len = delta.lengthSqr();
        if (!Double.isFinite(len) || len > 9.0D) {
            golem.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            Golems_arsenal.LOGGER.warn("[Sanctuary] movement_clamped uuid={} delta=({},{},{})",
                    golem.getUUID(), delta.x, delta.y, delta.z);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 10 != 0) {
            return;
        }
        Anchor data = get(server);
        for (UUID id : new ArrayList<>(LIVE.keySet())) {
            AbstractGolemEntity<?, ?> golem = liveEntity(id);
            if (golem == null) {
                try {
                    data.checkMissing(server, id);
                } catch (Throwable ignored) {
                }
                continue;
            }
            try {
                data.check(server, golem);
            } catch (Throwable ignored) {
            }
        }
        data.setDirty();
    }

    private static AbstractGolemEntity<?, ?> liveEntity(UUID id) {
        java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref = LIVE.get(id);
        return ref == null ? null : ref.get();
    }

    public static AbstractGolemEntity<?, ?> liveByUuid(UUID id) {
        return liveEntity(id);
    }

    public static AbstractGolemEntity<?, ?> liveById(int id) {
        for (java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref : new ArrayList<>(LIVE.values())) {
            AbstractGolemEntity<?, ?> golem = ref == null ? null : ref.get();
            if (golem != null && golem.getId() == id) {
                return golem;
            }
        }
        return null;
    }

    /**
     * 弱引用被 GC 后的兜底：对象没了，但快照/Soul 还在，仍然应该重建。
     * 只按 id 走，不再依赖旧实体对象。
     */
    private void checkMissing(MinecraftServer server, UUID id) {
        if (id == null || RELEASED.contains(id) || RemovalGuard.isLifecycleAllowed(id)) {
            if (id != null) {
                untrack(id);
                snapshots.remove(id);
            }
            return;
        }
        CompoundTag snapshot = snapshots.get(id);
        if (snapshot == null) {
            return;
        }
        int missing = MISSING.merge(id, 1, Integer::sum);
        if (missing < MISSING_LIMIT) {
            return;
        }
        long now = server.overworld().getGameTime();
        Long last = LAST_REBUILD_AT.get(id);
        if (last != null && now - last < 20L) {
            return;
        }
        LAST_REBUILD_AT.put(id, now);
        if (recreate(server.overworld(), id, snapshot, -1)) {
            recreated++;
            MISSING.remove(id);
            REBUILD_FAILS.remove(id);
            LAST_REBUILD_AT.remove(id);
            return;
        }
        int fails = REBUILD_FAILS.merge(id, 1, Integer::sum);
        if (fails >= 3) {
            Golems_arsenal.LOGGER.error("[Sanctuary] projection_abandoned uuid={} fails={} ledger=? (missing object)",
                    id, fails);
            untrack(id);
            Watchdog.forget(id);
            snapshots.remove(id);
            MISSING.remove(id);
            REBUILD_FAILS.remove(id);
            LAST_REBUILD_AT.remove(id);
        }
    }

    private void check(MinecraftServer server, AbstractGolemEntity<?, ?> golem) {
        UUID id = golem.getUUID();
        if (RemovalGuard.isLifecycleAllowedRecently(golem)) {
            release(id);
            snapshots.remove(id);
            return;
        }
        try {
            AgentBridge.onProtectedDataItem(golem);
        } catch (Throwable ignored) {
        }
        if (RELEASED.contains(id) || HealthLedger.observedDeath(golem)) {
            // 2026-11-09：这条以前是静默的，导致"傀儡突然不再被重挂"查不出原因。
            if (!RELEASED.contains(id)) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] anchor_dropped uuid={} observedDeath=true ledger={}",
                        id, HealthLedger.expectedOf(golem));
            }
            untrack(id);
            snapshots.remove(id);
            return;
        }
        // 账本归零 = 真死（宿主自己的 postHurt 回收路径没有 LivingDeathEvent）。
        if (HealthLedger.tracked(golem)) {
            float expected = HealthLedger.expectedOf(golem);
            if (Float.isFinite(expected) && expected <= 0.0F && !HealthLedger.recentTamper(golem, 200)) {
                untrack(id);
                snapshots.remove(id);
                return;
            }
        }
        if (!(golem.level() instanceof ServerLevel level) || level.getServer() != server) {
            return;
        }
        // 坐标被写成 NaN 之后，blockPosition() 是 NaN，isLoaded 恒 false —— 旧代码就在这里
        // 提前 return，于是重建永远不会发生（2026-11-08 实测：全死亡、零 recreated）。
        repairPosition(golem);
        VisibilityGuard.repair(golem);
        try {
            long now = level.getGameTime();
            Long last = LAST_CLIENT_SYNC.get(id);
            if (last == null || now - last >= 20L) {
                LAST_CLIENT_SYNC.put(id, now);
                a_silly_cat.golems_arsenal.guard.index.ClientProjectionSync.teleport(level, golem);
            }
        } catch (Throwable ignored) {
        }
        // 区块没加载：不判定，也不重建（否则会在错误的位置刷一只）。
        if (!level.isLoaded(golem.blockPosition())) {
            return;
        }
        // 1) 对象还在、索引里没有：加回去。
        if (level.getEntity(id) != golem) {
            // 先把 removalReason 字段清掉再注册：不清的话 addFreshEntity 会被当成"已移除"拒绝，
            // 表现就是"拦住了 onRemovedFromWorld / setRemoved，但实体还是从世界里消失"。
            RemovalGuard.clearField(golem);
            // 探针：add 之前把"世界眼里的移除状态"记下来，失败时一起打出来。
            Entity.RemovalReason preReason = RemovalGuard.rawRemovalReason(golem);
            boolean preRemoved = golem.isRemoved();
            if (IndexGuard.reindex(golem)) {
                reindexed++;
                MISSING.remove(id);
                RemovalGuard.clearField(golem);
                Golems_arsenal.LOGGER.warn("[Sanctuary] reindexed uuid={} ledger={}",
                        id, HealthLedger.expectedOf(golem));
            } else {
                // 重挂失败必须留痕：原版只会打一行 "Tried to add entity ... marked as removed already"，
                // 看不出是谁在重试、第几次、账本还活不活。2026-11-09 这一条就是用来定位
                // "字段被抢回去" / "读侧没被问到" 的。
                int misses = MISSING.merge(id, 1, Integer::sum);
                Golems_arsenal.LOGGER.warn("[Sanctuary] reindex_failed uuid={} misses={}/{} ledger={} "
                                + "preReason={} preRemoved={} postReason={}",
                        id, misses, MISSING_LIMIT, HealthLedger.expectedOf(golem),
                        preReason, preRemoved, RemovalGuard.rawRemovalReason(golem));
            }
        } else {
            MISSING.remove(id);
        }
        // 2) 索引里也没有、对象又加不回去：按快照重建。
        int missing = MISSING.getOrDefault(id, 0);
        if (missing >= MISSING_LIMIT) {
            long now = level.getGameTime();
            Long lastAttempt = LAST_REBUILD_AT.get(id);
            if (lastAttempt != null && now - lastAttempt < 20L) {
                return;
            }
            LAST_REBUILD_AT.put(id, now);
            // 旧实体可能是在 onJoin 之后才丢映射；重建前补一次 Soul/可信 NBT。
            SoulRegistry.ensure(golem, HealthLedger.expectedOf(golem), golem.getMaxHealth());
            CompoundTag snapshot = snapshots.get(id);
            CompoundTag soulNbt = SoulRegistry.nbtForProjection(id);
            if (snapshot == null) {
                snapshot = soulNbt;
            }
            if (snapshot != null) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_attempt uuid={} soulNbt={} snapshot={}",
                        id, soulNbt != null, snapshots.containsKey(id));
                if (recreate(level, id, snapshot, golem.getId())) {
                    recreated++;
                    untrack(id);
                    MISSING.remove(id);
                    REBUILD_FAILS.remove(id);
                    LAST_REBUILD_AT.remove(id);
                    return;
                }
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_failed uuid={} ledger={}",
                        id, HealthLedger.expectedOf(golem));
            } else {
                Golems_arsenal.LOGGER.error("[Sanctuary] recreate_impossible uuid={} ledger={} (没有 Soul/Anchor 快照，没法重建)",
                        id, HealthLedger.expectedOf(golem));
            }
            int fails = REBUILD_FAILS.merge(id, 1, Integer::sum);
            if (fails >= 3) {
                Golems_arsenal.LOGGER.error("[Sanctuary] projection_abandoned uuid={} fails={} ledger={}",
                        id, fails, HealthLedger.expectedOf(golem));
                untrack(id);
                Watchdog.forget(id);
                snapshots.remove(id);
                MISSING.remove(id);
                REBUILD_FAILS.remove(id);
                LAST_REBUILD_AT.remove(id);
            }
        }
        // 3) 定期刷新快照。
        long now = golem.level().getGameTime();
        Long last = lastSnapshotTick.get(id);
        if (last == null || now - last >= SNAPSHOT_INTERVAL) {
            lastSnapshotTick.put(id, now);
            CompoundTag tag = new CompoundTag();
            golem.saveWithoutId(tag);
            tag.putString(KEY_TYPE, EntityType.getKey(golem.getType()).toString());
            snapshots.put(id, tag);
            snapshotsTaken++;
        }
    }

    /**
     * A 层 SECTION-DENIED 之后的状态修复：只 cancel callback 不够，toaru 已经把
     * raw removalReason / isAddedToWorld / pickable 写坏。下一 tick 在服务器线程
     * 清掉 raw 状态并重挂 section，避免“拦住了 section 但实体仍是 removed=true”。
     */
    public static void repairSectionDenied(Entity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        MinecraftServer server = golem.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                RemovalGuard.clearField(golem);
                java.lang.reflect.Field added = Entity.class.getDeclaredField("isAddedToWorld");
                added.setAccessible(true);
                added.setBoolean(golem, true);
                VisibilityGuard.repair(golem);
                if (golem.level() instanceof ServerLevel level) {
                    if (level.getEntity(golem.getId()) != golem) {
                        IndexGuard.reindex(golem);
                    }
                    a_silly_cat.golems_arsenal.guard.index.ClientProjectionSync.teleport(level, golem);
                }
                Golems_arsenal.LOGGER.warn(
                        "[Sanctuary] SECTION-REPAIRED uuid={} rawReason={} added={} pickable={}",
                        golem.getUUID(), RemovalGuard.rawRemovalReason(golem),
                        golem.isAddedToWorld(), golem.isPickable());
            } catch (Throwable t) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] SECTION-REPAIR-FAILED uuid={} {}",
                        entity.getUUID(), t.toString());
            }
        });
    }

    /**
     * 坐标修复：{@code setEntityPosReflection} 这类攻击直接写 {@code x/y/z} 字段，
     * 绕过 {@code setPosRaw} 的门。坐标一旦是 NaN，{@code blockPosition()} 与
     * {@code level.isLoaded()} 全部失真，后续所有"按位置判断"的逻辑都会瞎。
     */
    public static void repairPosition(AbstractGolemEntity<?, ?> golem) {
        try {
            double x = golem.getX();
            double y = golem.getY();
            double z = golem.getZ();
            if (isSanePosition(golem, x, y, z)) {
                LAST_POS.put(golem.getUUID(), golem.blockPosition());
                return;
            }
            net.minecraft.core.BlockPos good = LAST_POS.get(golem.getUUID());
            if (good == null) {
                Entity owner = golem.getOwner();
                if (owner != null && Double.isFinite(owner.getX())
                        && Double.isFinite(owner.getY()) && Double.isFinite(owner.getZ())) {
                    good = owner.blockPosition();
                }
            }
            if (good == null) {
                return;
            }
            final double gx = good.getX() + 0.5D;
            final double gy = good.getY();
            final double gz = good.getZ() + 0.5D;
            SanctuaryScopes.runInternal(() -> golem.moveTo(gx, gy, gz, golem.getYRot(), golem.getXRot()));
            golem.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            try {
                a_silly_cat.golems_arsenal.guard.index.ClientProjectionSync.teleport(
                        (ServerLevel) golem.level(), golem);
            } catch (Throwable ignored) {
            }
            Golems_arsenal.LOGGER.warn("[Sanctuary] pos_repaired uuid={} from=({},{},{}) -> ({},{},{})",
                    golem.getUUID(), x, y, z, gx, gy, gz);
        } catch (Throwable ignored) {
        }
    }

    /** 有限 + 不远离最后一次正常位置；防止 -999999/NaN 进入碰撞扫描。 */
    private static boolean isSanePosition(AbstractGolemEntity<?, ?> golem, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        if (Math.abs(x) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D
                || Math.abs(y) > 30_000_000.0D) {
            return false;
        }
        net.minecraft.core.BlockPos good = LAST_POS.get(golem.getUUID());
        net.minecraft.core.BlockPos now = net.minecraft.core.BlockPos.containing(x, y, z);
        if (good != null) {
            if (good.distSqr(now) > 256L * 256L) {
                return false;
            }
        } else {
            // 第一次快照之前没有 LAST_POS：用“区块是否加载 + 与主人距离”兜底。
            if (golem.level() instanceof ServerLevel level && !level.isLoaded(now)) {
                return false;
            }
            Entity owner = golem.getOwner();
            if (owner != null && Double.isFinite(owner.getX())
                    && owner.blockPosition().distSqr(now) > 256L * 256L) {
                return false;
            }
        }
        return true;
    }

    public static void snapshotNow(AbstractGolemEntity<?, ?> golem) {
        snapshotNow(golem, null);
    }

    /**
     * 立刻给一只傀儡拍快照，并记下"这次是哪个触发点拍的"。
     *
     * <p>{@code site == null} 表示周期快照（不打印，否则每 10 tick 每只傀儡一行会淹掉日志）；
     * 其余触发点会打 {@code [Sanctuary] snapshot site=...}，这是验收里"每一次改变都能对上账"
     * 的证据链。</p>
     */
    /**
     * 快照重入闸。
     *
     * <p>{@code saveWithoutId} 在敌意模组眼里也是一个可以挂钩的方法；万一它在里面回调到
     * 写血入口（{@code setHealth} / {@code SynchedEntityData#set}），拒绝路径又会来拍快照，
     * 没有这道闸就会无限递归。快照本身也不需要嵌套。</p>
     */
    private static final ThreadLocal<Boolean> SNAPSHOTTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void snapshotNow(AbstractGolemEntity<?, ?> golem, String site) {
        if (golem == null || golem.level().isClientSide() || SNAPSHOTTING.get()) {
            return;
        }
        SoulRegistry.captureNow(golem);
        SNAPSHOTTING.set(Boolean.TRUE);
        try {
            CompoundTag tag = new CompoundTag();
            golem.saveWithoutId(tag);
            float ledger = HealthLedger.expectedOf(golem);
            if (Float.isFinite(ledger) && ledger > 0.0F) {
                tag.putFloat("Health", ledger);
            }
            tag.putString(KEY_TYPE, EntityType.getKey(golem.getType()).toString());
            MinecraftServer server = golem.getServer();
            // 拒绝写可能发生在别的线程（strict off-thread 分支）：SavedData 不是线程安全的，
            // 拍快照必须回到服务器线程，否则宁可这一张不拍。
            if (server == null || !server.isSameThread()) {
                return;
            }
            Anchor anchor = get(server);
            anchor.snapshots.put(golem.getUUID(), tag);
            anchor.lastSnapshotTick.put(golem.getUUID(), golem.level().getGameTime());
            if (isSanePosition(golem, golem.getX(), golem.getY(), golem.getZ())) {
                LAST_POS.put(golem.getUUID(), golem.blockPosition());
            }
            snapshotsTaken++;
            if (site != null) {
                snapshotsForced++;
                logSnapshot(golem, site);
            }
        } catch (Throwable ignored) {
        } finally {
            SNAPSHOTTING.set(Boolean.FALSE);
        }
    }

    private static void logSnapshot(AbstractGolemEntity<?, ?> golem, String site) {
        try {
            String key = golem.getUUID() + ":" + site;
            long now = golem.level().getGameTime();
            Long last = LAST_SNAP_LOG.get(key);
            if (last != null && now - last < 20) {
                return;
            }
            LAST_SNAP_LOG.put(key, now);
            Golems_arsenal.LOGGER.info("[Sanctuary] snapshot site={} uuid={} ledger={}",
                    site, golem.getUUID(), HealthLedger.expectedOf(golem));
        } catch (Throwable ignored) {
        }
    }

    /** 用快照重建一只并放回世界。返回是否成功。 */
    private static boolean recreate(ServerLevel level, UUID oldId, CompoundTag snapshot, int oldEntityId) {
        try {
            CompoundTag source = SoulRegistry.nbtForProjection(oldId);
            if (source == null) {
                source = snapshot;
            }
            if (source == null) {
                return false;
            }
            // 新建投影必须是“构造出来的新 UUID”，不能 setUUID：
            // toaru 的 GungnirEntityMixin 在 setUUID HEAD 上有条件 cancel，
            // AV 也可能在别的层锁身份。直接去掉 NBT 里的 UUID，让构造器的随机 UUID 保留。
            source = source.copy();
            source.remove("UUID");
            source.remove("UUIDMost");
            source.remove("UUIDLeast");
            String typeName = source.getString(KEY_TYPE);
            if (typeName.isEmpty()) {
                typeName = source.getString("id");
            }
            EntityType<?> type = EntityType.byString(typeName).orElse(null);
            if (type == null) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_type_missing uuid={} type={}", oldId, typeName);
                return false;
            }
            Entity entity = type.create(level);
            if (entity == null) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_create_null uuid={} type={}", oldId, typeName);
                return false;
            }
            SanctuaryScopes.enterLoad();
            try {
                entity.load(source);
            } catch (Throwable t) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_load_failed uuid={} type={} err={}",
                        oldId, typeName, t.toString());
                return false;
            } finally {
                SanctuaryScopes.exitLoad();
            }
            // 位置门在 SEALED 下会拦掉一切非作用域的位置写入（同一个 UUID 还在冻结表里），
            // 重建的落点必须显式声明成"我们自己写的"。
            SanctuaryScopes.runInternal(() ->
                    entity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot()));
            // 重建之后把账本血写回去：NBT 里的 Health 可能是被攻击方写过的值，
            // 而 GuardedEntity#setHealth 只许加血，所以要走 guardedData + DATA_HEALTH 两条腿。
            if (entity instanceof AbstractGolemEntity<?, ?> rebuilt) {
                float ledger = SoulRegistry.healthForProjection(oldId);
                if (!Float.isFinite(ledger)) {
                    ledger = HealthLedger.expectedOf(rebuilt);
                }
                if (Float.isFinite(ledger) && ledger > 0.0F) {
                    final float hp = ledger;
                    SanctuaryScopes.runInternal(() -> rebuilt.setGuardedDataImpl(hp, true, false));
                    a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.writeRawHealth(rebuilt, hp);
                }
            }
            // 不走 addFreshEntity / addNewEntity：AV 的 EntityGuardTransformer 把
            // ServerLevel.addFreshEntity、PersistentEntitySectionManager.addNewEntity、
            // EntityLookup.add 全部改写成 shouldBlockCreate/Add，从 Anchor 发起的重建
            // 永远会被判成“非自愿”。这里直接写索引容器。
            boolean[] added = {false};
            ProtectedEntityPolicy.runRebuild(() -> added[0] =
                    a_silly_cat.golems_arsenal.guard.index.WorldIndexGuard.register(level, entity));
            if (!added[0]) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] recreate_register_failed uuid={} type={}",
                        oldId, EntityType.getKey(type));
                return false;
            }
            // 成功之后才迁移：失败不能污染 LIVE / agent PROTECTED / Soul 映射。
            if (entity instanceof AbstractGolemEntity<?, ?> rebuilt && !entity.getUUID().equals(oldId)) {
                IdentityGuard.migrate(rebuilt, oldId, entity.getUUID());
                SoulRegistry.rotateProjection(oldId, entity.getUUID());
                HealthLedger.ensureTracked(rebuilt);
            }
            Golems_arsenal.LOGGER.warn("[Sanctuary] recreated uuid={} type={} ledger={}",
                    entity.getUUID(), EntityType.getKey(type), HealthLedger.expectedOf((AbstractGolemEntity<?, ?>) entity));
            // 旧客户端实体必须真的被删掉，再以新 entityId/UUID 加回来；
            // 先解除旧 id 的保护，否则会被我们自己的 ClientLevel 门挡住。
            if (oldEntityId != entity.getId()) {
                a_silly_cat.golems_arsenal.init.ModNetwork.sendAllowRemoval(oldId, oldEntityId);
                unprotect(oldEntityId);
                for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(oldEntityId));
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity));
                }
                Golems_arsenal.LOGGER.warn("[Sanctuary] client_resync oldId={} newId={} uuid={}",
                        oldEntityId, entity.getId(), entity.getUUID());
            }
            putLive((AbstractGolemEntity<?, ?>) entity);
            return true;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] recreate failed: {}", t.toString());
            return false;
        }
    }

    public static String statusLine() {
        return "[sanctuary] anchor watched=" + LIVE.size()
                + " snapshots=" + snapshotsTaken
                + " forced=" + snapshotsForced
                + " reindexed=" + reindexed
                + " recreated=" + recreated;
    }

    public static void forget(UUID id) {
        untrack(id);
        RELEASED.remove(id);
        MISSING.remove(id);
    }

    /** 身份轮换：监视表、快照、位置缓存全部换 key。 */
    public static void rotateIdentity(AbstractGolemEntity<?, ?> golem, UUID old, UUID fresh) {
        if (old == null || fresh == null || old.equals(fresh)) {
            return;
        }
        java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref = LIVE.remove(old);
        AbstractGolemEntity<?, ?> live = ref == null ? null : ref.get();
        LIVE.put(fresh, ref == null ? new java.lang.ref.WeakReference<>(golem) : ref);
        RELEASED.remove(old);
        BlockPos pos = LAST_POS.remove(old);
        if (pos != null) {
            LAST_POS.put(fresh, pos);
        }
        Integer missing = MISSING.remove(old);
        if (missing != null) {
            MISSING.put(fresh, missing);
        }
        // log keys: "<old>:<site>"
        String prefix = old + ":";
        for (String key : new ArrayList<>(LAST_SNAP_LOG.keySet())) {
            if (key.startsWith(prefix)) {
                Long value = LAST_SNAP_LOG.remove(key);
                if (value != null) {
                    LAST_SNAP_LOG.put(fresh + key.substring(old.toString().length()), value);
                }
            }
        }
        MinecraftServer server = golem.getServer();
        if (server == null) {
            return;
        }
        try {
            Anchor data = get(server);
            CompoundTag tag = data.snapshots.remove(old);
            if (tag != null) {
                tag.putUUID("UUID", fresh);
                data.snapshots.put(fresh, tag);
            }
            Long tick = data.lastSnapshotTick.remove(old);
            if (tick != null) {
                data.lastSnapshotTick.put(fresh, tick);
            }
            data.setDirty();
        } catch (Throwable ignored) {
        }
    }
}
