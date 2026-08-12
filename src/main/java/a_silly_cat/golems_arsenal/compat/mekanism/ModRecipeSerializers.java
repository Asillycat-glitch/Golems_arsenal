package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, Golems_arsenal.MODID);

    public static final RegistryObject<RecipeSerializer<PartCrushingRecipe>> PART_CRUSHING =
            SERIALIZERS.register("part_crushing", PartCrushingRecipeSerializer::new);
    public static final RegistryObject<RecipeSerializer<PartSawingRecipe>> PART_SAWING =
            SERIALIZERS.register("part_sawing", PartSawingRecipeSerializer::new);

    private ModRecipeSerializers() {
    }

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
