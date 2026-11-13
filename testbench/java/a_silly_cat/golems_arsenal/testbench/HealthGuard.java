package a_silly_cat.golems_arsenal.testbench;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgradeLevels;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * P2：按实体 Guard（PRIVATE TESTBENCH）。
 *
 * <p>只对 {@code role=test} 且 {@code guardMode != OFF} 的实体生效；control 永远 OFF。
 * 三个写入点 + 一个兜底：</p>
 *
 * <ul>
 *   <li>{@code GuardedEntity.setGuardedDataImpl}：傀儡真正的直写 backdoor；</li>
 *   <li>{@code LivingEntity.setHealth} / {@code GuardedEntity.setHealth}：普通实体和傀儡的外部写；</li>
 *   <li>{@code Entity.setRemoved(KILLED/DISCARDED)}：直接清除；expected>0 才拦；</li>
 *   <li>tick 末尾 reconcile：反射直接改真血、HEALTH_SEAL 裁剪、任何漏掉的写，都通过
 *       {@link HealthTrace} 的影子账本 expected 对比；ENFORCE 回滚，SHADOW 只记录。</li>
 * </ul>
 *
 * <p><b>允许合法事件写：</b>P1 的 ledger 在 {@code LivingDamageEvent}/{@code LivingHealEvent}
 * 时已经把 expected 更新成“这次写应该到达的值”。写入 arg 与 expected 一致就放行；
 * 不一致才 SHADOW/ENFORCE。这样正常 L2、heal、死亡都不会被误拦。</p>
 *
 * <p>本类不碰 L2 的 amount，也不改 control；所有决定写 {@code kind=guard} 的 JSONL。</p>
 */
public final class HealthGuard {

    static final float EPS = 1.0E-3F;

    /** reconcile 只在 gap 变大时记一条；避免 feed 回血每 tick 刷屏。 */
    private static final Map<UUID, Float> LAST_GAP = new HashMap<>();
    private static final Map<UUID, Float> LAST_MAX = new HashMap<>();

    /**
     * tech_expansion 是“自家锻造模板”的 maxHealth 代价：每级 -10%，走
     * GolemStatType.applyToEntity 的 setBaseValue，不是 AttributeModifier。
     * 因此 P2 必须按模板等级识别它，不能当成 HEALTH_SEAL 把 base 顶回 300。
     */
    private static final Map<UUID, Integer> LAST_TECH_LEVEL = new HashMap<>();

    /** 材料/升级列表变化会重算 maxHealth；这是换部件/改升级，不是外部 seal。 */
    private static final Map<UUID, Integer> LAST_BUILD = new HashMap<>();

    /** getHealth 报告值与真值不一致时，只在数值变化时记一条，避免刷屏。 */
    private static final Map<UUID, String> LAST_TRUTH = new HashMap<>();

    /** SynchedEntityData 运行时类变化时只记一条。 */
    private static final Map<UUID, String> LAST_CONTAINER = new HashMap<>();

    /** synced 与 guardedData 长期不一致时只记一条。 */
    private static final Map<UUID, String> LAST_GUARDED = new HashMap<>();

    /** DATA_HEALTH_ID 索引缺失（super-steve 删索引）只记一次。 */
    private static final Map<UUID, Boolean> LAST_MISSING = new HashMap<>();

    /** 索引一致性：同一状态持续 5 tick 才判定，避免 setRemoved / 区块切换的瞬时状态误报。 */
    private static final Map<UUID, IndexWatch> INDEX = new HashMap<>();

    /**
     * fantasy true damage 豁免：EntityActuallyHurt 写入后，在若干 tick 内接受实际真值，
     * 不拦 setHealth/setRemoved，也不在 reconcile 里回滚。梦终剑应该能伤害傀儡。
     */
    private static final Map<UUID, Integer> FANTASY_GRACE = new HashMap<>();

    /**
     * 1.20.1 的 LivingEntity.health 不是普通字段，而是 SynchedEntityData 的 DATA_HEALTH_ID
     * （SRG f_20961_，EntityDataAccessor&lt;Float&gt;）。VanShAttackEntity 和 iss_csw.EntityUtil
     * 都是直接写这个 accessor；P2 也必须在这一层拦，不能只等 tick reconcile。
     */
    private static final EntityDataAccessor<Float> HEALTH_ACCESSOR = resolveHealthAccessor();

