package a_silly_cat.golems_arsenal.testbench;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * P0 血量观测的 Forge 接线（PRIVATE TESTBENCH）。
 *
 * <p>全部用 {@link EventPriority#LOWEST} + {@code receiveCanceled = true}：这样读到的是
 * 其它监听器（包括 l2）都处理完之后的 amount/取消状态，且被取消的命中也能被记下来。
 * 这里不调用任何 setter，只读。</p>
 */
@Mod.EventBusSubscriber(modid = "golems_arsenal")
public final class HealthTraceEvents {

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingAttack(LivingAttackEvent event) {
        HealthTrace.onLivingAttack(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurt(LivingHurtEvent event) {
        HealthTrace.onLivingHurt(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamage(LivingDamageEvent event) {
        HealthTrace.onLivingDamage(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHeal(LivingHealEvent event) {
        HealthTrace.onLivingHeal(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        HealthTrace.onLivingDeath(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            HealthTrace.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        HealthTrace.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HealthTrace.onServerStopping(event.getServer());
    }

    private HealthTraceEvents() {
    }
}
