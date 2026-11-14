package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 回调门：{@code Entity#setLevelCallback(NULL)}。
 *
 * <p>清除类模组（tcondiadema/Test、部分 jzyy 路径）会把 levelCallback 换成 NULL，
 * 实体对象还在、索引也还在，但世界不再 tick、不再移动、不再交互——等于"软删除"。
 * 受保护傀儡在正常生命周期之外被置 NULL 时取消。</p>
 */
@Mixin(Entity.class)
public abstract class EntityLevelCallbackGuardMixin {

    @Inject(method = "setLevelCallback", at = @At("HEAD"), cancellable = true)
    private void ga$guardLevelCallback(EntityInLevelCallback callback, CallbackInfo ci) {
        if (IndexGuard.denyLevelCallback((Entity) (Object) this, callback)) {
            ci.cancel();
        }
    }
}
