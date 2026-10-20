package a_silly_cat.golems_arsenal.compat.mekanism;

import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * **只给 JEI 看的**粉碎配方：一条固定输入 → 一条固定产出。
 *
 * <p>为什么需要这个类：{@link PartCrushingRecipe} 的产出是按输入部件的 NBT 材质
 * <b>动态</b>算的，所以它的 {@code getOutputDefinition()} 只能给个代表产物，
 * JEI 里就只显示一条。用户要求「一种材质一条配方，全部展示」——
 * 那就必须在 JEI 侧枚举出「材质 × 部件」的每一条，每条产出一个确定的物品。
 *
 * <p>参考的是傀儡装配 {@code GolemJEIPlugin.addPartCraftRecipes} 的做法：
 * <pre>
 *   for (ResourceLocation mat : config.getAllMaterials())
 *       for (GolemPart&lt;?, ?&gt; item : GolemPart.LIST)
 *           recipes.add(factory.createAnvilRecipe(
 *               GolemPart.setMaterial(new ItemStack(item), mat), ...));
 * </pre>
 * 即<b>材质 × 部件双重枚举，输入带上材质的 ItemStack，输出固定</b>。
 *
 * <p>这些实例<b>只活在 JEI 里</b>（通过 {@code IRecipeRegistration.addRecipes} 注册到
 * MEK 的粉碎/锯木类别），不会写进配方管理器，也不参与实际合成——实际产出永远由
 * {@link PartCrushingRecipe#getOutput(ItemStack)} 决定。
 */
public class FixedPartCrushingRecipe extends ItemStackToItemStackRecipe {

    private final ItemStack output;

    public FixedPartCrushingRecipe(ResourceLocation id, ItemStack input, ItemStack output) {
        // 输入用单个物品（不是 tag）：JEI 上每条配方的输入图标就该是那个部件本身
        super(id, IngredientCreatorAccess.item().from(input), output);
        this.output = output.copy();
    }

    @Override
    public ItemStack getOutput(ItemStack input) {
        return this.output.copy();
    }

    @Override
    public List<ItemStack> getOutputDefinition() {
        return List.of(this.output.copy());
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
