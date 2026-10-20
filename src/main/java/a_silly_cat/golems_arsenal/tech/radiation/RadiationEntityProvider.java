package a_silly_cat.golems_arsenal.tech.radiation;

import mekanism.api.radiation.capability.IRadiationEntity;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.CapabilityCache;
import mekanism.common.capabilities.resolver.BasicCapabilityResolver;
import mekanism.common.lib.radiation.capability.DefaultRadiationEntity;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

/**
 * 把 Mekanism 的 {@code IRadiationEntity} 能力挂到傀儡身上，让**傀儡真的会被辐射伤害**。
 *
 * <p>这是整条链的最后一环：{@code GolemRadiationAttachHandler} 负责"挂得上"（监听
 * {@code AttachCapabilitiesEvent}），这个 Provider 负责"挂什么"。实现刻意与 Mekanism 自己的
 * {@code DefaultRadiationEntity.Provider} 保持一致（同一个实现类、同一套 NBT 键、
 * 同样的 {@code BasicCapabilityResolver.constant} 注册方式），这样：
 * <ul>
 *   <li>剂量累积与掉血的规则**完全由 Mekanism 决定**——我们不自造伤害、不自己算阈值；</li>
 *   <li>存档格式与 Mekanism 一致，将来若它改了衰减/阈值，我们自动跟随。</li>
 * </ul>
 *
 * <p><b>注意：这个类引用了 Mekanism 的类，所以它的加载必须被门控。</b>实际上它由
 * {@code GolemRadiationAttachHandler} 在 {@code isLoaded("mekanism")} 为真时才会被解析，
 * 类加载器因此不会在没装 MEK 时碰到它。
 */
public class RadiationEntityProvider implements ICapabilitySerializable<CompoundTag> {

    private final IRadiationEntity impl = new DefaultRadiationEntity();
    private final CapabilityCache capabilityCache = new CapabilityCache();

    public RadiationEntityProvider() {
        capabilityCache.addCapabilityResolver(
                BasicCapabilityResolver.constant(Capabilities.RADIATION_ENTITY, impl));
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        return capabilityCache.getCapability(cap, side);
    }

    public void invalidate() {
        capabilityCache.invalidateAll();
    }

    @Override
    public CompoundTag serializeNBT() {
        return impl.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        impl.deserializeNBT(nbt);
    }

    // 不要在这里再加一个 deserializeNBT(Tag) 重载：Forge 的 INBTSerializable 另有泛型方法
    // deserializeNBT(Tag)，两个重载会构成"名称冲突：两者都覆盖对方"的编译错误。
    // Mekanism 自己的 DefaultRadiationEntity.Provider 也只有 CompoundTag 这一个重载。
}
