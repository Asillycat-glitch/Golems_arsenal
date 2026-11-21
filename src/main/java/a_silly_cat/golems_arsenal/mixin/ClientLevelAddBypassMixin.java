package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.index.WorldIndexGuard;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端受保护实体 add 的最终权威。
 *
 * <p>有的模组会 mixin ClientLevel.addEntity 并在 HEAD cancel（例如“已擦除 UUID 不再允许加入”）。
 * 对受保护实体，我们直接写 TransientEntitySectionManager，再 cancel 原版 addEntity，
 * 避免我们的 resync 包被别人的 add 门吞掉。</p>
 */
@Mixin(value = ClientLevel.class, priority = 500)
public abstract class ClientLevelAddBypassMixin {

    @Inject(method = "m_104739_", at = @At("HEAD"), cancellable = true)
    private void ga$forceProtectedAdd(int entityId, Entity entity, CallbackInfo ci) {
        if (entity == null || !ci.isCancelled()) {
            // 没人 cancel 时让原版 addEntity 正常跑：它会做 setLevel / tracking / onAddedToWorld
            // 等副作用；我们只在“已经被别人 cancel”时兜底。
            return;
        }
        if (Anchor.isProtectedEntityId(entityId) || Anchor.isProtectedUuid(entity.getUUID())) {
            WorldIndexGuard.addClientEntity((ClientLevel) (Object) this, entity);
        }
    }
}
