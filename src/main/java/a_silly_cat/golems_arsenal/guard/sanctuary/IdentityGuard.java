package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import a_silly_cat.golems_arsenal.guard.SoulRegistry;
import dev.xkmc.modulargolems.content.capability.GolemTracker;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份层：受保护傀儡的 UUID 不是稳定身份，而是"一次性名牌"。
 *
 * <p>为什么 mod 层也要这一层：ArcaneVortex / toaru 的清除流程会把目标 UUID 记进
 * 自己的名单（死亡名单、待处理队列、墓碑、延迟任务），然后<b>按 UUID 回来补刀</b>。
 * 只拦 API 不够——名单里那一条仍然指向这只傀儡。把 UUID 换掉之后，旧名字变成
 * {@link #FAKE_DEAD} 里的死名字，对方的后续步骤会打空。</p>
 *
 * <p>三条路径分开：</p>
 * <ul>
 *   <li>{@link #rotate}：门拦下一次移除时，把仍活着的实体换名（同时迁移世界索引和
 *       我们自己的所有 UUID 表）；</li>
 *   <li>{@link #scrubTag}：从物品/NBT 重新生成实体时，如果 NBT 里的 UUID 在假死名单里，
 *       换成新名字。这是"玩家手里的傀儡放出来还是同一只、但不再被旧名单命中"的实现点；</li>
 *   <li>{@link #migrate}：只迁移内部表，供 Anchor 用快照重建时复用。</li>
 * </ul>
 *
 * <p>所有失败都吞掉并记数：换名是增益项，不能因为一次反射失败把移除守卫本身带崩。</p>
 */
public final class IdentityGuard {

    /** 假死名单上限。超过之后清空：旧名单只影响"生成时洗名字"，不影响存活判定。 */
    private static final int MAX_FAKE_DEAD = 8192;
    /** 同一只傀儡 5 秒内最多换一次名，避免被连续攻击时刷爆索引。 */
    private static final long ROTATE_COOLDOWN_TICKS = 100L;

    private static final Set<UUID> FAKE_DEAD = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_ROTATE = new ConcurrentHashMap<>();

    private static long rotated;
    private static long washed;
    private static long failed;

    private IdentityGuard() {
    }

    // ------------------------------------------------------------------ 假死名单

    public static boolean isFakeDead(UUID id) {
        return id != null && FAKE_DEAD.contains(id);
    }

    public static void markFakeDead(UUID id) {
        if (id == null) {
            return;
        }
        if (FAKE_DEAD.size() >= MAX_FAKE_DEAD) {
            FAKE_DEAD.clear();
            Golems_arsenal.LOGGER.warn("[Sanctuary] fake_dead list reached {} entries; cleared", MAX_FAKE_DEAD);
        }
        FAKE_DEAD.add(id);
    }

    public static int fakeDeadCount() {
        return FAKE_DEAD.size();
    }

    // ------------------------------------------------------------------ 生成时洗名字

    /**
     * {@code Entity#load} HEAD / Anchor 重建前调用：NBT 里的 UUID 如果已经在假死名单里，
     * 换成一个新 UUID。只对服务端、玩家傀儡生效。
     */
    public static void scrubTag(Entity entity, CompoundTag tag) {
        try {
            if (entity == null || tag == null || entity.level().isClientSide()) {
                return;
            }
            if (!(entity instanceof AbstractGolemEntity<?, ?>)) {
                return;
            }
            if (!tag.hasUUID("UUID")) {
                return;
            }
            UUID old = tag.getUUID("UUID");
            if (!FAKE_DEAD.contains(old)) {
                return;
            }
            UUID fresh = UUID.randomUUID();
            tag.putUUID("UUID", fresh);
            // 只把旧名字记进假死名单。新名字不能记——否则这只傀儡下次从区块读档时
            // 又会被当成"物品生成"再洗一次，账本/监视表的 UUID 会跟丢。
            washed++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] SPAWN-UUID-WASHED {} -> {} fakeDead={}",
                    old, fresh, FAKE_DEAD.size());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 拦下时换名

    /**
     * 把一只仍活着的受保护傀儡换名。
     *
     * @param site 触发点（只用于日志）
     * @return true 表示 UUID 已换、索引和内部表都已迁移
     */
    public static boolean rotate(AbstractGolemEntity<?, ?> golem, String site) {
        if (golem == null || golem.level().isClientSide()) {
            return false;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return false;
        }
        if (!(golem.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return false;
        }
        if (golem.getOwnerUUID() == null || SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
            return false;
        }
        float expected = HealthLedger.expectedOf(golem);
        if (!Float.isFinite(expected) || expected <= 1.0F) {
            return false;
        }

        UUID old = golem.getUUID();
        long now = level.getGameTime();
        Long last = LAST_ROTATE.get(old);
        if (last != null && now - last < ROTATE_COOLDOWN_TICKS) {
            return false;
        }

        UUID fresh = UUID.randomUUID();
        Object tracker = null;
        try {
            tracker = golem.getTracker();
        } catch (Throwable ignored) {
        }

        try {
            golem.setUUID(fresh);
        } catch (Throwable t) {
            failed++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] IDENTITY-ROTATE-FAILED {}: {}", old, t.toString());
            return false;
        }

        // 世界索引：byUuid 和 knownUuids 跟着换。byId 不变，实体对象和 tick 列表不动。
        try {
            replaceWorldIndex(level, golem, old, fresh);
        } catch (Throwable t) {
            failed++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] IDENTITY-INDEX-MIGRATE-FAILED {} -> {}: {}",
                    old, fresh, t.toString());
        }

        // 宿主模组的 tracker 是按 UUID 存的；不迁移的话换名之后它会以为这是一只新傀儡。
        try {
            if (tracker instanceof GolemTracker gt) {
                var data = gt.data.remove(old);
                if (data != null) {
                    gt.data.put(fresh, data);
                }
            }
        } catch (Throwable ignored) {
        }

        migrate(golem, old, fresh);
        markFakeDead(old);
        LAST_ROTATE.put(fresh, now);
        rotated++;
        Golems_arsenal.LOGGER.warn("[Sanctuary] IDENTITY-ROTATED {} -> {} site={} fakeDead={}",
                old, fresh, site, FAKE_DEAD.size());
        return true;
    }

    /**
     * 只迁移我们自己的 UUID 表，不改实体字段。Anchor 用快照重建出一个新 UUID 的实体后调用。
     */
    public static void migrate(AbstractGolemEntity<?, ?> golem, UUID old, UUID fresh) {
        if (old == null || fresh == null || old.equals(fresh)) {
            return;
        }
        try {
            HealthLedger.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            SoulRegistry.rotateProjection(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            Anchor.rotateIdentity(golem, old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            DataGuard.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            Projection.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            RemovalGuard.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            Sanctuary.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            Watchdog.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            AttributeGuard.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            DamageAdmission.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            IndexGuard.rotateIdentity(old, fresh);
        } catch (Throwable ignored) {
        }
        try {
            AgentBridge.onIdentityRotated(old, fresh);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 世界索引

    private static Field serverEntityManager;
    private static Field managerLookup;
    private static Field managerKnownUuids;
    private static Field lookupByUuid;

    private static void replaceWorldIndex(ServerLevel level, Entity entity, UUID old, UUID fresh) {
        try {
            if (serverEntityManager == null) {
                serverEntityManager = findField(ServerLevel.class, "f_143244_", "entityManager");
            }
            Object manager = serverEntityManager == null ? null : serverEntityManager.get(level);
            if (manager == null) {
                return;
            }
            if (managerLookup == null) {
                managerLookup = findField(manager.getClass(), "f_157494_", "visibleEntityStorage");
            }
            if (managerKnownUuids == null) {
                managerKnownUuids = findField(manager.getClass(), "f_157491_", "knownUuids");
            }
            Object lookup = managerLookup == null ? null : managerLookup.get(manager);
            if (lookup != null) {
                if (lookupByUuid == null) {
                    lookupByUuid = findField(EntityLookup.class, "f_156808_", "byUuid");
                }
                Object map = lookupByUuid == null ? null : lookupByUuid.get(lookup);
                if (map instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> byUuid = (Map<Object, Object>) raw;
                    byUuid.remove(old);
                    byUuid.put(fresh, entity);
                }
            }
            Object known = managerKnownUuids == null ? null : managerKnownUuids.get(manager);
            if (known instanceof Set<?> raw) {
                @SuppressWarnings("unchecked")
                Set<Object> uuids = (Set<Object>) raw;
                uuids.remove(old);
                uuids.add(fresh);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 反射

    private static Field findField(Class<?> owner, String srg, String... fallback) {
        try {
            Field field = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(owner, srg);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        for (String name : fallback) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static String statusLine() {
        return "[sanctuary] identity rotated=" + rotated
                + " washed=" + washed
                + " failed=" + failed
                + " fakeDead=" + FAKE_DEAD.size();
    }

    public static void clear() {
        FAKE_DEAD.clear();
        LAST_ROTATE.clear();
        rotated = 0;
        washed = 0;
        failed = 0;
    }
}
