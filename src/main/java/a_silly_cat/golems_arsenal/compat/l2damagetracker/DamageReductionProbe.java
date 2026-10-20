package a_silly_cat.golems_arsenal.compat.l2damagetracker;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 伤害诊断探针 v3 —— <b>只读</b>。不修改任何伤害。
 *
 * <h2>为什么是只读的</h2>
 * 曾经设计过"按 {@code fin / base} 反推、再 {@code setAmount} 写回"的穿透方案，已放弃：
 * <ol>
 *   <li><b>写不进去。</b>{@code AttackCache.pushDamagePre} 的最后一句是 {@code event.setAmount(damage)}，
 *       而 {@code onDamageFinalized} 是在它内部的 {@code run()} 里被调用的 —— 在这里写 {@code setAmount}
 *       会被下一句立刻覆盖；想改累加器又会撞上 {@code DamageAccumulator.addHurtModifier} 的
 *       {@code if (frozen) throw new IllegalStateException("modify damage only on event.")}。</li>
 *   <li><b>它是软后门。</b>目标身上不留痕迹、无法被反制、不与任何减伤机制对话；
 *       代码并不知道损失来自护甲、抗性、附魔还是 boss 的阶段减伤。</li>
 *   <li><b>高减伤下失衡。</b>语义是 {@code 最终 = 普通伤害 + 被减掉的伤害 × p}；
 *       实测也已经抓到反例：{@code slime} 上出现过 {@code kept = 1.143}（净放大），
 *       按比值覆盖会把这 4 次增伤直接抹掉。</li>
 * </ol>
 *
 * <h2>v3 相比 v2 补了什么</h2>
 * <ol>
 *   <li><b>目标面板</b>：每次命中读目标的 {@code generic.armor} / {@code generic.armor_toughness} /
 *       {@code l2damagetracker:damage_reduction} / {@code damage_absorption} 与抗性提升等级，
 *       汇总时给出区间。</li>
 *   <li><b>模型分解</b>：按命中的伤害源标签，把 {@code kept} 拆成
 *       {@code 护甲 × 抗性 × damage_reduction = 模型}，剩下 {@code 残差 = kept / 模型}
 *       就是"属性以外的减伤"（保护附魔 / {@code meror_defense} / {@code damage_cap} / 其他 dealt 修正）。
 *       这样就不用再从乘积反推配置了。</li>
 *   <li><b>盲区计数</b>：v2 漏掉了"过了 {@code LivingAttackEvent} 但没走到伤害结算"的命中
 *       （无敌帧、{@code isInvulnerableTo}、{@code isInvulnerableToExtra}、扣到 0）。
 *       现在用 {@code 过了攻击事件 − 落地} 直接量出来。</li>
 *   <li><b>取消明细带伤害类型</b>：{@code isCanceled()} 本身不说是谁取消的，
 *       但记下 {@code DamageSource.getMsgId()} 与 fire/true 标记，通常一眼就能对上是哪个免疫。</li>
 * </ol>
 *
 * <h2>读数含义</h2>
 * <ul>
 *   <li>{@code raw} = {@code getPreDamageOriginal()}：{@code LivingAttackEvent} 时的原始值</li>
 *   <li>{@code base} = {@code getPreDamage()}：攻击方乘区之后、护甲之前</li>
 *   <li>{@code fin} = {@code getDamageDealt()}：护甲 × 抗性 × 附魔 × 全部 dealt 修正之后</li>
 *   <li>{@code kept = fin / base}：目标侧总减伤后的保留比例，越小被削得越狠，1.0 表示没有减伤</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class DamageReductionProbe implements AttackListener {

    /**
     * 号越大越晚。已知占用：l2dt {@code GeneralAttackListener} 1000、本家 Z 光剑 3400、
     * modulargolems {@code GolemAttackListener} 3500 / {@code WeaponAttackListener} 7900、
     * 傀儡地牢遗迹锻锤 3513。探针只读，放最后只是为了排在最后看一眼终值。
     */
    private static final int PRIORITY = 9000;

    /** l2damagetracker 的两个防守方属性 id（jarjar 依赖，按 id 取，做成可空）。 */
    private static final ResourceLocation REDUCTION_ID =
            new ResourceLocation("l2damagetracker", "damage_reduction");
    private static final ResourceLocation ABSORB_ID =
            new ResourceLocation("l2damagetracker", "damage_absorption");

    public static void register() {
        AttackEventHandler.register(PRIORITY, new DamageReductionProbe());
    }

    private DamageReductionProbe() {
    }

    private static Attribute reductionAttr;
    private static boolean reductionResolved;

    private static Attribute reductionAttribute() {
        if (!reductionResolved) {
            reductionResolved = true;
            reductionAttr = ForgeRegistries.ATTRIBUTES.getValue(REDUCTION_ID);
            if (reductionAttr == null) {
                Golems_arsenal.LOGGER.warn("[GA-Probe] 属性 {} 不存在，damage_reduction 一项将按 1.0 计",
                        REDUCTION_ID);
            }
        }
        return reductionAttr;
    }

    // ------------------------------------------------------------------ 统计

    private static final class Tally {
        long hits;
        /** fin > base：减伤层上出现净放大。 */
        long amplified;
        /** fin <= 0：被完全减伤。 */
        long zeroed;
        double sumKept;
        double minKept = Double.MAX_VALUE;
        /** 伤害返还在本模组的 END_NONLINEAR 档执行，这里记"返还前"的同口径比例，用来对照。 */
        double sumKeptRaw;
        double minKeptRaw = Double.MAX_VALUE;
        /** 返还真的抬高过伤害的命中数。 */
        long restituted;
        /** 实际使用的返还比例之和；除命中数就是"这一批的平均返还比例"，用来验证键刃的处决曲线。 */
        double sumPercent;
        /** 两次采样之间的实际掉血之和（可为负：那说明目标在回血）。 */
        double sumActualDrop;
        long dropSamples;
        double sumBase;
        double sumFin;
        /** 模型预测的保留比例（护甲 × 抗性 × damage_reduction）与残差。 */
        double sumModel;
        double sumResidual;
        double minArmor = Double.MAX_VALUE;
        double maxArmor = -Double.MAX_VALUE;
        double minTough = Double.MAX_VALUE;
        double maxTough = -Double.MAX_VALUE;
        double minReduction = Double.MAX_VALUE;
        double maxReduction = -Double.MAX_VALUE;
        double minAbsorb = Double.MAX_VALUE;
        double maxAbsorb = -Double.MAX_VALUE;
        int maxResistanceAmp = -1;
        /** 抗性提升等级的最小值；和最大值一起看才知道"是不是每一次都被抗性 V 归零"。 */
        int minResistanceAmp = Integer.MAX_VALUE;
        /** 模型被抗性归零（amplifier >= 4）的命中数，这些不参与残差平均。 */
        long resImmune;
        /** 参与模型/残差平均的命中数。 */
        long modelHits;
        /** 第一次见到的傀儡修正名单（把"残差"落到具体名字上）。 */
        String modifiers;
        /** 同类型下见过几种不同配置；>1 说明这一行不能代表全部目标。 */
        int modifierVariants = 1;
    }

    private static final Map<String, Tally> TALLIES = new ConcurrentHashMap<>();
    /** 被取消的命中：键是"目标类型 <- 伤害类型 [标记]"，因为这些全都没进管线。 */
    private static final Map<String, AtomicLong> CANCELLED = new ConcurrentHashMap<>();

    /** 上一次采样时各实体的血量，用来量"实际掉血"。 */
    private static final Map<UUID, Float> LAST_HEALTH = new ConcurrentHashMap<>();

    /** 过了 {@code LivingAttackEvent} 且攻击者是傀儡的命中数（汇总后清零）。 */
    private static final AtomicLong ATTEMPTED = new AtomicLong();
    /** 真正走到伤害结算（dealt 累加器跑完）的命中数。 */
    private static final AtomicLong RECORDED = new AtomicLong();
    /** 到了 {@code onDamageFinalized} 却拿不到阶段对象/数值的次数；正常应为 0。 */
    private static final AtomicLong GUARDED = new AtomicLong();
    /** 攻击者不是傀儡而被过滤的命中数。 */
    private static final AtomicLong OTHER_ATTACKER = new AtomicLong();
    /** 整个会话是否已经打过"开始记录"那一行。 */
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private static int ticks;
    private static int heartbeats;

    // ------------------------------------------------------- 只读采样（终值）

    @Override
    public void onDamageFinalized(AttackCache cache, ItemStack weapon) {
        if (!Config.DAMAGE_PROBE_ENABLED.get()) {
            return;
        }
        LivingEntity target = cache.getAttackTarget();
        LivingEntity attacker = cache.getAttacker();
        if (target == null) {
            return;
        }
        if (Config.DAMAGE_PROBE_ONLY_GOLEM_ATTACKS.get()
                && !(attacker instanceof AbstractGolemEntity<?, ?>)) {
            return; // 已在 onAttack 里计入 OTHER_ATTACKER，这里不重复计
        }
        if (cache.getLivingHurtEvent() == null || cache.getLivingDamageEvent() == null) {
            GUARDED.incrementAndGet();
            return;
        }

        float raw;
        float base;
        float fin;
        try {
            raw = cache.getPreDamageOriginal();
            base = cache.getPreDamage();
            fin = cache.getDamageDealt();
        } catch (IllegalStateException e) {
            GUARDED.incrementAndGet();
            return;
        }
        if (!(base > 0.0F)) {
            GUARDED.incrementAndGet();
            return;
        }

        DamageSource source = cache.getLivingDamageEvent().getSource();
        String key = keyOf(target);

        double armor = attributeOf(target, Attributes.ARMOR);
        double toughness = attributeOf(target, Attributes.ARMOR_TOUGHNESS);
        double reduction = attributeOf(target, reductionAttribute());
        double absorb = attributeOf(target, ForgeRegistries.ATTRIBUTES.getValue(ABSORB_ID));
        int resistanceAmp = resistanceAmplifier(target);

        // 模型：把"能按属性/标签算出来的那一部分减伤"复现出来，剩下的都算残差。
        double model = 1.0;
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            model *= armorFactor(base, armor, toughness);
        }
        if (resistanceAmp >= 0
                && !source.is(DamageTypeTags.BYPASSES_EFFECTS)
                && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
            model *= Math.max((25 - (resistanceAmp + 1) * 5) / 25.0, 0.0);
        }
        if (reductionAttribute() != null && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            model *= Math.max(reduction, 0.0);
        }
        boolean modelZero = model <= 1.0E-5;
        model = Mth.clamp(model, 1.0E-6, 1.0E6);

        double kept = fin / base;
        // "实际掉血"：拿本次采样时的血量与上一次采样时的血量相减。这一项在伤害管线之外，
        // 是唯一能抓到 GuardedEntity 血量地板（dynamic_reduction）的读数 ——
        // 如果 fin 有 108 而实际掉血是 0，就证明伤害算出来了但没落进血量。
        float healthNow = target.getHealth();
        Float healthBefore = LAST_HEALTH.put(target.getUUID(), healthNow);
        double actualDrop = healthBefore == null ? Double.NaN : healthBefore - healthNow;
        // 伤害返还在 END_NONLINEAR 档执行，所以拿到的是"返还前"的终值。
        // 没走到返还时返回 null，此时 keptRaw 就等于 kept、比例按 0 计。
        DamageRestitutionListener.RestitutionInfo info = DamageRestitutionListener.takePreRestitution(target);
        double percent = info == null ? 0.0 : info.percent();
        boolean restituted = info != null && fin > info.pre();
        double keptRaw = info == null ? kept : info.pre() / base;
        // 分解必须用"返还前"的值，否则返还会把残差抬高。
        // 模型为 0（抗性提升 V 自己就把伤害归零）时不能相除，这类命中单独计数。
        double residual = modelZero ? 0.0 : keptRaw / model;

        Tally tally = TALLIES.computeIfAbsent(key, k -> new Tally());
        synchronized (tally) {
            tally.hits++;
            tally.sumKept += kept;
            tally.minKept = Math.min(tally.minKept, kept);
            tally.sumKeptRaw += keptRaw;
            tally.minKeptRaw = Math.min(tally.minKeptRaw, keptRaw);
            if (restituted) {
                tally.restituted++;
            }
            tally.sumPercent += percent;
            tally.sumBase += base;
            tally.sumFin += fin;
            if (!Double.isNaN(actualDrop)) {
                tally.sumActualDrop += actualDrop;
                tally.dropSamples++;
            }
            if (modelZero) {
                // 抗性提升 V 把模型压成 0：这一层自己就归零了，残差对它没有意义。
                tally.resImmune++;
            } else {
                tally.modelHits++;
                tally.sumModel += model;
                tally.sumResidual += residual;
            }
            if (kept > 1.0) {
                tally.amplified++;
            }
            if (fin <= 0.0F) {
                tally.zeroed++;
            }
            tally.minArmor = Math.min(tally.minArmor, armor);
            tally.maxArmor = Math.max(tally.maxArmor, armor);
            tally.minTough = Math.min(tally.minTough, toughness);
            tally.maxTough = Math.max(tally.maxTough, toughness);
            tally.minReduction = Math.min(tally.minReduction, reduction);
            tally.maxReduction = Math.max(tally.maxReduction, reduction);
            tally.minAbsorb = Math.min(tally.minAbsorb, absorb);
            tally.maxAbsorb = Math.max(tally.maxAbsorb, absorb);
            tally.maxResistanceAmp = Math.max(tally.maxResistanceAmp, resistanceAmp);
            tally.minResistanceAmp = Math.min(tally.minResistanceAmp, resistanceAmp);
            String mods = modifierSummary(target);
            if (tally.modifiers == null) {
                tally.modifiers = mods;
            } else if (tally.modifierVariants == 1 && !tally.modifiers.equals(mods)) {
                tally.modifierVariants = 2; // 只区分"一种"和"多种"，避免为每个傀儡存一份
            }
        }

        long recorded = RECORDED.incrementAndGet();
        boolean first = ANNOUNCED.compareAndSet(false, true);
        if (first || Config.DAMAGE_PROBE_LOG_EVERY_HIT.get()) {
            if (first) {
                heartbeats = 0;
            }
            Golems_arsenal.LOGGER.info(
                    "[GA-Probe] {} -> {}  raw={} base={} fin={} kept={} | 护甲={} 韧性={} 减伤属性={} 吸收={} 抗性amp={} "
                            + "|| 模型={} 残差={} 标签[armorBy={} resBy={} effBy={}]",
                    attacker == null ? "?" : keyOf(attacker), key,
                    fmt(raw), fmt(base), fmt(fin), fmt(kept),
                    fmt(armor), fmt(toughness), fmt(reduction), fmt(absorb),
                    resistanceAmp < 0 ? "无" : String.valueOf(resistanceAmp),
                    fmt(model), fmt(residual),
                    source.is(DamageTypeTags.BYPASSES_ARMOR),
                    source.is(DamageTypeTags.BYPASSES_RESISTANCE),
                    source.is(DamageTypeTags.BYPASSES_EFFECTS));
        }

        if (recorded % Config.DAMAGE_PROBE_SUMMARY_EVERY.get() == 0) {
            flush();
        }
    }

    // ---------------------------------------------- 攻击事件：统计 + 取消检测

    /**
     * {@code LivingAttackEvent} 在 {@code LivingEntity.hurt} 里、{@code actuallyHurt} 之前发出，
     * 是"过了这关才算数"的分界线。{@code receiveCanceled = true} 让被取消的那些也能收到。
     *
     * <p>这里记下的 {@code ATTEMPTED} 与 {@code onDamageFinalized} 的 {@code RECORDED} 之差，
     * 就是 v2 漏掉的那一块：<b>过了攻击事件、却没走到伤害结算</b>的命中
     * （无敌帧、{@code isInvulnerableTo}、{@code isInvulnerableToExtra}、扣到 0 都会被算进来）。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onAttack(LivingAttackEvent event) {
        if (!Config.DAMAGE_PROBE_ENABLED.get()) {
            return;
        }
        DamageSource source = event.getSource();
        if (Config.DAMAGE_PROBE_ONLY_GOLEM_ATTACKS.get()
                && !(source.getEntity() instanceof AbstractGolemEntity<?, ?>)) {
            OTHER_ATTACKER.incrementAndGet();
            return;
        }
        if (!event.isCanceled()) {
            ATTEMPTED.incrementAndGet();
            return;
        }
        StringBuilder key = new StringBuilder(keyOf(event.getEntity()))
                .append("  <- ").append(source.getMsgId());
        if (source.is(DamageTypeTags.IS_FIRE)) {
            key.append(" [fire]");
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            key.append(" [true]");
        }
        CANCELLED.computeIfAbsent(key.toString(), k -> new AtomicLong()).incrementAndGet();
    }

    // ------------------------------------------------------ 时间兜底 + 心跳

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Config.DAMAGE_PROBE_ENABLED.get()) {
            return;
        }
        if (++ticks < Config.DAMAGE_PROBE_INTERVAL.get()) {
            return;
        }
        ticks = 0;

        boolean sawData = RECORDED.get() > 0 || ATTEMPTED.get() > 0
                || OTHER_ATTACKER.get() > 0 || !CANCELLED.isEmpty();
        if (sawData) {
            flush();
            return;
        }
        if (heartbeats >= Config.DAMAGE_PROBE_MAX_HEARTBEATS.get()) {
            return;
        }
        heartbeats++;
        Golems_arsenal.LOGGER.info(
                "[GA-Probe] 探针在线，最近 {} 秒内没有记录到任何命中。"
                        + "若你正在用傀儡打怪却看不到数据，请把 damage_probe.only_golem_attacks 设为 false 再试（当前为 {}）；"
                        + "这条心跳最多重复 {} 次",
                Config.DAMAGE_PROBE_INTERVAL.get() / 20,
                Config.DAMAGE_PROBE_ONLY_GOLEM_ATTACKS.get(),
                Config.DAMAGE_PROBE_MAX_HEARTBEATS.get());
    }

    // ------------------------------------------------------------------ 输出

    private static void flush() {
        long attempted = ATTEMPTED.getAndSet(0);
        long recorded = RECORDED.getAndSet(0);
        long guarded = GUARDED.getAndSet(0);
        long other = OTHER_ATTACKER.getAndSet(0);
        List<Map.Entry<String, Tally>> rows = new ArrayList<>(TALLIES.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, Tally> e) -> e.getValue().hits).reversed());

        if (rows.isEmpty() && CANCELLED.isEmpty() && other == 0 && attempted == 0 && recorded == 0) {
            return;
        }

        Golems_arsenal.LOGGER.info("[GA-Probe] ===== 减伤诊断汇总（只读；kept = fin / base，越小被削得越狠，1.0 = 无减伤）=====");
        Golems_arsenal.LOGGER.info(
                "[GA-Probe] 命中账：过了攻击事件 {} 次 → 落地结算 {} 次；**未落地 {} 次**"
                        + "（无敌帧 / isInvulnerableTo / isInvulnerableToExtra / 扣到 0，全在这一段）",
                attempted, recorded, Math.max(0, attempted - recorded));
        if (guarded > 0) {
            Golems_arsenal.LOGGER.warn("[GA-Probe] 另有 {} 次到了终结阶段却拿不到阶段对象，属异常，请反馈", guarded);
        }
        if (other > 0) {
            Golems_arsenal.LOGGER.info(
                    "[GA-Probe] 另有 {} 次命中因攻击者不是傀儡被 only_golem_attacks 过滤（要看就把该项设为 false）", other);
        }

        for (Map.Entry<String, Tally> row : rows) {
            Tally t = row.getValue();
            synchronized (t) {
                Golems_arsenal.LOGGER.info(
                        "[GA-Probe]   {}  n={}  kept 均值={} 最低={}{}{}  |  base 均值={} -> fin 均值={}",
                        row.getKey(), t.hits,
                        fmt(t.sumKept / t.hits), fmt(t.minKept),
                        t.zeroed > 0 ? "（归零 " + t.zeroed + " 次）" : "",
                        t.amplified > 0 ? "  [净放大 " + t.amplified + " 次]" : "",
                        fmt(t.sumBase / t.hits), fmt(t.sumFin / t.hits));
                Golems_arsenal.LOGGER.info(
                        "[GA-Probe]     伤害返还：返还前 kept 均值={}（最低={}） → 返还后 kept 均值={}；实际返还比例 p 均值={}{}",
                        fmt(t.sumKeptRaw / t.hits), fmt(t.minKeptRaw), fmt(t.sumKept / t.hits),
                        fmt(t.sumPercent / t.hits),
                        t.restituted > 0 ? "  [实际抬高 " + t.restituted + " 次]" : "  [未生效]");
                if (t.dropSamples > 0) {
                    double avgDrop = t.sumActualDrop / t.dropSamples;
                    double avgFin = t.sumFin / t.hits;
                    // 判据要卡两道：fin 本身要有意义（否则"掉血 0"只是因为伤害本来就≈0），
                    // 而且掉血要明显低于 fin，才算"结算完了但没落进血量"。
                    String verdict;
                    if (avgFin <= 20.0) {
                        verdict = "这一批已结算伤害本身就很小（≤20），掉血读数不具判别力 —— 先看上面的 kept";
                    } else if (avgDrop < avgFin * 0.2) {
                        verdict = "**结算了 " + fmt(avgFin) + " 但血量几乎没掉：GuardedEntity 血量地板（dynamic_reduction）在吃伤害**";
                    } else if (avgDrop < avgFin * 0.6) {
                        verdict = "掉血明显少于已结算伤害，差额被吸收/地板吃掉了";
                    } else {
                        verdict = "掉血与已结算伤害基本一致";
                    }
                    Golems_arsenal.LOGGER.info(
                            "[GA-Probe]     实际掉血：每次采样平均 {}（{} 次采样） vs 已结算 fin 均值 {}  →  {}",
                            fmt(avgDrop), t.dropSamples, fmt(avgFin), verdict);
                }
                Golems_arsenal.LOGGER.info(
                        "[GA-Probe]     面板区间：护甲 {}..{}  韧性 {}..{}  damage_reduction {}..{}  damage_absorption {}..{}  抗性amp {}..{}",
                        fmt(t.minArmor), fmt(t.maxArmor), fmt(t.minTough), fmt(t.maxTough),
                        fmt(t.minReduction), fmt(t.maxReduction), fmt(t.minAbsorb), fmt(t.maxAbsorb),
                        t.minResistanceAmp == Integer.MAX_VALUE ? "无" : String.valueOf(t.minResistanceAmp),
                        t.maxResistanceAmp < 0 ? "无" : String.valueOf(t.maxResistanceAmp));
                double model = t.sumModel / Math.max(1, t.modelHits);
                double residual = t.sumResidual / Math.max(1, t.modelHits);
                Golems_arsenal.LOGGER.info(
                        "[GA-Probe]     分解（均值；已排除被抗性归零的 {} 次）：模型（护甲×抗性×damage_reduction）={}  实测 kept（返还前）={}  残差={}"
                                + "  ← 残差 = 属性以外的减伤：保护附魔 / meror_defense / damage_cap / dynamic_reduction 血量地板 / 其他 dealt 修正",
                        t.resImmune, fmt(model), fmt(t.sumKeptRaw / t.hits), fmt(residual));
                Golems_arsenal.LOGGER.info(
                        "[GA-Probe]     傀儡修正{}：{}",
                        t.modifierVariants > 1 ? "（同类型下有多种配置，下面只是其中之一）" : "",
                        t.modifiers == null ? "（目标不是傀儡或没有修正）" : t.modifiers);
            }
        }

        if (!CANCELLED.isEmpty()) {
            Golems_arsenal.LOGGER.info("[GA-Probe]   被取消明细（这些完全没进伤害管线，kept 对它们无意义）：");
            CANCELLED.forEach((key, count) -> Golems_arsenal.LOGGER.info(
                    "[GA-Probe]     {}  n={}", key, count.get()));
        }

        TALLIES.clear();
        CANCELLED.clear();
        heartbeats = 0;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 等价于原版 {@code CombatRules.getDamageAfterAbsorb}：返回护甲之后保留的比例。
     * 只在伤害源没有 {@code bypasses_armor} 时才被调用。
     */
    private static double armorFactor(float damage, double armor, double toughness) {
        if (damage <= 0.0F || Double.isNaN(armor) || Double.isNaN(toughness)) {
            return 1.0;
        }
        double f = 2.0 + toughness / 4.0;
        double clamped = Mth.clamp(armor - damage / f, armor * 0.2, 20.0);
        return Math.max(0.0, 1.0 - clamped / 25.0);
    }

    private static double attributeOf(LivingEntity entity, Attribute attribute) {
        if (attribute == null) {
            return Double.NaN;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? Double.NaN : instance.getValue();
    }

    private static int resistanceAmplifier(LivingEntity entity) {
        MobEffectInstance instance = entity.getEffect(MobEffects.DAMAGE_RESISTANCE);
        return instance == null ? -1 : instance.getAmplifier();
    }

    /**
     * 目标身上的 golem 修正名单与等级，按 id 排序。{@code GolemModifier#getDescriptionId()} 形如
     * {@code modifier.<命名空间>.<路径>}，这里把前缀去掉只留 {@code 命名空间:路径}，方便和配置/代码对照。
     * <p>
     * 这是把"残差"落到具体名字上的关键：光看残差只能知道"还有 ×0.59 没解释"，
     * 有了这份名单就能直接确认 {@code meror_defense} 到底是几级。
     */
    private static String modifierSummary(LivingEntity target) {
        if (!(target instanceof AbstractGolemEntity<?, ?> golem)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        golem.getModifiers().forEach((mod, level) -> {
            String id = mod.getDescriptionId();
            if (id.startsWith("modifier.")) {
                id = id.substring("modifier.".length());
            }
            parts.add(id + "=" + level);
        });
        parts.sort(Comparator.naturalOrder());
        return parts.isEmpty() ? "无" : String.join("  ", parts);
    }

    private static String keyOf(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id != null ? id.toString() : type.getDescriptionId();
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
