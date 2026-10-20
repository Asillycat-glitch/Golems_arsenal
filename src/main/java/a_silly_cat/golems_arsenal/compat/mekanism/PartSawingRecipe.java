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

    /**
     * **只给 JEI 显示用的主产物定义**。
     *
     * <p>以前返回「所有材质 × 所有部件」的完整矩阵（几十上百个 ItemStack），
     * MEK 的 JEI 锯木类别会把它全部当产物渲染，界面塞满产物格——这就是
     * 用户反馈的"JEI 显示不合格"。实际产出由 {@link #getOutput(ItemStack)} 决定，
     * 这里只留一个代表产物让 JEI 画一条正常配方。
     */
    @Override
    public List<ItemStack> getMainOutputDefinition() {
        List<ItemStack> all = PartRecyclingUtil.getMaterialOutputs(Config.PART_SAWMILL_YIELD_PERCENT.get(), false);
        return all.isEmpty() ? List.of() : List.of(all.get(0));
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
            return PartRecyclingUtil.getMaterialOutput(input, Config.PART_SAWMILL_YIELD_PERCENT.get(), false);
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
