package a_silly_cat.golems_arsenal.compat;

import a_silly_cat.golems_arsenal.compat.mekanism.MekanismCompat;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

public final class CompatDispatch {
    private CompatDispatch() {
    }

    /** Registers optional integrations, mirroring Modular Golems' soft-dependency pattern. */
    public static void registerCommon(IEventBus modEventBus) {
        if (ModList.get().isLoaded("mekanism")) {
            MekanismCompat.register(modEventBus);
        }
    }
}
