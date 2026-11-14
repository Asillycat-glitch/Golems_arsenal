package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * “活着”这件事的读侧权威。
 *
 * <p>2026-11-07：toaru 的 {@code GungnirLivingEntityMixin} 会注入 {@code isAlive} / {@code isDeadOrDying}
 * （{@code toaru$terminatedIsNotAlive} / {@code toaru$terminatedIsDead}），给实体打上“已终止”标记后
 * 直接返回 false/true，和真实血量无关。结果：血修回来了、`removalReason` 也清了，
 * 但 modulargolems 的交互判定看到 {@code isAlive()==false}，**回收按钮/右键全部无效**。</p>
 *
 * <p>这里在 HEAD 上抢先给答案（{@code priority=500}，比默认 mixin 先应用）：
 * 受保护傀儡只要真实 {@code DATA_HEALTH > 1}、没被移除、没有真实死亡事件，就是活的。
 * 只向上纠正“被判定为死”，真死（血量 &lt;= 1 / 死亡栈 / 见过死亡事件）不动。</p>
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class LivingEntityAliveReadMixin {

    @Inject(method = "isAlive", at = @At("HEAD"), cancellable = true)
    private void ga$alive(CallbackInfoReturnable<Boolean> cir) {
        if (isHealthyProtected((LivingEntity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isDeadOrDying", at = @At("HEAD"), cancellable = true)
    private void ga$deadOrDying(CallbackInfoReturnable<Boolean> cir) {
        if (isHealthyProtected((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean isHealthyProtected(LivingEntity entity) {
        try {
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return false;
            }
            if (golem.getOwnerUUID() == null) {
                return false;
            }
            if (SanctuaryScopes.inDeath() || HealthLedger.observedDeath(golem)) {
                return false;
            }
            // mode=OFF：读侧权威关闭，让"关掉防御"的实验看到原版行为。
            if (a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary.mode()
                    != a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode.ENFORCE) {
                return false;
            }
            // 2026-11-08：判据改成账本。旧实现看 raw>1，攻击方只要把 DATA_HEALTH 写 0
            // （DataItem 直写，不发事件），这一帧 isAlive() 就变 false，别人的
            // "if (!isAlive()) 就掉落/回收"立刻发生，修回来也晚了。
            float ledger = HealthLedger.expectedOf(golem);
            return Float.isFinite(ledger) && ledger > 1.0F;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
