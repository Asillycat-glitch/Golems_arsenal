package a_silly_cat.golems_arsenal.tech.orbit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 激光发射台 —— 天基激光的**地面控制端**。
 *
 * <p>它不是炮本身：真正落下来的是在轨平台。发射台负责三件事：
 * <ol>
 *   <li><b>授权</b>：世界里必须至少有一台启用、且绑定了在轨平台的发射台，傀儡的引导升级才会开火；</li>
 *   <li><b>定档</b>：能量档决定每发 FE 成本与伤害倍率，冷却档决定两发之间的间隔；</li>
 *   <li><b>安全</b>：友军保护开关决定冲击波是否避开自己人；安全区半径内不落炮。</li>
 * </ol>
 *
 * <p><b>平台绑定</b>：发射台放在地上时记录放置者，并自动绑定该玩家最早的一颗在轨平台
 * （{@link OrbitalPlatforms}）。如果放得比火箭早，会每 2 秒重试一次，火箭入轨后自动接上。
 * 没有平台时发射台可以配置，但不会开火。
 *
 * <p><b>全局覆盖</b>（2026-10-21 用户定）：不要求傀儡在发射台附近；同一维度里最近的
 * 一台「启用 + 已绑定」的发射台决定这一发的档位。发射台本身不储电——FE 仍从傀儡自己的池里扣，
 * 这样合金能源链和引导链仍然咬合。
 */
public class LaserConsoleBlockEntity extends BlockEntity implements MenuProvider {

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    private static final String TAG_ID = "ConsoleId";
    private static final String TAG_OWNER = "ConsoleOwner";
    private static final String TAG_PLATFORM = "ConsolePlatform";
    private static final String TAG_ENABLED = "ConsoleEnabled";
    private static final String TAG_FRIENDLY = "ConsoleFriendly";
    private static final String TAG_ENERGY_TIER = "ConsoleEnergyTier";
    private static final String TAG_COOLDOWN_TIER = "ConsoleCooldownTier";
    private static final String TAG_SAFE_RADIUS = "ConsoleSafeRadius";

    /** 这台发射台的稳定 id：全局记录用它做键，拆掉重放会得到新 id。 */
    private UUID consoleId;
    private UUID owner;
    private UUID platformId;
    private boolean enabled = true;
    private boolean friendlyProtection = true;
    private int energyTier = 1;
    private int cooldownTier = 1;
    private int safeRadius = 8;

