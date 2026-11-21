package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端实体移除的最终门。
 *
 * <p>vanilla remove 包可以被过滤，但自定义包（supersteve RemoveClientEntity、
 * AV S2CSyncDespawn 等）最终也会调用 ClientLevel.removeEntity。只在 packet
 * 构造器上过滤不够。</p>
 *
 * <p>只按受保护 entityId 判断，不按 mod / 武器 / 类名。</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelRemoveGuardMixin {

    @Inject(method = "m_171642_", at = @At("HEAD"), cancellable = true)
    private void ga$guardClientRemove(int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
        if (Anchor.isProtectedEntityId(entityId)) {
            ci.cancel();
            Golems_arsenal.LOGGER.info(
                    "[Sanctuary] client_remove_blocked id={} reason={}", entityId, reason);
        }
    }
}
