package a_silly_cat.golems_arsenal.testbench;

import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * P2 真值读取（PRIVATE TESTBENCH）。
 *
 * <p>为什么不能直接 {@code entity.getHealth()}：1.20.1 的当前血量存在
 * {@code SynchedEntityData} 的 {@code DATA_HEALTH_ID} 里，但 jzyy 这类 coremod 会重定向
 * {@code getHealth}，ArcaneVortex / super-steve 又可能替换 {@code SynchedEntityData} 本身或删索引。
 * Guard 如果用 {@code getHealth()} 做判定，攻击者只要让 getter 说“满血”就能骗过 reconcile。</p>
 *
 * <p>这里绕过 getter，直接读：</p>
 * <ul>
 *   <li>{@code Entity.f_19804_} → {@code SynchedEntityData}（字段，不走可能被覆写的 getter）；</li>
 *   <li>{@code SynchedEntityData.f_135345_}（itemsById）→ {@code DataItem.f_135391_}（value）；</li>
 *   <li>傀儡再读 {@code GuardedEntity.guardedData.amount()}，但只作为诊断值；
 *       真血以 {@code DATA_HEALTH_ID} 为准。ArcaneVortex 会把 maxHealth 压到 1，
 *       此时 guardedData 可能仍是 100；取 max 会漏掉真实的 1/1 状态。</li>
 * </ul>
 *
 * <p>只读，不初始化 guardedData、不写血。反射字段整体不可用时 {@link #read} 返回 {@code null}，
 * 由调用方回退到原来 {@link HealthTrace#readHealthQuiet(LivingEntity)}；单个数据项缺失时返回
 * NaN，交给 Guard 记录并停止回滚。</p>
 */
final class HealthTruth {

    private static final EntityDataAccessor<Float> HEALTH_ACCESSOR = resolveHealthAccessor();

    /** Entity.entityData（protected final）。 */
    private static final Field ENTITY_DATA = resolveField(Entity.class, "f_19804_");

    /** SynchedEntityData.itemsById（private final Int2ObjectMap）。 */
    private static final Field ITEMS_BY_ID = resolveField(SynchedEntityData.class, "f_135345_");

    /**
     * SynchedEntityData.DataItem.value。1.20.1 是 {@code f_135391_}；老映射里有人写
     * {@code f_135403_}（那其实是 getter 的 SRG 名），所以两个都试。
     */
    private static final Field DATA_ITEM_VALUE = resolveDataItemValue();

    /** GuardedEntity.guardedData（mod 自有字段，生产里不重映射）。 */
    private static final Field GUARDED_DATA = resolveGuardedData();

    /**
     * Entity.removalReason。isRemoved() 是 final，但 getRemovalReason() 不是；
     * 直接读字段，避免被覆写的 getter 骗过索引检查。
     */
    private static final Field REMOVAL_REASON = resolveField(Entity.class, "f_146795_");

    private static volatile Method guardedAmount;

    private HealthTruth() {
    }

    /**
     * 一次真值快照。
     *
     * @param synced         DATA_HEALTH_ID 里实际存的值
     * @param guarded        傀儡 guardedData.amount()；非傀儡/未初始化是 NaN
     * @param effective      真血：优先 synced；索引缺失时才回退 guarded（不再取 max）
     * @param reported       getHealth() 的返回值；不需要时为 NaN（避免无谓的 getter 调用）
     * @param containerOk    SynchedEntityData 的运行时类是否就是原版类
     * @param container      运行时类名
     * @param removed        isRemoved()
     * @param removalReason  removalReason 的名字；null 表示没有
     * @param addedToWorld   Forge isAddedToWorld()
     * @param canUpdate      Entity.canUpdate()
     * @param inLevelById    level.getEntity(id) == entity
     * @param inLevelByUuid  level.getEntity(uuid) == entity
     */
    record Snapshot(float synced, float guarded, float effective, float reported,
                    boolean containerOk, String container,
                    boolean removed, String removalReason, boolean addedToWorld,
                    boolean canUpdate, boolean inLevelById, boolean inLevelByUuid) {

        boolean divergence() {
            return Float.isFinite(effective) && Float.isFinite(reported)
                    && Math.abs(effective - reported) > HealthGuard.EPS;
        }

        /** synced 与 guardedData 长期不一致：通常是外部写了其中一个，或 maxHealth 被压。 */
        boolean guardedMismatch() {
            return Float.isFinite(synced) && Float.isFinite(guarded)
                    && Math.abs(synced - guarded) > HealthGuard.EPS;
        }

        String notes() {
            return "synced=" + num(synced)
                    + " guarded=" + num(guarded)
                    + " reported=" + num(reported)
                    + " class=" + simple(container)
                    + " removed=" + removed
                    + " reason=" + removalReason
                    + " added=" + addedToWorld
                    + " canUpdate=" + canUpdate
                    + " byId=" + inLevelById
                    + " byUuid=" + inLevelByUuid;
        }
    }

    /**
     * 读快照。
     *
     * @param withReported 是否调用 getHealth() 取“报告值”。写入路径传 false（避免递归/副作用），
     *                     tick 末尾传 true 用来抓 getHealth 重定向。
     */
    static Snapshot read(LivingEntity entity, boolean withReported) {
        if (entity == null) {
            return null;
        }
        // 反射初始化失败时整体回退到旧的 getHealth 路径，而不是把每只实体都当成“索引缺失”。
        if (ENTITY_DATA == null || HEALTH_ACCESSOR == null
                || ITEMS_BY_ID == null || DATA_ITEM_VALUE == null) {
            return null;
        }
        try {
            SynchedEntityData data = entityData(entity);
            String container = data == null ? "null" : data.getClass().getName();
            boolean containerOk = data != null && data.getClass() == SynchedEntityData.class;

            float synced = readSynced(data);
            float guarded = readGuarded(entity);
            // 关键：真血是 DATA_HEALTH_ID（synced），不是 max(synced, guarded)。
            // ArcaneVortex 把 maxHealth 压到 1 时，guardedData 仍可能是 100；取 max 会让
            // Guard 以为傀儡满血，从而漏掉 1/1 的真实状态。guarded 只作诊断/索引缺失兜底。
            float effective = Float.isFinite(synced) ? synced : guarded;
            float reported = withReported ? HealthTrace.readHealthQuiet(entity) : Float.NaN;

            Level level = entity.level();
            Entity byId = level == null ? null : level.getEntity(entity.getId());
            Entity byUuid = level instanceof ServerLevel serverLevel
                    ? serverLevel.getEntity(entity.getUUID()) : null;
            Entity.RemovalReason reason = readRemovalReason(entity);

            return new Snapshot(synced, guarded, effective, reported,
                    containerOk, container,
                    entity.isRemoved(), reason == null ? null : reason.name(),
                    entity.isAddedToWorld(), entity.canUpdate(),
                    byId == entity, byUuid == entity);
        } catch (Throwable ignored) {
            // 反射失败时不要影响游戏；调用方会回退。
            return null;
        }
    }

    // ------------------------------------------------------------------ 底层读取

    private static SynchedEntityData entityData(LivingEntity entity) {
        if (ENTITY_DATA == null) {
            return null;
        }
        try {
            return (SynchedEntityData) ENTITY_DATA.get(entity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float readSynced(SynchedEntityData data) {
        if (data == null || HEALTH_ACCESSOR == null || ITEMS_BY_ID == null || DATA_ITEM_VALUE == null) {
            return Float.NaN;
        }
        try {
            Object map = ITEMS_BY_ID.get(data);
            if (!(map instanceof Map<?, ?> items)) {
                return Float.NaN;
            }
            Object item = items.get(HEALTH_ACCESSOR.getId());
            if (item == null) {
                // super-steve 会直接删索引；这里返回 NaN，由容器/索引检查记录。
                return Float.NaN;
            }
            Object value = DATA_ITEM_VALUE.get(item);
            return value instanceof Float f ? f : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static Entity.RemovalReason readRemovalReason(Entity entity) {
        if (REMOVAL_REASON != null) {
            try {
                return (Entity.RemovalReason) REMOVAL_REASON.get(entity);
            } catch (Throwable ignored) {
                // 回退到 getter。
            }
        }
        return entity.getRemovalReason();
    }

    private static float readGuarded(LivingEntity entity) {        if (!(entity instanceof GuardedEntity) || GUARDED_DATA == null) {
            return Float.NaN;
        }
        try {
            Object guarded = GUARDED_DATA.get(entity);
            if (guarded == null) {
                // guardedData 尚未初始化：不要调用 getGuardedDataImpl，避免懒初始化副作用。
                return Float.NaN;
            }
            Method method = guardedAmount;
            if (method == null || method.getDeclaringClass() != guarded.getClass()) {
                method = guarded.getClass().getMethod("amount");
                method.setAccessible(true);
                guardedAmount = method;
            }
            Object value = method.invoke(guarded);
            return value instanceof Float f ? f : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    // ------------------------------------------------------------------ 反射初始化

    private static EntityDataAccessor<Float> resolveHealthAccessor() {
        try {
            Field field = ObfuscationReflectionHelper.findField(LivingEntity.class, "f_20961_");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> accessor = (EntityDataAccessor<Float>) field.get(null);
            return accessor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field resolveField(Class<?> owner, String name) {
        try {
            Field field = ObfuscationReflectionHelper.findField(owner, name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field resolveDataItemValue() {
        try {
            Class<?> itemClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataItem");
            // f_135391_ 是生产 SRG；value 是开发环境 official 名；f_135403_ 是旧资料里的误名，留作兜底。
            for (String name : new String[]{"f_135391_", "value", "f_135403_"}) {
                try {
                    Field field = itemClass.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // 试下一个映射名。
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field resolveGuardedData() {
        try {
            Field field = GuardedEntity.class.getDeclaredField("guardedData");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String num(float value) {
        if (!Float.isFinite(value)) {
            return Float.isNaN(value) ? "NaN" : String.valueOf(value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String simple(String className) {
        if (className == null) {
            return "null";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
