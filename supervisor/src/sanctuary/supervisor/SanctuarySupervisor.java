package sanctuary.supervisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

/**
 * 独立于游戏 JVM 的最小 supervisor。
 * 只监控游戏 PID；游戏进程消失后写 journal，可选执行重启命令。
 * 它不直接改 Minecraft 世界，也不依赖游戏类。
 */
public final class SanctuarySupervisor {

    private SanctuarySupervisor() {
    }

    public static void main(String[] args) throws Exception {
        long pid = -1L;
        Path journal = Path.of("sanctuary-supervisor.log");
        String restart = null;
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--pid" -> pid = Long.parseLong(args[++i]);
                case "--journal" -> journal = Path.of(args[++i]);
                case "--restart" -> restart = args[++i];
                default -> {
                }
            }
        }
        if (pid <= 0L) {
            log(journal, "missing --pid");
            return;
        }
        log(journal, "supervisor started gamePid=" + pid);
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            log(journal, "game process not found pid=" + pid);
            return;
        }
        ProcessHandle game = handle.get();
        while (game.isAlive()) {
            Thread.sleep(1000L);
        }
        log(journal, "game process ended pid=" + pid);
        if (restart != null && !restart.isBlank()) {
            try {
                new ProcessBuilder("cmd.exe", "/c", restart)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(journal.toFile()))
                        .start();
                log(journal, "restart requested");
            } catch (Throwable t) {
                log(journal, "restart failed: " + t);
            }
        }
    }

    private static void log(Path file, String message) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    Instant.now() + " " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
