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

    /**
     * 真正执行时用这个方法：按输入部件的材质动态算产出。
     *
     * <p>粉碎机走的是这条路径（{@code getOutput(input)}），所以回收逻辑是准的。
     */
    @Override
    public ItemStack getOutput(ItemStack input) {
        return PartRecyclingUtil.getMaterialOutput(input, Config.PART_CRUSHER_YIELD_PERCENT.get(), true);
    }

    /**
     * **只给 JEI 显示用的产物定义**。
     *
     * <p><b>为什么这里不能返回完整矩阵</b>：以前返回的是
     * {@code PartRecyclingUtil.getMaterialOutputs(loss)}，也就是「所有材质 × 所有部件」
     * 去重后的列表——几十到上百个 {@code ItemStack}。MEK 的 JEI 粉碎类别把这个列表
     * 直接当产物渲染，结果界面上塞满产物格，完全没法看（用户反馈的"显示不合格"）。
     *
     * <p>傀儡装配的处理方式相反：{@code addPartCraftRecipes} 是**一种材质一条 JEI 配方**，
     * 每条只挂一个产物。所以这里只取**第一个**作为代表产物，让 JEI 画出一条正常的
     * 粉碎配方；实际产出仍由 {@link #getOutput(ItemStack)} 按材质决定。
     */
    @Override
    public List<ItemStack> getOutputDefinition() {
        List<ItemStack> all = PartRecyclingUtil.getMaterialOutputs(Config.PART_CRUSHER_YIELD_PERCENT.get(), true);
        return all.isEmpty() ? List.of() : List.of(all.get(0));
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
