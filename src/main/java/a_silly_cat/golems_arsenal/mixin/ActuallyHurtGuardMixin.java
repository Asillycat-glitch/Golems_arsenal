package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 直伤门（基类）：{@code LivingEntity#actuallyHurt}。
 *
 * <p>普通的 {@code hurt()} 会先发 {@code LivingAttackEvent}，Sanctuary 放行后才进
 * {@code actuallyHurt}，所以窗口里是"已准入"。绕过 {@code hurt()} 直接调
 * {@code actuallyHurt}（ArcaneVortex 的 {@code EntityKillerHelp}、iss_csw 的
 * {@code actuallyHurt} 旁路）没有窗口，这里直接取消。</p>
 *
 * <p>{@code GuardedEntity} 覆写了 {@code actuallyHurt}（final），所以另有一个
 * {@link GuardedEntityActuallyHurtGuardMixin}；两个都必须在。</p>
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class ActuallyHurtGuardMixin {

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void ga$guardActualHurt(DamageSource source, float amount, CallbackInfo ci) {
        if (IndexGuard.denyDirectDamage((LivingEntity) (Object) this, source, amount,
                "LivingEntity.actuallyHurt")) {
            ci.cancel();
        }
    }
}
