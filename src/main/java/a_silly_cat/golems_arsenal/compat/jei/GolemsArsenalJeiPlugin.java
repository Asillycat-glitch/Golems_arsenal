package a_silly_cat.golems_arsenal.compat.jei;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI information pages for this mod's crossover effects. JEI 15 exposes
 * {@code IRecipeRegistration#addItemStackInfo}, so no custom recipe category is needed: the pages
 * are attached to the relevant items (the full-onslaught upgrade, and the Gilded artifact pieces
 * when L2Artifacts is installed). This class is only loaded by JEI itself, so JEI stays optional.
 */
@JeiPlugin
public class GolemsArsenalJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            new ResourceLocation(Golems_arsenal.MODID, "jei");
    private static final String[] GILDED_SLOTS =
            {"head", "necklace", "body", "bracelet", "belt"};

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(ModItems.FULL_ONSLAUGHT_UPGRADE.get()));
        if (ModList.get().isLoaded("l2artifacts")) {
            for (String slot : GILDED_SLOTS) {
                for (int rank = 1; rank <= 5; rank++) {
                    Item item = ForgeRegistries.ITEMS.getValue(
                            new ResourceLocation("l2artifacts", "gilded_" + slot + "_" + rank));
                    if (item != null) {
                        stacks.add(new ItemStack(item));
                    }
                }
            }
        }
        registration.addItemStackInfo(stacks,
                Component.translatable("jei.golems_arsenal.gilded_onslaught.title"),
                Component.translatable("jei.golems_arsenal.synergy.weakened"),
                Component.translatable("jei.golems_arsenal.gilded_onslaught.header"),
                Component.translatable("jei.golems_arsenal.gilded_onslaught.desc"));
    }
}
