package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import mekanism.client.jei.MekanismJEI;
import mekanism.client.jei.MekanismJEIRecipeType;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.SawmillRecipe;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「部件回收」配方**按材质 × 部件逐条枚举**后塞进 MEK 的 JEI 粉碎类别。
 *
 * <p><b>为什么不能只靠配方管理器里的那一条</b>：傀儡部件是同一个物品 + NBT 材质，
 * 一条动态配方在 JEI 里只能显示一个代表产物（那条已经改过，见
 * {@code PartCrushingRecipe#getOutputDefinition}）。用户要的是「每种材质都看得见」。
 *
 * <p><b>照抄的参考</b>：傀儡装配 {@code GolemJEIPlugin.addPartCraftRecipes}：
 * <pre>
 *   for (ResourceLocation mat : config.getAllMaterials())
 *       for (GolemPart&lt;?, ?&gt; item : GolemPart.LIST)
 *           recipes.add(factory.createAnvilRecipe(
 *               GolemPart.setMaterial(new ItemStack(item), mat), ...));
 * </pre>
 * 也就是<b>材质 × 部件双重枚举，输入用带材质的 ItemStack</b>。
 *
 * <p><b>这些配方只在 JEI 里</b>：通过 {@code IRecipeRegistration.addRecipes} 注册到
 * MEK 的粉碎类别，不进配方管理器、不参与实际合成。真正的产出仍由
 * {@link PartCrushingRecipe#getOutput(ItemStack)} 按 NBT 算。
 *
 * <p>本类引用 Mekanism 与 ModularGolems，只由 {@code GolemsArsenalJeiPlugin} 在确认
 * 两个模组都在之后调用，类加载因此被推迟。
 */
public final class MekanismJeiPartRecipes {

    private MekanismJeiPartRecipes() {
    }

    /** 注册粉碎的逐材质展示配方。 */
    @SuppressWarnings("unchecked")
    public static void registerCrushing(IRecipeRegistration registration) {
        int percent = Config.PART_CRUSHER_YIELD_PERCENT.get();
        List<ItemStackToItemStackRecipe> recipes = new ArrayList<>();

        for (ResourceLocation mat : GolemMaterialConfig.get().getAllMaterials()) {
            ItemStack[] ingredients = GolemMaterialConfig.get().getCraftIngredient(mat).getItems();
            if (ingredients.length == 0 || ingredients[0].isEmpty() || ingredients[0].is(Items.BARRIER)) {
                continue;   // 这种材质没有可回收的实物材料
            }
            ItemStack base = ingredients[0];
            for (GolemPart<?, ?> part : GolemPart.LIST) {
                int count = PartRecyclingUtil.yieldCount(part.count, percent, true);   // 粉碎：向上取整
                if (count <= 0) {
                    continue;
                }
                ItemStack out = base.copy();
                out.setCount(Math.min(count, out.getMaxStackSize()));
                // 输入带材质 NBT——JEI 上就能区分"铁傀儡部件"和"青铜傀儡部件"
                ItemStack in = GolemPart.setMaterial(new ItemStack(part), mat);
                recipes.add(new FixedPartCrushingRecipe(recipeId("crush", mat, part), in, out));
            }
        }

        Golems_arsenal.LOGGER.info(
                "JEI: registered {} per-material part-crushing recipes for the Mekanism crusher",
                recipes.size());
        // JEI 15 的 addRecipes 要的是 **JEI 自己的** RecipeType，
        // 不能直接给 MC 的 RecipeType。MEK 提供了转换：
        //   MekanismJEI.recipeType(MekanismJEIRecipeType.CRUSHING)
        registration.addRecipes(MekanismJEI.recipeType(MekanismJEIRecipeType.CRUSHING), recipes);
    }

    /** 注册锯木的逐材质展示配方（主产物 = 材料，副产物 = 黏土球）。 */
    public static void registerSawing(IRecipeRegistration registration) {
        int percent = Config.PART_SAWMILL_YIELD_PERCENT.get();
        int clay = Config.PART_SAWMILL_CLAY.get();
        List<SawmillRecipe> recipes = new ArrayList<>();

        for (ResourceLocation mat : GolemMaterialConfig.get().getAllMaterials()) {
            ItemStack[] ingredients = GolemMaterialConfig.get().getCraftIngredient(mat).getItems();
            if (ingredients.length == 0 || ingredients[0].isEmpty() || ingredients[0].is(Items.BARRIER)) {
                continue;
            }
            ItemStack base = ingredients[0];
            for (GolemPart<?, ?> part : GolemPart.LIST) {
                int count = PartRecyclingUtil.yieldCount(part.count, percent, false);  // 锯木：向下取整
                if (count <= 0) {
                    continue;
                }
                ItemStack main = base.copy();
                main.setCount(Math.min(count, main.getMaxStackSize()));
                ItemStack secondary = clay > 0
                        ? new ItemStack(Items.CLAY_BALL, clay) : ItemStack.EMPTY;
                ItemStack in = GolemPart.setMaterial(new ItemStack(part), mat);
                recipes.add(new FixedPartSawingRecipe(
                        recipeId("saw", mat, part), in, main, secondary, 1.0D));
            }
        }

        Golems_arsenal.LOGGER.info(
                "JEI: registered {} per-material part-sawing recipes for the Mekanism sawmill",
                recipes.size());
        registration.addRecipes(MekanismJEI.recipeType(MekanismJEIRecipeType.SAWING), recipes);
    }

    /** 生成稳定的配方 id；JEI 只用来去重，不写盘。 */
    private static ResourceLocation recipeId(String prefix, ResourceLocation material, GolemPart<?, ?> part) {
        ResourceLocation partId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(part);
        String p = partId == null ? "part" : partId.getPath();
        return new ResourceLocation(Golems_arsenal.MODID,
                "jei/" + prefix + "/" + material.getNamespace() + "_" + material.getPath() + "/" + p);
    }
}
