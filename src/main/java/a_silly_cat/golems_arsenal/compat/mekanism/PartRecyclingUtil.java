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

public final class PartRecyclingUtil {

    private static final TagKey<Item> PARTS_TAG = TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems:parts"));

    private PartRecyclingUtil() {
    }

    /**
     * Strips the crafting material from a golem part. The returned amount never exceeds the part's
     * crafting cost (GolemPart.count); {@code loss} is subtracted for processing.
     */
    public static ItemStack getMaterialOutput(ItemStack part, int loss) {
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
        int count = Math.max(0, golemPart.count - loss);
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = items[0].copy();
        result.setCount(Math.min(count, result.getMaxStackSize()));
        return result;
    }

    /**
     * All outputs this recycling recipe can produce, for JEI display: every material resolved for every
     * part in {@code #modulargolems:parts}, deduplicated by item and count.
     */
    public static List<ItemStack> getMaterialOutputs(int loss) {
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
                int count = part.count - loss;
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
