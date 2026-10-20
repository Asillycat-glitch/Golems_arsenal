package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.slot.chemical.GasInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * 火箭燃料对实体的挂载与读写入口。
 *
 * <p><b>这一版是把储罐挂成 Forge 能力</b>（之前是一张静态 map，读法完全错了——
 * 那样外面的管道接不进来，界面也读不到。对照 MEK 的 {@code ItemBlockChemicalTank}
 * 才看清：储罐要"注册成能力"才能被访问）。
 *
 * <p>写法和 {@code GolemRadiationAttachHandler} 一样：监听
 * {@code AttachCapabilitiesEvent<Entity>}，只处理 {@code RocketEntity}，
 * 并且<b>用 {@code ModList.isLoaded} 门控</b>——{@link RocketGasProvider} 引用 MEK 的类，
 * 它的加载必须推迟到确认装了 MEK 之后。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class FuelBridge {

    private static final ResourceLocation GAS_ID =
            new ResourceLocation(Golems_arsenal.MODID, "rocket_gas");

    /** 这个实体能不能用燃料系统（即 MEK 装了没）。 */
    public static boolean available() {
        return ModList.get().isLoaded("mekanism");
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof HasGasProvider rocket)) {
            return;
        }
        // 没装 MEK 时直接退出：下面会引用 RocketGasProvider / RocketTank，
        // 类加载就发生在那一行。
        if (!available()) {
            return;
        }
        attach(rocket, event);
    }

    private static void attach(HasGasProvider rocket, AttachCapabilitiesEvent<?> event) {
        RocketTank tank = new RocketTank(rocket::markFuelDirty);
        RocketGasProvider provider = new RocketGasProvider(tank);
        rocket.setGasProvider(provider);
        event.addCapability(GAS_ID, provider);
        // 标准 Forge 清理，与 WeaponEventHandler.attachEnergy 对 FE 的做法一致
        event.addListener(provider::invalidate);
    }

    // ------------------------------------------------------------------
    // 实体调用的读写入口
    //
    // 参数是 HasGasProvider（不引用 MEK 的接口），内部强转成 RocketGasProvider——
    // 唯一写这个字段的地方是上面的 attach，所以转换安全（见 HasGasProvider 的说明）。
    // ------------------------------------------------------------------

    /** 储罐提供者；没挂上就返回 null。 */
    @Nullable
    private static RocketTank tankOf(@Nullable HasGasProvider owner) {
        return owner != null && owner.gasProvider() instanceof RocketGasProvider p
                ? p.tank() : null;
    }

    public static int stored(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        return tank == null ? 0 : tank.stored();
    }

    /**
     * 菜单打开时取"原版 Slot 视图"。
     *
     * <p>槽位对象归储罐所有（持久化在实体上），所以菜单关掉再开不会丢东西——
     * 这正是 MEK 自己管方块实体槽位的方式。{@code createContainerSlot()} 是
     * {@code IInventorySlot} 提供的方法，由 MEK 实现。
     */
    /**
     * 菜单打开时取"原版 Slot 视图"。
     *
     * <p><b>这里不做任何自定义拦截</b>。之前我给它加过 {@code mayPickup -> false}
     * 想堵住"无限取出"，那是把功能改掉而不是修 bug，已经撤销。
     *
     * <p>MEK 自己的实现（反编译 {@code InventoryContainerSlot}）是自洽的：
     * <pre>
     *   public boolean mayPickup(Player p) {
     *       return !this.slot.extractItem(1, SIMULATE, MANUAL).isEmpty();   // 模拟能抽出
     *   }
     *   public ItemStack remove(int amount) {
     *       return this.slot.extractItem(amount, EXECUTE, MANUAL);          // 真抽
     *   }
     * </pre>
     * 两个走同一个 {@code extractItem}，所以"取出"本身不会重复。
     *
     * <p>槽位对象归储罐所有（持久化在实体上），菜单每次打开只取一个包装视图——
     * 这也是 MEK 自己管 {@code IInventorySlot} 的方式。
     */
    @Nullable
    public static net.minecraft.world.inventory.Slot createSlot(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        return tank == null ? null : tank.fillSlot().createContainerSlot();
    }

    /**
     * 每 tick 让 MEK 自己的搬运逻辑把物品里的氢气打进储罐。
     *
     * <p><b>外面包了一层 try/catch 是故意的</b>：槽位里可能被塞进任何带气体能力的物品
     * （比如装了乙烯的创造储罐），MEK 的搬运代码在「气体不匹配」的极端情况下抛异常时，
     * 不应该让整个服务端 tick 崩掉。异常照常记日志，方便定位。
     */
    public static void tickFill(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        if (tank == null) {
            return;
        }
        try {
            tank.tickFill();
        } catch (RuntimeException e) {
            Golems_arsenal.LOGGER.error(
                    "Rocket fuel transfer failed (slot item {}); the item stays in the slot but no gas moved",
                    tank.fillSlot().getStack(), e);
        }
    }

    /** 当前燃料气体的注册名；没装 MEK 或空罐时是空串。 */
    public static String gasId(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        return tank == null ? "" : tank.gasId();
    }

    /** 发射时扣燃料。 */
    public static void consume(@Nullable HasGasProvider owner, int amount) {
        RocketTank tank = tankOf(owner);
        if (tank != null) {
            tank.consume(amount);
        }
    }

    public static CompoundTag save(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        return tank == null ? new CompoundTag() : tank.save();
    }

    /** 槽位里那个储气物品的存档（MEK 的槽位自带实现）。 */
    public static CompoundTag saveSlot(@Nullable HasGasProvider owner) {
        RocketTank tank = tankOf(owner);
        return tank == null ? new CompoundTag() : tank.saveSlot();
    }

    public static void loadSlot(@Nullable HasGasProvider owner, CompoundTag tag) {
        RocketTank tank = tankOf(owner);
        if (tank != null) {
            tank.loadSlot(tag);
        }
    }

    public static void load(@Nullable HasGasProvider owner, CompoundTag tag) {
        RocketTank tank = tankOf(owner);
        if (tank != null) {
            tank.load(tag);
        }
    }

    private FuelBridge() {
    }
}
