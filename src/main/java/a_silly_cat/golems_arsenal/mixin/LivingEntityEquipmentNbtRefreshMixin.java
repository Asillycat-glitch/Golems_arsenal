package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.base.event.WeaponEventHandler;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 装备 NBT 原地变化的补刷入口。
 *
 * <p>上游 {@code collectEquipmentChanges} 比较的是槽位里的 {@code ItemStack}；同一个引用
 * 改 NBT（武士刀/光剑升级、词条写入）不会触发换装事件，{@code getGolemModifiers} 不会重算。
 * 在 {@code detectEquipmentUpdates} 的 TAIL 做一次指纹比较，只有变化时才补刷。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEquipmentNbtRefreshMixin {

    @Inject(method = "detectEquipmentUpdates", at = @At("TAIL"))
    private void ga$refreshEquipmentNbt(CallbackInfo ci) {
        WeaponEventHandler.onEquipmentNbtChanged((LivingEntity) (Object) this);
    }
}
