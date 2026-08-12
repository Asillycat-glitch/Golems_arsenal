package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.Config;
import mekanism.api.recipes.SawmillRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Sawmill recipe: returns fewer materials than crushing, plus a clay byproduct. */
public class PartSawingRecipe extends SawmillRecipe {

    public PartSawingRecipe(ResourceLocation id, ItemStackIngredient input, ItemStack placeholderMain,
                            ItemStack placeholderSecondary, double secondaryChance) {
        super(id, input, placeholderMain, placeholderSecondary, secondaryChance);
    }

    @Override
    public ChanceOutput getOutput(ItemStack input) {
        return new PartChanceOutput(input, getSecondaryChance());
    }

    @Override
    public List<ItemStack> getMainOutputDefinition() {
        return PartRecyclingUtil.getMaterialOutputs(Config.PART_SAWMILL_MATERIAL_LOSS.get());
    }

    @Override
    public List<ItemStack> getSecondaryOutputDefinition() {
        return List.of(new ItemStack(Items.CLAY_BALL, Config.PART_SAWMILL_CLAY.get()));
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public RecipeType<SawmillRecipe> getType() {
        return (RecipeType<SawmillRecipe>) (RecipeType<?>) MekanismRecipeType.SAWING.get();
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<SawmillRecipe> getSerializer() {
        return (RecipeSerializer<SawmillRecipe>) (RecipeSerializer<?>) ModRecipeSerializers.PART_SAWING.get();
    }

    public class PartChanceOutput extends ChanceOutput {
        private final ItemStack input;

        private PartChanceOutput(ItemStack input, double chance) {
            super(chance);
            this.input = input;
        }

        @Override
        public ItemStack getMainOutput() {
            return PartRecyclingUtil.getMaterialOutput(input, Config.PART_SAWMILL_MATERIAL_LOSS.get());
        }

        @Override
        public ItemStack getMaxSecondaryOutput() {
            return new ItemStack(Items.CLAY_BALL, Config.PART_SAWMILL_CLAY.get());
        }

        @Override
        public ItemStack getSecondaryOutput() {
            return rand <= getSecondaryChance() ? getMaxSecondaryOutput() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack nextSecondaryOutput() {
            return RANDOM.nextDouble() <= getSecondaryChance() ? getMaxSecondaryOutput() : ItemStack.EMPTY;
        }
    }
}
