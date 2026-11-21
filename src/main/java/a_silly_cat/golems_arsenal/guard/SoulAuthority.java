package a_silly_cat.golems_arsenal.guard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Soul 的离线 authority 雏形：只接收不可变快照，不持有 Entity/Level。
 *
 * <p>server thread 仍然做同步门判定；这个线程只做审计/持久化/后续独立进程的入口。</p>
 */
public final class SoulAuthority {

    public record Snapshot(UUID soulId, UUID owner, float health, float maxHealth,
                           boolean died, long revision, long receivedAt) {
    }

    private static final Map<UUID, Snapshot> LATEST = new ConcurrentHashMap<>();
    private static final AtomicLong PUBLISHED = new AtomicLong();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "golems-arsenal-soul-authority");
        t.setDaemon(true);
        return t;
    });

    private SoulAuthority() {
    }

    public static void publish(GolemSoul soul) {
        if (soul == null) {
            return;
        }
        Snapshot snapshot = new Snapshot(soul.soulId, soul.owner, soul.health,
                soul.maxHealth, soul.died, soul.revision, System.currentTimeMillis());
        EXECUTOR.execute(() -> {
            LATEST.put(snapshot.soulId(), snapshot);
            PUBLISHED.incrementAndGet();
        });
    }

    public static Snapshot latest(UUID soulId) {
        return soulId == null ? null : LATEST.get(soulId);
    }

    public static long published() {
        return PUBLISHED.get();
    }

    public static int size() {
        return LATEST.size();
    }
}
