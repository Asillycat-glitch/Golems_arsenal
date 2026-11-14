package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 守卫守望：只在 server tick 上做"读字段 + 对账"，一个方法都不 hook。
 *
 * <p>为什么不用方法门：本包里最狠的清除手段（字段直写 + 拆索引 + 改写查询 + 装死值闸门）没有
 * 方法边界可挂。但<b>它们改不掉"字段里现在是什么"</b>——所以这里只读字段：</p>
 * <ul>
 *   <li><b>G1</b> 五字段不变量：{@code removalReason} / {@code isAddedToWorld} / {@code levelCallback}，
 *       以及 {@code ClientboundRemoveEntitiesPacket} 之外没有任何 API 能撒谎的地方；</li>
 *   <li><b>G2</b> 原始索引枚举：{@code PersistentEntitySectionManager#entityLookup#byUuid}，
 *       绕开被 filterReturn 改写的 {@code level.getEntity(id)}；</li>
 *   <li><b>G8</b> 时间源固定 {@code ServerTickEvent.Phase.END}：TimeStop / 死值闸门 / blockTick
 *       都停不掉 server tick，所以对账一定会跑；</li>
 *   <li><b>G16</b> 权威副本在 {@link Anchor} 的 SavedData 里（落盘，任何内存清洗都抹不掉）。</li>
 * </ul>
 *
 * <p>所有反射都在 try/catch 里：读不到就当"没意见"，绝不因为字段改名而崩。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class Watchdog {

    private static final int INTERVAL = 10;

    private static final Map<UUID, AbstractGolemEntity<?, ?>> LIVE = new HashMap<>();

    private static Field removalReasonField;
    private static Field addedToWorldField;
    private static Field levelCallbackField;
    private static Field deadField;
    private static Field deathTimeField;
    private static Field entityManagerField;
    private static Field entityLookupField;
    private static Field lookupByUuidField;

    private static long ticks;
    private static long reasonCleared;
    private static long revivedFields;
    private static long readded;
    private static long readdFailed;
    private static final Map<UUID, Long> LAST_READD_FAIL = new HashMap<>();
    private static long posRepaired;
    private static long notIndexed;
    private static long lookupReplaced;
    /** 上一次看到的 {@code PersistentEntitySectionManager#entityLookup} 对象本身。 */
    private static Object seenLookup;

    private Watchdog() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem
                && Sanctuary.protectedGolem(golem)) {
            LIVE.put(golem.getUUID(), golem);
        }
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
            // 只有正常生命周期/真死才停止守望；被拦下的敌意移除继续盯。
            if (RemovalGuard.isAllowedLifecycle(golem) || HealthLedger.observedDeath(golem)) {
                LIVE.remove(golem.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // G8：只认 server tick，且只在 END（这一 tick 的所有实体逻辑都跑完了）。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        // hard_lock=true 时按配置间隔跑（默认 1 tick）；关掉时退回原来的 10 tick 守望。
        int interval = hardLock() ? Math.max(1, lockInterval()) : INTERVAL;
        if (++ticks % interval != 0) {
            return;
        }
        // G2b：先确认"世界的索引还是同一个对象"。
        // 匠战妖域的 superKillEntity 不是逐个摘实体，而是把整个 EntityLookup 换掉；
        // 逐实体检查看不出来（对象还在、字段也没动），所以这里单独盯对象身份。
        watchIndex(event.getServer().overworld());
        for (AbstractGolemEntity<?, ?> golem : new ArrayList<>(LIVE.values())) {
            try {
                check(golem);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void watchIndex(ServerLevel level) {
        try {
            Object manager = read(entityManagerField(), level);
            if (manager == null) {
                return;
            }
            Object lookup = read(entityLookupField(manager), manager);
            if (lookup == null) {
                return;
            }
            if (seenLookup == null) {
                seenLookup = lookup;
                return;
            }
            if (seenLookup != lookup) {
                lookupReplaced++;
                Golems_arsenal.LOGGER.warn("[Sanctuary] index_replaced lookup {} -> {} watched={} (anchor reindexes next pass)",
                        seenLookup.getClass().getName(), lookup.getClass().getName(), LIVE.size());
                seenLookup = lookup;
                // 这里不自己重挂：索引换了之后 level.getEntity(id) 必然查不到，
                // Anchor 的 10 tick 对账会走 reindex 分支（它还会先清 removalReason）。
            }
        } catch (Throwable ignored) {
        }
    }

    private static void check(AbstractGolemEntity<?, ?> golem) {
        UUID id = golem.getUUID();
        if (HealthLedger.observedDeath(golem)) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] watchdog_dropped uuid={} observedDeath=true ledger={}",
                    id, HealthLedger.expectedOf(golem));
            LIVE.remove(id);
            return;
        }
        // 真死：账本归零且没有"最近被篡改"的痕迹，放手。
        if (HealthLedger.expectedOf(golem) <= 0.0F && !HealthLedger.recentTamper(golem, 200)) {
            LIVE.remove(id);
            return;
        }
        if (!(golem.level() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        // 坐标被写成 NaN 之后 blockPosition()/isLoaded 全部失真，先修位置再判断索引。
        Anchor.repairPosition(golem);
        // G1a：removalReason 被直写（setRemoved/setRemovalReason/Unsafe 都会落在这里）。
        // 用 hardRepair 而不是 clearField：正常卸载/跨维度/玩家回收不能被硬修顶回去。
        Object reason = read(removalReasonField(), golem);
        if (reason != null) {
            if (RemovalGuard.hardRepair(golem)) {
                reasonCleared++;
                // 强制快照（触发点 2/4）：有人刚用字段直写把它标成"已移除"，
                // 这正是最该留一张干净副本的时刻（之后的 recreate 就靠它）。
                Anchor.snapshotNow(golem, "watchdog_removal_reason");
                Golems_arsenal.LOGGER.warn("[Sanctuary] watchdog cleared removalReason={} uuid={} ledger={}",
                        reason, id, HealthLedger.expectedOf(golem));
            }
        }
        // G1d：hard_lock 的 dead / deathTime 不变量。真正的 die 栈（inDeath）不碰；
        // 只有"账本说活着、字段却说死了"才回写，避免和正常死亡动画打架。
        if (hardLock() && !SanctuaryScopes.inDeath()
                && Float.isFinite(HealthLedger.expectedOf(golem))
                && HealthLedger.expectedOf(golem) > 1.0F) {
            if (clearDeadFlag(golem)) {
                revivedFields++;
            }
            if (clearDeathTime(golem)) {
                revivedFields++;
            }
        }
        // G1b：isAddedToWorld 被置 false（清除类攻击的标配）。
        Object added = read(addedToWorldField(), golem);
        boolean inWorld = !(added instanceof Boolean b) || b;
        // G2：原始索引枚举，绕开被改写的 level.getEntity(id)。
        boolean indexed = rawIndexed(level, id);
        if (!inWorld || !indexed) {
            notIndexed++;
            // 只有"原始索引也查不到"才回加，避免在被过滤的查询上误判。
            if (!indexed && level.isLoaded(golem.blockPosition())) {
                RemovalGuard.clearField(golem);
                Entity.RemovalReason preReason = RemovalGuard.rawRemovalReason(golem);
                boolean preRemoved = golem.isRemoved();
                // 先走"直接塞回索引"（不触发 EntityJoinLevelEvent，也不会被 knownUuids 拒绝）；
                // 失败再退回 addFreshEntity。
                boolean ok = IndexGuard.reindex(golem);
                if (!ok) {
                    ok = level.addFreshEntity(golem);
                }
                if (ok) {
                    readded++;
                    Golems_arsenal.LOGGER.warn("[Sanctuary] watchdog readded uuid={} ledger={} reason={}",
                            id, HealthLedger.expectedOf(golem), reason);
                } else {
                    readdFailed++;
                    long now = golem.level().getGameTime();
                    Long lastFail = LAST_READD_FAIL.put(id, now);
                    if (lastFail == null || now - lastFail >= 40) {
                        Golems_arsenal.LOGGER.warn("[Sanctuary] watchdog readd_failed uuid={} ledger={} "
                                        + "preReason={} preRemoved={} postReason={}",
                                id, HealthLedger.expectedOf(golem), preReason, preRemoved,
                                RemovalGuard.rawRemovalReason(golem));
                    }
                }
            }
        }
        // G1c：坐标被写成非有限值（清除类攻击的另一半）。
        if (!Double.isFinite(golem.getX()) || !Double.isFinite(golem.getY()) || !Double.isFinite(golem.getZ())) {
            posRepaired++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] watchdog non-finite position uuid={} pos=({},{},{})",
                    id, golem.getX(), golem.getY(), golem.getZ());
        }
        // G1e：tick 列表被摘掉 = 实体还在但不再 tick。hard_lock 下每轮补回。
        if (hardLock()) {
            IndexGuard.ensureTicking(golem);
        }

    }

    // ------------------------------------------------------------------ G2 原始索引

    private static boolean rawIndexed(ServerLevel level, UUID id) {
        try {
            Object manager = read(entityManagerField(), level);
            if (manager == null) {
                return false;
            }
            Object lookup = read(entityLookupField(manager), manager);
            if (lookup == null) {
                return false;
            }
            Object map = read(lookupByUuidField(lookup), lookup);
            return map instanceof Map<?, ?> byUuid && byUuid.containsKey(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static boolean hardLock() {
        try {
            return a_silly_cat.golems_arsenal.Config.SANCTUARY_HARD_LOCK.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int lockInterval() {
        try {
            return a_silly_cat.golems_arsenal.Config.SANCTUARY_LOCK_INTERVAL.get();
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static boolean clearDeadFlag(AbstractGolemEntity<?, ?> golem) {
        try {
            if (deadField == null) {
                deadField = find(net.minecraft.world.entity.LivingEntity.class, "f_20890_");
            }
            if (deadField == null) {
                return false;
            }
            if (!deadField.getBoolean(golem)) {
                return false;
            }
            deadField.setBoolean(golem, false);
            Golems_arsenal.LOGGER.warn("[Sanctuary] HARDLOCK dead=false uuid={} ledger={}",
                    golem.getUUID(), HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean clearDeathTime(AbstractGolemEntity<?, ?> golem) {
        try {
            if (deathTimeField == null) {
                deathTimeField = find(net.minecraft.world.entity.LivingEntity.class, "f_20919_");
            }
            if (deathTimeField == null) {
                return false;
            }
            if (deathTimeField.getInt(golem) <= 0) {
                return false;
            }
            deathTimeField.setInt(golem, 0);
            Golems_arsenal.LOGGER.warn("[Sanctuary] HARDLOCK deathTime=0 uuid={} ledger={}",
                    golem.getUUID(), HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        AbstractGolemEntity<?, ?> golem = LIVE.remove(old);
        if (golem != null) {
            LIVE.put(fresh, golem);
        }
        Long fail = LAST_READD_FAIL.remove(old);
        if (fail != null) {
            LAST_READD_FAIL.put(fresh, fail);
        }
    }

    private static Object read(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field removalReasonField() {
        if (removalReasonField == null) {
            removalReasonField = find(Entity.class, "f_146795_");
        }
        return removalReasonField;
    }

    private static Field addedToWorldField() {
        if (addedToWorldField == null) {
            addedToWorldField = find(Entity.class, "isAddedToWorld");
        }
        return addedToWorldField;
    }

    private static Field levelCallbackField() {
        if (levelCallbackField == null) {
            levelCallbackField = find(Entity.class, "f_146801_");
        }
        return levelCallbackField;
    }

    private static Field entityManagerField() {
        if (entityManagerField == null) {
            entityManagerField = find(ServerLevel.class, "f_143244_");
        }
        return entityManagerField;
    }

    private static Field entityLookupField(Object manager) {
        if (entityLookupField == null) {
            entityLookupField = find(manager.getClass(), "f_157494_");
        }
        return entityLookupField;
    }

    private static Field lookupByUuidField(Object lookup) {
        if (lookupByUuidField == null) {
            lookupByUuidField = find(lookup.getClass(), "f_156808_");
        }
        return lookupByUuidField;
    }

    private static Field find(Class<?> owner, String name) {
        try {
            Field field = ObfuscationReflectionHelper.findField(owner, name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String statusLine() {
        return "[sanctuary] watchdog watched=" + LIVE.size()
                + " hardLock=" + (hardLock() ? "on/" + lockInterval() + "t" : "off")
                + " reasonCleared=" + reasonCleared
                + " revivedFields=" + revivedFields
                + " readded=" + readded
                + " readdFailed=" + readdFailed
                + " posRepaired=" + posRepaired
                + " notIndexed=" + notIndexed
                + " lookupReplaced=" + lookupReplaced;
    }

    public static void forget(UUID id) {
        LIVE.remove(id);
    }
}
