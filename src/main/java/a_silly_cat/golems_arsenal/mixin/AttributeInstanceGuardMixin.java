package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.AttributeGuard;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 属性级守卫入口：在修饰符/基础值进场的瞬间拦，而不是等事后审计。
 *
 * <p>三个添加修饰符的重载都要包（{@code addModifier}/{@code addPermanentModifier}/
 * {@code addTransientModifier}）——ArcaneVortex 用的是 {@code addPermanentModifier}，
 * 只包 {@code addModifier} 会漏。</p>
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeInstanceGuardMixin {

    @Inject(method = "addModifier", at = @At("HEAD"), cancellable = true)
    private void ga$guardAdd(AttributeModifier modifier, CallbackInfo ci) {
        if (!AttributeGuard.allowModifier((AttributeInstance) (Object) this, modifier)) {
            ci.cancel();
        }
    }

    @Inject(method = "addPermanentModifier", at = @At("HEAD"), cancellable = true)
    private void ga$guardPermanent(AttributeModifier modifier, CallbackInfo ci) {
        if (!AttributeGuard.allowModifier((AttributeInstance) (Object) this, modifier)) {
            ci.cancel();
        }
    }

    @Inject(method = "addTransientModifier", at = @At("HEAD"), cancellable = true)
    private void ga$guardTransient(AttributeModifier modifier, CallbackInfo ci) {
        if (!AttributeGuard.allowModifier((AttributeInstance) (Object) this, modifier)) {
            ci.cancel();
        }
    }

    @Inject(method = "setBaseValue", at = @At("HEAD"), cancellable = true)
    private void ga$guardBase(double value, CallbackInfo ci) {
        if (!AttributeGuard.allowBaseValue((AttributeInstance) (Object) this, value)) {
            ci.cancel();
        }
    }
}
