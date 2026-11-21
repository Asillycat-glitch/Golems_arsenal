package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * B：客户端自定义 payload 的受保护 UUID 过滤。
 *
 * <p>不认 mod 名/类名：只要一个 play-to-client 自定义包的数据里出现受保护 UUID 的
 * 16 字节，就丢弃。这样 toaru 的 GungnirDeathPayload（以及任何同类擦除包）不会
 * 进入客户端 tombstone，模型/血量/交互不会被它私有状态清掉。</p>
 *
 * <p>自己的网络通道必须放行，否则 ClientboundAllowRemoval/Protect 会被自己过滤。</p>
 */
@Mixin(value = ClientPacketListener.class, priority = 2500)
public abstract class ClientCustomPayloadGuardMixin {

    @Inject(method = "m_7413_", at = @At("HEAD"), cancellable = true)
    private void ga$filterProtectedPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet == null || packet.getIdentifier() == null) {
            return;
        }
        // 自己的控制通道放行：它本身会携带受保护 UUID。
        String namespace = packet.getIdentifier().getNamespace();
        if (Golems_arsenal.MODID.equals(namespace)
                || "minecraft".equals(namespace)
                || "fml".equals(namespace)
                || "forge".equals(namespace)) {
            // fml:play 承载 Forge 的 spawn/同步数据；过滤它会把模型/装备/血量同步一起打掉。
            return;
        }
        try {
            FriendlyByteBuf data = packet.getData();
            if (data == null || data.readableBytes() <= 0) {
                return;
            }
            // 死亡/擦除 payload 很小；大包多半是同步/资源数据，不能按 UUID 盲杀。
            if (data.readableBytes() > 64) {
                return;
            }
            byte[] bytes = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), bytes);
            for (UUID id : Anchor.protectedUuids()) {
                if (contains(bytes, uuidBytes(id))) {
                    Golems_arsenal.LOGGER.warn(
                            "[Sanctuary] CUSTOM_PAYLOAD_BLOCKED channel={} uuid={} bytes={}",
                            packet.getIdentifier(), id, bytes.length);
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
