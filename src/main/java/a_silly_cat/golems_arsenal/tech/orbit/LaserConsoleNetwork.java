package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 激光发射台的**全局记录**。
 *
 * <p>为什么需要它：发射台要求「全局生效」——玩家在基地放一台，跑到很远的地方打架时
 * 它仍然应该授权天基打击。方块实体只存在于加载中的区块，所以真正决定“世界上有哪些
 * 启用的发射台”的必须是这份与区块无关的 {@link SavedData}。
 *
 * <p><b>同步方向</b>：方块实体是配置的编辑入口；每次 {@code setChanged()} 都把自己的
 * 快照写进这里。区块卸载后方块实体消失，但记录还在；重新加载时方块实体从自己的 NBT
 * 恢复字段，再覆盖写一次记录。
 *
 * <p><b>维度</b>：记录带维度字符串；查询只匹配目标所在维度（跨维度遥控在观感上说不通）。
 *
 * <p>记录用 UUID 作键，方块实体的 UUID 存在自己的 NBT 里。这样同一坐标拆掉重放会得到
 * 一条新记录，不会把旧配置错误地继承下来。
 */
public class LaserConsoleNetwork extends SavedData {

    private static final String DATA_NAME = Golems_arsenal.MODID + "_laser_consoles";

    private static final String TAG_CONSOLE = "Consoles";
    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_PLATFORM = "Platform";
    private static final String TAG_ENABLED = "Enabled";
    private static final String TAG_FRIENDLY = "Friendly";
    private static final String TAG_ENERGY_TIER = "EnergyTier";
    private static final String TAG_COOLDOWN_TIER = "CooldownTier";
    private static final String TAG_SAFE_RADIUS = "SafeRadius";

    /**
     * 一条发射台记录。不可变；配置变化时整体替换。
     */
    public record Console(UUID id, String dimension, BlockPos pos, UUID owner, UUID platformId,
                          boolean enabled, boolean friendlyProtection,
                          int energyTier, int cooldownTier, int safeRadius) {

        /** 平台记录是否仍然存在。 */
        public boolean linked(ServerLevel level) {
            return this.platformId != null && OrbitalPlatforms.get(level).byId(this.platformId) != null;
        }

        /** 能不能开火：启用 + 平台仍有效。**不要求发射台区块加载**。 */
        public boolean canFire(ServerLevel level) {
            return this.enabled && this.linked(level);
        }

        public boolean inDimension(ServerLevel level) {
            return this.dimension.equals(level.dimension().location().toString());
        }

        public double energyCostMultiplier() {
            return LaserConsoleTiers.energyCostMultiplier(this.energyTier);
        }

        public double energyDamageMultiplier() {
            return LaserConsoleTiers.energyDamageMultiplier(this.energyTier);
        }

        public double cooldownMultiplier() {
            return LaserConsoleTiers.cooldownMultiplier(this.cooldownTier);
        }

        public int effectiveCooldownTicks() {
            return LaserConsoleTiers.effectiveCooldownTicks(this.cooldownTier);
        }

        public int displayCost() {
            return LaserConsoleTiers.displayCost(this.energyTier);
        }

        public float displayDamage() {
            return LaserConsoleTiers.displayDamage(this.energyTier);
        }
    }

    private final Map<UUID, Console> consoles = new LinkedHashMap<>();

