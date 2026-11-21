package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * H0/H1 影子层：soulId 稳定，projection UUID 只作为当前实体映射。
 *
 * <p>目前只观察和提供 expected health，不接管移除、重建或客户端 tracking。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class SoulRegistry {

    private static final Map<UUID, GolemSoul> SOULS = new HashMap<>();
    /** projection entity UUID -> soulId */
    private static final Map<UUID, UUID> PROJECTIONS = new HashMap<>();

    private SoulRegistry() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary.protectedGolem(golem)) {
            return;
        }
        // 不要用 raw getHealth() 初始化 Soul：AV 可以先写 0 再让实体 join。
        // 账本高于 raw 时以账本为准；只有账本不可用时才退回 raw。
        float raw = golem.getHealth();
        float ledger = HealthLedger.expectedOf(golem);
        float hp = Float.isFinite(ledger) && ledger > 1.0F ? Math.max(ledger, raw) : raw;
        ensure(golem, hp, golem.getMaxHealth());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // H0：不在 leave 时拆 projection 映射。区块卸载/敌对移除都会触发 leave；
        // 现在拆掉会让同一只傀儡下次 ensure 出第二个 soulId。
        // 真正的释放由后续 Soul 生命周期/重建逻辑处理。
    }

    /** 没有 Soul 时创建一个；已有则同步当前观察值。 */
    public static synchronized GolemSoul ensure(AbstractGolemEntity<?, ?> golem,
                                                float health, float maxHealth) {
        if (golem == null || golem.level().isClientSide() || golem.getOwnerUUID() == null) {
            return null;
        }
        if (!a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary.protectedGolem(golem)) {
            return null;
        }
        UUID projection = golem.getUUID();
        UUID tagged = null;
        try {
            tagged = golem.getPersistentData().getUUID("ga$soul");
        } catch (Throwable ignored) {
        }
        UUID soulId;
        if (tagged != null && SOULS.containsKey(tagged)) {
            soulId = tagged;
            PROJECTIONS.put(projection, soulId);
        } else {
            soulId = PROJECTIONS.computeIfAbsent(projection, id -> UUID.randomUUID());
        }
        GolemSoul soul = SOULS.get(soulId);
        if (soul == null) {
            soul = new GolemSoul(soulId, golem.getOwnerUUID(), golem.level().dimension(),
                    golem.blockPosition(), health, maxHealth);
            SOULS.put(soulId, soul);
        } else {
            // ensure() 只允许抬高；真正被准入的伤害由 observe() 单独下调。
            float safe = Float.isFinite(health) ? Math.max(health, soul.health()) : soul.health();
            soul.observe(safe, maxHealth, golem.blockPosition(), golem.level().dimension(), false);
        }
        if (soul.trustedNbt == null && Float.isFinite(health) && health > 1.0F) {
            capture(golem, soul);
        }
        SoulAuthority.publish(soul);
        return soul;
    }

    private static void capture(AbstractGolemEntity<?, ?> golem, GolemSoul soul) {
        try {
            CompoundTag tag = new CompoundTag();
            golem.saveWithoutId(tag);
            tag.putString("id", EntityType.getKey(golem.getType()).toString());
            tag.putUUID("ga$soul", soul.soulId);
            try {
                golem.getPersistentData().putUUID("ga$soul", soul.soulId);
            } catch (Throwable ignored) {
            }
            soul.captureNbt(tag);
            SoulAuthority.publish(soul);
        } catch (Throwable ignored) {
        }
    }

    /** 在真正死亡/掉落前抓一次完整 NBT；调用方负责确认这不是被攻击方写脏的窗口。 */
    public static synchronized void captureNow(AbstractGolemEntity<?, ?> golem) {
        if (golem == null || golem.level().isClientSide() || golem.getOwnerUUID() == null) {
            return;
        }
        GolemSoul soul = ensure(golem, golem.getHealth(), golem.getMaxHealth());
        if (soul == null) {
            return;
        }
        if (!Float.isFinite(golem.getHealth()) || golem.getHealth() <= 0.0F) {
            return;
        }
        capture(golem, soul);
    }

    /** 重建用：按旧 projection UUID 找 trusted NBT。 */
    public static synchronized CompoundTag nbtForProjection(UUID projection) {
        UUID soulId = PROJECTIONS.get(projection);
        GolemSoul soul = soulId == null ? null : SOULS.get(soulId);
        return soul == null ? null : soul.nbt();
    }

    /** 重建用：按旧 projection UUID 找 Soul 血量。 */
    public static synchronized float healthForProjection(UUID projection) {
        UUID soulId = PROJECTIONS.get(projection);
        GolemSoul soul = soulId == null ? null : SOULS.get(soulId);
        return soul == null ? Float.NaN : soul.health();
    }

    public static synchronized boolean hasProjection(UUID projection) {
        return projection != null && PROJECTIONS.containsKey(projection);
    }

    public static synchronized GolemSoul soulFor(AbstractGolemEntity<?, ?> golem) {
        if (golem == null || golem.level().isClientSide()) {
            return null;
        }
        UUID soulId = PROJECTIONS.get(golem.getUUID());
        return soulId == null ? null : SOULS.get(soulId);
    }

    public static synchronized void observe(AbstractGolemEntity<?, ?> golem,
                                            float health, float maxHealth, boolean died) {
        GolemSoul soul = ensure(golem, health, maxHealth);
        if (soul == null) {
            return;
        }
        soul.observe(health, maxHealth, golem.blockPosition(), golem.level().dimension(), died);
        SoulAuthority.publish(soul);
    }

    public static synchronized void rotateProjection(UUID oldProjection, UUID freshProjection) {
        if (oldProjection == null || freshProjection == null
                || oldProjection.equals(freshProjection)) {
            return;
        }
        UUID soulId = PROJECTIONS.remove(oldProjection);
        if (soulId != null) {
            PROJECTIONS.put(freshProjection, soulId);
        }
    }

    public static synchronized int size() {
        return SOULS.size();
    }

    /**
     * 同一 UUID 出现两个投影对象时，只保留当前 level.getEntity(id) 认可的那个。
     * 只处理有主傀儡，且删除动作走 rebuild scope，不会误伤正常实体。
     */
    public static synchronized int cleanupDuplicates(net.minecraft.server.level.ServerLevel level) {
        if (level == null) {
            return 0;
        }
        Map<UUID, net.minecraft.world.entity.Entity> seen = new HashMap<>();
        int removed = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)
                    || golem.getOwnerUUID() == null) {
                continue;
            }
            net.minecraft.world.entity.Entity previous = seen.putIfAbsent(golem.getUUID(), golem);
            if (previous == null || previous == golem) {
                continue;
            }
            net.minecraft.world.entity.Entity keep = level.getEntity(golem.getId()) == golem ? golem : previous;
            net.minecraft.world.entity.Entity drop = keep == golem ? previous : golem;
            try {
                ProtectedEntityPolicy.runRebuild(() -> drop.discard());
                removed++;
                Golems_arsenal.LOGGER.warn("[Sanctuary] duplicate_projection_removed uuid={} keep={} drop={}",
                        golem.getUUID(), keep.getId(), drop.getId());
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    public static synchronized void clear() {
        SOULS.clear();
        PROJECTIONS.clear();
    }

    /** 给 /ga 状态用；不修改任何状态。 */
    public static synchronized String statusLine() {
        return "souls=" + SOULS.size() + " projections=" + PROJECTIONS.size()
                + " authority=" + SoulAuthority.size();
    }
}
