package a_silly_cat.golems_arsenal.tech.energy;

/**
 * One tier of the golem's FE pool.
 *
 * <p>When Mekanism is installed these values are derived from the matching
 * {@code EnergyCubeTier}: capacity is half of the cube's stored energy, throughput is the cube's
 * full output. Without Mekanism the single base tier falls back to the config values.</p>
 *
 * @param capacityFe          stored FE of the pool
 * @param transferFePerTick   total FE per tick across all equipment targets (not per item)
 */
public record GolemEnergyTier(int capacityFe, int transferFePerTick) {

    public static final GolemEnergyTier NONE = new GolemEnergyTier(0, 0);

    public boolean isPresent() {
        return capacityFe > 0 || transferFePerTick > 0;
    }
}
