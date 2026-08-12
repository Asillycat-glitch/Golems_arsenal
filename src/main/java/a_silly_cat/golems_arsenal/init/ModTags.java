package a_silly_cat.golems_arsenal.init;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class ModTags {
    public static final TagKey<Item> LARGE_GOLEM_WEAPONS =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "large_golem_weapons"));

    private ModTags() {
    }
}
