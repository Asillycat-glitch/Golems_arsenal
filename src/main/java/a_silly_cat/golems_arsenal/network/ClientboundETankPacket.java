package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.client.ClientETankData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C sync of a golem's E-tank contents (stored materials / capacity) for the golem info panel. */
public class ClientboundETankPacket {
    private final int entityId;
    private final int stored;
    private final int capacity;

    public ClientboundETankPacket(int entityId, int stored, int capacity) {
        this.entityId = entityId;
        this.stored = stored;
        this.capacity = capacity;
    }

    public static void encode(ClientboundETankPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.stored);
        buf.writeInt(msg.capacity);
    }

    public static ClientboundETankPacket decode(FriendlyByteBuf buf) {
        return new ClientboundETankPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(ClientboundETankPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientETankData.put(msg.entityId, msg.stored, msg.capacity));
        ctx.get().setPacketHandled(true);
    }
}
