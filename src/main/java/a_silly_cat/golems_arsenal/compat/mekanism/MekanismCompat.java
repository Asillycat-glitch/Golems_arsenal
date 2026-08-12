package a_silly_cat.golems_arsenal.compat.mekanism;

import net.minecraftforge.eventbus.api.IEventBus;

public final class MekanismCompat {
    private MekanismCompat() {
    }

    public static void register(IEventBus modEventBus) {
        ModRecipeSerializers.register(modEventBus);
    }
}
