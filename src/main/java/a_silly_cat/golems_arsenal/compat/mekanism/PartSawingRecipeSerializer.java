package a_silly_cat.golems_arsenal.compat.mekanism;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
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

public class PartSawingRecipeSerializer implements RecipeSerializer<PartSawingRecipe> {

    @NotNull
    @Override
    public PartSawingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
        JsonElement input = GsonHelper.isArrayNode(json, JsonConstants.INPUT)
                ? GsonHelper.getAsJsonArray(json, JsonConstants.INPUT)
                : GsonHelper.getAsJsonObject(json, JsonConstants.INPUT);
        ItemStackIngredient inputIngredient = IngredientCreatorAccess.item().deserialize(input);
        ItemStack main = SerializerHelper.getItemStack(json, JsonConstants.MAIN_OUTPUT);
        ItemStack secondary = SerializerHelper.getItemStack(json, JsonConstants.SECONDARY_OUTPUT);
        JsonElement chance = json.get(JsonConstants.SECONDARY_CHANCE);
        if (!GsonHelper.isNumberValue(chance)) {
            throw new JsonSyntaxException("Expected secondaryChance to be a number.");
        }
        double secondaryChance = chance.getAsJsonPrimitive().getAsDouble();
        if (secondaryChance <= 0 || secondaryChance > 1) {
            throw new JsonSyntaxException("Expected secondaryChance to be greater than zero, and less than or equal to one.");
        }
        return new PartSawingRecipe(recipeId, inputIngredient, main, secondary, secondaryChance);
    }

    @Override
    public PartSawingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
        ItemStackIngredient inputIngredient = IngredientCreatorAccess.item().read(buffer);
        ItemStack main = buffer.readItem();
        ItemStack secondary = buffer.readItem();
        double secondaryChance = buffer.readDouble();
        return new PartSawingRecipe(recipeId, inputIngredient, main, secondary, secondaryChance);
    }

    @Override
    public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull PartSawingRecipe recipe) {
        recipe.write(buffer);
    }
}
