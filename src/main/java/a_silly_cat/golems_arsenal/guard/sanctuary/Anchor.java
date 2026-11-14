package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
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

    /** 活着的受保护傀儡（对象级）。 */
    private static final Map<UUID, AbstractGolemEntity<?, ?>> LIVE = new HashMap<>();

    /**
     * 当前在监视表里的傀儡（拷贝）。
     *
     * <p>给 {@link Projection} 每 tick 遍历用：只走"入世界时登记过"的这一小撮，
     * 不去每 tick 扫全世界实体。</p>
     */
    public static java.util.List<AbstractGolemEntity<?, ?>> liveSnapshot() {
        return new ArrayList<>(LIVE.values());
    }
    /** 玩家/正常生命周期放行的移除，不再重建。 */
    private static final Set<UUID> RELEASED = new HashSet<>();
    /** 最后一次"坐标正常"的位置。攻击方能把 x/y/z 直接写成 NaN，之后所有 isLoaded 判定都会假。 */
    private static final Map<UUID, net.minecraft.core.BlockPos> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> MISSING = new HashMap<>();

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
            return;
        }
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !Sanctuary.protectedGolem(golem)) {
            return;
        }
        LIVE.put(golem.getUUID(), golem);
        AgentBridge.onProtected(golem.getUUID());
        RELEASED.remove(golem.getUUID());
        MISSING.remove(golem.getUUID());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem) {
            Entity.RemovalReason reason = golem.getRemovalReason();
            if (reason == null) {
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

    /** 正常生命周期放行（玩家回收、跨维度、卸载）：不再重建这只。 */
    public static void release(UUID id) {
        if (id == null) {
            return;
        }
        RELEASED.add(id);
        LIVE.remove(id);
        MISSING.remove(id);
    }

    // ------------------------------------------------------------------ 每 tick 对账

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
        for (AbstractGolemEntity<?, ?> golem : new ArrayList<>(LIVE.values())) {
            try {
                data.check(server, golem);
            } catch (Throwable ignored) {
            }
        }
        data.setDirty();
    }

    private void check(MinecraftServer server, AbstractGolemEntity<?, ?> golem) {
        UUID id = golem.getUUID();
        if (RELEASED.contains(id) || HealthLedger.observedDeath(golem)) {
            // 2026-11-09：这条以前是静默的，导致"傀儡突然不再被重挂"查不出原因。
            if (!RELEASED.contains(id)) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] anchor_dropped uuid={} observedDeath=true ledger={}",
                        id, HealthLedger.expectedOf(golem));
            }
            LIVE.remove(id);
            snapshots.remove(id);
            return;
        }
        // 账本归零 = 真死（宿主自己的 postHurt 回收路径没有 LivingDeathEvent）。
        if (HealthLedger.tracked(golem)) {
            float expected = HealthLedger.expectedOf(golem);
            if (Float.isFinite(expected) && expected <= 0.0F && !HealthLedger.recentTamper(golem, 200)) {
                LIVE.remove(id);
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
            if (level.addFreshEntity(golem)) {
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
            CompoundTag snapshot = snapshots.get(id);
            if (snapshot != null && recreate(level, id, snapshot)) {
                recreated++;
                LIVE.remove(id);
                MISSING.remove(id);
            } else if (snapshot == null) {
                Golems_arsenal.LOGGER.error("[Sanctuary] recreate_impossible uuid={} ledger={} (没有快照，没法重建)",
                        id, HealthLedger.expectedOf(golem));
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
     * 坐标修复：{@code setEntityPosReflection} 这类攻击直接写 {@code x/y/z} 字段，
     * 绕过 {@code setPosRaw} 的门。坐标一旦是 NaN，{@code blockPosition()} 与
     * {@code level.isLoaded()} 全部失真，后续所有"按位置判断"的逻辑都会瞎。
     */
    public static void repairPosition(AbstractGolemEntity<?, ?> golem) {
        try {
            double x = golem.getX();
            double y = golem.getY();
            double z = golem.getZ();
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                LAST_POS.put(golem.getUUID(), golem.blockPosition());
                return;
            }
            net.minecraft.core.BlockPos good = LAST_POS.get(golem.getUUID());
            if (good == null) {
                Entity owner = golem.getOwner();
                if (owner != null && Double.isFinite(owner.getX())) {
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
            Golems_arsenal.LOGGER.warn("[Sanctuary] pos_repaired uuid={} -> ({},{},{})",
                    golem.getUUID(), gx, gy, gz);
        } catch (Throwable ignored) {
        }
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
            if (Double.isFinite(golem.getX()) && Double.isFinite(golem.getY()) && Double.isFinite(golem.getZ())) {
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
    private static boolean recreate(ServerLevel level, UUID oldId, CompoundTag snapshot) {
        try {
            EntityType<?> type = EntityType.byString(snapshot.getString(KEY_TYPE)).orElse(null);
            if (type == null) {
                return false;
            }
            Entity entity = type.create(level);
            if (entity == null) {
                return false;
            }
            // 快照里的 UUID 如果已经在假死名单里，Entity#load 的 mixin 会换一个新名字；
            // 这里不重复洗，避免一次 load 连换两次。
            SanctuaryScopes.enterLoad();
            try {
                entity.load(snapshot);
            } finally {
                SanctuaryScopes.exitLoad();
            }
            UUID freshId = entity.getUUID();
            if (!freshId.equals(oldId)) {
                // 新实体已经带新 UUID；把我们的账本/监视表从旧名字搬过来。
                if (entity instanceof AbstractGolemEntity<?, ?> rebuilt) {
                    IdentityGuard.migrate(rebuilt, oldId, freshId);
                }
            }
            // 位置门在 SEALED 下会拦掉一切非作用域的位置写入（同一个 UUID 还在冻结表里），
            // 重建的落点必须显式声明成"我们自己写的"。
            SanctuaryScopes.runInternal(() ->
                    entity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot()));
            // 重建之后把账本血写回去：NBT 里的 Health 可能是被攻击方写过的值，
            // 而 GuardedEntity#setHealth 只许加血，所以要走 guardedData + DATA_HEALTH 两条腿。
            if (entity instanceof AbstractGolemEntity<?, ?> rebuilt) {
                float ledger = HealthLedger.expectedOf(rebuilt);
                if (Float.isFinite(ledger) && ledger > 0.0F) {
                    SanctuaryScopes.runInternal(() -> rebuilt.setGuardedDataImpl(ledger, true, false));
                    a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.writeRawHealth(rebuilt, ledger);
                }
            }
            if (!level.addFreshEntity(entity)) {
                return false;
            }
            Golems_arsenal.LOGGER.warn("[Sanctuary] recreated uuid={} type={} ledger={}",
                    entity.getUUID(), EntityType.getKey(type), HealthLedger.expectedOf((AbstractGolemEntity<?, ?>) entity));
            LIVE.put(entity.getUUID(), (AbstractGolemEntity<?, ?>) entity);
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
        LIVE.remove(id);
        RELEASED.remove(id);
        MISSING.remove(id);
    }

    /** 身份轮换：监视表、快照、位置缓存全部换 key。 */
    public static void rotateIdentity(AbstractGolemEntity<?, ?> golem, UUID old, UUID fresh) {
        if (old == null || fresh == null || old.equals(fresh)) {
            return;
        }
        AbstractGolemEntity<?, ?> live = LIVE.remove(old);
        LIVE.put(fresh, live == null ? golem : live);
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
