package a_silly_cat.golems_arsenal.compat.fantasy;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * fantasy_ending 真实血量兼容（正式内容，不是 testbench）。
 *
 * <p>fantasy_ending 的 {@code EntityActuallyHurt} 会绕过 Modular Golems 的
 * {@code setGuardedDataImpl}，直接通过 VarHandle 把真实血量写进
 * {@code DATA_HEALTH_ID}；但 {@code GuardedEntity.guardedData} 仍停在旧值。
 * 结果：</p>
 *
 * <ul>
 *   <li>{@code GuardedEntity.getHealth()} 返回 {@code max(真实血量, guardedData)}，看起来还是满血；</li>
 *   <li>{@code GuardedEntity.heal()} 计算 {@code maxHealth - getHealth()}，结果是 0，喂食/回血全变 no-op；</li>
 *   <li>{@code validateGuardedData()} 每 tick 想把真实血量顶回 guardedData，但被 fantasy 的真实血量层钳住。</li>
 * </ul>
 *
 * <p>这里在真实血量低于 guardedData 持续 5 tick 时，把 guardedData 向下同步到
 * {@code DATA_HEALTH_ID}。同步后 {@code getHealth()} 与 heal 都按真实血量工作，
 * 同时保留 fantasy 已经算好的伤害，不会把伤害“治回去”。</p>
 *
 * <p>只处理玩家拥有的 {@link AbstractGolemEntity}，只在 fantasy_ending 加载时生效，
 * 并可用 {@code compat.fantasy_true_health_sync=false} 关闭（P2 guard 测试建议关闭，
 * 否则会与“把 guardedData 保持在 expected”的 Guard 对打）。</p>
 */
@Mod.EventBusSubscriber(modid = "golems_arsenal")
public final class FantasyTrueHealthCompat {

    private static final float EPS = 1.0E-3F;
    private static final int CONFIRM_TICKS = 5;

    private static final Map<UUID, Integer> DIVERGENT_TICKS = new HashMap<>();

    private static Field healthField;
    private static EntityDataAccessor<Float> healthAccessor;
    private static Boolean fantasyLoaded;

    private FantasyTrueHealthCompat() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (entity.level().isClientSide || golem.getOwnerUUID() == null) {
            return;
        }
        if (!isFantasyLoaded() || !isEnabled()) {
            return;
        }
        UUID uuid = entity.getUUID();
        if (entity.isRemoved()) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }

        float raw = readRawHealth(entity);
        if (!Float.isFinite(raw)) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }
        if (raw <= 0.0F) {
            // 负数/0 血不夹、不回收：梦终的 true damage 应该真的打伤傀儡，
            // 这里只处理“正数真实血量低于 guardedData”的 heal/getHealth 同步。
            DIVERGENT_TICKS.remove(uuid);
            return;
        }

        if (!entity.isAlive()) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }

        float guarded = entity.getHealth();
        if (!Float.isFinite(guarded)) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }
        // raw 已经贴住 max 时不处理：这可能是 max 被压（HEALTH_SEAL / 伪 1 血），
        // 把 guardedData 降到 raw 只会把“压 max”合法化，反而让 Guard 没法修。
        float max = entity.getMaxHealth();
        if (!Float.isFinite(max) || max <= raw + EPS) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }
        if (guarded <= raw + EPS) {
            DIVERGENT_TICKS.remove(uuid);
            return;
        }

        int ticks = DIVERGENT_TICKS.merge(uuid, 1, Integer::sum);
        if (ticks < CONFIRM_TICKS) {
            return;
        }
        if (entity instanceof GuardedEntity guardedEntity) {
            // sync=true：让客户端 HUD/guarded.getHealth 也跟着降到真实血量。
            // 这是兼容性修正、不是伤害，显式声明成内部写，别被写入口当成外来直写拒绝。
            a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes.runInternal(
                    () -> guardedEntity.setGuardedDataImpl(raw, true, false));
            DIVERGENT_TICKS.remove(uuid);
            Golems_arsenal.LOGGER.info(
                    "[Compat] fantasy true-health sync: uuid={} raw={} guarded={}",
                    uuid, raw, guarded);
        }
    }

    private static boolean isEnabled() {
        try {
            return Config.FANTASY_TRUE_HEALTH_SYNC.get();
        } catch (Throwable ignored) {
            // 配置尚未加载时保持关闭，避免启动早期抛异常。
            return false;
        }
    }

    private static boolean isFantasyLoaded() {
        Boolean cached = fantasyLoaded;
        if (cached != null) {
            return cached;
        }
        boolean loaded = false;
        try {
            loaded = ModList.get().isLoaded("fantasy_ending");
        } catch (Throwable ignored) {
            loaded = false;
        }
        fantasyLoaded = loaded;
        return loaded;
    }

    /** 读 DATA_HEALTH_ID 原值，不经过 GuardedEntity.getHealth() 的 max。 */
    private static float readRawHealth(LivingEntity entity) {
        try {
            EntityDataAccessor<Float> accessor = healthAccessor;
            if (accessor == null) {
                Field field = healthField;
                if (field == null) {
                    field = ObfuscationReflectionHelper.findField(LivingEntity.class, "f_20961_");
                    field.setAccessible(true);
                    healthField = field;
                }
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Float> resolved = (EntityDataAccessor<Float>) field.get(null);
                accessor = resolved;
                healthAccessor = resolved;
            }
            if (accessor == null) {
                return Float.NaN;
            }
            return entity.getEntityData().get(accessor);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }
}
