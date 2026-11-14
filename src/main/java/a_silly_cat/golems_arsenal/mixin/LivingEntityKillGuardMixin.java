package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code kill()}（{@code /kill}、任何"跳过伤害管线直接抹掉"的入口）的通用拦截。
 *
 * <p>{@code Entity#kill()} 在 1.20.1 里是 {@code hurt(genericKill, Float.MAX_VALUE)}，
 * 本来会被 {@code Sanctuary} 判成 OPERATION 拒掉；但两个例外：</p>
 * <ul>
 *   <li>宿主模组 {@code GuardedEntity#kill} 自己覆写了它：{@code dynamicReductionRate() > 0}
 *       时直接把 {@code guardedData} 清零再走 {@code die}；</li>
 *   <li>别的模组可能覆写/绕开 {@code hurt}。</li>
 * </ul>
 *
 * <p>所以这里在 {@code kill()} 的 HEAD 上直接按账本判一次：账本说活着就不执行。
 * 真死（账本归零 / 见过死亡事件）时照常放行。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityKillGuardMixin {

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void ga$guardKill(CallbackInfo ci) {
        if (RemovalGuard.ledgerAlive((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
