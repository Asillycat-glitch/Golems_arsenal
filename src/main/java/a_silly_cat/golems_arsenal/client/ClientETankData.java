package a_silly_cat.golems_arsenal.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side cache of per-golem E-tank contents, synced from the server. */
public final class ClientETankData {
    public record ETankInfo(int stored, int capacity) {
    }

    private static final Map<Integer, ETankInfo> TANKS = new ConcurrentHashMap<>();

    public static void put(int entityId, int stored, int capacity) {
        if (capacity <= 0) {
            TANKS.remove(entityId);
        } else {
            TANKS.put(entityId, new ETankInfo(stored, capacity));
        }
    }

    public static ETankInfo get(int entityId) {
        return TANKS.get(entityId);
    }

    private ClientETankData() {
    }
}
