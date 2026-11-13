package a_silly_cat.golems_arsenal.testbench;

import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P1：影子账本（PRIVATE TESTBENCH）。
 *
 * <p>只在 {@code LivingDamageEvent} / {@code LivingHealEvent} / {@code LivingDeathEvent} /
 * {@code Entity.setRemoved} 里更新 expected；比较时读 {@code entity.getHealth()}，**从不写血、
 * 从不拦截 setHealth**。</p>
 *
 * <p>expected 是“按合法事件应该剩多少血”。如果实际值和 expected 不一致，说明有事件之外的写入
 * （外部 setHealth、反射写字段、getHealth 被锁、maxHealth 被改等）。P1 只记录
 * {@code UNEXPLAINED_HEALTH}，不修正。P2 才会在 Test 实体上按模式拦截。</p>
 *
 * <p>账本只对“已追踪实体”生效；未追踪实体不产生开销。control/test 都可以挂，control 用来
 * 验证正常 L2 不会误报。</p>
 */
final class HealthLedger {

    /** 血量是 float；1e-3 足够区分浮点误差和真正的写血。 */
    static final float EPS = 1.0E-3F;

    static final class Entry {
        float expected = Float.NaN;
        float lastActual = Float.NaN;
        float lastLoggedActual = Float.NaN;
        /** 上一次比较时的 |actual-expected|；只有 gap 变大时才记一条，避免 feed 回血每 tick 刷屏。 */
        float lastGap = Float.NaN;
        long lastLogTick = Long.MIN_VALUE;
        long lastEventTick = Long.MIN_VALUE;
        String lastEvent = "-";
        long unexplainedTicks;
        long unexplainedEvents;
        /** P2：挂账本时的 maxHealth，用来发现 HEALTH_SEAL / trim_max_health。 */
        float baseMax = Float.NaN;
        /** P2：最近一次 LivingHealEvent 的量和 tick；用于放行 feed 的回血写。 */
        float lastHealAmount = Float.NaN;
        long lastHealTick = Long.MIN_VALUE;
    }

    static final class Finding {
        final LivingEntity entity;
        final Entry entry;
        final String reason;
        final float expected;
        final float actual;
        final HealthTrace.LastWrite lastWrite;

        Finding(LivingEntity entity, Entry entry, String reason,
                float expected, float actual, HealthTrace.LastWrite lastWrite) {
            this.entity = entity;
            this.entry = entry;
            this.reason = reason;
            this.expected = expected;
            this.actual = actual;
            this.lastWrite = lastWrite;
        }

        float delta() {
            return actual - expected;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    void attach(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        Entry entry = new Entry();
        entry.expected = HealthTrace.readTruthQuiet(entity);
        entry.lastActual = entry.expected;
        entry.baseMax = entity.getMaxHealth();
        entries.put(entity.getUUID(), entry);
    }

    void detach(UUID uuid) {
        if (uuid != null) {
            entries.remove(uuid);
        }
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    Entry entry(UUID uuid) {
        return entries.get(uuid);
    }

    boolean has(UUID uuid) {
        return entries.containsKey(uuid);
    }

    float expected(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? Float.NaN : entry.expected;
    }

    float baseMax(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? Float.NaN : entry.baseMax;
    }

    float healAllowance(UUID uuid, int tick) {
        Entry entry = entries.get(uuid);
        if (entry == null || tick - entry.lastHealTick > 1L) {
            return Float.NaN;
        }
        return entry.lastHealAmount;
    }

    /**
     * 接受一次“自家模板”的 maxHealth 变化（tech_expansion 等）：
     * 更新 baseMax，并把 expected 压到新 max，避免 P2 把模板的 -10%/级当成 HEALTH_SEAL 顶掉。
     */
    void acceptMax(UUID uuid, float max) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return;
        }
        entry.baseMax = max;
        if (Float.isFinite(entry.expected) && entry.expected > max) {
            entry.expected = max;
        }
        entry.lastGap = Float.NaN;
    }

