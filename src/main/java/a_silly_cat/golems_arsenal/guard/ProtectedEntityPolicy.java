package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;

/**
 * 统一实体谓词：受保护傀儡 + Soul 活着 + 非正常生命周期。
 *
 * <p>这是门层唯一允许判断“要不要管”的地方。它不看 mod 名、类名、物品 ID，
 * 只看实体状态；内部重建用 {@link #runRebuild} 放行。</p>
 */
public final class ProtectedEntityPolicy {

    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> false);

    private ProtectedEntityPolicy() {
    }

    /** 服务端：是否应该保护这只实体。 */
    public static boolean protects(Entity entity) {
        if (entity == null || entity.level().isClientSide
                || !(entity instanceof AbstractGolemEntity<?, ?> golem)
                || golem.getOwnerUUID() == null) {
            return false;
        }
        try {
            net.minecraft.server.MinecraftServer server = golem.getServer();
            if (server == null) {
                return false;
            }
            if (server.isSingleplayer() && server.getSingleplayerProfile() != null) {
                net.minecraft.world.entity.player.Player local =
                        server.getPlayerList().getPlayer(server.getSingleplayerProfile().getId());
                if (local != null) {
                    if (!local.getUUID().equals(golem.getOwnerUUID())) {
                        return false;
                    }
                } else if (server.getPlayerList().getPlayer(golem.getOwnerUUID()) == null
                        && server.getProfileCache().get(golem.getOwnerUUID()).isEmpty()) {
                    return false;
                }
            } else if (server.getPlayerList().getPlayer(golem.getOwnerUUID()) == null
                    && server.getProfileCache().get(golem.getOwnerUUID()).isEmpty()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (inRebuild()) {
            return false;
        }
        // setRemoved 已经被 RemovalGuard 判成正常生命周期（回收/卸载/跨维度），
        // 后续容器清理不在 toItem 的栈上；必须继续放行，否则物品已回收、实体还在。
        if (RemovalGuard.isLifecycleAllowedRecently(golem)) {
            return false;
        }
        GolemSoul soul = SoulRegistry.soulFor(golem);
        if (soul != null) {
            if (soul.died) {
                return false;
            }
        } else if (!(HealthLedger.expectedOf(golem) > 1.0F)) {
            // H0 迁移期：Soul 还没建立时沿用账本。
            return false;
        }
        // 正常生命周期（回收/卸载/跨维度/真死）必须放行。
        return !RemovalGuard.isAllowedLifecycle(golem);
    }

    /** 客户端：当前 entityId 是否属于受保护投影。 */
    public static boolean protectsClient(Entity entity) {
        if (entity == null || !entity.level().isClientSide
                || !(entity instanceof AbstractGolemEntity<?, ?> golem)
                || golem.getOwnerUUID() == null) {
            return false;
        }
        return Anchor.isProtectedEntityId(entity.getId());
    }

    public static boolean inRebuild() {
        return Boolean.TRUE.equals(REBUILDING.get());
    }

    public static void runRebuild(Runnable action) {
        boolean previous = Boolean.TRUE.equals(REBUILDING.get());
        REBUILDING.set(true);
        if (!previous) {
            a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge.enterRebuild();
        }
        try {
            action.run();
        } finally {
            if (!previous) {
                a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge.exitRebuild();
            }
            REBUILDING.set(previous);
        }
    }
}
