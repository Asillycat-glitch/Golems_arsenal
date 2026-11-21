package a_silly_cat.golems_arsenal.guard.index;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 只负责安装/重申世界索引门。真正的判定在 {@link WorldIndexGuard}。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class WorldIndexEvents {

    private static int ticks;
    private static int targetTicks;

    private WorldIndexEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WorldIndexGuard.ensureAll(event.getServer());
        Golems_arsenal.LOGGER.info("[IndexGuard] installed on server start: {}", WorldIndexGuard.status());
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldIndexGuard.ensure(level);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (++ticks % 20 != 0) {
            return;
        }
        WorldIndexGuard.ensureAll(server);
        // 清掉敌对生物里 target 已经移除/不在 level 的陈旧锁定。
        if (++targetTicks % 5 == 0) {
            a_silly_cat.golems_arsenal.guard.sanctuary.GhostTargetGuard.sweep(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // WeakHashMap keys are levels; drop them explicitly so a new world starts clean.
        WorldIndexGuard.clear();
    }
}
