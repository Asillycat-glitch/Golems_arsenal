package a_silly_cat.golems_arsenal.compat.mekanism;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mekanism.api.JsonConstants;
import mekanism.api.SerializerHelper;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.jetbrains.annotations.NotNull;

public class PartCrushingRecipeSerializer implements RecipeSerializer<PartCrushingRecipe> {

    @NotNull
    @Override
    public PartCrushingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
        JsonElement input = GsonHelper.isArrayNode(json, JsonConstants.INPUT)
                ? GsonHelper.getAsJsonArray(json, JsonConstants.INPUT)
                : GsonHelper.getAsJsonObject(json, JsonConstants.INPUT);
        ItemStackIngredient inputIngredient = IngredientCreatorAccess.item().deserialize(input);
        ItemStack placeholder = SerializerHelper.getItemStack(json, JsonConstants.OUTPUT);
        return new PartCrushingRecipe(recipeId, inputIngredient, placeholder);
    }

    @Override
    public PartCrushingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
        ItemStackIngredient inputIngredient = IngredientCreatorAccess.item().read(buffer);
        ItemStack placeholder = buffer.readItem();
        return new PartCrushingRecipe(recipeId, inputIngredient, placeholder);
    }

    @Override
    public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull PartCrushingRecipe recipe) {
        recipe.write(buffer);
    }
}
