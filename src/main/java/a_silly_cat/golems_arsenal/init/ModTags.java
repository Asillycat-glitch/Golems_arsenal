package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class ModTags {
    public static final TagKey<Item> LARGE_GOLEM_WEAPONS =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "large_golem_weapons"));

    /** Meme-upgrade items: applying one on an anvil adds the matching enchantment to the item. */
    public static final TagKey<Item> MEME_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "meme_upgrades"));

    private ModTags() {
    }
}
