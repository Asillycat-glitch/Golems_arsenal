package a_silly_cat.golems_arsenal.guard.index;

import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 直接写 EntityLookup 不会经过 ChunkMap 的 tracking 建立流程，客户端可能永远
 * 收不到这只实体：服务端 dump 有、玩家眼前没有，回收手杖却能操作到。
 * 这里补一次显式 resync：先允许并移除旧客户端实体，再 add + data + teleport。
 * 先 remove 再 add 是为了避免同 entityId/UUID 在 ClientLevel 里重复。
 */
public final class ClientProjectionSync {

    private ClientProjectionSync() {
    }

    public static void resync(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        int id = entity.getId();
        java.util.UUID uuid = entity.getUUID();
        try {
            // 客户端已经报“这个 UUID 没有实体”，所以这里只 add，不做 remove。
            // 先发 protect，保证 add 之后即使对手立刻发 remove 包，保护集也已经生效；
            // 这条路径不能有 allow/remove 窗口，否则 toaru/AV 可以卡在窗口里删掉。
            Anchor.protect(uuid, id);
            ModNetwork.sendProtect(uuid, id);
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(entity);
            ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(entity);
            ClientboundSetEntityDataPacket data = values == null || values.isEmpty()
                    ? null : new ClientboundSetEntityDataPacket(id, values);
            for (ServerPlayer player : level.players()) {
                player.connection.send(add);
                if (data != null) {
                    player.connection.send(data);
                }
                player.connection.send(teleport);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void teleport(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        try {
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(entity);
            ClientboundSetEntityDataPacket data = values == null || values.isEmpty()
                    ? null : new ClientboundSetEntityDataPacket(entity.getId(), values);
            for (ServerPlayer player : level.players()) {
                player.connection.send(teleport);
                if (data != null) {
                    player.connection.send(data);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
