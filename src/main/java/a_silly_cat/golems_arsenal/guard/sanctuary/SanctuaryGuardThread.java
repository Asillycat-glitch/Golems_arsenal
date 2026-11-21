package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立守卫线程：不直接碰世界，只做两件事：
 * <ul>
 *   <li>以固定频率把“对账任务”压回服务器线程（server.execute）；</li>
 *   <li>用 pending 标志防止服务器线程卡住时任务无限堆积。</li>
 * </ul>
 *
 * <p>真正改实体/容器的仍是服务器线程，所以不会出现“后台线程改 ServerLevel”的线程安全问题。
 * 它存在的意义是：对面开线程/在 tick 之间清理时，我们不必等下一个 tick 才发起对账。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SanctuaryGuardThread {

    private static final long INTERVAL_MS = 1000L;
    private static volatile boolean running;
    private static volatile MinecraftServer server;
    private static Thread thread;

    private SanctuaryGuardThread() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        // 不在世界加载/JEI 配方构建期间启动：服务器线程最忙时 100ms 对账会拖死它。
        server = event.getServer();
        Golems_arsenal.LOGGER.info("[Sanctuary] guard thread deferred until first player login");
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        MinecraftServer s = server;
        if (s != null) {
            start(s);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        stop();
    }

    private static synchronized void start(MinecraftServer s) {
        if (running || s == null) {
            return;
        }
        server = s;
        running = true;
        thread = new Thread(SanctuaryGuardThread::loop, "SanctuaryGuardThread");
        thread.setDaemon(true);
        thread.start();
        Golems_arsenal.LOGGER.info("[Sanctuary] guard thread started interval={}ms", INTERVAL_MS);
    }

    private static synchronized void stop() {
        running = false;
        server = null;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop() {
        AtomicBoolean pending = new AtomicBoolean(false);
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            MinecraftServer s = server;
            if (s == null || s.isStopped()) {
                return;
            }
            if (!pending.compareAndSet(false, true)) {
                continue;
            }
            try {
                s.execute(() -> {
                    try {
                        Anchor.threadPulse(s);
                    } finally {
                        pending.set(false);
                    }
                });
            } catch (Throwable t) {
                pending.set(false);
            }
        }
    }
}
