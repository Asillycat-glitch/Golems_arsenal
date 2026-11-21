package a_silly_cat.golems_arsenal.network;

import a_silly_cat.golems_arsenal.guard.index.ClientProjectionSync;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 客户端发现受保护 UUID 没有实体时，请求服务端做一次投影 resync。 */
public class ServerboundResyncPacket {
    private final UUID uuid;

    public ServerboundResyncPacket(UUID uuid) {
        this.uuid = uuid;
    }

    public static void encode(ServerboundResyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.uuid);
    }

    public static ServerboundResyncPacket decode(FriendlyByteBuf buf) {
        return new ServerboundResyncPacket(buf.readUUID());
    }

    public static void handle(ServerboundResyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || msg.uuid == null) {
                return;
            }
            for (ServerLevel level : player.server.getAllLevels()) {
                Entity entity = null;
                for (Entity candidate : level.getAllEntities()) {
                    if (candidate.getUUID().equals(msg.uuid)) {
                        entity = candidate;
                        break;
                    }
                }
                if (entity instanceof AbstractGolemEntity<?, ?> golem) {
                    Anchor.protect(golem.getUUID(), golem.getId());
                    ClientProjectionSync.resync(level, golem);
                    a_silly_cat.golems_arsenal.Golems_arsenal.LOGGER.info(
                            "[Sanctuary] server resync sent uuid={} id={}", golem.getUUID(), golem.getId());
                    return;
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
