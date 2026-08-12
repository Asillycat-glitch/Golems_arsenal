package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.enchantment.FullOnslaughtEnchantment;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, Golems_arsenal.MODID);

    public static final RegistryObject<FullOnslaughtEnchantment> FULL_ONSLAUGHT =
            ENCHANTMENTS.register("golem_full_onslaught", FullOnslaughtEnchantment::new);

    public static void register(IEventBus modEventBus) {
        ENCHANTMENTS.register(modEventBus);
    }

    private ModEnchantments() {
    }
}
