package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * tick 列表门：{@code EntityTickList#remove}。
 *
 * <p>拆索引的模组（jzyy 的 {@code MsUtil.superKillEntity}）不只清 section/lookup，
 * 还会把实体从 {@code ServerLevel.entityTickList} 里摘掉。对象还在、索引也还在，
 * 但这一 tick 起不再 {@code tick()}——AI、AI 目标、升级回调全部停摆，等于软删除。</p>
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListGuardMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void ga$guardTickListRemove(Entity entity, CallbackInfo ci) {
        if (IndexGuard.denyTickListRemoval(entity)) {
            ci.cancel();
        }
    }
}
