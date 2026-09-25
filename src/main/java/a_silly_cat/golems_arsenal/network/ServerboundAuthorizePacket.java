package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.base.event.GolemPhoenixHandler;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：玩家手里拿着命令手杖、对着傀儡按下授权键。服务端再做一次完整校验（装了 mgdp、拿着手杖、
 * 距离够近、傀儡到了火候、玩家授权冷却已过），任何一条不满足就当作没发生——毕竟这是个隐藏彩蛋。
 */
public class ServerboundAuthorizePacket {
    private final int entityId;

    public ServerboundAuthorizePacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(ServerboundAuthorizePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static ServerboundAuthorizePacket decode(FriendlyByteBuf buf) {
        return new ServerboundAuthorizePacket(buf.readInt());
    }

    public static void handle(ServerboundAuthorizePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(msg.entityId);
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return;
            }
            if (!GolemPhoenixHandler.holdsCommandWand(player)
                    || player.distanceToSqr(golem) > 64.0) {
                return;
            }
            GolemPhoenixHandler.authorize(player, golem);
        });
        ctx.get().setPacketHandled(true);
    }
}
