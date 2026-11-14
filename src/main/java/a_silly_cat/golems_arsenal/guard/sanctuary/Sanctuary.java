package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golem Sanctuary：目标侧的伤害裁决。
 *
 * <p>它替掉的是"逐物品/逐 mod 判断"的源级门：不再问"这把武器危不危险"，而是问
 * "<b>这一下是谁打的，打的是不是我的傀儡</b>"。判定表见 {@link SanctuaryRules}。</p>
 *
 * <p>和写入口（{@code DamageAdmission}）的分工：</p>
 * <ul>
 *   <li>这里判"这次 hurt 要不要发生"，在 {@link LivingAttackEvent} 的 HIGHEST 阶段；
 *       放行时顺手把 {@link SanctuaryScopes} 的 hurt 窗口标成"已准入"；</li>
 *   <li>写入口只认窗口/死亡/读档/内部写，不再看调用者类名。所以"绕过 hurt 直接写血"
 *       无论从哪个模组、哪条线程来，都是默认拒绝。</li>
 * </ul>
 *
 * <p>不做什么：不碰 tag、不改数值、不拦敌人、不拦 {@code bypasses_invulnerability}
 * （那是另一层的事）。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class Sanctuary {

    /** 原版的行政伤害类型：世界边界、重生点。虚空不再单列。 */
    private static final String[] ADMIN_MSG_IDS = {
            "outsideBorder", "badRespawnPoint"
    };

    private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static final Map<SourceKind, Long> ALLOWED = new EnumMap<>(SourceKind.class);
    private static final Map<SourceKind, Long> DENIED = new EnumMap<>(SourceKind.class);

    private static volatile SanctuaryMode override;

    private Sanctuary() {
    }

    // ------------------------------------------------------------------ 事件

    /**
     * 启动时把"实际读到的配置"打出来。
     *
     * <p>2026-11-06 踩过一次：配置文件被外部改写后 Forge 静默回退到 SHADOW，
     * 日志里只有 would_reject，很难看出"其实是影子模式"。这条日志一眼就能确认。</p>
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        // agent 状态单独打一行：attach 失败时服务端照样能起，但防线会退化成纯 mod 层，
        // 这条日志让"到底有没有 agent"不再需要去翻多个文件才能确认。
        Golems_arsenal.LOGGER.info("[Sanctuary] agent={} (config agent_enabled={})",
                AgentBootstrap.isInstalled() ? "ATTACHED" : "NOT ATTACHED",
                agentConfigValue());
        Golems_arsenal.LOGGER.info("[Sanctuary] mode={} rules={} removal={} {}",
                mode(), rules(), RemovalGuard.mode(), AttributeGuard.statusLine());
        // 启动自检：确认关键 mixin handler 还挂在目标类上（对面有类转换器，可能被 retransform）。
        GuardCanary.verify();
        // 服务端起来了才允许 agent 开始周期重打（加载期重定义会和类加载抢锁）。
        AgentBootstrap.armWatch();
    }

    /**
     * 傀儡进入世界时就把 MAX_HEALTH 属性实例绑定到主人。
     *
     * <p>属性守卫靠这张表认出“这个 AttributeInstance 属于哪只受保护傀儡”。只绑在受击时不够——
     * 攻击方可能第一次攻击就不走 {@code hurt}，直接挂修饰符。</p>
     */
    @SubscribeEvent
    public static void onJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem) || !protectedGolem(golem)) {
            return;
        }
        try {
            AttributeGuard.bind(golem.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH), golem);
            // D3 封条：登记这只傀儡的血量 DataItem，DataItem#setValue 的字段门才认得出它。
            a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.sealHealthItem(golem);
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!protectedGolem(golem) || mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        // 2026-11-08：判定改用账本，不再看 raw / max。
        // raw 和 max 都是攻击方能写的（setHealth(0) / MAX_HEALTH 修饰符），拿它们当阈值
        // 等于用被污染的数据去验证被污染的数据。账本只在"被准入的伤害事件"里下降。
        float expected = a_silly_cat.golems_arsenal.guard.HealthLedger.expectedOf(golem);
        if (!Float.isFinite(expected) || expected <= 1.0F) {
            return; // 账本说已经空了：这次死亡是伤害管线带来的
        }
        if (SanctuaryScopes.inDeath() && a_silly_cat.golems_arsenal.guard.HealthLedger.observedDeath(golem)) {
            return; // 真的死在 die 里：不再二次否决
        }
        // GuardedEntity.die / LivingEntity.die 都会看 ForgeHooks.onLivingDeath 的返回值，
        // 取消事件等于让 die 直接 return，后面的 setHealth(0) / remove(KILLED) 都不会发生。
        event.setCanceled(true);
        a_silly_cat.golems_arsenal.guard.HealthLedger.noteTamper(golem);
        log(golem, "death_vetoed", SourceKind.INTERNAL, event.getSource(), expected);
    }

    /**
     * 掉落层：即使某个模组绕过 {@code die()} 直接调 {@code dropAllDeathLoot}
     * （toaru 的 {@code GungnirLootMixin#finishNativeLoot} 就是这么干的），
     * {@code LivingDropsEvent} 仍会触发，这里直接清空。
     *
     * <p>和 {@code DropLootGuardMixin} 双保险：mixin 拦方法调用，事件拦锻造/附魔/其它模组
     * 通过 {@code ForgeHooks.onLivingDrops} 走的那条路。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // 掉落清空是 SEALED 测试态的行为，不是永久规则。
        // 2026-11-09：原来这里不判投影模式，切到 NORMAL 之后掉落仍然被清空，
        // "可玩"就变成了"永远不掉落"。
        if (Projection.mode() != ProjectionMode.SEALED) {
            return;
        }
        if (blockExternalClear(golem, "LivingDropsEvent")) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!protectedGolem(golem)) {
            return;
        }
        // 受击时把 MAX_HEALTH 属性实例和主人绑定：属性级守卫（AttributeGuard）靠它认出
        // "这个 AttributeInstance 属于哪只受保护傀儡"，从而在修饰符进场时就拦下。
        try {
            AttributeGuard.bind(golem.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH), golem);
        } catch (Throwable ignored) {
        }
        // 死亡栈里的 hurt（例如 die 里的最后一击）不参与裁决。
        if (SanctuaryScopes.inDeath()) {
            SanctuaryScopes.admit(golem);
            return;
        }

        SanctuaryMode mode = mode();
        if (mode == SanctuaryMode.OFF) {
            SanctuaryScopes.admit(golem);
            return;
        }

        DamageSource source = event.getSource();
        SourceKind kind = classify(golem, source);
        boolean allowed = rules().allows(kind);
        if (allowed) {
            SanctuaryScopes.admit(golem);
            count(ALLOWED, kind);
            return;
        }

        if (mode == SanctuaryMode.SHADOW) {
            count(DENIED, kind);
            log(golem, "would_reject", kind, source, event.getAmount());
            // 影子模式必须照常准入：否则写入口会把这次 hurt 当成“绕过管线的直写”拒掉，
            // SHADOW 就变成实际 ENFORCE 了。
            SanctuaryScopes.admit(golem);
            return;
        }

        count(DENIED, kind);
        log(golem, "rejected", kind, source, event.getAmount());
        // 给动手的玩家一张命名纸（已有就不重复给）。
        if (event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer attacker) {
            SanctuaryTaunt.give(attacker);
        }
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 纯分类：这一下是谁打的。顺序很重要——先认"自己人"，再认"敌人"，最后才落到
     * "没有攻击者"的兜底。
     */
    public static SourceKind classify(AbstractGolemEntity<?, ?> golem, DamageSource source) {
        if (source == null) {
            return SourceKind.UNATTRIBUTED;
        }
        // 内部标记只覆盖“本模组自己、没有攻击者”的伤害（admission_rehurt 就是这种）。
        // 带攻击者的本模组伤害类型仍按身份判：傀儡自己的 AoE 打到同主傀儡属于 ALLY。
        if (source.getEntity() == null && source.getDirectEntity() == null && isInternalDamage(source)) {
            return SourceKind.INTERNAL;
        }
        // 不再单独给 outOfWorld 开环境白名单：虚空按普通伤害类型走 tag/身份分类。
        // generic_kill = kill() / /kill。默认算“说不清来路”，不跟着 operation 放行；
        // allow_generic_kill=true 时才放回环境，/kill 才能对受保护傀儡生效。
        if ("genericKill".equals(source.getMsgId())) {
            return allowGenericKill() ? SourceKind.ENVIRONMENT : SourceKind.UNATTRIBUTED;
        }
        // 操作级伤害不看攻击者：生物、别的玩家、脚本一视同仁。
        if (isOperation(source)) {
            return SourceKind.OPERATION;
        }

        UUID owner = golem.getOwnerUUID();
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();

        if (owner != null) {
            UUID directOwner = ownerOf(direct);
            if (direct != null && owner.equals(directOwner) && direct != attacker) {
                return SourceKind.OWNER_PROJECTILE;
            }
            if (owner.equals(entityId(attacker))) {
                return SourceKind.OWNER;
            }
            if (attacker instanceof AbstractGolemEntity<?, ?> other
                    && owner.equals(other.getOwnerUUID())) {
                return SourceKind.ALLY;
            }
            if (attacker instanceof OwnableEntity ownable && owner.equals(ownable.getOwnerUUID())) {
                return SourceKind.OWNER_PROJECTILE;
            }
        }

        if (attacker != null) {
            // 有实体来源就是"有人打的"：别的玩家、别人的傀儡、野生生物都算敌人。
            return SourceKind.ENEMY;
        }
        if (direct != null) {
            // 有直接实体但没有攻击者（无主箭、掉落方块、点燃的 TNT）：按环境处理。
            return SourceKind.ENVIRONMENT;
        }
        // generic_kill（/kill）没被 allow_generic_kill 放行时，isOperation 已经把它归到 OPERATION。
        return isEnvironment(source) ? SourceKind.ENVIRONMENT : SourceKind.UNATTRIBUTED;
    }

    /**
     * “操作级”伤害：带 {@code minecraft:bypasses_invulnerability} 的类型。
     *
     * <p>这是语义判断，不是 id 名单：任何模组只要把自己的伤害类型加进这条 tag，
     * 就是自己声明了“我要穿透无敌/处决”，我们一律按操作级处理。</p>
     */
    public static boolean isOperation(DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 伤害类型注册名在 {@code golems_arsenal} 命名空间 = 本模组的内部伤害（L2 标记）。 */
    public static boolean isInternalDamage(DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            Holder<DamageType> holder = source.typeHolder();
            ResourceKey<DamageType> key = holder.unwrapKey().orElse(null);
            return key != null && Golems_arsenal.MODID.equals(key.location().getNamespace());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isEnvironment(DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            if (source.is(DamageTypeTags.IS_FIRE)
                    || source.is(DamageTypeTags.IS_EXPLOSION)
                    || source.is(DamageTypeTags.IS_FALL)
                    || source.is(DamageTypeTags.IS_DROWNING)
                    || source.is(DamageTypeTags.IS_FREEZING)
                    || source.is(DamageTypeTags.IS_LIGHTNING)) {
                return true;
            }
            String id = source.getMsgId();
            for (String admin : ADMIN_MSG_IDS) {
                if (admin.equals(id)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static UUID entityId(Entity entity) {
        return entity == null ? null : entity.getUUID();
    }

    private static UUID ownerOf(Entity entity) {
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            return owner == null ? null : owner.getUUID();
        }
        if (entity instanceof OwnableEntity ownable) {
            return ownable.getOwnerUUID();
        }
        return null;
    }

    // ------------------------------------------------------------------ 配置/运行时

    /**
     * 受 Sanctuary 保护的实体：服务端的、有主人的傀儡。
     *
     * <p>刻意不依赖 {@code damage_admission.enabled}：那是写入口的开关，Sanctuary 是判定层，
     * 两件事分开配置，免得关掉写入口就顺手把判定也关了。</p>
     */
    public static boolean protectedGolem(AbstractGolemEntity<?, ?> golem) {
        return golem != null
                && !golem.level().isClientSide
                && golem.getOwnerUUID() != null;
    }

    /**
     * 外部“清除/处决”入口的统一判定（mod 层 mixin 调用）。
     *
     * <p>2026-11-08：目标改为“先全部挡住”——HardErase / GungnirExecution 这些深度清除
     * 直接在方法第一行拦掉，不再等实体半清除后再修。</p>
     */
    public static boolean blockExternalClear(Entity target, String site) {
        try {
            if (!(target instanceof AbstractGolemEntity<?, ?> golem)) {
                return false;
            }
            if (mode() != SanctuaryMode.ENFORCE || !protectedGolem(golem)) {
                return false;
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            // 玩家回收/正常卸载/跨维度：不要在下游的 onRemovedFromWorld / dropAllDeathLoot
            // 里再拦一次，否则实体被卡在"一半移除"的状态。
            if (RemovalGuard.isAllowedLifecycle(golem)) {
                return false;
            }
            Golems_arsenal.LOGGER.info("[Sanctuary] blocked site={} uuid={} owner={}",
                    site, golem.getUUID(), golem.getOwnerUUID());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static SanctuaryMode mode() {        SanctuaryMode value = override;
        if (value != null) {
            return value;
        }
        try {
            return Config.SANCTUARY_MODE.get();
        } catch (Throwable ignored) {
            return SanctuaryMode.SHADOW;
        }
    }

    public static void setOverride(SanctuaryMode value) {
        override = value;
    }

    public static SanctuaryRules rules() {
        try {
            return new SanctuaryRules(
                    Config.SANCTUARY_DENY_OWNER.get(),
                    Config.SANCTUARY_DENY_ALLY.get(),
                    Config.SANCTUARY_DENY_UNATTRIBUTED.get(),
                    Config.SANCTUARY_ALLOW_ENVIRONMENT.get(),
                    Config.SANCTUARY_DENY_OPERATION.get());
        } catch (Throwable ignored) {
            return SanctuaryRules.DEFAULT;
        }
    }

    /** {@code kill()} / {@code /kill} 是否算环境伤害。默认 false（当"说不清来路"处理）。 */
    public static boolean allowGenericKill() {
        try {
            return Config.SANCTUARY_ALLOW_GENERIC_KILL.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 只给日志用：读配置值失败时不要让整条启动自检跟着抛。 */
    private static String agentConfigValue() {
        try {
            return Config.SPEC.isLoaded() ? String.valueOf(Config.SANCTUARY_AGENT.get()) : "not-loaded";
        } catch (Throwable t) {
            return "unreadable(" + t.getClass().getSimpleName() + ")";
        }
    }

    public static String statusLine() {
        SanctuaryMode value = override;
        return "[sanctuary] mode=" + mode()
                + (value == null ? "(config)" : "(runtime)")
                + " rules=" + rules()
                + " denied=" + DENIED
                + " allowed=" + ALLOWED
                + " agent=" + (AgentBootstrap.isInstalled() ? "attached" : "off")
                + " " + SanctuaryScopes.status();
    }

    public static void clearCounters() {
        synchronized (ALLOWED) {
            ALLOWED.clear();
            DENIED.clear();
        }
    }

    public static void rotateIdentity(java.util.UUID old, java.util.UUID fresh) {
        Long at = LAST_LOG.remove(old);
        if (at != null) {
            LAST_LOG.put(fresh, at);
        }
    }

    // ------------------------------------------------------------------ 日志

    private static void count(Map<SourceKind, Long> map, SourceKind kind) {
        synchronized (map) {
            map.merge(kind, 1L, Long::sum);
        }
    }

    /**
     * 死亡预防：受保护傀儡在 {@code die} 的 HEAD 处被拦下，血写回账本值。
     *
     * <p>对应匠战妖域里 {@code etstlib} 的 {@code OnHoldingPreventDeathHook} /
     * {@code jzyy} 的 {@code ImmortalDeath}：不是取消死亡事件，而是让死亡流程根本不走完。
     * 这样致命一击没有机会在 die 之后继续写 {@code removalReason} / 清索引。</p>
     */
    public static boolean preventDeath(net.minecraft.world.entity.LivingEntity entity, DamageSource source) {
        try {
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return false;
            }
            if (mode() != SanctuaryMode.ENFORCE || !protectedGolem(golem)) {
                return false;
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            // 2026-11-08：判定用账本，不用 raw。
            // 旧实现看 raw > 1，攻击方只要"先把 raw 写 0、同一 tick 里调 die"，就永远拦不住。
            // 账本只在被准入的伤害事件里下降，所以"同一 tick 连打带杀"也说服不了它。
            float expected = HealthLedger.expectedOf(golem);
            if (!Float.isFinite(expected) || expected <= 1.0F) {
                return false; // 真被打空了：允许正常死亡
            }
            float max = golem.getMaxHealth();
            float floor = Math.max(1.0F, Float.isFinite(max) && max > 0.0F
                    ? Math.min(expected, max) : expected);
            // 补回冻结的血量，再让 die 直接 return。
            a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.writeRawHealth(golem, floor);
            HealthLedger.noteTamper(golem);
            // 强制快照（触发点 3/4）：死亡栈里救回来之后立刻拍一张，保证重建副本是"救回来之后"的样子。
            Anchor.snapshotNow(golem, "death_prevented");
            Golems_arsenal.LOGGER.info("[Sanctuary] death_prevented uuid={} ledger={} raw={} source={}",
                    golem.getUUID(), expected,
                    a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.rawHealth(golem),
                    source == null ? "none" : source.getMsgId());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void log(AbstractGolemEntity<?, ?> golem, String action,
                            SourceKind kind, DamageSource source, float amount) {
        // 时间源用游戏时间：canUpdate=false 会冻住 tickCount，节流会变成"永久只打一行"。
        long now = golem.level().getGameTime();
        Long last = LAST_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        String attacker = source == null || source.getEntity() == null
                ? "none" : source.getEntity().getName().getString();
        String detail = source == null ? "none" : source.getMsgId();
        Golems_arsenal.LOGGER.info(
                "[Sanctuary] {} kind={} target={} uuid={} amount={} source={} attacker={}",
                action, kind, golem.getName().getString(), golem.getUUID(),
                amount, detail, attacker);
    }

    /** 调试用：给命令打印一张"这一下会怎么判"的表。 */
    public static String explain(AbstractGolemEntity<?, ?> golem, DamageSource source) {
        SourceKind kind = classify(golem, source);
        return "kind=" + kind + " allowed=" + rules().allows(kind);
    }
}
