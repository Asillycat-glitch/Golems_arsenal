package a_silly_cat.golems_arsenal.tech.orbit;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 激光发射台的配置菜单。
 *
 * <p>没有物品槽——这是一个纯配置终端，界面只读 {@link ContainerData} 并把按钮 id 发回服务端。
 * 走原版 {@link AbstractContainerMenu} 而不是仓库里火箭那套 l2library 容器框架：
 * 火箭界面需要燃料槽和 176×184 的贴图布局，这里一个槽都没有，用原版菜单更轻。
 *
 * <p><b>判定全部在服务端</b>：客户端只发 {@code ServerboundContainerButtonClickPacket}，
 * {@link #clickMenuButton} 在服务端执行，客户端改不了档位规则。
 */
public class LaserConsoleMenu extends AbstractContainerMenu {

    // ------------------------------------------------------------------
    // 数据槽索引
    // ------------------------------------------------------------------

    public static final int DATA_ENABLED = 0;
    public static final int DATA_LINKED = 1;
    public static final int DATA_ENERGY_TIER = 2;
    public static final int DATA_COOLDOWN_TIER = 3;
    public static final int DATA_FRIENDLY = 4;
    public static final int DATA_SAFE_RADIUS = 5;
    public static final int DATA_PLATFORM_INDEX = 6;
    public static final int DATA_PLATFORM_COUNT = 7;
    public static final int DATA_COOLDOWN_TICKS = 8;
    public static final int DATA_COST = 9;
    public static final int DATA_DAMAGE_X100 = 10;
    public static final int DATA_COUNT = 11;

    // ------------------------------------------------------------------
    // 按钮 id
    // ------------------------------------------------------------------

    public static final int BUTTON_TOGGLE_ENABLED = 0;
    public static final int BUTTON_TOGGLE_FRIENDLY = 1;
    public static final int BUTTON_CYCLE_SAFE_RADIUS = 2;
    public static final int BUTTON_CYCLE_ENERGY = 3;
    public static final int BUTTON_CYCLE_COOLDOWN = 4;
    public static final int BUTTON_CYCLE_PLATFORM = 5;

    @Nullable
    private final LaserConsoleBlockEntity console;
    private final ContainerData data;

    /** 客户端用：从额外数据里的方块坐标找回方块实体。 */
    public LaserConsoleMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos());
    }

    /** 客户端用：按坐标取方块实体。 */
    public LaserConsoleMenu(int id, Inventory inventory, BlockPos pos) {
        this(id, inventory, inventory.player.level().getBlockEntity(pos)
                instanceof LaserConsoleBlockEntity be ? be : null);
    }

    /** 服务端用：方块实体自己建菜单。 */
    public LaserConsoleMenu(int id, Inventory inventory, LaserConsoleBlockEntity console) {
        super(ModMenus.LASER_CONSOLE.get(), id);
        this.console = console;
        this.data = console != null ? console.containerData() : EMPTY_DATA;
        this.addDataSlots(this.data);
    }

    private static final ContainerData EMPTY_DATA = new ContainerData() {
        @Override
        public int get(int index) {
            return 0;
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    @Nullable
    public LaserConsoleBlockEntity console() {
        return this.console;
    }

    /** 界面读数据槽（服务端读方块实体，客户端读同步副本）。 */
    public int getData(int index) {
        return this.data.get(index);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.console != null && this.console.stillValid(player);
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (this.console == null || !this.console.stillValid(player)) {
            return false;
        }
        switch (button) {
            case BUTTON_TOGGLE_ENABLED -> this.console.toggleEnabled();
            case BUTTON_TOGGLE_FRIENDLY -> this.console.toggleFriendlyProtection();
            case BUTTON_CYCLE_SAFE_RADIUS -> this.console.cycleSafeRadius();
            case BUTTON_CYCLE_ENERGY -> this.console.cycleEnergyTier();
            case BUTTON_CYCLE_COOLDOWN -> this.console.cycleCooldownTier();
            case BUTTON_CYCLE_PLATFORM -> this.console.cyclePlatform();
            default -> {
                return false;
            }
        }
        this.broadcastChanges();
        return true;
    }

    /** 没有物品槽，Shift 点击什么也不搬。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
