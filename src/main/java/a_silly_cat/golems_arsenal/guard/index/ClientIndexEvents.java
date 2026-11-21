package a_silly_cat.golems_arsenal.guard.index;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端登录/换世界时把 ClientLevel 的实体容器换成通用门。 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientIndexEvents {

    private static int ticks;
    private static final Map<UUID, Long> REQUESTED = new HashMap<>();

    private ClientIndexEvents() {
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        install();
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) {
            WorldIndexGuard.installClient(level);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks % 20 == 0) {
            install();
            requestMissing();
        }
    }

    private static void requestMissing() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        for (UUID id : Anchor.protectedUuids()) {
            try {
                boolean present = false;
                for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                    if (entity.getUUID().equals(id)) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    continue;
                }
                Long last = REQUESTED.get(id);
                if (last != null && now - last < 100L) {
                    continue;
                }
                REQUESTED.put(id, now);
                Golems_arsenal.LOGGER.info("[Sanctuary] client resync request uuid={}", id);
                ModNetwork.requestResync(id);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void install() {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel != null) {
            WorldIndexGuard.installClient(clientLevel);
        }
    }
}
