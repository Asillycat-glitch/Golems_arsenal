package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 直伤门（宿主覆写）：{@code GuardedEntity#actuallyHurt} 是 {@code final} 覆写，
 * 基类 {@code LivingEntity} 上的 {@link ActuallyHurtGuardMixin} 对傀儡不会执行。
 */
@Mixin(value = GuardedEntity.class, priority = 2000)
public abstract class GuardedEntityActuallyHurtGuardMixin {

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void ga$guardGuardedActualHurt(DamageSource source, float amount, CallbackInfo ci) {
        if (IndexGuard.denyDirectDamage((LivingEntity) (Object) this, source, amount,
                "GuardedEntity.actuallyHurt")) {
            ci.cancel();
        }
    }
}
