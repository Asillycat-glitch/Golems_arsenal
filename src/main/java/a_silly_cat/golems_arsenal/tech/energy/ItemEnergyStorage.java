package a_silly_cat.golems_arsenal.tech.energy;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.function.IntSupplier;

/**
 * Shared Forge-energy storage persisted in an item's NBT, used by all energy weapons (katana,
 * hammer, tracking bow). Capacity is supplied by the owning item.
 */
public class ItemEnergyStorage implements IEnergyStorage {
    public static final String ENERGY_TAG = "Energy";

    private final ItemStack stack;
    private final IntSupplier capacity;

    public ItemEnergyStorage(ItemStack stack, IntSupplier capacity) {
        this.stack = stack;
        this.capacity = capacity;
    }

    public static int getStored(ItemStack stack) {
        return Math.max(0, stack.getOrCreateTag().getInt(ENERGY_TAG));
    }

    /** Simulate-then-extract idiom shared by every energy-consuming weapon. */
    public static boolean consume(ItemStack stack, int cost) {
        return stack.getCapability(ForgeCapabilities.ENERGY).map(s -> consume(s, cost)).orElse(false);
    }

    /** Simulate-then-extract on an arbitrary FE storage (items and golem capabilities). */
    public static boolean consume(IEnergyStorage storage, int cost) {
        if (cost <= 0) {
            return true;
        }
        if (storage.extractEnergy(cost, true) < cost) {
            return false;
        }
        storage.extractEnergy(cost, false);
        return true;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int accepted = Math.min(Math.max(maxReceive, 0), getMaxEnergyStored() - getEnergyStored());
        if (!simulate && accepted > 0) {
            set(getEnergyStored() + accepted);
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        int extracted = Math.min(Math.max(maxExtract, 0), getEnergyStored());
        if (!simulate && extracted > 0) {
            set(getEnergyStored() - extracted);
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return getStored(stack);
    }

    @Override
    public int getMaxEnergyStored() {
        return Math.max(0, capacity.getAsInt());
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    private void set(int energy) {
        stack.getOrCreateTag().putInt(ENERGY_TAG, Mth.clamp(energy, 0, getMaxEnergyStored()));
    }
}
