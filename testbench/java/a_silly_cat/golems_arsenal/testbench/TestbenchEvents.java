package a_silly_cat.golems_arsenal.testbench;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 测试台的 Forge 侧接线（PRIVATE TESTBENCH）：tick 末尾重发命中、服务器启动复位开关、报告落盘。
 */
@Mod.EventBusSubscriber(modid = "golems_arsenal")
public final class TestbenchEvents {

    /**
     * 必须晚于 l2 的缓存清理（{@code AttackEventHandler.onServerTick} 是默认优先级）。
     * 清完缓存再重发，就是一次干净、无嵌套的命中。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        TestbenchCore.flush(now);
        TestbenchCore.restoreExpired(now);
    }

    /** 每次服务器启动强制复位：忘了关也不会污染正常游玩。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        DebugFlags flags = DebugFlags.get(event.getServer());
        if (flags != null) {
            flags.resetAll();
        }
        PrivateTestbench.LOGGER.warn("测试台开关已复位为全关（用 /ga debug enable 开启）。");
    }

    public static void printSummary(net.minecraft.commands.CommandSourceStack src) {
        TestbenchCore.print(src);
    }

    /** 日志 + 聊天栏广播（扫档过程要让玩家看得见下一步做什么）。 */
    public static void broadcast(String msg) {
        PrivateTestbench.LOGGER.info("[GA-Testbench] {}", msg);
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("[GA-Testbench] " + msg), false);
        }
    }

    private static Path debugDir() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("golems_arsenal").resolve("debug");
    }

    /** 扫档结果落盘：条件 + 每档一行。 */
    public static Path writeSweep(TestbenchCore.Sweep sweep) {
        Path dir = debugDir();
        Path file = dir.resolve("sweep-" + System.currentTimeMillis() + ".json");
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"hitsPerTier\": ").append(sweep.hitsPerTier).append(",\n");
            sb.append("  \"conditions\": \"").append(sweep.conditions.replace("\"", "'")).append("\",\n");
            sb.append("  \"rows\": [\n");
            for (int i = 0; i < sweep.rows.size(); i++) {
                sb.append("    \"").append(sweep.rows.get(i).replace("\"", "'")).append("\"")
                        .append(i + 1 < sweep.rows.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n}\n");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            return file;
        } catch (IOException e) {
            PrivateTestbench.LOGGER.error("写扫档报告失败", e);
            return null;
        }
    }

    /** 把当前一轮统计写成 JSON，放到 {@code config/golems_arsenal/debug/}。 */
    public static Path writeReport(MinecraftServer server) {
        Path dir = debugDir();
        Path file = dir.resolve("report-" + System.currentTimeMillis() + ".json");
        try {
            Files.createDirectories(dir);
            DebugFlags flags = DebugFlags.get(server);
            String head = "// testbench conditions: "
                    + (flags == null ? "n/a" : flags.exportLine()) + "\n";
            Files.writeString(file, head + TestbenchCore.toJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE);
            return file;
        } catch (IOException e) {
            PrivateTestbench.LOGGER.error("写报告失败", e);
            return null;
        }
    }

    private TestbenchEvents() {
    }
}