    /**
     * build（材料/升级）变化：max 和当前 health 都可能被 mod 重算。
     * expected 必须同步到这次实际值，否则 max 回升时 P2 会把血回滚到旧值。
     */
    void acceptBuild(UUID uuid, float max, float expected) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return;
        }
        entry.baseMax = max;
        float e = Float.isFinite(expected) ? Math.max(0.0F, Math.min(expected, max)) : max;
        entry.expected = e;
        entry.lastGap = Float.NaN;
    }

    /**
     * 伤害/治疗链内的最终写：以实际写入值为新的 expected。
     *
     * <p>Apotheosis / 古遗物 / 本家升级可能在 LivingHurtEvent / LivingDamageEvent / L2 的
     * 后段继续修改 amount；如果我们的 LOWEST 监听器读到的不是最终值，写入口就会把增伤当成
     * 外部写拦掉。这里在确认调用栈属于 hurt/heal 链后，直接把 expected 对齐到写入口的 amount，
     * 保证事件增伤不被 reconcile 回滚。</p>
     */
    void acceptDamageWrite(UUID uuid, float amount) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return;
        }
        float value = Float.isFinite(amount) ? Math.max(0.0F, amount) : 0.0F;
        entry.expected = value;
        entry.lastActual = value;
        entry.lastGap = Float.NaN;
    }

    /**
     * fantasy true damage：接受写入值本身，允许负数。
     * 这是“梦终剑应该能伤害傀儡”的豁免，不做夹取/回滚。
     */
    void acceptFantasyWrite(UUID uuid, float amount) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return;
        }
        float value = Float.isFinite(amount) ? amount : 0.0F;
        entry.expected = value;
        entry.lastActual = value;
        entry.lastGap = Float.NaN;
    }

    // ------------------------------------------------------------------ 合法事件更新 expected

    /**
     * max 上限：如果 HEALTH_SEAL / trim_max_health 把当前 max 压到 100，但挂账本时 baseMax=300，
     * 仍允许 expected 保持到 300；否则 heal 事件会把 expected 一起 clamp 到 100，P2 恢复 max
     * 后就找不到原来的 300。
     */
    private static float bound(LivingEntity entity, Entry entry) {
        float max = Math.max(0.0F, entity.getMaxHealth());
        if (Float.isFinite(entry.baseMax)) {
            max = Math.max(max, entry.baseMax);
        }
        return max;
    }

    void onDamage(LivingEntity entity, float amount, int tick) {
        Entry entry = entries.get(entity.getUUID());
        if (entry == null || !Float.isFinite(entry.expected)) {
            return;
        }
        if (!Float.isFinite(amount) || amount <= 0.0F) {
            return;
        }
        // 已经 0 血时 heal/伤害事件可能仍被触发，但不会真正改血；不更新 expected，避免误报。
        if (HealthTrace.readTruthQuiet(entity) <= 0.0F) {
            return;
        }
        // LivingDamageEvent 的 amount 是“吸收之前”的伤害；吸收吃掉的部分不会进 setHealth。
        // 只把真正会掉血的部分记进 expected，否则全吸收的一击会让 reconcile 把血打穿吸收。
        float absorption = entity.getAbsorptionAmount();
        if (!Float.isFinite(absorption) || absorption < 0.0F) {
            absorption = 0.0F;
        }
        float healthLoss = Math.max(0.0F, amount - absorption);
        float max = bound(entity, entry);
        entry.expected = Math.max(0.0F, Math.min(max, entry.expected - healthLoss));
        entry.lastEvent = "damage";
        entry.lastEventTick = tick;
    }

    void onHeal(LivingEntity entity, float amount, int tick) {
        Entry entry = entries.get(entity.getUUID());
        if (entry == null || !Float.isFinite(entry.expected)) {
            return;
        }
        if (!Float.isFinite(amount) || amount <= 0.0F) {
            return;
        }
        // 0 血时 heal 事件不会真正加血；这里跳过，否则会误报。
        float actual = HealthTrace.readTruthQuiet(entity);
        if (actual <= 0.0F) {
            return;
        }
        // 已经在当前 max（HEALTH_SEAL 把 max 压到 100 时也是一样）时，heal 是 no-op；
        // 不要再把 expected 往 baseMax 推，否则 seal 期间每 tick 都会制造新的 gap。
        if (actual >= Math.max(0.0F, entity.getMaxHealth()) - EPS) {
            return;
        }
        float max = bound(entity, entry);
        entry.expected = Math.max(0.0F, Math.min(max, entry.expected + amount));
        entry.lastEvent = "heal";
        entry.lastEventTick = tick;
        entry.lastHealAmount = amount;
        entry.lastHealTick = tick;
    }

    /** 自然死亡（expected 已被伤害事件压到 0）不算异常；expected>0 的死是外部杀。 */
    Finding onDeath(LivingEntity entity, int tick) {
        Entry entry = entries.get(entity.getUUID());
        if (entry == null) {
            return null;
        }
        Finding finding = null;
        if (Float.isFinite(entry.expected) && entry.expected > EPS) {
            finding = new Finding(entity, entry, "unexpected_death",
                    entry.expected, HealthTrace.readHealthQuiet(entity), null);
        }
        entry.expected = 0.0F;
        entry.lastEvent = "death";
        entry.lastEventTick = tick;
        return finding;
    }

    /** 实体被移除时 expected 仍 >0：外部 erase / remove。正常死亡会是 0。 */
    Finding onRemoved(LivingEntity entity, int tick) {
        Entry entry = entries.remove(entity.getUUID());
        if (entry == null) {
            return null;
        }
        if (Float.isFinite(entry.expected) && entry.expected > EPS) {
            return new Finding(entity, entry, "removed_alive",
                    entry.expected, HealthTrace.readHealthQuiet(entity), null);
        }
        return null;
    }

    // ------------------------------------------------------------------ 每 tick 比较

    /**
     * @return 只在 gap 变大（新的外部写/偏离）时返回一条；feed 把血拉回 expected 的每 tick
     *         不重复刷 JSONL。entry.unexplainedTicks 仍然逐 tick 累计。
     */
    Finding check(LivingEntity entity, int tick, HealthTrace.LastWrite lastWrite) {
        Entry entry = entries.get(entity.getUUID());
        if (entry == null || !Float.isFinite(entry.expected)) {
            return null;
        }
        float actual = HealthTrace.readHealthQuiet(entity);
        entry.lastActual = actual;
        float gap = Math.abs(actual - entry.expected);
        if (gap <= EPS) {
            entry.lastLoggedActual = Float.NaN;
            entry.lastGap = Float.NaN;
            return null;
        }
        entry.unexplainedTicks++;
        boolean first = !Float.isFinite(entry.lastGap);
        boolean worse = first || gap > entry.lastGap + EPS;
        entry.lastGap = gap;
        if (!worse) {
            entry.lastLoggedActual = actual;
            return null;
        }
        entry.lastLoggedActual = actual;
        entry.lastLogTick = tick;
        entry.unexplainedEvents++;
        return new Finding(entity, entry, "health_mismatch",
                entry.expected, actual, lastWrite);
    }
}
