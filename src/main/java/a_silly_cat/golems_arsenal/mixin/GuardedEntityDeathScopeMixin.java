package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.SoulRegistry;
import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 死亡栈 + 死亡预防：{@code GuardedEntity} 自己的 {@code die}。
 *
 * <p>为什么必须单独包：{@code die()} 在 {@code getGuardedDataImpl() > 0} 时会直接 return，
 * 也不一定走到 {@code LivingEntity#die}；只包 vanilla 的 die 会漏掉它内部的写。</p>
 *
 * <p>2026-11-07 起同时做“死亡预防”，对应匠战妖域里 {@code etstlib} 的
 * {@code OnHoldingPreventDeathHook} / {@code jzyy.ImmortalDeath}：<b>不是取消死亡事件，
 * 而是让死亡流程根本不走完</b>——受保护傀儡在 HEAD 处直接 return，并把血写回账本值。
 * 这样 Arcane 的致命一击没有机会在 die 之后继续写 removalReason / 清索引。</p>
 *
 * <p>刻意<b>不</b>包 {@code kill()}：显式 kill 是“跳过死亡管线直接抹掉”，正是这一层要挡的。
 * 它走 {@code hurt(generic_kill)} 时由 {@code Sanctuary} 判定，走
 * {@code setHealth(0)} 时没有死亡作用域、会被写入口拒绝。</p>
 *
 * <p>方法名在生产环境是 SRG 名，靠手工维护的 {@code golems_arsenal.refmap.json} 翻译。</p>
 */
@Mixin(GuardedEntity.class)
public abstract class GuardedEntityDeathScopeMixin {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void ga$enterDie(DamageSource source, CallbackInfo ci) {
        if ((Object) this instanceof AbstractGolemEntity<?, ?> golem) {
            SoulRegistry.captureNow(golem);
        }
        if (Sanctuary.preventDeath((GuardedEntity) (Object) this, source)) {
            ci.cancel();
            return;
        }
        SanctuaryScopes.enterDeath();
    }

    @Inject(method = "die", at = @At("RETURN"))
    private void ga$exitDie(DamageSource source, CallbackInfo ci) {
        SanctuaryScopes.exitDeath();
    }
}
