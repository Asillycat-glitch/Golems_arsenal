package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 索引门（section 层）：{@code ClassInstanceMultiMap#remove}。
 *
 * <p>1.20.1 里 {@code EntitySection#remove} 只是把实体从 section 的
 * {@code ClassInstanceMultiMap} 里摘掉；摘掉之后实体不再占据区块、也不在
 * {@code getAll()} 的结果里。攻击方（AV 的 {@code MainRemove}、jzyy 的
 * {@code superKillEntity}）直接调这一层时，{@code setRemoved} 门看不到。</p>
 *
 * <p>取消返回 false，调用方 {@code EntitySection#remove} 也跟着返回 false，
 * 不会出现"section 里有、lookup 里没有"的半截状态。</p>
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapGuardMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void ga$guardIndexRemove(Object value, CallbackInfoReturnable<Boolean> cir) {
        if (IndexGuard.denyIndexRemoval(value, "ClassInstanceMultiMap.remove")) {
            cir.setReturnValue(false);
        }
    }
}
