package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 宿主 AbstractGolemEntity.onRemove 的副作用门。
 *
 * <p>AV/融合清除会直接写 removalReason、再调 onRemovedFromWorld/onRemove；
 * 我们的 setRemoved 门虽然没让实体真的消失，但宿主已经执行“forcefully removed”
 * 的 untrack/日志/状态修改，造成“实体还在、宿主以为它没了”的半移除。</p>
 *
 * <p>只在移除未被 RemovalGuard 放行时取消；正常回收/真死/卸载仍走原逻辑。</p>
 */
@Mixin(AbstractGolemEntity.class)
public abstract class AbstractGolemOnRemoveGuardMixin {

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void ga$guardOnRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        AbstractGolemEntity<?, ?> golem = (AbstractGolemEntity<?, ?>) (Object) this;
        boolean protectedEntity = golem.level().isClientSide
                ? Anchor.isProtectedEntityId(golem.getId()) || Anchor.isProtectedUuid(golem.getUUID())
                : ProtectedEntityPolicy.protects(golem);
        if (protectedEntity && !RemovalGuard.isAllowedLifecycle(golem)) {
            ci.cancel();
        }
    }
}
