package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 守卫存活自检（canary）。
 *
 * <p>2026-11-07 起，对手（ArcaneVortex 0.7.10）自带 native agent + 类转换器，可以在运行时
 * retransform 掉我们的 mixin。被改写之后我们的日志会"安静下来"——看起来像修好了，其实是守卫没了。</p>
 *
 * <p>这里在启动后和每 600 tick 检查一次：几个关键 mixin 的 handler 方法是否还挂在目标类上。
 * 缺任何一个就打 {@code [Sanctuary] GUARD_TAMPERED missing=...}，并且只打一次（直到恢复）。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GuardCanary {

    private static final String[][] EXPECTED = {
            {"net.minecraft.world.entity.LivingEntity", "ga$admitSetHealth"},
            {"net.minecraft.world.entity.LivingEntity", "ga$openHurtWindow"},
            {"net.minecraft.world.entity.LivingEntity", "ga$guardActualHurt"},
            {"net.minecraft.world.entity.LivingEntity", "ga$guardDeathLoot"},
            {"net.minecraft.world.entity.LivingEntity", "ga$refreshEquipmentNbt"},
            {"net.minecraft.network.syncher.SynchedEntityData", "ga$admitData"},
            {"net.minecraft.world.entity.Entity", "ga$guardRemoval"},
            {"net.minecraft.world.entity.Entity", "ga$guardIsRemoved"},
            {"net.minecraft.world.entity.Entity", "ga$guardLevelCallback"},
            {"net.minecraft.world.level.entity.EntityLookup", "ga$guardLookupRemove"},
            {"net.minecraft.world.level.entity.EntityTickList", "ga$guardTickListRemove"},
            {"net.minecraft.util.ClassInstanceMultiMap", "ga$guardIndexRemove"},
            {"net.minecraft.world.entity.ai.attributes.AttributeInstance", "ga$guardAdd"},
            {"dev.xkmc.modulargolems.content.entity.common.GuardedEntity", "ga$correctReportedHealth"},
            {"dev.xkmc.modulargolems.content.entity.common.GuardedEntity", "ga$guardGuardedActualHurt"},
            {"dev.xkmc.modulargolems.content.entity.common.GuardedEntity", "ga$admitGuardedSetHealth"},
            {"dev.xkmc.modulargolems.content.entity.common.GuardedEntity", "ga$guardGuardedKill"}
    };

    private static String lastReport = "<init>";
    private static int tick;

    private GuardCanary() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (tick++ % 600 != 0) {
            return;
        }
        verify();
    }

    /** 立刻自检一次（启动时也会调用）。返回缺失项描述，空串表示完整。 */
    public static String verify() {
        StringBuilder missing = new StringBuilder();
        for (String[] row : EXPECTED) {
            if (!hasHandler(row[0], row[1])) {
                if (missing.length() > 0) {
                    missing.append(',');
                }
                missing.append(row[0]).append('#').append(row[1]);
            }
        }
        String report = missing.toString();
        if (!report.equals(lastReport)) {
            lastReport = report;
            if (report.isEmpty()) {
                Golems_arsenal.LOGGER.info("[Sanctuary] guard canary: all {} hooks present", EXPECTED.length);
            } else {
                Golems_arsenal.LOGGER.warn(
                        "[Sanctuary] GUARD_TAMPERED missing={} (有类转换器改写了我们的 mixin，守卫已失效)",
                        report);
            }
        }
        return report;
    }

    private static boolean hasHandler(String className, String handlerName) {
        try {
            Class<?> cls = Class.forName(className, false, GuardCanary.class.getClassLoader());
            for (Method method : cls.getDeclaredMethods()) {
                // Mixin 生成的名字形如 handler$efh000$ga$admitSetHealth
                if (method.getName().contains(handlerName)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            // 类还没加载：不算篡改（第一次 tick 后目标类基本都已加载）
            return true;
        }
    }

    public static String statusLine() {
        return "canary=" + (lastReport.isEmpty() || "<init>".equals(lastReport)
                ? "ok" : "tampered(" + lastReport + ")");
    }
}