    public static LaserConsoleNetwork get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                LaserConsoleNetwork::load, LaserConsoleNetwork::new, DATA_NAME);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Collection<Console> all() {
        return List.copyOf(this.consoles.values());
    }

    @Nullable
    public Console byId(UUID id) {
        return id == null ? null : this.consoles.get(id);
    }

    @Nullable
    public Console byPos(String dimension, BlockPos pos) {
        for (Console console : this.consoles.values()) {
            if (console.pos().equals(pos) && console.dimension().equals(dimension)) {
                return console;
            }
        }
        return null;
    }

    /**
     * 同一维度里最近的、能开火的发射台。
     *
     * <p>不检查发射台自己的区块是否加载——这正是这份 SavedData 存在的理由。
     */
    @Nullable
    public static Console findBest(ServerLevel level, Vec3 pos) {
        OrbitalPlatforms platforms = OrbitalPlatforms.get(level);
        Console best = null;
        double bestDist = Double.MAX_VALUE;
        for (Console console : get(level).consoles.values()) {
            if (!console.inDimension(level) || !console.enabled() || console.platformId() == null) {
                continue;
            }
            if (platforms.byId(console.platformId()) == null) {
                continue;
            }
            double dist = console.pos().distToCenterSqr(pos.x, pos.y, pos.z);
            if (dist < bestDist) {
                bestDist = dist;
                best = console;
            }
        }
        return best;
    }

    /** 落点是否落在任一启用发射台的安全区里。 */
    public static boolean isInSafeZone(ServerLevel level, Vec3 impact) {
        for (Console console : get(level).consoles.values()) {
            if (!console.inDimension(level) || !console.enabled() || console.safeRadius() <= 0) {
                continue;
            }
            double radius = console.safeRadius();
            if (console.pos().distToCenterSqr(impact.x, impact.y, impact.z) < radius * radius) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    public void put(Console console) {
        this.consoles.put(console.id(), console);
        this.setDirty();
    }

    /** 方块被拆掉时删除记录；区块卸载不会调用到这里。 */
    public void removeAt(String dimension, BlockPos pos) {
        Console found = this.byPos(dimension, pos);
        if (found != null) {
            this.consoles.remove(found.id());
            this.setDirty();
        }
    }

    public void remove(UUID id) {
        if (this.consoles.remove(id) != null) {
            this.setDirty();
        }
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    public static LaserConsoleNetwork load(CompoundTag tag) {
        LaserConsoleNetwork data = new LaserConsoleNetwork();
        ListTag list = tag.getList(TAG_CONSOLE, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = entry.hasUUID(TAG_ID) ? entry.getUUID(TAG_ID) : UUID.randomUUID();
            String dimension = entry.getString(TAG_DIMENSION);
            BlockPos pos = BlockPos.of(entry.getLong(TAG_POS));
            UUID owner = entry.hasUUID(TAG_OWNER) ? entry.getUUID(TAG_OWNER) : null;
            UUID platform = entry.hasUUID(TAG_PLATFORM) ? entry.getUUID(TAG_PLATFORM) : null;
            Console console = new Console(
                    id, dimension, pos, owner, platform,
                    !entry.contains(TAG_ENABLED) || entry.getBoolean(TAG_ENABLED),
                    !entry.contains(TAG_FRIENDLY) || entry.getBoolean(TAG_FRIENDLY),
                    LaserConsoleTiers.clampTier(entry.getInt(TAG_ENERGY_TIER),
                            LaserConsoleTiers.ENERGY_COUNT),
                    LaserConsoleTiers.clampTier(entry.getInt(TAG_COOLDOWN_TIER),
                            LaserConsoleTiers.COOLDOWN_COUNT),
                    entry.contains(TAG_SAFE_RADIUS) ? entry.getInt(TAG_SAFE_RADIUS) : 8);
            data.consoles.put(id, console);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Console console : this.consoles.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_ID, console.id());
            entry.putString(TAG_DIMENSION, console.dimension());
            entry.putLong(TAG_POS, console.pos().asLong());
            if (console.owner() != null) {
                entry.putUUID(TAG_OWNER, console.owner());
            }
            if (console.platformId() != null) {
                entry.putUUID(TAG_PLATFORM, console.platformId());
            }
            entry.putBoolean(TAG_ENABLED, console.enabled());
            entry.putBoolean(TAG_FRIENDLY, console.friendlyProtection());
            entry.putInt(TAG_ENERGY_TIER, console.energyTier());
            entry.putInt(TAG_COOLDOWN_TIER, console.cooldownTier());
            entry.putInt(TAG_SAFE_RADIUS, console.safeRadius());
            list.add(entry);
        }
        tag.put(TAG_CONSOLE, list);
        return tag;
    }
}
