package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.events.event.GolemInfoEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Adds the backup-energy shield line to the golem info overlay. */
@Mod.EventBusSubscriber(modid = "golems_arsenal", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientShieldInfoHandler {

    @SubscribeEvent
    public static void onGolemInfo(GolemInfoEvent event) {
        AbstractGolemEntity<?, ?> golem = event.getGolem();
        if (!golem.getModifiers().containsKey(GolemUpgrades.BACKUP_ENERGY.get())) {
            return;
        }
        ClientShieldData.ShieldInfo info = ClientShieldData.get(golem.getId());
        if (info == null || info.max() <= 0) {
            return;
        }
        event.addLine(Component.translatable("info.golems_arsenal.backup_energy",
                String.format("%.1f", info.shield()), String.format("%.1f", info.max()))
                .withStyle(ChatFormatting.AQUA));
    }

    private ClientShieldInfoHandler() {
    }
}