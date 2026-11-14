package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 掉落门：{@code LivingEntity#dropAllDeathLoot}。
 *
 * <p>有的清除实现绕过 {@code die()} 直接调它（toaru 的 {@code GungnirLootMixin}），
 * 事件层的 {@code LivingDropsEvent} 不一定经过。活着的受保护傀儡一律不掉。</p>
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class DropLootGuardMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void ga$guardDeathLoot(DamageSource source, CallbackInfo ci) {
        if (Sanctuary.blockExternalClear((LivingEntity) (Object) this, "LivingEntity.dropAllDeathLoot")) {
            ci.cancel();
        }
    }
}
