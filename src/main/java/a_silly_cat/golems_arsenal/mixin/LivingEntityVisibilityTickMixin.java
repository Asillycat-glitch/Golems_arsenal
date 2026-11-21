package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity 自己重写了 tick；Entity 上的 tick 注入不一定走到。
 * 在 LivingEntity.tick 尾部再补一次可见/碰撞/包围盒修复。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityVisibilityTickMixin {

    @Inject(method = "m_8119_", at = @At("TAIL"))
    private void ga$repair(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        try {
            boolean protectedEntity = self.level().isClientSide
                    ? Anchor.isProtectedEntityId(self.getId()) || Anchor.isProtectedUuid(self.getUUID())
                    : ProtectedEntityPolicy.protects(self);
            if (!protectedEntity) {
                return;
            }
            if (self.noPhysics) {
                self.noPhysics = false;
            }
            if (self.isInvisible()) {
                self.setInvisible(false);
            }
            AABB box = self.getBoundingBox();
            if (!ga$valid(box)) {
                self.refreshDimensions();
                box = self.getBoundingBox();
                if (!ga$valid(box)) {
                    self.setBoundingBox(self.getDimensions(self.getPose()).makeBoundingBox(self.position()));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean ga$valid(AABB box) {
        if (box == null) {
            return false;
        }
        double sx = box.getXsize();
        double sy = box.getYsize();
        double sz = box.getZsize();
        return Double.isFinite(sx) && Double.isFinite(sy) && Double.isFinite(sz)
                && sx > 1.0E-4D && sy > 1.0E-4D && sz > 1.0E-4D
                && sx < 1.0E4D && sy < 1.0E4D && sz < 1.0E4D;
    }
}
