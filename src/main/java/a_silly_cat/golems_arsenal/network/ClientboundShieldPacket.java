package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.client.ClientShieldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C sync of a golem's backup-energy shield (current/max) for the golem info panel. */
public class ClientboundShieldPacket {
    private final int entityId;
    private final float shield;
    private final float max;

    public ClientboundShieldPacket(int entityId, float shield, float max) {
        this.entityId = entityId;
        this.shield = shield;
        this.max = max;
    }

    public static void encode(ClientboundShieldPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeFloat(msg.shield);
        buf.writeFloat(msg.max);
    }

    public static ClientboundShieldPacket decode(FriendlyByteBuf buf) {
        return new ClientboundShieldPacket(buf.readInt(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(ClientboundShieldPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientShieldData.put(msg.entityId, msg.shield, msg.max));
        ctx.get().setPacketHandled(true);
    }
}