package a_silly_cat.golems_arsenal;

import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.init.GolemEffects;
import a_silly_cat.golems_arsenal.init.ModAttributes;
import a_silly_cat.golems_arsenal.init.ModEnchantments;
import a_silly_cat.golems_arsenal.init.ModRecipeSerializers;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.compat.CompatDispatch;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Golems_arsenal.MODID)
public class Golems_arsenal {
    public static final String MODID = "golems_arsenal";
    public static final Logger LOGGER = LoggerFactory.getLogger(Golems_arsenal.class);

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    public Golems_arsenal() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        GolemUpgrades.register();
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        GolemEffects.register(modEventBus);
        ModAttributes.register(modEventBus);
        ModEnchantments.register(modEventBus);
        ModRecipeSerializers.register(modEventBus);
        ModNetwork.register();
        modEventBus.addListener(ModAttributes::modifyAttributes);
        CompatDispatch.registerCommon(modEventBus);
        // Z 光剑的近战穿甲：向 l2damagetracker 注册一个监听器（和傀儡地牢遗迹锻锤同一条通道）。
        // l2damagetracker 由本家/莱特兰以 jarjar 形式提供，理论上一定在；仍然兜一层，避免它不是
        // 独立依赖时把整个 mod 加载带崩。
        try {
            a_silly_cat.golems_arsenal.compat.l2damagetracker.ZeroSwordAttackListener.register();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("Z sword armor-pierce listener not registered: {}", t.toString());
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

}
