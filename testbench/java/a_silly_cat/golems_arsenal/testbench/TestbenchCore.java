package a_silly_cat.golems_arsenal.testbench;

import a_silly_cat.golems_arsenal.base.item.ExampleWeapon;
import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.l2damagetracker.contents.attack.DamageModifier;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 测试台核心（PRIVATE TESTBENCH）：把"绕过减伤"的各个杠杆挂在 l2 的 {@link AttackListener} 上，
 * 逐项可控、可测。
 *
 * <h2>一次挥击的流程</h2>
 * <ol>
 *   <li>原始命中（{@code minecraft:mob_attack} / {@code player_attack}）到达 {@link #onAttack}：
 *       若开启档位切换，则<b>取消原命中</b>并把"用哪个档位、打几段"排进队列。</li>
 *   <li>本 tick 末尾（{@code ServerTickEvent} END，见 {@code TestbenchEvents}）按队列用档位伤害
 *       重发 {@code target.hurt(...)} —— 这时 l2 的缓存已经被清掉，是干净的一击。</li>
 *   <li>档位命中的每一段走 {@link #postAttack}/{@link #postHurt}/{@link #onDamage}：
 *       反取消、三种 floor、END 层返还。</li>
 *   <li>重发返回后（{@code afterHit}）再做"管线外"的干预：写血 / 砍最大生命 / 摘附魔，
 *       并记录<b>实际掉血</b>，用来区分"伤害算出来了"和"血真的掉了"。</li>
 * </ol>
 *
 * <p>{@code tier=vanilla} 是特殊档：<b>不换伤害类型</b>，杠杆直接作用在真实命中上 —— 用来测
 * "只加杠杆、不改 tag"的收益。</p>
 */
public final class TestbenchCore implements AttackListener {

    /** 监听器优先级：避开我们自己的 3400/3600 与地牢的 3513。 */
    private static final int PRIORITY = 4000;
    /** END_NONLINEAR 档内排在最后，读到的就是终值。 */
    private static final int FINAL_PRIORITY = 9400;
    private static final UUID TRIM_ID =
            UUID.nameUUIDFromBytes("golems_arsenal:testbench_trim".getBytes(StandardCharsets.UTF_8));

    private static final String STRIP_ROOT = "ga_test_stripped";
    private static final String STRIP_ORIGINAL = "originalEnchantments";
    private static final String STRIP_TIME = "startTime";

    // ------------------------------------------------------------------ 队列与记录

    /** 待重发的一击。 */
    public record Pending(ServerLevel level, LivingEntity attacker, LivingEntity target,
                          float amount, TestbenchTiers tier, long due) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private record Stripped(ItemStack stack, long expire) {
    }

    private static final List<Stripped> STRIPPED = new ArrayList<>();

    /** 测量：键 = 档位/来源 + 当前杠杆组合 + 目标类型。 */
    public static final class Tally {
        /** 走了 l2 管线（LivingDamageEvent 阶段）的命中数。 */
        public int hits;
        public int canceled;
        public double sumBase;
        public double sumFin;
        public double minKept = Double.MAX_VALUE;
        /** 重发出去的命中数（管线上不上报都会计）。 */
        public int planned;
        /** 重发时传进去的伤害量总和（= 攻击阶段的值）。 */
        public double sumAmount;
        /** 用血量差量到的命中数（ground truth）。 */
        public int dropSamples;
        /** 血量真实下降总和（含写血等管线外干预）。 */
        public double sumDrop;
        public int writeApplied;
        public int writeRejected;
        public String levers = "-";

        public double avgKept() {
            return hits == 0 ? 0.0D : sumFin / Math.max(1.0E-6D, sumBase);
        }

        /** 真实通过率：真正掉的血 / 打出去的伤害量。l2 没上报时这一列才是唯一可信的。 */
        public double passRate() {
            return sumAmount <= 0.0D ? 0.0D : sumDrop / sumAmount;
        }
    }

    private static final Map<String, Tally> TALLIES = new LinkedHashMap<>();
    private static int recorded;
    private static int totalCanceled;

    public static List<Pending> pending() {
        return PENDING;
    }

    public static Map<String, Tally> tallies() {
        return TALLIES;
    }

    public static void clearTallies() {
        TALLIES.clear();
        recorded = 0;
        totalCanceled = 0;
    }

    public static void register() {
        dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler.register(PRIORITY,
                new TestbenchCore());
    }

    private TestbenchCore() {
    }

    // ------------------------------------------------------------------ l2 回调

    @Override
    public void onAttack(AttackCache cache, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled) {
            return;
        }
        LivingAttackEvent event = cache.getLivingAttackEvent();
        if (event == null) {
            return;
        }
        DamageSource source = event.getSource();
        LivingEntity attacker = cache.getAttacker();
        LivingEntity target = cache.getAttackTarget();
        if (attacker == null || target == null || target.level().isClientSide) {
            return;
        }
        // 档位命中 / 光柱命中：杠杆在后面的回调里挂，这里不重发
        if (TestbenchTiers.of(source) != null) {
            return;
        }
        if (!isTestWeapon(attacker, f)) {
            return;
        }
        if (f.tier == TestbenchTiers.VANILLA) {
            return; // 不换类型：交给 postAttack/postHurt/onDamage
        }
        // 原始武器命中：取消，排队用档位伤害重发
        event.setCanceled(true);
        planHits(f, (ServerLevel) target.level(), attacker, target, event.getAmount());
    }

    @Override
    public void postAttack(AttackCache cache, LivingAttackEvent event, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled || !tracked(cache, f)) {
            return;
        }
        Tally tally = tally(cache, f);
        if (event.isCanceled()) {
            // 这一击被别人的免疫（magic_immune / fire_affinity / projectile_reject …）取消了
            tally.canceled++;
            totalCanceled++;
            if (f.lever("uncancel")) {
                event.setCanceled(false); // 反取消：虚空之触的做法
            }
        }
    }

    @Override
    public void postHurt(AttackCache cache, LivingHurtEvent event, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled || !tracked(cache, f)) {
            return;
        }
        if (f.lever("uncancel") && event.isCanceled()) {
            event.setCanceled(false);
        }
        if (f.lever("floor_hurt")) {
            event.setAmount(Math.max(event.getAmount(), cache.getPreDamage()));
        }
    }

    /**
     * {@code addHurtModifier} 只能在 hurt 累加器的 collect 阶段（也就是这里）登记：
     * 累加器一旦冻结，{@code DamageAccumulator.addHurtModifier} 会直接抛
     * {@code IllegalStateException("modify damage only on event.")}。
     */
    @Override
    public void onHurt(AttackCache cache, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled || !tracked(cache, f)) {
            return;
        }
        if (f.lever("floor_attack")) {
            double attack = cache.getAttacker() == null ? 0.0D
                    : cache.getAttacker().getAttributeValue(Attributes.ATTACK_DAMAGE);
            cache.addHurtModifier(DamageModifier.nonlinearPre(0, e -> (float) Math.max(e, attack)));
        }
        // mgdp「取消升级」与 JVG 莫厄武器用的是同一招：直接写目标的无敌帧计数器。
        // 它不依赖任何 tag，而且会连带影响"之后所有攻击者"对该目标的命中（组队时变成增益）。
        if (f.lever("clear_iframe")) {
            cache.getAttackTarget().invulnerableTime = 0;
        }
    }

    @Override
    public void onDamage(AttackCache cache, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled || !tracked(cache, f)) {
            return;
        }
        final float pre;
        try {
            pre = cache.getPreDamage();
        } catch (IllegalStateException e) {
            return;
        }
        if (!(pre > 0.0F)) {
            return;
        }
        if (f.lever("floor_dealt")) {
            cache.addDealtModifier(DamageModifier.nonlinearPre(5000,
                    e -> Math.max(e, pre)));
        }
        if (f.restitution > 0.0D) {
            final float pen = (float) Math.min(1.0D, Math.max(0.0D, f.restitution));
            cache.addDealtModifier(DamageModifier.nonlinearFinal(FINAL_PRIORITY, x -> {
                if (x >= pre) {
                    return x;
                }
                float ratio = x > 0.0F ? x / pre : 0.0F;
                return pre * (1.0F - (1.0F - ratio) * (1.0F - pen));
            }));
        }
    }

    @Override
    public void onDamageFinalized(AttackCache cache, ItemStack weapon) {
        DebugFlags f = flags(cache);
        if (f == null || !f.enabled || !tracked(cache, f)) {
            return;
        }
        float base;
        float fin;
        try {
            base = cache.getPreDamage();
            fin = cache.getDamageDealt();
        } catch (IllegalStateException e) {
            return;
        }
        if (!(base > 0.0F)) {
            return;
        }
        Tally tally = tally(cache, f);
        tally.hits++;
        tally.sumBase += base;
        tally.sumFin += fin;
        tally.minKept = Math.min(tally.minKept, fin / base);
        tally.levers = f.activeLeverTag();
        recorded++;
        if ((SWEEP == null || !SWEEP.active) && f.reportEvery > 0 && recorded % f.reportEvery == 0) {
            TestbenchEvents.printSummary(null);
        }
    }

    // ------------------------------------------------------------------ 重发与管线外干预

    /** 本 tick 末尾调用：把到点的待发命中打出去。 */
    public static void flush(long now) {
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.due() > now) {
                continue;
            }
            it.remove();
            LivingEntity target = p.target();
            if (!target.isAlive() || target.level() != p.level()) {
                continue;
            }
            DebugFlags f = DebugFlags.get(p.level().getServer());
            if (f == null || !f.enabled) {
                continue;
            }
            float before = target.getHealth();
            target.hurt(p.tier().source(p.level(), p.attacker()), p.amount());
            afterHit(p, before, f, now);
        }
    }

    /** 还原到期的附魔（摘附魔的还原部分）。 */
    public static void restoreExpired(long now) {
        STRIPPED.removeIf(s -> {
            if (now >= s.expire()) {
                restoreEnchants(s.stack());
                return true;
            }
            return false;
        });
    }

    /**
     * 重发返回之后：先做管线外的状态干预，再用<b>血量差</b>量真正的战果。
     *
     * <p>血量差是 ground truth：l2 的管线上报只在"它认识这条伤害源"时才有，
     * 所以 {@code 通过率 = 掉血 / 打出去的伤害量} 这一列永远可信。</p>
     */
    private static void afterHit(Pending p, float before, DebugFlags f, long now) {
        LivingEntity target = p.target();
        Tally tally = tallyFor(p.tier(), f, target);
        tally.planned++;
        tally.sumAmount += Math.max(0.0F, p.amount());
        // 管线外的干预（写血/砍最大生命/摘附魔）—— 只对非玩家生效
        if (!(target instanceof Player)) {
            if (f.lever("write_health") && f.writePct > 0.0D) {
                float max = Math.max(1.0F, target.getMaxHealth());
                if (target.getHealth() / max <= f.writeBelowPct) {
                    float want = (float) (max * f.writePct);
                    if (writeHealth(target, want)) {
                        tally.writeApplied++;
                    } else {
                        tally.writeRejected++;
                    }
                }
            }
            if (f.lever("trim_max_health") && f.trimPct > 0.0D) {
                trimMaxHealth(target, f.trimPct);
            }
            if (f.lever("strip_enchants") && f.stripPieces > 0) {
                stripEnchants(target, f.stripPieces, f.stripSeconds, now);
            }
        }
        float after = target.getHealth();
        if (!Float.isNaN(before) && !Float.isNaN(after)) {
            tally.sumDrop += Math.max(0.0F, before - after);
            tally.dropSamples++;
        }
        LAST_TARGET = target;
        if (SWEEP != null && SWEEP.active) {
            onSweepHit(target, f);
        }
    }

    // ------------------------------------------------------------------ 目标体检

    /** 最近一次被测试台重发的目标（`/ga debug inspect` 的兜底）。 */
    private static LivingEntity LAST_TARGET;

    /** 体检目标：优先"玩家看向的生物"，其次最近一次重发的目标。 */
    public static LivingEntity resolveInspectTarget(CommandSourceStack src) {
        if (src.getEntity() instanceof Player player) {
            net.minecraft.world.phys.HitResult hit = player.pick(12.0D, 0.0F, false);
            if (hit instanceof net.minecraft.world.phys.EntityHitResult ehr
                    && ehr.getEntity() instanceof LivingEntity living) {
                return living;
            }
        }
        return LAST_TARGET;
    }

    /**
     * 目标体检：把"为什么打不动"需要的字段一次打全（登记表、护甲/韧性、l2 减伤属性、血量地板）。
     * 带 {@code modulargolems:immunity} 的傀儡会把所有非 {@code bypasses_invulnerability} 的伤害整击取消，
     * 这一行就是最直接的判据。
     */
    public static List<String> inspectLines(LivingEntity e) {
        List<String> out = new ArrayList<>();
        double armor = e.getAttributeValue(Attributes.ARMOR);
        double tough = e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        out.add("目标 " + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())
                + "  名=" + e.getName().getString()
                + "  HP " + fmt(e.getHealth()) + "/" + fmt(e.getMaxHealth()));
        out.add("  护甲 " + fmt(armor) + "  韧性 " + fmt(tough)
                + "  damage_reduction " + fmt(attr(e, "l2damagetracker", "damage_reduction"))
                + "  damage_absorption " + fmt(attr(e, "l2damagetracker", "damage_absorption")));
        if (e instanceof dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity<?, ?> golem) {
            StringBuilder sb = new StringBuilder("  傀儡登记表: ");
            golem.getModifiers().forEach((mod, level) -> {
                String id = mod.getDescriptionId();
                if (id.startsWith("modifier.")) {
                    id = id.substring("modifier.".length());
                }
                sb.append(id).append('=').append(level).append("  ");
            });
            out.add(sb.toString());
        }
        if (e instanceof GuardedEntity guarded) {
            out.add("  血量地板: guarded=" + fmt(guarded.getGuardedDataImpl())
                    + "  baseline=" + fmt(guarded.getDynamicBaseline()));
        }
        out.add("  判读：有 modulargolems:immunity ⇒ 只有 bypasses_invulnerability 的伤害能进（其余整击取消）；"
                + "有 magic_immune/fire_immune/projectile_reject ⇒ 对应语义被取消；"
                + "damage_reduction 很大 ⇒ 只有 bypasses_invulnerability 能跳过它。");
        return out;
    }

    private static double attr(LivingEntity e, String namespace, String path) {
        net.minecraft.world.entity.ai.attributes.Attribute attribute =
                net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(
                        new ResourceLocation(namespace, path));
        return attribute == null ? Double.NaN : e.getAttributeValue(attribute);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * 直接写血（破外层钳制与硬速率门的唯一手段）。
     *
     * <p>傀儡（{@code GuardedEntity}）有自己的血量存储，且它的 {@code setHealth} 在
     * {@code tickCount > 5} 之后<b>拒绝"变小"的写入</b>；好消息是它的
     * {@code setGuardedDataImpl(float, boolean, boolean)} 是 <b>public</b>，不需要反射、不需要
     * mixin，直接调就行 —— 这正是 Arcane Vortex 那一大套反射/Agent 在傀儡身上不必存在的理由。</p>
     *
     * @return 写入是否被接受（读回校验，用于统计"被拒绝"次数）
     */
    public static boolean writeHealth(LivingEntity target, float amount) {
        float before = target.getHealth();
        float next = Math.max(0.0F, before - amount);
        if (target instanceof GuardedEntity guarded) {
            guarded.setGuardedDataImpl(next, true, false);
        } else {
            target.setHealth(next);
        }
        return Math.abs(target.getHealth() - next) < 0.05F;
    }

    /** 砍最大生命：用固定 UUID 的 ADDITION 修饰符累加削减量，返回新的最大生命。 */
    public static float trimMaxHealth(LivingEntity target, double pct) {
        AttributeInstance inst = target.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) {
            return target.getMaxHealth();
        }
        double base = inst.getBaseValue();
        AttributeModifier old = inst.getModifier(TRIM_ID);
        double total = (old == null ? 0.0D : old.getAmount()) - base * pct;
        if (old != null) {
            inst.removeModifier(TRIM_ID);
        }
        inst.addTransientModifier(new AttributeModifier(TRIM_ID, "ga_test_trim", total,
                AttributeModifier.Operation.ADDITION));
        return target.getMaxHealth();
    }

    /** 从 NBT 里摘掉附魔（EnchantmentDisabler 的做法），到期由 {@link #flush} 还原。 */
    public static int stripEnchants(LivingEntity target, int pieces, int seconds, long now) {
        List<ItemStack> pool = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = target.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isEnchanted()) {
                pool.add(stack);
            }
        }
        if (pool.isEmpty()) {
            return 0;
        }
        Collections.shuffle(pool);
        int count = Math.min(pieces, pool.size());
        long expire = now + (long) seconds * 20L;
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (stripOne(pool.get(i), expire)) {
                STRIPPED.add(new Stripped(pool.get(i), expire));
                done++;
            }
        }
        return done;
    }

    private static boolean stripOne(ItemStack stack, long expire) {
        CompoundTag root = stack.getOrCreateTag();
        if (!root.contains("Enchantments", Tag.TAG_LIST)) {
            return false;
        }
        ListTag list = root.getList("Enchantments", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return false;
        }
        CompoundTag marker = stack.getOrCreateTagElement(STRIP_ROOT);
        marker.put(STRIP_ORIGINAL, list.copy());
        marker.putLong(STRIP_TIME, expire);
        root.put("Enchantments", new ListTag());
        stack.setTag(root);
        return true;
    }

    private static void restoreEnchants(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(STRIP_ROOT, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag marker = root.getCompound(STRIP_ROOT);
        ListTag original = marker.getList(STRIP_ORIGINAL, Tag.TAG_COMPOUND);
        ListTag current = root.getList("Enchantments", Tag.TAG_COMPOUND);
        original.addAll(current);
        root.put("Enchantments", original);
        root.remove(STRIP_ROOT);
        stack.setTag(root);
    }

    // ------------------------------------------------------------------ 判定与测量

    private static DebugFlags flags(AttackCache cache) {
        LivingEntity target = cache.getAttackTarget();
        if (target == null || target.level().isClientSide) {
            return null;
        }
        net.minecraft.server.MinecraftServer server = target.getServer();
        return server == null ? null : DebugFlags.get(server);
    }

    /** 这一击是否由测试台接管（档位命中 / vanilla 档的真实命中 / 开了开关的光柱命中）。 */
    private static boolean tracked(AttackCache cache, DebugFlags f) {
        DamageSource source = sourceOf(cache);
        if (source == null) {
            return false;
        }
        if (TestbenchTiers.of(source) != null) {
            return true;
        }
        LivingEntity attacker = cache.getAttacker();
        if (!isTestWeapon(attacker, f)) {
            return false;
        }
        if (f.tier == TestbenchTiers.VANILLA) {
            return true;
        }
        return f.lever("light_pillar_onhit") && isLightPillar(source);
    }

    private static DamageSource sourceOf(AttackCache cache) {
        LivingAttackEvent event = cache.getLivingAttackEvent();
        return event == null ? null : event.getSource();
    }

    /** 主手是测试武器（example_weapon）；allow_player 控制玩家是否算。 */
    private static boolean isTestWeapon(LivingEntity attacker, DebugFlags f) {
        if (attacker == null) {
            return false;
        }
        if (!(attacker.getMainHandItem().getItem() instanceof ExampleWeapon)) {
            return false;
        }
        return !(attacker instanceof Player) || f.allowPlayer;
    }

    /** 光柱与幻梦零共用的伤害类型：{@code golems_arsenal:genmu_zero}。 */
    private static boolean isLightPillar(DamageSource source) {
        return source != null && source.is(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                new ResourceLocation("golems_arsenal", "genmu_zero")));
    }

    private static void planHits(DebugFlags f, ServerLevel level, LivingEntity attacker,
                                 LivingEntity target, float amount) {
        int total = Math.max(1, 1 + Math.min(8, f.extraHits));
        long now = level.getGameTime();
        for (int i = 0; i < total; i++) {
            TestbenchTiers tier = f.tier;
            if (i > 0 && f.lever("distinct_ids")) {
                tier = TestbenchTiers.REAL.get((f.tier.ordinal() + i) % TestbenchTiers.REAL.size());
            }
            if (tier.key() == null) {
                tier = TestbenchTiers.PLAIN;
            }
            // 默认排到"下一个 tick"：本 tick 里 l2 的缓存可能还挂着（原命中被取消留下的），
            // 同一 tick 重发会撞进它的 recursive 分支而进不了监听器（伤害照常，但测不到）。
            // same_tick 保留给"专测无敌帧"的场景，那时本来就要同 tick 多段。
            long due = f.lever("same_tick") ? now : now + 1 + i;
            PENDING.add(new Pending(level, attacker, target, amount, tier, due));
        }
    }

    private static Tally tally(AttackCache cache, DebugFlags f) {
        DamageSource source = sourceOf(cache);
        TestbenchTiers tier = source == null ? null : TestbenchTiers.of(source);
        if (tier == null && !isLightPillar(source)) {
            tier = TestbenchTiers.VANILLA;
        }
        return tallyFor(tier, f, cache.getAttackTarget());
    }

    /** 键里必须带<b>目标类型</b>：不同目标的减伤层完全不同，混在一起统计会得出错误结论。 */
    private static Tally tallyFor(TestbenchTiers tier, DebugFlags f, LivingEntity target) {
        String type = target == null ? "?"
                : String.valueOf(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                        .getKey(target.getType()));
        String key = (tier == null ? "pillar" : tier.id()) + "@" + f.activeLeverTag() + "@" + type;
        return TALLIES.computeIfAbsent(key, k -> new Tally());
    }

    // ------------------------------------------------------------------ 自动扫档（sweep）

    /** 扫档用的档位阶梯：从基线一路加到"真伤 + 破冷却"。 */
    public static final List<TestbenchTiers> SWEEP_LADDER = List.of(
            TestbenchTiers.PLAIN,
            TestbenchTiers.ARMOR,
            TestbenchTiers.ARMOR_ENCH,
            TestbenchTiers.PIERCE4,
            TestbenchTiers.TRUE_EDGE,
            TestbenchTiers.TRUE_EDGE_CD);

    /** 自动扫档状态：一个命令把整条阶梯跑完，省得手点十几次。 */
    public static final class Sweep {
        public boolean active;
        public int index;
        public int hitsPerTier = 20;
        /**
         * 本档每个目标已经累计的"尝试次数"——<b>不管有没有打穿都算</b>。
         * 免疫档（比如带 {@code modulargolems:immunity} 的傀儡会把整击取消）也必须能推进，
         * 否则测试会卡在那一档进不去（这正是"无法进入测试"的成因）。
         */
        public final Map<String, Integer> attempts = new LinkedHashMap<>();
        public String conditions = "";
        public final List<String> rows = new ArrayList<>();
        public TestbenchTiers previousTier = TestbenchTiers.PLAIN;

        public TestbenchTiers currentTier() {
            return SWEEP_LADDER.get(Math.min(index, SWEEP_LADDER.size() - 1));
        }

        public String attemptsText() {
            if (attempts.isEmpty()) {
                return "(还没有命中)";
            }
            StringBuilder sb = new StringBuilder();
            attempts.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ")
                    .append(k).append('=').append(v).append('/').append(hitsPerTier));
            return sb.toString();
        }
    }

    private static Sweep SWEEP;

    public static Sweep sweep() {
        return SWEEP;
    }

    /** 开始自动扫档：强制开启、按阶梯逐个档位收集 {@code hitsPerTier} 条命中。 */
    public static void startSweep(DebugFlags f, int hitsPerTier) {
        Sweep sweep = new Sweep();
        sweep.active = true;
        sweep.hitsPerTier = Math.max(5, hitsPerTier);
        sweep.previousTier = f.tier;
        f.enabled = true;
        f.tier = SWEEP_LADDER.get(0);
        f.setDirty();
        sweep.conditions = f.exportLine();
        clearTallies();
        SWEEP = sweep;
        TestbenchEvents.broadcast("扫档开始：" + sweep.hitsPerTier + " 下/档，阶梯 = "
                + SWEEP_LADDER.stream().map(TestbenchTiers::id).toList());
        TestbenchEvents.broadcast("现在用测试武器打同一个目标（建议：真正的怪，别用假人；每档都要打满 "
                + sweep.hitsPerTier + " 下，中途换目标会自动重来本档）。");
    }

    public static void stopSweep(DebugFlags f, boolean restore) {
        if (SWEEP == null) {
            return;
        }
        SWEEP.active = false;
        if (f != null && restore) {
            f.tier = SWEEP.previousTier;
            f.setDirty();
        }
    }

    /**
     * 每"尝试"一次就喂进来（不是每"打穿"一次）。
     *
     * <p>这一点很关键：带 {@code modulargolems:immunity} 的傀儡会把整击取消，l2 的伤害阶段根本不会触发，
     * 若按"打穿"计数，扫档就会卡在那一档 —— 现在按尝试计数，免疫档会正常跑完并显示通过率 0。</p>
     */
    private static void onSweepHit(LivingEntity target, DebugFlags f) {
        Sweep sweep = SWEEP;
        if (sweep == null || !sweep.active || target == null) {
            return;
        }
        String type = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getKey(target.getType()));
        int n = sweep.attempts.merge(type, 1, Integer::sum);
        if (n >= sweep.hitsPerTier) {
            captureRow(sweep, type);
            sweep.index++;
            sweep.attempts.clear();
            clearTallies();
            if (sweep.index >= SWEEP_LADDER.size()) {
                finishSweep(f);
                return;
            }
            f.tier = sweep.currentTier();
            f.setDirty();
            TestbenchEvents.broadcast("下一档：" + f.tier.id()
                    + "（继续打同一个目标；被整击免疫的档也会照常计数，通过率会是 0）");
            return;
        }
        if (n % 5 == 0) {
            TestbenchEvents.broadcast("本档[" + sweep.currentTier().id() + "] 进度：" + sweep.attemptsText());
        }
    }

    private static void captureRow(Sweep sweep, String targetType) {
        String tier = sweep.currentTier().id();
        // 取"本档 + 本目标"那条统计
        Tally only = null;
        for (Map.Entry<String, Tally> e : TALLIES.entrySet()) {
            if (!e.getKey().startsWith(tier + "@") || !e.getKey().endsWith("@" + targetType)) {
                continue;
            }
            if (only == null || e.getValue().planned > only.planned) {
                only = e.getValue();
            }
        }
        if (only == null) {
            sweep.rows.add(String.format(Locale.ROOT, "%-14s %-32s (无数据)", tier, targetType));
            return;
        }
        double amount = only.planned == 0 ? 0.0D : only.sumAmount / only.planned;
        sweep.rows.add(String.format(Locale.ROOT,
                "%-14s %-32s 重发=%3d 管线=%3d 取消=%3d 量=%6.2f fin=%7.2f kept=%6.3f 掉血=%7.2f 通过率=%6.3f %s",
                tier, targetType, only.planned, only.hits, only.canceled, amount,
                only.hits == 0 ? 0.0D : only.sumFin / only.hits, only.avgKept(),
                only.dropSamples == 0 ? 0.0D : only.sumDrop / only.dropSamples, only.passRate(),
                only.writeRejected > 0 ? "写血被拒=" + only.writeRejected : ""));
    }

    private static void finishSweep(DebugFlags f) {
        Sweep sweep = SWEEP;
        sweep.active = false;
        TestbenchEvents.broadcast("=========== 扫档结果（每档 " + sweep.hitsPerTier + " 下）===========");
        TestbenchEvents.broadcast("条件：" + sweep.conditions);
        for (String row : sweep.rows) {
            TestbenchEvents.broadcast(row);
        }
        TestbenchEvents.broadcast("看「通过率」这一列（= 真正掉的血 / 打出去的伤害），它不受 l2 是否上报影响；");
        TestbenchEvents.broadcast("「管线」列若远小于「重发」，说明这条伤害类型没被 l2 的伤害阶段接到，只看通过率即可。");
        TestbenchEvents.broadcast("也已写入 config/golems_arsenal/debug/ 的 sweep-*.json");
        TestbenchEvents.writeSweep(sweep);
        stopSweep(f, true);
    }

    // ------------------------------------------------------------------ 报告

    /** 汇总打印；{@code src} 不为 null 时同时发到聊天栏。 */
    public static void print(CommandSourceStack src) {
        List<String> lines = summaryLines();
        for (String line : lines) {
            PrivateTestbench.LOGGER.info(line);
            if (src != null) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
    }

    public static List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[GA-Testbench] 档位@杠杆@目标   重发  管线  被取消  量均值   fin均值  kept均值  掉血均值  通过率");
        TALLIES.forEach((key, t) -> {
            double amount = t.planned == 0 ? 0.0D : t.sumAmount / t.planned;
            double drop = t.dropSamples == 0 ? 0.0D : t.sumDrop / t.dropSamples;
            lines.add(String.format(Locale.ROOT,
                    "[GA-Testbench] %-46s %4d %5d %6d %8.2f %9.2f %8.3f %9.2f %7.3f%s",
                    key, t.planned, t.hits, t.canceled, amount,
                    t.hits == 0 ? 0.0D : t.sumFin / t.hits, t.avgKept(), drop, t.passRate(),
                    t.writeRejected > 0 ? "  写血被拒=" + t.writeRejected : ""));
        });
        lines.add("[GA-Testbench] 累计取消 " + totalCanceled + " 次；管线记录 " + recorded + " 条"
                + "（重发 > 管线 = 有命中没走 l2 的伤害阶段，这时只看「通过率」）");
        return lines;
    }

    /** 落盘成 JSON（方便多轮对比，不用翻日志）。 */
    public static String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"totalCanceled\": ").append(totalCanceled).append(",\n");
        sb.append("  \"recorded\": ").append(recorded).append(",\n");
        sb.append("  \"tallies\": {\n");
        boolean first = true;
        for (Map.Entry<String, Tally> e : TALLIES.entrySet()) {
            Tally t = e.getValue();
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    \"").append(e.getKey().replace("\"", "'")).append("\": {")
                    .append("\"planned\": ").append(t.planned)
                    .append(", \"pipelineHits\": ").append(t.hits)
                    .append(", \"canceled\": ").append(t.canceled)
                    .append(", \"avgAmount\": ").append(String.format(Locale.ROOT, "%.3f",
                            t.planned == 0 ? 0.0D : t.sumAmount / t.planned))
                    .append(", \"avgBase\": ").append(String.format(Locale.ROOT, "%.3f",
                            t.hits == 0 ? 0.0D : t.sumBase / t.hits))
                    .append(", \"avgFin\": ").append(String.format(Locale.ROOT, "%.3f",
                            t.hits == 0 ? 0.0D : t.sumFin / t.hits))
                    .append(", \"avgKept\": ").append(String.format(Locale.ROOT, "%.4f", t.avgKept()))
                    .append(", \"minKept\": ").append(String.format(Locale.ROOT, "%.4f",
                            t.minKept == Double.MAX_VALUE ? 0.0D : t.minKept))
                    .append(", \"avgDrop\": ").append(String.format(Locale.ROOT, "%.3f",
                            t.dropSamples == 0 ? 0.0D : t.sumDrop / t.dropSamples))
                    .append(", \"passRate\": ").append(String.format(Locale.ROOT, "%.4f", t.passRate()))
                    .append(", \"writeApplied\": ").append(t.writeApplied)
                    .append(", \"writeRejected\": ").append(t.writeRejected)
                    .append(", \"levers\": \"").append(t.levers.replace("\"", "'")).append("\"}")
                    ;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }
}
