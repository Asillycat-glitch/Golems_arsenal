package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyModifier;
import dev.xkmc.modulargolems.content.item.golem.GolemHolder;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class GolemEnergyItemProvider implements ICapabilityProvider {
    private final ItemStack stack;
    private final LazyOptional<IEnergyStorage> energy;

    public GolemEnergyItemProvider(ItemStack stack) { this.stack = stack; this.energy = LazyOptional.of(Storage::new); }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return cap == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
    }

    /** FE capacity of a stowed golem, derived from the energy upgrades installed on the holder. */
    public static int capacityOf(ItemStack stack) {
        return GolemEnergyModifier.capacityForLevel(energyUpgradeLevel(stack));
    }

    /** Current stored FE of a stowed golem, clamped to the computed capacity. */
    public static int energyOf(ItemStack stack) {
        return Math.max(0, Math.min(storedEnergy(stack), capacityOf(stack)));
    }

    private static int energyUpgradeLevel(ItemStack stack) {
        int level = 0;
        for (IUpgradeItem upgrade : GolemHolder.getUpgrades(stack)) {
            for (ModifierInstance instance : upgrade.get()) {
                if (instance.mod() instanceof GolemEnergyModifier) {
                    level += instance.level();
                }
            }
        }
        return Math.min(level, GolemEnergyModifier.MAX_LEVEL);
    }

    private static int storedEnergy(ItemStack stack) {
        return data(stack).getInt("GolemEnergy");
    }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrCreateTag().getCompound("golem_entity");
    }

    private final class Storage implements IEnergyStorage {
        private final ItemStack stack;

        private Storage() {
            this.stack = GolemEnergyItemProvider.this.stack;
        }

        private void set(int value) {
            CompoundTag root = stack.getOrCreateTag();
            CompoundTag entity = root.getCompound("golem_entity");
            int clamped = Math.max(0, Math.min(value, getMaxEnergyStored()));
            entity.putInt("GolemEnergy", clamped);
            entity.putInt("GolemMaxEnergy", getMaxEnergyStored());
            root.put("golem_entity", entity);
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) { int n = Math.min(Math.max(amount, 0), getMaxEnergyStored() - getEnergyStored()); if (!simulate && n > 0) set(getEnergyStored() + n); return n; }

        @Override
        public int extractEnergy(int amount, boolean simulate) { int n = Math.min(Math.max(amount, 0), getEnergyStored()); if (!simulate && n > 0) set(getEnergyStored() - n); return n; }

        @Override
        public int getEnergyStored() { return GolemEnergyItemProvider.energyOf(stack); }

        @Override
        public int getMaxEnergyStored() { return GolemEnergyItemProvider.capacityOf(stack); }

        @Override
        public boolean canExtract() { return true; }

        @Override
        public boolean canReceive() { return getMaxEnergyStored() > 0; }
    }
}
