package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.sanctuary.AttributeGuard;
import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import a_silly_cat.golems_arsenal.guard.sanctuary.ProjectionMode;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import a_silly_cat.golems_arsenal.base.event.GolemsArsenalSetHandler;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Week 3：只读 HealthLedger + TickAudit。
 *
 * <p>它只做三件事：</p>
 * <ol>
 *   <li>按正常伤害/治疗事件维护 expected；</li>
 *   <li>每 20 tick 对比 raw（DATA_HEALTH_ID）和 expected，记录
 *       {@code UNEXPLAINED_HEALTH}；</li>
 *   <li>记录 {@code REMOVED_WITHOUT_DEATH}、{@code CLASS_CHANGED}、{@code OFF_THREAD}。</li>
 * </ol>
 *
 * <p><b>不写血、不拦截、不修数据。</b> expected 只是审计用；raw 才是事实。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class HealthLedger {

    private static final int AUDIT_INTERVAL = 20;

    private static final Map<UUID, Track> TRACKED = new HashMap<>();
    private static volatile boolean enabled = true;

    private static long unexplained;
    private static long removedWithoutDeath;
    private static long classChanged;
    private static long offThread;
    private static long restored;
    private static long nonFinite;
    private static long nbtWindowChanged;
    private static long windowAnomaly;
    private static long windowRejected;
    private static long tamperRepaired;
    /** 未经 Sanctuary 准入、却直接触发 LivingDamageEvent 的次数（账本拒绝记账）。 */
    private static long outsideAdmission;

    private HealthLedger() {
    }

    public static boolean isEnabled() {
        if (!enabled) {
            return false;
        }
        try {
            return Config.GUARD_AUDIT_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        if (!isEnabled() || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide()) {
            return;
        }
        Track track = track(golem);
        if (track == null) {
            return;
        }
        ServerLevel level = golem.level() instanceof ServerLevel sl ? sl : null;
        if (level != null && !level.getServer().isSameThread()) {
            offThread++;
            log(track, "OFF_THREAD", "damage on non-server thread source=" + event.getSource().getMsgId());
        }
        // 2026-11-09：账本只该在被 Sanctuary 放行过的那次 hurt 里下降。
        // 实测有 mod 绕过 LivingAttackEvent 直接触发 LivingDamageEvent（日志里是
        // `rejected kind=UNATTRIBUTED amount=1.0 source=magic` 之后账本仍然 -1），
        // 这条 if 就是拦它：不是准入窗口里的伤害，一律不记账，只把它当篡改记下来。
        boolean admitted = SanctuaryScopes.isAdmitted(golem)
                || SanctuaryScopes.inDeath()
                || SanctuaryScopes.inLoad();
        if (!admitted) {
            outsideAdmission++;
            noteTamper(golem);
            log(track, "DAMAGE_OUTSIDE_ADMISSION",
                    "amount=" + event.getAmount() + " source=" + event.getSource().getMsgId()
                            + " expected=" + track.expected);
            return;
        }
        // 只拒绝“数值不合法”，不限制正常大伤害：
        // 1,000,000 伤害/1,000,000 血量仍然放行；toaru 的 Float.MAX 会被挡。
        float damageAmount = event.getAmount();
        float maxHealth = golem.getMaxHealth();
        float ceiling = Math.max(1_000_000.0F, maxHealth * 100.0F);
        if (!Float.isFinite(damageAmount) || damageAmount < 0.0F
                || damageAmount > ceiling) {
            outsideAdmission++;
            noteTamper(golem);
            log(track, "INVALID_DAMAGE",
                    "amount=" + damageAmount + " ceiling=" + ceiling
                            + " source=" + event.getSource().getMsgId()
                            + " expected=" + track.expected);
            return;
        }
        track.expected = HealthAuditMath.expectedAfterDamage(track.expected, event.getAmount(), golem.getAbsorptionAmount());
        track.lastEvent = "damage:" + event.getSource().getMsgId();
        track.lastEventTick = eventTick(golem);
        SoulRegistry.observe(golem, track.expected, golem.getMaxHealth(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHeal(LivingHealEvent event) {
        if (!isEnabled() || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide()) {
            return;
        }
        Track track = track(golem);
        if (track == null) {
            return;
        }
        float healed = Math.max(0.0F, event.getAmount());
        track.expected = HealthAuditMath.expectedAfterHeal(track.expected, golem.getMaxHealth(), healed);
        track.lastEvent = "heal";
        track.lastEventTick = eventTick(golem);
        SoulRegistry.observe(golem, track.expected, golem.getMaxHealth(), false);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!isEnabled() || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide()) {
            return;
        }
        // 被 Sanctuary 否决掉的死亡不是死亡。
        // 2026-11-09：这个 handler 是默认优先级，排在 Sanctuary 的 HIGHEST 之后；
        // 不判 canceled 的话，一次"伪造死亡"就能把账本记成 died=true + expected=0，
        // 之后 observedDeath 让 Anchor 直接放弃这只傀儡——等于替攻击方把账本清了。
        if (event.isCanceled()) {
            return;
        }
        Track track = track(golem);
        if (track != null) {
            track.died = true;
            track.expected = 0.0F;
            SoulRegistry.observe(golem, 0.0F, golem.getMaxHealth(), true);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        // 客户端离开世界只是"我这里看不见了"，不是事实。同一个 UUID 还有服务端那一条，
        // 2026-11-09 实测：客户端区块卸载把服务端的账本删了（日志里的 [Render thread] REMOVED_WITHOUT_DEATH）。
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!isEnabled() || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!golem.isRemoved() || golem.getRemovalReason() == null) {
            return;
        }
        Track track = TRACKED.get(golem.getUUID());
        // 只有"正常生命周期"才算离开；敌意移除（KILLED、被拦下的 DISCARDED、冒充卸载）
        // 必须保留账本，否则 expectedOf 会退回被写脏的 raw，Anchor 直接放弃这只傀儡。
        if (track != null && !track.died && !RemovalGuard.isAllowedLifecycle(golem)) {
            removedWithoutDeath++;
            log(track, "REMOVED_WITHOUT_DEATH",
                    "reason=" + golem.getRemovalReason()
                            + " raw=" + DamageAdmission.rawHealth(golem)
                            + " ledger=" + track.expected + " kept=true");
            ORPHAN_SINCE.put(golem.getUUID(), gameTime(golem));
            return;
        }
        TRACKED.remove(golem.getUUID());
        ORPHAN_SINCE.remove(golem.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % AUDIT_INTERVAL != 0) {
            return;
        }
        auditAll(server);
    }

    private static void auditAll(MinecraftServer server) {
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof AbstractGolemEntity<?, ?> golem) || !owned(golem)) {
                    continue;
                }
                Track track = track(golem);
                if (track == null) {
                    continue;
                }
                seen.add(track.id);
                // 实体又出现了（Anchor 重挂/重建成功）：孤儿账本被认领，不再计时。
                ORPHAN_SINCE.remove(track.id);
                a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.runTrusted(() -> audit(golem, track));
            }
        }
        for (Track track : new HashSet<>(TRACKED.values())) {
            if (!seen.contains(track.id)) {
                auditMissing(server, track);
            }
        }
        expireOrphans(server);
    }

    /**
     * 孤儿账本的超时清理。
     *
     * <p>只清"敌意移除后 {@link #ORPHAN_TTL} tick 内没有被 Anchor 认领"的条目。
     * 敌意移除时保留、到点再清，是为了让 Entity 被删除的那一瞬间
     * {@link #expectedOf} 仍然回答得出来（否则 ledgerAlive 会当场失效）。</p>
     */
    private static void expireOrphans(MinecraftServer server) {
        if (ORPHAN_SINCE.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        var it = ORPHAN_SINCE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue() <= ORPHAN_TTL) {
                continue;
            }
            it.remove();
            Track track = TRACKED.remove(entry.getKey());
            HealthLedgerData data = HealthLedgerData.get(server);
            if (data != null) {
                data.remove(entry.getKey());
            }
            if (track != null) {
                log(track, "LEDGER_EXPIRED",
                        "kept=" + ORPHAN_TTL + "ticks expected=" + track.expected + " died=" + track.died);
            }
        }
    }

    private static void audit(AbstractGolemEntity<?, ?> golem, Track track) {
        String className = golem.getClass().getName();
        if (!className.equals(track.className)) {
            classChanged++;
            log(track, "CLASS_CHANGED", "was=" + track.className + " now=" + className);
            track.className = className;
        }

        float raw = DamageAdmission.rawHealth(golem);
        float actual = golem.getHealth();
        if (!Float.isFinite(raw) || !Float.isFinite(actual)) {
            nonFinite++;
            log(track, "NON_FINITE_HEALTH",
                    "raw=" + raw + " actual=" + actual + " expected=" + track.expected);
            return;
        }
        // 复活（神灵金「不灭重生」这类）：之前记录的 died 不能一直挂着，否则修复和移除守卫
        // 会永远把这只傀儡当成"已经死了"。
        if (track.died && raw > 0.0F) {
            track.died = false;
        }
        guardMaxHealth(golem, track);
        raw = repairTamper(golem, track, raw);
        clearDeadFlag(golem, track, raw);
        checkNbtWindow(golem, track);
        if (Float.isFinite(track.expected) && HealthAuditMath.unexplained(track.expected, raw)) {
            unexplained++;
            log(track, "UNEXPLAINED_HEALTH",
                    "expected=" + track.expected + " raw=" + raw
                            + " actual=" + actual
                            + " delta=" + (raw - track.expected)
                            + " max=" + golem.getMaxHealth()
                            + " died=" + track.died
                            + " repair=" + repairEnabled()
                            + " lastEvent=" + track.lastEvent);
        }
        // 2026-11-08：账本"只管下界"。
        // 过去这里无条件 track.expected = raw —— 一旦攻击方在审计那一 tick 把 raw 写成 0，
        // 账本就会把 0 当成"应得值"，Aegis 也就没得修了。现在只有"向上"或"账本还没有值"时才采纳。
        if (!Float.isFinite(track.expected) || track.expected <= 0.0F || raw >= track.expected) {
            track.expected = raw;
        }
        track.lastRaw = raw;
        track.lastActual = actual;
        track.lastPos = golem.blockPosition();
        track.dimension = golem.level().dimension();
        track.className = className;
        MinecraftServer server = golem.getServer();
        if (server != null) {
            HealthLedgerData data = HealthLedgerData.get(server);
            data.put(track.id, new HealthLedgerData.Entry(
                    track.expected, track.lastRaw, track.className, track.dimension.location().toString()));
        }
    }


    /** 直接注册（不经过 EntityJoinLevelEvent）后把新投影纳入账本。 */
    public static void ensureTracked(AbstractGolemEntity<?, ?> golem) {
        if (golem != null) {
            try {
                track(golem);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 这只傀儡是否已经被账本跟踪（有被准入的伤害/治疗历史）。 */
    public static boolean tracked(AbstractGolemEntity<?, ?> golem) {
        return golem != null
                && (SoulRegistry.soulFor(golem) != null || TRACKED.containsKey(golem.getUUID()));
    }

    /** 这只傀儡是否真的触发过 {@code LivingDeathEvent}。克制用：不看血量（血量可被篡改）。 */
    public static boolean observedDeath(AbstractGolemEntity<?, ?> golem) {
        GolemSoul soul = SoulRegistry.soulFor(golem);
        if (soul != null) {
            return soul.died;
        }
        Track track = TRACKED.get(golem.getUUID());
        return track != null && track.died;
    }

    /**
     * 账本认为这只傀儡现在应该有多少血。
     *
     * <p>H1：Soul 存在时优先读 Soul；旧 Track 只作为迁移期 fallback。
     * 没被跟踪过就返回 raw（当作"没发生过任何伤害"）。</p>
     */
    public static float expectedOf(AbstractGolemEntity<?, ?> golem) {
        GolemSoul soul = SoulRegistry.soulFor(golem);
        if (soul != null && Float.isFinite(soul.health())) {
            return soul.health();
        }
        Track track = TRACKED.get(golem.getUUID());
        if (track != null && Float.isFinite(track.expected)) {
            return track.expected;
        }
        return DamageAdmission.rawHealth(golem);
    }

    /** 记一次"血量被篡改"的时刻，供移除守卫判断"刚被攻击过就 discard"这种组合。 */
    private static final Map<UUID, Long> TAMPER_TICK = new HashMap<>();
    /**
     * 敌意移除留下的孤儿账本：{@code uuid -> 被移除时的游戏时间}。
     *
     * <p>这些条目<b>故意不删</b>：Anchor 要靠账本把实体挂回去/重建，账本一删，
     * {@code expectedOf} 就退回被写脏的 raw，所有"账本说活着"的判定全部失效。</p>
     */
    private static final Map<UUID, Long> ORPHAN_SINCE = new HashMap<>();
    /** 孤儿账本的宽限期（tick）。覆盖 Anchor 的 MISSING_LIMIT 重建窗口，超时才算真没了。 */
    private static final int ORPHAN_TTL = 1200;
    /** 最近被打过的傀儡：每 tick 单独对账，避免"客户端先看到 0 → 播死亡动画"。 */
    private static final Map<UUID, java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>>> HOT = new HashMap<>();
    /** MAX_HEALTH_TAMPER 的日志节流。 */
    private static final Map<UUID, Long> LAST_MAX_LOG = new HashMap<>();

    /**
     * 时间源：游戏时间，<b>不是</b> {@code tickCount}。
     *
     * <p>坑 #1：攻击方把 {@code canUpdate} 写成 false 之后实体不再 tick，{@code tickCount} 永远
     * 停在那一刻，所有基于它的窗口都会永久失真——{@link #recentTamper} 会永远回答"没被篡改过"，
     * 于是 {@link Anchor} 把一只刚被标死的傀儡当成真死放手，重建再也不发生。
     * 游戏时间由 level tick 推进，写字段停不掉它。</p>
     */
    private static long gameTime(AbstractGolemEntity<?, ?> golem) {
        try {
            return golem.level().getGameTime();
        } catch (Throwable ignored) {
            return golem.tickCount;
        }
    }

    public static void noteTamper(AbstractGolemEntity<?, ?> golem) {
        TAMPER_TICK.put(golem.getUUID(), gameTime(golem));
        HOT.put(golem.getUUID(), new java.lang.ref.WeakReference<>(golem));
    }

    /** 最近 {@code window} tick 内是否发生过血量篡改。 */
    public static boolean recentTamper(AbstractGolemEntity<?, ?> golem, int window) {
        Long at = TAMPER_TICK.get(golem.getUUID());
        return at != null && gameTime(golem) - at <= window;
    }

    /**
     * 热区对账：被打过的傀儡每 tick 检查一次。
     *
     * <p>常规审计是 20 tick 一次，攻击方把 max/health 写成 0 之后，客户端会先收到一帧 0
     * 并播放死亡动画；修复再快也已经迟了。对"最近 100 tick 内被拒过写"的傀儡改成每 tick 对账，
     * 把这个窗口压到最小。</p>
     */
    @SubscribeEvent
    public static void onServerTickHot(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled() || HOT.isEmpty()) {
            return;
        }
        for (java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref : new ArrayList<>(HOT.values())) {
            AbstractGolemEntity<?, ?> golem = ref == null ? null : ref.get();
            if (golem == null) {
                continue;
            }
            try {
                if (golem.isRemoved() || !recentTamper(golem, 100)) {
                    HOT.remove(golem.getUUID());
                    TAMPER_TICK.remove(golem.getUUID());
                    LAST_MAX_LOG.remove(golem.getUUID());
                    continue;
                }
                a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.runTrusted(() -> audit(golem, track(golem)));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 账本兜底：raw 明显低于账本时，把 raw 写回账本值。
     *
     * <p>为什么需要它：像 ArcaneVortex / toaru 这类模组会直接写
     * {@code SynchedEntityData.DataItem.value}（字段写），没有任何方法可以挂钩，
     * 方法边界的写入口天生看不见。唯一通用的办法是事后对账：账本说应该是多少，
     * raw 就必须是多少；对不上就按账本写回。</p>
     *
     * <p>边界（避免和正常伤害打架）：</p>
     * <ul>
     *   <li>只向上修（{@code raw < expected}），从不往下压血；</li>
     *   <li>只修“差距 &gt; 20% 最大生命”的大幅下降，小误差不动；</li>
     *   <li>hurt / 死亡栈里不修，账本自己漂到 max 以上也不修（只记 UNEXPLAINED）；</li>
     *   <li>只对活着的、玩家拥有的傀儡生效。</li>
     * </ul>
     */
    private static boolean repairEnabled() {
        try {
            return Config.SANCTUARY_REPAIR_TAMPER.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 最大生命守卫。
     *
     * <p>2026-11-07 发现：ArcaneVortex 的 {@code HealthModify.attributeModifier} 会给
     * {@code MAX_HEALTH} 挂一个 {@code -(max-health)+0.001} 的 ADDITION 修饰符，把上限压到 0。
     * 上限一旦是 0，{@code getHealth()} 读到 0，后面的修复和死亡否决全部失效
     * （日志里的 {@code max=0.0} 就是它）。</p>
     *
     * <p>这里做两件事：把 base 恢复成上次的正常值，并移除“大到足以腰斩上限”的负向修饰符。
     * 只在上限掉到一半以下时动手，正常升级/卸下不会触发。</p>
     */
    private static java.lang.reflect.Field deadField;

    /**
     * 清除被外部写死的 {@code LivingEntity.dead} 标志（{@code f_20890_}）。
     *
     * <p>VanSh 的 {@code EntityKillerHelp.tickDeath} 会反射把 {@code dead} 置真。
     * 血量修回来之后 {@code dead} 还挂着，{@code isDeadOrDying()} 就一直为真——
     * 表现正是"左键之后空手收不回来，用回收手杖收回再放出来才好"（重新放出是全新实体）。</p>
     *
     * <p>只在真实血量 &gt; 1 且不在死亡栈里时清，真死的时候不会被动。</p>
     */
    private static void clearDeadFlag(AbstractGolemEntity<?, ?> golem, Track track, float raw) {
        try {
            if (!(raw > 1.0F) || SanctuaryScopes.inDeath()) {
                return;
            }
            if (deadField == null) {
                deadField = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                        net.minecraft.world.entity.LivingEntity.class, "f_20890_");
                deadField.setAccessible(true);
            }
            if (deadField.getBoolean(golem)) {
                deadField.setBoolean(golem, false);
                log(track, "DEAD_FLAG_CLEARED", "raw=" + raw);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * SEALED 下把"最大生命下滑"拉回去。
     *
     * <p>为什么单独做：{@link #guardMaxHealth} 原来的判据是"跌幅超过 50%"，因为正常玩法里
     * 掉一点上限是允许的。但清除类武器会按 -1/tick 慢慢磨（实测 666→661、100→84），
     * 一步都没到 50%，于是被当成正常变化写进 {@code lastMax}，账本
     * {@code target = min(expected, max)} 也跟着往下掉——看起来就像"挡不住"。</p>
     *
     * <p>做法：超过 1 点的下滑就回填 base，并清掉负的 ADDITION 修饰符。返回 true 表示
     * "已经处理过，本轮别再往下走"。</p>
     */
    private static boolean restoreMaxDrop(AbstractGolemEntity<?, ?> golem, Track track, float max) {
        try {
            if (Projection.mode() != ProjectionMode.SEALED) {
                return false;
            }
            if (a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard.mode() != SanctuaryMode.ENFORCE) {
                return false;
            }
            if (!(max < track.lastMax - 1.0F)) {
                return false;
            }
            var inst = golem.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (inst == null) {
                return false;
            }
            AttributeGuard.bind(inst, golem);
            int removed = 0;
            for (var modifier : new ArrayList<>(inst.getModifiers())) {
                if (modifier.getAmount() < 0.0D
                        && modifier.getOperation()
                        == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION) {
                    inst.removeModifier(modifier);
                    removed++;
                }
            }
            // 只补"还差多少"，不要直接把 base 设成 lastMax —— 那样会把升级/装备给的正向
            // 修饰符一起算进 base，每修一次 max 就涨一截。
            float afterRemoval = golem.getMaxHealth();
            float shortfall = track.lastMax - afterRemoval;
            if (Float.isFinite(shortfall) && shortfall > 0.5F) {
                double base = inst.getBaseValue();
                inst.setBaseValue(Math.max(0.0D, base + shortfall));
            }
            float after = golem.getMaxHealth();
            log(track, "MAX_HEALTH_RESTORED",
                    "max=" + max + " -> " + after + " base=" + inst.getBaseValue() + " removedMods=" + removed);
            if (Float.isFinite(after) && after > 0.0F) {
                track.lastMax = after;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void guardMaxHealth(AbstractGolemEntity<?, ?> golem, Track track) {
        try {
            float max = golem.getMaxHealth();
            if (!Float.isFinite(max)) {
                return;
            }
            if (track.lastMax <= 0.0F) {
                track.lastMax = max;
                return;
            }
            // 2026-11-09 SEALED：最大生命只许不掉。
            // 实测有武器按 -1/s 慢慢磨 max（666 -> 661、100 -> 84），旧逻辑因为"跌幅没到 50%"
            // 直接把它当成正常变化采纳（lastMax 跟着往下走），于是账本 target=min(expected,max)
            // 也跟着掉。全封死阶段不再接受任何下滑。
            if (restoreMaxDrop(golem, track, max)) {
                return;
            }
            if (max > 0.0F && max >= track.lastMax * 0.5F) {
                track.lastMax = max;
                return;
            }
            var inst = golem.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            AttributeGuard.bind(inst, golem);
            if (inst == null) {
                return;
            }
            int removed = 0;
            for (var modifier : new java.util.ArrayList<>(inst.getModifiers())) {
                double amount = modifier.getAmount();
                var op = modifier.getOperation();
                boolean hugeNegative =
                        (op == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION
                                && amount <= -track.lastMax * 0.5D)
                        || (op == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL
                                && amount <= -0.5D)
                        || (op == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE
                                && amount <= -0.5D);
                if (hugeNegative) {
                    inst.removeModifier(modifier);
                    removed++;
                }
            }
            if (inst.getBaseValue() < track.lastMax * 0.5D) {
                inst.setBaseValue(track.lastMax);
            }
            float after = golem.getMaxHealth();
            long now = gameTime(golem);
            Long last = LAST_MAX_LOG.put(golem.getUUID(), now);
            if (removed > 0 || last == null || now - last >= 20) {
                log(track, "MAX_HEALTH_TAMPER",
                        "max=" + track.lastMax + " -> " + max + " -> " + after
                                + " removedMods=" + removed
                                + " base=" + inst.getBaseValue()
                                + " mods=" + inst.getModifiers().size()
                                + " attrId=" + inst.getAttribute().getDescriptionId());
            }
            if (Float.isFinite(after) && after > 0.0F) {
                track.lastMax = after;
            }
        } catch (Throwable ignored) {
        }
    }

    private static float repairTamper(AbstractGolemEntity<?, ?> golem, Track track, float raw) {
        try {
            float max = golem.getMaxHealth();
            if (!Float.isFinite(max) || max <= 0.0F) {
                max = track.lastMax;
            }
            boolean measurable = Float.isFinite(max) && max > 0.0F
                    && Float.isFinite(track.expected) && track.expected > 0.0F;
            float gap = measurable ? track.expected - raw : 0.0F;
            boolean big = measurable && gap >= 1.0F && gap >= max * 0.2F;

            if (!Config.SANCTUARY_REPAIR_TAMPER.get()) {
                if (big) {
                    log(track, "HEALTH_TAMPER_SKIP", "repair_tamper=false");
                }
                return raw;
            }
            if (!Float.isFinite(raw) || !Float.isFinite(track.expected) || track.expected <= 0.0F) {
                return raw;
            }
            // 真实死亡之后不再修：die 已经把 expected 归零，这里只是保险。
            // 2026-11-07 修：不再看 track.died——伪造的死亡事件会把 died 置真，
            // 之后修复就永远不跑；真正的死亡由 expected<=0 和 inDeath 拦住。
            if (SanctuaryScopes.inDeath()) {
                if (big) {
                    log(track, "HEALTH_TAMPER_SKIP", "inDeath");
                }
                return raw;
            }
            if (SanctuaryScopes.inHurt(golem)) {
                if (big) {
                    log(track, "HEALTH_TAMPER_SKIP", "inHurt");
                }
                return raw;
            }
            if (!measurable) {
                return raw;
            }
            // 账本自己已经漂到 max 以上：先不修，留给 UNEXPLAINED 记录。
            if (track.expected > max + 1.0F) {
                if (big) {
                    log(track, "HEALTH_TAMPER_SKIP",
                            "ledger-drift expected=" + track.expected + " max=" + max);
                }
                return raw;
            }
            if (!big) {
                return raw;
            }
            float target = Math.min(track.expected, max);
            if (!DamageAdmission.writeRawHealth(golem, target)) {
                log(track, "HEALTH_TAMPER_SKIP", "write-failed target=" + target);
                return raw;
            }
            tamperRepaired++;
            noteTamper(golem);
            log(track, "HEALTH_TAMPER_REPAIRED",
                    "raw=" + raw + " -> " + target + " (账本值，只向上修)");
            float after = DamageAdmission.rawHealth(golem);
            return Float.isFinite(after) ? after : target;
        } catch (Throwable ignored) {
            return raw;
        }
    }

    /** Selftest / 直写入口用：记录一次非服务器线程回调。 */
    public static void noteOffThread(LivingEntity entity, String path) {
        if (!isEnabled() || !(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!(golem.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.getServer().isSameThread()) {
            return;
        }
        offThread++;
        Golems_arsenal.LOGGER.info("[GuardAudit] OFF_THREAD uuid={} path={} thread={}",
                golem.getUUID(), path, Thread.currentThread().getName());
    }

    /** GolemsArsenalSetHandler 正常写光柱计时后调用，避免被当成 NBT 外部改。 */
    public static void noteTimerWrite(AbstractGolemEntity<?, ?> golem) {
        Track track = TRACKED.get(golem.getUUID());
        if (track != null) {
            track.timerWrite = true;
        }
    }

    /** 光柱计时被 enforce 拒绝时记录。 */
    public static void noteWindowRejected(AbstractGolemEntity<?, ?> golem, String detail) {
        windowRejected++;
        Track track = TRACKED.get(golem.getUUID());
        if (track != null) {
            log(track, "WINDOW_REJECTED", detail);
        } else {
            Golems_arsenal.LOGGER.info("[GuardAudit] WINDOW_REJECTED uuid={} {}", golem.getUUID(), detail);
        }
    }

    private static void checkNbtWindow(AbstractGolemEntity<?, ?> golem, Track track) {
        CompoundTag tag = golem.getPersistentData();
        long cd = tag.getLong(GolemsArsenalSetHandler.PILLAR_CD_KEY);
        long until = tag.getLong(GolemsArsenalSetHandler.PILLAR_UNTIL_KEY);
        if (track.windowInitialized
                && !track.timerWrite
                && (cd != track.lastPillarCd || until != track.lastPillarUntil)) {
            nbtWindowChanged++;
            log(track, "NBT_WINDOW_CHANGED",
                    "pillarCd=" + track.lastPillarCd + "->" + cd
                            + " pillarUntil=" + track.lastPillarUntil + "->" + until);
        }
        if (track.windowInitialized
                && ((track.lastPillarCd != 0L && cd == 0L)
                || (track.lastPillarUntil != 0L && until == 0L)
                || (until == Long.MAX_VALUE && track.lastPillarUntil != Long.MAX_VALUE))) {
            windowAnomaly++;
            log(track, "WINDOW_ANOMALY",
                    "pillarCd=" + cd + " pillarUntil=" + until);
        }
        track.lastPillarCd = cd;
        track.lastPillarUntil = until;
        track.windowInitialized = true;
        track.timerWrite = false;
    }

    /** Selftest 用：立即对一只测试傀儡做一次只读对账。 */
    public static void auditNow(AbstractGolemEntity<?, ?> golem) {
        if (!isEnabled()) {
            return;
        }
        Track track = track(golem);
        if (track != null) {
            audit(golem, track);
        }
    }

    /** Selftest 用：立即记录一次“没有死亡事件的移除”。 */
    public static void auditRemovedNow(AbstractGolemEntity<?, ?> golem) {
        Track track = TRACKED.get(golem.getUUID());
        if (track == null) {
            return;
        }
        if (!track.died) {
            removedWithoutDeath++;
            log(track, "REMOVED_WITHOUT_DEATH",
                    "reason=" + golem.getRemovalReason() + " raw=" + track.lastRaw);
        }
        TRACKED.remove(track.id);
        MinecraftServer server = golem.getServer();
        if (server != null) {
            HealthLedgerData.get(server).remove(track.id);
        }
    }

    /** Selftest 清理：不产生 finding。 */
    public static void forget(AbstractGolemEntity<?, ?> golem) {
        Track track = TRACKED.remove(golem.getUUID());
        MinecraftServer server = golem.getServer();
        if (track != null && server != null) {
            HealthLedgerData.get(server).remove(track.id);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(isEnabled(), TRACKED.size(), unexplained,
                removedWithoutDeath, classChanged, offThread, restored,
                nonFinite, nbtWindowChanged, windowAnomaly, windowRejected);
    }

    private static void auditMissing(MinecraftServer server, Track track) {
        // 敌意移除留下的孤儿账本：这里是"实体确实不在世界里"，但账本要留着给 Anchor 用。
        // 2026-11-09 之前这里无条件 TRACKED.remove + data.remove，等于替攻击方把证据清了。
        if (ORPHAN_SINCE.containsKey(track.id)) {
            return;
        }
        ServerLevel level = server.getLevel(track.dimension);
        if (level == null) {
            TRACKED.remove(track.id);
            return;
        }
        if (!level.isLoaded(track.lastPos)) {
            // 区块未加载：不能判定为移除。
            return;
        }
        if (level.getEntity(track.id) != null) {
            return;
        }
        if (!track.died) {
            // 没死、区块开着、索引里也没有：和 onLeave 看到的是同一件事。
            // 保留账本并记成孤儿，等 Anchor 重建；不能在这里删。
            removedWithoutDeath++;
            log(track, "REMOVED_WITHOUT_DEATH",
                    "chunkLoaded=true raw=" + track.lastRaw + " actual=" + track.lastActual
                            + " ledger=" + track.expected + " kept=true");
            ORPHAN_SINCE.putIfAbsent(track.id, server.overworld().getGameTime());
            return;
        }
        TRACKED.remove(track.id);
        HealthLedgerData data = HealthLedgerData.get(server);
        if (data != null) {
            data.remove(track.id);
        }
    }

    private static Track track(AbstractGolemEntity<?, ?> golem) {
        if (!owned(golem) || !(golem.level() instanceof ServerLevel)) {
            return null;
        }
        Track track = TRACKED.computeIfAbsent(golem.getUUID(), id -> {
            float raw = DamageAdmission.rawHealth(golem);
            if (!Float.isFinite(raw)) {
                raw = golem.getHealth();
            }
            Track created = new Track(id, golem.level().dimension(), golem.blockPosition(),
                    golem.getClass().getName(), raw, golem.getHealth());
            MinecraftServer server = golem.getServer();
            if (server != null) {
                HealthLedgerData data = HealthLedgerData.get(server);
                if (data != null && data.get(id) != null) {
                    restored++;
                }
            }
            return created;
        });
        SoulRegistry.ensure(golem, track.expected, golem.getMaxHealth());
        return track;
    }

    private static boolean owned(AbstractGolemEntity<?, ?> golem) {
        return golem.getOwnerUUID() != null;
    }

    private static int eventTick(AbstractGolemEntity<?, ?> golem) {
        return golem.tickCount;
    }

    private static void log(Track track, String kind, String detail) {
        Golems_arsenal.LOGGER.info("[GuardAudit] {} uuid={} tick={} {}",
                kind, track.id, track.lastEventTick, detail);
    }

    public static String statusLine() {
        Snapshot s = snapshot();
        return "[guard-audit] enabled=" + s.enabled()
                + " tracked=" + s.tracked()
                + " unexplained=" + s.unexplained()
                + " removedWithoutDeath=" + s.removedWithoutDeath()
                + " classChanged=" + s.classChanged()
                + " offThread=" + s.offThread()
                + " nonFinite=" + s.nonFinite()
                + " nbtWindowChanged=" + s.nbtWindowChanged()
                + " windowAnomaly=" + s.windowAnomaly()
                + " windowRejected=" + s.windowRejected()
                + " tamperRepaired=" + tamperRepaired
                + " outsideAdmission=" + outsideAdmission
                + " restored=" + s.restored();
    }

    /** 身份轮换：把这只傀儡在所有 UUID 表里的条目从 old 搬到 fresh。 */
    public static void rotateIdentity(UUID old, UUID fresh) {
        if (old == null || fresh == null || old.equals(fresh)) {
            return;
        }
        SoulRegistry.rotateProjection(old, fresh);
        Track oldTrack = TRACKED.remove(old);
        if (oldTrack != null) {
            TRACKED.put(fresh, new Track(oldTrack, fresh));
        }
        move(TAMPER_TICK, old, fresh);
        move(ORPHAN_SINCE, old, fresh);
        move(HOT, old, fresh);
        move(LAST_MAX_LOG, old, fresh);
        try {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                HealthLedgerData.get(server).rename(old, fresh);
            }
        } catch (Throwable ignored) {
        }
    }

    private static <V> void move(Map<UUID, V> map, UUID old, UUID fresh) {
        V value = map.remove(old);
        if (value != null) {
            map.put(fresh, value);
        }
    }

    public static void clear() {
        TRACKED.clear();
        SoulRegistry.clear();
        TAMPER_TICK.clear();
        ORPHAN_SINCE.clear();
        HOT.clear();
        LAST_MAX_LOG.clear();
        unexplained = 0;
        removedWithoutDeath = 0;
        classChanged = 0;
        offThread = 0;
        nonFinite = 0;
        nbtWindowChanged = 0;
        windowAnomaly = 0;
        windowRejected = 0;
        tamperRepaired = 0;
        outsideAdmission = 0;
        restored = 0;
    }

    public record Snapshot(boolean enabled, int tracked, long unexplained,
                           long removedWithoutDeath, long classChanged,
                           long offThread, long restored, long nonFinite,
                           long nbtWindowChanged, long windowAnomaly, long windowRejected) {
    }

    private static final class Track {
        final UUID id;
        ResourceKey<Level> dimension;
        BlockPos lastPos;
        String className;
        float expected;
        float lastRaw;
        float lastActual;
        /** 最近一次看到的正常最大生命（攻击方会把 max 压成 0，这个值用来恢复和对账）。 */
        float lastMax;
        boolean died;
        boolean windowInitialized;
        boolean timerWrite;
        long lastPillarCd;
        long lastPillarUntil;
        int lastEventTick = -1;
        String lastEvent = "-";

        Track(UUID id, ResourceKey<Level> dimension, BlockPos lastPos, String className,
              float expected, float lastActual) {
            this.id = id;
            this.dimension = dimension;
            this.lastPos = lastPos;
            this.className = className;
            this.expected = expected;
            this.lastRaw = expected;
            this.lastActual = lastActual;
            this.lastMax = lastActual;
        }

        /** 身份轮换用：换 id，其余审计状态原样带过去。 */
        Track(Track other, UUID id) {
            this.id = id;
            this.dimension = other.dimension;
            this.lastPos = other.lastPos;
            this.className = other.className;
            this.expected = other.expected;
            this.lastRaw = other.lastRaw;
            this.lastActual = other.lastActual;
            this.lastMax = other.lastMax;
            this.died = other.died;
            this.windowInitialized = other.windowInitialized;
            this.timerWrite = other.timerWrite;
            this.lastPillarCd = other.lastPillarCd;
            this.lastPillarUntil = other.lastPillarUntil;
            this.lastEventTick = other.lastEventTick;
            this.lastEvent = other.lastEvent;
        }
    }
}
