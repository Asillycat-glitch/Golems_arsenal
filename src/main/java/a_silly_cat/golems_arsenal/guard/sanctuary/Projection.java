package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哑投影（B 档）：受保护傀儡在这一层被"封死"，实体只当显示层。
 *
 * <p>这一版是<b>测试态</b>：不留任何后门——主人打不了、敌人打不了、右键交互不了、
 * 掉落清空、AI 关掉、实体不再 tick、永久无敌、坐标只认我们自己写的。目的不是"能玩"，
 * 而是先把"哪些攻击落在了哪一层"测清楚。</p>
 *
 * <p>为什么要这么做：{@code 匠战妖域}/{@code 秘法涡流} 的清除手段根本不走伤害事件，
 * 它们直写 {@code DataItem}/{@code removalReason}、拆世界索引、甚至自己开线程补刀。
 * 方法级的门挡不住这些，所以把"实体"降级成消耗品：打坏了由 {@link Anchor} 按快照重建。</p>
 *
 * <p>和 {@link Anchor} 的分工：这里负责"封"，Anchor 负责"活"。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class Projection {

    /** 快照间隔（tick）。封装本身是每 tick 做的。 */
    private static final int INTERVAL = 10;

    /** 已冻结（不再 tick）的受保护傀儡。按 UUID 记，跨实体对象。 */
    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();
    /** 封印前的状态，切回 NORMAL 时原样还原，避免留下无敌/无 AI。 */
    private static final Map<UUID, SealedState> SEALED_STATE = new ConcurrentHashMap<>();
    private static final String SEALED_KEY = "GolemsArsenalProjectionSealed";

    private record SealedState(boolean invulnerable, boolean noAi) {
    }

    private static int tick;
    private static long sealed;
    private static long frozen;
    private static ProjectionMode loggedMode;
    private static volatile ProjectionMode override;
    /** 热路径用：本 tick 是不是 SEALED。避免每个实体每 tick 都去读一次配置。 */
    private static volatile boolean sealedActive;

    private Projection() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            if (sealedActive) {
                releaseAll();
            }
            sealedActive = false;
            return;
        }
        try {
            ProjectionMode mode = mode();
            sealedActive = mode == ProjectionMode.SEALED;
            if (loggedMode != mode) {
                loggedMode = mode;
                if (mode == ProjectionMode.SEALED) {
                    Golems_arsenal.LOGGER.info("[Sanctuary] projection=SEALED interval={} "
                                    + "(damage/interact/drops closed, AI off, tick frozen, position locked)",
                            INTERVAL);
                } else {
                    Golems_arsenal.LOGGER.info("[Sanctuary] projection=NORMAL interval={} (playable, anchor snapshots only)",
                            INTERVAL);
                    // 切回 NORMAL 必须解冻，否则上一轮冻住的傀儡永远不动。
                    FROZEN.clear();
                }
            }
            tick++;
            boolean snapshotNow = (tick % INTERVAL) == 0;
            for (AbstractGolemEntity<?, ?> golem : Anchor.liveSnapshot()) {
                if (mode == ProjectionMode.SEALED) {
                    seal(golem);
                } else {
                    unseal(golem);
                }
                // 快照两种模式都拍：NORMAL 下不再封，但"被删就重建"的权威副本不能停。
                if (snapshotNow) {
                    Anchor.snapshotNow(golem);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把一只傀儡压成"哑投影"：无敌 + 无 AI + 无仇恨 + 冻结 tick + 锁位置。 */
    private static void seal(AbstractGolemEntity<?, ?> golem) {
        try {
            Entity self = golem;
            boolean legacySealed = golem.isInvulnerable()
                    && self instanceof Mob mob && mob.isNoAi();
            SealedState prior = legacySealed
                    ? new SealedState(false, false)
                    : new SealedState(golem.isInvulnerable(),
                    self instanceof Mob mob && mob.isNoAi());
            SEALED_STATE.putIfAbsent(golem.getUUID(), prior);
            golem.getPersistentData().putBoolean(SEALED_KEY, true);
            if (!golem.isInvulnerable()) {
                golem.setInvulnerable(true);
            }
            if (self instanceof Mob mob) {
                mob.setNoAi(true);
                if (mob.getTarget() != null) {
                    mob.setTarget(null);
                }
            }
            if (FROZEN.add(golem.getUUID())) {
                frozen++;
            }
            sealed++;
        } catch (Throwable ignored) {
        }
    }

    /** 切回 NORMAL：还原封印前的无敌/无 AI，只解我们自己的封印。 */
    private static void unseal(AbstractGolemEntity<?, ?> golem) {
        try {
            UUID id = golem.getUUID();
            boolean wasSealed = FROZEN.remove(id)
                    | SEALED_STATE.containsKey(id)
                    | golem.getPersistentData().getBoolean(SEALED_KEY);
            if (!wasSealed) {
                // 旧版本封印没有 flag/state；"无敌 + 无 AI"这个组合视为遗留封印。
                Entity self = golem;
                wasSealed = golem.isInvulnerable() && self instanceof Mob mob && mob.isNoAi();
            }
            if (!wasSealed) {
                return;
            }
            SealedState prior = SEALED_STATE.remove(id);
            golem.getPersistentData().remove(SEALED_KEY);
            boolean invulnerable = prior != null && prior.invulnerable();
            if (golem.isInvulnerable() != invulnerable) {
                golem.setInvulnerable(invulnerable);
            }
            Entity self = golem;
            if (self instanceof Mob mob) {
                boolean noAi = prior != null && prior.noAi();
                if (mob.isNoAi() != noAi) {
                    mob.setNoAi(noAi);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void releaseAll() {
        try {
            for (AbstractGolemEntity<?, ?> golem : Anchor.liveSnapshot()) {
                unseal(golem);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 给 mixin 用的判定（都在热路径上，保持 O(1)）

    /** 这只实体现在是不是被封着的受保护傀儡。 */
    public static boolean sealed(Entity entity) {
        return sealedActive
                && entity instanceof AbstractGolemEntity<?, ?>
                && !entity.level().isClientSide()
                && FROZEN.contains(entity.getUUID());
    }

    /** tick 冻结：SEALED 下受保护傀儡不再走 {@code LivingEntity#tick}。 */
    public static boolean tickFrozen(LivingEntity entity) {
        return sealedActive
                && entity instanceof AbstractGolemEntity<?, ?>
                && !entity.level().isClientSide()
                && FROZEN.contains(entity.getUUID());
    }

    /** 交互（方法级）：{@code Mob#interact} / {@code Entity#interactAt} 直接失败。 */
    public static boolean denyInteraction(Entity entity) {
        if (!sealedActive || !(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        return !entity.level().isClientSide()
                && Sanctuary.protectedGolem(golem)
                && FROZEN.contains(golem.getUUID());
    }

    /** 给 {@code MobInteractGuardMixin} 的返回值；null = 放行。 */
    public static InteractionResult denyInteraction(Mob mob, Player player, InteractionHand hand) {
        return denyInteraction(mob) ? InteractionResult.FAIL : null;
    }

    public static long frozenCount() {
        return frozen;
    }


    /**
     * 伤害：这一版全拒。
     *
     * <p>注意连"敌人打的"也拒——测试态就是要让所有伤害都落空，这样后面每开一条白名单，
     * 就能用"血量有没有掉"直接判定是哪个口子漏的。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE || !Sanctuary.protectedGolem(golem)) {
            return;
        }
        if (mode() != ProjectionMode.SEALED) {
            return;
        }
        if (SanctuaryScopes.inDeath()) {
            return;
        }
        event.setCanceled(true);
    }

    /** 交互：右键/左键点傀儡一律拒绝（主人也一样，这一版不留后门）。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (deny(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    /**
     * 回收手杖的 shift-use 不走 EntityInteract：{@code RetrievalWandItem#use} 直接扫周围的傀儡
     * 并 {@code toItem}。SEALED 下必须在这里也关掉，否则会出现"discard 被拦下、物品仍然发到
     * 玩家背包"的复制漏洞。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()
                || mode() != ProjectionMode.SEALED
                || Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        if (event.getItemStack().getItem()
                instanceof dev.xkmc.modulargolems.content.item.wand.RetrievalWandItem) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (deny(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    private static boolean deny(Entity target) {
        return target instanceof AbstractGolemEntity<?, ?> golem
                && Sanctuary.mode() == SanctuaryMode.ENFORCE
                && Sanctuary.protectedGolem(golem)
                && mode() == ProjectionMode.SEALED;
    }

    // ------------------------------------------------------------------ 模式

    /**
     * 当前投影模式。
     *
     * <p>注意这里<b>不</b>看 {@link SanctuaryMode}：那是"拦不拦伤害"的总闸，这是"封不封实体"。
     * NORMAL + ENFORCE 是正常游玩要的组合；SEALED 只在测试轮用。</p>
     */
    public static ProjectionMode mode() {
        ProjectionMode value = override;
        if (value != null) {
            return value;
        }
        try {
            return Config.SANCTUARY_PROJECTION.get();
        } catch (Throwable ignored) {
            return ProjectionMode.SEALED;
        }
    }

    /** 运行时切换（{@code /ga guard projection ...}）；null 回到配置值。 */
    public static void setOverride(ProjectionMode value) {
        override = value;
    }

    public static String statusLine() {
        ProjectionMode value = override;
        return "projection=" + mode()
                + (value == null ? "(config)" : "(runtime)")
                + " reseals=" + sealed
                + " frozen=" + FROZEN.size();
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        if (FROZEN.remove(old)) {
            FROZEN.add(fresh);
        }
    }

    public static void clearCounters() {
        sealed = 0;
        frozen = 0;
        loggedMode = null;
    }
}
