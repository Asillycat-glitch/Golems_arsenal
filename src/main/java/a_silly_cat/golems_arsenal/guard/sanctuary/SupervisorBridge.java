package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 从 mod jar 释放 sanctuary-supervisor.jar，并作为独立进程启动。
 * supervisor 只监控游戏 PID/写 journal，不碰世界；因此不会引入跨线程世界访问。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SupervisorBridge {

    private static volatile boolean started;

    private SupervisorBridge() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start();
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("golems_arsenal").resolve("supervisor");
            Files.createDirectories(dir);
            Path jar = dir.resolve("sanctuary-supervisor.jar");
            try (InputStream in = SupervisorBridge.class.getResourceAsStream("/sanctuary-supervisor.jar")) {
                if (in == null) {
                    Golems_arsenal.LOGGER.warn("[Sanctuary] supervisor resource missing");
                    return;
                }
                Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            Path java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
            if (!Files.isRegularFile(java)) {
                java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            }
            ProcessBuilder pb = new ProcessBuilder(
                    java.toString(),
                    "-jar", jar.toString(),
                    "--pid", Long.toString(ProcessHandle.current().pid()),
                    "--journal", dir.resolve("supervisor.log").toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("supervisor.out").toFile()));
            pb.start();
            Golems_arsenal.LOGGER.info("[Sanctuary] supervisor started from {}", jar);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] supervisor start failed: {}", t.toString());
        }
    }
}
