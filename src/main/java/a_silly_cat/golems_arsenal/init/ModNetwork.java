package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.network.ClientboundFlipPacket;
import a_silly_cat.golems_arsenal.network.ClientboundETankPacket;
import a_silly_cat.golems_arsenal.network.ClientboundShieldPacket;
import a_silly_cat.golems_arsenal.network.ClientboundStancePacket;
import a_silly_cat.golems_arsenal.network.ServerboundAuthorizePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

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
    }
}
