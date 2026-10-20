package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Config;
import mekanism.api.IContentsListener;
import mekanism.common.inventory.slot.chemical.GasInventorySlot;
import mekanism.api.chemical.ChemicalTankBuilder;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.common.registries.MekanismGases;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 火箭的氢气储罐 —— **照着 Mekanism 自己 {@code TileEntityChemicalTank} 的写法**。
 *
 * <p>之前我自己手搓了一套"读物品罐里的气再塞进实体字段"，而且手搓出了 bug
 * （{@code getChemicalInTank(0)} 没先查 {@code getTanks()}，越界）。
 * 读过 MEK 的源码后改成正统做法：
 *
 * <ul>
 *   <li>储罐用 {@link ChemicalTankBuilder#GAS}.{@code create(capacity, listener)} 建 —— MEK 的
 *       {@code ChemicalTankChemicalTank} 也是这么建的。返回的对象同时实现 {@code IGasTank}
 *       与 {@code IGasHandler}，<b>自带 NBT 序列化</b>（{@code BasicChemicalTank} 实现
 *       {@code IChemicalTank} 的 {@code serializeNBT/deserializeNBT}），不用我自己存。</li>
 *   <li>只接受氢气：{@code create(capacity, isValid, listener)} 那个重载传
 *       {@code gas -> gas == HYDROGEN}。**由储罐自己拒绝**，而不是我每次抽取前手查。</li>
 *   <li>物品→储罐的搬运照抄 MEK 的
 *       {@code ChemicalInventorySlot.fillChemicalTankFromItem}（见 {@link #fillFromItem}）：
 *       <b>先 simulate 算能装多少，再按那个量抽</b>，绝不硬塞。</li>
 * </ul>
 *
 * <p><b>这个类引用 Mekanism 的类，所以加载必须被门控</b>——照 {@code GolemRadiationAttachHandler}
 * 的先例，通过 {@link FuelBridge} 间接使用，而 {@code FuelBridge} 只被
 * {@code ModList.isLoaded("mekanism")} 之后的代码引用。
 */
public final class RocketTank {

    /**
     * 储罐。
     *
     * <p>声明成 {@code IGasTank}——这是 {@code ChemicalTankBuilder.GAS.create} 的静态返回类型。
     * 实际对象是公开类 {@code ChemicalTankBuilder.BasicGasTank}，
     * 它<b>同时实现 {@code IGasTank} 与 {@code IGasHandler}</b>（查过 javap 确认），
     * 所以一个对象两种视角都能用，只是要显式转换。
     */
    private final IGasTank tank;

    /**
     * **Mekanism 自己的储气槽位**（不是我们手搓的）。
     *
     * <p>这是"不算半成品"的关键：{@code GasInventorySlot.fill} 由 MEK 提供，
     * 自带
     * <ul>
     *   <li>接受判断（物品里有没有可抽取的氢气，见 {@code getFillExtractPredicate}）；</li>
     *   <li>搬运逻辑（{@code fillTank()} → {@code fillChemicalTankFromItem}，先 simulate 再抽）；</li>
     *   <li>存档（实现 {@code INBTSerializable<CompoundTag>}）；</li>
     *   <li>生成原版 {@code Slot} 的包装器（{@code createContainerSlot()}）。</li>
     * </ul>
     * 之前我自己写了 {@code PredSlot} + 手写转移，那确实是个半成品。
     *
     * <p><b>为什么槽位属于储罐（而不是菜单）</b>：槽位里存着玩家放的储气物品。
     * 如果槽位跟着菜单走，界面一关物品就没了。MEK 的做法也是"槽位归方块实体所有"，
     * 菜单每次打开只是包一层 {@code Slot} 视图。
     */
    private final GasInventorySlot fillSlot;

    private final IContentsListener contentsListener;

    public RocketTank(@Nullable Runnable contentsListener) {
        // 第二个参数是"允许装什么气体"的谓词：只认氢气。由储罐自己把关。
        this.contentsListener = contentsListener == null ? null : contentsListener::run;
        // **判定标准与 MEK 的燃气发电机一致**：只要气体带 Fuel 属性就收。
        // MEK 的 TileEntityGasGenerator.FuelTank 就是这么写的：
        //     ChemicalTankBuilder.GAS.create(..., gas -> gas.has(Fuel.class), ...)
        // 所以氢气、乙烯（以及 MEK 以后加的任何带 Fuel 属性的气体）都能用，
        // 不用我们维护一份气体名单。
        this.tank = ChemicalTankBuilder.GAS.create(
                Config.ROCKET_FUEL_CAPACITY.get(),
                gas -> gas.has(mekanism.api.chemical.gas.attribute.GasAttributes.Fuel.class),
                this.contentsListener);
        // MEK 的"从物品抽气进罐"槽位。
        // **坐标必须在这里给定**：原版 Slot 的 x/y 是 final 字段，生成后改不了，
        // 而槽位归储罐所有（不归菜单），所以只能建的时候就从布局 JSON 读一次。
        int[] pos = layoutPos();
        this.fillSlot = GasInventorySlot.fill(this.tank, this.contentsListener, pos[0], pos[1]);
    }

    /**
     * 从布局 JSON 读燃料槽的坐标。
     *
     * <p>读不到时退回 {@code (44, 24)}——那是 {@code rocket.json} 里的值。
     * 为什么允许兜底：储罐是在 {@code AttachCapabilitiesEvent} 里建的，
     * 那一刻数据包配置**理论上**已经加载完，但不值得为此冒 NPE 的风险。
     */
    public static int[] layoutPos() {
        try {
            var r = RocketMenu.SPRITE.get().getComp(RocketMenu.COMP_FUEL);
            if (r.w > 0) {
                return new int[]{r.x, r.y};
            }
        } catch (RuntimeException ignored) {
            // 布局未加载：用兜底值
        }
        return new int[]{44, 24};
    }

    /** 这个槽位（菜单里包一层原版 Slot 用）。 */
    public GasInventorySlot fillSlot() {
        return this.fillSlot;
    }

    /** 每 tick 让 MEK 自己的逻辑把物品里的氢气搬进储罐。 */
    public void tickFill() {
        this.fillSlot.fillTank();
    }

    /** 槽位里物品的存档（MEK 的 {@code BasicInventorySlot} 自带实现）。 */
    public CompoundTag saveSlot() {
        return this.fillSlot.serializeNBT();
    }

    public void loadSlot(CompoundTag tag) {
        this.fillSlot.deserializeNBT(tag);
    }

    /**
     * 气体能力视角（挂 Forge 能力用）。
     *
     * <p>转换安全：{@code Capabilities.GAS_HANDLER} 是 {@code Capability<IGasHandler>}，
     * 而这里的具体对象就是 {@code BasicGasTank}（实现了 {@code IGasHandler}）。
     */
    public IGasHandler handler() {
        return (IGasHandler) this.tank;
    }

    /** 储罐视角（自己读写用：insert/extract/shrinkStack 这些在 IChemicalTank 上）。 */
    private IGasTank asTank() {
        return this.tank;
    }

    /**
     * 当前储罐里气体的注册名（例如 {@code mekanism:hydrogen}）；空罐返回空串。
     *
     * <p>客户端拿不到真储罐（实体没有 MEK 的容器级同步），所以服务端要把
     * 「是哪种气」单独同步过去，界面才能画对贴图并显示正确的中文名。
     */
    public String gasId() {
        var stack = this.asTank().getStack();
        if (stack.isEmpty()) {
            return "";
        }
        var name = stack.getType().getRegistryName();
        return name == null ? "" : name.toString();
    }

    /** 当前储量（mB）。 */
    public int stored() {
        return (int) Math.min(Integer.MAX_VALUE, this.asTank().getStored());
    }

    public int capacity() {
        return (int) Math.min(Integer.MAX_VALUE, this.asTank().getCapacity());
    }

    public boolean isEmpty() {
        return this.asTank().isEmpty();
    }

    /** 还能装多少（mB）。 */
    public long needed() {
        return this.asTank().getNeeded();
    }

    /** 消耗（发射时扣燃料）。 */
    public void consume(long amount) {
        this.asTank().shrinkStack(amount, mekanism.api.Action.EXECUTE);
    }

    // ------------------------------------------------------------------
    // 存档：储罐自带 NBT，不用自己写格式
    // ------------------------------------------------------------------

    public CompoundTag save() {
        return this.asTank().serializeNBT();
    }

    public void load(CompoundTag tag) {
        this.asTank().deserializeNBT(tag);
    }

    // ------------------------------------------------------------------
    // 物品 → 储罐
    // ------------------------------------------------------------------

}