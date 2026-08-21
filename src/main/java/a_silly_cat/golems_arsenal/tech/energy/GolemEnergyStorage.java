package a_silly_cat.golems_arsenal.tech.energy;

import net.minecraftforge.energy.EnergyStorage;

/** Energy storage used by Modular Golems entities. Capacity is updated by upgrades. */
public class GolemEnergyStorage extends EnergyStorage {
    public GolemEnergyStorage() {
        super(0, 0, 0, 0);
    }

    public void setCapacity(int capacity) {
        capacity = Math.max(0, capacity);
        this.capacity = capacity;
        this.maxReceive = capacity;
        this.maxExtract = capacity;
        this.energy = Math.min(this.energy, capacity);
    }

    public void setEnergy(int value) {
        this.energy = Math.max(0, Math.min(value, capacity));
    }
}
