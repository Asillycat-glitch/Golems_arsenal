package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.Config;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Crusher recipe: returns the part's materials minus a small loss, resolved from the part's NBT material. */
public class PartCrushingRecipe extends ItemStackToItemStackRecipe {

    public PartCrushingRecipe(ResourceLocation id, ItemStackIngredient input, ItemStack placeholderOutput) {
        super(id, input, placeholderOutput);
    }

    @Override
    public ItemStack getOutput(ItemStack input) {
        return PartRecyclingUtil.getMaterialOutput(input, Config.PART_CRUSHER_MATERIAL_LOSS.get());
    }

    @Override
    public List<ItemStack> getOutputDefinition() {
        return PartRecyclingUtil.getMaterialOutputs(Config.PART_CRUSHER_MATERIAL_LOSS.get());
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public RecipeType<ItemStackToItemStackRecipe> getType() {
        return (RecipeType<ItemStackToItemStackRecipe>) (RecipeType<?>) MekanismRecipeType.CRUSHING.get();
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<ItemStackToItemStackRecipe> getSerializer() {
        return (RecipeSerializer<ItemStackToItemStackRecipe>) (RecipeSerializer<?>) ModRecipeSerializers.PART_CRUSHING.get();
    }
}
