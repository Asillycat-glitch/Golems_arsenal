package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * 磁盘层防火墙：<b>假设有人会改 jar、改存档</b>。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>jar 清单</b>：启动时给 {@code mods/*.jar} 记 size + mtime + 头 1MB 的 SHA-1，
 *       落到 {@code config/golems_arsenal/disk-manifest.tsv}；</li>
 *   <li><b>只读</b>：给所有 jar 打 {@code dos:readonly}，并起一个 {@link WatchService} 线程盯
 *       {@code mods/}；一旦有人改/换/删，重新打只读并计数（计数在内存里，日志可被掐）；</li>
 *   <li><b>存档镜像</b>：把 {@code anchor}/{@code health_ledger} 两个 SavedData 复制到
 *       {@code config/golems_arsenal/vault/}，下次启动如果线上文件缺失/变小，就从镜像恢复。</li>
 * </ol>
 *
 * <p>为什么不做"真正的文件防火墙"：Java 17 没有 SecurityManager，纯 Java 拿不到文件写拦截；
 * 能做的就是"只读属性 + 监控 + 恢复"。这也正是最坏情况下还能保住的东西。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class DiskShield {

    private static final String[] SAVE_DATA = {
            "golems_arsenal_anchor.dat",
            "golems_arsenal_health_ledger.dat"
    };

    private static final Map<String, String> MANIFEST = new HashMap<>();

    private static Path modsDir;
    private static Path vaultDir;
    private static Path savesDataDir;
    private static WatchService watchService;
    private static Thread watchThread;
    private static volatile boolean running;

    private static long jarsHashed;
    private static long jarsLocked;
    private static long violations;
    private static long notWritable;
    private static long vaultCopies;
    private static long vaultRestores;
    private static String lastViolation = "-";

    private DiskShield() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            start(event.getServer());
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] disk shield failed to start: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    /** 每 30 秒把我们的存档文件镜像一份到 vault。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) {
            return;
        }
        if (event.getServer().getTickCount() % 600 != 0) {
            return;
        }
        mirrorSaves();
    }

    // ------------------------------------------------------------------ 启动

    private static void start(MinecraftServer server) throws IOException {
        if (running) {
            return;
        }
        Path gameDir = Paths.get("").toAbsolutePath();
        modsDir = gameDir.resolve("mods");
        Path configDir = gameDir.resolve("config").resolve("golems_arsenal");
        Files.createDirectories(configDir);
        vaultDir = configDir.resolve("vault");
        Files.createDirectories(vaultDir);
        savesDataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");

        hashMods();
        writeManifest(configDir.resolve("disk-manifest.tsv"));
        // lockJars();  // 2026-11-11：只读会导致 PCL/启动器无法更新 mod，默认关闭
        startWatcher();
        scanTargets();
        restoreSavesIfTruncated();
        mirrorSaves();
        running = true;
        Golems_arsenal.LOGGER.info(
                "[Sanctuary] disk shield on: jars={} locked={} vault={} saveDir={}",
                jarsHashed, jarsLocked, vaultDir, savesDataDir);
    }

    private static void stop() {
        running = false;
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (Throwable ignored) {
        }
        watchService = null;
        watchThread = null;
    }

    // ------------------------------------------------------------------ jar 清单 / 只读

    private static void hashMods() {
        MANIFEST.clear();
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(p -> {
                try {
                    jarsHashed++;
                    MANIFEST.put(p.getFileName().toString(),
                            Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis()
                                    + ":" + headHash(p));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void writeManifest(Path target) {
        StringBuilder sb = new StringBuilder("# jar\tsize:mtime:headsha1\n");
        for (Map.Entry<String, String> e : MANIFEST.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try {
            Files.writeString(target, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void lockJars() {
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(p -> {
                if (setReadOnly(p)) {
                    jarsLocked++;
                } else {
                    notWritable++;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean setReadOnly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String headHash(Path path) {
        try (var in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            long remaining = 1L << 20;
            int read;
            while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                md.update(buf, 0, read);
                remaining -= read;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ 文件监控

    private static void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            modsDir.register(watchService, ENTRY_MODIFY, ENTRY_DELETE, ENTRY_CREATE);
            watchThread = new Thread(DiskShield::watchLoop, "SanctuaryDiskWatch");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] disk watcher unavailable: {}", t.toString());
        }
    }

    private static void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx == null || !ctx.toString().endsWith(".jar")) {
                        continue;
                    }
                    Path changed = modsDir.resolve(ctx.toString());
                    checkOne(changed);
                }
                key.reset();
            } catch (Throwable ignored) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private static void checkOne(Path jar) {
        String name = jar.getFileName().toString();
        String recorded = MANIFEST.get(name);
        String now;
        try {
            now = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis() + ":" + headHash(jar);
        } catch (Throwable t) {
            violations++;
            lastViolation = name + " (missing)";
            Golems_arsenal.LOGGER.error("[Sanctuary] disk_violation missing jar={}", name);
            return;
        }
        if (recorded != null && !recorded.equals(now)) {
            violations++;
            lastViolation = name;
            Golems_arsenal.LOGGER.error("[Sanctuary] disk_violation modified jar={} was={} now={}",
                    name, recorded, now);
            setReadOnly(jar);
        }
    }

    // ------------------------------------------------------------------ 数据驱动目标集

    /**
     * 扫 {@code mods/*.jar} 里的每个 class，凡是引用了这些原语的都作为可疑类推给 agent。
     *
     * <p>原语清单只描述"能干什么"，不描述"是谁"——所以这份名单天然不针对任何模组。</p>
     */
    private static final String[] TARGET_MARKERS = {
            // ---- 数据面原语 ----
            "f_146795_",                 // removalReason 直写
            "f_135391_",                 // DataItem.value 直写
            "f_146801_",                 // levelCallback 替换
            "removeServerLevelEntity",   // 拆索引
            "setRemovalReason",          // 移除
            "onRemovedFromWorld",        // 强制移除
            // ---- 能力面（换名字也躲不掉）----
            "redefineClasses",           // 运行期重定义
            "retransformClasses",        // 运行期重变换
            "ClassFileTransformer",      // 自带类变换器
            "jdk/internal/misc/Unsafe",  // JDK 内部 Unsafe
            "sun/misc/Unsafe",           // 老 Unsafe
            "Unsafe.put",                // 直接写内存
            "ClassRestore",              // 类恢复机制
            "Instrumentation"            // 拿到 java agent 句柄
    };

    /** 自家代码、原版、框架一律不是目标 —— 否则会把自己的类也改写掉（2026-11-10 C1 崩溃）。 */
    private static boolean isExcludedTarget(String dottedName) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (dottedName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] EXCLUDED_PREFIXES = {
            "a_silly_cat.", "sanctuary.",
            "net.minecraft.", "net.minecraftforge.", "com.mojang.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.spongepowered.", "org.objectweb.asm.",
            "dev.xkmc.modulargolems.", "dev.xkmc.l2"
    };

    private static void scanTargets() {
        long started = System.currentTimeMillis();
        java.util.Set<String> found = new java.util.HashSet<>();
        try (Stream<Path> jars = Files.list(modsDir)) {
            jars.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(jar -> {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class") || entry.getSize() > 400_000L) {
                            continue;
                        }
                        byte[] bytes;
                        try (var in = zip.getInputStream(entry)) {
                            bytes = in.readAllBytes();
                        }
                        String entryName = entry.getName().replace('/', '.');
                        if (isExcludedTarget(entryName)) {
                            continue;
                        }
                        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                        for (String marker : TARGET_MARKERS) {
                            if (text.contains(marker)) {
                                found.add(entry.getName()
                                        .replace('/', '.')
                                        .substring(0, entry.getName().length() - ".class".length()));
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        targetsFound = found.size();
        AgentBootstrap.pushTargets(found);
        Golems_arsenal.LOGGER.info("[Sanctuary] data-driven scan: {} class(es) in {} ms",
                found.size(), System.currentTimeMillis() - started);
    }

    private static int targetsFound;

    // ------------------------------------------------------------------ 存档镜像

    private static void mirrorSaves() {
        try {
            Files.createDirectories(vaultDir);
            for (String file : SAVE_DATA) {
                Path live = savesDataDir.resolve(file);
                if (Files.isRegularFile(live)) {
                    Files.copy(live, vaultDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    vaultCopies++;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 线上文件缺失或明显变小时，从镜像恢复（只在启动时做，避免覆盖正在写的存档）。 */
    private static void restoreSavesIfTruncated() {
        for (String file : SAVE_DATA) {
            try {
                Path live = savesDataDir.resolve(file);
                Path vault = vaultDir.resolve(file);
                if (!Files.isRegularFile(vault)) {
                    continue;
                }
                boolean missing = !Files.isRegularFile(live);
                boolean shrunk = !missing && Files.size(live) < Files.size(vault) * 0.5;
                if (missing || shrunk) {
                    Files.copy(vault, live, StandardCopyOption.REPLACE_EXISTING);
                    vaultRestores++;
                    Golems_arsenal.LOGGER.warn("[Sanctuary] disk_restore save data file={} missing={} shrunk={}",
                            file, missing, shrunk);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static String statusLine() {
        return "[sanctuary] disk=" + (running ? "on" : "off")
                + " jars=" + jarsHashed
                + " locked=" + jarsLocked
                + " notWritable=" + notWritable
                + " violations=" + violations
                + " lastViolation=" + lastViolation
                + " vaultCopies=" + vaultCopies
                + " vaultRestores=" + vaultRestores
                + " targets=" + targetsFound;
    }

    /**
     * 手动核对一遍 jar 清单 —— 用来<B>主动测试</B>"改 jar 会不会被抓到"。
     *
     * <p>试法：随便改一个 jar 的 mtime（甚至替换内容），然后 {@code /ga guard disk verify}，
     * {@code changed} 应该加 1，并且那个 jar 会被重新打上只读。</p>
     */
    public static String verifyNow() {
        if (MANIFEST.isEmpty()) {
            return "[sanctuary] disk verify: no manifest (shield off?)";
        }
        int checked = 0;
        int changed = 0;
        int missing = 0;
        for (Map.Entry<String, String> entry : MANIFEST.entrySet()) {
            Path jar = modsDir.resolve(entry.getKey());
            try {
                if (!Files.isRegularFile(jar)) {
                    missing++;
                    lastViolation = entry.getKey() + " (missing)";
                    continue;
                }
                String now = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis()
                        + ":" + headHash(jar);
                checked++;
                if (!now.equals(entry.getValue())) {
                    changed++;
                    violations++;
                    lastViolation = entry.getKey();
                    setReadOnly(jar);
                }
            } catch (Throwable ignored) {
            }
        }
        return "[sanctuary] disk verify: checked=" + checked + " changed=" + changed
                + " missing=" + missing + " violations=" + violations
                + " lastViolation=" + lastViolation;
    }
}
