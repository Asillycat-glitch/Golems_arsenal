package a_silly_cat.golems_arsenal;

import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.init.GolemEffects;
import a_silly_cat.golems_arsenal.init.ModAttributes;
import a_silly_cat.golems_arsenal.compat.CompatDispatch;
import a_silly_cat.golems_arsenal.upgrade.GolemUpgrades;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.resources.ResourceLocation;

@Mod(Golems_arsenal.MODID)
public class Golems_arsenal {
    public static final String MODID = "golems_arsenal";
    public static ResourceLocation id(String path) { return new ResourceLocation(MODID, path); }

    public Golems_arsenal() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        GolemUpgrades.register();
        ModItems.register(modEventBus);
        GolemEffects.register(modEventBus);
        ModAttributes.register(modEventBus);
        modEventBus.addListener(ModAttributes::modifyAttributes);
        CompatDispatch.registerCommon(modEventBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

}
