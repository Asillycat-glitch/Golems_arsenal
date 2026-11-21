package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体冻结（SEALED）：受保护傀儡不再走 {@code LivingEntity#tick}。
 *
 * <p>为什么冻结而不是只关 AI：{@code setNoAi} 只停 {@code aiStep}，实体仍然会跑
 * {@code baseTick}（火焰/水/摔落/药水/接触伤害）、{@code travel}（击退/爆炸位移）、
 * {@code deathTime} 倒计时、以及所有升级/装备的 tick 回调。测试态要的是"它就是一具模型"，
 * 所以直接掐掉 tick。</p>
 *
 * <p>注意 1.20.1 <b>没有</b> {@code Entity#canUpdate()}（1.16 之后被删了），
 * 所以这里只能挂 {@code tick}。{@code tickCount} 由 {@code ServerLevel#tickNonPassenger}
 * 自增，不受这条影响。</p>
 *
 * <p>只作用于服务端：客户端那一份还要渲染/插值，冻了会看起来卡死。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTickFreezeMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void ga$guardBeforeTick(CallbackInfo ci) {
        if ((Object) this instanceof AbstractGolemEntity<?, ?> golem) {
            Anchor.guardBeforeTick(golem);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ga$freezeTick(CallbackInfo ci) {
        if (Projection.tickFrozen((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
