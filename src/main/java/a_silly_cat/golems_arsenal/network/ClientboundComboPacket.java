package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.client.ClientComboData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：某只傀儡开始播三连击的<b>第几段</b>。客户端只负责按
 * {@code GolemComboStages} 的参数把这一段演出来（进度自己按 tick 推），
 * 所以服务端不需要持续同步位置或进度。
 */
public class ClientboundComboPacket {
    private final int entityId;
    private final int stage;

    public ClientboundComboPacket(int entityId, int stage) {
        this.entityId = entityId;
        this.stage = stage;
    }

    public static void encode(ClientboundComboPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.stage);
    }

    public static ClientboundComboPacket decode(FriendlyByteBuf buf) {
        return new ClientboundComboPacket(buf.readInt(), buf.readInt());
    }

    public static void handle(ClientboundComboPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientComboData.start(msg.entityId, msg.stage));
        ctx.get().setPacketHandled(true);
    }
}
