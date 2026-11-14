package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 写血门（宿主覆写）：{@code GuardedEntity#setHealth} 是 {@code final} 覆写，它不调用
 * {@code super.setHealth}，而是先改 {@code guardedData}、再在 {@code setGuardedDataImpl}
 * 里 {@code invokespecial AbstractGolem.setHealth}。基类门看得到后半段，看不到前半段，
 * 所以这里单独在 HEAD 拦一次：被拒绝时 {@code guardedData} 也不会被改小。
 */
@Mixin(value = GuardedEntity.class, priority = 2000)
public abstract class GuardedEntitySetHealthGuardMixin {

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ga$admitGuardedSetHealth(float amount, CallbackInfo ci) {
        if (!DamageAdmission.allowSetHealth((LivingEntity) (Object) this, amount)) {
            ci.cancel();
        }
    }
}
