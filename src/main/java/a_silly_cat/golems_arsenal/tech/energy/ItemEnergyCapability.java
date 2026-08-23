package a_silly_cat.golems_arsenal.tech.energy;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/** Capability provider exposing an item's FE storage; shared by the energy weapons. */
public final class ItemEnergyCapability implements ICapabilityProvider {
    private final LazyOptional<IEnergyStorage> energy;

    public ItemEnergyCapability(ItemStack stack, IntSupplier capacity) {
        energy = LazyOptional.of(() -> new ItemEnergyStorage(stack, capacity));
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return cap == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
    }
}
