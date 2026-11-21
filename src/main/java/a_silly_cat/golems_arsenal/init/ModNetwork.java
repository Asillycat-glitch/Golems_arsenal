package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.network.ClientboundComboPacket;
import a_silly_cat.golems_arsenal.network.ClientboundFlipPacket;
import a_silly_cat.golems_arsenal.network.ClientboundETankPacket;
import a_silly_cat.golems_arsenal.network.ClientboundAllowRemovalPacket;
import a_silly_cat.golems_arsenal.network.ClientboundProtectEntityPacket;
import a_silly_cat.golems_arsenal.network.ClientboundShieldPacket;
import a_silly_cat.golems_arsenal.network.ClientboundStancePacket;
import a_silly_cat.golems_arsenal.network.ServerboundAuthorizePacket;
import a_silly_cat.golems_arsenal.network.ServerboundResyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

public final class ModNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Golems_arsenal.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, ClientboundStancePacket.class,
                ClientboundStancePacket::encode, ClientboundStancePacket::decode,
                ClientboundStancePacket::handle);
        CHANNEL.registerMessage(1, ClientboundShieldPacket.class,
                ClientboundShieldPacket::encode, ClientboundShieldPacket::decode,
                ClientboundShieldPacket::handle);
        CHANNEL.registerMessage(2, ClientboundFlipPacket.class,
                ClientboundFlipPacket::encode, ClientboundFlipPacket::decode,
                ClientboundFlipPacket::handle);
        CHANNEL.registerMessage(3, ServerboundAuthorizePacket.class,
                ServerboundAuthorizePacket::encode, ServerboundAuthorizePacket::decode,
                ServerboundAuthorizePacket::handle);
        CHANNEL.registerMessage(4, ClientboundETankPacket.class,
                ClientboundETankPacket::encode, ClientboundETankPacket::decode,
                ClientboundETankPacket::handle);
        CHANNEL.registerMessage(5, ClientboundComboPacket.class,
                ClientboundComboPacket::encode, ClientboundComboPacket::decode,
                ClientboundComboPacket::handle);
        CHANNEL.registerMessage(6, ClientboundAllowRemovalPacket.class,
                ClientboundAllowRemovalPacket::encode, ClientboundAllowRemovalPacket::decode,
                ClientboundAllowRemovalPacket::handle);
        CHANNEL.registerMessage(7, ClientboundProtectEntityPacket.class,
                ClientboundProtectEntityPacket::encode, ClientboundProtectEntityPacket::decode,
                ClientboundProtectEntityPacket::handle);
        CHANNEL.registerMessage(8, ServerboundResyncPacket.class,
                ServerboundResyncPacket::encode, ServerboundResyncPacket::decode,
                ServerboundResyncPacket::handle);
    }

    /** 服务端已放行正常移除：通知所有客户端放行这个 entityId。 */
    public static void sendAllowRemoval(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel)) {
            return;
        }
        sendAllowRemoval(entity.getUUID(), entity.getId());
    }

    public static void sendAllowRemoval(UUID uuid, int entityId) {
        try {
            CHANNEL.send(PacketDistributor.ALL.noArg(),
                    new ClientboundAllowRemovalPacket(entityId, uuid));
        } catch (Throwable ignored) {
        }
    }

    public static void sendAllowRemoval(int entityId) {
        sendAllowRemoval(null, entityId);
    }

    public static void sendProtect(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel)) {
            return;
        }
        sendProtect(entity.getUUID(), entity.getId());
    }

    public static void sendProtect(UUID uuid, int entityId) {
        try {
            CHANNEL.send(PacketDistributor.ALL.noArg(),
                    new ClientboundProtectEntityPacket(entityId, uuid));
        } catch (Throwable ignored) {
        }
    }

    public static void sendProtect(int entityId) {
        sendProtect(null, entityId);
    }

    public static void requestResync(UUID uuid) {
        try {
            CHANNEL.sendToServer(new ServerboundResyncPacket(uuid));
        } catch (Throwable ignored) {
        }
    }
}
