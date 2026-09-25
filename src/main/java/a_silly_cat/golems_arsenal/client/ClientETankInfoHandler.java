package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.events.event.GolemInfoEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Adds the E-tank storage line to the golem info overlay (same panel as the backup-energy shield). */
@Mod.EventBusSubscriber(modid = "golems_arsenal", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientETankInfoHandler {

    @SubscribeEvent
    public static void onGolemInfo(GolemInfoEvent event) {
        AbstractGolemEntity<?, ?> golem = event.getGolem();
        if (!golem.getModifiers().containsKey(GolemUpgrades.E_TANK.get())) {
            return;
        }
        ClientETankData.ETankInfo info = ClientETankData.get(golem.getId());
        if (info == null || info.capacity() <= 0) {
            return;
        }
        event.addLine(Component.translatable("info.golems_arsenal.e_tank",
                        info.stored(), info.capacity())
                .withStyle(ChatFormatting.GOLD));
    }

    private ClientETankInfoHandler() {
    }
}
