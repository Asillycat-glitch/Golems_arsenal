package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.client.ClientFlipData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: a golem started a flip; the client renderer animates it. Lion slash sends its 360° somersault,
 * the phoenix rush sends its backflip end angle plus a flag so the renderer holds the angle through
 * the hover/dive and unwinds it while landing.
 */
public class ClientboundFlipPacket {
    private final int entityId;
    private final float maxAngleDeg;
    private final boolean phoenix;

    public ClientboundFlipPacket(int entityId) {
        this(entityId, 360.0F, false);
    }

    public ClientboundFlipPacket(int entityId, float maxAngleDeg, boolean phoenix) {
        this.entityId = entityId;
        this.maxAngleDeg = maxAngleDeg;
        this.phoenix = phoenix;
    }

    public static void encode(ClientboundFlipPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeFloat(msg.maxAngleDeg);
        buf.writeBoolean(msg.phoenix);
    }

    public static ClientboundFlipPacket decode(FriendlyByteBuf buf) {
        return new ClientboundFlipPacket(buf.readInt(), buf.readFloat(), buf.readBoolean());
    }

    public static void handle(ClientboundFlipPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientFlipData.start(msg.entityId, msg.maxAngleDeg, msg.phoenix));
        ctx.get().setPacketHandled(true);
    }
}
