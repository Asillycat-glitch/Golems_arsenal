package a_silly_cat.golems_arsenal.guard.sanctuary;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.phys.AABB;

/**
 * 实体可见/碰撞面的服务端修复：AV 会把位置、invisible 位或包围盒改成
 * "看起来不存在"；这里把受保护傀儡拉回可见、可碰撞、有合法 AABB 的状态。
 */
public final class VisibilityGuard {

    private VisibilityGuard() {
    }

    public static void repair(AbstractGolemEntity<?, ?> golem) {
        if (golem == null) {
            return;
        }
        try {
            golem.noPhysics = false;
            if (golem.isInvisible()) {
                golem.setInvisible(false);
            }
            AABB box = golem.getBoundingBox();
            if (!valid(box)) {
                golem.refreshDimensions();
                box = golem.getBoundingBox();
                if (!valid(box)) {
                    golem.setBoundingBox(golem.getDimensions(golem.getPose()).makeBoundingBox(golem.position()));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean valid(AABB box) {
        if (box == null) {
            return false;
        }
        double sx = box.getXsize();
        double sy = box.getYsize();
        double sz = box.getZsize();
        if (!Double.isFinite(sx) || !Double.isFinite(sy) || !Double.isFinite(sz)) {
            return false;
        }
        // 0 尺寸 = 不可选中/不可碰撞；异常大 = 已经被写成垃圾数据。
        return sx > 1.0E-4D && sy > 1.0E-4D && sz > 1.0E-4D
                && sx < 1.0E4D && sy < 1.0E4D && sz < 1.0E4D;
    }
}
