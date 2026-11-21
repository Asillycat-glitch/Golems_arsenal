package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端/服务端可见性、拾取与碰撞的读侧门。
 * AV 的死亡渲染/幽灵路径会通过 invisible 位、isPickable 或包围盒让实体"看起来不存在"；
 * 受保护实体这些读侧一律回答"可见、可交互、可碰撞"。
 */
@Mixin(value = Entity.class, priority = Integer.MAX_VALUE)
public abstract class EntityVisibilityGuardMixin {

    private static boolean ga$protected(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.level().isClientSide) {
            return Anchor.isProtectedEntityId(entity.getId()) || Anchor.isProtectedUuid(entity.getUUID());
        }
        return ProtectedEntityPolicy.protects(entity) || Anchor.isProtectedUuid(entity.getUUID());
    }

    @Inject(method = "m_8119_", at = @At("TAIL"))
    private void ga$tick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!ga$protected(self)) {
            return;
        }
        try {
            if (self.noPhysics) {
                self.noPhysics = false;
            }
            if (self.isInvisible()) {
                self.setInvisible(false);
            }
            net.minecraft.world.phys.AABB box = self.getBoundingBox();
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

    private static boolean ga$valid(net.minecraft.world.phys.AABB box) {
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

    @Inject(method = "m_20145_", at = @At("HEAD"), cancellable = true)
    private void ga$isInvisible(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "m_20177_", at = @At("HEAD"), cancellable = true)
    private void ga$isInvisibleTo(Player player, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "m_6087_", at = @At("HEAD"), cancellable = true)
    private void ga$isPickable(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_5829_", at = @At("HEAD"), cancellable = true)
    private void ga$canBeCollidedWith(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_6094_", at = @At("HEAD"), cancellable = true)
    private void ga$isPushable(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canUpdate()Z", at = @At("HEAD"), cancellable = true)
    private void ga$canUpdate(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ga$protected(self)) {
            cir.setReturnValue(true);
        }
    }
}
