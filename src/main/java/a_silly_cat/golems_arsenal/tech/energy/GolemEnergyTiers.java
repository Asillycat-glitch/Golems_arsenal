package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Config;

import java.util.function.IntFunction;

/**
 * Resolves the golem FE tier for a total modifier level.
 *
 * <p>The base energy upgrade is level 1; the three alloy tiers add 2 / 4 / 8, so the meaningful
 * totals are 1 / 3 / 7 / 15. With Mekanism installed the resolver delegates to
 * {@code MekanismEnergyTiers}, which mirrors Mekanism's energy cube tiers. Without Mekanism only
 * the base upgrade exists, so the fallback config values are used.</p>
 *
 * <p>The provider is installed by the Mekanism compat entry point rather than referenced here:
 * this class is loaded without Mekanism too, and must not put any {@code mekanism.*} type in its
 * constant pool.</p>
 */
public final class GolemEnergyTiers {

    private static IntFunction<GolemEnergyTier> mekProvider;

    private GolemEnergyTiers() {
    }

    /** Called only from the Mekanism-gated compat registration. */
    public static void setMekProvider(IntFunction<GolemEnergyTier> provider) {
        mekProvider = provider;
    }

    public static GolemEnergyTier forLevel(int totalLevel) {
        if (totalLevel <= 0) {
            return GolemEnergyTier.NONE;
        }
        if (mekProvider != null) {
            GolemEnergyTier tier = mekProvider.apply(totalLevel);
            if (tier != null && tier.isPresent()) {
                return tier;
            }
        }
        return new GolemEnergyTier(Config.GOLEM_ENERGY_FALLBACK_CAPACITY.get(),
                Config.GOLEM_ENERGY_FALLBACK_TRANSFER.get());
    }
}
