package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield;
import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 读侧掩护：最大生命也不给外人看。
 *
 * <p>有一整类处决武器的判据是"<b>目标最大生命 &lt; 攻击者最大生命</b>就秒杀"
 * （{@code momotinker:Origin}、{@code compassionateeverything}、{@code jzyy:pulverize}）。
 * 只要它们读到的 {@code getMaxHealth()} 是 {@link ReadShield#DECOY}，这条分支就不成立 ——
 * 不需要拦它们的写入，只需要让它们"算错"。</p>
 *
 * <p>和 {@link GuardedEntityHealthReadMixin} 一样：客户端不挡（血条要显示），
 * 我们自己的作用域不挡（账本、快照、重建都要真值）。</p>
 */
@Mixin(value = LivingEntity.class, priority = 1900)
public abstract class LivingEntityMaxHealthHideMixin {

    @Inject(method = "getMaxHealth", at = @At("HEAD"), cancellable = true)
    private void ga$hideMaxHealth(CallbackInfoReturnable<Float> cir) {
        try {
            if (!((Object) this instanceof AbstractGolemEntity<?, ?> golem)) {
                return;
            }
            if (golem.getOwnerUUID() == null || golem.level().isClientSide) {
                return;
            }
            if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
                return;
            }
            if (true) {
                ReadShield.noteTrusted();
                return;
            }
            ReadShield.noteHidden();
            cir.setReturnValue(ReadShield.DECOY);
        } catch (Throwable ignored) {
        }
    }
}
