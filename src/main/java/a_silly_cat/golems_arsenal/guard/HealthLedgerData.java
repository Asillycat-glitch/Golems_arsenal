package a_silly_cat.golems_arsenal.guard;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 只读审计的持久化影子：UUID → 上次的 expected / raw / class / dimension。
 *
 * <p>它不保存“真实血量”，也不回写实体；只是在重启后能继续对账，并识别
 * {@code REMOVED_WITHOUT_DEATH} / {@code CLASS_CHANGED}。存档数据坏了也只是审计缺失，
 * 不影响游戏。</p>
 */
public final class HealthLedgerData extends SavedData {

    private static final String NAME = "golems_arsenal_health_ledger";

    private final Map<UUID, Entry> entries = new HashMap<>();

    public static HealthLedgerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                HealthLedgerData::load, HealthLedgerData::new, NAME);
    }

    private static HealthLedgerData load(CompoundTag tag) {
        HealthLedgerData data = new HealthLedgerData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                UUID id = UUID.fromString(entry.getString("id"));
                data.entries.put(id, new Entry(
                        entry.getFloat("expected"),
                        entry.getFloat("raw"),
                        entry.getString("class"),
                        entry.getString("dimension")));
            } catch (IllegalArgumentException ignored) {
                // 单条坏数据不影响其它条目。
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry value = e.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putString("id", e.getKey().toString());
            entry.putFloat("expected", value.expected());
            entry.putFloat("raw", value.lastRaw());
            entry.putString("class", value.className());
            entry.putString("dimension", value.dimension());
            list.add(entry);
        }
        tag.put("entries", list);
        return tag;
    }

    public synchronized Entry get(UUID id) {
        return entries.get(id);
    }

    public synchronized void put(UUID id, Entry entry) {
        entries.put(id, entry);
        setDirty();
    }

    public synchronized void remove(UUID id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }

    /** 身份轮换：持久化条目换 key。 */
    public synchronized void rename(UUID old, UUID fresh) {
        Entry entry = entries.remove(old);
        if (entry != null) {
            entries.put(fresh, entry);
            setDirty();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public record Entry(float expected, float lastRaw, String className, String dimension) {
    }
}
