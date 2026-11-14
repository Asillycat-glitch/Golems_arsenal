package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 每 tick 维持（对齐匠战妖域里 {@code tinkersinnovation} 的 {@code UndyingTraitModifier}
 * 和 {@code toaru.GungnirProtection.maintain} 的做法）。
 *
 * <p>之前的修复是“审计时对账”（20 tick 一次，hot 1 tick），留给深度攻击一整段窗口。
 * 这里每 5 tick 扫一遍已加载的受保护傀儡：只要账本说它该有多少血、而真实血缘低，
 * 立刻走一次审计（修复 + 上限守卫 + 死亡标志清理）。</p>
 *
 * <p>只在 ENFORCE、只在服务端、只对“有主且未被移除”的傀儡生效；正常受击窗口跳过。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class SanctuaryMaintain {

    private static int tick;

    private SanctuaryMaintain() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (tick++ % 5) != 0) {
            return;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            for (var level : server.getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                        continue;
                    }
                    if (golem.isRemoved() || golem.getOwnerUUID() == null) {
                        continue;
                    }
                    if (SanctuaryScopes.inHurt(golem) || SanctuaryScopes.inDeath()) {
                        continue;
                    }
                    float raw = DamageAdmission.rawHealth(golem);
                    float expected = HealthLedger.expectedOf(golem);
                    if (!Float.isFinite(raw) || !Float.isFinite(expected)) {
                        continue;
                    }
                    if (expected - raw > 0.5F) {
                        HealthLedger.auditNow(golem);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