    public LaserConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(a_silly_cat.golems_arsenal.init.ModBlockEntities.LASER_CONSOLE.get(), pos, state);
    }

    public UUID consoleId() {
        return this.consoleId;
    }

    /** 把当前配置写成一份不可变快照，交给 {@link LaserConsoleNetwork} 持久化。 */
    public LaserConsoleNetwork.Console snapshot() {
        String dimension = this.level instanceof ServerLevel server
                ? server.dimension().location().toString() : "";
        UUID id = this.consoleId;
        return new LaserConsoleNetwork.Console(
                id, dimension, this.worldPosition, this.owner, this.platformId,
                this.enabled, this.friendlyProtection,
                this.energyTier, this.cooldownTier, this.safeRadius);
    }

    /**
     * 配置变化时同时写全局记录。
     *
     * <p>方块实体自己的 NBT 仍然由 {@code setChanged()} 标脏；这里多写一份与区块无关的
     * 记录，是为了让发射台在区块卸载后仍然“存在”。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this.level instanceof ServerLevel server && this.consoleId != null) {
            LaserConsoleNetwork.get(server).put(this.snapshot());
        }
    }

    // ------------------------------------------------------------------
    // 平台绑定
    // ------------------------------------------------------------------

    public void setOwner(@Nullable Player player) {
        this.owner = player == null ? null : player.getUUID();
        this.platformId = null;
        this.tryLinkPlatform();
        this.setChanged();
    }

    @Nullable
    public UUID owner() {
        return this.owner;
    }

    /** 当前绑定的平台记录；未绑定或记录已被删除时返回 null。 */
    @Nullable
    public OrbitalPlatforms.Platform linkedPlatform() {
        if (this.platformId == null || !(this.level instanceof ServerLevel server)) {
            return null;
        }
        return OrbitalPlatforms.get(server).byId(this.platformId);
    }

    public boolean linked() {
        return this.linkedPlatform() != null;
    }

    /** 该玩家当前拥有的平台列表（按入轨顺序）。 */
    private List<OrbitalPlatforms.Platform> ownedPlatforms() {
        if (this.owner == null || !(this.level instanceof ServerLevel server)) {
            return List.of();
        }
        return OrbitalPlatforms.get(server).ownedBy(this.owner);
    }

    /**
     * 没有绑定 / 绑定失效时，自动接上该玩家的第一颗平台。
     *
     * <p>放得比火箭早也能用：tick 每 2 秒重试一次，火箭入轨后自动接上。
     */
    public void tryLinkPlatform() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        if (this.platformId != null && OrbitalPlatforms.get(server).byId(this.platformId) != null) {
            return;
        }
        List<OrbitalPlatforms.Platform> owned = this.ownedPlatforms();
        UUID next = owned.isEmpty() ? null : owned.get(0).id();
        if (!java.util.Objects.equals(this.platformId, next)) {
            this.platformId = next;
            this.setChanged();
            this.refreshLit();
        }
    }

    /** GUI 的「切换平台」：在放置者拥有的平台之间循环。 */
    public void cyclePlatform() {
        List<OrbitalPlatforms.Platform> owned = this.ownedPlatforms();
        if (owned.isEmpty()) {
            this.platformId = null;
        } else {
            int current = -1;
            for (int i = 0; i < owned.size(); i++) {
                if (owned.get(i).id().equals(this.platformId)) {
                    current = i;
                    break;
                }
            }
            this.platformId = owned.get((current + 1) % owned.size()).id();
        }
        this.setChanged();
        this.refreshLit();
    }

    /** 平台序号（1 起）；未绑定返回 0。 */
    public int platformIndex() {
        if (this.platformId == null) {
            return 0;
        }
        List<OrbitalPlatforms.Platform> owned = this.ownedPlatforms();
        for (int i = 0; i < owned.size(); i++) {
            if (owned.get(i).id().equals(this.platformId)) {
                return i + 1;
            }
        }
        return 0;
    }

    public int platformCount() {
        return this.ownedPlatforms().size();
    }

    // ------------------------------------------------------------------
    // 档位
    // ------------------------------------------------------------------

    public boolean enabled() {
        return this.enabled;
    }

    public boolean friendlyProtection() {
        return this.friendlyProtection;
    }

    public int energyTier() {
        return this.energyTier;
    }

    public int cooldownTier() {
        return this.cooldownTier;
    }

    public int safeRadius() {
        return this.safeRadius;
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

    /** 两发之间的实际间隔（tick）：基础冷却 × 冷却档倍率。 */
    public int effectiveCooldownTicks() {
        return LaserConsoleTiers.effectiveCooldownTicks(this.cooldownTier);
    }

    /** GUI 展示用：满级引导升级的一发成本（含能量档倍率）。 */
    public int displayCost() {
        return LaserConsoleTiers.displayCost(this.energyTier);
    }

    /** GUI 展示用：满级引导升级的一发基础伤害（含能量档倍率）。 */
    public float displayDamage() {
        return LaserConsoleTiers.displayDamage(this.energyTier);
    }

    /** 切换总开关。 */
    public void toggleEnabled() {
        this.enabled = !this.enabled;
        this.setChanged();
        this.refreshLit();
    }

    public void toggleFriendlyProtection() {
        this.friendlyProtection = !this.friendlyProtection;
        this.setChanged();
    }

    public void cycleEnergyTier() {
        this.energyTier = (this.energyTier + 1) % LaserConsoleTiers.ENERGY_COUNT;
        this.setChanged();
    }

    public void cycleCooldownTier() {
        this.cooldownTier = (this.cooldownTier + 1) % LaserConsoleTiers.COOLDOWN_COUNT;
        this.setChanged();
    }

    public void cycleSafeRadius() {
        this.safeRadius = LaserConsoleTiers.nextSafeRadius(this.safeRadius);
        this.setChanged();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel) {
            if (this.consoleId == null) {
                this.consoleId = UUID.randomUUID();
            }
            // 方块实体自己的 NBT 是权威；加载/放好后写一次全局快照。
            // **不要在这里 tryLinkPlatform / refreshLit**：它们可能 setBlock，
            // 而 onLoad 发生在区块加载过程中，改方块状态容易引出递归区块更新。
            // 新放置的发射台由 setPlacedBy -> setOwner 立刻绑定；加载出来的由 serverTick 补。
            this.setChanged();
        }
    }

    @Override
    public void setRemoved() {
        // 注意：区块卸载也会走到这里，所以**不能**在这里删全局记录。
        // 真正的拆除由 LaserConsoleBlock#onRemove 处理。
        super.setRemoved();
    }

    /** 用方块状态里的 LIT 表示「启用且已绑定平台」，给光照与后续特效留一个同步位。 */
    public void refreshLit() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        BlockState state = server.getBlockState(this.worldPosition);
        if (!state.hasProperty(LaserConsoleBlock.LIT)) {
            return;
        }
        boolean lit = this.enabled && this.linked();
        if (state.getValue(LaserConsoleBlock.LIT) != lit) {
            server.setBlock(this.worldPosition, state.setValue(LaserConsoleBlock.LIT, lit), 3);
        }
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel server) || server.isClientSide) {
            return;
        }
        // 每 2 秒重试绑定：处理「先放发射台、后发射火箭」以及平台被删除的情况。
        if (server.getGameTime() % 40L == 0L) {
            this.tryLinkPlatform();
            this.refreshLit();
        }
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.consoleId != null) {
            tag.putUUID(TAG_ID, this.consoleId);
        }
        if (this.owner != null) {
            tag.putUUID(TAG_OWNER, this.owner);
        }
        if (this.platformId != null) {
            tag.putUUID(TAG_PLATFORM, this.platformId);
        }
        tag.putBoolean(TAG_ENABLED, this.enabled);
        tag.putBoolean(TAG_FRIENDLY, this.friendlyProtection);
        tag.putInt(TAG_ENERGY_TIER, this.energyTier);
        tag.putInt(TAG_COOLDOWN_TIER, this.cooldownTier);
        tag.putInt(TAG_SAFE_RADIUS, this.safeRadius);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.consoleId = tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : UUID.randomUUID();
        this.owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
        this.platformId = tag.hasUUID(TAG_PLATFORM) ? tag.getUUID(TAG_PLATFORM) : null;
        this.enabled = !tag.contains(TAG_ENABLED) || tag.getBoolean(TAG_ENABLED);
        this.friendlyProtection = !tag.contains(TAG_FRIENDLY) || tag.getBoolean(TAG_FRIENDLY);
        this.energyTier = LaserConsoleTiers.clampTier(tag.getInt(TAG_ENERGY_TIER),
                LaserConsoleTiers.ENERGY_COUNT);
        this.cooldownTier = LaserConsoleTiers.clampTier(tag.getInt(TAG_COOLDOWN_TIER),
                LaserConsoleTiers.COOLDOWN_COUNT);
        this.safeRadius = tag.contains(TAG_SAFE_RADIUS)
                ? Math.max(0, tag.getInt(TAG_SAFE_RADIUS)) : 8;
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.golems_arsenal.laser_console");
    }

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new LaserConsoleMenu(windowId, inventory, this);
    }

    /**
     * 右键打开界面。走 {@code IForgeMenuType} 的额外数据通道把方块坐标带过去，
     * 客户端据此找回同一个方块实体。
     */
    public void openScreen(ServerPlayer player) {
        NetworkHooks.openScreen(player, this, buf -> buf.writeBlockPos(this.worldPosition));
    }

    /** 菜单数据源：服务端读方块实体，客户端读同步下来的副本。 */
    public ContainerData containerData() {
        return new ContainerData() {
            private final int[] client = new int[LaserConsoleMenu.DATA_COUNT];

            @Override
            public int get(int index) {
                if (LaserConsoleBlockEntity.this.level == null) {
                    return 0;
                }
                if (LaserConsoleBlockEntity.this.level.isClientSide) {
                    return this.client[index];
                }
                return LaserConsoleBlockEntity.this.serverValue(index);
            }

            @Override
            public void set(int index, int value) {
                if (index >= 0 && index < this.client.length) {
                    this.client[index] = value;
                }
            }

            @Override
            public int getCount() {
                return LaserConsoleMenu.DATA_COUNT;
            }
        };
    }

    /** 服务端把界面要显示的值映射成 int。 */
    private int serverValue(int index) {
        return switch (index) {
            case LaserConsoleMenu.DATA_ENABLED -> this.enabled ? 1 : 0;
            case LaserConsoleMenu.DATA_LINKED -> this.linked() ? 1 : 0;
            case LaserConsoleMenu.DATA_ENERGY_TIER -> this.energyTier;
            case LaserConsoleMenu.DATA_COOLDOWN_TIER -> this.cooldownTier;
            case LaserConsoleMenu.DATA_FRIENDLY -> this.friendlyProtection ? 1 : 0;
            case LaserConsoleMenu.DATA_SAFE_RADIUS -> this.safeRadius;
            case LaserConsoleMenu.DATA_PLATFORM_INDEX -> this.platformIndex();
            case LaserConsoleMenu.DATA_PLATFORM_COUNT -> this.platformCount();
            case LaserConsoleMenu.DATA_COOLDOWN_TICKS -> this.effectiveCooldownTicks();
            case LaserConsoleMenu.DATA_COST -> this.displayCost();
            case LaserConsoleMenu.DATA_DAMAGE_X100 -> Math.round(this.displayDamage() * 100.0F);
            default -> 0;
        };
    }

    /** 模型朝向用的水平方向（放置时由方块写进状态，这里只是给渲染/调试留的入口）。 */
    public Direction facing() {
        BlockState state = this.getBlockState();
        return state.hasProperty(LaserConsoleBlock.FACING)
                ? state.getValue(LaserConsoleBlock.FACING) : Direction.NORTH;
    }

    /** 方块实体被移除后，菜单里残留的引用不应再触发平台查询。 */
    public boolean stillValid(Player player) {
        return !this.isRemoved()
                && this.level != null
                && player.distanceToSqr(this.worldPosition.getX() + 0.5D,
                this.worldPosition.getY() + 0.5D,
                this.worldPosition.getZ() + 0.5D) <= 64.0D;
    }
}
