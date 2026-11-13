package a_silly_cat.golems_arsenal.testbench;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * P0 血量观测：被追踪的实体与角色（PRIVATE TESTBENCH）。
 *
 * <p>只存"谁在被观察"，不存任何血量、不参与任何伤害/治疗/移除处理。角色只有两个：
 * {@code control}（对照组）与 {@code test}（实验组）。P0 阶段两组都不挂 Guard ——
 * 这个阶段量的是"同一套 L2 管线下，两边是否完全一样"，为 P1 的影子账本提供基准。</p>
 *
 * <p>数据存在存档级 {@link SavedData} 里，所以换维度、重进世界都还能找回同一个实体；
 * 但每次服务器启动都会把运行态 {@link #active} 复位成 false（避免忘关）。</p>
 */
public class HealthTraceData extends SavedData {

    public static final String DATA_NAME = "golems_arsenal_health_trace";

    /** 上限只是防止误操作时把整世界的实体都塞进来。 */
    private static final int MAX_TRACKED = 64;

    public enum Role {
        CONTROL("control"),
        TEST("test"),
        OTHER("other");

        private final String id;

        Role(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Role byId(String id) {
            if (id == null) {
                return null;
            }
            for (Role role : values()) {
                if (role.id.equalsIgnoreCase(id)) {
                    return role;
                }
            }
            return null;
        }
    }

    /** P2：每实体 Guard 档位。OFF 永远不拦；SHADOW 只记 would-block；ENFORCE 真拦/回滚。 */
    public enum GuardMode {
        OFF("off"),
        SHADOW("shadow"),
        ENFORCE("enforce");

        private final String id;

        GuardMode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static GuardMode byId(String id) {
            if (id == null) {
                return OFF;
            }
            for (GuardMode m : values()) {
                if (m.id.equalsIgnoreCase(id)) {
                    return m;
                }
            }
            return OFF;
        }

        /** 命令用：非法名字返回 null，而不是静默变 OFF。 */
        public static GuardMode byIdOrNull(String id) {
            if (id == null) {
                return null;
            }
            for (GuardMode m : values()) {
                if (m.id.equalsIgnoreCase(id)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** 一个被追踪的实体。uuid 是主键；type/name/dim 是快照，实体卸载后日志里仍然可读。 */
    public static final class Tracked {
        public final UUID uuid;
        public Role role;
        public String type;
        public String name;
        public String dimension;
        /** P2：只对 test 生效；control 永远 OFF。 */
        public GuardMode guardMode = GuardMode.OFF;
        /** 当前实体 id；-1 表示这一 tick 找不到实体（卸载/未加载）。 */
        public int entityId = -1;
        public long lastSeen;

        Tracked(UUID uuid, Role role) {
            this.uuid = uuid;
            this.role = role;
        }
    }

    /** 存档里持久化的追踪表。用 LinkedHashMap 只为输出顺序稳定。 */
    private final Map<UUID, Tracked> tracked = new LinkedHashMap<>();

    /** 是否观察所有实体（默认关；开了以后 setHealth/getHealth 会非常多，仅用于摸底）。 */
    public boolean trackAll;
    /** getHealth 采样率：每 N 次记录 1 次调用者。setHealth 永远全记（它本来就少）。 */
    public int sampleEvery = 256;
    /** 是否记录 getHealth（关掉只留 setHealth/事件/影子读数）。 */
    public boolean logGetHealth = true;
    /** P1 影子账本：只读，记录“事件之外的血量变化”。默认开。 */
    public boolean ledgerEnabled = true;

    /** 运行态，不落盘。 */
    public boolean active;

    public static HealthTraceData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return server.overworld().getDataStorage()
                .computeIfAbsent(HealthTraceData::load, HealthTraceData::new, DATA_NAME);
    }

    public static HealthTraceData load(CompoundTag tag) {
        HealthTraceData ans = new HealthTraceData();
        ans.trackAll = tag.getBoolean("trackAll");
        ans.sampleEvery = tag.contains("sampleEvery") ? tag.getInt("sampleEvery") : 256;
        ans.logGetHealth = !tag.contains("logGetHealth") || tag.getBoolean("logGetHealth");
        ans.ledgerEnabled = !tag.contains("ledgerEnabled") || tag.getBoolean("ledgerEnabled");
        ListTag list = tag.getList("tracked", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            try {
                UUID uuid = UUID.fromString(c.getString("uuid"));
                Role role = Role.byId(c.getString("role"));
                if (role == null) {
                    role = Role.OTHER;
                }
                Tracked t = new Tracked(uuid, role);
                t.type = c.getString("type");
                t.name = c.getString("name");
                t.dimension = c.getString("dim");
                t.guardMode = GuardMode.byId(c.getString("guard"));
                t.lastSeen = c.getLong("seen");
                ans.tracked.put(uuid, t);
            } catch (RuntimeException ignored) {
                // 坏一条不影响其它；测试台不需要为此中断。
            }
        }
        return ans;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("trackAll", trackAll);
        tag.putInt("sampleEvery", sampleEvery);
        tag.putBoolean("logGetHealth", logGetHealth);
        tag.putBoolean("ledgerEnabled", ledgerEnabled);
        ListTag list = new ListTag();
        for (Tracked t : tracked.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", t.uuid.toString());
            c.putString("role", t.role.id());
            c.putString("type", t.type == null ? "" : t.type);
            c.putString("name", t.name == null ? "" : t.name);
            c.putString("dim", t.dimension == null ? "" : t.dimension);
            c.putString("guard", t.guardMode.id());
            c.putLong("seen", t.lastSeen);
            list.add(c);
        }
        tag.put("tracked", list);
        return tag;
    }

    // ------------------------------------------------------------------ 追踪

    public Tracked get(UUID uuid) {
        return tracked.get(uuid);
    }

    public Tracked track(LivingEntity entity, Role role) {
        Tracked t = tracked.get(entity.getUUID());
        if (t == null) {
            if (tracked.size() >= MAX_TRACKED) {
                return null;
            }
            t = new Tracked(entity.getUUID(), role);
            tracked.put(t.uuid, t);
        }
        t.role = role;
        t.type = typeId(entity);
        t.name = entity.getName().getString();
        t.dimension = entity.level().dimension().location().toString();
        t.entityId = entity.getId();
        t.lastSeen = entity.level().getGameTime();
        setDirty();
        return t;
    }

    public boolean untrack(UUID uuid) {
        boolean removed = tracked.remove(uuid) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public int clearTracked() {
        int n = tracked.size();
        if (n > 0) {
            tracked.clear();
            setDirty();
        }
        return n;
    }

    public Collection<Tracked> tracked() {
        return tracked.values();
    }

    public int setGuardMode(Role role, GuardMode mode) {
        int n = 0;
        for (Tracked t : tracked.values()) {
            if (t.role != role) {
                continue;
            }
            if (role == Role.CONTROL && mode != GuardMode.OFF) {
                continue;
            }
            t.guardMode = mode;
            n++;
        }
        if (n > 0) {
            setDirty();
        }
        return n;
    }

    /** 应急/服务器启动：所有实体 Guard 回 OFF。 */
    public int guardAllOff() {
        int n = 0;
        for (Tracked t : tracked.values()) {
            if (t.guardMode != GuardMode.OFF) {
                t.guardMode = GuardMode.OFF;
                n++;
            }
        }
        if (n > 0) {
            setDirty();
        }
        return n;
    }

    public int size() {
        return tracked.size();
    }

    public Tracked byEntityId(int id) {
        for (Tracked t : tracked.values()) {
            if (t.entityId == id) {
                return t;
            }
        }
        return null;
    }

    /** 某一角色下每种实体类型的数量；用于 start 前验证 control/test 是不是同一种傀儡。 */
    public Map<String, Integer> roleTypes(Role role) {
        Map<String, Integer> map = new TreeMap<>();
        for (Tracked t : tracked.values()) {
            if (t.role == role) {
                map.merge(t.type == null || t.type.isEmpty() ? "?" : t.type, 1, Integer::sum);
            }
        }
        return map;
    }

    /**
     * 硬错误：两边都有数据但类型集合不同（例如 control 是狗傀儡、test 是金属傀儡）。
     * 单边（只有 control 或只有 test）不在这里拒绝，方便 P3 只观察一个测试玩家；
     * 由 {@link #pairWarning()} 提示。
     */
    public String pairMismatch() {
        Map<String, Integer> control = roleTypes(Role.CONTROL);
        Map<String, Integer> test = roleTypes(Role.TEST);
        if (control.isEmpty() || test.isEmpty()) {
            return null;
        }
        if (!control.keySet().equals(test.keySet())) {
            return "control/test 类型不一致：control=" + control + " test=" + test
                    + "；P0 只比较同类型实体。先 /ga health untrack all，再各登记一个同类型傀儡。";
        }
        return null;
    }

    /** 软提示：缺一组，或两边数量不同。 */
    public String pairWarning() {
        Map<String, Integer> control = roleTypes(Role.CONTROL);
        Map<String, Integer> test = roleTypes(Role.TEST);
        if (control.isEmpty() && test.isEmpty()) {
            return "没有 control/test 追踪";
        }
        if (control.isEmpty()) {
            return "只有 test，没有 control；compare 会缺一组";
        }
        if (test.isEmpty()) {
            return "只有 control，没有 test；compare 会缺一组";
        }
        if (!control.equals(test)) {
            return "control/test 数量不同：" + control + " vs " + test
                    + "（compare 用均值，建议 1:1）";
        }
        return null;
    }

    /** 一行可读的配对检查，给 /ga health list 用。 */
    public String pairSummary() {
        Map<String, Integer> control = roleTypes(Role.CONTROL);
        Map<String, Integer> test = roleTypes(Role.TEST);
        String mismatch = pairMismatch();
        String warning = pairWarning();
        String state = mismatch != null ? "BAD: " + mismatch
                : warning != null ? "WARN: " + warning : "OK";
        return "control=" + control + " test=" + test + "  pair=" + state;
    }

    /** 把 uuid 解析回实体；维度/区块没加载时返回 null，不强行加载区块。 */
    public LivingEntity resolve(MinecraftServer server, Tracked t) {
        ServerLevel level = level(server, t.dimension);
        if (level != null) {
            Entity entity = level.getEntity(t.uuid);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        // 玩家/傀儡跨维度后，快照里的 dimension 会过期；在已加载的维度里再找一遍。
        // 只在原维度找不到时才遍历，正常路径仍然是一次 O(1) 查找。
        for (ServerLevel other : server.getAllLevels()) {
            if (other == level) {
                continue;
            }
            Entity entity = other.getEntity(t.uuid);
            if (entity instanceof LivingEntity living) {
                String newDim = other.dimension().location().toString();
                if (!newDim.equals(t.dimension)) {
                    t.dimension = newDim;
                    setDirty();
                }
                t.entityId = entity.getId();
                t.name = entity.getName().getString();
                t.type = typeId(entity);
                return living;
            }
        }
        return null;
    }

    /** 每 20 tick 刷新一次实体 id/名字；不加载区块，只查已加载的实体表。 */
    public void refresh(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Tracked t : tracked.values()) {
            LivingEntity entity = resolve(server, t);
            if (entity == null) {
                t.entityId = -1;
                continue;
            }
            t.entityId = entity.getId();
            t.name = entity.getName().getString();
            t.type = typeId(entity);
            t.lastSeen = now;
        }
    }

    public static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = dimension == null ? null : ResourceLocation.tryParse(dimension);
        if (id == null) {
            return server.overworld();
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    public static String typeId(Entity entity) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id == null ? entity.getType().getDescriptionId() : id.toString();
    }

    public static String dimensionId(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    /** 只给日志/命令显示用，不参与判定。 */
    public static String describe(Tracked t) {
        return t.role.id() + " " + t.type + " " + (t.name == null ? "?" : t.name)
                + " uuid=" + t.uuid + " eid=" + t.entityId + " guard=" + t.guardMode.id();
    }
}
