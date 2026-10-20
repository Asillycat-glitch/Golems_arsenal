package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 火箭附近的**镜头震动** —— 点火和升空时地面在抖。
 *
 * <p>做法是改相机的 <b>roll（滚转）</b>与 yaw：滚转是"画面倾斜"的那种抖动，
 * 对火箭这种地面震动最贴切（灾变那类模组也是这个路子）。强度由
 * "火箭状态 × 距离衰减"决定：
 *
 * <pre>
 *   倒计时  基础强度 0.25   —— 轻微预震，像引擎在预热点火
 *   升空    基础强度 1.00   —— 全力，且随上升高度略微衰减（越远越弱）
 *   距离    8 格内满强度，32 格外归零，中间线性过渡
 * </pre>
 *
 * <p>用正弦叠加而不是纯随机：纯随机会让画面"抽搐"，正弦是"震动"。
 * 三个频率错开（不互成整数倍），观感更自然。
 *
 * <p>只看**客户端已加载**的火箭实体，不需要网络包——服务端本来就在同步这些实体的位置与状态。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class RocketShakeHandler {

    /** 满强度距离（格）。 */
    private static final double FULL_STRENGTH_DISTANCE = 8.0;
    /** 归零距离（格）。 */
    private static final double ZERO_DISTANCE = 32.0;
    /**
     * 倒计时时的强度系数。
     *
     * <p><b>用户明确要求倒计时（右键后的 10 秒）不震</b>——震动只属于"真的在上升"那一段，
     * 所以这里是 0。之前给 0.8 是我自己加的"预震"想法，不是他要的。
     */
    private static final float COUNTDOWN_SCALE = 0.0F;
    /** 最大滚转角度（度）。 */
    private static final float MAX_ROLL = 2.2F;
    /** 最大偏航抖动（度）。 */
    private static final float MAX_YAW = 0.8F;

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        float strength = shakeStrength(mc);
        if (strength <= 0.0F) {
            return;
        }

        // 用真实时间（毫秒）驱动，与 tick 无关，掉帧时也不会变慢
        double t = (System.currentTimeMillis() % 100000L) / 1000.0;
        float roll = (float) (Math.sin(t * 37.0) * 0.6 + Math.sin(t * 53.0) * 0.4);
        float yaw = (float) (Math.sin(t * 41.0) * 0.5 + Math.sin(t * 61.0) * 0.5);

        event.setRoll(event.getRoll() + roll * MAX_ROLL * strength);
        event.setYaw(event.getYaw() + yaw * MAX_YAW * strength);
    }

    /** 算出当前该有多强的震动（0 = 不震）。 */
    private static float shakeStrength(Minecraft mc) {
        AABB area = mc.player.getBoundingBox().inflate(ZERO_DISTANCE);
        float best = 0.0F;
        for (RocketEntity rocket : mc.level.getEntitiesOfClass(RocketEntity.class, area)) {
            float base = switch (rocket.state()) {
                case RocketEntity.STATE_COUNTDOWN -> COUNTDOWN_SCALE;
                case RocketEntity.STATE_ASCENT -> 1.0F;
                default -> 0.0F;   // 待发的火箭不该震
            };
            if (base <= 0.0F) {
                continue;
            }
            double dist = mc.player.distanceTo(rocket);
            if (dist >= ZERO_DISTANCE) {
                continue;
            }
            float falloff = dist <= FULL_STRENGTH_DISTANCE ? 1.0F
                    : (float) ((ZERO_DISTANCE - dist) / (ZERO_DISTANCE - FULL_STRENGTH_DISTANCE));
            // 升空越高越弱：飞远了就不该再震
            if (rocket.state() == RocketEntity.STATE_ASCENT) {
                float heightFade = (float) Math.max(0.0, 1.0 - rocket.getY() / 120.0);
                falloff *= heightFade;
            }
            best = Math.max(best, base * falloff);
        }
        return best;
    }

    private RocketShakeHandler() {
    }
}
