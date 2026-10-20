package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 【临时】三连击的手感调试触发器：每隔 {@link #INTERVAL_TICKS} tick，让离玩家最近的大型傀儡
 * <b>单独播一段</b>，并按 ③ → ① → ② 轮换。
 * <p>
 * 之所以一段一段地放：每一段的起始姿态、角度、缩放都要能单独调，
 * 三段连播时前一段的拖影会盖住后一段的起手，看不出问题在哪。
 * <p>
 * 它只是为了让"刀光那一层"能独立看到、不用等真正的战斗触发。等正式方案落地
 * （服务端近战命中 → 网络包带上"第几段 + 起始 tick" → {@link ClientComboData#start(int, int)}），
 * 正式触发已经接上了 —— 所以现在默认 {@link #ENABLED} = false，免得它每 50 tick 自己播一段、
 * 跟真实触发打架。想单独盯某一段的手感时把它打开、并用 {@link #ONLY_STAGE} 固定那一段。
 * <p>
 * （原本计划直接删掉这个类，但沙箱拒了对该文件的删除，于是改成默认关闭的开关留存。）
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class ComboTestTrigger {

    /** 调试用总开关：默认关闭。 */
    private static final boolean ENABLED = false;

    /** 每隔这么多 tick 自动播一次。想看得更勤就调小。 */
    private static final int INTERVAL_TICKS = 50;

    /**
     * 只播某一段（0 = ③横扫，1 = ①上挑，2 = ②下劈）；
     * 设成 -1 = 三段轮换。单独调某一段的手感时，把它固定成那一段最省事。
     */
    private static final int ONLY_STAGE = -1;

    /** 只挑这个距离内的傀儡，免得远处不知道哪只在挥。 */
    private static final double MAX_DISTANCE_SQR = 48.0 * 48.0;

    private static int nextStage;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        long now = mc.level.getGameTime();
        if (now % INTERVAL_TICKS != 0L) {
            return;
        }
        // 顺手清掉播完/傀儡已经不在的残留状态（渲染层没机会自己清的那种）
        ClientComboData.prune(now);

        MetalGolemEntity nearest = null;
        double best = MAX_DISTANCE_SQR;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof MetalGolemEntity golem) {
                double distance = golem.distanceToSqr(mc.player);
                if (distance < best) {
                    best = distance;
                    nearest = golem;
                }
            }
        }
        if (nearest == null) {
            return;
        }
        int stage = ONLY_STAGE >= 0
                ? Math.min(ONLY_STAGE, GolemComboStages.stageCount() - 1)
                : nextStage++ % GolemComboStages.stageCount();
        ClientComboData.start(nearest.getId(), stage);
    }

    private ComboTestTrigger() {
    }
}
