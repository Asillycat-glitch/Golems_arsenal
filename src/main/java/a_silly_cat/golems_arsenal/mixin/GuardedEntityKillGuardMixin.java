package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code kill()} 的宿主覆写。{@code GuardedEntity#kill} 会直接把 {@code guardedData}
 * 清零再走死亡流程，不经过 {@code hurt(generic_kill)}；基类的
 * {@link LivingEntityKillGuardMixin} 对傀儡不执行。
 */
@Mixin(value = GuardedEntity.class, priority = 2000)
public abstract class GuardedEntityKillGuardMixin {

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void ga$guardGuardedKill(CallbackInfo ci) {
        if (RemovalGuard.ledgerAlive((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
