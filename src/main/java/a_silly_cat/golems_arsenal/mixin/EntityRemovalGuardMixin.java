package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除守卫入口：{@code Entity#setRemoved(RemovalReason)}。
 *
 * <p>{@code remove(reason)} / {@code discard()} 最终都走到这里，所以按 reason 判一次就够。
 * 真正的口径在 {@link RemovalGuard}：{@code DISCARDED}/卸载放行，
 * 只有“活体被 {@code KILLED}”才是要拦的那一种。</p>
 */
@Mixin(Entity.class)
public abstract class EntityRemovalGuardMixin {

    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void ga$guardRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!RemovalGuard.allow((Entity) (Object) this, reason)) {
            ci.cancel();
        }
    }
}
