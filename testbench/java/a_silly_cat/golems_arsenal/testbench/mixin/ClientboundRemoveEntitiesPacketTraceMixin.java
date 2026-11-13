package a_silly_cat.golems_arsenal.testbench.mixin;

import a_silly_cat.golems_arsenal.testbench.HealthTrace;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P0 观测：客户端移除包（PRIVATE TESTBENCH）。
 *
 * <p>只观察"服务端有没有把被追踪实体的 remove 包发出去"，用于识别"服务端已移除、
 * 客户端没收到"的幽灵模型。包构造在服务端线程，频率很低；不读网络线程、不拦截发送。</p>
 *
 * <p>两个构造器都是 vanilla 方法名（{@code <init>}），用 {@code remap = false}，
 * 不需要 refmap。</p>
 */
@Mixin(value = ClientboundRemoveEntitiesPacket.class, remap = false)
public abstract class ClientboundRemoveEntitiesPacketTraceMixin {

    @Inject(method = "<init>([I)V", at = @At("RETURN"), remap = false)
    private void ga$initIds(int[] ids, CallbackInfo ci) {
        HealthTrace.onClientRemove(ids);
    }

    @Inject(method = "<init>(Lit/unimi/dsi/fastutil/ints/IntList;)V", at = @At("RETURN"), remap = false)
    private void ga$initList(IntList ids, CallbackInfo ci) {
        HealthTrace.onClientRemove(ids == null ? null : ids.toIntArray());
    }
}
