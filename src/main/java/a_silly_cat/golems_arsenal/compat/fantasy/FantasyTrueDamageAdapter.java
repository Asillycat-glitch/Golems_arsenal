package a_silly_cat.golems_arsenal.compat.fantasy;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import com.mega.uom.event.entity.CatchActuallyHurt0Event;
import com.mega.uom.util.entity.EntityASMUtil;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * fantasy_ending true-damage 适配器（只在 fantasy 加载时注册）。
 *
 * <p>当前只注册 {@code CatchActuallyHurt0Event.Pre}，不注册 {@code PrePre}：
 * 同一个 actuallyHurt 会先 PrePre、后 Pre，两个都注册会双重 cancel/re-hurt。</p>
 *
 * <p>true damage 策略还没有最终定；这里先只加诊断，不改变 cancel/rejected 行为。</p>
 */
public final class FantasyTrueDamageAdapter {

    private static final ThreadLocal<Boolean> ADAPTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FantasyTrueDamageAdapter() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(FantasyTrueDamageAdapter.class);
        Golems_arsenal.LOGGER.info("[DamageAdmission] fantasy true-damage adapter registered");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCatchPre(CatchActuallyHurt0Event.Pre event) {
        adapt(event);
    }

    /** 只读诊断：HIGHEST 阶段，看 admission_rehurt 是否一进来就被别的 listener 取消。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onAdmissionAttackHighest(LivingAttackEvent event) {
        logAttack("highest", event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
    public static void onAdmissionAttackHigh(LivingAttackEvent event) {
        logAttack("high", event);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    public static void onAdmissionAttackNormal(LivingAttackEvent event) {
        logAttack("normal", event);
    }

    @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
    public static void onAdmissionAttackLow(LivingAttackEvent event) {
        logAttack("low", event);
    }

    /** 只读诊断：看 admission_rehurt 是否在其它 listener 那里被取消。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onAdmissionAttackLowest(LivingAttackEvent event) {
        logAttack("lowest", event);
    }

    private static void logAttack(String stage, LivingAttackEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !DamageAdmission.isProtected(golem)
                || !event.getSource().getMsgId().equals("admission_rehurt")) {
            return;
        }
        Golems_arsenal.LOGGER.info(
                "[DamageAdmission] fantasy_attack stage={} source=admission_rehurt amount={} canceled={} "
                        + "invulnerableTime={} deadOrDying={}",
                stage, event.getAmount(), event.isCanceled(),
                golem.invulnerableTime, golem.isDeadOrDying());
    }

    /** 只读诊断：LivingHurtEvent 阶段的最终 amount / cancel。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onAdmissionHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !DamageAdmission.isProtected(golem)
                || !event.getSource().getMsgId().equals("admission_rehurt")) {
            return;
        }
        Golems_arsenal.LOGGER.info(
                "[DamageAdmission] fantasy_hurt source=admission_rehurt amount={} canceled={} "
                        + "invulnerableTime={} deadOrDying={}",
                event.getAmount(), event.isCanceled(), golem.invulnerableTime, golem.isDeadOrDying());
    }

    private static void adapt(CatchActuallyHurt0Event event) {
        LivingEntity entity = event.getEntity();
        if (!DamageAdmission.isProtected(entity)) {
            return;
        }
        DamageSource source = event.getSource();
        float amount = event.getAmount();
        boolean handled = DamageAdmission.isHandled(entity, source);

        DamageSource normal = null;
        try {
            normal = DamageAdmission.normalSource(entity, source);
        } catch (Throwable ignored) {
        }
        logBefore(entity, source, amount, normal, handled);

        cancel(event);
        try {
            EntityASMUtil.setHealthDelta(entity, 0.0F);
        } catch (Throwable ignored) {
        }
        if (!handled) {
            DamageAdmission.logRejected(entity, "true_damage",
                    "unhandled source=" + (source == null ? "none" : source.getMsgId())
                            + " amount=" + amount);
            return;
        }

        if (ADAPTING.get()) {
            // 重结算的 normal hurt 又意外走回 fantasy true damage 时，只取消，不递归。
            return;
        }
        if (!Float.isFinite(amount) || amount <= 0.0F) {
            return;
        }
        float current = entity.getHealth();
        if (!Float.isFinite(current) || current <= 0.0F) {
            return;
        }
        float cappedAmount = Math.min(amount, current);
        if (cappedAmount <= 0.0F) {
            return;
        }
        if (normal == null) {
            return;
        }

        ADAPTING.set(Boolean.TRUE);
        int oldInvulnerableTime = entity.invulnerableTime;
        boolean resetIframe = oldInvulnerableTime > 0;
        if (resetIframe) {
            entity.invulnerableTime = 0;
        }
        try {
            boolean hurt = entity.hurt(normal, cappedAmount);
            if (!hurt && resetIframe && entity.invulnerableTime == 0) {
                entity.invulnerableTime = oldInvulnerableTime;
            }
            DamageAdmission.logHandled(entity,
                    "source=" + (source == null ? "none" : source.getMsgId())
                            + " amount=" + amount
                            + " cappedAmount=" + cappedAmount
                            + " normalSource=" + normal.getMsgId()
                            + " iframeReset=" + oldInvulnerableTime + "->0"
                            + " applied=" + hurt
                            + " postHealth=" + entity.getHealth()
                            + " postInvulnerableTime=" + entity.invulnerableTime);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[DamageAdmission] fantasy adapter re-hurt failed: {}", t.toString());
        } finally {
            ADAPTING.set(Boolean.FALSE);
        }
    }

    private static void logBefore(LivingEntity entity, DamageSource source, float amount,
                                  DamageSource normal, boolean handled) {
        boolean invulnerable = entity.isInvulnerable();
        boolean invulnerableTo = normal != null && entity.isInvulnerableTo(normal);
        Boolean guardedExtra = null;
        if (entity instanceof GuardedEntity guarded && normal != null) {
            try {
                guardedExtra = guarded.isInvulnerableToExtra(normal);
            } catch (Throwable ignored) {
            }
        }
        Golems_arsenal.LOGGER.info(
                "[DamageAdmission] fantasy_pre stage=Pre source={} amount={} handled={} "
                        + "invulnerable={} invulnerableTime={} deadOrDying={} health={} "
                        + "invulnerableTo={} guardedExtra={}",
                source == null ? "none" : source.getMsgId(), amount, handled,
                invulnerable, entity.invulnerableTime, entity.isDeadOrDying(), entity.getHealth(),
                invulnerableTo, guardedExtra);
    }

    private static void cancel(CatchActuallyHurt0Event event) {
        try {
            if (event.isCancelable()) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
        try {
            event.setAmount(0.0F);
        } catch (Throwable ignored) {
        }
    }
}
