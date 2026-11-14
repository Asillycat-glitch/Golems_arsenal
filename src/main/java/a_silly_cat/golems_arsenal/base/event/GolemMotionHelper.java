package a_silly_cat.golems_arsenal.base.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 强制位移的安全落点工具。
 * <p>
 * 凤凰天驱、狮子斩这类演出直接 {@code setPos}，不经过原版碰撞解算；一旦目标点落在墙/天花板里，
 * 傀儡就会卡住。这里统一做四件事：判定某个脚底位置能否放下碰撞箱、沿线段找最后一个合法点、
 * 求某个方向/高度上的可用空间、以及从已经卡住的位置挤到最近的空位。
 * <p>
 * 只用于服务端；客户端侧的 {@code level.noCollision} 没有意义，直接放行。
 */
public final class GolemMotionHelper {

    /** 采样步长（格）。0.25 足够细，又不会让每 tick 的检测次数失控。 */
    private static final double STEP = 0.25;
    /** 脱困搜索半径（格）。 */
    private static final double UNSTICK_RADIUS = 2.0;

    private GolemMotionHelper() {
    }

    /** 脚底放在 {@code feet} 时，傀儡的碰撞箱是否不与方块相交。 */
    public static boolean fits(Entity entity, Vec3 feet) {
        if (entity.level().isClientSide) {
            return true;
        }
        return entity.level().noCollision(entity, boxAt(entity, feet));
    }

    /** 把实体当前的碰撞箱平移到 {@code feet}（脚底坐标）。 */
    public static AABB boxAt(Entity entity, Vec3 feet) {
        return entity.getBoundingBox().move(feet.subtract(entity.position()));
    }

    /**
     * 从 {@code from} 向 {@code to} 走，返回撞到东西前的最后一个合法位置。
     * <p>
     * 起点已经卡住时改为就近脱困，而不是沿线段继续往前找 ——
     * 后者会直接穿到墙的另一侧，反而更糟。
     */
    public static Vec3 clamp(Entity entity, Vec3 from, Vec3 to) {
        if (!fits(entity, from)) {
            return unstick(entity, from);
        }
        double length = from.distanceTo(to);
        if (length < 1.0E-6) {
            return from;
        }
        int steps = Math.max(1, (int) Math.ceil(length / STEP));
        Vec3 last = from;
        for (int i = 1; i <= steps; i++) {
            Vec3 point = from.lerp(to, (double) i / steps);
            if (!fits(entity, point)) {
                return last;
            }
            last = point;
        }
        return last;
    }

    /**
     * 从 {@code from} 沿水平单位方向走，返回在高度 {@code y} 上最后一个合法的水平距离。
     * <p>
     * 用于"落点/后撤点"的预计算：先把整条水平路线扫一遍，撞墙就停在墙前，
     * 而不是先算一个墙后的点、再靠每 tick 的 {@link #clamp} 硬拽回来。
     *
     * @param max 期望走的最大距离（格）
     */
    public static double clearDistance(Entity entity, Vec3 from, double dirX, double dirZ,
                                       double max, double y) {
        if (max <= 0.0 || !fits(entity, new Vec3(from.x, y, from.z))) {
            return 0.0;
        }
        double last = 0.0;
        int steps = (int) Math.ceil(max / STEP);
        for (int i = 1; i <= steps; i++) {
            double distance = Math.min(max, i * STEP);
            Vec3 point = new Vec3(from.x + dirX * distance, y, from.z + dirZ * distance);
            if (!fits(entity, point)) {
                return last;
            }
            last = distance;
        }
        return last;
    }

    /** 在固定的 x/z 上，从 {@code fromY} 往上找最后一个能放下碰撞箱的脚底高度。 */
    public static double clearHeight(Entity entity, double x, double z, double fromY, double toY) {
        if (toY <= fromY || !fits(entity, new Vec3(x, fromY, z))) {
            return fromY;
        }
        double last = fromY;
        int steps = (int) Math.ceil((toY - fromY) / STEP);
        for (int i = 1; i <= steps; i++) {
            double y = fromY + Math.min(toY - fromY, i * STEP);
            if (!fits(entity, new Vec3(x, y, z))) {
                return last;
            }
            last = y;
        }
        return last;
    }

    /**
     * 就近找一个能放下碰撞箱的位置：先绕水平八方向扩圈，再尝试上下。
     * 找不到时原样返回（宁可不动，也不乱飞）。
     */
    public static Vec3 unstick(Entity entity, Vec3 at) {
        if (fits(entity, at)) {
            return at;
        }
        for (double radius = STEP; radius <= UNSTICK_RADIUS; radius += STEP) {
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 0.25 * i;
                Vec3 point = at.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
                if (fits(entity, point)) {
                    return point;
                }
            }
            Vec3 up = at.add(0.0, radius, 0.0);
            if (fits(entity, up)) {
                return up;
            }
            Vec3 down = at.add(0.0, -radius, 0.0);
            if (fits(entity, down)) {
                return down;
            }
        }
        return at;
    }
}
