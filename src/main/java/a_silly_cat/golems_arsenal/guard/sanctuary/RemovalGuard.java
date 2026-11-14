package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 移除守卫：按 {@link Entity.RemovalReason} 判，不按 id。
 *
 * <p>口径（和回收/倒地那套状态对齐）：</p>
 * <ul>
 *   <li>{@code KILLED}：只有"已经死了"才放行（{@code isDeadOrDying()}、血 &lt;= 0、
 *       或者在死亡栈里）；<b>活着被 KILLED 移除</b>才是要拦的那一种。</li>
 *   <li>{@code DISCARDED}：默认也按敌意处理（toaru/AV/Capsule 都走它），只放行
 *       玩家回收窗口和宿主 {@code RetrievalWandItem -> toItem} 这两条合法链；</li>
 *   <li>{@code UNLOADED_TO_CHUNK}：区块真的不在才放行；区块还开着（冒充卸载）拦；</li>
 *   <li>{@code UNLOADED_WITH_PLAYER} / {@code CHANGED_DIMENSION}：正常搬运/传送，放行。</li>
 * </ul>
 *
 * <p>默认 SHADOW：只记 {@code would_reject}。ENFORCE 下会取消 {@code setRemoved}，
 * 并对活体做一次身份轮换（{@link IdentityGuard}）。</p>
 */
public final class RemovalGuard {

    private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();

    private static long wouldReject;
    private static long rejected;
    private static long allowed;
    private static long resurrected;
    private static volatile SanctuaryMode override;

    private RemovalGuard() {
    }

    public static SanctuaryMode mode() {
        SanctuaryMode value = override;
        if (value != null) {
            return value;
        }
        try {
            return Config.SANCTUARY_REMOVAL.get();
        } catch (Throwable ignored) {
            return SanctuaryMode.SHADOW;
        }
    }

    /** 运行时切换（{@code /ga guard removal ...}）；null 回到配置值。 */
    public static void setMode(SanctuaryMode value) {
        override = value;
    }

