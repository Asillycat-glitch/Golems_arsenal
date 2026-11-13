package a_silly_cat.golems_arsenal.testbench.mixin;

import a_silly_cat.golems_arsenal.testbench.HealthGuard;
import a_silly_cat.golems_arsenal.testbench.HealthTrace;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P0 观测：{@code Entity.setRemoved}（PRIVATE TESTBENCH）。
 *
 * <p>这是所有移除路径的汇合点：{@code discard()} → {@code remove(DISCARDED)}、
 * {@code kill()} → {@code remove(KILLED)}、跨维度/卸载也走这里。只读 reason，
 * 不阻止移除 —— P0 还没有 Guard。</p>
 */
@Mixin(Entity.class)
public abstract class EntityTraceMixin {

    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void ga$setRemovedHead(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity living && !HealthGuard.allowRemoved(living, reason)) {
            ci.cancel();
            return;
        }
        HealthTrace.onSetRemoved(entity, reason);
    }
}
