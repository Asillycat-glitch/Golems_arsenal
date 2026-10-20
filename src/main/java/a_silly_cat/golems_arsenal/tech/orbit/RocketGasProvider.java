package a_silly_cat.golems_arsenal.tech.orbit;

import mekanism.api.chemical.gas.IGasHandler;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.CapabilityCache;
import mekanism.common.capabilities.resolver.BasicCapabilityResolver;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

/**
 * 把火箭的氢气储罐**作为 Mekanism 的气体能力暴露出去**。
 *
 * <p><b>这是之前一直缺的一环。</b>对照 MEK 自己的储罐才看清：
 * <ul>
 *   <li>物品版：{@code ItemBlockChemicalTank.gatherCapabilities} 里
 *       {@code capabilities.add(ChemicalTankContentsHandler.create(tier))}——
 *       <b>把罐注册成 Forge 能力</b>；</li>
 *   <li>方块版：{@code getInitialGasTanks} 返回 {@code IChemicalTankHolder}，
 *       由 MEK 的能力注册流程挂上去。</li>
 * </ul>
 * 而我把罐藏在 {@code FuelBridge} 的静态 map 里，<b>根本没挂到实体上</b>，
 * 所以外面的管道接不进来、界面也没法通过能力读它。
 *
 * <p><b>写法照本工程的 {@code RadiationEntityProvider}</b>：同样用
 * {@code CapabilityCache + BasicCapabilityResolver.constant}，与 MEK 自己的
 * {@code DefaultRadiationEntity.Provider} 保持一致。
 *
 * <p><b>注意</b>：这里实现的是 {@code ICapabilityProvider} 而不是
 * {@code ICapabilitySerializable}——储罐的 NBT 由实体统一在
 * {@code addAdditionalSaveData} 里存（见 {@link FuelBridge#save}），
 * 不重复走能力那条序列化通道（两条通道会互相覆盖）。
 *
 * <p>这个类引用 Mekanism，所以只在 {@code isLoaded("mekanism")} 为真时被构造。
 */
public class RocketGasProvider implements ICapabilityProvider {

    private final RocketTank tank;
    private final CapabilityCache capabilityCache = new CapabilityCache();

    public RocketGasProvider(RocketTank tank) {
        this.tank = tank;
        // 储罐本身同时实现 IGasTank 与 IGasHandler，直接当作气体能力交出去
        this.capabilityCache.addCapabilityResolver(
                BasicCapabilityResolver.constant(Capabilities.GAS_HANDLER, tank.handler()));
    }

    public RocketTank tank() {
        return this.tank;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        return this.capabilityCache.getCapability(cap, side);
    }

    /** 与 RadiationEntityProvider 一致：跟着实体的能力清理一起作废。 */
    public void invalidate() {
        this.capabilityCache.invalidateAll();
    }

    /** 便捷方法：直接当 {@code IGasHandler} 用（内部就是那个储罐）。 */
    public IGasHandler asHandler() {
        return this.tank.handler();
    }
}
