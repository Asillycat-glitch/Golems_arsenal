package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用终态：清除类 mod 在拆索引前常先 invalidateCaps()，把能量/升级/能力全部失效。
 * 受保护实体在正常生命周期之外不允许被 invalidate。
 */
@Mixin(Entity.class)
public abstract class EntityInvalidateCapsGuardMixin {

    @Inject(method = "m_142036_", at = @At("HEAD"), cancellable = true)
    private void ga$invalidateCaps(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (ProtectedEntityPolicy.protects(self)
                && !RemovalGuard.isAllowedLifecycle(self)) {
            ci.cancel();
        }
    }
}
