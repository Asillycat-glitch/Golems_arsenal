package a_silly_cat.golems_arsenal.guard.sanctuary;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.UUID;

/**
 * 回收/移除后，敌对生物的 target / Brain 记忆里可能还留着旧实体。
 * 旧实体的 AI 仍然会"锁定幽灵"，所以这里在生命周期放行时主动清一次，
 * 并周期性扫掉 target 已经 isRemoved / 不在同一 level 的陈旧目标。
 */
public final class GhostTargetGuard {

    private GhostTargetGuard() {
    }

    public static void clearFor(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return;
        }
        try {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob) {
                    clearMob(mob, id, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 周期扫描：target 已经真死/被移除/不在当前 level 的，清掉。 */
    public static void sweep(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof Mob mob) {
                        clearMob(mob, null, true);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearMob(Mob mob, UUID id, boolean sweepStale) {
        try {
            LivingEntity target = mob.getTarget();
            if (target != null && stale(target, id, sweepStale)) {
                mob.setTarget(null);
            }
            Brain<?> brain = mob.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                LivingEntity memory = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
                if (memory == null || stale(memory, id, sweepStale)) {
                    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                }
            }
            if (brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)) {
                UUID angry = brain.getMemory(MemoryModuleType.ANGRY_AT).orElse(null);
                if (angry != null && angry.equals(id)) {
                    brain.eraseMemory(MemoryModuleType.ANGRY_AT);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean stale(LivingEntity target, UUID id, boolean sweepStale) {
        if (target == null) {
            return true;
        }
        if (id != null && id.equals(target.getUUID())) {
            return true;
        }
        if (!sweepStale) {
            return false;
        }
        try {
            if (target.isRemoved()) {
                return true;
            }
            if (target.level() instanceof ServerLevel level && level.getEntity(target.getId()) != target) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
