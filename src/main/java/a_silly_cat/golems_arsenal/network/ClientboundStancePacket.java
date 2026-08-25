package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.client.ClientStanceData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C sync of the player's stance gauge (stacks, progress, charge flag) for the HUD. */
public class ClientboundStancePacket {
    private final int stacks;
    private final double progress;
    private final boolean charged;

    public ClientboundStancePacket(int stacks, double progress, boolean charged) {
        this.stacks = stacks;
        this.progress = progress;
        this.charged = charged;
    }

    public static void encode(ClientboundStancePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.stacks);
        buf.writeDouble(msg.progress);
        buf.writeBoolean(msg.charged);
    }

    public static ClientboundStancePacket decode(FriendlyByteBuf buf) {
        return new ClientboundStancePacket(buf.readInt(), buf.readDouble(), buf.readBoolean());
    }

    public static void handle(ClientboundStancePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientStanceData.stacks = msg.stacks;
            ClientStanceData.progress = msg.progress;
            ClientStanceData.charged = msg.charged;
        });
        ctx.get().setPacketHandled(true);
    }
}
