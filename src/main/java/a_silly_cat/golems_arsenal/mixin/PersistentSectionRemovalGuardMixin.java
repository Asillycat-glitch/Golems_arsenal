package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A：section manager 终态门。
 *
 * <p>PersistentEntitySectionManager$Callback.onRemove 是真正把实体从 section 拿掉的
 * 最后一步；它看的是 raw RemovalReason，不经过 Entity.isRemoved 的读侧纠正。
 * 对受保护 UUID，在非正常生命周期移除时直接取消，保留 section/callback。</p>
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class PersistentSectionRemovalGuardMixin {

    @Shadow
    @Final
    private EntityAccess f_157609_;

    @Inject(method = "m_142472_", at = @At("HEAD"), cancellable = true)
    private void ga$denySectionDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!(f_157609_ instanceof Entity entity)) {
            return;
        }
        if (!Anchor.isProtectedUuid(entity.getUUID())) {
            return;
        }
        if (RemovalGuard.isAllowedLifecycle(entity, reason)) {
            return;
        }
        Golems_arsenal.LOGGER.warn("[Sanctuary] SECTION-DENIED uuid={} reason={} id={}",
                entity.getUUID(), reason, entity.getId());
        Anchor.repairSectionDenied(entity);
        ci.cancel();
    }
}
