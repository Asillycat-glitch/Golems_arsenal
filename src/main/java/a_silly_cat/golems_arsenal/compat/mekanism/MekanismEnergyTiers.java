package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyTier;
import mekanism.api.math.FloatingLong;
import mekanism.common.tier.EnergyCubeTier;
import mekanism.common.util.UnitDisplayUtils;

/**
 * Mirrors Mekanism's energy cube tiers onto the golem FE pool.
 *
 * <p>Capacity is <b>half</b> of the cube's stored energy; throughput is the cube's <b>full</b>
 * output, interpreted as a total across all equipped targets (not per item). The energy cube
 * values are config-backed, so a pack that changes Mekanism's cube numbers changes the golem
 * numbers too. Both are Joules in Mekanism and FE on the golem side, hence the conversion.</p>
 *
 * <p>Level totals: 1 = base, 3 = +infused, 7 = +elite, 15 = +ultimate. The alloy materials used
 * by the recipes are the same Mekanism alloys that gate the corresponding cube tiers.</p>
 */
public final class MekanismEnergyTiers {

    private MekanismEnergyTiers() {
    }

    public static GolemEnergyTier forLevel(int totalLevel) {
        EnergyCubeTier cube = cubeFor(totalLevel);
        if (cube == null) {
            return GolemEnergyTier.NONE;
        }
        FloatingLong capacityJ = cube.getMaxEnergy().divide(2);
        long capacityFe = UnitDisplayUtils.EnergyUnit.FORGE_ENERGY.convertToAsLong(capacityJ);
        long transferFe = UnitDisplayUtils.EnergyUnit.FORGE_ENERGY.convertToAsLong(cube.getOutput());
        return new GolemEnergyTier(clamp(capacityFe), clamp(transferFe));
    }

    private static EnergyCubeTier cubeFor(int totalLevel) {
        if (totalLevel >= 15) {
            return EnergyCubeTier.ULTIMATE;
        }
        if (totalLevel >= 7) {
            return EnergyCubeTier.ELITE;
        }
        if (totalLevel >= 3) {
            return EnergyCubeTier.ADVANCED;
        }
        return EnergyCubeTier.BASIC;
    }

    private static int clamp(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
