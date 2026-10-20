package a_silly_cat.golems_arsenal.compat.mekanism;

import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 部件回收的产出计算。
 *
 * <p><b>2026-10-17 改了语义</b>：以前是「材料数 − 固定损耗」（{@code loss} 是减数），
 * 现在是「材料数 × 回收率」，并且两种机器的取整方向不同（用户指定）：
 * <ul>
 *   <li><b>粉碎：向上取整</b>（半折后剩 2.5 → 3）</li>
 *   <li><b>锯木：向下取整</b>（半折后剩 2.5 → 2，但有黏土球副产物）</li>
 * </ul>
 * 用整数运算，避免浮点误差：{@code num = count × percent}，
 * 向下 = {@code num / 100}，向上 = {@code (num + 99) / 100}。
 */
public final class PartRecyclingUtil {

    private static final TagKey<Item> PARTS_TAG = TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems:parts"));

    private PartRecyclingUtil() {
    }

    /**
     * 按回收率算出实际产出数量。
     *
     * @param partCount 部件原本的材料数量（{@code GolemPart.count}）
     * @param percent   回收率百分比（0..100）
     * @param roundUp   true = 向上取整（粉碎），false = 向下取整（锯木）
     * @return 产出数量；小于等于 0 表示这一条不该产出
     */
    public static int yieldCount(int partCount, int percent, boolean roundUp) {
        if (partCount <= 0 || percent <= 0) {
            return 0;
        }
        int num = partCount * Math.min(percent, 100);
        return roundUp ? (num + 99) / 100 : num / 100;
    }

    /** 从部件上剥离材料。产出数量由回收率决定，且不超过部件原本的材料数。 */
    public static ItemStack getMaterialOutput(ItemStack part, int percent, boolean roundUp) {
        if (!(part.getItem() instanceof GolemPart<?, ?> golemPart)) {
            return ItemStack.EMPTY;
        }
        Optional<ResourceLocation> material = GolemPart.getMaterial(part);
        if (material.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Ingredient ingredient = GolemMaterialConfig.get().getCraftIngredient(material.get());
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) {
            return ItemStack.EMPTY;
        }
        int count = yieldCount(golemPart.count, percent, roundUp);
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = items[0].copy();
        result.setCount(Math.min(count, result.getMaxStackSize()));
        return result;
    }

    /**
     * 这条回收配方**理论上**能产出的所有物品（去重）。
     *
     * <p>注意：这个方法的返回值只用于「是否存在可回收材料」这类判断，
     * <b>不要再直接喂给 JEI</b>——它会枚举所有材质，列表极长。
     * JEI 的逐材质展示见 {@code MekanismJeiPartRecipes}。
     */
    public static List<ItemStack> getMaterialOutputs(int percent, boolean roundUp) {
        List<GolemPart<?, ?>> parts = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(PARTS_TAG)) {
            if (holder.value() instanceof GolemPart<?, ?> part) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            parts.addAll(GolemPart.LIST);
        }
        List<ItemStack> ans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResourceLocation material : GolemMaterialConfig.get().getAllMaterials()) {
            Ingredient ingredient = GolemMaterialConfig.get().getCraftIngredient(material);
            ItemStack[] items = ingredient.getItems();
            if (items.length == 0 || items[0].isEmpty() || items[0].is(Items.BARRIER)) {
                continue;
            }
            ItemStack base = items[0];
            for (GolemPart<?, ?> part : parts) {
                int count = yieldCount(part.count, percent, roundUp);
                if (count <= 0) {
                    continue;
                }
                String key = base.getItem().getDescriptionId() + "#" + count;
                if (!seen.add(key)) {
                    continue;
                }
                ItemStack out = base.copy();
                out.setCount(Math.min(count, out.getMaxStackSize()));
                ans.add(out);
            }
        }
        return ans;
    }
}
