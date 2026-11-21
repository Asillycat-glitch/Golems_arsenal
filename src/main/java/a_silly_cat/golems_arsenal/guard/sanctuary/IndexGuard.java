package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTickList;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引/回调/直伤这三条"没有事件、没有 setHealth"的野路子的 mod 层门。
 *
 * <p>2026-11-11 交接文档把这三条列成"mod 层管不到"，其实 mod 层能管，只是之前没写：</p>
 * <ul>
 *   <li>{@code ClassInstanceMultiMap#remove} / {@code EntityLookup#remove}：拆索引的终点。
 *       拦在这里，实体仍在 section 和 byUuid 里，Anchor 不需要"重建"；</li>
 *   <li>{@code Entity#setLevelCallback(NULL)}：清除类模组让实体"不再 tick"的常用手法；</li>
 *   <li>{@code LivingEntity#actuallyHurt} / {@code GuardedEntity#actuallyHurt}：绕过
 *       {@code hurt()}（因而绕过 LivingAttackEvent 和 hurt 窗口）的直伤入口。</li>
 * </ul>
 *
 * <p>判据不是类名，而是"受保护傀儡 + 还活着 + 这次移除/伤害不在正常生命周期里"。
 * 正常卸载、跨维度、玩家回收、真死都放行；只有"活体被折索引/被直伤"才拒绝。</p>
 */
public final class IndexGuard {

    private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static Field serverTickList;

    private static long indexBlocked;
    private static long lookupBlocked;
    private static long tickBlocked;
    private static long callbackBlocked;
    private static long reindexed;
    private static long tickingRestored;
    private static long damageBlocked;

    private IndexGuard() {
    }

    // ------------------------------------------------------------------ 索引

    /** {@code ClassInstanceMultiMap#remove(Object)} / {@code EntityLookup#remove(EntityAccess)} HEAD。 */
    public static boolean denyIndexRemoval(Object value, String site) {
        try {
            if (ProtectedEntityPolicy.inRebuild()) {
                return false;
            }
            if (!(value instanceof Entity entity) || !(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return false;
            }
            if (!protectedAlive(golem)) {
                return false;
            }
            Entity.RemovalReason reason = RemovalGuard.rawRemovalReason(entity);
            if (RemovalGuard.isAllowedLifecycle(golem)) {
                return false;
            }
            boolean hostile;
            if (reason == null) {
                // 正常 chunk visibility 变化也会走 EntityLookup#remove，但那时没有 removalReason。
                // 只在"刚被写过/正在被受伤"时才当成拆索引，避免把正常的 tracking 变化拦死。
                hostile = HealthLedger.recentTamper(golem, 200) || SanctuaryScopes.inHurt(golem);
            } else if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                // 冒充卸载：区块还开着，说明不是真的区块卸载。
                hostile = entity.level().isLoaded(entity.blockPosition());
            } else if (reason == Entity.RemovalReason.CHANGED_DIMENSION
                    || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                hostile = false;
            } else {
                hostile = true;
            }
            if (!hostile) {
                return false;
            }
            if (site != null && site.contains("Lookup")) {
                lookupBlocked++;
            } else {
                indexBlocked++;
            }
            log(golem, "INDEX-DENIED site=" + site + " reason=" + reason
                    + " ledger=" + HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code EntityTickList#remove} HEAD：把受保护活体从 tick 列表里摘掉 = 软删除。 */
    public static boolean denyTickListRemoval(Entity entity) {
        try {
            if (ProtectedEntityPolicy.inRebuild()) {
                return false;
            }
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem) || !protectedAlive(golem)) {
                return false;
            }
            Entity.RemovalReason reason = RemovalGuard.rawRemovalReason(entity);
            boolean hostile;
            if (reason == null) {
                hostile = HealthLedger.recentTamper(golem, 200) || SanctuaryScopes.inHurt(golem);
            } else {
                hostile = !RemovalGuard.isAllowedLifecycle(golem);
            }
            if (!hostile) {
                return false;
            }
            tickBlocked++;
            log(golem, "TICKLIST-DENIED reason=" + reason
                    + " ledger=" + HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把实体塞回 {@code ServerLevel.entityTickList}（如果不在了）。 */
    public static boolean ensureTicking(AbstractGolemEntity<?, ?> golem) {
        try {
            if (golem == null || !(golem.level() instanceof ServerLevel level)) {
                return false;
            }
            if (serverTickList == null) {
                serverTickList = findField(ServerLevel.class, "f_143243_", "entityTickList");
            }
            Object value = serverTickList == null ? null : serverTickList.get(level);
            if (!(value instanceof EntityTickList tickList)) {
                return false;
            }
            if (tickList.contains(golem)) {
                return true;
            }
            tickList.add(golem);
            tickingRestored++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] TICKLIST-READDED uuid={} ledger={}",
                    golem.getUUID(), HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code Entity#setLevelCallback} HEAD：NULL 或非原版 callback 都可能把实体变成"不再 tick 的雕像"。 */
    public static boolean denyLevelCallback(Entity entity, EntityInLevelCallback callback) {
        try {
            if (ProtectedEntityPolicy.inRebuild()) {
                return false;
            }
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return false;
            }
            if (!protectedAlive(golem)) {
                return false;
            }

            boolean nullCallback = callback == EntityInLevelCallback.NULL;
            String callbackClass = callback == null ? "null" : callback.getClass().getName();
            boolean vanillaCallback = callbackClass.startsWith(
                    "net.minecraft.world.level.entity.PersistentEntitySectionManager");
            if (!nullCallback && !vanillaCallback) {
                // 通用规则：受保护实体的 level callback 只能由原版 section manager 维护。
                // 任何第三方自定义 callback（死亡回调/幽灵回调/软删除回调）都拒。
                Entity.RemovalReason reason = RemovalGuard.rawRemovalReason(entity);
                if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                        || reason == Entity.RemovalReason.CHANGED_DIMENSION
                        || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                        || reason == Entity.RemovalReason.DISCARDED
                        && RemovalGuard.isAllowedLifecycle(golem)) {
                    return false;
                }
                callbackBlocked++;
                log(golem, "FOREIGN-LEVEL-CALLBACK-DENIED callback=" + callbackClass
                        + " reason=" + reason + " ledger=" + HealthLedger.expectedOf(golem));
                return true;
            }
            if (!nullCallback) {
                return false;
            }

            Entity.RemovalReason reason = RemovalGuard.rawRemovalReason(entity);
            // 区块卸载/跨维度/玩家搬运的 NULL 回调是正常清理的一部分：
            // 这里不拿 isLoaded 判竞态，直接放行，否则可能在卸载边界把实体留成"还在 tick"。
            if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                    || reason == Entity.RemovalReason.CHANGED_DIMENSION
                    || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                return false;
            }
            if (reason == Entity.RemovalReason.DISCARDED
                    && RemovalGuard.isAllowedLifecycle(golem)) {
                return false;
            }
            if (reason == null && !HealthLedger.recentTamper(golem, 200)) {
                // 正常生命周期里也可能先清 reason 再清回调；没有篡改痕迹时不拦。
                return false;
            }
            callbackBlocked++;
            log(golem, "LEVEL-CALLBACK-DENIED reason=" + reason
                    + " ledger=" + HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * addFreshEntity 失败时的最后注册路径：直接调 PersistentEntitySectionManager.addNewEntity。
     * 只用于新建投影，不用于旧实体；调用方负责重建作用域与客户端 add 包。
     */
    public static boolean forceRegister(Entity entity) {
        try {
            if (entity == null || !(entity.level() instanceof ServerLevel level)) {
                return false;
            }
            Object manager = readField(level, "f_143244_", "entityManager");
            if (manager == null) {
                return false;
            }
            for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
                if (!method.getName().equals("m_157533_") || method.getParameterCount() != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(manager, entity);
                    boolean indexed = level.getEntity(entity.getId()) == entity;
                    Golems_arsenal.LOGGER.warn("[Sanctuary] force_register uuid={} indexed={}",
                            entity.getUUID(), indexed);
                    return indexed;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 完整重挂：先把旧 lookup/section/tick/knownUuids 摘干净，再走
     * {@code ServerLevel.addFreshEntity}，让 ChunkMap 真正建立 tracking。
     *
     * <p>旧实现直接往 EntityLookup/Section 里塞对象，绕过了 ChunkMap，
     * 结果就是服务端 section 有、客户端/Jade 没有、watchdog 永动机。</p>
     */
    public static boolean reindex(AbstractGolemEntity<?, ?> golem) {
        try {
            if (golem == null || golem.level().isClientSide()
                    || !(golem.level() instanceof ServerLevel level)) {
                return false;
            }
            unindex(golem);
            RemovalGuard.clearField(golem);
            boolean[] added = {false};
            ProtectedEntityPolicy.runRebuild(() -> added[0] =
                    a_silly_cat.golems_arsenal.guard.index.WorldIndexGuard.register(level, golem));
            if (!added[0]) {
                Golems_arsenal.LOGGER.warn("[Sanctuary] REINDEX-ADD-FAILED uuid={} ledger={}",
                        golem.getUUID(), HealthLedger.expectedOf(golem));
                return false;
            }
            ensureTicking(golem);
            boolean indexed = level.getEntity(golem.getId()) == golem;
            reindexed++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] INDEX-READDED uuid={} ledger={} lookup={}",
                    golem.getUUID(), HealthLedger.expectedOf(golem), indexed);
            return indexed;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] INDEX-READD-FAILED uuid={} {}",
                    golem == null ? null : golem.getUUID(), t.toString());
            return false;
        }
    }

    /** 把实体从旧索引/knownUuids/tickList 完整摘掉，给 addFreshEntity 一个干净起点。 */
    private static void unindex(AbstractGolemEntity<?, ?> golem) {
        try {
            ServerLevel level = (ServerLevel) golem.level();
            Object manager = readField(level, "f_143244_", "entityManager");
            if (manager == null) {
                return;
            }
            Object lookup = readField(manager, "f_157494_", "visibleEntityStorage");
            if (lookup instanceof EntityLookup<?> rawLookup) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                EntityLookup raw = (EntityLookup) rawLookup;
                try {
                    raw.remove(golem);
                } catch (Throwable ignored) {
                }
            }
            Object storage = readField(manager, "f_157495_", "sectionStorage");
            if (storage instanceof EntitySectionStorage<?> rawStorage) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                EntitySectionStorage sections = (EntitySectionStorage) rawStorage;
                EntitySection<?> section = sections.getOrCreateSection(
                        SectionPos.asLong(golem.blockPosition()));
                @SuppressWarnings({"rawtypes", "unchecked"})
                EntitySection rawSection = (EntitySection) section;
                try {
                    rawSection.remove(golem);
                } catch (Throwable ignored) {
                }
            }
            Object known = readField(manager, "f_157491_", "knownUuids");
            if (known instanceof Set<?> rawSet) {
                @SuppressWarnings("unchecked")
                Set<Object> uuids = (Set<Object>) rawSet;
                uuids.remove(golem.getUUID());
            }
            if (serverTickList == null) {
                serverTickList = findField(ServerLevel.class, "f_143243_", "entityTickList");
            }
            Object tick = serverTickList == null ? null : serverTickList.get(level);
            if (tick instanceof EntityTickList tickList) {
                try {
                    tickList.remove(golem);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 直伤

    /** {@code actuallyHurt} HEAD：不在"被 Sanctuary 放行的 hurt 窗口"里的直伤一律拒绝。 */
    public static boolean denyDirectDamage(LivingEntity entity, DamageSource source, float amount, String site) {
        try {
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem) || !protectedAlive(golem)) {
                return false;
            }
            if (SanctuaryScopes.inLoad() || SanctuaryScopes.inInternal() || SanctuaryScopes.inDeath()) {
                return false;
            }
            if (SanctuaryScopes.isAdmitted(golem)) {
                return false;
            }
            damageBlocked++;
            log(golem, "HURT-DENIED site=" + site + " amount=" + amount
                    + " source=" + (source == null ? "null" : source.getMsgId())
                    + " ledger=" + HealthLedger.expectedOf(golem));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 公共判据

    private static boolean protectedAlive(AbstractGolemEntity<?, ?> golem) {
        if (golem == null || golem.level().isClientSide() || golem.getOwnerUUID() == null) {
            return false;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return false;
        }
        return !SanctuaryScopes.inDeath() && !HealthLedger.observedDeath(golem);
    }

    private static void log(AbstractGolemEntity<?, ?> golem, String detail) {
        long now = golem.level().getGameTime();
        Long last = LAST_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        Golems_arsenal.LOGGER.warn("[Sanctuary] {} target={} uuid={}",
                detail, golem.getName().getString(), golem.getUUID());
    }

    // ------------------------------------------------------------------ 反射

    private static Field findField(Class<?> owner, String srg, String fallback) {
        try {
            Field field = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(owner, srg);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fallback);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object readField(Object target, String srg, String fallback) {
        if (target == null) {
            return null;
        }
        try {
            Field field = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                    target.getClass(), srg);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fallback);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        move(LAST_LOG, old, fresh);
    }

    private static void move(Map<UUID, Long> map, UUID old, UUID fresh) {
        Long value = map.remove(old);
        if (value != null) {
            map.put(fresh, value);
        }
    }

    public static String statusLine() {
        return "[sanctuary] index indexBlocked=" + indexBlocked
                + " lookupBlocked=" + lookupBlocked
                + " tickBlocked=" + tickBlocked
                + " callbackBlocked=" + callbackBlocked
                + " readded=" + reindexed
                + " tickingRestored=" + tickingRestored
                + " damageBlocked=" + damageBlocked;
    }

    public static void clear() {
        LAST_LOG.clear();
        indexBlocked = 0;
        lookupBlocked = 0;
        tickBlocked = 0;
        callbackBlocked = 0;
        reindexed = 0;
        tickingRestored = 0;
        damageBlocked = 0;
    }
}
