package a_silly_cat.golems_arsenal.base.recipe;

import a_silly_cat.golems_arsenal.init.ModRecipeSerializers;
import dev.xkmc.l2library.serial.recipe.AbstractSmithingRecipe;
import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemHolder;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Repeatable version of Modular Golems' {@code golem_add_slot} smithing recipe.
 * <p>
 * The vanilla recipe refuses to apply the same template twice
 * ({@code upgrades.contains(template)}). This one drops that check and instead only stops at the
 * linked modifier's {@code maxLevel}, so the same expansion template can be forged onto a golem
 * holder many times and each application stacks +1 modifier level.
 * <p>
 * Usage in the smithing table: template slot = this mod's expansion template item,
 * base slot = golem holder, addition slot = an item matching the golem's crafting material.
 */
public class GolemRepeatableSmithAddSlotRecipe extends AbstractSmithingRecipe<GolemRepeatableSmithAddSlotRecipe> {

    public final Ingredient template;
    public final Ingredient base;
    public final Ingredient addition;

    public GolemRepeatableSmithAddSlotRecipe(ResourceLocation id, Ingredient template, Ingredient base,
                                             Ingredient addition, ItemStack result) {
        super(id, template, base, addition, result);
        this.template = template;
        this.base = base;
        this.addition = addition;
    }

    @Override
    public boolean matches(Container container, Level level) {
        ItemStack baseStack = container.getItem(1);
        if (!template.test(container.getItem(0))) return false;
        if (!base.test(baseStack)) return false;
        if (!GolemHolder.getCraftMaterial(baseStack).test(container.getItem(2))) return false;
        if (!(container.getItem(0).getItem() instanceof IUpgradeItem up)) return false;

        ArrayList<IUpgradeItem> installed = GolemHolder.getUpgrades(baseStack);
        ArrayList<dev.xkmc.modulargolems.content.config.GolemMaterial> materials = GolemHolder.getMaterial(baseStack);

        // Allow repeats, but stop once the linked modifier has reached maxLevel.
        HashMap<GolemModifier, Integer> current =
                GolemMaterial.collectModifiers(materials, installed);
        for (ModifierInstance ins : up.get()) {
            if (current.getOrDefault(ins.mod(), 0) >= ins.mod().maxLevel) return false;
        }

        // Simulate the application to make sure remaining slots never go negative.
        ArrayList<IUpgradeItem> after = new ArrayList<>(installed);
        after.add(up);
        return baseStack.getItem() instanceof GolemHolder<?, ?> holder
                && holder.getRemaining(materials, after) >= 0;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        ItemStack result = container.getItem(1).copy();
        GolemHolder.addUpgrade(result, (IUpgradeItem) container.getItem(0).getItem());
        return result;
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return GolemMaterialConfig.get().ingredients.values().stream().anyMatch(i -> i.test(stack));
    }

    @Override
    public AbstractSmithingRecipe.Serializer<GolemRepeatableSmithAddSlotRecipe> getSerializer() {
        return ModRecipeSerializers.REPEATABLE_ADD_SLOT.get();
    }
}