    /**
     * {@code Entity#setRemoved(RemovalReason)} HEAD：返回 false 表示取消这次移除。
     */
    public static boolean allow(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return true;
        }
        if (entity.level().isClientSide || golem.getOwnerUUID() == null) {
            return true;
        }
        SanctuaryMode mode = mode();
        if (mode == SanctuaryMode.OFF) {
            return true;
        }
        String name = reason == null ? "null" : reason.name();
        if (isDead(golem)) {
            allowed++;
            return true;
        }
        // 2026-11-12：DISCARDED 不再一律放行。
        // 回收手杖的 toItem 也是 discard()，所以单独给"玩家回收"留窗口：
        //   1) 玩家右键回收手杖时记 10 tick 窗口；
        //   2) 调用栈里同时出现 RetrievalWandItem 和 AbstractGolemEntity.toItem 时直接认；
        // 其余 DISCARDED（Capsule 清区、toaru finishRemoval、AV 的 discard）一律按敌意处理。
        if (isAllowedLifecycle(golem, reason)) {
            allowed++;
            return true;
        }
        boolean hostile = "KILLED".equals(name)
                || "DISCARDED".equals(name)
                || ("UNLOADED_TO_CHUNK".equals(name)
                    && entity.level().isLoaded(entity.blockPosition()));
        if (!hostile) {
            allowed++;
            return true;
        }
        if (mode == SanctuaryMode.SHADOW) {
            wouldReject++;
            // 强制快照（触发点 1/4）：SHADOW 下移除会真的发生，必须在它发生前拍一张，
            // 否则 Anchor 重建时手里只有 10 tick 前的旧副本（或者被攻击方写过的 0 血）。
            Anchor.snapshotNow(golem, "removal_would_reject");
            log(golem, "would_reject " + name, reason);
            return true;
        }
        rejected++;
        Anchor.snapshotNow(golem, "removal_rejected");
        // 换名是"拦下之后"的增益项：对方的死亡名单/延迟任务按旧 UUID 回来时会打空。
        // 失败不影响拒绝本身（rotate 内部已经把异常吞掉）。
        IdentityGuard.rotate(golem, "removal_" + name);
        log(golem, "rejected " + name, reason);
        return false;
    }

    /**
     * 判定"这只傀儡是不是真的死了"。
     *
     * <p>2026-11-06 修两次：</p>
     * <ol>
     *   <li>先是不再相信 {@code raw <= 0}（攻击方可以先把 raw 写 0 再发 KILLED）；</li>
     *   <li>然后是不再相信 {@code isDeadOrDying()}——它只看血量，而血量正好是攻击方能改的那个值。
     *       现在认三件事：<b>正在死亡栈里</b>（{@code die} 的 mixin 开着）、
     *       <b>HealthLedger 真的见过这只傀儡的 {@code LivingDeathEvent}</b>、
     *       或者<b>账本已经被准入伤害压到 0</b>（宿主 postHurt 回收没有死亡事件）。</li>
     * </ol>
     */
    private static boolean isDead(AbstractGolemEntity<?, ?> golem) {
        try {
            // 最近被篡改过血：连"见过死亡事件"都不信——伪造的 die 会先把 died 置真。
            if (HealthLedger.recentTamper(golem, 100)) {
                return SanctuaryScopes.inDeath();
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return true;
            }
            // 2026-11-12：账本归零也是真死。宿主自己的回收路径（AbstractGolemEntity.postHurt
            // 在 guardedData<=0 时 returnToInventory + discard）没有死亡事件，只有账本能回答。
            if (HealthLedger.tracked(golem)) {
                float expected = HealthLedger.expectedOf(golem);
                return Float.isFinite(expected) && expected <= 0.0F;
            }
            return false;
        } catch (Throwable ignored) {
            // 读不到状态时保守放行，避免把实体卡死在世界里。
            return true;
        }
    }

    // ------------------------------------------------------------------ 正常生命周期 / 玩家回收
    //
    // 2026-11-12：DISCARDED 从"一律放行"改成"默认敌意"，所以必须有办法把玩家的正常回收
    // 和攻击方的 discard 分开。只用事件不够（回收手杖的 shift-use 根本不逐个 interact），
    // 所以这里用两条独立证据：
    //   * 玩家右键回收手杖时的 10 tick 窗口（MobInteractGuardMixin 记录）；
    //   * 调用栈里同时有 RetrievalWandItem 和 AbstractGolemEntity.toItem（host 回收路径）。

    private static final Map<UUID, Long> RECOVERY_AT = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** 玩家右键回收手杖、目标是一只受保护傀儡时调用。 */
    public static void notePlayerRecovery(Entity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)
                || golem.level().isClientSide() || golem.getOwnerUUID() == null) {
            return;
        }
        try {
            long now = golem.level().getGameTime();
            if (RECOVERY_AT.size() > 1024) {
                RECOVERY_AT.entrySet().removeIf(e -> now - e.getValue() > 200);
            }
            RECOVERY_AT.put(golem.getUUID(), now);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isRecoveryWindow(AbstractGolemEntity<?, ?> golem) {
        try {
            // SEALED 是"不留后门"的测试态，连玩家的回收窗口也不放行。
            if (Projection.mode() == ProjectionMode.SEALED) {
                return false;
            }
            Long at = RECOVERY_AT.get(golem.getUUID());
            if (at == null) {
                return false;
            }
            long age = golem.level().getGameTime() - at;
            return age >= 0 && age <= recoveryTicks();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int recoveryTicks() {
        try {
            return Math.max(0, Config.SANCTUARY_RECOVERY_TICKS.get());
        } catch (Throwable ignored) {
            return 10;
        }
    }

    /** 这次移除是不是正常生命周期（卸载/跨维度/玩家回收/放生），不是"活体被折索引"。 */
    public static boolean isAllowedLifecycle(Entity entity) {
        return isAllowedLifecycle(entity, rawRemovalReason(entity));
    }

    /** 同上，但由调用方给出本次 reason（{@code setRemoved} 的 HEAD 里字段还没写）。 */
    public static boolean isAllowedLifecycle(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case CHANGED_DIMENSION, UNLOADED_WITH_PLAYER -> true;
            // 正常区块卸载在 setRemoved 时区块仍可能是 loaded；retrieval 的 toItem
            // 也可能紧接着触发 UNLOADED_TO_CHUNK。只要栈里是宿主回收，就放行。
            case UNLOADED_TO_CHUNK -> !entity.level().isLoaded(entity.blockPosition())
                    || isRecoveryWindow(golem) || isHostRetrieval(golem) || isVoidRecycle(golem);
            case DISCARDED -> isRecoveryWindow(golem) || isHostRetrieval(golem) || isVoidRecycle(golem);
            case KILLED -> false;
        };
    }

    /**
     * 调用栈里出现宿主自己的"合法移除"链：
     * <ul>
     *   <li>回收手杖 / 空手收回：{@code AbstractGolemEntity.toItem}（不要求同时出现
     *       {@code RetrievalWandItem}——空手收回没有手杖帧）；</li>
     *   <li>虚空/致命伤的宿主回收：{@code AbstractGolemEntity.returnToInventory} +
     *       {@code discard}（{@code hurt}/{@code postHurt} 两条）。</li>
     * </ul>
     * 别的 mod 直接调 {@code discard()} 不会同时出现这些帧。
     */
    private static boolean isHostRetrieval(AbstractGolemEntity<?, ?> golem) {
        // SEALED 是"不留后门"的测试态：即使是宿主回收路径也不放行。
        if (Projection.mode() == ProjectionMode.SEALED) {
            return false;
        }
        try {
            return WALKER.walk(frames -> {
                boolean toItem = false;
                boolean recycle = false;
                var it = frames.iterator();
                while (it.hasNext()) {
                    var frame = it.next();
                    String cls = frame.getClassName();
                    if ("dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity".equals(cls)) {
                        if ("toItem".equals(frame.getMethodName())) {
                            toItem = true;
                        } else if ("returnToInventory".equals(frame.getMethodName())) {
                            recycle = true;
                        }
                    }
                    if (toItem || recycle) {
                        return true;
                    }
                }
                return false;
            });
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 虚空死亡：{@code AbstractGolemEntity.hurt} 在 y 低于世界底部 64 格时直接
     * {@code untrack + discard}，既没有 LivingDeathEvent，也不一定走 returnToInventory。
     * 这是"掉进虚空就该死"的正常路径；只在 y 确实在虚空里、且调用栈来自 hurt 时放行。
     */
    private static boolean isVoidRecycle(AbstractGolemEntity<?, ?> golem) {
        try {
            if (Projection.mode() == ProjectionMode.SEALED) {
                return false;
            }
            double floor = golem.level().getMinBuildHeight() - 64.0D;
            if (!(golem.getY() < floor)) {
                return false;
            }
            return WALKER.walk(frames -> frames.anyMatch(f ->
                    "dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity".equals(f.getClassName())
                            && ("hurt".equals(f.getMethodName()) || "m_6469_".equals(f.getMethodName()))));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        Long at = RECOVERY_AT.remove(old);
        if (at != null) {
            RECOVERY_AT.put(fresh, at);
        }
        Long log = LAST_LOG.remove(old);
        if (log != null) {
            LAST_LOG.put(fresh, log);
        }
        Long guard = LAST_GUARD_LOG.remove(old);
        if (guard != null) {
            LAST_GUARD_LOG.put(fresh, guard);
        }
    }

    private static java.lang.reflect.Field removalReasonField;

    /**
     * 字段级移除守卫：{@code Entity#isRemoved()} 被调用时检查，必要时把 {@code removalReason} 清掉。
     *
     * <p>ArcaneVortex 的长按右键走的是
     * {@code IsRemovedHelper.setIsRemoved(entity,true)} + {@code RemovalReasonHelper.setRemovalReason}
     * ——直接反射写 {@code Entity.removalReason} 字段，<b>根本不调用 setRemoved</b>，
     * 所以 {@link #allow} 那层看不到它。这里在"世界认为它已被移除"的那一刻把字段还原。</p>
     *
     * <p>只对 KILLED/DISCARDED 生效；区块卸载、跨维度这些原因一律放行。</p>
     */
    public static boolean resurrect(Entity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        try {
            Entity.RemovalReason reason = golem.getRemovalReason();
            if (reason == null) {
                return false;
            }
            String name = reason.name();
            if (!"KILLED".equals(name) && !"DISCARDED".equals(name)) {
                return false;
            }
            if (entity.level().isClientSide || golem.getOwnerUUID() == null) {
                return false;
            }
            if (mode() != SanctuaryMode.ENFORCE) {
                return false;
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            // 2026-11-07：只在"最近真的被篡改过"时才复活。
            // 之前无条件复活，把玩家的回收（也是 DISCARDED）也一起顶回去了——
            // 表现就是"回收傀儡被清除时无法回收"。正常回收没有 tamper 记录，直接放行。
            if (!HealthLedger.recentTamper(golem, 60)) {
                return false;
            }
            float max = golem.getMaxHealth();
            float expected = HealthLedger.expectedOf(golem);
            float threshold = (Float.isFinite(max) && max > 0.0F) ? max * 0.2F : 1.0F;
            if (!Float.isFinite(expected) || expected <= threshold) {
                return false;
            }
            if (removalReasonField == null) {
                removalReasonField = net.minecraftforge.fml.util.ObfuscationReflectionHelper
                        .findField(Entity.class, "f_146795_");
                removalReasonField.setAccessible(true);
            }
            removalReasonField.set(golem, null);
            resurrected++;
            log(golem, "resurrected " + name, reason);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 硬锁层的无条件修复：把 {@code removalReason} 清回 {@code null}。
     *
     * <p>和 {@link #resurrect(Entity)} 的区别：这里<b>不走</b>"最近有没有被篡改 / 模式是不是
     * ENFORCE / 账本还够不够"这些门——调用方已经是 {@link Aegis} 的不变量检查，
     * 它只知道一件事：账本说活着，实体却被标成已移除。再问一遍就是给自己留后门。</p>
     *
     * <p>只拒绝三种：客户端、没有主人、真的死过（{@link HealthLedger#observedDeath}）。
     * 卸载/跨维度也不在这里修（{@link Aegis} 会把它们从监视表里拿掉）。</p>
     */
    public static boolean hardRepair(Entity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        if (entity.level().isClientSide || golem.getOwnerUUID() == null) {
            return false;
        }
        Entity.RemovalReason reason = golem.getRemovalReason();
        if (reason == null) {
            return false;
        }
        try {
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            // 正常卸载 / 跨维度 / 玩家回收 / 放生：不硬修，否则会把合法移除顶回去。
            if (isAllowedLifecycle(golem)) {
                return false;
            }
            if (removalReasonField == null) {
                removalReasonField = net.minecraftforge.fml.util.ObfuscationReflectionHelper
                        .findField(Entity.class, "f_146795_");
                removalReasonField.setAccessible(true);
            }
            removalReasonField.set(golem, null);
            resurrected++;
            log(golem, "hard_repaired " + reason, reason);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 读侧权威：这只实体"是否算已移除"由账本回答。
     *
     * <p>为什么不复用 {@link #resurrect(Entity)}：那条路是"事后把字段改回来"，要先满足
     * 最近被篡改、模式、阈值等条件，而且它<b>不能</b>在读取的同一帧生效——别的模组在
     * {@code isRemoved()} 为真的那一帧就已经按"它没了"处理了。</p>
     *
     * <p>这里只做一件事：有主 + 服务端 + ENFORCE + 账本说活着 + 不是真死 + 不是跨维度，
     * 就回答"没被移除"。字段本身仍由 {@code Aegis} 下一 tick 清掉。</p>
     */
    public static boolean ledgerAlive(Entity entity) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        try {
            if (entity.level().isClientSide || golem.getOwnerUUID() == null) {
                return false;
            }
            if (mode() != SanctuaryMode.ENFORCE) {
                return false;
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            if (rawRemovalReason(entity) == Entity.RemovalReason.CHANGED_DIMENSION) {
                return false;
            }
            boolean alive = HealthLedger.expectedOf(golem) > 1.0F;
            if (alive) {
                noteGuardAnswer(golem);
            }
            return alive;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 读侧探针
    //
    // 2026-11-09 加：实测出现"rawReason=null 但 addFreshEntity 仍说 marked as removed"。
    // vanilla 的 isRemoved() 就是 final 的 `removalReason != null`，所以这只有两种可能：
    //   1. 我们的读侧回答根本没被调用（别的东西在更前面就把返回值定了）；
    //   2. 调用时字段确实非空，之后又被清掉。
    // 这个探针区分 1 和 2：guard_forced 出现 = 我们答了 false；没出现 = 我们没被问到。

    private static final Map<UUID, Long> LAST_GUARD_LOG = new ConcurrentHashMap<>();
    private static long guardForced;
    private static long guardAsked;

    private static void noteGuardAnswer(AbstractGolemEntity<?, ?> golem) {
        guardAsked++;
        try {
            long now = golem.level().getGameTime();
            Long last = LAST_GUARD_LOG.get(golem.getUUID());
            if (last != null && now - last < 40) {
                return;
            }
            LAST_GUARD_LOG.put(golem.getUUID(), now);
            guardForced++;
            Golems_arsenal.LOGGER.warn("[Sanctuary] isremoved_forced uuid={} raw={} ledger={}",
                    golem.getUUID(), rawRemovalReason(golem), HealthLedger.expectedOf(golem));
        } catch (Throwable ignored) {
        }
    }

    public static String guardStatus() {
        return "isRemovedAsked=" + guardAsked + " forced=" + guardForced;
    }

    /** 直接读字段，避免 {@code getRemovalReason()} 撞上我们自己的 mixin 造成递归。 */
    public static Entity.RemovalReason rawRemovalReason(Entity entity) {
        try {
            if (removalReasonField == null) {
                removalReasonField = net.minecraftforge.fml.util.ObfuscationReflectionHelper
                        .findField(Entity.class, "f_146795_");
                removalReasonField.setAccessible(true);
            }
            return (Entity.RemovalReason) removalReasonField.get(entity);
        } catch (Throwable ignored) {
            return entity.getRemovalReason();
        }
    }

    private static void log(AbstractGolemEntity<?, ?> golem, String action, Entity.RemovalReason reason) {
        // 时间源用游戏时间：canUpdate=false 会冻住 tickCount，节流会变成"永久只打一行"。
        long now = golem.level().getGameTime();
        Long last = LAST_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        Golems_arsenal.LOGGER.info(
                "[Sanctuary] removal {} target={} uuid={} reason={} health={} raw={} dead={}",
                action, golem.getName().getString(), golem.getUUID(), reason,
                golem.getHealth(),
                a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission.rawHealth(golem),
                golem.isDeadOrDying());
    }

    public static String statusLine() {
        return "[sanctuary] removal=" + mode()
                + " allowed=" + allowed
                + " wouldReject=" + wouldReject
                + " rejected=" + rejected
                + " recoveryWindows=" + RECOVERY_AT.size();
    }

    /** 把 {@code removalReason} 字段清空（重建/重新注册前必须做，否则 addFreshEntity 会被当成已移除）。 */
    public static boolean clearField(Entity entity) {
        try {
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem) || entity.level().isClientSide()) {
                return false;
            }
            Entity.RemovalReason reason = rawRemovalReason(entity);
            if (reason == null) {
                return false;
            }
            if (removalReasonField == null) {
                removalReasonField = net.minecraftforge.fml.util.ObfuscationReflectionHelper
                        .findField(Entity.class, "f_146795_");
                removalReasonField.setAccessible(true);
            }
            removalReasonField.set(golem, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}