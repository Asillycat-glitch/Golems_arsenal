package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 神器伤害准入：只拦“受保护傀儡 + 下降写 + 非正常伤害链/内部写”。
 *
 * <p>普通 hurt 的写会经过 LivingEntity#hurt/actuallyHurt/GuardedEntity#hurtFinal，
 * 栈判定放行；直接 setHealth（true damage / 外部写）会被默认拒绝。只对玩家拥有的
 * Modular Golems 傀儡生效，其他实体完全不受影响。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHealthAdmissionMixin {

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ga$admitSetHealth(float amount, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        HealthLedger.noteOffThread(entity, "setHealth");
        if (!DamageAdmission.allowSetHealth(entity, amount)) {
            ci.cancel();
        }
    }
}
