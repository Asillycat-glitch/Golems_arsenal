package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AV EntityDeath 会把 isAddedToWorld 读成 false；有些渲染/跟踪路径会据此跳过实体。
 * 受保护实体在真实移除之前一律回答已加入世界。
 */
@Mixin(value = Entity.class, priority = 2000)
public abstract class EntityAddedToWorldGuardMixin {

    @Inject(method = "isAddedToWorld", at = @At("HEAD"), cancellable = true)
    private void ga$isAddedToWorld(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide) {
            if (Anchor.isProtectedEntityId(self.getId()) || Anchor.isProtectedUuid(self.getUUID())) {
                cir.setReturnValue(true);
            }
        } else if (ProtectedEntityPolicy.protects(self)) {
            cir.setReturnValue(true);
        }
    }
}
