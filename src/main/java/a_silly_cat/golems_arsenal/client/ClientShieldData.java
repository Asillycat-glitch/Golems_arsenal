package a_silly_cat.golems_arsenal.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side cache of per-golem backup-energy shields, synced from the server. */
public final class ClientShieldData {
    public record ShieldInfo(float shield, float max) {
    }

    private static final Map<Integer, ShieldInfo> SHIELDS = new ConcurrentHashMap<>();

    public static void put(int entityId, float shield, float max) {
        if (max <= 0) {
            SHIELDS.remove(entityId);
        } else {
            SHIELDS.put(entityId, new ShieldInfo(shield, max));
        }
    }

    public static ShieldInfo get(int entityId) {
        return SHIELDS.get(entityId);
    }

    private ClientShieldData() {
    }
}