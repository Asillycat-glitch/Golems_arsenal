package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除流程的第二步：{@code Entity#onRemovedFromWorld}。
 *
 * <p>{@code setRemoved}/{@code setRemovalReason} 之后，攻击方通常还会显式调一次它，把
 * {@code isAddedToWorld} 置 false、{@code invalidateCaps()}、清 GameEvent 监听。
 * 匠战妖域的清除代码（{@code jzyy/MsUtil}、{@code miztinker/EntityRemoveUtil}、
 * {@code tcondiadema/method/Test}）都<b>先写 {@code f_146795_} 再调它</b>，
 * 所以只拦 {@code setRemoved} 是不够的。</p>
 *
 * <p>用 {@link Sanctuary#blockExternalClear} 判：受保护傀儡 + ENFORCE + 账本说活着 → 取消。</p>
 */
@Mixin(Entity.class)
public abstract class EntityOnRemovedGuardMixin {

    @Inject(method = "onRemovedFromWorld", at = @At("HEAD"), cancellable = true)
    private void ga$guardOnRemoved(CallbackInfo ci) {
        if (Sanctuary.blockExternalClear((Entity) (Object) this, "Entity.onRemovedFromWorld")) {
            ci.cancel();
        }
    }
}
