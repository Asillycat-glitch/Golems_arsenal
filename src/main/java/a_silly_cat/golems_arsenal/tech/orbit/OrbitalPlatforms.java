package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 在轨平台记录 —— **卫星的存档形态**。
 *
 * <p>设计意图（{@code docs/ORBITAL-LASER-DESIGN.md} §3）：入轨之后**没有实体、没有方块、
 * 不加载区块**，世界上只留下一条记录。打击时按记录里的坐标现算，不需要维持任何"在天上飞的东西"。
 *
 * <p>这样做的好处是明显的：不占区块加载、不会因为玩家离开而丢失、多人服里性能开销恒定为零。
 *
 * <p>存在**主世界**的 {@code SavedData} 里（不管火箭从哪个维度发射），
 * 因为"在轨"本身不隶属于某个维度；打击时再用记录里的维度字段决定去哪儿开火。
 */
public class OrbitalPlatforms extends SavedData {

    private static final String DATA_NAME = Golems_arsenal.MODID + "_orbital_platforms";

    private static final String TAG_PLATFORMS = "Platforms";
    private static final String TAG_ID = "Id";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "LaunchPos";
    private static final String TAG_LAUNCHED_AT = "LaunchedAt";

    /** 一条在轨平台记录。 */
    public static final class Platform {
        private final UUID id;
        private final UUID owner;
        private final String dimension;
        private final BlockPos launchPos;
        private final long launchedAt;

        public Platform(UUID id, UUID owner, String dimension, BlockPos launchPos, long launchedAt) {
            this.id = id;
            this.owner = owner;
            this.dimension = dimension;
            this.launchPos = launchPos;
            this.launchedAt = launchedAt;
        }

        public UUID id() {
            return this.id;
        }

        /** 发射者。{@code null} = 无归属（调试或非玩家发射）。 */
        public UUID owner() {
            return this.owner;
        }

        /** 发射时所在的维度 id（例如 {@code minecraft:overworld}）。 */
        public String dimension() {
            return this.dimension;
        }

        /** 发射台所在坐标（作为"这颗卫星的编号"，也用于校验）。 */
        public BlockPos launchPos() {
            return this.launchPos;
        }

        public long launchedAt() {
            return this.launchedAt;
        }
    }

    private final List<Platform> platforms = new ArrayList<>();

    public static OrbitalPlatforms get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                OrbitalPlatforms::load, OrbitalPlatforms::new, DATA_NAME);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 只读快照（避免外部直接改内部 List）。 */
    public List<Platform> all() {
        return Collections.unmodifiableList(this.platforms);
    }

    /** 某个玩家拥有的全部平台。 */
    public List<Platform> ownedBy(UUID owner) {
        List<Platform> out = new ArrayList<>();
        for (Platform p : this.platforms) {
            if (p.owner() != null && p.owner().equals(owner)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 世界里有没有任何一颗在轨卫星（引导升级决定"要不要开火"时会问这个）。 */
    public boolean isEmpty() {
        return this.platforms.isEmpty();
    }

    public int size() {
        return this.platforms.size();
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    /**
     * 入轨：登记一条平台记录。
     *
     * @param level    发射所在的维度（用于记录 dimension 字段）
     * @param launchPos 发射坐标
     * @param owner    发射者（可为 null）
     */
    public static Platform addPlatform(ServerLevel level, BlockPos launchPos, UUID owner) {
        OrbitalPlatforms data = get(level);
        Platform platform = new Platform(
                UUID.randomUUID(),
                owner,
                level.dimension().location().toString(),
                launchPos,
                level.getGameTime());
        data.platforms.add(platform);
        data.setDirty();
        Golems_arsenal.LOGGER.info(
                "Orbital platform registered: dim={} pos={} owner={} (total {})",
                platform.dimension(), launchPos, owner, data.platforms.size());
        return platform;
    }

    /** 移除一条平台记录（例如玩家主动回收）。 */
    public boolean removePlatform(UUID id) {
        boolean removed = this.platforms.removeIf(p -> p.id().equals(id));
        if (removed) {
            this.setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    public static OrbitalPlatforms load(CompoundTag tag) {
        OrbitalPlatforms data = new OrbitalPlatforms();
        ListTag list = tag.getList(TAG_PLATFORMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = entry.hasUUID(TAG_ID) ? entry.getUUID(TAG_ID) : UUID.randomUUID();
            UUID owner = entry.hasUUID(TAG_OWNER) ? entry.getUUID(TAG_OWNER) : null;
            String dimension = entry.getString(TAG_DIMENSION);
            BlockPos pos = BlockPos.of(entry.getLong(TAG_POS));
            long launchedAt = entry.getLong(TAG_LAUNCHED_AT);
            data.platforms.add(new Platform(id, owner, dimension, pos, launchedAt));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Platform p : this.platforms) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_ID, p.id());
            if (p.owner() != null) {
                entry.putUUID(TAG_OWNER, p.owner());
            }
            entry.putString(TAG_DIMENSION, p.dimension());
            entry.putLong(TAG_POS, p.launchPos().asLong());
            entry.putLong(TAG_LAUNCHED_AT, p.launchedAt());
            list.add(entry);
        }
        tag.put(TAG_PLATFORMS, list);
        return tag;
    }
}
