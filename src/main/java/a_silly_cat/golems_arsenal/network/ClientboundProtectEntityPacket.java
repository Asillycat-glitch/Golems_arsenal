package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C：重新保护一个 entityId/UUID（resync 的 remove+add 之后用）。 */
public class ClientboundProtectEntityPacket {
    private final int entityId;
    private final UUID uuid;

    public ClientboundProtectEntityPacket(int entityId, UUID uuid) {
        this.entityId = entityId;
        this.uuid = uuid;
    }

    public static void encode(ClientboundProtectEntityPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeBoolean(msg.uuid != null);
        if (msg.uuid != null) {
            buf.writeUUID(msg.uuid);
        }
    }

    public static ClientboundProtectEntityPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        UUID uuid = buf.readBoolean() ? buf.readUUID() : null;
        return new ClientboundProtectEntityPacket(id, uuid);
    }

    public static void handle(ClientboundProtectEntityPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> Anchor.protect(msg.uuid, msg.entityId));
        ctx.get().setPacketHandled(true);
    }
}
