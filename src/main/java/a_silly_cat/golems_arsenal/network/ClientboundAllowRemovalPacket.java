package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C：服务端已经同意这次正常移除（回收/卸载/死亡），客户端可以放行该 entityId/UUID。
 * 只在 RemovalGuard 放行正常生命周期时发送；AV 直接 purge 不会带这个授权。
 */
public class ClientboundAllowRemovalPacket {
    private final int entityId;
    private final UUID uuid;

    public ClientboundAllowRemovalPacket(int entityId, UUID uuid) {
        this.entityId = entityId;
        this.uuid = uuid;
    }

    public static void encode(ClientboundAllowRemovalPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeBoolean(msg.uuid != null);
        if (msg.uuid != null) {
            buf.writeUUID(msg.uuid);
        }
    }

    public static ClientboundAllowRemovalPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        UUID uuid = buf.readBoolean() ? buf.readUUID() : null;
        return new ClientboundAllowRemovalPacket(id, uuid);
    }

    public static void handle(ClientboundAllowRemovalPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> Anchor.unprotect(msg.uuid, msg.entityId));
        ctx.get().setPacketHandled(true);
    }
}