    /**
     * SynchedEntityData.entity 的 SRG 字段。字段 refmap 在当前 Mixin/Forge 组合下不可靠，
     * 所以 mixin 不写 @Shadow，统一通过这里反射拿 owner。
     */
    private static final Field SYNCED_ENTITY_FIELD = resolveSyncedEntityField();

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

    private static Field resolveSyncedEntityField() {
        try {
            Field field = ObfuscationReflectionHelper.findField(SynchedEntityData.class, "f_135344_");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Entity entityOf(SynchedEntityData data) {
        if (data == null || SYNCED_ENTITY_FIELD == null) {
            return null;
        }
        try {
            return (Entity) SYNCED_ENTITY_FIELD.get(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isHealthAccessor(EntityDataAccessor<?> key) {
        // 不比较对象 identity：攻击者可以造一个同 id 的新 accessor 绕开 identity 判断。
        // EntityDataAccessor 的 id 在每个实体类里唯一；LivingEntity 的 DATA_HEALTH_ID 是静态共享的。
        return key != null && HEALTH_ACCESSOR != null && key.getId() == HEALTH_ACCESSOR.getId();
    }

    enum Decision {
        ALLOW,
        WOULD_BLOCK,
        BLOCK
    }

    private HealthGuard() {
    }

    static void clear() {
        LAST_GAP.clear();
        LAST_MAX.clear();
        LAST_TECH_LEVEL.clear();
        LAST_BUILD.clear();
        LAST_TRUTH.clear();
        LAST_CONTAINER.clear();
        LAST_GUARDED.clear();
        LAST_MISSING.clear();
        INDEX.clear();
        FANTASY_GRACE.clear();
    }

    /**
     * 换部件/改升级列表后，Modular Golems 会重算 MAX_HEALTH。
     * build 签名变了就接受新 max，更新 baseMax/expected；否则换 upgrade/parts 会被当成 seal 顶回去。
     */
    static void acceptBuildMax(HealthTraceData.Tracked tracked, LivingEntity entity, int tick) {
        if (tracked == null || !(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        int hash;
        try {
            hash = buildHash(golem);
        } catch (Throwable ignored) {
            return;
        }
        UUID uuid = entity.getUUID();
        Integer previous = LAST_BUILD.put(uuid, hash);
        if (previous == null || previous == hash) {
            return;
        }
        float oldBase = HealthTrace.ledgerBaseMax(uuid);
        float max = entity.getMaxHealth();
        if (!Float.isFinite(oldBase) || Math.abs(max - oldBase) <= EPS) {
            return;
        }
        float actual = HealthTrace.readHealthQuiet(entity);
        HealthTrace.acceptBuildMax(uuid, max, actual);
        HealthTrace.logBuildMax(entity, tracked, oldBase, max);
    }

    private static int buildHash(AbstractGolemEntity<?, ?> golem) {
        return Objects.hash(golem.getMaterials(), golem.getUpgrades());
    }

    /**
     * build 变化和 health 写入可能发生在同一个 tick：在写入口当场接受，
     * 不能等 tick 末尾 acceptBuildMax，否则 max 已经变了但 expected 还是旧值。
     */
    static boolean acceptBuildChangeNow(LivingEntity entity, float amount) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        UUID uuid = entity.getUUID();
        int hash;
        try {
            hash = buildHash(golem);
        } catch (Throwable ignored) {
            return false;
        }
        Integer previous = LAST_BUILD.get(uuid);
        if (previous == null || previous == hash) {
            return false;
        }
        if (!HealthTrace.isBuildChangeCaller()) {
            // 伤害事件 / tick / validate 里也会出现 build hash 变化（新实体、读档后的重建），
            // 但这些不是“换部件/升级”。只记签名，不把当前 max 接受成新的 baseMax，
            // 否则 ArcaneVortex 把 max 压到 1 时会被永久合法化。
            LAST_BUILD.put(uuid, hash);
            return false;
        }
        LAST_BUILD.put(uuid, hash);
        float max = entity.getMaxHealth();
        float current = HealthTrace.readHealthQuiet(entity);
        float expected = Float.isFinite(amount) && amount >= 0.0F ? Math.min(amount, max) : current;
        float oldBase = HealthTrace.ledgerBaseMax(uuid);
        HealthTrace.acceptBuildMax(uuid, max, expected);
        HealthTraceData.Tracked tracked = HealthTrace.trackedFor(entity);
        if (tracked != null && Float.isFinite(oldBase) && Math.abs(max - oldBase) > EPS) {
            HealthTrace.logBuildMax(entity, tracked, oldBase, max);
        }
        return true;
    }

    /**
     * 记录当前 build 签名，但不改 baseMax。
     * 读档/首次挂账时 materials/upgrades 可能被重建，hash 看起来变了；
     * 如果不先记下来，下一次 acceptBuildMax 会把 NBT 里被压低的 max（例如 1）当成
     * “换部件”接受，baseMax 从 100 变成 1，Guard 就再也修不回来了。
     */
    static void noteBuild(LivingEntity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        try {
            LAST_BUILD.put(entity.getUUID(), buildHash(golem));
        } catch (Throwable ignored) {
            // build 读不到就保持原状。
        }
    }

    /**
     * 在 P1 check 之前同步自家模板的 maxHealth 变化。
     * tech_expansion 安装/升级/移除时 base max 会变；这里接受新 max、更新 baseMax，
     * 让 P2 的 max guard 不再把它当外部 HEALTH_SEAL。
     */
    static void acceptTemplateMax(HealthTraceData.Tracked tracked, LivingEntity entity, int tick) {
        if (tracked == null || !(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        int level;
        try {
            level = GolemUpgradeLevels.levelOf(golem, "golems_arsenal:tech_expansion");
        } catch (Throwable ignored) {
            return;
        }
        UUID uuid = entity.getUUID();
        Integer previous = LAST_TECH_LEVEL.put(uuid, level);
        int oldLevel = previous == null ? 0 : previous;
        boolean ownTemplate = level > 0 || oldLevel > 0;
        if (!ownTemplate || oldLevel == level) {
            return;
        }
        float oldBase = HealthTrace.ledgerBaseMax(uuid);
        float max = entity.getMaxHealth();
        if (!Float.isFinite(oldBase) || Math.abs(max - oldBase) <= EPS) {
            return;
        }
        // tech_expansion 每级 -10% maxHealth。只有 max 与“旧等级 → 新等级”的预期一致，
        // 才认定为模板变化；模板激活期间的 HEALTH_SEAL 仍然走 P2 的 max guard。
        float expected = templateMax(oldBase, oldLevel, level);
        if (!Float.isFinite(expected) || Math.abs(max - expected) > EPS) {
            return;
        }
        HealthTrace.acceptMax(uuid, max);
        HealthTrace.logTemplateMax(entity, tracked, oldBase, max, level);
    }

    private static int clampTechLevel(int level) {
        return Math.max(0, Math.min(5, level));
    }

    private static float templateMax(float oldBase, int oldLevel, int newLevel) {
        int a = clampTechLevel(oldLevel);
        int b = clampTechLevel(newLevel);
        // tech_expansion 的 AttrEntry 是每级 -0.1，先按加法 -10% × 等级计算。
        return oldBase * (1.0F - 0.1F * b) / (1.0F - 0.1F * a);
    }

    // ------------------------------------------------------------------ mixin 入口

    public static boolean allowSetHealth(LivingEntity entity, float amount) {
        return allowWrite(entity, "setHealth", amount, null);
    }

    public static boolean allowGuardedData(GuardedEntity entity, float amount) {
        return allowWrite(entity, "guarded.data", amount, null);
    }

    /** 直接写 SynchedEntityData(DATA_HEALTH_ID) 的拦截；这是 VanSh/EntityUtil 的实际路径。 */
    public static boolean allowEntityDataHealth(LivingEntity entity, float amount) {
        return allowWrite(entity, "entityData.health", amount, null);
    }

    /** 返回 false 时 mixin 会 cancel setRemoved。 */
    public static boolean allowRemoved(LivingEntity entity, Entity.RemovalReason reason) {
        if (!isGuardedTest(entity)) {
            return true;
        }
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return true;
        }
        // 梦终 true damage 打死的傀儡：允许移除，不要在死亡路径上回滚。
        if (isFantasyGrace(entity.getUUID(), HealthTrace.currentTick())) {
            return true;
        }
        // dead/负血：死亡清理和尸体移除必须放行，避免留下不会动的 dead 实体。
        // 这里用真值而不是 getHealth：jzyy 可以让 getter 说还活着，骗过 setRemoved 的放行。
        HealthTruth.Snapshot truth = HealthTruth.read(entity, false);
        float actual = effective(truth, entity);
        if (entity.isDeadOrDying() || actual <= 0.0F) {
            return true;
        }
        // 傀儡收回：toItem 已经先造好同 UUID 持有物，再 discard 实体。
        // 这里必须放行，否则会出现“实体没删 + 手里多一个同 ID 傀儡”。
        if (reason == Entity.RemovalReason.DISCARDED && HealthTrace.isGolemRecall()) {
            return true;
        }
        float expected = HealthTrace.ledgerExpected(entity.getUUID());
        if (!Float.isFinite(expected) || expected <= EPS) {
            // 正常死亡：伤害事件已经把 expected 压到 0；放行。
            return true;
        }
        HealthTraceData.Tracked tracked = HealthTrace.trackedFor(entity);
        HealthTraceData.GuardMode mode = tracked.guardMode;
        boolean block = mode == HealthTraceData.GuardMode.ENFORCE;
        HealthTrace.guardLog(entity, tracked, "setRemoved",
                block ? "blocked" : "would_block",
                0.0F, expected, actual,
                "reason=" + reason.name());
        return !block;
    }

    private static boolean allowWrite(LivingEntity entity, String op, float amount, String notes) {
        if (!isGuardedTest(entity)) {
            return true;
        }
        // fantasy true damage：梦终剑应该能伤害傀儡。允许写入，并把 expected 接受为这次写入值；
        // 随后几 tick 的 reconcile 也不回滚（见 isFantasyGrace）。
        if (HealthTrace.isFantasyHurtCaller()) {
            markFantasy(entity.getUUID(), amount);
            return true;
        }
        // 读档写入：允许，但绝不能把 NBT 里被压低的 max 当成“换部件 accepted_build”。
        // 这里只更新 build 签名，baseMax/expected 保持原值；max 交给 tick 末的 max guard 修。
        boolean fromSave = HealthTrace.isReadAdditionalCaller();
        if (fromSave) {
            if (HealthTrace.isLegitSaveLoad()) {
                noteBuild(entity);
                return true;
            }
            // 反射调用 readAdditionalSaveData：不享受读档豁免，继续走下面的 Guard。
        } else if (acceptBuildChangeNow(entity, amount)) {
            // 换部件/改升级与 health 写入可能同 tick：当场同步 expected，别让后续写入被旧值拦。
            return true;
        }
        // 真值（SynchedEntityData/guardedData）优先；getHealth 可能被 coremod 重定向。
        HealthTruth.Snapshot truth = HealthTruth.read(entity, false);
        float actualNow = effective(truth, entity);
        // 伤害/治疗链内的最终写：允许，并把 expected 对齐到这次写入值。
        // Apotheosis / 古遗物 / L2 fin / 本家升级在事件后段加的伤害不会被当成外部写吞掉。
        // 事件链结束后（tick handler、反射、命令）的 setHealth 仍然走下面的 Guard。
        if (Float.isFinite(actualNow) && Float.isFinite(amount)
                && amount <= actualNow + EPS && HealthTrace.isDamageChainWrite()) {
            HealthTrace.acceptDamageWrite(entity.getUUID(), amount);
            return true;
        }
        // 已经 dead/负血：不要再拦死亡清理，否则会留下 dead=true、removed=false 的“尸体”，
        // AI 不动、不能收回。直接致死写（amount<=0）放行并记录，不假装能防住。
        if (entity.isDeadOrDying() || actualNow <= 0.0F || amount <= 0.0F) {
            HealthTrace.guardLog(entity, HealthTrace.trackedFor(entity), op, "allowed_lethal",
                    amount, HealthTrace.ledgerExpected(entity.getUUID()), actualNow, notes);
            return true;
        }
        // GuardedEntity.validateGuardedData 是傀儡自己的数据校验/同步，不是外部写；
        // 不拦它，否则 ENFORCE 会和 mod 的 validate 对打。
        if (HealthTrace.isInternalGuardedSync()) {
            return true;
        }
        float expected = HealthTrace.ledgerExpected(entity.getUUID());
        if (!Float.isFinite(expected)) {
            return true;
        }
        if (Float.isFinite(amount) && Math.abs(amount - expected) <= EPS) {
            // 事件驱动的 damage/heal 写、guardedData 的 super.setHealth 同步、死亡写 0：放行。
            return true;
        }
        // feed 回血时 expected 可能仍停在 max（外部 drop 还没还完），但这是最近的 LivingHealEvent
        // 对应的合法增加；按 lastHealAmount 放行，避免 SHADOW 刷屏/ENFORCE 拦住回血。
        float allowance = HealthTrace.ledgerHealAllowance(entity.getUUID());
        if (Float.isFinite(amount) && Float.isFinite(allowance)
                && amount >= actualNow - EPS
                && amount <= actualNow + allowance + EPS) {
            // guarded.data 先写 176，内层 super.setHealth 再写 176 时 actual 已经是 176；
            // 两种情况都属于最近 LivingHealEvent 的合法回血。
            return true;
        }
        HealthTraceData.Tracked tracked = HealthTrace.trackedFor(entity);
        HealthTraceData.GuardMode mode = tracked.guardMode;
        boolean block = mode == HealthTraceData.GuardMode.ENFORCE;
        HealthTrace.guardLog(entity, tracked, op,
                block ? "blocked" : "would_block",
                amount, expected, actualNow, notes);
        return !block;
    }

    private static boolean isGuardedTest(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide) {
            return false;
        }
        HealthTraceData.Tracked tracked = HealthTrace.trackedFor(entity);
        if (tracked == null || tracked.role != HealthTraceData.Role.TEST) {
            return false;
        }
        return tracked.guardMode != HealthTraceData.GuardMode.OFF;
    }

    // ------------------------------------------------------------------ 每 tick 兜底

    /**
     * 反射直写真血、HEALTH_SEAL/trim_max_health、以及任何绕过 mixin 的写，都逃不过
     * “actual vs expected”。ENFORCE 先尝试恢复 maxHealth，再把当前血回滚到 expected；
     * SHADOW 只记录。
     */
    static void reconcile(HealthTraceData.Tracked tracked, LivingEntity entity, int tick) {
        if (tracked == null || tracked.role != HealthTraceData.Role.TEST
                || tracked.guardMode == HealthTraceData.GuardMode.OFF) {
            return;
        }
        // 真值优先：getHealth 可能被 jzyy coremod 重定向；容器类/索引也在这里一并检查。
        HealthTruth.Snapshot truth = HealthTruth.read(entity, true);
        checkTruth(tracked, entity, truth);
        checkIndex(tracked, entity, truth);
        // 梦终 true damage 窗口：接受实际真值（包括负数），不做 max/current 回滚。
        if (isFantasyGrace(entity.getUUID(), tick)) {
            HealthTrace.acceptFantasyWrite(entity.getUUID(), effective(truth, entity));
            return;
        }
        if (truth != null && Float.isNaN(truth.synced())) {
            // DATA_HEALTH_ID 索引被删：只记录，不往坏掉的容器里写，避免 set 抛异常。
            return;
        }

        float expected = HealthTrace.ledgerExpected(entity.getUUID());
        if (!Float.isFinite(expected)) {
            return;
        }
        HealthTraceData.GuardMode mode = tracked.guardMode;
        float actual = effective(truth, entity);

        // 1) maxHealth：HEALTH_SEAL / trim_max_health / ArcaneVortex 会只改 max。
        // 这一段放在 dead 判断之前：max=0/1 本身就是外部压制，死了也要把 base 修回来，
        // 否则收回时 NBT 会把 1/1 写进持有物，再放出来就永久 1 血。
        float baseMax = HealthTrace.ledgerBaseMax(entity.getUUID());
        float max = entity.getMaxHealth();
        if (Float.isFinite(baseMax) && Math.abs(max - baseMax) > EPS) {
            Float lastMax = LAST_MAX.get(entity.getUUID());
            boolean changed = lastMax == null || Math.abs(lastMax - max) > EPS;
            if (mode == HealthTraceData.GuardMode.SHADOW) {
                if (changed) {
                    HealthTrace.guardLog(entity, tracked, "max_health", "would_block",
                            max, baseMax, actual, "currentMax=" + max);
                }
                LAST_MAX.put(entity.getUUID(), max);
            } else {
                AttributeInstance attr = entity.getAttribute(Attributes.MAX_HEALTH);
                if (attr != null) {
                    attr.setBaseValue(baseMax);
                }
                float afterMax = entity.getMaxHealth();
                if (changed) {
                    HealthTrace.guardLog(entity, tracked, "max_health", "blocked",
                            max, baseMax, actual, "restoredMax=" + afterMax);
                }
                LAST_MAX.put(entity.getUUID(), max);
            }
        } else {
            LAST_MAX.remove(entity.getUUID());
        }

        // dead/负血实体不再回滚当前血；让它完成死亡/移除，否则会卡成不会动的尸体。
        if (entity.isDeadOrDying() || actual <= 0.0F) {
            return;
        }
        if (!Float.isFinite(actual)) {
            // DATA_HEALTH_ID 索引被删/容器不可读：checkTruth 已记录，但不能安全写回。
            return;
        }

        // 2) 当前血：反射/漏网写/HEALTH_SEAL 裁剪。
        // 只在 gap 第一次出现或变大时记一条；feed 回血把 gap 缩小的 tick 不刷屏。
        actual = effective(HealthTruth.read(entity, false), entity);
        float gap = Math.abs(actual - expected);
        if (gap <= EPS) {
            LAST_GAP.remove(entity.getUUID());
            return;
        }
        Float lastGap = LAST_GAP.get(entity.getUUID());
        boolean first = lastGap == null;
        boolean worse = first || gap > lastGap + EPS;
        LAST_GAP.put(entity.getUUID(), gap);
        if (mode == HealthTraceData.GuardMode.SHADOW) {
            if (first || worse) {
                HealthTrace.guardLog(entity, tracked, "reconcile", "would_restore",
                        actual, expected, actual, "gap=" + (actual - expected));
            }
        } else {
            HealthTrace.restoreToExpected(entity, expected);
            float after = effective(HealthTruth.read(entity, false), entity);
            if (first || worse) {
                HealthTrace.guardLog(entity, tracked, "reconcile", "restored",
                        actual, expected, after, "gap=" + (actual - expected));
            }
            if (Math.abs(after - expected) <= EPS) {
                LAST_GAP.remove(entity.getUUID());
            }
        }
    }

    // ------------------------------------------------------------------ 真值 / 容器 / 索引诊断

    /** 真值优先；只有真值读取整体不可用（truth == null）时才回退 getHealth。 */
    private static float effective(HealthTruth.Snapshot truth, LivingEntity entity) {
        if (truth != null) {
            // 注意：容器/索引被破坏时 effective 会是 NaN。这时不能回退到 getHealth——
            // 那正是攻击者想让我们信的“报告值”。NaN 交给调用方按“不可读”处理。
            return truth.effective();
        }
        return HealthTrace.readHealthQuiet(entity);
    }

    /**
     * 抓两类“getter 层”的绕过：
     * <ul>
     *   <li>SynchedEntityData 被换成子类（super-steve 的 Hurt/SafeSynchedEntityData）；</li>
     *   <li>getHealth 报告值与 DATA_HEALTH_ID/guardedData 真值不一致（jzyy coremod）。</li>
     * </ul>
     * 只记录，不 cancel getter；真正的回滚仍由 reconcile 的 expected 对比完成。
     */
    private static void checkTruth(HealthTraceData.Tracked tracked, LivingEntity entity,
                                   HealthTruth.Snapshot truth) {
        if (truth == null) {
            return;
        }
        UUID uuid = entity.getUUID();
        String container = truth.container();
        String previousContainer = LAST_CONTAINER.put(uuid, container);
        if (!truth.containerOk() && (previousContainer == null || !previousContainer.equals(container))) {
            HealthTrace.guardLog(entity, tracked, "entityData.container", "detected",
                    truth.effective(), HealthTrace.ledgerExpected(uuid), truth.reported(),
                    "class=" + container);
        }
        boolean missingSynced = Float.isNaN(truth.synced());
        Boolean wasMissing = LAST_MISSING.put(uuid, missingSynced);
        if (missingSynced && !Boolean.TRUE.equals(wasMissing)) {
            HealthTrace.guardLog(entity, tracked, "entityData.index", "detected",
                    truth.guarded(), HealthTrace.ledgerExpected(uuid), truth.reported(),
                    "DATA_HEALTH_ID missing; class=" + container);
        }
        if (truth.guardedMismatch()) {
            String key = Math.round(truth.guarded() * 1000.0F) + "/"
                    + Math.round(truth.synced() * 1000.0F);
            String previous = LAST_GUARDED.put(uuid, key);
            if (previous == null || !previous.equals(key)) {
                HealthTrace.guardLog(entity, tracked, "guarded.data.mismatch", "detected",
                        truth.guarded(), truth.synced(), truth.synced(),
                        "guardedData=" + truth.guarded() + " synced=" + truth.synced()
                                + " reported=" + truth.reported() + " maxHealth=" + entity.getMaxHealth());
            }
        } else {
            LAST_GUARDED.remove(uuid);
        }
        if (truth.divergence()) {
            String key = Math.round(truth.reported() * 1000.0F) + "/"
                    + Math.round(truth.effective() * 1000.0F);
            String previous = LAST_TRUTH.put(uuid, key);
            if (previous == null || !previous.equals(key)) {
                HealthTrace.guardLog(entity, tracked, "getHealth", "detected",
                        truth.reported(), truth.effective(), truth.effective(), truth.notes());
            }
        } else {
            LAST_TRUTH.remove(uuid);
        }
    }

    /**
     * 抓“实体还活着，但索引已经不一致”：omnimobs forceRemove / super-steve killEntity 不经过
     * setRemoved 时，isRemoved/removalReason 与 level entityLookup 会对不上。
     *
     * <p>setRemoved 与区块切换的瞬时状态会持续 1–2 tick，所以同一状态连续 5 tick 才判定一次；
     * 状态变化后重新计时，避免正常收回/死亡刷屏。</p>
     */
    private static void checkIndex(HealthTraceData.Tracked tracked, LivingEntity entity,
                                   HealthTruth.Snapshot truth) {
        if (truth == null) {
            return;
        }
        UUID uuid = entity.getUUID();
        IndexKey key = new IndexKey(truth.removed(), truth.removalReason(), truth.addedToWorld(),
                truth.canUpdate(), truth.inLevelById(), truth.inLevelByUuid());
        IndexWatch watch = INDEX.get(uuid);
        if (watch == null || !watch.key().equals(key)) {
            watch = new IndexWatch(key, 1, false);
        } else {
            watch = new IndexWatch(key, watch.ticks() + 1, watch.logged());
        }
        INDEX.put(uuid, watch);

        float expected = HealthTrace.ledgerExpected(uuid);
        boolean live = Float.isFinite(expected) && expected > EPS;
        // 只抓“活着但状态自相矛盾”的组合：
        //  - removed 却没有 removalReason：forceRemove 绕过 setRemoved；
        //  - 没 removed 却有 reason：状态被外部改了一半；
        //  - 没 removed 但 byUuid 能查到、byId 查不到：索引被针对性删除（super-steve）；
        //  - 没 removed、isAddedToWorld 仍为 true，但 level 里查不到：实体从索引消失；
        //  - 没 removed、还在 level 里，但 canUpdate=false：tick 列表与索引脱节。
        // 不用 removed && byId —— setRemoved 后 lookup 可能还要 1–2 tick 才清，容易误报。
        boolean inconsistent = (truth.removed() && truth.removalReason() == null)
                || (!truth.removed() && truth.removalReason() != null)
                || (!truth.removed() && truth.inLevelByUuid() && !truth.inLevelById())
                || (!truth.removed() && truth.addedToWorld() && !truth.inLevelById())
                || (!truth.removed() && truth.inLevelById() && !truth.canUpdate());
        if (!live || !inconsistent) {
            INDEX.remove(uuid);
            return;
        }
        if (watch.ticks() >= 5 && !watch.logged()) {
            HealthTrace.guardLog(entity, tracked, "index", "detected",
                    truth.effective(), expected, truth.reported(),
                    "removed=" + truth.removed()
                            + " reason=" + truth.removalReason()
                            + " added=" + truth.addedToWorld()
                            + " canUpdate=" + truth.canUpdate()
                            + " byId=" + truth.inLevelById()
                            + " byUuid=" + truth.inLevelByUuid());
            INDEX.put(uuid, new IndexWatch(key, watch.ticks(), true));
        }
    }

    private static void markFantasy(UUID uuid, float amount) {
        FANTASY_GRACE.put(uuid, HealthTrace.currentTick() + 5);
        HealthTrace.acceptFantasyWrite(uuid, amount);
    }

    private static boolean isFantasyGrace(UUID uuid, int tick) {
        Integer until = FANTASY_GRACE.get(uuid);
        if (until == null) {
            return false;
        }
        if (tick <= until) {
            return true;
        }
        FANTASY_GRACE.remove(uuid);
        return false;
    }

    private record IndexKey(boolean removed, String reason, boolean added, boolean canUpdate,
                            boolean inLevelById, boolean inLevelByUuid) {
    }

    private record IndexWatch(IndexKey key, int ticks, boolean logged) {
    }
}
