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

    /**
     * 快照里也不保留明文 health/maxHealth：authority 线程拿到的也是 SealedFloat。
     * 这不是“防任意 native”，只是避免在另一个长期存活的容器里复制明文。
     */
    public static final class Snapshot {
        private final UUID soulId;
        private final UUID owner;
        private final SealedFloat health = new SealedFloat();
        private final SealedFloat maxHealth = new SealedFloat();
        private final boolean died;
        private final long revision;
        private final long receivedAt;

        Snapshot(UUID soulId, UUID owner, float health, float maxHealth,
                 boolean died, long revision, long receivedAt) {
            this.soulId = soulId;
            this.owner = owner;
            this.health.set(health);
            this.maxHealth.set(maxHealth);
            this.died = died;
            this.revision = revision;
            this.receivedAt = receivedAt;
        }

        public UUID soulId() {
            return soulId;
        }

        public UUID owner() {
            return owner;
        }

        public float health() {
            return health.get();
        }

        public float maxHealth() {
            return maxHealth.get();
        }

        public boolean died() {
            return died;
        }

        public long revision() {
            return revision;
        }

        public long receivedAt() {
            return receivedAt;
        }
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
        Snapshot snapshot = new Snapshot(soul.soulId, soul.owner, soul.health(),
                soul.maxHealth(), soul.died, soul.revision, System.currentTimeMillis());
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
