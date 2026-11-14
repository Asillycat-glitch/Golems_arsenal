package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 把 {@link MinionEnergyReloadListener} 挂到服务器的数据包 reload 流程。 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MinionEnergyReloadHandler {

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MinionEnergyReloadListener());
    }

    private MinionEnergyReloadHandler() {
    }
}
