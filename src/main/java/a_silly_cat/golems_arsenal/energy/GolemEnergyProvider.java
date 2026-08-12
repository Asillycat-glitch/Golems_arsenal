package a_silly_cat.golems_arsenal.energy;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities; // <--- 新增导入
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class GolemEnergyProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public static final Capability<IEnergyStorage> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});
    private final GolemEnergyStorage storage = new GolemEnergyStorage();
    private final LazyOptional<IEnergyStorage> holder = LazyOptional.of(() -> storage);

    public GolemEnergyStorage storage() { return storage; }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        // 修改点：同时兼容自定义能力 和 Forge 标准能量能力
        if (cap == CAPABILITY || cap == ForgeCapabilities.ENERGY) {
            return holder.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("energy", storage.getEnergyStored());
        tag.putInt("capacity", storage.getMaxEnergyStored());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        storage.setCapacity(tag.getInt("capacity"));
        storage.setEnergy(tag.getInt("energy"));
    }

    public void invalidate() { holder.invalidate(); }
}