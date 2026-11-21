package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 tracking 守卫：受保护实体的网络 entity id 不允许被移除包删掉。
 *
 * <p>RemovalGuard 只能保住服务端对象；如果 ServerEntity/ChunkMap 已经按 raw
 * removalReason 发了 ClientboundRemoveEntitiesPacket，客户端实体会消失，服务端
 * dump 却仍然存在。这里在包构造完成后按受保护 entity id 过滤。</p>
 *
 * <p>不按 mod / 武器 / 类名判断，只看这个 id 是否属于受保护实体。</p>
 */
@Mixin(ClientboundRemoveEntitiesPacket.class)
public abstract class ClientboundRemoveEntitiesPacketGuardMixin {

    @Shadow
    @Final
    @Mutable
    private IntList f_182717_;

    @Inject(method = "<init>(Lit/unimi/dsi/fastutil/ints/IntList;)V", at = @At("RETURN"))
    private void ga$filterProtectedIds(IntList ids, CallbackInfo ci) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        IntList kept = new IntArrayList(ids.size());
        boolean changed = false;
        for (int id : ids) {
            if (Anchor.isProtectedEntityId(id)) {
                changed = true;
            } else {
                kept.add(id);
            }
        }
        if (changed) {
            this.f_182717_ = kept;
        }
    }

    @Inject(method = "<init>([I)V", at = @At("RETURN"))
    private void ga$filterProtectedIdsVarargs(int[] ids, CallbackInfo ci) {
        if (ids == null || ids.length == 0) {
            return;
        }
        IntList kept = new IntArrayList(ids.length);
        boolean changed = false;
        for (int id : ids) {
            if (Anchor.isProtectedEntityId(id)) {
                changed = true;
            } else {
                kept.add(id);
            }
        }
        if (changed) {
            this.f_182717_ = kept;
        }
    }
}
