package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * hurt 窗口：在 {@code LivingEntity#hurt} 里开一个作用域，返回时关掉。
 *
 * <p>普通伤害的实际写血（{@code actuallyHurt} → {@code setHealth}）都发生在这个调用栈里，
 * 所以写入口只要问“当前窗口是不是这只傀儡、且这次 hurt 被 Sanctuary 准入过”就够了，
 * 不需要 StackWalker 认类名。嵌套的 hurt（梦终 re-hurt、分摊给另一只傀儡）用栈处理。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtScopeMixin {

    @Inject(method = "hurt", at = @At("HEAD"))
    private void ga$openHurtWindow(DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        SanctuaryScopes.pushHurt((LivingEntity) (Object) this);
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void ga$closeHurtWindow(DamageSource source, float amount,
                                    CallbackInfoReturnable<Boolean> cir) {
        SanctuaryScopes.popHurt();
    }
}
