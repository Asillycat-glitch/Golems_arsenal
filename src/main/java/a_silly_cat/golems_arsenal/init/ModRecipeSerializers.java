package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.recipe.GolemRepeatableSmithAddSlotRecipe;
import dev.xkmc.l2library.serial.recipe.AbstractSmithingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, Golems_arsenal.MODID);

    public static final RegistryObject<AbstractSmithingRecipe.Serializer<GolemRepeatableSmithAddSlotRecipe>>
            REPEATABLE_ADD_SLOT = SERIALIZERS.register(
            "golem_repeatable_add_slot",
            () -> new AbstractSmithingRecipe.Serializer<>(GolemRepeatableSmithAddSlotRecipe::new));

    private ModRecipeSerializers() {
    }

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
