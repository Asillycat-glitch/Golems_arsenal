package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.AttributeGuard;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 属性读侧地板：{@code AttributeInstance#getValue}。
 *
 * <p>为什么写入口不够：{@code setBaseValue} 我们有门，但本包里有人直接写
 * {@code AttributeInstance.cachedValue}（momotinker 的 accessor mixin），还有
 * {@code Helper.killMAX} 把 13 个 {@code RangedAttribute} 的 min/max 改成 ±{@code Double.MAX}。
 * 这两种都不经过 {@code setBaseValue}，写入口看不见。</p>
 *
 * <p>这里只在"已经绑定到受保护傀儡的 MAX_HEALTH 实例"上做事，且只拦<b>腰斩式</b>下降
 * （报告值 &lt; 上次正常值的一半），正常升级/降级不受影响。绑定由
 * {@code AttributeGuard.bind} 在进世界和受击时完成。</p>
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeValueFloorMixin {

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void ga$floorValue(CallbackInfoReturnable<Double> cir) {
        Double reported = cir.getReturnValue();
        if (reported == null || !Double.isFinite(reported)) {
            return;
        }
        double floored = AttributeGuard.floorMaxHealth((AttributeInstance) (Object) this, reported);
        if (floored != reported) {
            cir.setReturnValue(floored);
        }
    }
}
