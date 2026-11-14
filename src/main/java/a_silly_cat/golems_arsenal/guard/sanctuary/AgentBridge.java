package a_silly_cat.golems_arsenal.guard.sanctuary;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * agent 与 mod 之间的单向桥。
 *
 * <p>agent 运行在 bootstrap 层，碰不到 Minecraft 类，所以它只把"被拒绝的目标 + 站点"传回来，
 * 由这里（mod 类加载器）决定给谁塞那张纸。</p>
 */
public final class AgentBridge {

    private AgentBridge() {
    }

    /** agent 每次 DENIED 调一次；签名必须保持 (Object, String)，agent 侧是反射调用。 */
    public static void onDenied(Object target, String site) {
        try {
            if (!(target instanceof AbstractGolemEntity<?, ?> golem)) {
                return;
            }
            if (!(golem.level() instanceof ServerLevel level) || golem.getOwnerUUID() == null) {
                return;
            }
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(golem.getOwnerUUID());
            SanctuaryTaunt.give(owner);
        } catch (Throwable ignored) {
        }
    }
    /** 把受保护实体的 UUID 推给 agent（agent 侧只存 UUID，用于清扫外来墓碑表）。 */
    public static void onProtected(java.util.UUID id) {
        if (id == null) {
            return;
        }
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            runtime.getMethod("protect", java.util.UUID.class).invoke(null, id);
        } catch (Throwable ignored) {
            // agent 没挂：什么也不做
        }
    }

    /**
     * 2026-11-12：mod 层活体身份轮换后同步 agent。
     *
     * <p>agent 的 {@code PROTECTED} / {@code EXEMPT} 是按 UUID 查的；不同步的话，
     * 轮换后的新 UUID 不属于任何门，agent 字段门/方法调用门会突然放行。</p>
     */
    public static void onIdentityRotated(java.util.UUID oldId, java.util.UUID freshId) {
        if (oldId == null || freshId == null || oldId.equals(freshId)) {
            return;
        }
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            runtime.getMethod("adoptIdentity", java.util.UUID.class, java.util.UUID.class)
                    .invoke(null, oldId, freshId);
        } catch (Throwable ignored) {
            // agent 没挂：mod 自己的 IdentityGuard 已经迁移完
        }
    }

    /** 给 {@code /ga guard identity} 用：agent 当前保护/假死计数；没挂返回 off。 */
    public static String agentStatusLine() {
        try {
            Class<?> runtime = Class.forName("sanctuary.agent.GuardRuntime");
            Object ids = runtime.getMethod("protectedIds").invoke(null);
            int protectedCount = ids instanceof java.util.Set<?> set ? set.size() : -1;
            Object fake = runtime.getMethod("fakeDeadCount").invoke(null);
            int fakeCount = fake instanceof Number n ? n.intValue() : -1;
            return "agentProtected=" + protectedCount + " agentFakeDead=" + fakeCount;
        } catch (Throwable ignored) {
            return "agent=off";
        }
    }
}