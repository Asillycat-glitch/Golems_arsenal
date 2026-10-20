package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import dev.xkmc.l2library.base.menu.base.BaseContainerMenu;
import dev.xkmc.l2library.base.menu.base.PredSlot;
import dev.xkmc.l2library.base.menu.base.MenuLayoutConfig;
import dev.xkmc.l2library.base.menu.base.SpriteManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 火箭界面 —— 用 l2library 的容器框架做（{@code BaseContainerMenu}）。
 *
 * <p>用框架而不是自己糊一个 {@code AbstractContainerMenu}，是因为槽位坐标全部写在数据包的
 * 布局 JSON 里（{@code data/golems_arsenal/l2library_config/menu_layout/rocket.json}），
 * 改位置不用重编译。这也是 l2 系列模组的既有做法。
 *
 * <p><b>与 l2backpack 那个参照实现的区别</b>：它们是"物品当容器"（容器数据挂在物品的
 * capability 上，还要用 UUID 校验物品没被换掉）；我们是**实体当容器**，直接用实体自己的
 * 字段，不需要那套校验。
 *
 * <p><b>{@code isVirtual} 必须传 false</b>：传 true 的话 {@code removed()} 会把容器里的
 * 东西全部还给玩家（那是"虚拟工作台"的语义）。燃料是机器存储，不该被吐出来。
 */
public class RocketMenu extends BaseContainerMenu<RocketMenu> {

    /** 布局 JSON 的名字：{@code data/golems_arsenal/l2library_config/menu_layout/rocket.json}。 */
    public static final SpriteManager SPRITE = new SpriteManager(Golems_arsenal.MODID, "rocket");

    /** 发射/中止按钮的 id（{@code AbstractContainerMenu.clickMenuButton} 用）。 */
    public static final int BUTTON_LAUNCH = 0;

    /** 布局 JSON 里燃料槽的组件名。 */
    public static final String COMP_FUEL = "fuel";

    private final RocketEntity rocket;

    /** 燃料槽在 {@code slots} 里的索引（没有槽位时是 -1）。 */
    private int fuelSlotIndex = -1;
    /** 燃料槽背后的容器（包装实体上的燃料物品字段）。 */

    /** 客户端用：从网络包里的实体 id 找回火箭。 */
    public RocketMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, buf.readInt());
    }

    /** 服务端用：实体自己建菜单时直接给出 id。 */
    public RocketMenu(int id, Inventory inv, int entityId) {
        // 容器不需要我们提供：燃料物品存在 **MEK 的储气槽位**里（那个槽位归储罐所有、
        // 持久化在实体上），菜单只取一个原版 Slot 视图塞进来。
        // 所以这里给框架一个空容器占位——注意不能给 null，构造器会立刻取它的 size。
        super(ModMenus.ROCKET.get(), id, inv, SPRITE,
                menu -> new net.minecraft.world.SimpleContainer(0), false);
        Player player = inv.player;
        // 客户端与服务端都能按 id 拿到实体；服务端拿到的就是同一个对象
        this.rocket = player.level().getEntity(entityId) instanceof RocketEntity r ? r : null;

        // MEK 的储气槽位：接受判断、搬运逻辑、存档全部由 MEK 负责
        net.minecraft.world.inventory.Slot fuelSlot =
                (this.rocket != null && FuelBridge.available())
                        ? FuelBridge.createSlot(this.rocket) : null;
        if (fuelSlot != null) {
            this.addSlot(fuelSlot);
        }
        // **当场记下索引**：玩家背包已经在 super() 里绑好了（0..35），
        // 所以燃料槽就是加入后的最后一个索引。不记下来就只能像以前那样写死 0，那是错的。
        this.fuelSlotIndex = fuelSlot != null ? this.slots.size() - 1 : -1;
    }

    /** 打开界面的火箭；实体已经不在时返回 null。 */
    @Nullable
    public RocketEntity rocket() {
        return this.rocket;
    }

    /**
     * 按钮：走框架的 {@code clickMenuButton} 通道——客户端只发按钮 id，
     * **判定全部在服务端做**，这样客户端改不了规则。
     */
    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (this.rocket == null) {
            return false;
        }
        if (button != BUTTON_LAUNCH) {
            return false;
        }
        // 倒计时中再按一次 = 中止（用户定的"倒计时可以被取消"）
        if (this.rocket.state() == RocketEntity.STATE_COUNTDOWN) {
            this.rocket.abortCountdown();
            return true;
        }
        if (this.rocket.canLaunch()) {
            this.rocket.ignite();
            return true;
        }
        return false;
    }

    /** 界面关闭时告诉实体，让它松掉菜单引用。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (this.rocket != null) {
            this.rocket.onMenuClosed(this);
        }
    }

    /**
     * Shift 点击的搬运。
     *
     * <p><b>索引必须算，不能写死 0</b>：l2 的 {@code BaseContainerMenu} 构造器
     * <b>先把玩家背包绑进来（36 格，索引 0..35）</b>，燃料槽是之后才 {@code addSlot} 的，
     * 所以它是<b>最后一个索引</b>。
     *
     * <p>之前写成「槽位 0 = 燃料槽」，导致 Shift 点击背包物品时往背包槽 0 里塞，
     * 而 Shift 点击燃料槽时又把储气罐塞进背包槽 0 —— 全是错的。
     *
     * <p>另外：**没真正搬动时必须返回 {@code ItemStack.EMPTY}**。原版调用方靠这个
     * 判断"有没有搬成功"，返回非空会让它以为搬了（继续走后续逻辑）。
     */
    /**
     * Shift 点击的搬运。
     *
     * <p><b>索引不能写死 0</b>：框架 {@code bindPlayerInventory} 先在构造器里绑
     * 36 个玩家背包槽（索引 0..35），我们的燃料槽是之后才加的 = 索引 36。
     * 傀儡装配的 {@code EquipmentsMenu.quickMoveStack} 里也是用
     * {@code if (id >= 36)} 区分"自定义槽"和"背包槽"，可以对照。
     *
     * <p><b>返回值必须是 {@code ItemStack.EMPTY}</b>。参考傀儡装配的写法：
     * <pre>
     *   public ItemStack quickMoveStack(Player pl, int id) {
     *       ...搬运...
     *       this.container.setChanged();
     *       return ItemStack.EMPTY;      // ← 永远返回 EMPTY
     *   }
     * </pre>
     * 之前我返回的是 {@code moveItemStackTo} 用剩的残留栈，语义不对——
     * 原版约定里这个返回值是「被移动槽位的新内容」，给错会让调用方把残留塞回别处。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        int fuel = this.fuelSlotIndex;
        ItemStack stack = slot.getItem();
        if (fuel >= 0 && index == fuel) {
            // 燃料槽 → 玩家背包
            this.moveItemStackTo(stack, 0, fuel, true);
        } else if (fuel >= 0) {
            // 玩家背包 → 燃料槽
            this.moveItemStackTo(stack, fuel, fuel + 1, false);
        }
        this.container.setChanged();
        return ItemStack.EMPTY;
    }
}
