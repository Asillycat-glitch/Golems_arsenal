package a_silly_cat.golems_arsenal.compat.mekanism;

import mekanism.api.recipes.SawmillRecipe;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * **只给 JEI 看的**锯木配方：一条固定输入 → 固定主产物 + 固定副产物。
 *
 * <p>和 {@link FixedPartCrushingRecipe} 同一个用途（逐材质展示），但 {@code SawmillRecipe}
 * 的基类本身就以「固定的主产物 / 副产物 / 概率」存字段，
 * 而且 {@code getMainOutputDefinition()} / {@code getSecondaryOutputDefinition()}
 * 已经是返回那两个字段的固定实现——所以这里不用重写它们的逻辑，
 * 只在副产物为空时返回空列表（避免 JEI 画一个空槽）。
 */
public class FixedPartSawingRecipe extends SawmillRecipe {

    private final ItemStack mainOutput;
    private final ItemStack secondaryOutput;

    public FixedPartSawingRecipe(ResourceLocation id, ItemStack input,
                                 ItemStack mainOutput, ItemStack secondaryOutput, double secondaryChance) {
        super(id, IngredientCreatorAccess.item().from(input),
                mainOutput, secondaryOutput, secondaryChance);
        this.mainOutput = mainOutput.copy();
        this.secondaryOutput = secondaryOutput;
    }

    @Override
    public List<ItemStack> getMainOutputDefinition() {
        return List.of(this.mainOutput.copy());
    }

    @Override
    public List<ItemStack> getSecondaryOutputDefinition() {
        return this.secondaryOutput.isEmpty() ? List.of() : List.of(this.secondaryOutput.copy());
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
}
