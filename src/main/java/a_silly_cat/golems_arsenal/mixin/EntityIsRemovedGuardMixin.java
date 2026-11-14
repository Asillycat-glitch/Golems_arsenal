package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "它还在不在"的读侧权威。
 *
 * <p>2026-11-08 调整：从"事后复活"改成"当场回答"。原因很直接——攻击方写
 * {@code removalReason} 之后，<b>别的模组在同一个 tick 里</b>就会调 {@code isRemoved()} /
 * {@code getRemovalReason()} 并据此走"已移除"分支（停止 AI、清 BossBar、跳过交互、触发掉落）。
 * 等下一 tick 再清字段，那些副作用已经发生了。</p>
 *
 * <p>口径见 {@link RemovalGuard#ledgerAlive}：有主 + 服务端 + ENFORCE + 账本说活着 +
 * 不是真死 + 不是跨维度 → 回答"没被移除"。跨维度必须先放行，否则传送会被自己卡住。</p>
 *
 * <p>字段本身仍然由 {@code Aegis} 清（读侧改不了真实字段），两层配合：读侧保证这一帧，
 * 写侧保证下一帧。</p>
 */
@Mixin(value = Entity.class, priority = 2000)
public abstract class EntityIsRemovedGuardMixin {

    @Inject(method = "isRemoved", at = @At("HEAD"), cancellable = true)
    private void ga$guardIsRemoved(CallbackInfoReturnable<Boolean> cir) {
        if (RemovalGuard.ledgerAlive((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getRemovalReason", at = @At("HEAD"), cancellable = true)
    private void ga$guardRemovalReason(CallbackInfoReturnable<Entity.RemovalReason> cir) {
        if (RemovalGuard.ledgerAlive((Entity) (Object) this)) {
            cir.setReturnValue(null);
        }
    }
}
