package a_silly_cat.golems_arsenal.testbench.mixin;

import a_silly_cat.golems_arsenal.testbench.HealthGuard;
import a_silly_cat.golems_arsenal.testbench.HealthTrace;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P0 观测：vanilla {@code LivingEntity} 的 setHealth/getHealth（PRIVATE TESTBENCH）。
 *
 * <p>只读。HEAD/RETURN 各一次，把 arg、before、after、调用者栈交给 {@link HealthTrace}。
 * 不开记录时 {@code HealthTrace.active()} 为 false，回调只做一次 volatile 读。</p>
 *
 * <p>傀儡（{@code GuardedEntity}）覆写了这两个方法，所以它走
 * {@code GuardedEntityTraceMixin}；这里的回调只在傀儡内部调用
 * {@code super.setHealth} 时才会看到（那正是 guardedData 同步进 vanilla 字段的那一步）。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTraceMixin {

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ga$setHealthHead(float amount, CallbackInfo ci) {
        if (!HealthGuard.allowSetHealth((LivingEntity) (Object) this, amount)) {
            ci.cancel();
            return;
        }
        HealthTrace.onLivingSetHealthHead((LivingEntity) (Object) this, amount);
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void ga$setHealthReturn(float amount, CallbackInfo ci) {
        HealthTrace.onLivingSetHealthReturn((LivingEntity) (Object) this, amount);
    }

    @Inject(method = "getHealth", at = @At("RETURN"))
    private void ga$getHealthReturn(CallbackInfoReturnable<Float> cir) {
        HealthTrace.onLivingGetHealth((LivingEntity) (Object) this, cir.getReturnValueF());
    }
}
